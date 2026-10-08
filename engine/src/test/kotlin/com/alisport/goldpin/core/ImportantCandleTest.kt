package com.alisport.goldpin.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * §۲-۱ سند استراتژی — «کندل مهم».
 *
 * سند (مرجع):
 *  • پایین‌ترین‌ها (BU): اولی نزولی و دومی صعودی؛ کندلی که **کف بزرگ‌تری** دارد مهم
 *    است، و در تساوی کف، کندلی که **سقف کوچک‌تری** دارد.
 *  • بالاترین‌ها (BE): اولی صعودی و دومی نزولی؛ کندلی که **سقف بزرگ‌تری** دارد مهم است.
 *
 * ⚠ این آزمون قاعدهٔ سند را قفل می‌کند. پیش از ۱٫۳٫۷ کد `a.l < b.l` داشت یعنی
 * کم‌ترین کف را برمی‌داشت که با قاعدهٔ سند نمی‌خواند.
 */
class ImportantCandleTest {

    private fun c(bi: Int, o: Double, h: Double, l: Double, cl: Double) =
        Candle(bi, bi.toLong() * 3600_000L, o, h, l, cl, 100.0)

    @Test
    fun `BU picks the candle with the LARGER low as the doc requires`() {
        // a: کف ۱۰۰ (کوچک‌تر) · b: کف ۱۰۵ (بزرگ‌تر) ⇒ کندل مهم باید b باشد
        val a = c(1, 110.0, 112.0, 100.0, 101.0)
        val b = c(2, 104.0, 108.0, 105.0, 107.0)
        val (_, lo, bi) = Engine(Settings()).pickLow(a, b)
        assertEquals(105.0, lo, 1e-9, "کف بزرگ‌تر باید انتخاب شود")
        assertEquals(2, bi, "کندل مهم باید b باشد")
    }

    @Test
    fun `BU order of arguments does not change the result`() {
        val a = c(1, 110.0, 112.0, 100.0, 101.0)
        val b = c(2, 104.0, 108.0, 105.0, 107.0)
        val r1 = Engine(Settings()).pickLow(a, b)
        val r2 = Engine(Settings()).pickLow(b, a)
        assertEquals(r1.second, r2.second, 1e-9, "نتیجه نباید به ترتیب آرگومان‌ها وابسته باشد")
        assertEquals(r1.third, r2.third)
    }

    @Test
    fun `BU tie on the low is broken by the SMALLER high`() {
        // کف‌ها برابر (۱۰۰) · سقف a = ۱۲۰ · سقف b = ۱۱۰ ⇒ b (سقف کوچک‌تر) مهم است
        val a = c(1, 118.0, 120.0, 100.0, 101.0)
        val b = c(2, 108.0, 110.0, 100.0, 107.0)
        val (_, lo, bi) = Engine(Settings()).pickLow(a, b)
        assertEquals(100.0, lo, 1e-9)
        assertEquals(2, bi, "در تساوی کف، کندل با سقف کوچک‌تر مهم است")
    }

    @Test
    fun `BE picks the candle with the LARGER high`() {
        // a: سقف ۲۰۰ · b: سقف ۲۱۰ ⇒ b مهم است
        val a = c(1, 198.0, 200.0, 190.0, 199.0)
        val b = c(2, 205.0, 210.0, 202.0, 203.0)
        val (hi, _, bi) = Engine(Settings()).pickHigh(a, b)
        assertEquals(210.0, hi, 1e-9, "سقف بزرگ‌تر باید انتخاب شود")
        assertEquals(2, bi)
    }

    @Test
    fun `BE tie on the high is broken by the LARGER low`() {
        val a = c(1, 198.0, 210.0, 190.0, 199.0)   // کف ۱۹۰
        val b = c(2, 205.0, 210.0, 202.0, 203.0)   // کف ۲۰۲ (بزرگ‌تر)
        val (_, _, bi) = Engine(Settings()).pickHigh(a, b)
        assertEquals(2, bi, "در تساوی سقف، کندل با کف بزرگ‌تر مهم است")
    }
}
