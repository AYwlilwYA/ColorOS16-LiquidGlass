package com.coloros16.liquidglass.hook

import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.drawable.Drawable
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceControl
import android.view.View
import android.view.ViewGroup
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.liquidglass.LiquidGlassShader
import com.coloros16.liquidglass.liquidglass.ShaderInstancePool
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.LinkedHashSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

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

    // ---- [spec/117] 17.3.12 玻璃渲染链指纹（BaseDrawable→b、drawBlurShader 内联进 static a(d)） ----
    /** 指纹/17.x 兼容日志 tag（logcat `-s lg-hook`） */
    private const val TAG_FINGERPRINT = "lg-hook"
    /** [spec/116 P0/P1] 桌面 17.3.12 versionCode 门槛 */
    private const val VERSION_LAUNCHER_17_3_12 = 170030012L
    /** 稳定探针：内部类名未混淆，可反查外层 BaseDrawable（17.3.12 = drawable.b） */
    private const val PROBE_BASE_DRAWABLE_INNER =
        "com.oplus.posteffect.drawable.BaseDrawable\$configurationChangeObserver\$1"
    /** 17.3.12 blurShaderPaint getter 被拆 → public final 字段 M */
    private const val FIELD_BLUR_SHADER_PAINT_17 = "M"
    /** 17.3.12 paint getter 被拆 → public final 字段 f28547f */
    private const val FIELD_PAINT_17 = "f28547f"
    /** 17.3.12 renderNode → f28549h（路径 A 自录用） */
    private const val FIELD_RENDER_NODE_17 = "f28549h"
    /** 17.3.12 drawablePath → f28550i（路径 A 自录用） */
    private const val FIELD_DRAWABLE_PATH_17 = "f28550i"
    /** 17.3.12 paintAlpha → F（路径 A 自录用，等价旧 drawBlurShader 的 setAlpha） */
    private const val FIELD_PAINT_ALPHA_17 = "F"
    /** 17.3.12 enableBlurShader → f28542a（false 时系统跳过 shader 分支） */
    private const val FIELD_ENABLE_BLUR_SHADER_17 = "f28542a"
    /** 17.3.12 drawBlurShader 内联目标：static void a(d)，按指纹确认防撞名 */
    private const val INLINE_RECORD_STATIC_NAME = "a"
    /** 17.3.12 onBlurReady 单 drawable 重载改名 f（同签名 List,HardwareBuffer,int,float） */
    private const val METHOD_ON_BLUR_READY_17 = "f"
    /** 17.3.12 CornerType 迁移 FQCN（常量名混淆，用 values()[2]） */
    private const val CLASS_CORNER_TYPE_17 = "com.oplus.posteffect.params.CornerType"
    /** 17.3.12 CornerParams 混淆类（旧 GradientStrokeCornerParams） */
    private const val CLASS_CORNER_PARAMS_17 = "zn.c"
    /** 17.3.12 指纹扫描候选：drawable 包单字母类（有限枚举，不整包扫） */
    private const val PROBE_SCAN_LETTERS = "bcdefghijklmnop"

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

    // ---- [doc/spec/83 开启动画壁纸缩放跟随 2026-10-10] ----
    /** `WallpaperManager.sendWallpaperCommand(IBinder, String, int, int, int, Bundle)`：ColorOS
     *  开/关/文件夹动画期间由 Launcher `OplusDepthController.startWallpaperAnimation` 调用，
     *  壁纸服务收到后按 Bundle 里的 scale/duration/spring **自己缩放渲染壁纸**（中心 pivot，
     *  `composePivotParams` = 0.5/0.5 反编译实证）。本 hook 只读参数做镜像折算，不改原调用。 */
    private const val METHOD_SEND_WALLPAPER_COMMAND = "sendWallpaperCommand"
    private const val WALLPAPER_ANIMATION_ACTION = "oplus.wallpaper.animation"
    private const val WP_KEY_SCALE_FROM = "scale_from_x"
    private const val WP_KEY_SCALE_TO = "scale_to_x"
    private const val WP_KEY_DURATION = "duration"
    private const val WP_KEY_SPRING_STIFFNESS = "spring_stiffness"
    private const val WP_KEY_SPRING_DAMPING = "spring_dampingRatio"
    /** Bundle 缺 duration 时的兜底时长（OplusDepthController APP_OPEN_WALLPAPER_TIME = 650ms）。 */
    private const val WP_DEFAULT_DURATION_MS = 650L
    /** 无 spring 参数（或 damping≠临界阻尼）时退化为线性插值的固定时长。 */
    private const val WP_TICK_INTERVAL_MS = 16L
    /** 缩放折算的等值阈值：小于它认为没变化，不触发 invalidate。 */
    private const val WP_SCALE_EPS = 1.0E-4f

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
        /** [doc/spec/83] 位图烘焙时的壁纸缩放系数：**抓屏源** = 抓屏瞬间的壁纸缩放（屏幕上壁纸
         *  可能已被放大，位图内容已是缩放后的内容）；**壁纸文件裁剪源** = 1（文件永远未缩放）。
         *  折算只用相对变化 `wallpaperZoomScale / 本值`，避免「抓屏已含缩放 → 再折算一次」的双重缩放。 */
        val wallpaperZoomAtCapture: Float,
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
        /** [doc/spec/95 + spec/96] 上次应用的折射环带高度 px（已按相对/绝对模式折算，见
         *  [Prefs.refractionHeightPx]）。桌面侧改动前只比对几何/源，材质参数一变就被早退分支吃掉 →
         *  必须显式比对；存**折算后的 px**（而非比例）才能同时抓住「比例改了」「切了模式」
         *  「绝对像素值改了」三种变化（任一变化都会改变该值 → 重设 uRefractionHeight）。 */
        val refractionHeight: Float = 0f,
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

    // ---- [spec/117] 17.3.12 指纹解析缓存（旧名命中时全部为空，16.x 行为零变化） ----
    /** 解析出的 BaseDrawable 类（旧名或指纹） */
    private var mBaseDrawableCls: Class<*>? = null
    /** 类指纹来源：old-name / probe-inner / probe-scan（仅日志） */
    private var mBaseDrawableFp: String? = null
    /** 17.3.12 blurShaderPaint 字段 M（旧名路径下为 null） */
    private var mBlurShaderPaintField: Field? = null
    /** 17.3.12 paint 字段 f28547f */
    private var mPaintField: Field? = null
    /** 17.3.12 renderNode 字段 f28549h（路径 A 自录） */
    private var mRenderNodeField: Field? = null
    /** 17.3.12 drawablePath 字段 f28550i（路径 A 自录） */
    private var mDrawablePathField: Field? = null
    /** 17.3.12 paintAlpha 字段 F（路径 A 自录） */
    private var mPaintAlphaField: Field? = null
    /** 17.3.12 enableBlurShader 字段 f28542a */
    private var mEnableBlurShaderField: Field? = null
    /** 17.3.12 内联录制方法 static void a(d)（16.x 恒 null） */
    private var mInlineRecordMethod: Method? = null
    /** CornerType.CONIC 兼容解析结果（旧 getField(CONIC) / 17.x values()[2]；当前渲染未消费，兼容链预留） */
    @Suppress("unused")
    private var mCornerTypeConic: Any? = null
    /** 版本门控缓存：宿主 ≥17.3.12（-1/读失败按 false 处理，指纹路径不触发） */
    @Volatile
    private var launcherVer17Plus: Boolean? = null

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
    /** [spec/90 #5] shader 身份集合：O(1) 判「paint.shader 是否本模块实例」，替代 containsValue O(n) 每帧扫描 */
    private val shaderOwnerSet = ConcurrentHashMap.newKeySet<RuntimeShader>()
    private val appliedUniformsMap = ConcurrentHashMap<Int, AppliedKey>()
    /** drawable(identityHashCode) → 屏幕区域 + 局部 bounds（映射 hook 建立） */
    private val screenRegionMap = ConcurrentHashMap<Int, RegionEntry>()
    /** 渲染模式日志（GLASS/BLUR 切换才打，防刷屏） */
    private val lastRenderModeMap = ConcurrentHashMap<Int, String>()

    // ---- [spec/85 补接] 省电模式门控（Launcher 进程侧，与 SystemUI BlurDrawHook 同语义） ----
    /** 省电模式当前状态（broadcast 实测写入 + [isPowerSaveGlassOff] TTL 复读；热路径 volatile 读零开销） */
    @Volatile
    private var powerSaveActive = false
    @Volatile
    private var powerSaveMeasuredAt = 0L
    /** 省电状态 TTL 缓存毫秒（同 SystemUI 侧 500ms） */
    private const val POWER_SAVE_STATE_CACHE_MS = 500L
    /** [spec/85 补接] Launcher 进程 Application context（Launcher 是普通 app 进程，currentApplication 恒可用） */
    private val launcherAppContext: android.content.Context? by lazy {
        try {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null) as? android.content.Context
        } catch (t: Throwable) {
            null
        }
    }

    // ---- [spec/90 #3] 追踪器可见性门控缓存（熄屏/锁屏时降频自续，不逐帧 Binder） ----
    @Volatile
    private var trackableCached = true
    @Volatile
    private var trackableCachedAtMs = 0L

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

    // ---- [doc/spec/83] 壁纸缩放镜像（开/关/文件夹动画期间壁纸服务按中心 pivot 缩放） ----
    /** 当前壁纸服务缩放系数（1 = 原尺寸）；render 每帧读它反向折算 srcRect。 */
    @Volatile
    private var wallpaperZoomScale = 1f
    /** 缩放动画进行中（主线程驱动；非主线程只读 volatile 字段） */
    @Volatile
    private var zoomAnimRunning = false
    private var zoomAnimFrom = 1f
    private var zoomAnimTo = 1f
    private var zoomAnimStartMs = 0L
    private var zoomAnimDurationMs = WP_DEFAULT_DURATION_MS
    /** 临界阻尼弹簧角频率 ω=√stiffness（0 = 线性插值兜底）。 */
    private var zoomAnimOmega = 0f
    /** 已应用过的缩放（防同值 invalidate；from==to 快捷路径在 hook 线程也会写 → volatile） */
    @Volatile
    private var zoomAppliedScale = 1f
    /** 16ms 主线程驱动 tick（缩放动画只在动画期间自续，结束后自停；object 表达式以便 this = Runnable） */
    private val zoomTick: Runnable = object : Runnable {
        override fun run() {
            try {
                if (zoomAnimRunning) {
                    val now = SystemClock.uptimeMillis()
                    val elapsedMs = now - zoomAnimStartMs
                    var scale: Float
                    if (zoomAnimOmega > 0f) {
                        // 临界阻尼弹簧解析解（m=1、初速 0）：x(t) = to + (from-to)(1+ωt)e^{-ωt}
                        val tSec = elapsedMs / 1000f
                        val x = (1f + zoomAnimOmega * tSec) * exp(-zoomAnimOmega * tSec)
                        scale = zoomAnimTo + (zoomAnimFrom - zoomAnimTo) * x
                    } else {
                        val p = if (zoomAnimDurationMs <= 0L) 1f
                        else (elapsedMs.toFloat() / zoomAnimDurationMs).coerceIn(0f, 1f)
                        scale = zoomAnimFrom + (zoomAnimTo - zoomAnimFrom) * p
                    }
                    if (elapsedMs >= zoomAnimDurationMs) {
                        // 到点贴终值（弹簧截断，与壁纸服务 cut 行为一致）
                        scale = zoomAnimTo
                        zoomAnimRunning = false
                    } else {
                        mainHandler().postDelayed(this, WP_TICK_INTERVAL_MS)
                    }
                    applyWallpaperZoom(scale)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "wallpaper zoom: tick failed", t)
                zoomAnimRunning = false
            }
        }
    }

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
        // [doc/spec/83] 开/关/文件夹动画的壁纸缩放信号（镜像折算玻璃采样窗）
        mountWallpaperZoomCommand(api, classLoader)
        // [spec/85 补接] 省电模式广播（开 → 即刻门控 + 重录宿主；关 → 下一帧恢复玻璃）
        registerPowerSaveBroadcastReceiver()
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
        // 渲染 hook 反射：旧名优先，17.3.12 走指纹（失败只禁 drawBlurShader 替换，不崩）
        val base = resolveBaseDrawableClass(classLoader)
        if (base != null) {
            resolveBaseDrawableMembers(base, classLoader)
        } else {
            Log.e(TAG, "LauncherHook resolve BaseDrawable class failed, drawBlurShader replace disabled")
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
        // [doc/spec/66] Dock 借壳反射：createBlurDrawable 在 17.3.12 不存在（BlurProperties 新体系），
        // 与 mShortcutsAndWidgetsField 拆开解析，字段仍可用于 onDraw 映射
        try {
            val hotseat = Class.forName(CLASS_OPLUS_HOTSEAT, false, classLoader)
            mShortcutsAndWidgetsField = findFieldAlongHierarchy(hotseat, FIELD_SHORTCUTS_AND_WIDGETS)
            Log.i(TAG, "LauncherHook resolved OplusHotseat.mShortcutsAndWidgets (dock map)")
        } catch (t: Throwable) {
            Log.e(TAG, "LauncherHook resolve OplusHotseat.mShortcutsAndWidgets failed, dock map disabled", t)
        }
        try {
            val hotseat = Class.forName(CLASS_OPLUS_HOTSEAT, false, classLoader)
            mCreateBlurDrawable = hotseat.getDeclaredMethod(METHOD_CREATE_BLUR_DRAWABLE)
                .apply { isAccessible = true }
            Log.i(TAG, "LauncherHook resolved OplusHotseat.createBlurDrawable (dock glass)")
        } catch (t: Throwable) {
            if (isLauncher17Plus()) {
                Log.i(
                    TAG_FINGERPRINT,
                    "launcher-glass: OplusHotseat.createBlurDrawable missing on 17.x (BlurProperties path), dock shell-borrow disabled",
                )
            } else {
                Log.e(TAG, "LauncherHook resolve OplusHotseat.createBlurDrawable failed, dock glass disabled", t)
            }
            mCreateBlurDrawable = null
        }
    }

    /** [spec/117 A1] BaseDrawable 类指纹解析：旧名 → 探针1（内部类反查）→ 探针2（单字母扫描）。
     *  旧名命中即返回，指纹路径不执行（16.x 零风险）。 */
    private fun resolveBaseDrawableClass(classLoader: ClassLoader): Class<*>? {
        mBaseDrawableCls?.let { return it }
        // ① 旧名（16.x 直接命中）
        try {
            val c = Class.forName(CLASS_BASE_DRAWABLE, false, classLoader)
            mBaseDrawableCls = c
            mBaseDrawableFp = "old-name"
            Log.i(TAG_FINGERPRINT, "launcher-glass: resolved @ old-name ($CLASS_BASE_DRAWABLE)")
            return c
        } catch (_: ClassNotFoundException) {
            Log.i(TAG_FINGERPRINT, "launcher-glass: BaseDrawable old-name miss, try fingerprint")
        } catch (t: Throwable) {
            Log.w(TAG_FINGERPRINT, "launcher-glass: BaseDrawable old-name load error", t)
        }
        // ② 指纹探针仅在 ≥17.3.12 启用（版本未知/-1 不探，防误判）
        if (!isLauncher17Plus()) {
            Log.w(
                TAG_FINGERPRINT,
                "launcher-glass: BaseDrawable unresolved and host <17.3.12 (or version unknown), skip fingerprint probe",
            )
            return null
        }
        // 探针1：内部类 configurationChangeObserver$1 未混淆 → 反查外层类
        try {
            val inner = Class.forName(PROBE_BASE_DRAWABLE_INNER, false, classLoader)
            val outer = try {
                inner.enclosingClass
            } catch (t: Throwable) {
                null
            }
            if (outer != null && isBaseDrawableFingerprint(outer)) {
                mBaseDrawableCls = outer
                mBaseDrawableFp = "probe-inner"
                Log.i(TAG_FINGERPRINT, "launcher-glass: resolved @ probe-inner (${outer.name} via $PROBE_BASE_DRAWABLE_INNER)")
                return outer
            }
        } catch (_: ClassNotFoundException) {
            Log.i(TAG_FINGERPRINT, "launcher-glass: probe-inner miss, try probe-scan")
        } catch (t: Throwable) {
            Log.w(TAG_FINGERPRINT, "launcher-glass: probe-inner failed", t)
        }
        // 探针2：drawable 包单字母有限枚举，找「extends Drawable + 持有 DrawableShader 字段 + Paint 字段」
        for (ch in PROBE_SCAN_LETTERS) {
            val name = "com.oplus.posteffect.drawable.$ch"
            try {
                val c = Class.forName(name, false, classLoader)
                if (isBaseDrawableFingerprint(c)) {
                    mBaseDrawableCls = c
                    mBaseDrawableFp = "probe-scan"
                    Log.i(TAG_FINGERPRINT, "launcher-glass: resolved @ probe-scan ($name)")
                    return c
                }
            } catch (_: ClassNotFoundException) {
                continue
            } catch (_: Throwable) {
                continue
            }
        }
        Log.e(TAG_FINGERPRINT, "launcher-glass: BaseDrawable fingerprint resolve failed (all probes miss)")
        return null
    }

    /** [spec/117 §2] BaseDrawable 指纹判据：extends Drawable 且【自身声明】同时持有 DrawableShader + Paint 字段。
     *  子类（d/f）继承 L/M 但不声明 → 被排除，精确落到 BaseDrawable 本体。 */
    private fun isBaseDrawableFingerprint(c: Class<*>): Boolean {
        if (!Drawable::class.java.isAssignableFrom(c)) return false
        var hasDrawableShader = false
        var hasPaint = false
        for (f in c.declaredFields) {
            val ft = f.type
            if (ft.name == "com.oplus.posteffect.agsl.DrawableShader" || ft.simpleName == "DrawableShader") {
                hasDrawableShader = true
            }
            if (ft == Paint::class.java) {
                hasPaint = true
            }
        }
        return hasDrawableShader && hasPaint
    }

    /** [spec/117 A2] 对已解析 BaseDrawable 逐项解析方法/字段：旧名优先，17.3.12 名/字段兜底。 */
    private fun resolveBaseDrawableMembers(base: Class<*>, classLoader: ClassLoader) {
        // getBlurShaderPaint：旧方法 → 17.x 字段 M
        try {
            mGetBlurShaderPaint = base.getMethod(METHOD_GET_BLUR_SHADER_PAINT)
            Log.i(TAG_FINGERPRINT, "launcher-glass: getBlurShaderPaint @ method (old-name)")
        } catch (_: NoSuchMethodException) {
            try {
                mBlurShaderPaintField = base.getDeclaredField(FIELD_BLUR_SHADER_PAINT_17)
                Log.i(TAG_FINGERPRINT, "launcher-glass: getBlurShaderPaint @ field $FIELD_BLUR_SHADER_PAINT_17 (17.3.12)")
            } catch (t: Throwable) {
                Log.e(TAG_FINGERPRINT, "launcher-glass: getBlurShaderPaint unresolved (no method, no field $FIELD_BLUR_SHADER_PAINT_17)", t)
            }
        } catch (t: Throwable) {
            Log.e(TAG_FINGERPRINT, "launcher-glass: getBlurShaderPaint resolve error", t)
        }
        // getPaint：旧方法 → 17.x 字段 f28547f（当前渲染链未消费，兼容预留）
        try {
            mGetPaint = base.getMethod(METHOD_GET_PAINT)
            Log.i(TAG_FINGERPRINT, "launcher-glass: getPaint @ method (old-name)")
        } catch (_: NoSuchMethodException) {
            try {
                mPaintField = base.getDeclaredField(FIELD_PAINT_17)
                Log.i(TAG_FINGERPRINT, "launcher-glass: getPaint @ field $FIELD_PAINT_17 (17.3.12)")
            } catch (t: Throwable) {
                Log.w(TAG_FINGERPRINT, "launcher-glass: getPaint unresolved (no method, no field $FIELD_PAINT_17)", t)
            }
        } catch (t: Throwable) {
            Log.w(TAG_FINGERPRINT, "launcher-glass: getPaint resolve error", t)
        }
        // getBounds：继承 framework Drawable，旧名/17.x 均可用
        try {
            mGetBounds = base.getMethod(METHOD_GET_BOUNDS)
            Log.i(TAG_FINGERPRINT, "launcher-glass: getBounds @ method (inherited)")
        } catch (t: Throwable) {
            Log.e(TAG_FINGERPRINT, "launcher-glass: getBounds unresolved", t)
        }
        // drawBlurShader：17.x 不存在独立方法 → 标记内联路径（mount 阶段挂 static a(d)）
        try {
            base.getDeclaredMethod(METHOD_DRAW_BLUR_SHADER, Canvas::class.java)
            Log.i(TAG_FINGERPRINT, "launcher-glass: drawBlurShader @ method (old-name)")
        } catch (_: NoSuchMethodException) {
            if (isLauncher17Plus()) {
                mInlineRecordMethod = resolveInlineRecordMethod(base)
                Log.i(
                    TAG_FINGERPRINT,
                    "launcher-glass: drawBlurShader @ inline " +
                        (mInlineRecordMethod?.let { "${it.declaringClass.name}#${it.name}(d)" } ?: "UNRESOLVED"),
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG_FINGERPRINT, "launcher-glass: drawBlurShader resolve error", t)
        }
        // 路径 A 自录所需字段（仅 17.3.12；16.x 不解析不触发）
        if (isLauncher17Plus() && mInlineRecordMethod != null) {
            try {
                mRenderNodeField = base.getDeclaredField(FIELD_RENDER_NODE_17)
                mDrawablePathField = base.getDeclaredField(FIELD_DRAWABLE_PATH_17)
                mPaintAlphaField = base.getDeclaredField(FIELD_PAINT_ALPHA_17)
                mEnableBlurShaderField = base.getDeclaredField(FIELD_ENABLE_BLUR_SHADER_17)
                Log.i(
                    TAG_FINGERPRINT,
                    "launcher-glass: inline record fields @ $FIELD_RENDER_NODE_17/$FIELD_DRAWABLE_PATH_17/" +
                        "$FIELD_PAINT_ALPHA_17/$FIELD_ENABLE_BLUR_SHADER_17",
                )
            } catch (t: Throwable) {
                Log.e(TAG_FINGERPRINT, "launcher-glass: inline record fields unresolved, path A disabled", t)
                mInlineRecordMethod = null
            }
        }
        // onBlurReady 兼容链在 mountOnBlurReady 内逐签名尝试
        // CornerType/CornerParams 兼容解析（当前 Launcher 渲染未消费 CONIC，兼容链预留）
        resolveCornerCompat(classLoader)
    }

    /** [spec/117 §6 路径 A] 定位内联录制方法：static void a(d)，d 为 BaseDrawable 子类，防单字母撞名。 */
    private fun resolveInlineRecordMethod(base: Class<*>): Method? {
        var fallback: Method? = null
        for (m in base.declaredMethods) {
            if (!Modifier.isStatic(m.modifiers)) continue
            if (m.returnType != Void.TYPE) continue
            val params = m.parameterTypes
            if (params.size != 1) continue
            if (!base.isAssignableFrom(params[0])) continue
            if (m.name == INLINE_RECORD_STATIC_NAME) return m
            if (fallback == null) fallback = m
        }
        return fallback
    }

    /** [spec/117 §4] CornerType/CornerParams 兼容链：旧 FQCN → 17.x params.CornerType / zn.c。 */
    private fun resolveCornerCompat(classLoader: ClassLoader) {
        // CornerType：旧 com.oplus.posteffect.CornerType#getField("CONIC")
        try {
            val old = Class.forName("com.oplus.posteffect.CornerType", false, classLoader)
            mCornerTypeConic = old.getField("CONIC").get(null)
            Log.i(TAG_FINGERPRINT, "launcher-glass: CornerType.CONIC @ field CONIC (old-name)")
        } catch (_: ClassNotFoundException) {
            try {
                val ct17 = Class.forName(CLASS_CORNER_TYPE_17, false, classLoader)
                // 17.x 常量名混淆（a/b/c/d），values() 顺序 NONE,FULL,CONIC,G2 → CONIC = values()[2]
                val conic = ct17.getMethod("values").invoke(null)?.let { arr ->
                    (arr as? Array<*>)?.getOrNull(2)
                }
                if (conic != null) {
                    mCornerTypeConic = conic
                    Log.i(TAG_FINGERPRINT, "launcher-glass: CornerType.CONIC @ values()[2] ($CLASS_CORNER_TYPE_17)")
                } else {
                    try {
                        mCornerTypeConic = ct17.getField("f28674c").get(null)
                        Log.i(TAG_FINGERPRINT, "launcher-glass: CornerType.CONIC @ field f28674c ($CLASS_CORNER_TYPE_17)")
                    } catch (t: Throwable) {
                        Log.w(TAG_FINGERPRINT, "launcher-glass: CornerType.CONIC unresolved on 17.3.12", t)
                    }
                }
            } catch (t2: Throwable) {
                Log.w(TAG_FINGERPRINT, "launcher-glass: CornerType unresolved (old miss,17.x miss)", t2)
            }
        } catch (t: Throwable) {
            Log.w(TAG_FINGERPRINT, "launcher-glass: CornerType old-name CONIC field failed", t)
        }
        // CornerParams 类：旧 FQCN → zn.c（仅确认类在，无 getter 可反射）
        try {
            Class.forName("com.oplus.posteffect.CornerParams", false, classLoader)
            Log.i(TAG_FINGERPRINT, "launcher-glass: CornerParams @ old-name")
        } catch (_: ClassNotFoundException) {
            try {
                Class.forName(CLASS_CORNER_PARAMS_17, false, classLoader)
                Log.i(TAG_FINGERPRINT, "launcher-glass: CornerParams @ $CLASS_CORNER_PARAMS_17 (17.3.12)")
            } catch (t: Throwable) {
                Log.w(TAG_FINGERPRINT, "launcher-glass: CornerParams unresolved (old miss,17.x miss)", t)
            }
        } catch (t: Throwable) {
            Log.w(TAG_FINGERPRINT, "launcher-glass: CornerParams old-name load error", t)
        }
    }

    /** [spec/116 P0/P1] 宿主 ≥17.3.12 判定（进程内缓存；读失败/-1 按 false，指纹路径不触发）。 */
    private fun isLauncher17Plus(): Boolean {
        launcherVer17Plus?.let { return it }
        val ver = HostVersion.hostVersionCode("com.android.launcher")
        val v = ver >= VERSION_LAUNCHER_17_3_12
        launcherVer17Plus = v
        Log.i(TAG_FINGERPRINT, "launcher-glass: host versionCode=$ver,17.3.12+=$v")
        return v
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
     * [spec/117] 17.3.12：独立 drawBlurShader 消失 → 挂内联 static a(d)（路径 A 自录 + skip proceed）。
     */
    private fun mountDrawBlurShader(api: XposedInterface, classLoader: ClassLoader) {
        // ① 16.x 旧路径：drawBlurShader(Canvas)（旧名命中时恒走此分支，指纹路径不执行）
        val oldMethod = try {
            mBaseDrawableCls?.getDeclaredMethod(METHOD_DRAW_BLUR_SHADER, Canvas::class.java)
        } catch (_: NoSuchMethodException) {
            null
        } catch (t: Throwable) {
            Log.e(TAG, "LauncherHook drawBlurShader old-name lookup error", t)
            null
        }
        if (oldMethod != null) {
            if (mGetBlurShaderPaint == null && mBlurShaderPaintField == null) {
                Log.w(TAG, "LauncherHook BaseDrawable paint unresolved, drawBlurShader replace disabled")
                return
            }
            try {
                api.hook(oldMethod)
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
                Log.i(TAG, "LauncherHook mounted: ${oldMethod.declaringClass.name}#$METHOD_DRAW_BLUR_SHADER(Canvas)")
            } catch (t: Throwable) {
                Log.e(TAG, "LauncherHook mount FAILED: $CLASS_BASE_DRAWABLE#$METHOD_DRAW_BLUR_SHADER", t)
            }
            return
        }
        // ② 17.3.12 路径 A：内联 static a(d)，before 自录玻璃路径并 skip proceed；失败回退 proceed
        mountInlineRecordPathA(api)
    }

    /**
     * [spec/117 §6 路径 A] 17.3.12 渲染主链：hook static void a(d) before。
     * 玻璃就绪 → 自行 beginRecording/drawPath/endRecording 后 skip proceed；
     * 任一步失败 → ERROR 并 proceed 原方法（系统模糊），不留半残状态。16.x 不触发（mInlineRecordMethod 恒 null）。
     */
    private fun mountInlineRecordPathA(api: XposedInterface) {
        val method = mInlineRecordMethod
        if (method == null) {
            if (isLauncher17Plus()) {
                Log.e(TAG_FINGERPRINT, "launcher-glass: P1 主链未启用（inline a(d) 未解析），回退系统模糊")
            }
            return
        }
        if (mBlurShaderPaintField == null || mRenderNodeField == null ||
            mDrawablePathField == null || mPaintAlphaField == null
        ) {
            Log.e(TAG_FINGERPRINT, "launcher-glass: path A fields incomplete, inline hook disabled")
            return
        }
        try {
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val drawable = try {
                        chain.getArgs()?.getOrNull(0)
                    } catch (t: Throwable) {
                        Log.e(TAG_FINGERPRINT, "launcher-glass: inline getArgs failed", t); null
                    }
                    var handled = false
                    if (drawable != null) {
                        handled = try {
                            recordGlassInline(drawable)
                        } catch (t: Throwable) {
                            Log.e(TAG_FINGERPRINT, "launcher-glass: inline record error, fallback proceed", t)
                            false
                        }
                    }
                    if (!handled) chain.proceed()
                }
            Log.i(
                TAG_FINGERPRINT,
                "launcher-glass: mounted path A ${method.declaringClass.name}#${method.name}(d) (inline record + skip proceed)",
            )
        } catch (t: Throwable) {
            Log.e(TAG_FINGERPRINT, "launcher-glass: path A mount FAILED, system blur fallback", t)
        }
    }

    /**
     * [spec/117 路径 A] 自录玻璃：替换 shader 后 beginRecording → drawPath(glassPaint) → endRecording。
     * 返回 true = 已自录，调用方 skip proceed；false = 回退原方法。
     */
    private fun recordGlassInline(drawable: Any): Boolean {
        // enableBlurShader=false 时系统跳过 shader 分支 → 我们也回退原方法
        val enable = try {
            mEnableBlurShaderField?.getBoolean(drawable) ?: true
        } catch (t: Throwable) {
            true
        }
        if (!enable) return false
        // 门控 + 建玻璃 shader（与16.x 同一函数：省电/开关/映射/源全在这里判）
        if (!replaceShaderIfPossible(drawable)) return false
        val paint = getBlurShaderPaintOf(drawable) ?: return false
        val rn = try {
            mRenderNodeField?.get(drawable) as? RenderNode
        } catch (t: Throwable) {
            Log.e(TAG_FINGERPRINT, "launcher-glass: read renderNode failed", t); null
        } ?: return false
        val path = try {
            mDrawablePathField?.get(drawable) as? Path
        } catch (t: Throwable) {
            Log.e(TAG_FINGERPRINT, "launcher-glass: read drawablePath failed", t); null
        } ?: return false
        // 等价旧 drawBlurShader 的 setAlpha(paintAlpha)
        try {
            mPaintAlphaField?.getInt(drawable)?.let { paint.alpha = it }
        } catch (ignored: Throwable) {
        }
        var recorded = false
        val canvas = try {
            rn.beginRecording()
        } catch (t: Throwable) {
            Log.e(TAG_FINGERPRINT, "launcher-glass: beginRecording failed, fallback proceed", t); null
        } ?: return false
        try {
            canvas.drawPath(path, paint)
            recorded = true
        } finally {
            try {
                rn.endRecording()
            } catch (t: Throwable) {
                Log.e(TAG_FINGERPRINT, "launcher-glass: endRecording failed", t)
                recorded = false
            }
        }
        if (recorded) {
            logRenderMode(System.identityHashCode(drawable), "GLASS")
        }
        return recorded
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
        // 候选签名链：16.x (Canvas,View) 优先 → 17.3.12 (Canvas) → 17.3.12 (Canvas,ConvertBgParams,ItemInfo)
        try {
            val clazz = Class.forName(CLASS_OPLUS_PREVIEW_BACKGROUND, false, classLoader)
            val candidates = clazz.declaredMethods
                .filter { it.name == METHOD_DRAW_BACKGROUND && it.parameterTypes.isNotEmpty() }
                .sortedBy { m ->
                    // (Canvas,View) 排最前（16.x 命中后不再尝试其余）
                    if (m.parameterTypes.size == 2 && m.parameterTypes[1] == View::class.java) 0 else 1
                }
            var mounted = false
            for (method in candidates) {
                try {
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
                    val sig = method.parameterTypes.joinToString(", ") { it.simpleName }
                    Log.i(TAG, "LauncherHook mounted: $CLASS_OPLUS_PREVIEW_BACKGROUND#$METHOD_DRAW_BACKGROUND($sig) (folder icon map)")
                    mounted = true
                    // 16.x 双参命中即停；17.x 多签名只挂最靠前的一个（避免重复注册映射）
                    break
                } catch (t: Throwable) {
                    Log.w(TAG, "LauncherHook mount drawBackground(${method.parameterTypes.joinToString { it.simpleName }}) failed, try next", t)
                }
            }
            if (!mounted) {
                Log.e(TAG, "LauncherHook mount FAILED: $CLASS_OPLUS_PREVIEW_BACKGROUND#$METHOD_DRAW_BACKGROUND (all candidate signatures)")
            }
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
            // 候选名链：16.x onBlurReady → 17.3.12 改名 f（同签名）
            val candidates = if (isLauncher17Plus()) {
                listOf(METHOD_ON_BLUR_READY, METHOD_ON_BLUR_READY_17)
            } else {
                listOf(METHOD_ON_BLUR_READY)
            }
            var mounted = false
            for (name in candidates) {
                try {
                    val method = clazz.getDeclaredMethod(
                        name,
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
                    Log.i(
                        TAG,
                        "LauncherHook mounted: $CLASS_BLUR_DRAWABLE_MANAGER#$name(List, HardwareBuffer, int, float) (folder bg blur refresh signal)",
                    )
                    mounted = true
                    break
                } catch (_: NoSuchMethodException) {
                    continue
                } catch (t: Throwable) {
                    Log.w(TAG, "LauncherHook mount $CLASS_BLUR_DRAWABLE_MANAGER#$name failed, try next", t)
                }
            }
            if (!mounted) {
                Log.e(TAG, "LauncherHook mount FAILED: $CLASS_BLUR_DRAWABLE_MANAGER#onBlurReady/f (all candidate names)")
            }
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

    /**
     * [doc/spec/83] hook `WallpaperManager.sendWallpaperCommand(IBinder, String, int, int, int, Bundle)` ——
     * ColorOS 开/关/文件夹动画期间 Launcher `OplusDepthController.startWallpaperAnimation`（反编译
     * OplusDepthController L1216/L545）发给壁纸服务的缩放命令：action = "oplus.wallpaper.animation"，
     * Bundle = scale_from/scale_to + duration + spring_stiffness/dampingRatio + pivot 0.5/0.5（居中）。
     * 壁纸服务按这些参数**自己缩放渲染**壁纸，而玻璃背景源（壁纸文件 / 整屏快照）是动画开始前抓的
     * 静态图 → 背景不跟。本 hook 只读参数做**同款动画镜像**，每帧把缩放系数交给
     * [resolveRenderSource] 反向折算采样窗；`chain.proceed()` 原样放行，壁纸命令行为完全不变。
     *
     * 失败只禁缩放跟随（scale 恒 1），不崩 Launcher。
     */
    private fun mountWallpaperZoomCommand(api: XposedInterface, classLoader: ClassLoader) {
        try {
            val clazz = Class.forName(CLASS_WALLPAPER_MANAGER, false, classLoader)
            val method = clazz.getMethod(
                METHOD_SEND_WALLPAPER_COMMAND,
                IBinder::class.java, String::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Bundle::class.java,
            )
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        val args = chain.getArgs()
                        if (args != null && args.size >= 6) {
                            val action = args[1] as? String
                            val extras = args[5] as? Bundle
                            if (WALLPAPER_ANIMATION_ACTION == action && extras != null) {
                                handleWallpaperZoomCommand(extras)
                            }
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "wallpaper zoom: sendWallpaperCommand intercept error", t)
                    }
                    chain.proceed()
                }
            Log.i(
                TAG,
                "LauncherHook mounted: $CLASS_WALLPAPER_MANAGER#$METHOD_SEND_WALLPAPER_COMMAND(IBinder, String, int, int, int, Bundle) (wallpaper zoom mirror)",
            )
        } catch (t: Throwable) {
            Log.e(TAG, "LauncherHook mount FAILED: $CLASS_WALLPAPER_MANAGER#$METHOD_SEND_WALLPAPER_COMMAND", t)
        }
    }

    /** 读 Bundle 数值（launcher 侧 putFloat/putDouble/putInt 混用，统一转 Float；缺失/不识 → NaN）。 */
    @Suppress("DEPRECATION") // Bundle.get() 已废弃但仍是唯一能按实际存储类型取值的 API
    private fun readWallpaperNumber(b: Bundle, key: String): Float {
        if (!b.containsKey(key)) return Float.NaN
        return when (val v = b.get(key)) {
            is Float -> v
            is Double -> v.toFloat()
            is Int -> v.toFloat()
            is Long -> v.toFloat()
            else -> Float.NaN
        }
    }

    /** 解析壁纸缩放命令并启动镜像动画（无 scale 语义的空 bundle → 忽略，保持当前缩放）。 */
    private fun handleWallpaperZoomCommand(b: Bundle) {
        try {
            val from = readWallpaperNumber(b, WP_KEY_SCALE_FROM)
            val to = readWallpaperNumber(b, WP_KEY_SCALE_TO)
            if (from.isNaN() || to.isNaN()) return
            if (from == to) {
                applyWallpaperZoom(to)
                return
            }
            val durRaw = readWallpaperNumber(b, WP_KEY_DURATION)
            // duration=0 是「立即到终值」（app_background_reset 实测）；只有缺失才用 650ms 兜底
            val durationMs =
                if (durRaw.isNaN()) WP_DEFAULT_DURATION_MS else durRaw.toLong().coerceAtLeast(0L)
            val stiffness = readWallpaperNumber(b, WP_KEY_SPRING_STIFFNESS)
            val damping = readWallpaperNumber(b, WP_KEY_SPRING_DAMPING)
            // 只在临界阻尼（launcher 反编译实测恒 dampingRatio=1.0）用弹簧解析解，其余线性兜底
            val omega = if (!stiffness.isNaN() && stiffness > 0f &&
                !damping.isNaN() && abs(damping - 1f) < 0.02f
            ) {
                sqrt(stiffness)
            } else {
                0f
            }
            // hook 可能来自 WALLPAPER_TRANSACTION_EXECUTOR 线程 → 动画状态统一在主线程改
            mainHandler().post {
                zoomAnimFrom = from
                zoomAnimTo = to
                zoomAnimStartMs = SystemClock.uptimeMillis()
                zoomAnimDurationMs = durationMs
                zoomAnimOmega = omega
                zoomAnimRunning = true
                mainHandler().removeCallbacks(zoomTick)
                mainHandler().post(zoomTick)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "wallpaper zoom: parse command failed", t)
        }
    }

    /** 应用新的壁纸缩放系数（主线程）：写 volatile + invalidate 宿主，让玻璃重算采样窗。 */
    private fun applyWallpaperZoom(scale: Float) {
        val s = scale.coerceIn(0.1f, 4f)
        if (abs(s - zoomAppliedScale) < WP_SCALE_EPS) return
        zoomAppliedScale = s
        wallpaperZoomScale = s
        postInvalidateRegistered()
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

    /** [spec/117 A2] blurShaderPaint 双路径读取：16.x 方法 getBlurShaderPaint() → 17.3.12 字段 M。 */
    private fun getBlurShaderPaintOf(drawable: Any): Paint? {
        mGetBlurShaderPaint?.let { m ->
            return try {
                m.invoke(drawable) as? Paint
            } catch (t: Throwable) {
                Log.e(TAG, "launcher render: getBlurShaderPaint failed", t); null
            }
        }
        mBlurShaderPaintField?.let { f ->
            return try {
                f.get(drawable) as? Paint
            } catch (t: Throwable) {
                Log.e(TAG, "launcher render: read blurShaderPaint field failed", t); null
            }
        }
        return null
    }

    /**
     * 把 blurShaderPaint.shader 替换为液态玻璃 RuntimeShader。
     * 强兜底契约：任何失败都不动 paint（保持系统原样），返回 false → 调用方回退系统绘制。
     */
    private fun replaceShaderIfPossible(drawable: Any): Boolean {
        val id = System.identityHashCode(drawable)
        // [spec/85 补接·Launcher] 省电开启 → 还原模块 shader 并回退系统绘制（与 SystemUI 同门控，
        // 最优先）。paint.shader 若是本模块实例 → 置 null（系统 BaseDrawable 自配 blur shader，
        // getShaderOrNull 语义同 SystemUI 侧：通常 null）。只 return false 不还原会残留玻璃。
        if (isPowerSaveGlassOff()) {
            try {
                val p = getBlurShaderPaintOf(drawable)
                val cur = p?.shader
                if (cur is RuntimeShader && shaderOwnerSet.contains(cur)) {
                    p.shader = null
                    p.colorFilter = null
                }
            } catch (t: Throwable) {
                Log.w(TAG, "power-save: restore launcher shader failed", t)
            }
            logRenderMode(id, "BLUR", "power-save: glass disabled")
            return false
        }
        // [2026-10-13 玻璃总开关] 关 → 还原模块 shader 回退系统模糊（与 SystemUI 同门控）
        if (!Prefs.glassEnabled(api)) {
            try {
                val p = getBlurShaderPaintOf(drawable)
                val cur = p?.shader
                if (cur is RuntimeShader && shaderOwnerSet.contains(cur)) {
                    p.shader = null
                    p.colorFilter = null
                }
            } catch (t: Throwable) {
                Log.w(TAG, "glass switch off: restore launcher shader failed", t)
            }
            logRenderMode(id, "BLUR", "glass switch off")
            return false
        }
        val paint = try {
            getBlurShaderPaintOf(drawable)
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
        if (current is RuntimeShader && shaderOwnerSet.contains(current)) {
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
        val shader = shaderCache.getOrPut(id) { ShaderInstancePool.pollOrCreate() }
        shaderOwnerSet.add(shader)
        if (shaderCache.size > 128) { shaderCache.clear(); shaderOwnerSet.clear() }
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
            // [doc/spec/96] 与 buildParams 同一 TTL 缓存读出的折算结果（TTL 内两次读返回同一值）
            refractionHeight = Prefs.refractionHeightPx(
                minOf(bounds.width(), bounds.height()).toFloat(),
                api,
            ),
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
        // [doc/spec/95 + spec/96] 折射区域纳入比对：比例改了 / 切了相对↔绝对模式 / 绝对像素值改了
        // （哪怕几何与源全没变）也要重设 uRefractionHeight。读 Prefs 的 500ms TTL 缓存 → 每帧只是
        // volatile 读，热路径零 IPC。存折算后的 px，与 buildParams 同一恒等式。
        val refractionHeight = Prefs.refractionHeightPx(
            minOf(bounds.width(), bounds.height()).toFloat(),
            api,
        )
        if (applied != null && applied.sourceBitmapId == sourceBitmapId &&
            applied.viewport == viewport && applied.srcRect == srcRect &&
            applied.cornerRadius == entry.cornerRadius &&
            applied.refractionHeight == refractionHeight
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
            appliedUniformsMap[id] = AppliedKey(sourceBitmapId, viewport, srcRect, entry.cornerRadius, refractionHeight)
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
            // [doc/spec/83] 开/关/文件夹动画期间壁纸服务按中心放大渲染 → 采样窗先反向折算；
            // 折算系数用相对变化（当前缩放 ÷ 位图烘焙时的缩放），抓屏源/壁纸文件源都正确
            val region = zoomedRegionForWallpaper(screenRegion, snap.region, snap.wallpaperZoomAtCapture)
            val srcRect = RectF(
                (region.left - snap.region.left) * scaleX,
                (region.top - snap.region.top) * scaleY,
                (region.right - snap.region.left) * scaleX,
                (region.bottom - snap.region.top) * scaleY,
            )
            return RenderSource(snap.bitmap, srcRect)
        }
        return null
    }

    /**
     * [doc/spec/83] 壁纸缩放反向折算：壁纸服务以屏幕中心 c 按系数 s 放大渲染壁纸时，
     * 屏幕上 p 处显示的是未缩放壁纸 `c + (p-c)/s` 处的内容 → 元素采样窗按同一中心收缩
     * （s>1，开启动画实测区间 1.0→1.2）或外扩（s<1，回退/反向）。
     *
     * [baseScale] = 位图烘焙时的壁纸缩放（`ElementBackground.wallpaperZoomAtCapture`）：
     * 只用相对变化 `s = wallpaperZoomScale / baseScale` —— 抓屏若在缩放中进行，位图内容
     * 本身已含缩放，必须除掉，否则会折算两次；壁纸文件源 baseScale=1，退化为直接用当前缩放。
     *
     * 外扩越界部分夹回快照覆盖区（快照没有屏外内容）；结果退化为空时回退原窗口。
     */
    private fun zoomedRegionForWallpaper(r: RectF, screen: RectF, baseScale: Float): RectF {
        val s = if (baseScale > 0f) wallpaperZoomScale / baseScale else wallpaperZoomScale
        if (abs(s - 1f) < WP_SCALE_EPS) return r
        val cx = screen.centerX()
        val cy = screen.centerY()
        val out = RectF(
            cx + (r.left - cx) / s,
            cy + (r.top - cy) / s,
            cx + (r.right - cx) / s,
            cy + (r.bottom - cy) / s,
        )
        if (out.width() <= 0f || out.height() <= 0f) return r
        out.left = out.left.coerceIn(screen.left, screen.right)
        out.right = out.right.coerceIn(out.left, screen.right)
        out.top = out.top.coerceIn(screen.top, screen.bottom)
        out.bottom = out.bottom.coerceIn(out.top, screen.bottom)
        if (out.width() <= 0f || out.height() <= 0f) return r
        return out
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
                                                    ElementBackground(copy, region, wallpaperZoomScale)
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
            // 壁纸文件源：文件内容与壁纸服务的缩放无关 → 基准恒为 1（折算系数 = 当前缩放）
            ElementBackground(out, RectF(0f, 0f, scrW.toFloat(), scrH.toFloat()), 1f)
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
                // [doc/spec/95 + spec/96 2026-10-10] 折射环带高度走统一出口 Prefs.refractionHeightPx：
                // 相对模式 = 元素短边 × KEY_REFRACTION_HEIGHT_RATIO（默认 0.5 = 改动前的 min(w,h)/2f）；
                // 绝对模式 = KEY_REFRACTION_HEIGHT_PX（固定像素，默认 64）。三参数各自 500ms TTL 缓存
                // （热路径零 IPC）；变化经 AppliedKey.refractionHeight 早退比对触发全量 setUniforms。
                refractionHeight = Prefs.refractionHeightPx(minOf(width, height), api),
                refractionAmount = prefs.getFloat(Prefs.KEY_REFRACTION_AMOUNT, def.refractionAmount),
                depth = prefs.getFloat(Prefs.KEY_DEPTH, def.depth),
                dispersion = prefs.getFloat(Prefs.KEY_DISPERSION, def.dispersion),
                highlight = prefs.getFloat(Prefs.KEY_HIGHLIGHT, def.highlight),
                highlightWidth = prefs.getFloat(Prefs.KEY_HIGHLIGHT_WIDTH, def.highlightWidth),
                highlightFalloff = prefs.getFloat(Prefs.KEY_HIGHLIGHT_FALLOFF, Prefs.DEFAULT_HIGHLIGHT_FALLOFF),
                highlightInnerAtt = prefs.getFloat(Prefs.KEY_HIGHLIGHT_INNER_ATT, Prefs.DEFAULT_HIGHLIGHT_INNER_ATT),
                innerGlow = prefs.getFloat(Prefs.KEY_INNER_GLOW, Prefs.DEFAULT_INNER_GLOW).coerceIn(0f, 1f),
                vibrancy = prefs.getFloat(Prefs.KEY_VIBRANCY, Prefs.DEFAULT_VIBRANCY).coerceIn(1.0f, 3.0f),
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
            // [spec/90 #3] 熄屏/锁屏门控：不可见时跳过位置比对，500ms 降频自续代替每帧回调
            if (!isLauncherFrameTrackableNow()) {
                mainHandler().postDelayed({ kickMotionTracker() }, POWER_SAVE_STATE_CACHE_MS)
                return@FrameCallback
            }
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

    // ------------------------------------------------------------ [spec/85 补接 / spec/90 #3] 门控辅助

    /** [spec/85 补接] 省电模式判定：`PowerManager.isPowerSaveMode` + 500ms TTL（同 SystemUI 侧）。
     *  判定失败保守返回旧缓存值（误判只影响省电表现，不崩不漏）。 */
    private fun isPowerSaveGlassOff(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - powerSaveMeasuredAt < POWER_SAVE_STATE_CACHE_MS) return powerSaveActive
        val v = try {
            val pm = launcherAppContext?.getSystemService(android.content.Context.POWER_SERVICE)
                as? android.os.PowerManager
            pm?.isPowerSaveMode ?: powerSaveActive
        } catch (t: Throwable) {
            powerSaveActive
        }
        powerSaveActive = v
        powerSaveMeasuredAt = now
        return v
    }

    /** [spec/85 补接] 注册省电模式状态广播（Launcher 进程侧）：开 → 缓存过期 + 重录宿主（下一帧门控
     *  即刻生效）；关 → 下一帧 [replaceShaderIfPossible] 恢复玻璃。生命周期 = Launcher 进程，无需 unregister。 */
    private fun registerPowerSaveBroadcastReceiver() {
        val ctx = launcherAppContext
        if (ctx == null) {
            Log.w(TAG, "power-save: broadcast receiver skipped, context null")
            return
        }
        try {
            val filter = android.content.IntentFilter(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                    try {
                        powerSaveMeasuredAt = 0L
                        val on = isPowerSaveGlassOff()
                        postInvalidateRegistered()
                        Log.i(TAG, "power-save: changed on=$on, hosts invalidated")
                    } catch (t: Throwable) {
                        Log.e(TAG, "power-save: broadcast onReceive error", t)
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.applicationContext.registerReceiver(receiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
            } else {
                ctx.applicationContext.registerReceiver(receiver, filter)
            }
            Log.i(TAG, "power-save: broadcast receiver registered (on=${isPowerSaveGlassOff()})")
        } catch (t: Throwable) {
            Log.e(TAG, "power-save: register broadcast receiver failed", t)
        }
    }

    /** [spec/90 #3] 逐帧追踪可见性判定：熄屏（!isInteractive）或锁屏 → false。
     *  500ms TTL 缓存（否则 60fps 下每帧一次 Binder）；失败保守 true（保持旧行为）。 */
    private fun isLauncherFrameTrackableNow(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - trackableCachedAtMs < POWER_SAVE_STATE_CACHE_MS) return trackableCached
        val ctx = launcherAppContext
        val v = if (ctx == null) {
            true
        } else {
            try {
                val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
                val interactive = pm?.isInteractive ?: true
                val km = ctx.getSystemService(android.content.Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
                interactive && km?.isKeyguardLocked != true
            } catch (t: Throwable) {
                true
            }
        }
        trackableCached = v
        trackableCachedAtMs = now
        return v
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
