package com.alisport.goldpin.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

// ═══════════════════════════════════════════════════════════════════════════════
//  ذخیره/بازیابی روی حافظهٔ دستگاه
//    ۱) خودکار: پوشهٔ اختصاصی اپ (روی «حافظه» و قابل برداشت با کابل)
//    ۲) دستی : انتخاب محل توسط کاربر (SAF) — هر جا که بخواهد
//  فایل ذخیره‌شده شامل کل وضعیت است: تنظیمات، کندل‌ها، باکس‌های ناحیه (حتی
//  آن‌هایی که پاک شده‌اند)، سفارش‌ها، معاملات و موجودی — بازگشت دقیق و بی‌کم‌وکاست.
// ═══════════════════════════════════════════════════════════════════════════════
object Storage {

    const val FILE_NAME = "goldpin_state.json.gz"

    fun dir(ctx: Context): File {
        val ext = ctx.getExternalFilesDir(null)
        return ext ?: ctx.filesDir
    }

    fun autoFile(ctx: Context): File = File(dir(ctx), FILE_NAME)

    fun writeText(file: File, json: String) {
        GZIPOutputStream(file.outputStream().buffered()).use { it.write(json.toByteArray(Charsets.UTF_8)) }
    }

    fun readText(file: File): String {
        if (!file.exists()) throw FeedException("فایل ذخیره وجود ندارد")
        return readStream(file.inputStream(), gz = true)
    }

    fun writeUri(ctx: Context, uri: Uri, json: String) {
        val os: OutputStream = ctx.contentResolver.openOutputStream(uri, "wt")
            ?: throw FeedException("نوشتن در مسیر انتخابی ممکن نشد")
        GZIPOutputStream(os.buffered()).use { it.write(json.toByteArray(Charsets.UTF_8)) }
    }

    fun readUri(ctx: Context, uri: Uri): String {
        val ins: InputStream = ctx.contentResolver.openInputStream(uri)
            ?: throw FeedException("خواندن از مسیر انتخابی ممکن نشد")
        return readStream(ins, gz = true)
    }

    /** خواندن با تشخیص خودکار gzip */
    fun readStream(ins: InputStream, gz: Boolean): String {
        val buffered = ins.buffered(1 shl 16)
        buffered.mark(4)
        val b0 = buffered.read()
        val b1 = buffered.read()
        buffered.reset()
        val isGz = (b0 == 0x1f && b1 == 0x8b) || gz
        val stream = if (isGz) GZIPInputStream(buffered) else buffered
        return stream.readBytes().toString(Charsets.UTF_8)
    }

    /** نوشتن یک متن ساده (گزارش CSV) */
    fun writeReport(ctx: Context, uri: Uri, text: String) {
        ctx.contentResolver.openOutputStream(uri, "wt")?.use { os ->
            os.write(text.toByteArray(Charsets.UTF_8))
        } ?: throw FeedException("نوشتن گزارش ممکن نشد")
    }
}
