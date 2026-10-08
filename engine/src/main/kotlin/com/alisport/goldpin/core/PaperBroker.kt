package com.alisport.goldpin.core

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// ═══════════════════════════════════════════════════════════════════════════════
//  کارگزار کاغذی (Paper) — معادل ⑮ در Pine
//  ثبت سفارش لیمیت روی لبهٔ ناحیهٔ ولوم زیاد ، پر شدن ، خروج پله‌ای ۳۳/۳۳/۳۴ ،
//  سر‌به‌سر بعد از TP1 ، انقضای سفارش ، و محاسبهٔ سود/زیان به دلار و R.
// ═══════════════════════════════════════════════════════════════════════════════
class PaperBroker(private val cfg: Settings) {

    val orders = ArrayList<Order>()
    val trades = ArrayList<Trade>()

    var balance = cfg.initialEquity          // موجودی تحقق‌یافته
    var equity = cfg.initialEquity           // موجودی + سود/زیان باز
    var maxEquity = cfg.initialEquity
    var maxDrawdown = 0.0
    var orderSeq = 0L
    var tradeSeq = 0L
    var lastError: String? = null
    var engine: Engine? = null

    /** قلاب‌های لاگر و هشدار (اپ وصل می‌کند) */
    var logSink: ((String, String, String?) -> Unit)? = null
    var alertSink: ((String, String, String) -> Unit)? = null

    private fun lg(cat: String, msg: String, data: String? = null) { logSink?.invoke(cat, msg, data) }

    private fun fmt(v: Double): String = if (v.isNaN()) "-" else String.format("%.2f", v)
    private fun alarm(kind: String, title: String, body: String) { alertSink?.invoke(kind, title, body) }

    private var pending: Order? = null
    private var open: Trade? = null

    val hasOpenTrade: Boolean get() = open != null
    val hasPending: Boolean get() = pending != null
    val openTrade: Trade? get() = open
    val pendingOrder: Order? get() = pending

    fun reset() {
        orders.clear(); trades.clear()
        balance = cfg.initialEquity; equity = cfg.initialEquity
        maxEquity = cfg.initialEquity; maxDrawdown = 0.0
        pending = null; open = null; lastError = null
    }

    /** حجم بر اساس ریسک یا درصد سرمایه — معادل f_qty */
    fun qtyFor(px: Double, sl: Double): Double {
        if (px <= 0) return 0.0
        val eq = if (hasOpenTrade) equity else balance
        val risk = abs(px - sl)
        var q = if (cfg.useRiskPct) {
            if (risk > 0) eq * cfg.riskPct / 100.0 / risk else 0.0
        } else {
            eq * cfg.equityPct / 100.0 / px
        }
        val capQty = eq * cfg.maxLeverage / px
        q = min(q, capQty)
        if (cfg.roundQty) q = floor(q)
        return max(q, 0.0)
    }

    /** ثبت سفارش ورود (در لحظهٔ مسلح شدن). مثل Pine: فقط اگر پوزیشن و سفارش بازی نباشد. */
    fun registerLimit(s: Setup, bar: Candle) {
        if (pending != null || open != null) {
            lastError = "سفارش جدید ثبت نشد (پوزیشن/سفارش باز)"
            return
        }
        val entryPx = if (s.bull) s.hvH else s.hvL
        val qty = qtyFor(entryPx, s.sl)
        if (qty <= 0) {
            lastError = "حجم صفر بود"
            return
        }
        val o = Order(
            id = orderSeq++, setupId = s.id, dir = s.dir, type = "LIMIT",
            price = entryPx, sl = s.sl, tp1 = s.tp1, tp2 = s.tp2, tpX = s.tpx,
            qty = qty, zoneTop = s.zTop, zoneBot = s.zBot,
            placedT = bar.t, placedBi = bar.bi
        )
        s.orderId = o.id
        orders.add(o)
        pending = o
        lg("ORDER", "سفارش ورود ثبت شد (آماده) · ستاپ #${s.id}",
            "type=${if (s.dir == 1) "BUY_LIMIT" else "SELL_LIMIT"} price=${fmt(entryPx)} sl=${fmt(s.sl)} tp1=${fmt(s.tp1)} tp2=${fmt(s.tp2)} tp=${fmt(s.tpx)} qty=${fmt(qty)} bar=${bar.bi}")
        alarm(AlertKind.ARMED, if (s.dir == 1) "سفارش خرید آماده شد" else "سفارش فروش آماده شد",
            "ستاپ #${s.id} · ورود ${fmt(entryPx)} · حدضرر ${fmt(s.sl)} · حدسود ${fmt(s.tpx)} · حجم ${fmt(qty)}")
    }

    fun cancel(order: Order, reason: String, t: Long, bi: Int) {
        lg("ORDER", "سفارش لغو شد · #${order.id}", "reason=$reason status=${order.status}")
        order.status = OrderStatus.CANCELLED_INVALID
        order.cancelReason = reason
        order.closedT = t
        if (pending?.id == order.id) pending = null
        if (open?.orderId == order.id && open?.open == true) {
            // بستن اجباری پوزیشن باز
            closeAll(open!!, order.price, t, bi, "خروج همگام (ابطال ستاپ)")
        }
    }

    fun onSetupInvalidated(s: Setup) {
        val o = orders.firstOrNull { it.id == s.orderId } ?: return
        if (o.status == OrderStatus.PENDING) cancel(o, "ستاپ باطل شد", o.placedT, o.placedBi)
        else if (o.status == OrderStatus.FILLED && open?.orderId == o.id && open?.open == true) {
            closeAll(open!!, o.price, o.placedT, o.placedBi, "خروج همگام (ابطال ستاپ)")
        }
    }

    /** بازگردانی سفارش معلق از فایل ذخیره‌شده */
    fun restorePending(o: Order?) { pending = o }

    /** بازگردانی پوزیشن باز از فایل ذخیره‌شده */
    fun restoreOpen(t: Trade?) { open = t }

    fun cancelPendingManually(t: Long, bi: Int) {
        pending?.let { cancel(it, "لغو دستی توسط کاربر", t, bi) }
    }

    /** پردازش کندل: پر شدن سفارش + مدیریت پوزیشن. */
    fun onBar(cd: Candle, live: Boolean = false) {
        // ① پر شدن سفارش لیمیت
        val p = pending
        if (p != null) {
            val touched = if (p.dir == 1) cd.l <= p.price else cd.h >= p.price
            val barsWaiting = cd.bi - p.placedBi
            if (touched) {
                fillEntry(p, cd)
            } else if (barsWaiting > cfg.maxBarsToFill) {
                cancel(p, "مهلت پر شدن تمام شد ($barsWaiting کندل)", cd.t, cd.bi)
                alarm(AlertKind.CANCEL, "سفارش ورود منقضی شد",
                    "پس از $barsWaiting کندل پر نشد · قیمت ${fmt(p.price)}")
            }
        }
        // ② مدیریت پوزیشن باز
        val t = open
        if (t != null && t.open) manageBar(t, cd)
        // ③ به‌روزرسانی سود/زیان شناور
        refreshEquity(cd)
    }

    private fun fillEntry(o: Order, cd: Candle) {
        o.status = OrderStatus.FILLED
        o.filledT = cd.t
        o.filledBi = cd.bi
        pending = null
        val tr = Trade(
            id = tradeSeq++, orderId = o.id, setupId = o.setupId, dir = o.dir,
            entryT = cd.t, entryBi = cd.bi, entry = o.price,
            sl0 = o.sl, tp1 = o.tp1, tp2 = o.tp2, tpX = o.tpX, qty = o.qty,
            zoneTop = o.zoneTop, zoneBot = o.zoneBot, contractSize = cfg.contractSize
        )
        tr.fills.add(Fill(cd.t, cd.bi, o.price, o.qty, "ENTRY", 0.0))
        trades.add(tr)
        open = tr
        lg("TRADE", "سفارش پر شد → ورود انجام شد · معامله #${tr.id}",
            "dir=${if (tr.dir == 1) "BUY" else "SELL"} entry=${fmt(tr.entry)} sl=${fmt(tr.sl0)} tp=${fmt(tr.tpX)} qty=${fmt(tr.qty)} bar=${cd.bi}")
        alarm(AlertKind.FILLED, if (tr.dir == 1) "ورود خرید انجام شد" else "ورود فروش انجام شد",
            "معامله #${tr.id} · ورود ${fmt(tr.entry)} · حدضرر ${fmt(tr.sl0)} · حدسود ${fmt(tr.tpX)}")
    }

    private fun manageBar(t: Trade, cd: Candle) {
        val bull = t.dir == 1
        val slLvl = if (t.be) t.entry else t.sl0
        val q1 = t.qty * 0.33
        val q2 = t.qty * 0.33
        val usedQty = t.fills.filter { it.kind != "ENTRY" }.sumOf { it.qty }
        val remaining = t.qty - usedQty

        // ترتیب محافظه‌کارانه: ابتدا حدضرر/سر‌به‌سر ، بعد حدسود نهایی ، بعد پله‌ها
        val hitStop = if (bull) cd.l <= slLvl else cd.h >= slLvl
        if (hitStop) {
            closeAll(t, slLvl, cd.t, cd.bi, if (t.be) "خروج سر‌به‌سر" else "حد ضرر")
            return
        }
        val hitFinal = if (bull) cd.h >= t.tpX else cd.l <= t.tpX
        if (hitFinal) {
            closeAll(t, t.tpX, cd.t, cd.bi, "حد سود نهایی (1.272)")
            return
        }
        val tp1Done = t.fills.any { it.kind == "TP1" }
        if (!tp1Done && !t.tp1.isNaN()) {
            val hit = if (bull) cd.h >= t.tp1 else cd.l <= t.tp1
            if (hit) {
                addExit(t, cd, t.tp1, min(q1, remaining), "TP1")
                t.be = true                    // حدضرر به سر‌به‌سر منتقل می‌شود
            }
        }
        val remainingNow = t.qty - t.fills.filter { it.kind != "ENTRY" }.sumOf { it.qty }
        val tp2Done = t.fills.any { it.kind == "TP2" }
        if (t.open && !tp2Done && !t.tp2.isNaN() && remainingNow > 1e-9) {
            val hit = if (bull) cd.h >= t.tp2 else cd.l <= t.tp2
            if (hit) addExit(t, cd, t.tp2, min(q2, remainingNow), "TP2")
        }
    }

    private fun addExit(t: Trade, cd: Candle, px: Double, qty: Double, kind: String) {
        val q = if (qty <= 0) t.qty - t.fills.filter { it.kind != "ENTRY" }.sumOf { it.qty } else qty
        if (q <= 0) return
        val pnl = if (t.dir == 1) (px - t.entry) * q * t.contractSize else (t.entry - px) * q * t.contractSize
        t.fills.add(Fill(cd.t, cd.bi, px, q, kind, pnl))
        balance += pnl
        maxEquity = max(maxEquity, balance)
        val dd = maxEquity - balance
        if (dd > maxDrawdown) maxDrawdown = dd
        lg("TRADE", "پلهٔ خروج $kind اجرا شد · معامله #${t.id}",
            "px=${fmt(px)} qty=${fmt(q)} pnl=${fmt(pnl)} موجودی=${fmt(balance)}")
        val kindAlert = when (kind) {
            "TP1" -> AlertKind.TP1; "TP2" -> AlertKind.TP2; "TPX" -> AlertKind.TPX
            "SL" -> AlertKind.SL; "BE" -> AlertKind.BE; else -> AlertKind.CLOSE
        }
        alarm(kindAlert, "خروج $kind", "معامله #${t.id} · قیمت ${fmt(px)} · حجم ${fmt(q)} · سود/زیان ${fmt(pnl)} دلار")
    }

    private fun closeAll(t: Trade, px: Double, time: Long, bi: Int, reason: String) {
        val used = t.fills.filter { it.kind != "ENTRY" }.sumOf { it.qty }
        val remaining = t.qty - used
        val kind = when {
            reason.contains("سر‌به‌سر") -> "BE"
            reason.startsWith("حد ضرر") -> "SL"
            reason.contains("1.272") -> "TPX"
            else -> "CLOSE"
        }
        if (remaining > 1e-9) {
            val pnl = if (t.dir == 1) (px - t.entry) * remaining * t.contractSize else (t.entry - px) * remaining * t.contractSize
            t.fills.add(Fill(time, bi, px, remaining, kind, pnl))
            balance += pnl
            maxEquity = max(maxEquity, balance)
            val dd = maxEquity - balance
            if (dd > maxDrawdown) maxDrawdown = dd
        }
        t.exitT = time; t.exitBi = bi; t.exitPx = px
        t.reason = reason
        open = null
        lg("TRADE", "معامله #${t.id} بسته شد", "reason=$reason exit=${fmt(px)} pnl=${fmt(t.pnl())} R=${fmt(t.rMultiple())} موجودی=${fmt(balance)}")
        val pnl = t.pnl()
        alarm(AlertKind.CLOSE, "معامله بسته شد · ${t.reason}",
            "معامله #${t.id} · خروج ${fmt(px)} · سود/زیان ${fmt(pnl)} دلار · ${fmt(t.rMultiple())}R")
    }

    /** به‌روزرسانی موجودی و سود/زیان شناور با قیمت جاری. */
    fun refreshEquity(cd: Candle) {
        val t = open
        equity = if (t != null && t.open) {
            val used = t.fills.filter { it.kind != "ENTRY" }.sumOf { it.qty }
            val rem = t.qty - used
            val unreal = if (t.dir == 1) (cd.c - t.entry) * rem * t.contractSize
            else (t.entry - cd.c) * rem * t.contractSize
            balance + unreal
        } else balance
        maxEquity = max(maxEquity, equity)
        val dd = maxEquity - equity
        if (dd > maxDrawdown) maxDrawdown = dd
    }

    fun openTradePnl(): Double {
        val t = open ?: return 0.0
        return t.fills.sumOf { it.pnl }
    }

    fun closedTrades(): List<Trade> = trades.filter { !it.open }
}
