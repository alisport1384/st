package com.alisport.goldpin.util

import android.content.Context
import android.os.Build
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// ═══════════════════════════════════════════════════════════════════════════════
//  لاگر کامل GoldPin
//  • پیش‌فرض خاموش است؛ فقط با روشن کردن کاربر کار می‌کند.
//  • به «همه جا» وصل است: موتور، کارگزار، فید، ذخیره‌سازی، سرویس لایو،
//    هشدارها، رابط کاربری و تنظیمات.
//  • خروجی را هم‌زمان در دو فایل `.md` و `.txt` می‌نویسد (به‌صورت زنده/append).
//  • سطح‌بندی (DEBUG/INFO/WARN/ERROR) + دسته‌بندی (CAT_*) + حذف دسته‌ها.
//  • چرخش خودکار فایل با پر شدن (پارتیشن‌بندی) و امکان خروجی گرفتن دستی.
//
//  نمونهٔ خط خروجی:
//    12:31:07.412  INFO   [ENGINE]  ناحیهٔ فیکس‌رنج ثبت شد · Z1 · 4105.20 تا 4118.40
//                                             داده: dir=BULL loIdx=44 hiIdx=52
// ═══════════════════════════════════════════════════════════════════════════════

object Log {

    // ── سطوح ──────────────────────────────────────────────────────────────────
    const val VERBOSE = 0
    const val DEBUG = 1
    const val INFO = 2
    const val WARN = 3
    const val ERROR = 4

    fun levelName(l: Int): String = when (l) {
        VERBOSE -> "VERBOSE"; DEBUG -> "DEBUG"; INFO -> "INFO"; WARN -> "WARN"; else -> "ERROR"
    }

    fun levelFa(l: Int): String = when (l) {
        VERBOSE -> "ریز"; DEBUG -> "اشکال‌زدایی"; INFO -> "اطلاع"; WARN -> "هشدار"; else -> "خطا"
    }

    // ── دسته‌ها ───────────────────────────────────────────────────────────────
    const val CAT_APP = "APP"           // چرخهٔ عمر اپ
    const val CAT_ENGINE = "ENGINE"     // موتور استراتژی (کندل مهم، روند، ستاپ‌ها)
    const val CAT_ZONE = "ZONE"         // ناحیهٔ فیکس‌رنج و چرخهٔ عمر باکس‌ها
    const val CAT_ORDER = "ORDER"       // ثبت/لغو/پر شدن سفارش
    const val CAT_TRADE = "TRADE"       // ورود، پله‌ها، حدضرر، حدسود، سر‌به‌سر
    const val CAT_FEED = "FEED"         // دانلود و به‌روزرسانی داده
    const val CAT_STORE = "STORE"       // ذخیره/بازیابی
    const val CAT_LIVE = "LIVE"         // سرویس ارزیابی دائمی
    const val CAT_ALERT = "ALERT"       // هشدارها و نوتیفیکیشن
    const val CAT_UI = "UI"             // تب‌ها، تمام‌صفحه، ژست‌ها
    const val CAT_CFG = "CFG"           // تغییر تنظیمات
    const val CAT_PERF = "PERF"         // زمان‌بندی و کارایی

    val ALL_CATS = listOf(
        CAT_APP, CAT_ENGINE, CAT_ZONE, CAT_ORDER, CAT_TRADE,
        CAT_FEED, CAT_STORE, CAT_LIVE, CAT_ALERT, CAT_UI, CAT_CFG, CAT_PERF
    )

    // ── وضعیت ─────────────────────────────────────────────────────────────────
    @Volatile var enabled: Boolean = false
        private set
    @Volatile var minLevel: Int = INFO
        private set
    @Volatile var writeToFile: Boolean = true
        private set
    /** دسته‌های خاموش (خالی = همه روشن) */
    private val muted = HashSet<String>()
    @Volatile private var mirrorToLogcat = true

    private var appCtx: Context? = null
    private var session: Session? = null
    private val buffer = ArrayDeque<Entry>()
    private const val MAX_BUFFER = 6000
    private const val MAX_FILE_BYTES = 1_500_000L

    val sessionStart: Long = System.currentTimeMillis()
    var appVersion: String = "1.1"

    class Entry(val t: Long, val level: Int, val cat: String, val msg: String, val data: String?)

    class Session(
        val base: File,
        val md: File,
        val txt: File,
        var part: Int
    ) {
        var bytes: Long = 0
        var mdOut: BufferedWriter? = null
        var txtOut: BufferedWriter? = null
        var pendingFlush = 0
        fun close() {
            try { mdOut?.flush(); mdOut?.close() } catch (e: Exception) { }
            try { txtOut?.flush(); txtOut?.close() } catch (e: Exception) { }
            mdOut = null; txtOut = null
        }
    }

    // ── راه‌اندازی ────────────────────────────────────────────────────────────
    fun init(ctx: Context, version: String = "1.1") {
        appCtx = ctx.applicationContext
        appVersion = version
        // اگر پوشهٔ لاگ عوض شده باشد (مثلاً پاک شدن حافظه یا اجرای مجدد)،
        // نشست قبلی باطل است و از نو باز می‌شود.
        try { session?.close() } catch (t: Throwable) { }
        session = null
        val p = prefs(ctx)
        enabled = p.getBoolean("log_enabled", false)
        minLevel = p.getInt("log_level", INFO)
        writeToFile = p.getBoolean("log_to_file", true)
        mirrorToLogcat = p.getBoolean("log_logcat", true)
        muted.clear()
        p.getStringSet("log_muted", emptySet())?.forEach { muted.add(it) }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("goldpin", Context.MODE_PRIVATE)

    private fun persist(cfg: Context) {
        prefs(cfg).edit()
            .putBoolean("log_enabled", enabled)
            .putInt("log_level", minLevel)
            .putBoolean("log_to_file", writeToFile)
            .putBoolean("log_logcat", mirrorToLogcat)
            .putStringSet("log_muted", HashSet(muted))
            .apply()
    }

    fun setEnabled(ctx: Context, v: Boolean) {
        enabled = v
        persist(ctx)
        if (v) {
            i(CAT_APP, "لاگر روشن شد")
        } else {
            i(CAT_APP, "لاگر خاموش شد")
            flush()
        }
    }

    fun setLevel(ctx: Context, l: Int) { minLevel = l; persist(ctx); i(CAT_APP, "سطح لاگ تغییر کرد به ${levelName(l)}") }

    fun setWriteToFile(ctx: Context, v: Boolean) { writeToFile = v; persist(ctx); i(CAT_APP, "نوشتن در فایل: $v") }

    fun setLogcat(ctx: Context, v: Boolean) { mirrorToLogcat = v; persist(ctx) }

    fun isMuted(cat: String) = muted.contains(cat)

    fun setMuted(ctx: Context, cat: String, m: Boolean) {
        if (m) muted.add(cat) else muted.remove(cat)
        persist(ctx)
        i(CAT_CFG, "دستهٔ $cat ${if (m) "خاموش" else "روشن"} شد")
    }

    // ── ثبت ───────────────────────────────────────────────────────────────────
    fun v(cat: String, msg: String, data: String? = null) = add(VERBOSE, cat, msg, data)
    fun d(cat: String, msg: String, data: String? = null) = add(DEBUG, cat, msg, data)
    fun i(cat: String, msg: String, data: String? = null) = add(INFO, cat, msg, data)
    fun w(cat: String, msg: String, data: String? = null) = add(WARN, cat, msg, data)
    fun e(cat: String, msg: String, data: String? = null) = add(ERROR, cat, msg, data)

    fun e(cat: String, msg: String, ex: Throwable) =
        add(ERROR, cat, msg, "exception=${ex.javaClass.simpleName} · ${ex.message}")

    @Synchronized
    fun add(level: Int, cat: String, msg: String, data: String? = null) {
        if (!enabled) return
        if (level < minLevel) return
        if (muted.contains(cat)) return
        val e = Entry(System.currentTimeMillis(), level, cat, msg, data)
        buffer.addLast(e)
        while (buffer.size > MAX_BUFFER) buffer.removeFirst()
        if (mirrorToLogcat) {
            try {
                when (level) {
                    WARN -> android.util.Log.w("GoldPin/$cat", msg)
                    ERROR -> android.util.Log.e("GoldPin/$cat", msg)
                    else -> android.util.Log.i("GoldPin/$cat", msg)
                }
            } catch (t: Throwable) { }
        }
        if (writeToFile) writeEntry(e)
    }

    // ── فایل ──────────────────────────────────────────────────────────────────
    private fun logDir(): File {
        val c = appCtx
        val base = if (c != null) (c.getExternalFilesDir(null) ?: c.filesDir) else File("/tmp")
        val d = File(base, "logs")
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun sessionName(part: Int): String {
        val f = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val stamp = f.format(Date(sessionStart))
        return if (part <= 1) "goldpin_log_$stamp" else "goldpin_log_${stamp}_p$part"
    }

    private fun openSession(): Session {
        val part = (session?.part ?: 0) + 1
        val name = sessionName(part)
        val s = Session(File(logDir(), name), File(logDir(), "$name.md"), File(logDir(), "$name.txt"), part)
        s.mdOut = BufferedWriter(java.io.OutputStreamWriter(java.io.FileOutputStream(s.md, true), Charsets.UTF_8))
        s.txtOut = BufferedWriter(java.io.OutputStreamWriter(java.io.FileOutputStream(s.txt, true), Charsets.UTF_8))
        s.mdOut!!.write(mdHeader())
        s.txtOut!!.write(txtHeader())
        s.mdOut!!.flush(); s.txtOut!!.flush()
        s.bytes = s.md.length() + s.txt.length()
        return s
    }

    private fun mdHeader(): String {
        val tz = TimeZone.getDefault().id
        return buildString {
            append("# لاگ GoldPin\n\n")
            append("| عنوان | مقدار |\n|---|---|\n")
            append("| نسخهٔ اپ | $appVersion |\n")
            append("| شروع نشست | ${Fa.jalali(sessionStart)} |\n")
            append("| منطقهٔ زمانی | $tz |\n")
            append("| دستگاه | ${Build.MANUFACTURER} ${Build.MODEL} |\n")
            append("| اندروید | ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) |\n")
            append("| سطح لاگ | ${levelName(minLevel)} |\n\n")
            append("| زمان | سطح | دسته | پیام | داده |\n|---|---|---|---|---|\n")
        }
    }

    private fun txtHeader(): String = buildString {
        append("==================== لاگ GoldPin ====================\n")
        append("نسخه: $appVersion   شروع: ${Fa.jalali(sessionStart)}\n")
        append("دستگاه: ${Build.MANUFACTURER} ${Build.MODEL}   اندروید: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        append("سطح: ${levelName(minLevel)}\n")
        append("=====================================================\n")
    }

    private fun writeEntry(e: Entry) {
        val s = session ?: openSession().also { session = it }
        val hhmmss = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(e.t))
        val mdLine = "| $hhmmss | ${levelName(e.level)} | ${e.cat} | ${mdEsc(e.msg)} | ${mdEsc(e.data ?: "")} |\n"
        val txtLine = "$hhmmss ${levelName(e.level).padEnd(5)} [${e.cat}] ${e.msg}" + (e.data?.let { "  ·  $it" } ?: "") + "\n"
        try {
            s.mdOut?.write(mdLine)
            s.txtOut?.write(txtLine)
            s.bytes += (mdLine.length + txtLine.length).toLong()
            s.pendingFlush++
            val urgent = e.level >= WARN
            if (urgent || s.pendingFlush >= 16) {
                s.mdOut?.flush(); s.txtOut?.flush(); s.pendingFlush = 0
            }
            if (s.bytes > MAX_FILE_BYTES) {
                s.close()
                session = openSession()
            }
        } catch (t: Throwable) {
            // اگر نوشتن فایل شکست خورد (مثلاً پوشه پاک شده)، نشست را ترمیم می‌کنیم
            try {
                s.close()
                session = null
                val s2 = openSession()
                session = s2
                s2.mdOut?.write(mdLine)
                s2.txtOut?.write(txtLine)
                s2.mdOut?.flush(); s2.txtOut?.flush()
            } catch (t2: Throwable) {
                // لاگر هرگز نباید اپ را زمین بزند
            }
        }
    }

    private fun mdEsc(x: String): String = x.replace("|", "\\|").replace("\n", " ")

    /** نوشتن همهٔ آنچه در حافظه مانده + بستن فایل‌ها (برای خروجی گرفتن) */
    @Synchronized
    fun flush() {
        session?.let {
            try { it.mdOut?.flush(); it.txtOut?.flush() } catch (e: Exception) { }
        }
    }

    @Synchronized
    fun closeSession() {
        flush()
        session?.close()
    }

    // ── خروجی ─────────────────────────────────────────────────────────────────
    /** گزارش Markdown از کل بافر حافظه (برای نمایش/اشتراک‌گذاری) */
    fun reportMd(): String {
        val sb = StringBuilder()
        sb.append(mdHeader())
        for (e in buffer) {
            val hhmmss = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(e.t))
            sb.append("| $hhmmss | ${levelName(e.level)} | ${e.cat} | ${mdEsc(e.msg)} | ${mdEsc(e.data ?: "")} |\n")
        }
        sb.append("\n> تعداد خطوط: ${buffer.size}\n")
        return sb.toString()
    }

    fun reportTxt(): String {
        val sb = StringBuilder()
        sb.append(txtHeader())
        for (e in buffer) {
            val hhmmss = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(e.t))
            sb.append("$hhmmss ${levelName(e.level).padEnd(5)} [${e.cat}] ${e.msg}")
            if (e.data != null) sb.append("  ·  ${e.data}")
            sb.append('\n')
        }
        sb.append("تعداد خطوط: ${buffer.size}\n")
        return sb.toString()
    }

    fun tail(n: Int = 300): List<String> {
        val last = buffer.toList().takeLast(n.coerceAtLeast(1))
        return last.map { e ->
            val hhmmss = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(e.t))
            "$hhmmss ${levelName(e.level)} [${e.cat}] ${e.msg}" + (e.data?.let { d -> " · $d" } ?: "")
        }
    }

    fun count(): Int = buffer.size

    @Synchronized
    fun clearBuffer() {
        buffer.clear()
        i(CAT_APP, "بافر لاگ پاک شد")
    }

    /** فایل‌های لاگ موجود روی حافظه */
    fun files(): List<File> =
        (logDir().listFiles() ?: emptyArray()).filter { it.isFile && it.name.startsWith("goldpin_log") }
            .sortedByDescending { it.lastModified() }

    fun dirPath(): String = logDir().absolutePath
}
