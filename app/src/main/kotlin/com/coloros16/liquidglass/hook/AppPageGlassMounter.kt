package com.coloros16.liquidglass.hook

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Path
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
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.entry.XposedEntry
import com.coloros16.liquidglass.log.Lg
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * 负一屏/搜索页整页玻璃批量挂载器（[spec/147]）。
 *
 * 视图树扫描挂载，两型候选：
 * - **A 型**（drawable 背景：GradientDrawable / VectorDrawable / 实色 ColorDrawable）——
 *   形态规则自动发现；
 * - **B 型**（HighlightShadow 族 onDraw 自绘背板）——类名单命中 + 同一 A 族通道挂玻璃，
 *   并经填充咽喉条件化抑制实底（仅已玻璃化宿主抑制，未登记元素自绘照常）；
 * - **硬编码兜底**（无 drawable 背景、形态规则扫不到但需挂的容器/整卡）。
 *
 * 扫描触发：Activity.onResume（进页）+ View.setBackground（背景变更信号），延迟一帧到
 * idle 执行；挂载后每控件持续 preDraw 同步（开关关还原、bounds 变重挂）。
 * A 族写入经 [AppGlassHook] 静态挂载点（旁路拦截防把玻璃记档成宿主原值）。
 */
object AppPageGlassMounter {

    private const val TAG = "AppPageGlass"

    private const val SWITCH_TTL_MS = 500L

    /** 扫描最小间隔（ms）：连续 resume / 背景变更去重。 */
    private const val SCAN_MIN_INTERVAL_MS = 500L

    private const val MAX_SCAN_DEPTH = 40

    private const val MAX_SCAN_NODES = 3000

    /** A 型形态规则最小短边（dp）：挡 divider / 小图标底。 */
    private const val MIN_SHORT_EDGE_DP = 24f

    /** 包 → 整页开关键。 */
    private val PAGE_SWITCH_KEYS = mapOf(
        XposedEntry.PACKAGE_ASSISTANT_SCREEN to Prefs.KEY_ASSISTANT_PAGE_GLASS,
        XposedEntry.PACKAGE_QUICK_SEARCH_BOX to Prefs.KEY_QUICK_SEARCH_PAGE_GLASS,
    )

    private val PAGE_SWITCH_DEFAULTS = mapOf(
        Prefs.KEY_ASSISTANT_PAGE_GLASS to Prefs.DEFAULT_ASSISTANT_PAGE_GLASS,
        Prefs.KEY_QUICK_SEARCH_PAGE_GLASS to Prefs.DEFAULT_QUICK_SEARCH_PAGE_GLASS,
    )

    /** B 型族类（真名稳定锚，common-legacy 库不混淆；ButtonView 真机版本已删，保留条目防版本回摆）。 */
    private val HIGHLIGHT_SHADOW_CLASSES = setOf(
        "com.coloros.common.widget.HighlightShadowConstraintLayout",
        "com.coloros.common.widget.HighlightShadowImageView",
        "com.coloros.common.widget.HighlightShadowButtonView",
    )

    /** 硬编码兜底：无 drawable 背景但需整卡挂玻璃的容器/整卡。 */
    private val HARDCODED_CLASSES = setOf(
        "com.oplus.assistantscreen.cardcontainer.ui.MainPageBannerFixedCardView",
        "com.heytap.quicksearchbox.ui.card.AppSuggestCardView",
    )

    /** 抑制咽喉（R8 混淆名，按真机 APK 版本锚定 + 方法签名锚校验）。 */
    private const val CLASS_FILL_THROAT = "tc.u"
    private const val CLASS_FILL_STROKE = "tc.b"

    private const val FAMILY_A = "A"
    private const val FAMILY_B = "B"
    private const val FAMILY_H = "H"

    private var api: XposedInterface? = null

    @Volatile
    private var switchKey: String = ""

    @Volatile
    private var switchDefault: Boolean = false

    @Volatile
    private var cachedSwitch = false

    @Volatile
    private var cachedSwitchAtMs = 0L

    @Volatile
    private var switchLogged = false

    @Volatile
    private var lastScanAtMs = 0L

    private var hooksMounted = false

    /** 抑制桥：族类 onDraw 已玻璃化宿主 set，填充咽喉读（同线程顺序调用，onDraw 返回即清）。 */
    private val suppressHost = ThreadLocal<View?>()

    private class PageGlassState(view: View, val family: String) {
        val viewRef = WeakReference(view)
        var applied = false
        var compileFailed = false
        var logged = false
        var lastW = 0
        var lastH = 0
        val listener = ViewTreeObserver.OnPreDrawListener {
            viewRef.get()?.let { syncPageGlass(it) }
            true
        }
    }

    /** 已挂载/在同步的控件（全主线程访问：扫描 / preDraw / setBackground 均 UI 线程）。 */
    private val states = WeakHashMap<View, PageGlassState>()

    private val loggedKeys = ConcurrentHashMap.newKeySet<String>()

    private val diagAtMs = ConcurrentHashMap<String, Long>()

    fun install(api: XposedInterface, classLoader: ClassLoader, packageName: String) {
        val key = PAGE_SWITCH_KEYS[packageName] ?: return
        this.api = api
        switchKey = key
        switchDefault = PAGE_SWITCH_DEFAULTS[key] ?: false
        if (hooksMounted) return
        hooksMounted = true
        Lg.i(TAG, "installing page glass mounter into $packageName (switch=$key default=$switchDefault)")
        mountHighlightOnDrawHooks(api, classLoader)
        mountFillSuppressHook(api, classLoader)
        mountResumeScanHook(api)
        precompileSelfCheck()
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

    /** B 型族类 onDraw 前置：已玻璃化宿主在 draw 期间架抑制桥（未登记元素零干预）。 */
    private fun mountHighlightOnDrawHooks(api: XposedInterface, classLoader: ClassLoader) {
        for (clsName in HIGHLIGHT_SHADOW_CLASSES) {
            val clazz = try {
                hostForName(clsName, classLoader)
            } catch (t: Throwable) {
                Lg.w(TAG, "$clsName unavailable, onDraw bridge skipped")
                continue
            }
            try {
                val onDraw = clazz.getMethod("onDraw", Canvas::class.java)
                api.hook(onDraw)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val view = chain.getThisObject() as? View
                        if (view != null && pageGlassEnabled() && isApplied(view)) {
                            suppressHost.set(view)
                            try {
                                chain.proceed()
                            } finally {
                                suppressHost.remove()
                            }
                        } else {
                            chain.proceed()
                        }
                    }
                Lg.i(TAG, "mounted onDraw suppression bridge: $clsName")
            } catch (t: Throwable) {
                Lg.w(TAG, "onDraw bridge hook FAILED $clsName", t)
            }
        }
    }

    /** 咽喉抑制：签名锚（c/d/i 三方法交叉校验）防同名错 hook，锚不过则不挂。 */
    private fun mountFillSuppressHook(api: XposedInterface, classLoader: ClassLoader) {
        val clazz = try {
            hostForName(CLASS_FILL_THROAT, classLoader)
        } catch (t: Throwable) {
            Lg.w(TAG, "$CLASS_FILL_THROAT unavailable, fill suppression skipped")
            return
        }
        try {
            val cMethod = clazz.getMethod(
                "c",
                Canvas::class.java,
                Path::class.java,
                java.lang.Float.TYPE,
            )
            if (cMethod.returnType != Void.TYPE) {
                throw IllegalStateException("c returns ${cMethod.returnType.name}")
            }
            val iMethod = clazz.getMethod(
                "i",
                java.lang.Float.TYPE,
                java.lang.Integer.TYPE,
                java.lang.Integer.TYPE,
            )
            if (!Pair::class.java.isAssignableFrom(iMethod.returnType)) {
                throw IllegalStateException("i returns ${iMethod.returnType.name}")
            }
            clazz.getMethod("d", Canvas::class.java, Path::class.java, hostForName(CLASS_FILL_STROKE, classLoader))
            api.hook(cMethod)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
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
            Lg.i(TAG, "mounted fill suppression: $CLASS_FILL_THROAT#c(Canvas,Path,float) (anchors OK)")
        } catch (t: Throwable) {
            Lg.w(TAG, "fill suppression hook FAILED $CLASS_FILL_THROAT: ${t.message}")
        }
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

    /** 背景变更信号（AppGlassHook 的 setBackground 拦截转发）：A 型候选出现即调度扫描。 */
    internal fun onHostBackgroundSet(view: View) {
        if (switchKey.isEmpty() || !pageGlassEnabled()) return
        scheduleScan(view.rootView)
    }

    private fun scheduleScan(root: View) {
        if (!pageGlassEnabled()) return
        val now = SystemClock.uptimeMillis()
        if (now - lastScanAtMs < SCAN_MIN_INTERVAL_MS) return
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

    private fun scan(root: View) {
        if (!pageGlassEnabled()) return
        var candidates = 0
        fun walk(view: View, depth: Int) {
            if (depth > MAX_SCAN_DEPTH || candidates > MAX_SCAN_NODES) return
            // root 是 decorView：窗口背景会被 A 型规则误命中成整窗玻璃罩，只递归子节点不分类
            if (view !== root) {
                val family = classify(view)
                if (family != null) {
                    candidates++
                    ensurePageState(view, family)
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
            "scan root=${root.javaClass.simpleName} candidates=$candidates states=${states.size}",
            10_000L,
        )
    }

    /** 形态规则分类：B 型族类 / 硬编码容器 / A 型 drawable 背景；未布局返回 null。 */
    private fun classify(view: View): String? {
        val cls = view.javaClass.name
        if (cls in HIGHLIGHT_SHADOW_CLASSES) return FAMILY_B
        if (cls in HARDCODED_CLASSES) return FAMILY_H
        if (view.width <= 0 || view.height <= 0) return null
        val bg = try {
            view.background
        } catch (t: Throwable) {
            null
        } ?: return null
        val d = unwrapInset(bg)
        val solid = when (d) {
            is GradientDrawable -> true
            is VectorDrawable -> true
            is ColorDrawable -> d.alpha > 0
            else -> false
        }
        if (!solid) return null
        val minPx = MIN_SHORT_EDGE_DP * view.resources.displayMetrics.density
        if (minOf(view.width, view.height) < minPx) return null
        return FAMILY_A
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

    private fun ensurePageState(view: View, family: String) {
        val existing = states[view]
        if (existing != null) {
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
        val state = PageGlassState(view, family)
        states[view] = state
        try {
            view.viewTreeObserver.addOnPreDrawListener(state.listener)
        } catch (t: Throwable) {
            Lg.w(TAG, "page preDraw add failed", t)
        }
    }

    /** preDraw 同步：开关关还原；开且尺寸就绪挂载；已挂且 bounds 变重挂。 */
    private fun syncPageGlass(view: View) {
        val state = states[view] ?: return
        if (state.compileFailed) return
        if (!pageGlassEnabled()) {
            if (state.applied) restorePageGlass(view, state)
            return
        }
        if (view.width <= 1 || view.height <= 1) return
        if (state.applied) {
            if (view.width != state.lastW || view.height != state.lastH) {
                unapplyPageGlass(view, state)
                applyPageGlass(view, state)
            }
            return
        }
        applyPageGlass(view, state)
    }

    private fun applyPageGlass(view: View, state: PageGlassState) {
        if (AppGlassHook.glassSetBgMethod() == null) return
        val corner = AppGlassHook.resolveButtonCornerRadius(view)
        val effect = AppGlassHook.cachedGlassEffect(view.width, view.height, corner)
        if (effect == null) {
            state.compileFailed = true
            Lg.w(TAG, "page glass compile failed, decor fallback view=${view.javaClass.name} error=${AppGlassHook.lastGlassCompileError}")
            AppGlassHook.ensureBDecor(view)
            return
        }
        if (!AppGlassHook.applyGlassEffect(view, effect)) return
        state.applied = true
        state.lastW = view.width
        state.lastH = view.height
        AppGlassHook.registerElement(view, view.javaClass.name, state.family)
        if (!state.logged) {
            state.logged = true
            Lg.i(
                TAG,
                "page glass applied [${state.family}] view=${view.javaClass.name} " +
                    "${view.width}x${view.height} corner=$corner",
            )
        }
    }

    private fun unapplyPageGlass(view: View, state: PageGlassState) {
        if (!state.applied) return
        AppGlassHook.restoreHostEffect(view)
        state.applied = false
        logOnce(
            "page-unapply:${view.javaClass.name}",
            "page glass unbound for rebind view=${view.javaClass.name} ${state.lastW}x${state.lastH}",
        )
    }

    private fun restorePageGlass(view: View, state: PageGlassState) {
        if (!state.applied) return
        AppGlassHook.restoreHostEffect(view)
        state.applied = false
        logOnce(
            "page-restore:${view.javaClass.name}",
            "page glass restored view=${view.javaClass.name} (switch off)",
        )
    }

    /** 整页玻璃是否已对该控件施加（A 族拦截换回与抑制桥共用判定）。 */
    internal fun isApplied(view: View): Boolean = states[view]?.applied == true

    /** 整页开关键（TTL [SWITCH_TTL_MS] 重读；AppGlassHook 装饰层兜底联动）。 */
    internal fun pageGlassEnabled(): Boolean {
        val key = switchKey
        if (key.isEmpty()) return false
        val now = SystemClock.uptimeMillis()
        if (now - cachedSwitchAtMs > SWITCH_TTL_MS) {
            cachedSwitchAtMs = now
            val before = cachedSwitch
            cachedSwitch = try {
                api?.let { Prefs.read(it).getBoolean(key, switchDefault) } ?: false
            } catch (t: Throwable) {
                Lg.w(TAG, "prefs read $key failed, default false", t)
                false
            }
            val shouldLog = cachedSwitch != before || !switchLogged
            switchLogged = true
            if (shouldLog) Lg.i(TAG, "switch $key = $cachedSwitch")
        }
        return cachedSwitch
    }

    private fun logOnce(key: String, msg: String) {
        if (!loggedKeys.add(key)) return
        if (loggedKeys.size > 512) loggedKeys.clear()
        Lg.i(TAG, msg)
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
