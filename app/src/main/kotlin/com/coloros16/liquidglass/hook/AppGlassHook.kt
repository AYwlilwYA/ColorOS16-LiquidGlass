package com.coloros16.liquidglass.hook

import android.graphics.Outline
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.entry.XposedEntry
import com.coloros16.liquidglass.glass.GlassRegistry
import com.coloros16.liquidglass.log.Lg
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.reflect.Method
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.WeakHashMap

/**
 * 第三方 App 进程玻璃化接入（相册 / 计算器 / 智能侧边栏）。
 *
 * 这些 App 的模糊不走 `com.oplus.posteffect` 的 drawBlurShader 漏斗，收敛为两族：
 * - **A 族**：`com.oplus.view.OplusViewBackgroundRenderEffect.setBackgroundRenderEffect`
 *   （视图背景 RenderEffect，相册照片页 / 按钮等）；
 * - **B 族**：framework `BackgroundBlurDrawable` 设为 View 背景（窗口级模糊，`draw()` 空实现，
 *   计算器对话框 / 侧边栏面板等）。
 *
 * 本 hook 在目标进程内拦截这两族的挂载点，把命中的元素按 **View 类名** 登记进 [GlassRegistry]
 * （组件清单出现该组件、预设可按组件命中）。渲染腿现状：
 * - A 族：相册 BlurButtonWrapperView 五钮直接施加背板玻璃（[AppGlassBackdropShader]，
 *   input=节点下方已绘内容，每帧 GPU 自动喂入；[Prefs.KEY_GALLERY_GLASS] 控，探针开时让位）；
 *   shader input 覆盖范围探针（[Prefs.KEY_GALLERY_SHADER_PROBE]，默认关）保留——
 *   把背景 RenderEffect 临时换成越界采样染色探针（[AppGlassProbeShader]），判定折射环带可行性；
 *   自调用挂载点（[mountSelfProbeHook]）同时调度探针与五钮玻璃；
 * - B 族：边缘光学装饰层（高光描边 + 内发光，[AppGlassDecorDrawable]）——随该 App 玻璃开关
 *   生效，经 View.overlay 覆盖绘制，系统 SF 模糊保持原样当底；五钮玻璃编译失败时同层兜底。
 *
 * 开关按 App 独立（默认 false，TTL 500ms 重读）：关闭时拦截体只读 volatile 后直接放行。
 */
object AppGlassHook {

    private const val TAG = "AppGlass"

    /** 开关重读间隔（ms）：改动无需重启目标 App。 */
    private const val SWITCH_TTL_MS = 500L

    /** A 族挂载点：oplus framework 视图背景 RenderEffect（静态方法，arg1 = 目标 View）。 */
    private const val CLASS_VIEW_BG_RENDER_EFFECT = "com.oplus.view.OplusViewBackgroundRenderEffect"
    private const val METHOD_SET_BACKGROUND_RENDER_EFFECT = "setBackgroundRenderEffect"

    /** B 族挂载点：framework 窗口背景模糊 drawable（本体 draw 空实现，合成层处理）。 */
    private const val CLASS_BACKGROUND_BLUR_DRAWABLE = "com.android.internal.graphics.drawable.BackgroundBlurDrawable"

    /** 相册按钮容器（图集页五类纯色钮共用类）：探针自调用挂载目标。 */
    private const val CLASS_BLUR_BUTTON_WRAPPER = "com.oplus.gallery.basebiz.widget.BlurButtonWrapperView"

    /** 目标包 → 玻璃开关键。 */
    private val SWITCH_KEYS = mapOf(
        XposedEntry.PACKAGE_GALLERY to Prefs.KEY_GALLERY_GLASS,
        XposedEntry.PACKAGE_CALCULATOR to Prefs.KEY_CALCULATOR_GLASS,
        XposedEntry.PACKAGE_SMART_SIDEBAR to Prefs.KEY_SMART_SIDEBAR_GLASS,
        XposedEntry.PACKAGE_ASSISTANT_SCREEN to Prefs.KEY_ASSISTANT_SCREEN_GLASS,
        XposedEntry.PACKAGE_QUICK_SEARCH_BOX to Prefs.KEY_QUICK_SEARCH_GLASS,
    )

    private val SWITCH_DEFAULTS = mapOf(
        Prefs.KEY_GALLERY_GLASS to Prefs.DEFAULT_GALLERY_GLASS,
        Prefs.KEY_CALCULATOR_GLASS to Prefs.DEFAULT_CALCULATOR_GLASS,
        Prefs.KEY_SMART_SIDEBAR_GLASS to Prefs.DEFAULT_SMART_SIDEBAR_GLASS,
        Prefs.KEY_ASSISTANT_SCREEN_GLASS to Prefs.DEFAULT_ASSISTANT_SCREEN_GLASS,
        Prefs.KEY_QUICK_SEARCH_GLASS to Prefs.DEFAULT_QUICK_SEARCH_GLASS,
    )

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
    private var cachedProbe = false

    @Volatile
    private var cachedProbeAtMs = 0L

    @Volatile
    private var probeLogged = false

    private var aFamilyMounted = false

    private var bFamilyMounted = false

    private var selfProbeMounted = false

    /** 自调用 setBackgroundRenderEffect 的反射句柄（install 期解析，调度路径直用）。 */
    @Volatile
    private var selfProbeSetBg: Method? = null

    /** 自调用进行中标记：旁路自家 A 族拦截，防重入。 */
    @Volatile
    private var selfProbeActive = false

    /** 探针效果按 w×h 编译缓存（RuntimeShader 编译昂贵，同尺寸效果对象复用）。 */
    private val probeEffectCache = ConcurrentHashMap<String, RenderEffect>()

    /** 已挂 preDraw 等布局的 View → 监听器（防尺寸变化回调重复调度叠监听）。 */
    private val selfProbeScheduled = WeakHashMap<View, ViewTreeObserver.OnPreDrawListener>()

    /** A 族拦截记录的宿主原 effect（view → 宿主设过的值；玻璃还原依据，原值无公开 getter）。 */
    private val lastHostEffect = WeakHashMap<View, RenderEffect?>()

    /** 五钮背板玻璃状态（持续 preDraw 同步启停，模式同 [BDecorState]）。 */
    private class ButtonGlassState(view: View) {
        val viewRef = WeakReference(view)
        var applied = false
        var compileFailed = false
        val listener = ViewTreeObserver.OnPreDrawListener {
            viewRef.get()?.let { syncButtonGlass(it) }
            true
        }
    }

    private val buttonGlassStates = WeakHashMap<View, ButtonGlassState>()

    /** 背板玻璃效果 w×h×corner 编译缓存（RuntimeShader 编译昂贵，同参数效果对象复用）。 */
    private val glassEffectCache = ConcurrentHashMap<String, RenderEffect>()

    /** A/B 族命中类名是否已打过日志（只打一次，防刷屏）。 */
    private val loggedClasses = ConcurrentHashMap.newKeySet<String>()

    /** 已登记过的元素（identityHashCode），防重复 bind 日志刷屏。 */
    private val boundElements = ConcurrentHashMap.newKeySet<Int>()

    /** B 族装饰层状态（View 弱引用键：值不得强引用键，否则 WeakHashMap 失效）。 */
    private class BDecorState(view: View) {
        val viewRef = WeakReference(view)
        val drawable = AppGlassDecorDrawable(view.resources.displayMetrics.density)
        var overlayAdded = false
        var lastW = 0
        var lastH = 0
        val listener = ViewTreeObserver.OnPreDrawListener {
            viewRef.get()?.let { syncBDecor(it) }
            true
        }
    }

    private val bDecorStates = WeakHashMap<View, BDecorState>()

    fun install(api: XposedInterface, classLoader: ClassLoader, packageName: String) {
        this.api = api
        val key = SWITCH_KEYS[packageName] ?: run {
            Lg.w(TAG, "unknown package $packageName, skip install")
            return
        }
        switchKey = key
        switchDefault = SWITCH_DEFAULTS[key] ?: false
        Lg.i(TAG, "installing app glass hook into $packageName (switch=$key default=$switchDefault)")
        mountBackgroundRenderEffectHook(api, classLoader)
        mountViewSetBackgroundHook(api)
        if (switchKey == Prefs.KEY_GALLERY_GLASS) {
            mountSelfProbeHook(api, classLoader)
            Lg.i(TAG, "probe switch read=${probeEnabled()}")
        }
        AppPageGlassMounter.install(api, classLoader, packageName)
    }

    /** A 族：hook 视图背景 RenderEffect 挂载点，命中即按 View 类登记组件。 */
    private fun mountBackgroundRenderEffectHook(api: XposedInterface, classLoader: ClassLoader) {
        if (aFamilyMounted) return
        val clazz = try {
            hostForName(CLASS_VIEW_BG_RENDER_EFFECT, classLoader)
        } catch (t: Throwable) {
            Lg.i(TAG, "$CLASS_VIEW_BG_RENDER_EFFECT unavailable in this process, A-family skipped")
            return
        }
        try {
            val method: Method = clazz.getMethod(
                METHOD_SET_BACKGROUND_RENDER_EFFECT, RenderEffect::class.java, View::class.java,
            )
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain -> aFamilyIntercept(chain) }
            aFamilyMounted = true
            selfProbeSetBg = method
            Lg.i(TAG, "mounted: $CLASS_VIEW_BG_RENDER_EFFECT#$METHOD_SET_BACKGROUND_RENDER_EFFECT(RenderEffect, View) (A-family)")
        } catch (t: Throwable) {
            Lg.e(TAG, "mount FAILED: $CLASS_VIEW_BG_RENDER_EFFECT#$METHOD_SET_BACKGROUND_RENDER_EFFECT", t)
        }
    }

    /** B 族：hook View.setBackground，背景是 BackgroundBlurDrawable 时按 View 类登记组件。 */
    private fun mountViewSetBackgroundHook(api: XposedInterface) {
        if (bFamilyMounted) return
        try {
            val method = View::class.java
                .getDeclaredMethod("setBackground", Drawable::class.java)
                .apply { isAccessible = true }
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain -> bFamilyIntercept(chain) }
            bFamilyMounted = true
            Lg.i(TAG, "mounted: android.view.View#setBackground(Drawable) (B-family $CLASS_BACKGROUND_BLUR_DRAWABLE)")
        } catch (t: Throwable) {
            Lg.e(TAG, "mount FAILED: android.view.View#setBackground", t)
        }
    }

    /**
     * 相册专属探针自调用挂载：BlurButtonWrapperView attach/尺寸就绪后由模块自己调
     * `setBackgroundRenderEffect` 换探针，不依赖宿主那条双重门控的调用。
     * 只挂该类自身声明的生命周期方法或构造器，禁 hook 框架 View 基类。
     */
    private fun mountSelfProbeHook(api: XposedInterface, classLoader: ClassLoader) {
        if (selfProbeMounted) return
        val clazz = try {
            hostForName(CLASS_BLUR_BUTTON_WRAPPER, classLoader)
        } catch (t: Throwable) {
            Lg.w(TAG, "$CLASS_BLUR_BUTTON_WRAPPER unavailable, self-probe skipped")
            return
        }
        val setBg = try {
            hostForName(CLASS_VIEW_BG_RENDER_EFFECT, classLoader)
                .getMethod(METHOD_SET_BACKGROUND_RENDER_EFFECT, RenderEffect::class.java, View::class.java)
        } catch (t: Throwable) {
            Lg.w(TAG, "$CLASS_VIEW_BG_RENDER_EFFECT unavailable, self-probe skipped")
            return
        }
        selfProbeSetBg = setBg
        val ownMethods = clazz.declaredMethods
        val onAttach = ownMethods.firstOrNull {
            it.name == "onAttachedToWindow" && it.parameterTypes.isEmpty()
        }
        val onSize = ownMethods.firstOrNull {
            it.name == "onSizeChanged" &&
                it.parameterTypes.size == 4 &&
                it.parameterTypes.all { p -> p == Integer.TYPE }
        }
        val lifecycle = onAttach ?: onSize
        if (lifecycle != null) {
            try {
                lifecycle.isAccessible = true
                api.hook(lifecycle)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val result = chain.proceed()
                        try {
                            (chain.getThisObject() as? View)?.let {
                                scheduleSelfProbe(it)
                                ensureButtonGlass(it)
                            }
                        } catch (t: Throwable) {
                            Lg.w(TAG, "self-probe schedule error", t)
                        }
                        result
                    }
                selfProbeMounted = true
                Lg.i(TAG, "self-probe mounted on ${clazz.name}#${lifecycle.name} (declared)")
                return
            } catch (t: Throwable) {
                Lg.w(TAG, "self-probe lifecycle hook FAILED ${clazz.name}#${lifecycle.name}", t)
            }
        }
        var hooked = 0
        for (ctor in clazz.declaredConstructors) {
            try {
                ctor.isAccessible = true
                api.hook(ctor)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val result = chain.proceed()
                        try {
                            (chain.getThisObject() as? View)?.let { v ->
                                v.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                                    override fun onViewAttachedToWindow(attached: View) {
                                        attached.removeOnAttachStateChangeListener(this)
                                        scheduleSelfProbe(attached)
                                        ensureButtonGlass(attached)
                                    }

                                    override fun onViewDetachedFromWindow(detached: View) = Unit
                                })
                            }
                        } catch (t: Throwable) {
                            Lg.w(TAG, "self-probe attach listener error", t)
                        }
                        result
                    }
                hooked++
            } catch (t: Throwable) {
                Lg.w(TAG, "self-probe ctor hook FAILED ${clazz.name}", t)
            }
        }
        selfProbeMounted = hooked > 0
        Lg.i(TAG, "self-probe mounted on ${clazz.name} constructors x$hooked")
    }

    /** 调度自调用探针：挂一次性 preDraw 等首布局，尺寸有效且开关开时装探针。 */
    private fun scheduleSelfProbe(view: View) {
        if (selfProbeScheduled.containsKey(view)) return
        val observer = view.viewTreeObserver
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val current = view.viewTreeObserver
                if (!glassEnabled() || !probeEnabled()) {
                    removeSelfProbeListener(view, this, current, observer)
                    return true
                }
                if (view.width > 1 && view.height > 1) {
                    removeSelfProbeListener(view, this, current, observer)
                    applySelfProbe(view)
                }
                return true
            }
        }
        try {
            observer.addOnPreDrawListener(listener)
            selfProbeScheduled[view] = listener
        } catch (t: Throwable) {
            Lg.w(TAG, "self-probe preDraw add failed", t)
        }
    }

    private fun removeSelfProbeListener(
        view: View,
        listener: ViewTreeObserver.OnPreDrawListener,
        current: ViewTreeObserver,
        fallback: ViewTreeObserver,
    ) {
        selfProbeScheduled.remove(view)
        try {
            (if (current.isAlive) current else fallback).removeOnPreDrawListener(listener)
        } catch (_: Throwable) {
        }
    }

    /** 装探针：取缓存的探针效果，经静态挂载点写进 View 背景；自调用期间旁路 A 族拦截。 */
    private fun applySelfProbe(view: View) {
        val setBg = selfProbeSetBg ?: return
        val effect = cachedProbeEffect(view.width, view.height) ?: run {
            logOnce(
                "probe:size-skip:${view.javaClass.name}",
                "probe:size-skip view=${view.javaClass.name} ${view.width}x${view.height}",
            )
            return
        }
        selfProbeActive = true
        try {
            setBg.invoke(null, effect, view)
            logOnce(
                "probe:self-swap:${view.javaClass.name}",
                "self probe swapped view=${view.javaClass.name} ${view.width}x${view.height}",
            )
        } catch (t: Throwable) {
            logOnce("probe:self-error", "self probe invoke failed: ${t.message}")
        } finally {
            selfProbeActive = false
        }
    }

    /** 探针效果 w×h 编译缓存复用（每尺寸只编译一次 RuntimeShader）。 */
    private fun cachedProbeEffect(width: Int, height: Int): RenderEffect? {
        val key = "${width}x$height"
        probeEffectCache[key]?.let { return it }
        val effect = try {
            AppGlassProbeShader.createEffect(width, height)
        } catch (t: Throwable) {
            logOnce("probe:compile-error", "probe shader compile failed: ${t.message}")
            null
        }
        if (effect != null) {
            if (probeEffectCache.size >= 16) probeEffectCache.clear()
            probeEffectCache[key] = effect
        }
        return effect
    }

    /** 五钮背板玻璃挂载：首见建状态并挂持续 preDraw；重绑时换挂到当前 ViewTreeObserver。 */
    private fun ensureButtonGlass(view: View) {
        val existing = buttonGlassStates[view]
        if (existing != null) {
            try {
                view.viewTreeObserver.removeOnPreDrawListener(existing.listener)
            } catch (_: Throwable) {
            }
            try {
                view.viewTreeObserver.addOnPreDrawListener(existing.listener)
            } catch (t: Throwable) {
                Lg.w(TAG, "button glass preDraw re-add failed", t)
            }
            return
        }
        val state = ButtonGlassState(view)
        buttonGlassStates[view] = state
        try {
            view.viewTreeObserver.addOnPreDrawListener(state.listener)
        } catch (t: Throwable) {
            Lg.w(TAG, "button glass preDraw add failed", t)
        }
    }

    /**
     * preDraw 同步五钮背板玻璃：探针开 → 还原玻璃（探针优先，互斥）；玻璃关 → 还原宿主原 effect；
     * 玻璃开且尺寸就绪 → 施加背板玻璃。
     */
    private fun syncButtonGlass(view: View) {
        val state = buttonGlassStates[view] ?: return
        if (state.compileFailed) return
        if (probeEnabled()) {
            if (state.applied) restoreButtonGlass(view, state)
            return
        }
        if (!glassEnabled()) {
            if (state.applied) restoreButtonGlass(view, state)
            return
        }
        if (state.applied) return
        if (view.width > 1 && view.height > 1) {
            glassifyButton(view, state)
        }
    }

    /** 施加背板玻璃：取缓存效果经静态挂载点写进 View 背景；编译失败转装饰层兜底并停试。 */
    private fun glassifyButton(view: View, state: ButtonGlassState) {
        val setBg = selfProbeSetBg ?: return
        val corner = resolveButtonCornerRadius(view)
        val effect = cachedGlassEffect(view.width, view.height, corner)
        if (effect == null) {
            state.compileFailed = true
            Lg.w(TAG, "button glass compile failed, fallback decor view=${view.javaClass.name}")
            ensureBDecor(view)
            return
        }
        selfProbeActive = true
        try {
            setBg.invoke(null, effect, view)
            state.applied = true
            registerElement(view, view.javaClass.name, "G")
            logOnce(
                "glass:button:${view.javaClass.name}",
                "button glass applied view=${view.javaClass.name} ${view.width}x${view.height} corner=$corner",
            )
        } catch (t: Throwable) {
            logOnce("glass:button-error", "button glass invoke failed: ${t.message}")
        } finally {
            selfProbeActive = false
        }
    }

    /** 还原宿主原 effect（A 族拦截记录值，无记录 = null 清效果）。 */
    private fun restoreButtonGlass(view: View, state: ButtonGlassState) {
        val setBg = selfProbeSetBg ?: return
        val orig = lastHostEffect[view]
        selfProbeActive = true
        try {
            setBg.invoke(null, orig, view)
            state.applied = false
            logOnce(
                "glass:button-restore:${view.javaClass.name}",
                "button glass restored view=${view.javaClass.name} origEffect=${if (orig == null) "null" else "held"}",
            )
        } catch (t: Throwable) {
            logOnce("glass:button-restore-error", "button glass restore failed: ${t.message}")
        } finally {
            selfProbeActive = false
        }
    }

    /** 背板玻璃效果 w×h×corner 编译缓存（失败不缓存，由 [ButtonGlassState.compileFailed] 停试）。 */
    internal fun cachedGlassEffect(width: Int, height: Int, cornerRadius: Float): RenderEffect? {
        val key = "${width}x$height@${cornerRadius.toInt()}"
        glassEffectCache[key]?.let { return it }
        val effect = try {
            AppGlassBackdropShader.createEffect(width, height, cornerRadius, cornerIsConic = true)
        } catch (t: Throwable) {
            null
        }
        if (effect != null) {
            if (glassEffectCache.size >= 16) glassEffectCache.clear()
            glassEffectCache[key] = effect
        }
        return effect
    }

    /**
     * 五钮圆角解析：outline provider 优先（ColorOS 钮多为胶囊/圆钮）；
     * 兜底短边 ≤96px 取 min/2（胶囊），大元素取短边×0.3（防圆团）。
     */
    internal fun resolveButtonCornerRadius(view: View): Float {
        val w = view.width
        val h = view.height
        val short = minOf(w, h).toFloat()
        try {
            val outline = Outline()
            view.outlineProvider.getOutline(view, outline)
            val r = outline.radius
            if (r > 0f && r <= short) return r
        } catch (_: Throwable) {
        }
        return if (short <= 96f) short * 0.5f else short * 0.3f
    }

    /** A 族拦截体：登记元素；记档宿主原 effect；已玻璃化元素被覆写时换回玻璃；探针开关开时换探针。 */
    private fun aFamilyIntercept(chain: XposedInterface.Chain): Any? {
        try {
            if (selfProbeActive) return chain.proceed()
            val view = try {
                chain.getArg(1) as? View
            } catch (t: Throwable) {
                null
            }
            if (view != null) {
                // 宿主原 effect 记档：本调用经上方旁路 return 后必来自宿主，arg0 即还原值
                val orig = try {
                    chain.getArg(0) as? RenderEffect
                } catch (t: Throwable) {
                    null
                }
                lastHostEffect[view] = orig
                if (glassEnabled()) {
                    registerElement(view, view.javaClass.name, "A")
                    val state = buttonGlassStates[view]
                    if (state != null && state.applied && !state.compileFailed && !probeEnabled()) {
                        // 宿主覆写已玻璃化元素：换回玻璃（宿主新值已记档，还原时取它）
                        cachedGlassEffect(view.width, view.height, resolveButtonCornerRadius(view))
                            ?.let { glass ->
                                val args = chain.getArgs().toTypedArray()
                                args[0] = glass
                                logOnce(
                                    "glass:remount:${view.javaClass.name}",
                                    "button glass remount view=${view.javaClass.name}",
                                )
                                return chain.proceed(args)
                            }
                    }
                    if (switchKey == Prefs.KEY_GALLERY_GLASS && probeEnabled()) {
                        val probe = cachedProbeEffect(view.width, view.height)
                        if (probe != null) {
                            val args = chain.getArgs().toTypedArray()
                            args[0] = probe
                            logOnce(
                                "probe:swap:${view.javaClass.name}",
                                "A probe swapped view=${view.javaClass.name} ${view.width}x${view.height}",
                            )
                            return chain.proceed(args)
                        }
                        logOnce(
                            "probe:size-skip:${view.javaClass.name}",
                            "probe:size-skip view=${view.javaClass.name} ${view.width}x${view.height}",
                        )
                    }
                }
                // 整页玻璃元素被宿主覆写：换回挂载器玻璃（记档已在上方完成）
                if (AppPageGlassMounter.isApplied(view)) {
                    cachedGlassEffect(view.width, view.height, resolveButtonCornerRadius(view))
                        ?.let { glass ->
                            val args = chain.getArgs().toTypedArray()
                            args[0] = glass
                            logOnce(
                                "page-remount:${view.javaClass.name}",
                                "page glass remount view=${view.javaClass.name}",
                            )
                            return chain.proceed(args)
                        }
                }
            }
        } catch (t: Throwable) {
            Lg.e(TAG, "A-family intercept error", t)
        }
        return chain.proceed()
    }

    /** B 族拦截体：登记元素并确保边缘装饰层挂载（装饰随 App 玻璃开关启停，preDraw 同步）。 */
    private fun bFamilyIntercept(chain: XposedInterface.Chain): Any? {
        try {
            val view = chain.getThisObject() as? View
            // 背景变更 = 整页挂载器的 A 型候选信号（mounter 自查整页开关与扫描节流）
            if (view != null) AppPageGlassMounter.onHostBackgroundSet(view)
            if (glassEnabled()) {
                val bg = try {
                    chain.getArg(0) as? Drawable
                } catch (t: Throwable) {
                    null
                }
                if (view != null && bg != null &&
                    bg.javaClass.name == CLASS_BACKGROUND_BLUR_DRAWABLE
                ) {
                    registerElement(view, view.javaClass.name, "B")
                    ensureBDecor(view)
                }
            }
        } catch (t: Throwable) {
            Lg.e(TAG, "B-family intercept error", t)
        }
        return chain.proceed()
    }

    /** B 族装饰层挂载：首次建状态并挂 preDraw 监听；重绑时换挂到当前 ViewTreeObserver。 */
    internal fun ensureBDecor(view: View) {
        val existing = bDecorStates[view]
        if (existing != null) {
            try {
                view.viewTreeObserver.removeOnPreDrawListener(existing.listener)
            } catch (_: Throwable) {
            }
            try {
                view.viewTreeObserver.addOnPreDrawListener(existing.listener)
            } catch (t: Throwable) {
                Lg.w(TAG, "preDraw listener re-add failed", t)
            }
            return
        }
        val state = BDecorState(view)
        bDecorStates[view] = state
        try {
            view.viewTreeObserver.addOnPreDrawListener(state.listener)
        } catch (t: Throwable) {
            Lg.w(TAG, "preDraw listener add failed", t)
        }
    }

    /** preDraw 同步装饰层：开关开 → 补挂 overlay 并跟随尺寸；开关关 → 摘除。 */
    private fun syncBDecor(view: View) {
        val state = bDecorStates[view] ?: return
        if (!glassEnabled() && !AppPageGlassMounter.pageGlassEnabled()) {
            if (state.overlayAdded) {
                try {
                    view.overlay.remove(state.drawable)
                } catch (_: Throwable) {
                }
                state.overlayAdded = false
            }
            return
        }
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) return
        if (w != state.lastW || h != state.lastH) {
            state.lastW = w
            state.lastH = h
            state.drawable.bounds = Rect(0, 0, w, h)
        }
        if (!state.overlayAdded) {
            try {
                view.overlay.add(state.drawable)
                state.overlayAdded = true
                logOnce(
                    "b-decor:${view.javaClass.name}",
                    "B decor overlay added view=${view.javaClass.name} ${w}x${h}",
                )
            } catch (t: Throwable) {
                Lg.w(TAG, "overlay add failed", t)
            }
        }
    }

    /** 把命中元素按 [viewClass] 登记进组件清单（view 与背景 drawable 双键，任一命中都能按组件取预设）。 */
    internal fun registerElement(view: View, viewClass: String, family: String) {
        val viewKey = System.identityHashCode(view)
        if (boundElements.add(viewKey)) {
            if (boundElements.size > 512) boundElements.clear()
            GlassRegistry.bind(viewKey, view, viewClass)
            logOnce("view:$viewClass", "glass element [$family] view=$viewClass w=${view.width} h=${view.height}")
        }
        val bg = try {
            view.background
        } catch (t: Throwable) {
            null
        } ?: return
        val bgKey = System.identityHashCode(bg)
        if (boundElements.add(bgKey)) {
            if (boundElements.size > 512) boundElements.clear()
            GlassRegistry.bind(bgKey, bg, viewClass)
        }
    }

    /** A 族静态挂载点句柄（整页挂载器写 View 背景玻璃用；未解析时返回 null）。 */
    internal fun glassSetBgMethod(): Method? = selfProbeSetBg

    /** 经静态挂载点施加玻璃 effect（旁路 A 族拦截，防把玻璃自身记档成宿主原值）。 */
    internal fun applyGlassEffect(view: View, effect: RenderEffect): Boolean {
        val setBg = selfProbeSetBg ?: return false
        selfProbeActive = true
        return try {
            setBg.invoke(null, effect, view)
            true
        } catch (t: Throwable) {
            Lg.w(TAG, "applyGlassEffect failed view=${view.javaClass.name}", t)
            false
        } finally {
            selfProbeActive = false
        }
    }

    /** 还原宿主原 effect（A 族拦截记档值；无记录 = null 清效果）。 */
    internal fun restoreHostEffect(view: View): Boolean {
        val setBg = selfProbeSetBg ?: return false
        val orig = lastHostEffect[view]
        selfProbeActive = true
        return try {
            setBg.invoke(null, orig, view)
            true
        } catch (t: Throwable) {
            Lg.w(TAG, "restoreHostEffect failed view=${view.javaClass.name}", t)
            false
        } finally {
            selfProbeActive = false
        }
    }

    /** 玻璃开关（TTL [SWITCH_TTL_MS] 重读，改动无需重启目标 App）。 */
    private fun glassEnabled(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - cachedSwitchAtMs > SWITCH_TTL_MS) {
            cachedSwitchAtMs = now
            val before = cachedSwitch
            cachedSwitch = try {
                api?.let { Prefs.read(it).getBoolean(switchKey, switchDefault) } ?: false
            } catch (t: Throwable) {
                Lg.w(TAG, "prefs read $switchKey failed, default false", t)
                false
            }
            val shouldLog = cachedSwitch != before || !switchLogged
            switchLogged = true
            if (shouldLog) Lg.i(TAG, "switch $switchKey = $cachedSwitch")
        }
        return cachedSwitch
    }

    /** 探针开关（TTL [SWITCH_TTL_MS] 重读，语义同 [glassEnabled]）。 */
    private fun probeEnabled(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - cachedProbeAtMs > SWITCH_TTL_MS) {
            cachedProbeAtMs = now
            val before = cachedProbe
            cachedProbe = try {
                api?.let {
                    Prefs.read(it).getBoolean(Prefs.KEY_GALLERY_SHADER_PROBE, Prefs.DEFAULT_GALLERY_SHADER_PROBE)
                } ?: false
            } catch (t: Throwable) {
                Lg.w(TAG, "prefs read ${Prefs.KEY_GALLERY_SHADER_PROBE} failed, default false", t)
                false
            }
            val shouldLog = cachedProbe != before || !probeLogged
            probeLogged = true
            if (shouldLog) Lg.i(TAG, "switch ${Prefs.KEY_GALLERY_SHADER_PROBE} = $cachedProbe")
        }
        return cachedProbe
    }

    /** 同类日志只打一次（挂载期/首命中诊断，防高频拦截刷屏）。 */
    private fun logOnce(key: String, msg: String) {
        if (!loggedClasses.add(key)) return
        Lg.i(TAG, msg)
    }

    // 宿主 ClassLoader 加载 hide/宿主类：模块类做 caller 会按模块签名判 hidden API 名单被拦成 CNFE
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
