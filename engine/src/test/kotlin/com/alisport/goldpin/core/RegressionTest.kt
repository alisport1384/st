package com.alisport.goldpin.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * آزمون‌های بازگشتی برای باگ‌هایی که در نسخهٔ ۱٫۳ پیدا و رفع شدند.
 * هر آزمون روی کد قدیمی **شکست می‌خورد** و روی کد اصلاح‌شده پاس می‌شود.
 */
class RegressionTest {

    /** دادهٔ مصنوعی قطعی (همان random walk آزمون Store) */
    private fun synth(n: Int, seed: Int = 12345): List<Candle> {
        val r = Random(seed)
        val out = ArrayList<Candle>(n)
        var px = 2000.0
        var t = 1_700_000_000_000L
        for (i in 0 until n) {
            val drift = r.nextDouble(-0.9, 0.9)
            val o = px
            val c = px + drift
            val h = maxOf(o, c) + r.nextDouble(0.0, 0.6)
            val l = minOf(o, c) - r.nextDouble(0.0, 0.6)
            val v = 50.0 + r.nextDouble(0.0, 400.0) + if (i % 97 == 0) 900.0 else 0.0
            out.add(Candle(i, t, o, h, l, c, v))
            px = c
            t += 60_000
        }
        return out
    }

    private fun wire(cfg: Settings): Pair<Engine, PaperBroker> {
        val e = Engine(cfg)
        val b = PaperBroker(cfg)
        e.broker = b; b.engine = e
        e.barCommitHook = { cd -> b.onBar(cd, live = false) }
        return e to b
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ۱) پیش‌نمایش کندل باز نباید وضعیت موتور (به‌ویژه حجم تجمیع‌شده) را تغییر دهد
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun `repeated live preview of the open candle must not change engine state`() {
        val candles = synth(3_000)

        // مرجع: یک پاس تمیز که همهٔ کندل‌ها بسته‌اند
        val (eRef, bRef) = wire(Settings())
        eRef.feed(candles, lastIsClosed = true)

        // لایو: آخرین کندل ۲۰ بار «پیش‌نمایش» می‌شود (۲۰ تیک فید روی همان کندل باز)
        val (eLive, bLive) = wire(Settings())
        eLive.feed(candles.subList(0, candles.size - 1), lastIsClosed = true)
        repeat(20) { eLive.feed(candles, lastIsClosed = false) }
        eLive.feed(candles, lastIsClosed = true)

        val vRef = eRef.aggS.cur?.v ?: Double.NaN
        val vLive = eLive.aggS.cur?.v ?: Double.NaN
        assertEquals(vRef, vLive, 1e-9,
            "حجم تجمیع‌شدهٔ کندل ساختار با تیک‌های زنده باد شد (تمیز=$vRef لایو=$vLive)")

        assertEquals(eRef.aggM.cur?.v ?: -1.0, eLive.aggM.cur?.v ?: -1.0, 1e-9, "حجم کندل میانی")
        assertEquals(eRef.agg1.cur?.v ?: -1.0, eLive.agg1.cur?.v ?: -1.0, 1e-9, "حجم کندل تریگر۱")
        assertEquals(eRef.prevT1Vol, eLive.prevT1Vol, 1e-9,
            "prevT1Vol خراب شد → شرط «کندل ولوم کم» دیگر قابل اتکا نیست")

        assertEquals(eRef.cnt.setup, eLive.cnt.setup, "تعداد ستاپ")
        assertEquals(eRef.cnt.lv, eLive.cnt.lv, "تعداد ناحیهٔ ولوم کم")
        assertEquals(eRef.cnt.hv, eLive.cnt.hv, "تعداد ناحیهٔ ولوم زیاد")
        assertEquals(eRef.cnt.entry, eLive.cnt.entry, "تعداد ورود")
        assertEquals(bRef.trades.size, bLive.trades.size, "تعداد معاملات")
        assertEquals(bRef.balance, bLive.balance, 1e-6, "موجودی نهایی")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ۲) سقف «تعداد باکس روی چارت» نباید باکس‌های زنده را بی‌دلیل نابود کند
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun `zone box cap only hides the oldest visible boxes, never live ones early`() {
        val candles = synth(20_000)

        val (eCapped, _) = wire(Settings().apply { maxZoneBoxes = 24 })
        eCapped.feed(candles, lastIsClosed = true)

        // همان داده با سقف عملاً بی‌نهایت → هیچ باکسی نباید CAP شود
        val (eFree, _) = wire(Settings().apply { maxZoneBoxes = Int.MAX_VALUE })
        eFree.feed(candles, lastIsClosed = true)

        val capStat = eCapped.zones.groupingBy { it.status }.eachCount()
        println("REGRESSION zones total=${eCapped.zones.size} byStatus=$capStat")

        // الف) سقف فقط روی باکس‌های *نمایان* اعمال می‌شود
        val visible = eCapped.zones.count {
            it.status == ZoneStatus.ACTIVE || it.status == ZoneStatus.REJECTED
        }
        assertTrue(visible <= 24, "باکس نمایان بیشتر از سقف: $visible")
        // پیش از رفع باگ این عدد **صفر** بود: همهٔ ۳۳۷ باکس CAP/DELETED بودند و روی چارت
        // هیچ ناحیه‌ای دیده نمی‌شد.
        assertTrue(visible in 1..24,
            "تعداد باکس نمایان باید بین ۱ و سقف باشد (visible=$visible) — پیش از رفع باگ صفر بود")

        // ب) وضعیت باکس‌ها نباید به سقف وابسته باشد (CAP فقط یک برچسب نمایشی است)
        val freeStatus = eFree.zones.map { it.status }
        val cappedStatus = eCapped.zones.map {
            // باکس‌هایی که فقط به‌خاطر سقف CAP شده‌اند، در حالت آزاد هم همان وضعیت اصلی را دارند
            if (it.status == ZoneStatus.CAP) {
                eFree.zones.first { z -> z.id == it.id }.status
            } else it.status
        }
        assertContentEquals(freeStatus, cappedStatus, "وضعیت باکس‌ها با تغییر سقف عوض شد")

        // پ) هیچ باکس CAP نباید وجود داشته باشد وقتی سقف بی‌نهایت است
        assertEquals(0, eFree.zones.count { it.status == ZoneStatus.CAP },
            "با سقف بی‌نهایت هیچ باکسی نباید CAP شود")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ۳) سفارشی که به مهلت می‌خورد باید «منقضی» باشد، نه «لغو»
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun `order that times out is marked expired, not invalid`() {
        val cfg = Settings().apply { maxBarsToFill = 3 }
        val (_, b) = wire(cfg)
        val s = Setup(1L, 1, 6, 1950.0, 1850.0, 1, 0)
        s.hvH = 1850.0; s.hvL = 1840.0; s.sl = 1839.0
        s.tp1 = 1860.0; s.tp2 = 1870.0; s.tpx = 1880.0
        b.registerLimit(s, Candle(0, 1_700_000_000_000L, 1900.0, 1901.0, 1899.0, 1900.0, 100.0))

        // بازار هرگز به ۱۸۵۰ نمی‌رسد → سفارش باید منقضی شود
        for (i in 0 until 40) {
            b.onBar(Candle(i, 1_700_000_000_000L + i * 60_000, 1900.0, 1901.0, 1899.0, 1900.0, 100.0))
        }
        val o = b.orders.first()
        assertEquals(OrderStatus.CANCELLED_EXPIRED, o.status,
            "سفارش منقضی باید وضعیت CANCELLED_EXPIRED بگیرد تا تب سفارش‌ها «منقضی» نشان دهد")
        assertTrue(o.cancelReason?.contains("مهلت") == true, "دلیل انقضا ثبت نشده")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ۴) لغو دستی وضعیت مخصوص خودش را می‌گیرد
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun `manual cancel keeps its own status`() {
        val (_, b) = wire(Settings())
        val s = Setup(1L, 1, 6, 1950.0, 1850.0, 1, 0)
        s.hvH = 1850.0; s.hvL = 1840.0; s.sl = 1839.0
        s.tp1 = 1860.0; s.tp2 = 1870.0; s.tpx = 1880.0
        b.registerLimit(s, Candle(0, 1_700_000_000_000L, 1900.0, 1901.0, 1899.0, 1900.0, 100.0))
        b.cancelPendingManually(1_700_000_060_000L, 1)
        assertEquals(OrderStatus.CANCELLED_MANUAL, b.orders.first().status)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ۵) همهٔ تنظیمات (از جمله rejectCandleNext) از ذخیره/بازیابی سالم برمی‌گردند
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun `every setting survives the round trip`() {
        val cfg = Settings().apply { rejectCandleNext = true; vpRows = 30 }
        val (e, b) = wire(cfg)
        val json = Store.save(cfg, e, b, synth(500))
        val back = Store.load(json).cfg
        assertEquals(true, back.rejectCandleNext, "rejectCandleNext ذخیره نمی‌شد")
        assertEquals(30, back.vpRows)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ۶) فایل ذخیرهٔ بریده نباید اپ را با استثنا زمین بزند
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun `truncated json with a broken unicode escape does not throw`() {
        val broken = """{"a":"x\u12","b":[1,2],"c":"y\uZZ34"}"""
        val m = Json.parse(broken).asMap()
        assertEquals(2, m["b"].asList().size)
        // هیچ استثنایی پرتاب نشد = پاس
        assertTrue(m.containsKey("a"))
    }
}
