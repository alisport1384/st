package com.alisport.goldpin.svc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import com.alisport.goldpin.AppState
import com.alisport.goldpin.MainActivity
import com.alisport.goldpin.core.OrderStatus
import com.alisport.goldpin.util.Alerts
import com.alisport.goldpin.util.Fa
import com.alisport.goldpin.util.Log

// ═══════════════════════════════════════════════════════════════════════════════
//  سرویس همیشه‌فعال — ارزیابی زندهٔ شرایط و ثبت سفارش‌ها در پس‌زمینه
//  هر چند ثانیه: دنبالهٔ کندل‌ها به‌روزرسانی می‌شود ، موتور کندل‌های بستهٔ جدید را
//  پردازش می‌کند و سفارش/معاملهٔ کاغذی به‌روز می‌شود. نوتیفیکیشن وضعیت را نشان می‌دهد.
// ═══════════════════════════════════════════════════════════════════════════════
class EngineService : Service() {

    private val state get() = AppState.instance
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastAutosave = 0L

    private val tick = object : Runnable {
        override fun run() {
            try {
                if (state.liveRunning) {
                    state.updateLiveOnce()
                    maybeAutosave()
                    updateNotification()
                }
            } catch (e: Exception) {
                // در پس‌زمینه هرگز کرش نکن — فقط لاگ کن
                Log.e(Log.CAT_LIVE, "خطا در حلقهٔ ارزیابی زنده", e)
            }
            handler.postDelayed(this, state.livePollMs)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(Log.CAT_LIVE, "سرویس ارزیابی زنده ساخته شد", "فاصله=${state.livePollMs}ms")
        createChannel()
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "goldpin:live")
            wakeLock?.setReferenceCounted(false)
            wakeLock?.acquire(12L * 60L * 60L * 1000L)
        } catch (e: Exception) {
            wakeLock = null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        state.liveRunning = true
        Alerts.liveRunning = true
        Alerts.inBacktest = false
        Log.i(Log.CAT_LIVE, "سرویس ارزیابی زنده شروع شد", "فاصله=${state.livePollMs}ms نماد=${state.symbol}")
        startForeground(NOTIF_ID, buildNotification())
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(Log.CAT_LIVE, "سرویس ارزیابی زنده متوقف شد")
        handler.removeCallbacks(tick)
        try { wakeLock?.release() } catch (e: Exception) { }
        state.liveRunning = false
        Alerts.liveRunning = false
        state.saveToAutoFile(this)
        state.notifyUi()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun maybeAutosave() {
        val now = System.currentTimeMillis()
        if (now - lastAutosave > state.autosaveEveryMs) {
            lastAutosave = now
            Log.d(Log.CAT_LIVE, "ذخیرهٔ خودکار دوره‌ای در پس‌زمینه")
            state.io.execute { state.saveToAutoFile(applicationContext) }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(CHANNEL, "ارزیابی زنده", NotificationManager.IMPORTANCE_LOW)
            ch.description = "پایش دائمی شرایط استراتژی و ثبت سفارش کاغذی"
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val s = state
        val price = if (s.lastLivePrice.isNaN()) "—" else Fa.n(s.lastLivePrice, 2)
        val trend = when (s.engine.trend) {
            1 -> "صعودی"; -1 -> "نزولی"; else -> "بدون روند"
        }
        val open = s.broker.openTrade
        val pending = s.broker.pendingOrder
        val line2 = buildString {
            append("قیمت: ").append(price).append("  |  روند: ").append(trend)
            if (pending != null && pending.status == OrderStatus.PENDING) {
                append("  |  سفارش ورود @ ").append(Fa.n(pending.price, 2))
            }
            if (open != null && open.open) {
                append("  |  پوزیشن باز: ").append(Fa.signed(open.pnl(), 2))
            }
        }
        val err = s.lastFeedError
        val line3 = if (err.isNullOrEmpty()) "آخرین به‌روزرسانی: ${Fa.jalali(s.lastFeedAt)}"
        else "خطای فید: $err"

        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL)
        else
            @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle("GoldPin · ارزیابی زنده")
            .setContentText(line2)
            .setStyle(Notification.BigTextStyle().bigText("$line2\n$line3"))
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification() {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID, buildNotification())
        } catch (e: Exception) { }
    }

    companion object {
        const val CHANNEL = "goldpin_live"
        const val NOTIF_ID = 4711

        fun start(ctx: Context) {
            val i = Intent(ctx, EngineService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            AppState.instance.liveRunning = false
            ctx.stopService(Intent(ctx, EngineService::class.java))
        }
    }
}
