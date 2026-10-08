package com.alisport.goldpin

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.alisport.goldpin.core.Candle
import com.alisport.goldpin.ui.ChartView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Method

/**
 * رگرسیون باگ‌های نسخهٔ ۱٫۱:
 *  ۱) فریز و مصرف کل CPU هنگام رسم چارت (حلقهٔ بی‌پایان شبکهٔ قیمت) — هر رسم باید سریع تمام شود.
 *  ۲) هم‌پوشانی دکمه‌ها/انتخاب تایم‌فریم با چارت — نوارها باید «خواهر» چارت باشند، نه شناور روی آن.
 *  ۳) تمام‌صفحه نباید اجباری باشد.
 *  ۴) تب گزارش و منوی لاگ نباید نخ رابط را قفل کنند.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class FreezeAndLayoutTest {

    private fun layout(cv: View, w: Int = 1080, h: Int = 1700) {
        cv.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        )
        cv.layout(0, 0, w, h)
    }

    private fun draw(cv: View): Long {
        val bmp = Bitmap.createBitmap(1080.coerceAtLeast(1), 1700.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val t0 = System.nanoTime()
        cv.draw(c)
        return (System.nanoTime() - t0) / 1_000_000
    }

    private fun candlesFlat(n: Int): List<Candle> =
        (0 until n).map { Candle(it, 1_700_000_000_000L + it * 60_000L, 4000.0, 4000.0, 4000.0, 4000.0, 0.0) }

    private fun candlesNormal(n: Int): List<Candle> =
        (0 until n).map {
            val p = 4000.0 + it * 0.3
            Candle(it, 1_700_000_000_000L + it * 60_000L, p, p + 1.1, p - 0.9, p + 0.4, 50.0 + it % 50)
        }

    private fun synthetic(n: Int): List<Candle> =
        (0 until n).map {
            val p = 4000.0 + kotlin.math.sin(it / 25.0) * 6.0 + it * 0.01
            Candle(it, 1_700_000_000_000L + it * 60_000L, p, p + 1.2, p - 1.0, p + 0.3, 60.0 + (it % 90))
        }

    /** باگ ۱: دادهٔ تخت (دامنهٔ صفر) — قبلاً حلقهٔ شبکه هرگز تمام نمی‌شد */
    @Test
    fun `flat data never freezes the chart draw`() {
        val cv = ChartView(RuntimeEnvironment.getApplication())
        cv.candles = candlesFlat(300)
        layout(cv)
        cv.resetView()
        val ms = draw(cv)
        assertTrue("رسم دادهٔ تخت باید سریع باشد ولی ${ms}ms طول کشید", ms < 1500)
    }

    /** باگ ۱ (ادامه): پینچ شدید روی هر دو محور، سپس رسم — نباید قفل کند */
    @Test
    fun `extreme pinch then draw stays fast`() {
        val cv = ChartView(RuntimeEnvironment.getApplication())
        cv.candles = candlesNormal(2000)
        layout(cv)
        cv.resetView()
        var worst = 0L
        repeat(60) {
            cv.zoomBoth(2.6f, 540f, 700f)          // زوم پیاپی زمان + قیمت
            worst = maxOf(worst, draw(cv))
        }
        repeat(60) {
            cv.zoomBoth(0.4f, 12f, 40f)            // خروج شدید از زوم
            worst = maxOf(worst, draw(cv))
        }
        assertTrue("بدترین زمان رسم ${worst}ms — باید زیر ۱۵۰۰ms بماند", worst < 1500)
    }

    /** باگ ۱ (سقف): شبیه‌سازی مقادیر ناسالم (NaN/صفر) — رسم باید سالم و سریع باشد */
    @Test
    fun `degenerate view state is repaired before draw`() {
        val cv = ChartView(RuntimeEnvironment.getApplication())
        cv.candles = candlesNormal(500)
        layout(cv)
        // تزریق حالت ناسالم از طریق API عمومی با ضرایب غیرعادی
        cv.zoomBoth(Float.NaN, 100f, 100f)
        cv.zoomBoth(0f, 0f, 0f)
        cv.zoomBoth(1e-9f, 500f, 600f)
        val ms = draw(cv)
        assertTrue("رسم پس از حالت ناسالم باید سریع باشد ولی ${ms}ms بود", ms < 1500)
    }

    /** باگ ۲: نوار ابزار و ردیف تایم‌فریم باید بیرون از چارت و بدون هم‌پوشانی باشند */
    @Test
    fun `toolbar and timeframe row sit above the chart without overlap`() {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        val act = c.get()
        shadowOf(Looper.getMainLooper()).idle()
        val content = act.window.decorView.findViewById<ViewGroup>(android.R.id.content)
        val shell = content.getChildAt(0) as LinearLayout
        val tabContent = shell.getChildAt(1) as ViewGroup      // محتوای تب
        val col = tabContent.getChildAt(0) as LinearLayout

        // چیدمان جدید: [نوار ابزار][جداکننده][ردیف تایم‌فریم][جداکننده][چارت][جداکننده][پنل پایین]
        val views = (0 until col.childCount).map { col.getChildAt(it) }
        val chartIdx = views.indexOfFirst { it is ChartView }
        assertTrue("چارت باید در چیدمان باشد", chartIdx >= 0)
        assertTrue("چارت باید بعد از نوار ابزار و ردیف تایم‌فریم بیاید (نه زیر آن‌ها)", chartIdx >= 4)
        // col یک LinearLayout است، پس نوارها در آن جریان عادی دارند و شناور نیستند.

        // چارت با وزن ۱ تمام فضای باقی‌مانده را می‌گیرد
        val chart = views[chartIdx] as ChartView
        assertTrue("ارتفاع چارت باید کشسان (weight=1) باشد", chart.layoutParams.height == 0)

        // ChartView خود یک View مستقل است و نوارها در col هستند، نه روی آن.
        c.pause().stop().destroy()
    }

    /** باگ ۳: تمام‌صفحه دیگر اجباری نیست */
    @Test
    fun `fullscreen is off by default`() {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        val act = c.get()
        shadowOf(Looper.getMainLooper()).idle()
        val content = act.window.decorView.findViewById<ViewGroup>(android.R.id.content)
        val shell = content.getChildAt(0) as LinearLayout
        assertEquals("هدر باید دیده شود (تمام‌صفحه اختیاری)", View.VISIBLE, shell.getChildAt(0).visibility)
        assertFalse("پیش‌فرض تمام‌صفحه باید خاموش باشد", AppState.instance.autoFullscreen)
        c.pause().stop().destroy()
    }

    /** باگ ۴: تب گزارش‌ها و منوی لاگ باید سریع باز شوند و نخ رابط را قفل نکنند */
    @Test
    fun `reports tab and log menu open quickly`() {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        val act = c.get()
        shadowOf(Looper.getMainLooper()).idle()
        val s = AppState.instance
        s.setCandles(synthetic(20_000))
        shadowOf(Looper.getMainLooper()).idle()

        fun callTab(i: Int) {
            val m: Method = act.javaClass.getDeclaredMethod("showTab", Int::class.javaPrimitiveType)
            m.isAccessible = true
            m.invoke(act, i)
        }
        var t0 = System.nanoTime()
        callTab(3)
        shadowOf(Looper.getMainLooper()).idle()
        val reportsMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue("تب گزارش‌ها باید سریع باز شود ولی ${reportsMs}ms شد", reportsMs < 2500)

        // لاگر با ۱۵٬۰۰۰ خط و باز کردن منوی لاگ
        com.alisport.goldpin.util.Log.init(act, "1.2")
        com.alisport.goldpin.util.Log.setEnabled(act, true)
        com.alisport.goldpin.util.Log.setWriteToFile(act, false)
        com.alisport.goldpin.util.Log.setLevel(act, com.alisport.goldpin.util.Log.DEBUG)
        for (i in 0 until 15_000) com.alisport.goldpin.util.Log.i(com.alisport.goldpin.util.Log.CAT_ENGINE, "خط $i", "v=$i")

        t0 = System.nanoTime()
        callTab(4)
        shadowOf(Looper.getMainLooper()).idle()
        val settingsMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue("تب تنظیمات با لاگر پرمحتو باید سریع باز شود ولی ${settingsMs}ms شد", settingsMs < 2500)

        t0 = System.nanoTime()
        val m2: Method = act.javaClass.getDeclaredMethod("showLogViewer")
        m2.isAccessible = true
        m2.invoke(act)
        shadowOf(Looper.getMainLooper()).idle()
        val logMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue("نمایش لاگ باید سریع باشد ولی ${logMs}ms شد", logMs < 2000)
        com.alisport.goldpin.util.Log.setEnabled(act, false)
        com.alisport.goldpin.util.Log.clearBuffer()
        c.pause().stop().destroy()
    }

    /** تازه‌سازی مکرر رابط (سیل رخدادها) نباید CPU را بخورد */
    @Test
    fun `frequent ui notifications are coalesced`() {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        AppState.instance.setCandles(synthetic(5_000))
        val t0 = System.nanoTime()
        repeat(200) { AppState.instance.notifyUi() }      // مثل سیل رخدادهای موتور
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("۲۰۰ اطلاع‌رسانی پیاپی باید ارزان باشد ولی ${ms}ms شد", ms < 2500)
        assertEquals("تعداد شنونده‌ها نباید انباشته شود", 1, AppState.instance.listeners.size)
        c.pause().stop().destroy()
    }
}
