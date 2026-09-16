package com.coloros16.liquidglass.hook

import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.drawable.Drawable
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceControl
import android.view.View
import android.view.ViewGroup
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.liquidglass.LiquidGlassShader
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.LinkedHashSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * [spec/10] Launcher 进程液态玻璃：桌面文件夹背景玻璃化。
 *
 * 与 SystemUI 共用【同一套渲染 hook】：hook `com.oplus.posteffect.drawable.BaseDrawable#drawBlurShader(Canvas)`
 * 替换 drawableShaderPaint 为液态玻璃 RuntimeShader —— posteffect 在 SystemUI APK 与 Launcher APK
 * 各打包一份、FQCN 相同，两份独立 hook 实例各进程命中。
 *
 * 反编译依据（OplusLauncher\sources）：
 * - `BaseDrawable.drawBlurShader(Canvas)`（L610，public final）：`blurShaderPaint.setAlpha` +
 *   `canvas.drawPath(drawablePath, blurShaderPaint)`。**Launcher 版 Paint getter 是
 *   `getBlurShaderPaint()`（非 SystemUI 的 `getDrawableShaderPaint()`）**，必须单独反射。
 * - 渲染链：LayerBlurDrawable（extends LayerDrawable）持有私有字段 `mBlurDrawable`
 *   （`com.oplus.posteffect.BlurDrawable`，L62）→ LayerDrawable.draw → `mBlurDrawable.draw(canvas)`
 *   → BaseDrawable.draw（L553，canvas.drawRenderNode）→ ContinuousBlurDrawable.onDrawBlurContent
 *   （L180-183：`getBlurShaderPaint().setShader(...)` → `drawBlurShader(canvas)`）→ drawBlurShader 的
 *   **this 与 LayerBlurDrawable.mBlurDrawable 同一实例**（identityHashCode 可作映射键，已结构确认）。
 *
 * Launcher 特有映射 hook（SystemUI 的 AutoBlurDrawable/PlatformBlurDrawable 链在 Launcher 零命中）：
 * **闭合文件夹图标**（两套映射入口，缺一不可）：
 * 1. **常态静止**：hook `com.android.launcher3.folder.FolderRoundImageView.onDraw(Canvas)`（L33，protected）
 *   → `getDrawable()`（= mBgDrawable = LayerBlurDrawable）→ 反射内层 `mBlurDrawable`（BlurDrawable）→
 *   按 identityHashCode 注册 screenRegionMap（region = FolderRoundImageView 屏幕位置 + 尺寸，
 *   圆角 = `getMRadius()` = `mRadius`）。【2026-08-13 新增：drawBackground(Canvas,View) 仅 CellLayout
 *   委派绘制（拖动）时触发，常态静止闭合图标从不调用 → 闭合图标玻璃未生效根因 = 映射从未建立】
 * 2. **拖动/接受动画**：hook `OplusPreviewBackground.drawBackground(Canvas, View)`（L472）→ 同上注册
 *   （region 必须用 mBgView 定位，drawUnderItem 传入的 view 是 CellLayout，不可用于定位）。
 * 【2026-08-13 方向修正】打开的大文件夹卡片不玻璃（移除 Hook 3 Folder.dispatchDraw 手动玻璃），
 * 回退系统纯色填充；闭合文件夹图标要玻璃（修复 Hook 2 常态映射）。
 *
 * 背景源：`IWindowManager.captureDisplay` 整屏排除 Launcher 根 surface（文件夹图标/卡片自身在
 * Launcher 窗口内，排除它 = 抓干净壁纸背景，同 SystemUI 方案）。
 *
 * 强兜底：所有 mount 独立 try-catch + [ExceptionMode.PROTECTIVE]；任一部分失败只影响对应控件
 * （回退系统模糊/纯色），不崩 Launcher。
 */
object LauncherHook {

    private const val TAG = "LiquidGlass"

    // ---- posteffect 渲染 hook（与 SystemUI 同 FQCN，Launcher 版 Paint getter 不同） ----
    private const val CLASS_BASE_DRAWABLE = "com.oplus.posteffect.drawable.BaseDrawable"
    private const val METHOD_DRAW_BLUR_SHADER = "drawBlurShader"
    /** Launcher 版 BaseDrawable Paint getter（反编译 L661）；SystemUI 是 getDrawableShaderPaint */
    private const val METHOD_GET_BLUR_SHADER_PAINT = "getBlurShaderPaint"
    private const val METHOD_GET_PAINT = "getPaint"
    private const val METHOD_GET_BOUNDS = "getBounds"

    // ---- 闭合文件夹图标映射 hook（spec/10） ----
    private const val CLASS_OPLUS_PREVIEW_BACKGROUND = "com.android.launcher3.folder.OplusPreviewBackground"
    private const val METHOD_DRAW_BACKGROUND = "drawBackground"
    private const val FIELD_MBG_DRAWABLE = "mBgDrawable"
    private const val FIELD_MBG_VIEW = "mBgView"
    private const val FIELD_MRADIUS = "mRadius"
    private const val CLASS_LAYER_BLUR_DRAWABLE = "com.android.launcher3.uioverrides.states.blurdrawable.LayerBlurDrawable"
    /** LayerBlurDrawable 私有字段（反编译 L62：`private final BlurDrawable mBlurDrawable`），
     *  与 drawBlurShader 的 this 同一实例。用字段反射避免 Kotlin getter 名歧义。 */
    private const val FIELD_MBLUR_DRAWABLE = "mBlurDrawable"

    // ---- 闭合文件夹图标常态映射 hook（spec/10，2026-08-13 修复：drawBackground 仅拖动态触发） ----
    /** FolderRoundImageView = OplusPreviewBackground.mBgView（子 ImageView，imageDrawable = LayerBlurDrawable）。
     *  常态静止闭合图标的绘制链是 onDraw → drawable.draw，而非 drawBackground(Canvas, View)。 */
    private const val CLASS_FOLDER_ROUND_IMAGE_VIEW = "com.android.launcher3.folder.FolderRoundImageView"
    private const val METHOD_ON_DRAW = "onDraw"
    private const val METHOD_GET_M_RADIUS = "getMRadius"

    // ---- [doc/spec/36 抓屏跟随系统模糊活动 2026-08-13] Launcher 进程系统模糊结果回调（folder 背景刷新信号） ----
    /** Launcher APK 各打包一份 posteffect，BlurDrawableManager FQCN 与 SystemUI 相同；
     *  onBlurReady 单 drawable 重载 = 文件夹背景被系统模糊刷新的时机（取代 spec/33 周期抓屏）。 */
    private const val CLASS_BLUR_DRAWABLE_MANAGER = "com.oplus.posteffect.manager.BlurDrawableManager"
    private const val METHOD_ON_BLUR_READY = "onBlurReady"

    // ---- [doc/spec/52 壁纸文件解码背景源 2026-08-13] ----
    /** hook android.app.WallpaperManager.setWallpaperOffsets（Launcher WallpaperOffsetInterpolator
     *  在 WALLPAPER_TRANSACTION_EXECUTOR 线程调，offset=[0,1]，反编译实证 L63：setWallpaperOffsets
     *  (iBinder, mCurrentOffset, 0.5f)）——滚动壁纸视差跟手（裁剪可见区域跟随）。 */
    private const val CLASS_WALLPAPER_MANAGER = "android.app.WallpaperManager"
    private const val METHOD_SET_WALLPAPER_OFFSETS = "setWallpaperOffsets"

    // ---- [doc/spec/66 2026-09-16] 桌面 Dock 栏液态玻璃化（借壳方案）----
    /** Dock 控件：`com.android.launcher3.OplusHotseat`（extends Hotseat extends CellLayout），
     *  背景 = 字段 `mShortcutsAndWidgets`（父类 CellLayout）的 background。 */
    private const val CLASS_OPLUS_HOTSEAT = "com.android.launcher3.OplusHotseat"
    /** Dock 背景装配入口（OplusHotseat:1564，public void，无参）。 */
    private const val METHOD_SET_DOCKER_BACKGROUND = "setDockerBackground"
    /** 建 blur 背景（OplusHotseat:181，**private** → 反射调用）。直板机因其内部
     *  `hasLargeDisplayFeatures()` 门控不会走到，故需从外部强调（本模块借壳核心）。 */
    private const val METHOD_CREATE_BLUR_DRAWABLE = "createBlurDrawable"
    /** Dock 图标条容器（定义在父类 CellLayout → 沿继承链找）。 */
    private const val FIELD_SHORTCUTS_AND_WIDGETS = "mShortcutsAndWidgets"
    /** 门控方法（ScreenUtils:223，**static**）：`!isDockerExpandDisabled && (isTablet || isFoldScreenExpanded)`。
     *  直板机恒 false → `setDockerBackground()` 全部调用点被挡 → Dock 无任何背景。
     *  ⚠️ 只改这一个，**不动 `hasLargeDisplayFeatures`**（后者被 OplusTaskHeaderView 等复用，强制 true 会误伤）。 */
    private const val CLASS_SCREEN_UTILS = "com.android.common.util.ScreenUtils"
    private const val METHOD_IS_SUPPORT_DOCKER_EXPAND_SCREEN = "isSupportDockerExpandScreen"
    /** Dock 玻璃圆角回退值（dp）：系统 `createBlurDrawable` 用 `R.dimen.dp_20`。 */
    private const val DOCK_CORNER_DP_FALLBACK = 20f
    /** Dock 玻璃开关重读间隔（ms）：改动无需重启 Launcher（同 spec/64/65 的 TTL 模式）。 */
    private const val DOCK_GLASS_TTL_MS = 500L

    /** 整屏快照抓屏任务 id（worker 队列唯一任务；Int.MIN_VALUE 不可能与真实 identityHashCode 冲突） */
    private const val SNAPSHOT_ID = Int.MIN_VALUE
    /** [doc/spec/42 2026-08-13] Launcher 抓屏「只抓壁纸层」UID 过滤：setUid 语义 = 只抓
     *  ownerUid == 指定值 的层（SDK android-34 ScreenCapture.java:434-440「skip any surfaces
     *  that don't belong to the specified uid」）。壁纸层 UID 动态解析（见 resolveWallpaperUid）：
     *  静态壁纸 = SystemUI（com.android.systemui.wallpapers.ImageWallpaper，SF dumpsys 实证内容层
     *  uid=10161），动态壁纸 = WallpaperInfo.serviceInfo 包名 UID。过滤后快照只剩壁纸层
     *  （+SystemUI 透明装饰层，不影响文件夹区域），app 退出轨迹/shade/文件夹浮层/图标天然跳过。
     *  ⚠️ BlurService 的 setUid(-334) 不可复用到 captureDisplay：-334 是其 captureLayers 子树
     *  私有哨兵（反编译 a/d.java g() L550），Launcher 整屏 setUid(-334) 实测 captureDisplay
     *  getBuffer null（SF 无 ownerUid=-334 的层）。 */
    /** 整屏抓屏失败冷却毫秒（3 次用尽后暂停，下个内容变化/渲染兜底重试） */
    private const val ELEMENT_CAPTURE_COOLDOWN_MS = 2000L
    /** CONIC 角权重默认（本模块未用 CONIC，保留常量占位） */
    @Suppress("unused")
    private const val DEFAULT_CORNER_WEIGHT = (8.0f * 1.1f) / (3.0f * 1.1f + 3.0f)

    // ------------------------------------------------------------ 数据结构

    /** screenRegionMap 条目：屏幕区域 + 局部 bounds + 宿主名 + 圆角。 */
    private data class RegionEntry(
        val region: RectF,
        val autoBounds: Rect,
        val hostViewName: String,
        val cornerRadius: Float,
    )

    /** 整屏快照条目：captureDisplay 整屏（排除 Launcher 根 surface，0.5 降采样）干净壁纸背景，共享所有玻璃元素。 */
    private class ElementBackground(
        val bitmap: Bitmap,
        val region: RectF,
    )

    /** [doc/spec/52] 壁纸文件解码源：整幅壁纸位图（inSampleSize 降采样）+ 逻辑原生尺寸 + 壁纸 id。
     *  渲染背景 = 从 [bitmap] 按桌面滚动 offset 裁剪屏幕可见区域（见 buildVisibleCrop）。 */
    private class WallpaperSource(
        val bitmap: Bitmap,
        val logicalWidth: Int,
        val logicalHeight: Int,
        val wallpaperId: Int,
    )

    /** 渲染背景源决策：整屏快照 + 元素区域折算到快照坐标的 srcRect。 */
    private class RenderSource(
        val source: Bitmap,
        val srcRect: RectF,
    )

    /** 已应用 uniform 状态（变化检测：任一变化才重设 uniform）。 */
    private data class AppliedKey(
        val sourceBitmapId: Int,
        val viewport: RectF,
        val srcRect: RectF,
        val cornerRadius: Float,
    )

    private class LiquidShaderBuild(val shader: RuntimeShader)

    /** 已登记宿主 View（运动追踪用，WeakReference 防泄漏）。 */
    private class HostEntry(view: View) {
        val ref = WeakReference(view)
        var lastX = Int.MIN_VALUE
        var lastY = Int.MIN_VALUE
        var lastW = -1
        var lastH = -1
    }

    // ------------------------------------------------------------ 状态字段

    @Volatile
    private var api: XposedInterface? = null

    @Volatile
    private var moduleLogEnabled = false

    // ---- 反射缓存（Launcher classloader 下解析，进程内复用） ----
    private var mGetBlurShaderPaint: Method? = null
    private var mGetPaint: Method? = null
    private var mGetBounds: Method? = null

    private var mBgDrawableField: Field? = null
    private var mBgViewField: Field? = null
    private var mRadiusField: Field? = null
    /** FolderRoundImageView.getMRadius()（L28，public）——常态映射圆角（= OplusPreviewBackground.mRadius） */
    private var mGetFrivRadius: Method? = null
    private var mLayerCls: Class<*>? = null
    private var mLayerBlurInnerField: Field? = null

    // [doc/spec/66] Dock 借壳反射（解析失败只禁 Dock 玻璃，不影响文件夹）
    private var mCreateBlurDrawable: Method? = null
    private var mShortcutsAndWidgetsField: Field? = null
    /** Dock 玻璃开关缓存（TTL 见 [DOCK_GLASS_TTL_MS]，热路径不每帧读 Prefs） */
    @Volatile
    private var cachedDockGlass = false
    @Volatile
    private var cachedDockGlassAtMs = 0L

    // ---- 渲染资源 ----
    /** 每个 drawable（identityHashCode）一个液态玻璃 RuntimeShader 实例（防 uniform 串扰） */
    private val shaderCache = ConcurrentHashMap<Int, RuntimeShader>()
    private val appliedUniformsMap = ConcurrentHashMap<Int, AppliedKey>()
    /** drawable(identityHashCode) → 屏幕区域 + 局部 bounds（映射 hook 建立） */
    private val screenRegionMap = ConcurrentHashMap<Int, RegionEntry>()
    /** 渲染模式日志（GLASS/BLUR 切换才打，防刷屏） */
    private val lastRenderModeMap = ConcurrentHashMap<Int, String>()

    // ---- 整屏快照背景源 ----
    @Volatile
    private var screenSnapshot: ElementBackground? = null
    private val elementCaptureQueue = ConcurrentLinkedQueue<Int>()
    private val pendingCaptureIds = ConcurrentHashMap.newKeySet<Int>()
    private val captureInFlightIds = ConcurrentHashMap.newKeySet<Int>()
    private val elementCaptureRetries = ConcurrentHashMap<Int, Int>()
    private val elementCaptureQuitAt = ConcurrentHashMap<Int, Long>()
    @Volatile
    private var elementCaptureWorkerRunning = 0
    /** 缓存的 Launcher 根 SurfaceControl（反射 hostView.getRootView().getViewRootImpl().getSurfaceControl()） */
    @Volatile
    private var launcherSfcCache: SurfaceControl? = null

    // ---- [doc/spec/52] 壁纸文件解码背景源（静态壁纸零持续抓屏） ----
    /** 整幅壁纸解码源（静态壁纸；动态壁纸不缓存，回退 captureDisplay）。壁纸变化（id 变）才重解码。 */
    @Volatile
    private var wallpaperSource: WallpaperSource? = null
    /** 桌面滚动视差 xOffset（hook setWallpaperOffsets 实时更新，[0,1]；默认 0 = 首屏） */
    @Volatile
    private var wallpaperOffsetX = 0f
    /** 桌面滚动视差 yOffset（Launcher 恒 0.5f，垂直不可滚时无影响） */
    @Volatile
    private var wallpaperOffsetY = 0f
    /** 壁纸解码进行中（防 onBlurReady 多 drawable 同帧连调重复入队） */
    @Volatile
    private var wallpaperDecodePending = false
    /** 待解码/已解码目标壁纸 id（壁纸变化时更新，worker 按最新 id 解码） */
    @Volatile
    private var wallpaperDecodeTargetId = -1

    // ---- 运动追踪（宿主位置/尺寸变化 → invalidate → 重录 → 映射跟随） ----
    private val registeredHostViews = LinkedHashSet<HostEntry>()
    @Volatile
    private var choreographer: Choreographer? = null
    @Volatile
    private var frameCallbackPosted = false

    @Volatile
    private var mainHandler: android.os.Handler? = null

    // ------------------------------------------------------------ 入口

    fun install(api: XposedInterface, classLoader: ClassLoader) {
        this.api = api
        // [2026-08-13 用户要求·关闭日志选项] install 时读取并缓存（热路径不每帧读 Prefs 防 binder 开销）
        moduleLogEnabled = try {
            Prefs.read(api).getBoolean(Prefs.KEY_ENABLE_LOGS, Prefs.DEFAULT_ENABLE_LOGS)
        } catch (t: Throwable) {
            false
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(TAG, "LauncherHook skipped: SDK=${Build.VERSION.SDK_INT} < Q")
            return
        }
        resolveMethods(classLoader)
        mountDrawBlurShader(api, classLoader)
        mountPreviewBackground(api, classLoader)
        mountFolderRoundImageView(api, classLoader)
        // [doc/spec/66] 桌面 Dock 栏液态玻璃化（借壳：解开系统门控让 Dock 拿到 LayerBlurDrawable，
        // 再补映射交给现有 drawBlurShader 替换链渲染玻璃）
        mountDockGlass(api, classLoader)
        mountOnBlurReady(api, classLoader)
        mountWallpaperOffsets(api, classLoader)
        Log.i(TAG, "LauncherHook install done")
    }

    private fun mainHandler(): android.os.Handler {
        mainHandler?.let { return it }
        return synchronized(this) {
            mainHandler ?: android.os.Handler(android.os.Looper.getMainLooper()).also { mainHandler = it }
        }
    }

    // ------------------------------------------------------------ 反射解析

    private fun resolveMethods(classLoader: ClassLoader) {
        // 渲染 hook 反射（失败只禁 drawBlurShader 替换，不崩）
        try {
            val base = Class.forName(CLASS_BASE_DRAWABLE, false, classLoader)
            mGetBlurShaderPaint = base.getMethod(METHOD_GET_BLUR_SHADER_PAINT)
            mGetPaint = base.getMethod(METHOD_GET_PAINT)
            mGetBounds = base.getMethod(METHOD_GET_BOUNDS)
            Log.i(TAG, "LauncherHook resolved BaseDrawable: $METHOD_GET_BLUR_SHADER_PAINT/$METHOD_GET_BOUNDS/$METHOD_GET_PAINT")
        } catch (t: Throwable) {
            Log.e(TAG, "LauncherHook resolve BaseDrawable methods failed, drawBlurShader replace disabled", t)
        }
        // 闭合文件夹图标映射反射（失败只禁文件夹图标映射）
        try {
            val opb = Class.forName(CLASS_OPLUS_PREVIEW_BACKGROUND, false, classLoader)
            mBgDrawableField = opb.getField(FIELD_MBG_DRAWABLE)
            mBgViewField = opb.getField(FIELD_MBG_VIEW)
            mRadiusField = opb.getDeclaredField(FIELD_MRADIUS).apply { isAccessible = true }
            val layer = Class.forName(CLASS_LAYER_BLUR_DRAWABLE, false, classLoader)
            mLayerCls = layer
            mLayerBlurInnerField = layer.getDeclaredField(FIELD_MBLUR_DRAWABLE).apply { isAccessible = true }
            Log.i(TAG, "LauncherHook resolved OplusPreviewBackground/LayerBlurDrawable fields (folder icon map)")
        } catch (t: Throwable) {
            Log.e(TAG, "LauncherHook resolve folder icon fields failed, folder icon map disabled", t)
        }
        // 闭合文件夹图标常态映射反射（FolderRoundImageView.onDraw → imageDrawable → 内层 BlurDrawable）
        try {
            val friv = Class.forName(CLASS_FOLDER_ROUND_IMAGE_VIEW, false, classLoader)
            mGetFrivRadius = friv.getMethod(METHOD_GET_M_RADIUS)
            Log.i(TAG, "LauncherHook resolved FolderRoundImageView.getMRadius (folder icon normal-state map)")
        } catch (t: Throwable) {
            Log.e(TAG, "LauncherHook resolve FolderRoundImageView failed, folder icon map uses fallback radius", t)
        }
        // [doc/spec/66] Dock 借壳反射（createBlurDrawable 是 private → getDeclaredMethod + setAccessible）
        try {
            val hotseat = Class.forName(CLASS_OPLUS_HOTSEAT, false, classLoader)
            mCreateBlurDrawable = hotseat.getDeclaredMethod(METHOD_CREATE_BLUR_DRAWABLE)
                .apply { isAccessible = true }
            // mShortcutsAndWidgets 定义在父类（CellLayout）→ 沿继承链找，不写死所在类
            mShortcutsAndWidgetsField = findFieldAlongHierarchy(hotseat, FIELD_SHORTCUTS_AND_WIDGETS)
            Log.i(TAG, "LauncherHook resolved OplusHotseat.createBlurDrawable/mShortcutsAndWidgets (dock glass)")
        } catch (t: Throwable) {
            Log.e(TAG, "LauncherHook resolve OplusHotseat failed, dock glass disabled", t)
        }
    }

    /** [doc/spec/66] 沿继承链找字段（superclass 逐层 getDeclaredField），找不到返回 null。 */
    private fun findFieldAlongHierarchy(start: Class<*>, name: String): Field? {
        var c: Class<*>? = start
        while (c != null && c != Any::class.java) {
            try {
                return c.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            } catch (t: Throwable) {
                return null
            }
        }
        return null
    }

    // ------------------------------------------------------------ Hook 1：BaseDrawable.drawBlurShader（主绘制）

    /**
     * 渲染 hook：替换 Launcher posteffect BaseDrawable.drawBlurShader 的 blurShaderPaint.shader
     * 为液态玻璃 RuntimeShader。与 SystemUI BlurDrawHook.mountDrawBlurShader 同款，仅 Paint getter 名不同。
     * 命中对象：闭合文件夹图标（LayerBlurDrawable.mBlurDrawable，与映射 hook 注册的实例同 identityHashCode）。
     */
    private fun mountDrawBlurShader(api: XposedInterface, classLoader: ClassLoader) {
        if (mGetBlurShaderPaint == null) {
            Log.w(TAG, "LauncherHook BaseDrawable methods unresolved, drawBlurShader replace disabled")
            return
        }
        try {
            val clazz = Class.forName(CLASS_BASE_DRAWABLE, false, classLoader)
            val method = clazz.getDeclaredMethod(METHOD_DRAW_BLUR_SHADER, Canvas::class.java)
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val drawable = try {
                        chain.getThisObject()
                    } catch (t: Throwable) {
                        Log.e(TAG, "launcher drawBlurShader: getThisObject failed", t); null
                    }
                    if (drawable != null) {
                        try {
                            replaceShaderIfPossible(drawable)
                        } catch (t: Throwable) {
                            Log.e(TAG, "launcher render: drawBlurShader intercept error", t)
                        }
                    }
                    chain.proceed()
                }
            Log.i(TAG, "LauncherHook mounted: $CLASS_BASE_DRAWABLE#$METHOD_DRAW_BLUR_SHADER(Canvas)")
        } catch (t: Throwable) {
            Log.e(TAG, "LauncherHook mount FAILED: $CLASS_BASE_DRAWABLE#$METHOD_DRAW_BLUR_SHADER", t)
        }
    }

    // ------------------------------------------------------------ Hook 2：闭合文件夹图标映射

    /**
     * 拖动态映射 hook：OplusPreviewBackground.drawBackground(Canvas, View)（反编译 L472）。
     *
     * [2026-08-13 修复] drawBackground 只被父类 drawUnderItem 调用（CellLayout.onDraw L2495 遍历
     * mDelegatedCellDrawings），而 mDelegatedCellDrawings 仅在该 PreviewBackground drawingDelegated
     * （拖动/接受动画，mDrawingDelegate != null）时存在 → **常态静止闭合图标 drawBackground 不触发**，
     * 这是闭合图标玻璃未生效的根因。常态映射改由 FolderRoundImageView.onDraw hook 建立（见下）。
     *
     * drawBackground 入口只负责拖动态兜底：映射键 = 内层 BlurDrawable 的 identityHashCode（与
     * drawBlurShader 的 this 同实例，结构确认：LayerDrawable.draw → mBlurDrawable.draw → BaseDrawable.draw
     * → onDrawBlurContent → drawBlurShader(canvas)）。region = mBgView（FolderRoundImageView）屏幕位置 +
     * 尺寸（即玻璃实际绘制区域，与 FolderRoundImageView.onDraw 同源）；圆角 = OplusPreviewBackground.mRadius。
     * 失败只影响文件夹图标映射（回退系统模糊），不崩。
     */
    private fun mountPreviewBackground(api: XposedInterface, classLoader: ClassLoader) {
        if (mBgDrawableField == null || mBgViewField == null || mRadiusField == null || mLayerBlurInnerField == null) {
            Log.w(TAG, "LauncherHook folder icon fields unresolved, folder icon map disabled")
            return
        }
        try {
            val clazz = Class.forName(CLASS_OPLUS_PREVIEW_BACKGROUND, false, classLoader)
            val method = clazz.getMethod(METHOD_DRAW_BACKGROUND, Canvas::class.java, View::class.java)
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        registerPreviewRegion(chain)
                    } catch (t: Throwable) {
                        Log.e(TAG, "launcher preview: drawBackground intercept error", t)
                    }
                    chain.proceed()
                }
            Log.i(TAG, "LauncherHook mounted: $CLASS_OPLUS_PREVIEW_BACKGROUND#$METHOD_DRAW_BACKGROUND(Canvas, View) (folder icon map, drag-delegated state)")
        } catch (t: Throwable) {
            Log.e(TAG, "LauncherHook mount FAILED: $CLASS_OPLUS_PREVIEW_BACKGROUND#$METHOD_DRAW_BACKGROUND", t)
        }
    }

    /**
     * 常态映射 hook：FolderRoundImageView.onDraw(Canvas)（反编译 L33，protected，覆写）。
     *
     * [2026-08-13 新增] 常态静止的闭合文件夹图标绘制链 =
     * FolderIcon.dispatchDraw → 子 View FolderRoundImageView（mBgView）onDraw → getDrawable()
     * （= mBgDrawable = LayerBlurDrawable）setBounds(0,0,w,h) → draw(canvas) → 内层 mBlurDrawable.draw
     * → BaseDrawable.draw → canvas.drawRenderNode → renderNode positionChanged → drawBlurShader。
     * onDraw 每帧触发 → 映射实时建立/更新，this = mBgView，屏幕位置 + 尺寸 = 玻璃区域，圆角 =
     * getMRadius()（= OplusPreviewBackground.mRadius）。drawBlurShader 的 this 与映射键（内层
     * BlurDrawable identityHashCode）同实例。
     */
    private fun mountFolderRoundImageView(api: XposedInterface, classLoader: ClassLoader) {
        if (mLayerBlurInnerField == null) {
            Log.w(TAG, "LauncherHook LayerBlurDrawable.mBlurDrawable unresolved, folder icon normal-state map disabled")
            return
        }
        try {
            val clazz = Class.forName(CLASS_FOLDER_ROUND_IMAGE_VIEW, false, classLoader)
            val method = clazz.getDeclaredMethod(METHOD_ON_DRAW, Canvas::class.java).apply { isAccessible = true }
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        registerFolderRoundImageViewRegion(chain)
                    } catch (t: Throwable) {
                        Log.e(TAG, "launcher preview: FolderRoundImageView.onDraw intercept error", t)
                    }
                    chain.proceed()
                }
            Log.i(TAG, "LauncherHook mounted: $CLASS_FOLDER_ROUND_IMAGE_VIEW#$METHOD_ON_DRAW(Canvas) (folder icon map, normal state)")
        } catch (t: Throwable) {
            Log.e(TAG, "LauncherHook mount FAILED: $CLASS_FOLDER_ROUND_IMAGE_VIEW#$METHOD_ON_DRAW", t)
        }
    }

    // ------------------------------------------------------------ [doc/spec/66] Dock 玻璃（借壳）

    /**
     * Dock 栏液态玻璃化（借壳三步）。反编译实证（OplusLauncher）：
     * - Dock 控件 `OplusHotseat`，背景 = 父类字段 `mShortcutsAndWidgets` 的 background
     * - `OplusHotseat:1564 setDockerBackground()` 的全部调用点都套在 `ScreenUtils.isSupportDockerExpandScreen()`
     *   （`:223` = `!isDockerExpandDisabled && (isTablet || isFoldScreenExpanded)`）里 → **直板机恒 false →
     *   该函数一次都不被调用 → Dock 无任何背景**（既非 blur 也非普通背景，纯透明）
     * - 即便进了该方法，`:1569` 还有 `hasLargeDisplayFeatures()` 门控（`isFoldScreenExpanded || isTablet`）
     *   → 直板机走 `getHotseatNormalBgDrawable`（普通背景）而非 `createBlurDrawable()`（blur 背景）
     *
     * 故：①解开 `isSupportDockerExpandScreen` 门控让系统恢复调用；②在其 after 里反射调 private
     * `createBlurDrawable()` 强建 `LayerBlurDrawable` 覆盖上去（绕过 ②' 的 `hasLargeDisplayFeatures`）；
     * ③补 `screenRegionMap` 映射 —— **本项目特有**（LuckyTool 只需系统模糊显示，我们要用自研玻璃顶掉它，
     * 无映射则 `drawBlurShader` 查表 miss → 回退系统模糊，与文件夹「玻璃不出现」同一坑，见 spec/10）。
     *
     * ⚠️ 只解 `isSupportDockerExpandScreen`，**不动 `hasLargeDisplayFeatures`**（后者被 OplusTaskHeaderView
     * 等复用，强制 true 会误伤；LuckyTool 同款做法，其源码中该 hook 被注释）。
     */
    private fun mountDockGlass(api: XposedInterface, classLoader: ClassLoader) {
        // ① 解开调用点门控：static 方法，interceptor 直接返回 true = 跳过原方法（LibXposed 的 Chain
        //    没有 returnAndSkip/setResult，但 Hooker.intercept 的返回值即被 hook 方法的返回值）
        try {
            val cls = Class.forName(CLASS_SCREEN_UTILS, false, classLoader)
            val method = cls.getMethod(METHOD_IS_SUPPORT_DOCKER_EXPAND_SCREEN)
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    if (dockGlassEnabled()) true else chain.proceed()
                }
            Log.i(TAG, "LauncherHook mounted: $CLASS_SCREEN_UTILS#$METHOD_IS_SUPPORT_DOCKER_EXPAND_SCREEN -> true (dock glass)")
        } catch (t: Throwable) {
            Log.e(TAG, "LauncherHook mount FAILED: $CLASS_SCREEN_UTILS#$METHOD_IS_SUPPORT_DOCKER_EXPAND_SCREEN", t)
        }

        if (mCreateBlurDrawable == null || mShortcutsAndWidgetsField == null) {
            Log.w(TAG, "LauncherHook dock glass: OplusHotseat unresolved, skip remaining dock hooks")
            return
        }

        // ② setDockerBackground() after → 强建 blur 背景（系统逻辑先跑完，再覆盖）
        try {
            val hotseat = Class.forName(CLASS_OPLUS_HOTSEAT, false, classLoader)
            val method = hotseat.getMethod(METHOD_SET_DOCKER_BACKGROUND)
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    try {
                        if (dockGlassEnabled()) forceDockBlurBackground(chain.getThisObject())
                    } catch (t: Throwable) {
                        Log.e(TAG, "launcher dock: force blur background failed", t)
                    }
                    result
                }
            Log.i(TAG, "LauncherHook mounted: $CLASS_OPLUS_HOTSEAT#$METHOD_SET_DOCKER_BACKGROUND (dock blur background)")
        } catch (t: Throwable) {
            Log.e(TAG, "LauncherHook mount FAILED: $CLASS_OPLUS_HOTSEAT#$METHOD_SET_DOCKER_BACKGROUND", t)
        }

        // ③ 常态映射：Dock 每次绘制登记 screenRegionMap（同 spec/10 文件夹的 FolderRoundImageView.onDraw）
        try {
            val hotseat = Class.forName(CLASS_OPLUS_HOTSEAT, false, classLoader)
            val method = hotseat.getMethod(METHOD_ON_DRAW, Canvas::class.java)
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        if (dockGlassEnabled()) registerDockRegion(chain)
                    } catch (t: Throwable) {
                        Log.e(TAG, "launcher dock: onDraw map failed", t)
                    }
                    chain.proceed()
                }
            Log.i(TAG, "LauncherHook mounted: $CLASS_OPLUS_HOTSEAT#$METHOD_ON_DRAW(Canvas) (dock map)")
        } catch (t: Throwable) {
            Log.e(TAG, "LauncherHook mount FAILED: $CLASS_OPLUS_HOTSEAT#$METHOD_ON_DRAW", t)
        }
    }

    /** [doc/spec/66 ②] 反射调 private `createBlurDrawable()` → 设给 `mShortcutsAndWidgets`。
     *  重复调用幂等（`OplusHotseat:191-197`：背景已是 LayerBlurDrawable 且 bounds 匹配时直接返回原对象）。
     *  无子项时清背景（与系统 `setDockerBackground` 的空态处理一致）。 */
    private fun forceDockBlurBackground(hotseat: Any?) {
        if (hotseat == null) return
        val sw = mShortcutsAndWidgetsField?.get(hotseat) as? ViewGroup ?: return
        if (sw.childCount == 0) {
            sw.setBackgroundResource(0)
            return
        }
        val drawable = mCreateBlurDrawable?.invoke(hotseat) as? Drawable ?: return
        sw.background = drawable
    }

    /** [doc/spec/66 ③] Dock 映射登记：`mShortcutsAndWidgets` 的背景（LayerBlurDrawable）→ 内层
     *  posteffect `BlurDrawable` → `screenRegionMap`（与 `drawBlurShader` 的 this 同 identityHashCode）。 */
    private fun registerDockRegion(chain: XposedInterface.Chain) {
        val hotseat = chain.getThisObject() as? View ?: return
        val sw = mShortcutsAndWidgetsField?.get(hotseat) as? View ?: return
        val drawable = sw.background ?: return
        if (mLayerCls?.isInstance(drawable) != true) return
        val inner = try {
            mLayerBlurInnerField?.get(drawable)
        } catch (t: Throwable) {
            Log.e(TAG, "launcher dock: read LayerBlurDrawable.mBlurDrawable failed", t); null
        } ?: return
        registerBlurRegion(inner, sw, resolveDockCornerPx(sw))
    }

    /** [doc/spec/66] Dock 玻璃圆角 px：系统 `createBlurDrawable`（`OplusHotseat:203`）取
     *  `R.dimen.dp_20`；解析不到时按 `20dp × density` 折算。**必须给正值** —— `registerBlurRegion`
     *  对 corner<=0 会回退 `min(w,h)/2`（长条 Dock 会变成胶囊）。 */
    private fun resolveDockCornerPx(host: View): Float {
        return try {
            val res = host.resources
            val id = res.getIdentifier("dp_20", "dimen", host.context.packageName)
            if (id > 0) res.getDimensionPixelSize(id).toFloat()
            else DOCK_CORNER_DP_FALLBACK * res.displayMetrics.density
        } catch (t: Throwable) {
            Log.e(TAG, "launcher dock: resolve corner radius failed, fallback ${DOCK_CORNER_DP_FALLBACK}dp", t)
            DOCK_CORNER_DP_FALLBACK * 3f
        }
    }

    /** [doc/spec/66] Dock 玻璃开关（TTL [DOCK_GLASS_TTL_MS] 重读，改动无需重启 Launcher）。 */
    private fun dockGlassEnabled(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - cachedDockGlassAtMs > DOCK_GLASS_TTL_MS) {
            cachedDockGlassAtMs = now
            cachedDockGlass = try {
                api?.let {
                    Prefs.read(it).getBoolean(Prefs.KEY_DOCK_GLASS_ENABLE, Prefs.DEFAULT_DOCK_GLASS_ENABLE)
                } ?: false
            } catch (t: Throwable) {
                Log.w(TAG, "prefs read dock_glass_enable failed, default false", t)
                false
            }
        }
        return cachedDockGlass
    }

    /**
     * [doc/spec/36 抓屏跟随系统模糊活动 2026-08-13] hook Launcher 进程 BlurDrawableManager.onBlurReady
     * （单 drawable 重载，反编译 OplusLauncher BlurDrawableManager.java L1416）：文件夹背景被系统模糊
     * 刷新时触发（Binder onBlurReady(Bundle) → 分组重载 L976 → 逐 drawable 重载 L1416）。新帧 =
     * 文件夹背景系统模糊活动信号 → 触发整屏快照重抓。
     *
     * 取代 spec/33 周期抓屏（periodicCaptureRunnable，2026-08-13 移除）：**shade 下拉时 Launcher 侧
     * 无文件夹背景模糊活动（模糊管线静止）→ onBlurReady 不触发 → 不抓屏 → 自然抓不到 shade 玻璃**
     * （无需跨进程 exclude shade，见 spec/37）。动态背景（壁纸滚动/动态壁纸）刷新时系统会重新模糊
     * 文件夹背景 → onBlurReady 触发 → 背景跟随实时。
     *
     * 反编译依据（OplusLauncher\sources\com\oplus\posteffect\manager\
     * BlurDrawableManager.java）：L1416 `public final void onBlurReady(List, HardwareBuffer, int, float)`
     * （JADX 注明原 private，Kotlin internal 编译为 public；getDeclaredMethod 可解析），与 SystemUI
     * BlurDrawHook 同签名。失败只禁跟随触发（回退内容变化触发），不崩 Launcher。
     */
    private fun mountOnBlurReady(api: XposedInterface, classLoader: ClassLoader) {
        try {
            val clazz = Class.forName(CLASS_BLUR_DRAWABLE_MANAGER, false, classLoader)
            val method = clazz.getDeclaredMethod(
                METHOD_ON_BLUR_READY,
                List::class.java, HardwareBuffer::class.java,
                Int::class.javaPrimitiveType, Float::class.javaPrimitiveType,
            )
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        // 系统模糊文件夹背景刷新 = 内容变化信号 → 触发整屏快照重抓（无节流立即；
                        // pending 去重防 onBlurReady 多 drawable 同帧连调重复入队）
                        triggerBackgroundCapture()
                    } catch (t: Throwable) {
                        Log.e(TAG, "launcher blur-refresh: onBlurReady intercept error", t)
                    }
                    chain.proceed()
                }
            Log.i(TAG, "LauncherHook mounted: $CLASS_BLUR_DRAWABLE_MANAGER#$METHOD_ON_BLUR_READY(List, HardwareBuffer, int, float) (folder bg blur refresh signal)")
        } catch (t: Throwable) {
            Log.e(TAG, "LauncherHook mount FAILED: $CLASS_BLUR_DRAWABLE_MANAGER#$METHOD_ON_BLUR_READY", t)
        }
    }

    /**
     * [doc/spec/52 壁纸文件解码背景源 2026-08-13] hook `WallpaperManager.setWallpaperOffsets(IBinder,
     * float xOffset, float yOffset)`——桌面翻页滚动壁纸的视差更新信号（反编译 OplusLauncher
     * WallpaperOffsetInterpolator L63 实证：mWM.setWallpaperOffsets(iBinder, mCurrentOffset, 0.5f)，
     * 在 WALLPAPER_TRANSACTION_EXECUTOR 线程调用，offset=[0,1]）。
     *
     * 滚动壁纸背景源 = 壁纸文件按 offset 裁剪可见区域：offset 更新 → 入队 worker 重裁剪（异步，
     * pending 去重，最新 offset 在 worker 处理时生效，单通道与抓屏共用）。静态/非滚动壁纸 offset
     * 不影响裁剪（maxScroll=0）。失败只禁视差跟手（保留静态裁剪），不崩 Launcher。
     */
    private fun mountWallpaperOffsets(api: XposedInterface, classLoader: ClassLoader) {
        try {
            val clazz = Class.forName(CLASS_WALLPAPER_MANAGER, false, classLoader)
            val method = clazz.getMethod(
                METHOD_SET_WALLPAPER_OFFSETS,
                IBinder::class.java, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
            )
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        val args = chain.getArgs()
                        if (args != null && args.size >= 3) {
                            val x = (args[1] as? Float) ?: 0f
                            val y = (args[2] as? Float) ?: 0f
                            wallpaperOffsetX = x
                            wallpaperOffsetY = y
                            // 视差跟手：入队 worker 重裁剪（异步；offset 最新值 worker 处理时读取）
                            enqueueSnapshotCapture()
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "launcher parallax: setWallpaperOffsets intercept error", t)
                    }
                    chain.proceed()
                }
            Log.i(TAG, "LauncherHook mounted: $CLASS_WALLPAPER_MANAGER#$METHOD_SET_WALLPAPER_OFFSETS(IBinder, float, float) (wallpaper parallax)")
        } catch (t: Throwable) {
            Log.e(TAG, "LauncherHook mount FAILED: $CLASS_WALLPAPER_MANAGER#$METHOD_SET_WALLPAPER_OFFSETS", t)
        }
    }

    /** [拖动态] hookBefore：mBgView 屏幕位置 + 尺寸 → 注册内层 BlurDrawable 的 screenRegionMap。 */
    private fun registerPreviewRegion(chain: XposedInterface.Chain) {
        val oplusBg = try {
            chain.getThisObject()
        } catch (t: Throwable) {
            Log.e(TAG, "launcher preview: getThisObject failed", t); return
        } ?: return
        val bgDrawable = try {
            mBgDrawableField?.get(oplusBg) as? Drawable
        } catch (t: Throwable) {
            Log.e(TAG, "launcher preview: read mBgDrawable failed", t); null
        } ?: return
        // 仅 LayerBlurDrawable（含 posteffect BlurDrawable 内层）才注册；非 blur 路径（LayerNormalDrawable/
        // GradientDrawable 纯色背景）不触发 drawBlurShader hook，注册无意义
        if (mLayerCls?.isInstance(bgDrawable) != true) return
        val inner = try {
            mLayerBlurInnerField?.get(bgDrawable)
        } catch (t: Throwable) {
            Log.e(TAG, "launcher preview: read LayerBlurDrawable.mBlurDrawable failed", t); null
        } ?: return
        val radius = try {
            mRadiusField?.getFloat(oplusBg) ?: 0f
        } catch (t: Throwable) {
            0f
        }
        // 定位宿主：mBgView（FolderRoundImageView）。注意 drawUnderItem 传入的 view 参数是 CellLayout
        // （CellLayout.onDraw L2495），不能用它定位；mBgView 的 LayoutParams 即 preview 区域（玻璃绘制区域）。
        val host = try {
            mBgViewField?.get(oplusBg) as? View
        } catch (t: Throwable) {
            Log.e(TAG, "launcher preview: read mBgView failed", t); null
        } ?: return
        registerBlurRegion(inner, host, radius)
    }

    /** [常态] hookBefore：FolderRoundImageView.onDraw → imageDrawable（LayerBlurDrawable）→ 注册映射。 */
    private fun registerFolderRoundImageViewRegion(chain: XposedInterface.Chain) {
        val view = try {
            chain.getThisObject() as? View
        } catch (t: Throwable) {
            Log.e(TAG, "launcher preview: FolderRoundImageView getThisObject failed", t); null
        } ?: return
        val drawable = try {
            (view as? android.widget.ImageView)?.drawable
        } catch (t: Throwable) {
            Log.e(TAG, "launcher preview: FolderRoundImageView getDrawable failed", t); null
        } as? Drawable ?: return
        if (mLayerCls?.isInstance(drawable) != true) return
        val inner = try {
            mLayerBlurInnerField?.get(drawable)
        } catch (t: Throwable) {
            Log.e(TAG, "launcher preview: read LayerBlurDrawable.mBlurDrawable failed", t); null
        } ?: return
        val radius = try {
            mGetFrivRadius?.invoke(view) as? Float ?: 0f
        } catch (t: Throwable) {
            0f
        }
        registerBlurRegion(inner, view, radius)
    }

    /** 公共注册：内层 BlurDrawable → screenRegionMap（region = hostView 屏幕位置 + 尺寸，corner = 圆角）。 */
    private fun registerBlurRegion(inner: Any, hostView: View, corner: Float) {
        if (!hostView.isAttachedToWindow) return
        val loc = IntArray(2)
        try {
            hostView.getLocationOnScreen(loc)
        } catch (t: Throwable) {
            Log.e(TAG, "launcher preview: getLocationOnScreen failed", t); return
        }
        val bw = hostView.width
        val bh = hostView.height
        if (bw <= 0 || bh <= 0) return
        val region = RectF(
            loc[0].toFloat(),
            loc[1].toFloat(),
            (loc[0] + bw).toFloat(),
            (loc[1] + bh).toFloat(),
        )
        val key = System.identityHashCode(inner)
        val resolvedCorner = if (corner > 0f) corner else minOf(bw, bh) / 2f
        registerHostView(hostView)
        val entry = RegionEntry(region, Rect(0, 0, bw, bh), "FolderIcon", resolvedCorner)
        val previous = screenRegionMap[key]
        if (previous == null) {
            screenRegionMap[key] = entry
            if (screenRegionMap.size > 512) screenRegionMap.clear()
            Log.i(
                TAG,
                "coord: folder-icon id=$key view=${hostView.javaClass.simpleName} loc=[${loc[0]},${loc[1]}] " +
                    "size=${bw}x${bh} cornerRadius=$resolvedCorner"
            )
            // [doc/spec/07 + 2026-08-13] 新映射建立 = 内容变化信号 → 触发整屏快照重抓（无节流立即）
            triggerBackgroundCapture()
        } else if (previous.region != region) {
            screenRegionMap[key] = entry
            // 位置/尺寸更新仅写表不重抓（背景为静态壁纸快照，srcRect 渲染侧实时折算已跟手）
        }
    }

    // ------------------------------------------------------------ 核心：替换 shader（强兜底）

    /**
     * 把 blurShaderPaint.shader 替换为液态玻璃 RuntimeShader。
     * 强兜底契约：任何失败都不动 paint（保持系统原样），返回 false → 调用方回退系统绘制。
     */
    private fun replaceShaderIfPossible(drawable: Any): Boolean {
        val id = System.identityHashCode(drawable)
        val paint = try {
            mGetBlurShaderPaint?.invoke(drawable) as? Paint
        } catch (t: Throwable) {
            Log.e(TAG, "launcher render: getBlurShaderPaint failed", t); null
        }
        if (paint == null) {
            logRenderMode(id, "BLUR", "no blurShaderPaint")
            return false
        }
        val bounds = try {
            mGetBounds?.invoke(drawable) as? Rect
        } catch (t: Throwable) {
            Log.e(TAG, "launcher render: getBounds failed", t); null
        }
        if (bounds == null || bounds.width() <= 0 || bounds.height() <= 0) {
            logRenderMode(id, "BLUR", "empty bounds")
            return false
        }
        val current = try {
            paint.shader
        } catch (t: Throwable) {
            null
        }
        // 已是本模块缓存的液态玻璃实例 → 每帧重跑 uniform（源/尺寸/位置变化才重设）
        if (current is RuntimeShader && shaderCache.containsValue(current)) {
            return refreshShaderUniforms(paint, current, id, bounds)
        }
        val liquid = try {
            buildLiquidShader(drawable, bounds)
        } catch (t: Throwable) {
            Log.w(TAG, "launcher render: buildLiquidShader exception (id=$id)", t)
            null
        } ?: run {
            logRenderMode(id, "BLUR", "no map or source")
            return false
        }
        val original = try {
            paint.shader
        } catch (t: Throwable) {
            null
        }
        try {
            paint.shader = liquid.shader
        } catch (t: Throwable) {
            try {
                paint.shader = original
            } catch (ignored: Throwable) {
            }
            logRenderMode(id, "BLUR", "setShader failed")
            return false
        }
        logRenderMode(id, "GLASS")
        return true
    }

    /** 构建液态玻璃 shader（查映射 → 快照源 → 缓存 shader → setUniforms）。map/source 缺失返回 null。 */
    private fun buildLiquidShader(drawable: Any, bounds: Rect): LiquidShaderBuild? {
        val id = System.identityHashCode(drawable)
        val entry = screenRegionMap[id] ?: return null
        val region = entry.region
        if (region.width() <= 0f || region.height() <= 0f) return null
        val renderSource = resolveRenderSource(id, region) ?: return null
        val srcRect = renderSource.srcRect
        val source = renderSource.source
        val shader = shaderCache.getOrPut(id) { LiquidGlassShader.create() }
        if (shaderCache.size > 128) shaderCache.clear()
        val params = buildParams(bounds.width().toFloat(), bounds.height().toFloat(), entry.cornerRadius)
        val sourceShader = LiquidGlassShader.createSourceBitmapShader(source)
        LiquidGlassShader.setUniforms(
            shader, params, sourceShader, sourceShader,
            sourceRect = srcRect, lowResSourceRect = srcRect,
        )
        appliedUniformsMap[id] = AppliedKey(
            System.identityHashCode(source),
            RectF(0f, 0f, bounds.width().toFloat(), bounds.height().toFloat()),
            srcRect,
            entry.cornerRadius,
        )
        if (appliedUniformsMap.size > 128) appliedUniformsMap.clear()
        return LiquidShaderBuild(shader)
    }

    /** [R1] 已替换的液态玻璃实例每帧刷新 uniform；失败不破坏现有绘制（返回 true 保持玻璃）。 */
    private fun refreshShaderUniforms(paint: Paint, shader: RuntimeShader, id: Int, bounds: Rect): Boolean {
        try {
            val entry = screenRegionMap[id] ?: return true
            val region = entry.region
            if (region.width() <= 0f || region.height() <= 0f) return true
            val renderSource = resolveRenderSource(id, region) ?: return true
            val srcRect = renderSource.srcRect
            val viewport = RectF(0f, 0f, bounds.width().toFloat(), bounds.height().toFloat())
            val sourceBitmapId = System.identityHashCode(renderSource.source)
            val applied = appliedUniformsMap[id]
            if (applied != null && applied.sourceBitmapId == sourceBitmapId &&
                applied.viewport == viewport && applied.srcRect == srcRect &&
                applied.cornerRadius == entry.cornerRadius
            ) {
                return true
            }
            val params = buildParams(bounds.width().toFloat(), bounds.height().toFloat(), entry.cornerRadius)
            if (applied == null || applied.sourceBitmapId != sourceBitmapId) {
                val sourceShader = LiquidGlassShader.createSourceBitmapShader(renderSource.source)
                LiquidGlassShader.setUniforms(
                    shader, params, sourceShader, sourceShader,
                    sourceRect = srcRect, lowResSourceRect = srcRect,
                )
            } else {
                LiquidGlassShader.setUniforms(
                    shader, params, null, null,
                    sourceRect = srcRect, lowResSourceRect = srcRect,
                )
            }
            appliedUniformsMap[id] = AppliedKey(sourceBitmapId, viewport, srcRect, entry.cornerRadius)
            if (appliedUniformsMap.size > 128) appliedUniformsMap.clear()
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "launcher render: refresh uniform failed, keep existing liquid glass shader", t)
            return true
        }
    }

    /**
     * [整屏快照 doc/spec/07 + 2026-08-13] 解析渲染背景源：共享整屏快照，srcRect = 元素当前 region
     * 折算到快照坐标（移动实时跟手，不重抓）。快照缺失 → 异步请求后台 worker 抓屏，本帧 null 回退系统模糊。
     */
    private fun resolveRenderSource(id: Int, screenRegion: RectF): RenderSource? {
        val snap = requestScreenSnapshotAsync()
        if (snap != null && snap.bitmap.width > 0 && snap.bitmap.height > 0 && snap.region.width() > 0f) {
            val scaleX = snap.bitmap.width.toFloat() / snap.region.width()
            val scaleY = snap.bitmap.height.toFloat() / snap.region.height()
            val srcRect = RectF(
                (screenRegion.left - snap.region.left) * scaleX,
                (screenRegion.top - snap.region.top) * scaleY,
                (screenRegion.right - snap.region.left) * scaleX,
                (screenRegion.bottom - snap.region.top) * scaleY,
            )
            return RenderSource(snap.bitmap, srcRect)
        }
        return null
    }

    /** 渲染触发整屏快照请求：**异步**（不阻塞渲染线程；失败冷却期内不重抓）。快照缺失返回 null。 */
    private fun requestScreenSnapshotAsync(): ElementBackground? {
        val snap = screenSnapshot
        val valid = snap != null && snap.bitmap.width > 0 && snap.bitmap.height > 0
        if (valid) return snap
        val now = SystemClock.uptimeMillis()
        if (now < (elementCaptureQuitAt[SNAPSHOT_ID] ?: 0L)) return null
        if (!captureInFlightIds.contains(SNAPSHOT_ID) && pendingCaptureIds.add(SNAPSHOT_ID)) {
            elementCaptureQueue.add(SNAPSHOT_ID)
            kickElementCaptureWorker()
            Log.d(TAG, "bg-element: render-trigger async launcher snapshot capture (non-blocking)")
        }
        return null
    }

    // ------------------------------------------------------------ 整屏快照抓屏（同 SystemUI 方案）

    /**
     * 内容变化 → 触发背景源刷新（无节流立即入队；异步 worker 消费，不阻塞主线程/渲染线程；静止零抓屏）。
     *
     * [doc/spec/52] 壁纸文件解码背景源开启时：静态壁纸背景 = 壁纸内容，**不需要持续抓屏**——
     * onBlurReady 频繁回调 / 新映射建立 都只在壁纸变化（id 变）时真正重解码（refreshWallpaperSourceIfChanged
     * 内部判定），其余情况 no-op，消除 Launcher 侧持续抓屏耗电。动态壁纸/解码失败回退 captureDisplay 抓屏。
     */
    private fun triggerBackgroundCapture() {
        if (wallpaperFileSourceEnabled()) {
            refreshWallpaperSourceIfChanged()
            return
        }
        enqueueSnapshotCapture()
    }

    /** 入队背景源刷新任务（唯一任务 = SNAPSHOT_ID 整屏快照/壁纸解码；pending 去重防 onBlurReady
     *  多 drawable 同帧连调重复入队）。失败冷却期内不重入队（3 次失败暂停，下个内容变化/渲染兜底重试）。 */
    private fun enqueueSnapshotCapture() {
        val now = SystemClock.uptimeMillis()
        if (now < (elementCaptureQuitAt[SNAPSHOT_ID] ?: 0L)) return
        if (pendingCaptureIds.add(SNAPSHOT_ID)) {
            elementCaptureQueue.add(SNAPSHOT_ID)
            kickElementCaptureWorker()
        }
    }

    /**
     * [doc/spec/52] 壁纸文件解码背景源的门控：内容变化信号进来，判断是否需要真正重解码。
     * - 静态壁纸：壁纸 id 未变 && 已解码 → no-op（不抓屏、不重解码）。
     * - 静态壁纸 id 变 / 首次 → 置 wallpaperDecodePending + 入队 worker 解码。
     * - 动态壁纸 / WallpaperManager 拿不到 → 清静态源 + 入队回退 captureDisplay 抓屏。
     */
    private fun refreshWallpaperSourceIfChanged() {
        val wm = wallpaperManager()
        if (wm == null) {
            enqueueSnapshotCapture()
            return
        }
        val isLive = try {
            wm.wallpaperInfo != null
        } catch (t: Throwable) {
            false
        }
        if (isLive) {
            // 动态壁纸：静态文件源不可用 → 回退 captureDisplay 抓屏（spec/42 setUid 方案，resolveWallpaperUid 已解析动态壁纸 UID）
            wallpaperSource = null
            wallpaperDecodePending = false
            enqueueSnapshotCapture()
            return
        }
        val id = try {
            wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM)
        } catch (t: Throwable) {
            -1
        }
        if (id <= 0) {
            enqueueSnapshotCapture()
            return
        }
        val src = wallpaperSource
        if (src != null && src.wallpaperId == id && !wallpaperDecodePending) {
            // 壁纸未变：no-op（静态壁纸内容不变，无需重抓/重解码）
            return
        }
        wallpaperDecodeTargetId = id
        if (!wallpaperDecodePending) {
            wallpaperDecodePending = true
            enqueueSnapshotCapture()
        }
    }

    private fun kickElementCaptureWorker() {
        synchronized(this) {
            if (elementCaptureWorkerRunning > 0) return
            elementCaptureWorkerRunning++
            try {
                Thread { runElementCaptureWorker() }.start()
            } catch (t: Throwable) {
                elementCaptureWorkerRunning--
                Log.e(TAG, "bg-element: spawn worker failed", t)
            }
        }
    }

    /** 抓屏 worker：消费队列（唯一任务 = SNAPSHOT_ID 整屏快照/壁纸解码）。 */
    private fun runElementCaptureWorker() {
        try {
            while (true) {
                val id = elementCaptureQueue.poll() ?: break
                pendingCaptureIds.remove(id)
                if (id != SNAPSHOT_ID) continue
                // [doc/spec/52] 壁纸文件解码背景源优先（静态壁纸零持续抓屏）；解码失败/动态壁纸
                // 回退 captureDisplay 抓屏（spec/42 setUid 只抓壁纸层，兼容向后）。
                captureInFlightIds.add(id)
                val bg = try {
                    if (wallpaperFileSourceEnabled()) {
                        refreshWallpaperBackground() ?: captureScreenSnapshotWithSfc()
                    } else {
                        captureScreenSnapshotWithSfc()
                    }
                } finally {
                    captureInFlightIds.remove(id)
                }
                if (bg != null) {
                    screenSnapshot = bg
                    elementCaptureRetries[id] = 0
                    elementCaptureQuitAt.remove(id)
                    Log.i(TAG, "bg-element: launcher background ready ${bg.bitmap.width}x${bg.bitmap.height} region=${bg.region.toShortString()}")
                    postInvalidateRegistered()
                } else {
                    // 失败重试（3 次/320ms），用尽冷却
                    val n = (elementCaptureRetries[id] ?: 0) + 1
                    elementCaptureRetries[id] = n
                    if (n < 3) {
                        try {
                            Thread.sleep(320L)
                        } catch (ignored: InterruptedException) {
                        }
                        enqueueCapture(id)
                    } else {
                        elementCaptureRetries[id] = 0
                        elementCaptureQuitAt[id] = SystemClock.uptimeMillis() + ELEMENT_CAPTURE_COOLDOWN_MS
                        Log.w(TAG, "bg-element: launcher background capture failed 3 x, cooldown ${ELEMENT_CAPTURE_COOLDOWN_MS}ms")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "bg-element: worker error", t)
        } finally {
            synchronized(this) {
                if (elementCaptureWorkerRunning > 0) elementCaptureWorkerRunning--
            }
        }
    }

    /** 回退抓屏：解析 Launcher 根 surface → captureDisplay 整屏排除 Launcher 窗口（spec/42 setUid 壁纸层）。
     *  sfc 解析失败返回 null（走失败重试/冷却，不静默丢弃）。 */
    private fun captureScreenSnapshotWithSfc(): ElementBackground? {
        // [doc/spec/42] SystemUI 弹窗（USB 选择等，ownerUid=SystemUI=壁纸同 UID）setUid 无法
        // 排除；曾尝试反射 IWindowManager.getVisibleWindows() 检测弹窗跳过抓屏，实测 ColorOS 16
        // 该方法已移除（hidden API 限制），检测失效已移除。根治=跨进程 exclude（见 spec/42）。
        val launcherSfc = resolveLauncherSfc()
        if (launcherSfc == null || !launcherSfc.isValid) return null
        return captureScreenSnapshot(launcherSfc)
    }

    private fun enqueueCapture(id: Int) {
        if (pendingCaptureIds.add(id)) {
            elementCaptureQueue.add(id)
        }
    }

    /**
     * 解析 Launcher 根 SurfaceControl：优先缓存；否则遍历已登记宿主 View，取任一 attach 宿主
     * getRootView() → ViewRootImpl.getSurfaceControl()（反射，@SystemApi）。失败返回 null（静默）。
     */
    @Synchronized
    private fun resolveLauncherSfc(): SurfaceControl? {
        launcherSfcCache?.let { if (it.isValid) return it }
        for (entry in registeredHostViews) {
            val v = entry.ref.get() ?: continue
            if (!v.isAttachedToWindow) continue
            val rootView = try {
                v.rootView
            } catch (t: Throwable) {
                null
            }
            if (rootView == null) continue
            val sfc = reflectViewRootSurfaceControl(rootView)
            if (sfc != null && sfc.isValid) {
                launcherSfcCache = sfc
                Log.i(TAG, "bg-source: launcher sfc resolved from ${rootView.javaClass.simpleName}, name=${reflectSurfaceControlName(sfc)}")
                return sfc
            }
        }
        return null
    }

    /** 反射 View.getViewRootImpl() → ViewRootImpl.getSurfaceControl()（@SystemApi/@UnsupportedAppUsage）。 */
    private fun reflectViewRootSurfaceControl(rootView: View): SurfaceControl? {
        return try {
            val vri = rootView.javaClass.getMethod("getViewRootImpl").invoke(rootView)
            if (vri == null) {
                null
            } else {
                vri.javaClass.getMethod("getSurfaceControl").invoke(vri) as? SurfaceControl
            }
        } catch (t: Throwable) {
            Log.e(TAG, "bg-source: getViewRootImpl().getSurfaceControl() reflect failed", t)
            null
        }
    }

    /** 反射 SurfaceControl.getName()（@SystemApi）→ 层名（仅日志）。失败 null。 */
    private fun reflectSurfaceControlName(sfc: SurfaceControl): String? = try {
        sfc.javaClass.getMethod("getName").invoke(sfc) as? String
    } catch (t: Throwable) {
        null
    }

    /** 整屏快照抓屏：captureDisplay 整屏（region=全屏）排除 Launcher 根 surface + 0.5 降采样，copy 独立位图。 */
    private fun captureScreenSnapshot(launcherSfc: SurfaceControl): ElementBackground? {
        val dm = android.content.res.Resources.getSystem().displayMetrics
        val region = RectF(0f, 0f, dm.widthPixels.toFloat(), dm.heightPixels.toFloat())
        return captureElementBackground(launcherSfc, region)
    }

    /**
     * 反射 IWindowManager.captureDisplay 抓【region 屏幕区域】排除 launcherSfc → 干净壁纸 HardwareBuffer
     * （0.5 降采样），wrap+copy 出独立软件位图后 close hw。失败返回 null（静默，兜底铁律）。
     */
    private fun captureElementBackground(launcherSfc: SurfaceControl, region: RectF): ElementBackground? {
        val dm = android.content.res.Resources.getSystem().displayMetrics
        val maxW = dm.widthPixels.coerceAtLeast(1)
        val maxH = dm.heightPixels.coerceAtLeast(1)
        val crop = Rect(
            region.left.toInt().coerceIn(0, maxW),
            region.top.toInt().coerceIn(0, maxH),
            region.right.toInt().coerceIn(0, maxW),
            region.bottom.toInt().coerceIn(0, maxH),
        )
        if (crop.width() <= 0 || crop.height() <= 0) {
            Log.w(TAG, "bg-element: empty crop after clamp $region -> $crop")
            return null
        }
        return try {
            val wmBinder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "window")
            if (wmBinder == null) {
                Log.w(TAG, "bg-element: ServiceManager.getService(window) null")
                null
            } else {
                val wm = Class.forName("android.view.IWindowManager\$Stub")
                    .getMethod("asInterface", IBinder::class.java).invoke(null, wmBinder)
                if (wm == null) {
                    Log.w(TAG, "bg-element: IWindowManager.Stub.asInterface null")
                    null
                } else {
                    val captureCls = Class.forName("android.window.ScreenCapture")
                    val argsCls = Class.forName("android.window.ScreenCapture\$CaptureArgs")
                    val builderCls = Class.forName("android.window.ScreenCapture\$CaptureArgs\$Builder")
                    val builder = builderCls.getConstructor().newInstance()
                    val scale = bgCaptureScale()
                    builderCls.getMethod("setSourceCrop", Rect::class.java).invoke(builder, crop)
                    builderCls.getMethod("setExcludeLayers", Array<SurfaceControl>::class.java)
                        .invoke(builder, arrayOf(launcherSfc))
                    builderCls.getMethod("setFrameScale", Float::class.javaPrimitiveType).invoke(builder, scale)
                    // [doc/spec/42 2026-08-13] 只抓壁纸层：整屏 captureDisplay 只 exclude [launcherSfc]
                    // 会把非 Launcher 窗口层（正在退出的 app 窗口轨迹、shade、状态栏等）一起拍进快照 =
                    // 文件夹背景"没抓干净"。setUid 语义 = 只抓 ownerUid 匹配的层（SDK
                    // ScreenCapture.java:434-440 实证），按壁纸层 ownerUid 过滤后快照只剩壁纸层，
                    // app 退出轨迹/非 SystemUI 窗口层天然跳过（SystemUI 自身窗口见 spec/42 边界）。
                    // ⚠️ BlurService 的 setUid(-334) 是其 captureLayers 子树私有哨兵，captureDisplay
                    // 实测 getBuffer null 抓空，不可复用。
                    // 开关 KEY_BG_CAPTURE_UID_FILTER（默认 true）可关；方法不存在/反射失败则保持现行为。
                    if (bgCaptureUidFilterEnabled()) {
                        val uid = resolveWallpaperUid()
                        if (uid != null) {
                            try {
                                try {
                                    builderCls.getMethod("setUid", Long::class.javaPrimitiveType)
                                        .invoke(builder, uid)
                                } catch (nsme: NoSuchMethodException) {
                                    builderCls.getMethod("setUid", Int::class.javaPrimitiveType)
                                        .invoke(builder, uid.toInt())
                                }
                                Log.i(TAG, "bg-element: setUid($uid) wallpaper-only capture applied")
                            } catch (t: Throwable) {
                                Log.w(TAG, "bg-element: setUid unavailable (${t.message}), keep full-display capture")
                            }
                        } else {
                            Log.w(TAG, "bg-element: wallpaper uid unresolved, keep full-display capture")
                        }
                    }
                    val args = builderCls.getMethod("build").invoke(builder)
                    if (args == null) {
                        Log.w(TAG, "bg-element: CaptureArgs.build() null")
                        null
                    } else {
                        val listener = captureCls.getMethod("createSyncCaptureListener").invoke(null)
                        if (listener == null) {
                            Log.w(TAG, "bg-element: createSyncCaptureListener() null")
                            null
                        } else {
                            val listenerCls = Class.forName("android.window.ScreenCapture\$ScreenCaptureListener")
                            val m = wm.javaClass.getMethod(
                                "captureDisplay",
                                Int::class.javaPrimitiveType, argsCls, listenerCls
                            )
                            m.invoke(wm, 0, args, listener)
                            val shb = listener.javaClass.getMethod("getBuffer").invoke(listener)
                            if (shb == null) {
                                Log.w(TAG, "bg-element: captureDisplay getBuffer null")
                                null
                            } else {
                                val hb = shb.javaClass.getMethod("getHardwareBuffer").invoke(shb) as? HardwareBuffer
                                if (hb == null) {
                                    Log.w(TAG, "bg-element: ScreenshotHardwareBuffer.getHardwareBuffer() null")
                                    null
                                } else if (hb.isClosed) {
                                    Log.w(TAG, "bg-element: captureDisplay returned closed buffer")
                                    null
                                } else {
                                    try {
                                        val wrapped = Bitmap.wrapHardwareBuffer(hb, null)
                                        if (wrapped == null) {
                                            Log.w(TAG, "bg-element: wrapHardwareBuffer null hw=${hb.width}x${hb.height}")
                                            null
                                        } else {
                                            try {
                                                val copy = wrapped.copy(Bitmap.Config.ARGB_8888, false)
                                                if (copy == null) {
                                                    Log.w(TAG, "bg-element: copy null hw=${hb.width}x${hb.height}")
                                                    null
                                                } else {
                                                    Log.i(TAG, "bg-element: IWindowManager.captureDisplay ok hw=${hb.width}x${hb.height} crop=$crop (${scale}x, excl launcher)")
                                                    ElementBackground(copy, region)
                                                }
                                            } finally {
                                                try {
                                                    wrapped.recycle()
                                                } catch (ignored: Throwable) {
                                                }
                                            }
                                        }
                                    } finally {
                                        try {
                                            hb.close()
                                        } catch (ignored: Throwable) {
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "bg-element: IWindowManager.captureDisplay failed", t)
            null
        }
    }

    /** 抓屏降采样比例（Prefs KEY_BG_CAPTURE_SCALE，clamp 0.25~1.0，默认 0.5） */
    private fun bgCaptureScale(): Float {
        val a = api ?: return Prefs.DEFAULT_BG_CAPTURE_SCALE
        return try {
            Prefs.read(a).getFloat(Prefs.KEY_BG_CAPTURE_SCALE, Prefs.DEFAULT_BG_CAPTURE_SCALE)
                .coerceIn(0.25f, 1.0f)
        } catch (t: Throwable) {
            Log.w(TAG, "prefs read bg_capture_scale failed, default 0.5", t)
            Prefs.DEFAULT_BG_CAPTURE_SCALE
        }
    }

    /** [doc/spec/42] 抓屏 UID 过滤开关（Prefs KEY_BG_CAPTURE_UID_FILTER，默认 true）：
     *  true=CaptureArgs.setUid(壁纸层 UID) 只抓壁纸层；false=不过滤（整屏 exclude [launcherSfc]）。 */
    private fun bgCaptureUidFilterEnabled(): Boolean {
        val a = api ?: return Prefs.DEFAULT_BG_CAPTURE_UID_FILTER
        return try {
            Prefs.read(a).getBoolean(Prefs.KEY_BG_CAPTURE_UID_FILTER, Prefs.DEFAULT_BG_CAPTURE_UID_FILTER)
        } catch (t: Throwable) {
            Log.w(TAG, "prefs read bg_capture_uid_filter failed, default true", t)
            Prefs.DEFAULT_BG_CAPTURE_UID_FILTER
        }
    }

    /**
     * [doc/spec/42] 解析壁纸层 ownerUid（setUid 按 ownerUid 只抓该层）：
     * - 静态壁纸 = SystemUI 包 UID（SF dumpsys 实证壁纸内容层
     *   com.android.systemui.wallpapers.ImageWallpaper#61134 uid=10161）；
     * - 动态壁纸 = [WallpaperManager.getWallpaperInfo] 的 serviceInfo 包名 UID。
     * 解析失败返回 null（调用方跳过 setUid，保持全屏抓屏）。
     * 不缓存：壁纸切换后 UID 可能变化，每次抓屏动态解析（binder 轻量）。
     */
    private fun resolveWallpaperUid(): Long? {
        return try {
            val app = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null) as? android.content.Context
                ?: return null
            val pkg = try {
                android.app.WallpaperManager.getInstance(app).wallpaperInfo
                    ?.serviceInfo?.packageName
            } catch (t: Throwable) {
                null
            } ?: "com.android.systemui"
            val uid = app.packageManager.getPackageUid(pkg, 0).toLong()
            Log.i(TAG, "bg-element: wallpaper uid resolved pkg=$pkg uid=$uid")
            uid
        } catch (t: Throwable) {
            Log.w(TAG, "bg-element: resolve wallpaper uid failed", t)
            null
        }
    }

    // ------------------------------------------------------------ [spec/52] 壁纸文件解码背景源

    /**
     * [doc/spec/52] worker 线程执行：刷新壁纸文件背景源。
     * - 首次 / 壁纸变化（wallpaperDecodePending 或源缺失）→ 解码整幅壁纸文件 → 缓存
     *   [wallpaperSource] → 按当前 offset 裁剪屏幕可见区域返回。
     * - 已解码 → 按最新 offset 重裁剪（滚动壁纸视差跟手）。
     * - 动态壁纸 / 解码失败 → null（调用方回退 captureDisplay 抓屏，保持向后兼容）。
     */
    private fun refreshWallpaperBackground(): ElementBackground? {
        val src = wallpaperSource
        if (wallpaperDecodePending || src == null) {
            val decoded = decodeWallpaperSource()
            wallpaperDecodePending = false
            if (decoded == null) return null
            wallpaperSource = decoded
            wallpaperDecodeTargetId = decoded.wallpaperId
            syncWallpaperOffsetsFromSystem()
            Log.i(
                TAG,
                "bg-wallpaper: decoded id=${decoded.wallpaperId} bmp=${decoded.bitmap.width}x${decoded.bitmap.height} " +
                    "logical=${decoded.logicalWidth}x${decoded.logicalHeight}"
            )
            return buildVisibleCrop(decoded)
        }
        return buildVisibleCrop(src)
    }

    /**
     * [doc/spec/52] 解码整幅壁纸文件（worker 线程）。
     * `WallpaperManager.getWallpaperFile(FLAG_SYSTEM)` → PFD → inSampleSize 降采样解码（防大壁纸内存过高）。
     * - 动态壁纸（getWallpaperInfo != null）：返回 null（调用方回退 captureDisplay）。
     * - 记录逻辑原生尺寸 [logicalWidth/Height]（滚动裁剪折算用）与壁纸 id（变化检测用）。
     */
    private fun decodeWallpaperSource(): WallpaperSource? {
        return try {
            val wm = wallpaperManager() ?: return null
            // 动态壁纸：无文件可解码 → 回退 captureDisplay 抓屏
            val info = try {
                wm.wallpaperInfo
            } catch (t: Throwable) {
                null
            }
            if (info != null) {
                Log.w(TAG, "bg-wallpaper: live wallpaper, fallback to captureDisplay")
                return null
            }
            val id = try {
                wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM)
            } catch (t: Throwable) {
                -1
            }
            if (id <= 0) {
                Log.w(TAG, "bg-wallpaper: getWallpaperId(FLAG_SYSTEM)=$id, fallback to captureDisplay")
                return null
            }
            val pfd = try {
                wm.getWallpaperFile(WallpaperManager.FLAG_SYSTEM)
            } catch (t: Throwable) {
                Log.w(TAG, "bg-wallpaper: getWallpaperFile failed", t)
                null
            } ?: return null
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                pfd.dup().use { d -> BitmapFactory.decodeFileDescriptor(d.fileDescriptor, null, bounds) }
                val wpW = bounds.outWidth
                val wpH = bounds.outHeight
                if (wpW <= 0 || wpH <= 0) {
                    Log.w(TAG, "bg-wallpaper: decode bounds invalid ${wpW}x$wpH")
                    return null
                }
                var sample = 1
                val cap = wallpaperMaxDecodeDim()
                while (maxOf(wpW, wpH) / sample > cap) sample *= 2
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bmp = pfd.dup().use { d -> BitmapFactory.decodeFileDescriptor(d.fileDescriptor, null, opts) }
                if (bmp == null || bmp.width <= 0 || bmp.height <= 0) {
                    Log.w(TAG, "bg-wallpaper: decode file failed")
                    return null
                }
                WallpaperSource(bmp, wpW, wpH, id)
            } finally {
                try {
                    pfd.close()
                } catch (ignored: Throwable) {
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "bg-wallpaper: decode failed", t)
            null
        }
    }

    /**
     * [doc/spec/52] 从整幅壁纸按当前桌面滚动 offset 裁剪**屏幕可见区域**，缩放到屏宽屏高 × 背景降采样比例。
     *
     * 折算（Android 壁纸贴屏语义）：壁纸缩放 scale = max(scrW/wpW, scrH/wpH) 贴满屏幕 → 可见窗口
     * （scrW×scrH）在壁纸文件坐标 = scrW/scale × scrH/scale，横向滚动量 = (wpW - cropW) × xOffset。
     * 产出 ElementBackground(bitmap=可见裁剪, region=全屏)——region 全屏 + bitmap 屏尺寸 ⇒
     * resolveRenderSource 的 scaleX/Y ≈ 1，srcRect ≈ 元素屏幕区域（与"整屏快照"数据流完全一致，
     * 渲染侧零改动）。
     *
     * 静态/非滚动壁纸（wpW ≤ cropW）maxScroll=0 → 恒裁剪首屏，offset 无影响。
     */
    private fun buildVisibleCrop(src: WallpaperSource): ElementBackground? {
        return try {
            val dm = android.content.res.Resources.getSystem().displayMetrics
            val scrW = dm.widthPixels
            val scrH = dm.heightPixels
            if (scrW <= 0 || scrH <= 0) return null
            val wpW = src.logicalWidth
            val wpH = src.logicalHeight
            if (wpW <= 0 || wpH <= 0 || src.bitmap.width <= 0 || src.bitmap.height <= 0) return null
            val scale = maxOf(scrW.toFloat() / wpW, scrH.toFloat() / wpH)
            if (scale <= 0f) return null
            // 可见窗口在壁纸文件坐标
            val cropW = scrW / scale
            val cropH = scrH / scale
            val maxScrollX = (wpW - cropW).coerceAtLeast(0f)
            val maxScrollY = (wpH - cropH).coerceAtLeast(0f)
            val cropX = (wallpaperOffsetX.coerceIn(0f, 1f) * maxScrollX).coerceIn(0f, maxScrollX)
            val cropY = (wallpaperOffsetY.coerceIn(0f, 1f) * maxScrollY).coerceIn(0f, maxScrollY)
            // 折算到解码位图坐标
            val sx = src.bitmap.width.toFloat() / wpW
            val sy = src.bitmap.height.toFloat() / wpH
            val bx = (cropX * sx).toInt()
            val by = (cropY * sy).toInt()
            val bw = (cropW * sx).toInt().coerceIn(1, src.bitmap.width - bx)
            val bh = (cropH * sy).toInt().coerceIn(1, src.bitmap.height - by)
            if (bx < 0 || by < 0 || bx + bw > src.bitmap.width || by + bh > src.bitmap.height) {
                Log.w(TAG, "bg-wallpaper: crop out of bounds bx=$bx by=$by bw=$bw bh=$bh bmp=${src.bitmap.width}x${src.bitmap.height}")
                return null
            }
            val cropBmp = Bitmap.createBitmap(src.bitmap, bx, by, bw, bh)
            val bgScale = bgCaptureScale()
            val tw = (scrW * bgScale).toInt().coerceAtLeast(1)
            val th = (scrH * bgScale).toInt().coerceAtLeast(1)
            val out = if (cropBmp.width == tw && cropBmp.height == th) {
                cropBmp
            } else {
                val scaled = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(scaled)
                val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
                canvas.drawBitmap(cropBmp, null, RectF(0f, 0f, tw.toFloat(), th.toFloat()), paint)
                scaled
            }
            ElementBackground(out, RectF(0f, 0f, scrW.toFloat(), scrH.toFloat()))
        } catch (t: Throwable) {
            Log.e(TAG, "bg-wallpaper: build visible crop failed", t)
            null
        }
    }

    /** [spec/52] 从系统读当前壁纸 offset 初始化 [wallpaperOffsetX/Y]（解码首次/壁纸变化后校准，
     *  之后由 setWallpaperOffsets hook 实时更新）。取任一已 attach 宿主的 rootView.windowToken。
     *  `getWallpaperOffsets` 是 @SystemApi（public SDK 不可见），反射调用；失败静默保留当前 offset。 */
    private fun syncWallpaperOffsetsFromSystem() {
        try {
            val wm = wallpaperManager() ?: return
            var token: IBinder? = null
            for (entry in registeredHostViews) {
                val v = entry.ref.get() ?: continue
                if (!v.isAttachedToWindow) continue
                token = v.rootView.windowToken
                if (token != null) break
            }
            if (token == null) return
            val xs = FloatArray(1)
            val ys = FloatArray(1)
            wm.javaClass.getMethod(
                "getWallpaperOffsets",
                IBinder::class.java, FloatArray::class.java, FloatArray::class.java,
            ).invoke(wm, token, xs, ys)
            wallpaperOffsetX = xs[0]
            wallpaperOffsetY = ys[0]
        } catch (t: Throwable) {
            Log.w(TAG, "bg-wallpaper: getWallpaperOffsets read failed", t)
        }
    }

    /** [spec/52] WallpaperManager 实例（ActivityThread.currentApplication 上下文，Launcher 进程内）。 */
    private fun wallpaperManager(): WallpaperManager? {
        return try {
            val app = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null) as? android.content.Context ?: return null
            WallpaperManager.getInstance(app)
        } catch (t: Throwable) {
            Log.w(TAG, "bg-wallpaper: WallpaperManager.getInstance failed", t)
            null
        }
    }

    /** [spec/52] 壁纸文件解码背景源开关（Prefs KEY_BG_WALLPAPER_FILE_SOURCE，默认 true）；
     *  true=壁纸文件解码优先（静态壁纸零持续抓屏），false=回退 captureDisplay 抓屏。 */
    private fun wallpaperFileSourceEnabled(): Boolean {
        val a = api ?: return Prefs.DEFAULT_BG_WALLPAPER_FILE_SOURCE
        return try {
            Prefs.read(a).getBoolean(Prefs.KEY_BG_WALLPAPER_FILE_SOURCE, Prefs.DEFAULT_BG_WALLPAPER_FILE_SOURCE)
        } catch (t: Throwable) {
            Log.w(TAG, "prefs read bg_wallpaper_file_source failed, default true", t)
            Prefs.DEFAULT_BG_WALLPAPER_FILE_SOURCE
        }
    }

    /** [spec/52] 壁纸整幅解码最大边长（Prefs KEY_BG_WALLPAPER_MAX_DIM，clamp 512~8192，默认 2048）。 */
    private fun wallpaperMaxDecodeDim(): Int {
        val a = api ?: return Prefs.DEFAULT_BG_WALLPAPER_MAX_DIM
        return try {
            Prefs.read(a).getInt(Prefs.KEY_BG_WALLPAPER_MAX_DIM, Prefs.DEFAULT_BG_WALLPAPER_MAX_DIM)
                .coerceIn(512, 8192)
        } catch (t: Throwable) {
            Log.w(TAG, "prefs read bg_wallpaper_max_dim failed, default 2048", t)
            Prefs.DEFAULT_BG_WALLPAPER_MAX_DIM
        }
    }

    // ------------------------------------------------------------ 材质参数

    private fun buildParams(width: Float, height: Float, cornerRadius: Float): LiquidGlassShader.Params {
        // def 兼作 prefs 不可用时的兜底：blurRadius 须带上配置默认值（否则退回 Params 默认 0f = 清晰透镜）
        val def = LiquidGlassShader.Params(
            viewportWidth = width, viewportHeight = height,
            blurRadius = Prefs.DEFAULT_BLUR_RADIUS,
        )
        val prefs = try {
            api?.let { Prefs.read(it) }
        } catch (t: Throwable) {
            Log.e(TAG, "launcher buildParams: prefs read failed", t); null
        }
        if (prefs == null) return def
        return try {
            LiquidGlassShader.Params(
                viewportWidth = width,
                viewportHeight = height,
                cornerRadius = cornerRadius,
                // 文件夹玻璃本体角：RBox 圆弧（非 CONIC；Launcher 无 CornerParams CONIC 语义）
                cornerIsConic = false,
                cornerWeight = DEFAULT_CORNER_WEIGHT,
                // [2026-09-16 恢复配置化] 原硬编码 0（清晰透镜，同 SystemUI 的「强制透明诊断档」），
                // 改回读 KEY_BLUR_RADIUS —— 用户反馈「玻璃太透、背景看得一清二楚」。
                blurRadius = prefs.getFloat(Prefs.KEY_BLUR_RADIUS, Prefs.DEFAULT_BLUR_RADIUS),
                // [2026-08-14 折射自适应] 折射环带高度恒 = 元素短边一半（不再读 refraction_height 配置）
                refractionHeight = minOf(width, height) / 2f,
                refractionAmount = prefs.getFloat(Prefs.KEY_REFRACTION_AMOUNT, def.refractionAmount),
                depth = prefs.getFloat(Prefs.KEY_DEPTH, def.depth),
                dispersion = prefs.getFloat(Prefs.KEY_DISPERSION, def.dispersion),
                highlight = prefs.getFloat(Prefs.KEY_HIGHLIGHT, def.highlight),
                highlightWidth = prefs.getFloat(Prefs.KEY_HIGHLIGHT_WIDTH, def.highlightWidth),
                highlightFalloff = prefs.getFloat(Prefs.KEY_HIGHLIGHT_FALLOFF, Prefs.DEFAULT_HIGHLIGHT_FALLOFF),
                vibrancy = prefs.getFloat(Prefs.KEY_VIBRANCY, Prefs.DEFAULT_VIBRANCY),
                lightAngleDegrees = prefs.getFloat(Prefs.KEY_LIGHT_ANGLE, def.lightAngleDegrees),
                parallaxX = prefs.getFloat(Prefs.KEY_PARALLAX_X, def.parallaxX),
                parallaxY = prefs.getFloat(Prefs.KEY_PARALLAX_Y, def.parallaxY),
            )
        } catch (t: Throwable) {
            Log.e(TAG, "launcher buildParams: prefs float read failed, use defaults", t)
            def
        }
    }

    // ------------------------------------------------------------ 运动追踪（区域冻结根治）

    private val motionFrameCallback = Choreographer.FrameCallback {
        frameCallbackPosted = false
        try {
            trackHostViewMotion()
        } catch (t: Throwable) {
            Log.e(TAG, "launcher taskG+: trackHostViewMotion error", t)
        }
        var hasLive = false
        synchronized(this) {
            for (entry in registeredHostViews) {
                if (entry.ref.get() != null) { hasLive = true; break }
            }
        }
        if (hasLive) kickMotionTracker()
    }

    /** 登记宿主 View（WeakReference 防泄漏）：清理失效弱引用，再按 referent 去重。 */
    @Synchronized
    private fun registerHostView(view: View?) {
        if (view == null) return
        val it = registeredHostViews.iterator()
        while (it.hasNext()) {
            if (it.next().ref.get() == null) it.remove()
        }
        for (entry in registeredHostViews) {
            if (entry.ref.get() === view) return
        }
        registeredHostViews.add(HostEntry(view))
        kickMotionTracker()
    }

    /** 启动/续跑帧回调（主线程调用；惰性获取 Choreographer，任何失败静默不崩） */
    private fun kickMotionTracker() {
        if (frameCallbackPosted) return
        val c = choreographer ?: try {
            Choreographer.getInstance().also { choreographer = it }
        } catch (t: Throwable) {
            Log.e(TAG, "launcher taskG+: Choreographer.getInstance failed (not main thread?)", t)
            return
        }
        frameCallbackPosted = true
        try {
            c.postFrameCallback(motionFrameCallback)
        } catch (t: Throwable) {
            frameCallbackPosted = false
            Log.e(TAG, "launcher taskG+: postFrameCallback failed", t)
        }
    }

    /** 逐帧快照比对：位置/尺寸变化的宿主 invalidate() → 重录 → 映射跟随（srcRect 实时折算跟手）。 */
    @Synchronized
    private fun trackHostViewMotion() {
        if (registeredHostViews.isEmpty()) return
        val loc = IntArray(2)
        val it = registeredHostViews.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            val v = entry.ref.get()
            if (v == null) {
                it.remove()
                continue
            }
            try {
                if (!v.isAttachedToWindow) continue
                v.getLocationOnScreen(loc)
                val w = v.width
                val h = v.height
                if (entry.lastX == Int.MIN_VALUE) {
                    entry.lastX = loc[0]; entry.lastY = loc[1]; entry.lastW = w; entry.lastH = h
                    continue
                }
                if (entry.lastX != loc[0] || entry.lastY != loc[1] || entry.lastW != w || entry.lastH != h) {
                    entry.lastX = loc[0]; entry.lastY = loc[1]; entry.lastW = w; entry.lastH = h
                    // 移动/尺寸变化：invalidate 宿主 → 重录 → 映射更新（移动只更新 srcRect 折算，不重抓背景）
                    try {
                        v.invalidate()
                    } catch (t: Throwable) {
                        Log.e(TAG, "launcher taskG+: invalidate host failed", t)
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "launcher taskG+: track single host failed", t)
            }
        }
    }

    @Synchronized
    private fun invalidateRegisteredHostViews() {
        val it = registeredHostViews.iterator()
        while (it.hasNext()) {
            val v = it.next().ref.get()
            if (v == null) {
                it.remove()
                continue
            }
            try {
                v.invalidate()
            } catch (t: Throwable) {
                Log.e(TAG, "launcher taskG+: invalidate host view failed", t)
            }
        }
    }

    /** [worker → 主线程] 新快照就绪 → 主线程 invalidate 宿主重录（渲染触发异步请求后本帧可能回退了系统模糊）。 */
    private fun postInvalidateRegistered() {
        try {
            mainHandler().post { invalidateRegisteredHostViews() }
        } catch (t: Throwable) {
            Log.e(TAG, "bg-element: postInvalidateRegistered failed", t)
        }
    }

    // ------------------------------------------------------------ 渲染模式日志

    /** 渲染模式日志：GLASS/BLUR 切换才打（防刷屏），BLUR 带原因。 */
    private fun logRenderMode(id: Int, mode: String, reason: String? = null) {
        if (!moduleLogEnabled) return
        if (lastRenderModeMap[id] == mode) return
        if (lastRenderModeMap.size > 256) lastRenderModeMap.clear()
        lastRenderModeMap[id] = mode
        Log.i(TAG, "launcher render: $id $mode" + (reason?.let { " ($it)" } ?: ""))
    }
}
