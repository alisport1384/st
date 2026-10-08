package com.alisport.goldpin

import android.content.Context
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import com.alisport.goldpin.core.Candle
import com.alisport.goldpin.ui.ChartView
import com.alisport.goldpin.util.Alerts
import com.alisport.goldpin.util.Log
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * آزمون‌های لاگر (خروجی .md و .txt)، سیستم هشدار و ژست‌های چارت.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class LoggerAlertTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    // ── ۱) لاگر پیش‌فرض خاموش است ────────────────────────────────────────────
    @Test
    fun `logger is off by default and writes nothing`() {
        Log.init(ctx, "1.1")
        Log.setEnabled(ctx, false)
        val before = Log.count()
        Log.i(Log.CAT_APP, "این خط نباید ثبت شود")
        assertEquals("در حالت خاموش هیچ خطی ثبت نمی‌شود", before, Log.count())
        assertFalse(Log.enabled)
    }

    // ── ۲) روشن کردن لاگر + خروجی .md و .txt ────────────────────────────────
    @Test
    fun `logger writes both md and txt files when enabled`() {
        Log.init(ctx, "1.1")
        Log.setLevel(ctx, Log.DEBUG)
        Log.setWriteToFile(ctx, true)
        Log.setEnabled(ctx, true)

        Log.clearBuffer()
        Log.i(Log.CAT_ENGINE, "کندل مهم Ready BU تایید شد", "lo=4123.50 bar=1200")
        Log.i(Log.CAT_ORDER, "سفارش ورود ثبت شد", "price=4128.00 qty=2.15")
        Log.w(Log.CAT_FEED, "فید کند بود", "مدت=3200ms")
        Log.e(Log.CAT_FEED, "درخواست ناموفق", "HTTP 429")
        Log.flush()

        val files = Log.files()
        assertTrue("باید فایل لاگ ساخته شود (مسیر=${Log.dirPath()})", files.isNotEmpty())
        val md = files.first { it.name.endsWith(".md") }
        val txt = files.first { it.name.endsWith(".txt") }
        val mdText = md.readText()
        val txtText = txt.readText()

        assertTrue("جدول Markdown باید سرصفحه داشته باشد", mdText.contains("| زمان | سطح | دسته | پیام | داده |"))
        assertTrue("خط لاگ در md", mdText.contains("Ready BU"))
        assertTrue("خط لاگ در txt", txtText.contains("Ready BU"))
        assertTrue("دستهٔ سفارش در txt", txtText.contains("[ORDER]"))
        assertTrue("سطح WARN در txt", txtText.contains("WARN"))
        assertTrue("سطح ERROR در md", mdText.contains("ERROR"))
        // متن کامل فارسی هم باید درست ذخیره شود
        assertTrue(mdText.contains("سفارش ورود ثبت شد"))

        Log.setEnabled(ctx, false)
        Log.closeSession()
    }

    // ── ۳) سطح و دسته‌بندی ─────────────────────────────────────────────────
    @Test
    fun `level filter and mute work`() {
        Log.init(ctx, "1.1")
        Log.setEnabled(ctx, true)
        Log.setWriteToFile(ctx, false)
        Log.setLevel(ctx, Log.WARN)
        val n0 = Log.count()
        Log.i(Log.CAT_APP, "اطلاع — نباید ثبت شود")
        assertEquals(n0, Log.count())
        Log.w(Log.CAT_APP, "هشدار — باید ثبت شود")
        assertEquals(n0 + 1, Log.count())

        Log.setMuted(ctx, Log.CAT_APP, true)
        val n1 = Log.count()
        Log.w(Log.CAT_APP, "هشدار خاموش‌شده")
        assertEquals(n1, Log.count())
        Log.setMuted(ctx, Log.CAT_APP, false)
        Log.setEnabled(ctx, false)
    }

    // ── ۴) موتور و کارگزار واقعاً لاگ می‌دهند ────────────────────────────────
    @Test
    fun `engine and broker feed the logger`() {
        Log.init(ctx, "1.1")
        Log.setEnabled(ctx, true)
        Log.setWriteToFile(ctx, false)
        Log.setLevel(ctx, Log.DEBUG)
        val s = AppState.instance
        s.initApp(ctx)
        s.wireLogging()
        assertEquals("قلاب موتور وصل نشد", true, s.engine.logSink != null)
        assertEquals("قلاب کارگزار وصل نشد", true, s.broker.logSink != null)

        // داده واقعی نمونه
        val text = com.alisport.goldpin.data.Feed.openSampleAsset(ctx)
            .use { com.alisport.goldpin.data.Feed.readCsvStream(it) }
        val list = com.alisport.goldpin.data.Feed.parseCsv(text).take(12_000)
        s.cfg.tf2 = 60; s.chartTfSec = 60; s.backtestFull = true
        s.setCandles(list)
        val before = Log.count()
        s.rebuild()
        val after = Log.count()
        assertTrue("اجرای موتور باید لاگ تولید کند", after > before)

        val tail = Log.tail(4000).joinToString("\n")
        assertTrue("لاگ ناحیه/ستاپ باید ثبت شود", tail.contains("ستاپ") || tail.contains("ناحیه"))
        Log.setEnabled(ctx, false)
    }

    // ── ۵) سیستم هشدار ─────────────────────────────────────────────────────
    @Test
    fun `alerts fire, record history and call banner`() {
        Alerts.init(ctx)
        Alerts.setEnabled(ctx, true)
        Alerts.setKind(ctx, Alerts.K_ARMED, true)
        var bannerText = ""
        Alerts.bannerSink = { t, _, _ -> bannerText = t }

        Alerts.fire(ctx, Alerts.K_ARMED, "سفارش خرید آماده شد", "ورود ۴۱۲۸٫۵۰ · حدضرر ۴۱۲۶٫۱۰", "st=1")
        val h = Alerts.history()
        assertTrue("هشدار باید در تاریخچه ثبت شود", h.isNotEmpty())
        assertEquals(Alerts.K_ARMED, h.last().kind)
        assertEquals("سفارش خرید آماده شد", h.last().title)
        assertTrue("بنر باید فراخوانی شود", bannerText.contains("سفارش خرید آماده شد"))

        // خاموش کردن یک نوع → نباید بنر بیاید
        Alerts.setKind(ctx, Alerts.K_ARMED, false)
        bannerText = ""
        Alerts.fire(ctx, Alerts.K_ARMED, "دوباره", "تست")
        assertTrue("این نوع خاموش است", bannerText.isEmpty())
        Alerts.bannerSink = null
    }

    // ── ۶) ژست پینچ دو انگشتی → زوم هم‌زمان زمان و قیمت ───────────────────────
    @Test
    fun `pinch gesture zooms both time and price axes`() {
        val cv = ChartView(ctx)
        val n = 500
        val list = ArrayList<Candle>()
        var px = 4000.0
        for (i in 0 until n) {
            list.add(Candle(i, 1_700_000_000_000L + i * 60_000L, px, px + 1.2, px - 1.2, px + 0.3, 100.0 + i))
            px += 0.4
        }
        cv.candles = list
        cv.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY)
        )
        cv.layout(0, 0, 1080, 1600)
        cv.resetView()
        val w0 = cv.barW
        val span0 = cv.priceSpan
        assertTrue("عرض کندل اولیه معتبر", w0 > 0f)
        assertTrue("مقیاس خودکار فعال است", cv.autoPrice)

        // پینچ واقعی با دو انگشت: فاصله ۲۰۰ → ۴۰۰ پیکسل
        fun ev(action: Int, x0: Float, y0: Float, x1: Float, y1: Float, id1: Int = 1, count: Int = 2): MotionEvent {
            val props = arrayOf(MotionEvent.PointerProperties(), MotionEvent.PointerProperties())
            val coords = arrayOf(MotionEvent.PointerCoords(), MotionEvent.PointerCoords())
            props[0].id = 0; props[0].toolType = MotionEvent.TOOL_TYPE_FINGER
            props[1].id = id1; props[1].toolType = MotionEvent.TOOL_TYPE_FINGER
            coords[0].x = x0; coords[0].y = y0; coords[0].pressure = 1f; coords[0].size = 1f
            coords[1].x = x1; coords[1].y = y1; coords[1].pressure = 1f; coords[1].size = 1f
            return MotionEvent.obtain(0, 0, action, count, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        }

        val d0 = ev(MotionEvent.ACTION_DOWN, 440f, 800f, 440f, 800f, id1 = 0, count = 1)
        cv.onTouchEvent(d0)
        val pd = ev(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 440f, 800f, 640f, 800f)
        cv.onTouchEvent(pd)
        val mv = ev(MotionEvent.ACTION_MOVE, 340f, 800f, 740f, 800f)
        cv.onTouchEvent(mv)

        assertTrue("پینچ باید کندل‌ها را بازتر کند (زوم زمان)", cv.barW > w0)
        assertTrue("پینچ باید مقیاس قیمت را هم تغییر دهد", cv.priceSpan != span0)
        assertFalse("پینچ مقیاس خودکار را خاموش می‌کند", cv.autoPrice)

        val pu = ev(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 340f, 800f, 740f, 800f)
        cv.onTouchEvent(pu)
        val up = ev(MotionEvent.ACTION_UP, 340f, 800f, 340f, 800f, id1 = 0, count = 1)
        cv.onTouchEvent(up)

        // جابه‌جایی با یک انگشت
        val idx0 = cv.rightIdx
        cv.panPixels(-120f, 0f)
        assertTrue("جابه‌جایی باید موقعیت را تغییر دهد", cv.rightIdx != idx0)

        // بازنشانی نما
        cv.resetView()
        assertTrue("پس از بازنشانی، دنبال لایو", cv.followLive)
        assertTrue("پس از بازنشانی، مقیاس خودکار", cv.autoPrice)
        assertEquals("عرض کندل به حالت اولیه", w0, cv.barW, 0.001f)
    }

    // ── ۷) محور قیمت: کشیدن = مقیاس دستی ، دو ضربه = خودکار ─────────────────
    @Test
    fun `price axis drag disables auto and double tap on axis restores it`() {
        val cv = ChartView(ctx)
        val list = ArrayList<Candle>()
        for (i in 0 until 300) list.add(Candle(i, 1_700_000_000_000L + i * 60_000L, 4000.0, 4002.0, 3998.0, 4001.0, 100.0))
        cv.candles = list
        cv.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY)
        )
        cv.layout(0, 0, 1080, 1200)
        cv.resetView()

        // کشیدن در ناحیهٔ محور قیمت (چپ/راست چارت فارسی — محور در سمت plotW به بعد)
        val axisX = 1080f - 20f
        var t = 0L
        fun e(x: Float, y: Float, act: Int): MotionEvent =
            MotionEvent.obtain(t, t, act, x, y, 0)
        cv.onTouchEvent(e(axisX, 600f, MotionEvent.ACTION_DOWN))
        t += 16
        cv.onTouchEvent(e(axisX, 700f, MotionEvent.ACTION_MOVE))
        t += 16
        cv.onTouchEvent(e(axisX, 700f, MotionEvent.ACTION_UP))
        assertFalse("کشیدن روی محور قیمت → مقیاس دستی", cv.autoPrice)
        cv.resetPrice()
        assertTrue("بازگشت به مقیاس خودکار", cv.autoPrice)
    }

    // ── ۸) حالت تمام‌صفحه در اکتیویتی ────────────────────────────────────────
    @Test
    fun `fullscreen mode hides header and tabs`() {
        val c = org.robolectric.Robolectric.buildActivity(MainActivity::class.java).setup()
        val act = c.get()
        shadowOf(Looper.getMainLooper()).idle()
        val content = act.window.decorView.findViewById<android.view.ViewGroup>(android.R.id.content)
        val shell = content.getChildAt(0) as android.widget.LinearLayout
        assertNotNull(shell)
        val header = shell.getChildAt(0)
        val tabs = shell.getChildAt(2)
        // با پیش‌فرض روشن، اپ باید تمام‌صفحه باشد
        val s = AppState.instance
        s.autoFullscreen = true
        assertEquals("هدر در تمام‌صفحه پنهان است", View.GONE, header.visibility)
        assertEquals("نوار تب‌ها در تمام‌صفحه پنهان است", View.GONE, tabs.visibility)
        // خروج از تمام‌صفحه با back
        act.onKeyDown(android.view.KeyEvent.KEYCODE_BACK, android.view.KeyEvent(0, android.view.KeyEvent.KEYCODE_BACK))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("پس از خروج، هدر برمی‌گردد", View.VISIBLE, header.visibility)
        assertEquals("پس از خروج، تب‌ها برمی‌گردند", View.VISIBLE, tabs.visibility)
        c.pause().stop().destroy()
    }
}
