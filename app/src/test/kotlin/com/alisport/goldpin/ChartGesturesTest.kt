package com.alisport.goldpin

import android.app.Application
import android.os.Looper
import com.alisport.goldpin.ui.ChartView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

// ═══════════════════════════════════════════════════════════════════════════════
//  دو درخواست کاربر:
//   ۱) «لمس و نگه‌داشتن انگشت روی چارت منوی چارت رو میاره، زمانش رو ۲ ثانیه بیشتر کن»
//   ۲) «در تمام‌صفحه بخشی از منوی بالا مثل آخرین کندل و تنظیمات میره بالای نوار
//       وضعیت و دیده نمیشه»
// ═══════════════════════════════════════════════════════════════════════════════
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ChartGesturesTest {

    // ── ۱) آستانهٔ لمس طولانی ۲ ثانیه بیشتر شده ──────────────────────────────
    @Test
    fun `long press threshold is the old 420ms plus two seconds`() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        val cv = ChartView(ctx)
        assertEquals("۴۲۰ms قدیمی + ۲۰۰۰ms", 2420L, cv.longPressMs)
    }

    /**
     * ویو را به یک Activity واقعی وصل و layout می‌کند.
     * ⚠ لازم است: `View.postDelayed` روی ویوی **جدا از پنجره** اجرا نمی‌شود (در
     * `HandlerActionQueue` می‌ماند تا attach شود)، پس بدون attach آزمون بی‌معناست.
     */
    /**
     * کنترلرهای Activity ساخته‌شده. **باید در `@After` نابود شوند**: این آزمون‌ها
     * `MainActivity` واقعی می‌سازند که در `onCreate` یک شنونده روی singleton
     * `AppState` ثبت می‌کند و `AnrWatchdog` را روشن می‌کند. اگر رهایشان کنیم،
     * آزمون‌های دیگر (مثل `FreezeAndLayoutTest` و `AnrWatchdogTest`) آلوده می‌شوند.
     */
    private val controllers = ArrayList<org.robolectric.android.controller.ActivityController<*>>()

    @After
    fun tearDown() {
        controllers.forEach { c ->
            try { c.pause().stop().destroy() } catch (t: Throwable) { }
        }
        controllers.clear()
        com.alisport.goldpin.util.AnrWatchdog.stop()
    }

    private fun <T : android.app.Activity> build(cls: Class<T>): T {
        val c = Robolectric.buildActivity(cls).setup()
        controllers.add(c)
        return c.get()
    }

    private fun attachedChart(): Pair<ChartView, android.app.Activity> {
        val act = build(android.app.Activity::class.java)
        val cv = ChartView(act)
        act.setContentView(cv, android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT))
        cv.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY))
        cv.layout(0, 0, 1080, 1920)
        return cv to act
    }

    private fun down(cv: ChartView) = cv.dispatchTouchEvent(
        android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, 500f, 900f, 0))

    private fun move(cv: ChartView) = cv.dispatchTouchEvent(
        android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_MOVE, 800f, 400f, 0))

    /** رفتاری: در ۴۲۰ms منو باز **نمی‌شود**، در ۲۴۲۰ms باز می‌شود. */
    @Test
    fun `context menu does not fire at the old delay but fires at the new one`() {
        val (cv, _) = attachedChart()
        var fired = 0
        cv.onContextMenu = { fired++ }

        down(cv)
        shadowOf(Looper.getMainLooper()).idleFor(500, TimeUnit.MILLISECONDS)
        assertEquals("در ۵۰۰ms نباید منو باز شود", 0, fired)

        shadowOf(Looper.getMainLooper()).idleFor(2200, TimeUnit.MILLISECONDS)
        assertEquals("در ۲۴۲۰ms باید منو باز شود", 1, fired)

    }

    /** کشیدن انگشت باید تایمر را لغو کند (وگرنه هر زوم منو را باز می‌کرد). */
    @Test
    fun `moving the finger cancels the pending long press`() {
        val (cv, _) = attachedChart()
        var fired = 0
        cv.onContextMenu = { fired++ }
        down(cv)
        move(cv)
        shadowOf(Looper.getMainLooper()).idleFor(3000, TimeUnit.MILLISECONDS)
        assertEquals("بعد از کشیدن، منو نباید باز شود", 0, fired)
    }

    // ── ۲) تمام‌صفحه: محتوا به اندازهٔ نوار وضعیت پایین می‌آید ────────────────
    @Test
    fun `fullscreen pads the content below the status bar and removes it on exit`() {
        val act = build(MainActivity::class.java)
        val bar = act.statusBarHeightPx()
        println("statusBarHeightPx = $bar")
        assertTrue("ارتفاع نوار وضعیت باید مثبت باشد", bar > 0)

        val content = act.contentForTest
        val before = content.paddingTop

        act.applyFullscreenTopInset(true)
        assertEquals("در تمام‌صفحه باید به اندازهٔ نوار وضعیت padding بگیرد", bar, content.paddingTop)

        act.applyFullscreenTopInset(false)
        assertEquals("در خروج باید صفر شود", before, content.paddingTop)
    }
}
