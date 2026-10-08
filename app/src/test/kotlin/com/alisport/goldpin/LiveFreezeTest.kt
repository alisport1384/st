package com.alisport.goldpin

import com.alisport.goldpin.core.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * رگرسیون «فریز اپ در حالت لایو».
 *
 * در نسخهٔ ۱٫۳ دو کار سنگین روی نخ رابط انجام می‌شد:
 *   ۱) بازیابی وضعیت هنگام بالا آمدن اپ (`Store.load` ≈ ۳۳۶ms روی ۴۰٬۰۰۰ کندل)
 *   ۲) ادغام دنبالهٔ داده + اجرای موتور در هر تیک لایو (`AppState.updateLiveOnce`)
 * هر دو به نخ پس‌زمینه منتقل شدند.
 *
 * اما خودِ آن اصلاح یک خطر تازه داشت: `stateLock` حالا هنگام ساخت JSON ذخیرهٔ خودکار
 * (~۲۵۰ms) نگه داشته می‌شود؛ اگر خواندن وضعیت هم پشت همان قفل منتظر بماند، رابط در هر
 * ذخیرهٔ دوره‌ای فریز می‌شود. این آزمون همان ویژگی را قفل می‌کند:
 * **خواندن وضعیت برای رابط باید بدون هیچ انتظاری انجام شود.**
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class LiveFreezeTest {

    private fun candles(n: Int): List<Candle> = (0 until n).map {
        val p = 4000.0 + it * 0.05
        Candle(it, 1_700_000_000_000L + it * 60_000L, p, p + 1.0, p - 1.0, p + 0.3, 100.0)
    }

    private fun stateLock(): Any {
        val f = AppState::class.java.getDeclaredField("stateLock")
        f.isAccessible = true
        return f.get(AppState.instance)
    }

    /** رابط حتی وقتی موتور قفل را نگه داشته باشد، بدون انتظار وضعیت را می‌خواند. */
    @Test
    fun `reading ui state never waits on the engine lock`() {
        val s = AppState.instance
        s.setCandles(candles(2_000))

        val lock = stateLock()
        synchronized(lock) {
            // قفل در دست این تست است؛ اگر uiSnapshot() هم synchronized باشد، برای همیشه می‌ماند.
            val done = CountDownLatch(1)
            val reader = Thread {
                s.uiSnapshot(); s.tradesSnapshot(); s.ordersSnapshot()
                done.countDown()
            }
            reader.isDaemon = true
            reader.start()
            assertTrue(
                "خواندن وضعیت پشت stateLock منتظر ماند → رابط در هر ذخیرهٔ خودکار فریز می‌شود",
                done.await(3, TimeUnit.SECONDS)
            )
            // خواندن‌ها باید واقعاً داده برگردانند (نه فهرست خالی)
            assertEquals(2_000, s.uiSnapshot().candles.size)
        }
    }

    /** snapshot بعد از هر تغییر وضعیت منتشر می‌شود، پس رابط دادهٔ کهنه نشان نمی‌دهد. */
    @Test
    fun `snapshot is republished after a rebuild`() {
        val s = AppState.instance
        s.wipe()
        assertEquals(0, s.uiSnapshot().candles.size)
        s.setCandles(candles(1_500))
        val snap = s.uiSnapshot()
        assertEquals(1_500, snap.candles.size)
        // وضعیت موتور هم باید منتشر شده باشد، نه فقط کندل‌ها
        assertEquals(s.engine.processed, snap.candles.size)
        assertEquals(s.broker.trades.size, snap.trades.size)
    }

    /** بازیابی وضعیت دیگر روی نخ رابط اجرا نمی‌شود. */
    @Test
    fun `startup restore returns immediately and reports on the main thread later`() {
        val s = AppState.instance
        val ctx = RuntimeEnvironment.getApplication()
        var reported: String? = null
        val t0 = System.nanoTime()
        s.restoreOrSample(ctx) { reported = it }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("restoreOrSample باید فوراً برگردد (ولی ${ms}ms طول کشید)", ms < 200)
        assertEquals("نتیجه نباید هم‌زمان آماده باشد (کار پس‌زمینه است)", null, reported)
    }
}
