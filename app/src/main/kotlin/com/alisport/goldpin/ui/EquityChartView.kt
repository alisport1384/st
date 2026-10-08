package com.alisport.goldpin.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.alisport.goldpin.core.Reports
import com.alisport.goldpin.core.Trade
import com.alisport.goldpin.util.Fa
import com.alisport.goldpin.util.Palette
import com.alisport.goldpin.util.Ui
import kotlin.math.max

// ═══════════════════════════════════════════════════════════════════════════════
//  نمودار سود و زیان (منحنی سرمایه) با تاریخ روی محور افقی
//  • زوم دو‌انگشتی و کشیدن   • نقطه‌های معامله (سبز/سرخ)   • لمس = جزئیات معامله
// ═══════════════════════════════════════════════════════════════════════════════
class EquityChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var trades: List<Trade> = emptyList()
    private var initial = 10000.0
    private var pts: List<Pair<Long, Double>> = emptyList()
    /**
     * معاملات بسته، **به همان ترتیبی که Reports.equityCurve نقطه می‌سازد** (مرتب بر اساس exitT).
     * پیش‌تر ایندکس نقطه مستقیم به فهرست `trades` (ترتیب ساخت) نگاشت می‌شد؛ هر جا ترتیب ساخت
     * با ترتیب خروج فرق می‌کرد، لمس یک نقطه جزئیات معاملهٔ اشتباهی را باز می‌کرد.
     */
    private var ordered: List<Trade> = emptyList()

    var onPick: ((Trade) -> Unit)? = null

    /** تعداد نقاط قابل نمایش (زوم) */
    private var visibleFrom = 0
    private var visibleTo = 0

    private val pLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = Ui.dp(context, 1.8f).toFloat()
        color = Palette.accent
    }
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1E4C8DFF") }
    private val pGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1E2530"); strokeWidth = 1f }
    private val pTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.dim; textSize = Ui.dp(context, 10f).toFloat()
    }
    private val pDot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pBase = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.grey; strokeWidth = 1f
        pathEffect = DashPathEffect(floatArrayOf(5f, 5f), 0f)
    }
    private val path = Path()
    private val lastGestureX = FloatArray(2)
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var scale = 1f
    private var pan = 0f

    fun setData(trades: List<Trade>, initialEquity: Double) {
        this.trades = trades
        this.initial = initialEquity
        this.ordered = trades.filter { !it.open && it.exitT != null }.sortedBy { it.exitT }
        pts = Reports.equityCurve(trades, initialEquity)
        visibleFrom = 0
        visibleTo = max(0, pts.size - 1)
        invalidate()
    }

    fun resetZoom() {
        scale = 1f; pan = 0f
        visibleFrom = 0; visibleTo = max(0, pts.size - 1)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; moved = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    val d = event.getX(0) - event.getX(1)
                    val prev = lastGestureX[0] - lastGestureX[1]
                    if (prev != 0f) scale = (scale * (d / prev)).coerceIn(1f, 40f)
                    lastGestureX[0] = event.getX(0); lastGestureX[1] = event.getX(1)
                    applyZoom()
                    moved = true
                } else {
                    val dx = event.x - downX
                    if (kotlin.math.abs(dx) > Ui.dp(context, 6f)) {
                        moved = true
                        pan = (pan + dx * 0.001f).coerceIn(0f, 1f)
                        applyZoom()
                        downX = event.x
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!moved) {
                    pickAt(event.x)
                }
            }
        }
        return true
    }

    private fun applyZoom() {
        val total = pts.size
        if (total <= 2) { visibleFrom = 0; visibleTo = total - 1; invalidate(); return }
        val span = max(2, (total / scale).toInt())
        val maxStart = max(0, total - span)
        val start = (maxStart * pan).toInt().coerceIn(0, maxStart)
        visibleFrom = start
        visibleTo = minOf(total - 1, start + span - 1)
        invalidate()
    }

    private fun pickAt(x: Float) {
        if (pts.isEmpty()) return
        val w = width - axisW()
        if (x > w) return
        val n = visibleTo - visibleFrom
        if (n <= 0) return
        val idx = visibleFrom + ((x / w) * n).toInt().coerceIn(0, n)
        // نقطهٔ ۰ = شروع؛ معاملهٔ i برابر نقطهٔ i+1
        val tradeIdx = idx - 1
        ordered.getOrNull(tradeIdx)?.let { onPick?.invoke(it) }
    }

    private fun axisW(): Float = Ui.dp(context, 58f).toFloat()

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Palette.bg)
        if (pts.size < 2) {
            pTxt.textSize = Ui.dp(context, 12f).toFloat()
            canvas.drawText("هنوز معامله‌ای بسته نشده است", Ui.dp(context, 16f).toFloat(), height / 2f, pTxt)
            pTxt.textSize = Ui.dp(context, 10f).toFloat()
            return
        }
        val w = (width - axisW()).coerceAtLeast(1f)
        val h = height.toFloat()
        val n = visibleTo - visibleFrom
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (i in visibleFrom..visibleTo) {
            val v = pts[i].second
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        if (lo == Double.MAX_VALUE) { lo = 0.0; hi = 1.0 }
        val pad = max((hi - lo) * 0.12, initial * 0.005)
        lo -= pad; hi += pad
        val range = (hi - lo).coerceAtLeast(1e-9)

        fun xAt(i: Int): Float = if (n == 0) 0f else (i - visibleFrom) * w / n
        fun yAt(v: Double): Float = (h * (hi - v) / range).toFloat()

        // ── شبکه و محور قیمت/سرمایه ──
        for (k in 0..5) {
            val v = lo + range * k / 5.0
            val y = yAt(v)
            canvas.drawLine(0f, y, w, y, pGrid)
            canvas.drawText(Fa.n(v, 0), w + Ui.dp(context, 4f), y + Ui.dp(context, 3.5f), pTxt)
        }
        // خط شروع
        val y0 = yAt(initial)
        canvas.drawLine(0f, y0, w, y0, pBase)

        // ── ناحیهٔ زیر منحنی ──
        path.reset()
        path.moveTo(xAt(visibleFrom), yAt(pts[visibleFrom].second))
        for (i in visibleFrom..visibleTo) path.lineTo(xAt(i), yAt(pts[i].second))
        path.lineTo(xAt(visibleTo), h)
        path.lineTo(xAt(visibleFrom), h)
        path.close()
        canvas.drawPath(path, pFill)

        // ── خط منحنی ──
        path.reset()
        path.moveTo(xAt(visibleFrom), yAt(pts[visibleFrom].second))
        for (i in visibleFrom..visibleTo) path.lineTo(xAt(i), yAt(pts[i].second))
        canvas.drawPath(path, pLine)

        // ── نقاط معاملات ──
        if (n < 400) {
            for (i in visibleFrom..visibleTo) {
                val v = pts[i].second
                val prevV = if (i == 0) initial else pts[i - 1].second
                pDot.color = if (v >= prevV) Palette.up else Palette.down
                canvas.drawCircle(xAt(i), yAt(v), Ui.dp(context, 2.4f).toFloat(), pDot)
            }
        }

        // ── محور تاریخ ──
        val steps = 4
        for (k in 0..steps) {
            val i = visibleFrom + (n * k / steps)
            if (i > pts.size - 1) continue
            val t = pts[i].first
            val label = if (t == 0L) "شروع" else Fa.jalaliShort(t)
            val x = xAt(i)
            canvas.drawLine(x, 0f, x, h, pGrid)
            val tw = pTxt.measureText(label)
            canvas.drawText(label, (x - tw / 2).coerceIn(0f, w - tw), h - Ui.dp(context, 4f), pTxt)
        }
    }
}
