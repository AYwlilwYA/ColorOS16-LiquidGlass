package com.coloros16.liquidglass.debug

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.coloros16.liquidglass.glass.GlassRegistry
import com.coloros16.liquidglass.hook.BlurDrawHook
import com.coloros16.liquidglass.log.Lg
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.math.abs
import kotlin.math.roundToInt

/** 调试悬浮窗快照元素（BlurDrawHook / LauncherHook 的 debugSnapshot 产出；全部为注册/渲染链现成字段的只读拷贝）。 */
internal data class DebugElement(
    val id: Int,
    val region: RectF,
    val cornerRadius: Float,
    val cornerRadiusSource: String,
    val hostViewName: String,
    val sourceTag: String,
    val renderLeg: String?,
    val lastSeenMs: Long,
    val hostViewRef: WeakReference<View>?,
)

/**
 * 调试悬浮球工具（SystemUI / Launcher 进程各自一份实例）：悬浮球 + 面板 + 框体三窗。
 * 球可拖动、点球开合面板；面板含抓取/复制按钮、单槽输出区、玻璃绘制清单、整页 UI 树；
 * 抓取命中后框体窗画双框（青=玻璃实际框，橙=控件实际框）。
 *
 * 硬约束：只用普通 Canvas + Paint，全程不碰 RuntimeShader；
 * 全 try-catch，失败只打日志、绝不影响宿主；开关关 = 每 tick 仅一次 volatile 读。
 */
@SuppressLint("SetTextI18n")
internal object DebugOverlay {

    private const val TAG = "LiquidGlass"

    /** SystemUI 自有权限允许多实例的窗口类型（TYPE_VOLUME_OVERLAY）。 */
    private const val WINDOW_TYPE = 2020

    /** 普通应用进程（Launcher）的悬浮窗类型（TYPE_APPLICATION_OVERLAY，需 SAW 权限）。 */
    private const val WINDOW_TYPE_APP = 2038

    private const val PROC_SYSTEMUI = "systemui"
    private const val PROC_LAUNCHER = "launcher"

    private const val TICK_MS = 66L
    private const val TICK_FAST_MS = 33L
    private const val PICK_TOLERANCE_DIP = 24f
    private const val GLASS_LIST_INTERVAL_MS = 1000L
    private const val STALE_MS = 3000L
    private const val TREE_MAX_DEPTH = 8
    private const val TREE_MAX_NODES = 800
    private const val PARENT_CHAIN_CAP = 12
    private const val TITLE_CAP = 40
    private const val LOG_THROTTLE_MS = 2000L
    private const val BALL_SIZE_DIP = 48f
    private const val DRAG_SLOP_DIP = 8f
    private const val LAUNCHER_BALL_OFFSET_Y_PX = 100f
    private const val PANEL_HEIGHT_RATIO = 0.5f

    private const val COLOR_GLASS_BOX = 0xFF00E5FF.toInt()
    private const val COLOR_CONTROL_BOX = 0xFFFF9100.toInt()
    private const val COLOR_BRIDGE_BOX = 0xFFFF4081.toInt()

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var running = false

    private var contextProvider: (() -> Context?)? = null
    private var snapshotProvider: (() -> List<DebugElement>)? = null
    private var enabledProvider: (() -> Boolean)? = null
    private var processLabel = PROC_SYSTEMUI

    private var windowManager: WindowManager? = null
    private var density = 1f
    private var screenW = 0
    private var screenH = 0

    private var ballView: BallView? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var pickView: PickView? = null
    private var panelScroll: ScrollView? = null
    private var outputText: TextView? = null
    private var glassListText: TextView? = null
    private var treeText: TextView? = null
    private var frameView: FrameView? = null

    private var panelExpanded = false
    private var pendingPick = false
    private var captureResult: CaptureResult? = null
    private var outputContent = ""
    private var ballX = 0f
    private var ballY = 0f
    private var dragBaseX = 0f
    private var dragBaseY = 0f
    private var lastGlassListAt = 0L
    private var lastGlassHash = Int.MIN_VALUE
    private val logThrottleMap = HashMap<String, Long>()

    /** 抓取结果：输出区文本 + 双框（glassBox 青、controlBox 橙）+ 桥区虚线框列表（品红；无桥空表）。 */
    private class CaptureResult(
        val text: String,
        val glassBox: RectF?,
        val controlBox: RectF?,
        val bridgeBoxes: List<RectF> = emptyList(),
    ) {
        fun hasBoxes(): Boolean = glassBox != null || controlBox != null || bridgeBoxes.isNotEmpty()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                tickOnce()
            } catch (t: Throwable) {
                Lg.w(TAG, "debug overlay tick failed", t)
            }
            if (running) {
                val interval = if (pendingPick || panelExpanded) TICK_FAST_MS else TICK_MS
                mainHandler.postDelayed(this, interval)
            }
        }
    }

    /** 幂等启动 tick 循环（install 尾部调用；窗口的挂/摘全部在 tick 内按开关状态收敛）。 */
    fun ensureStarted(
        contextProvider: () -> Context?,
        snapshotProvider: () -> List<DebugElement>,
        enabledProvider: () -> Boolean,
        processLabel: String = PROC_SYSTEMUI,
    ) {
        if (running) return
        this.contextProvider = contextProvider
        this.snapshotProvider = snapshotProvider
        this.enabledProvider = enabledProvider
        this.processLabel = processLabel
        if (processLabel == PROC_LAUNCHER && ballY == 0f) ballY = LAUNCHER_BALL_OFFSET_Y_PX
        running = true
        mainHandler.post(tick)
    }

    /** 摘除全部窗口并停 tick（shutdownForHotReload 也走这里）。 */
    fun detach() {
        running = false
        mainHandler.removeCallbacksAndMessages(null)
        val cleanup = Runnable { teardownAll() }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cleanup.run()
        } else {
            mainHandler.post(cleanup)
        }
    }

    // ------------------------------------------------------------ tick --------------------------------------------------

    private fun tickOnce() {
        val on = enabledProvider?.invoke() == true
        if (!on) {
            teardownAll()
            return
        }
        val ctx = contextProvider?.invoke() ?: return
        val wmgr = resolveWindowManager(ctx) ?: return
        syncWindows(ctx, wmgr)
        if (panelExpanded) refreshGlassListIfDue()
    }

    /** 状态变更后立即跑一轮 tick，让窗口挂摘不等下个周期。 */
    private fun pumpNow() {
        try {
            tickOnce()
        } catch (t: Throwable) {
            Lg.w(TAG, "debug overlay pump failed", t)
        }
    }

    /** 清态并摘全部窗口（开关关 / detach 共用）。 */
    private fun teardownAll() {
        pendingPick = false
        panelExpanded = false
        captureResult = null
        outputContent = ""
        lastGlassHash = Int.MIN_VALUE
        lastGlassListAt = 0
        logThrottleMap.clear()
        removeViewSafe(frameView)
        frameView = null
        removeViewSafe(pickView)
        pickView = null
        removeViewSafe(panelScroll)
        panelScroll = null
        outputText = null
        glassListText = null
        treeText = null
        removeViewSafe(ballView)
        ballView = null
        ballParams = null
    }

    // ------------------------------------------------------------ 窗口管理 --------------------------------------------------

    private fun resolveWindowManager(ctx: Context): WindowManager? {
        return try {
            val w = windowManager
                ?: (ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
                    ?.also { windowManager = it }
            val dm = ctx.resources.displayMetrics
            density = dm.density
            screenW = dm.widthPixels
            screenH = dm.heightPixels
            w
        } catch (t: Throwable) {
            null
        }
    }

    /** 本进程可用的悬浮窗类型（SystemUI=2020，应用进程=2038）。 */
    private fun windowType(): Int =
        if (processLabel == PROC_LAUNCHER) WINDOW_TYPE_APP else WINDOW_TYPE

    private fun baseLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            windowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
    }

    /** 按期望窗口集合重排（z 序=frame < pick < panel < ball；集合变化才动，动即全量按序重挂）。 */
    private fun syncWindows(ctx: Context, wmgr: WindowManager) {
        val wantFrame = captureResult?.hasBoxes() == true
        val wantPick = pendingPick
        val wantPanel = panelExpanded
        val desired = ArrayList<String>(4)
        if (wantFrame) desired.add("frame")
        if (wantPick) desired.add("pick")
        if (wantPanel) desired.add("panel")
        desired.add("ball")
        val current = ArrayList<String>(4)
        if (frameView?.parent != null) current.add("frame")
        if (pickView?.parent != null) current.add("pick")
        if (panelScroll?.parent != null) current.add("panel")
        if (ballView?.parent != null) current.add("ball")
        if (current == desired) return
        removeViewSafe(frameView)
        removeViewSafe(pickView)
        removeViewSafe(panelScroll)
        removeViewSafe(ballView)
        if (wantFrame) attachFrame(ctx, wmgr)
        if (wantPick) attachPick(ctx, wmgr)
        if (wantPanel) attachPanel(ctx, wmgr)
        attachBall(ctx, wmgr)
    }

    private fun attachFrame(ctx: Context, wmgr: WindowManager) {
        try {
            if (frameView == null) frameView = FrameView(ctx)
            val v = frameView ?: return
            v.result = captureResult
            if (v.parent == null) {
                val lp = baseLayoutParams()
                lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                wmgr.addView(v, lp)
            }
        } catch (t: Throwable) {
            logThrottled("attach-frame-fail", "debug overlay attach frame failed ${t.javaClass.simpleName}", warn = true)
            frameView = null
        }
    }

    private fun attachPick(ctx: Context, wmgr: WindowManager) {
        try {
            if (pickView == null) {
                val v = PickView(ctx)
                v.onTouch = { x, y -> handlePickTouch(x, y) }
                pickView = v
            }
            val v = pickView ?: return
            if (v.parent == null) wmgr.addView(v, baseLayoutParams())
        } catch (t: Throwable) {
            Lg.w(TAG, "debug overlay attach pick failed", t)
            pickView = null
            pendingPick = false
            ballView?.pending = false
        }
    }

    private fun attachPanel(ctx: Context, wmgr: WindowManager) {
        try {
            if (panelScroll == null) buildPanel(ctx)
            val v = panelScroll ?: return
            if (v.parent == null) {
                val lp = baseLayoutParams()
                lp.height = (screenH * PANEL_HEIGHT_RATIO).toInt().coerceAtLeast(1)
                lp.gravity = Gravity.BOTTOM
                wmgr.addView(v, lp)
            }
        } catch (t: Throwable) {
            logThrottled("attach-panel-fail", "debug overlay attach panel failed ${t.javaClass.simpleName}", warn = true)
        }
    }

    private fun attachBall(ctx: Context, wmgr: WindowManager) {
        try {
            if (ballView == null) {
                val v = BallView(ctx, if (processLabel == PROC_LAUNCHER) "调L" else "调S")
                v.onTap = { togglePanel() }
                v.onDragStart = {
                    dragBaseX = ballX
                    dragBaseY = ballY
                }
                v.onDragMove = { dx, dy -> moveBall(dragBaseX + dx, dragBaseY + dy) }
                ballView = v
            }
            val v = ballView ?: return
            if (v.parent == null) {
                val size = (BALL_SIZE_DIP * density).roundToInt()
                val lp = ballParams ?: WindowManager.LayoutParams(
                    size,
                    size,
                    windowType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT,
                ).apply { gravity = Gravity.TOP or Gravity.START }
                ballParams = lp
                lp.x = ballX.roundToInt().coerceIn(0, (screenW - size).coerceAtLeast(0))
                lp.y = ballY.roundToInt().coerceIn(0, (screenH - size).coerceAtLeast(0))
                wmgr.addView(v, lp)
            }
        } catch (t: Throwable) {
            logThrottled("attach-ball-fail", "debug overlay attach ball failed ${t.javaClass.simpleName}", warn = true)
            ballView = null
            ballParams = null
        }
    }

    private fun moveBall(x: Float, y: Float) {
        val wmgr = windowManager ?: return
        val v = ballView ?: return
        val lp = ballParams ?: return
        ballX = x.coerceIn(0f, (screenW - lp.width).coerceAtLeast(0).toFloat())
        ballY = y.coerceIn(0f, (screenH - lp.height).coerceAtLeast(0).toFloat())
        lp.x = ballX.roundToInt()
        lp.y = ballY.roundToInt()
        try {
            wmgr.updateViewLayout(v, lp)
        } catch (ignored: Throwable) {
        }
    }

    private fun removeViewSafe(v: View?) {
        val wmgr = windowManager ?: return
        if (v == null || v.parent == null) return
        try {
            wmgr.removeView(v)
        } catch (t: Throwable) {
            try {
                wmgr.removeViewImmediate(v)
            } catch (ignored: Throwable) {
            }
        }
    }

    // ------------------------------------------------------------ 交互 --------------------------------------------------

    private fun togglePanel() {
        panelExpanded = !panelExpanded
        if (panelExpanded) {
            // 点球开面板时退出待点抓取态：面板与待点窗不并存
            pendingPick = false
            ballView?.pending = false
            openPanelExtras()
        }
        pumpNow()
    }

    /** 展开面板时重建 UI 树并立即刷新玻璃清单与输出区。 */
    private fun openPanelExtras() {
        treeText?.text = buildTreeText()
        outputText?.text = outputContent
        lastGlassListAt = 0
        panelScroll?.scrollTo(0, 0)
    }

    private fun onPickButton() {
        panelExpanded = false
        pendingPick = true
        ballView?.pending = true
        logThrottled("pick-arm", "debug overlay: pick armed, tap a control")
        pumpNow()
    }

    private fun onCopyButton() {
        if (pendingPick) {
            pendingPick = false
            ballView?.pending = false
            pumpNow()
        }
        val text = outputContent
        if (text.isEmpty()) {
            logThrottled("copy-empty", "debug overlay: copy skipped, output empty")
            return
        }
        val ctx = contextProvider?.invoke() ?: return
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            cm.setPrimaryClip(ClipData.newPlainText("lg-debug-output", text))
            logThrottled("copy", "debug overlay: output copied to clipboard")
        } catch (t: Throwable) {
            Lg.w(TAG, "debug overlay copy failed", t)
        }
    }

    /** 待点窗口捕获一击：注册表优先，未命中走进程内 View 树兜底。 */
    private fun handlePickTouch(x: Float, y: Float) {
        pendingPick = false
        ballView?.pending = false
        val result = captureAt(x, y)
        captureResult = result
        outputContent = result.text
        outputText?.text = result.text
        frameView?.result = result
        panelExpanded = true
        openPanelExtras()
        pumpNow()
    }

    private fun captureAt(x: Float, y: Float): CaptureResult {
        val snap = snapshotProvider?.invoke() ?: emptyList()
        val tol = PICK_TOLERANCE_DIP * density
        var best: DebugElement? = null
        var bestZ = Int.MIN_VALUE
        var bestDepth = -1
        var bestArea = Float.MAX_VALUE
        var zIndexMap: Map<View, Int>? = null
        for (e in snap) {
            val r = e.region
            if (r.left - tol <= x && x <= r.right + tol && r.top - tol <= y && y <= r.bottom + tol) {
                val host = e.hostViewRef?.get()
                val root = try {
                    host?.rootView
                } catch (t: Throwable) {
                    null
                }
                val z = if (root != null) {
                    (zIndexMap ?: buildWindowZIndexMap().also { zIndexMap = it })[root] ?: -1
                } else {
                    -1
                }
                val depth = if (host != null) viewDepthOf(host) else -1
                val area = r.width() * r.height()
                // 上层优先：窗口 z 序（mViews 索引大=上）> 同窗 View 树深度 > 面积小者
                if (z > bestZ ||
                    (z == bestZ && depth > bestDepth) ||
                    (z == bestZ && depth == bestDepth && area < bestArea)
                ) {
                    bestZ = z
                    bestDepth = depth
                    bestArea = area
                    best = e
                }
            }
        }
        val hit = best
        return if (hit != null) {
            buildGlassResult(hit, via = "registry")
        } else {
            buildFallbackResult(x, y)
        }
    }

    /** 窗口根 → mViews 索引映射（索引大=窗口层高；反射失败返回空表，决胜退化为深度/面积）。 */
    private fun buildWindowZIndexMap(): Map<View, Int> {
        return try {
            val cls = Class.forName("android.view.WindowManagerGlobal")
            val inst = cls.getMethod("getInstance").invoke(null)
            val views = cls.getDeclaredField("mViews").apply { isAccessible = true }
                .get(inst) as? ArrayList<View> ?: return emptyMap()
            val snapshot = ArrayList(views)
            val map = HashMap<View, Int>(snapshot.size)
            for (i in snapshot.indices) {
                val decor = snapshot[i] ?: continue
                map[decor] = i
            }
            map
        } catch (t: Throwable) {
            emptyMap()
        }
    }

    /** View 树深度（窗口根=0；越大越靠上层）。 */
    private fun viewDepthOf(v: View): Int {
        var depth = 0
        var p = v.parent
        while (p is View && depth < 64) {
            depth++
            p = p.parent
        }
        return depth
    }

    private fun buildFallbackResult(x: Float, y: Float): CaptureResult {
        val entries = try {
            enumerateWindows()
        } catch (t: Throwable) {
            return CaptureResult(
                "[进程] $processLabel\n[命中] 未命中任何控件（hitTest 失败：窗口枚举 ${t.javaClass.simpleName}: ${t.message}）",
                null,
                null,
            )
        }
        var bestView: View? = null
        var bestArea = Int.MAX_VALUE
        for (entry in entries) {
            val v = findDeepestAt(entry.decor, x, y) ?: continue
            val area = v.width * v.height
            if (area in 1 until bestArea) {
                bestArea = area
                bestView = v
            }
        }
        val v = bestView ?: return CaptureResult("[进程] $processLabel\n[命中] 未命中任何控件（hitTest 无可见控件命中）", null, null)
        return buildViewResult(v)
    }

    // ------------------------------------------------------------ 输出组装 --------------------------------------------------

    private fun buildGlassResult(e: DebugElement, via: String): CaptureResult {
        val sb = StringBuilder()
        sb.append("[进程] ").append(processLabel).append('\n')
        val v = e.hostViewRef?.get()
        val loc = IntArray(2)
        if (v != null) {
            try {
                v.getLocationOnScreen(loc)
            } catch (ignored: Throwable) {
            }
        }
        // [命中]
        sb.append("[命中] ").append(v?.javaClass?.name ?: e.hostViewName)
        if (v != null) {
            sb.append("  id=").append(idNameOf(v))
            sb.append("  bounds=").append(loc[0]).append(',').append(loc[1])
                .append(' ').append(v.width).append('×').append(v.height)
            sb.append("  attach=").append(v.isAttachedToWindow)
        } else {
            sb.append("（宿主 View 已回收）")
        }
        sb.append("  via=").append(via)
        sb.append('\n')
        // [窗口]
        appendWindowLine(sb, v)
        // [父链]（绘制对象类父链，cap 12）
        val chain = GlassRegistry.classChainOf(e.id)
        sb.append("[父链] ").append(if (chain.isEmpty()) "—" else chain.joinToString(" → ")).append('\n')
        // [背景]
        sb.append("[背景] ").append(v?.background?.javaClass?.name ?: "null").append('\n')
        sb.append("—— 以下仅玻璃元素 ——\n")
        // [玻璃]
        val r = e.region
        sb.append("[玻璃] glassBox=").append(boxStr(r.left.toInt(), r.top.toInt(), r.width().toInt(), r.height().toInt()))
        if (v != null) {
            sb.append(" controlBox=").append(boxStr(loc[0], loc[1], v.width, v.height))
        }
        sb.append('\n')
        // [预设]
        val st = GlassRegistry.statusOf(e.id)
        if (st == null) {
            sb.append("[预设] ").append(GlassRegistry.lookupLineOf(e.id)).append('\n')
        } else {
            sb.append("[预设] card=").append(st.cardClass ?: "null")
                .append(" hit=").append(st.status)
                .append(" presets=").append(st.presetCount)
                .append(" key=").append(st.hitKey ?: "null")
                .append('\n')
        }
        // [链路]
        sb.append("[链路] 挂点=").append(e.sourceTag.ifEmpty { "?" })
            .append(" 渲染腿=").append(e.renderLeg ?: "（尚未渲染）")
            .append(" 圆角覆盖=").append(GlassRegistry.cornerOverrideFor(e.id) ?: "null")
            .append('\n')
        // [黏连]（SystemUI：命中元素相关的桥对；Launcher 无桥数据源）
        val bridgeBoxes = ArrayList<RectF>()
        if (processLabel != PROC_SYSTEMUI) {
            sb.append("[黏连] 本进程无桥数据\n")
        } else {
            val pairs = BlurDrawHook.debugBridgePairs().filter { it.aId == e.id || it.bId == e.id }
            if (pairs.isEmpty()) {
                sb.append("[黏连] 无\n")
            } else {
                for (p in pairs) {
                    val other = if (p.aId == e.id) "#${p.bId} ${p.bName}" else "#${p.aId} ${p.aName}"
                    sb.append("[黏连] ").append(p.kind)
                        .append(" 对方=").append(other)
                        .append(" gap=").append(if (p.gapPx < 0f) "—" else "${p.gapPx.toInt()}px")
                        .append(" mode=").append(p.mode.toInt())
                        .append(" k=").append(p.k)
                        .append(" bridgeBox=").append(boxStr(p.bridgeBox.left.toInt(), p.bridgeBox.top.toInt(), p.bridgeBox.width().toInt(), p.bridgeBox.height().toInt()))
                        .append('\n')
                    bridgeBoxes.add(RectF(p.bridgeBox))
                }
            }
        }
        val controlBox = if (v != null) {
            RectF(
                loc[0].toFloat(),
                loc[1].toFloat(),
                (loc[0] + v.width).toFloat(),
                (loc[1] + v.height).toFloat(),
            )
        } else {
            null
        }
        return CaptureResult(sb.toString(), RectF(r), controlBox, bridgeBoxes)
    }

    private fun buildViewResult(v: View): CaptureResult {
        val loc = IntArray(2)
        try {
            v.getLocationOnScreen(loc)
        } catch (ignored: Throwable) {
        }
        val sb = StringBuilder()
        sb.append("[进程] ").append(processLabel).append('\n')
        // [命中]
        sb.append("[命中] ").append(v.javaClass.name)
        sb.append("  id=").append(idNameOf(v))
        sb.append("  bounds=").append(loc[0]).append(',').append(loc[1])
            .append(' ').append(v.width).append('×').append(v.height)
        sb.append("  attach=").append(v.isAttachedToWindow)
        sb.append("  via=viewtree").append('\n')
        // [窗口]
        appendWindowLine(sb, v)
        // [父链]（View 父链，cap 12）
        sb.append("[父链] ").append(viewChain(v)).append('\n')
        // [背景]
        sb.append("[背景] ").append(v.background?.javaClass?.name ?: "null").append('\n')
        val controlBox = RectF(
            loc[0].toFloat(),
            loc[1].toFloat(),
            (loc[0] + v.width).toFloat(),
            (loc[1] + v.height).toFloat(),
        )
        return CaptureResult(sb.toString(), null, controlBox)
    }

    private fun appendWindowLine(sb: StringBuilder, v: View?) {
        if (v == null) return
        val root = v.rootView ?: return
        sb.append("[窗口] root=").append(root.javaClass.name)
            .append(" type=").append(reflectWindowType(root))
            .append(" title=").append(truncate(reflectWindowTitle(root)?.toString() ?: "—", TITLE_CAP))
            .append('\n')
    }

    private fun viewChain(v: View): String {
        val parts = ArrayList<String>(8)
        var cur: View? = v
        var depth = 0
        while (cur != null && depth < PARENT_CHAIN_CAP) {
            parts.add(cur.javaClass.name.substringAfterLast('.'))
            cur = cur.parent as? View
            depth++
        }
        return parts.joinToString(" → ")
    }

    private fun boxStr(l: Int, t: Int, w: Int, h: Int): String = "$l,$t ${w}×$h"

    private fun truncate(s: String, cap: Int): String = if (s.length <= cap) s else s.substring(0, cap) + "…"

    private fun idNameOf(v: View): String {
        if (v.id == View.NO_ID) return "#-"
        return try {
            "#" + v.resources.getResourceEntryName(v.id)
        } catch (t: Throwable) {
            "#" + Integer.toHexString(v.id)
        }
    }

    // ------------------------------------------------------------ 面板 --------------------------------------------------

    private fun buildPanel(ctx: Context) {
        val pad = (12 * density).toInt()
        val root = LinearLayout(ctx)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(0xDD101418.toInt())
        root.setPadding(pad, pad, pad, pad)

        val buttons = LinearLayout(ctx)
        buttons.orientation = LinearLayout.HORIZONTAL
        buttons.addView(makeButton(ctx, "抓取屏幕控件") { onPickButton() })
        buttons.addView(makeButton(ctx, "复制输出") { onCopyButton() })
        root.addView(buttons)

        val out = makeTextView(ctx, 11f)
        outputText = out
        root.addView(out)

        root.addView(makeSectionLabel(ctx, "当前页面玻璃绘制清单"))
        val list = makeTextView(ctx, 10f)
        glassListText = list
        root.addView(list)

        val treeHeader = LinearLayout(ctx)
        treeHeader.orientation = LinearLayout.HORIZONTAL
        treeHeader.addView(makeSectionLabel(ctx, "整页 UI 树"))
        treeHeader.addView(makeButton(ctx, "刷新树") { treeText?.text = buildTreeText() })
        root.addView(treeHeader)
        val tree = makeTextView(ctx, 10f)
        treeText = tree
        root.addView(tree)

        val scroll = ScrollView(ctx)
        scroll.isFillViewport = true
        val scrollLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        scroll.addView(root, scrollLp)
        panelScroll = scroll
    }

    private fun makeButton(ctx: Context, label: String, onClick: () -> Unit): TextView {
        return TextView(ctx).apply {
            text = label
            setTextColor(0xFF69F0AE.toInt())
            textSize = 13f
            val p = (10 * density).toInt()
            setPadding(p, p / 2, p, p / 2)
            val a = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            val bg = a.getResourceId(0, 0)
            a.recycle()
            if (bg != 0) setBackgroundResource(bg)
            setOnClickListener { onClick() }
        }
    }

    private fun makeSectionLabel(ctx: Context, label: String): TextView {
        return TextView(ctx).apply {
            text = label
            setTextColor(0xFFB0BEC5.toInt())
            textSize = 12f
            val p = (6 * density).toInt()
            setPadding(0, p, 0, p / 2)
        }
    }

    private fun makeTextView(ctx: Context, sizeSp: Float): TextView {
        return TextView(ctx).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(0xFFE0E0E0.toInt())
            textSize = sizeSp
            val p = (4 * density).toInt()
            setPadding(0, p, 0, p)
        }
    }

    /** 玻璃绘制清单（1Hz 节流 + 内容哈希脏检查；超 3s 未见条目标 stale）。 */
    private fun refreshGlassListIfDue() {
        val now = SystemClock.uptimeMillis()
        if (now - lastGlassListAt < GLASS_LIST_INTERVAL_MS) return
        lastGlassListAt = now
        val snap = snapshotProvider?.invoke() ?: emptyList()
        val h = contentHash(snap)
        if (h == lastGlassHash) return
        lastGlassHash = h
        glassListText?.text = assembleGlassList(snap)
    }

    private fun assembleGlassList(snap: List<DebugElement>): String {
        if (snap.isEmpty()) return "当前页面无玻璃绘制"
        val now = SystemClock.uptimeMillis()
        val sb = StringBuilder()
        for (e in snap) {
            val cls = GlassRegistry.classOf(e.id) ?: e.hostViewName
            val st = GlassRegistry.statusOf(e.id)
            sb.append(cls.substringAfterLast('.')).append(' ')
                .append(e.region.left.toInt()).append(',').append(e.region.top.toInt()).append(' ')
                .append(e.region.width().toInt()).append('×').append(e.region.height().toInt()).append(' ')
                .append('[').append(e.sourceTag.ifEmpty { "?" }).append("] ")
                .append("card=").append(st?.cardClass?.substringAfterLast('.') ?: "null").append(' ')
                .append("hit=").append(st?.hitKey?.substringAfterLast('.') ?: "null")
            if (now - e.lastSeenMs > STALE_MS) sb.append(" stale")
            sb.append('\n')
        }
        return sb.toString()
    }

    /** 内容脏检查哈希：id/区域/身份/渲染腿（不含 lastSeenMs —— 它每帧都变，纳入会恒脏）。 */
    private fun contentHash(list: List<DebugElement>): Int {
        var h = list.size
        for (e in list) {
            h = h * 31 + e.id
            h = h * 31 + e.region.left.toInt()
            h = h * 31 + e.region.top.toInt()
            h = h * 31 + e.region.right.toInt()
            h = h * 31 + e.region.bottom.toInt()
            h = h * 31 + e.sourceTag.hashCode()
            h = h * 31 + (e.renderLeg?.hashCode() ?: 0)
        }
        return h
    }

    // ------------------------------------------------------------ UI 树 --------------------------------------------------

    /** 整页 UI 树文本（WindowManagerGlobal 反枚举；深度 cap 8、节点 cap 800；失败=一行原因）。 */
    private fun buildTreeText(): String {
        return try {
            val entries = enumerateWindows()
            if (entries.isEmpty()) return "（未枚举到窗口）"
            val sb = StringBuilder()
            val counter = IntArray(1)
            for ((i, entry) in entries.withIndex()) {
                sb.append("[窗口").append(i + 1).append("] type=").append(entry.type)
                    .append(" title=").append(truncate(entry.title ?: "—", TITLE_CAP))
                    .append(" root=").append(entry.decor.javaClass.name.substringAfterLast('.'))
                    .append('\n')
                if (counter[0] >= TREE_MAX_NODES) {
                    sb.append("…(truncated)\n")
                    break
                }
                if (entry.decor is ViewGroup) {
                    val decor = entry.decor as ViewGroup
                    for (ci in 0 until decor.childCount) {
                        if (counter[0] >= TREE_MAX_NODES) {
                            sb.append("…(truncated)\n")
                            break
                        }
                        appendTreeNode(sb, decor.getChildAt(ci), 1, counter)
                    }
                }
            }
            sb.toString()
        } catch (t: Throwable) {
            "UI 树读取失败：${t.javaClass.simpleName}: ${t.message}"
        }
    }

    private fun appendTreeNode(sb: StringBuilder, v: View, depth: Int, counter: IntArray) {
        if (counter[0] >= TREE_MAX_NODES || depth > TREE_MAX_DEPTH) return
        counter[0]++
        val indent = "  ".repeat(depth)
        sb.append(indent).append(shortName(v)).append(' ').append(treeIdLabel(v)).append(' ')
        val loc = IntArray(2)
        try {
            v.getLocationOnScreen(loc)
        } catch (ignored: Throwable) {
        }
        sb.append(loc[0]).append(',').append(loc[1]).append(' ')
            .append(v.width).append('×').append(v.height)
        if (v.visibility != View.VISIBLE) sb.append(" [不可见]")
        sb.append('\n')
        if (v is ViewGroup && v.childCount > 0 && v.visibility != View.GONE) {
            for (i in 0 until v.childCount) {
                if (counter[0] >= TREE_MAX_NODES || depth + 1 > TREE_MAX_DEPTH) {
                    sb.append(indent).append("  …(truncated)\n")
                    return
                }
                appendTreeNode(sb, v.getChildAt(i), depth + 1, counter)
            }
        }
    }

    private fun shortName(v: View): String {
        val simple = v.javaClass.simpleName
        return if (simple.isEmpty()) v.javaClass.name.substringAfterLast('.') else simple
    }

    private fun treeIdLabel(v: View): String {
        val id = if (v.id == View.NO_ID) {
            "-"
        } else {
            try {
                v.resources.getResourceEntryName(v.id)
            } catch (t: Throwable) {
                Integer.toHexString(v.id)
            }
        }
        return "#$id@${Integer.toHexString(System.identityHashCode(v))}"
    }

    // ------------------------------------------------------------ 窗口枚举 / hitTest --------------------------------------------------

    private class WindowEntry(val decor: View, val type: Int, val title: String?)

    /** 反射 WindowManagerGlobal 枚举本进程全部窗口根（debug-only；调用方 try-catch 兜底）。 */
    @Suppress("UNCHECKED_CAST")
    private fun enumerateWindows(): List<WindowEntry> {
        val cls = Class.forName("android.view.WindowManagerGlobal")
        val inst = cls.getMethod("getInstance").invoke(null)
        val views = cls.getDeclaredField("mViews").apply { isAccessible = true }
            .get(inst) as? ArrayList<View> ?: return emptyList()
        val params = try {
            cls.getDeclaredField("mParams").apply { isAccessible = true }
                .get(inst) as? ArrayList<WindowManager.LayoutParams>
        } catch (t: Throwable) {
            null
        }
        val snapshot = ArrayList(views)
        val out = ArrayList<WindowEntry>(snapshot.size)
        for (i in snapshot.indices) {
            val decor = snapshot[i] ?: continue
            if (isOwnWindow(decor)) continue
            val lp = params?.getOrNull(i)
            val type = lp?.type ?: reflectWindowType(decor)
            out.add(WindowEntry(decor, type, lp?.title?.toString()))
        }
        return out
    }

    private fun isOwnWindow(decor: View): Boolean {
        return decor === ballView || decor === pickView || decor === panelScroll || decor === frameView
    }

    /** 屏幕坐标点在 decor 子树里找最深可见命中 View（无命中 null）。 */
    private fun findDeepestAt(v: View, sx: Float, sy: Float): View? {
        if (v.visibility != View.VISIBLE) return null
        val loc = IntArray(2)
        try {
            v.getLocationOnScreen(loc)
        } catch (ignored: Throwable) {
            return null
        }
        if (sx < loc[0] || sy < loc[1] || sx >= loc[0] + v.width || sy >= loc[1] + v.height) return null
        if (v is ViewGroup) {
            for (i in v.childCount - 1 downTo 0) {
                findDeepestAt(v.getChildAt(i), sx, sy)?.let { return it }
            }
        }
        return v
    }

    // ------------------------------------------------------------ 窗口反射 --------------------------------------------------

    private var mGetViewRootImpl: Method? = null
    private var mGetWindowAttributes: Method? = null
    private var mWindowGetTitleMethod: Method? = null
    private var mWindowAttrsTypeField: Field? = null

    /** 反射读窗口根 View 的窗口类型（ViewRootImpl.getWindowAttributes().type）。失败 -1。 */
    private fun reflectWindowType(rootView: View): Int {
        return try {
            var m1 = mGetViewRootImpl
            if (m1 == null) {
                m1 = View::class.java.getMethod("getViewRootImpl")
                mGetViewRootImpl = m1
            }
            val vri = m1.invoke(rootView) ?: return -1
            var m2 = mGetWindowAttributes
            if (m2 == null) {
                m2 = vri.javaClass.getMethod("getWindowAttributes")
                mGetWindowAttributes = m2
            }
            val lp = m2.invoke(vri) ?: return -1
            var f = mWindowAttrsTypeField
            if (f == null) {
                f = lp.javaClass.getField("type")
                mWindowAttrsTypeField = f
            }
            (f.get(lp) as? Int) ?: -1
        } catch (t: Throwable) {
            -1
        }
    }

    /** 反射读窗口根 View 的窗口标题（ViewRootImpl.getWindowAttributes().getTitle()）。失败 null。 */
    private fun reflectWindowTitle(rootView: View): CharSequence? {
        return try {
            var m1 = mGetViewRootImpl
            if (m1 == null) {
                m1 = View::class.java.getMethod("getViewRootImpl")
                mGetViewRootImpl = m1
            }
            val vri = m1.invoke(rootView) ?: return null
            var m2 = mGetWindowAttributes
            if (m2 == null) {
                m2 = vri.javaClass.getMethod("getWindowAttributes")
                mGetWindowAttributes = m2
            }
            val lp = m2.invoke(vri) ?: return null
            var m3 = mWindowGetTitleMethod
            if (m3 == null) {
                m3 = lp.javaClass.getMethod("getTitle")
                mWindowGetTitleMethod = m3
            }
            m3.invoke(lp) as? CharSequence
        } catch (t: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------ 日志 --------------------------------------------------

    /** 按 key 节流的 i 级日志（同 key 默认 2s 一条）；warn=true 走 w 级。 */
    private fun logThrottled(key: String, msg: String, warn: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        val last = logThrottleMap[key]
        if (last != null && now - last < LOG_THROTTLE_MS) return
        logThrottleMap[key] = now
        if (logThrottleMap.size > 64) logThrottleMap.clear()
        if (warn) Lg.w(TAG, msg) else Lg.i(TAG, msg)
    }

    // ------------------------------------------------------------ 视图 --------------------------------------------------

    /** 框体窗内容：青=玻璃实际框、橙=控件实际框；无文字。 */
    private class FrameView(ctx: Context) : View(ctx) {

        var result: CaptureResult? = null
            set(value) {
                if (field !== value) {
                    field = value
                    invalidate()
                }
            }

        private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * resources.displayMetrics.density
            color = COLOR_GLASS_BOX
        }
        private val controlPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * resources.displayMetrics.density
            color = COLOR_CONTROL_BOX
        }
        private val bridgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * resources.displayMetrics.density
            color = COLOR_BRIDGE_BOX
            pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
        }

        override fun onDraw(canvas: Canvas) {
            val r = result ?: return
            r.controlBox?.let { canvas.drawRect(it, controlPaint) }
            r.glassBox?.let { canvas.drawRect(it, glassPaint) }
            for (b in r.bridgeBoxes) canvas.drawRect(b, bridgePaint)
        }
    }

    /** 待点窗口：全屏透明、只捕获落点坐标（无遮罩无文字）。 */
    private class PickView(ctx: Context) : View(ctx) {

        var onTouch: ((Float, Float) -> Unit)? = null

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                onTouch?.invoke(event.rawX, event.rawY)
            }
            return true
        }
    }

    /** 悬浮球：可拖动、点击开合面板；待点状态变色。 */
    private class BallView(ctx: Context, private val label: String) : View(ctx) {

        var pending = false
            set(value) {
                if (field != value) {
                    field = value
                    invalidate()
                }
            }

        var onTap: (() -> Unit)? = null
        var onDragStart: (() -> Unit)? = null
        var onDragMove: ((Float, Float) -> Unit)? = null

        private val slopPx = DRAG_SLOP_DIP * resources.displayMetrics.density
        private var downRawX = 0f
        private var downRawY = 0f
        private var dragging = false

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * resources.displayMetrics.density
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = 13f * resources.displayMetrics.density
            textAlign = Paint.Align.CENTER
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val rad = minOf(width, height) / 2f - strokePaint.strokeWidth
            fillPaint.color = if (pending) 0xCCFF6D00.toInt() else 0x99000000.toInt()
            strokePaint.color = if (pending) 0xFFFFD54F.toInt() else 0xFF69F0AE.toInt()
            canvas.drawCircle(cx, cy, rad, fillPaint)
            canvas.drawCircle(cx, cy, rad, strokePaint)
            val baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(label, cx, baseline, textPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    dragging = false
                    onDragStart?.invoke()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (dragging || abs(dx) > slopPx || abs(dy) > slopPx) {
                        dragging = true
                        onDragMove?.invoke(dx, dy)
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) onTap?.invoke()
                    return true
                }
            }
            return true
        }
    }
}
