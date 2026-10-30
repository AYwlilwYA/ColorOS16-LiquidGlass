package com.coloros16.liquidglass.debug

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.coloros16.liquidglass.config.GlassElementEntry
import com.coloros16.liquidglass.config.GlassElementRegistry
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.log.Lg
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.ref.WeakReference
import java.util.ArrayList
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 应用级点选固化工作台（spec/148 §二.2）：宿主 decorView 内覆盖层，
 * 扫描标框 → 点选信息 → 采锚固化写 GlassElementRegistry → 触发引擎 rescan。
 * 只操作本进程 View 树，不申请系统悬浮窗权限。
 */
@SuppressLint("SetTextI18n", "UseSwitchCompatOrMaterialCode")
object AppGlassWorkbench {

    private const val TAG = "AppGlassWorkbench"

    private const val SWITCH_TTL_MS = 500L

    private const val MAX_SCAN_DEPTH = 8
    private const val MAX_SCAN_NODES = 2000
    private const val PARENT_CHAIN_LEVELS = 3
    private const val SIZE_TOLERANCE = 0.3f

    private const val MOUNT_BACKGROUND = "BACKGROUND"
    private const val MOUNT_DRAW_HOOK = "DRAW_HOOK"
    private const val MOUNT_THROAT = "THROAT"

    private const val SUPPRESS_NONE = "NONE"
    private const val SUPPRESS_THROAT = "THROAT"
    private const val SUPPRESS_FULL = "FULL"

    private const val DEFAULT_PRESET_KEY = "default"

    private const val THROAT_CLASS_DEFAULT = "tc.u"
    private const val THROAT_METHOD_DEFAULT = "c"
    private const val THROAT_SIGNATURE_DEFAULT = "c(Canvas,Path,float)"

    private const val COLOR_BOX_FIXED = 0xFF00E676.toInt()
    private const val COLOR_BOX_GRAY = 0x4DFFFFFF
    private const val COLOR_BOX_SELECTED = 0xFF00E5FF.toInt()

    private const val PAGE_ENGINE_CLASS = "com.coloros16.liquidglass.hook.PageGlassEngine"

    private val mainHandler = Handler(Looper.getMainLooper())

    private var api: XposedInterface? = null
    private var packageName = ""
    private var started = false
    private var hooksMounted = false

    private var currentActivity = WeakReference<Activity>(null)

    private var rootLayer: FrameLayout? = null
    private var boxLayer: BoxLayer? = null
    private var handleView: TextView? = null
    private var panelScroll: ScrollView? = null
    private var panelContent: LinearLayout? = null

    private var infoText: TextView? = null
    private var labelInput: EditText? = null
    private var mountGroup: RadioGroup? = null
    private var suppressGroup: RadioGroup? = null
    private var throatClassInput: EditText? = null
    private var throatMethodInput: EditText? = null
    private var throatSigInput: EditText? = null
    private var listContainer: LinearLayout? = null

    private var selectedView = WeakReference<View>(null)

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!started) return
            syncAttachment()
            mainHandler.postDelayed(this, SWITCH_TTL_MS)
        }
    }

    /** 组B AppGlassHook 入口：开关开=挂工作台层，关=移除；幂等。 */
    fun ensureStarted(api: XposedInterface, packageName: String) {
        this.api = api
        this.packageName = packageName
        if (started) return
        started = true
        Lg.i(TAG, "workbench started in $packageName")
        mountActivityResumeHook(api)
        mainHandler.postDelayed(tickRunnable, SWITCH_TTL_MS)
    }

    private fun mountActivityResumeHook(api: XposedInterface) {
        if (hooksMounted) return
        hooksMounted = true
        try {
            val onResume = Activity::class.java.getDeclaredMethod("onResume")
            api.hook(onResume)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    try {
                        (chain.getThisObject() as? Activity)?.let { act ->
                            currentActivity = WeakReference(act)
                            mainHandler.post { syncAttachment() }
                        }
                    } catch (t: Throwable) {
                        Lg.w(TAG, "resume attach error", t)
                    }
                    result
                }
            Lg.i(TAG, "mounted resume trigger: android.app.Activity#onResume")
        } catch (t: Throwable) {
            Lg.w(TAG, "Activity#onResume hook FAILED", t)
        }
    }

    private fun switchOn(): Boolean {
        val a = api ?: return false
        return try {
            Prefs.read(a).getBoolean(Prefs.KEY_ELEMENT_GLASS_ENABLE, false)
        } catch (t: Throwable) {
            false
        }
    }

    private fun syncAttachment() {
        val act = currentActivity.get()
        val decor = act?.window?.decorView as? ViewGroup
        val layer = rootLayer
        if (!switchOn() || decor == null) {
            detachLayer()
            return
        }
        if (layer == null) {
            val ctx = decor.context
            buildLayer(ctx)
        }
        val l = rootLayer ?: return
        val parent = l.parent
        if (parent !== decor) {
            (parent as? ViewGroup)?.removeView(l)
            decor.addView(
                l,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
            Lg.i(TAG, "layer attached to ${decor.javaClass.name}")
        }
    }

    private fun detachLayer() {
        val l = rootLayer ?: return
        val parent = l.parent as? ViewGroup ?: return
        parent.removeView(l)
        Lg.i(TAG, "layer detached")
    }

    // ---------- 工作台层构建 ----------

    private fun buildLayer(ctx: Context) {
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Float): Int = (v * density + 0.5f).toInt()

        val root = FrameLayout(ctx).apply {
            isClickable = false
            isFocusable = false
        }

        val boxes = BoxLayer(ctx)
        boxes.visibility = View.GONE

        val panel = buildPanel(ctx, ::dp)

        val handle = TextView(ctx).apply {
            text = "LG"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16f).toFloat()
                setColor(0xCC1A1A1A.toInt())
            }
            setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
        }

        var downX = 0f
        var downY = 0f
        var baseX = 0f
        var baseY = 0f
        var dragging = false
        val slop = dp(8f).toFloat()
        handle.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    baseX = v.x; baseY = v.y
                    dragging = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragging) {
                        val nx = baseX + (ev.rawX - downX)
                        val ny = baseY + (ev.rawY - downY)
                        v.x = nx.coerceIn(0f, (v.parent as? View)?.width?.minus(v.width)?.toFloat() ?: nx)
                        v.y = ny.coerceIn(0f, (v.parent as? View)?.height?.minus(v.height)?.toFloat() ?: ny)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    dragging = false
                    if (abs(ev.rawX - downX) < slop && abs(ev.rawY - downY) < slop) {
                        togglePanel(panel)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> { dragging = false; true }
                else -> false
            }
        }

        root.addView(boxes, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        root.addView(panel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(320f),
            Gravity.BOTTOM,
        ))
        root.addView(handle, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START,
        ).apply {
            setMargins(dp(12f), dp(80f), 0, 0)
        })

        panel.visibility = View.GONE
        boxLayer = boxes
        rootLayer = root
        handleView = handle
        panelScroll = panel
    }

    private fun buildPanel(ctx: Context, dp: (Float) -> Int): ScrollView {
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12f).toFloat()
                setColor(0xF01A1A1A.toInt())
            }
        }
        panelContent = content

        content.addView(TextView(ctx).apply {
            text = "点选固化工作台"
            textSize = 14f
            setTextColor(Color.WHITE)
        })

        val scanRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        scanRow.addView(Button(ctx).apply {
            text = "扫描"
            setOnClickListener { scanAndMark() }
        })
        scanRow.addView(Button(ctx).apply {
            text = "清框"
            setOnClickListener {
                boxLayer?.clear()
                selectedView = WeakReference(null)
                infoText?.text = ""
            }
        })
        scanRow.addView(Button(ctx).apply {
            text = "刷新列表"
            setOnClickListener { refreshFrozenList() }
        })
        content.addView(scanRow)

        infoText = TextView(ctx).apply {
            textSize = 11f
            setTextColor(0xFFB0B0B0.toInt())
            setText("点框选择控件", TextView.BufferType.NORMAL)
        }
        content.addView(infoText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(6f) })

        content.addView(sectionLabel(ctx, "固化参数", dp))
        labelInput = EditText(ctx).apply {
            hint = "元素名（label）"
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 13f
        }
        content.addView(labelInput)

        mountGroup = RadioGroup(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        for ((value, title) in listOf(
            MOUNT_BACKGROUND to "背景",
            MOUNT_DRAW_HOOK to "自绘hook",
            MOUNT_THROAT to "咽喉",
        )) {
            mountGroup!!.addView(RadioButton(ctx).apply {
                text = title
                id = value.hashCode()
                tag = value
                textSize = 12f
                setTextColor(Color.WHITE)
            })
        }
        content.addView(mountGroup)

        suppressGroup = RadioGroup(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        for ((value, title) in listOf(
            SUPPRESS_NONE to "不抑制",
            SUPPRESS_THROAT to "咽喉抑制",
            SUPPRESS_FULL to "全抑制",
        )) {
            suppressGroup!!.addView(RadioButton(ctx).apply {
                text = title
                id = value.hashCode()
                tag = value
                textSize = 12f
                setTextColor(Color.WHITE)
            })
        }
        content.addView(suppressGroup)

        val throatRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        throatClassInput = EditText(ctx).apply { hint = "咽喉类"; textSize = 12f }
        throatMethodInput = EditText(ctx).apply { hint = "方法"; textSize = 12f }
        throatSigInput = EditText(ctx).apply { hint = "签名"; textSize = 12f }
        throatRow.addView(throatClassInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))
        throatRow.addView(throatMethodInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.6f))
        throatRow.addView(throatSigInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))
        content.addView(throatRow)

        content.addView(Button(ctx).apply {
            text = "固化（写入并生效）"
            setOnClickListener { freezeSelected() }
        })

        content.addView(sectionLabel(ctx, "已固化列表", dp))
        listContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        content.addView(listContainer)

        content.addView(TextView(ctx).apply {
            text = "本工作台只在已加入名单的应用内显示；添加应用在模块设置页"
            textSize = 10f
            setTextColor(0xFF777777.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(8f), 0, 0)
        })

        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            addView(content)
        }
        panelScroll = scroll
        panelContent = content
        return scroll
    }

    private fun sectionLabel(ctx: Context, text: String, dp: (Float) -> Int): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = 12f
            setTextColor(0xFF80CBC4.toInt())
            setPadding(0, dp(8f), 0, dp(2f))
        }

    private fun togglePanel(panel: View) {
        if (panel.visibility == View.VISIBLE) {
            panel.visibility = View.GONE
        } else {
            panel.visibility = View.VISIBLE
            refreshFrozenList()
        }
    }

    // ---------- 扫描与标框 ----------

    private fun scanAndMark() {
        val decor = rootLayer?.parent as? ViewGroup ?: return
        val workbench = rootLayer
        val boxes = ArrayList<RectF>()
        val views = ArrayList<View>()
        var fixed = 0

        fun walk(view: View, depth: Int) {
            if (depth > MAX_SCAN_DEPTH || views.size >= MAX_SCAN_NODES) return
            if (view === workbench || !view.isShown || view.width <= 0 || view.height <= 0) return
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            val dl = IntArray(2)
            decor.getLocationOnScreen(dl)
            val r = RectF(
                (loc[0] - dl[0]).toFloat(),
                (loc[1] - dl[1]).toFloat(),
                (loc[0] - dl[0] + view.width).toFloat(),
                (loc[1] - dl[1] + view.height).toFloat(),
            )
            boxes.add(r)
            views.add(view)
            if (matchFixed(view)) fixed++
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    walk(view.getChildAt(i), depth + 1)
                }
            }
        }
        for (i in 0 until decor.childCount) {
            walk(decor.getChildAt(i), 1)
        }
        boxLayer?.setTargets(views, boxes, fixed)
        boxLayer?.visibility = View.VISIBLE
        infoText?.text = "扫描完成：${views.size} 框（绿=${fixed}），点框选择"
        Lg.i(TAG, "scan done nodes=${views.size} fixed=$fixed")
    }

    private fun matchFixed(view: View): Boolean = try {
        GlassElementRegistry.matchAnchor(view) != null
    } catch (t: Throwable) {
        false
    }

    // ---------- 点选信息 ----------

    private fun onBoxPicked(index: Int) {
        val boxes = boxLayer ?: return
        val view = boxes.viewAt(index) ?: return
        selectedView = WeakReference(view)
        val cls = view.javaClass
        val sb = StringBuilder()
        sb.append("类: ").append(cls.name).append('\n')

        var idEntry: String? = null
        var idPkg: String? = null
        if (view.id != View.NO_ID) {
            try {
                idEntry = view.resources.getResourceEntryName(view.id)
                idPkg = view.resources.getResourcePackageName(view.id)
                sb.append("id: ").append(idEntry).append(" @").append(idPkg).append('\n')
            } catch (t: Throwable) {
                sb.append("id: 0x").append(Integer.toHexString(view.id)).append("（不可解析）\n")
            }
        } else {
            sb.append("id: 无\n")
        }

        val chain = parentChain(view)
        sb.append("父链: ").append(chain.joinToString(" < ")).append('\n')
        sb.append("尺寸: ").append(view.width).append("x").append(view.height).append('\n')

        val bgName = try { view.background?.javaClass?.name } catch (t: Throwable) { null }
        val selfDraw = hasDeclaredOnDraw(cls)
        sb.append("背景: ").append(bgName ?: "无drawable")
        if (selfDraw) sb.append(" +onDraw自绘")
        infoText?.text = sb.toString()

        labelInput?.setText(cls.simpleName)
        val mount = when {
            bgName != null -> MOUNT_BACKGROUND
            cls.name.contains("HighlightShadow") -> MOUNT_THROAT
            else -> MOUNT_DRAW_HOOK
        }
        checkRadio(mountGroup, mount)
        val isHighlight = cls.name.contains("HighlightShadow")
        checkRadio(suppressGroup, if (isHighlight) SUPPRESS_THROAT else SUPPRESS_NONE)
        if (isHighlight) {
            throatClassInput?.setText(THROAT_CLASS_DEFAULT)
            throatMethodInput?.setText(THROAT_METHOD_DEFAULT)
            throatSigInput?.setText(THROAT_SIGNATURE_DEFAULT)
        } else {
            throatClassInput?.text?.clear()
            throatMethodInput?.text?.clear()
            throatSigInput?.text?.clear()
        }
        selectedIdEntry = idEntry
        selectedIdPkg = idPkg
        selectedChain = chain
    }

    private var selectedIdEntry: String? = null
    private var selectedIdPkg: String? = null
    private var selectedChain: List<String> = emptyList()

    private fun checkRadio(group: RadioGroup?, value: String) {
        group ?: return
        val id = value.hashCode()
        for (i in 0 until group.childCount) {
            val rb = group.getChildAt(i) as? RadioButton ?: continue
            rb.isChecked = rb.id == id
        }
    }

    private fun radioValue(group: RadioGroup?, default: String): String {
        group ?: return default
        for (i in 0 until group.childCount) {
            val rb = group.getChildAt(i) as? RadioButton ?: continue
            if (rb.isChecked) return rb.tag as? String ?: default
        }
        return default
    }

    private fun parentChain(view: View): List<String> {
        val out = ArrayList<String>(PARENT_CHAIN_LEVELS)
        var p = view.parent as? View
        var n = 0
        while (p != null && n < PARENT_CHAIN_LEVELS) {
            out.add(p.javaClass.name)
            p = p.parent as? View
            n++
        }
        return out
    }

    private fun hasDeclaredOnDraw(cls: Class<*>): Boolean {
        if (cls.name.startsWith("android.") || cls.name.startsWith("androidx.")) return false
        return try {
            cls.declaredMethods.any { it.name == "onDraw" }
        } catch (t: Throwable) {
            false
        }
    }

    // ---------- 固化 ----------

    private fun freezeSelected() {
        val view = selectedView.get()
        if (view == null) {
            toast("先点选一个控件框")
            return
        }
        val label = labelInput?.text?.toString()?.trim()?.ifEmpty { view.javaClass.simpleName }
            ?: view.javaClass.simpleName
        val minW = (view.width * (1f - SIZE_TOLERANCE)).roundToInt().coerceAtLeast(1)
        val maxW = (view.width * (1f + SIZE_TOLERANCE)).roundToInt().coerceAtLeast(1)
        val minH = (view.height * (1f - SIZE_TOLERANCE)).roundToInt().coerceAtLeast(1)
        val maxH = (view.height * (1f + SIZE_TOLERANCE)).roundToInt().coerceAtLeast(1)
        val throatClass = throatClassInput?.text?.toString()?.trim()?.ifEmpty { null }
        val entry = GlassElementEntry(
            label = label,
            anchorClassName = view.javaClass.name,
            anchorViewId = selectedIdEntry,
            anchorIdPackage = selectedIdPkg,
            anchorParentChain = selectedChain,
            anchorSizeMinW = minW,
            anchorSizeMaxW = maxW,
            anchorSizeMinH = minH,
            anchorSizeMaxH = maxH,
            mountType = radioValue(mountGroup, MOUNT_BACKGROUND),
            suppressMode = radioValue(suppressGroup, SUPPRESS_NONE),
            throatClass = throatClass,
            throatMethod = throatMethodInput?.text?.toString()?.trim()?.ifEmpty { null },
            throatSignature = throatSigInput?.text?.toString()?.trim()?.ifEmpty { null },
            presetKey = DEFAULT_PRESET_KEY,
            overrides = emptyMap(),
            enabled = true,
        )
        try {
            GlassElementRegistry.append(entry)
        } catch (t: Throwable) {
            Lg.w(TAG, "append failed", t)
            toast("固化失败: ${t.javaClass.simpleName}")
            return
        }
        Lg.i(TAG, "element-frozen label=$label cls=${entry.anchorClassName} mount=${entry.mountType} suppress=${entry.suppressMode}")
        toast("已固化「$label」，立即生效")
        tryRescan()
        refreshFrozenList()
    }

    private fun tryRescan() {
        try {
            val cls = Class.forName(PAGE_ENGINE_CLASS)
            val method = cls.getDeclaredMethod("rescanNow")
            method.isAccessible = true
            method.invoke(null)
            Lg.i(TAG, "rescanNow invoked")
        } catch (t: Throwable) {
            Lg.i(TAG, "rescan reflection unavailable: ${t.javaClass.simpleName}")
        }
    }

    // ---------- 已固化列表 ----------

    private fun refreshFrozenList() {
        val container = listContainer ?: return
        container.removeAllViews()
        val ctx = container.context
        val density = ctx.resources.displayMetrics.density
        val entries = try {
            GlassElementRegistry.load()
        } catch (t: Throwable) {
            Lg.w(TAG, "load failed", t)
            container.addView(TextView(ctx).apply {
                text = "读取失败: ${t.javaClass.simpleName}"
                textSize = 11f
                setTextColor(0xFFFF5252.toInt())
            })
            return
        }
        if (entries.isEmpty()) {
            container.addView(TextView(ctx).apply {
                text = "（暂无固化条目）"
                textSize = 11f
                setTextColor(0xFF777777.toInt())
            })
            return
        }
        for (entry in entries) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(TextView(ctx).apply {
                text = "${entry.label} · ${entry.anchorClassName.substringAfterLast('.')}"
                textSize = 11f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Switch(ctx).apply {
                isChecked = entry.enabled
                setOnCheckedChangeListener { _, checked ->
                    try {
                        GlassElementRegistry.updateEnabled(resolveAnchorKey(entry), checked)
                        toast(if (checked) "已启用「${entry.label}」" else "已停用「${entry.label}」")
                        tryRescan()
                    } catch (t: Throwable) {
                        Lg.w(TAG, "updateEnabled failed", t)
                    }
                }
            })
            row.addView(Button(ctx).apply {
                text = "删"
                textSize = 12f
                setOnClickListener {
                    try {
                        GlassElementRegistry.remove(resolveAnchorKey(entry))
                        toast("已删除「${entry.label}」，rescan 生效")
                        tryRescan()
                    } catch (t: Throwable) {
                        Lg.w(TAG, "remove failed", t)
                    }
                    refreshFrozenList()
                }
            })
            container.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = (4 * density).toInt() })
        }
    }

    private fun resolveAnchorKey(entry: GlassElementEntry): String = entry.anchorKey

    private fun toast(msg: String) {
        val decor = rootLayer?.parent as? View ?: return
        try {
            Toast.makeText(decor.context, msg, Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Lg.w(TAG, "toast failed", t)
        }
    }

    // ---------- 标框层 ----------

    private class BoxLayer(ctx: Context) : View(ctx) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        private var views: List<View> = emptyList()
        private var rects: List<RectF> = emptyList()
        private var fixedMask: BooleanArray = BooleanArray(0)
        private var selectedIndex = -1

        fun setTargets(views: List<View>, rects: List<RectF>, @Suppress("UNUSED_PARAMETER") fixedHint: Int) {
            this.views = views
            this.rects = rects
            this.fixedMask = BooleanArray(views.size) { matchFixed(views[it]) }
            this.selectedIndex = -1
            invalidate()
        }

        fun clear() {
            views = emptyList()
            rects = emptyList()
            fixedMask = BooleanArray(0)
            selectedIndex = -1
            visibility = GONE
            invalidate()
        }

        fun viewAt(index: Int): View? = views.getOrNull(index)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            for (i in rects.indices) {
                paint.color = when {
                    i == selectedIndex -> COLOR_BOX_SELECTED
                    fixedMask[i] -> COLOR_BOX_FIXED
                    else -> COLOR_BOX_GRAY
                }
                canvas.drawRect(rects[i], paint)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked != MotionEvent.ACTION_UP) return true
            val x = event.x
            val y = event.y
            var hit = -1
            for (i in rects.indices.reversed()) {
                if (rects[i].contains(x, y)) {
                    hit = i
                    break
                }
            }
            if (hit >= 0) {
                selectedIndex = hit
                invalidate()
                onBoxPicked(hit)
            }
            return true
        }
    }
}
