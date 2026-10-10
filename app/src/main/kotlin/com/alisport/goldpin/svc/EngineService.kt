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

    /**
     * ⚠ این Handler پیش‌تر روی `Looper.getMainLooper()` بود — یعنی **کل حلقهٔ لایو
     * روی نخ رابط کاربری اجرا می‌شد**. هر تیک شامل این کارهاست:
     *  • `buildNotification()` → خواندن وضعیت موتور/کارگزار + `Fa.jalali(...)`
     *  • `PendingIntent.getActivity(...)` → یک Binder IPC
     *  • `nm.notify(...)` → یک Binder IPC دیگر
     * هیچ‌کدام لازم نیست روی نخ رابط باشد، و همه فقط در حالت لایو اتفاق می‌افتند —
     * یعنی دقیقاً همان حالتی که کاربر گزارش فریز داده. حالا روی یک HandlerThread
     * اختصاصی اجرا می‌شوند و نخ رابط آزاد می‌ماند.
     */
    private var tickThread: android.os.HandlerThread? = null
    private var handler: Handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastAutosave = 0L

    private fun ensureTickThread(): Handler {
        tickThread?.let { return handler }
        val t = android.os.HandlerThread("goldpin-live-tick").apply { start() }
        tickThread = t
        handler = Handler(t.looper)
        Log.i(Log.CAT_LIVE, "حلقهٔ تیک لایو به نخ پس‌زمینه منتقل شد", "thread=${t.name}")
        return handler
    }

    /**
     * حلقهٔ تیک لایو — **fixed-delay، نه fixed-rate**.
     *
     * پیش‌تر `postDelayed(this, livePollMs)` بی‌قیدوشراجرا می‌شد: حتی وقتی تیک قبلی
     * هنوز تمام نشده بود، یک تیک دیگر در صف executor می‌نشست. با فید کند (یا قطعی
     * شبکه) صف بی‌نهایت رشد می‌کرد و چون ذخیره/بازسازی/خروجی لاگ هم به همان executor
     * تک‌نخی می‌روند، کل اپ «فریز» می‌شد.
     *
     * حالا:
     *  • `updateLiveOnce` خودش با `CoalescingGate` تیک تکراری را حذف می‌کند؛
     *  • اگر چند تیک پشت‌سرهم خطا بدهد، فاصله به‌صورت نمایی عقب می‌رود (تا ۵ دقیقه)
     *    تا روی شبکهٔ قطعی hammering نکنیم و باتری/ترافیک هدر نرود.
     */
    private val tick = object : Runnable {
        override fun run() {
            try {
                if (state.liveRunning) {
                    state.updateLiveOnce()
                    maybeAutosave()
                    updateNotification()
                } else {
                    return   // لایو خاموش شده — دیگر تیک بعدی را زمان‌بندی نکن
                }
            } catch (e: Exception) {
                // در پس‌زمینه هرگز کرش نکن — فقط لاگ کن
                Log.e(Log.CAT_LIVE, "خطا در حلقهٔ ارزیابی زنده", e)
            }
            handler.postDelayed(this, nextDelayMs())
        }
    }

    /** فاصلهٔ تیک بعدی: `livePollMs` در حالت سالم، و عقب‌گرد نمایی تا ۵ دقیقه وقتی فید خطا می‌دهد. */
    private fun nextDelayMs(): Long {
        val base = state.livePollMs.coerceAtLeast(1000L)
        val fails = state.liveFailStreak
        if (fails <= 0) return base
        val factor = 1L shl (fails - 1).coerceAtMost(5)     // ۱،۲،۴،۸،۱۶،۳۲
        return (base * factor).coerceAtMost(MAX_BACKOFF_MS)
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
        state.liveFailStreak = 0
        Alerts.liveRunning = true
        Alerts.inBacktest = false
        Log.i(Log.CAT_LIVE, "سرویس ارزیابی زنده شروع شد", "فاصله=${state.livePollMs}ms نماد=${state.symbol}")
        startForeground(NOTIF_ID, buildNotification())
        val h = ensureTickThread()
        h.removeCallbacks(tick)
        h.post(tick)
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(Log.CAT_LIVE, "سرویس ارزیابی زنده متوقف شد")
        handler.removeCallbacks(tick)
        try { tickThread?.quitSafely() } catch (e: Exception) { }
        tickThread = null
        try { wakeLock?.release() } catch (e: Exception) { }
        state.liveRunning = false
        Alerts.liveRunning = false
        // ذخیره روی نخ پس‌زمینه: نوشتن gzip یک وضعیت چند مگابایتی روی نخ اصلی = ANR
        val app = applicationContext
        state.io.execute { state.saveToAutoFile(app) }
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
        val nOpen = s.broker.openTrades.size
        val nPend = s.broker.pendingOrders.size
        val line2 = buildString {
            append("قیمت: ").append(price).append("  |  روند: ").append(trend)
            if (nPend > 0) append("  |  سفارش معلق: ").append(nPend).append(" تا @").append(Fa.n(pending?.price ?: Double.NaN, 2))
            if (nOpen > 0) {
                val netPnl = s.broker.openTrades.sumOf { it.pnl() }
                append("  |  پوزیشن باز: ").append(nOpen).append(" تا ").append(Fa.signed(netPnl, 2))
            }
        }
        val err = s.lastFeedError
        val line3 = if (err.isNullOrEmpty()) "آخرین به‌روزرسانی: ${Fa.jalali(s.lastFeedAt)}"
        else "خطای فید: $err"
        val line4 = if (s.liveFailStreak > 0 || s.lastLiveSkips > 0)
            "فید کند: ${s.liveFailStreak} خطای پشت‌سرهم · ${s.lastLiveSkips} تیک حذف‌شده — فاصلهٔ تیک موقتاً بیشتر می‌شود"
        else null

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
            .setStyle(Notification.BigTextStyle().bigText(if (line4 == null) "$line2\n$line3" else "$line2\n$line3\n$line4"))
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
        /** سقف عقب‌گرد نمایی فاصلهٔ تیک وقتی فید خطا می‌دهد */
        const val MAX_BACKOFF_MS = 5L * 60L * 1000L

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
