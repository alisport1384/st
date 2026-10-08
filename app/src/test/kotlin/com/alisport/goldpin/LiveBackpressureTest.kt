package com.alisport.goldpin

import com.alisport.goldpin.util.CoalescingGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// ═══════════════════════════════════════════════════════════════════════════════
//  آزمون «فشار برگشتی» تیک لایو — همان باگی که اپ را فریز می‌کرد
//
//  سناریوی واقعی کاربر:
//    لایو را روشن می‌کند → شبکه کند/قطع است → هر تیک ده‌ها ثانیه طول می‌کشد، ولی
//    حلقهٔ سرویس هر `livePollMs` یک تیک جدید در صف executor تک‌نخی می‌گذارد. صف
//    بی‌سقف رشد می‌کند و چون **ذخیره، بازسازی موتور و خروجی لاگ هم به همان executor
//    می‌روند**، هر دکمه‌ای که کاربر می‌زند پشت صدها تیک لایو معطل می‌ماند.
//
//  `Executors.newSingleThreadExecutor()` مستنداً معادل
//  `ThreadPoolExecutor(1, 1, 0L, MILLISECONDS, LinkedBlockingQueue<>())` است، پس
//  اینجا همان ساختار را می‌سازیم تا عمق صف قابل اندازه‌گیری باشد.
// ═══════════════════════════════════════════════════════════════════════════════
class LiveBackpressureTest {

    private fun newSingleThread() =
        ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue())

    /** یک تیک لایو که مثل فید کند [workMs] میلی‌ثانیه طول می‌کشد. */
    private fun slowTick(workMs: Long, started: AtomicInteger) = Runnable {
        started.incrementAndGet()
        try { Thread.sleep(workMs) } catch (e: InterruptedException) { }
    }

    // ── ۱) بدون دروازه: صف بی‌سقف باد می‌کند ──────────────────────────────────
    @Test
    fun `ungated live ticks flood the queue that every UI action shares`() {
        val ex = newSingleThread()
        val started = AtomicInteger(0)
        val workMs = 30L
        val periodMs = 4L
        val stormMs = 240L

        var submitted = 0
        val deadline = System.currentTimeMillis() + stormMs
        while (System.currentTimeMillis() < deadline) {
            ex.execute(slowTick(workMs, started))   // دقیقاً کاری که حلقهٔ سرویس می‌کرد
            submitted++
            Thread.sleep(periodMs)
        }
        // عمق صف در لحظه‌ای که کاربر یک دکمه می‌زند
        val queuedWhenUserActs = ex.queue.size

        // کار «رابط» (مثل rebuildAsync / خروجی لاگ) پشت همان صف می‌ایستد
        val t0 = System.nanoTime()
        val gate = java.util.concurrent.CountDownLatch(1)
        ex.execute { gate.countDown() }
        assertTrue("کار رابط باید بالاخره اجرا شود", gate.await(30, TimeUnit.SECONDS))
        val uiLatencyMs = (System.nanoTime() - t0) / 1_000_000

        ex.shutdownNow()

        println("UNGATED: submitted=$submitted started=${started.get()} " +
                "queuedWhenUserActs=$queuedWhenUserActs uiLatencyMs=$uiLatencyMs")

        assertTrue("باید تیک‌های زیادی ثبت شده باشد (submitted=$submitted)", submitted >= 20)
        assertTrue(
            "بدون دروازه صف باید باد کند — ولی فقط $queuedWhenUserActs کار در صف بود",
            queuedWhenUserActs >= 10
        )
        assertTrue(
            "کار رابط باید پشت صف معطل بماند — ولی فقط ${uiLatencyMs}ms طول کشید",
            uiLatencyMs >= 150
        )
    }

    // ── ۲) با دروازه: حداکثر یک تیک در صف ─────────────────────────────────────
    @Test
    fun `gated live ticks keep at most one task queued so UI actions run immediately`() {
        val ex = newSingleThread()
        val gate = CoalescingGate("test")
        val started = AtomicInteger(0)
        val workMs = 30L
        val periodMs = 4L
        val stormMs = 240L

        var attempts = 0
        var maxQueued = 0
        val deadline = System.currentTimeMillis() + stormMs
        while (System.currentTimeMillis() < deadline) {
            attempts++
            if (gate.tryAcquire()) {
                ex.execute {
                    try { slowTick(workMs, started).run() } finally { gate.release() }
                }
            }
            maxQueued = maxOf(maxQueued, ex.queue.size)
            Thread.sleep(periodMs)
        }

        val t0 = System.nanoTime()
        val done = java.util.concurrent.CountDownLatch(1)
        ex.execute { done.countDown() }
        assertTrue("کار رابط باید اجرا شود", done.await(30, TimeUnit.SECONDS))
        val uiLatencyMs = (System.nanoTime() - t0) / 1_000_000

        ex.shutdownNow()

        println("GATED: attempts=$attempts started=${started.get()} " +
                "acquired=${gate.acquired} skipped=${gate.skipped} maxQueued=$maxQueued uiLatencyMs=$uiLatencyMs")

        assertEquals("هر تلاش باید یا گرفته شود یا حذف", attempts.toLong(),
            gate.acquired + gate.skipped)
        assertTrue("بیشتر تیک‌ها باید حذف شوند (skipped=${gate.skipped})", gate.skipped > 0)
        assertTrue(
            "هرگز نباید بیش از یک تیک در صف باشد — ولی maxQueued=$maxQueued",
            maxQueued <= 1
        )
        assertTrue(
            "کار رابط نباید پشت صف معطل بماند — ولی ${uiLatencyMs}ms طول کشید",
            uiLatencyMs <= 250
        )
    }

    // ── ۳) دروازه: هر acquire دقیقاً یک release ───────────────────────────────
    @Test
    fun `gate stays usable across acquire-release cycles`() {
        val g = CoalescingGate("cycle")
        assertFalse(g.busy)
        repeat(50) {
            assertTrue("دروازه باید آزاد باشد (دور $it)", g.tryAcquire())
            assertTrue(g.busy)
            assertFalse("تیک تکراری باید رد شود", g.tryAcquire())
            g.release()
            assertFalse(g.busy)
        }
        assertEquals(50L, g.acquired)
        assertEquals(50L, g.skipped)
        assertNotNull(g.toString())
    }
}
