package com.alisport.goldpin

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.alisport.goldpin.core.*
import com.alisport.goldpin.data.Feed
import com.alisport.goldpin.data.FeedException
import com.alisport.goldpin.data.Storage
import java.io.File
import java.util.concurrent.Executors

// ═══════════════════════════════════════════════════════════════════════════════
//  وضعیت سراسری اپ — موتور، کارگزار، کندل‌ها، تنظیمات فید و ذخیره/بازیابی
// ═══════════════════════════════════════════════════════════════════════════════
class AppState {

    val cfg = Settings()
    val engine = Engine(cfg)
    val broker = PaperBroker(cfg)

    /** کندل‌های تایم‌فریم چارت (تریگر ۲) که موتور روی آن‌ها اجرا می‌شود */
    val candles = ArrayList<Candle>()

    // تنظیمات فید (اپ)
    var symbol: String = Feed.SYMBOL_DEFAULT
    var baseTfSec: Int = Tf.M1
    var chartTfSec: Int = Tf.M1
    var depth: Int = 2                 // ۱=کوتاه ، ۲=متوسط ، ۳=عمیق
    var useSyntheticVolume: Boolean = true
    var livePollMs: Long = 10000L
    var liveRunning: Boolean = false
    var lastLivePrice: Double = Double.NaN
    var lastFeedAt: Long = 0L
    var lastFeedError: String? = null
    var lastPriceSource: String = "—"
    var autosaveEveryMs: Long = 120000L

    /**
     * حالت بک‌تست کامل: آخرین کندل هم بسته حساب می‌شود (دادهٔ تاریخی).
     * در حالت لایو، آخرین کندل «باز» می‌ماند و فقط پس از بسته شدن پردازش می‌شود
     * (سفارش‌ها اما در همان لحظه با قیمت زنده پر می‌شوند).
     */
    var backtestFull: Boolean = true

    val io = Executors.newSingleThreadExecutor()
    val main = Handler(Looper.getMainLooper())
    val listeners = ArrayList<() -> Unit>()

    init {
        engine.broker = broker
        broker.engine = engine
        engine.barCommitHook = { cd -> broker.onBar(cd, live = false) }
    }

    fun onChange(f: () -> Unit) { listeners.add(f) }

    fun notifyUi() {
        if (Looper.myLooper() == Looper.getMainLooper()) listeners.forEach { it() }
        else main.post { listeners.forEach { it() } }
    }

    fun offChange(f: () -> Unit) { listeners.remove(f) }

    // ── تنظیمات و بازسازی ──────────────────────────────────────────────────────
    /** بعد از تغییر تنظیمات: موتور و کارگزار از صفر روی دادهٔ موجود اجرا می‌شوند. */
    fun rebuild(clearOrders: Boolean = true) {
        engine.reset()
        engine.broker = broker
        broker.engine = engine
        if (clearOrders) broker.reset()
        engine.barCommitHook = { cd -> broker.onBar(cd, live = false) }
        if (candles.isNotEmpty()) engine.feed(candles, lastIsClosed = backtestFull)
        engine.broker = broker
        notifyUi()
    }

    fun setMode(modeIdx: Int) {
        val m = TF_MODES.firstOrNull { it.idx == modeIdx } ?: TF_MODES[0]
        cfg.tfMode = m.idx
        if (!m.custom) { cfg.tfS = m.s; cfg.tfM = m.m; cfg.tf1 = m.t1; cfg.tf2 = m.t2 }
        if (chartTfSec != cfg.tf2) {
            chartTfSec = cfg.tf2
            setCandles(Feed.aggregate(candles, chartTfSec).let { res ->
                if (useSyntheticVolume) Feed.synthesizeVolumeIfMissing(res) else res
            }, rebuildNow = false)
        }
        rebuild()
    }

    fun setCandles(list: List<Candle>, rebuildNow: Boolean = true) {
        candles.clear()
        candles.addAll(list)
        if (rebuildNow) rebuild()
        notifyUi()
    }

    // ── دانلود تاریخچه از فید ───────────────────────────────────────────────────
    fun downloadHistory(onDone: (String) -> Unit = {}) {
        io.execute {
            try {
                val base = Feed.baseTfFor(chartTfSec)
                baseTfSec = base
                val (interval, range) = Feed.yahooSpec(base, depth)
                var raw = Feed.yahooChart(symbol, interval, range)
                if (raw.size < 50) {
                    // تلاش دوباره با بازهٔ بزرگ‌تر
                    raw = Feed.yahooChart(symbol, "1m", "7d")
                }
                var chart = if (base == chartTfSec) raw else Feed.aggregate(raw, chartTfSec)
                if (useSyntheticVolume) chart = Feed.synthesizeVolumeIfMissing(chart)
                val n = chart.size
                main.post {
                    setCandles(chart)
                    lastFeedAt = System.currentTimeMillis()
                    lastFeedError = null
                    onDone("دانلود شد: ${com.alisport.goldpin.util.Fa.d(n.toString())} کندل (${com.alisport.goldpin.core.Tf.label(chartTfSec)})")
                }
            } catch (e: Exception) {
                val msg = e.message ?: "خطای نامشخص"
                main.post {
                    lastFeedError = msg
                    notifyUi()
                    onDone("خطای دانلود: $msg")
                }
            }
        }
    }

    /** به‌روزرسانی زنده: فقط دنبالهٔ داده را می‌گیریم و با سری موجود ادغام می‌کنیم. */
    fun updateLiveOnce(notifyDone: Boolean = false, onDone: (String) -> Unit = {}) {
        io.execute {
            try {
                val base = Feed.baseTfFor(chartTfSec)
                val (interval, range) = Feed.yahooSpec(base, 1)
                val raw = Feed.yahooChart(symbol, interval, range)
                var tail = if (base == chartTfSec) raw else Feed.aggregate(raw, chartTfSec)
                if (useSyntheticVolume) tail = Feed.synthesizeVolumeIfMissing(tail)
                val (spot, spotSrc) = Feed.spotPrice()
                val last = tail.lastOrNull()?.c ?: Double.NaN
                main.post {
                    mergeTail(tail)
                    lastLivePrice = if (spot != null && !spot.isNaN()) spot else last
                    lastPriceSource = spotSrc
                    lastFeedAt = System.currentTimeMillis()
                    lastFeedError = null
                    engine.broker = broker
                    engine.barCommitHook = { cd -> broker.onBar(cd, live = false) }
                    engine.feed(candles, lastIsClosed = false)
                    candles.lastOrNull()?.let { broker.onBar(it, live = true) }
                    notifyUi()
                    if (notifyDone) onDone("به‌روزرسانی شد · ${com.alisport.goldpin.util.Fa.n(lastLivePrice, 2)}")
                }
            } catch (e: Exception) {
                main.post {
                    lastFeedError = e.message ?: "خطای فید"
                    notifyUi()
                    if (notifyDone) onDone("خطای فید: ${lastFeedError}")
                }
            }
        }
    }

    /** ادغام دنبالهٔ تازه در سری چارت (بر اساس زمان — بدون تکرار و بدون ریپینت) */
    fun mergeTail(tail: List<Candle>) {
        if (tail.isEmpty()) return
        if (candles.isEmpty()) { setCandles(tail, rebuildNow = true); return }
        val firstNew = tail.first().t
        // حذف بخش هم‌پوشان از انتهای سری فعلی
        var cut = candles.size
        while (cut > 0 && candles[cut - 1].t >= firstNew) cut--
        val merged = ArrayList<Candle>(cut + tail.size)
        for (i in 0 until cut) merged.add(candles[i])
        for (c in tail) merged.add(c)
        candles.clear()
        candles.addAll(merged.mapIndexed { i, c -> Candle(i, c.t, c.o, c.h, c.l, c.c, c.v) })
    }

    /** تغییر تایم‌فریم چارت بدون دانلود مجدد (تجمیع داخلی) */
    fun changeChartTf(newTf: Int) {
        if (newTf == chartTfSec) return
        chartTfSec = newTf
        cfg.tf2 = newTf
        io.execute {
            val base = Feed.baseTfFor(newTf)
            try {
                val (interval, range) = Feed.yahooSpec(base, depth)
                var raw = Feed.yahooChart(symbol, interval, range)
                var chart = if (base == newTf) raw else Feed.aggregate(raw, newTf)
                if (useSyntheticVolume) chart = Feed.synthesizeVolumeIfMissing(chart)
                main.post { setCandles(chart) }
            } catch (e: Exception) {
                // بدون شبکه: از دادهٔ موجود تجمیع می‌کنیم
                val chart = Feed.aggregate(candles, newTf)
                main.post {
                    lastFeedError = "تجمیع داخلی از دادهٔ موجود: ${e.message}"
                    setCandles(chart)
                }
            }
        }
    }

    // ── ذخیره / بازیابی ────────────────────────────────────────────────────────
    fun buildJson(): String {
        val meta = linkedMapOf<String, Any?>(
            "symbol" to symbol,
            "baseTfSec" to baseTfSec,
            "chartTfSec" to chartTfSec,
            "depth" to depth,
            "appVersion" to 1
        )
        return Store.save(cfg, engine, broker, candles, meta)
    }

    fun saveToAutoFile(ctx: Context): String {
        return try {
            val json = buildJson()
            Storage.writeText(Storage.autoFile(ctx), json)
            "ذخیره شد: ${Storage.autoFile(ctx).absolutePath}"
        } catch (e: Exception) {
            "خطای ذخیره: ${e.message}"
        }
    }

    fun loadFromAutoFile(ctx: Context): String {
        val f: File = Storage.autoFile(ctx)
        if (!f.exists()) return "فایل ذخیره‌ای پیدا نشد"
        return loadFromText(Storage.readText(f))
    }

    /** بارگذاری کامل — وضعیت دقیقاً همان‌طور که ذخیره شده بود برمی‌گردد. */
    fun loadFromText(json: String): String {
        return try {
            val ld = Store.load(json)
            copySettings(ld.cfg, cfg)
            engine.reset()
            broker.reset()
            // انتقال وضعیت بازیابی‌شده به نمونه‌های سراسری
            copyEngine(ld.eng, engine)
            copyBroker(ld.broker, broker)
            engine.broker = broker
            broker.engine = engine
            engine.barCommitHook = { cd -> broker.onBar(cd, live = false) }
            candles.clear(); candles.addAll(ld.candles)
            val m = ld.meta
            symbol = m["symbol"]?.toString() ?: symbol
            baseTfSec = (m["baseTfSec"] as? Long)?.toInt() ?: baseTfSec
            chartTfSec = (m["chartTfSec"] as? Long)?.toInt() ?: cfg.tf2
            depth = (m["depth"] as? Long)?.toInt() ?: depth
            notifyUi()
            "بازیابی شد: ${com.alisport.goldpin.util.Fa.d(candles.size.toString())} کندل · " +
                    "${com.alisport.goldpin.util.Fa.d(broker.trades.size.toString())} معامله · " +
                    "${com.alisport.goldpin.util.Fa.d(engine.zones.size.toString())} باکس ناحیه"
        } catch (e: Exception) {
            "خطای بازیابی: ${e.message}"
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun copySettings(from: Settings, to: Settings) {
        val c = from.copy()
        to.tfMode = c.tfMode; to.tfS = c.tfS; to.tfM = c.tfM; to.tf1 = c.tf1; to.tf2 = c.tf2
        to.vpRows = c.vpRows; to.vpVA = c.vpVA; to.vpSmooth = c.vpSmooth; to.distMode = c.distMode
        to.minZoneRows = c.minZoneRows; to.drawZones = c.drawZones; to.showRejectedZones = c.showRejectedZones
        to.clearUsedZones = c.clearUsedZones; to.maxZoneBoxes = c.maxZoneBoxes; to.showPocVA = c.showPocVA
        to.maxSetups = c.maxSetups; to.midInvalidClose = c.midInvalidClose
        to.hvScanFirstTouch = c.hvScanFirstTouch; to.hvMarkFirstIncrease = c.hvMarkFirstIncrease
        to.mintick = c.mintick; to.slBufTicks = c.slBufTicks; to.minTPunits = c.minTPunits
        to.useRiskPct = c.useRiskPct; to.riskPct = c.riskPct; to.equityPct = c.equityPct
        to.maxLeverage = c.maxLeverage; to.roundQty = c.roundQty; to.maxBarsToFill = c.maxBarsToFill
        to.contractSize = c.contractSize; to.initialEquity = c.initialEquity
    }

    /** کپی وضعیت موتورِ بازگردانی‌شده به موتور سراسری (تا ارجاع‌ها یکسان بماند) */
    private fun copyEngine(src: Engine, dst: Engine) {
        dst.aggS.reset(); dst.aggM.reset(); dst.agg1.reset()
        copyAgg(src.aggS, dst.aggS); copyAgg(src.aggM, dst.aggM); copyAgg(src.agg1, dst.agg1)
        dst.structBars.clear(); dst.structBars.addAll(src.structBars)
        dst.microS.clear(); dst.microS.addAll(src.microS)
        dst.pins.clear(); dst.pins.addAll(src.pins)
        dst.setups.clear(); dst.setups.addAll(src.setups)
        dst.zones.clear(); dst.zones.addAll(src.zones)
        dst.markers.clear(); dst.markers.addAll(src.markers)
        dst.events.clear(); dst.events.addAll(src.events)
        dst.cnt.setup = src.cnt.setup; dst.cnt.touch = src.cnt.touch; dst.cnt.mid = src.cnt.mid
        dst.cnt.lv = src.cnt.lv; dst.cnt.hv = src.cnt.hv; dst.cnt.entry = src.cnt.entry
        dst.cnt.done = src.cnt.done; dst.cnt.zoneCount = src.cnt.zoneCount; dst.cnt.zoneRejected = src.cnt.zoneRejected
        dst.trend = src.trend; dst.HH = src.HH; dst.HL = src.HL; dst.LL = src.LL; dst.LH = src.LH
        dst.seqStart = src.seqStart
        dst.bullBU1Low = src.bullBU1Low; dst.bullBEHigh = src.bullBEHigh
        dst.bullBU2Low = src.bullBU2Low; dst.bullBU2Bi = src.bullBU2Bi
        dst.bearBE1High = src.bearBE1High; dst.bearBULow = src.bearBULow
        dst.bearBE2High = src.bearBE2High; dst.bearBE2Bi = src.bearBE2Bi
        dst.bullSetup = src.bullSetup; dst.bearSetup = src.bearSetup
        dst.expectedReady = src.expectedReady
        dst.lowestBUpin = src.lowestBUpin; dst.highestBE = src.highestBE
        dst.lastBUpin = src.lastBUpin; dst.lastBEpin = src.lastBEpin
        dst.lastZoneTop = src.lastZoneTop; dst.lastZoneBot = src.lastZoneBot
        dst.lastPocPx = src.lastPocPx; dst.lastProfBars = src.lastProfBars
        dst.lastEntryPx = src.lastEntryPx; dst.lastSlPx = src.lastSlPx; dst.lastTpPx = src.lastTpPx
        dst.lastResult = src.lastResult
        dst.prevT1Vol = src.prevT1Vol; dst.prevChartVol = src.prevChartVol
        dst.zoneSeq = src.zoneSeq; dst.setupSeq = src.setupSeq; dst.processed = src.processed
        dst.curBi = src.curBi; dst.curT = src.curT
    }

    private fun copyAgg(s: Agg, d: Agg) {
        d.reset()
        d.periods = s.periods
        d.cur = s.cur?.let { Candle(it.bi, it.t, it.o, it.h, it.l, it.c, it.v) }
        d.cl = s.cl?.let { Candle(it.bi, it.t, it.o, it.h, it.l, it.c, it.v) }
        d.closedNow = false
    }

    private fun copyBroker(src: PaperBroker, dst: PaperBroker) {
        dst.orders.clear(); dst.orders.addAll(src.orders)
        dst.trades.clear(); dst.trades.addAll(src.trades)
        dst.balance = src.balance; dst.equity = src.equity
        dst.maxEquity = src.maxEquity; dst.maxDrawdown = src.maxDrawdown
        dst.orderSeq = src.orderSeq; dst.tradeSeq = src.tradeSeq
        dst.restorePending(src.pendingOrder)
        dst.restoreOpen(src.openTrade)
    }

    /** پاک کردن کامل */
    fun wipe() {
        candles.clear()
        broker.reset()
        engine.reset()
        engine.broker = broker; broker.engine = engine
        engine.barCommitHook = { cd -> broker.onBar(cd, live = false) }
        notifyUi()
    }

    /** بارگذاری دادهٔ نمونهٔ داخلی (آفلاین) */
    fun loadSample(ctx: Context, onDone: (String) -> Unit) {
        io.execute {
            try {
                Feed.openSampleAsset(ctx).use { ins ->
                    val text = Feed.readCsvStream(ins)
                    var list = Feed.parseCsv(text)
                    list = Feed.aggregate(list, chartTfSec)
                    main.post {
                        setCandles(list)
                        onDone("نمونهٔ داخلی بارگذاری شد: ${com.alisport.goldpin.util.Fa.d(list.size.toString())} کندل")
                    }
                }
            } catch (e: Exception) {
                main.post { onDone("خطا در نمونهٔ داخلی: ${e.message}") }
            }
        }
    }

    /** ورود CSV از حافظهٔ دستگاه */
    fun importCsv(ctx: Context, uri: android.net.Uri, baseTfGuess: Int, onDone: (String) -> Unit) {
        io.execute {
            try {
                val text = Storage.readStream(ctx.contentResolver.openInputStream(uri)!!, gz = false)
                var list = Feed.parseCsv(text)
                baseTfSec = baseTfGuess
                if (baseTfGuess != chartTfSec) list = Feed.aggregate(list, chartTfSec)
                if (useSyntheticVolume) list = Feed.synthesizeVolumeIfMissing(list)
                main.post {
                    setCandles(list)
                    onDone("وارد شد: ${com.alisport.goldpin.util.Fa.d(list.size.toString())} کندل")
                }
            } catch (e: Exception) {
                main.post { onDone("خطای ورود فایل: ${e.message}") }
            }
        }
    }

    companion object {
        val instance = AppState()
    }
}
