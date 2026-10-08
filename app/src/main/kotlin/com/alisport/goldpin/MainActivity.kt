package com.alisport.goldpin

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import com.alisport.goldpin.core.Order
import com.alisport.goldpin.core.OrderStatus
import com.alisport.goldpin.core.Reports
import com.alisport.goldpin.core.TF_MODES
import com.alisport.goldpin.core.Tf
import com.alisport.goldpin.core.Trade
import com.alisport.goldpin.core.ZoneStatus
import com.alisport.goldpin.data.Storage
import com.alisport.goldpin.svc.EngineService
import com.alisport.goldpin.ui.ChartView
import com.alisport.goldpin.ui.EquityChartView
import com.alisport.goldpin.ui.SetupOverlay
import com.alisport.goldpin.util.Fa
import com.alisport.goldpin.util.Palette
import com.alisport.goldpin.util.Ui
import kotlin.math.abs
import kotlin.math.max

class MainActivity : Activity() {

    private val s get() = AppState.instance
    private lateinit var content: FrameLayout
    private lateinit var tabButtons: LinearLayout
    private lateinit var headerPrice: TextView
    private lateinit var headerStatus: TextView
    private var tab = 0

    // چارت
    private var chart: ChartView? = null
    private var chartInfo: TextView? = null
    private var engineInfo: TextView? = null
    private var eventsBox: LinearLayout? = null
    private var chartAreaWeight = 1f
    private var reportKind = Reports.WEEKLY
    private var refreshing = false

    private val REQ_SAVE = 101
    private val REQ_LOAD = 102
    private val REQ_CSV = 103
    private val REQ_REPORT = 104

    private val tfChoices = arrayOf(Tf.M1, Tf.M5, Tf.M15, Tf.M30, Tf.H1, Tf.H4, Tf.D1, Tf.W1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildShell()
        s.onChange { refreshCurrent() }
        // بازیابی خودکار آخرین وضعیت (اگر فایلی باشد) ، وگرنه دادهٔ نمونه
        val msg = s.loadFromAutoFile(applicationContext)
        if (!msg.startsWith("بازیابی")) {
            s.loadSample(applicationContext) { m -> toast(m) }
        } else {
            toast(msg)
        }
        showTab(0)
    }

    override fun onPause() {
        super.onPause()
        s.io.execute { s.saveToAutoFile(applicationContext) }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  اسکلت
    // ══════════════════════════════════════════════════════════════════════════
    private fun buildShell() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.bg)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        // ── هدر ──
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.panel)
            setPadding(Ui.dp(this@MainActivity, 10f), Ui.dp(this@MainActivity, 8f),
                    Ui.dp(this@MainActivity, 10f), Ui.dp(this@MainActivity, 6f))
        }
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(Ui.tv(this, "GoldPin · طلا", 16f, Palette.gold, true),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        headerPrice = Ui.tv(this, "—", 16f, Palette.txt, true)
        titleRow.addView(headerPrice, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val liveBtn = Ui.btn(this, "لایو", Palette.panel2, Palette.up, 12f)
        liveBtn.setOnClickListener { toggleLive() }
        titleRow.addView(liveBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        header.addView(titleRow)
        headerStatus = Ui.tv(this, "آماده", 11f, Palette.dim)
        header.addView(headerStatus)
        root.addView(header)

        // ── محتوا ──
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ── نوار پایین ──
        tabButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Palette.panel)
        }
        val names = arrayOf("چارت", "سفارش‌ها", "سود و زیان", "گزارش‌ها", "تنظیمات")
        names.forEachIndexed { i, n ->
            val b = Ui.tv(this, n, 12f, Palette.dim, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, Ui.dp(this@MainActivity, 12f), 0, Ui.dp(this@MainActivity, 12f))
                setOnClickListener { showTab(i) }
            }
            tabButtons.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(tabButtons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(root)
    }

    private fun showTab(i: Int) {
        tab = i
        chart = null; chartInfo = null; engineInfo = null; eventsBox = null
        for (k in 0 until tabButtons.childCount) {
            val tv = tabButtons.getChildAt(k) as TextView
            tv.setTextColor(if (k == i) Palette.gold else Palette.dim)
        }
        content.removeAllViews()
        when (i) {
            0 -> content.addView(buildChartScreen())
            1 -> content.addView(buildOrdersScreen())
            2 -> content.addView(buildPnlScreen())
            3 -> content.addView(buildReportsScreen())
            4 -> content.addView(buildSettingsScreen())
        }
        refreshCurrent()
    }

    private fun refreshCurrent() {
        if (refreshing) return
        refreshing = true
        try {
            headerPrice.text = if (s.lastLivePrice.isNaN()) "—" else Fa.n(s.lastLivePrice, 2)
            val trend = when (s.engine.trend) { 1 -> "صعودی"; -1 -> "نزولی"; else -> "بدون روند" }
            val err = s.lastFeedError
            headerStatus.text = buildString {
                append(if (s.liveRunning) "● لایو روشن" else "○ لایو خاموش")
                append("   |   ${Tf.label(s.chartTfSec)}   |   روند: $trend")
                append("   |   معاملات: ${Fa.d(s.broker.closedTrades().size.toString())}")
                append("   |   موجودی: ${Fa.n(s.broker.equity, 2)}")
                if (s.lastPriceSource != "—") append("   |   منبع قیمت: ${s.lastPriceSource}")
                if (!err.isNullOrEmpty()) append("   |   ⚠ $err")
            }
            when (tab) {
                0 -> refreshChartTab()
                1 -> { content.removeAllViews(); content.addView(buildOrdersScreen()) }
                2 -> { content.removeAllViews(); content.addView(buildPnlScreen()) }
                3 -> { content.removeAllViews(); content.addView(buildReportsScreen()) }
            }
        } finally {
            refreshing = false
        }
    }

    private fun toggleLive() {
        if (s.liveRunning) {
            EngineService.stop(this)
            toast("ارزیابی زنده متوقف شد")
        } else {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 77)
            }
            EngineService.start(this)
            toast("ارزیابی زنده روشن شد — شرایط دائماً بررسی می‌شود")
        }
        refreshCurrent()
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ۱) تب چارت
    // ══════════════════════════════════════════════════════════════════════════
    private fun buildChartScreen(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        engineInfo = Ui.tv(this, "", 11f, Palette.dim).apply {
            setPadding(Ui.dp(this@MainActivity, 8f), Ui.dp(this@MainActivity, 4f),
                    Ui.dp(this@MainActivity, 8f), Ui.dp(this@MainActivity, 4f))
        }
        root.addView(engineInfo)
        root.addView(Ui.hline(this))

        // ── نوار ابزار ──
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val bIn = Ui.btn(this, "＋", Palette.panel2, Palette.txt, 14f)
        val bOut = Ui.btn(this, "−", Palette.panel2, Palette.txt, 14f)
        val bFit = Ui.btn(this, "تنظیم نما", Palette.panel2, Palette.txt, 12f)
        val bVol = Ui.btn(this, "حجم", Palette.panel2, Palette.txt, 12f)
        val bZone = Ui.btn(this, "باکس‌ها", Palette.panel2, Palette.txt, 12f)
        val bMark = Ui.btn(this, "برچسب‌ها", Palette.panel2, Palette.txt, 12f)
        for (b in listOf(bIn, bOut, bFit, bVol, bZone, bMark)) {
            b.setPadding(Ui.dp(this, 6f), 0, Ui.dp(this, 6f), 0)
            tools.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(tools)

        bIn.setOnClickListener { chart?.zoomIn() }
        bOut.setOnClickListener { chart?.zoomOut() }
        bFit.setOnClickListener { chart?.resetView() }
        bVol.setOnClickListener { chart?.let { it.showVolume = !it.showVolume; it.invalidate() } }
        bZone.setOnClickListener { chart?.let { it.showZones = !it.showZones; it.invalidate() } }
        bMark.setOnClickListener { chart?.let { it.showMarkers = !it.showMarkers; it.invalidate() } }

        // ── چارت ──
        val cv = ChartView(this)
        chart = cv
        cv.chartTfSec = s.chartTfSec
        cv.onInfo = { txt -> chartInfo?.text = txt }
        root.addView(cv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, chartAreaWeight))

        // ── دستهٔ تغییر اندازهٔ چارت ──
        val handle = Ui.tv(this, "⋮⋮  کشیدن برای تغییر اندازهٔ چارت  ⋮⋮", 10f, Palette.dim).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(Palette.panel2)
            setPadding(0, Ui.dp(this@MainActivity, 4f), 0, Ui.dp(this@MainActivity, 4f))
        }
        root.addView(handle)
        handle.setOnTouchListener(object : View.OnTouchListener {
            var lastY = 0f
            override fun onTouch(v: View, e: android.view.MotionEvent): Boolean {
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> lastY = e.rawY
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val dy = e.rawY - lastY
                        lastY = e.rawY
                        chartAreaWeight = (chartAreaWeight - dy / 400f).coerceIn(0.35f, 4.5f)
                        cv.layoutParams = (cv.layoutParams as LinearLayout.LayoutParams).apply { weight = chartAreaWeight }
                        cv.requestLayout()
                    }
                }
                return true
            }
        })

        // ── اطلاعات کراس‌هیر ──
        chartInfo = Ui.tv(this, "با نگه‌داشتن انگشت، قیمت و زمان کندل را ببینید · زوم دو‌انگشتی · کشیدن یک‌انگشتی", 10.5f, Palette.dim).apply {
            setPadding(Ui.dp(this@MainActivity, 8f), Ui.dp(this@MainActivity, 4f),
                    Ui.dp(this@MainActivity, 8f), Ui.dp(this@MainActivity, 4f))
        }
        root.addView(chartInfo)

        // ── تایم‌فریم چارت (تریگر ۲) ──
        val tfRow = HorizontalScrollView(this)
        val tfBox = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tfRow.addView(tfBox)
        tfBox.addView(Ui.tv(this, "تایم‌فریم چارت (تریگر ۲): ", 11f, Palette.dim).apply {
            setPadding(Ui.dp(this@MainActivity, 8f), Ui.dp(this@MainActivity, 6f), 0, 0)
        })
        for (c in tfChoices) {
            val b = Ui.btn(this, Tf.label(c), if (c == s.chartTfSec) Palette.accent else Palette.panel2,
                    if (c == s.chartTfSec) Palette.txt else Palette.dim, 11f)
            b.setPadding(Ui.dp(this, 8f), 0, Ui.dp(this, 8f), 0)
            b.setOnClickListener {
                s.changeChartTf(c)
                chart?.chartTfSec = c
                showTab(0)
            }
            tfBox.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(tfRow)

        // ── رویدادهای موتور ──
        eventsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val evScroll = ScrollView(this)
        evScroll.addView(eventsBox)
        root.addView(evScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.9f))

        root.addView(Ui.hline(this))
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val bHist = Ui.btn(this, "دانلود تاریخچه", Palette.panel2, Palette.txt, 11f)
        val bBack = Ui.btn(this, "بک‌تست مجدد", Palette.panel2, Palette.txt, 11f)
        btnRow.addView(bHist); btnRow.addView(bBack)
        bHist.setOnClickListener { s.downloadHistory { m -> toast(m) } }
        bBack.setOnClickListener { s.rebuild(); toast("موتور روی دادهٔ موجود از نو اجرا شد") }
        root.addView(btnRow)
        return root
    }

    private fun refreshChartTab() {
        val cv = chart ?: return
        cv.candles = s.candles
        cv.zones = s.engine.zones
        cv.markers = s.engine.markers
        cv.trades = s.broker.trades
        cv.pocPrice = s.engine.lastPocPx
        if (cv.showZones && s.engine.zones.none { it.status == ZoneStatus.ACTIVE }) cv.pocPrice = s.engine.lastPocPx
        cv.overlays = s.engine.setups.map { st ->
            SetupOverlay(st.id, st.dir, st.stage, st.zTop, st.zBot, st.lvH, st.lvL, st.hvH, st.hvL,
                    st.entry, st.sl, st.tp1, st.tp2, st.tpx, st.midRef, st.zBi)
        }
        cv.refresh()

        val e = s.engine
        engineInfo?.text = buildString {
            append("ستاپ‌های هم‌زمان: ${Fa.d(e.setups.size.toString())}")
            append("  ·  مرحله: ${e.setups.minByOrNull { it.stage }?.let { e.stageFa(it.stage) } ?: "—"}")
            append("  ·  باکس ناحیه: ${Fa.d(e.zones.count { it.status == ZoneStatus.ACTIVE }.toString())} فعال")
            append(" / ${Fa.d(e.zones.count { it.status == ZoneStatus.DELETED }.toString())} پاک‌شده")
            append(" / ${Fa.d(e.zones.count { it.status == ZoneStatus.REJECTED }.toString())} ردشده")
            append("\nآخرین ورود: ${Fa.n(e.lastEntryPx)}   حدضرر: ${Fa.n(e.lastSlPx)}   حدسود: ${Fa.n(e.lastTpPx)}   نتیجه: ${e.lastResult}")
            append("\nآمار: ستاپ ${Fa.d(e.cnt.setup.toString())} · برخورد ${Fa.d(e.cnt.touch.toString())} · میانی ${Fa.d(e.cnt.mid.toString())} · ولوم کم ${Fa.d(e.cnt.lv.toString())} · ولوم زیاد ${Fa.d(e.cnt.hv.toString())} · ورود ${Fa.d(e.cnt.entry.toString())}")
        }

        // رویدادها
        eventsBox?.let { box ->
            box.removeAllViews()
            val evs = e.events.takeLast(14).reversed()
            if (evs.isEmpty()) {
                box.addView(Ui.tv(this, "رویدادی ثبت نشده — با دانلود تاریخچه، بک‌تست شروع می‌شود.", 11f, Palette.dim))
            }
            for (ev in evs) {
                box.addView(Ui.tv(this, "• $ev", 11f, Palette.dim).apply {
                    setPadding(Ui.dp(this@MainActivity, 8f), Ui.dp(this@MainActivity, 1f),
                            Ui.dp(this@MainActivity, 8f), Ui.dp(this@MainActivity, 1f))
                })
            }
            // پوزیشن باز / سفارش معلق
            s.broker.openTrade?.takeIf { it.open }?.let { t ->
                box.addView(Ui.tv(this, "پوزیشن باز: ${if (t.dir == 1) "خرید" else "فروش"} @ ${Fa.n(t.entry)} · سود/زیان: ${Fa.signed(t.pnl())}",
                        11f, Palette.gold))
            }
            s.broker.pendingOrder?.let { o ->
                box.addView(Ui.tv(this, "سفارش ورود در انتظار: ${if (o.dir == 1) "خرید لیمیت" else "فروش لیمیت"} @ ${Fa.n(o.price)}",
                        11f, Palette.accent))
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ۲) تب سفارش‌ها
    // ══════════════════════════════════════════════════════════════════════════
    private fun buildOrdersScreen(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val orders = s.broker.orders
        val trades = s.broker.trades

        root.addView(Ui.tv(this, "سفارش‌ها (${Fa.d(orders.size.toString())}) — هر سفارش با جزئیات کامل", 12.5f, Palette.gold, true).apply {
            setPadding(Ui.dp(this@MainActivity, 8f), Ui.dp(this@MainActivity, 6f), 0, Ui.dp(this@MainActivity, 4f))
        })

        val lvOrders = ListView(this)
        lvOrders.adapter = object : BaseAdapter() {
            override fun getCount() = orders.size
            override fun getItem(position: Int) = orders[orders.size - 1 - position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val o = getItem(position) as Order
                return orderRow(o)
            }
        }
        lvOrders.setOnItemClickListener { _, _, position, _ ->
            showOrderDialog(orders[orders.size - 1 - position])
        }
        root.addView(lvOrders, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(Ui.hline(this))
        root.addView(Ui.tv(this, "معاملات (${Fa.d(trades.size.toString())})", 12.5f, Palette.gold, true).apply {
            setPadding(Ui.dp(this@MainActivity, 8f), Ui.dp(this@MainActivity, 6f), 0, Ui.dp(this@MainActivity, 4f))
        })
        val lvTrades = ListView(this)
        lvTrades.adapter = object : BaseAdapter() {
            override fun getCount() = trades.size
            override fun getItem(position: Int) = trades[trades.size - 1 - position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val t = getItem(position) as Trade
                return tradeRow(t)
            }
        }
        lvTrades.setOnItemClickListener { _, _, position, _ ->
            showTradeDialog(trades[trades.size - 1 - position])
        }
        root.addView(lvTrades, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun orderRow(o: Order): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@MainActivity, 10f), Ui.dp(this@MainActivity, 7f),
                    Ui.dp(this@MainActivity, 10f), Ui.dp(this@MainActivity, 7f))
        }
        val st = when (o.status) {
            OrderStatus.PENDING -> "در انتظار"
            OrderStatus.FILLED -> "پر شده"
            OrderStatus.CANCELLED_EXPIRED -> "منقضی"
            OrderStatus.CANCELLED_INVALID -> "لغو" + (o.cancelReason?.let { " ($it)" } ?: "")
            else -> "لغو دستی"
        }
        val col = when (o.status) {
            OrderStatus.PENDING -> Palette.accent
            OrderStatus.FILLED -> Palette.up
            else -> Palette.dim
        }
        row.addView(Ui.tv(this, "#${Fa.d(o.id.toString())}  ${if (o.dir == 1) "خرید لیمیت" else "فروش لیمیت"}  @ ${Fa.n(o.price)}   [$st]",
                12.5f, col, true))
        row.addView(Ui.tv(this, "حدضرر ${Fa.n(o.sl)} · TP1 ${Fa.n(o.tp1)} · TP2 ${Fa.n(o.tp2)} · TP ${Fa.n(o.tpX)} · حجم ${Fa.n(o.qty, 3)}",
                11f, Palette.dim))
        row.addView(Ui.tv(this, "ناحیه: ${Fa.n(o.zoneBot)} تا ${Fa.n(o.zoneTop)} · ثبت ${Fa.jalali(o.placedT)}" +
                (o.filledT?.let { " · پر شد ${Fa.jalali(it)}" } ?: ""), 10.5f, Palette.dim))
        return row
    }

    private fun tradeRow(t: Trade): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@MainActivity, 10f), Ui.dp(this@MainActivity, 7f),
                    Ui.dp(this@MainActivity, 10f), Ui.dp(this@MainActivity, 7f))
        }
        val pnl = t.pnl()
        val col = if (t.open) Palette.accent else if (pnl >= 0) Palette.up else Palette.down
        row.addView(Ui.tv(this, "#${Fa.d(t.id.toString())}  ${if (t.dir == 1) "خرید" else "فروش"}  " +
                "ورود ${Fa.n(t.entry)}  →  ${t.exitPx?.let { Fa.n(it) } ?: "باز"}   " +
                "${Fa.signed(pnl)} دلار  (${Fa.n(t.rMultiple(), 2)}R)",
                12.5f, col, true))
        row.addView(Ui.tv(this, "حدضرر اولیه ${Fa.n(t.sl0)} · TP ${Fa.n(t.tpX)} · حجم ${Fa.n(t.qty, 3)}" +
                (if (t.open) "" else " · دلیل: ${t.reason}"), 11f, Palette.dim))
        row.addView(Ui.tv(this, "ورود ${Fa.jalali(t.entryT)}" + (t.exitT?.let { " · خروج ${Fa.jalali(it)}" } ?: ""),
                10.5f, Palette.dim))
        return row
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ۳) تب سود و زیان
    // ══════════════════════════════════════════════════════════════════════════
    private fun buildPnlScreen(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sum = Reports.summary(s.broker.trades)

        val box = Ui.box(this)
        box.addView(Ui.tv(this, "خلاصهٔ عملکرد", 13f, Palette.gold, true))
        box.addView(Ui.label(this, "سود/زیان خالص (دلار)", Fa.signed(sum.net), vColor = if (sum.net >= 0) Palette.up else Palette.down))
        box.addView(Ui.label(this, "تعداد معاملات", Fa.d(sum.trades.toString())))
        box.addView(Ui.label(this, "برد / باخت", "${Fa.d(sum.wins.toString())} / ${Fa.d(sum.losses.toString())}  (${Fa.pct(sum.winRate)})"))
        box.addView(Ui.label(this, "ضریب سود (Profit Factor)", Fa.n(sum.profitFactor, 2)))
        box.addView(Ui.label(this, "میانگین R", Fa.n(sum.avgR, 2)))
        box.addView(Ui.label(this, "بیشترین افت سرمایه", Fa.n(sum.maxDD, 2)))
        box.addView(Ui.label(this, "موجودی نهایی", Fa.n(s.broker.balance, 2)))
        box.addView(Ui.label(this, "بهترین / بدترین معامله", "${Fa.signed(sum.best)} / ${Fa.signed(sum.worst)}"))
        root.addView(box)

        val eq = EquityChartView(this)
        eq.setData(s.broker.trades, s.cfg.initialEquity)
        eq.onPick = { idx -> s.broker.trades.getOrNull(idx)?.let { showTradeDialog(it) } }
        root.addView(eq, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 190f)))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val bReset = Ui.btn(this, "بازگشت زوم نمودار", Palette.panel2, Palette.txt, 11f)
        bReset.setOnClickListener { eq.resetZoom() }
        val bCsv = Ui.btn(this, "خروجی CSV معاملات", Palette.panel2, Palette.txt, 11f)
        bCsv.setOnClickListener { exportTradesCsv() }
        row.addView(bReset); row.addView(bCsv)
        root.addView(row)

        root.addView(Ui.tv(this, "معاملات (لمس کنید تا جزئیات کامل بیاید)", 12f, Palette.gold, true).apply {
            setPadding(Ui.dp(this@MainActivity, 8f), Ui.dp(this@MainActivity, 6f), 0, 0)
        })
        val lv = ListView(this)
        val list = s.broker.trades.reversed()
        lv.adapter = object : BaseAdapter() {
            override fun getCount() = list.size
            override fun getItem(position: Int) = list[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View = tradeRow(list[position])
        }
        lv.setOnItemClickListener { _, _, position, _ -> showTradeDialog(list[position]) }
        root.addView(lv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ۴) تب گزارش‌ها
    // ══════════════════════════════════════════════════════════════════════════
    private fun buildReportsScreen(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val kinds = arrayOf("روزانه", "هفتگی", "ماهانه", "سالانه")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        kinds.forEachIndexed { i, n ->
            val b = Ui.btn(this, n, if (reportKind == i) Palette.accent else Palette.panel2,
                    if (reportKind == i) Palette.txt else Palette.dim, 12f)
            b.setOnClickListener { reportKind = i; showTab(3) }
            row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(row)

        val rows = Reports.group(s.broker.trades, reportKind)
        val sum = Reports.summary(s.broker.trades)
        val box = Ui.box(this)
        box.addView(Ui.tv(this, "${kinds[reportKind]} — ${Fa.d(rows.size.toString())} دوره", 12.5f, Palette.gold, true))
        box.addView(Ui.label(this, "جمع سود/زیان", Fa.signed(sum.net), vColor = if (sum.net >= 0) Palette.up else Palette.down))
        box.addView(Ui.label(this, "نرخ برد کل", Fa.pct(sum.winRate)))
        box.addView(Ui.label(this, "ضریب سود کل", Fa.n(sum.profitFactor, 2)))
        root.addView(box)

        val lv = ListView(this)
        lv.adapter = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(position: Int) = rows[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val r = getItem(position) as Reports.Row
                val v = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(Ui.dp(this@MainActivity, 10f), Ui.dp(this@MainActivity, 6f),
                            Ui.dp(this@MainActivity, 10f), Ui.dp(this@MainActivity, 6f))
                }
                v.addView(Ui.tv(this@MainActivity, "${r.label}   ${Fa.signed(r.net)} دلار", 12.5f,
                        if (r.net >= 0) Palette.up else Palette.down, true))
                v.addView(Ui.tv(this@MainActivity,
                        "معامله ${Fa.d(r.trades.toString())} · برد ${Fa.d(r.wins.toString())} · باخت ${Fa.d(r.losses.toString())} · " +
                                "نرخ برد ${Fa.pct(r.winRate)} · PF ${Fa.n(r.profitFactor, 2)} · میانگین R ${Fa.n(r.avgR, 2)} · " +
                                "افت ${Fa.n(r.maxDD, 1)}", 10.5f, Palette.dim))
                return v
            }
        }
        root.addView(lv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bCsv = Ui.btn(this, "خروجی CSV این گزارش", Palette.panel2, Palette.txt, 11f)
        bCsv.setOnClickListener { exportReportCsv(rows, kinds[reportKind]) }
        root.addView(bCsv)
        return root
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ۵) تب تنظیمات
    // ══════════════════════════════════════════════════════════════════════════
    private fun buildSettingsScreen(): View {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@MainActivity, 10f), Ui.dp(this@MainActivity, 6f),
                    Ui.dp(this@MainActivity, 10f), Ui.dp(this@MainActivity, 20f))
        }
        scroll.addView(root)

        // ── تایم‌فریم ──
        root.addView(Ui.section(this, "① تایم‌فریم‌ها"))
        for (m in TF_MODES) {
            val b = Ui.btn(this, "مود ${Fa.d(m.idx.toString())} — ${m.pretty()}",
                    if (s.cfg.tfMode == m.idx) Palette.accent else Palette.panel2,
                    if (s.cfg.tfMode == m.idx) Palette.txt else Palette.dim, 11.5f)
            b.setOnClickListener {
                s.setMode(m.idx)
                toast("مود ${Fa.d(m.idx.toString())} انتخاب شد — موتور از نو اجرا می‌شود")
                showTab(4)
            }
            root.addView(b)
            root.addView(Ui.spacer(this, 3f))
        }
        if (s.cfg.tfMode == 5) {
            root.addView(pickRow("ساختار", s.cfg.tfS) { v ->
                s.cfg.tfS = v; s.rebuild()
            })
            root.addView(pickRow("میانی", s.cfg.tfM) { v -> s.cfg.tfM = v; s.rebuild() })
            root.addView(pickRow("تریگر ۱", s.cfg.tf1) { v -> s.cfg.tf1 = v; s.rebuild() })
            root.addView(pickRow("تریگر ۲ (چارت)", s.cfg.tf2) { v ->
                s.cfg.tf2 = v; s.changeChartTf(v)
            })
        } else {
            root.addView(Ui.tv(this, "چارت روی تریگر ۲ = ${Tf.label(s.chartTfSec)} اجرا می‌شود (طبق استراتژی)", 11f, Palette.dim))
        }

        // ── پروفایل حجم ──
        root.addView(Ui.section(this, "② پروفایل حجم فیکس‌رنج و ناحیه‌ها (الگوریتم v2)"))
        root.addView(numRow("تعداد ردیف‌ها", s.cfg.vpRows.toDouble()) { v -> s.cfg.vpRows = v.toInt(); s.rebuild() })
        root.addView(numRow("هموارسازی ردیف‌ها (۰-۳)", s.cfg.vpSmooth.toDouble()) { v -> s.cfg.vpSmooth = v.toInt(); s.rebuild() })
        root.addView(numRow("حداقل ردیف یک ناحیه", s.cfg.minZoneRows.toDouble()) { v -> s.cfg.minZoneRows = v.toInt(); s.rebuild() })
        root.addView(numRow("حداکثر باکس ناحیه روی چارت", s.cfg.maxZoneBoxes.toDouble()) { v -> s.cfg.maxZoneBoxes = v.toInt() })
        root.addView(switchRow("نمایش ناحیه‌های ردشده (خط‌چین خاکستری)", s.cfg.showRejectedZones) { v ->
            s.cfg.showRejectedZones = v; s.rebuild()
        })
        root.addView(switchRow("پاک کردن باکس با کلوز از سمت دور", s.cfg.clearUsedZones) { v ->
            s.cfg.clearUsedZones = v; s.rebuild()
        })
        root.addView(Ui.tv(this, "مدل توزیع حجم داخل کندل ۱ دقیقه‌ای", 11.5f, Palette.dim))
        val dists = arrayOf("مثلثی حول Close", "مثلثی حول Typical", "یکنواخت")
        dists.forEachIndexed { i, n ->
            val rb = Ui.btn(this, n, if (s.cfg.distMode == i) Palette.accent else Palette.panel2,
                    if (s.cfg.distMode == i) Palette.txt else Palette.dim, 11f)
            rb.setOnClickListener { s.cfg.distMode = i; s.rebuild(); showTab(4) }
            root.addView(rb)
        }

        // ── موتور ──
        root.addView(Ui.section(this, "③ موتور استراتژی"))
        root.addView(numRow("حداکثر ستاپ‌های هم‌زمان", s.cfg.maxSetups.toDouble()) { v -> s.cfg.maxSetups = v.toInt(); s.rebuild() })
        root.addView(switchRow("شکست تایید میانی با کلوز (خاموش = با سایه)", s.cfg.midInvalidClose) { v ->
            s.cfg.midInvalidClose = v; s.rebuild()
        })
        root.addView(switchRow("اسکن ولوم زیاد از اولین برگشت به باکس (خاموش = از لحظهٔ تایید)", s.cfg.hvScanFirstTouch) { v ->
            s.cfg.hvScanFirstTouch = v; s.rebuild()
        })
        root.addView(switchRow("کندل ولوم زیاد = اولین افزایش حجم نسبت به کندل قبل", s.cfg.hvMarkFirstIncrease) { v ->
            s.cfg.hvMarkFirstIncrease = v; s.rebuild()
        })

        // ── معامله ──
        root.addView(Ui.section(this, "④ معامله و بک‌تست"))
        root.addView(numRow("بافر حد ضرر (تیک)", s.cfg.slBufTicks) { v -> s.cfg.slBufTicks = v; s.rebuild() })
        root.addView(numRow("حداقل فاصلهٔ حدسود (واحد قیمتی)", s.cfg.minTPunits) { v -> s.cfg.minTPunits = v; s.rebuild() })
        root.addView(switchRow("حجم بر اساس ریسک ثابت (خاموش = درصد سرمایه)", s.cfg.useRiskPct) { v ->
            s.cfg.useRiskPct = v; s.rebuild()
        })
        root.addView(numRow("ریسک هر معامله (٪)", s.cfg.riskPct) { v -> s.cfg.riskPct = v; s.rebuild() })
        root.addView(numRow("حداکثر اهرم مجاز", s.cfg.maxLeverage) { v -> s.cfg.maxLeverage = v; s.rebuild() })
        root.addView(numRow("سرمایهٔ اولیه", s.cfg.initialEquity) { v -> s.cfg.initialEquity = v; s.rebuild() })
        root.addView(numRow("لغو سفارش پس از N کندل", s.cfg.maxBarsToFill.toDouble()) { v -> s.cfg.maxBarsToFill = v.toInt(); s.rebuild() })
        root.addView(numRow("اندازهٔ قرارداد (اونس)", s.cfg.contractSize) { v -> s.cfg.contractSize = v; s.rebuild() })

        // ── فید ──
        root.addView(Ui.section(this, "⑤ فید داده (طلا)"))
        root.addView(Ui.tv(this, "نماد: ${s.symbol}   |   تایم‌فریم چارت: ${Tf.label(s.chartTfSec)}   |   کندل‌ها: ${Fa.d(s.candles.size.toString())}", 11.5f, Palette.txt))
        root.addView(editRow("نماد (Yahoo)", s.symbol) { v -> s.symbol = v; })
        val depths = arrayOf("کوتاه", "متوسط", "عمیق")
        depths.forEachIndexed { i, n ->
            val b = Ui.btn(this, "عمق تاریخچه: $n", if (s.depth == i + 1) Palette.accent else Palette.panel2,
                    if (s.depth == i + 1) Palette.txt else Palette.dim, 11f)
            b.setOnClickListener { s.depth = i + 1; showTab(4) }
            root.addView(b)
        }
        root.addView(switchRow("ساخت حجم تقریبی اگر فید حجم نداشت", s.useSyntheticVolume) { v -> s.useSyntheticVolume = v })
        root.addView(switchRow("حالت بک‌تست کامل (آخرین کندل هم بسته حساب شود)", s.backtestFull) { v ->
            s.backtestFull = v; s.rebuild(clearOrders = false)
        })
        root.addView(numRow("فاصلهٔ به‌روزرسانی لایو (ثانیه)", s.livePollMs / 1000.0) { v -> s.livePollMs = (v * 1000).toLong().coerceAtLeast(2000) })

        val feedRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val bHist = Ui.btn(this, "دانلود تاریخچه", Palette.panel2, Palette.txt, 11f)
        val bSample = Ui.btn(this, "دادهٔ نمونه", Palette.panel2, Palette.txt, 11f)
        val bCsv = Ui.btn(this, "ورود CSV", Palette.panel2, Palette.txt, 11f)
        feedRow.addView(bHist); feedRow.addView(bSample); feedRow.addView(bCsv)
        bHist.setOnClickListener { s.downloadHistory { m -> toast(m); showTab(4) } }
        bSample.setOnClickListener { s.loadSample(applicationContext) { m -> toast(m); showTab(4) } }
        bCsv.setOnClickListener { pickCsv() }
        root.addView(feedRow)

        // ── ذخیره/بازیابی ──
        root.addView(Ui.section(this, "⑥ ذخیره و بازیابی (دقیق، بی‌کم‌وکاست)"))
        root.addView(Ui.tv(this, "فایل خودکار: ${Storage.autoFile(this).absolutePath}", 10.5f, Palette.dim))
        val saveRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val bSaveAuto = Ui.btn(this, "ذخیرهٔ فوری", Palette.panel2, Palette.txt, 11f)
        val bSaveFile = Ui.btn(this, "ذخیره در فایل…", Palette.panel2, Palette.txt, 11f)
        val bLoadFile = Ui.btn(this, "بازیابی از فایل…", Palette.panel2, Palette.txt, 11f)
        saveRow.addView(bSaveAuto); saveRow.addView(bSaveFile); saveRow.addView(bLoadFile)
        bSaveAuto.setOnClickListener { toast(s.saveToAutoFile(applicationContext)) }
        bSaveFile.setOnClickListener { pickSave() }
        bLoadFile.setOnClickListener { pickLoad() }
        root.addView(saveRow)
        root.addView(Ui.tv(this, "نکته: باکس‌های ناحیه‌ای که با گذشت زمان پاک شده‌اند هم ذخیره می‌شوند و پس از بازیابی، دقیقاً همان‌طور (خط‌چین خاکستری) برمی‌گردند.", 10.5f, Palette.dim))

        val bWipe = Ui.btn(this, "پاک کردن همه چیز و شروع از صفر", Palette.panel2, Palette.down, 11.5f)
        bWipe.setOnClickListener {
            AlertDialog.Builder(this).setTitle("پاک کردن همه چیز؟")
                .setMessage("همهٔ کندل‌ها، سفارش‌ها، معاملات و باکس‌ها پاک می‌شود.")
                .setPositiveButton("بله") { _, _ -> s.wipe(); toast("پاک شد") }
                .setNegativeButton("نه", null).show()
        }
        root.addView(bWipe)

        root.addView(Ui.section(this, "⑦ دربارهٔ اپ"))
        root.addView(Ui.tv(this, "GoldPin — نسخهٔ ۱٫۰\nموتور PinReady (کندل مهم → Ready → pin → روند → ناحیهٔ فیکس‌رنج → تایید میانی → ولوم کم → ولوم زیاد → ورود/حدضرر/حدسود ۱٫۲۷۲)\n" +
                "همهٔ محاسبات داخل گوشی انجام می‌شود؛ معاملات «کاغذی» است و به هیچ کارگزاری وصل نیست.\n" +
                "فید پیش‌فرض: Yahoo (GC=F) + طلای نقدی gold-api — برای تاریخچهٔ عمیق با حجم واقعی می‌توانید CSV دوکاس‌کپی را وارد کنید.", 11f, Palette.dim))

        return scroll
    }

    private fun pickRow(label: String, current: Int, onPick: (Int) -> Unit): View {
        val b = Ui.btn(this, "$label : ${Tf.label(current)}", Palette.panel2, Palette.txt, 11.5f)
        b.setOnClickListener {
            val labels = tfChoices.map { Tf.label(it) }.toTypedArray()
            AlertDialog.Builder(this).setTitle(label)
                .setItems(labels) { _, which -> onPick(tfChoices[which]); showTab(4) }
                .show()
        }
        return b
    }

    private fun numRow(label: String, value: Double, apply: (Double) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val t = Ui.tv(this, label, 11.5f, Palette.dim)
        val e = Ui.edit(this, if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString())
        val b = Ui.btn(this, "ثبت", Palette.panel2, Palette.accent, 11f)
        b.setOnClickListener {
            val v = e.text.toString().trim().toDoubleOrNull()
            if (v == null) toast("عدد نامعتبر") else { apply(v); toast("$label = ${Fa.n(v, 2)}"); showTab(4) }
        }
        row.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f))
        row.addView(e, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return row
    }

    private fun editRow(label: String, value: String, apply: (String) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val e = Ui.edit(this, value)
        e.inputType = android.text.InputType.TYPE_CLASS_TEXT
        val b = Ui.btn(this, "ثبت", Palette.panel2, Palette.accent, 11f)
        b.setOnClickListener { apply(e.text.toString().trim()); toast("$label ثبت شد"); showTab(4) }
        row.addView(Ui.tv(this, label, 11.5f, Palette.dim), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f))
        row.addView(e, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return row
    }

    private fun switchRow(label: String, value: Boolean, apply: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val sw = android.widget.Switch(this).apply {
            isChecked = value
            setOnCheckedChangeListener { _, v -> apply(v); }
        }
        row.addView(Ui.tv(this, label, 11.5f, Palette.dim), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(sw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return row
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  دیالوگ جزئیات
    // ══════════════════════════════════════════════════════════════════════════
    private fun showTradeDialog(t: Trade) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@MainActivity, 14f), Ui.dp(this@MainActivity, 10f),
                    Ui.dp(this@MainActivity, 14f), Ui.dp(this@MainActivity, 10f))
        }
        fun add(k: String, v: String, c: Int = Palette.txt) {
            box.addView(Ui.label(this, k, v, vColor = c))
        }
        val pnl = t.pnl()
        add("شناسهٔ معامله", "#${Fa.d(t.id.toString())}")
        add("جهت", if (t.dir == 1) "خرید (Long)" else "فروش (Short)")
        add("وضعیت", if (t.open) "باز" else "بسته — ${t.reason}")
        add("زمان ورود", Fa.jalali(t.entryT))
        add("قیمت ورود", Fa.n(t.entry))
        add("حجم", "${Fa.n(t.qty, 3)} اونس")
        add("حد ضرر اولیه", Fa.n(t.sl0))
        add("TP1 / TP2", "${Fa.n(t.tp1)} / ${Fa.n(t.tp2)}")
        add("حد سود نهایی (۱٫۲۷۲)", Fa.n(t.tpX))
        add("ناحیهٔ ساختار", "${Fa.n(t.zoneBot)} تا ${Fa.n(t.zoneTop)}")
        if (!t.open) {
            add("زمان خروج", Fa.jalali(t.exitT ?: 0))
            add("قیمت خروج", Fa.n(t.exitPx ?: Double.NaN))
            add("مدت", duration(t.entryT, t.exitT ?: 0))
        }
        add("کف‌شکنی سربه‌سر", if (t.be) "فعال شد (پس از TP1)" else "فعال نشد")
        add("سود/زیان", "${Fa.signed(pnl)} دلار", if (pnl >= 0) Palette.up else Palette.down)
        add("بازده بر ریسک", "${Fa.n(t.rMultiple(), 2)} R")
        box.addView(Ui.spacer(this, 6f))
        box.addView(Ui.tv(this, "پله‌های اجرا", 12f, Palette.gold, true))
        for (f in t.fills) {
            box.addView(Ui.tv(this, "• ${f.kind}  ${Fa.n(f.qty, 3)} @ ${Fa.n(f.price)}  ·  ${Fa.signed(f.pnl)} دلار  ·  ${Fa.jalali(f.t)}",
                    11f, if (f.pnl >= 0) Palette.txt else Palette.down))
        }
        AlertDialog.Builder(this).setTitle("جزئیات معامله").setView(box).setPositiveButton("بستن", null).show()
    }

    private fun showOrderDialog(o: Order) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@MainActivity, 14f), Ui.dp(this@MainActivity, 10f),
                    Ui.dp(this@MainActivity, 14f), Ui.dp(this@MainActivity, 10f))
        }
        fun add(k: String, v: String) = box.addView(Ui.label(this, k, v))
        add("شناسه", "#${Fa.d(o.id.toString())}")
        add("نوع", "سفارش لیمیت ${if (o.dir == 1) "خرید" else "فروش"} (کاغذی)")
        add("وضعیت", when (o.status) {
            OrderStatus.PENDING -> "در انتظار پر شدن"
            OrderStatus.FILLED -> "پر شده"
            OrderStatus.CANCELLED_EXPIRED -> "منقضی شده"
            else -> "لغو شده" + (o.cancelReason?.let { " — $it" } ?: "")
        })
        add("قیمت سفارش", Fa.n(o.price))
        add("حد ضرر", Fa.n(o.sl))
        add("TP1 / TP2 / TP نهایی", "${Fa.n(o.tp1)} / ${Fa.n(o.tp2)} / ${Fa.n(o.tpX)}")
        add("حجم", "${Fa.n(o.qty, 3)} اونس")
        add("ناحیهٔ ساختار", "${Fa.n(o.zoneBot)} تا ${Fa.n(o.zoneTop)}")
        add("زمان ثبت", Fa.jalali(o.placedT))
        o.filledT?.let { add("زمان پر شدن", Fa.jalali(it)) }
        add("مهلت", "${Fa.d(s.cfg.maxBarsToFill.toString())} کندل")
        AlertDialog.Builder(this).setTitle("جزئیات سفارش").setView(box).setPositiveButton("بستن", null).show()
    }

    private fun duration(a: Long, b: Long): String {
        val d = abs(b - a) / 1000
        val h = d / 3600
        val m = (d % 3600) / 60
        return "${Fa.d(h.toString())} ساعت و ${Fa.d(m.toString())} دقیقه"
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  فایل‌ها (SAF)
    // ══════════════════════════════════════════════════════════════════════════
    private fun pickSave() {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/gzip"
            putExtra(Intent.EXTRA_TITLE, "goldpin_state_${System.currentTimeMillis() / 1000}.json.gz")
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_SAVE)
    }

    private fun pickLoad() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_LOAD)
    }

    private fun pickCsv() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_CSV)
    }

    private fun exportTradesCsv() {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/csv"
            putExtra(Intent.EXTRA_TITLE, "goldpin_trades.csv")
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_REPORT)
    }

    private fun exportReportCsv(rows: List<Reports.Row>, title: String) {
        val sb = StringBuilder("دوره,معاملات,برد,باخت,نرخ برد,سود خالص,سود ناخالص,زیان ناخالص,ضریب سود,میانگین R,بیشترین افت,بهترین,بدترین\n")
        for (r in rows) {
            sb.append(r.label).append(',').append(r.trades).append(',').append(r.wins).append(',').append(r.losses)
                .append(',').append(String.format("%.1f", r.winRate)).append(',').append(String.format("%.2f", r.net))
                .append(',').append(String.format("%.2f", r.grossWin)).append(',').append(String.format("%.2f", r.grossLoss))
                .append(',').append(String.format("%.2f", r.profitFactor)).append(',').append(String.format("%.2f", r.avgR))
                .append(',').append(String.format("%.2f", r.maxDD)).append(',').append(String.format("%.2f", r.best))
                .append(',').append(String.format("%.2f", r.worst)).append('\n')
        }
        pendingReport = sb.toString()
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/csv"
            putExtra(Intent.EXTRA_TITLE, "goldpin_report_$title.csv")
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_REPORT)
    }

    private var pendingReport: String? = null

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        when (requestCode) {
            REQ_SAVE -> {
                try {
                    Storage.writeUri(this, uri, s.buildJson())
                    toast("فایل ذخیره شد: ${uri.lastPathSegment}")
                } catch (e: Exception) { toast("خطای ذخیره: ${e.message}") }
            }
            REQ_LOAD -> {
                try {
                    val txt = Storage.readUri(this, uri)
                    val msg = s.loadFromText(txt)
                    toast(msg)
                    showTab(0)
                } catch (e: Exception) { toast("خطای بازیابی: ${e.message}") }
            }
            REQ_CSV -> {
                s.importCsv(this, uri, s.baseTfSec) { m -> toast(m); showTab(0) }
            }
            REQ_REPORT -> {
                try {
                    val text = pendingReport ?: tradesCsv()
                    Storage.writeReport(this, uri, text)
                    toast("گزارش ذخیره شد")
                } catch (e: Exception) { toast("خطای گزارش: ${e.message}") }
            }
        }
    }

    private fun tradesCsv(): String {
        val sb = StringBuilder("id,dir,entryT,entry,exitT,exit,sl,tpX,qty,pnl,R,reason\n")
        for (t in s.broker.trades) {
            sb.append(t.id).append(',').append(if (t.dir == 1) "BUY" else "SELL").append(',')
                .append(t.entryT).append(',').append(t.entry).append(',')
                .append(t.exitT ?: 0).append(',').append(t.exitPx ?: 0.0).append(',')
                .append(t.sl0).append(',').append(t.tpX).append(',').append(t.qty).append(',')
                .append(String.format("%.2f", t.pnl())).append(',').append(String.format("%.2f", t.rMultiple()))
                .append(',').append(t.reason).append('\n')
        }
        return sb.toString()
    }

    // ══════════════════════════════════════════════════════════════════════════
    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
    }
}
