package com.alisport.goldpin

import android.app.Application
import com.alisport.goldpin.util.AnrWatchdog
import com.alisport.goldpin.util.Log
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// ═══════════════════════════════════════════════════════════════════════════════
//  سگ نگهبان نخ رابط
//
//  کاربر دو بار گفت «اپ فریز می‌شود» و دو بار گفت «نمی‌شود لاگ گرفت». یعنی تنها
//  کانال تشخیصی دقیقاً در لحظهٔ فریز از کار می‌افتد. این آزمون ثابت می‌کند که
//  نگهبان، فریز را تشخیص می‌دهد و پشتهٔ نخ اصلی را قبل از هر چیز ثبت می‌کند.
//
//  در Robolectric حلقهٔ پیام اصلی «متوقف» است (Runnableها تا idle شدن اجرا
//  نمی‌شوند) — یعنی دقیقاً همان وضعیتی که نخ رابط مسدود است.
// ═══════════════════════════════════════════════════════════════════════════════
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AnrWatchdogTest {

    @Test
    fun `watchdog detects a blocked main thread and logs the main-thread stack`() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        Log.init(ctx, "test")
        Log.setEnabled(ctx, true)   // پیش‌فرضِ prefs خاموش است؛ اینجا باید روشن باشد
        Log.clearBuffer()

        AnrWatchdog.thresholdMs = 300L
        AnrWatchdog.checkMs = 150L
        val before = AnrWatchdog.detectedCount
        try {
            AnrWatchdog.start()
            // حلقهٔ پیام اصلی را idle نمی‌کنیم ⇒ Runnable نگهبان هرگز اجرا نمی‌شود
            // ⇒ یعنی نخ رابط مسدود است. کمی صبر می‌کنیم تا نگهبان نمونه بگیرد.
            Thread.sleep(1400L)
        } finally {
            AnrWatchdog.stop()
        }

        val detected = AnrWatchdog.detectedCount - before
        val anrLines = Log.tail(500).filter { it.contains("[ANR]") }
        println("watchdog detected=$detected worst=${AnrWatchdog.worstStallMs}ms anrLines=${anrLines.size}")
        anrLines.take(2).forEach { println("  $it") }

        assertTrue("نگهبان باید فریز را تشخیص می‌داد", detected > 0)
        assertTrue("باید دست‌کم یک ردپای ANR در لاگ باشد", anrLines.isNotEmpty())
        assertTrue(
            "ردپا باید پشتهٔ نخ اصلی را داشته باشد",
            anrLines.any { it.contains("main-thread stack") || it.contains("at ") }
        )
        assertTrue("بدترین فریز باید ثبت شود", AnrWatchdog.worstStallMs >= AnrWatchdog.thresholdMs)
    }

    @Test
    fun `watchdog stays silent when the main thread is healthy`() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        Log.init(ctx, "test")
        Log.setEnabled(ctx, true)   // پیش‌فرضِ prefs خاموش است؛ اینجا باید روشن باشد

        // حلقهٔ پیام اصلی سالم است: Runnableها بلافاصله اجرا می‌شوند
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        AnrWatchdog.thresholdMs = 200L
        AnrWatchdog.checkMs = 100L
        val before = AnrWatchdog.detectedCount
        try {
            AnrWatchdog.start()
            // نگهبان روی نخ خودش است؛ حلقهٔ اصلی باید آزاد بماند تا Runnableها اجرا شوند
            val end = System.currentTimeMillis() + 900L
            while (System.currentTimeMillis() < end) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                Thread.sleep(40L)
            }
        } finally {
            AnrWatchdog.stop()
        }
        val detected = AnrWatchdog.detectedCount - before
        println("healthy main thread: detected=$detected")
        assertTrue("نخ رابط سالم نباید گزارش فریز بدهد — ولی $detected بار داد", detected == 0)
    }

    /**
     * حلقهٔ کامل تشخیص باید بسته باشد: ردپای ANR نه‌تنها نوشته شود، بلکه بعد از
     * بستن و بازکردن اپ هم **قابل صدور** باشد. پیش‌تر `exportLog` فقط بافر نشست
     * جاری را می‌داد، یعنی بعد از فریز راهی برای بیرون کشیدن ردپا نبود.
     */
    @Test
    fun `previous session log is included in the export after a restart`() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        Log.init(ctx, "test")
        Log.setEnabled(ctx, true)
        val marker = "ANR-MARKER-${System.nanoTime()}"
        Log.e(Log.CAT_ANR, marker, "main-thread stack:\n    at fake.Frame")
        Log.flush()

        // شبیه‌سازی بستن و بازکردن مجدد اپ
        Log.init(ctx, "test")
        val exported = Log.reportTxt()
        println("export contains previous-session marker: ${exported.contains(marker)}")
        assertTrue(
            "خروجی باید دنبالهٔ نشست قبلی (شامل ردپای ANR) را داشته باشد",
            exported.contains(marker)
        )
        assertTrue(
            "خروجی باید مشخص کند این بخش از نشست قبلی است",
            exported.contains("نشست قبلی")
        )
    }
}
