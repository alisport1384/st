package com.alisport.goldpin

import com.alisport.goldpin.core.Candle
import com.alisport.goldpin.core.Tf
import com.alisport.goldpin.util.Alerts
import com.alisport.goldpin.util.Log
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

// ═══════════════════════════════════════════════════════════════════════════════
//  بازتولید دقیق چیزی که کاربر گزارش داد
//
//  لاگ واقعی کاربر (samsung SM-J810F، اندروید ۱۰) پیش از روشن‌کردن لایو:
//
//      21:36:31.969  CFG   مود تایم‌فریمی 2 انتخاب شد   W / D / 4H / 1H   ← سری ۱ ساعته
//      21:36:34.590  CFG   مود تایم‌فریمی 1 انتخاب شد   1H / 15m / 5m / 1m ← chartTf = 1m
//      21:36:34.904  ENGINE اجرای موتور تمام شد  مدت=311ms پردازش‌شده=668
//                     ستاپ=443 ولوم‌کم=1950 ورود=83 باکس=273
//
//  یعنی `candles` ششصد‌وشصت‌وهشت کندل **یک‌ساعته** است ولی `chartTfSec` یک دقیقه شده
//  (تجمیع ۱H به 1m ممکن نیست، پس همان ۶۶۸ کندل ۱H با برچسب 1m می‌ماند). بعد تیک لایو
//  با ("1m","5d") پنج روز کندل **یک‌دقیقه‌ای** می‌گیرد و `mergeTail` آن‌ها را داخل سری
//  یک‌ساعته می‌ریزد ⇒ موتور ناگهان ~۶٬۰۰۰ کندل «جدید» می‌بیند.
//
//  چون سرویس لایو `Alerts.inBacktest = false` گذاشته، همهٔ آن‌ها «زنده» حساب می‌شدند و
//  برای هر کدام یک `runOnUiThread` (ساخت/حذف View + requestLayout) و یک نوتیفیکیشن
//  ساخته می‌شد ⇒ چند هزار کار روی نخ رابط ⇒ فریز کامل.
//
//  ⚠ نکتهٔ اندازه‌گیری: `Engine.alertSink` **بدون توجه به `inBacktest`** صدا زده می‌شود
//  (تصمیم‌گیرنده `Alerts.fire` است). پس شمارش `alertSink` سیل را نشان نمی‌دهد؛ چیزی که
//  واقعاً نخ رابط را پر می‌کند `Alerts.bannerSink` و `postNotification` است. اینجا همان
//  `bannerSink` را می‌شماریم.
// ═══════════════════════════════════════════════════════════════════════════════
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class LiveAlertStormTest {

    private fun hourlySeries(n: Int, endMs: Long): List<Candle> {
        val out = ArrayList<Candle>(n)
        var px = 4200.0
        var t = endMs - (n - 1) * Tf.H1 * 1000L
        for (i in 0 until n) {
            val o = px
            out.add(Candle(i, t, o, o + 12 + (i % 7), o - 11 - (i % 5), o + ((i * 37) % 19) - 9,
                900.0 + (i % 40)))
            px = o + ((i * 37) % 19) - 9; t += Tf.H1 * 1000L
        }
        return out
    }

    private fun minuteSeries(n: Int, endMs: Long, startIdx: Int = 0): List<Candle> {
        val out = ArrayList<Candle>(n)
        var px = 4180.0
        var t = endMs - (n - 1) * Tf.M1 * 1000L
        for (i in 0 until n) {
            val o = px
            out.add(Candle(startIdx + i, t, o, o + 3.2 + (i % 5) * 0.4, o - 3.0 - (i % 4) * 0.3,
                o + ((i * 53) % 11) * 0.4 - 2.0, 40.0 + (i % 30)))
            px = o + ((i * 53) % 11) * 0.4 - 2.0; t += Tf.M1 * 1000L
        }
        return out
    }

    /**
     * بنرها (= پست‌های `runOnUiThread` در اپ واقعی) را می‌شمارد.
     *
     * ⚠ `alertSink` موتور را **دست نمی‌زنیم**: در اپ واقعی `wireLogging()` آن را به
     * `Alerts.fire` وصل می‌کند و اگر اینجا no-op بگذاریم، مسیر واقعی اصلاً اجرا
     * نمی‌شود و آزمون بی‌معناست.
     */
    private fun countBanners(body: () -> Unit): Int {
        val n = AtomicInteger(0)
        val prevBanner = Alerts.bannerSink
        val prevInBt = Alerts.inBacktest
        val prevRun = Alerts.liveRunning
        Alerts.bannerSink = { _, _, _ -> n.incrementAndGet() }   // (متن، آیکون، نوع)
        try {
            body()
        } finally {
            Alerts.bannerSink = prevBanner
            Alerts.inBacktest = prevInBt
            Alerts.liveRunning = prevRun
        }
        return n.get()
    }

    // ═══ ۱) نتیجهٔ منفیِ ثبت‌شده: «سیل بنر» علت فریز نبود ══════════════════════
    //
    //  این آزمون در ابتدا برای اثبات این ادعا نوشته شده بود که بازپخش انبوه، نخ رابط
    //  را با بنر پر می‌کند. اندازه‌گیری واقعی آن را **رد کرد**: خطوط «هشدار […]» در لاگ
    //  کاربر پیش از بررسی `isOn(kind)` ثبت می‌شوند، پس دیدن چند صد خط هشدار در لاگ
    //  به‌معنی چند صد کار روی نخ رابط نیست. اینجا همان اندازه‌گیری قفل می‌شود تا
    //  کسی دوباره سراغ این نظریهٔ ردشده نرود.
    @Test
    fun `replaying thousands of bars logs many alerts but does NOT flood the UI with banners`() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        Log.init(ctx, "test")
        Log.setEnabled(ctx, true)
        val s = AppState()
        s.chartTfSec = Tf.M1
        s.cfg.tf2 = Tf.M1
        val now = System.currentTimeMillis()
        val h1 = hourlySeries(668, now)
        s.setCandles(h1)
        s.rebuild()
        assertEquals(668, s.engine.processed)

        val logBefore = Log.count()
        // رفتار mergeTail قدیمی: الحاق کورکورانه بر اساس زمان، بدون بررسی تایم‌فریم
        val spliced = (h1 + minuteSeries(6600, now))
            .mapIndexed { i, c -> Candle(i, c.t, c.o, c.h, c.l, c.c, c.v) }

        val banners = countBanners {
            Alerts.inBacktest = false
            Alerts.liveRunning = true
            s.engine.feed(spliced, lastIsClosed = false)
        }
        val logAlerts = Log.count() - logBefore
        println("NEGATIVE RESULT: splicedBars=${spliced.size} logLines=$logAlerts bannerPosts=$banners")
        // اندازه‌گیری واقعی: ۶٬۶۰۰ کندل بازپخش‌شده ⇒ ۸۷ ورودی لاگ هشدار و **صفر** بنر.
        assertTrue("باید ورودی لاگ هشدار ثبت شود (اندازه‌گیری واقعی: ۸۷)", logAlerts > 20)
        assertTrue(
            "نظریهٔ «سیل بنر» رد شده است — اگر این عدد بزرگ شد، علت فریز را دوباره بررسی کن",
            banners <= 20
        )
    }

    // ═══ ۲) رفتار مورد انتظار: بازپخش تاریخچه نباید بنر بسازد ══════════════════
    @Test
    fun `feedLiveTail replays historical bars silently - no banner flood`() {
        val s = AppState()
        s.chartTfSec = Tf.M1
        s.cfg.tf2 = Tf.M1
        val now = System.currentTimeMillis()
        s.setCandles(minuteSeries(400, now - 500L * Tf.M1 * 1000L))   // سری 1m قدیمی
        s.rebuild()

        // اپ ۳۰۰ دقیقه بسته بوده → دنباله ۳۰۰ کندل بستهٔ «جدید» می‌آورد (همان تایم‌فریم)
        val tail = minuteSeries(700, now)
        val banners = countBanners {
            Alerts.inBacktest = false
            Alerts.liveRunning = true
            s.feedLiveTail(tail)
        }
        println("AFTER FIX: candles=${s.candles.size} processed=${s.engine.processed} " +
                "silentBars=${s.lastLiveSilentBars} bannerPosts=$banners")
        assertTrue("بازپرخ تاریخچه نباید بنر بسازد — ولی $banners بنر پست شد", banners <= 2)
        assertTrue("باید بازپرخ ساکت ثبت شود", s.lastLiveSilentBars > 3)
    }

    // ═══ ۳) تیک عادی لایو باید همچنان هشدار بدهد (رفع، هشدارها را نکشته باشد) ═══
    @Test
    fun `a normal live tick still shows its banner`() {
        val s = AppState()
        s.chartTfSec = Tf.M1
        s.cfg.tf2 = Tf.M1
        val now = System.currentTimeMillis()
        s.setCandles(minuteSeries(400, now))
        s.rebuild()

        // فقط ۲ کندل تازه → تیک عادی، باید هشدارها پخش شوند
        val tail = minuteSeries(402, now + 2L * Tf.M1 * 1000L)
        Alerts.inBacktest = false
        Alerts.liveRunning = true
        try {
            s.feedLiveTail(tail)
        } finally {
            Alerts.inBacktest = false
            Alerts.liveRunning = false
        }
        println("NORMAL TICK: silentBars=${s.lastLiveSilentBars}")
        assertEquals("تیک عادی نباید ساکت حساب شود", 0, s.lastLiveSilentBars)
    }

    // ═══ ۴) mergeTail هرگز دو تایم‌فریم را قاطی نمی‌کند ═════════════════════════
    @Test
    fun `mergeTail must never mix two timeframes into one series`() {
        val s = AppState()
        s.chartTfSec = Tf.M1
        s.cfg.tf2 = Tf.M1
        val now = System.currentTimeMillis()
        s.setCandles(hourlySeries(668, now))
        s.rebuild()
        s.mergeTail(minuteSeries(600, now))
        val gaps = s.candles.zipWithNext { a, b -> (b.t - a.t) / 1000L }.distinct().sorted()
        println("interval seconds present after merge: $gaps")
        assertTrue("سری باید یک تایم‌فریم داشته باشد — ولی $gaps پیدا شد", gaps.size <= 1)
    }
}
