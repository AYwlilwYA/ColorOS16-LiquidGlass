package com.coloros16.liquidglass.debug

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextPaint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.coloros16.liquidglass.glass.GlassRegistry
import com.coloros16.liquidglass.hook.BlurDrawHook
import com.coloros16.liquidglass.log.Lg

/** 调试悬浮窗快照元素（BlurDrawHook.debugSnapshot 产出；全部为注册/渲染链现成字段的只读拷贝）。 */
internal data class DebugElement(
    val id: Int,
    val region: RectF,
    val cornerRadius: Float,
    val cornerRadiusSource: String,
    val hostViewName: String,
    val sourceTag: String,
    val renderLeg: String?,
    val lastSeenMs: Long,
    val hostViewRef: java.lang.ref.WeakReference<View>?,
)

/**
 * [doc/spec/142] 悬浮调试窗（SystemUI 进程内）：框出每个玻璃元素 + 名称标签；
 * 「点选元素查看」开启后进入一次拾取，命中元素弹完整详情面板（绘制链 / 视图 / 类名）。
 *
 * 硬约束：只用普通 Canvas.drawRect/drawText + Paint，全程不碰 RuntimeShader；
 * 全 try-catch，失败只打日志、绝不影响宿主；拾取窗单次生效 + 超时自动退出。
 */
@SuppressLint("SetTextI18n")
internal object DebugOverlay {

    private const val TAG = "LiquidGlass"

    /** SystemUI 自有权限允许多实例的窗口类型（TYPE_VOLUME_OVERLAY）。 */
    private const val WINDOW_TYPE = 2020

    private const val TICK_MS = 66L
    private const val TICK_FAST_MS = 33L
    private const val PICK_TIMEOUT_MS = 10_000L
    private const val PICK_TOLERANCE_DIP = 24f

    /** sourceTag → 静态链路说明（挂点→注册→渲染；照 spec §5.2 链路表）。 */
    private val CHAIN_NOTES = mapOf(
        "map" to "磁贴/卡片背景 hook → recordScreenRegion → core → drawBlurShader 主腿",
        "swipe" to "侧滑菜单 → recordSwipeMenuRegion → core（maskRect 专用）→ 主腿",
        "capsule" to "锁屏胶囊挂点 → registerPlatformBlurRegion → 主腿",
        "capsule-shard" to "锁屏胶囊碎片挂点 → registerPlatformBlurRegion → 主腿",
        "capsuleEar" to "锁屏胶囊耳挂点 → registerPlatformBlurRegion → 主腿",
        "mediaCarousel" to "媒体横幅挂点 → registerPlatformBlurRegion → 主腿",
        "notifCard" to "通知卡 hook → recordNotificationCardRegion 直构 → 主腿",
        "seedling" to "流体云/菜单卡挂点 → registerSeedlingCardRegion → seedling canvas 腿",
    )

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var running = false

    private var contextProvider: (() -> Context?)? = null
    private var snapshotProvider: (() -> List<DebugElement>)? = null
    private var overlayEnabledProvider: (() -> Boolean)? = null
    private var pickEnabledProvider: (() -> Boolean)? = null

    private var windowManager: android.view.WindowManager? = null
    private var density = 1f

    private var frameView: FrameView? = null
    private var pickView: PickView? = null
    private var panelRoot: LinearLayout? = null
    private var panelText: TextView? = null
    private var panelShown = false
    private var panelTarget: DebugElement? = null

    private var picking = false
    private var pickDeadlineMs = 0L
    private var lastPickPref = false

    private var cached: List<DebugElement> = emptyList()
    private var lastHash = 0
    private val labelCache = HashMap<Int, String>()

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                tickOnce()
            } catch (t: Throwable) {
                Lg.w(TAG, "debug overlay tick failed", t)
            }
            if (running) {
                val interval = if (picking || panelShown) TICK_FAST_MS else TICK_MS
                mainHandler.postDelayed(this, interval)
            }
        }
    }

    /** 幂等启动 tick 循环（install 尾部调用；窗口的挂/摘全部在 tick 内按开关状态收敛）。 */
    fun ensureStarted(
        contextProvider: () -> Context?,
        snapshotProvider: () -> List<DebugElement>,
        overlayEnabledProvider: () -> Boolean,
        pickEnabledProvider: () -> Boolean,
    ) {
        if (running) return
        this.contextProvider = contextProvider
        this.snapshotProvider = snapshotProvider
        this.overlayEnabledProvider = overlayEnabledProvider
        this.pickEnabledProvider = pickEnabledProvider
        running = true
        mainHandler.post(tick)
    }

    /** 摘除全部窗口并停 tick（开关关闭 tick 内自收敛；shutdownForHotReload 也走这里）。 */
    fun detach() {
        running = false
        mainHandler.removeCallbacksAndMessages(null)
        val cleanup = Runnable {
            picking = false
            panelTarget = null
            removeViewSafe(frameView)
            frameView = null
            removeViewSafe(pickView)
            pickView = null
            removeViewSafe(panelRoot)
            panelRoot = null
            panelText = null
            panelShown = false
            cached = emptyList()
            lastHash = 0
            labelCache.clear()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cleanup.run()
        } else {
            mainHandler.post(cleanup)
        }
    }

    // ------------------------------------------------------------ tick --------------------------------------------------

    private fun tickOnce() {
        val on = overlayEnabledProvider?.invoke() == true
        if (!on) {
            if (frameView != null) removeFrame()
            if (picking) endPick()
            if (panelShown) dismissPanel()
            cached = emptyList()
            lastHash = 0
            labelCache.clear()
            lastPickPref = pickEnabledProvider?.invoke() == true
            return
        }
        attachFrameIfNeeded()
        val pickOn = pickEnabledProvider?.invoke() == true
        val edge = pickOn && !lastPickPref
        lastPickPref = pickOn
        if (edge && !picking) beginPick()
        if (picking && SystemClock.uptimeMillis() > pickDeadlineMs) endPick()
        val snap = snapshotProvider?.invoke() ?: emptyList()
        val h = contentHash(snap)
        if (h != lastHash) {
            lastHash = h
            cached = snap
            labelCache.clear()
            frameView?.elements = snap
            frameView?.invalidate()
        }
        val target = panelTarget
        if (panelShown && target != null) {
            panelText?.text = assemblePanel(target)
        }
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

    // ------------------------------------------------------------ 窗口三件 --------------------------------------------------

    private fun resolveWindowManager(ctx: Context): android.view.WindowManager? {
        return try {
            val w = windowManager
                ?: (ctx.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager)
                    ?.also { windowManager = it }
            density = ctx.resources.displayMetrics.density
            w
        } catch (t: Throwable) {
            null
        }
    }

    private fun baseLayoutParams(): android.view.WindowManager.LayoutParams {
        return android.view.WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            WINDOW_TYPE,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
    }

    private fun attachFrameIfNeeded() {
        if (frameView != null && frameView?.parent != null) return
        val ctx = contextProvider?.invoke() ?: return
        val wmgr = resolveWindowManager(ctx) ?: return
        try {
            if (frameView == null) frameView = FrameView(ctx)
            val lp = baseLayoutParams()
            lp.flags = lp.flags or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            wmgr.addView(frameView, lp)
        } catch (t: Throwable) {
            Lg.w(TAG, "debug overlay attach frame failed", t)
            frameView = null
        }
    }

    private fun removeFrame() {
        removeViewSafe(frameView)
        frameView = null
    }

    private fun beginPick() {
        val ctx = contextProvider?.invoke() ?: return
        val wmgr = resolveWindowManager(ctx) ?: return
        try {
            if (pickView == null) {
                val v = PickView(ctx)
                v.onPick = { x, y -> handlePick(x, y) }
                pickView = v
            }
            val v = pickView ?: return
            if (v.parent == null) {
                val lp = baseLayoutParams()
                wmgr.addView(v, lp)
            }
            picking = true
            pickDeadlineMs = SystemClock.uptimeMillis() + PICK_TIMEOUT_MS
        } catch (t: Throwable) {
            Lg.w(TAG, "debug overlay begin pick failed", t)
            endPick()
        }
    }

    private fun endPick() {
        picking = false
        removeViewSafe(pickView)
        pickView = null
    }

    private fun handlePick(x: Float, y: Float) {
        val tol = PICK_TOLERANCE_DIP * density
        var best: DebugElement? = null
        var bestArea = Float.MAX_VALUE
        for (e in cached) {
            val r = e.region
            if (r.left - tol <= x && x <= r.right + tol && r.top - tol <= y && y <= r.bottom + tol) {
                val area = r.width() * r.height()
                if (area < bestArea) {
                    bestArea = area
                    best = e
                }
            }
        }
        endPick()
        val hit = best
        if (hit != null) {
            showPanel(hit)
        } else {
            dismissPanel()
        }
    }

    private fun showPanel(e: DebugElement) {
        val ctx = contextProvider?.invoke() ?: return
        val wmgr = resolveWindowManager(ctx) ?: return
        try {
            if (panelRoot == null) buildPanelRoot(ctx)
            val root = panelRoot ?: return
            panelText?.text = assemblePanel(e)
            panelTarget = e
            if (!panelShown) {
                val lp = android.view.WindowManager.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    WINDOW_TYPE,
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT,
                )
                lp.gravity = Gravity.BOTTOM
                wmgr.addView(root, lp)
                panelShown = true
            }
        } catch (t: Throwable) {
            Lg.w(TAG, "debug overlay show panel failed", t)
        }
    }

    private fun dismissPanel() {
        panelTarget = null
        removeViewSafe(panelRoot)
        panelRoot = null
        panelText = null
        panelShown = false
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

    // ------------------------------------------------------------ 面板 --------------------------------------------------

    private fun buildPanelRoot(ctx: Context) {
        val pad = (12 * density).toInt()
        val root = LinearLayout(ctx)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(0xDD101418.toInt())
        root.setPadding(pad, pad, pad, pad)

        val title = TextView(ctx)
        title.text = "元素详情"
        title.setTextColor(Color.WHITE)
        title.textSize = 14f
        root.addView(title)

        val buttons = LinearLayout(ctx)
        buttons.orientation = LinearLayout.HORIZONTAL
        val pickAgain = TextView(ctx)
        pickAgain.text = "再选一个"
        pickAgain.setTextColor(0xFF69F0AE.toInt())
        pickAgain.textSize = 13f
        pickAgain.setPadding(0, pad / 2, pad * 2, pad / 2)
        pickAgain.setOnClickListener {
            dismissPanel()
            beginPick()
        }
        buttons.addView(pickAgain)
        val close = TextView(ctx)
        close.text = "关闭"
        close.setTextColor(Color.LTGRAY)
        close.textSize = 13f
        close.setPadding(0, pad / 2, 0, pad / 2)
        close.setOnClickListener { dismissPanel() }
        buttons.addView(close)
        root.addView(buttons)

        val scroll = ScrollView(ctx)
        val text = TextView(ctx)
        text.typeface = Typeface.MONOSPACE
        text.setTextColor(0xFFE0E0E0.toInt())
        text.textSize = 11f
        text.setPadding(0, pad / 2, 0, 0)
        scroll.addView(text)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (280 * density).toInt(),
            ),
        )
        panelRoot = root
        panelText = text
    }

    /** 详情面板文本（一期行集：身份 + 绘制链 + 视图 + 类名；档位链/每帧分支/注册时间属二期不显示）。 */
    private fun assemblePanel(e: DebugElement): String {
        val sb = StringBuilder()
        sb.append("[身份] id=0x").append(Integer.toHexString(e.id))
        sb.append(" 区域=").append(e.region.left.toInt()).append(',').append(e.region.top.toInt())
        sb.append(' ').append(e.region.width().toInt()).append('×').append(e.region.height().toInt())
        sb.append(" r=").append(e.cornerRadius.toInt()).append('(').append(e.cornerRadiusSource).append(')')
        sb.append("\n\n[绘制链]\n")
        sb.append("挂点: ").append(e.sourceTag.ifEmpty { "?" }).append('\n')
        sb.append("链路: ").append(CHAIN_NOTES[e.sourceTag] ?: "未收录").append('\n')
        sb.append("渲染腿: ").append(e.renderLeg ?: "（尚未渲染）").append('\n')
        sb.append("预设: ").append(GlassRegistry.lookupLineOf(e.id)).append('\n')
        sb.append("\n[视图]\n")
        val v = e.hostViewRef?.get()
        if (v == null) {
            sb.append("宿主 View 已回收\n")
        } else {
            sb.append("类: ").append(v.javaClass.name).append('\n')
            sb.append("id: ").append(idNameOf(v)).append('\n')
            sb.append("hostViewName: ").append(e.hostViewName).append('\n')
            sb.append("bounds: ").append(v.left).append(',').append(v.top).append(' ')
                .append(v.width).append('×').append(v.height).append('\n')
            sb.append("attach: ").append(v.isAttachedToWindow).append('\n')
            sb.append("背景: ").append(v.background?.javaClass?.name ?: "null").append('\n')
            val root = v.rootView
            sb.append("窗口根: ").append(root?.javaClass?.name ?: "?").append('\n')
            if (root != null) {
                sb.append("窗口type: ").append(BlurDrawHook.reflectWindowType(root)).append('\n')
                sb.append("窗口title: ").append(BlurDrawHook.reflectWindowTitle(root) ?: "—").append('\n')
            }
            sb.append("View父链:\n")
            for (c in classChainOf(v.javaClass)) sb.append("  ").append(c).append('\n')
        }
        sb.append("\n[类名]\n")
        val cls = GlassRegistry.classOf(e.id)
        sb.append("绘制对象: ").append(cls ?: "（未登记）").append('\n')
        val chain = GlassRegistry.classChainOf(e.id)
        if (chain.isEmpty()) {
            sb.append("父链: —\n")
        } else {
            sb.append("父链:\n")
            for (c in chain) sb.append("  ").append(c).append('\n')
        }
        val st = GlassRegistry.statusOf(e.id)
        sb.append("状态: ").append(st?.status ?: "UNBOUND").append('\n')
        if (st != null) {
            sb.append("卡片类: ").append(st.cardClass ?: "—").append('\n')
            sb.append("命中键: ").append(st.hitKey ?: "—")
                .append("（预设数 ").append(st.presetCount).append("）\n")
        }
        sb.append("圆角覆盖: ").append(GlassRegistry.cornerOverrideFor(e.id) ?: "—")
        return sb.toString()
    }

    /** View 类的父链（cap 12 层；与绘制对象父链并列显示）。 */
    private fun classChainOf(clazz: Class<*>): List<String> {
        val out = ArrayList<String>(8)
        var c: Class<*>? = clazz
        var depth = 0
        while (c != null && depth < 12) {
            out.add(c.name)
            c = c.superclass
            depth++
        }
        return out
    }

    private fun idNameOf(v: View): String {
        if (v.id == View.NO_ID) return "—"
        return try {
            v.resources.getResourceEntryName(v.id)
        } catch (t: Throwable) {
            "#${Integer.toHexString(v.id)}"
        }
    }

    // ------------------------------------------------------------ 视图 --------------------------------------------------

    /** 框体窗内容：元素矩形（圆角贴合）+ 名称标签（labelCache 按 id 缓存，热路径零字符串分配）。 */
    private class FrameView(ctx: Context) : View(ctx) {

        var elements: List<DebugElement> = emptyList()

        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * resources.displayMetrics.density
            color = 0xFF69F0AE.toInt()
        }
        private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF69F0AE.toInt()
            textSize = 10f * resources.displayMetrics.density
            setShadowLayer(2f, 0f, 0f, 0xCC000000.toInt())
        }

        override fun onDraw(canvas: Canvas) {
            for (e in elements) {
                val r = e.region
                val rad = if (e.cornerRadius > 0f) {
                    e.cornerRadius
                } else {
                    minOf(r.width(), r.height()) / 2f
                }
                canvas.drawRoundRect(r, rad, rad, strokePaint)
                val label = labelFor(e)
                val y = (r.top - 4f * resources.displayMetrics.density)
                    .coerceAtLeast(textPaint.textSize)
                canvas.drawText(label, r.left, y, textPaint)
            }
        }

        private fun labelFor(e: DebugElement): String {
            val cached = labelCache[e.id]
            val cls = GlassRegistry.classOf(e.id)
            val label = if (cls != null) GlassRegistry.classLabel(cls) else e.hostViewName
            val cur = "$label·${e.hostViewName}"
            if (cached != cur) labelCache[e.id] = cur
            return cur
        }
    }

    /** 拾取窗：全屏可点、半透明提示层；命中测试在 handlePick。 */
    private class PickView(ctx: Context) : View(ctx) {

        var onPick: ((Float, Float) -> Unit)? = null

        private val tintPaint = Paint().apply { color = 0x22FF5252 }
        private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 13f * resources.displayMetrics.density
            setShadowLayer(3f, 0f, 0f, 0xDD000000.toInt())
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), tintPaint)
            canvas.drawText(
                "点选一个元素查看详情（10 秒无操作自动退出）",
                16f * resources.displayMetrics.density,
                120f * resources.displayMetrics.density,
                textPaint,
            )
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> return true
                MotionEvent.ACTION_UP -> {
                    onPick?.invoke(event.x, event.y)
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
}
