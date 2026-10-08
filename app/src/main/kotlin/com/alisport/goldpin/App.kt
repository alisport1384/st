package com.alisport.goldpin

import android.os.Handler
import android.os.Looper
import android.content.Context
import com.alisport.goldpin.core.*
import com.alisport.goldpin.data.Feed
import com.alisport.goldpin.data.FeedException
import com.alisport.goldpin.data.Storage
import com.alisport.goldpin.util.Alerts
import com.alisport.goldpin.util.Fa
import com.alisport.goldpin.util.Log
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
    /**
     * فهرست کندل‌ها. به‌صورت «تعویض اتمیک» به‌روزرسانی می‌شود تا نخ رابط
     * هرگز فهرست نیمه‌ساخته نبیند (منشأ قبلی فریز و پرش).
     */
    @Volatile
    var candles: List<Candle> = emptyList()
        private set

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
    /** در حال محاسبهٔ سنگین (اجرای موتور/دانلود) — رابط پیام «در حال محاسبه…» نشان می‌دهد */
    @Volatile var busy: Boolean = false
    @Volatile var busyText: String = ""
    /** ورود خودکار به تمام‌صفحه در تب چارت */
    var autoFullscreen: Boolean = false
    private var appCtx: Context? = null

    val io = Executors.newSingleThreadExecutor()
    val main = Handler(Looper.getMainLooper())
    val listeners = ArrayList<() -> Unit>()

    // موتور در نخ IO بازسازی می‌شود و رابط در نخ اصلی می‌خواند؛ همهٔ نماها snapshot می‌گیرند.
    private val stateLock = Any()
    data class UiSnapshot(
        val candles: List<Candle>,
        val zones: List<ZoneBox>,
        val markers: List<Marker>,
        val setups: List<Setup>,
        val events: List<String>,
        val orders: List<Order>,
        val trades: List<Trade>,
        val openTrade: Trade?,
        val pendingOrder: Order?,
        val lastPocPx: Double,
        val lastEntryPx: Double,
        val lastSlPx: Double,
        val lastTpPx: Double,
        val lastResult: String
    )

    fun uiSnapshot(): UiSnapshot = synchronized(stateLock) {
        UiSnapshot(
            candles.toList(), engine.zones.toList(), engine.markers.toList(), engine.setups.toList(),
            engine.events.toList(), broker.orders.toList(), broker.trades.toList(),
            broker.openTrade, broker.pendingOrder, engine.lastPocPx, engine.lastEntryPx,
            engine.lastSlPx, engine.lastTpPx, engine.lastResult
        )
    }

    fun tradesSnapshot(): List<Trade> = synchronized(stateLock) { broker.trades.toList() }
    fun ordersSnapshot(): List<Order> = synchronized(stateLock) { broker.orders.toList() }

    init {
        engine.broker = broker
        broker.engine = engine
        engine.barCommitHook = { cd -> broker.onBar(cd, live = false) }
        wireLogging()
    }

    /** راه‌اندازی لاگر و هشدارها + وصل کردن آن‌ها به موتور و کارگزار */
    fun initApp(ctx: Context) {
        appCtx = ctx.applicationContext
        Log.init(appCtx!!, "1.3")
        Alerts.init(appCtx!!)
        val p = appCtx!!.getSharedPreferences("goldpin", Context.MODE_PRIVATE)
        // مهاجرت ۱٫۱ → ۱٫۲: تمام‌صفحهٔ خودکار دیگر پیش‌فرض نیست؛ تنظیم قدیمی true را یک‌بار خاموش کن.
        val uiVersion = p.getInt("ui_prefs_version", 0)
        autoFullscreen = if (uiVersion < 2) false else p.getBoolean("auto_fullscreen", false)
        if (uiVersion < 2) p.edit().putBoolean("auto_fullscreen", false).putInt("ui_prefs_version", 2).apply()
        Log.i(Log.CAT_APP, "AppState راه‌اندازی شد",
            "autoFullscreen=$autoFullscreen logEnabled=${Log.enabled} alerts=${Alerts.enabled}")
        wireLogging()
    }

    fun persistPrefs(ctx: Context) {
        ctx.getSharedPreferences("goldpin", Context.MODE_PRIVATE).edit()
            .putBoolean("auto_fullscreen", autoFullscreen).apply()
    }

    /**
     * لاگر و هشدار را به موتور، کارگزار و همهٔ بخش‌ها وصل می‌کند.
     * اگر لاگر خاموش باشد، قلاب‌ها null می‌شوند تا هیچ هزینه‌ای تحمیل نشود.
     */
    fun wireLogging() {
        if (Log.enabled) {
            engine.logSink = { cat, msg, data -> Log.i(cat, msg, data) }
            broker.logSink = { cat, msg, data -> Log.i(cat, msg, data) }
        } else {
            engine.logSink = null
            broker.logSink = null
        }
        val c = appCtx
        engine.alertSink = { kind, title, body -> Alerts.fire(c, kind, title, body) }
        broker.alertSink = { kind, title, body -> Alerts.fire(c, kind, title, body) }
    }

    fun onChange(f: () -> Unit) { listeners.add(f) }

    fun notifyUi() {
        val run = Runnable {
            // هر شنونده جدا محافظت می‌شود تا یک خطا کل رابط را از کار نیندازد
            for (f in ArrayList(listeners)) {
                try { f() } catch (t: Throwable) { Log.e(Log.CAT_UI, "خطا در شنوندهٔ رابط", t) }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else main.post(run)
    }

    fun offChange(f: () -> Unit) { listeners.remove(f) }

    // ── تنظیمات و بازسازی ──────────────────────────────────────────────────────
    /** بازسازی غیرهمزمان؛ تغییرات تنظیمات هرگز نخ رابط را قفل نمی‌کنند. */
    fun rebuildAsync(clearOrders: Boolean = true) {
        busy = true
        busyText = "در حال بازسازی موتور…"
        notifyUi()
        io.execute {
            try {
                rebuild(clearOrders)
            } catch (e: Throwable) {
                Log.e(Log.CAT_ENGINE, "بازسازی موتور ناموفق بود", e)
                lastFeedError = "خطای بازسازی موتور: ${e.message}"
            } finally {
                busy = false
                busyText = ""
                notifyUi()
            }
        }
    }

    /** بعد از تغییر تنظیمات: موتور و کارگزار از صفر روی دادهٔ موجود اجرا می‌شوند. */
    fun rebuild(clearOrders: Boolean = true) {
        synchronized(stateLock) {
        val t0 = System.currentTimeMillis()
        Log.i(Log.CAT_ENGINE, "شروع اجرای مجدد موتور",
            "کندل=${candles.size} مود=${cfg.tfMode} چارت=${com.alisport.goldpin.core.Tf.label(chartTfSec)} بک‌تست‌کامل=$backtestFull پاک‌کردن‌سفارش=$clearOrders")
        engine.reset()
        engine.broker = broker
        broker.engine = engine
        if (clearOrders) broker.reset()
        engine.barCommitHook = { cd -> broker.onBar(cd, live = false) }
        val prev = Alerts.inBacktest
        Alerts.inBacktest = true
        if (candles.isNotEmpty()) engine.feed(candles, lastIsClosed = backtestFull)
        Alerts.inBacktest = prev
        engine.broker = broker
        Log.i(Log.CAT_ENGINE, "اجرای موتور تمام شد",
            "مدت=${System.currentTimeMillis() - t0}ms پردازش‌شده=${engine.processed} ستاپ=${engine.cnt.setup} برخورد=${engine.cnt.touch} " +
                "میانی=${engine.cnt.mid} ولوم‌کم=${engine.cnt.lv} ولوم‌زیاد=${engine.cnt.hv} ورود=${engine.cnt.entry} پایان=${engine.cnt.done} " +
                "باکس=${engine.zones.size} سفارش=${broker.orders.size} معامله=${broker.trades.size} موجودی=${Fa.n(broker.balance, 2)}")
        notifyUi()
        }
    }

    fun setMode(modeIdx: Int) {
        Log.i(Log.CAT_CFG, "تغییر مود تایم‌فریمی", "مود=$modeIdx")
        val m = TF_MODES.firstOrNull { it.idx == modeIdx } ?: TF_MODES[0]
        busy = true
        busyText = "در حال تغییر مود تایم‌فریمی…"
        notifyUi()
        io.execute {
            try {
                cfg.tfMode = m.idx
                if (!m.custom) { cfg.tfS = m.s; cfg.tfM = m.m; cfg.tf1 = m.t1; cfg.tf2 = m.t2 }
                if (chartTfSec != cfg.tf2) {
                    chartTfSec = cfg.tf2
                    val res = Feed.aggregate(candles, chartTfSec).let {
                        if (useSyntheticVolume) Feed.synthesizeVolumeIfMissing(it) else it
                    }
                    setCandles(res, rebuildNow = false)
                }
                rebuild()
            } catch (e: Throwable) {
                Log.e(Log.CAT_ENGINE, "تغییر مود ناموفق بود", e)
                lastFeedError = "خطای تغییر مود: ${e.message}"
            } finally {
                busy = false
                busyText = ""
                notifyUi()
            }
        }
    }

    fun setCandles(list: List<Candle>, rebuildNow: Boolean = true) {
        Log.i(Log.CAT_FEED, "داده در اپ گذاشته شد", "کندل=${list.size}")
        busy = true
        try {
            candles = ArrayList(list)          // تعویض اتمیک
            if (rebuildNow) rebuild()
        } finally {
            busy = false
        }
        notifyUi()
    }

    // ── دانلود تاریخچه از فید ───────────────────────────────────────────────────
    fun downloadHistory(onDone: (String) -> Unit = {}) {
        val t0 = System.currentTimeMillis()
        busy = true
        busyText = "در حال دانلود تاریخچهٔ طلا…"
        lastFeedError = null
        notifyUi()
        Log.i(Log.CAT_FEED, "شروع دانلود تاریخچه", "نماد=$symbol چارت=${com.alisport.goldpin.core.Tf.label(chartTfSec)} عمق=$depth")
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
                Log.i(Log.CAT_FEED, "دانلود تاریخچه موفق",
                    "کندل=$n مدت=${System.currentTimeMillis() - t0}ms پایه=${com.alisport.goldpin.core.Tf.label(base)} بازه=$range")
                // اجرای موتور روی همین نخ پس‌زمینه انجام می‌شود (رابط کاربری فریز نمی‌کند)
                busyText = "در حال محاسبهٔ موتور روی ${com.alisport.goldpin.util.Fa.d(n.toString())} کندل…"
                setCandles(chart)
                main.post {
                    lastFeedAt = System.currentTimeMillis()
                    lastFeedError = null
                    busy = false
                    busyText = ""
                    notifyUi()
                    onDone("دانلود شد: ${com.alisport.goldpin.util.Fa.d(n.toString())} کندل (${com.alisport.goldpin.core.Tf.label(chartTfSec)})")
                }
            } catch (e: Exception) {
                val msg = e.message ?: "خطای نامشخص"
                Log.e(Log.CAT_FEED, "دانلود تاریخچه ناموفق", e)
                main.post {
                    lastFeedError = msg
                    busy = false
                    busyText = ""
                    notifyUi()
                    onDone("خطای دانلود: $msg")
                }
            }
        }
    }

    /** به‌روزرسانی زنده: فقط دنبالهٔ داده را می‌گیریم و با سری موجود ادغام می‌کنیم. */
    fun updateLiveOnce(notifyDone: Boolean = false, onDone: (String) -> Unit = {}) {
        val t0 = System.currentTimeMillis()
        io.execute {
            try {
                val base = Feed.baseTfFor(chartTfSec)
                val (interval, range) = Feed.yahooSpec(base, 1)
                val raw = Feed.yahooChart(symbol, interval, range)
                var tail = if (base == chartTfSec) raw else Feed.aggregate(raw, chartTfSec)
                if (useSyntheticVolume) tail = Feed.synthesizeVolumeIfMissing(tail)
                val (spot, spotSrc) = Feed.spotPrice()
                val last = tail.lastOrNull()?.c ?: Double.NaN
                Log.d(Log.CAT_LIVE, "به‌روزرسانی زنده",
                    "دنباله=${tail.size} قیمت=${if (spot != null) Fa.n(spot, 2) else "-"} منبع=$spotSrc مدت=${System.currentTimeMillis() - t0}ms")
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
                Log.w(Log.CAT_LIVE, "به‌روزرسانی زنده ناموفق", e.message ?: "")
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
        val before = candles.size
        if (candles.isEmpty()) { setCandles(tail, rebuildNow = true); return }
        val firstNew = tail.first().t
        // حذف بخش هم‌پوشان از انتهای سری فعلی
        var cut = candles.size
        while (cut > 0 && candles[cut - 1].t >= firstNew) cut--
        val merged = ArrayList<Candle>(cut + tail.size)
        for (i in 0 until cut) merged.add(candles[i])
        for (c in tail) merged.add(c)
        candles = merged.mapIndexed { i, c -> Candle(i, c.t, c.o, c.h, c.l, c.c, c.v) }
        Log.d(Log.CAT_LIVE, "ادغام دنبالهٔ داده", "قبل=$before بعد=${candles.size} جدید=${candles.size - before}")
    }

    /** تغییر تایم‌فریم چارت بدون دانلود مجدد (تجمیع داخلی) */
    fun changeChartTf(newTf: Int) {
        if (newTf == chartTfSec) return
        Log.i(Log.CAT_CFG, "تغییر تایم‌فریم چارت", "${com.alisport.goldpin.core.Tf.label(chartTfSec)} → ${com.alisport.goldpin.core.Tf.label(newTf)}")
        chartTfSec = newTf
        cfg.tf2 = newTf
        io.execute {
            val base = Feed.baseTfFor(newTf)
            try {
                val (interval, range) = Feed.yahooSpec(base, depth)
                var raw = Feed.yahooChart(symbol, interval, range)
                var chart = if (base == newTf) raw else Feed.aggregate(raw, newTf)
                if (useSyntheticVolume) chart = Feed.synthesizeVolumeIfMissing(chart)
                setCandles(chart)
            } catch (e: Exception) {
                // بدون شبکه: از دادهٔ موجود تجمیع می‌کنیم
                val chart = Feed.aggregate(candles, newTf)
                lastFeedError = "تجمیع داخلی از دادهٔ موجود: ${e.message}"
                setCandles(chart)
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
        val t0 = System.currentTimeMillis()
        return try {
            busy = true
            val json = buildJson()
            val main = Storage.autoFile(ctx)
            try {   // پشتیبان از آخرین نسخهٔ سالم
                if (main.exists()) {
                    val tmp = java.io.File(main.parentFile, main.name + ".tmp")
                    main.copyTo(tmp, overwrite = true)
                    val bak = java.io.File(main.parentFile, main.name + ".bak")
                    if (bak.exists()) bak.delete()
                    tmp.renameTo(bak)
                }
            } catch (e: Exception) { Log.w(Log.CAT_STORE, "نسخهٔ پشتیبان ساخته نشد", e.message ?: "") }
            Storage.writeText(main, json)
            Log.i(Log.CAT_STORE, "ذخیرهٔ خودکار انجام شد",
                "بایت=${json.length} کندل=${candles.size} باکس=${engine.zones.size} سفارش=${broker.orders.size} معامله=${broker.trades.size} مدت=${System.currentTimeMillis() - t0}ms")
            "ذخیره شد: ${Storage.autoFile(ctx).absolutePath}"
        } catch (e: Exception) {
            Log.e(Log.CAT_STORE, "ذخیرهٔ خودکار ناموفق", e)
            "خطای ذخیره: ${e.message}"
        } finally {
            busy = false
        }
    }

    fun loadFromAutoFile(ctx: Context): String {
        val f: File = Storage.autoFile(ctx)
        if (!f.exists()) return "فایل ذخیره‌ای پیدا نشد"
        return try {
            loadFromText(Storage.readText(f))
        } catch (e: Exception) {
            // هرگز اپ را با فایل خراب از کار نینداز؛ فقط اطلاع بده
            Log.e(Log.CAT_STORE, "بازیابی خودکار ناموفق بود", e)
            "بازیابی نشد: ${e.message}"
        }
    }

    /** بارگذاری کامل — وضعیت دقیقاً همان‌طور که ذخیره شده بود برمی‌گردد. */
    fun loadFromText(json: String): String {
        val t0 = System.currentTimeMillis()
        Log.i(Log.CAT_STORE, "شروع بازیابی وضعیت", "بایت=${json.length}")
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
            candles = ArrayList(ld.candles)
            val m = ld.meta
            symbol = m["symbol"]?.toString() ?: symbol
            baseTfSec = (m["baseTfSec"] as? Long)?.toInt() ?: baseTfSec
            chartTfSec = (m["chartTfSec"] as? Long)?.toInt() ?: cfg.tf2
            depth = (m["depth"] as? Long)?.toInt() ?: depth
            notifyUi()
            Log.i(Log.CAT_STORE, "بازیابی موفق",
                "کندل=${candles.size} باکس=${engine.zones.size} (فعال=${engine.zones.count { it.status == ZoneStatus.ACTIVE }} " +
                    "پاک‌شده=${engine.zones.count { it.status == ZoneStatus.DELETED }} ردشده=${engine.zones.count { it.status == ZoneStatus.REJECTED }}) " +
                    "سفارش=${broker.orders.size} معامله=${broker.trades.size} موجودی=${Fa.n(broker.balance, 2)} مدت=${System.currentTimeMillis() - t0}ms")
            "بازیابی شد: ${com.alisport.goldpin.util.Fa.d(candles.size.toString())} کندل · " +
                    "${com.alisport.goldpin.util.Fa.d(broker.trades.size.toString())} معامله · " +
                    "${com.alisport.goldpin.util.Fa.d(engine.zones.size.toString())} باکس ناحیه"
        } catch (e: Exception) {
            Log.e(Log.CAT_STORE, "بازیابی ناموفق", e)
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
        Log.w(Log.CAT_APP, "پاک کردن همهٔ داده‌ها و وضعیت")
        candles = emptyList()
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
                    Log.i(Log.CAT_FEED, "دادهٔ نمونهٔ داخلی بارگذاری شد", "کندل=${list.size}")
                    setCandles(list)
                    main.post {
                        onDone("نمونهٔ داخلی بارگذاری شد: ${com.alisport.goldpin.util.Fa.d(list.size.toString())} کندل")
                    }
                }
            } catch (e: Exception) {
                Log.e(Log.CAT_FEED, "خطا در دادهٔ نمونه", e)
                main.post { onDone("خطا در نمونهٔ داخلی: ${e.message}") }
            }
        }
    }

    /** ورود CSV از حافظهٔ دستگاه */
    fun importCsv(ctx: Context, uri: android.net.Uri, baseTfGuess: Int, onDone: (String) -> Unit) {
        Log.i(Log.CAT_FEED, "ورود CSV", "uri=$uri پایه=${com.alisport.goldpin.core.Tf.label(baseTfGuess)}")
        io.execute {
            try {
                val text = Storage.readStream(ctx.contentResolver.openInputStream(uri)!!, gz = false)
                var list = Feed.parseCsv(text)
                baseTfSec = baseTfGuess
                if (baseTfGuess != chartTfSec) list = Feed.aggregate(list, chartTfSec)
                if (useSyntheticVolume) list = Feed.synthesizeVolumeIfMissing(list)
                setCandles(list)
                main.post {
                    onDone("وارد شد: ${com.alisport.goldpin.util.Fa.d(list.size.toString())} کندل")
                }
            } catch (e: Exception) {
                Log.e(Log.CAT_FEED, "خطای ورود فایل CSV", e)
                main.post { onDone("خطای ورود فایل: ${e.message}") }
            }
        }
    }

    companion object {
        val instance = AppState()

        /** از MainActivity فراخوانی می‌شود: لاگر + هشدارها را آماده و وصل می‌کند */
        fun init(ctx: Context) = instance.initApp(ctx)
    }
}
