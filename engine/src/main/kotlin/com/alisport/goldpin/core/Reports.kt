package com.alisport.goldpin.core

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.min

// ═══════════════════════════════════════════════════════════════════════════════
//  گزارش‌های دوره‌ای (روزانه / هفتگی / ماهانه / سالانه) + منحنی سرمایه
//  از java.util.Calendar استفاده می‌شود تا روی اندروید ۷ به بالا (minSdk 24) هم کار کند.
// ═══════════════════════════════════════════════════════════════════════════════
object Reports {

    const val DAILY = 0
    const val WEEKLY = 1
    const val MONTHLY = 2
    const val YEARLY = 3

    class Row {
        var key = ""
        var label = ""
        var trades = 0
        var wins = 0
        var losses = 0
        var net = 0.0
        var grossWin = 0.0
        var grossLoss = 0.0
        var rSum = 0.0
        var best = Double.NaN
        var worst = Double.NaN
        var maxDD = 0.0
        val winRate: Double get() = if (trades > 0) wins * 100.0 / trades else 0.0
        val avgR: Double get() = if (trades > 0) rSum / trades else 0.0
        val profitFactor: Double get() = if (grossLoss > 0) grossWin / grossLoss else if (grossWin > 0) 99.0 else 0.0
    }

    private fun cal(ms: Long, tz: TimeZone = TimeZone.getDefault()): Calendar =
        Calendar.getInstance(tz).apply { timeInMillis = ms }

    private val UTC = TimeZone.getTimeZone("UTC")

    /**
     * شروع هفته (پنجشنبه ۰۰:۰۰ UTC).
     *
     * انتخاب شد تا با مبدأ کندل‌های تایم‌فریم هفتگی (W1) هماهنگ باشد — در
     * بازار XAUUSD هفته از باز شدن سیدنی/توکیو در بامداد دوشنبه شروع می‌شود،
     * اما TradingView/ابزارهای نموداری کندل هفتگی را از پنجشنبه UTC می‌سازند
     * (سازگاری epoch 1970-01-01 که پنجشنبه بود). برای اینکه گزارش‌های دوره‌ای
     * و کندل‌های هفتگی روی هم بیفتند، هر دو پنجشنبه ۰۰:۰۰ UTC هستند.
     */
    fun weekStart(ms: Long, tz: TimeZone = UTC): Long {
        val c = cal(ms, UTC)
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        val dow = c.get(Calendar.DAY_OF_WEEK)                 // 1=یکشنبه … 5=پنجشنبه … 7=شنبه
        // تعداد روزها که باید به عقب برویم تا به آخرین پنجشنبه برسیم.
        val back = (dow - Calendar.THURSDAY + 7) % 7
        if (back > 0) c.add(Calendar.DAY_OF_MONTH, -back)
        return c.timeInMillis
    }

    fun dayStart(ms: Long, tz: TimeZone = TimeZone.getDefault()): Long {
        val c = cal(ms, tz)
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    fun monthStart(ms: Long, tz: TimeZone = TimeZone.getDefault()): Long {
        val c = cal(ms, tz)
        c.set(Calendar.DAY_OF_MONTH, 1)
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    fun yearStart(ms: Long, tz: TimeZone = TimeZone.getDefault()): Long {
        val c = cal(ms, tz)
        c.set(Calendar.DAY_OF_YEAR, 1)
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    fun bucketStart(kind: Int, ms: Long, tz: TimeZone = TimeZone.getDefault()): Long = when (kind) {
        DAILY -> dayStart(ms, tz)
        WEEKLY -> weekStart(ms, tz)
        MONTHLY -> monthStart(ms, tz)
        else -> yearStart(ms, tz)
    }

    /** برچسب فارسی خوانا برای شروع یک دوره. */
    fun label(kind: Int, startMs: Long, tz: TimeZone = TimeZone.getDefault()): String {
        val c = cal(startMs, tz)
        val y = c.get(Calendar.YEAR)
        val m = c.get(Calendar.MONTH) + 1
        val d = c.get(Calendar.DAY_OF_MONTH)
        return when (kind) {
            DAILY -> String.format("%04d-%02d-%02d", y, m, d)
            WEEKLY -> String.format("هفتهٔ %04d-%02d-%02d", y, m, d)
            MONTHLY -> String.format("%04d-%02d", y, m)
            else -> "$y"
        }
    }

    /** گروه‌بندی معاملات بسته‌شده در دوره‌ها؛ دوره‌ها نزولی (جدیدترین اول). */
    fun group(trades: List<Trade>, kind: Int, tz: TimeZone = TimeZone.getDefault()): List<Row> {
        val map = LinkedHashMap<Long, Row>()
        val closed = trades.filter { !it.open && it.exitT != null }.sortedBy { it.exitT }
        var peak = 0.0
        var cum = 0.0
        for (t in closed) {
            val start = bucketStart(kind, t.exitT!!, tz)
            val row = map.getOrPut(start) { Row().also { it.key = start.toString(); it.label = label(kind, start, tz) } }
            val pnl = t.pnl()
            row.trades++
            if (pnl >= 0) { row.wins++; row.grossWin += pnl } else { row.losses++; row.grossLoss += -pnl }
            row.net += pnl
            row.rSum += t.rMultiple()
            row.best = if (row.best.isNaN()) pnl else max(row.best, pnl)
            row.worst = if (row.worst.isNaN()) pnl else min(row.worst, pnl)
            cum += pnl
            peak = max(peak, cum)
            row.maxDD = max(row.maxDD, peak - cum)
        }
        return map.values.sortedByDescending { it.key.toLong() }
    }

    /** نقاط منحنی سرمایه: (زمان، موجودی انباشته) — یک نقطه به ازای هر معاملهٔ بسته. */
    fun equityCurve(trades: List<Trade>, initial: Double): List<Pair<Long, Double>> {
        val out = ArrayList<Pair<Long, Double>>()
        var cum = initial
        out.add(0L to initial)
        for (t in trades.filter { !it.open && it.exitT != null }.sortedBy { it.exitT }) {
            cum += t.pnl()
            out.add(t.exitT!! to cum)
        }
        return out
    }

    /** خلاصهٔ کل. */
    class Summary {
        var trades = 0; var wins = 0; var losses = 0
        var net = 0.0; var grossWin = 0.0; var grossLoss = 0.0
        var rSum = 0.0; var maxDD = 0.0; var best = Double.NaN; var worst = Double.NaN
        var avgWin = 0.0; var avgLoss = 0.0
        val winRate get() = if (trades > 0) wins * 100.0 / trades else 0.0
        val avgR get() = if (trades > 0) rSum / trades else 0.0
        val profitFactor get() = if (grossLoss > 0) grossWin / grossLoss else if (grossWin > 0) 99.0 else 0.0
    }

    fun summary(trades: List<Trade>): Summary {
        val s = Summary()
        val closed = trades.filter { !it.open }.sortedBy { it.exitT ?: 0 }
        var peak = 0.0; var cum = 0.0
        for (t in closed) {
            val pnl = t.pnl()
            s.trades++
            if (pnl >= 0) { s.wins++; s.grossWin += pnl } else { s.losses++; s.grossLoss += -pnl }
            s.net += pnl; s.rSum += t.rMultiple()
            s.best = if (s.best.isNaN()) pnl else max(s.best, pnl)
            s.worst = if (s.worst.isNaN()) pnl else min(s.worst, pnl)
            cum += pnl; peak = max(peak, cum); s.maxDD = max(s.maxDD, peak - cum)
        }
        s.avgWin = if (s.wins > 0) s.grossWin / s.wins else 0.0
        s.avgLoss = if (s.losses > 0) s.grossLoss / s.losses else 0.0
        return s
    }
}
