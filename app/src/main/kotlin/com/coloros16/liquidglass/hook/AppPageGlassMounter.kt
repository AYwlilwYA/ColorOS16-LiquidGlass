package com.coloros16.liquidglass.hook

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Path
import android.graphics.RenderEffect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.VectorDrawable
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.coloros16.liquidglass.config.GlassElementEntry
import com.coloros16.liquidglass.config.GlassElementRegistry
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.entry.XposedEntry
import com.coloros16.liquidglass.hook.spec.EngineLog
import com.coloros16.liquidglass.log.Lg
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * 点选元素玻璃挂载引擎：按 [GlassElementRegistry] 固化表匹配 View 树并挂背板玻璃。
 *
 * - 触发：Activity.onResume（进页）+ View.setBackground（背景变更信号），延迟一帧到 idle 扫描；
 *   固化写入后经 [rescanNow] 立即热挂。
 * - 匹配：类名全等 + id（含包）+ 父链 + 尺寸区间（[GlassElementRegistry.matchAnchor]）。
 * - 挂载：全部条目走背景 RenderEffect 直挂（[AppGlassHook.applyGlassEffect] 通道）。
 * - 抑制：条目 suppressMode 驱动——THROAT = 族类 onDraw 架抑制桥 + 咽喉方法条件化跳过
 *   （仅已玻璃化宿主抑制，未命中元素自绘照常）；FULL = 该类 onDraw 对已挂玻璃宿主整段跳过。
 * - 旧形态规则自动发现降为候选提示：命中形态但未固化 → 只打节流日志，不挂。
 */
object PageGlassEngine {

    private const val TAG = "AppPageGlass"

    private const val SWITCH_TTL_MS = 500L

    private const val SCAN_MIN_INTERVAL_MS = 500L

    private const val MAX_SCAN_DEPTH = 40

    private const val MAX_SCAN_NODES = 3000

    private const val GUARD_THROTTLE_MS = 2000L

    /** 候选提示最小短边（dp）：挡 divider / 小图标底。 */
    private const val MIN_SHORT_EDGE_DP = 24f

    /** 页卡圆角链兜底值（dp）：不按短边比例（钮观感），大卡套小圆角会画成大胶囊角。 */
    private const val DEFAULT_CORNER_DP = 28f

    /** 旧整页开关键（读兼容；总闸 [Prefs.KEY_ELEMENT_GLASS_ENABLE] 与之 OR）。 */
    private val COMPAT_SWITCH_KEYS = mapOf(
        XposedEntry.PACKAGE_ASSISTANT_SCREEN to Prefs.KEY_ASSISTANT_PAGE_GLASS,
        XposedEntry.PACKAGE_QUICK_SEARCH_BOX to Prefs.KEY_QUICK_SEARCH_PAGE_GLASS,
    )

    private val COMPAT_SWITCH_DEFAULTS = mapOf(
        Prefs.KEY_ASSISTANT_PAGE_GLASS to Prefs.DEFAULT_ASSISTANT_PAGE_GLASS,
        Prefs.KEY_QUICK_SEARCH_PAGE_GLASS to Prefs.DEFAULT_QUICK_SEARCH_PAGE_GLASS,
    )

    private var api: XposedInterface? = null

    private var hostClassLoader: ClassLoader? = null

    @Volatile
    private var compatSwitchKey: String? = null

    @Volatile
    private var compatSwitchDefault: Boolean = false

    @Volatile
    private var cachedSwitch = false

    @Volatile
    private var cachedSwitchAtMs = 0L

    @Volatile
    private var switchLogged = false

    @Volatile
    private var lastScanAtMs = 0L

    private var hooksMounted = false

    /** 填充抑制桥：已玻璃化宿主 set（同线程顺序调用，onDraw 返回即清）。 */
    private val suppressHost = ThreadLocal<View?>()

    /** 已挂 onDraw 桥 / 咽喉 hook 的目标（按类名去重，registry 增量补挂）。 */
    private val onDrawBridged = ConcurrentHashMap.newKeySet<String>()

    private val throatHooked = ConcurrentHashMap.newKeySet<String>()

    private class PageGlassState(view: View, var entry: GlassElementEntry) {
        val viewRef = WeakReference(view)
        var applied = false
        var compileFailed = false
        var logged = false
        var lastW = 0
        var lastH = 0
        var instance: AppGlassBackdropShader.Instance? = null
        val listener = ViewTreeObserver.OnPreDrawListener {
            viewRef.get()?.let { syncPageGlass(it) }
            true
        }
    }

    /** 已挂载/在同步的控件（全主线程访问：扫描 / preDraw / setBackground 均 UI 线程）。 */
    private val states = WeakHashMap<View, PageGlassState>()

    /** 近期扫描过的窗口根（rescanNow 热挂目标）。 */
    private val knownRoots = ArrayList<WeakReference<View>>()

    private val loggedKeys = ConcurrentHashMap.newKeySet<String>()

    private val diagAtMs = ConcurrentHashMap<String, Long>()

    fun install(api: XposedInterface, classLoader: ClassLoader, packageName: String) {
        this.api = api
        this.hostClassLoader = classLoader
        compatSwitchKey = COMPAT_SWITCH_KEYS[packageName]
        compatSwitchKey?.let { compatSwitchDefault = COMPAT_SWITCH_DEFAULTS[it] ?: false }
        GlassElementRegistry.attach(api)
        EngineLog.attach { Prefs.read(api) }
        if (hooksMounted) return
        hooksMounted = true
        Lg.i(TAG, "installing page glass engine into $packageName (compat switch=$compatSwitchKey)")
        EngineLog.log("PageGlass", "install pkg=$packageName compatSwitch=$compatSwitchKey")
        mountResumeScanHook(api)
        precompileSelfCheck()
        ensureRuntimeHooks()
    }

    /** install 期预编译自检：固定样例触发 shader 编译，装机日志直接见编译真凶。 */
    private fun precompileSelfCheck() {
        try {
            val effect = AppGlassBackdropShader.createEffect(100, 100, 20f, cornerIsConic = true)
            if (effect != null) {
                Lg.i(TAG, "page shader precompile OK")
            } else {
                Lg.w(TAG, "page shader precompile skipped: createEffect returned null")
            }
        } catch (t: Throwable) {
            Lg.w(TAG, "page shader precompile failed: ${t.javaClass.name}: ${t.message}")
        }
    }

    /** 固化表增量补挂：启用条目需要的 onDraw 桥与咽喉 hook（已挂的类跳过）。 */
    fun ensureRuntimeHooks() {
        for (cls in GlassElementRegistry.suppressedClasses()) ensureOnDrawBridge(cls)
        for (entry in GlassElementRegistry.enabledThroats()) ensureThroatHook(entry)
    }

    /** 抑制桥：条目类 onDraw 前置——FULL 整段跳过 / THROAT 架桥（无命中零干预）。 */
    private fun ensureOnDrawBridge(clsName: String) {
        if (!onDrawBridged.add(clsName)) {
            logGuard("guard:bridge-already:$clsName", "onDraw bridge skipped: already mounted $clsName", fullDetail = true)
            return
        }
        val cl = hostClassLoader ?: run {
            logGuard("guard:bridge-cl-null", "onDraw bridge skipped: classloader missing $clsName", fullDetail = true)
            return
        }
        val clazz = try {
            hostForName(clsName, cl)
        } catch (t: Throwable) {
            Lg.w(TAG, "$clsName unavailable, onDraw bridge skipped")
            return
        }
        try {
            val onDraw = clazz.getMethod("onDraw", Canvas::class.java)
            api?.hook(onDraw)
                ?.setExceptionMode(ExceptionMode.PROTECTIVE)
                ?.intercept { chain ->
                    val view = chain.getThisObject() as? View
                    var suppressFull = false
                    var bridge = false
                    if (view != null && pageGlassEnabled()) {
                        val entry = GlassElementRegistry.matchAnchor(view)
                        if (entry != null && isApplied(view)) {
                            when (entry.suppressMode) {
                                GlassElementEntry.SUPPRESS_FULL -> suppressFull = true
                                GlassElementEntry.SUPPRESS_THROAT -> bridge = true
                            }
                        }
                    }
                    when {
                        suppressFull -> {
                            logPageDiag(
                                "page-suppress-full:${view?.javaClass?.name}",
                                "onDraw full suppressed view=${view?.javaClass?.name}",
                                2000L,
                            )
                            null
                        }
                        bridge -> {
                            suppressHost.set(view)
                            try {
                                chain.proceed()
                            } finally {
                                suppressHost.remove()
                            }
                        }
                        else -> chain.proceed()
                    }
                }
            Lg.i(TAG, "mounted onDraw suppression bridge: $clsName")
        } catch (t: Throwable) {
            onDrawBridged.remove(clsName)
            Lg.w(TAG, "onDraw bridge hook FAILED $clsName", t)
        }
    }

    /** 咽喉抑制：条目提供的方法签名锚全部命中才挂（防混淆错位），锚不过则不挂。 */
    private fun ensureThroatHook(entry: GlassElementEntry) {
        val clsName = entry.throatClass ?: run {
            logGuard("guard:throat-null", "fill suppression skipped: throatClass null label=${entry.label}")
            return
        }
        if (!throatHooked.add(clsName)) {
            logGuard("guard:throat-already:$clsName", "fill suppression skipped: already mounted $clsName", fullDetail = true)
            return
        }
        val cl = hostClassLoader ?: run {
            logGuard("guard:throat-cl-null", "fill suppression skipped: classloader missing $clsName", fullDetail = true)
            return
        }
        val clazz = try {
            hostForName(clsName, cl)
        } catch (t: Throwable) {
            Lg.w(TAG, "$clsName unavailable, fill suppression skipped")
            return
        }
        try {
            val anchors = parseThroatAnchors(entry.throatSignature, entry.throatMethod, clazz, cl)
            val primary = anchors.first()
            api?.hook(primary)
                ?.setExceptionMode(ExceptionMode.PROTECTIVE)
                ?.intercept { chain ->
                    val host = suppressHost.get()
                    if (host != null) {
                        logPageDiag(
                            "page-suppress:${host.javaClass.name}",
                            "fill suppressed view=${host.javaClass.name}",
                            2000L,
                        )
                        null
                    } else {
                        chain.proceed()
                    }
                }
            Lg.i(TAG, "mounted fill suppression: $clsName#${primary.name} (anchors=${anchors.size} OK)")
        } catch (t: Throwable) {
            throatHooked.remove(clsName)
            Lg.w(TAG, "fill suppression hook FAILED $clsName: ${t.message}")
        }
    }

    /** 解析咽喉锚串："name(Type,Type);..."；分号分隔多项、全部 getMethod 命中才通过。 */
    private fun parseThroatAnchors(
        signature: String?,
        method: String?,
        clazz: Class<*>,
        cl: ClassLoader,
    ): List<java.lang.reflect.Method> {
        if (!signature.isNullOrBlank()) {
            val out = ArrayList<java.lang.reflect.Method>()
            for (piece in signature.split(';')) {
                val s = piece.trim()
                if (s.isEmpty()) continue
                val paren = s.indexOf('(')
                if (paren <= 0 || !s.endsWith(')')) throw IllegalArgumentException("bad anchor $s")
                val name = s.substring(0, paren)
                val typesRaw = s.substring(paren + 1, s.length - 1)
                val params = if (typesRaw.isBlank()) {
                    emptyArray()
                } else {
                    typesRaw.split(',').map { resolveParamType(it.trim(), cl) }.toTypedArray()
                }
                out.add(clazz.getMethod(name, *params))
            }
            if (out.isEmpty()) throw IllegalArgumentException("empty throat signature")
            return out
        }
        if (!method.isNullOrBlank()) return listOf(clazz.getMethod(method))
        throw IllegalArgumentException("no throat anchor on ${clazz.name}")
    }

    /** 咽喉锚参数类型解析：原始类型 + 常见简单名映射，其余按全限定名走宿主 ClassLoader。 */
    private val PARAM_TYPE_ALIASES = mapOf(
        "Canvas" to "android.graphics.Canvas",
        "Path" to "android.graphics.Path",
        "View" to "android.view.View",
        "Bitmap" to "android.graphics.Bitmap",
        "Paint" to "android.graphics.Paint",
        "Rect" to "android.graphics.Rect",
        "RectF" to "android.graphics.RectF",
        "Drawable" to "android.graphics.drawable.Drawable",
        "String" to "java.lang.String",
    )

    private fun resolveParamType(name: String, cl: ClassLoader): Class<*> = when (name) {
        "void" -> Void.TYPE
        "int" -> Integer.TYPE
        "long" -> java.lang.Long.TYPE
        "float" -> java.lang.Float.TYPE
        "double" -> java.lang.Double.TYPE
        "boolean" -> java.lang.Boolean.TYPE
        "short" -> java.lang.Short.TYPE
        "byte" -> java.lang.Byte.TYPE
        "char" -> java.lang.Character.TYPE
        else -> hostForName(PARAM_TYPE_ALIASES[name] ?: name, cl)
    }

    /** 进页扫描触发：onResume → 延迟一帧 → 主线程 idle 执行扫描。 */
    private fun mountResumeScanHook(api: XposedInterface) {
        try {
            val onResume = Activity::class.java.getDeclaredMethod("onResume")
            api.hook(onResume)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    try {
                        (chain.getThisObject() as? Activity)?.window?.decorView?.let { scheduleScan(it) }
                    } catch (t: Throwable) {
                        Lg.w(TAG, "resume scan schedule error", t)
                    }
                    result
                }
            Lg.i(TAG, "mounted scan trigger: android.app.Activity#onResume")
        } catch (t: Throwable) {
            Lg.w(TAG, "Activity#onResume hook FAILED", t)
        }
    }

    /** 背景变更信号（AppGlassHook 的 setBackground 拦截转发）：候选出现即调度扫描。 */
    internal fun onHostBackgroundSet(view: View) {
        if (api == null) {
            logGuard("guard:onbg-api-null", "background signal dropped: engine not installed", fullDetail = true)
            return
        }
        scheduleScan(view.rootView)
    }

    /** 固化 append 后立即热挂：补挂 hook + 对已知窗口根无节流重扫。 */
    @JvmStatic
    fun rescanNow() {
        ensureRuntimeHooks()
        val roots = knownRoots.mapNotNull { it.get() }.filter { it.isAttachedToWindow }
        for (root in roots) {
            root.post {
                Looper.myQueue().addIdleHandler {
                    try {
                        if (root.isAttachedToWindow) scan(root)
                    } catch (t: Throwable) {
                        Lg.w(TAG, "rescan error", t)
                    }
                    false
                }
                true
            }
        }
        Lg.i(TAG, "rescanNow roots=${roots.size} entries=${GlassElementRegistry.load().size}")
        EngineLog.log("PageGlass", "rescanNow roots=${roots.size} entries=${GlassElementRegistry.load().size}")
    }

    private fun scheduleScan(root: View) {
        if (!pageGlassEnabled()) {
            logGuard("guard:scan-schedule-off", "scan skipped: switch off (root=${root.javaClass.simpleName})")
            return
        }
        noteRoot(root)
        val now = SystemClock.uptimeMillis()
        if (now - lastScanAtMs < SCAN_MIN_INTERVAL_MS) {
            logGuard("guard:scan-throttle", "scan skipped: min-interval throttle", fullDetail = true)
            return
        }
        lastScanAtMs = now
        root.post {
            Looper.myQueue().addIdleHandler {
                try {
                    if (root.isAttachedToWindow) scan(root)
                } catch (t: Throwable) {
                    Lg.w(TAG, "scan error", t)
                }
                false
            }
            true
        }
    }

    private fun noteRoot(root: View) {
        knownRoots.removeAll { it.get() == null }
        if (knownRoots.any { it.get() === root }) return
        if (knownRoots.size >= 8) knownRoots.removeAt(0)
        knownRoots.add(WeakReference(root))
    }

    private fun scan(root: View) {
        if (!pageGlassEnabled()) {
            logGuard("guard:scan-off", "scan aborted: switch off (root=${root.javaClass.simpleName})")
            return
        }
        noteRoot(root)
        ensureRuntimeHooks()
        var hits = 0
        fun walk(view: View, depth: Int) {
            if (depth > MAX_SCAN_DEPTH || hits > MAX_SCAN_NODES) {
                logGuard("guard:scan-cap", "scan truncated depth=$depth hits=$hits", fullDetail = true)
                return
            }
            // root 是 decorView：窗口背景不参与匹配，只递归子节点
            if (view !== root) {
                val entry = GlassElementRegistry.matchAnchor(view)
                if (entry != null) {
                    hits++
                    ensurePageState(view, entry)
                } else {
                    maybeLogCandidate(view)
                }
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    walk(view.getChildAt(i), depth + 1)
                }
            }
        }
        walk(root, 0)
        logPageDiag(
            "page-scan",
            "scan root=${root.javaClass.simpleName} hits=$hits states=${states.size}",
            10_000L,
        )
        EngineLog.log("PageGlass", "scan root=${root.javaClass.simpleName} hits=$hits states=${states.size}")
    }

    /** 旧形态规则降级：形态像背板但未固化 → 只打节流候选日志，不挂。 */
    private fun maybeLogCandidate(view: View) {
        if (view.width <= 0 || view.height <= 0) return
        val bg = try {
            view.background
        } catch (t: Throwable) {
            null
        } ?: return
        val d = unwrapInset(bg)
        val solid = when (d) {
            is GradientDrawable -> true
            is VectorDrawable -> true
            is ColorDrawable -> d.alpha > 0
            else -> false
        }
        if (!solid) return
        val minPx = MIN_SHORT_EDGE_DP * view.resources.displayMetrics.density
        if (minOf(view.width, view.height) < minPx) return
        logPageDiag(
            "page-candidate:${view.javaClass.name}",
            "page-candidate ${view.javaClass.name} ${view.width}x${view.height}",
            30_000L,
        )
    }

    private fun unwrapInset(drawable: Drawable): Drawable {
        var cur = drawable
        var guard = 0
        while (cur is InsetDrawable && guard < 4) {
            val inner = cur.drawable ?: break
            cur = inner
            guard++
        }
        return cur
    }

    private fun ensurePageState(view: View, entry: GlassElementEntry) {
        val existing = states[view]
        if (existing != null) {
            existing.entry = entry
            try {
                view.viewTreeObserver.removeOnPreDrawListener(existing.listener)
            } catch (_: Throwable) {
            }
            try {
                view.viewTreeObserver.addOnPreDrawListener(existing.listener)
            } catch (t: Throwable) {
                Lg.w(TAG, "page preDraw re-add failed", t)
            }
            return
        }
        val state = PageGlassState(view, entry)
        states[view] = state
        try {
            view.viewTreeObserver.addOnPreDrawListener(state.listener)
        } catch (t: Throwable) {
            Lg.w(TAG, "page preDraw add failed", t)
        }
        Lg.i(TAG, "page element matched [${entry.mountType}] view=${view.javaClass.name} label=${entry.label}")
        EngineLog.log("PageGlass", "matched [${entry.mountType}] view=${view.javaClass.name} label=${entry.label}")
    }

    /** preDraw 同步：开关关 / 条目失配还原；开且尺寸就绪挂载；已挂且 bounds 变走 uniform 热更。 */
    private fun syncPageGlass(view: View) {
        val state = states[view] ?: run {
            logGuard("guard:state-missing", "preDraw sync skip: state missing view=${view.javaClass.name}", fullDetail = true)
            return
        }
        if (state.compileFailed) {
            logGuard(
                "guard:compile-stopped:${view.javaClass.name}",
                "preDraw skip: compile failed view=${view.javaClass.name}",
            )
            return
        }
        val entry = GlassElementRegistry.matchAnchor(view)
        if (!pageGlassEnabled()) {
            if (state.applied) restorePageGlass(view, state)
            else logGuard("guard:sync-switch-off:${view.javaClass.name}", "sync idle: switch off view=${view.javaClass.name} (not applied)")
            return
        }
        if (entry == null) {
            if (state.applied) restorePageGlass(view, state)
            else logGuard("guard:anchor-lost:${view.javaClass.name}", "sync idle: anchor unmatched view=${view.javaClass.name} (entry removed/disabled/resized)")
            return
        }
        state.entry = entry
        if (view.width <= 1 || view.height <= 1) {
            logGuard("guard:size-not-ready:${view.javaClass.name}", "sync wait: size not ready view=${view.javaClass.name} ${view.width}x${view.height}")
            return
        }
        if (state.applied) {
            if (view.width != state.lastW || view.height != state.lastH) {
                val (corner, _) = resolveCorner(view, state.entry)
                val updated = state.instance?.update(view.width, view.height, corner) == true
                state.lastW = view.width
                state.lastH = view.height
                if (updated) {
                    logPageDiag(
                        "page-uniform:${view.javaClass.name}",
                        "page glass uniform-update ${view.width}x${view.height} corner=$corner",
                        2000L,
                    )
                    EngineLog.log(
                        "PageGlass",
                        "uniform-update view=${view.javaClass.name} ${view.width}x${view.height}",
                    )
                }
            }
            return
        }
        applyPageGlass(view, state)
    }

    private fun applyPageGlass(view: View, state: PageGlassState) {
        if (AppGlassHook.glassSetBgMethod() == null) {
            logGuard("guard:setbg-null", "apply skipped: A-family mount point unresolved view=${view.javaClass.name}")
            return
        }
        val (corner, cornerSource) = resolveCorner(view, state.entry)
        val instance = try {
            state.instance?.also { it.update(view.width, view.height, corner) }
                ?: AppGlassBackdropShader.createInstance(
                    view.width, view.height, corner, cornerIsConic = true,
                )?.also { state.instance = it }
        } catch (t: Throwable) {
            AppGlassHook.noteGlassCompileError(t)
            null
        }
        if (instance == null) {
            state.compileFailed = true
            Lg.w(TAG, "page glass compile failed, decor fallback view=${view.javaClass.name} error=${AppGlassHook.lastGlassCompileError}")
            AppGlassHook.ensureBDecor(view)
            return
        }
        if (!AppGlassHook.applyGlassEffect(view, instance.effect)) {
            logGuard("guard:apply-failed:${view.javaClass.name}", "applyGlassEffect failed view=${view.javaClass.name} (detail in AppGlass log)")
            return
        }
        state.applied = true
        state.lastW = view.width
        state.lastH = view.height
        AppGlassHook.registerElement(view, view.javaClass.name, state.entry.mountType)
        EngineLog.log("PageGlass", "applied [${state.entry.mountType}] view=${view.javaClass.name} ${view.width}x${view.height} corner=$corner[$cornerSource] label=${state.entry.label}")
        if (!state.logged) {
            state.logged = true
            Lg.i(
                TAG,
                "page glass applied [${state.entry.mountType}] view=${view.javaClass.name} " +
                    "${view.width}x${view.height} corner=$corner[$cornerSource] label=${state.entry.label}",
            )
        }
    }

    /**
     * 页卡圆角解析链：①条目 overrides ②outline 实测 ③背景 drawable 圆角 ④兜底 28dp。
     * 返回 (值, 来源标记)，来源进挂载日志对照。
     */
    private fun resolveCorner(view: View, entry: GlassElementEntry): Pair<Float, String> {
        entry.overrides["cornerRadius"]?.let { return it to "overrides" }
        entry.overrides["cornerRadiusDp"]?.let { return it * view.resources.displayMetrics.density to "overrides" }
        try {
            val outline = Outline()
            view.outlineProvider.getOutline(view, outline)
            val r = outline.radius
            if (r > 0f && r <= minOf(view.width, view.height)) return r to "outline"
        } catch (_: Throwable) {
        }
        drawableCorner(view)?.let { return it to "drawable" }
        return DEFAULT_CORNER_DP * view.resources.displayMetrics.density to "default"
    }

    /** 背景 drawable 圆角：unwrap Inset 后取 GradientDrawable 四角最大值 / 单角值。 */
    private fun drawableCorner(view: View): Float? {
        val bg = try {
            view.background
        } catch (_: Throwable) {
            null
        } ?: return null
        val d = unwrapInset(bg)
        if (d is GradientDrawable) {
            val radii = try {
                d.cornerRadii
            } catch (_: Throwable) {
                null
            }
            if (radii != null) {
                val maxR = radii.maxOrNull() ?: 0f
                if (maxR > 0f) return maxR
            }
            val single = try {
                d.cornerRadius
            } catch (_: Throwable) {
                0f
            }
            if (single > 0f) return single
        }
        return null
    }

    /** A 族覆写换回专用：复用该 View 实例按当前尺寸/圆角热更 uniform，返回可换回的 effect。 */
    internal fun freshGlassEffect(view: View): RenderEffect? {
        val state = states[view] ?: return null
        if (!state.applied) return null
        val instance = state.instance ?: return null
        instance.update(view.width, view.height, resolveCorner(view, state.entry).first)
        return instance.effect
    }

    private fun restorePageGlass(view: View, state: PageGlassState) {
        if (!state.applied) {
            logGuard("guard:restore-idle", "restore no-op: not applied view=${view.javaClass.name}", fullDetail = true)
            return
        }
        AppGlassHook.restoreHostEffect(view)
        state.applied = false
        logOnce(
            "page-restore:${view.javaClass.name}",
            "page glass restored view=${view.javaClass.name} (switch off or entry gone)",
        )
        EngineLog.log("PageGlass", "restored view=${view.javaClass.name} label=${state.entry.label}")
    }

    /** 元素是否已被本引擎施加玻璃（A 族拦截换回与抑制桥共用判定）。 */
    internal fun isApplied(view: View): Boolean = states[view]?.applied == true

    /** 点选元素玻璃总闸（总键 OR 旧整页键，TTL [SWITCH_TTL_MS] 重读）。 */
    internal fun pageGlassEnabled(): Boolean {
        if (api == null) {
            logGuard("guard:engine-api-null", "pageGlassEnabled false: engine not installed", fullDetail = true)
            return false
        }
        val now = SystemClock.uptimeMillis()
        if (now - cachedSwitchAtMs > SWITCH_TTL_MS) {
            cachedSwitchAtMs = now
            val before = cachedSwitch
            cachedSwitch = try {
                val prefs = Prefs.read(api!!)
                prefs.getBoolean(Prefs.KEY_ELEMENT_GLASS_ENABLE, Prefs.DEFAULT_ELEMENT_GLASS_ENABLE) ||
                    compatSwitchKey?.let {
                        prefs.getBoolean(it, compatSwitchDefault)
                    } == true
            } catch (t: Throwable) {
                Lg.w(TAG, "prefs read element glass switch failed, default false", t)
                false
            }
            val shouldLog = cachedSwitch != before || !switchLogged
            switchLogged = true
            if (shouldLog) Lg.i(TAG, "switch element_glass=$cachedSwitch (compat=$compatSwitchKey)")
        }
        return cachedSwitch
    }

    private fun logOnce(key: String, msg: String) {
        if (!loggedKeys.add(key)) return
        if (loggedKeys.size > 512) loggedKeys.clear()
        Lg.i(TAG, msg)
    }

    /** 守卫诊断：2s 节流；关键守卫 Lg.i，琐碎匹配/幂等失败 fullDetail=Lg.d。 */
    private fun logGuard(key: String, msg: String, fullDetail: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        val prev = diagAtMs[key] ?: 0L
        if (now - prev < GUARD_THROTTLE_MS) return
        diagAtMs[key] = now
        if (diagAtMs.size > 256) diagAtMs.clear()
        if (fullDetail) Lg.d(TAG, "[$key] $msg") else Lg.i(TAG, "[$key] $msg")
    }

    /** 时间窗节流诊断（key 回显进正文，按消息文本可 grep）。 */
    private fun logPageDiag(key: String, msg: String, windowMs: Long) {
        val now = SystemClock.uptimeMillis()
        val prev = diagAtMs[key] ?: 0L
        if (now - prev < windowMs) return
        diagAtMs[key] = now
        if (diagAtMs.size > 256) diagAtMs.clear()
        Lg.i(TAG, "[$key] $msg")
    }

    // 宿主 ClassLoader 加载宿主/混淆类：模块类做 caller 会按模块签名判 hidden API 名单被拦成 CNFE
    private fun hostForName(name: String, fallbackLoader: ClassLoader?): Class<*> {
        fallbackLoader?.let { cl ->
            try {
                return Class.forName(name, false, cl)
            } catch (_: ClassNotFoundException) {
            }
        }
        return Class.forName(name)
    }
}
