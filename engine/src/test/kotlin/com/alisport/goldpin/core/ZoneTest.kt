package com.alisport.goldpin.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * آزمون بازتولید مثال عددی کارفرما برای الگوریتم ناحیهٔ فیکس‌رنج نسخهٔ ۲.
 *
 * ردیف‌ها از کف به سقف (صعودی):
 *   85, 95, 125, 325, 324, 254, 117, 226, 231, 390, 472, 489, 300, 217, 390
 *
 * ناحیهٔ ۱ : قلهٔ اول = ردیف ۳ (۳۲۵) ، درهٔ اول = ردیف ۶ (۱۱۷)
 *           کف ناحیه = مرز بین ۱۲۵ و ۳۲۵  ،  سقف ناحیه = مرز بین ۱۱۷ و ۲۲۶
 * ناحیهٔ ۲ : قله = ردیف ۱۱ (۴۸۹) ، دره = ردیف ۱۳ (۲۱۷)
 *           کف ناحیه = مرز بین ۴۷۲ و ۴۸۹  ،  سقف ناحیه = مرز بین ۲۱۷ و ۳۹۰
 */
class ZoneTest {

    private val rows = doubleArrayOf(
        85.0, 95.0, 125.0, 325.0, 324.0, 254.0, 117.0, 226.0,
        231.0, 390.0, 472.0, 489.0, 300.0, 217.0, 390.0
    )

    private fun zonesAt(close: Double, bull: Boolean = true, rejected: Boolean = false, step: Double = 10.0, lo: Double = 1000.0): List<Zone> {
        val prof = Profile(rows.copyOf(), lo, step, 0.0, 0.0, 0.0)
        return VolumeProfile.zones(prof, rows.size, bull = bull, cClose = close, minRows = 1, wantRejected = rejected)
    }

    @Test
    fun `zone one runs from the boundary below the first peak to the boundary above the first trough`() {
        val z = zonesAt(close = 4000.0)
        assertEquals(2, z.size, "باید دو ناحیه پیدا شود")
        assertEquals(3, z[0].loIdx, "ردیف قلهٔ اول = ۳ (۳۲۵)")
        assertEquals(6, z[0].hiIdx, "ردیف درهٔ اول = ۶ (۱۱۷)")
        assertEquals(11, z[1].loIdx, "ردیف قلهٔ دوم = ۱۱ (۴۸۹)")
        assertEquals(13, z[1].hiIdx, "ردیف درهٔ دوم = ۱۳ (۲۱۷)")
    }

    @Test
    fun `prices match the employer example exactly`() {
        val step = 10.0
        val lo = 1000.0
        val z = zonesAt(close = lo + 300 * step)
        assertEquals(2, z.size)
        // ناحیهٔ ۱ : مرز ۱۲۵|۳۲۵ تا مرز ۱۱۷|۲۲۶
        assertEquals(lo + 3 * step, z[0].bot, 1e-9, "کف ناحیه ۱ = مرز بین ۱۲۵ و ۳۲۵")
        assertEquals(lo + 7 * step, z[0].top, 1e-9, "سقف ناحیه ۱ = مرز بین ۱۱۷ و ۲۲۶")
        // ناحیهٔ ۲ : مرز ۴۷۲|۴۸۹ تا مرز ۲۱۷|۳۹۰
        assertEquals(lo + 11 * step, z[1].bot, 1e-9, "کف ناحیه ۲ = مرز بین ۴۷۲ و ۴۸۹")
        assertEquals(lo + 14 * step, z[1].top, 1e-9, "سقف ناحیه ۲ = مرز بین ۲۱۷ و ۳۹۰")
    }

    @Test
    fun `close inside the band rejects the zone`() {
        val lo = 1000.0; val step = 10.0
        // کلوز داخل ناحیهٔ اول (بین ۱۰۳۰ و ۱۰۷۰) → ناحیه نباید پذیرفته شود
        assertEquals(0, zonesAt(close = lo + 5 * step).size, "کلوز داخل ناحیه → رد شدن")
        val rej = zonesAt(close = lo + 5 * step, rejected = true)
        assertEquals(2, rej.size, "هر دو ناحیه ردشده‌اند (کلوز زیر هر دو)")
        // کلوز بالای ناحیهٔ اول ولی زیر ناحیهٔ دوم → فقط ناحیهٔ اول پذیرفته می‌شود
        assertEquals(1, zonesAt(close = lo + 9 * step).size)
        assertEquals(2, zonesAt(close = lo + 16 * step).size, "کلوز بالای هر دو ناحیه")
    }

    @Test
    fun `bearish is a perfect mirror`() {
        val lo = 1000.0; val step = 10.0
        // در نزولی، ردیف‌ها از سقف به کف خوانده می‌شوند — همان داده آینه‌ای
        val rev = rows.reversedArray()
        val prof = Profile(rev, lo, step, 0.0, 0.0, 0.0)
        val z = VolumeProfile.zones(prof, rev.size, bull = false, cClose = lo - step, minRows = 1)
        assertEquals(2, z.size)
        // آینهٔ ناحیهٔ ۱ صعودی (ردیف‌های ۳..۶) → در آرایهٔ آینه‌ای ردیف‌های ۸..۱۱
        assertEquals(lo + 8 * step, z[0].bot, 1e-9, "کف ناحیهٔ آینه‌ای")
        assertEquals(lo + 12 * step, z[0].top, 1e-9, "سقف ناحیهٔ آینه‌ای")
        // آینهٔ ناحیهٔ ۲ صعودی (ردیف‌های ۱۱..۱۳) → ردیف‌های ۱..۳
        assertEquals(lo + 1 * step, z[1].bot, 1e-9)
        assertEquals(lo + 4 * step, z[1].top, 1e-9)
    }

    @Test
    fun `minRows filters out too thin bands`() {
        // با حداقل ۵ ردیف، ناحیهٔ اول (۴ ردیف) حذف می‌شود
        val prof = Profile(rows.copyOf(), 1000.0, 10.0, 0.0, 0.0, 0.0)
        val z = VolumeProfile.zones(prof, rows.size, bull = true, cClose = 4000.0, minRows = 5)
        assertEquals(0, z.size, "همهٔ ناحیه‌های این مثال باریک‌تر از ۵ ردیف‌اند")
    }
}
