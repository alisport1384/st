package com.alisport.goldpin

import android.content.Context
import com.alisport.goldpin.util.Log
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

// ═══════════════════════════════════════════════════════════════════════════════
//  نگهبان‌های مسیر لایو — دو باگی که با هم «فریز + نشدنِ گرفتن لاگ» را می‌ساختند
//
//  ۱) `AppState.updateLiveOnce` باید وقتی تیک قبلی هنوز در جریان است، تیک جدید را
//     **حذف** کند. پیش‌تر هر تیک بی‌قیدوشرط در صف executor تک‌نخی می‌نشست و چون
//     ذخیره/بازسازی/خروجی لاگ هم به همان صف می‌روند، اپ از کار می‌افتاد.
//
//  ۲) `Log.reportTxt()/reportMd()/tail()` بافر را **بدون قفل** می‌پیمودند در حالی که
//     نخ لایو هم‌زمان به آن اضافه می‌کرد ⇒ `ConcurrentModificationException`
//     ⇒ «حتی نمی‌شود لاگ گرفت».
// ═══════════════════════════════════════════════════════════════════════════════
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class LiveGuardTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    // ── ۱) تیک تکراری حذف می‌شود، صف نمی‌شود ──────────────────────────────────
    @Test
    fun `updateLiveOnce drops a tick while the previous tick is still in flight`() {
        val s = AppState.instance
        assertFalse("دروازه در شروع باید آزاد باشد", s.liveGate.busy)
        assertTrue("دروازه باید گرفته شود", s.liveGate.tryAcquire())
        val acquiredBefore = s.liveGate.acquired
        val skippedBefore = s.liveGate.skipped
        var msg: String? = null
        try {
            // دروازه بسته است ⇒ باید فوراً برگردد و هیچ کاری در صف نگذارد
            s.updateLiveOnce(notifyDone = true) { msg = it }
            assertEquals("تیک تکراری نباید دروازه بگیرد (= نباید در صف برود)",
                acquiredBefore, s.liveGate.acquired)
            assertEquals("تیک تکراری باید شمرده شود", skippedBefore + 1, s.liveGate.skipped)
            assertNotNull("کاربر باید بداند چرا به‌روزرسانی نشد", msg)
            assertTrue("پیام باید دلیل را بگوید: $msg", msg!!.contains("جریان"))
            assertEquals("lastLiveSkips باید برای نوتیفیکیشن به‌روز شود",
                s.liveGate.skipped, s.lastLiveSkips)
        } finally {
            s.liveGate.release()
        }
        assertFalse("دروازه باید آزاد بماند وگرنه لایو برای همیشه می‌خوابد", s.liveGate.busy)
    }

    // ── ۲) خروجی لاگ حین لاگ‌شدنِ لایو نمی‌شکند ────────────────────────────────
    @Test
    fun `exporting the log while live logging is running does not throw`() {
        // لاگر روشن، ولی فقط بافر حافظه (نه فایل و نه Logcat) تا آزمون قطعی بماند
        ctx.getSharedPreferences("goldpin", Context.MODE_PRIVATE).edit()
            .putBoolean("log_enabled", true)
            .putBoolean("log_to_file", false)
            .putBoolean("log_logcat", false)
            .apply()
        Log.init(ctx, "1.3.1")
        assertTrue("لاگر باید برای این آزمون روشن شود", Log.enabled)
        Log.clearBuffer()

        val stop = AtomicBoolean(false)
        val errors = CopyOnWriteArrayList<Throwable>()
        // نخ لایو: مثل تیک‌های واقعی پشت‌سرهم لاگ می‌زند
        val live = Thread {
            var n = 0
            while (!stop.get()) {
                Log.i(Log.CAT_LIVE, "به‌روزرسانی زنده", "تیک=${n++}")
                Log.d(Log.CAT_ENGINE, "ستاپ بررسی شد", "bi=$n")
            }
        }
        live.isDaemon = true
        live.start()
        try {
            // نخ رابط: کاربر «ذخیره .txt» / «کپی متن لاگ» را می‌زند
            repeat(300) {
                Log.reportTxt()
                Log.reportMd()
                Log.tail(120)
                Log.count()
            }
        } catch (t: Throwable) {
            errors.add(t)
        } finally {
            stop.set(true)
            live.join(5000)
            Log.setEnabled(ctx, false)
            Log.clearBuffer()
        }
        assertTrue(
            "خواندن بافر لاگ هم‌زمان با نوشتن آن نباید بشکند: " +
                errors.joinToString { "${it.javaClass.simpleName}: ${it.message}" },
            errors.isEmpty()
        )
    }

    // ── ۳) تاریخچهٔ هشدارها هم زیر بار لایو ایمن است ──────────────────────────
    @Test
    fun `alert history stays readable while alerts fire from the live thread`() {
        val s = AppState.instance
        com.alisport.goldpin.util.Alerts.clearHistory()
        val stop = AtomicBoolean(false)
        val errors = CopyOnWriteArrayList<Throwable>()
        // alertSink خاموش تا نوتیفیکیشن/صدا در آزمون ساخته نشود؛ فقط تاریخچه نوشته می‌شود
        val prevEngine = s.engine.alertSink
        val prevBroker = s.broker.alertSink
        s.engine.alertSink = null
        s.broker.alertSink = null
        val fire = Thread {
            var n = 0
            while (!stop.get()) {
                com.alisport.goldpin.util.Alerts.fire(
                    null, com.alisport.goldpin.util.Alerts.K_PIN, "کندل مهم #$n", "بدنهٔ آزمایش $n"
                )
                n++
            }
        }
        fire.isDaemon = true
        fire.start()
        try {
            repeat(300) { com.alisport.goldpin.util.Alerts.history() }
        } catch (t: Throwable) {
            errors.add(t)
        } finally {
            stop.set(true)
            fire.join(5000)
            s.engine.alertSink = prevEngine
            s.broker.alertSink = prevBroker
            com.alisport.goldpin.util.Alerts.clearHistory()
        }
        assertTrue(
            "تاریخچهٔ هشدارها هم‌زمان با شلیک هشدار نباید بشکند: " +
                errors.joinToString { "${it.javaClass.simpleName}: ${it.message}" },
            errors.isEmpty()
        )
    }
}
