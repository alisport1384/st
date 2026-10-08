package com.alisport.goldpin.util

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Calendar
import java.util.TimeZone

// ═══════════════════════════════════════════════════════════════════════════════
//  ابزار رابط کاربری — ساخت ویو‌ها به‌صورت برنامه‌ای (بدون وابستگی خارجی)
//  + اعداد فارسی + تاریخ شمسی
// ═══════════════════════════════════════════════════════════════════════════════

object Palette {
    val bg = Color.parseColor("#0E1116")
    val panel = Color.parseColor("#151A22")
    val panel2 = Color.parseColor("#1C2230")
    val line = Color.parseColor("#2A3242")
    val txt = Color.parseColor("#E8ECF3")
    val dim = Color.parseColor("#9AA6B8")
    val up = Color.parseColor("#26A69A")
    val down = Color.parseColor("#EF5350")
    val gold = Color.parseColor("#FFC107")
    val accent = Color.parseColor("#4C8DFF")
    val violet = Color.parseColor("#B388FF")
    val grey = Color.parseColor("#7A8798")
}

object Fa {
    private val digits = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')

    /** تبدیل ارقام لاتین به فارسی */
    fun d(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) sb.append(if (ch in '0'..'9') digits[ch - '0'] else ch)
        return sb.toString()
    }

    /** عدد با جداکنندهٔ هزارگان و تعداد اعشار مشخص */
    fun n(v: Double, dec: Int = 2, persian: Boolean = true): String {
        if (v.isNaN()) return "—"
        val s = String.format("%,.${dec}f", v)
        return if (persian) d(s) else s
    }

    fun money(v: Double, persian: Boolean = true): String = n(v, 2, persian)

    fun vol(v: Double, persian: Boolean = true): String {
        val s = when {
            v >= 1_000_000 -> n(v / 1_000_000.0, 2, false) + "M"
            v >= 1_000 -> n(v / 1_000.0, 1, false) + "K"
            else -> n(v, 0, false)
        }
        return if (persian) d(s) else s
    }

    fun pct(v: Double, dec: Int = 1): String = n(v, dec) + "٪"

    fun signed(v: Double, dec: Int = 2): String {
        val s = if (v > 0) "+" else if (v < 0) "−" else ""
        return s + n(kotlin.math.abs(v), dec)
    }

    // ── تاریخ شمسی (جلالی) ─────────────────────────────────────────────────────
    private val jMonths = arrayOf(
        "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
        "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند"
    )

    /** تبدیل میلادی به شمسی (الگوریتم استاندارد). */
    fun toJalali(gy: Int, gm: Int, gd: Int): Triple<Int, Int, Int> {
        val g_d_m = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        var jy: Int
        val gy2 = if (gm > 2) gy + 1 else gy
        var days = 355666 + (365 * gy) + ((gy2 + 3) / 4) - ((gy2 + 99) / 100) +
                ((gy2 + 399) / 400) + gd + g_d_m[gm - 1]
        jy = -1595 + (33 * (days / 12053))
        days %= 12053
        jy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) {
            jy += (days - 1) / 365
            days = (days - 1) % 365
        }
        val jm: Int
        val jd: Int
        if (days < 186) {
            jm = 1 + (days / 31)
            jd = 1 + (days % 31)
        } else {
            jm = 7 + ((days - 186) / 30)
            jd = 1 + ((days - 186) % 30)
        }
        return Triple(jy, jm, jd)
    }

    /** تاریخ شمسی + ساعت به وقت محلی */
    fun jalali(ms: Long, withTime: Boolean = true, timeZone: TimeZone = TimeZone.getDefault()): String {
        if (ms <= 0) return "—"
        val c = Calendar.getInstance(timeZone).apply { timeInMillis = ms }
        val (jy, jm, jd) = toJalali(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
        val date = d(String.format("%04d/%02d/%02d", jy, jm, jd))
        if (!withTime) return date
        val hh = c.get(Calendar.HOUR_OF_DAY)
        val mm = c.get(Calendar.MINUTE)
        return "$date ${d(String.format("%02d:%02d", hh, mm))}"
    }

    fun jalaliShort(ms: Long): String {
        if (ms <= 0) return "—"
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        val (jy, jm, jd) = toJalali(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
        return d(String.format("%02d/%02d/%02d", jy % 100, jm, jd))
    }

    fun jMonthName(m: Int): String = jMonths[(m - 1).coerceIn(0, 11)]
}

object Ui {

    fun dp(ctx: Context, v: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics
    ).toInt()

    fun tv(ctx: Context, text: String, size: Float = 13f, color: Int = Palette.txt, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            includeFontPadding = false
        }

    fun btn(ctx: Context, text: String, bg: Int = Palette.panel2, fg: Int = Palette.txt, size: Float = 13f): Button =
        Button(ctx).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(fg)
            background = rounded(bg, 8f, ctx)
            isAllCaps = false
            minHeight = dp(ctx, 34f)
            minimumHeight = dp(ctx, 34f)
            setPadding(dp(ctx, 10f), 0, dp(ctx, 10f), 0)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

    fun rounded(color: Int, radius: Float, ctx: Context, strokeColor: Int? = null, strokeW: Int = 0): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(ctx, radius).toFloat()
        if (strokeColor != null) g.setStroke(dp(ctx, strokeW.toFloat()), strokeColor)
        return g
    }

    fun box(ctx: Context, bg: Int = Palette.panel, radius: Float = 10f, pad: Float = 8f, stroke: Int? = null): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(bg, radius, ctx, stroke, if (stroke != null) 1 else 0)
            setPadding(dp(ctx, pad), dp(ctx, pad), dp(ctx, pad), dp(ctx, pad))
        }

    fun row(ctx: Context, vararg views: View): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            for (v in views) {
                addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }

    fun rowWrap(ctx: Context, vararg views: View): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            for (v in views) addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(ctx, 6f)
            })
        }

    fun hline(ctx: Context): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1f))
        setBackgroundColor(Palette.line)
    }

    fun spacer(ctx: Context, h: Float = 6f): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, h))
    }

    fun label(ctx: Context, k: String, v: String, kColor: Int = Palette.dim, vColor: Int = Palette.txt): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(tv(ctx, k, 12f, kColor), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(tv(ctx, v, 12.5f, vColor, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f))
            setPadding(0, dp(ctx, 2f), 0, dp(ctx, 2f))
        }

    fun edit(ctx: Context, value: String, hint: String = ""): EditText =
        EditText(ctx).apply {
            setText(value)
            this.hint = hint
            setTextColor(Palette.txt)
            setHintTextColor(Palette.dim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            background = rounded(Palette.bg, 6f, ctx, Palette.line, 1)
            setPadding(dp(ctx, 8f), dp(ctx, 6f), dp(ctx, 8f), dp(ctx, 6f))
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                    android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        }

    fun section(ctx: Context, title: String): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(tv(ctx, title, 13.5f, Palette.gold, true))
        addView(hline(ctx))
        setPadding(0, dp(ctx, 10f), 0, dp(ctx, 4f))
    }
}
