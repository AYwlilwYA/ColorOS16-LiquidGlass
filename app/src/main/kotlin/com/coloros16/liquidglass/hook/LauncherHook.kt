package com.coloros16.liquidglass.hook

import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
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
import android.view.Choreographer
import android.view.SurfaceControl
import android.view.View
import android.view.ViewGroup
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.debug.DebugElement
import com.coloros16.liquidglass.debug.DebugOverlay
import com.coloros16.liquidglass.glass.GlassRegistry
import com.coloros16.liquidglass.liquidglass.LiquidGlassShader
import com.coloros16.liquidglass.liquidglass.LowResTier
import com.coloros16.liquidglass.liquidglass.ShaderInstancePool
import com.coloros16.liquidglass.log.Lg
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
    private const val CLASS_ITEM_INFO = "com.android.launcher3.model.data.ItemInfo"
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

    // ---- [doc/spec/76] 桌面「问小布」入口液态玻璃化（借壳补映射） ----
    /** 「问小布」宿主控件：页面小圆点与「问小布」胶囊共用的同一个 View（系统 setState 切 alpha）。 */
    private const val CLASS_OPLUS_PAGE_INDICATOR = "com.android.launcher.pageindicators.OplusPageIndicator"
    /** 每帧登记映射的 hook 点（public，其内部调 drawBackgroundIfNeeded）。 */
    private const val METHOD_DISPATCH_DRAW = "dispatchDraw"
    /** 取壳入口（public，无参）→ 系统自建的 LayerBlurDrawable；无系统模糊时返回 null。 */
    private const val METHOD_GET_BLUR_DRAWABLE = "getBlurDrawable"
    /** 系统自算圆角（public）= min(min(w,h)/2, mBackgroundRadius)；直接用全圆角 dimen 会画成方形。 */
    private const val METHOD_GET_BACKGROUND_RADIUS = "getBackgroundRadius"
    /** 问小布玻璃开关重读间隔（ms）。 */
    private const val PAGE_INDICATOR_GLASS_TTL_MS = 500L

    // ---- [doc/spec/78] 桌面卡片/小组件背景玻璃化（借壳补映射） ----
    /** 卡片背景模糊管理器：卡片 / 分组卡片 / 小组件背景模糊的唯一产生点。 */
    private const val CLASS_CARD_BACKGROUND_BLUR_MANAGER = "com.android.launcher3.card.CardBackgroundBlurManager"
    /** 建卡片 blur 背景（private → getDeclaredMethod）；prepareBlur 在本方法内执行，
     *  所以映射必须在 proceed() 之后读 targetView.background。 */
    private const val METHOD_CREATE_BLUR_BACKGROUND = "createBlurBackground"
    /** 卡片圆角 tag 名（系统在建 blur 背景后 setTag 该 id）。 */
    private const val ID_KEY_BLUR_VIEW_RADIUS = "key_blur_view_radius"
    /** 卡片玻璃开关重读间隔（ms）。 */
    private const val CARD_GLASS_TTL_MS = 500L

    // ---- 最近任务清除按钮玻璃（View 级渲染接管：该按钮全链零 blur，不走 posteffect 漏斗） ----
    /** 最近任务清除钮面板容器（onFinishInflate 后读 public 字段 mClearAllBtn 捕获按钮实例）。 */
    private const val CLASS_CLEAR_ALL_PANEL_VIEW = "com.oplus.quickstep.views.OplusClearAllPanelView"
    /** 面板内清除钮字段（反编译实证 `public PressFeedbackButton mClearAllBtn`）。 */
    private const val FIELD_CLEAR_ALL_BTN = "mClearAllBtn"
    /** 按压反馈处理器（launcher 全库按钮共用；drawPressedColor = 平色 Paint 圆角矩形）。 */
    private const val CLASS_PRESS_FEEDBACK_HANDLER = "com.android.launcher.togglebar.animation.PressFeedbackHandler"
    /** 按压底色绘制方法（PressFeedbackButton.onDraw 先调它再画文字 → 在此盖玻璃不遮「清除」字样）。 */
    private const val METHOD_DRAW_PRESSED_COLOR = "drawPressedColor"
    /** handler 持有的宿主 View 私有字段（白名单判定：仅清除钮实例接管）。 */
    private const val FIELD_PFH_HOST_VIEW = "mView"
    /** 清除钮语义类（GlassRegistry 登记键，预设按「最近任务清除按钮」行命中）。 */
    private const val CLASS_CLEAR_ALL_BUTTON = "com.android.launcher.views.PressFeedbackButton"
    /** 清除钮玻璃开关重读间隔（ms）。 */
    private const val CLEAR_ALL_BTN_GLASS_TTL_MS = 500L

    // ---- 桌面编辑模式整理/完成钮玻璃（并入 PressFeedback 白名单共享机制） ----
    /** 编辑模式工具条容器（onFinishInflate 后读 getter 捕获整理/完成两钮实例）。 */
    private const val CLASS_EDIT_TOOLBAR = "com.android.launcher.togglebar.views.ToggleStateToolbar"
    /** 左钮 getter（back_to_main_page，动态改文为「整理」）。 */
    private const val METHOD_GET_BACK_TO_MAIN_PAGE = "getBackToMainPage"
    /** 右钮 getter（apply，固定文案「完成」）。 */
    private const val METHOD_GET_APPLY_BTN = "getApplyBtn"
    /** 编辑工具条玻璃开关重读间隔（ms）。 */
    private const val EDIT_TOOLBAR_GLASS_TTL_MS = 500L

    // ---- 桌面编辑工具条四圆钮玻璃（drawCircleColor 类门控；唯一调用方=PressFeedbackCircleImageView） ----
    /** 编辑工具条 item 圆底图标（drawCircleColor 唯一调用方，类名即门控，无需身份槽）。 */
    private const val CLASS_CIRCLE_IMAGE_VIEW =
        "com.android.launcher.togglebar.views.PressFeedbackLinearLayout\$PressFeedbackCircleImageView"
    /** 圆底平色绘制方法（正圆：圆心/半径 = h/2，不读 mRadius）。 */
    private const val METHOD_DRAW_CIRCLE_COLOR = "drawCircleColor"
    /** 四圆钮玻璃开关重读间隔（ms）。 */
    private const val EDIT_TOOLBTN_GLASS_TTL_MS = 500L

    // ---- 桌面长按菜单底衬玻璃（PopupBlurView 离线位图两层 → 自研玻璃渲染核） ----
    /** 长按菜单全屏底衬 View（应用/文件夹/卡片/堆叠长按共用同一弹窗底衬）。 */
    private const val CLASS_POPUP_BLUR_VIEW = "com.android.launcher3.popup.PopupBlurView"
    /** 底衬模糊壁纸层私有字段（BitmapDrawable，bounds=全屏）。 */
    private const val FIELD_POPUP_WM_DRAWABLE = "mWallpaperDrawable"
    /** 底衬模糊 dragLayer 层私有字段（BitmapDrawable，bounds=全屏）。 */
    private const val FIELD_POPUP_DL_DRAWABLE = "mDragLayerDrawable"
    /** 底衬绘制方法（原实现顺序画壁纸层→dragLayer 层→super 子 View）。 */
    private const val METHOD_POPUP_DISPATCH_DRAW = "dispatchDraw"
    /** 底衬分离回调（清理我们侧合成缓存；系统在其中回收两层源位图）。 */
    private const val METHOD_POPUP_ON_DETACHED = "onDetachedFromWindow"
    /** 长按菜单玻璃开关重读间隔（ms）。 */
    private const val POPUP_MENU_GLASS_TTL_MS = 500L

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

    /** screenRegionMap 条目：屏幕区域 + 局部 bounds + 宿主名 + 圆角 + 最近登记时刻 + 宿主弱引用。 */
    private data class RegionEntry(
        val region: RectF,
        val autoBounds: Rect,
        val hostViewName: String,
        val cornerRadius: Float,
        val lastSeenMs: Long,
        val hostViewRef: WeakReference<View>,
    )

    /** 整屏快照条目：captureDisplay 整屏（排除 Launcher 根 surface，0.5 降采样）干净壁纸背景，共享所有玻璃元素。 */
    private class ElementBackground(
        val bitmap: Bitmap,
        val region: RectF,
        /** [doc/spec/83] 位图烘焙时的壁纸缩放系数：**抓屏源** = 抓屏瞬间的壁纸缩放（屏幕上壁纸
         *  可能已被放大，位图内容已是缩放后的内容）；**壁纸文件裁剪源** = 1（文件永远未缩放）。
         *  折算只用相对变化 `wallpaperZoomScale / 本值`，避免「抓屏已含缩放 → 再折算一次」的双重缩放。 */
        val wallpaperZoomAtCapture: Float,
        /** 快照覆盖的屏幕坐标参考（壁纸条带偏移后与 region 不同，供壁纸缩放折算用） */
        val screenRect: RectF = region,
        /** bitmap 为整条可滚动壁纸条带（offset 变化只平移 region，不重裁位图） */
        val isWallpaperStrip: Boolean = false,
        /** 条带 offset=0 时的区域（region = 此矩形按当前 offset 平移） */
        val stripBaseRect: RectF? = null,
        /** 条带满滚动范围（屏幕像素） */
        val stripMaxScroll: PointF? = null,
    )

    /** [doc/spec/52] 壁纸文件解码源：整幅壁纸位图（inSampleSize 降采样）+ 逻辑原生尺寸 + 壁纸 id。
     *  渲染背景 = 从 [bitmap] 构建整条可滚动条带（见 buildVisibleCrop），offset 只平移 region。 */
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
        /** 材质参数指纹（含预设覆盖值）：任一材质键变化都要重设 uniform，否则被早退分支吃掉。 */
        val materialHash: Int = 0,
        /** 实际绑定的 uSourceLowRes 档位（miss 回退记实际档 4；档位变 → 全量重绑）。 */
        val lowResDiv: Int = 4,
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
    /** CornerParams 类兼容解析结果（旧 FQCN / zn.c / dex 探测命中；兼容链预留） */
    @Suppress("unused")
    private var mCornerParamsClass: Class<*>? = null
    /** 宿主 dex 类名枚举缓存（CornerType/CornerParams 探测共用，一次性；禁止热路径调用） */
    @Volatile
    private var hostDexNamesCache: List<String>? = null
    /** 探测执行标志（各跑一次，miss 也不重扫） */
    private var cornerTypeScanDone = false
    private var cornerParamsScanDone = false
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

    // [doc/spec/143] 桌面覆盖写穿：LayerBlurDrawable.setCornerRadius 四参写口
    //（名字优先，17.3.12 按形状指纹；解析/调用失败只禁写口，不崩不劣化）
    private var mLayerSetCornerRadius: Method? = null
    /** 写口失败后置 true，后续注册零反射直接跳过 */
    private var launcherCornerWriteDisabled = false

    /** [doc/spec/143] 桌面覆盖写穿影子：元素 id → 写穿前系统原角 / 我们最后写入值 */
    private class LauncherCornerShadow(
        val layerRef: WeakReference<Any>,
        /** 写穿前系统原角（= 注册期 resolvedCorner） */
        val original: Float,
        /** 我们最后一次写入的角（幂等比对） */
        var lastWritten: Float,
    )

    private val launcherCornerShadow = ConcurrentHashMap<Int, LauncherCornerShadow>()
    private const val LAUNCHER_CORNER_SHADOW_MAX = 32

    // [doc/spec/66] Dock 借壳反射（解析失败只禁 Dock 玻璃，不影响文件夹）
    private var mCreateBlurDrawable: Method? = null
    private var mShortcutsAndWidgetsField: Field? = null
    /** Dock 玻璃开关缓存（TTL 见 [DOCK_GLASS_TTL_MS]，热路径不每帧读 Prefs） */
    @Volatile
    private var cachedDockGlass = false
    @Volatile
    private var cachedDockGlassAtMs = 0L

    // [doc/spec/76] 问小布借壳反射（解析失败只禁问小布玻璃，不影响其他功能）
    private var mPageIndicatorGetBlurDrawable: Method? = null
    private var mPageIndicatorGetBackgroundRadius: Method? = null
    /** 问小布玻璃开关缓存（TTL 见 [PAGE_INDICATOR_GLASS_TTL_MS]，热路径不每帧读 Prefs） */
    @Volatile
    private var cachedPageIndicatorGlass = false
    @Volatile
    private var cachedPageIndicatorGlassAtMs = 0L
    /** 问小布开关是否已打过首读值日志（只打一次，防刷屏） */
    @Volatile
    private var pageIndicatorSwitchLogged = false

    // [doc/spec/78] 卡片借壳：圆角 tag id 解析缓存（0 = 尚未解析）
    private var mCardBlurRadiusTagId = 0
    /** [doc/spec/78] 卡片映射诊断日志计数（原因 → 已打条数，每原因最多 3 条） */
    private val cardDiagAtMs = ConcurrentHashMap<String, Long>()
    /** page-ind 诊断时间窗节流表（key → 上次打印时刻）。 */
    private val pageIndDiagAtMs = ConcurrentHashMap<String, Long>()
    /** 工作区页码胶囊资源 id（惰性解析缓存；0=解析失败）。 */
    @Volatile
    private var pageIndicatorResId = 0
    @Volatile
    private var pageIndicatorResIdResolved = false
    /** 卡片开关是否已打过首读值日志（只打一次，防刷屏） */
    @Volatile
    private var cardSwitchLogged = false
    /** [doc/spec/78] 等待布局完成后补登记的卡片宿主（弱引用集合：防重复挂监听 + 防泄漏） */
    private val pendingCardViews: MutableSet<View> =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>())
    /** 卡片玻璃开关缓存（TTL 见 [CARD_GLASS_TTL_MS]，热路径不每帧读 Prefs） */
    @Volatile
    private var cachedCardGlass = false
    @Volatile
    private var cachedCardGlassAtMs = 0L

    // ---- PressFeedback 玻璃接管（白名单驱动共享机制：drawPressedColor 一次挂载，
    //      按宿主白名单圈定接管范围；新宿主 = 白名单加条目 + 捕获挂点 + 开关行，零新绘制 hook） ----

    /** 白名单条目：宿主捕获槽 + 预设登记类 + 玻璃开关。 */
    private class PressGlassSlot(
        /** 宿主身份标签（日志用） */
        val name: String,
        /** GlassRegistry 预设登记键（按组件行命中） */
        val cardClass: String,
        /** 本条目玻璃开关（TTL volatile 各自缓存） */
        val enabled: () -> Boolean,
    ) {
        /** 捕获的宿主 View（该宿主捕获挂点写入；WeakReference 防泄漏） */
        @Volatile
        var hostRef: WeakReference<View>? = null

        /** 命中判定：拦截到的按压宿主是否本条目捕获的实例（引用身份比较）。 */
        fun matches(view: View): Boolean = hostRef?.get() === view
    }

    /** 清除钮条目（白名单第一条目；宿主 = OplusClearAllPanelView.btn_clear）。 */
    private val clearAllBtnSlot = PressGlassSlot(
        name = "clear-all-btn",
        cardClass = CLASS_CLEAR_ALL_BUTTON,
        enabled = { clearAllBtnGlassEnabled() },
    )

    /** 编辑模式「整理」钮条目（宿主 = ToggleStateToolbar.backToMainPage）。 */
    private val editTidySlot = PressGlassSlot(
        name = "edit-tidy-btn",
        cardClass = CLASS_CLEAR_ALL_BUTTON,
        enabled = { editToolbarGlassEnabled() },
    )

    /** 编辑模式「完成」钮条目（宿主 = ToggleStateToolbar.applyBtn）。 */
    private val editApplySlot = PressGlassSlot(
        name = "edit-apply-btn",
        cardClass = CLASS_CLEAR_ALL_BUTTON,
        enabled = { editToolbarGlassEnabled() },
    )

    /** 编辑工具条四圆钮条目（drawCircleColor 类门控直接命中，不经 hostRef 身份匹配）。 */
    private val editToolBtnSlot = PressGlassSlot(
        name = "edit-toolbtn",
        cardClass = CLASS_CLEAR_ALL_BUTTON,
        enabled = { editToolBtnGlassEnabled() },
    )

    /** 白名单表：drawPressedColor 拦截逐条匹配，未命中 → 原绘制。 */
    private val pressGlassSlots = listOf(clearAllBtnSlot, editTidySlot, editApplySlot)

    /** PressFeedbackHandler.mView 私有字段（白名单判定反射）。 */
    private var mPfhHostViewField: Field? = null
    /** PressFeedbackHandler.getMRadius()（平色圆角；解析失败回退 min(w,h)/2）。 */
    private var mPfhGetRadius: Method? = null
    /** PressFeedback 玻璃绘制专用 Paint（每帧绑定 shader，finally 清空防残留到其他绘制）。 */
    private val pressGlassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    /** 清除钮玻璃开关缓存（TTL 见 [CLEAR_ALL_BTN_GLASS_TTL_MS]，热路径不每帧读 Prefs）。 */
    @Volatile
    private var cachedClearAllBtnGlass = false
    @Volatile
    private var cachedClearAllBtnGlassAtMs = 0L

    /** 编辑工具条玻璃开关缓存（整理/完成两钮共享一个开关，TTL 同清除钮）。 */
    @Volatile
    private var cachedEditToolbarGlass = false
    @Volatile
    private var cachedEditToolbarGlassAtMs = 0L

    /** 编辑工具条四圆钮玻璃开关缓存（TTL 同清除钮）。 */
    @Volatile
    private var cachedEditToolBtnGlass = false
    @Volatile
    private var cachedEditToolBtnGlassAtMs = 0L

    // ---- 长按菜单底衬玻璃运行态 ----
    /** 底衬两层位图的反射字段（install 期解析）。 */
    @Volatile
    private var mPopupWmField: Field? = null
    @Volatile
    private var mPopupDlField: Field? = null
    /** 长按菜单玻璃开关缓存（TTL [POPUP_MENU_GLASS_TTL_MS]）。 */
    @Volatile
    private var cachedPopupMenuGlass = false
    @Volatile
    private var cachedPopupMenuGlassAtMs = 0L
    /** 玻璃绘制专用 Paint（每帧绑定 shader，finally 清空防残留到子 View 绘制）。 */
    private val popupGlassPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 两层系统位图的一次合成缓存（按底衬 View 身份键控；任一层 drawable 换新即重建）。 */
    private class PopupBackdrop(
        val wallpaperKey: Int,
        val dragLayerKey: Int,
        val bitmap: Bitmap,
    )

    /** 底衬 View identityHashCode → 合成背景（dispatchDraw 主线程访问；detach 清理）。 */
    private val popupBackdropMap = ConcurrentHashMap<Int, PopupBackdrop>()

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
            hostForName("android.app.ActivityThread")
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

    /** 高频路径日志去重表（同 key 1s 内只打一条，保留「出现了」的诊断价值） */
    private val logOnceAtMs = ConcurrentHashMap<String, Long>()

    private fun logOncePerSecond(key: String, block: () -> Unit) {
        val now = SystemClock.uptimeMillis()
        val last = logOnceAtMs[key] ?: 0L
        if (now - last < 1000L) return
        logOnceAtMs[key] = now
        if (logOnceAtMs.size > 16) logOnceAtMs.clear()
        block()
    }

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
                Lg.e(TAG, "wallpaper zoom: tick failed", t)
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

    /** 宿主 ClassLoader（install 期保存）：hide/宿主类反射用它做 caller，模块类做 caller 会被 hidden API 名单拦成 CNFE */
    @Volatile
    private var hostClassLoader: ClassLoader? = null

    // 宿主 ClassLoader 加载 hide/宿主类：宿主 loader miss 时回退裸加载（保留原降级行为）
    private fun hostForName(name: String): Class<*> {
        val cl = hostClassLoader
        return if (cl != null) Class.forName(name, true, cl) else Class.forName(name)
    }

    fun install(api: XposedInterface, classLoader: ClassLoader) {
        this.api = api
        hostClassLoader = classLoader
        GlassRegistry.attach { this.api?.let { a -> Prefs.read(a) } }
        // [2026-08-13 用户要求·关闭日志选项] install 时读取并缓存（热路径不每帧读 Prefs 防 binder 开销）
        moduleLogEnabled = try {
            Prefs.read(api).getBoolean(Prefs.KEY_ENABLE_LOGS, Prefs.DEFAULT_ENABLE_LOGS)
        } catch (t: Throwable) {
            false
        }
        LowResTier.logEnabled = moduleLogEnabled
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Lg.w(TAG, "LauncherHook skipped: SDK=${Build.VERSION.SDK_INT} < Q")
            return
        }
        resolveMethods(classLoader)
        mountDrawBlurShader(api, classLoader)
        mountPreviewBackground(api, classLoader)
        mountFolderRoundImageView(api, classLoader)
        // [doc/spec/66] 桌面 Dock 栏液态玻璃化（借壳：解开系统门控让 Dock 拿到 LayerBlurDrawable，
        // 再补映射交给现有 drawBlurShader 替换链渲染玻璃）
        mountDockGlass(api, classLoader)
        // [doc/spec/76] 桌面「问小布」入口液态玻璃化（借壳：系统已自建 LayerBlurDrawable，只需补映射）
        mountPageIndicatorGlass(api, classLoader)
        // [doc/spec/78] 桌面卡片/分组卡片/小组件背景玻璃化（Launcher 侧此前唯一未映射的模糊产生点）
        mountCardBlurGlass(api, classLoader)
        // PressFeedback 平色家族玻璃接管（白名单驱动；当前条目：最近任务清除钮/编辑模式整理完成钮）
        mountPressFeedbackGlass(api, classLoader)
        // 桌面长按菜单全屏底衬玻璃（离线位图两层 → 自研玻璃渲染核）
        mountPopupMenuGlass(api)
        mountOnBlurReady(api, classLoader)
        mountWallpaperOffsets(api, classLoader)
        // [doc/spec/83] 开/关/文件夹动画的壁纸缩放信号（镜像折算玻璃采样窗）
        mountWallpaperZoomCommand(api, classLoader)
        // [spec/85 补接] 省电模式广播（开 → 即刻门控 + 重录宿主；关 → 下一帧恢复玻璃）
        registerPowerSaveBroadcastReceiver()
        // 调试悬浮球（launcher 进程独立实例，与 SystemUI 三窗互不共享运行态）
        DebugOverlay.ensureStarted(
            contextProvider = { launcherAppContext },
            snapshotProvider = ::debugSnapshot,
            enabledProvider = { Prefs.debugDrawEnabled(api) },
            processLabel = "launcher",
        )
        Lg.i(TAG, "LauncherHook install done")
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
            Lg.e(TAG, "LauncherHook resolve BaseDrawable class failed, drawBlurShader replace disabled")
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
            Lg.i(TAG, "LauncherHook resolved OplusPreviewBackground/LayerBlurDrawable fields (folder icon map)")
        } catch (t: Throwable) {
            Lg.e(TAG, "LauncherHook resolve folder icon fields failed, folder icon map disabled", t)
        }
        // [doc/spec/143] 覆盖写穿写口：LayerBlurDrawable.setCornerRadius(float, boolean, ItemInfo, boolean)。
        // 名字优先；17.3.12 R8 混淆时按形状指纹扫描（float, boolean, 非primitive, boolean）→ void；仍失败只禁写口。
        try {
            val layerCls = mLayerCls ?: Class.forName(CLASS_LAYER_BLUR_DRAWABLE, false, classLoader)
            val itemInfoCls = Class.forName(CLASS_ITEM_INFO, false, classLoader)
            val byName = try {
                layerCls.getMethod(
                    "setCornerRadius",
                    Float::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                    itemInfoCls, Boolean::class.javaPrimitiveType,
                )
            } catch (t: Throwable) {
                null
            }
            mLayerSetCornerRadius = byName ?: layerCls.methods.firstOrNull { m ->
                val ps = m.parameterTypes
                ps.size == 4 &&
                    ps[0] == Float::class.javaPrimitiveType &&
                    ps[1] == Boolean::class.javaPrimitiveType &&
                    !ps[2].isPrimitive &&
                    ps[3] == Boolean::class.javaPrimitiveType &&
                    m.returnType == Void.TYPE
            }
            if (mLayerSetCornerRadius != null) {
                Lg.i(
                    TAG,
                    "LauncherHook resolved LayerBlurDrawable.setCornerRadius " +
                        "(${if (byName != null) "by-name" else "by-fingerprint"}) (corner write-through)",
                )
            } else {
                Lg.w(TAG, "LauncherHook setCornerRadius not found by name or fingerprint, corner write-through disabled")
            }
        } catch (t: Throwable) {
            Lg.w(TAG, "LauncherHook resolve setCornerRadius failed, corner write-through disabled", t)
        }
        // 闭合文件夹图标常态映射反射（FolderRoundImageView.onDraw → imageDrawable → 内层 BlurDrawable）
        try {
            val friv = Class.forName(CLASS_FOLDER_ROUND_IMAGE_VIEW, false, classLoader)
            mGetFrivRadius = friv.getMethod(METHOD_GET_M_RADIUS)
            Lg.i(TAG, "LauncherHook resolved FolderRoundImageView.getMRadius (folder icon normal-state map)")
        } catch (t: Throwable) {
            Lg.e(TAG, "LauncherHook resolve FolderRoundImageView failed, folder icon map uses fallback radius", t)
        }
        // [doc/spec/66] Dock 借壳反射：createBlurDrawable 在 17.3.12 不存在（BlurProperties 新体系），
        // 与 mShortcutsAndWidgetsField 拆开解析，字段仍可用于 onDraw 映射
        try {
            val hotseat = Class.forName(CLASS_OPLUS_HOTSEAT, false, classLoader)
            mShortcutsAndWidgetsField = findFieldAlongHierarchy(hotseat, FIELD_SHORTCUTS_AND_WIDGETS)
            Lg.i(TAG, "LauncherHook resolved OplusHotseat.mShortcutsAndWidgets (dock map)")
        } catch (t: Throwable) {
            Lg.e(TAG, "LauncherHook resolve OplusHotseat.mShortcutsAndWidgets failed, dock map disabled", t)
        }
        try {
            val hotseat = Class.forName(CLASS_OPLUS_HOTSEAT, false, classLoader)
            mCreateBlurDrawable = hotseat.getDeclaredMethod(METHOD_CREATE_BLUR_DRAWABLE)
                .apply { isAccessible = true }
            Lg.i(TAG, "LauncherHook resolved OplusHotseat.createBlurDrawable (dock glass)")
        } catch (t: Throwable) {
            if (isLauncher17Plus()) {
                Lg.i(
                    TAG_FINGERPRINT,
                    "launcher-glass: OplusHotseat.createBlurDrawable missing on 17.x (BlurProperties path), dock shell-borrow disabled",
                )
            } else {
                Lg.e(TAG, "LauncherHook resolve OplusHotseat.createBlurDrawable failed, dock glass disabled", t)
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
            Lg.i(TAG_FINGERPRINT, "launcher-glass: resolved @ old-name ($CLASS_BASE_DRAWABLE)")
            return c
        } catch (_: ClassNotFoundException) {
            Lg.i(TAG_FINGERPRINT, "launcher-glass: BaseDrawable old-name miss, try fingerprint")
        } catch (t: Throwable) {
            Lg.w(TAG_FINGERPRINT, "launcher-glass: BaseDrawable old-name load error", t)
        }
        // ② 指纹探针仅在 ≥17.3.12 启用（版本未知/-1 不探，防误判）
        if (!isLauncher17Plus()) {
            Lg.w(
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
                Lg.i(TAG_FINGERPRINT, "launcher-glass: resolved @ probe-inner (${outer.name} via $PROBE_BASE_DRAWABLE_INNER)")
                return outer
            }
        } catch (_: ClassNotFoundException) {
            Lg.i(TAG_FINGERPRINT, "launcher-glass: probe-inner miss, try probe-scan")
        } catch (t: Throwable) {
            Lg.w(TAG_FINGERPRINT, "launcher-glass: probe-inner failed", t)
        }
        // 探针2：drawable 包单字母有限枚举，找「extends Drawable + 持有 DrawableShader 字段 + Paint 字段」
        for (ch in PROBE_SCAN_LETTERS) {
            val name = "com.oplus.posteffect.drawable.$ch"
            try {
                val c = Class.forName(name, false, classLoader)
                if (isBaseDrawableFingerprint(c)) {
                    mBaseDrawableCls = c
                    mBaseDrawableFp = "probe-scan"
                    Lg.i(TAG_FINGERPRINT, "launcher-glass: resolved @ probe-scan ($name)")
                    return c
                }
            } catch (_: ClassNotFoundException) {
                continue
            } catch (_: Throwable) {
                continue
            }
        }
        Lg.e(TAG_FINGERPRINT, "launcher-glass: BaseDrawable fingerprint resolve failed (all probes miss)")
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
            Lg.i(TAG_FINGERPRINT, "launcher-glass: getBlurShaderPaint @ method (old-name)")
        } catch (_: NoSuchMethodException) {
            try {
                mBlurShaderPaintField = base.getDeclaredField(FIELD_BLUR_SHADER_PAINT_17)
                Lg.i(TAG_FINGERPRINT, "launcher-glass: getBlurShaderPaint @ field $FIELD_BLUR_SHADER_PAINT_17 (17.3.12)")
            } catch (t: Throwable) {
                Lg.e(TAG_FINGERPRINT, "launcher-glass: getBlurShaderPaint unresolved (no method, no field $FIELD_BLUR_SHADER_PAINT_17)", t)
            }
        } catch (t: Throwable) {
            Lg.e(TAG_FINGERPRINT, "launcher-glass: getBlurShaderPaint resolve error", t)
        }
        // getPaint：旧方法 → 17.x 字段 f28547f（当前渲染链未消费，兼容预留）
        try {
            mGetPaint = base.getMethod(METHOD_GET_PAINT)
            Lg.i(TAG_FINGERPRINT, "launcher-glass: getPaint @ method (old-name)")
        } catch (_: NoSuchMethodException) {
            try {
                mPaintField = base.getDeclaredField(FIELD_PAINT_17)
                Lg.i(TAG_FINGERPRINT, "launcher-glass: getPaint @ field $FIELD_PAINT_17 (17.3.12)")
            } catch (t: Throwable) {
                Lg.w(TAG_FINGERPRINT, "launcher-glass: getPaint unresolved (no method, no field $FIELD_PAINT_17)", t)
            }
        } catch (t: Throwable) {
            Lg.w(TAG_FINGERPRINT, "launcher-glass: getPaint resolve error", t)
        }
        // getBounds：继承 framework Drawable，旧名/17.x 均可用
        try {
            mGetBounds = base.getMethod(METHOD_GET_BOUNDS)
            Lg.i(TAG_FINGERPRINT, "launcher-glass: getBounds @ method (inherited)")
        } catch (t: Throwable) {
            Lg.e(TAG_FINGERPRINT, "launcher-glass: getBounds unresolved", t)
        }
        // drawBlurShader：17.x 不存在独立方法 → 标记内联路径（mount 阶段挂 static a(d)）
        try {
            base.getDeclaredMethod(METHOD_DRAW_BLUR_SHADER, Canvas::class.java)
            Lg.i(TAG_FINGERPRINT, "launcher-glass: drawBlurShader @ method (old-name)")
        } catch (_: NoSuchMethodException) {
            if (isLauncher17Plus()) {
                mInlineRecordMethod = resolveInlineRecordMethod(base)
                Lg.i(
                    TAG_FINGERPRINT,
                    "launcher-glass: drawBlurShader @ inline " +
                        (mInlineRecordMethod?.let { "${it.declaringClass.name}#${it.name}(d)" } ?: "UNRESOLVED"),
                )
            }
        } catch (t: Throwable) {
            Lg.w(TAG_FINGERPRINT, "launcher-glass: drawBlurShader resolve error", t)
        }
        // 路径 A 自录所需字段（仅 17.3.12；16.x 不解析不触发）
        if (isLauncher17Plus() && mInlineRecordMethod != null) {
            try {
                mRenderNodeField = base.getDeclaredField(FIELD_RENDER_NODE_17)
                mDrawablePathField = base.getDeclaredField(FIELD_DRAWABLE_PATH_17)
                mPaintAlphaField = base.getDeclaredField(FIELD_PAINT_ALPHA_17)
                mEnableBlurShaderField = base.getDeclaredField(FIELD_ENABLE_BLUR_SHADER_17)
                Lg.i(
                    TAG_FINGERPRINT,
                    "launcher-glass: inline record fields @ $FIELD_RENDER_NODE_17/$FIELD_DRAWABLE_PATH_17/" +
                        "$FIELD_PAINT_ALPHA_17/$FIELD_ENABLE_BLUR_SHADER_17",
                )
            } catch (t: Throwable) {
                Lg.e(TAG_FINGERPRINT, "launcher-glass: inline record fields unresolved, path A disabled", t)
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

    /** [spec/117 §4] CornerType/CornerParams 兼容链：旧 FQCN → 17.x params.CornerType / zn.c；
     *  硬编码候选全 miss 时转一次性宿主 dex 探测（scanCornerTypeCompat / scanCornerParamsCompat）。 */
    private fun resolveCornerCompat(classLoader: ClassLoader) {
        // CornerType：旧 com.oplus.posteffect.CornerType#getField("CONIC")
        try {
            val old = Class.forName("com.oplus.posteffect.CornerType", false, classLoader)
            mCornerTypeConic = old.getField("CONIC").get(null)
            Lg.i(TAG_FINGERPRINT, "launcher-glass: CornerType.CONIC @ field CONIC (old-name)")
        } catch (_: ClassNotFoundException) {
            try {
                val ct17 = Class.forName(CLASS_CORNER_TYPE_17, false, classLoader)
                // 17.x 常量名混淆（a/b/c/d），values() 顺序 NONE,FULL,CONIC,G2 → CONIC = values()[2]
                val conic = ct17.getMethod("values").invoke(null)?.let { arr ->
                    (arr as? Array<*>)?.getOrNull(2)
                }
                if (conic != null) {
                    mCornerTypeConic = conic
                    Lg.i(TAG_FINGERPRINT, "launcher-glass: CornerType.CONIC @ values()[2] ($CLASS_CORNER_TYPE_17)")
                } else {
                    try {
                        mCornerTypeConic = ct17.getField("f28674c").get(null)
                        Lg.i(TAG_FINGERPRINT, "launcher-glass: CornerType.CONIC @ field f28674c ($CLASS_CORNER_TYPE_17)")
                    } catch (t: Throwable) {
                        Lg.w(TAG_FINGERPRINT, "launcher-glass: CornerType.CONIC unresolved on 17.3.12", t)
                    }
                }
            } catch (t2: Throwable) {
                Lg.w(TAG_FINGERPRINT, "launcher-glass: CornerType unresolved (old miss,17.x miss)", t2)
            }
        } catch (t: Throwable) {
            Lg.w(TAG_FINGERPRINT, "launcher-glass: CornerType old-name CONIC field failed", t)
        }
        // CornerParams 类：旧 FQCN → zn.c（仅确认类在，无 getter 可反射）
        var paramsHit: Class<*>? = null
        try {
            paramsHit = Class.forName("com.oplus.posteffect.CornerParams", false, classLoader)
            Lg.i(TAG_FINGERPRINT, "launcher-glass: CornerParams @ old-name")
        } catch (_: ClassNotFoundException) {
            try {
                paramsHit = Class.forName(CLASS_CORNER_PARAMS_17, false, classLoader)
                Lg.i(TAG_FINGERPRINT, "launcher-glass: CornerParams @ $CLASS_CORNER_PARAMS_17 (17.3.12)")
            } catch (t: Throwable) {
                Lg.w(TAG_FINGERPRINT, "launcher-glass: CornerParams unresolved (old miss,17.x miss)", t)
            }
        } catch (t: Throwable) {
            Lg.w(TAG_FINGERPRINT, "launcher-glass: CornerParams old-name load error", t)
        }
        mCornerParamsClass = paramsHit
        // 硬编码候选全 miss 才进 dex 探测（探测只在 resolve 期跑，各一次）
        if (mCornerTypeConic == null) scanCornerTypeCompat(classLoader)
        if (paramsHit == null) scanCornerParamsCompat(classLoader)
    }

    /** 枚举宿主 ClassLoader 全部 dex 内的类名（BaseDexClassLoader.pathList.dexElements[].dexFile.entries()）。
     *  一次性执行、结果缓存；任一步异常返回 null（调用方按探测 miss 降级）。 */
    private fun hostDexClassNames(classLoader: ClassLoader): List<String>? {
        hostDexNamesCache?.let { return it }
        return try {
            val bdl = Class.forName("dalvik.system.BaseDexClassLoader", false, classLoader)
            if (!bdl.isInstance(classLoader)) return null
            val pathList = bdl.getDeclaredField("pathList").apply { isAccessible = true }.get(classLoader)
            val elements = pathList.javaClass.getDeclaredField("dexElements")
                .apply { isAccessible = true }.get(pathList) as? Array<*>
                ?: return null
            val names = ArrayList<String>()
            for (el in elements) {
                val df = el?.javaClass?.getDeclaredField("dexFile")
                    ?.apply { isAccessible = true }?.get(el) ?: continue
                @Suppress("UNCHECKED_CAST")
                val entries = df.javaClass.getMethod("entries").invoke(df) as? java.util.Enumeration<String>
                    ?: continue
                while (entries.hasMoreElements()) {
                    names.add(entries.nextElement())
                }
            }
            hostDexNamesCache = names
            names
        } catch (t: Throwable) {
            null
        }
    }

    /** 从候选类提取 CONIC 常量：CONIC 字段直取 → 4 常量枚举取 values()[2]（常量序 NONE,FULL,CONIC,G2）；都失败返回 null。 */
    private fun conicFrom(clazz: Class<*>): Any? {
        try {
            return clazz.getField("CONIC").get(null)
        } catch (_: Throwable) {
        }
        return try {
            if (clazz.isEnum) clazz.enumConstants?.takeIf { it.size == 4 }?.get(2) else null
        } catch (t: Throwable) {
            null
        }
    }

    /** CornerType 运行时 dex 探测（硬编码候选全 miss 后执行，一次性）。
     *  匹配依据：① 类名末段以 CornerType 结尾（构建未混淆该类名时命中，含 GradientStrokeCornerType 等同族）；
     *  ② 构建把类名混淆掉时，筛 com.oplus.posteffect 包内「恰好 4 个常量的枚举」——CornerType 固有
     *  NONE,FULL,CONIC,G2 四常量且顺序恒定，故唯一命中取 values()[2]；多候选项不唯一即放弃（保守降级）。 */
    private fun scanCornerTypeCompat(classLoader: ClassLoader) {
        if (cornerTypeScanDone) return
        cornerTypeScanDone = true
        var hitCls: Class<*>? = null
        var conic: Any? = null
        try {
            val names = hostDexClassNames(classLoader)
            if (names != null) {
                val byName = names.filter { it.substringAfterLast('.').endsWith("CornerType") }
                for (n in byName) {
                    val clazz = try {
                        Class.forName(n, false, classLoader)
                    } catch (_: ClassNotFoundException) {
                        continue
                    }
                    val v = conicFrom(clazz)
                    if (v != null) {
                        hitCls = clazz
                        conic = v
                        break
                    }
                }
                if (hitCls == null) {
                    val structural = ArrayList<String>()
                    for (n in names) {
                        if (!n.startsWith("com.oplus.posteffect.")) continue
                        val clazz = try {
                            Class.forName(n, false, classLoader)
                        } catch (_: Throwable) {
                            continue
                        }
                        val fourConstEnum = try {
                            clazz.isEnum && clazz.enumConstants?.size == 4
                        } catch (t: Throwable) {
                            false
                        }
                        if (fourConstEnum) {
                            structural.add(n)
                            if (structural.size >= 2) break
                        }
                    }
                    if (structural.size == 1) {
                        val clazz = try {
                            Class.forName(structural[0], false, classLoader)
                        } catch (t: Throwable) {
                            null
                        }
                        val v = clazz?.enumConstants?.getOrNull(2)
                        if (clazz != null && v != null) {
                            hitCls = clazz
                            conic = v
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            hitCls = null
            conic = null
        }
        if (hitCls != null && conic != null) {
            mCornerTypeConic = conic
            Lg.i(TAG_FINGERPRINT, "launcher-glass: CornerType scanned hit=${hitCls.name}")
        } else {
            Lg.i(TAG_FINGERPRINT, "launcher-glass: CornerType scanned miss")
        }
    }

    /** CornerParams 运行时 dex 探测（硬编码候选全 miss 后执行，一次性）。
     *  匹配依据：① 类名末段以 CornerParams 结尾（含 GradientStrokeCornerParams 同族）；
     *  ② 类名混淆时按结构筛 com.oplus.posteffect 包内「声明了名字含 radius 的 float/double 字段、
     *  且有非合成构造器」的类——字段名被混淆的构建取不到该信号，命中数非 1（0 或多）一律放弃（保守降级）。 */
    private fun scanCornerParamsCompat(classLoader: ClassLoader) {
        if (cornerParamsScanDone) return
        cornerParamsScanDone = true
        var hitCls: Class<*>? = null
        try {
            val names = hostDexClassNames(classLoader)
            if (names != null) {
                for (n in names) {
                    if (!n.substringAfterLast('.').endsWith("CornerParams")) continue
                    hitCls = try {
                        Class.forName(n, false, classLoader)
                    } catch (_: ClassNotFoundException) {
                        continue
                    }
                    break
                }
                if (hitCls == null) {
                    val structural = ArrayList<String>()
                    for (n in names) {
                        if (!n.startsWith("com.oplus.posteffect.")) continue
                        val clazz = try {
                            Class.forName(n, false, classLoader)
                        } catch (_: Throwable) {
                            continue
                        }
                        val radiusField = try {
                            clazz.declaredFields.any { f ->
                                (f.type == Float::class.javaPrimitiveType ||
                                    f.type == Double::class.javaPrimitiveType) &&
                                    f.name.contains("radius", ignoreCase = true)
                            }
                        } catch (t: Throwable) {
                            false
                        }
                        val ctor = try {
                            clazz.declaredConstructors.isNotEmpty()
                        } catch (t: Throwable) {
                            false
                        }
                        if (radiusField && ctor) {
                            structural.add(n)
                            if (structural.size >= 2) break
                        }
                    }
                    if (structural.size == 1) {
                        hitCls = try {
                            Class.forName(structural[0], false, classLoader)
                        } catch (t: Throwable) {
                            null
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            hitCls = null
        }
        if (hitCls != null) {
            mCornerParamsClass = hitCls
            Lg.i(TAG_FINGERPRINT, "launcher-glass: CornerParams scanned hit=${hitCls.name}")
        } else {
            Lg.i(TAG_FINGERPRINT, "launcher-glass: CornerParams scanned miss")
        }
    }

    /** [spec/116 P0/P1] 宿主 ≥17.3.12 判定（进程内缓存；读失败/-1 按 false，指纹路径不触发）。 */
    private fun isLauncher17Plus(): Boolean {
        launcherVer17Plus?.let { return it }
        val ver = HostVersion.hostVersionCode("com.android.launcher")
        val v = ver >= VERSION_LAUNCHER_17_3_12
        launcherVer17Plus = v
        Lg.i(TAG_FINGERPRINT, "launcher-glass: host versionCode=$ver,17.3.12+=$v")
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
            Lg.e(TAG, "LauncherHook drawBlurShader old-name lookup error", t)
            null
        }
        if (oldMethod != null) {
            if (mGetBlurShaderPaint == null && mBlurShaderPaintField == null) {
                Lg.w(TAG, "LauncherHook BaseDrawable paint unresolved, drawBlurShader replace disabled")
                return
            }
            try {
                api.hook(oldMethod)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val drawable = try {
                            chain.getThisObject()
                        } catch (t: Throwable) {
                            Lg.e(TAG, "launcher drawBlurShader: getThisObject failed", t); null
                        }
                        if (drawable != null) {
                            try {
                                replaceShaderIfPossible(drawable)
                            } catch (t: Throwable) {
                                Lg.e(TAG, "launcher render: drawBlurShader intercept error", t)
                            }
                        }
                        chain.proceed()
                    }
                Lg.i(TAG, "LauncherHook mounted: ${oldMethod.declaringClass.name}#$METHOD_DRAW_BLUR_SHADER(Canvas)")
                GlassRegistry.registerClass(oldMethod.declaringClass.name)
            } catch (t: Throwable) {
                Lg.e(TAG, "LauncherHook mount FAILED: $CLASS_BASE_DRAWABLE#$METHOD_DRAW_BLUR_SHADER", t)
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
                Lg.e(TAG_FINGERPRINT, "launcher-glass: P1 主链未启用（inline a(d) 未解析），回退系统模糊")
            }
            return
        }
        if (mBlurShaderPaintField == null || mRenderNodeField == null ||
            mDrawablePathField == null || mPaintAlphaField == null
        ) {
            Lg.e(TAG_FINGERPRINT, "launcher-glass: path A fields incomplete, inline hook disabled")
            return
        }
        try {
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val drawable = try {
                        chain.getArgs()?.getOrNull(0)
                    } catch (t: Throwable) {
                        Lg.e(TAG_FINGERPRINT, "launcher-glass: inline getArgs failed", t); null
                    }
                    var handled = false
                    if (drawable != null) {
                        handled = try {
                            recordGlassInline(drawable)
                        } catch (t: Throwable) {
                            Lg.e(TAG_FINGERPRINT, "launcher-glass: inline record error, fallback proceed", t)
                            false
                        }
                    }
                    if (!handled) chain.proceed()
                }
            Lg.i(
                TAG_FINGERPRINT,
                "launcher-glass: mounted path A ${method.declaringClass.name}#${method.name}(d) (inline record + skip proceed)",
            )
        } catch (t: Throwable) {
            Lg.e(TAG_FINGERPRINT, "launcher-glass: path A mount FAILED, system blur fallback", t)
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
            Lg.e(TAG_FINGERPRINT, "launcher-glass: read renderNode failed", t); null
        } ?: return false
        val path = try {
            mDrawablePathField?.get(drawable) as? Path
        } catch (t: Throwable) {
            Lg.e(TAG_FINGERPRINT, "launcher-glass: read drawablePath failed", t); null
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
            Lg.e(TAG_FINGERPRINT, "launcher-glass: beginRecording failed, fallback proceed", t); null
        } ?: return false
        try {
            canvas.drawPath(path, paint)
            recorded = true
        } finally {
            try {
                rn.endRecording()
            } catch (t: Throwable) {
                Lg.e(TAG_FINGERPRINT, "launcher-glass: endRecording failed", t)
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
            Lg.w(TAG, "LauncherHook folder icon fields unresolved, folder icon map disabled")
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
                                Lg.e(TAG, "launcher preview: drawBackground intercept error", t)
                            }
                            chain.proceed()
                        }
                    val sig = method.parameterTypes.joinToString(", ") { it.simpleName }
                    Lg.i(TAG, "LauncherHook mounted: $CLASS_OPLUS_PREVIEW_BACKGROUND#$METHOD_DRAW_BACKGROUND($sig) (folder icon map)")
                    GlassRegistry.registerClass(CLASS_OPLUS_PREVIEW_BACKGROUND)
                    mounted = true
                    // 16.x 双参命中即停；17.x 多签名只挂最靠前的一个（避免重复注册映射）
                    break
                } catch (t: Throwable) {
                    Lg.w(TAG, "LauncherHook mount drawBackground(${method.parameterTypes.joinToString { it.simpleName }}) failed, try next", t)
                }
            }
            if (!mounted) {
                Lg.e(TAG, "LauncherHook mount FAILED: $CLASS_OPLUS_PREVIEW_BACKGROUND#$METHOD_DRAW_BACKGROUND (all candidate signatures)")
            }
        } catch (t: Throwable) {
            Lg.e(TAG, "LauncherHook mount FAILED: $CLASS_OPLUS_PREVIEW_BACKGROUND#$METHOD_DRAW_BACKGROUND", t)
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
            Lg.w(TAG, "LauncherHook LayerBlurDrawable.mBlurDrawable unresolved, folder icon normal-state map disabled")
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
                        Lg.e(TAG, "launcher preview: FolderRoundImageView.onDraw intercept error", t)
                    }
                    chain.proceed()
                }
            Lg.i(TAG, "LauncherHook mounted: $CLASS_FOLDER_ROUND_IMAGE_VIEW#$METHOD_ON_DRAW(Canvas) (folder icon map, normal state)")
            GlassRegistry.registerClass(CLASS_FOLDER_ROUND_IMAGE_VIEW)
        } catch (t: Throwable) {
            Lg.e(TAG, "LauncherHook mount FAILED: $CLASS_FOLDER_ROUND_IMAGE_VIEW#$METHOD_ON_DRAW", t)
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
            Lg.i(TAG, "LauncherHook mounted: $CLASS_SCREEN_UTILS#$METHOD_IS_SUPPORT_DOCKER_EXPAND_SCREEN -> true (dock glass)")
        } catch (t: Throwable) {
            Lg.e(TAG, "LauncherHook mount FAILED: $CLASS_SCREEN_UTILS#$METHOD_IS_SUPPORT_DOCKER_EXPAND_SCREEN", t)
        }

        if (mCreateBlurDrawable == null || mShortcutsAndWidgetsField == null) {
            Lg.w(TAG, "LauncherHook dock glass: OplusHotseat unresolved, skip remaining dock hooks")
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
                        Lg.e(TAG, "launcher dock: force blur background failed", t)
                    }
                    result
                }
            Lg.i(TAG, "LauncherHook mounted: $CLASS_OPLUS_HOTSEAT#$METHOD_SET_DOCKER_BACKGROUND (dock blur background)")
        } catch (t: Throwable) {
            Lg.e(TAG, "LauncherHook mount FAILED: $CLASS_OPLUS_HOTSEAT#$METHOD_SET_DOCKER_BACKGROUND", t)
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
                        Lg.e(TAG, "launcher dock: onDraw map failed", t)
                    }
                    chain.proceed()
                }
            Lg.i(TAG, "LauncherHook mounted: $CLASS_OPLUS_HOTSEAT#$METHOD_ON_DRAW(Canvas) (dock map)")
            GlassRegistry.registerClass(CLASS_OPLUS_HOTSEAT)
        } catch (t: Throwable) {
            Lg.e(TAG, "LauncherHook mount FAILED: $CLASS_OPLUS_HOTSEAT#$METHOD_ON_DRAW", t)
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
            Lg.e(TAG, "launcher dock: read LayerBlurDrawable.mBlurDrawable failed", t); null
        } ?: return
        registerBlurRegion(inner, sw, resolveDockCornerPx(sw), hostName = "Dock", blurLayerForCorner = drawable)
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
            Lg.e(TAG, "launcher dock: resolve corner radius failed, fallback ${DOCK_CORNER_DP_FALLBACK}dp", t)
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
                Lg.w(TAG, "prefs read dock_glass_enable failed, default false", t)
                false
            }
        }
        return cachedDockGlass
    }

    /**
     * [doc/spec/76] 桌面「问小布」入口液态玻璃化 —— 借壳，只需「补映射」一步。
     *
     * 与 Dock 的差异：Dock 在直板机上被系统门控挡死、系统从不给它配背景，要解门控 + 强建 drawable；
     * 问小布系统自己已建好 `LayerBlurDrawable`，本模块只要把它的 region 登记进 screenRegionMap，
     * drawBlurShader 就会把系统磨砂换成自研玻璃。
     */
    private fun mountPageIndicatorGlass(api: XposedInterface, classLoader: ClassLoader) {
        try {
            val clazz = hostForName(CLASS_OPLUS_PAGE_INDICATOR)
            mPageIndicatorGetBlurDrawable = clazz.getMethod(METHOD_GET_BLUR_DRAWABLE)
            mPageIndicatorGetBackgroundRadius = clazz.getMethod(METHOD_GET_BACKGROUND_RADIUS)
        } catch (t: Throwable) {
            Lg.e(TAG, "launcher page-indicator: resolve $CLASS_OPLUS_PAGE_INDICATOR failed, glass disabled", t)
            return
        }
        try {
            val clazz = hostForName(CLASS_OPLUS_PAGE_INDICATOR)
            val method = clazz.getMethod(METHOD_DISPATCH_DRAW, Canvas::class.java)
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        if (pageIndicatorGlassEnabled()) registerPageIndicatorRegion(chain)
                    } catch (t: Throwable) {
                        Lg.e(TAG, "launcher page-indicator: dispatchDraw map failed", t)
                    }
                    chain.proceed()
                }
            Lg.i(TAG, "LauncherHook mounted: $CLASS_OPLUS_PAGE_INDICATOR#$METHOD_DISPATCH_DRAW(Canvas) (ask-xiaobu map)")
        } catch (t: Throwable) {
            Lg.e(TAG, "LauncherHook mount FAILED: $CLASS_OPLUS_PAGE_INDICATOR#$METHOD_DISPATCH_DRAW", t)
        }
    }

    /** [doc/spec/76] 问小布映射登记：系统自建 LayerBlurDrawable → 内层 posteffect BlurDrawable
     *  → screenRegionMap（键与 drawBlurShader 的 this 同 identityHashCode）。
     *  getBlurDrawable() 返回 null 时回退（不登记 = 保持系统磨砂），回退原因打节流诊断日志。 */
    private fun registerPageIndicatorRegion(chain: XposedInterface.Chain) {
        val indicator = chain.getThisObject() as? View ?: return
        // 文件夹页码实例（id≠工作区 page_indicator）诊断配额与工作区实例隔离
        val indReasonSuffix = if (isWorkspacePageIndicator(indicator)) "" else "-folder"
        val layer = try {
            mPageIndicatorGetBlurDrawable?.invoke(indicator)
        } catch (t: Throwable) {
            Lg.e(TAG, "launcher page-indicator: getBlurDrawable failed", t); null
        } ?: run {
            logCardDiag(
                "page-ind-null-layer$indReasonSuffix",
                "coord: PageIndicator skip —— getBlurDrawable null（背景层缺失，保持系统磨砂）",
            )
            return
        }
        if (mLayerCls?.isInstance(layer) != true) {
            logCardDiag(
                "page-ind-not-layer$indReasonSuffix",
                "coord: PageIndicator skip —— getBlurDrawable 返回 ${layer?.javaClass?.name ?: "null"} 非 LayerBlurDrawable",
            )
            return
        }
        val inner = try {
            mLayerBlurInnerField?.get(layer)
        } catch (t: Throwable) {
            Lg.e(TAG, "launcher page-indicator: read LayerBlurDrawable.mBlurDrawable failed", t); null
        } ?: run {
            logCardDiag(
                "page-ind-null-inner$indReasonSuffix",
                "coord: PageIndicator skip —— LayerBlurDrawable.mBlurDrawable null",
            )
            return
        }
        val corner = try {
            (mPageIndicatorGetBackgroundRadius?.invoke(indicator) as? Int)?.toFloat() ?: 0f
        } catch (t: Throwable) {
            Lg.w(TAG, "launcher page-indicator: getBackgroundRadius failed, fallback min(w,h)/2", t)
            0f
        }
        // 编辑态缩放几何诊断：scale≠1 特征帧 2s 节流优先可见；常态 60s 节流兜底（不烧配额）
        val geomSx = indicator.scaleX
        val geomSy = indicator.scaleY
        val geomLoc = IntArray(2)
        indicator.getLocationOnScreen(geomLoc)
        val geomGlobal = Rect()
        val geomHasGlobal = try {
            indicator.getGlobalVisibleRect(geomGlobal)
        } catch (t: Throwable) {
            false
        }
        val geomMsg =
            "coord: PageIndicator geom raw=${indicator.width}x${indicator.height} loc=[${geomLoc[0]},${geomLoc[1]}] " +
                "global=$geomGlobal hasGlobal=$geomHasGlobal scale=($geomSx,$geomSy) pivot=(${indicator.pivotX},${indicator.pivotY}) " +
                "trans=(${indicator.translationX},${indicator.translationY})"
        if (geomSx != 1f || geomSy != 1f) {
            logPageIndDiag("page-ind-geom-ed", geomMsg, 2000L)
        } else {
            logPageIndDiag("page-ind-geom-normal", geomMsg, 60_000L)
        }
        registerBlurRegion(
            inner, indicator, corner,
            hostName = "PageIndicator", cardClass = CLASS_OPLUS_PAGE_INDICATOR,
            blurLayerForCorner = layer,
        )
    }

    /** 工作区页码胶囊判定：id == 宿主包 page_indicator 资源 id（惰性一次解析）。 */
    private fun isWorkspacePageIndicator(view: View): Boolean {
        if (!pageIndicatorResIdResolved) {
            pageIndicatorResIdResolved = true
            pageIndicatorResId = try {
                view.resources.getIdentifier("page_indicator", "id", view.context.packageName)
            } catch (t: Throwable) {
                0
            }
        }
        return pageIndicatorResId != 0 && view.id == pageIndicatorResId
    }

    /** page-ind 诊断时间窗节流：正文以 [key] 起头（grep 可达），每 key 每 windowMs 最多一条。 */
    private fun logPageIndDiag(key: String, msg: String, windowMs: Long) {
        val now = SystemClock.uptimeMillis()
        val prev = pageIndDiagAtMs[key] ?: 0L
        if (now - prev < windowMs) return
        pageIndDiagAtMs[key] = now
        Lg.i(TAG, "[$key] $msg")
    }

    /** [doc/spec/76] 问小布玻璃开关（TTL [PAGE_INDICATOR_GLASS_TTL_MS] 重读，改动无需重启 Launcher）。 */
    private fun pageIndicatorGlassEnabled(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - cachedPageIndicatorGlassAtMs > PAGE_INDICATOR_GLASS_TTL_MS) {
            cachedPageIndicatorGlassAtMs = now
            val before = cachedPageIndicatorGlass
            cachedPageIndicatorGlass = try {
                api?.let {
                    Prefs.read(it).getBoolean(Prefs.KEY_PAGE_INDICATOR_GLASS, Prefs.DEFAULT_PAGE_INDICATOR_GLASS)
                } ?: false
            } catch (t: Throwable) {
                Lg.w(TAG, "prefs read page_indicator_glass_enable failed, default false", t)
                false
            }
            val shouldLog = cachedPageIndicatorGlass != before || !pageIndicatorSwitchLogged
            pageIndicatorSwitchLogged = true
            if (shouldLog) {
                Lg.i(TAG, "launcher page-indicator: switch ${Prefs.KEY_PAGE_INDICATOR_GLASS} = $cachedPageIndicatorGlass")
            }
        }
        return cachedPageIndicatorGlass
    }

    /**
     * [doc/spec/78] 桌面卡片 / 分组卡片 / 小组件背景玻璃化。
     *
     * 与文件夹映射同类：借壳 + 补映射。对 Launcher 全部 `OplusBlurProperties.prepareBlur()` 调用点
     * 做过穷举，只有 5 处产生 LayerBlurDrawable，本类是其中此前唯一未映射的一处。
     */
    private fun mountCardBlurGlass(api: XposedInterface, classLoader: ClassLoader) {
        if (mLayerBlurInnerField == null) {
            Lg.w(TAG, "LauncherHook LayerBlurDrawable.mBlurDrawable unresolved, card blur map disabled")
            return
        }
        try {
            val clazz = hostForName(CLASS_CARD_BACKGROUND_BLUR_MANAGER)
            val method = clazz.getDeclaredMethod(
                METHOD_CREATE_BLUR_BACKGROUND,
                View::class.java, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val target = try {
                        chain.getArg(0) as? View
                    } catch (t: Throwable) {
                        Lg.e(TAG, "launcher card: read targetView failed", t); null
                    }
                    val result = chain.proceed()
                    val cardEnabled = cardGlassEnabled()
                    if (target != null && cardEnabled) {
                        try {
                            registerCardBlurRegion(target)
                        } catch (t: Throwable) {
                            Lg.e(TAG, "launcher card: createBlurBackground map failed", t)
                        }
                    } else if (target == null) {
                        logCardDiag("no-target", "launcher card: createBlurBackground 取不到 targetView（arg0）")
                    }
                    result
                }
            Lg.i(
                TAG,
                "LauncherHook mounted: $CLASS_CARD_BACKGROUND_BLUR_MANAGER#$METHOD_CREATE_BLUR_BACKGROUND" +
                    "(View, boolean, int) (card/group-card/widget map)",
            )
        } catch (t: Throwable) {
            Lg.e(TAG, "LauncherHook mount FAILED: $CLASS_CARD_BACKGROUND_BLUR_MANAGER#$METHOD_CREATE_BLUR_BACKGROUND", t)
        }
    }

    /** [doc/spec/78] 卡片映射登记：背景在 proceed() 内由 prepareBlur 装上，尺寸未就绪时延迟到布局后。 */
    private fun registerCardBlurRegion(target: View) {
        val inner = resolveCardInner(target) ?: return
        if (target.isAttachedToWindow && target.width > 0 && target.height > 0) {
            registerBlurRegion(
                inner, target, readCardCornerPx(target),
                hostName = "Card", cardClass = CLASS_CARD_BACKGROUND_BLUR_MANAGER,
                blurLayerForCorner = target.background,
            )
            return
        }
        scheduleCardRegionAfterLayout(target)
    }

    /** [doc/spec/78] 解析卡片的内层 posteffect drawable；失败时打诊断并返回 null。 */
    private fun resolveCardInner(target: View): Any? {
        val drawable = try {
            target.background
        } catch (t: Throwable) {
            Lg.e(TAG, "launcher card: read background failed", t); null
        }
        if (drawable == null) {
            logCardDiag("no-bg", "launcher card: ${target.javaClass.name} 无 background（未生成模糊底？）")
            return null
        }
        if (mLayerCls?.isInstance(drawable) != true) {
            logCardDiag(
                "not-layer-" + drawable.javaClass.simpleName,
                "launcher card: ${target.javaClass.name} 背景是 ${drawable.javaClass.name}，不是 LayerBlurDrawable",
            )
            return null
        }
        val inner = try {
            mLayerBlurInnerField?.get(drawable)
        } catch (t: Throwable) {
            Lg.e(TAG, "launcher card: read LayerBlurDrawable.mBlurDrawable failed", t); null
        }
        if (inner == null) {
            logCardDiag("no-inner", "launcher card: LayerBlurDrawable.mBlurDrawable 为 null")
            return null
        }
        return inner
    }

    /** [doc/spec/78] 卡片宿主布局未完成时，挂一次性 layout 监听，布局后补登记（弱引用去重防重复挂）。 */
    private fun scheduleCardRegionAfterLayout(target: View) {
        if (!pendingCardViews.add(target)) return
        logCardDiag("defer", "launcher card: ${target.javaClass.name} 布局未完成，延迟到 layout 后登记")
        target.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View, left: Int, top: Int, right: Int, bottom: Int,
                oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int,
            ) {
                v.removeOnLayoutChangeListener(this)
                pendingCardViews.remove(v)
                if (v.width <= 0 || v.height <= 0) return
                try {
                    val innerNow = resolveCardInner(v) ?: return
                    registerBlurRegion(
                        innerNow, v, readCardCornerPx(v),
                        hostName = "Card", cardClass = CLASS_CARD_BACKGROUND_BLUR_MANAGER,
                        blurLayerForCorner = v.background,
                    )
                } catch (t: Throwable) {
                    Lg.e(TAG, "launcher card: deferred map failed", t)
                }
            }
        })
    }

    /** [doc/spec/78] 卡片映射诊断日志：每 reason 每时间窗最多一条（正文以 [reason] 起头，grep 可达）。 */
    private fun logCardDiag(reason: String, msg: String, windowMs: Long = 60_000L) {
        val now = SystemClock.uptimeMillis()
        val prev = cardDiagAtMs[reason] ?: 0L
        if (now - prev < windowMs) return
        cardDiagAtMs[reason] = now
        Lg.i(TAG, "[$reason] $msg")
    }

    /** [doc/spec/78] 卡片玻璃开关（TTL [CARD_GLASS_TTL_MS] 重读，改动无需重启 Launcher）。 */
    private fun cardGlassEnabled(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - cachedCardGlassAtMs > CARD_GLASS_TTL_MS) {
            cachedCardGlassAtMs = now
            val before = cachedCardGlass
            cachedCardGlass = try {
                api?.let {
                    Prefs.read(it).getBoolean(Prefs.KEY_CARD_GLASS_ENABLE, Prefs.DEFAULT_CARD_GLASS_ENABLE)
                } ?: false
            } catch (t: Throwable) {
                Lg.w(TAG, "prefs read card_glass_enable failed, default false", t)
                false
            }
            val shouldLog = cachedCardGlass != before || !cardSwitchLogged
            cardSwitchLogged = true
            if (shouldLog) {
                Lg.i(TAG, "launcher card: switch ${Prefs.KEY_CARD_GLASS_ENABLE} = $cachedCardGlass")
            }
        }
        return cachedCardGlass
    }

    /** [doc/spec/78] 卡片圆角 px：优先读系统建 blur 背景时 setTag 的圆角；tag 缺失（轮播/堆叠
     *  背景层从不打 tag，系统可见圆角走 view outline clip）时读 outline provider 圆角半径；
     *  都读不到返回 0f → registerBlurRegion 回退 min(w,h)/2。tag id 惰性解析并缓存。 */
    private fun readCardCornerPx(target: View): Float {
        if (mCardBlurRadiusTagId == 0) {
            mCardBlurRadiusTagId = resolveCardBlurRadiusTagId(target)
        }
        var corner = 0f
        if (mCardBlurRadiusTagId != 0) {
            corner = try {
                (target.getTag(mCardBlurRadiusTagId) as? Float) ?: 0f
            } catch (t: Throwable) {
                0f
            }
        }
        if (corner <= 0f) {
            corner = readOutlineClipCornerPx(target)
        }
        if (corner <= 0f) {
            logCardDiag(
                "no-corner-tag",
                "launcher card: ${target.javaClass.name} 无 $ID_KEY_BLUR_VIEW_RADIUS tag 且 outline 无圆角，圆角走 min/2 兜底",
            )
        }
        return corner
    }

    /** CarouselBlurBackgroundView（轮播/堆叠背景层）不打 radius tag，系统靠 outline clip 出圆角：
     *  ClipSmoothOutlineProvider 首个 float 字段 = 圆角半径（px），另一 float 是角权重（无量纲
     *  <1，取 max 不会误选）。clipToOutline 未开或读不到返回 0f。 */
    private fun readOutlineClipCornerPx(target: View): Float {
        if (!target.clipToOutline) return 0f
        val provider = try {
            target.outlineProvider
        } catch (t: Throwable) {
            null
        } ?: return 0f
        return try {
            var radius = 0f
            for (f in provider.javaClass.declaredFields) {
                if (f.type == Float::class.javaPrimitiveType) {
                    f.isAccessible = true
                    val v = f.getFloat(provider)
                    if (v > radius) radius = v
                }
            }
            radius
        } catch (t: Throwable) {
            0f
        }
    }

    /** tag id 属桌面 APK 资源；小组件子 View 的 context 是小组件 app 的，
     *  用它 getIdentifier 恒 0（资源里没有该 id）→ 圆角误走 min/2。优先用进程内桌面 Application 解析。 */
    private fun resolveCardBlurRadiusTagId(target: View): Int {
        val appTagId = try {
            val app = hostForName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null) as? android.content.Context
            app?.resources?.getIdentifier(ID_KEY_BLUR_VIEW_RADIUS, "id", app.packageName) ?: 0
        } catch (t: Throwable) {
            0
        }
        if (appTagId != 0) return appTagId
        return try {
            target.resources.getIdentifier(ID_KEY_BLUR_VIEW_RADIUS, "id", target.context.packageName)
        } catch (t: Throwable) {
            Lg.w(TAG, "launcher card: resolve $ID_KEY_BLUR_VIEW_RADIUS failed", t); 0
        }
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
                                Lg.e(TAG, "launcher blur-refresh: onBlurReady intercept error", t)
                            }
                            chain.proceed()
                        }
                    Lg.i(
                        TAG,
                        "LauncherHook mounted: $CLASS_BLUR_DRAWABLE_MANAGER#$name(List, HardwareBuffer, int, float) (folder bg blur refresh signal)",
                    )
                    mounted = true
                    break
                } catch (_: NoSuchMethodException) {
                    continue
                } catch (t: Throwable) {
                    Lg.w(TAG, "LauncherHook mount $CLASS_BLUR_DRAWABLE_MANAGER#$name failed, try next", t)
                }
            }
            if (!mounted) {
                Lg.e(TAG, "LauncherHook mount FAILED: $CLASS_BLUR_DRAWABLE_MANAGER#onBlurReady/f (all candidate names)")
            }
        } catch (t: Throwable) {
            Lg.e(TAG, "LauncherHook mount FAILED: $CLASS_BLUR_DRAWABLE_MANAGER#$METHOD_ON_BLUR_READY", t)
        }
    }

    /**
     * [doc/spec/52 壁纸文件解码背景源 2026-08-13] hook `WallpaperManager.setWallpaperOffsets(IBinder,
     * float xOffset, float yOffset)`——桌面翻页滚动壁纸的视差更新信号（反编译 OplusLauncher
     * WallpaperOffsetInterpolator L63 实证：mWM.setWallpaperOffsets(iBinder, mCurrentOffset, 0.5f)，
     * 在 WALLPAPER_TRANSACTION_EXECUTOR 线程调用，offset=[0,1]）。
     *
     * 滚动壁纸背景源 = 整条可滚动壁纸条带位图（壁纸 id 变/首次才构建一次）：offset 更新只平移
     * 快照 region（零位图重裁、零 worker 入队）。静态/非滚动壁纸（maxScroll=0）region 恒等于
     * 条带本身，offset 无影响。失败只禁视差跟手（保留静态条带），不崩 Launcher。
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
                            applyWallpaperParallaxShift()
                        }
                    } catch (t: Throwable) {
                        Lg.e(TAG, "launcher parallax: setWallpaperOffsets intercept error", t)
                    }
                    chain.proceed()
                }
            Lg.i(TAG, "LauncherHook mounted: $CLASS_WALLPAPER_MANAGER#$METHOD_SET_WALLPAPER_OFFSETS(IBinder, float, float) (wallpaper parallax)")
        } catch (t: Throwable) {
            Lg.e(TAG, "LauncherHook mount FAILED: $CLASS_WALLPAPER_MANAGER#$METHOD_SET_WALLPAPER_OFFSETS", t)
        }
    }

    /** offset 变化 → 平移壁纸条带快照的 region（复用同一位图，零重裁、零入队）；region 未变则完全静默。 */
    private fun applyWallpaperParallaxShift() {
        val snap = screenSnapshot ?: return
        if (!snap.isWallpaperStrip) return
        val base = snap.stripBaseRect ?: return
        val maxScroll = snap.stripMaxScroll ?: return
        val cropX = wallpaperOffsetX.coerceIn(0f, 1f) * maxScroll.x
        val cropY = wallpaperOffsetY.coerceIn(0f, 1f) * maxScroll.y
        val region = RectF(base.left - cropX, base.top - cropY, base.right - cropX, base.bottom - cropY)
        if (region == snap.region) return
        LowResTier.prebuild(snap.bitmap)
        screenSnapshot = ElementBackground(
            snap.bitmap, region, snap.wallpaperZoomAtCapture, snap.screenRect,
            isWallpaperStrip = true, stripBaseRect = base, stripMaxScroll = maxScroll,
        )
        postInvalidateRegistered()
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
                        Lg.e(TAG, "wallpaper zoom: sendWallpaperCommand intercept error", t)
                    }
                    chain.proceed()
                }
            Lg.i(
                TAG,
                "LauncherHook mounted: $CLASS_WALLPAPER_MANAGER#$METHOD_SEND_WALLPAPER_COMMAND(IBinder, String, int, int, int, Bundle) (wallpaper zoom mirror)",
            )
        } catch (t: Throwable) {
            Lg.e(TAG, "LauncherHook mount FAILED: $CLASS_WALLPAPER_MANAGER#$METHOD_SEND_WALLPAPER_COMMAND", t)
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
            Lg.e(TAG, "wallpaper zoom: parse command failed", t)
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
            Lg.e(TAG, "launcher preview: getThisObject failed", t); return
        } ?: return
        val bgDrawable = try {
            mBgDrawableField?.get(oplusBg) as? Drawable
        } catch (t: Throwable) {
            Lg.e(TAG, "launcher preview: read mBgDrawable failed", t); null
        } ?: return
        // 仅 LayerBlurDrawable（含 posteffect BlurDrawable 内层）才注册；非 blur 路径（LayerNormalDrawable/
        // GradientDrawable 纯色背景）不触发 drawBlurShader hook，注册无意义
        if (mLayerCls?.isInstance(bgDrawable) != true) return
        val inner = try {
            mLayerBlurInnerField?.get(bgDrawable)
        } catch (t: Throwable) {
            Lg.e(TAG, "launcher preview: read LayerBlurDrawable.mBlurDrawable failed", t); null
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
            Lg.e(TAG, "launcher preview: read mBgView failed", t); null
        } ?: return
        registerBlurRegion(inner, host, radius)
    }

    /** [常态] hookBefore：FolderRoundImageView.onDraw → imageDrawable（LayerBlurDrawable）→ 注册映射。 */
    private fun registerFolderRoundImageViewRegion(chain: XposedInterface.Chain) {
        val view = try {
            chain.getThisObject() as? View
        } catch (t: Throwable) {
            Lg.e(TAG, "launcher preview: FolderRoundImageView getThisObject failed", t); null
        } ?: return
        val drawable = try {
            (view as? android.widget.ImageView)?.drawable
        } catch (t: Throwable) {
            Lg.e(TAG, "launcher preview: FolderRoundImageView getDrawable failed", t); null
        } as? Drawable ?: return
        if (mLayerCls?.isInstance(drawable) != true) return
        val inner = try {
            mLayerBlurInnerField?.get(drawable)
        } catch (t: Throwable) {
            Lg.e(TAG, "launcher preview: read LayerBlurDrawable.mBlurDrawable failed", t); null
        } ?: return
        val radius = try {
            mGetFrivRadius?.invoke(view) as? Float ?: 0f
        } catch (t: Throwable) {
            0f
        }
        registerBlurRegion(inner, view, radius)
    }

    /** 公共注册：内层 BlurDrawable → screenRegionMap（region = hostView 屏幕位置 + 尺寸，corner = 圆角）。
     *  [hostName] 标识宿主来源，用于日志与 RegionEntry.hostViewName（默认 FolderIcon 保持既有调用点行为）。
     *  [cardClass] 该元素所属的卡片语义类（问小布/桌面卡片），随 bind 登记供预设按组件命中。
     *  [blurLayerForCorner] LayerBlurDrawable 实例（覆盖写穿写口；不传 = 该宿主不参与写穿）。 */
    private fun registerBlurRegion(
        inner: Any,
        hostView: View,
        corner: Float,
        hostName: String = "FolderIcon",
        cardClass: String? = null,
        blurLayerForCorner: Any? = null,
    ) {
        if (!hostView.isAttachedToWindow) {
            logCardDiag("$hostName-not-attached", "coord: $hostName 跳过 —— 宿主 ${hostView.javaClass.name} 未 attach")
            return
        }
        val bw = hostView.width
        val bh = hostView.height
        if (bw <= 0 || bh <= 0) {
            logCardDiag("$hostName-zero-size", "coord: $hostName 跳过 —— 宿主尺寸 ${bw}x${bh}（通常= 布局尚未完成）")
            return
        }
        // 登记区域用变换后可见矩形（getGlobalVisibleRect：祖先缩放/平移后的真实屏幕占地，
        // scale=1 全可见时与 loc+布局尺寸一致）；不可见/读失败回退 loc+布局尺寸
        val globalRect = Rect()
        val hasGlobal = try {
            hostView.getGlobalVisibleRect(globalRect)
        } catch (t: Throwable) {
            false
        }
        val region = if (hasGlobal && !globalRect.isEmpty) {
            RectF(globalRect)
        } else {
            val loc = IntArray(2)
            try {
                hostView.getLocationOnScreen(loc)
            } catch (t: Throwable) {
                Lg.e(TAG, "launcher preview: getLocationOnScreen failed", t); return
            }
            RectF(loc[0].toFloat(), loc[1].toFloat(), (loc[0] + bw).toFloat(), (loc[1] + bh).toFloat())
        }
        val key = System.identityHashCode(inner)
        GlassRegistry.bind(key, inner, cardClass)
        val resolvedCorner = if (corner > 0f) corner else minOf(bw, bh) / 2f
        applyLauncherCornerWrite(blurLayerForCorner, key, resolvedCorner, bw.toFloat(), bh.toFloat(), "$hostName")
        registerHostView(hostView)
        val previous = screenRegionMap[key]
        screenRegionMap[key] = RegionEntry(
            region,
            Rect(0, 0, bw, bh),
            hostName,
            resolvedCorner,
            SystemClock.uptimeMillis(),
            WeakReference(hostView),
        )
        if (previous == null) {
            if (screenRegionMap.size > 512) screenRegionMap.clear()
            Lg.i(
                TAG,
                "coord: $hostName id=$key view=${hostView.javaClass.simpleName} region=$region raw=${bw}x${bh} " +
                    "cornerRadius=$resolvedCorner"
            )
            // [doc/spec/07] 新映射建立 = 内容变化信号 → 触发整屏快照重抓（无节流立即）
            triggerBackgroundCapture()
        }
        // 位置/尺寸/重绘更新仅写表不重抓（背景为静态壁纸快照，srcRect 渲染侧实时折算已跟手）
    }

    /** 调试快照条目 TTL（超时条目视为已回收，不进快照）。 */
    private const val DEBUG_SNAPSHOT_TTL_MS = 3000L

    /** 调试悬浮球快照：只读 screenRegionMap，TTL 过滤后映射为 DebugElement（零副作用）。 */
    internal fun debugSnapshot(): List<DebugElement> {
        val now = SystemClock.uptimeMillis()
        val out = ArrayList<DebugElement>(screenRegionMap.size)
        for ((id, e) in screenRegionMap) {
            if (now - e.lastSeenMs > DEBUG_SNAPSHOT_TTL_MS) continue
            out.add(
                DebugElement(
                    id = id,
                    region = RectF(e.region),
                    cornerRadius = e.cornerRadius,
                    cornerRadiusSource = "launcher",
                    hostViewName = e.hostViewName,
                    sourceTag = debugSourceTagOf(e.hostViewName),
                    renderLeg = null,
                    lastSeenMs = e.lastSeenMs,
                    hostViewRef = e.hostViewRef,
                )
            )
        }
        return out
    }

    /** 宿主名 → 调试快照挂点标签。 */
    private fun debugSourceTagOf(hostName: String): String = when (hostName) {
        "Card" -> "launcher-card"
        "Dock" -> "launcher-dock"
        "PageIndicator" -> "launcher-page-indicator"
        else -> "launcher-folder"
    }

    /** [doc/spec/143 §5.3] 桌面 Layer A 覆盖写穿：ov 非空且总开关开 → desired 写进
     *  LayerBlurDrawable.setCornerRadius（内部写 mBlurDrawable path 圆角）；ov 空 / 总开关关
     *  且此前写过 → 影子回写系统原值。desired 对齐渲染侧 cap 公式（短边一半封顶）。
     *  任何解析/调用失败只禁写口，不崩不劣化（玻璃角照变、容器不跟 = 现状）。 */
    private fun applyLauncherCornerWrite(
        layer: Any?,
        id: Int,
        resolvedCorner: Float,
        w: Float,
        h: Float,
        hostTag: String,
    ) {
        val ov = GlassRegistry.cornerOverrideFor(id)
        val existing = launcherCornerShadow[id]
        if (ov == null || !Prefs.glassEnabled(api)) {
            if (existing != null) restoreLauncherCornerShadow(id, existing, hostTag)
            return
        }
        if (layer == null || launcherCornerWriteDisabled) return
        val method = mLayerSetCornerRadius
        if (method == null) {
            launcherCornerWriteDisabled = true
            Lg.w(TAG, "launcher corner-write: setCornerRadius unresolved, write-through disabled")
            return
        }
        val desired = ov.coerceAtMost(minOf(w, h) / 2f)
        if (existing != null && existing.lastWritten == desired) return
        try {
            // 对齐系统 tag 路径形态（CardBackgroundBlurManager mask=8：isFolder=false、itemInfo=null、isHotseat=false）
            method.invoke(layer, desired, false, null, false)
        } catch (t: Throwable) {
            launcherCornerWriteDisabled = true
            Lg.w(TAG, "launcher corner-write: setCornerRadius invoke failed, write-through disabled", t)
            return
        }
        if (existing != null) {
            existing.lastWritten = desired
        } else {
            if (launcherCornerShadow.size >= LAUNCHER_CORNER_SHADOW_MAX) restoreAllLauncherCorners()
            launcherCornerShadow[id] = LauncherCornerShadow(WeakReference(layer), resolvedCorner, desired)
        }
        Lg.i(TAG, "launcher corner-write: $hostTag id=$id own=$resolvedCorner -> $desired")
    }

    /** [doc/spec/143 §六] 单条还原：影子回写系统原值（layer 已被 GC 时只清影子）。 */
    private fun restoreLauncherCornerShadow(id: Int, shadow: LauncherCornerShadow, hostTag: String) {
        val layer = shadow.layerRef.get()
        val method = mLayerSetCornerRadius
        if (layer != null && method != null) {
            try {
                method.invoke(layer, shadow.original, false, null, false)
                Lg.i(TAG, "launcher corner-write: $hostTag id=$id restored -> ${shadow.original}")
            } catch (t: Throwable) {
                Lg.w(TAG, "launcher corner-write: $hostTag id=$id restore invoke failed", t)
            }
        }
        launcherCornerShadow.remove(id)
    }

    /** [doc/spec/143 §六] 影子表满：先全量回写再清（防「写穿值滞留系统、还原依据被丢」）。 */
    private fun restoreAllLauncherCorners() {
        if (launcherCornerShadow.isEmpty()) return
        Lg.i(TAG, "launcher corner-write: shadow table full (${launcherCornerShadow.size}), restore-all then clear")
        for ((id, sh) in launcherCornerShadow) {
            val layer = sh.layerRef.get()
            val method = mLayerSetCornerRadius
            if (layer != null && method != null) {
                try {
                    method.invoke(layer, sh.original, false, null, false)
                } catch (t: Throwable) {
                    Lg.w(TAG, "launcher corner-write: restore-all id=$id failed", t)
                }
            }
        }
        launcherCornerShadow.clear()
    }

    // ------------------------------------------------------------ PressFeedback 玻璃接管（白名单驱动共享机制）

    /**
     * PressFeedback 平色家族玻璃接管 —— 共享挂点。按钮背景是 PressFeedbackHandler 的平色
     * Paint 圆角矩形/圆形（全链零 blur，不走 posteffect 漏斗）→ 必须 View 级接管。
     * handler 被 launcher 全库按钮共用（widget 面板等）→ **必须 [pressGlassSlots] 白名单圈定**，
     * 未命中一律原绘制。玻璃绘制/bounds 跟随/快照失效回落全部收在共享路径
     * [drawPressGlassOnCanvas]；新宿主接入 = 白名单加 [PressGlassSlot] 条目 + 该宿主的
     * 捕获挂点 + 开关行，零新绘制 hook。任何解析/绘制失败只回退系统平色，不崩 Launcher。
     */
    private fun mountPressFeedbackGlass(api: XposedInterface, classLoader: ClassLoader) {
        // ① 各宿主捕获挂点（按钮实例就绪时点 → 写入白名单槽）
        mountClearAllBtnCapture(api)
        mountEditToolbarCapture(api)
        // ② 共享拦截：drawPressedColor 白名单命中 → 玻璃顶掉平色（不 proceed）；未命中原样
        try {
            val handlerCls = hostForName(CLASS_PRESS_FEEDBACK_HANDLER)
            mPfhHostViewField = handlerCls.getDeclaredField(FIELD_PFH_HOST_VIEW).apply { isAccessible = true }
            mPfhGetRadius = handlerCls.getMethod(METHOD_GET_M_RADIUS)
            val method = handlerCls.getDeclaredMethod(METHOD_DRAW_PRESSED_COLOR, Canvas::class.java)
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    var glassDrawn = false
                    try {
                        val view = mPfhHostViewField?.get(chain.getThisObject()) as? View
                        if (view != null) {
                            val slot = pressGlassSlots.firstOrNull { it.matches(view) }
                            if (slot != null && slot.enabled()) {
                                val canvas = chain.getArgs()?.getOrNull(0) as? Canvas
                                if (canvas != null) {
                                    val radius = try {
                                        (mPfhGetRadius?.invoke(chain.getThisObject()) as? Float) ?: 0f
                                    } catch (t: Throwable) {
                                        0f
                                    }
                                    glassDrawn = drawPressGlassOnCanvas(canvas, view, radius, slot)
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        Lg.e(TAG, "launcher press-glass: drawPressedColor intercept error", t)
                    }
                    if (!glassDrawn) chain.proceed()
                }
            Lg.i(TAG, "LauncherHook mounted: $CLASS_PRESS_FEEDBACK_HANDLER#$METHOD_DRAW_PRESSED_COLOR(Canvas) (press-feedback glass whitelist)")
        } catch (t: Throwable) {
            Lg.e(TAG, "LauncherHook mount FAILED: $CLASS_PRESS_FEEDBACK_HANDLER#$METHOD_DRAW_PRESSED_COLOR, press-feedback glass disabled", t)
        }
        // ③ 编辑工具条四圆钮：drawCircleColor 类门控（唯一调用方 = PressFeedbackCircleImageView，
        //    类名圈定即可，无需身份槽）；radius = h/2（正圆语义，不读 mRadius 高光偏移值）。
        //    无 alpha 守卫（alpha=0 帧照画，合成期自然乘掉，按 alpha skip 会永久空白）。
        try {
            val circleCls = hostForName(CLASS_PRESS_FEEDBACK_HANDLER)
            mPfhHostViewField = circleCls.getDeclaredField(FIELD_PFH_HOST_VIEW).apply { isAccessible = true }
            val circleMethod = circleCls.getDeclaredMethod(METHOD_DRAW_CIRCLE_COLOR, Canvas::class.java)
            api.hook(circleMethod)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    var glassDrawn = false
                    try {
                        val view = mPfhHostViewField?.get(chain.getThisObject()) as? View
                        if (view != null && view.javaClass.name == CLASS_CIRCLE_IMAGE_VIEW) {
                            val canvas = chain.getArgs()?.getOrNull(0) as? Canvas
                            if (canvas != null) {
                                glassDrawn = drawPressGlassOnCanvas(canvas, view, view.height / 2f, editToolBtnSlot)
                            }
                        }
                    } catch (t: Throwable) {
                        Lg.e(TAG, "launcher press-glass: drawCircleColor intercept error", t)
                    }
                    if (!glassDrawn) chain.proceed()
                }
            Lg.i(TAG, "LauncherHook mounted: $CLASS_PRESS_FEEDBACK_HANDLER#$METHOD_DRAW_CIRCLE_COLOR(Canvas) (edit-toolbtn circle glass)")
        } catch (t: Throwable) {
            Lg.e(TAG, "LauncherHook mount FAILED: $CLASS_PRESS_FEEDBACK_HANDLER#$METHOD_DRAW_CIRCLE_COLOR, edit toolbtn glass disabled", t)
        }
    }

    /** 清除钮捕获挂点：面板 onFinishInflate 后读 public 字段 mClearAllBtn 写入白名单槽。
     *  挂失败只禁清除钮条目（共享拦截照挂，供其他条目使用）。 */
    private fun mountClearAllBtnCapture(api: XposedInterface) {
        try {
            val panel = hostForName(CLASS_CLEAR_ALL_PANEL_VIEW)
            val field = panel.getField(FIELD_CLEAR_ALL_BTN)
            val method = panel.getMethod("onFinishInflate")
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val btn = field.get(chain.getThisObject()) as? View
                        if (btn != null) {
                            clearAllBtnSlot.hostRef = WeakReference(btn)
                            Lg.i(TAG, "launcher press-glass: ${clearAllBtnSlot.name} captured (${btn.javaClass.name})")
                        }
                    } catch (t: Throwable) {
                        Lg.w(TAG, "launcher press-glass: read mClearAllBtn failed", t)
                    }
                    result
                }
            Lg.i(TAG, "LauncherHook mounted: $CLASS_CLEAR_ALL_PANEL_VIEW#onFinishInflate (${clearAllBtnSlot.name} capture)")
        } catch (t: Throwable) {
            Lg.e(TAG, "LauncherHook mount FAILED: $CLASS_CLEAR_ALL_PANEL_VIEW#onFinishInflate, ${clearAllBtnSlot.name} glass disabled", t)
        }
    }

    /** 清除钮玻璃开关（TTL [CLEAR_ALL_BTN_GLASS_TTL_MS] 重读，改动无需重启 Launcher）。 */
    private fun clearAllBtnGlassEnabled(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - cachedClearAllBtnGlassAtMs > CLEAR_ALL_BTN_GLASS_TTL_MS) {
            cachedClearAllBtnGlassAtMs = now
            cachedClearAllBtnGlass = try {
                api?.let {
                    Prefs.read(it).getBoolean(Prefs.KEY_CLEAR_ALL_BTN_GLASS, Prefs.DEFAULT_CLEAR_ALL_BTN_GLASS)
                } ?: false
            } catch (t: Throwable) {
                Lg.w(TAG, "prefs read clear_all_btn_glass_enable failed, default false", t)
                false
            }
        }
        return cachedClearAllBtnGlass
    }

    /** 编辑工具条捕获挂点：工具条 onFinishInflate 后读 getter 捕获整理/完成两钮写入白名单槽。
     *  挂失败只留空槽（拦截照挂但恒不命中 = 原绘制，零风险）。 */
    private fun mountEditToolbarCapture(api: XposedInterface) {
        try {
            val toolbar = hostForName(CLASS_EDIT_TOOLBAR)
            val getTidy = toolbar.getMethod(METHOD_GET_BACK_TO_MAIN_PAGE)
            val getApply = toolbar.getMethod(METHOD_GET_APPLY_BTN)
            val method = toolbar.getMethod("onFinishInflate")
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val obj = chain.getThisObject()
                        (getTidy.invoke(obj) as? View)?.let {
                            editTidySlot.hostRef = WeakReference(it)
                            Lg.i(TAG, "launcher press-glass: ${editTidySlot.name} captured (${it.javaClass.name})")
                        }
                        (getApply.invoke(obj) as? View)?.let {
                            editApplySlot.hostRef = WeakReference(it)
                            Lg.i(TAG, "launcher press-glass: ${editApplySlot.name} captured (${it.javaClass.name})")
                        }
                    } catch (t: Throwable) {
                        Lg.w(TAG, "launcher press-glass: read edit toolbar buttons failed", t)
                    }
                    result
                }
            Lg.i(TAG, "LauncherHook mounted: $CLASS_EDIT_TOOLBAR#onFinishInflate (${editTidySlot.name}/${editApplySlot.name} capture)")
        } catch (t: Throwable) {
            Lg.e(TAG, "LauncherHook mount FAILED: $CLASS_EDIT_TOOLBAR#onFinishInflate, edit toolbar glass disabled", t)
        }
    }

    /** 编辑工具条玻璃开关（TTL [EDIT_TOOLBAR_GLASS_TTL_MS] 重读，开关切换无需重启 Launcher）。 */
    private fun editToolbarGlassEnabled(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - cachedEditToolbarGlassAtMs > EDIT_TOOLBAR_GLASS_TTL_MS) {
            cachedEditToolbarGlassAtMs = now
            cachedEditToolbarGlass = try {
                api?.let {
                    Prefs.read(it).getBoolean(Prefs.KEY_EDIT_TOOLBAR_GLASS, Prefs.DEFAULT_EDIT_TOOLBAR_GLASS)
                } ?: false
            } catch (t: Throwable) {
                Lg.w(TAG, "prefs read edit_toolbar_glass_enable failed, default false", t)
                false
            }
        }
        return cachedEditToolbarGlass
    }

    /** 编辑工具条四圆钮玻璃开关（TTL [EDIT_TOOLBTN_GLASS_TTL_MS] 重读，开关切换无需重启 Launcher）。 */
    private fun editToolBtnGlassEnabled(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - cachedEditToolBtnGlassAtMs > EDIT_TOOLBTN_GLASS_TTL_MS) {
            cachedEditToolBtnGlassAtMs = now
            cachedEditToolBtnGlass = try {
                api?.let {
                    Prefs.read(it).getBoolean(Prefs.KEY_EDIT_TOOLBTN_GLASS, Prefs.DEFAULT_EDIT_TOOLBTN_GLASS)
                } ?: false
            } catch (t: Throwable) {
                Lg.w(TAG, "prefs read edit_toolbtn_glass_enable failed, default false", t)
                false
            }
        }
        return cachedEditToolBtnGlass
    }

    /** 在按压宿主的 onDraw canvas 上画液态玻璃（坐标系 = 按钮局部坐标，无需平移）。
     *  返回 true = 已画玻璃（拦截侧跳过原平色）；false = 任一门控/快照失败，回退原平色（零黑屏）。
     *  每帧全量重绑 uniform（单个小按钮，成本可忽略）。 */
    private fun drawPressGlassOnCanvas(canvas: Canvas, btn: View, handlerRadius: Float, slot: PressGlassSlot): Boolean {
        if (isPowerSaveGlassOff()) return false
        if (!slot.enabled()) return false
        if (!Prefs.glassEnabled(api)) return false
        val w = btn.width
        val h = btn.height
        if (w <= 0 || h <= 0) return false
        if (!btn.isAttachedToWindow) return false
        val id = System.identityHashCode(btn)
        GlassRegistry.bind(id, btn, slot.cardClass)
        registerHostView(btn)
        val loc = IntArray(2)
        try {
            btn.getLocationOnScreen(loc)
        } catch (t: Throwable) {
            Lg.w(TAG, "launcher press-glass: ${slot.name} getLocationOnScreen failed", t)
            return false
        }
        val region = RectF(
            loc[0].toFloat(),
            loc[1].toFloat(),
            (loc[0] + w).toFloat(),
            (loc[1] + h).toFloat(),
        )
        val renderSource = resolveRenderSource(id, region)
        if (renderSource == null) {
            logRenderMode(id, "BLUR", "no snapshot source")
            return false
        }
        val corner = if (handlerRadius > 0f) handlerRadius else minOf(w, h) / 2f
        val params = buildParams(w.toFloat(), h.toFloat(), corner, elementId = id)
        val shader = shaderCache.getOrPut(id) { ShaderInstancePool.pollOrCreate() }
        shaderOwnerSet.add(shader)
        if (shaderCache.size > 128) { shaderCache.clear(); shaderOwnerSet.clear() }
        val sourceShader = LiquidGlassShader.createSourceBitmapShader(renderSource.source)
        val wantDiv = LowResTier.tierOf(0.807f * params.blurRadius)
        val tier = LowResTier.tierShaderOf(renderSource.source, renderSource.srcRect, wantDiv)
        LiquidGlassShader.setUniforms(
            shader, params, sourceShader, tier?.shader ?: sourceShader,
            sourceRect = renderSource.srcRect,
            lowResSourceRect = tier?.lowResSrcRect ?: renderSource.srcRect,
        )
        return try {
            canvas.save()
            try {
                pressGlassPaint.shader = shader
                canvas.drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), corner, corner, pressGlassPaint)
            } finally {
                pressGlassPaint.shader = null
                canvas.restore()
            }
            logRenderMode(id, "GLASS")
            true
        } catch (t: Throwable) {
            Lg.e(TAG, "launcher press-glass: ${slot.name} draw failed", t)
            logRenderMode(id, "BLUR", "draw failed")
            false
        }
    }

    // ------------------------------------------------------------ 桌面长按菜单底衬玻璃

    /**
     * 长按菜单全屏底衬玻璃接管 —— hook `PopupBlurView.dispatchDraw`：底衬是系统离线位图管线
     * （模糊壁纸层 + 模糊 dragLayer 层，与 posteffect 漏斗零关系）→ 必须 View 级接管。
     * 开关关/源位图未就绪/任一门控失败 → 原样 proceed（系统两层照画）。玻璃命中时先画玻璃
     * 再把两层字段临时置空 → proceed 只画子 View（菜单项在玻璃上），finally 还原字段保系统
     * 回收链（onDetachedFromWindow 按位图身份回收，字段必须是原对象）。
     * 底衬创建即 setAlpha(0)+330ms 动画 → **严禁 alpha<=0 守卫**（alpha=0 帧照画，合成期
     * RenderNode alpha 自然乘掉；按 alpha skip 会永久空背景）。
     */
    private fun mountPopupMenuGlass(api: XposedInterface) {
        try {
            val popupCls = hostForName(CLASS_POPUP_BLUR_VIEW)
            mPopupWmField = popupCls.getDeclaredField(FIELD_POPUP_WM_DRAWABLE).apply { isAccessible = true }
            mPopupDlField = popupCls.getDeclaredField(FIELD_POPUP_DL_DRAWABLE).apply { isAccessible = true }
            val dispatch = popupCls.getDeclaredMethod(METHOD_POPUP_DISPATCH_DRAW, Canvas::class.java)
            api.hook(dispatch)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    var glassDrawn = false
                    try {
                        val view = chain.getThisObject() as? View
                        val canvas = chain.getArgs()?.getOrNull(0) as? Canvas
                        if (view != null && canvas != null && popupMenuGlassActive() && drawPopupGlassOnCanvas(canvas, view)) {
                            glassDrawn = true
                            val wm = mPopupWmField?.get(view)
                            val dl = mPopupDlField?.get(view)
                            mPopupWmField?.set(view, null)
                            mPopupDlField?.set(view, null)
                            try {
                                chain.proceed()
                            } finally {
                                mPopupWmField?.set(view, wm)
                                mPopupDlField?.set(view, dl)
                            }
                        }
                    } catch (t: Throwable) {
                        Lg.w(TAG, "launcher popup-glass: dispatchDraw intercept error", t)
                    }
                    if (!glassDrawn) chain.proceed()
                }
            // 分离清理：合成缓存位图是我们侧分配的，系统只回收它自己的两层源位图
            try {
                val detach = popupCls.getDeclaredMethod(METHOD_POPUP_ON_DETACHED)
                api.hook(detach)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val result = chain.proceed()
                        try {
                            val id = System.identityHashCode(chain.getThisObject())
                            popupBackdropMap.remove(id)?.let {
                                if (!it.bitmap.isRecycled) it.bitmap.recycle()
                            }
                        } catch (t: Throwable) {
                            Lg.w(TAG, "launcher popup-glass: backdrop cleanup failed", t)
                        }
                        result
                    }
            } catch (t: Throwable) {
                Lg.w(TAG, "launcher popup-glass: onDetachedFromWindow hook failed, backdrop cache may linger", t)
            }
            Lg.i(TAG, "LauncherHook mounted: $CLASS_POPUP_BLUR_VIEW#$METHOD_POPUP_DISPATCH_DRAW(Canvas) (popup menu backdrop glass)")
        } catch (t: Throwable) {
            Lg.e(TAG, "LauncherHook mount FAILED: $CLASS_POPUP_BLUR_VIEW#$METHOD_POPUP_DISPATCH_DRAW, popup menu glass disabled", t)
        }
    }

    /** 长按菜单玻璃总门控（TTL [POPUP_MENU_GLASS_TTL_MS] 重读；热路径 volatile 读）。 */
    private fun popupMenuGlassEnabled(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - cachedPopupMenuGlassAtMs > POPUP_MENU_GLASS_TTL_MS) {
            cachedPopupMenuGlassAtMs = now
            cachedPopupMenuGlass = try {
                api?.let {
                    Prefs.read(it).getBoolean(Prefs.KEY_POPUP_MENU_GLASS_ENABLE, Prefs.DEFAULT_POPUP_MENU_GLASS_ENABLE)
                } ?: false
            } catch (t: Throwable) {
                Lg.w(TAG, "prefs read popup_menu_glass_enable failed, default false", t)
                false
            }
        }
        return cachedPopupMenuGlass
    }

    /** dispatchDraw 拦截前置门控（draw 内部还有各层细门控，任一失败回退原绘制）。 */
    private fun popupMenuGlassActive(): Boolean =
        popupMenuGlassEnabled() && !isPowerSaveGlassOff() && Prefs.glassEnabled(api)

    /** 取/建两层系统位图的合成背景（任一层 drawable 身份变化 → 重建并回收旧合成）。 */
    private fun resolvePopupBackdrop(popup: View, wm: Drawable, dl: Drawable): PopupBackdrop? {
        val wmKey = System.identityHashCode(wm)
        val dlKey = System.identityHashCode(dl)
        val id = System.identityHashCode(popup)
        popupBackdropMap[id]?.let { hit ->
            if (hit.wallpaperKey == wmKey && hit.dragLayerKey == dlKey && !hit.bitmap.isRecycled) return hit
            if (!hit.bitmap.isRecycled) hit.bitmap.recycle()
            popupBackdropMap.remove(id)
        }
        if (popupBackdropMap.size > 4) {
            for ((k, v) in popupBackdropMap) {
                if (!v.bitmap.isRecycled) v.bitmap.recycle()
                popupBackdropMap.remove(k)
            }
        }
        return try {
            val wmBmp = (wm as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return null
            val dlBmp = (dl as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return null
            if (wmBmp.isRecycled || dlBmp.isRecycled) return null
            if (wmBmp.config == Bitmap.Config.HARDWARE || dlBmp.config == Bitmap.Config.HARDWARE) {
                logOncePerSecond("popup-glass-hw-bitmap") {
                    Lg.w(TAG, "launcher popup-glass: hardware bitmap source, glass skipped")
                }
                return null
            }
            val bw = wm.bounds.width().takeIf { it > 0 } ?: wmBmp.width
            val bh = wm.bounds.height().takeIf { it > 0 } ?: wmBmp.height
            val composite = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            val c = Canvas(composite)
            wm.draw(c)
            dl.draw(c)
            PopupBackdrop(wmKey, dlKey, composite).also { popupBackdropMap[id] = it }
        } catch (t: Throwable) {
            Lg.w(TAG, "launcher popup-glass: composite backdrop failed", t)
            null
        }
    }

    /** 在底衬 dispatchDraw 的 canvas 上画全屏液态玻璃（坐标系 = View 局部，全屏无圆角）。
     *  返回 true = 已画玻璃（拦截侧再置空两层字段 proceed 只画子 View）；false = 回退原绘制。 */
    private fun drawPopupGlassOnCanvas(canvas: Canvas, popup: View): Boolean {
        if (isPowerSaveGlassOff()) return false
        if (!popupMenuGlassEnabled()) return false
        if (!Prefs.glassEnabled(api)) return false
        val w = popup.width
        val h = popup.height
        if (w <= 0 || h <= 0) return false
        if (!popup.isAttachedToWindow) return false
        val wm = try {
            mPopupWmField?.get(popup) as? Drawable
        } catch (t: Throwable) {
            null
        }
        val dl = try {
            mPopupDlField?.get(popup) as? Drawable
        } catch (t: Throwable) {
            null
        }
        if (wm == null || dl == null) {
            logOncePerSecond("popup-glass-src-pending") {
                Lg.i(TAG, "launcher popup-glass: source layers not ready yet")
            }
            return false
        }
        val backdrop = resolvePopupBackdrop(popup, wm, dl) ?: return false
        val id = System.identityHashCode(popup)
        GlassRegistry.bind(id, popup, CLASS_POPUP_BLUR_VIEW)
        registerHostView(popup)
        // 全屏底衬：无元素圆角（SDF 角效应只在屏幕边界，避免短边比例折射失控）
        val params = buildParams(w.toFloat(), h.toFloat(), 0f, elementId = id)
        val shader = shaderCache.getOrPut(id) { ShaderInstancePool.pollOrCreate() }
        shaderOwnerSet.add(shader)
        if (shaderCache.size > 128) { shaderCache.clear(); shaderOwnerSet.clear() }
        val sourceShader = LiquidGlassShader.createSourceBitmapShader(backdrop.bitmap)
        val srcRect = RectF(0f, 0f, backdrop.bitmap.width.toFloat(), backdrop.bitmap.height.toFloat())
        val wantDiv = LowResTier.tierOf(0.807f * params.blurRadius)
        val tier = LowResTier.tierShaderOf(backdrop.bitmap, srcRect, wantDiv)
        LiquidGlassShader.setUniforms(
            shader, params, sourceShader, tier?.shader ?: sourceShader,
            sourceRect = srcRect,
            lowResSourceRect = tier?.lowResSrcRect ?: srcRect,
            sourceSizeX = backdrop.bitmap.width.toFloat(),
            sourceSizeY = backdrop.bitmap.height.toFloat(),
        )
        return try {
            canvas.save()
            try {
                popupGlassPaint.shader = shader
                canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), popupGlassPaint)
            } finally {
                popupGlassPaint.shader = null
                canvas.restore()
            }
            logRenderMode(id, "GLASS")
            true
        } catch (t: Throwable) {
            Lg.w(TAG, "launcher popup-glass: draw failed", t)
            logRenderMode(id, "BLUR", "draw failed")
            false
        }
    }

    // ------------------------------------------------------------ 核心：替换 shader（强兜底）

    /** [spec/117 A2] blurShaderPaint 双路径读取：16.x 方法 getBlurShaderPaint() → 17.3.12 字段 M。 */
    private fun getBlurShaderPaintOf(drawable: Any): Paint? {
        mGetBlurShaderPaint?.let { m ->
            return try {
                m.invoke(drawable) as? Paint
            } catch (t: Throwable) {
                Lg.e(TAG, "launcher render: getBlurShaderPaint failed", t); null
            }
        }
        mBlurShaderPaintField?.let { f ->
            return try {
                f.get(drawable) as? Paint
            } catch (t: Throwable) {
                Lg.e(TAG, "launcher render: read blurShaderPaint field failed", t); null
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
        GlassRegistry.bind(id, drawable)
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
                Lg.w(TAG, "power-save: restore launcher shader failed", t)
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
                Lg.w(TAG, "glass switch off: restore launcher shader failed", t)
            }
            logRenderMode(id, "BLUR", "glass switch off")
            return false
        }
        val paint = try {
            getBlurShaderPaintOf(drawable)
        } catch (t: Throwable) {
            Lg.e(TAG, "launcher render: getBlurShaderPaint failed", t); null
        }
        if (paint == null) {
            logRenderMode(id, "BLUR", "no blurShaderPaint")
            return false
        }
        val bounds = try {
            mGetBounds?.invoke(drawable) as? Rect
        } catch (t: Throwable) {
            Lg.e(TAG, "launcher render: getBounds failed", t); null
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
            Lg.w(TAG, "launcher render: buildLiquidShader exception (id=$id)", t)
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
        val params = buildParams(bounds.width().toFloat(), bounds.height().toFloat(), entry.cornerRadius, elementId = id)
        val sourceShader = LiquidGlassShader.createSourceBitmapShader(source)
        // σ 分档三档源（texel≈σ 根除点阵）：命中绑档位 shader + 折算 lowResSrcRect，
        // miss 回退现状源并记实际档 4
        val wantDiv = LowResTier.tierOf(0.807f * params.blurRadius)
        val tier = LowResTier.tierShaderOf(source, srcRect, wantDiv)
        LiquidGlassShader.setUniforms(
            shader, params, sourceShader, tier?.shader ?: sourceShader,
            sourceRect = srcRect, lowResSourceRect = tier?.lowResSrcRect ?: srcRect,
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
            materialHash = params.hashCode(),
            lowResDiv = if (tier != null) wantDiv else 4,
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
        val params = buildParams(bounds.width().toFloat(), bounds.height().toFloat(), entry.cornerRadius, elementId = id)
        val materialHash = params.hashCode()
        val wantDiv = LowResTier.tierOf(0.807f * params.blurRadius)
        // 档位变（含 miss 回退后下帧重试）→ 禁早退，走全量重绑
        val lowResTierChanged = applied != null && applied.lowResDiv != wantDiv
        if (applied != null && applied.sourceBitmapId == sourceBitmapId &&
            applied.viewport == viewport && applied.srcRect == srcRect &&
            applied.cornerRadius == entry.cornerRadius &&
            applied.refractionHeight == refractionHeight &&
            applied.materialHash == materialHash && !lowResTierChanged
        ) {
            return true
        }
            var boundDiv = applied?.lowResDiv ?: 4
            if (applied == null || applied.sourceBitmapId != sourceBitmapId || lowResTierChanged) {
                // 源或档位变：重建 source shader 并按 σ 档重绑 lowRes（miss → 回退现状源记实际档 4）
                val tier = LowResTier.tierShaderOf(renderSource.source, srcRect, wantDiv)
                val sourceShader = LiquidGlassShader.createSourceBitmapShader(renderSource.source)
                LiquidGlassShader.setUniforms(
                    shader, params, sourceShader, tier?.shader ?: sourceShader,
                    sourceRect = srcRect, lowResSourceRect = tier?.lowResSrcRect ?: srcRect,
                )
                boundDiv = if (tier != null) wantDiv else 4
            } else {
                // 源与档位均未变：命中 → 保留上次绑定，rect 按已绑档位折算；
                // miss → 绑定与坐标同退化到全分辨率，防 /N 绑定配全分辨率坐标出晶格；
                // 记实际档 4，下帧 want≠4 → lowResTierChanged → 重试重绑
                val divRect = LowResTier.divRectOf(renderSource.source, srcRect, boundDiv)
                if (divRect != null) {
                    LiquidGlassShader.setUniforms(
                        shader, params, null, null,
                        sourceRect = srcRect,
                        lowResSourceRect = divRect,
                    )
                } else {
                    val fullSourceShader = LiquidGlassShader.createSourceBitmapShader(renderSource.source)
                    LiquidGlassShader.setUniforms(
                        shader, params, fullSourceShader, fullSourceShader,
                        sourceRect = srcRect,
                        lowResSourceRect = srcRect,
                    )
                    boundDiv = 4
                }
            }
            appliedUniformsMap[id] = AppliedKey(sourceBitmapId, viewport, srcRect, entry.cornerRadius, refractionHeight, materialHash, boundDiv)
            if (appliedUniformsMap.size > 128) appliedUniformsMap.clear()
            return true
        } catch (t: Throwable) {
            Lg.e(TAG, "launcher render: refresh uniform failed, keep existing liquid glass shader", t)
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
            val region = zoomedRegionForWallpaper(screenRegion, snap.screenRect, snap.wallpaperZoomAtCapture)
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

    /** 渲染触发整屏快照请求：**异步**（不阻塞渲染线程；失败冷却期内不重抓）。快照缺失返回 null。
     *  静态模式 → 不入队（零抓屏），返回最近快照。 */
    private fun requestScreenSnapshotAsync(): ElementBackground? {
        val snap = screenSnapshot
        val valid = snap != null && snap.bitmap.width > 0 && snap.bitmap.height > 0
        if (valid) return snap
        if (captureMode() == Prefs.BG_CAPTURE_MODE_STATIC) return snap
        val now = SystemClock.uptimeMillis()
        if (now < (elementCaptureQuitAt[SNAPSHOT_ID] ?: 0L)) return null
        if (!captureInFlightIds.contains(SNAPSHOT_ID) && pendingCaptureIds.add(SNAPSHOT_ID)) {
            elementCaptureQueue.add(SNAPSHOT_ID)
            kickElementCaptureWorker()
            Lg.d(TAG, "bg-element: render-trigger async launcher snapshot capture (non-blocking)")
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
                Lg.e(TAG, "bg-element: spawn worker failed", t)
            }
        }
    }

    /** 两张位图逐像素比对（同 config 同尺寸才可比；异常按「不同」处理，宁多重绘不漏重绘）。 */
    private fun sameBitmapContent(a: Bitmap, b: Bitmap): Boolean {
        if (a === b) return true
        if (a.width != b.width || a.height != b.height || a.config != b.config) return false
        return try {
            a.sameAs(b)
        } catch (t: Throwable) {
            false
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
                    val prev = screenSnapshot
                    val unchanged = prev != null &&
                        prev.region == bg.region &&
                        prev.wallpaperZoomAtCapture == bg.wallpaperZoomAtCapture &&
                        sameBitmapContent(prev.bitmap, bg.bitmap)
                    LowResTier.prebuild(bg.bitmap)
                    screenSnapshot = bg
                    elementCaptureRetries[id] = 0
                    elementCaptureQuitAt.remove(id)
                    if (unchanged) {
                        logOncePerSecond("bgUnchanged") {
                            Lg.d(TAG, "bg-element: launcher background unchanged, skip invalidate")
                        }
                    } else {
                        Lg.i(TAG, "bg-element: launcher background ready ${bg.bitmap.width}x${bg.bitmap.height} region=${bg.region.toShortString()}")
                        postInvalidateRegistered()
                    }
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
                        Lg.w(TAG, "bg-element: launcher background capture failed 3 x, cooldown ${ELEMENT_CAPTURE_COOLDOWN_MS}ms")
                    }
                }
            }
        } catch (t: Throwable) {
            Lg.e(TAG, "bg-element: worker error", t)
        } finally {
            synchronized(this) {
                if (elementCaptureWorkerRunning > 0) elementCaptureWorkerRunning--
            }
        }
    }

    /** 回退抓屏：解析 Launcher 根 surface → captureDisplay 整屏排除 Launcher 窗口（spec/42 setUid 壁纸层）。
     *  sfc 解析失败返回 null（走失败重试/冷却，不静默丢弃）。静态模式 → 不抓屏（壁纸文件解码不受影响）。 */
    private fun captureScreenSnapshotWithSfc(): ElementBackground? {
        if (captureMode() == Prefs.BG_CAPTURE_MODE_STATIC) return null
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
                Lg.i(TAG, "bg-source: launcher sfc resolved from ${rootView.javaClass.simpleName}, name=${reflectSurfaceControlName(sfc)}")
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
            Lg.e(TAG, "bg-source: getViewRootImpl().getSurfaceControl() reflect failed", t)
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
            Lg.w(TAG, "bg-element: empty crop after clamp $region -> $crop")
            return null
        }
        return try {
            val wmBinder = hostForName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "window")
            if (wmBinder == null) {
                Lg.w(TAG, "bg-element: ServiceManager.getService(window) null")
                null
            } else {
                val wm = hostForName("android.view.IWindowManager\$Stub")
                    .getMethod("asInterface", IBinder::class.java).invoke(null, wmBinder)
                if (wm == null) {
                    Lg.w(TAG, "bg-element: IWindowManager.Stub.asInterface null")
                    null
                } else {
                    val captureCls = hostForName("android.window.ScreenCapture")
                    val argsCls = hostForName("android.window.ScreenCapture\$CaptureArgs")
                    val builderCls = hostForName("android.window.ScreenCapture\$CaptureArgs\$Builder")
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
                                logOncePerSecond("setUidApplied") {
                                    Lg.i(TAG, "bg-element: setUid($uid) wallpaper-only capture applied")
                                }
                            } catch (t: Throwable) {
                                Lg.w(TAG, "bg-element: setUid unavailable (${t.message}), keep full-display capture")
                            }
                        } else {
                            Lg.w(TAG, "bg-element: wallpaper uid unresolved, keep full-display capture")
                        }
                    }
                    val args = builderCls.getMethod("build").invoke(builder)
                    if (args == null) {
                        Lg.w(TAG, "bg-element: CaptureArgs.build() null")
                        null
                    } else {
                        val listener = captureCls.getMethod("createSyncCaptureListener").invoke(null)
                        if (listener == null) {
                            Lg.w(TAG, "bg-element: createSyncCaptureListener() null")
                            null
                        } else {
                            val listenerCls = hostForName("android.window.ScreenCapture\$ScreenCaptureListener")
                            val m = wm.javaClass.getMethod(
                                "captureDisplay",
                                Int::class.javaPrimitiveType, argsCls, listenerCls
                            )
                            m.invoke(wm, 0, args, listener)
                            val shb = listener.javaClass.getMethod("getBuffer").invoke(listener)
                            if (shb == null) {
                                Lg.w(TAG, "bg-element: captureDisplay getBuffer null")
                                null
                            } else {
                                val hb = shb.javaClass.getMethod("getHardwareBuffer").invoke(shb) as? HardwareBuffer
                                if (hb == null) {
                                    Lg.w(TAG, "bg-element: ScreenshotHardwareBuffer.getHardwareBuffer() null")
                                    null
                                } else if (hb.isClosed) {
                                    Lg.w(TAG, "bg-element: captureDisplay returned closed buffer")
                                    null
                                } else {
                                    try {
                                        val wrapped = Bitmap.wrapHardwareBuffer(hb, null)
                                        if (wrapped == null) {
                                            Lg.w(TAG, "bg-element: wrapHardwareBuffer null hw=${hb.width}x${hb.height}")
                                            null
                                        } else {
                                            try {
                                                val copy = wrapped.copy(Bitmap.Config.ARGB_8888, false)
                                                if (copy == null) {
                                                    Lg.w(TAG, "bg-element: copy null hw=${hb.width}x${hb.height}")
                                                    null
                                                } else {
                                                    logOncePerSecond("captureDisplayOk") {
                                                        Lg.i(TAG, "bg-element: IWindowManager.captureDisplay ok hw=${hb.width}x${hb.height} crop=$crop (${scale}x, excl launcher)")
                                                    }
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
            Lg.e(TAG, "bg-element: IWindowManager.captureDisplay failed", t)
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
            Lg.w(TAG, "prefs read bg_capture_scale failed, default 0.5", t)
            Prefs.DEFAULT_BG_CAPTURE_SCALE
        }
    }

    /** 抓屏模式：0=即时 / 1=变化 / 2=静态。桌面侧只按「静态=不抓屏」门控，其余维持现状。 */
    private fun captureMode(): Int = Prefs.captureMode(api)

    /** [doc/spec/42] 抓屏 UID 过滤开关（Prefs KEY_BG_CAPTURE_UID_FILTER，默认 true）：
     *  true=CaptureArgs.setUid(壁纸层 UID) 只抓壁纸层；false=不过滤（整屏 exclude [launcherSfc]）。 */
    private fun bgCaptureUidFilterEnabled(): Boolean {
        val a = api ?: return Prefs.DEFAULT_BG_CAPTURE_UID_FILTER
        return try {
            Prefs.read(a).getBoolean(Prefs.KEY_BG_CAPTURE_UID_FILTER, Prefs.DEFAULT_BG_CAPTURE_UID_FILTER)
        } catch (t: Throwable) {
            Lg.w(TAG, "prefs read bg_capture_uid_filter failed, default true", t)
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
            val app = hostForName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null) as? android.content.Context
                ?: return null
            val pkg = try {
                android.app.WallpaperManager.getInstance(app).wallpaperInfo
                    ?.serviceInfo?.packageName
            } catch (t: Throwable) {
                null
            } ?: "com.android.systemui"
            val uid = app.packageManager.getPackageUid(pkg, 0).toLong()
            logOncePerSecond("wallpaperUidResolved") {
                Lg.i(TAG, "bg-element: wallpaper uid resolved pkg=$pkg uid=$uid")
            }
            uid
        } catch (t: Throwable) {
            Lg.w(TAG, "bg-element: resolve wallpaper uid failed", t)
            null
        }
    }

    // ------------------------------------------------------------ [spec/52] 壁纸文件解码背景源

    /**
     * [doc/spec/52] worker 线程执行：刷新壁纸文件背景源。
     * - 首次 / 壁纸变化（wallpaperDecodePending 或源缺失）→ 解码整幅壁纸文件 → 缓存
     *   [wallpaperSource] → 构建整条可滚动条带返回。
     * - 已解码 → 重建条带（罕见兜底路径；offset 跟手不再走这里，见 applyWallpaperParallaxShift）。
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
            Lg.i(
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
                logOncePerSecond("liveWallpaperFallback") {
                    Lg.w(TAG, "bg-wallpaper: live wallpaper, fallback to captureDisplay")
                }
                return null
            }
            val id = try {
                wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM)
            } catch (t: Throwable) {
                -1
            }
            if (id <= 0) {
                Lg.w(TAG, "bg-wallpaper: getWallpaperId(FLAG_SYSTEM)=$id, fallback to captureDisplay")
                return null
            }
            val pfd = try {
                wm.getWallpaperFile(WallpaperManager.FLAG_SYSTEM)
            } catch (t: Throwable) {
                Lg.w(TAG, "bg-wallpaper: getWallpaperFile failed", t)
                null
            } ?: return null
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                pfd.dup().use { d -> BitmapFactory.decodeFileDescriptor(d.fileDescriptor, null, bounds) }
                val wpW = bounds.outWidth
                val wpH = bounds.outHeight
                if (wpW <= 0 || wpH <= 0) {
                    Lg.w(TAG, "bg-wallpaper: decode bounds invalid ${wpW}x$wpH")
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
                    Lg.w(TAG, "bg-wallpaper: decode file failed")
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
            Lg.e(TAG, "bg-wallpaper: decode failed", t)
            null
        }
    }

    /**
     * [doc/spec/52] 从整幅壁纸构建**整条可滚动条带**位图（贴屏缩放 × 背景降采样比例），只在壁纸
     * id 变/首次跑一次；offset 变化由 [applyWallpaperParallaxShift] 平移 region，不再重裁位图。
     *
     * 折算（Android 壁纸贴屏语义）：壁纸缩放 scale = max(scrW/wpW, scrH/wpH) 贴满屏幕 → 条带 =
     * 整幅壁纸 × scale（屏幕像素坐标，宽 stripScreenW = wpW×scale），region 按 offset 平移：
     * region = (-cropX, -cropY, stripScreenW-cropX, stripScreenH-cropY)，cropX = xOffset × maxScroll。
     * 产出 ElementBackground(bitmap=条带, region=平移后区域, screenRect=全屏)——条带宽 = region 宽
     * ⇒ resolveRenderSource 的 scaleX/Y ≈ bgScale，srcRect = (元素屏位 + crop) × bgScale。
     *
     * 静态/非滚动壁纸（wpW×scale ≤ scrW）maxScroll=0 → region 恒为 (0,0,scrW,scrH)，与首屏裁剪等价。
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
            val bgScale = bgCaptureScale()
            // 条带 = 整幅壁纸按贴屏缩放 × 降采样比例（一次构建，offset 不再动像素）
            val stripScreenW = wpW * scale
            val stripScreenH = wpH * scale
            val tw = (stripScreenW * bgScale).toInt().coerceAtLeast(1)
            val th = (stripScreenH * bgScale).toInt().coerceAtLeast(1)
            val stripBmp = if (src.bitmap.width == tw && src.bitmap.height == th) {
                src.bitmap
            } else {
                val scaled = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(scaled)
                val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
                canvas.drawBitmap(src.bitmap, null, RectF(0f, 0f, tw.toFloat(), th.toFloat()), paint)
                scaled
            }
            val maxScrollX = (stripScreenW - scrW).coerceAtLeast(0f)
            val maxScrollY = (stripScreenH - scrH).coerceAtLeast(0f)
            val base = RectF(0f, 0f, stripScreenW, stripScreenH)
            val cropX = wallpaperOffsetX.coerceIn(0f, 1f) * maxScrollX
            val cropY = wallpaperOffsetY.coerceIn(0f, 1f) * maxScrollY
            val region = RectF(base.left - cropX, base.top - cropY, base.right - cropX, base.bottom - cropY)
            // 壁纸文件源：文件内容与壁纸服务的缩放无关 → 基准恒为 1（折算系数 = 当前缩放）
            ElementBackground(
                stripBmp, region, 1f, RectF(0f, 0f, scrW.toFloat(), scrH.toFloat()),
                isWallpaperStrip = true, stripBaseRect = base, stripMaxScroll = PointF(maxScrollX, maxScrollY),
            )
        } catch (t: Throwable) {
            Lg.e(TAG, "bg-wallpaper: build visible crop failed", t)
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
            Lg.w(TAG, "bg-wallpaper: getWallpaperOffsets read failed", t)
        }
    }

    /** [spec/52] WallpaperManager 实例（ActivityThread.currentApplication 上下文，Launcher 进程内）。 */
    private fun wallpaperManager(): WallpaperManager? {
        return try {
            val app = hostForName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null) as? android.content.Context ?: return null
            WallpaperManager.getInstance(app)
        } catch (t: Throwable) {
            Lg.w(TAG, "bg-wallpaper: WallpaperManager.getInstance failed", t)
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
            Lg.w(TAG, "prefs read bg_wallpaper_file_source failed, default true", t)
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
            Lg.w(TAG, "prefs read bg_wallpaper_max_dim failed, default 2048", t)
            Prefs.DEFAULT_BG_WALLPAPER_MAX_DIM
        }
    }

    // ------------------------------------------------------------ 材质参数

    /** 材质参数（全部来自框架侧 Prefs）的进程内快照，渲染热路径按 TTL 复用。 */
    private data class CachedMaterialParams(
        val blurRadius: Float,
        val refractionAmount: Float,
        val depth: Float,
        val dispersion: Float,
        val highlight: Float,
        val highlightWidth: Float,
        val highlightFalloff: Float,
        val highlightFloor: Float,
        val highlightInnerAtt: Float,
        val innerGlow: Float,
        val vibrancy: Float,
        val lightAngleDegrees: Float,
        val parallaxX: Float,
        val parallaxY: Float,
    )

    @Volatile
    private var materialParamsCache: CachedMaterialParams? = null

    @Volatile
    private var materialParamsCacheTimeMs = 0L

    /** 材质参数重读间隔（毫秒）：热路径每帧调用改为每周期才读一次远程 Prefs。 */
    private const val MATERIAL_PREFS_CACHE_MS = 1000L

    /** 读材质参数（时间节流缓存）；Prefs 不可用 / 读取失败 → 配置默认值。 */
    private fun readMaterialParams(): CachedMaterialParams {
        val now = SystemClock.uptimeMillis()
        materialParamsCache?.let { cached ->
            if (now - materialParamsCacheTimeMs < MATERIAL_PREFS_CACHE_MS) return cached
        }
        materialParamsCacheTimeMs = now
        val def = LiquidGlassShader.Params(0f, 0f)
        val fallback = CachedMaterialParams(
            Prefs.DEFAULT_BLUR_RADIUS,
            def.refractionAmount, def.depth, def.dispersion,
            def.highlight, def.highlightWidth, Prefs.DEFAULT_HIGHLIGHT_FALLOFF, Prefs.DEFAULT_HIGHLIGHT_FLOOR, Prefs.DEFAULT_HIGHLIGHT_INNER_ATT,
            Prefs.DEFAULT_INNER_GLOW, Prefs.DEFAULT_VIBRANCY,
            def.lightAngleDegrees, def.parallaxX, def.parallaxY,
        )
        val prefs = try {
            api?.let { Prefs.read(it) }
        } catch (t: Throwable) {
            Lg.e(TAG, "launcher material: prefs read failed", t); null
        }
        val result = if (prefs == null) {
            fallback
        } else {
            try {
                CachedMaterialParams(
                    prefs.getFloat(Prefs.KEY_BLUR_RADIUS, Prefs.DEFAULT_BLUR_RADIUS),
                    prefs.getFloat(Prefs.KEY_REFRACTION_AMOUNT, def.refractionAmount),
                    prefs.getFloat(Prefs.KEY_DEPTH, def.depth),
                    prefs.getFloat(Prefs.KEY_DISPERSION, def.dispersion),
                    prefs.getFloat(Prefs.KEY_HIGHLIGHT, def.highlight),
                    prefs.getFloat(Prefs.KEY_HIGHLIGHT_WIDTH, def.highlightWidth),
                    prefs.getFloat(Prefs.KEY_HIGHLIGHT_FALLOFF, Prefs.DEFAULT_HIGHLIGHT_FALLOFF),
                    prefs.getFloat(Prefs.KEY_HIGHLIGHT_FLOOR, Prefs.DEFAULT_HIGHLIGHT_FLOOR),
                    prefs.getFloat(Prefs.KEY_HIGHLIGHT_INNER_ATT, Prefs.DEFAULT_HIGHLIGHT_INNER_ATT),
                    prefs.getFloat(Prefs.KEY_INNER_GLOW, Prefs.DEFAULT_INNER_GLOW).coerceIn(0f, 1f),
                    prefs.getFloat(Prefs.KEY_VIBRANCY, Prefs.DEFAULT_VIBRANCY).coerceIn(1.0f, 3.0f),
                    prefs.getFloat(Prefs.KEY_LIGHT_ANGLE, def.lightAngleDegrees),
                    prefs.getFloat(Prefs.KEY_PARALLAX_X, def.parallaxX),
                    prefs.getFloat(Prefs.KEY_PARALLAX_Y, def.parallaxY),
                )
            } catch (t: Throwable) {
                Lg.e(TAG, "launcher material: prefs float read failed, use defaults", t)
                fallback
            }
        }
        materialParamsCache = result
        return result
    }

    private fun buildParams(width: Float, height: Float, cornerRadius: Float, elementId: Int = 0): LiquidGlassShader.Params {
        // def 兼作 prefs 不可用时的兜底：blurRadius 须带上配置默认值（否则退回 Params 默认 0f = 清晰透镜）
        val def = LiquidGlassShader.Params(
            viewportWidth = width, viewportHeight = height,
            blurRadius = Prefs.DEFAULT_BLUR_RADIUS,
        )
        return try {
            val m = readMaterialParams()
            // 预设逐键覆盖：该元素类被某个预设接管的键取预设值，其余键仍取全局值
            val ov = GlassRegistry.overridesFor(elementId)
            fun pick(key: String, base: Float): Float = ov?.get(key) ?: base
            // 按类圆角覆盖：命中 → 覆盖实测值（短边一半封顶），未命中原样透传
            val corner = GlassRegistry.cornerOverrideFor(elementId)?.coerceAtMost(minOf(width, height) / 2f)
                ?: cornerRadius
            LiquidGlassShader.Params(
                viewportWidth = width,
                viewportHeight = height,
                cornerRadius = corner,
                // 文件夹玻璃本体角：RBox 圆弧（非 CONIC；Launcher 无 CornerParams CONIC 语义）
                cornerIsConic = false,
                cornerWeight = DEFAULT_CORNER_WEIGHT,
                // [2026-09-16 恢复配置化] 原硬编码 0（清晰透镜，同 SystemUI 的「强制透明诊断档」），
                // 改回读 KEY_BLUR_RADIUS —— 用户反馈「玻璃太透、背景看得一清二楚」。
                blurRadius = pick(Prefs.KEY_BLUR_RADIUS, m.blurRadius),
                // [doc/spec/95 + spec/96 2026-10-10] 折射环带高度走统一出口 Prefs.refractionHeightPx：
                // 相对模式 = 元素短边 × KEY_REFRACTION_HEIGHT_RATIO（默认 0.5 = 改动前的 min(w,h)/2f）；
                // 绝对模式 = KEY_REFRACTION_HEIGHT_PX（固定像素，默认 64）。三参数各自 500ms TTL 缓存
                // （热路径零 IPC）；变化经 AppliedKey.refractionHeight 早退比对触发全量 setUniforms。
                refractionHeight = ov?.get(Prefs.KEY_REFRACTION_HEIGHT_PX)?.coerceIn(0f, 4096f)
                    ?: ov?.get(Prefs.KEY_REFRACTION_HEIGHT_RATIO)?.let { (minOf(width, height) * it).coerceIn(0f, 4096f) }
                    ?: Prefs.refractionHeightPx(minOf(width, height), api),
                refractionAmount = pick(Prefs.KEY_REFRACTION_AMOUNT, m.refractionAmount),
                depth = pick(Prefs.KEY_DEPTH, m.depth),
                dispersion = pick(Prefs.KEY_DISPERSION, m.dispersion),
                highlight = pick(Prefs.KEY_HIGHLIGHT, m.highlight),
                highlightWidth = pick(Prefs.KEY_HIGHLIGHT_WIDTH, m.highlightWidth),
                highlightFalloff = pick(Prefs.KEY_HIGHLIGHT_FALLOFF, m.highlightFalloff),
                highlightFloor = pick(Prefs.KEY_HIGHLIGHT_FLOOR, m.highlightFloor),
                highlightInnerAtt = pick(Prefs.KEY_HIGHLIGHT_INNER_ATT, m.highlightInnerAtt),
                innerGlow = pick(Prefs.KEY_INNER_GLOW, m.innerGlow).coerceIn(0f, 1f),
                vibrancy = pick(Prefs.KEY_VIBRANCY, m.vibrancy).coerceIn(1.0f, 3.0f),
                lightAngleDegrees = pick(Prefs.KEY_LIGHT_ANGLE, m.lightAngleDegrees),
                parallaxX = pick(Prefs.KEY_PARALLAX_X, m.parallaxX),
                parallaxY = pick(Prefs.KEY_PARALLAX_Y, m.parallaxY),
            )
        } catch (t: Throwable) {
            Lg.e(TAG, "launcher buildParams: prefs float read failed, use defaults", t)
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
            Lg.e(TAG, "launcher taskG+: trackHostViewMotion error", t)
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
            Lg.e(TAG, "launcher taskG+: Choreographer.getInstance failed (not main thread?)", t)
            return
        }
        frameCallbackPosted = true
        try {
            c.postFrameCallback(motionFrameCallback)
        } catch (t: Throwable) {
            frameCallbackPosted = false
            Lg.e(TAG, "launcher taskG+: postFrameCallback failed", t)
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
                        Lg.e(TAG, "launcher taskG+: invalidate host failed", t)
                    }
                }
            } catch (t: Throwable) {
                Lg.e(TAG, "launcher taskG+: track single host failed", t)
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
                Lg.e(TAG, "launcher taskG+: invalidate host view failed", t)
            }
        }
    }

    /** [worker → 主线程] 新快照就绪 → 主线程 invalidate 宿主重录（渲染触发异步请求后本帧可能回退了系统模糊）。 */
    private fun postInvalidateRegistered() {
        try {
            mainHandler().post { invalidateRegisteredHostViews() }
        } catch (t: Throwable) {
            Lg.e(TAG, "bg-element: postInvalidateRegistered failed", t)
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
            Lg.w(TAG, "power-save: broadcast receiver skipped, context null")
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
                        Lg.i(TAG, "power-save: changed on=$on, hosts invalidated")
                    } catch (t: Throwable) {
                        Lg.e(TAG, "power-save: broadcast onReceive error", t)
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.applicationContext.registerReceiver(receiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
            } else {
                ctx.applicationContext.registerReceiver(receiver, filter)
            }
            Lg.i(TAG, "power-save: broadcast receiver registered (on=${isPowerSaveGlassOff()})")
        } catch (t: Throwable) {
            Lg.e(TAG, "power-save: register broadcast receiver failed", t)
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
        Lg.i(TAG, "launcher render: $id $mode" + (reason?.let { " ($it)" } ?: ""))
    }
}
