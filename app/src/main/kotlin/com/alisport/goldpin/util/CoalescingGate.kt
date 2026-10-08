package com.alisport.goldpin.util

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

// ═══════════════════════════════════════════════════════════════════════════════
//  CoalescingGate — «حداکثر یک کار در جریان، بقیه حذف می‌شوند»
//
//  چرا لازم است؟
//  ───────────────
//  کار دوره‌ای (تیک لایو) هر `livePollMs` یک‌بار در صف یک executor تک‌نخی گذاشته
//  می‌شود. اگر یک تیک بیشتر از فاصلهٔ تیک طول بکشد — که با فید کند کاملاً عادی است
//  (Yahoo + سه منبع قیمت لحظه‌ای با timeout = تا ~۸۰ ثانیه) — صف **بدون سقف** رشد
//  می‌کند: هر ۱۰ ثانیه یک کار جدید، در حالی که کار قبلی هنوز تمام نشده.
//
//  نتیجهٔ عملی روی گوشی: همهٔ کارهای رابط (تغییر تنظیمات → rebuildAsync، ذخیره،
//  ورود CSV، گزارش‌ها و **خروجی لاگ**) به همان executor تک‌نخی می‌روند و پشت
//  صدها تیک لایو معطل می‌مانند ⇒ «هر دکمه‌ای می‌زنم اپ فریز می‌شود و لاگ هم
//  گرفته نمی‌شود».
//
//  راه‌حل استاندارد برای کار دوره‌ای: **fixed-delay به‌جای fixed-rate** و
//  «coalesce/drop» برای کارهای تکراریِ هم‌معنی. تیک لایو idempotent است (دنبالهٔ
//  تازهٔ کندل‌ها را می‌گیرد)، پس حذف تیک اضافی هیچ داده‌ای را از بین نمی‌برد —
//  تیک بعدی همان دنباله را دوباره می‌گیرد.
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * دروازهٔ «یک کار در جریان».
 *
 * الگو:
 * ```
 * if (!gate.tryAcquire()) return      // کار قبلی هنوز تمام نشده → این یکی حذف می‌شود
 * executor.execute {
 *     try { … } finally { gate.release() }
 * }
 * ```
 *
 * ⚠ `release()` باید **دقیقاً یک‌بار** و در همهٔ مسیرها (موفق/خطا/استثنا) صدا زده شود،
 * وگرنه دروازه برای همیشه بسته می‌ماند و کار دوره‌ای از کار می‌افتد.
 */
class CoalescingGate(private val name: String = "gate") {

    private val inFlight = AtomicBoolean(false)
    private val _skipped = AtomicLong(0)
    private val _acquired = AtomicLong(0)

    /** چند بار یک کار به‌خاطر در جریان بودن کار قبلی حذف شد. */
    val skipped: Long get() = _skipped.get()

    /** چند بار دروازه با موفقیت گرفته شد (= تعداد کارهای واقعاً اجراشده/در حال اجرا). */
    val acquired: Long get() = _acquired.get()

    /**
     * @return `true` اگر این کار اجازهٔ شروع دارد؛ `false` یعنی کار قبلی هنوز در جریان است
     *         و این کار باید **حذف** شود (نه اینکه در صف بایستد).
     */
    fun tryAcquire(): Boolean {
        return if (inFlight.compareAndSet(false, true)) {
            _acquired.incrementAndGet()
            true
        } else {
            _skipped.incrementAndGet()
            false
        }
    }

    /** پایان کار — دروازه را برای کار بعدی باز می‌کند. */
    fun release() {
        inFlight.set(false)
    }

    /** آیا کاری در جریان است؟ (فقط برای لاگ/تست) */
    val busy: Boolean get() = inFlight.get()

    override fun toString(): String =
        "$name(acquired=$_acquired, skipped=$_skipped, busy=$busy)"
}
