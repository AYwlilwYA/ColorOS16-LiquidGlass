package com.coloros16.liquidglass.hook

import android.graphics.RenderEffect
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.entry.XposedEntry
import com.coloros16.liquidglass.glass.GlassRegistry
import com.coloros16.liquidglass.log.Lg
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

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
 * （组件清单出现该组件、预设可按组件命中）。**渲染腿尚未接通**：自研玻璃 shader 需要进程内背景源，
 * 而第三方进程没有抓屏权限（`IWindowManager.captureDisplay` 需 READ_FRAME_BUFFER），故当前只做
 * 组件登记与诊断，背景仍由系统模糊渲染。
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

    /** 目标包 → 玻璃开关键。 */
    private val SWITCH_KEYS = mapOf(
        XposedEntry.PACKAGE_GALLERY to Prefs.KEY_GALLERY_GLASS,
        XposedEntry.PACKAGE_CALCULATOR to Prefs.KEY_CALCULATOR_GLASS,
        XposedEntry.PACKAGE_SMART_SIDEBAR to Prefs.KEY_SMART_SIDEBAR_GLASS,
    )

    private val SWITCH_DEFAULTS = mapOf(
        Prefs.KEY_GALLERY_GLASS to Prefs.DEFAULT_GALLERY_GLASS,
        Prefs.KEY_CALCULATOR_GLASS to Prefs.DEFAULT_CALCULATOR_GLASS,
        Prefs.KEY_SMART_SIDEBAR_GLASS to Prefs.DEFAULT_SMART_SIDEBAR_GLASS,
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

    private var aFamilyMounted = false

    private var bFamilyMounted = false

    /** A/B 族命中类名是否已打过日志（只打一次，防刷屏）。 */
    private val loggedClasses = ConcurrentHashMap.newKeySet<String>()

    /** 已登记过的元素（identityHashCode），防重复 bind 日志刷屏。 */
    private val boundElements = ConcurrentHashMap.newKeySet<Int>()

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
                .intercept { chain ->
                    try {
                        if (glassEnabled()) {
                            val view = try {
                                chain.getArg(1) as? View
                            } catch (t: Throwable) {
                                null
                            }
                            if (view != null) registerElement(view, view.javaClass.name, "A")
                        }
                    } catch (t: Throwable) {
                        Lg.e(TAG, "A-family intercept error", t)
                    }
                    chain.proceed()
                }
            aFamilyMounted = true
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
                .intercept { chain ->
                    try {
                        if (glassEnabled()) {
                            val bg = try {
                                chain.getArg(0) as? Drawable
                            } catch (t: Throwable) {
                                null
                            }
                            val view = chain.getThisObject() as? View
                            if (view != null && bg != null &&
                                bg.javaClass.name == CLASS_BACKGROUND_BLUR_DRAWABLE
                            ) {
                                registerElement(view, view.javaClass.name, "B")
                            }
                        }
                    } catch (t: Throwable) {
                        Lg.e(TAG, "B-family intercept error", t)
                    }
                    chain.proceed()
                }
            bFamilyMounted = true
            Lg.i(TAG, "mounted: android.view.View#setBackground(Drawable) (B-family $CLASS_BACKGROUND_BLUR_DRAWABLE)")
        } catch (t: Throwable) {
            Lg.e(TAG, "mount FAILED: android.view.View#setBackground", t)
        }
    }

    /** 把命中元素按 [viewClass] 登记进组件清单（view 与背景 drawable 双键，任一命中都能按组件取预设）。 */
    private fun registerElement(view: View, viewClass: String, family: String) {
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
