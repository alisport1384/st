package com.alisport.goldpin.core

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// ═══════════════════════════════════════════════════════════════════════════════
//  موتور PinReady — پورت کامل اسکریپت Pine «PinReady_FRVP_Strategy»
//  زنجیره: کندل مهم → Ready(BU/BE) → pinBU/pinBE → روند(HH/HL/LL/LH)
//          → ناحیهٔ فیکس‌رنج (الگوریتم v2) → تایید میانی → ناحیهٔ ولوم کم (تریگر۱)
//          → ناحیهٔ ولوم زیاد (تریگر۲ / چارت) → ورود / حدضرر / حدسود / سر‌به‌سر
//
//  ⚠ موتور فقط با کلوز کندل‌ها تصمیم می‌گیرد (بدون ریپینت). کندل باز فقط پیش‌نمایش است.
// ═══════════════════════════════════════════════════════════════════════════════

class Settings {
    var tfMode: Int = 1
    var tfS: Int = Tf.H1
    var tfM: Int = Tf.M15
    var tf1: Int = Tf.M5
    var tf2: Int = Tf.M1

    var vpRows: Int = 24
    var vpVA: Double = 10.0
    var vpSmooth: Int = 1
    var distMode: Int = VolumeProfile.DIST_TRI_CLOSE
    var minZoneRows: Int = 1
    var drawZones: Boolean = true
    var showRejectedZones: Boolean = true
    var clearUsedZones: Boolean = true
    var maxZoneBoxes: Int = 24
    var showPocVA: Boolean = true

    var maxSetups: Int = 6
    /**
     * `true`  ⇒ تایید میانی فقط با **بسته شدن** کندل میانی آن‌طرف سطح باطل می‌شود.
     * `false` ⇒ با **عبور شمع** باطل می‌شود، حتی یک تیک و حتی اگر برگردد و آن‌طرف بسته نشود.
     *
     * پیش‌فرض پیشین `true` بود. مطابق قاعدهٔ کارفرما به `false` تغییر کرد: عبور، حتی
     * یک تیک، تایید را باطل می‌کند.
     */
    var midInvalidClose: Boolean = false       // true=شکست با کلوز ، false=با سایه
    var hvScanFirstTouch: Boolean = true      // true=از اولین برگشت به باکس ولوم کم
    var hvMarkFirstIncrease: Boolean = true   // true=اولین افزایش حجم نسبت به کندل قبل ، false=RunningMax
    var rejectCandleNext: Boolean = false

    var mintick: Double = 0.01
    var slBufTicks: Double = 2.0
    var minTPunits: Double = 10.0

    var useRiskPct: Boolean = true
    var riskPct: Double = 1.0
    var equityPct: Double = 100.0
    var maxLeverage: Double = 5.0
    var roundQty: Boolean = false
    var maxBarsToFill: Int = 150
    var contractSize: Double = 1.0
    var initialEquity: Double = 10000.0

    fun copy(): Settings = Settings().also {
        it.tfMode = tfMode; it.tfS = tfS; it.tfM = tfM; it.tf1 = tf1; it.tf2 = tf2
        it.vpRows = vpRows; it.vpVA = vpVA; it.vpSmooth = vpSmooth; it.distMode = distMode
        it.minZoneRows = minZoneRows; it.drawZones = drawZones; it.showRejectedZones = showRejectedZones
        it.clearUsedZones = clearUsedZones; it.maxZoneBoxes = maxZoneBoxes; it.showPocVA = showPocVA
        it.maxSetups = maxSetups; it.midInvalidClose = midInvalidClose
        it.hvScanFirstTouch = hvScanFirstTouch; it.hvMarkFirstIncrease = hvMarkFirstIncrease
        it.rejectCandleNext = rejectCandleNext
        it.mintick = mintick; it.slBufTicks = slBufTicks; it.minTPunits = minTPunits
        it.useRiskPct = useRiskPct; it.riskPct = riskPct; it.equityPct = equityPct
        it.maxLeverage = maxLeverage; it.roundQty = roundQty; it.maxBarsToFill = maxBarsToFill
        it.contractSize = contractSize; it.initialEquity = initialEquity
    }
}

class Counters {
    var setup = 0; var touch = 0; var mid = 0; var lv = 0; var hv = 0; var entry = 0; var done = 0
    var zoneCount = 0; var zoneRejected = 0
}

class Engine(val cfg: Settings) {

    // ── وضعیت ──────────────────────────────────────────────────────────────────
    val aggS = Agg(); val aggM = Agg(); val agg1 = Agg()
    val structBars = ArrayList<Candle>()
    val pins = ArrayList<Pin>()
    val setups = ArrayList<Setup>()
    val microS = ArrayList<Candle>()

    val zones = ArrayList<ZoneBox>()          // همهٔ باکس‌ها با چرخهٔ عمر (برای ذخیره/بازیابی دقیق)
    val markers = ArrayList<Marker>()         // برچسب‌های استراتژی روی چارت
    val events = ArrayList<String>()          // گزارش رویدادها
    val cnt = Counters()

    var trend = 0
    var HH = Double.NaN; var HL = Double.NaN; var LL = Double.NaN; var LH = Double.NaN
    var seqStart = 0
    var bullBU1Low = Double.NaN; var bullBEHigh = Double.NaN; var bullBU2Low = Double.NaN; var bullBU2Bi = -1
    var bearBE1High = Double.NaN; var bearBULow = Double.NaN; var bearBE2High = Double.NaN; var bearBE2Bi = -1
    var bullSetup = false; var bearSetup = false
    var expectedReady = 0
    var lowestBUpin = Double.NaN; var highestBE = Double.NaN
    var lastBUpin = Double.NaN; var lastBEpin = Double.NaN

    // ── docs/19: چرخهٔ عمر پین‌ها و روندِ توالی‌محور ────────────────────────────
    /** `lastBUpin`/`lastBEpin` **سطح** هستند و حتی بعد از ابطال پین باقی می‌مانند
     *  (بند ۵-۱: پینِ حذف‌شده تا تشکیل پین جدید نقش HL/LH را نگه می‌دارد).
     *  `buActive`/`beActive` می‌گویند آیا پین هنوز **معتبر** است. */
    var buActive = false; var beActive = false
    /** شمارهٔ توالی: با هر پینِ تاییدشده یکی زیاد می‌شود تا بدانیم کدام بعد از کدام آمده. */
    var pinSeq = 0; var buSeq = -1; var beSeq = -1
    /** `ref` و لبهٔ دیگرِ کندل مهم — برای بند ۳ (ابطال بعد از Ready). */
    var lastBUpinRef = Double.NaN; var lastBUpinHi = Double.NaN
    var lastBEpinRef = Double.NaN; var lastBEpinLo = Double.NaN

    var lastZoneTop = Double.NaN; var lastZoneBot = Double.NaN
    var lastPocPx = Double.NaN; var lastProfBars = 0
    var profileOnLastS: Profile? = null

    var lastEntryPx = Double.NaN; var lastSlPx = Double.NaN; var lastTpPx = Double.NaN
    var lastResult = "-"
    var stageFa = "-"

    var prevT1Vol = Double.NaN
    var zoneSeq = 0L
    var setupSeq = 0L
    var processed = 0
    var curBi = -1
    var curT = 0L
    var curVol = 0.0
    var prevChartVol = Double.NaN

    // پیش‌نمایش زنده (کندل باز) — فقط برای نمایش، بدون تغییر وضعیت
    val livePreview = ArrayList<String>()
    var liveCandle: Candle? = null

    var broker: PaperBroker? = null

    /** قلاب همگام‌سازی: بعد از پردازش هر کندل بسته فراخوانی می‌شود (برای کارگزار کاغذی). */
    var barCommitHook: ((Candle) -> Unit)? = null

    // ── قلاب‌های لاگر و هشدار (اپ آن‌ها را وصل می‌کند؛ موتور خالص می‌ماند) ──
    /** (دسته ، پیام ، داده) */
    var logSink: ((String, String, String?) -> Unit)? = null
    /** (نوع هشدار AlertKind ، عنوان ، توضیح) */
    var alertSink: ((String, String, String) -> Unit)? = null

    private fun lg(cat: String, msg: String, data: String? = null) {
        logSink?.invoke(cat, msg, data)
    }

    private fun alarm(kind: String, title: String, body: String) {
        alertSink?.invoke(kind, title, body)
    }

    val tf2: Int get() = cfg.tf2

    fun reset() {
        aggS.reset(); aggM.reset(); agg1.reset()
        structBars.clear(); pins.clear(); setups.clear(); microS.clear()
        zones.clear(); markers.clear(); events.clear()
        trend = 0; HH = Double.NaN; HL = Double.NaN; LL = Double.NaN; LH = Double.NaN
        seqStart = 0
        bullBU1Low = Double.NaN; bullBEHigh = Double.NaN; bullBU2Low = Double.NaN; bullBU2Bi = -1
        bearBE1High = Double.NaN; bearBULow = Double.NaN; bearBE2High = Double.NaN; bearBE2Bi = -1
        bullSetup = false; bearSetup = false; expectedReady = 0
        lowestBUpin = Double.NaN; highestBE = Double.NaN; lastBUpin = Double.NaN; lastBEpin = Double.NaN
        buActive = false; beActive = false
        pinSeq = 0; buSeq = -1; beSeq = -1
        lastBUpinRef = Double.NaN; lastBUpinHi = Double.NaN
        lastBEpinRef = Double.NaN; lastBEpinLo = Double.NaN
        lastZoneTop = Double.NaN; lastZoneBot = Double.NaN; lastPocPx = Double.NaN; lastProfBars = 0
        profileOnLastS = null
        lastEntryPx = Double.NaN; lastSlPx = Double.NaN; lastTpPx = Double.NaN
        lastResult = "-"; stageFa = "-"
        prevT1Vol = Double.NaN; zoneSeq = 0; setupSeq = 0
        processed = 0; curBi = -1; curT = 0; curVol = 0.0; prevChartVol = Double.NaN
        livePreview.clear(); liveCandle = null
    }

    // ── ورود داده ──────────────────────────────────────────────────────────────
    /**
     * پردازش لیست کندل‌های تایم‌فریم تریگر۲ (چارت).
     * همهٔ کندل‌ها به‌جز آخری «بسته» در نظر گرفته می‌شوند؛ آخری فقط پیش‌نمایش زنده است.
     */
    fun feed(candles: List<Candle>, lastIsClosed: Boolean = false) {
        if (candles.isEmpty()) return
        val lastIdx = candles.size - 1
        var i = processed
        while (i <= lastIdx) {
            val cd = candles[i]
            val isLast = i == lastIdx
            if (!isLast || lastIsClosed) {
                processClosed(cd, i, candles.getOrNull(i - 1))
                processed = i + 1
            } else {
                updateLive(cd, i, candles.getOrNull(i - 1))
            }
            i++
        }
    }

    /** بارگذاری کامل از صفر (برای بک‌تست). همهٔ کندل‌ها بسته‌اند. */
    fun runAll(candles: List<Candle>) {
        reset()
        feed(candles, lastIsClosed = true)
    }

    // ── پیش‌نمایش کندل باز (بدون تغییر وضعیت) ────────────────────────────────────
    /**
     * ⚠ این متد **هیچ** تغییری در وضعیت موتور نمی‌دهد.
     *
     * پیش‌تر کندل باز هم در تجمیع‌کننده‌ها ([aggS]/[aggM]/[agg1]) گام می‌زد. چون فید زنده
     * هر چند ثانیه همان کندل باز را دوباره می‌فرستد، حجم آن کندل به ازای هر تیک یک‌بار
     * دیگر جمع می‌شد (۲۰ تیک → حجم ~۲ برابر). آن حجم بادکرده بعداً در `agg?.cl` کپی می‌شد
     * و `prevT1Vol` را خراب می‌کرد → شرط «کندل ولوم کم» بیش از حد راحت پاس می‌شد و
     * نتیجهٔ لایو با بک‌تست یکی نبود.
     *
     * حالا تجمیع فقط در [processClosed] و فقط با کندل بسته انجام می‌شود (بدون ریپینت).
     */
    private fun updateLive(cd: Candle, i: Int, prev: Candle?) {
        curBi = i; curT = cd.t; curVol = cd.v; liveCandle = cd
        times[i] = cd.t
        livePreview.clear()
        prevChartVol = prev?.v ?: Double.NaN
        // وضعیت‌های در انتظار (فقط خواندنی)
        for (s in setups) {
            if (s.stage >= 1 && s.stage <= 6 && !s.zGone) {
                val d = if (s.dir == 1) "صعودی" else "نزولی"
                livePreview.add("ستاپ #${s.id} $d · مرحله: ${stageFa(s.stage)}")
            }
        }
        // آیا کندل جاری در حال مارک شدن به‌عنوان کندل ولوم زیاد است؟
        for (s in setups) {
            if (s.stage >= 4 && s.stage <= 5 && s.lvReEntered && s.hvBi < 0) {
                val inc = cd.v > (prev?.v ?: 0.0)
                if (inc) livePreview.add("کندل ولوم زیاد در حال مارک (حجم ${fmtV(cd.v)} > قبلی)")
            }
        }
    }

    private fun tfS() = cfg.tfS
    private fun tfM() = cfg.tfM
    private fun tf1() = cfg.tf1

    private fun aggStep(a: Agg, i: Int, cd: Candle, isNew: Boolean) {
        a.step(isNew, i, cd.t, cd.o, cd.h, cd.l, cd.c, cd.v)
    }

    // ── پردازش کندل بسته ───────────────────────────────────────────────────────
    private fun processClosed(cd: Candle, i: Int, prev: Candle?) {
        curBi = i; curT = cd.t; curVol = cd.v
        times[i] = cd.t
        if (times.size > 200000) times.clear()
        val isNewS = Tf.isNewBarStart(tfS(), cd.t, prev?.t)
        val isNewM = Tf.isNewBarStart(tfM(), cd.t, prev?.t)
        val isNew1 = Tf.isNewBarStart(tf1(), cd.t, prev?.t)

        aggStep(aggS, i, cd, isNewS)
        aggStep(aggM, i, cd, isNewM)
        aggStep(agg1, i, cd, isNew1)

        // ترتیب دقیقاً مثل Pine: ساختار → میانی → تریگر۱ → تریگر۲
        if (aggS.closedNow) aggS.cl?.let { structureEngine(it) }
        if (aggM.closedNow) middleEngine(aggM.cl!!)
        if (agg1.closedNow) trigger1Engine(agg1.cl!!)
        trigger2Engine(cd)

        // ریزکندل‌های تریگر۲ داخل کندل ساختار جاری
        if (isNewS) microS.clear()
        if (microS.size > 3000) microS.removeAt(0)
        microS.add(cd)

        // چرخهٔ عمر باکس‌های ناحیه (حذف با کلوز از سمت دور)
        if (cfg.clearUsedZones) {
            for (zb in zones) {
                if (zb.status != ZoneStatus.ACTIVE) continue
                val dead = if (zb.dir == 1) cd.c < zb.bot else cd.c > zb.top
                if (dead) {
                    zb.status = ZoneStatus.DELETED
                    zb.endBi = i; zb.endT = cd.t
                }
            }
        }
        // سقف تعداد باکس‌های نمایان:
        // ⚠ پیش‌تر این‌جا `zones.size > maxZoneBoxes` سنجیده می‌شد؛ ولی `zones` هرگز کوچک نمی‌شود
        // (باکس‌ها فقط وضعیت می‌گیرند)، پس بعد از ساخت ۲۴ باکس، در **هر** کندل یک باکس زنده
        // بی‌دلیل CAP می‌شد و عملاً هیچ باکس ناحیه‌ای روی چارت نمی‌ماند.
        // شمارش درست = فقط باکس‌های نمایان (فعال/ردشده) — همان معیاری که trimZones دارد.
        trimZones()
        if (agg1.closedNow && agg1.cl != null) prevT1Vol = agg1.cl!!.v
        prevChartVol = cd.v
        cnt.zoneCount = zones.size
        barCommitHook?.invoke(cd)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  ⑩ موتور ساختار (کندل مهم · Ready · pin · روند · ناحیه فیکس‌رنج)
    // ═══════════════════════════════════════════════════════════════════════════
    private fun structureEngine(sb: Candle) {
        structBars.add(sb)
        while (structBars.size > 400) structBars.removeAt(0)
        val nS = structBars.size

        // ── تشخیص جفت‌کندل‌ها و ثبت کاندید کندل مهم ──
        if (nS >= 2) {
            val a = structBars[nS - 2]
            val b = structBars[nS - 1]
            if (a.c < a.o && b.c > b.o) {                       // کندل مهم در پایین‌ترین‌ها
                val (impH, impL, impBi) = pickLow(a, b)
                // سطح ابطال از **خودِ کندل مهم** گرفته می‌شود، نه از دو کندل مختلف.
                // کندل مهم صعودی باشد (کلوز > اوپن) ⇒ ref = اوپن
                // کندل مهم نزولی باشد (کلوز < اوپن) ⇒ ref = کلوز
                // یعنی همیشه پایین‌ترِ دو لبهٔ بدنهٔ کندل مهم.
                val impA = (impBi == a.bi)
                val refLo = if (impA) min(a.c, a.o) else min(b.c, b.o)
                pins.add(Pin(1, impH, impL, impBi, b.bi, refLo, 0))
                lg("ENGINE", "کاندید کندل مهم پایین‌ترین‌ها ثبت شد",
                    "kind=BU hi=${f2(impH)} lo=${f2(impL)} ref=${f2(refLo)} bar=${b.bi}")
            }
            if (a.c > a.o && b.c < b.o) {                       // کندل مهم در بالاترین‌ها
                val (impH2, impL2, impBi2) = pickHigh(a, b)
                // قرینه: همیشه بالاترینِ دو لبهٔ بدنهٔ کندل مهم.
                // کندل مهم صعودی ⇒ ref = کلوز · نزولی ⇒ ref = اوپن
                val impA2 = (impBi2 == a.bi)
                val refHi = if (impA2) max(a.c, a.o) else max(b.c, b.o)
                pins.add(Pin(-1, impH2, impL2, impBi2, b.bi, refHi, 0))
                lg("ENGINE", "کاندید کندل مهم بالاترین‌ها ثبت شد",
                    "kind=BE hi=${f2(impH2)} lo=${f2(impL2)} ref=${f2(refHi)} bar=${b.bi}")
            }
        }

        // ── چرخهٔ عمر کاندیدها : ابطال / Ready ──
        for (p in pins) {
            if (p.status == 0 && sb.bi > p.startBi) {
                // ابطال فقط با **بسته شدن** کندل، نه با باز شدن آن.
                // پیش‌تر `sb.o` هم چک می‌شد که باعث ابطال زودهنگام کاندید می‌شد.
                val dead = if (p.kind == 1) (sb.c <= p.ref) else (sb.c >= p.ref)
                if (dead) p.status = -1
                else {
                    val rdy = if (p.kind == 1) sb.c > p.hi else sb.c < p.lo
                    if (rdy) p.status = 1
                }
            }
        }

        // ── انتخاب Ready با تناوب سخت BU↔BE ──
        if (pins.isNotEmpty()) {
            if (expectedReady == 0 || expectedReady == 1) {
                val iBU = bestReady(pins, 1)
                if (iBU >= 0) {
                    val pBU = pins[iBU]
                    if (!hasBetterActive(pins, 1, pBU.lo)) {
                        lastBUpin = pBU.lo
                        lastBUpinRef = pBU.ref; lastBUpinHi = pBU.hi
                        buActive = true; buSeq = ++pinSeq
                        lowestBUpin = if (lowestBUpin.isNaN()) pBU.lo else min(lowestBUpin, pBU.lo)
                        addMarker(pBU.impBi, pBU.lo, 1, "کندل مهم پایین‌ترین\nReady BU · pinBU")
                        lg("ENGINE", "Ready BU (pinBU) تایید شد", "lo=${f2(pBU.lo)} hi=${f2(pBU.hi)} bar=${sb.bi}")
                        alarm(AlertKind.PIN, "کندل مهم · Ready BU",
                            "کف ${f2(pBU.lo)} — کندل مهم پایین‌ترین‌ها تایید شد (${Tf.label(cfg.tfS)})")
                        // docs/19 بند ۵-۲: `pinBU` به‌تنهایی روند **نمی‌سازد**.
                        // روند فقط با **توالی** تشکیل می‌شود (پایین‌تر، بلوک روند).
                        // در روند صعودی، کف pinBU همان HL است.
                        if (trend == 1) HL = pBU.lo
                        expectedReady = -1
                        pins.clear()
                    }
                }
            }
            if ((expectedReady == 0 || expectedReady == -1) && pins.isNotEmpty()) {
                val iBE = bestReady(pins, -1)
                if (iBE >= 0) {
                    val pBE = pins[iBE]
                    if (!hasBetterActive(pins, -1, pBE.hi)) {
                        lastBEpin = pBE.hi
                        lastBEpinRef = pBE.ref; lastBEpinLo = pBE.lo
                        beActive = true; beSeq = ++pinSeq
                        highestBE = if (highestBE.isNaN()) pBE.hi else max(highestBE, pBE.hi)
                        addMarker(pBE.impBi, pBE.hi, -1, "کندل مهم بالاترین\nReady BE · pinBE")
                        lg("ENGINE", "Ready BE (pinBE) تایید شد", "hi=${f2(pBE.hi)} lo=${f2(pBE.lo)} bar=${sb.bi}")
                        alarm(AlertKind.PIN, "کندل مهم · Ready BE",
                            "سقف ${f2(pBE.hi)} — کندل مهم بالاترین‌ها تایید شد (${Tf.label(cfg.tfS)})")
                        // docs/19 بند ۵-۲: `pinBE` هم به‌تنهایی روند نمی‌سازد.
                        if (trend == -1) LH = pBE.hi
                        expectedReady = 1
                        pins.clear()
                    }
                }
            }
        }

        // ── ابطال پین **بعد از** Ready (docs/19 بند ۳) ──
        // اگر باز **یا** کلوز آن‌طرف `ref` برود، **ولی** کلوز پایین‌تر از کف کندل مهم
        // بسته نشود ⇒ کندل مهم و pinBU حذف می‌شوند و مجدد سنجیده می‌شوند.
        // ⚠ سطح `lastBUpin` دست‌نخورده می‌ماند — بند ۵-۱: پینِ حذف‌شده تا تشکیل پین
        //    جدید نقش HL را برای تغییر روند نگه می‌دارد.
        if (buActive && !lastBUpinRef.isNaN() && sb.bi > 0) {
            val crossed = sb.o < lastBUpinRef || sb.c < lastBUpinRef
            val closedBelowLow = !lastBUpin.isNaN() && sb.c < lastBUpin
            if (crossed && !closedBelowLow) {
                buActive = false
                addMarker(sb.bi, lastBUpin, 10, "pinBU بی‌اعتبار شد (کلوز/باز زیر ref)")
                lg("ENGINE", "pinBU بی‌اعتبار و حذف شد — مجدد سنجیده می‌شود",
                    "ref=${f2(lastBUpinRef)} lo=${f2(lastBUpin)} close=${f2(sb.c)} open=${f2(sb.o)}")
            }
        }
        // قرینه: pinBE
        if (beActive && !lastBEpinRef.isNaN() && sb.bi > 0) {
            val crossed = sb.o > lastBEpinRef || sb.c > lastBEpinRef
            val closedAboveHigh = !lastBEpin.isNaN() && sb.c > lastBEpin
            if (crossed && !closedAboveHigh) {
                beActive = false
                addMarker(sb.bi, lastBEpin, 10, "pinBE بی‌اعتبار شد (کلوز/باز بالای ref)")
                lg("ENGINE", "pinBE بی‌اعتبار و حذف شد — مجدد سنجیده می‌شود",
                    "ref=${f2(lastBEpinRef)} hi=${f2(lastBEpin)} close=${f2(sb.c)} open=${f2(sb.o)}")
            }
        }

        // ── تعیین / ادامه / تغییر روند (فقط با کلوز کندل ساختار) ──
        var evTrendUp = false; var evTrendDn = false
        var evChgUp = false; var evChgDn = false
        when (trend) {
            // ── docs/19 بند ۵-۲: در اول چارت هیچ روندی معلوم نیست؛ منتظر **توالی** می‌مانیم.
            //    توالی یعنی هر دو pinBU و pinBE وجود داشته باشند. آنگاه:
            //      کلوز بالای سقف pinBE ⇒ روند صعودی · کلوز زیر کف pinBU ⇒ روند نزولی
            //    (هر چهار ترکیبِ ترتیبِ کارفرما به همین دو قاعده فرومی‌کاهد.)
            0 -> {
                if (!lastBUpin.isNaN() && !lastBEpin.isNaN()) {
                    if (sb.c > lastBEpin) {
                        trend = 1; HH = lastBEpin; HL = lastBUpin; evTrendUp = true
                        // ۷-۶: reset مراجع فیبو به سکانس جدید
                        highestBE = lastBEpin; lowestBUpin = lastBUpin
                        lg("ENGINE", "روند صعودی تثبیت شد (توالی)",
                            "pinBE=${f2(lastBEpin)} pinBU=${f2(lastBUpin)} close=${f2(sb.c)}")
                        alarm(AlertKind.TREND, "روند صعودی شد",
                            "توالی کامل · کلوز ${f2(sb.c)} بالای سقف pinBE ${f2(lastBEpin)}")
                    } else if (sb.c < lastBUpin) {
                        trend = -1; LL = lastBUpin; LH = lastBEpin; evTrendDn = true
                        // ۷-۶: reset مراجع فیبو به سکانس جدید
                        highestBE = lastBEpin; lowestBUpin = lastBUpin
                        lg("ENGINE", "روند نزولی تثبیت شد (توالی)",
                            "pinBU=${f2(lastBUpin)} pinBE=${f2(lastBEpin)} close=${f2(sb.c)}")
                        alarm(AlertKind.TREND, "روند نزولی شد",
                            "توالی کامل · کلوز ${f2(sb.c)} زیر کف pinBU ${f2(lastBUpin)}")
                    }
                }
            }
            1 -> {
                if (sb.c > HH) HH = sb.c
                if (!lastBUpin.isNaN() && sb.c < lastBUpin) {
                    // docs/19 بند ۲: چرخش روند نیاز به **توالی** دارد — باید بعد از آن
                    // pinBU یک pinBE هم شکل گرفته باشد (beSeq > buSeq).
                    if (beSeq > buSeq) {
                        // سند: LL = کف pinBU (همان lastBUpin که شکسته شد) و LH = HH
                        // (قبلاً LL = sb.c بود که بیرون سطح بود و درست نبود)
                        trend = -1; LL = lastBUpin; LH = HH; evChgDn = true
                        // ۷-۶: reset مراجع فیبو به سکانس جدید
                        highestBE = HH; lowestBUpin = lastBUpin
                        lg("ENGINE", "چرخش روند به نزولی (کلوز زیر pinBU · توالی کامل)",
                            "pinBU=${f2(lastBUpin)} close=${f2(sb.c)}")
                        alarm(AlertKind.TREND, "چرخش روند به نزولی",
                            "کلوز ${f2(sb.c)} زیر کف pinBU ${f2(lastBUpin)} بسته شد")
                    } else if (buActive) {
                        // بدون pinBE بعدی ⇒ روند تغییر نمی‌کند، فقط pinBU بی‌اعتبار می‌شود.
                        buActive = false
                        addMarker(sb.bi, lastBUpin, 10, "pinBU بی‌اعتبار شد (بدون توالی)")
                        lg("ENGINE", "کلوز زیر pinBU ولی توالی کامل نبود — فقط pinBU حذف شد، روند دست نخورد",
                            "pinBU=${f2(lastBUpin)} close=${f2(sb.c)} buSeq=$buSeq beSeq=$beSeq")
                    }
                }
            }
            -1 -> {
                if (sb.c < LL) LL = sb.c
                if (!lastBEpin.isNaN() && sb.c > lastBEpin) {
                    if (buSeq > beSeq) {
                        // سند: HH = سقف pinBE (همان lastBEpin که شکسته شد) و HL = LL
                        // (قبلاً HH = sb.c بود که بیرون سطح بود و درست نبود)
                        trend = 1; HH = lastBEpin; HL = LL; evChgUp = true
                        // ۷-۶: reset مراجع فیبو به سکانس جدید
                        highestBE = lastBEpin; lowestBUpin = LL
                        lg("ENGINE", "چرخش روند به صعودی (کلوز بالای pinBE · توالی کامل)",
                            "pinBE=${f2(lastBEpin)} close=${f2(sb.c)}")
                        alarm(AlertKind.TREND, "چرخش روند به صعودی",
                            "کلوز ${f2(sb.c)} بالای سقف pinBE ${f2(lastBEpin)} بسته شد")
                    } else if (beActive) {
                        beActive = false
                        addMarker(sb.bi, lastBEpin, 10, "pinBE بی‌اعتبار شد (بدون توالی)")
                        lg("ENGINE", "کلوز بالای pinBE ولی توالی کامل نبود — فقط pinBE حذف شد، روند دست نخورد",
                            "pinBE=${f2(lastBEpin)} close=${f2(sb.c)} buSeq=$buSeq beSeq=$beSeq")
                    }
                }
            }
        }

        if (evChgDn || evTrendDn) cancelDir(1)
        if (evChgUp || evTrendUp) cancelDir(-1)

        // ── پروفایل حجم فیکس‌رنج + ثبت ناحیه‌ها ──
        val bullC = sb.c > sb.o
        val bearC = sb.c < sb.o
        val dirOK = (trend == 1 && bullC) || (trend == -1 && bearC)
        val prof = VolumeProfile.build(microS, cfg.vpRows, cfg.vpVA, cfg.distMode, cfg.vpSmooth, cfg.mintick)
        profileOnLastS = prof
        lastProfBars = microS.size
        if (cfg.showPocVA && !prof.poc.isNaN()) lastPocPx = prof.poc
        if (dirOK && !prof.step.isNaN() && prof.step > 0) {
            val rej = VolumeProfile.zones(prof, cfg.vpRows, bullC, sb.c, cfg.minZoneRows, wantRejected = true)
            cnt.zoneRejected += rej.size
            if (cfg.showRejectedZones) for (rz in rej) {
                addZoneBox(trend, rz, sb, ZoneStatus.REJECTED)
                lg("ZONE", "ناحیهٔ ردشده (کلوز داخل محدوده بود)",
                    "dir=${if (bullC) "BULL" else "BEAR"} top=${f2(rz.top)} bot=${f2(rz.bot)} rows=${rz.loIdx}-${rz.hiIdx} bar=${sb.bi}")
            }
            val zs = VolumeProfile.zones(prof, cfg.vpRows, bullC, sb.c, cfg.minZoneRows)
            for (z in zs) {
                if (z.top > z.bot) {
                    addZoneBox(trend, z, sb, ZoneStatus.ACTIVE)
                    lastZoneTop = z.top; lastZoneBot = z.bot
                    cnt.setup++
                    val st = Setup(setupSeq++, trend, 0, z.top, z.bot, z.idx, sb.bi)
                    st.note = "ثبت شد"
                    setups.add(st)
                    lg("ZONE", "ناحیهٔ فیکس‌رنج ثبت شد · Z${z.idx}",
                        "dir=${if (bullC) "BULL" else "BEAR"} top=${f2(z.top)} bot=${f2(z.bot)} rows=${z.loIdx}-${z.hiIdx} bar=${sb.bi} setup=${st.id}")
                    alarm(AlertKind.ZONE_NEW, "ناحیهٔ فیکس‌رنج Z${z.idx} ساخته شد",
                        "${if (bullC) "صعودی" else "نزولی"} · ${f2(z.bot)} تا ${f2(z.top)} · ستاپ #${st.id}")
                }
            }
            pruneSetups()
            trimZones()
        }
    }

    private fun pruneSetups() {
        //  ⚠ ستاپ‌های در حال معامله (stage=7) هرگز حذف نمی‌شوند — §۷-۲ و قانون
        //  «معاملهٔ باز هرگز به‌زور بسته نمی‌شود». اول ستاپ‌های پایان‌یافته (stage=8)،
        //  بعد قدیمی‌ترین ستاپِ فعالِ قبل از ورود (stage<7) حذف می‌شود.
        while (setups.size > cfg.maxSetups) {
            var pick = setups.indexOfFirst { it.stage == 8 }
            if (pick < 0) pick = setups.indexOfFirst { it.stage < 7 }
            if (pick < 0) break   // همه ستاپ‌ها در معامله‌اند — بیش از این حذف نمی‌کنیم
            val s = setups.removeAt(pick)
            if (s.stage < 7) {
                events.add("[${s.id}] پروندهٔ ستاپ بسته شد (سقف ستاپ‌های هم‌زمان)")
                broker?.onSetupInvalidated(s)
            }
        }
    }

    private fun trimZones() {
        // مسیر سریع: اگر کل باکس‌ها هم از سقف کمترند، قطعاً باکس نمایان بیشتری وجود ندارد
        if (zones.size <= cfg.maxZoneBoxes) return
        // قدیمی‌ترین باکس نمایان (فعال/ردشده) تا وقتی که تعداد نمایان‌ها از سقف بگذرد حذف می‌شود
        while (zones.count { it.status == ZoneStatus.ACTIVE || it.status == ZoneStatus.REJECTED } > cfg.maxZoneBoxes) {
            val victim = zones.firstOrNull { it.status == ZoneStatus.ACTIVE || it.status == ZoneStatus.REJECTED } ?: break
            victim.status = ZoneStatus.CAP
        }
    }

    private fun addZoneBox(dir: Int, z: Zone, sb: Candle, status: Int) {
        zones.add(ZoneBox(zoneSeq++, dir, z.idx, z.top, z.bot, sb.bi, sb.t, z.loIdx, z.hiIdx, sb.bi, sb.t, status))
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  ⑪ موتور میانی (تایید میانی · کندل مارک‌شده · ابطال)
    // ═══════════════════════════════════════════════════════════════════════════
    private fun middleEngine(mc: Candle) {
        for (s in setups) {
            if (s.stage < 1 || s.stage >= 7) continue
            if (s.midRef.isNaN() && mc.t == s.touchMidStart) {
                // ✔ تایید میانی
                s.midRef = if (s.dir == 1) mc.l else mc.h
                s.midBi = mc.bi
                s.midConfirmBi = curBi
                s.markBi = -1; s.markH = Double.NaN; s.markL = Double.NaN
                s.stage = 2
                cnt.mid++
                s.lvReEntered = false
                lg("ENGINE", "تایید میانی انجام شد · ستاپ #${s.id}",
                    if (s.dir == 1) "کف تایید=${f2(s.midRef)} bar=${mc.bi}" else "سقف تایید=${f2(s.midRef)} bar=${mc.bi}")
                addMarker(mc.bi, s.midRef, 3, if (s.dir == 1) "کف تایید میانی" else "سقف تایید میانی")
            } else if (!s.midRef.isNaN()) {
                // ✖ ابطال تایید میانی — قبل از مارک چک می‌شود، چون کندل مارک هم
                //   می‌تواند (حتی در همان کندل پس از تایید، در پاس‌های بعدی) سطح را بشکند.
                //   سند ۶-۱۲: هر عبور شمع، حتی یک تیک، تایید را باطل می‌کند — شامل کندل بعد
                //   از تایید هم می‌شود (قبلاً این کندل از بررسی ابطال معاف بود).
                val broken = if (cfg.midInvalidClose) {
                    if (s.dir == 1) mc.c < s.midRef else mc.c > s.midRef
                } else {
                    if (s.dir == 1) mc.l < s.midRef else mc.h > s.midRef
                }
                if (broken) {
                    val old = s.midRef
                    //  ابطال میانی در هر مرحله‌ای (۱..۶) تایید را باطل می‌کند؛
                    //  اگر ستاپ قبلاً مسلح شده بود (stage=6)، سفارش معلق هم لغو می‌شود —
                    //  در غیر این‌صورت سفارش یتیم می‌ماند و ممکن است در کندل بعد پر شود
                    //  در حالی که موتور دیگر آن ستاپ را نمی‌شناسد.
                    if (s.stage == 6) broker?.onSetupInvalidated(s)
                    s.midRef = Double.NaN; s.midBi = -1; s.markBi = -1
                    s.touchMidStart = aggM.cur?.t ?: mc.t
                    s.stage = 2
                    s.lvBi = -1; s.lvConfBi = -1; s.hvBi = -1; s.hvScanBi = -1; s.runMax = Double.NaN
                    s.lvTouched = false; s.lvUsed = false
                    s.orderId = -1
                    addMarker(mc.bi, old, 10, "تایید میانی باطل شد")
                    lg("ENGINE", "تایید میانی باطل شد · ستاپ #${s.id}", "سطح قبلی=${f2(old)} bar=${mc.bi}")
                } else if (s.markBi < 0) {
                    // ◆ مارک کردن کندل بعد از تایید (فقط نشانه) — فقط اگر ابطال اتفاق نیفتاده باشد
                    s.markH = mc.h; s.markL = mc.l; s.markBi = mc.bi
                    if (s.stage == 2) s.stage = 3
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  ⑫ موتور تریگر ۱ · ناحیهٔ ولوم کم (یک‌بارمصرف)
    // ═══════════════════════════════════════════════════════════════════════════
    private fun trigger1Engine(q1: Candle) {
        for (s in setups) {
            if (s.stage < 2 || s.stage > 6 || s.midRef.isNaN() || q1.bi <= s.midConfirmBi) continue
            val bullL = s.dir == 1

            // ── مارک / تایید کندل ولوم کم ──
            if (!s.lvUsed && s.stage <= 4) {
                if (s.lvBi < 0) {
                    if (prevT1Vol.isNaN() || q1.v < prevT1Vol) {
                        s.lvBi = q1.bi; s.lvH = q1.h; s.lvL = q1.l
                        addMarker(q1.bi, if (bullL) q1.l else q1.h, 4, "کندل ولوم کم (تریگر۱)")
                        lg("ENGINE", "کندل ولوم کم مارک شد · ستاپ #${s.id}",
                            "vol=${f2(q1.v)} < prev=${f2(prevT1Vol)} hi=${f2(q1.h)} lo=${f2(q1.l)} bar=${q1.bi}")
                    }
                } else {
                    val violL = if (bullL) q1.l <= s.lvL else q1.h >= s.lvH
                    val confL = if (bullL) q1.c > s.lvH else q1.c < s.lvL
                    if (violL) {
                        s.lvBi = -1
                    } else if (confL) {
                        s.lvConfBi = curBi
                        s.stage = 4
                        cnt.lv++
                        s.lvTouched = false
                        s.lvReEntered = false
                        s.hvScanBi = -1
                        s.hvBi = -1
                        if (!cfg.hvScanFirstTouch) s.hvScanBi = curBi
                        addMarker(q1.bi, if (bullL) s.lvH else s.lvL, 5, "ناحیهٔ ولوم کم ✔ (تریگر۱)")
                        lg("ENGINE", "ناحیهٔ ولوم کم تایید شد · ستاپ #${s.id}",
                            "box=${f2(s.lvL)}-${f2(s.lvH)} bar=${q1.bi} مرحله=${s.stage}")
                        alarm(AlertKind.LV, "ناحیهٔ ولوم کم تایید شد",
                            "ستاپ #${s.id} · باکس ${f2(s.lvL)} تا ${f2(s.lvH)} · منتظر کندل ولوم زیاد (تریگر ۲)")
                    }
                }
            }

            // ── یک‌بارمصرف بودن باکس ولوم کم ──
            if (s.lvBi >= 0 && !s.lvUsed && s.stage in 4..6) {
                val inside = q1.l <= s.lvH && q1.h >= s.lvL
                if (inside && !s.lvTouched) {
                    s.lvTouched = true
                    if (s.hvScanBi < 0 && cfg.hvScanFirstTouch) s.hvScanBi = q1.bi
                } else if (s.lvTouched && !inside) {
                    val left = if (bullL) q1.c > s.lvH else q1.c < s.lvL
                    if (left) {
                        s.lvUsed = true
                        addMarker(q1.bi, if (bullL) s.lvH else s.lvL, 11, "باکس ولوم کم مصرف شد ✖")
                        lg("ZONE", "باکس ولوم کم مصرف شد (یک‌بارمصرف) · ستاپ #${s.id}",
                            "box=${f2(s.lvL)}-${f2(s.lvH)} bar=${q1.bi}")
                        if (s.stage in 4..6) {
                            //  §۶-۱۴: مصرف شدن باکس LV → ستاپ می‌میرد، حتی اگر مسلح (stage=6) باشد.
                            s.stage = 8
                            s.note = "باکس ولوم کم مصرف شد (یک‌بار)"
                            events.add("[${s.id}] باکس ولوم کم بدون معامله مصرف شد")
                            broker?.onSetupInvalidated(s)
                        }
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  ⑬ موتور تریگر ۲ (چارت) : ناحیهٔ ساختار · ناحیهٔ ولوم زیاد · ورود · مدیریت
    // ═══════════════════════════════════════════════════════════════════════════
    private fun trigger2Engine(cd: Candle) {
        val low = cd.l; val high = cd.h; val close = cd.c; val volume = cd.v
        for (s in setups) {
            val bull = s.dir == 1

            // ① برخورد به ناحیهٔ فیکس‌رنج ساختار
            if (s.stage == 0 && !s.zGone && curBi > s.zBi) {
                if (low <= s.zTop && high >= s.zBot) {
                    s.stage = 1
                    cnt.touch++
                    s.touchBi = curBi
                    s.touchMidStart = aggM.cur?.t ?: cd.t
                    addMarker(curBi, if (bull) s.zBot else s.zTop, 2, "برخورد · منتظر بسته شدن کندل میانی")
                    lg("ENGINE", "برخورد قیمت با ناحیهٔ ساختار · ستاپ #${s.id}",
                        "zone=${f2(s.zBot)}-${f2(s.zTop)} bar=$curBi · منتظر تایید میانی (${Tf.label(cfg.tfM)})")
                }
            }

            // ② پاک شدن / لغو ناحیهٔ ساختار (کلوز از سمت دور)
            if (s.stage < 7 && !s.zGone && curBi > s.zBi) {
                val broken = if (bull) close < s.zBot else close > s.zTop
                if (broken) {
                    //  اگر سفارش معلق داشت (stage=6) آن را هم لغو کن
                    if (s.stage == 6) broker?.onSetupInvalidated(s)
                    s.zGone = true
                    s.stage = 8
                    s.note = "شکست ناحیهٔ ساختار (لغو)"
                    s.orderId = -1
                    events.add("[${s.id}] لغو شد — شکست ناحیهٔ ساختار")
                    addMarker(curBi, close, 10, "لغو · شکست ناحیهٔ ساختار")
                    lg("ZONE", "ناحیهٔ ساختار شکست (لغو ستاپ #${s.id})",
                        "zone=${f2(s.zBot)}-${f2(s.zTop)} close=${f2(close)} bar=$curBi")
                    alarm(AlertKind.ZONE_DEAD, "ناحیهٔ ساختار شکست",
                        "ستاپ #${s.id} لغو شد · کلوز ${f2(close)} خارج از ناحیهٔ ${f2(s.zBot)}-${f2(s.zTop)}")
                }
            }

            // ③-الف بازگشت قیمت به باکس ولوم کم ← شروع اسکن ولوم زیاد
            if (s.stage == 4 && !s.zGone && s.lvBi >= 0 && !s.lvReEntered) {
                if (low <= s.lvH && high >= s.lvL) {
                    s.lvReEntered = true
                    if (s.hvScanBi < 0) s.hvScanBi = curBi
                    s.runMax = volume
                    addMarker(curBi, if (bull) s.lvH else s.lvL, 5, "بازگشت به باکس ولوم کم · اسکن ولوم زیاد")
                    lg("ENGINE", "بازگشت به باکس ولوم کم → اسکن ناحیهٔ ولوم زیاد فعال شد · ستاپ #${s.id}",
                        "box=${f2(s.lvL)}-${f2(s.lvH)} bar=$curBi vol=${f2(volume)}")
                }
            }

            // ③-ب ناحیهٔ ولوم زیاد (تریگر ۲)
            if (s.stage in 4..5 && s.lvBi >= 0 && s.lvReEntered) {
                val scanOK = if (s.hvScanBi < 0) true else curBi >= s.hvScanBi
                if (scanOK) {
                    val marked = s.hvBi >= 0
                    val viol = marked && (if (bull) low <= s.hvL else high >= s.hvH)
                    val conf = marked && (if (bull) close > s.hvH else close < s.hvL)
                    if (viol) {
                        s.hvBi = -1; s.runMax = volume; s.stage = 4
                        lg("ENGINE", "کندل ولوم زیاد نقض شد → جست‌وجوی کندل جدید · ستاپ #${s.id}",
                            "hvH=${f2(s.hvH)} hvL=${f2(s.hvL)} bar=$curBi")
                    } else if (conf) {
                        armSetup(s, cd, bull)
                    } else {
                        val markNow = if (cfg.hvMarkFirstIncrease) {
                            s.hvBi < 0 && volume > (prevChartVol.takeIf { !it.isNaN() } ?: Double.NEGATIVE_INFINITY)
                        } else {
                            volume > (s.runMax.takeIf { !it.isNaN() } ?: Double.NEGATIVE_INFINITY)
                        }
                        if (markNow) {
                            s.hvBi = curBi; s.hvH = high; s.hvL = low
                            s.runMax = volume; s.stage = 5
                            addMarker(curBi, if (bull) low else high, 6, "کندل ولوم زیاد (مارک)")
                            lg("ENGINE", "کندل ولوم زیاد مارک شد · ستاپ #${s.id}",
                                "vol=${f2(volume)} hi=${f2(high)} lo=${f2(low)} bar=$curBi مرحله=5")
                        }
                    }
                }
            }

            // ④ ورود : برگشت به ناحیهٔ ولوم زیاد (پر شدن سفارش لیمیت)
            if (s.stage == 6 && curBi > s.hvConfBi) {
                if (if (bull) close < s.hvL else close > s.hvH) {
                    //  §۶-۱۷: کلوز آن‌طرف ناحیه HV بعد از مسلح شدن → نقض.
                    s.hvBi = -1; s.runMax = volume; s.stage = 4
                    broker?.onSetupInvalidated(s)
                    s.orderId = -1
                } else if (if (bull) low <= s.hvH else high >= s.hvL) {
                    s.entry = if (bull) s.hvH else s.hvL
                    s.stage = 7
                    cnt.entry++
                    lastEntryPx = s.entry; lastSlPx = s.sl; lastTpPx = s.tpx
                    addMarker(curBi, s.entry, 8, (if (bull) "ورود خرید" else "ورود فروش") + " @ " + f2(s.entry))
                    lg("TRADE", "قیمت به ناحیهٔ ولوم زیاد برگشت (شرط ورود) · ستاپ #${s.id}",
                        "ورود=${f2(s.entry)} bar=$curBi")
                }
            }

            // ⑤ مدیریت معامله : حدضرر / سر‌به‌سر / حدسود (بررسی موتور)
            //   ترتیب محافظه‌کارانه، مطابق §۲-۹ و PaperBroker.manageBar:
            //   SL/TPX بررسی اول می‌شوند و در یک کندل پایان می‌دهند.
            //   TP1 و TP2 پله‌های میانی‌اند و در یک کندل می‌توانند هر دو لمس شوند
            //   (مثلاً اسپایک قوی) — قبلاً به‌خاطر else-if فقط TP1 ثبت می‌شد و TP2
            //   در همان کندل نادیده می‌ماند. حالا TP2 با if مستقل چک می‌شود.
            //   توجه: stage فقط در پایان واقعی (SL/TPX/BE) به ۸ می‌رود.
            if (s.stage == 7 && !s.sl.isNaN() && !s.tpx.isNaN()) {
                var done = false
                var px = Double.NaN
                var note = ""
                if (bull) {
                    if (low <= s.sl) { done = true; px = s.sl; note = if (s.be) "خروج سر‌به‌سر" else "حد ضرر" }
                    else if (high >= s.tpx) { done = true; px = s.tpx; note = "حد سود نهایی (1.272)" }
                    else {
                        //  TP1: قبل از BE، هم در یک کندل با TP2 می‌تواند رخ دهد
                        if (!s.be && !s.tp1.isNaN() && high >= s.tp1) {
                            s.be = true; s.sl = s.entry
                            addMarker(curBi, s.entry, 8, "TP1 (38%) ✔ · حدضرر = سر‌به‌سر")
                            lg("TRADE", "TP1 (۳۸٪) لمس شد → حدضرر به سر‌به‌سر منتقل شد · ستاپ #${s.id}",
                                "tp1=${f2(s.tp1)} bar=$curBi")
                            alarm(AlertKind.TP1, "TP1 (۳۸٪) لمس شد", "ستاپ #${s.id} · حدضرر به سر‌به‌سر منتقل شد · ${f2(s.tp1)}")
                        }
                        //  TP2: بعد از TP1 (در همان کندل ممکن است)
                        if (s.be && !s.tp2Hit && !s.tp2.isNaN() && high >= s.tp2) {
                            s.tp2Hit = true
                            addMarker(curBi, s.tp2, 8, "TP2 (50%) ✔")
                            lg("TRADE", "TP2 (۵۰٪) لمس شد · ستاپ #${s.id}", "tp2=${f2(s.tp2)} bar=$curBi")
                            alarm(AlertKind.TP2, "TP2 (۵۰٪) لمس شد", "ستاپ #${s.id} · قیمت ${f2(s.tp2)}")
                        }
                        //  BE خروج سر‌به‌سر — فقط بعد از TP1 و اگر قیمت به ورود برگشته
                        if (s.be && low <= s.entry) { done = true; px = s.entry; note = "خروج سر‌به‌سر" }
                    }
                } else {
                    if (high >= s.sl) { done = true; px = s.sl; note = if (s.be) "خروج سر‌به‌سر" else "حد ضرر" }
                    else if (low <= s.tpx) { done = true; px = s.tpx; note = "حد سود نهایی (1.272)" }
                    else {
                        if (!s.be && !s.tp1.isNaN() && low <= s.tp1) {
                            s.be = true; s.sl = s.entry
                            addMarker(curBi, s.entry, 8, "TP1 (38%) ✔ · حدضرر = سر‌به‌سر")
                            lg("TRADE", "TP1 (۳۸٪) لمس شد → حدضرر به سر‌به‌سر منتقل شد · ستاپ #${s.id}",
                                "tp1=${f2(s.tp1)} bar=$curBi")
                            alarm(AlertKind.TP1, "TP1 (۳۸٪) لمس شد", "ستاپ #${s.id} · حدضرر به سر‌به‌سر منتقل شد · ${f2(s.tp1)}")
                        }
                        if (s.be && !s.tp2Hit && !s.tp2.isNaN() && low <= s.tp2) {
                            s.tp2Hit = true
                            addMarker(curBi, s.tp2, 8, "TP2 (50%) ✔")
                            lg("TRADE", "TP2 (۵۰٪) لمس شد · ستاپ #${s.id}", "tp2=${f2(s.tp2)} bar=$curBi")
                            alarm(AlertKind.TP2, "TP2 (۵۰٪) لمس شد", "ستاپ #${s.id} · قیمت ${f2(s.tp2)}")
                        }
                        if (s.be && high >= s.entry) { done = true; px = s.entry; note = "خروج سر‌به‌سر" }
                    }
                }
                if (done) {
                    s.stage = 8
                    cnt.done++
                    lastResult = note
                    s.note = note
                    addMarker(curBi, px, 9, note + " @ " + f2(px))
                    lg("TRADE", "پایان معامله · ستاپ #${s.id} → $note",
                        "خروج=${f2(px)} bar=$curBi")
                }
            }
        }
        // وضعیت کلی برای پنل
        stageFa = setups.minByOrNull { it.stage }?.let { stageFa(it.stage) } ?: "-"
    }

    /** لحظهٔ مسلح شدن (تایید ناحیهٔ ولوم زیاد) → محاسبهٔ SL/TP و ثبت سفارش ورود. */
    private fun armSetup(s: Setup, cd: Candle, bull: Boolean) {
        val eRef = if (bull) s.hvH else s.hvL
        s.sl = if (bull) s.hvL - cfg.slBufTicks * cfg.mintick else s.hvH + cfg.slBufTicks * cfg.mintick
        val a0 = if (bull) highestBE else lowestBUpin
        val a1 = s.midRef
        val haveFib = !a0.isNaN() && !a1.isNaN() && (if (bull) a0 > a1 else a0 < a1)
        if (haveFib) {
            val r = if (bull) a0 - a1 else a1 - a0
            s.tp1 = if (bull) a1 + 0.382 * r else a1 - 0.382 * r
            s.tp2 = if (bull) a1 + 0.5 * r else a1 - 0.5 * r
            s.tpx = if (bull) a1 + 1.272 * r else a1 - 1.272 * r
        } else {
            s.tpx = if (bull) eRef + cfg.minTPunits else eRef - cfg.minTPunits
            s.tp1 = Double.NaN; s.tp2 = Double.NaN
        }
        if (bull) {
            if (s.tpx - eRef < cfg.minTPunits) s.tpx = eRef + cfg.minTPunits
            if (s.tp1.isNaN() || s.tp1 <= eRef) s.tp1 = eRef + (s.tpx - eRef) * 0.382
            if (s.tp2.isNaN() || s.tp2 <= eRef) s.tp2 = eRef + (s.tpx - eRef) * 0.5
        } else {
            if (eRef - s.tpx < cfg.minTPunits) s.tpx = eRef - cfg.minTPunits
            if (s.tp1.isNaN() || s.tp1 >= eRef) s.tp1 = eRef - (eRef - s.tpx) * 0.382
            if (s.tp2.isNaN() || s.tp2 >= eRef) s.tp2 = eRef - (eRef - s.tpx) * 0.5
        }
        //  ⚠ فقط اگر کارگزار واقعاً سفارش را ثبت کرد به stage=6 می‌رویم.
        //  اگر registerLimit به‌خاطر وجود سفارش/پوزیشن باز رد شد (قانون «فقط یک سفارش هم‌زمان»)،
        //  ستاپ در stage=5 می‌ماند و اسکن ادامه می‌دهد تا بعداً دوباره تلاش شود.
        //  این از «ورود شبح» جلوگیری می‌کند (موتور stage=7 می‌شد ولی کارگزار هیچ سفارشی نداشت).
        val registered = broker?.registerLimit(s, cd) == true
        if (!registered) {
            //  رد شد — کندل HV را ریست می‌کنیم تا وقتی سفارش فعلی تمام شد دوباره اسکن کند.
            s.hvBi = -1; s.runMax = Double.NaN
            lg("ENGINE", "ثبت سفارش برای ستاپ #${s.id} رد شد (${broker?.lastError}) — کندل HV ریست شد",
                "bar=$curBi")
            return
        }
        s.stage = 6
        cnt.hv++
        s.hvConfBi = curBi
        s.entry = eRef
        s.be = false
        s.tp2Hit = false   // هر بار مسلح شدنِ تازه، پله‌ها از نو حساب می‌شوند
        addMarker(curBi, eRef, 7, if (bull) "مسلح برای خرید (Armed)" else "مسلح برای فروش (Armed)")
        events.add("[${s.id}] مسلح شد · ورود ${f2(eRef)} · SL ${f2(s.sl)} · TP ${f2(s.tpx)}")
        lg("ENGINE", "ناحیهٔ ولوم زیاد تایید شد → ستاپ مسلح (Armed) · ستاپ #${s.id}",
            "dir=${if (bull) "BUY" else "SELL"} ورود=${f2(eRef)} SL=${f2(s.sl)} TP1=${f2(s.tp1)} TP2=${f2(s.tp2)} TP=${f2(s.tpx)} bar=$curBi")
        alarm(AlertKind.HV, "ناحیهٔ ولوم زیاد تایید شد",
            "ستاپ #${s.id} (" + (if (bull) "خرید" else "فروش") + ") · ورود ${f2(eRef)} · حدضرر ${f2(s.sl)} · حدسود ${f2(s.tpx)}")
    }

    // ── ابزارها ────────────────────────────────────────────────────────────────
    private fun bestReady(ps: List<Pin>, kind: Int): Int {
        var idx = -1
        var best = Double.NaN
        for (i in ps.indices) {
            val p = ps[i]
            if (p.status == 1 && p.kind == kind) {
                val lv = if (kind == 1) p.lo else p.hi
                if (best.isNaN() || (if (kind == 1) lv < best else lv > best)) { best = lv; idx = i }
            }
        }
        return idx
    }

    private fun hasBetterActive(ps: List<Pin>, kind: Int, readyLevel: Double): Boolean {
        for (p in ps) {
            if (p.status == 0 && p.kind == kind) {
                val lv = if (kind == 1) p.lo else p.hi
                if (if (kind == 1) lv < readyLevel else lv > readyLevel) return true
            }
        }
        return false
    }

    /**
     * انتخاب کندل مهم در «پایین‌ترین‌ها» — **مطابق §۲-۱ سند استراتژی**.
     *
     * سند: «از این دو، کندلی که **کف بزرگ‌تری** دارد مهم است (در تساوی کف، کندلی که
     * سقف کوچک‌تری دارد).»
     *
     * ⚠ پیش‌تر `a.l < b.l` بود یعنی **کم‌ترین** کف انتخاب می‌شد که با قاعدهٔ سند
     * نمی‌خواند. در ۱٫۳٫۷ به `a.l > b.l` اصلاح شد. شکستن تساوی (سقف کوچک‌تر) بدون
     * تغییر ماند چون از ابتدا مطابق سند بود.
     *
     * `pickHigh` دست نخورد: «سقف بزرگ‌تر» از ابتدا درست پیاده شده بود.
     */
    internal fun pickLow(a: Candle, b: Candle): Triple<Double, Double, Int> =
        if (a.l > b.l || (a.l == b.l && a.h < b.h)) Triple(a.h, a.l, a.bi) else Triple(b.h, b.l, b.bi)

    internal fun pickHigh(a: Candle, b: Candle): Triple<Double, Double, Int> =
        if (a.h > b.h || (a.h == b.h && a.l > b.l)) Triple(a.h, a.l, a.bi) else Triple(b.h, b.l, b.bi)

    private fun cancelDir(dir: Int) {
        val it = setups.iterator()
        while (it.hasNext()) {
            val s = it.next()
            if (s.dir == dir && s.stage < 7) {
                s.stage = 8
                s.note = "لغو (تغییر روند)"
                events.add("[${s.id}] لغو شد — تغییر روند")
                lg("ENGINE", "ستاپ #${s.id} به دلیل تغییر روند لغو شد", "dir=${if (dir == 1) "BUY" else "SELL"}")
                broker?.onSetupInvalidated(s)
                it.remove()
            }
        }
    }

    private fun addMarker(bi: Int, price: Double, kind: Int, text: String) {
        val t = times[bi] ?: curT
        markers.add(Marker(t, bi, price, kind, text))
        while (markers.size > 2000) markers.removeAt(0)
    }

    /** نگاشت ایندکس کندل → زمان (برای برچسب‌ها و ذخیره‌سازی). */
    private val times = HashMap<Int, Long>()

    fun stageFa(st: Int): String = when (st) {
        0 -> "منتظر برخورد به ناحیهٔ ساختار"
        1 -> "برخورد شد · منتظر بسته شدن کندل میانی"
        2 -> "تایید میانی ✔"
        3 -> "اسکن ناحیهٔ ولوم کم (تریگر۱)"
        4 -> "ولوم کم ✔ · اسکن ولوم زیاد (تریگر۲)"
        5 -> "کندل ولوم زیاد (تریگر۲) مارک شد"
        6 -> "مسلح (Armed) · منتظر برگشت به ناحیهٔ ولوم زیاد"
        7 -> "در معامله"
        else -> "پایان‌یافته"
    }

    companion object {
        fun fmtV(v: Double): String = if (v >= 1000) String.format("%.1fk", v / 1000) else String.format("%.0f", v)
        fun f2(v: Double): String = String.format("%.2f", v)
    }
}

/** ستاپ — معادل ساختار Setup در Pine. */
class Setup(
    val id: Long,
    val dir: Int,
    var stage: Int,
    var zTop: Double,
    var zBot: Double,
    var zIdx: Int,
    var zBi: Int
) {
    var touchBi = -1
    var touchMidStart = 0L
    var midRef = Double.NaN
    var midBi = -1
    var markH = Double.NaN; var markL = Double.NaN; var markBi = -1
    var lvH = Double.NaN; var lvL = Double.NaN; var lvBi = -1; var lvConfBi = -1
    var hvH = Double.NaN; var hvL = Double.NaN; var hvBi = -1; var hvScanBi = -1; var hvConfBi = -1
    var midConfirmBi = -1
    var runMax = Double.NaN
    var zGone = false
    var lvTouched = false
    var lvUsed = false
    var lvReEntered = false
    var entry = Double.NaN
    var sl = Double.NaN
    var tp1 = Double.NaN
    var tp2 = Double.NaN
    var tpx = Double.NaN
    var be = false
    var tp2Hit = false      // آیا پلهٔ ۳۳٪ دوم (TP2) لمس شده (فقط برای مارکر چارت/لاگ)
    var note = ""
    var orderId = -1L
    val bull: Boolean get() = dir == 1
}
