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

/** 抓取请求：模块 → 目标包（extra: pkg / x / y 屏幕坐标）。 */
const val ACTION_TOOL_CAPTURE_REQUEST = "com.coloros16.liquidglass.BALL_TOOL_CAPTURE_REQUEST"

/** 抓取结果：目标包 → 模块（extra: ok / text / hasBox / bx / by / bw / bh）。 */
const val ACTION_TOOL_CAPTURE_RESULT = "com.coloros16.liquidglass.BALL_TOOL_CAPTURE_RESULT"

/** UI 树请求：模块 → 目标包（extra: pkg）。 */
const val ACTION_TOOL_TREE_REQUEST = "com.coloros16.liquidglass.BALL_TOOL_TREE_REQUEST"

/** UI 树结果：目标包 → 模块（extra: ok / text）。 */
const val ACTION_TOOL_TREE_RESULT = "com.coloros16.liquidglass.BALL_TOOL_TREE_RESULT"

/** 玻璃清单请求：模块 → 目标包（extra: pkg）。 */
const val ACTION_TOOL_LIST_REQUEST = "com.coloros16.liquidglass.BALL_TOOL_LIST_REQUEST"

/** 玻璃清单结果：目标包 → 模块（extra: ok / text）。 */
const val ACTION_TOOL_LIST_RESULT = "com.coloros16.liquidglass.BALL_TOOL_LIST_RESULT"

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
    internal const val COLOR_CAPTURE_BOX = 0xFF00E5FF.toInt()

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
        val saw = Settings.canDrawOverlays(context)
        Lg.i(TAG, "start requested saw=$saw")
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
    private var outputText: TextView? = null
    private var listText: TextView? = null
    private var treeText: TextView? = null

    private var receiver: BroadcastReceiver? = null

    private val items = mutableListOf<GlobalGlassBall.ScanItem>()
    private var pendingPkg = ""
    private var scanAwaiting = false
    private var lastFreezeItem: GlobalGlassBall.ScanItem? = null

    private var pendingTool = ""
    private var pickLayer: BallPickLayer? = null
    private var pickParams: WindowManager.LayoutParams? = null
    private var lastToolSentAtMs = 0L

    private var shown = false
    private var switchLogged = false
    private var lastScanSentAtMs = 0L

    private val scanTimeoutRunnable = Runnable {
        if (scanAwaiting) {
            scanAwaiting = false
            statusText?.text = "目标进程无响应（应用未在运行？）"
        }
    }

    private val toolTimeoutRunnable = Runnable {
        if (pendingTool.isNotEmpty()) {
            Lg.i(TAG, "tool timeout tool=$pendingTool pkg=$pendingPkg")
            statusText?.text = "工具无响应（应用未在运行？）"
            pendingTool = ""
            removePickWindow()
        }
    }

    private var lastTickLogOn: Boolean? = null
    private var lastAddFailAtMs = 0L
    private var lastDeferredAtMs = 0L

    private val tick = object : Runnable {
        override fun run() {
            val saw = Settings.canDrawOverlays(this@GlobalGlassBallService)
            val sw = switchOn()
            val on = sw && saw
            if (on != shown || lastTickLogOn == null) {
                Lg.i(TAG, "tick element_glass=$sw saw=$saw ballVisible=$on")
                lastTickLogOn = on
            }
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
        Lg.i(TAG, "service onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val saw = Settings.canDrawOverlays(this)
        Lg.i(TAG, "onStartCommand saw=$saw")
        if (!saw) {
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
        Lg.i(TAG, "service onDestroy shown=$shown")
        mainHandler.removeCallbacksAndMessages(null)
        hideBall()
        removePickWindow()
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
        if (!switchLogged) {
            switchLogged = true
            Lg.i(TAG, "switch element_glass_enable=${value ?: "unreadable(framework off, treat false)"}")
        }
        return value == true
    }

    // ---------- 窗口 ----------

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun showBall() {
        if (shown) return
        if (!addBallWindow()) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastDeferredAtMs >= 2000L) {
                lastDeferredAtMs = now
                Lg.i(TAG, "ball show deferred, add window failed")
            }
            return
        }
        shown = true
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

    /** 挂球窗；失败不置 shown（下个 tick 重试），失败日志 2s 节流防刷屏。 */
    private fun addBallWindow(): Boolean {
        if (ballView != null) return true
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
        return try {
            wm?.addView(ball, params)
            ballView = ball
            ballParams = params
            true
        } catch (t: Throwable) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastAddFailAtMs >= 2000L) {
                lastAddFailAtMs = now
                Lg.w(TAG, "add ball window failed", t)
            }
            false
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
            Lg.i(TAG, "panel shown")
        } catch (t: Throwable) {
            Lg.w(TAG, "add panel failed", t)
        }
    }

    private fun removePanelWindow() {
        val v = panelScroll ?: return
        try {
            wm?.removeView(v)
        } catch (t: Throwable) {
            Lg.w(TAG, "remove panel failed", t)
        }
        panelScroll = null
        panelParams = null
        statusText = null
        frozenText = null
        outputText = null
        listText = null
        treeText = null
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

        val toolRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        toolRow.addView(Button(this).apply {
            text = "抓取"
            setOnClickListener { armPick() }
        })
        toolRow.addView(Button(this).apply {
            text = "UI树"
            setOnClickListener { sendTool("tree") }
        })
        toolRow.addView(Button(this).apply {
            text = "清单"
            setOnClickListener { sendTool("list") }
        })
        toolRow.addView(Button(this).apply {
            text = "复制"
            setOnClickListener { copyOutput() }
        })
        content.addView(toolRow)

        content.addView(TextView(this).apply {
            text = "抓取输出"
            textSize = 12f
            setTextColor(0xFF80CBC4.toInt())
            setPadding(0, dp(8f), 0, dp(2f))
        })
        outputText = TextView(this).apply {
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(0xFFCCCCCC.toInt())
            text = "（点「抓取」后点屏幕控件）"
        }
        content.addView(outputText)

        content.addView(TextView(this).apply {
            text = "玻璃元素清单"
            textSize = 12f
            setTextColor(0xFF80CBC4.toInt())
            setPadding(0, dp(8f), 0, dp(2f))
        })
        listText = TextView(this).apply {
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(0xFFCCCCCC.toInt())
            text = "（点「清单」读取）"
        }
        content.addView(listText)

        content.addView(TextView(this).apply {
            text = "整页界面结构"
            textSize = 12f
            setTextColor(0xFF80CBC4.toInt())
            setPadding(0, dp(8f), 0, dp(2f))
        })
        treeText = TextView(this).apply {
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(0xFFCCCCCC.toInt())
            text = "（点「UI树」读取）"
        }
        content.addView(treeText)

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

    // ---------- 工具（抓取 / UI 树 / 玻璃清单 / 复制） ----------

    /** 进入待点抓取：收面板、清框层、开全屏点选层，点一击发抓取请求。 */
    private fun armPick() {
        if (pendingTool.isNotEmpty()) {
            Lg.i(TAG, "arm pick skipped, busy tool=$pendingTool")
            statusText?.text = "上一个工具还在跑"
            return
        }
        removePanelWindow()
        removeBoxWindow()
        addPickWindow()
        Lg.i(TAG, "pick armed pkg=$pendingPkg")
    }

    /** 抓取/UI树/清单 三工具统一派发（3s 超时 + 1s 防抖）。 */
    private fun sendTool(tool: String) {
        if (pendingTool.isNotEmpty()) {
            Lg.i(TAG, "tool skipped, busy tool=$pendingTool want=$tool")
            statusText?.text = "上一个工具还在跑"
            return
        }
        val pkg = pendingPkg
        if (pkg.isEmpty()) {
            Lg.i(TAG, "tool skipped, no target scanned want=$tool")
            statusText?.text = "请先扫描一次确定目标应用"
            return
        }
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastToolSentAtMs < 1000L) {
            Lg.i(TAG, "tool skipped, debounce want=$tool")
            statusText?.text = "操作太频繁，稍等"
            return
        }
        lastToolSentAtMs = now
        pendingTool = tool
        mainHandler.removeCallbacks(toolTimeoutRunnable)
        mainHandler.postDelayed(toolTimeoutRunnable, GlobalGlassBall.SCAN_REPLY_TIMEOUT_MS)
        val action = when (tool) {
            "tree" -> ACTION_TOOL_TREE_REQUEST
            "list" -> ACTION_TOOL_LIST_REQUEST
            else -> ACTION_TOOL_CAPTURE_REQUEST
        }
        try {
            val intent = Intent(action).setPackage(pkg).putExtra("pkg", pkg)
            sendBroadcast(intent)
            statusText?.text = when (tool) {
                "tree" -> "读取界面结构…"
                "list" -> "读取玻璃清单…"
                else -> "抓取中…"
            }
            Lg.i(TAG, "tool request tool=$tool pkg=$pkg")
        } catch (t: Throwable) {
            pendingTool = ""
            mainHandler.removeCallbacks(toolTimeoutRunnable)
            Lg.w(TAG, "send tool request failed tool=$tool", t)
            statusText?.text = "发送失败: ${t.javaClass.simpleName}"
        }
    }

    /** 抓取点选层点击回调（rawX/rawY 屏幕坐标 → 目标进程抓取）。 */
    private fun onPickTap(x: Float, y: Float) {
        removePickWindow()
        pendingTool = "capture"
        mainHandler.removeCallbacks(toolTimeoutRunnable)
        mainHandler.postDelayed(toolTimeoutRunnable, GlobalGlassBall.SCAN_REPLY_TIMEOUT_MS)
        val pkg = pendingPkg
        try {
            sendBroadcast(
                Intent(ACTION_TOOL_CAPTURE_REQUEST).setPackage(pkg)
                    .putExtra("pkg", pkg)
                    .putExtra("x", x.toInt())
                    .putExtra("y", y.toInt()),
            )
            statusText?.text = "抓取中…"
            Lg.i(TAG, "tool request tool=capture pkg=$pkg x=${x.toInt()} y=${y.toInt()}")
        } catch (t: Throwable) {
            pendingTool = ""
            mainHandler.removeCallbacks(toolTimeoutRunnable)
            Lg.w(TAG, "send capture failed", t)
            statusText?.text = "发送失败: ${t.javaClass.simpleName}"
        }
    }

    /** 工具结果统一入口（由 onReceive 分发）。 */
    private fun onToolResult(intent: Intent) {
        val tool = pendingTool
        val action = intent.action ?: ""
        val actionTool = when (action) {
            ACTION_TOOL_CAPTURE_RESULT -> "capture"
            ACTION_TOOL_TREE_RESULT -> "tree"
            ACTION_TOOL_LIST_RESULT -> "list"
            else -> ""
        }
        if (tool.isEmpty() || tool != actionTool) {
            Lg.i(TAG, "tool result unmatched tool=$tool action=$action")
            return
        }
        // 匹配通过才拆超时：过期结果不得误杀在途工具的 3s 超时
        mainHandler.removeCallbacks(toolTimeoutRunnable)
        pendingTool = ""
        val ok = intent.getBooleanExtra("ok", false)
        val text = intent.getStringExtra("text") ?: ""
        if (!ok) {
            statusText?.text = "工具失败: $text"
            Lg.i(TAG, "tool failed tool=$tool reason=$text")
            return
        }
        when (tool) {
            "capture" -> {
                if (panelScroll == null) addPanelWindow()
                outputText?.text = text
                if (intent.getBooleanExtra("hasBox", false)) {
                    showBoxWindow()
                    boxLayer?.setOverlayBoxes(
                        listOf(
                            BallOverlayBox(
                                intent.getIntExtra("bx", 0),
                                intent.getIntExtra("by", 0),
                                intent.getIntExtra("bw", 0),
                                intent.getIntExtra("bh", 0),
                            ),
                        ),
                    )
                }
                statusText?.text = "抓取完成"
            }
            "tree" -> {
                if (panelScroll == null) addPanelWindow()
                treeText?.text = text.ifEmpty { "（空）" }
                statusText?.text = "界面结构已刷新"
            }
            "list" -> {
                if (panelScroll == null) addPanelWindow()
                listText?.text = text.ifEmpty { "（本进程无玻璃元素）" }
                statusText?.text = "清单已刷新"
            }
        }
        Lg.i(TAG, "tool result tool=$tool bytes=${text.length}")
    }

    /** 复制抓取输出到剪贴板（模块进程本地）。 */
    private fun copyOutput() {
        val text = outputText?.text?.toString().orEmpty()
        if (text.isEmpty() || text.startsWith("（")) {
            statusText?.text = "没有可复制的输出"
            Lg.i(TAG, "copy skipped, output empty")
            return
        }
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            cm?.setPrimaryClip(android.content.ClipData.newPlainText("lg-debug-output", text))
            statusText?.text = "已复制输出"
            Lg.i(TAG, "output copied bytes=${text.length}")
        } catch (t: Throwable) {
            Lg.w(TAG, "copy failed", t)
            statusText?.text = "复制失败"
        }
    }

    private fun addPickWindow() {
        if (pickLayer != null) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        )
        val layer = BallPickLayer(this).apply { onTap = { x, y -> onPickTap(x, y) } }
        try {
            wm?.addView(layer, params)
            pickLayer = layer
            pickParams = params
            Lg.i(TAG, "pick layer shown")
        } catch (t: Throwable) {
            Lg.w(TAG, "add pick window failed", t)
        }
    }

    private fun removePickWindow() {
        val v = pickLayer ?: return
        try {
            wm?.removeView(v)
        } catch (t: Throwable) {
            Lg.w(TAG, "remove pick failed", t)
        }
        pickLayer = null
        pickParams = null
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
                Lg.i(TAG, "box layer shown items=${items.size}")
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
        } catch (t: Throwable) {
            Lg.w(TAG, "remove box failed", t)
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
                        ACTION_TOOL_CAPTURE_RESULT,
                        ACTION_TOOL_TREE_RESULT,
                        ACTION_TOOL_LIST_RESULT,
                        -> onToolResult(intent)
                    }
                } catch (t: Throwable) {
                    Lg.w(TAG, "onReceive error action=${intent.action}", t)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_SCAN_RESULT)
            addAction(ACTION_FREEZE_RESULT)
            addAction(ACTION_TOOL_CAPTURE_RESULT)
            addAction(ACTION_TOOL_TREE_RESULT)
            addAction(ACTION_TOOL_LIST_RESULT)
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

/** 抓取高亮框（青色，叠加在框层上；换扫描/新抓取时随 setItems 清空）。 */
data class BallOverlayBox(val x: Int, val y: Int, val w: Int, val h: Int)

/** 点选层：全屏透明，收一击返回屏幕坐标后由宿主摘除。 */
private class BallPickLayer(ctx: Context) : View(ctx) {

    var onTap: ((Float, Float) -> Unit)? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_UP) return true
        onTap?.invoke(event.rawX, event.rawY)
        return true
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
    private var overlayBoxes: List<BallOverlayBox> = emptyList()

    fun setItems(items: List<GlobalGlassBall.ScanItem>) {
        this.items = items
        this.overlayBoxes = emptyList()
        invalidate()
    }

    fun setOverlayBoxes(boxes: List<BallOverlayBox>) {
        this.overlayBoxes = boxes
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
        if (overlayBoxes.isNotEmpty()) {
            paint.color = GlobalGlassBall.COLOR_CAPTURE_BOX
            for (b in overlayBoxes) {
                canvas.drawRect(
                    b.x.toFloat(),
                    b.y.toFloat(),
                    (b.x + b.w).toFloat(),
                    (b.y + b.h).toFloat(),
                    paint,
                )
            }
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
