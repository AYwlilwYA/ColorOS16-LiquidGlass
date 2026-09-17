package com.coloros16.liquidglass.hook

import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Bundle
import android.text.Layout
import android.util.Log
import android.view.View
import android.view.ViewParent
import android.widget.TextView
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.liquidglass.LiquidGlassShader
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.util.WeakHashMap

/**
 * 锁屏大时钟液态玻璃化（doc/spec/43 → doc/spec/80 / spec/81）。
 *
 * ## 绘制链（反编译实证）
 *
 * 锁屏大时钟**不走** posteffect drawBlurShader 链，而是独立位图投递链：
 * `KeyguardWallpaperDeliveryController` → `BlurBitmapFactory` → `sendBlurBitmapToClock`
 * → `ThemePlugin.setWallpaperBitmapInternal(Bitmap,String,Bundle,boolean,boolean)` → 时钟插件。
 * 数字文字视图 = `MyCustomizedTextView`（`com.oplus.keyguard.clock.big.widget`，每数字一个 TextView）。
 *
 * ## 接管入口（spec/80 §二，踩坑纠正）
 *
 * ⚠️ **不能 hook `TextView.onDraw`**：类链
 * `MyCustomizedTextView → CustomizedTextView → OplusHDRTextView → COUITextView → TextView`
 * 中 `OplusHDRTextView.onDraw` 是 **final override**，会挡住基类 hook → 永不触发。
 * `Layout.draw` 同理被 `DynamicLayout`/`BoringLayout` 挡住。
 *
 * **唯一有效入口 = `View.draw`** —— `MyCustomizedTextView.draw` 的两条绘制分支
 * （`GeneralElementSwitchHelper.d(canvas)` 的 canvas.scale 缩放态 / 非缩放态）最终都调
 * `super.draw(canvas)` → `TextView.draw` → `View.draw`，且是**同一个 canvas**，
 * 故缩放动画天然作用到我们画的内容上。
 *
 * ## 字形玻璃的实现路线（spec/80 §六点五 → spec/81）
 *
 * ⛔ **不能把 shader 挂到 text paint 上**（已实测卡死）。原因已查实：HDR span
 * `a4.a.updateDrawState`（插件反编译，`OplusHDRTextView` 在 `f6439c=true` 时给文本加）
 * 只做三件事：`setShader(null)` + `setColorFilter(null)` + `setColor(getCurrentTextColor())`；
 * 而 `Layout.drawText` 会把 `mPaint` **复制到工作副本 `mWorkPaint`** 再交给 `TextLine`，
 * span 改的是那份副本 → 挂在我们 paint 上的 shader 必被清掉，且我们无法从外侧拦截。
 *
 * ✅ **本实现改为「字形轮廓 Path + drawPath + 玻璃 shader」**：
 * 用 `Paint.getTextPath` 把字形取成矢量轮廓（纯几何，与 span / 颜色 / shader 完全无关），
 * 再用**我们自己的 Paint** 承载 RuntimeShader 画 `drawPath`。全程不碰 text paint ⇒
 * 既躲开 HDR span，也躲开「画矩形有效、画字形 0.5s 失效」那条失效路径；
 * 且 `drawPath + shader` 是本项目**已验证可用**的 op 类别（其余玻璃元素同款）。
 *
 * 字形轮廓的排版参数（typeface / textSize / letterSpacing / fontFeatureSettings /
 * fontVariationSettings）逐项从 `layout.paint` 复制，保证与系统排版一致。
 * 定位：`dx = compoundPaddingLeft`，`dy = tv.baseline - layout.getLineBaseline(0)`
 * （= `getExtendedPaddingTop() + getVerticalOffset(true)`，即系统 `TextView.onDraw` 的平移量，
 * 见 AOSP `TextView.getBaseline()`）；行内原点 x 用 `layout.getLineLeft(i)`，
 * 与 `Layout.drawText` 内部算法（SDK34 `Layout.java:712-737`，ALIGN_CENTER 取整差异 ≤1px）一致。
 *
 * ## 配置
 * - `KEY_LOCKSCREEN_CLOCK_GLASS`（默认 false）：总开关，改动需重启 SystemUI 生效。
 * - `KEY_LOCKSCREEN_CLOCK_BLUR`（默认 6）：数字内部轻模糊半径，0=清晰透镜。
 *
 * ## 调试日志
 * 统一 tag `LiquidGlass`，前缀 `lockscreen-clock:`，由 `Prefs.KEY_ENABLE_LOGS` 门控（默认关）。
 *
 * ## ⛔ 停用原因（spec/81 决定性实证，2026-09-17）
 *
 * 本路径**已实测未生效**：把 [selfDrawClockDigit] 的字形改成不透明品红做二分 ——
 * 真机锁屏数字**仍是系统原样的浅灰渐变**，品红从未出现。
 * ⇒ **`View.draw` hook 根本没命中时钟数字 View**，问题不在渲染层，而在接管本身。
 * 这同时推翻了 spec/80「跳过系统绘制有效且持久」那条旧实证在新代码下的适用性。
 *
 * 故 `XposedEntry.installHooks` 里 `LockScreenClockHook.install(...)` **保持注释停用**，
 * 设置页对应的「锁屏大时钟液态玻璃」入口已一并移除（spec/81）。
 * 要重开需先查清 hook 为何未命中 —— 优先核对 [isClockDigitView] 的宿主类名常量
 * 与真机实际 View 链是否一致。
 */
object LockScreenClockHook {

    private const val TAG = "LiquidGlass"

    // ---- 目标类（真机反编译实证）----
    /** 时钟主题插件宿主，接收 KeyguardWallpaperDeliveryController 投递的壁纸位图。 */
    private const val CLASS_THEME_PLUGIN = "com.oplus.keyguard.plugin.ThemePlugin"
    private const val METHOD_SET_WALLPAPER_BITMAP_INTERNAL = "setWallpaperBitmapInternal"
    /** 锁屏时钟宿主 View（插件数字 View 的祖先）。 */
    private const val CLOCK_HOST_NAME = "com.oplus.systemui.keyguard.view.CustomOplusKeyguardStyleClock"
    private const val CLOCK_HOST_BASE_NAME = "com.oplus.keyguard.OplusKeyguardStyleClock"
    /** AOD 时钟宿主 View（锁屏 + AOD 双场景覆盖）。 */
    private const val CLOCK_HOST_AOD_NAME = "com.oplus.systemui.aod.aodclock.off.AodClockLayout"

    // ---- 状态 ----
    @Volatile
    private var enabled = false
    /** 时钟壁纸投递位图的**自有副本**（玻璃源兜底）。
     *  ⚠️ 必须 copy：系统投递的位图随时可能被回收，直接引用会踩
     *  `project_launcher-blur-bitmap-invalid`（位图失效 → 绘制静默失效）。 */
    @Volatile
    private var wallpaperBitmap: Bitmap? = null
    /** 屏幕尺寸（屏幕坐标 → 壁纸位图坐标折算用）。 */
    @Volatile
    private var screenWidth = 1080
    @Volatile
    private var screenHeight = 2400
    /** 原始壁纸（`getWallpaperFile` 解码的清晰版，优先于投递位图；模块自有，不会被回收）。 */
    @Volatile
    private var originalWallpaper: Bitmap? = null
    @Volatile
    private var originalWallpaperId = -1
    /** 壁纸滚动 offset，[0,1]（锁屏不自滚动，解码后从系统读一次）。 */
    @Volatile
    private var wallpaperOffsetX = 0f
    @Volatile
    private var wallpaperOffsetY = 0f
    @Volatile
    private var wallpaperOffsetsSynced = false

    /** 已判定「是否锁屏时钟数字 View」的缓存（防每帧走 parent 链）。 */
    private val clockDigitCache = WeakHashMap<TextView, Boolean>()
    /** 每数字 View 的玻璃 shader / 字形轮廓状态（独立实例防 uniform 串扰）。 */
    private val digitStates = WeakHashMap<TextView, DigitGlassState>()

    private var api: XposedInterface? = null

    /** `View.mRenderEffect` / `View.mRenderNode` 反射句柄（清 View 级 RenderEffect 用）。 */
    @Volatile
    private var mRenderEffectField: java.lang.reflect.Field? = null
    @Volatile
    private var mRenderEffectFieldResolved = false
    @Volatile
    private var mRenderNodeField: java.lang.reflect.Field? = null
    @Volatile
    private var mRenderNodeFieldResolved = false
    @Volatile
    private var mGetRenderNodeMethod: java.lang.reflect.Method? = null
    @Volatile
    private var mGetRenderNodeResolved = false

    /** 每数字 View 的玻璃绘制状态。 */
    private class DigitGlassState {
        /** 液态玻璃 RuntimeShader（挂在我们自己的 [glyphPaint] 上，与 text paint 无关）。 */
        val shader: RuntimeShader = LiquidGlassShader.create()
        /** 承载 shader 的自有 Paint（绘图属性每次重排时从 `layout.paint` 同步一次）。 */
        val glyphPaint: Paint = Paint()
        /** 字形矢量轮廓（文本/尺寸不变时复用）。 */
        val glyphPath: Path = Path()
        /** 轮廓缓存键（文本 + 尺寸 + 行左/基线）。 */
        var glyphKey: String? = null
        var sourceToken = -1
        var source: Shader? = null
        var viewport = RectF()
        var srcRect = RectF()
        /** uniform 是否已按 (viewport, srcRect) 设置过——壁纸源瞬时不可用时复用上次结果，避免回退系统白字。 */
        var ready = false
    }

    /** 壁纸「贴屏 cover」映射：把屏幕坐标折算回位图坐标。 */
    private class WallpaperMapping(val scale: Float, val cropX: Float, val cropY: Float)

    /** SystemUI 进程安装（XposedEntry.installHooks 调用）。 */
    fun install(api: XposedInterface, classLoader: ClassLoader) {
        this.api = api
        enabled = try {
            Prefs.read(api).getBoolean(Prefs.KEY_LOCKSCREEN_CLOCK_GLASS, Prefs.DEFAULT_LOCKSCREEN_CLOCK_GLASS)
        } catch (t: Throwable) {
            Prefs.DEFAULT_LOCKSCREEN_CLOCK_GLASS
        }
        if (!enabled) {
            Log.i(TAG, "lockscreen-clock-glass disabled by prefs, hooks skipped")
            return
        }
        val dm = android.content.res.Resources.getSystem().displayMetrics
        screenWidth = dm.widthPixels.coerceAtLeast(1)
        screenHeight = dm.heightPixels.coerceAtLeast(1)
        mountSetWallpaperBitmap(api, classLoader)
        mountRenderEffectBlocker(api, classLoader)
        mountViewDraw(api, classLoader)
        Log.i(TAG, "lockscreen-clock-glass mounted (wallpaper source + RenderEffect blocker + View.draw)")
    }

    // ------------------------------------------------------------ hook 挂载

    /**
     * 拦截时钟壁纸位图投递（`ThemePlugin.setWallpaperBitmapInternal`）：**复制一份自有副本**
     * 存为玻璃源，并 invalidate 已登记的时钟数字 View。
     */
    private fun mountSetWallpaperBitmap(api: XposedInterface, classLoader: ClassLoader) {
        try {
            val clazz = Class.forName(CLASS_THEME_PLUGIN, false, classLoader)
            val method = clazz.getMethod(
                METHOD_SET_WALLPAPER_BITMAP_INTERNAL,
                Bitmap::class.java, String::class.java, Bundle::class.java,
                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType
            )
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        val bmp = chain.getArg(0) as? Bitmap
                        // 必须 copy：系统位图随时可能被回收（见 project_launcher-blur-bitmap-invalid）
                        val mine = bmp?.let { adoptWallpaper(it) }
                        if (mine != null) {
                            if (moduleLogEnabled()) {
                                Log.i(TAG, "lockscreen-clock: wallpaper bitmap adopted ${mine.width}x${mine.height}")
                            }
                            invalidateClockDigits()
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "lockscreen-clock: setWallpaperBitmapInternal intercept error", t)
                    }
                    chain.proceed()
                }
            Log.i(TAG, "lockscreen-clock-glass mounted: ThemePlugin#setWallpaperBitmapInternal")
        } catch (t: Throwable) {
            Log.e(TAG, "lockscreen-clock-glass mount FAILED: ThemePlugin#setWallpaperBitmapInternal", t)
        }
    }

    /**
     * 阻断系统给时钟数字 View 挂 RenderEffect（blur 后处理会把玻璃字形再糊一遍）。
     * 两条路径：① `View.setRenderEffect` ② RenderThread 异步路径
     * `com.oplus.animation.OplusAsyncAnimatorUtils.setRenderEffect(View, RenderEffect)`。
     * 命中时钟数字 View 且 effect 非 null → 参数换 null 后 proceed；其余一律放行（严格 scoped）。
     */
    private fun mountRenderEffectBlocker(api: XposedInterface, classLoader: ClassLoader) {
        try {
            val method = View::class.java.getDeclaredMethod(
                "setRenderEffect", android.graphics.RenderEffect::class.java
            )
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        val view = chain.getThisObject() as? TextView
                        val effect = chain.getArg(0) as? android.graphics.RenderEffect
                        if (view != null && effect != null && isClockDigitView(view)) {
                            if (moduleLogEnabled()) {
                                Log.i(TAG, "lockscreen-clock: BLOCKED View.setRenderEffect on clock digit ${view.javaClass.name}")
                            }
                            val args = chain.getArgs().toTypedArray()
                            args[0] = null
                            return@intercept chain.proceed(args)
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "lockscreen-clock: View.setRenderEffect intercept error", t)
                    }
                    chain.proceed()
                }
            Log.i(TAG, "lockscreen-clock-glass mounted: View#setRenderEffect blocker")
        } catch (t: Throwable) {
            Log.e(TAG, "lockscreen-clock-glass mount FAILED: View#setRenderEffect blocker", t)
        }

        try {
            val clazz = Class.forName("com.oplus.animation.OplusAsyncAnimatorUtils", false, classLoader)
            val method = clazz.getDeclaredMethod(
                "setRenderEffect", View::class.java, android.graphics.RenderEffect::class.java
            )
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        val view = chain.getArg(0) as? TextView
                        val effect = chain.getArg(1) as? android.graphics.RenderEffect
                        if (view != null && effect != null && isClockDigitView(view)) {
                            if (moduleLogEnabled()) {
                                Log.i(TAG, "lockscreen-clock: BLOCKED OplusAsyncAnimatorUtils.setRenderEffect on clock digit")
                            }
                            val args = chain.getArgs().toTypedArray()
                            args[1] = null
                            return@intercept chain.proceed(args)
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "lockscreen-clock: OplusAsyncAnimatorUtils.setRenderEffect intercept error", t)
                    }
                    chain.proceed()
                }
            Log.i(TAG, "lockscreen-clock-glass mounted: OplusAsyncAnimatorUtils#setRenderEffect blocker")
        } catch (t: Throwable) {
            Log.i(TAG, "lockscreen-clock-glass: OplusAsyncAnimatorUtils 不可用（该路径不生效，其余路径兜底）")
        }
    }

    /**
     * 接管绘制：`View.draw` 命中时钟数字 View → 跳过系统绘制（白色字形不画）→ 自绘玻璃字形。
     * 自绘前置条件不满足（无壁纸源 / 无 Layout / 取不到轮廓）时**回退系统绘制**
     * （宁可见白字，不可见空白）。
     * scoped：仅时钟数字 View 接管，SystemUI 其他 View 一律放行。
     */
    private fun mountViewDraw(api: XposedInterface, classLoader: ClassLoader) {
        try {
            val clazz = Class.forName("android.view.View", false, classLoader)
            val method = clazz.getDeclaredMethod("draw", Canvas::class.java)
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        val tv = chain.getThisObject() as? TextView
                        if (tv != null && isClockDigitView(tv)) {
                            clearClockRenderEffect(tv)
                            val canvas = chain.getArg(0) as? Canvas
                            if (canvas != null) {
                                val state = digitStates.getOrPut(tv) { DigitGlassState() }
                                if (ensureGlass(tv, state) && selfDrawClockDigit(tv, canvas, state)) {
                                    return@intercept null
                                }
                                if (moduleLogEnabled()) {
                                    Log.i(TAG, "lockscreen-clock: self-draw skipped, fallback to system (ready=${state.ready})")
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "lockscreen-clock: View.draw intercept error", t)
                    }
                    chain.proceed()
                }
            Log.i(TAG, "lockscreen-clock-glass mounted: View#draw")
        } catch (t: Throwable) {
            Log.e(TAG, "lockscreen-clock-glass mount FAILED: View#draw", t)
        }
    }

    // ------------------------------------------------------------ 绘制

    /**
     * 用**字形矢量轮廓** + 自有 Paint 画玻璃字形。返回 true = 画成功（调用方跳过系统绘制）。
     *
     * ✅ 全程不碰 `layout.paint` —— 这是上一版（把 shader 挂 text paint）失效的根因：
     * HDR span 只在 `TextLine` 的工作副本上 `setShader(null)`，我们拦不到也保不住。
     * `drawPath + shader` 则是本项目已验证可用的 op。
     */
    private fun selfDrawClockDigit(tv: TextView, canvas: Canvas, state: DigitGlassState): Boolean {
        val layout = tv.layout ?: return false
        if (layout.lineCount <= 0) return false
        if (!ensureGlyphPath(tv, layout, state)) return false
        val dx = tv.compoundPaddingLeft.toFloat()
        val dy = (tv.baseline - layout.getLineBaseline(0)).toFloat()
        val save = canvas.save()
        try {
            canvas.translate(dx, dy)
            state.glyphPaint.shader = state.shader
            canvas.drawPath(state.glyphPath, state.glyphPaint)
        } finally {
            state.glyphPaint.shader = null
            canvas.restoreToCount(save)
        }
        return true
    }

    /**
     * 取字形轮廓（缓存：文本 / 尺寸 / 行左 / 基线不变则复用）。
     * 排版属性一次性从 `layout.paint` 复制到自有 [DigitGlassState.glyphPaint]，之后只更新 shader。
     */
    private fun ensureGlyphPath(tv: TextView, layout: Layout, state: DigitGlassState): Boolean {
        val text = tv.text ?: return false
        if (text.isEmpty()) return false
        // Paint.getTextPath 只接受 String / char[]（无 CharSequence 重载）
        val str = text.toString()
        val key = buildString {
            append(str)
            append('|').append(tv.width).append('x').append(tv.height)
            for (i in 0 until layout.lineCount) {
                append('|').append(layout.getLineLeft(i)).append(',').append(layout.getLineBaseline(i))
            }
        }
        if (key == state.glyphKey && !state.glyphPath.isEmpty) return true
        syncGlyphPaint(state.glyphPaint, layout.paint)
        val path = state.glyphPath
        path.reset()
        for (i in 0 until layout.lineCount) {
            val start = layout.getLineStart(i)
            val end = layout.getLineEnd(i)
            if (end <= start) continue
            state.glyphPaint.getTextPath(
                str, start, end,
                layout.getLineLeft(i), layout.getLineBaseline(i).toFloat(), path,
            )
        }
        if (path.isEmpty) {
            state.glyphKey = null
            return false
        }
        state.glyphKey = key
        return true
    }

    /**
     * 排版属性同步：从系统 paint 复制**只影响字形形状/位置**的参数，
     * 颜色 / shader / colorFilter / 下划线等一律重置（轮廓只需几何）。
     */
    private fun syncGlyphPaint(dst: Paint, src: Paint) {
        dst.reset()
        dst.typeface = src.typeface
        dst.textSize = src.textSize
        dst.textScaleX = src.textScaleX
        dst.textSkewX = src.textSkewX
        dst.letterSpacing = src.letterSpacing
        dst.fontFeatureSettings = src.fontFeatureSettings
        dst.fontVariationSettings = src.fontVariationSettings
        dst.textLocale = src.textLocale
        dst.isAntiAlias = true
        dst.isSubpixelText = false
        dst.style = Paint.Style.FILL
        dst.color = Color.WHITE
        dst.shader = null
        dst.colorFilter = null
        dst.xfermode = null
    }

    /**
     * 为时钟数字 View 准备液态玻璃 shader（含 uniform 刷新）。返回 false = 无源且从未就绪
     * → 调用方回退系统绘制。
     *
     * 着色参数（用户要求「纯玻璃效果，没有白」）：字形 = 背后壁纸的原位采样，**不做任何增亮** ——
     * refractionAmount=0（取消位移）、highlight=0（去掉加性白高光，其 floor 保底会把字形抬灰发白）、
     * dispersion=0、vibrancy=1。玻璃只透光，不发光、不位移。
     */
    private fun ensureGlass(tv: TextView, state: DigitGlassState): Boolean {
        val w = tv.width
        val h = tv.height
        if (w <= 0 || h <= 0) return false
        val bmp = glassSource() ?: return state.ready
        val mapping = wallpaperMapping(bmp) ?: return state.ready
        val loc = IntArray(2)
        try {
            tv.getLocationOnScreen(loc)
        } catch (t: Throwable) {
            return state.ready
        }
        if (!wallpaperOffsetsSynced) {
            wallpaperOffsetsSynced = true
            syncWallpaperOffsets(tv)
        }
        val srcRect = RectF(
            mapping.cropX + loc[0] / mapping.scale,
            mapping.cropY + loc[1] / mapping.scale,
            mapping.cropX + (loc[0] + w) / mapping.scale,
            mapping.cropY + (loc[1] + h) / mapping.scale,
        )
        val viewport = RectF(0f, 0f, w.toFloat(), h.toFloat())
        // 壁纸换代 → 重建 BitmapShader 并重绑输入
        val bmpToken = System.identityHashCode(bmp)
        if (state.sourceToken != bmpToken || state.source == null) {
            val src = LiquidGlassShader.createSourceBitmapShader(bmp)
            state.source = src
            state.shader.setInputShader("uSource", src)
            state.shader.setInputShader("uSourceLowRes", src)
            state.sourceToken = bmpToken
        }
        // 全部输入未变 → 复用上次 uniform（时间刻度视图每分钟才重绘）
        if (state.ready && state.viewport == viewport && state.srcRect == srcRect) return true
        val params = LiquidGlassShader.Params(
            viewportWidth = w.toFloat(),
            viewportHeight = h.toFloat(),
            cornerRadius = 0f,
            cornerIsConic = false,
            // 极大值 → 全走内部（原位）分支
            refractionHeight = 100000f,
            refractionAmount = 0f,
            depth = 0f,
            dispersion = 0f,
            highlight = 0f,
            highlightWidth = 0f,
            vibrancy = 1f,
            blurRadius = readClockBlur(),
        )
        LiquidGlassShader.setUniforms(
            state.shader, params, null,
            sourceRect = srcRect, lowResSourceRect = srcRect,
            debugCoord = 0f,
            maskRect = viewport, maskCornerRadius = 0f, maskOffsetX = 0f, maskOffsetY = 0f,
        )
        state.viewport = viewport
        state.srcRect = srcRect
        state.ready = true
        return true
    }

    // ------------------------------------------------------------ 判定 / 壁纸折算

    /**
     * 判定 TextView 是否为锁屏/AOD 时钟数字 View：parent 链上出现时钟宿主即为时钟内文字。
     * 结果缓存（WeakHashMap，attach 才判定）。
     */
    private fun isClockDigitView(tv: TextView): Boolean {
        clockDigitCache[tv]?.let { return it }
        if (!tv.isAttachedToWindow) return false
        var isClock = false
        var parent: ViewParent? = tv.parent
        var depth = 0
        while (parent is View && depth < 24) {
            val name = parent.javaClass.name
            if (name == CLOCK_HOST_NAME || name == CLOCK_HOST_BASE_NAME || name == CLOCK_HOST_AOD_NAME) {
                isClock = true
                break
            }
            parent = parent.parent
            depth++
        }
        if (tv.isAttachedToWindow) {
            clockDigitCache[tv] = isClock
        }
        return isClock
    }

    /** 玻璃源：优先自有解码的原图，回退系统投递位图的**自有副本**。 */
    private fun glassSource(): Bitmap? {
        refreshOriginalWallpaper()?.let { if (!it.isRecycled) return it }
        val fallback = wallpaperBitmap
        return if (fallback != null && !fallback.isRecycled) fallback else null
    }

    /**
     * 把系统投递的壁纸位图复制成自有副本（防系统侧回收后绘制静默失效，
     * 见记忆 `project_launcher-blur-bitmap-invalid`）。失败返回 null（调用方不改状态）。
     */
    private fun adoptWallpaper(bmp: Bitmap): Bitmap? {
        return try {
            if (bmp.isRecycled || bmp.width <= 0 || bmp.height <= 0) return null
            val copy = bmp.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            wallpaperBitmap = copy
            copy
        } catch (t: Throwable) {
            Log.w(TAG, "lockscreen-clock: wallpaper copy failed", t)
            null
        }
    }

    /**
     * 壁纸「贴屏 cover」映射：`scale` = 位图像素 → 屏幕像素（取两轴较大者）；可见窗口 = 屏幕 / scale；
     * 超出部分由滚动 offset 平移。语义与 `LauncherHook.buildVisibleCrop` 一致。
     *
     * 例：壁纸 2875x3168、屏幕 1440x3168 → scale = max(0.5009, 1.0) = 1.0（高度正好贴屏），
     * 横向可滚 1435px。**不可**用 `bmp.width / screenWidth`（会得 1.9965，横向拉伸 2 倍采到无关区域）。
     */
    private fun wallpaperMapping(bmp: Bitmap): WallpaperMapping? {
        val scrW = screenWidth.toFloat()
        val scrH = screenHeight.toFloat()
        if (scrW <= 0f || scrH <= 0f || bmp.width <= 0 || bmp.height <= 0) return null
        val scale = maxOf(scrW / bmp.width, scrH / bmp.height)
        if (scale <= 0f) return null
        val maxScrollX = (bmp.width - scrW / scale).coerceAtLeast(0f)
        val maxScrollY = (bmp.height - scrH / scale).coerceAtLeast(0f)
        return WallpaperMapping(
            scale = scale,
            cropX = wallpaperOffsetX.coerceIn(0f, 1f) * maxScrollX,
            cropY = wallpaperOffsetY.coerceIn(0f, 1f) * maxScrollY,
        )
    }

    /**
     * 从系统读壁纸 offset（`WallpaperManager.getWallpaperOffsets(IBinder, float[], float[])`，
     * @SystemApi 需反射）。锁屏不自滚动 → 读一次即可；失败静默保持 0（对不可滚壁纸无影响）。
     */
    private fun syncWallpaperOffsets(anchor: View) {
        try {
            val wm = wallpaperManager() ?: return
            val token = anchor.rootView?.windowToken ?: return
            val xs = FloatArray(1)
            val ys = FloatArray(1)
            wm.javaClass.getMethod(
                "getWallpaperOffsets",
                android.os.IBinder::class.java, FloatArray::class.java, FloatArray::class.java,
            ).invoke(wm, token, xs, ys)
            wallpaperOffsetX = xs[0]
            wallpaperOffsetY = ys[0]
            if (moduleLogEnabled()) {
                Log.i(TAG, "lockscreen-clock: wallpaper offsets x=${xs[0]} y=${ys[0]}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "lockscreen-clock: getWallpaperOffsets read failed (keep 0)", t)
        }
    }

    /** WallpaperManager 实例（ActivityThread.currentApplication 上下文，SystemUI 进程内）。 */
    private fun wallpaperManager(): WallpaperManager? {
        return try {
            val app = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null) as? android.content.Context ?: return null
            WallpaperManager.getInstance(app)
        } catch (t: Throwable) {
            Log.w(TAG, "lockscreen-clock: WallpaperManager.getInstance failed", t)
            null
        }
    }

    /**
     * 刷新原始壁纸（清晰）：静态壁纸 `WallpaperManager.getWallpaperFile(FLAG_SYSTEM)` 解码；
     * id 未变直接返回缓存；动态壁纸（无文件）返回 null → 调用方回退投递位图。
     */
    private fun refreshOriginalWallpaper(): Bitmap? {
        val cur = originalWallpaper
        if (cur != null && !cur.isRecycled) return cur
        val wm = wallpaperManager() ?: return null
        val info = try {
            wm.wallpaperInfo
        } catch (t: Throwable) {
            null
        }
        if (info != null) return null
        val id = try {
            wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM)
        } catch (t: Throwable) {
            -1
        }
        if (id <= 0 || id == originalWallpaperId) return originalWallpaper
        val pfd = try {
            wm.getWallpaperFile(WallpaperManager.FLAG_SYSTEM)
        } catch (t: Throwable) {
            null
        } ?: return null
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            pfd.dup().use { d -> BitmapFactory.decodeFileDescriptor(d.fileDescriptor, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            val maxDim = maxOf(screenWidth, screenHeight)
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDim) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bmp = pfd.dup().use { d -> BitmapFactory.decodeFileDescriptor(d.fileDescriptor, null, opts) }
            if (bmp != null && bmp.width > 0 && bmp.height > 0) {
                originalWallpaper = bmp
                originalWallpaperId = id
                wallpaperOffsetsSynced = false
                if (moduleLogEnabled()) {
                    Log.i(TAG, "lockscreen-clock: original wallpaper decoded ${bmp.width}x${bmp.height} id=$id")
                }
                return bmp
            }
        } catch (t: Throwable) {
            Log.e(TAG, "lockscreen-clock: original wallpaper decode failed", t)
        } finally {
            try {
                pfd.close()
            } catch (ignored: Throwable) {
            }
        }
        return null
    }

    // ------------------------------------------------------------ 辅助

    /**
     * 清除时钟数字 View 上系统挂的 RenderEffect（blur 后处理），兜底防路径 1/2 失效。
     * 反射清 `View.mRenderEffect` = null + `RenderNode.setRenderEffect(null)`（幂等）。
     */
    private fun clearClockRenderEffect(view: View) {
        try {
            try {
                mRenderEffectField()?.set(view, null)
            } catch (t: Throwable) {
                // 忽略（RenderNode 路径兜底）
            }
            try {
                mGetRenderNode(view)?.setRenderEffect(null)
            } catch (t: Throwable) {
                // 忽略
            }
        } catch (t: Throwable) {
            // 整体兜底：绝不影响绘制主流程
        }
    }

    private fun mRenderEffectField(): java.lang.reflect.Field? {
        if (mRenderEffectFieldResolved) return mRenderEffectField
        mRenderEffectFieldResolved = true
        mRenderEffectField = try {
            View::class.java.getDeclaredField("mRenderEffect").apply { isAccessible = true }
        } catch (t: Throwable) {
            null
        }
        return mRenderEffectField
    }

    private fun mGetRenderNode(view: View): android.graphics.RenderNode? {
        if (!mRenderNodeFieldResolved) {
            mRenderNodeFieldResolved = true
            mRenderNodeField = try {
                View::class.java.getDeclaredField("mRenderNode").apply { isAccessible = true }
            } catch (t: Throwable) {
                null
            }
        }
        val f = mRenderNodeField
        if (f != null) {
            return try {
                f.get(view) as? android.graphics.RenderNode
            } catch (t: Throwable) {
                null
            }
        }
        if (!mGetRenderNodeResolved) {
            mGetRenderNodeResolved = true
            mGetRenderNodeMethod = try {
                View::class.java.getDeclaredMethod("getRenderNode").apply { isAccessible = true }
            } catch (t: Throwable) {
                null
            }
        }
        val m = mGetRenderNodeMethod ?: return null
        return try {
            m.invoke(view) as? android.graphics.RenderNode
        } catch (t: Throwable) {
            null
        }
    }

    private fun invalidateClockDigits() {
        val it = clockDigitCache.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.value) {
                try {
                    val tv = e.key
                    if (tv.isAttachedToWindow) tv.invalidate()
                } catch (t: Throwable) {
                    // 忽略单 View 异常
                }
            }
        }
    }

    /** 时钟玻璃模糊半径：节流读 Prefs（热路径不每帧 IPC），默认 6f。 */
    private var lastBlurRead = -1f
    private var lastBlurReadAt = 0L

    private fun readClockBlur(): Float {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastBlurReadAt < 1000L && lastBlurRead >= 0f) return lastBlurRead
        lastBlurRead = try {
            api?.let { Prefs.read(it).getFloat(Prefs.KEY_LOCKSCREEN_CLOCK_BLUR, Prefs.DEFAULT_LOCKSCREEN_CLOCK_BLUR) }
                ?.coerceIn(0f, 60f) ?: Prefs.DEFAULT_LOCKSCREEN_CLOCK_BLUR
        } catch (t: Throwable) {
            Prefs.DEFAULT_LOCKSCREEN_CLOCK_BLUR
        }
        lastBlurReadAt = now
        return lastBlurRead
    }

    private var lastLogEnabled = false
    private var lastLogEnabledAt = 0L

    private fun moduleLogEnabled(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastLogEnabledAt < 1000L) return lastLogEnabled
        lastLogEnabled = try {
            api?.let { Prefs.read(it).getBoolean(Prefs.KEY_ENABLE_LOGS, Prefs.DEFAULT_ENABLE_LOGS) } ?: false
        } catch (t: Throwable) {
            false
        }
        lastLogEnabledAt = now
        return lastLogEnabled
    }
}
