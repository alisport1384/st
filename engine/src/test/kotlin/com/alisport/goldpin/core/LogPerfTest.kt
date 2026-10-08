package com.alisport.goldpin.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.io.File

/**
 * سنجش هزینهٔ قلاب لاگ/هشدار روی بک‌تست کامل.
 * اگر فایل CSV در دسترس نباشد، تست رد (skip) می‌شود.
 */
class LogPerfTest {

    private fun csv(): File? {
        val cand = listOf(
            File("/home/user/stdata/xauusd-m1-bid-2026-04-01-2026-10-06.csv"),
            File("data/xauusd-m1.csv"),
            File(System.getenv("GOLDPIN_CSV") ?: "-")
        )
        return cand.firstOrNull { it.exists() }
    }

    private fun parse(f: File): List<Candle> {
        val out = ArrayList<Candle>()
        var i = 0
        f.forEachLine { ln ->
            val p = ln.split(',', ';', '\t')
            if (p.size >= 6) {
                val t = p[0].trim().toLongOrNull() ?: p[1].trim().toLongOrNull() ?: return@forEachLine
                val o = p[1].trim().toDoubleOrNull() ?: return@forEachLine
                val h = p[2].trim().toDoubleOrNull() ?: return@forEachLine
                val l = p[3].trim().toDoubleOrNull() ?: return@forEachLine
                val c = p[4].trim().toDoubleOrNull() ?: return@forEachLine
                val v = p[5].trim().toDoubleOrNull() ?: 0.0
                out.add(Candle(i++, t, o, h, l, c, v))
            }
        }
        return out
    }

    private fun runOne(candles: List<Candle>, logSink: ((String, String, String?) -> Unit)?, alertSink: ((String, String, String) -> Unit)?): Triple<Long, Int, Int> {
        val st = Settings()
        val e = Engine(st)
        val b = PaperBroker(st)
        e.broker = b; b.engine = e
        e.barCommitHook = { cd -> b.onBar(cd, live = false) }
        e.logSink = logSink
        e.alertSink = alertSink
        b.logSink = logSink
        b.alertSink = alertSink
        val t0 = System.nanoTime()
        e.feed(candles, lastIsClosed = true)
        val ms = (System.nanoTime() - t0) / 1_000_000
        return Triple(ms, b.orders.size, b.trades.size)
    }

    @Test
    fun `logger hook cost on full backtest`() {
        val f = csv()
        if (f == null || !f.exists()) { println("PERF فایل CSV موجود نیست → رد شد"); return }
        val c = parse(f)
        if (c.size <= 10_000) { println("PERF کندل کافی نیست → رد شد"); return }

        // گرم‌کردن JVM (یک اجرای کامل + یک اجرای کوتاه)
        runOne(c, null, null)
        runOne(c.take(20_000), null, null)

        var lg = 0
        var al = 0
        val a = ArrayList<Long>()
        val b = ArrayList<Long>()
        var o0 = 0; var t0 = 0; var o1 = 0; var t1 = 0
        repeat(2) {
            val (ma, oa, ta) = runOne(c, null, null); a.add(ma); o0 = oa; t0 = ta
            val (mb, ob, tb) = runOne(c, { _, _, _ -> lg++ }, { _, _, _ -> al++ }); b.add(mb); o1 = ob; t1 = tb
        }
        val m0 = a.min(); val m1 = b.min()
        println("PERF کندل=${c.size} بدون‌سینک=${m0}ms  با‌سینک=${m1}ms  لاگ‌ها=$lg هشدارها=$al  سفارش=$o0/$o1 معامله=$t0/$t1")
        println("PERF سرباره=${m1 - m0}ms  نسبت=%.2f".format(if (m0 > 0) m1.toDouble() / m0 else 0.0))

        // نتیجهٔ بک‌تست با و بدون سینک باید یکسان باشد (سینک‌ها فقط ناظرند، نه تصمیم‌ساز)
        assertEquals(o0, o1, "تعداد سفارش با/بدون لاگر باید یکسان باشد")
        assertEquals(t0, t1, "تعداد معامله با/بدون لاگر باید یکسان باشد")
        assertTrue(lg > 100, "لاگر باید خط تولید کند")
    }
}
