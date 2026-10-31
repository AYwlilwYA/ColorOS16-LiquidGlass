package com.coloros16.liquidglass.debug

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.coloros16.liquidglass.App
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.log.Lg
import org.json.JSONArray
import kotlin.math.abs
import kotlin.math.roundToInt

// ---------- 广播通道协议（模块进程 ↔ 注入进程代理） ----------

/** 扫描请求：模块 → 目标包（extra: pkg=目标包名）。 */
const val ACTION_SCAN_REQUEST = "com.coloros16.liquidglass.BALL_SCAN_REQUEST"

/** 扫描结果：目标包 → 模块（extra: pkg / items=JSON数组 / truncated=截断条数）。 */
const val ACTION_SCAN_RESULT = "com.coloros16.liquidglass.BALL_SCAN_RESULT"

/** 固化请求：模块 → 目标包（extra: pkg / cls / id / x / y / w / h，屏幕坐标）。 */
const val ACTION_FREEZE_REQUEST = "com.coloros16.liquidglass.BALL_FREEZE_REQUEST"

/** 固化结果：目标包 → 模块（extra: ok / label / reason）。 */
const val ACTION_FREEZE_RESULT = "com.coloros16.liquidglass.BALL_FREEZE_RESULT"

private const val TAG = "GlobalGlassBall"

/**
 * 全局悬浮球（模块自身进程）：系统最上层小球 + 面板 + 标框层。
 * 扫描/固化在目标进程内执行（View 树只在其进程可见），本球负责显示与指挥，
 * 经广播通道与注入进程代理（AppGlassAgent）通信。
 */
object GlobalGlassBall {

    private const val MODULE_PKG = "com.coloros16.liquidglass"

    internal const val SWITCH_TTL_MS = 500L
    internal const val SCAN_REPLY_TIMEOUT_MS = 3000L
    internal const val DRAG_SLOP_DP = 8f
    internal const val BALL_SIZE_DP = 48f

    internal const val COLOR_BALL_BG = 0xCC1A1A1A.toInt()
    internal const val COLOR_BOX_FIXED = 0xFF00E676.toInt()
    internal const val COLOR_BOX_GRAY = 0x66FFFFFF
    internal const val COLOR_PANEL_BG = 0xF01A1A1A.toInt()

    /** 目标应用（AppGlassHook 作用域内、装有代理的 5 个进程）。 */
    internal val TARGETS = listOf(
        "com.coloros.assistantscreen" to "负一屏",
        "com.heytap.quicksearchbox" to "搜索",
        "com.coloros.gallery3d" to "相册",
        "com.coloros.calculator" to "计算器",
        "com.coloros.smartsidebar" to "侧边栏",
    )

    /** 结果广播发送方白名单（仅五目标包可回传）。 */
    internal val TARGET_PKG_SET: Set<String> = TARGETS.mapTo(HashSet()) { it.first }

    /** 扫描回传的控件条目（屏幕坐标）。 */
    data class ScanItem(
        val cls: String,
        val id: String,
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int,
        val label: String,
        var fixed: Boolean,
    )

    /** 从设置页拉起悬浮球服务（需 SAW 权限；无权限时服务自停）。 */
    fun startFrom(context: Context) {
        try {
            context.startService(Intent(context, GlobalGlassBallService::class.java))
        } catch (t: Throwable) {
            Lg.w(TAG, "startService failed", t)
        }
    }
}

/**
 * 悬浮球宿主服务（无 UI Service）：持有小球 / 面板 / 标框三个窗口。
 * 开关复用 element_glass_enable（500ms TTL）：开且有悬浮窗权限 = 球显示。
 */
class GlobalGlassBallService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var wm: WindowManager? = null

    private var ballView: TextView? = null
    private var ballParams: WindowManager.LayoutParams? = null

    private var panelScroll: ScrollView? = null
    private var panelParams: WindowManager.LayoutParams? = null

    private var boxLayer: BallBoxLayer? = null

    private var statusText: TextView? = null
    private var frozenText: TextView? = null

    private var receiver: BroadcastReceiver? = null

    private val items = mutableListOf<GlobalGlassBall.ScanItem>()
    private var pendingPkg = ""
    private var scanAwaiting = false
    private var lastFreezeItem: GlobalGlassBall.ScanItem? = null

    private var shown = false
    private var switchLogged = false
    private var lastScanSentAtMs = 0L

    private val scanTimeoutRunnable = Runnable {
        if (scanAwaiting) {
            scanAwaiting = false
            statusText?.text = "目标进程无响应（应用未在运行？）"
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val on = switchOn() && Settings.canDrawOverlays(this@GlobalGlassBallService)
            if (on != shown) {
                if (on) showBall() else hideBall()
            }
            mainHandler.postDelayed(this, GlobalGlassBall.SWITCH_TTL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            Lg.i(TAG, "no overlay permission, service stop")
            stopSelf()
            return START_NOT_STICKY
        }
        registerResultReceiver()
        mainHandler.removeCallbacks(tick)
        mainHandler.postDelayed(tick, 0L)
        Lg.i(TAG, "ball service started")
        return START_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        hideBall()
        receiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Throwable) {
            }
        }
        receiver = null
        super.onDestroy()
    }

    // ---------- 开关 ----------

    /** 点选元素玻璃总闸（框架侧存储优先，500ms TTL）。 */
    private fun switchOn(): Boolean {
        val svc = App.serviceRef
        val value = if (svc != null) {
            try {
                Prefs.remote(svc).getBoolean(Prefs.KEY_ELEMENT_GLASS_ENABLE, false)
            } catch (t: Throwable) {
                Lg.w(TAG, "switch read failed", t)
                null
            }
        } else {
            null
        }
        if (!switchLogged || value != null) {
            switchLogged = true
            if (value != null) Lg.i(TAG, "switch element_glass_enable=$value")
        }
        return value == true
    }

    // ---------- 窗口 ----------

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun showBall() {
        if (shown) return
        shown = true
        addBallWindow()
        Lg.i(TAG, "ball shown")
    }

    private fun hideBall() {
        if (!shown) return
        shown = false
        removePanelWindow()
        removeBoxWindow()
        removeBallWindow()
        Lg.i(TAG, "ball hidden")
    }

    private fun addBallWindow() {
        if (ballView != null) return
        val density = resources.displayMetrics.density
        val ballSize = (GlobalGlassBall.BALL_SIZE_DP * density + 0.5f).toInt()
        val params = WindowManager.LayoutParams(
            ballSize, ballSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = (resources.displayMetrics.heightPixels / 2)
        }
        val ball = TextView(this).apply {
            text = "LG"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(GlobalGlassBall.COLOR_BALL_BG)
            }
        }
        attachDrag(ball, params)
        try {
            wm?.addView(ball, params)
            ballView = ball
            ballParams = params
        } catch (t: Throwable) {
            Lg.w(TAG, "add ball window failed", t)
        }
    }

    private fun removeBallWindow() {
        val v = ballView ?: return
        try {
            wm?.removeView(v)
        } catch (t: Throwable) {
            Lg.w(TAG, "remove ball failed", t)
        }
        ballView = null
        ballParams = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(ball: TextView, params: WindowManager.LayoutParams) {
        val slop = dp(GlobalGlassBall.DRAG_SLOP_DP).toFloat()
        var downX = 0f
        var downY = 0f
        var baseX = 0
        var baseY = 0
        var dragging = false
        ball.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    baseX = params.x
                    baseY = params.y
                    dragging = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragging) {
                        params.x = (baseX + (ev.rawX - downX)).roundToInt()
                        params.y = (baseY + (ev.rawY - downY)).roundToInt().coerceAtLeast(0)
                        try {
                            wm?.updateViewLayout(v, params)
                        } catch (_: Throwable) {
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    dragging = false
                    val moved = abs(ev.rawX - downX) > slop || abs(ev.rawY - downY) > slop
                    if (moved) {
                        snapToEdge(v, params)
                    } else {
                        togglePanel()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    true
                }
                else -> false
            }
        }
    }

    /** 松手吸附最近屏幕边缘。 */
    private fun snapToEdge(ball: View, params: WindowManager.LayoutParams) {
        val screenW = resources.displayMetrics.widthPixels
        params.x = if (params.x + ball.width / 2 < screenW / 2) 0 else (screenW - ball.width)
        try {
            wm?.updateViewLayout(ball, params)
        } catch (_: Throwable) {
        }
    }

    // ---------- 面板 ----------

    private fun togglePanel() {
        if (panelScroll != null) {
            removePanelWindow()
        } else {
            addPanelWindow()
            refreshFrozenText()
        }
    }

    private fun addPanelWindow() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
        }
        val panel = buildPanel()
        try {
            wm?.addView(panel, params)
            panelScroll = panel
            panelParams = params
        } catch (t: Throwable) {
            Lg.w(TAG, "add panel failed", t)
        }
    }

    private fun removePanelWindow() {
        val v = panelScroll ?: return
        try {
            wm?.removeView(v)
        } catch (_: Throwable) {
        }
        panelScroll = null
        panelParams = null
        statusText = null
        frozenText = null
    }

    @SuppressLint("SetTextI18n")
    private fun buildPanel(): ScrollView {
        val density = resources.displayMetrics.density
        fun dp(v: Float): Int = (v * density + 0.5f).toInt()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), dp(10f), dp(12f), dp(14f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14f).toFloat()
                setColor(GlobalGlassBall.COLOR_PANEL_BG)
            }
        }

        content.addView(TextView(this).apply {
            text = "玻璃悬浮球"
            textSize = 14f
            setTextColor(Color.WHITE)
        })

        val hint = TextView(this).apply {
            text = "先切到目标应用，再点扫描"
            textSize = 11f
            setTextColor(0xFF888888.toInt())
        }
        content.addView(hint)

        val pkgNames = GlobalGlassBall.TARGETS.map { it.second }
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, pkgNames)
        }

        val scanRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        scanRow.addView(spinner, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        scanRow.addView(Button(this).apply {
            text = "扫描"
            setOnClickListener { sendScan(spinner.selectedItemPosition) }
        })
        content.addView(scanRow)

        val opsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        opsRow.addView(Button(this).apply {
            text = "清框"
            setOnClickListener {
                items.clear()
                boxLayer?.invalidate()
                statusText?.text = "已清框"
            }
        })
        opsRow.addView(Button(this).apply {
            text = "刷新列表"
            setOnClickListener { refreshFrozenText() }
        })
        content.addView(opsRow)

        statusText = TextView(this).apply {
            textSize = 11f
            setTextColor(0xFFB0B0B0.toInt())
            text = "就绪"
        }
        content.addView(statusText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(6f) })

        content.addView(TextView(this).apply {
            text = "已固化条目"
            textSize = 12f
            setTextColor(0xFF80CBC4.toInt())
            setPadding(0, dp(8f), 0, dp(2f))
        })
        frozenText = TextView(this).apply {
            textSize = 11f
            setTextColor(0xFFCCCCCC.toInt())
            text = "（未读取）"
        }
        content.addView(frozenText)

        content.addView(TextView(this).apply {
            text = "点框固化；点空白处收起框层"
            textSize = 10f
            setTextColor(0xFF777777.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(8f), 0, 0)
        })

        return ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
    }

    /** 已固化列表（框架侧存储用户条目；出厂预置不在本列表内）。 */
    @SuppressLint("SetTextI18n")
    private fun refreshFrozenText() {
        val svc = App.serviceRef ?: run {
            frozenText?.text = "（框架未连接）"
            return
        }
        val text = try {
            val json = Prefs.remote(svc).getString(Prefs.KEY_ELEMENT_GLASS_ENTRIES, null)
            if (json.isNullOrEmpty()) {
                "（暂无用户固化条目）"
            } else {
                val arr = org.json.JSONObject(json).optJSONArray("entries")
                if (arr == null || arr.length() == 0) {
                    "（暂无用户固化条目）"
                } else {
                    buildString {
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            append(o.optString("label"))
                            append(" · ")
                            append(o.optString("anchorClassName").substringAfterLast('.'))
                            if (!o.optBoolean("enabled", true)) append("（停用）")
                            if (i < arr.length() - 1) append('\n')
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Lg.w(TAG, "read frozen list failed", t)
            "（读取失败: ${t.javaClass.simpleName}）"
        }
        frozenText?.text = text
    }

    // ---------- 扫描 ----------

    private fun sendScan(position: Int) {
        val pkg = GlobalGlassBall.TARGETS.getOrNull(position)?.first ?: return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastScanSentAtMs < 1000L) {
            statusText?.text = "扫描太频繁，稍等"
            return
        }
        lastScanSentAtMs = now
        pendingPkg = pkg
        scanAwaiting = true
        statusText?.text = "扫描中…"
        mainHandler.removeCallbacks(scanTimeoutRunnable)
        mainHandler.postDelayed(scanTimeoutRunnable, GlobalGlassBall.SCAN_REPLY_TIMEOUT_MS)
        try {
            sendBroadcast(Intent(ACTION_SCAN_REQUEST).setPackage(pkg).putExtra("pkg", pkg))
            Lg.i(TAG, "scan request sent pkg=$pkg")
        } catch (t: Throwable) {
            scanAwaiting = false
            Lg.w(TAG, "send scan failed", t)
            statusText?.text = "发送失败: ${t.javaClass.simpleName}"
        }
    }

    private fun onScanResult(intent: Intent) {
        scanAwaiting = false
        mainHandler.removeCallbacks(scanTimeoutRunnable)
        val pkg = intent.getStringExtra("pkg") ?: ""
        val truncated = intent.getIntExtra("truncated", 0)
        items.clear()
        try {
            val arr = JSONArray(intent.getStringExtra("items") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                items.add(
                    GlobalGlassBall.ScanItem(
                        cls = o.optString("cls"),
                        id = o.optString("id"),
                        x = o.optInt("x"),
                        y = o.optInt("y"),
                        w = o.optInt("w"),
                        h = o.optInt("h"),
                        label = o.optString("simpleLabel"),
                        fixed = o.optInt("fixed") == 1,
                    ),
                )
            }
        } catch (t: Throwable) {
            Lg.w(TAG, "parse scan items failed", t)
        }
        if (pkg.isNotEmpty()) pendingPkg = pkg
        val fixedCount = items.count { it.fixed }
        statusText?.text = "扫描完成：${items.size} 框（绿=$fixedCount）" +
            if (truncated > 0) "，另截断 $truncated" else ""
        showBoxWindow()
        Lg.i(TAG, "scan result pkg=$pendingPkg items=${items.size} fixed=$fixedCount truncated=$truncated")
    }

    // ---------- 标框层 ----------

    private fun showBoxWindow() {
        if (boxLayer == null) {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
            )
            val layer = BallBoxLayer(this)
            layer.onBoxTap = { index -> sendFreeze(index) }
            layer.onEmptyTap = {
                removeBoxWindow()
                statusText?.text = "框层已收起"
            }
            try {
                wm?.addView(layer, params)
                boxLayer = layer
            } catch (t: Throwable) {
                Lg.w(TAG, "add box window failed", t)
                return
            }
        }
        boxLayer?.setItems(items)
        raiseOverlays()
    }

    private fun removeBoxWindow() {
        val v = boxLayer ?: return
        try {
            wm?.removeView(v)
        } catch (_: Throwable) {
        }
        boxLayer = null
    }

    /** 标框层全屏可点，会盖住球与面板：加入后把球/面板提到最上。 */
    private fun raiseOverlays() {
        ballView?.let { ball ->
            ballParams?.let { p ->
                try {
                    wm?.removeView(ball)
                    wm?.addView(ball, p)
                } catch (_: Throwable) {
                }
            }
        }
        panelScroll?.let { panel ->
            panelParams?.let { p ->
                try {
                    wm?.removeView(panel)
                    wm?.addView(panel, p)
                } catch (_: Throwable) {
                }
            }
        }
    }

    // ---------- 固化 ----------

    private fun sendFreeze(index: Int) {
        val item = items.getOrNull(index) ?: return
        val pkg = pendingPkg
        if (pkg.isEmpty()) {
            statusText?.text = "请先扫描"
            return
        }
        lastFreezeItem = item
        try {
            sendBroadcast(
                Intent(ACTION_FREEZE_REQUEST).setPackage(pkg)
                    .putExtra("pkg", pkg)
                    .putExtra("cls", item.cls)
                    .putExtra("id", item.id)
                    .putExtra("x", item.x)
                    .putExtra("y", item.y)
                    .putExtra("w", item.w)
                    .putExtra("h", item.h),
            )
            statusText?.text = "固化请求已发…"
            Lg.i(TAG, "freeze request pkg=$pkg cls=${item.cls} id=${item.id}")
        } catch (t: Throwable) {
            Lg.w(TAG, "send freeze failed", t)
            statusText?.text = "发送失败: ${t.javaClass.simpleName}"
        }
    }

    private fun onFreezeResult(intent: Intent) {
        val ok = intent.getBooleanExtra("ok", false)
        val label = intent.getStringExtra("label") ?: ""
        val reason = intent.getStringExtra("reason") ?: ""
        if (ok) {
            lastFreezeItem?.let { item ->
                items.firstOrNull { it === item }?.fixed = true
                boxLayer?.invalidate()
            }
            statusText?.text = "已固化「$label」，立即生效"
            Toast.makeText(this, "已固化「$label」", Toast.LENGTH_SHORT).show()
            refreshFrozenText()
            Lg.i(TAG, "freeze ok label=$label")
        } else {
            statusText?.text = "固化失败: $reason"
            Lg.w(TAG, "freeze failed reason=$reason")
        }
    }

    // ---------- 结果广播 ----------

    private fun registerResultReceiver() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                try {
                    val sender = if (Build.VERSION.SDK_INT >= 34) getSentFromPackage() else null
                    if (sender == null || sender !in GlobalGlassBall.TARGET_PKG_SET) {
                        Lg.w(TAG, "reject result action=${intent.action} sender=$sender")
                        return
                    }
                    when (intent.action) {
                        ACTION_SCAN_RESULT -> onScanResult(intent)
                        ACTION_FREEZE_RESULT -> onFreezeResult(intent)
                    }
                } catch (t: Throwable) {
                    Lg.w(TAG, "onReceive error action=${intent.action}", t)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_SCAN_RESULT)
            addAction(ACTION_FREEZE_RESULT)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(r, filter, RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(r, filter)
            }
            receiver = r
        } catch (t: Throwable) {
            Lg.w(TAG, "register result receiver failed", t)
        }
    }
}

/** 标框层：画目标进程回传的控件框（绿=已固化 灰=未固化），框可点，点空白收起。 */
private class BallBoxLayer(ctx: Context) : View(ctx) {

    var onBoxTap: ((Int) -> Unit)? = null
    var onEmptyTap: (() -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private var items: List<GlobalGlassBall.ScanItem> = emptyList()

    fun setItems(items: List<GlobalGlassBall.ScanItem>) {
        this.items = items
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (item in items) {
            paint.color = if (item.fixed) GlobalGlassBall.COLOR_BOX_FIXED else GlobalGlassBall.COLOR_BOX_GRAY
            canvas.drawRect(
                item.x.toFloat(),
                item.y.toFloat(),
                (item.x + item.w).toFloat(),
                (item.y + item.h).toFloat(),
                paint,
            )
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_UP) return true
        val x = event.x
        val y = event.y
        for (i in items.indices.reversed()) {
            val item = items[i]
            if (x >= item.x && x < item.x + item.w && y >= item.y && y < item.y + item.h) {
                onBoxTap?.invoke(i)
                return true
            }
        }
        onEmptyTap?.invoke()
        return true
    }
}
