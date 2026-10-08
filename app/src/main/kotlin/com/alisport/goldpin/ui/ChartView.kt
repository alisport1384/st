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
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.alisport.goldpin.core.Candle
import com.alisport.goldpin.core.Marker
import com.alisport.goldpin.core.Trade
import com.alisport.goldpin.core.ZoneBox
import com.alisport.goldpin.core.ZoneStatus
import com.alisport.goldpin.util.Fa
import com.alisport.goldpin.util.Palette
import com.alisport.goldpin.util.Ui
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// ═══════════════════════════════════════════════════════════════════════════════
//  چارت کندل — رسم سفارشی با Canvas
//  • زوم دو‌انگشتی (فشرده/باز کردن کندل‌ها)   • کشیدن افقی و عمودی
//  • مقیاس قیمت با دو انگشت عمودی            • کراس‌هیر با نگه‌داشتن انگشت
//  • باکس‌های ناحیه (زنده/پاک‌شده/ردشده) · برچسب‌های استراتژی · خطوط معامله
// ═══════════════════════════════════════════════════════════════════════════════

/** سطوح یک ستاپ برای نمایش روی چارت (باکس ولوم کم/زیاد و ناحیهٔ ساختار) */
class SetupOverlay(
    val id: Long,
    val dir: Int,
    val stage: Int,
    val zTop: Double,
    val zBot: Double,
    val lvH: Double,
    val lvL: Double,
    val hvH: Double,
    val hvL: Double,
    val entry: Double,
    val sl: Double,
    val tp1: Double,
    val tp2: Double,
    val tpx: Double,
    val midRef: Double,
    val zBi: Int
)

class ChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ── داده ───────────────────────────────────────────────────────────────────
    var candles: List<Candle> = emptyList()
    var zones: List<ZoneBox> = emptyList()
    var markers: List<Marker> = emptyList()
    var overlays: List<SetupOverlay> = emptyList()
    var trades: List<Trade> = emptyList()
    var pocPrice: Double = Double.NaN

    // ── تنظیمات نمایش ──────────────────────────────────────────────────────────
    var showVolume = true
    var showZones = true
    var showMarkers = true
    var showGrid = true
    var showTradeLines = true
    var followLive = true
    var onInfo: ((String) -> Unit)? = null

    // ── نما ────────────────────────────────────────────────────────────────────
    var barsOnScreen = 140f
        private set
    var rightIndex = -1f
        private set
    var priceZoom = 1f
    var priceShift = 0.0

    private var viewLo = 0.0
    private var viewHi = 1.0
    private var chartW = 0
    private var chartH = 0
    private val axisW: Float get() = Ui.dp(context, 56f).toFloat()
    private val timeH: Float get() = Ui.dp(context, 18f).toFloat()

    // ── رنگ‌ها ─────────────────────────────────────────────────────────────────
    private val pUp = Palette.up
    private val pDown = Palette.down

    // ── Paint ها ───────────────────────────────────────────────────────────────
    private val pWick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = Ui.dp(context, 1f).toFloat() }
    private val pBody = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pVol = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E2530"); strokeWidth = 1f
    }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.dim; textSize = Ui.dp(context, 10f).toFloat()
    }
    private val pTextSm = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.dim; textSize = Ui.dp(context, 9f).toFloat()
    }
    private val pZoneFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pZoneLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = Ui.dp(context, 1.2f).toFloat() }
    private val pDotted = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = Ui.dp(context, 1f).toFloat()
        pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
    }
    private val pDashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = Ui.dp(context, 1f).toFloat()
        pathEffect = DashPathEffect(floatArrayOf(10f, 6f), 0f)
    }
    private val pCross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f
        color = Color.parseColor("#8FA3BF")
        pathEffect = DashPathEffect(floatArrayOf(4f, 4f), 0f)
    }
    private val pBubble = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#28303E") }
    private val pBubbleTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.txt; textSize = Ui.dp(context, 10.5f).toFloat()
    }
    private val pLabelBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pLabelTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = Ui.dp(context, 9f).toFloat()
    }
    private val pPoc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.gold; strokeWidth = Ui.dp(context, 1f).toFloat()
        pathEffect = DashPathEffect(floatArrayOf(2f, 5f), 0f)
    }
    private val path = Path()

    // ── تعامل ──────────────────────────────────────────────────────────────────
    private var crossX = -1f
    private var crossY = -1f
    private var crossOn = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var pinchPriceMode = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            val dx = abs(d.currentSpan - d.previousSpan)
            pinchPriceMode = abs(d.focusY - lastTouchY) > dx * 1.2f
            return true
        }
        override fun onScale(d: ScaleGestureDetector): Boolean {
            if (pinchPriceMode) {
                priceZoom = (priceZoom * d.scaleFactor).coerceIn(0.05f, 40f)
                priceShift += (d.focusY - lastTouchY)
            } else {
                barsOnScreen = (barsOnScreen / d.scaleFactor).coerceIn(12f, 6000f)
                followLive = false
                if (rightIndex < 0 || rightIndex > candles.size + 5) rightIndex = (candles.size - 1).toFloat()
            }
            lastTouchY = d.focusY
            invalidate()
            return true
        }
    })

    private val gesture = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scaleDetector.isInProgress) return false
            val cw = chartW - axisW
            if (cw <= 0) return true
            val barW = cw / barsOnScreen
            rightIndex = (rightIndex + dx / barW)
            val maxIdx = (candles.size - 1).toFloat()
            if (rightIndex > maxIdx) rightIndex = maxIdx
            val minIdx = barsOnScreen * 0.15f
            if (rightIndex < minIdx) rightIndex = minIdx
            // حرکات عمودی → جابه‌جایی مقیاس قیمت
            if (abs(dy) > 1f) priceShift += dy.toDouble()
            if (rightIndex < maxIdx - barsOnScreen) followLive = false
            invalidate()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            resetView()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            crossOn = true
            crossX = e.x; crossY = e.y
            invalidate()
            reportInfo()
        }
    })

    // ── API ───────────────────────────────────────────────────────────────────┬
    fun resetView() {
        followLive = true
        priceZoom = 1f
        priceShift = 0.0
        barsOnScreen = 140f
        rightIndex = (candles.size - 1).toFloat()
        invalidate()
    }

    fun zoomIn() { barsOnScreen = (barsOnScreen / 1.35f).coerceAtLeast(12f); invalidate() }
    fun zoomOut() { barsOnScreen = (barsOnScreen * 1.35f).coerceAtMost(6000f); invalidate() }

    fun refresh() {
        val maxIdx = (candles.size - 1).toFloat()
        if (followLive || rightIndex < 0) rightIndex = maxIdx
        if (rightIndex > maxIdx + 2) rightIndex = maxIdx
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gesture.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x; lastTouchY = event.y
                crossOn = false
            }
            MotionEvent.ACTION_MOVE -> {
                lastTouchX = event.x; lastTouchY = event.y
                if (crossOn) {
                    crossX = event.x; crossY = event.y
                    invalidate(); reportInfo()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                crossOn = false
                onInfo?.invoke("")
                invalidate()
            }
            MotionEvent.ACTION_POINTER_UP -> lastTouchY = event.y
        }
        return true
    }

    private fun reportInfo() {
        val (idx, price) = hitTest(crossX, crossY)
        if (idx < 0 || idx >= candles.size) { onInfo?.invoke(""); return }
        val c = candles[idx]
        val o = Fa.n(c.o); val h = Fa.n(c.h); val l = Fa.n(c.l); val cl = Fa.n(c.c)
        onInfo?.invoke("${Fa.jalali(c.t)}   O:$o  H:$h  L:$l  C:$cl  V:${Fa.vol(c.v)}")
    }

    // ── نگاشت ──────────────────────────────────────────────────────────────────
    private fun idxToX(idx: Float): Float {
        if (chartW <= 0) return 0f
        val cw = chartW - axisW
        val barW = cw / barsOnScreen
        return cw - (rightIndex - idx + 0.5f) * barW
    }

    private fun xToIdx(x: Float): Float {
        val cw = chartW - axisW
        val barW = cw / barsOnScreen
        if (barW <= 0f) return -1f
        return rightIndex - (cw - x) / barW - 0.5f
    }

    private fun priceToY(p: Double): Float {
        if (viewHi == viewLo) return chartH / 2f
        return (chartH * (viewHi - p) / (viewHi - viewLo)).toFloat()
    }

    private fun yToPrice(y: Float): Double = viewHi - (y / chartH.toDouble()) * (viewHi - viewLo)

    private fun hitTest(x: Float, y: Float): Pair<Int, Double> {
        val idx = Math.round(xToIdx(x))
        return idx to yToPrice(y)
    }

    // ── اندازه‌گیری ───────────────────────────────────────────────────────────
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        chartW = w; chartH = h
        super.onSizeChanged(w, h, ow, oh)
    }

    private fun computeView() {
        val from = max(0, floor(rightIndex - barsOnScreen).toInt())
        val to = min(candles.size - 1, ceil(rightIndex + 1).toInt())
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        if (from <= to) {
            for (i in from..to) {
                val c = candles[i]
                if (c.l < lo) lo = c.l
                if (c.h > hi) hi = c.h
            }
        } else { lo = 0.0; hi = 1.0 }
        if (lo == Double.MAX_VALUE) { lo = 0.0; hi = 1.0 }
        // باکس‌ها و سطوح داخل نما را هم در نظر بگیر
        for (z in zones) {
            if (z.createdBi in from..to || z.endBi in from..to) {
                lo = min(lo, z.bot); hi = max(hi, z.top)
            }
        }
        for (o in overlays) {
            val arr = doubleArrayOf(o.zTop, o.zBot, o.lvH, o.lvL, o.hvH, o.hvL, o.entry, o.sl, o.tp1, o.tp2, o.tpx, o.midRef)
            for (v in arr) if (!v.isNaN()) { if (v < lo) lo = v; if (v > hi) hi = v }
        }
        val pad = (hi - lo) * 0.06
        var vLo = lo - pad
        var vHi = hi + pad
        if (vHi - vLo < 1e-9) { vLo -= 1.0; vHi += 1.0 }
        val center = (vLo + vHi) / 2.0
        val half = (vHi - vLo) / 2.0 / priceZoom
        val ppu = (chartH - timeH).coerceAtLeast(1f) / (half * 2).toFloat()   // پیکسل بر واحد قیمت
        val shiftPrice = priceShift / ppu
        viewLo = center - half + shiftPrice
        viewHi = center + half + shiftPrice
    }

    // ── رسم ────────────────────────────────────────────────────────────────────
    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Palette.bg)
        if (chartW == 0 || chartH == 0) return
        if (candles.isEmpty()) {
            canvas.drawText("داده‌ای نیست — از تب تنظیمات تاریخچه را دانلود کنید", Ui.dp(context, 16f).toFloat(),
                    chartH / 2f, pText.apply { textSize = Ui.dp(context, 12f).toFloat() })
            return
        }
        computeView()
        val cw = chartW - axisW
        val volH = if (showVolume) (chartH - timeH) * 0.20f else 0f
        val priceH = chartH - timeH - volH - 2f
        val barW = cw / barsOnScreen

        val from = max(0, floor(rightIndex - barsOnScreen).toInt())
        val to = min(candles.size - 1, ceil(rightIndex + 1).toInt())

        // ── شبکه و محور قیمت ──
        if (showGrid) {
            val steps = 6
            for (k in 0..steps) {
                val p = viewLo + (viewHi - viewLo) * k / steps
                val y = priceToY(p)
                if (y > priceH) continue
                canvas.drawLine(0f, y, cw, y, pGrid)
                canvas.drawText(Fa.n(p), cw + Ui.dp(context, 4f), y + Ui.dp(context, 3.5f), pText)
            }
        }

        // ── باکس‌های ناحیه ──
        if (showZones) drawZones(canvas, from, to, barW, priceH)

        // ── POC ──
        if (!pocPrice.isNaN()) {
            val y = priceToY(pocPrice)
            canvas.drawLine(0f, y, cw, y, pPoc)
            canvas.drawText("POC " + Fa.n(pocPrice), Ui.dp(context, 3f).toFloat(), y - Ui.dp(context, 2f).toFloat(), pTextSm)
        }

        // ── حجم ──
        if (showVolume) {
            var maxV = 0.0
            for (i in from..to) if (candles[i].v > maxV) maxV = candles[i].v
            if (maxV > 0) {
                val base = chartH - timeH
                for (i in from..to) {
                    val c = candles[i]
                    val x = idxToX(i.toFloat())
                    if (x < -barW || x > cw + barW) continue
                    val h = (c.v / maxV * volH).toFloat()
                    pVol.color = (if (c.c >= c.o) pUp else pDown) and 0x66FFFFFF
                    canvas.drawRect(x - barW * 0.35f, base - h, x + barW * 0.35f, base, pVol)
                }
                canvas.drawLine(0f, chartH - timeH - volH, cw, chartH - timeH - volH, pGrid)
            }
        }

        // ── کندل‌ها ──
        for (i in from..to) {
            val c = candles[i]
            val x = idxToX(i.toFloat())
            if (x < -barW || x > cw + barW) continue
            val up = c.c >= c.o
            val col = if (up) pUp else pDown
            pWick.color = col
            canvas.drawLine(x, priceToY(c.h), x, priceToY(c.l), pWick)
            val y1 = priceToY(max(c.o, c.c))
            val y2 = priceToY(min(c.o, c.c))
            pBody.color = col
            val bw = max(barW * 0.7f, 1f)
            canvas.drawRect(x - bw / 2f, y1, x + bw / 2f, max(y2, y1 + 1f), pBody)
        }

        // ── خطوط معاملات ──
        if (showTradeLines) drawTradeLines(canvas, cw, priceH)

        // ── برچسب‌های استراتژی ──
        if (showMarkers) drawMarkers(canvas, from, to, cw, priceH)

        // ── محور زمان ──
        val step = max(1, (barsOnScreen / 6f).toInt())
        var i = from
        while (i <= to) {
            val x = idxToX(i.toFloat())
            canvas.drawLine(x, 0f, x, chartH - timeH, pGrid)
            val label = timeLabel(candles[i].t, chartTfSeconds())
            canvas.drawText(label, x - Ui.dp(context, 14f).toFloat(), chartH - Ui.dp(context, 4f).toFloat(), pTextSm)
            i += step
        }

        // ── کراس‌هیر ──
        if (crossOn && crossX < cw) {
            canvas.drawLine(crossX, 0f, crossX, chartH - timeH, pCross)
            canvas.drawLine(0f, crossY, cw, crossY, pCross)
            val price = yToPrice(crossY)
            bubble(canvas, cw + 1f, crossY - Ui.dp(context, 9f), axisW - 2f, Fa.n(price))
            val idx = Math.round(xToIdx(crossX))
            if (idx in candles.indices) {
                val tx = min(crossX - Ui.dp(context, 28f), cw - Ui.dp(context, 60f))
                bubble(canvas, max(0f, tx), chartH - timeH + 1f, Ui.dp(context, 96f).toFloat(), Fa.jalali(candles[idx].t))
            }
        }
    }

    private fun chartTfSeconds(): Int = chartTfSec

    /** تایم‌فریم جاری چارت برای برچسب محور زمان (توسط اکتیویتی ست می‌شود) */
    var chartTfSec: Int = 60

    private fun timeLabel(t: Long, tf: Int): String {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = t }
        val hh = c.get(java.util.Calendar.HOUR_OF_DAY)
        val mm = c.get(java.util.Calendar.MINUTE)
        val day = c.get(java.util.Calendar.DAY_OF_MONTH)
        val mon = c.get(java.util.Calendar.MONTH) + 1
        return when {
            tf < 3600 -> Fa.d(String.format("%02d:%02d", hh, mm))
            tf < 86400 -> Fa.d(String.format("%02d %02d:%02d", day, hh, mm))
            tf < 604800 -> Fa.jalaliShort(t)
            else -> Fa.jalaliShort(t)
        }
    }

    private fun bubble(canvas: Canvas, x: Float, y: Float, w: Float, text: String) {
        val r = RectF(x, y, x + w, y + Ui.dp(context, 17f))
        canvas.drawRoundRect(r, Ui.dp(context, 3f).toFloat(), Ui.dp(context, 3f).toFloat(), pBubble)
        canvas.drawText(text, r.left + Ui.dp(context, 4f), r.bottom - Ui.dp(context, 4.5f), pBubbleTxt)
    }

    private fun drawZones(canvas: Canvas, from: Int, to: Int, barW: Float, priceH: Float) {
        for (z in zones) {
            if (z.status == ZoneStatus.CAP) continue
            val x1 = idxToX(z.createdBi.toFloat())
            val endIdx = if (z.status == ZoneStatus.ACTIVE) rightIndex + 2f else z.endBi.toFloat()
            val x2 = idxToX(endIdx)
            if (x2 < -barW || x1 > chartW - axisW + barW) continue
            if (max(x1, x2) < 0f) continue
            val yTop = priceToY(z.top)
            val yBot = priceToY(z.bot)
            val active = z.status == ZoneStatus.ACTIVE
            val color = when {
                z.status == ZoneStatus.REJECTED -> Color.parseColor("#7A8798")
                z.status == ZoneStatus.DELETED -> Color.parseColor("#5A6472")
                z.dir == 1 && z.idx == 1 -> Palette.up
                z.dir == -1 && z.idx == 1 -> Color.parseColor("#FF8A3D")
                else -> Palette.grey
            }
            val alpha = if (active) 40 else 18
            pZoneFill.color = (color and 0x00FFFFFF) or (alpha shl 24)
            pZoneFill.style = Paint.Style.FILL
            val left = max(x1, -barW)
            val right = min(x2, chartW - axisW + barW)
            canvas.drawRect(left, yTop, right, yBot, pZoneFill)
            val linePaint = if (z.status == ZoneStatus.REJECTED || z.status == ZoneStatus.DELETED) pDotted else pZoneLine
            linePaint.color = (color and 0x00FFFFFF) or (if (active) 0xC0000000.toInt() else 0x60000000)
            canvas.drawLine(left, yTop, right, yTop, linePaint)
            canvas.drawLine(left, yBot, right, yBot, linePaint)
            if (active && barW > 3f) {
                // برچسب شمارهٔ ناحیه
                pLabelBg.color = (color and 0x00FFFFFF) or (0xB0000000.toInt())
                canvas.drawRoundRect(RectF(left + 2f, yTop + 2f, left + Ui.dp(context, 30f), yTop + Ui.dp(context, 15f)),
                        Ui.dp(context, 3f).toFloat(), Ui.dp(context, 3f).toFloat(), pLabelBg)
                canvas.drawText("Z" + Fa.d(z.idx.toString()), left + Ui.dp(context, 5f), yTop + Ui.dp(context, 12.5f), pLabelTxt)
            }
        }
    }

    private fun drawMarkers(canvas: Canvas, from: Int, to: Int, cw: Float, priceH: Float) {
        for (m in markers) {
            if (m.bi < from - 2 || m.bi > to + 2) continue
            val x = idxToX(m.bi.toFloat())
            if (x < 0 || x > cw) continue
            val y = priceToY(m.price)
            if (y < -20 || y > priceH + 30) continue
            val col = when (m.kind) {
                1 -> Palette.accent
                -1 -> Color.parseColor("#C2417C")
                5 -> Palette.gold
                6 -> Palette.accent
                7 -> Palette.up
                8 -> Palette.gold
                9 -> Palette.violet
                10 -> Palette.down
                else -> Palette.grey
            }
            val r = Ui.dp(context, 3f).toFloat()
            val cy = if (m.kind == 1) y - r * 3 else y + r * 3
            pBody.color = col
            path.reset()
            path.moveTo(x, if (m.kind == 1) y - 2f else y + 2f)
            path.lineTo(x - r * 2, cy)
            path.lineTo(x + r * 2, cy)
            path.close()
            canvas.drawPath(path, pBody)
            // فقط برچسب‌های مهم متن دارند
            if (m.bi >= from + 1 && barW(cw) > 4f && m.text.isNotEmpty() && m.kind != 2 && m.kind != 6) {
                val t = m.text.lineSequence().first()
                pTextSm.color = col
                canvas.drawText(t, x + r * 2.4f, cy, pTextSm)
                pTextSm.color = Palette.dim
            }
        }
    }

    private fun barW(cw: Float): Float = cw / barsOnScreen

    private fun drawTradeLines(canvas: Canvas, cw: Float, priceH: Float) {
        fun line(price: Double, color: Int, label: String, fromIdx: Float) {
            if (price.isNaN()) return
            val y = priceToY(price)
            if (y < 0 || y > priceH) return
            pDashed.color = color
            val x0 = max(0f, idxToX(fromIdx))
            canvas.drawLine(x0, y, cw.toFloat(), y, pDashed)
            pLabelBg.color = (color and 0x00FFFFFF) or 0xD0000000.toInt()
            val w = Ui.dp(context, 4f) + pLabelTxt.measureText(label)
            canvas.drawRoundRect(RectF(x0, y - Ui.dp(context, 7f), x0 + w, y + Ui.dp(context, 7f)),
                    Ui.dp(context, 3f).toFloat(), Ui.dp(context, 3f).toFloat(), pLabelBg)
            canvas.drawText(label, x0 + Ui.dp(context, 2f), y + Ui.dp(context, 3.5f), pLabelTxt)
        }

        // سطوح ستاپ‌های فعال
        for (o in overlays) {
            if (o.stage == 8) continue
            line(o.zTop, Color.parseColor("#39B27C"), "ناحیه", o.zBi.toFloat())
            line(o.zBot, Color.parseColor("#39B27C"), "", o.zBi.toFloat())
            if (o.stage >= 4) {
                line(o.lvH, Palette.gold, "ولوم کم", o.zBi.toFloat())
                line(o.lvL, Palette.gold, "", o.zBi.toFloat())
            }
            if (o.stage >= 5 && !o.hvH.isNaN()) {
                line(o.hvH, Palette.accent, "ولوم زیاد", o.zBi.toFloat())
                line(o.hvL, Palette.accent, "", o.zBi.toFloat())
            }
            if (o.stage >= 6) {
                line(o.entry, Palette.up, "ورود", o.zBi.toFloat())
                line(o.sl, Palette.down, "حدضرر", o.zBi.toFloat())
                line(o.tp1, Color.parseColor("#FFB74D"), "TP1", o.zBi.toFloat())
                line(o.tp2, Color.parseColor("#FFB74D"), "TP2", o.zBi.toFloat())
                line(o.tpx, Palette.violet, "TP نهایی", o.zBi.toFloat())
            }
        }
        // پوزیشن باز / آخرین معاملهٔ بسته‌شده
        val t = trades.lastOrNull { it.open } ?: trades.lastOrNull()
        if (t != null) {
            line(t.entry, Palette.up, if (t.open) "ورود باز" else "ورود بسته", t.entryBi.toFloat())
            line(if (t.be) t.entry else t.sl0, Palette.down, if (t.be) "سربه‌سر" else "حدضرر", t.entryBi.toFloat())
            line(t.tpX, Palette.violet, "TP", t.entryBi.toFloat())
        }
    }
}
