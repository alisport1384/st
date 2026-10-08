package com.alisport.goldpin.cli

import com.alisport.goldpin.core.*
import java.io.File

/**
 * هارنس خط فرمان: اجرای موتور روی فایل CSV واقعی (مثلاً دانلود دوکاس‌کپی)
 * نمونه:
 *   gradle :cli:run --args="--csv data/xauusd-m1.csv --vol real"
 */
fun main(args: Array<String>) {
    var csv = ""
    var vol = "auto"
    var rows = 24
    var smooth = 1
    var tfMode = 1
    var maxRows = Int.MAX_VALUE
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--csv" -> csv = args[++i]
            "--vol" -> vol = args[++i]
            "--rows" -> rows = args[++i].toInt()
            "--smooth" -> smooth = args[++i].toInt()
            "--mode" -> tfMode = args[++i].toInt()
            "--max" -> maxRows = args[++i].toInt()
        }
        i++
    }
    if (csv.isEmpty()) { println("--csv لازم است"); return }

    val lines = File(csv).readLines()
    val header = lines.first()
    val hasVolume = header.contains("volume", true)
    val candles = ArrayList<Candle>(lines.size)
    var bi = 0
    for (k in 1 until lines.size) {
        val p = lines[k].split(',')
        if (p.size < 5) continue
        val t = p[0].toLong()
        val o = p[1].toDouble(); val h = p[2].toDouble()
        val l = p[3].toDouble(); val c = p[4].toDouble()
        var v = if (hasVolume && p.size >= 6) p[5].toDouble() else 0.0
        if (vol == "synthetic" || (vol == "auto" && v <= 0.0)) {
            // حجم مصنوعی: دامنهٔ کندل (رگرسیون سرانگشتی وقتی فید حجم ندارد)
            v = (h - l) * 1000.0 + 1.0
        }
        candles.add(Candle(bi++, t, o, h, l, c, v))
        if (candles.size >= maxRows) break
    }
    println("کندل‌های بارگذاری‌شده: ${candles.size}   حجم واقعی: ${hasVolume && vol != "synthetic"}")

    val cfg = Settings().apply {
        this.vpRows = rows; this.vpSmooth = smooth; this.tfMode = tfMode
    }
    val t2 = TF_MODES.first { it.idx == tfMode }
    cfg.tfS = t2.s; cfg.tfM = t2.m; cfg.tf1 = t2.t1; cfg.tf2 = t2.t2

    val eng = Engine(cfg)
    val broker = PaperBroker(cfg)
    eng.broker = broker
    broker.engine = eng

    eng.barCommitHook = { cd -> broker.onBar(cd, live = false) }

    val t0 = System.currentTimeMillis()
    // یک پاس تمیز و خطی روی همهٔ کندل‌ها (هر کندل بسته) — سرعت خطی، بدون O(n²)
    var upto = 0
    val step = 20000
    while (upto < candles.size) {
        val next = minOf(upto + step, candles.size)
        eng.feed(candles.subList(0, next), lastIsClosed = false)
        // کندل‌های [0, next-1) بسته پردازش شدند؛ آخرین کندل هم اکنون «باز» مانده
        // با رسیدن کندل بعدی، خودِ موتور آن را بسته پردازش می‌کند
        upto = next
    }
    // کندل آخر: در بک‌تست آن را هم بسته حساب می‌کنیم
    eng.feed(candles, lastIsClosed = true)
    val t1 = System.currentTimeMillis()

    println("پردازش موتور: ${t1 - t0} ms")
    val s = Reports.summary(broker.trades)
    println("──────── نتیجهٔ بک‌تست ────────")
    println("سفارش‌ها: ${broker.orders.size} (پر شده ${broker.orders.count { it.status == OrderStatus.FILLED }})")
    println("معاملات: ${s.trades} | برد ${s.wins} | باخت ${s.losses} | نرخ برد ${"%.1f".format(s.winRate)}٪")
    println("سود خالص: ${"%.2f".format(s.net)} | ضریب سود ${"%.2f".format(s.profitFactor)} | میانگین R ${"%.2f".format(s.avgR)}")
    println("بیشترین افت سرمایه: ${"%.2f".format(s.maxDD)} | موجودی نهایی: ${"%.2f".format(broker.balance)}")
    println("آمار موتور: ستاپ ${eng.cnt.setup} · برخورد ${eng.cnt.touch} · میانی ${eng.cnt.mid} · ولوم‌کم ${eng.cnt.lv} · ولوم‌زیاد ${eng.cnt.hv} · ورود ${eng.cnt.entry} · پایان ${eng.cnt.done}")
    println("باکس ناحیه: ${eng.zones.size} (فعال ${eng.zones.count { it.status == ZoneStatus.ACTIVE }}, ردشده ${eng.zones.count { it.status == ZoneStatus.REJECTED }}, پاک‌شده ${eng.zones.count { it.status == ZoneStatus.DELETED }})")
    println("──────── ۸ معاملهٔ آخر ────────")
    for (t in broker.trades.takeLast(8)) {
        println("#${t.id} dir=${if (t.dir == 1) "BUY " else "SELL"} entry=${"%.2f".format(t.entry)} sl=${"%.2f".format(t.sl0)} tp=${"%.2f".format(t.tpX)} " +
                "exit=${t.exitPx?.let { "%.2f".format(it) } ?: "-"} qty=${"%.3f".format(t.qty)} pnl=${"%.2f".format(t.pnl())} R=${"%.2f".format(t.rMultiple())} ${t.reason}")
    }
}
