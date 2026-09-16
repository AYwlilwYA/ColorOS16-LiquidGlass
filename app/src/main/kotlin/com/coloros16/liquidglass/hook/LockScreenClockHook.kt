package com.coloros16.liquidglass.hook

import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Bundle
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
 * 锁屏大时钟液态玻璃化（doc/spec/43，2026-08-13）。
 *
 * ## 绘制链（反编译实证，spec/14 + 本调查补充）
 *
 * 锁屏大时钟（大字时钟）**不走** posteffect drawBlurShader 链（面板/控件那套），而是独立位图投递链：
 * - `KeyguardWallpaperDeliveryController`（com.oplus.systemui.keyguard.clockstyle，:97-99,127-128,323-376）
 *   → `BlurParam(0,63)` + `setBlurRadius(180)` → `BlurBitmapFactory`（com.oplus.posteffect，注册
 *   BlurStateListener 收模糊结果）→ `liveWallpaperRealTimeBitmapListener.onBlurComplete(Bitmap,int,float)`
 *   → `sendBlurBitmapToClock`（:355）→ `IOplusKeyguardStyleTheme.setBlurWallpaperBitmap(Bitmap,Bundle,boolean)`
 *   → `ThemePlugin.setWallpaperBitmapInternal(Bitmap,String,Bundle,boolean,boolean)`（ThemePlugin.java:791）
 *   → 时钟插件 `setWallpaperBitmap`。
 * - 大时钟插件（system_ext/app/KeyguardPersonalityClocks，`com.oplus.keyguard.clock.big`）的数字文字视图 =
 *   `MyCustomizedTextView`（extends 公共 `CustomizedTextView` extends `OplusHDRTextView` extends TextView），
 *   布局 `big_clock_layout_single_clock_digital_time_view_*.xml` 每数字一个 TextView；
 *   `setBlurRatio(float)`（MyCustomizedTextView.java:133-140）给数字 View 挂 `RenderEffect.createBlurEffect`
 *   即系统"壁纸模糊衬底"。
 *
 * ## 实现（方案 B：对时钟数字 UI 注入液态玻璃 shader）
 *
 * 背景源 = 时钟壁纸投递位图（`ThemePlugin.setWallpaperBitmapInternal` hook 拦截保存）。数字 TextView
 * onDraw 时把 text paint 的 shader 替换为液态玻璃 RuntimeShader（复用 `LiquidGlassShader`，uSource =
 * 壁纸位图、uViewport=数字 View 尺寸、uSourceRect=数字 View 屏幕区域折算到壁纸位图），字形即以玻璃质感
 * 绘制（内部清晰/轻磨砂透镜 + vibrancy + 可选折射/高光），与时钟同一绘制层级（depth/景深不产生层级错乱）。
 *
 * ## 景深/层级（用户约束）
 *
 * 锁屏时钟启用景深（depth）会有层级遮挡。本实现直接替换**数字 View 自身**的 text paint shader，玻璃绘制在
 * 数字所在绘制层级内部（同一 View 的 onDraw 内），不新增覆盖层、不改 View 结构——玻璃与时钟天然同层。
 *
 * ## 配置
 * - `KEY_LOCKSCREEN_CLOCK_GLASS`（默认 false）：总开关，改动需重启 SystemUI 生效。
 * - `KEY_LOCKSCREEN_CLOCK_BLUR`（默认 6）：数字内部轻模糊半径，0=清晰透镜。
 * - 材质其他参数复用全局玻璃参数（KEY_BLUR_RADIUS/KEY_VIBRANCY 等，见 [Prefs]），改动即时生效。
 */
object LockScreenClockHook {

    private const val TAG = "LiquidGlass"

    // ---- 目标类（16.1 PJZ110 反编译实证）----
    /** 时钟主题插件宿主（SystemUI 类，IOplusKeyguardStyleTheme 实现），接收 KeyguardWallpaperDeliveryController
     *  投递的壁纸位图。 */
    private const val CLASS_THEME_PLUGIN = "com.oplus.keyguard.plugin.ThemePlugin"
    private const val METHOD_SET_WALLPAPER_BITMAP_INTERNAL = "setWallpaperBitmapInternal"
    /** 锁屏时钟宿主 View（插件数字 View 的祖先）：CustomOplusKeyguardStyleClock（keyguard/view）与
     *  基类 OplusKeyguardStyleClock（keyguard）。数字 View 的 parent 链走到它 = 属于锁屏时钟。 */
    private const val CLOCK_HOST_NAME = "com.oplus.systemui.keyguard.view.CustomOplusKeyguardStyleClock"
    private const val CLOCK_HOST_BASE_NAME = "com.oplus.keyguard.OplusKeyguardStyleClock"
    /** AOD 时钟宿主 View（SystemUI 反编译实证 `com.oplus.systemui.aod.aodclock.off`，AOD 时钟数字 View
     *  parent 链必经）——锁屏 + AOD 双场景覆盖（用户硬约束）。 */
    private const val CLOCK_HOST_AOD_NAME = "com.oplus.systemui.aod.aodclock.off.AodClockLayout"

    // ---- 状态 ----
    @Volatile
    private var enabled = false
    /** 时钟壁纸投递位图（玻璃源）。来自 ThemePlugin.setWallpaperBitmapInternal arg0。 */
    @Volatile
    private var wallpaperBitmap: Bitmap? = null
    /** 壁纸位图换代令牌（每次收到新位图 +1，用于 shader 重绑输入去重）。 */
    @Volatile
    private var wallpaperToken = 0
    /** 屏幕尺寸（用于把数字 View 屏幕区域折算到壁纸位图坐标）。 */
    @Volatile
    private var screenWidth = 1080
    @Volatile
    private var screenHeight = 2400

    /** 已判定"是否锁屏时钟数字 View"的缓存（防每帧走 parent 链）。 */
    private val clockDigitCache = WeakHashMap<TextView, Boolean>()

    /** 每数字 View 的玻璃 shader 状态（独立实例防 uniform 串扰，同 BlurDrawHook 铁律）。 */
    private class DigitGlassState {
        val shader: RuntimeShader = LiquidGlassShader.create()
        var wallpaperToken = -1
        var source: Shader? = null
        var viewport = RectF()
        var srcRect = RectF()
        var lastBlur = -1f
        var lastVibrancy = -1f
    }

    private val digitStates = WeakHashMap<TextView, DigitGlassState>()

    private var api: XposedInterface? = null

    /** `View.mRenderEffect` 反射句柄（路径 3 清字段用，类级缓存；解析失败 null = 仅 RenderNode 兜底）。
     *  `resolved` 标志防每帧重复反射失败。 */
    @Volatile
    private var mRenderEffectField: java.lang.reflect.Field? = null
    @Volatile
    private var mRenderEffectFieldResolved = false
    /** `View.getRenderNode()` 反射句柄（@hide，API 36 android.jar 不导出，需反射；类级缓存）。 */
    @Volatile
    private var mGetRenderNodeMethod: java.lang.reflect.Method? = null
    @Volatile
    private var mGetRenderNodeResolved = false
    /** `View.mRenderNode` 私有字段反射句柄（Android 稳定字段，类型 android.graphics.RenderNode；
     *  比 getRenderNode() 方法更可靠——Android 16 方法可能改名/隐藏，字段名长期稳定）。 */
    @Volatile
    private var mRenderNodeField: java.lang.reflect.Field? = null
    @Volatile
    private var mRenderNodeFieldResolved = false
    /** [spec/43 HDR span 保护] 时钟数字 View onDraw 绘制期间标志（ThreadLocal，同线程生效）：
     *  HDR 文字用自定义 Span（`a4.a.updateDrawState` 调 `TextPaint.setShader(null)`）清掉我们设的玻璃
     *  shader → hook `Paint.setShader` 在此标志为 true 且 newShader==null 时跳过，保护玻璃 shader。 */
    private val inClockDigitDraw: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }
    /** [2026-08-15 玻璃源改进] 原始壁纸源（WallpaperManager 解码清晰壁纸，替代投递的模糊位图——用户反馈
     *  字形磨砂 = 玻璃源含 BlurBitmapFactory 模糊）。静态壁纸文件解码；动态壁纸（无文件）返回 null
     *  → prepareGlass fallback 投递位图。 */
    @Volatile
    private var originalWallpaper: Bitmap? = null
    @Volatile
    private var originalWallpaperId = -1

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
        mountPaintSetShaderBlocker(api, classLoader)
        mountViewDraw(api, classLoader)
        mountTextViewOnDraw(api, classLoader)
        Log.i(TAG, "lockscreen-clock-glass mounted (wallpaper source + RenderEffect blocker + TextView.onDraw)")
    }

    // ------------------------------------------------------------ hook 挂载

    /**
     * 拦截时钟壁纸位图投递：`ThemePlugin.setWallpaperBitmapInternal(Bitmap,String,Bundle,boolean,boolean)`。
     * `KeyguardWallpaperDeliveryController.sendBlurBitmapToClock` → `setBlurWallpaperBitmap(bitmap,bundle,z)`
     * → `setWallpaperBitmapInternal(bitmap,"",bundle,false,z)` 全路径汇入此方法（含 setWallpaperBitmap 四参）。
     * 收到非空位图 → 存为玻璃源（wallpaperToken++），并 invalidate 已登记的时钟数字 View 重绘。
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
                        if (bmp != null && !bmp.isRecycled) {
                            wallpaperBitmap = bmp
                            wallpaperToken++
                            if (moduleLogEnabled()) {
                                Log.i(TAG, "lockscreen-clock: wallpaper bitmap captured ${bmp.width}x${bmp.height}")
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
     * 阻断系统把 RenderEffect（blur 后处理）挂到时钟数字 View（spec/43 修复，路径 1+2）。
     *
     * 根因：`MyCustomizedTextView.setBlurRatio(float)` 给数字 View 挂 `RenderEffect.createBlurEffect`，
     * View 级硬件后处理在 onDraw 之后把玻璃字形再糊一遍 → 玻璃观感未生效。两条挂载路径都要拦：
     * 1. 标准路径 `android.view.View.setRenderEffect(RenderEffect)`（主线程 super 调用）。
     * 2. RenderThread 路径 `com.oplus.animation.OplusAsyncAnimatorUtils.setRenderEffect(View, RenderEffect)`
     *    （静态方法，MyCustomizedTextView.setRenderEffecCheck 实证；Oplus framework 运行时类，不在反编译
     *    产物里，反射失败仅此路径失效，路径 1+3 兜底）。
     * 命中时钟数字 View && effect 非 null → 参数换 null 后 proceed（系统执行 setRenderEffect(null)=不挂 blur，
     * 避免直接跳过导致状态不一致）；effect null（清除）与非时钟 View 一律放行——严格 scoped，SystemUI
     * 其他 View 的 RenderEffect 不受影响。
     */
    private fun mountRenderEffectBlocker(api: XposedInterface, classLoader: ClassLoader) {
        // 路径 1：标准 View.setRenderEffect（时钟数字 View 恒为 TextView 子类，as? TextView 判定精确 scoped）
        try {
            val method = View::class.java.getDeclaredMethod("setRenderEffect", android.graphics.RenderEffect::class.java)
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        val view = chain.getThisObject() as? TextView
                        val effect = chain.getArg(0) as? android.graphics.RenderEffect
                        if (view != null && effect != null && isClockDigitView(view)) {
                            if (moduleLogEnabled()) {
                                Log.i(TAG, "lockscreen-clock: BLOCKED View.setRenderEffect(blur) on clock digit ${view.javaClass.name} w=${view.width} h=${view.height}")
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

        // 路径 2：RenderThread 异步路径 OplusAsyncAnimatorUtils.setRenderEffect(View, RenderEffect)（静态）
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
                                Log.i(TAG, "lockscreen-clock: BLOCKED OplusAsyncAnimatorUtils.setRenderEffect(blur) on clock digit ${view.javaClass.name}")
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
            Log.e(TAG, "lockscreen-clock-glass mount FAILED: OplusAsyncAnimatorUtils#setRenderEffect blocker (path2 inactive, path1+3 兜底)", t)
        }
    }

    /**
     * [spec/43 HDR span 保护] hook `Paint.setShader(Shader)`：时钟数字 View onDraw 绘制期间
     * （[inClockDigitDraw] ThreadLocal 标志 true），HDR 文字的自定义 Span（`a4.a.updateDrawState`，
     * 反编译实证调 `textPaint.setShader(null)` + `setColorFilter(null)` + `setColor(HDR色)`）会清掉
     * 我们设在 text paint 上的玻璃 shader → 拦截 newShader==null 时跳过（不执行），保护玻璃 shader
     * 让字形以玻璃质感填充。shader 优先级高于 color，HDR span 的 setColor 不影响玻璃效果。
     * scoped：仅 onDraw 绘制期间同线程命中，SystemUI 其他 View 的 Paint.setShader 不受影响。
     */
    private fun mountPaintSetShaderBlocker(api: XposedInterface, classLoader: ClassLoader) {
        try {
            val clazz = Class.forName("android.graphics.Paint", false, classLoader)
            val method = clazz.getDeclaredMethod("setShader", android.graphics.Shader::class.java)
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        val newShader = chain.getArg(0) as? android.graphics.Shader
                        if (newShader == null && inClockDigitDraw.get()) {
                            // HDR span 正在清 shader → 跳过原方法，保留玻璃 shader
                            if (moduleLogEnabled()) Log.i(TAG, "lockscreen-clock: PROTECTED glass shader from HDR span clear")
                            return@intercept null
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "lockscreen-clock: Paint.setShader intercept error", t)
                    }
                    chain.proceed()
                }
            Log.i(TAG, "lockscreen-clock-glass mounted: Paint#setShader protector")
        } catch (t: Throwable) {
            Log.e(TAG, "lockscreen-clock-glass mount FAILED: Paint#setShader protector", t)
        }
    }

    /**
     * hook `TextView.onDraw(Canvas)`：对【锁屏时钟数字 View】把 text paint 的 shader 替换为液态玻璃
     * RuntimeShader（字形以玻璃质感绘制），画完还原（不污染其他绘制）。非时钟数字 View O(1) 快速跳过。
     *
     * 选择 hook 框架基类 TextView.onDraw（而非插件类）的原因：插件类（com.oplus.keyguard.clock.big.*）由
     * 插件 package-context 的独立 classloader 加载，install 时拿不到；TextView 是 framework 基类，SystemUI
     * classloader 直接可 hook，且覆盖所有时钟风格（big/base/gallery/graffiti）的数字文字视图。
     */
    private fun mountTextViewOnDraw(api: XposedInterface, classLoader: ClassLoader) {
        try {
            val clazz = Class.forName("android.widget.TextView", false, classLoader)
            val method = clazz.getDeclaredMethod("onDraw", Canvas::class.java).apply { isAccessible = true }
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        val tv = chain.getThisObject() as? TextView
                        if (tv != null && isClockDigitView(tv)) {
                            // [2026-08-15 玻璃化第一步] 跳过系统 onDraw（白色字形不画），改用手动绘制：
                            // 用 getPaint()（完全控制）+ 玻璃 RuntimeShader 填充字形 → 字形=壁纸玻璃。
                            // 之前 shader/alpha 无效因为系统绘制用内部 paint ≠ getPaint()；手动绘制绕开该问题。
                            clearClockRenderEffect(tv)
                            val canvas = chain.getArg(0) as? Canvas
                            val glass = prepareGlass(tv)
                            if (canvas != null && glass != null) {
                                if (moduleLogEnabled()) {
                                    Log.i(TAG, "lockscreen-clock: clock digit GLASS self-draw w=${tv.width} h=${tv.height} text='${tv.text}'")
                                }
                                val paint = tv.paint
                                inClockDigitDraw.set(true)
                                try {
                                    // [临时测试 2026-08-15] drawText 用纯红 BitmapShader：验证 drawText+shader 是否真正
                                    // 作用字形（截图确认数字是否变红）。红色 = drawText+shader 生效，问题在玻璃 shader
                                    // 参数/采样；仍白 = drawText+shader 没生效（RuntimeShader 文字绘制/被覆盖）。
                                    val redBmp = try {
                                        android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888).apply {
                                            eraseColor(0xFFFF0000.toInt())
                                        }
                                    } catch (t: Throwable) {
                                        null
                                    }
                                    val testShader: Shader = if (redBmp != null) {
                                        android.graphics.BitmapShader(redBmp, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
                                    } else {
                                        glass
                                    }
                                    paint.shader = testShader
                                    // 用 Layout 精确坐标绘制文字（getLineLeft/getLineBaseline），确保画在字形正确位置
                                    val layout = tv.layout
                                    val text = tv.text?.toString().orEmpty()
                                    if (layout != null && text.isNotEmpty()) {
                                        val x = tv.paddingLeft.toFloat() + layout.getLineLeft(0)
                                        val y = tv.paddingTop.toFloat() + layout.getLineBaseline(0).toFloat()
                                        if (moduleLogEnabled()) {
                                            Log.i(TAG, "lockscreen-clock: drawText '$text' x=$x y=$y w=${tv.width} h=${tv.height}")
                                        }
                                        canvas.drawText(text, x, y, paint)
                                    }
                                    paint.shader = null
                                } finally {
                                    inClockDigitDraw.set(false)
                                }
                                return@intercept null
                            }
                            // 无玻璃源 → 保持隐藏（字形不画）
                            if (moduleLogEnabled()) {
                                Log.i(TAG, "lockscreen-clock: clock digit hidden (no glass) w=${tv.width} h=${tv.height} text='${tv.text}'")
                            }
                            return@intercept null
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "lockscreen-clock: TextView.onDraw intercept error", t)
                    }
                    chain.proceed()
                }
            Log.i(TAG, "lockscreen-clock-glass mounted: TextView#onDraw")
        } catch (t: Throwable) {
            Log.e(TAG, "lockscreen-clock-glass mount FAILED: TextView#onDraw", t)
        }
    }

    /**
     * [2026-08-15 完全自绘] hook `android.view.View.draw(Canvas)`（比 onDraw 更外层，覆盖放大动画
     *  helper actualDraw=super.draw 路径）：时钟数字 View 完全跳过系统绘制（背景/文字/任何），
     *  自己用玻璃 shader 绘制字形——放大动画后系统白色覆盖问题根治（任何系统绘制路径都经此注入玻璃）。
     *  scoped：isClockDigitView 命中才接管，SystemUI 其他 View 放行。
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
                            // [2026-08-15 修复] 不跳过 super.draw（跳过导致 RecordingCanvas 状态异常，drawText 不生效）：
                            // 先 proceed 让 super.draw 正常绘制（canvas 初始化 + 系统字形），之后 drawText 红色覆盖。
                            val result = chain.proceed()
                            if (canvas != null && tv.text?.isNotEmpty() == true) {
                                if (moduleLogEnabled()) {
                                    Log.i(TAG, "lockscreen-clock: clock digit View.draw OVERLAY w=${tv.width} h=${tv.height} text='${tv.text}'")
                                }
                                selfDrawClockDigit(tv, canvas, prepareGlass(tv))
                            }
                            return@intercept result
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "lockscreen-clock: View.draw intercept error", t)
                    }
                    chain.proceed()
                }
            Log.i(TAG, "lockscreen-clock-glass mounted: View#draw full self-draw")
        } catch (t: Throwable) {
            Log.e(TAG, "lockscreen-clock-glass mount FAILED: View#draw", t)
        }
    }

    /** [2026-08-15 完全自绘] 用正确坐标（居中 x + tv.baseline）+ 玻璃 shader 绘制字形。
     *  （当前 LockScreenClockHook 已注释停用——XposedEntry 不调用 install；此实现保留待后续恢复） */
    private fun selfDrawClockDigit(tv: TextView, canvas: Canvas, glass: RuntimeShader?) {
        val paint = tv.paint
        val text = tv.text?.toString().orEmpty()
        if (text.isEmpty() || glass == null) return
        val x = tv.paddingLeft.toFloat() + (tv.width - tv.paddingLeft - tv.paddingRight - paint.measureText(text)) / 2f
        val y = tv.baseline.toFloat()
        inClockDigitDraw.set(true)
        try {
            paint.shader = glass
            canvas.drawText(text, x, y, paint)
            paint.shader = null
        } finally {
            inClockDigitDraw.set(false)
            paint.shader = null
        }
    }

    // ------------------------------------------------------------ 判定 / 玻璃

    /**
     * 判定 TextView 是否为锁屏/AOD 时钟数字 View：parent 链上出现时钟宿主（锁屏
     * `OplusKeyguardStyleClock`/`CustomOplusKeyguardStyleClock`，AOD `AodClockLayout`）即为时钟内文字。
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
        // 仅 attach 状态下缓存判定结果（未 attach 不缓存，下次重判）
        if (tv.isAttachedToWindow) {
            clockDigitCache[tv] = isClock
        }
        return isClock
    }

    /**
     * 为时钟数字 TextView 准备液态玻璃 shader（含 uniform 刷新）。返回 null = 无壁纸源/尺寸非法 → 系统原样。
     *
     * uniform 契约（同 BlurDrawHook）：uViewport=数字 View 自身尺寸（onDraw canvas 为 View 局部坐标空间，
     * coord(0,0)=View 左上角）；uSourceRect=数字 View 屏幕区域折算到壁纸位图（srcCoord 手动换算）。
     * 玻璃参数：内部清晰/轻磨砂透镜（uBlurRadius=KEY_LOCKSCREEN_CLOCK_BLUR）+ vibrancy（KEY_VIBRANCY）；
     * 关闭 SDF 形状类边缘效果（refractionHeight 极大 → 全内部、refractionAmount=0、highlight=0、dispersion=0）
     * —— 字形本身由 TextView text paint 定义，无需也不应套用圆角矩形 SDF 折射/高光。
     */
    private fun prepareGlass(tv: TextView): RuntimeShader? {
        // [2026-08-15 玻璃源改进] 优先原始壁纸（WallpaperManager 解码，清晰），fallback 投递位图
        // （ThemePlugin 投递的可能含 BlurBitmapFactory 模糊 → 字形磨砂，用户反馈）
        val bmp = refreshOriginalWallpaper() ?: wallpaperBitmap
        if (bmp == null) {
            if (moduleLogEnabled()) Log.i(TAG, "lockscreen-clock: prepareGlass null (no wallpaper source)")
            return null
        }
        if (bmp.isRecycled) {
            if (moduleLogEnabled()) Log.i(TAG, "lockscreen-clock: prepareGlass null (wallpaper recycled)")
            if (bmp === wallpaperBitmap) wallpaperBitmap = null
            originalWallpaper = null
            return null
        }
        val w = tv.width
        val h = tv.height
        if (w <= 0 || h <= 0) {
            if (moduleLogEnabled()) Log.i(TAG, "lockscreen-clock: prepareGlass null (zero size w=$w h=$h) ${tv.javaClass.name}")
            return null
        }
        val loc = IntArray(2)
        try {
            tv.getLocationOnScreen(loc)
        } catch (t: Throwable) {
            if (moduleLogEnabled()) Log.i(TAG, "lockscreen-clock: prepareGlass null (getLocationOnScreen fail)")
            return null
        }
        // 壁纸位图坐标 ← 屏幕坐标：按 (位图/屏幕) 比例折算（投递位图为屏幕/壁纸缓存尺寸，近似屏幕对齐）
        val scaleX = bmp.width.toFloat() / screenWidth
        val scaleY = bmp.height.toFloat() / screenHeight
        val srcRect = RectF(
            loc[0] * scaleX, loc[1] * scaleY,
            (loc[0] + w) * scaleX, (loc[1] + h) * scaleY
        )
        val viewport = RectF(0f, 0f, w.toFloat(), h.toFloat())
        // 读配置（即时生效；热路径每绘制读 Prefs 有 binder 开销，用节流缓存）
        val blur = readClockBlur()
        val vibrancy = readVibrancy()
        val state = digitStates.getOrPut(tv) { DigitGlassState() }
        // 壁纸换了（原始壁纸/投递位图任一源换代）→ 重建 BitmapShader 并重绑输入
        // （uSource/uSourceLowRes 同源；用位图身份做 token，双源切换都能检测）
        val bmpToken = System.identityHashCode(bmp)
        if (state.wallpaperToken != bmpToken || state.source == null) {
            val src = LiquidGlassShader.createSourceBitmapShader(bmp)
            state.source = src
            state.shader.setInputShader("uSource", src)
            state.shader.setInputShader("uSourceLowRes", src)
            state.wallpaperToken = bmpToken
        }
        // 全部输入未变 → 复用上次 uniform，跳过 setUniforms（时间刻度视图每分钟才重绘，过渡动画期也零冗余）
        if (state.viewport == viewport && state.srcRect == srcRect &&
            state.lastBlur == blur && state.lastVibrancy == vibrancy
        ) {
            return state.shader
        }
        val params = LiquidGlassShader.Params(
            viewportWidth = w.toFloat(),
            viewportHeight = h.toFloat(),
            cornerRadius = 0f,
            cornerIsConic = false,
            // 内部透镜：refractionHeight 极大 → 全走折射环带分支，字形内部壁纸采样偏移 = refractionAmount
            refractionHeight = 100000f,
            // [2026-08-15 玻璃质感明显化] refractionAmount 200 → 字形内部壁纸内容大幅错位（玻璃透镜感，
            // 区别于系统壁纸透出）；色散/高光增强玻璃质感。用户反馈"和系统一样"= 之前 40px 偏移太小。
            refractionAmount = 200f,
            depth = 0f,
            dispersion = 0.1f,
            highlight = 0.5f,
            highlightWidth = 8f,
            vibrancy = vibrancy,
            // [2026-08-15 用户要求完全玻璃化、去磨砂] blurRadius 0 → 内部清晰透镜
            blurRadius = 0f,
        )
        // 输入 shader 已在上方按壁纸换代重绑；此处传 source=null 让 setUniforms 跳过重复 setInputShader
        // （仅重设 uSourceRect 等 uniform 即可——srcCoord 手动换算不依赖 BitmapShader 矩阵）
        LiquidGlassShader.setUniforms(
            state.shader, params, null,
            sourceRect = srcRect, lowResSourceRect = srcRect,
            debugCoord = 0f,
            maskRect = viewport, maskCornerRadius = 0f, maskOffsetX = 0f, maskOffsetY = 0f,
        )
        state.viewport = viewport
        state.srcRect = srcRect
        state.lastBlur = blur
        state.lastVibrancy = vibrancy
        if (moduleLogEnabled()) {
            Log.i(TAG, "lockscreen-clock: shader applied srcRect=$srcRect viewport=$viewport bmp=${bmp.width}x${bmp.height}")
        }
        return state.shader
    }

    // ------------------------------------------------------------ 辅助

    /** [spec/54] WallpaperManager 实例（ActivityThread.currentApplication 上下文，SystemUI 进程内）。 */
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

    /** [2026-08-15 玻璃源改进] 刷新原始壁纸（清晰）：静态壁纸 `WallpaperManager.getWallpaperFile(FLAG_SYSTEM)`
     *  解码（inSampleSize 使解码尺寸 ≈ 屏幕，坐标折算对齐）；id 未变直接返回缓存；动态壁纸
     *  （getWallpaperInfo != null，无文件）返回 null → prepareGlass fallback 投递位图。 */
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

    /** 路径 3 兜底：清除时钟数字 View 上系统挂的 RenderEffect（blur 后处理），双保险防路径 1/2 失效。
     *  反射清 `View.mRenderEffect` 字段 = null + `view.renderNode.setRenderEffect(null)`（`View.getRenderNode()`
     *  是 @hide 需反射；`RenderNode.setRenderEffect` public，null 幂等；effect 已空时不触发 invalidate）。
     *  整体 try-catch，单条失败忽略，绝不影响绘制主流程。仅由 isClockDigitView 命中（时钟数字 View）分支
     *  调用，SystemUI 其他 View 不受影响。 */
    private fun clearClockRenderEffect(view: View) {
        try {
            try {
                val f = mRenderEffectField()
                if (f != null) f.set(view, null)
            } catch (t: Throwable) {
                // 反射清字段失败：忽略（RenderNode 路径兜底）
            }
            try {
                val rn = mGetRenderNode(view) as? android.graphics.RenderNode
                if (rn != null) {
                    rn.setRenderEffect(null)
                    if (moduleLogEnabled()) Log.i(TAG, "lockscreen-clock: cleared RenderNode renderEffect")
                } else if (moduleLogEnabled()) {
                    Log.i(TAG, "lockscreen-clock: no RenderNode available to clear")
                }
            } catch (t: Throwable) {
                // RenderNode 清空失败：忽略
            }
        } catch (t: Throwable) {
            // 整体兜底：绝不让清除逻辑影响 onDraw
        }
    }

    private fun mRenderEffectField(): java.lang.reflect.Field? {
        if (mRenderEffectFieldResolved) return mRenderEffectField
        mRenderEffectFieldResolved = true
        mRenderEffectField = try {
            View::class.java.getDeclaredField("mRenderEffect").apply { isAccessible = true }
        } catch (t: Throwable) {
            Log.e(TAG, "lockscreen-clock: View.mRenderEffect field resolve failed (RenderNode path only)", t)
            null
        }
        return mRenderEffectField
    }

    private fun mGetRenderNode(view: View): android.graphics.RenderNode? {
        // 优先 `View.mRenderNode` 私有字段（稳定，Android 16 实测 getRenderNode() 方法名/可见性不可靠）
        if (!mRenderNodeFieldResolved) {
            mRenderNodeFieldResolved = true
            mRenderNodeField = try {
                View::class.java.getDeclaredField("mRenderNode").apply { isAccessible = true }
            } catch (t: Throwable) {
                Log.e(TAG, "lockscreen-clock: View.mRenderNode field resolve failed", t)
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
        // fallback：getRenderNode() 方法（Android 16 可能失败）
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
            val tv = e.key
            if (e.value) {
                try {
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

    /** vibrancy（复用全局玻璃参数）：节流读 Prefs，默认 1.5f。 */
    private var lastVibrancyRead = -1f
    private var lastVibrancyReadAt = 0L
    private fun readVibrancy(): Float {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastVibrancyReadAt < 1000L && lastVibrancyRead >= 0f) return lastVibrancyRead
        lastVibrancyRead = try {
            api?.let { Prefs.read(it).getFloat(Prefs.KEY_VIBRANCY, Prefs.DEFAULT_VIBRANCY) }
                ?.coerceIn(0.5f, 3f) ?: Prefs.DEFAULT_VIBRANCY
        } catch (t: Throwable) {
            Prefs.DEFAULT_VIBRANCY
        }
        lastVibrancyReadAt = now
        return lastVibrancyRead
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
