package com.alisport.goldpin.util

import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/**
 * سگ نگهبانِ نخ رابط کاربری (ANR Watchdog).
 *
 * ═══ چرا این فایل وجود دارد ═══
 * کاربر دو بار گزارش داد «وقتی لایو می‌گیرم اپ فریز می‌شود» و دو بار افزود
 * «نمی‌شود لاگ گرفت». یعنی تنها کانال تشخیصی ما دقیقاً در لحظهٔ فریز از کار
 * می‌افتد: دکمهٔ خروجی لاگ خودش پشت همان کارِ مسدودکننده در صف نخ رابط می‌ماند.
 * پس تا وقتی خودِ فریز را ثبت نکنیم، هر تشخیصی حدس است (و دو حدس اول اشتباه
 * از آب درآمد).
 *
 * این کلاس همان کاری را می‌کند که ابزار ANR خود اندروید می‌کند، ولی زودتر و با
 * ثبت در لاگ خودِ اپ:
 *
 *   ۱) هر [checkMs] یک Runnable سبک روی `Looper` اصلی می‌گذارد.
 *   ۲) یک نخ نگهبان [thresholdMs] صبر می‌کند؛ اگر آن Runnable هنوز اجرا نشده بود
 *      یعنی نخ رابط دست‌کم به مدت [thresholdMs] مسدود است.
 *   ۳) در آن لحظه **پشتهٔ نخ اصلی** را می‌گیرد و در لاگ می‌نویسد، سپس `Log.flush()`
 *      می‌زند تا حتی اگر فریز ادامه پیدا کند، ردپا روی دیسک باشد.
 *
 * خروجی، نام دقیق تابعی است که نخ رابط را نگه داشته — یعنی پاسخ قطعی به جای حدس.
 *
 * هزینه: یک نخ پس‌زمینه (daemon) + یک `post` در هر [checkMs]. عملاً صفر.
 */
object AnrWatchdog {

    /** دستهٔ لاگ برای ردپای فریز */
    const val CAT_ANR = "ANR"

    /** آستانهٔ اعلام فریز (میلی‌ثانیه). کمتر از این، نویز می‌شود. */
    @Volatile var thresholdMs: Long = 2500L

    /** فاصلهٔ نمونه‌برداری (میلی‌ثانیه) */
    @Volatile var checkMs: Long = 700L

    /** چند بار فریز تشخیص داده شده (برای نمایش در رابط و نوتیفیکیشن وضعیت) */
    @Volatile var detectedCount: Int = 0
        private set

    /** طولانی‌ترین فریز ثبت‌شده (میلی‌ثانیه) */
    @Volatile var worstStallMs: Long = 0L
        private set

    @Volatile private var running = false
    private var thread: Thread? = null
    private var mainHandler: Handler? = null

    @Synchronized
    fun start() {
        if (running) return
        if (Looper.getMainLooper() == null) return
        running = true
        mainHandler = Handler(Looper.getMainLooper())
        thread = Thread({ loop() }, "goldpin-anr-watch").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
        Log.i(CAT_ANR, "watchdog started", "threshold=${thresholdMs}ms check=${checkMs}ms")
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        thread?.interrupt()
        thread = null
        mainHandler = null
    }

    private fun loop() {
        val h = mainHandler ?: return
        while (running) {
            val ran = AtomicBoolean(false)
            val postedAt = monoMs()
            try {
                h.post {
                    ran.set(true)
                }
            } catch (t: Throwable) {
                return
            }
            try {
                Thread.sleep(thresholdMs)
            } catch (e: InterruptedException) {
                return
            }
            if (!running) return
            if (ran.get()) continue

            // ── نخ رابط دست‌کم thresholdMs است که به کار ما نرسیده ──
            var stallMs = monoMs() - postedAt
            detectedCount++
            if (stallMs > worstStallMs) worstStallMs = stallMs
            reportStall(stallMs)

            // تا رفع شدن فریز، هر thresholdMs یک ردپای تازه (حداکثر چند نمونه)
            var extra = 0
            while (running && !ran.get() && extra < MAX_EXTRA_SAMPLES) {
                try {
                    Thread.sleep(thresholdMs)
                } catch (e: InterruptedException) {
                    return
                }
                if (!running) return
                stallMs = monoMs() - postedAt
                if (stallMs > worstStallMs) worstStallMs = stallMs
                extra++
                reportStall(stallMs, followUp = extra)
            }
        }
    }

    /** حداکثر چند ردپای پی‌گیرانه برای یک فریز (جلوگیری از پر شدن لاگ) */
    private const val MAX_EXTRA_SAMPLES = 3

    /**
     * ساعت یکنواخت بر حسب میلی‌ثانیه.
     * `SystemClock.uptimeMillis()` در آزمون‌های Robolectric یخ‌زده است (و در دستگاه هم
     * با خواب عمیق بازی می‌کند)، پس برای سنجش «چقدر گذشته» از `nanoTime` استفاده
     * می‌کنیم که همیشه جلو می‌رود.
     */
    private fun monoMs(): Long = System.nanoTime() / 1_000_000L

    private fun reportStall(stallMs: Long, followUp: Int = 0) {
        val main = try {
            Looper.getMainLooper()?.thread
        } catch (t: Throwable) {
            null
        }
        val trace = if (main != null) {
            try {
                main.stackTrace.joinToString("\n") { "    at $it" }
            } catch (t: Throwable) {
                "    (پشته قابل خواندن نبود: ${t.message})"
            }
        } else "    (نخ اصلی در دسترس نبود)"

        val state = try {
            main?.state?.name ?: "?"
        } catch (t: Throwable) {
            "?"
        }

        val title = if (followUp == 0) "main thread stalled" else "still stalled #$followUp"
        Log.e(
            CAT_ANR, title,
            "lag=${stallMs}ms state=$state detected=#$detectedCount worst=${worstStallMs}ms\n" +
                "main-thread stack:\n$trace"
        )
        // حتی اگر فریز ادامه یابد، این ردپا باید روی دیسک بنشیند
        try {
            Log.flush()
        } catch (t: Throwable) {
        }
    }
}
