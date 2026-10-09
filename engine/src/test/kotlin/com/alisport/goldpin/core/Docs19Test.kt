package com.alisport.goldpin.core
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * docs/19 — چرخهٔ عمر پین‌ها و روندِ توالی‌محور.
 *
 * این آزمون **اثبات می‌کند مسیرهای جدید واقعاً اجرا می‌شوند**، نه اینکه فقط کامپایل
 * شده باشند. روی دادهٔ نمونهٔ اپ (۴۰٬۰۰۰ کندل) اجرا می‌شود.
 */
class Docs19Test {
    @Test fun `trend forms only by sequence, never by a lone pin`() {
        val rows = ArrayList<DoubleArray>()
        GZIPInputStream(java.io.File("../app/src/main/assets/sample_xauusd_m1.csv.gz").inputStream())
            .bufferedReader().useLines { ls -> ls.forEach { ln ->
                val p = ln.split(",")
                if (p.size >= 6) runCatching { rows.add(doubleArrayOf(p[0].toDouble(),p[1].toDouble(),p[2].toDouble(),p[3].toDouble(),p[4].toDouble(),p[5].toDouble())) } } }
        val e = Engine(Settings())
        var upSeq=0; var dnSeq=0; var buInval=0; var beInval=0; var noSeqBu=0; var noSeqBe=0; var flipUp=0; var flipDn=0
        e.logSink = { _, m, _ ->
            when {
                m.contains("روند صعودی تثبیت شد (توالی)") -> upSeq++
                m.contains("روند نزولی تثبیت شد (توالی)") -> dnSeq++
                m.contains("pinBU بی‌اعتبار و حذف شد") -> buInval++
                m.contains("pinBE بی‌اعتبار و حذف شد") -> beInval++
                m.contains("توالی کامل نبود — فقط pinBU") -> noSeqBu++
                m.contains("توالی کامل نبود — فقط pinBE") -> noSeqBe++
                m.contains("چرخش روند به صعودی") -> flipUp++
                m.contains("چرخش روند به نزولی") -> flipDn++
            }
        }
        e.runAll(rows.mapIndexed { i, r -> Candle(i, r[0].toLong(), r[1], r[2], r[3], r[4], r[5]) })
        // بند ۵-۲: روند با توالی تشکیل شد (حداقل یک بار)
        assertTrue(upSeq + dnSeq >= 1, "روند باید با توالی تشکیل شود — هیچ‌وقت تشکیل نشد")
        // بند ۲: چرخش روند مشروط به توالی — باید هم چرخشِ موفق داشته باشیم
        assertTrue(flipUp + flipDn >= 1, "هیچ چرخش روندی رخ نداد")
        // بند ۲ (شاخهٔ منفی): بدون توالی، فقط پین حذف می‌شود و روند دست نمی‌خورد
        assertTrue(noSeqBu + noSeqBe >= 1, "شاخهٔ «بدون توالی ⇒ فقط حذف پین» هرگز اجرا نشد")
        // بند ۳: ابطال پین بعد از Ready
        assertTrue(buInval + beInval >= 1, "ابطال پین بعد از Ready هرگز اجرا نشد")
        // روند باید تعیین شده باشد
        assertTrue(e.trend != 0, "روند پس از ۴۰٬۰۰۰ کندل هنوز صفر است")
        // سازگاری درونی: جهت روند با ترتیب توالی بخواند
        if (e.trend == 1) assertTrue(e.buSeq > e.beSeq || !e.beActive,
            "روند صعودی ولی pinBE بعد از pinBU آمده و هنوز معتبر است")
        assertEquals(40000, e.processed, "همهٔ کندل‌ها پردازش نشدند")
    }
}
