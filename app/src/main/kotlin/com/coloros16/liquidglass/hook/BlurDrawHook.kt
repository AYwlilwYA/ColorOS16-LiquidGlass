package com.coloros16.liquidglass.hook

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import android.view.SurfaceControl
import android.view.View
import android.view.ViewParent
import android.widget.TextView
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.liquidglass.LiquidGlassShader
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.HashSet
import java.util.LinkedHashSet
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlin.jvm.functions.Function0
import kotlin.math.abs

/**
 * Hook B：替换绘制 —— 把系统"纯模糊 + MixColor 混合"替换为液态玻璃 RuntimeShader（M2a 落地）。
 *
 * 16.1 位点（SystemUI\sources 实证）：
 * - `BaseDrawable.drawBlurShader(Canvas)`（line 441）：所有 BlurDrawable 子类（BlendDrawable/
 *   ContinuousBlurDrawable/BlurDrawable/MetaBallBlurDrawable）主绘制入口，
 *   `canvas.drawPath(drawablePath, drawableShaderPaint)` —— hookBefore 替换
 *   `getDrawableShaderPaint().setShader(液态玻璃 RuntimeShader)` 即可让面板/控件走液态玻璃。
 * - `BlendDrawable.onDrawContent(Canvas)`（line 52）：面板背景与 tile 背景共用主绘制，
 *   `getDrawableShaderPaint().setShader(getEnableShader() ? getDrawableShader().getShaderOrNull() : null)`
 *   → shader 非 null 走 drawBlurShader；**shader 为 null 时走 drawBitmapContent（位图平铺）**，
 *   此分支不触发 drawBlurShader hook，需在此接管兜底。
 *
 * 替换策略（强兜底，绝不崩 systemui）：
 * - drawBlurShader hookBefore：尝试把 drawableShaderPaint.shader 替换为液态玻璃 RuntimeShader；
 *   任一环节失败（编译 shader / 拿原图 / setUniforms / 反射 / bounds 非法）→ **不动 paint**
 *   （保持系统原样）；最终渲染模式打一行 `render: <id> GLASS|BLUR`（模式切换才打，不刷屏）。
 * - onDrawContent hookBefore：仅当系统 shader 路径不可用（将走 drawBitmapContent）时接管：
 *   替换 shader + 手动 drawBlurShader(canvas) 画液态玻璃，成功则跳过系统 proceed；失败 proceed 回退。
 * - 全程 [ExceptionMode.PROTECTIVE] + 全 try-catch，任何异常不外泄。
 *
 * 渲染资源：
 * - 每 drawable（按 identityHashCode）一个液态玻璃 RuntimeShader 实例，避免多 drawable
 *   共用实例导致 RenderNode 录制时 uniform 互相覆盖。
 * - uSource 输入：整屏快照背景源（doc/spec/07 + 2026-08-13 组合方案）——SystemUI 端 `captureDisplay`
 *   抓整屏（排除 shade 层）0.5 降采样干净背景图，存 screenSnapshot（共享所有玻璃元素）；渲染侧 srcRect
 *   = 元素当前 region 折算到整屏快照坐标，移动实时跟手、不重抓背景。
 *   [2026-08-13 用户决定] BlurService 端已全移除（不再注入未模糊原图），onBlurReady 收到的是
 *   系统模糊结果 buffer，不再作为玻璃源；快照缺失 → 回退系统模糊（PROTECTIVE）。
 * - R1（2026-08-12）：不再整体短路——paint.shader 已是缓存的液态玻璃实例时仍每帧刷新 uniform
 *   （uSource 跟随整屏快照最新实例（identityHashCode 变化 → 换新 Bitmap 后重新 setInputShader；
 *   uViewport/uSourceRect 跟随 drawable 当前尺寸/位置，任一变化才重设）。避免「首次替换后 uniform
 *   冻结 → 玻璃内容静止 / 卡片串图 / 磨砂时有时无」。
 */
object BlurDrawHook {

    private const val TAG = "LiquidGlass"

    private const val CLASS_BASE_DRAWABLE = "com.oplus.posteffect.drawable.BaseDrawable"
    private const val CLASS_BLEND_DRAWABLE = "com.oplus.posteffect.drawable.BlendDrawable"
    private const val CLASS_CONTINUOUS_BLUR_DRAWABLE = "com.oplus.posteffect.drawable.ContinuousBlurDrawable"
    private const val CLASS_DRAWABLE_SHADER = "com.oplus.posteffect.agsl.DrawableShader"
    private const val CLASS_BLUR_DRAWABLE_MANAGER = "com.oplus.posteffect.manager.BlurDrawableManager"
    private const val METHOD_DRAW_BLUR_SHADER = "drawBlurShader"
    private const val METHOD_ON_DRAW_CONTENT = "onDrawContent"
    private const val METHOD_ON_BLUR_READY = "onBlurReady"
    private const val METHOD_GET_DRAWABLE_SHADER_PAINT = "getDrawableShaderPaint"
    private const val METHOD_GET_DRAWABLE_SHADER = "getDrawableShader"
    private const val METHOD_GET_ENABLE_SHADER = "getEnableShader"
    private const val METHOD_GET_SHADER_OR_NULL = "getShaderOrNull"
    private const val METHOD_GET_BOUNDS = "getBounds"
    private const val METHOD_GET_PAINT = "getPaint"
    // ---- [M1 方案 A] 统一注册入口：ContinuousBlurDrawable.addBlurDrawable() 场景判定 ----
    private const val METHOD_ADD_BLUR_DRAWABLE = "addBlurDrawable"
    private const val METHOD_GET_DRAWABLE_ID = "getDrawableId"
    private const val METHOD_GET_DRAWING_PARAM = "getDrawingParam"
    private const val METHOD_GET_SURFACE_CONTROL = "getSurfaceControl"

    // ---- 坐标映射 hook（AutoBlurDrawable.draw）：drawable(identityHashCode) → 屏幕区域 ----
    private const val CLASS_AUTO_BLUR_DRAWABLE = "com.oplusos.systemui.common.blurability.drawable.AutoBlurDrawable"
    // [任务 I] 描边 tile 专属：LightStyleStrokeDrawable（extends AutoBlurDrawable）在
    // strokeShaderProxy != null 时 draw() 不回调 super.draw → AutoBlurDrawable.draw hook 不触发
    // → 无映射回退系统模糊（真机"部分 tile 磨砂"根因）。需单独 hook 其 draw 建立映射。
    private const val CLASS_LIGHT_STYLE_STROKE_DRAWABLE = "com.oplus.systemui.qs.base.widget.LightStyleStrokeDrawable"
    private const val CLASS_VIEW_BLUR_PROXY = "com.oplusos.systemui.common.blurability.ViewBlurProxy"
    private const val CLASS_PLATFORM_BLUR_DRAWABLE = "com.oplusos.systemui.common.blurability.platformblur.PlatformBlurDrawable"
    // [spec/61 2026-08-23] 系统控制中心模糊力度调整 + 阻止缩小（CLASS_BLUR_CONFIG 已有，见任务 D 常量区）
    private const val CLASS_NOTIFI_QS_PLATFORM_BLUR_KT = "com.oplusos.systemui.common.util.NotifiAndQsPlatformBlurExKt"
    private const val METHOD_APPLY_PANEL_MIRROR_SCALE = "applyPanelMirrorScale"
    /** [2026-09-16] PlatformStatic 分支的唯一执行体（ViewBlurProxy.java:314）——力度改写挂这里。
     *  `applyBlurConfig()`(:271)/`setBlurAmount()`(:484)/`setBlurType()`(:550)/drawable 创建回调(:161)
     *  四条路径全部汇聚到本方法，覆盖面大于只挂 applyBlurConfig()。 */
    private const val METHOD_APPLY_CONFIG_TO_PLATFORM_BLUR = "applyConfigToPlatformBlur"
    // [2026-09-16 面板材质底色] 面板背景 MixColor 材质底色（那层"灰"）的来源方法与替换目标。
    //   ScrimControllerExImp.getPanelPlatformMixConfig(:351) → panelPlatformMixConfig(context, useMotionBlur)
    //   → BlurMixSingle(MixColor(5, R.color.notification_and_qs_panel_mixed_color_top_layer,
    //                            R.color.notification_and_qs_panel_mixed_color_bottom_layer))
    //   改为返回 BlurMixConfig.None → PlatformBlurDrawable.applyBlurConfig(:72-78) 只设模糊不设材质色。
    private const val CLASS_BLUR_MIX_CONFIG = "com.oplusos.systemui.common.blurability.BlurMixConfig"
    private const val CLASS_BLUR_MIX_CONFIG_NONE = "com.oplusos.systemui.common.blurability.BlurMixConfig\$None"
    private const val METHOD_DRAW = "draw"
    private const val METHOD_GET_VIEW_BLUR_PROXY = "getViewBlurProxy"
    private const val METHOD_GET_VIEW = "getView"
    private const val METHOD_GET_BLUR_DRAWABLE = "getBlurDrawable"
    private const val FIELD_DEFAULT_DRAWABLE = "defaultDrawable"
    // ---- 任务 2/3：侧滑按钮（MaskBlurDrawable）与普通 tile（MixColorTileDrawable）映射补充 ----
    // 任务 2（16.1 逆向）：通知侧滑操作按钮背景 = MaskBlurDrawable（NotificationMenuRowExtImpl.getMenuItemBackground
    //   line 284），其 draw() 直接 viewBlurProxy.getBlurDrawable(null).draw(canvas)，不经 AutoBlurDrawable.draw
    //   → 侧滑按钮 MetaBallBlurDrawable 恒 map miss。单独 hook 其 draw 注册。
    // 任务 3（16.1 逆向）：1x1 tile mBg 背景 = MixColorTileDrawable（TileDrawableWrapper）包装 AutoBlurDrawable
    //   （new MixColorTileDrawable(new AutoBlurDrawable(viewBlurProxy, null), ...) line 383），其 draw() 稳态
    //   条件跳过 super.draw → 内层 AutoBlurDrawable.draw 不触发 → 192x192 圆开关 BlurDrawable 恒 map miss。
    private const val CLASS_MASK_BLUR_DRAWABLE = "com.oplusos.systemui.common.blurability.drawable.MaskBlurDrawable"
    private const val CLASS_MIX_COLOR_TILE_DRAWABLE = "com.oplus.systemui.qs.base.res.drawable.MixColorTileDrawable"
    private const val CLASS_NOTIF_HEADER_MASK = "com.oplus.systemui.statusbar.notification.stack.NotificationChildrenContainerExtImp\$NotificationHeaderMaskColorDrawable"
    private const val METHOD_GET_DRAWABLE = "getDrawable"

    // ---- 通知卡片专属映射 hook（C1）：NotificationBackgroundViewExtImp.draw(Canvas, Drawable) ----
    // 真机实锤（2026-08-12）：通知卡片模糊背景走 `NotificationBackgroundView.onDraw` →
    // `mExt.draw(canvas, mBackground)`（`NotificationBackgroundViewExtImp.draw`），内部
    // `viewBlurProxy.getBlurDrawable(drawable)` 后直接 setBounds+draw，**不经 AutoBlurDrawable.draw**
    // → screenRegionMap 从无卡片映射 → 等宽卡片兜底串图。此 hook 建立卡片专属映射。
    private const val CLASS_NBV_EXT = "com.android.systemui.statusbar.notification.row.NotificationBackgroundViewExt"
    private const val CLASS_NBV_EXT_IMP = "com.oplus.systemui.statusbar.notification.row.NotificationBackgroundViewExtImp"
    private const val METHOD_GET_BG_VIEW = "getBgView"
    private const val METHOD_DRAW_NOTIF_BG = "draw"
    // ---- 磨砂根因（2026-08-12）：移除玻璃之上的半透明材质色层 ----
    // 修 1（通知主背景磨砂）：ExtImp.draw 稳态分支在玻璃（drawable2.draw）之后画 materialColorDrawable
    //   （res/drawable/notification_stacking_material_color_bg.xml，16dp 圆角 shape，反编译实证）并
    //   setTint(currentMaterialColor) 覆盖整卡。proceed 前置透明 → tint 透明不覆盖玻璃。
    //   currentMaterialColor 存于 ViewBlurManager.public int currentMaterialColor（line 117；
    //   getter getCurrentMaterialColor line 857，setter setCurrentMaterialColor line 1118 会
    //   notifyMaterialColorChanged → invalidate → 重跑 draw）。
    // 修 2（控件磨砂）：PlatformBlurDrawable.draw（line 201-208）玻璃后画 blurMaskColor drawRect
    //   （仅 OverlayColor 场景非 0，applyBlurConfig line 88 setBlurMaskColor(blurColor)）。proceed 前置 0。
    private const val CLASS_VIEW_BLUR_MANAGER = "com.oplus.systemui.notification.blur.ViewBlurManager"
    private const val FIELD_VIEW_BLUR_MANAGER = "viewBlurManager"
    private const val FIELD_CURRENT_MATERIAL_COLOR = "currentMaterialColor"
    private const val FIELD_BLUR_MASK_COLOR = "blurMaskColor"
    private const val FIELD_CLICK_ALPHA = "clickAlpha"
    private const val FIELD_STACKED_MUSK_COLOR = "stackedNotificationMuskColor"
    // ---- 整屏快照背景源（doc/spec/07 + 2026-08-13 组合方案）：抓屏调度常量 ----
    // - 内容变化事件（面板开/关 mountPanelExpansion / onBlurReady 新帧 captureOriginalFrame /
    //   映射写入 registerScreenRegionCore / recordNotificationCardRegion）触发整屏快照重抓，
    //   **无节流立即刷新**（2026-08-13 用户决定，KEY_BG_REFRESH_MS 节流已废弃）；静止（无内容变化）零抓屏。
    // - 移动/滑动只更新 srcRect（渲染侧按元素当前 region 折算到整屏快照坐标），不触发背景重抓。
    // - 渲染时快照缺失 → 异步请求后台 worker 抓屏（渲染零阻塞，ANR 根因消除），失败回退系统模糊。
    /** 整屏抓屏失败冷却毫秒（3 次/320ms 用尽后暂停；下个内容变化/渲染兜底重试）。
     *  [2026-08-15 根因 B 修复] 2000 → 1000：失败冷却缩短，不长时间挡住新内容变化触发。
     *  [2026-08-15 主动式持续抓屏] 1000 → 300：**移除失败冷却硬拦截**——主动持续抓屏（下拉面板/通知
     *  横幅 worker）与失败快速重试（[scheduleCaptureRetry]，60ms）绕过冷却（force=true）；普通内容变化
     *  触发保留防 spin 冷却，但仅 300ms 短暂冷却（连续失败达 [bgCaptureRetryLimit] 才进入），背景不冻结
     *  旧帧（sfc 解析失败已是 60ms 快速重试 + 失败计数达 [bgCaptureRetryLimit] 才冷却；capture 失败保留
     *  快速重试）。 */
    private const val ELEMENT_CAPTURE_COOLDOWN_MS = 300L
    /** [2026-08-14 CPU 修复] 持续抓屏「sfc 长期无效 → 停止」阈值：shade 不在渲染（sfc 拿不到 = 面板已收起）
     *  持续超过此值 → 停止持续抓屏（兜底 panelExpansionActive 卡 true 时 120Hz 空转耗电）。 */
    private const val CONTINUOUS_CAPTURE_STOP_SFC_STALE_MS = 500L
    /** [doc/spec/49 2026-08-13] 周期抓屏空闲判定窗口毫秒：距上次内容变化信号（markContentChanged）超过
     *  此值视为空闲 → periodicCaptureRunnable 跳过抓屏（保持旧帧不更新）。内容变化驱动为主，周期仅兜底。 */
    private const val PERIODIC_CAPTURE_IDLE_SKIP_MS = 1000L
    /** 整屏快照抓屏任务 id（worker 队列唯一任务；Int.MIN_VALUE 不可能与真实 drawable identityHashCode 冲突） */
    private const val SNAPSHOT_ID = Int.MIN_VALUE
    /** 歧义 ABORTED 冷却毫秒（期间该 drawable 直接回退系统模糊，防折叠组展开等场景 GLASS/BLUR 高频切换闪磨砂） */
    private const val AMBIGUOUS_COOLDOWN_MS = 500L

    // ---- [spec/51 2026-08-13] 通知（heads-up）首帧主线程抓屏 + 连抓 5~6 帧 + 持续期间不间断抓屏 ----
    /** heads-up 连抓总帧数（用户要求 5~6 帧）：[2026-08-13 回退主线程同步后] 全部帧由专用连抓 worker
     *  后台快速抓（含首帧，第一帧立即执行；主线程零阻塞）。 */
    private const val HEADS_UP_BURST_TOTAL_FRAMES = 6
    /** heads-up 连抓帧间隔毫秒（worker 快速连抓；覆盖入场动画窗口 ~300ms）。 */
    private const val HEADS_UP_BURST_FRAME_INTERVAL_MS = 45L
    /** heads-up 连抓早期 sfc 未就绪（heads-up 窗口 attach 滞后）时重试间隔毫秒。 */
    private const val HEADS_UP_BURST_RETRY_MS = 60L
    /** [2026-08-13 用户要求「通知持续期间必须一直抓屏」] heads-up 活跃期间**不间断**抓屏间隔毫秒。
     *  通知是瞬态场景（持续数秒），持续抓屏可接受；间隔 50ms ≈ 20Hz（captureDisplay 同步 Binder 自身
     *  20-50ms 耗时叠加，实际 ~10-14Hz），覆盖通知内容/入场动画每一帧变化。worker 线程执行（主线程仅首帧
     *  同步一次），通知消失即停（零后台耗电）。
     *  [2026-08-15 主动式持续抓屏] **已废弃**——间隔改由可配速率 [continuousCaptureIntervalMs]
     *  （[KEY_PANEL_CAPTURE_HZ] 默认 120Hz，节流 60Hz）控制，与下拉面板共用同一套速率；本常量不再被
     *  代码读取（保留仅防遗留编译引用）。 */
    private const val HEADS_UP_CONTINUOUS_CAPTURE_INTERVAL_MS = 50L
    /** [2026-08-13 captureDisplay 开始延迟优化] heads-up 窗口 sfc 负缓存毫秒：resolveHeadsUpSfc 遍历
     *  registeredHostViews 全部宿主反射窗口类型（无 heads-up 时每宿主一次反射）后失败 → 此窗口内直接返回
     *  null 不重复遍历；新宿主登记（registerHostView）清零 → 通知出现时立即重新解析。 */
    private const val HEADS_UP_SFC_NEG_CACHE_MS = 300L
    /** [2026-08-13 captureDisplay 开始延迟优化] 抓屏热路径远程 Prefs（LibXposed 框架侧存储，跨进程 IPC）
     *  时间节流缓存毫秒：≥此值才重读一次，配置改动 ≤1s 生效；热路径零 IPC。 */
    private const val CAPTURE_PREFS_CACHE_MS = 1000L

    // ---- [spec/55 诊断日志 2026-08-14] hook/抓屏链路时间戳串联（仅诊断，不改逻辑）----
    /** hook 触发诊断日志 tag（lg-hook）：logcat 过滤 `-s lg-hook` 看触发时机；Log.d 级别（可关） */
    private const val DIAG_TAG_HOOK = "lg-hook"
    /** 抓屏链路诊断日志 tag（lg-cap）：logcat 过滤 `-s lg-cap` 看 入队 → captureDisplay 开始 → 完成 */
    private const val DIAG_TAG_CAP = "lg-cap"
    /** 诊断链路锚点重置毫秒：距上次 hook 日志超过此值 → 新建链路（每次下拉/通知事件独立对比，sinceAnchor 归零） */
    private const val DIAG_CHAIN_RESET_MS = 1000L
    /** [spec/58 追加 2026-08-15] 主线程首帧同步链路诊断日志 tag（lg-first）：logcat 过滤 `-s lg-first` 看
     *  下拉/通知出现瞬间主线程同步首帧全链路（触发 → sfc → captureDisplay → 缓冲区替换），定位竞态窗口。 */
    private const val DIAG_TAG_FIRST = "lg-first"
    /** [spec/59 追加 2026-08-14] 抓屏 worker 生命周期诊断日志 tag（lg-wk）：logcat 过滤 `-s lg-wk` 看
     *  worker 停摆/恢复/孤儿/慢 captureDisplay——区分竞态窗口根因（sfc 失败重试循环 / Binder 阻塞 / 孤儿泄漏）。 */
    private const val DIAG_TAG_WK = "lg-wk"

    /** 链路锚点（首条 hook 日志的 uptimeMillis）；抓屏日志显示 sinceAnchor=距 hook 毫秒（用户可直接看出哪段慢） */
    @Volatile
    private var diagChainStartMs = 0L

    /** 各诊断点上次打印时间（point → uptimeMillis；sinceLast=距上次同点间隔） */
    private val diagLastTimes = ConcurrentHashMap<String, Long>()

    // ---- 任务 D：圆角半径反射（问题 10） ----
    private const val CLASS_BLUR_CONFIG = "com.oplusos.systemui.common.blurability.BlurConfig"
    private const val METHOD_GET_BLUR_CONFIG = "getBlurConfig"
    private const val METHOD_GET_CORNER_RADIUS = "getCornerRadius"
    private const val METHOD_GET_LEFT_TOP_CORNER_RADIUS = "getLeftTopCornerRadius"
    // [spec/11] 圆角反射多级策略：四角 getter / pathProvider / gradientStrokeCornerParam
    private const val METHOD_GET_LEFT_BOTTOM_CORNER_RADIUS = "getLeftBottomCornerRadius"
    private const val METHOD_GET_RIGHT_TOP_CORNER_RADIUS = "getRightTopCornerRadius"
    private const val METHOD_GET_RIGHT_BOTTOM_CORNER_RADIUS = "getRightBottomCornerRadius"
    private const val METHOD_GET_PATH_PROVIDER = "getPathProvider"
    private const val METHOD_GET_GRADIENT_STROKE_CORNER_PARAM = "getGradientStrokeCornerParam"
    private const val CLASS_ROUND_RECT_OUTLINE_PROVIDER = "com.oplusos.systemui.common.outline.RoundRectOutlineProvider"
    private const val FIELD_CORNER_RADIUS = "cornerRadius"
    private const val CLASS_CORNER_PARAMS = "com.oplus.posteffect.CornerParams"
    private const val METHOD_GET_RADIUS = "getRadius"
    // [spec/18 圆角过渡区深挖修正] CONIC 本体判定：CornerParams.getType()/getWeight()（c16.1_PJZ110
    // CornerParams.java 反编译实证：public final CornerType getType() / public final float getWeight()），
    // CornerType 枚举 com.oplus.posteffect.CornerType（CONIC/FULL/G2，构造 CornerParams(int i) 默认
    // (i&1)!=0?FULL:CONIC → BlurConfig 默认 gradientStrokeCornerParam = CONIC）。
    private const val METHOD_GET_TYPE = "getType"
    private const val METHOD_GET_WEIGHT = "getWeight"
    // [spec/18 方角根因修复] BlurConfig.getRadiusWeight()（c16.1 BlurConfig.java:188-190，返回 Float?）。
    // 系统 setSmoothCorner 路径（QsSeekBarBlurManager/QsDetailBackgroundUtils）在 adaptGradientStrokeParams
    // 前写入 radiusWeight，clearGradientStrokeParams 只清 gradientStrokeCornerParam 不清它 —— 它是
    // "系统走 CONIC 平滑角" 的稳定信号。
    private const val METHOD_GET_RADIUS_WEIGHT = "getRadiusWeight"
    private const val CLASS_CORNER_TYPE = "com.oplus.posteffect.CornerType"
    // [spec/18 Bug 1 二级菜单] QsFlashLightBackgroundUtils.provideLevelBarBlurProxy 用
    // SmoothRoundRectPathProvider（com.oplus.posteffect.path，**非** RoundRectOutlineProvider）作
    // pathProvider → 五级反射 getCornerRadius/四角/RoundRectOutlineProvider/gradientStrokeCornerParam
    // 全 miss → 手电筒二级菜单亮度档方角。BaseRoundRectPathProvider.getMaxRadius()（反编译 line 54-56，
    // public final）读真实角半径。加为 pathProvider 级第 2 来源。
    private const val CLASS_BASE_ROUND_RECT_PATH_PROVIDER = "com.oplus.posteffect.path.BaseRoundRectPathProvider"
    private const val METHOD_GET_MAX_RADIUS = "getMaxRadius"

    /** CONIC 角权重默认值：(8×1.1)/(3×1.1+3) = 1.3968（系统 CornerParamsKt fMax=(8·w)/(3·w+3) 变换，
     *  卡/按钮 weight=1.1；与 LiquidGlassShader 掩码默认权重同公式同值）。 */
    private const val DEFAULT_CORNER_WEIGHT = (8.0f * 1.1f) / (3.0f * 1.1f + 3.0f)

    /** 圆角解析最终兜底默认半径 px（[2026-08-13 用户要求]）：反射五级全失败 / blurConfig 不可用时回退
     *  默认 50px 圆角（原 30px 用户反馈不够，改 50）。该源（"default"）不缓存——反射恢复后立即用真实值。 */
    private const val DEFAULT_CORNER_RADIUS_PX = 50f

    // ---- 任务 G（问题 13）：运动事件源 hook（QS 翻页 + 面板展开）→ invalidate 宿主 View 强制重录 draw ----
    // 16.1 反编译源码核对（SystemUI\sources）：
    // - QS 翻页容器 = PagedTileLayout（extends androidx ViewPager，QSPanel.mTileLayout 实证），
    //   mOnPageChangeListener = 内部 AnonymousClass2（jadx 注解 `renamed from:
    //   com.android.systemui.qs.PagedTileLayout$2`）→ 运行时类名 `PagedTileLayout$2`，
    //   方法 `onPageScrolled(int position, float positionOffset, int positionOffsetPixels)`
    //   （ViewPager 拖动/翻页动画每帧回调，PagedTileLayout.java line 90）。
    // - 面板下拉/展开 = QuickSettingsControllerImpl.setExpansionHeight(float)
    //   （line 1152 公开方法；触摸拖动 line 1020 `setExpansionHeight(f + mInitialHeightOnTouch)`、
    //    展开动画/overscroll 均经它驱动，NotificationPanelViewController:733 也调用）。
    //   QS 容器平移经 OplusQSContainerImplController → OplusQSContainerImpl.setTranslationY
    //   （View 字段），重录后 getLocationOnScreen 反映新位置 → 映射随之更新。
    private const val CLASS_PAGED_TILE_LAYOUT_LISTENER = "com.android.systemui.qs.PagedTileLayout\$2"
    private const val METHOD_ON_PAGE_SCROLLED = "onPageScrolled"
    private const val CLASS_QUICK_SETTINGS_CONTROLLER = "com.android.systemui.shade.QuickSettingsControllerImpl"
    private const val METHOD_SET_EXPANSION_HEIGHT = "setExpansionHeight"
    // ---- 任务 spec/14：未映射模糊控件补映射 hook（2026-08-13）----
    // 逆向依据（SystemUI\sources）：
    // - 锁屏胶囊（CapsuleBackgroundHelper.java:140-148 / CapsuleEarView.java:160-168）：
    //   `viewBlurProxy.getBlurDrawable(null)` 后直接 setBounds+draw，**不经 AutoBlurDrawable.draw**
    //   → screenRegionMap 恒无胶囊映射（MetaBallBlurDrawable 回退系统磨砂）。
    // - 媒体横幅（OplusMediaCarouselView.java:98-124）：onDraw 内同样直接 getBlurDrawable +
    //   setBounds + draw。MediaBgAodWrapper 仅改色不独立绘制（复用同 view）。
    // hook 点 = 各宿主类 draw 方法，反射 viewBlurProxy 字段 → 复用 registerScreenRegionCore。
    private const val CLASS_CAPSULE_BACKGROUND_HELPER = "com.oplus.systemui.notification.lockscreen.capsule.CapsuleBackgroundHelper"
    private const val CLASS_CAPSULE_EAR_VIEW = "com.oplus.systemui.notification.lockscreen.capsule.CapsuleEarView"
    private const val CLASS_MEDIA_CAROUSEL_VIEW = "com.oplus.systemui.media.controls.ui.OplusMediaCarouselView"
    private const val METHOD_DRAW_CAPSULE_BG = "drawCapsuleBg"
    private const val METHOD_ON_DRAW = "onDraw"
    private const val FIELD_VIEW_BLUR_PROXY = "viewBlurProxy"
    private const val FIELD_REPLACED_DRAWABLE = "replacedDrawable"
    // ---- [spec/48 遮罩走系统自带机制] 背景 scrim 纯黑 tint hook ----
    // 16.1 反编译实证（SystemUI\sources\com\android\systemui\scrim\ScrimView.java）：
    // - ScrimView extends View；scrim_behind（super_notification_shade.xml）match_parent 全屏、z-order 最低，
    //   覆盖整个 shade 含 QS 区。
    // - mScrimName（public String，:40）：CentralSurfacesImpl.java:2218 注入 behind_scrim/notifications_scrim/front_scrim。
    // - onDraw(Canvas)（public final，:117-126）：mDrawable.getAlpha()>0 时 mDrawable.draw(canvas)——canvas 全屏，
    //   面板背景唯一绘制点。
    // - setViewAlpha（public，:221-237）：mViewAlpha=f 后 `mDrawable.setAlpha((int)(f*255))` + ext.setViewAlpha(f)。
    //   ⚠️ AutoBlurDrawable.setAlpha 是空实现（AutoBlurDrawable.java:109-110）→ Drawable.getAlpha() 恒 255；
    //   系统真实 scrim 透明度由 ScrimViewExImp.setViewAlpha → setColor(blurConfig 颜色 alpha) →
    //   applyBlurConfig → view.invalidate() 驱动，ScrimView.mViewAlpha（public getViewAlpha()）即逐帧动画值。
    //   spec/48 方案 A：恢复 behind_scrim 画纯黑 tint（paint.shader=null+纯黑），alpha=behindScrimAlpha×maskAlpha
    //   （behindScrimAlpha 由 hook ScrimView.setViewAlpha 捕获系统动画 alpha，见 mountScrimViewAlpha）；
    //   过渡动画由 ScrimController 天然提供，模块不额外驱动；不恢复系统模糊。
    private const val CLASS_SCRIM_VIEW = "com.android.systemui.scrim.ScrimView"
    private const val CLASS_SCRIM_VIEW_EX = "com.android.systemui.scrim.ScrimViewEx"
    private const val CLASS_SCRIM_VIEW_EX_IMP = "com.oplus.systemui.scrim.ScrimViewExImp"
    private const val FIELD_SCRIM_NAME = "mScrimName"
    private const val FIELD_SCRIM_VIEW_ALPHA = "mViewAlpha"
    private const val METHOD_SET_VIEW_ALPHA = "setViewAlpha"
    private const val METHOD_GET_VIEW_ALPHA = "getViewAlpha"
    private const val METHOD_GET_SCRIM_VIEW = "getScrimView"

    // ---- [Bug 5] 通知卡片横向滑动不跟手（custom-card 路径）修复：hook OplusCustomRow.setTranslation(float) ----
    // 反编译实证（customcard\OplusCustomRow.java）：
    // - setAllChildViewInTranslateable（:946-965）显式把 NotificationBackgroundView（玻璃宿主 mBackgroundNormal）
    //   剔出 mTranslateableViews → custom-card 路径（mDismissUsingRowTranslationX=false）setTranslation（:1216-1235）
    //   只平移内容子 View、bgView getLocationOnScreen.x 不变 → tracker 检测不到 → region 冻结 → srcRect 不跟手。
    // - [2026-08-13 方向确认] 玻璃背景固定（系统原样：背景固定、内容滑）——setTranslation 只作「尺寸变化
    //   及时重采样」信号：invalidate bgView → 重录 → recordNotificationCardRegion 用最新位置/尺寸折算 region。
    //   mDismissUsingRowTranslationX 分支不再需要（不区分路径，统一 invalidate 即可；行平移路径
    //   getLocationOnScreen 已反映新位置，重录即跟）。
    private const val CLASS_OPLUS_CUSTOM_ROW = "com.oplus.systemui.statusbar.notification.customcard.OplusCustomRow"
    private const val METHOD_SET_TRANSLATION = "setTranslation"
    private const val FIELD_BG_NORMAL = "mBackgroundNormal"
    // ---- [spec/44b 流体云展开卡片玻璃化 2026-08-15] OplusCustomRow 宿主侧 hook（宿主卡片背景透明 + 内容更新驱动）----
    // 反编译实证（customcard/OplusCustomRow.java）：
    // - updateResource()（:738-758）：setCustomBackground(R.drawable.custom_material_bg) + ext.init() + setTint(mNormalColor)，
    //   每次（onFinishInflate:477 / onConfigurationChanged:470 / setTranslationY:720 显著位移）重设宿主卡片背景；
    //   hookAfter 重贴透明 drawable 兜底（背景透明硬需求，默认开启）。
    // - setNewSeedlingView(View)（:661-668）：插件内容 View 挂载/更新入口（getChildAt(1) remove + addView(view,1)），
    //   hookAfter 驱动 invalidate + triggerBackgroundCapture（插件内容更新后玻璃背景及时刷新）。
    private const val METHOD_UPDATE_RESOURCE = "updateResource"
    private const val METHOD_SET_NEW_SEEDLING_VIEW = "setNewSeedlingView"
    /** [spec/44b] NotificationBackgroundView.mBackground（public Drawable，宿主卡片背景真实存放字段：
     *  反编译实证 onDraw:214-227 直绘该字段（draw(canvas, this.mBackground) / mExt.draw(canvas, mBackground)）——
     *  **View.background 置空无效**，必须替换此字段才能让宿主卡片背景透明。 */
    private const val FIELD_NBV_BACKGROUND = "mBackground"
    // ---- [spec/44c 流体云展开卡片玻璃化 2026-08-15] CardBackgroundView 宿主 hook（SystemUIPlugin.apk 反编译实锤）----
    /** 展开大卡背景 View（反编译实锤，tools/dump/SystemUIPlugin/sources/.../seedling/card/ui/view/）：
     *  CardContainer(COUIRecyclerView, id card_container) → CardView(FrameLayout, id seeding_card_view) →
     *  CardBackgroundView(extends View, id seedling_card_bg)。背景 = viewRootManager.getBackgroundBlurDrawable()
     *  （BackgroundBlurDrawable，blurRadius 540，Oplus 材质 blend #ff585858 / mix #b3262626，圆角 20dp）。 */
    private const val CLASS_CARD_BACKGROUND_VIEW = "com.oplus.systemui.plugins.seedling.card.ui.view.CardBackgroundView"
    /** [2026-09-16 小岛玻璃化] 流体云小胶囊本体（插件 View，独立 classloader → 用字符串类名比较）。
     *  见 CapsuleView.java：可见胶囊宽度由 `capsuleDrawableWidth` 决定（背景/前景 Drawable 的 bounds），
     *  **不是** View 自身宽度；外层的 CapsuleContainer 只是"外框"（宽度 = 子宽 + padding，胶囊居中摆放）。 */
    private const val CLASS_CAPSULE_VIEW = "com.oplus.systemui.plugins.seedling.capsule.ui.view.CapsuleView"

    // ---- [spec/44 流体云展开卡片玻璃化 2026-08-13] Seedling 宿主容器 hook ----
    // 流体云（灵动岛）= Seedling（种子卡片）体系，UI 由 SeedlingPlugin 插件 APK 渲染，**不走
    // posteffect 模糊管线**（hook 不到 AutoBlurDrawable/drawBlurShader）。宿主容器 = 状态栏
    // seeding_card_container（`CapsulePluginContainer`，extends FrameLayout，status_bar.xml 实证
    // match_parent + clipChildren=false + translationZ=1px），插件经 `seedlingPlugin.onCreateView(0, container)`
    // 把种子卡片 View（缩小胶囊 + 展开大卡片）挂入。方案 A：hook 容器 `dispatchDraw(Canvas)`，
    // 在子 View 绘制前对**展开大卡片**（高度显著 > 状态栏高度的子 View）区域注入液态玻璃。
    // 缩小胶囊（≈状态栏高度）不处理（用户需求：只大卡片玻璃化）。
    private const val CLASS_CAPSULE_PLUGIN_CONTAINER = "com.oplus.systemui.statusbar.seeding.CapsulePluginContainer"
    private const val CLASS_VIEW_GROUP = "android.view.ViewGroup"
    private const val METHOD_DISPATCH_DRAW = "dispatchDraw"

    // ---- [方案 B] NC↔CC 切换 / 锁屏展开 重影修复：容器 View 缩放变换追踪（doc/spec/29）----
    // 反编译实证（SystemUI\sources）：
    // - NC↔CC 切换动画 = OplusPanelViewPagerController.access$setAlphaAndTranslationXForScrollX（:835-866）：
    //   对「被切走的面板」（NotificationPanelView / OplusQSRootView，qsPanelView 运行时类 =
    //   OplusQSRootView，OplusSeparateQSPluginImpl.onCreateQSPanelView:433-446 实证）执行
    //   setScaleX/Y（0.85~1.0，默认中心 pivot）+ setAlpha 淡出；对「切入面板」setTranslationX(initTranslationX+f)。
    // - 锁屏展开第二路径 = NotificationStackScrollLayoutControllerExtImpl.updateNotificationViewScale（:869-898）：
    //   NotificationStackScrollLayout setPivotX/Y + setScaleX/Y（0.9~1.0）。
    // - 根因：getLocationOnScreen 只累加 left+translation、**不乘祖先 scale** → 缩放动画期间
    //   screenRegion 停在未缩放布局坐标，而玻璃实际绘制在缩放后位置 → srcRect 采样区与绘制区错位 → 卡片重影。
    // - 修复：hook 容器 setScaleX/setScaleY/setTranslationX/setTranslationY，维护每个容器当前变换
    //   （scale + 屏幕坐标 pivot）；渲染侧 resolveRenderSource 折算 srcRect 前，把 region 用容器变换
    //   映射到「实际绘制位置」（正向：R_drawn = s·R + (1-s)·pivotScreen；R 已含 translation，
    //   pivotScreen = 容器 getLocationOnScreen + pivotX/Y，hook 主线程捕获）再折算 srcRect。
    private const val CLASS_NOTIFICATION_PANEL_VIEW = "com.android.systemui.shade.NotificationPanelView"
    private const val CLASS_OPLUS_QS_ROOT_VIEW = "com.oplus.systemui.plugins.qs.OplusQSRootView"
    private const val CLASS_NOTIFICATION_STACK_SCROLL_LAYOUT = "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout"
    private const val METHOD_SET_SCALE_X = "setScaleX"
    private const val METHOD_SET_SCALE_Y = "setScaleY"
    private const val METHOD_SET_TRANSLATION_X = "setTranslationX"
    private const val METHOD_SET_TRANSLATION_Y = "setTranslationY"

    // ---- [任务 Heads-Up 玻璃化 spec/19] ViewBlurProxy.setBlurType 强制 PlatformStatic ----
    // 根因：heads-up 期间系统无条件把背景切 BlurTypeMotion（framework BackgroundBlurDrawable，
    // draw 空实现）→ posteffect drawBlurShader hook 够不到。setBlurType 是全部类型变更的单一漏斗
    // （blurForHeadsUp:808 / NotificationChildrenContainerExtImp:214 全汇聚），一处 hook 全覆盖。
    // 判定：arg0 == BlurTypeMotion 且 this.view 是 NotificationBackgroundView（heads-up 背景，
    // shade/锁屏走 PlatformStatic/BlendWallpaper → 不误伤）→ 替换为 BlurTypePlatformStatic。
    private const val CLASS_NOTIFICATION_BACKGROUND_VIEW = "com.android.systemui.statusbar.notification.row.NotificationBackgroundView"
    private const val CLASS_VIEW_BLUR_TYPE = "com.oplusos.systemui.common.blurability.ViewBlurProxy\$BlurType"
    private const val CLASS_BLUR_TYPE_MOTION = "com.oplusos.systemui.common.blurability.ViewBlurProxy\$BlurType\$BlurTypeMotion"
    private const val CLASS_BLUR_TYPE_PLATFORM_STATIC = "com.oplusos.systemui.common.blurability.ViewBlurProxy\$BlurType\$BlurTypePlatformStatic"
    private const val METHOD_SET_BLUR_TYPE = "setBlurType"
    /** [spec/19 修复 2026-08-13] 纯白根因绕过：HeadsUpContainerWindow.isHeadsUpView(View)。
     *  ViewBlurManager.requireBlurProxyForView 的 excludeRules = `headsUpWindow.isHeadsUpView(view)`
     *  （实证 ViewBlurManager.java:1286）——强制 PlatformStatic 后 PlatformBlurHelper.ensureBlurDrawable
     *  因此拒绝创建 PlatformBlurDrawable → getBlurDrawable 返回原 mBackground（#fffafafa 近白）→ 纯白。
     *  本 hook 对"正在玻璃化的 heads-up bgView"单独放行（返回 false），仅持续到 drawable 创建确认。 */
    private const val CLASS_HEADS_UP_CONTAINER_WINDOW = "com.oplus.systemui.notification.headsup.windowframe.HeadsUpContainerWindow"
    private const val METHOD_IS_HEADS_UP_VIEW = "isHeadsUpView"
    /** [spec/19] heads-up 窗口类型 = TYPE_STATUS_BAR_SUB_PANEL（HeadsUpContainerWindow lp type，反编译实证） */
    private const val WINDOW_TYPE_HEADS_UP = 2017
    // ---- [2026-08-15 轻打扰折叠横幅玻璃化] Simple Banner 管线（独立于普通 heads-up）----
    // 逆向实证：容器 FullScreenBannerContainer（LinearLayout）→ 内容 FullScreenBanner（LinearLayout），
    // 背景 setBackground(blurDrawable)（BackgroundBlurDrawable）；窗口 type 2017 "Simple Banner Window"；
    // onViewAttachedToWindow → requireBlurProxyForView(mFullScreenBanner, CardType.FULLSCREENBANNER, 1.1f)
    // → setBlurType(BlurTypeMotion)（surface 合成模糊，draw 空实现 → 无液态玻璃）；**不走
    // NotificationBackgroundView** → 模块现有 spec/19 setBlurType hook（判据 this.view instanceof
    // NotificationBackgroundView）不触发。修：setBlurType 拦截新增 FullScreenBanner 类名命中 →
    // 同样替换 BlurTypePlatformStatic（复用替换逻辑，字符串类名比较跨 classloader 安全）。
    private const val CLASS_FULL_SCREEN_BANNER = "com.oplus.systemui.notification.interruption.fullscreenbanner.view.FullScreenBanner"
    /** [spec/19] shade 窗口根 View 类名（resolveShadeSfc 只接受它，防 heads-up 误当 shade） */
    private const val SHADE_ROOT_VIEW_CLASS_HINT = "NotificationShadeWindowView"
    /** [2026-08-15 偶发不抓屏根治] shade 窗口根 View 完整类名（反编译实证
     *  `com/android/systemui/shade/NotificationShadeWindowView.java`，extends WindowRootView，public final
     *  override onAttachedToWindow(:415)/onDetachedFromWindow(:444)——hook 用 getDeclaredMethod 锚定
     *  子类自身 override，防 hook 到框架 View.onAttachedToWindow 全 View 命中）。 */
    private const val CLASS_SHADE_ROOT_VIEW = "com.android.systemui.shade.NotificationShadeWindowView"
    private const val METHOD_ON_ATTACHED_TO_WINDOW = "onAttachedToWindow"
    private const val METHOD_ON_DETACHED_FROM_WINDOW = "onDetachedFromWindow"

    // ---- [任务 文字反转取色 spec/21] 亮度阈值与状态 ----
    /** 背景亮度阈值：>196 → 黑字（false）；≤196 → 白字（true）。与系统 OplusQsColorManager.setQsColorState 同阈值 */
    private const val TEXT_CONTRAST_BRIGHTNESS_THRESHOLD = 196
    /** [spec/21 修复 2026-08-13] onDraw 重采样节流间隔：仅快照换帧 / 位置变化才重采样（非每帧），
     *  150ms 内同 TextView 最多重采样一次，避免 getLocationOnScreen 每帧开销 */
    private const val TEXT_CONTRAST_RESAMPLE_INTERVAL_MS = 150L
    /** [2026-08-15 折叠组头误伤修复] 展开按钮数字 TextView 资源名（com.android.systemui:id/oplus_expand_button_number，
     *  折叠组头内 text="2"，原色灰中性色）。识别用资源名匹配（不依赖模块引用 SystemUI R.id）。 */
    private const val EXPAND_BUTTON_NUMBER_RES_NAME = "oplus_expand_button_number"

    // ------------------------------------------------------------ 坐标映射数据结构（R3） ------------------------------------------------------------

    /**
     * screenRegionMap 的 value：屏幕区域 + 产生该区域的输入（用于主键 miss 时的 bounds 兜底匹配）。
     * @param region     全屏像素屏幕区域（AutoBlurDrawable.draw hook 计算：hostView 屏幕左上角 + 局部 bounds）
     * @param autoBounds AutoBlurDrawable 局部 bounds（元素自身尺寸；兜底匹配键）
     * @param hostView   hostView 类名（诊断）
     * @param cornerRadius 真实圆角半径 px（任务 D：反射 BlurConfig；**0 = 反射失败/不可信 → 正方形玻璃**）
     * @param cornerRadiusSource cornerRadius 来源：reflect / none
     * @param cornerIsConic CONIC 本体角开关（true=三次贝塞尔 sdBezierDistance + shader 内 1.5×半径；
     *                      false=RBox 圆弧；spec/18 圆角过渡区深挖修正）
     * @param cornerWeight CONIC 本体角权重（系统 CornerParamsKt fMax=(8·w)/(3·w+3) 变换后值；CONIC 时用）
     */
    private data class RegionEntry(
        val region: RectF,
        val autoBounds: Rect,
        val hostViewName: String,
        val cornerRadius: Float,
        val cornerRadiusSource: String,
        /** CONIC 本体角开关（true=三次贝塞尔 + shader 内 1.5×半径；false=RBox 圆弧） */
        val cornerIsConic: Boolean = false,
        /** CONIC 本体角权重（CornerParamsKt fMax=(8·w)/(3·w+3) 变换后值；cornerIsConic=false 时忽略） */
        val cornerWeight: Float = DEFAULT_CORNER_WEIGHT,
        /** 掩码矩形（viewport 局部坐标：xy=左上, zw=宽高）；null=不裁剪（默认全视口）。侧滑按钮（swipe）专用 */
        val maskRect: RectF? = null,
        /** 掩码圆角半径 px（侧滑按钮真实圆角 = notification_corner_radius dimen）；maskRect==null 时忽略 */
        val maskCornerRadius: Float = 0f,
        /** [方案 B] 宿主 View 弱引用（NC↔CC/锁屏展开容器缩放变换追踪用；
         *  渲染侧沿父链找有变换的容器，把 region 映射到实际绘制坐标；null=跳过变换，回退现状） */
        val hostViewRef: WeakReference<View>? = null,
    )

    /** drawBlurShader 侧解析出的渲染区域（R2：viewport = drawable getBounds 自身尺寸）。 */
    private data class RenderRegion(
        val id: Int,
        val screenRegion: RectF,
        val srcRect: RectF,
        val viewport: RectF,
        val hostViewName: String,
        /** 任务 D（问题 10）：真实圆角半径（RegionEntry 反射得出），渲染传 uCornerRadius；>0 才可信，0=正方形玻璃（任务 H1） */
        val cornerRadius: Float,
        /** cornerRadius 来源：reflect / none */
        val cornerRadiusSource: String,
        /** CONIC 本体角开关（true=三次贝塞尔 + shader 内 1.5×半径；false=RBox 圆弧；spec/18 修正） */
        val cornerIsConic: Boolean = false,
        /** CONIC 本体角权重（CornerParamsKt fMax=(8·w)/(3·w+3) 变换后值；cornerIsConic=false 时忽略） */
        val cornerWeight: Float = DEFAULT_CORNER_WEIGHT,
        /** 掩码矩形（viewport 局部坐标）；null=无裁剪。侧滑按钮（swipe）传按钮实际区域 */
        val maskRect: RectF? = null,
        /** 掩码圆角半径 px（侧滑按钮 = notification_corner_radius dimen） */
        val maskCornerRadius: Float = 0f,
    )

    /**
     * 已应用到一个 drawable shader 的 uniform 状态（R1：变化检测）。
     * 任一字段变化 → 重设 uniform；源位图变化 → 必须重新 setInputShader
     * （整屏快照换新 Bitmap 后旧 BitmapShader 仍指向旧图，玻璃内容会冻结）。
     * [2026-08-13 用户决定] frame 恒 0（无全局 onBlurReady 帧缓存，BlurService 端全移除），
     * 源变化判断靠 sourceBitmapId（整屏快照新实例 identityHashCode）。
     */
    private data class AppliedKey(
        val frame: Long,
        val sourceBitmapId: Int,
        val viewport: RectF,
        val srcRect: RectF,
        /** 掩码区域（null=无裁剪）；变化 → 重设 uniform（侧滑按钮动效期间按钮尺寸/扩张区变化） */
        val maskRect: RectF? = null,
        /** [CONIC] 本体角开关（uniform 变化检测；corner 反射变化 → 重设 uCornerIsConic/uCornerWeight） */
        val cornerIsConic: Boolean = false,
        /** [CONIC] 本体角权重（uniform 变化检测） */
        val cornerWeight: Float = DEFAULT_CORNER_WEIGHT,
        /** 圆角半径 px（uniform 变化检测；corner 0→非0 反射恢复 → 重设 uCornerRadius） */
        val cornerRadius: Float = 0f,
        /** [2026-08-13] 本体角 CONIC↔圆弧渐变权重（uniform 变化检测；恒 1 = 角恒 CONIC） */
        val cornerConicBlend: Float = 1f,
        /** [2026-09-16 doc/spec/67] **材质参数指纹**（`LiquidGlassShader.Params.hashCode()`，data class
         *  自动生成，覆盖全部材质字段）。
         *  ⚠️ 此前材质参数**完全不在变化检测内**（[applyComputedGeometry] 只比对 frame/source/viewport/
         *  srcRect/corner/mask/maskAlpha）→ 改**任何材质滑杆**（高光强度/宽度/falloff/弧线保底/鲜艳度/
         *  光角/视差…）都不会即时生效，必须等几何变化（拖动、尺寸变、快照换新）才被顺带应用。
         *  用户实测「弧线高光保底没用、拖了没反应」即此因。纳入后配置改动约 1s（材质缓存周期）内生效。 */
        val materialHash: Int = 0,
        /** [spec/35 黑遮罩 / spec/41 作用范围修正] 该元素遮罩不透明度（uniform 变化检测；
         *  SystemUI 下拉所有元素统一值，配置变化 → 走全量重设重设 uMaskAlpha） */
        val maskAlpha: Float = 0f,
    )

    // ------------------------------------------------------------ 整屏快照背景源（doc/spec/07 + 2026-08-13 组合方案）

    /**
     * 整屏快照条目：captureDisplay 整屏（排除 shade，0.5 降采样）得到的干净背景位图，**共享给所有玻璃元素**。
     * 内容变化时立即重抓（无节流，异步 worker 控负载），移动只更新 srcRect 不重抓。
     * @param bitmap 整屏 0.5 降采样位图（captureDisplay 无 sourceCrop + setExcludeLayers([shade]) + setFrameScale 0.5）
     * @param region 抓屏时的屏幕覆盖区域（全屏 RectF(0,0,W,H)）；渲染侧按它折算 srcRect（bitmap 尺寸/region 尺寸 = 缩放比）
     */
    private class ElementBackground(
        val bitmap: Bitmap,
        val region: RectF,
    )

    /** [spec/21] 文字反转取色 per-TextView 缓存条目：命中条件 = 快照实例不变 + 屏幕位置不变 + 原色不变。
     *  命中时直接复用 [injectedColor]（O(1)，非每帧采样）。injectedColor == originalColor 表示无需反转。
     *  [2026-08-13 修复] lastCheckMs = 上次实际检查时间戳（onDraw 节流重采样用；快照/位置/原色变化才重采样）。
     *  x/y 用 Int.MIN_VALUE 表示「登记时位置未知」（未 attach），onDraw 首次检查时会取真实位置。 */
    private class TextContrastCacheEntry(
        val snapshotId: Int,
        val x: Int,
        val y: Int,
        val originalColor: Int,
        val injectedColor: Int,
        var lastCheckMs: Long = 0,
    )

    /** 渲染背景源决策结果：整屏快照（srcRect = 元素当前 region 折算到整屏快照坐标，移动实时跟） */
    private class RenderSource(
        val source: Bitmap,
        val srcRect: RectF,
        /** true=整屏快照源（0.5 降采样）；false=全局 onBlurReady 原图（已移除，恒 true） */
        val isElementSource: Boolean,
    )

    /** 整屏快照（@Volatile：worker 线程写入，渲染线程读取；内容变化时重抓，静止不抓） */
    @Volatile
    private var screenSnapshot: ElementBackground? = null

    /** 整屏快照抓屏失败重试计数（3 次/320ms） */
    private val elementCaptureRetries = ConcurrentHashMap<Int, Int>()

    /** [2026-08-13 延迟调查·spec/45 修正] shade sfc 解析失败连续计数（worker 线程读写）。sfc 暂不可用
     *  （heads-up 早期 attach 滞后 / shade 折叠期 detach）是暂时性状态 → 60ms 短延迟快速重试，连续失败达
     *  [bgCaptureRetryLimit] 次才走冷却（防无限 spin）。 */
    @Volatile
    private var sfcFailCount = 0

    /** 整屏快照抓屏 3 次用尽后的冷却截止时间（下个内容变化/渲染兜底重试） */
    private val elementCaptureQuitAt = ConcurrentHashMap<Int, Long>()

    /** [spec/17 配置接线] 上次内容变化抓屏入队时间（节流；KEY_BG_CAPTURE_MIN_INTERVAL_MS>0 时生效，
     *  记录实际入队时刻；0=未抓过/无节流不记录） */
    @Volatile
    private var lastCaptureTime = 0L

    /** [doc/spec/49 2026-08-13] 上次内容变化信号时刻（[markContentChanged] 更新；触发点：新映射写入 /
     *  onBlurReady 新帧 / setExpansionHeight / heads-up 卡同卡更新）。周期抓屏据此判空闲：距上次内容变化
     *  超过 [PERIODIC_CAPTURE_IDLE_SKIP_MS] → 保持旧帧不抓（内容变化驱动为主）。0=尚未收到内容变化信号。
     *  @Volatile：主线程（内容变化触发点）写，主线程（periodicCaptureRunnable）读。 */
    @Volatile
    private var lastContentChangeTimeMs = 0L

    /** [2026-08-15 恢复 worker] 待抓队列（整屏快照任务 id）——[scheduleElementCaptures] 入队 +
     *  [kickElementCaptureWorker] 起单 worker 消费（异步，主线程零阻塞）。触发抓屏重新走「入队 → worker
     *  消费」。**worker 卡死/死锁根因已修复**（[runElementCaptureWorker] finally 原子清理 + 自续跑：
     *  poll==null 到 running-- 竞态窗口入队后 kick 早退 → 孤儿队列 + pending 残留 + running=0
     *  → 永久不消费，见 spec/56 追加记录）。 */
    private val elementCaptureQueue = ConcurrentLinkedQueue<Int>()

    /** [2026-08-15 恢复 worker] pending 去重集合：pendingCaptureIds.add 成功才入队（同 id 已在队列/处理中
     *  则跳过）。worker 出队时移除；失败重试重新入队。**孤儿清理**：worker 退出时队列空 → 清空残留 pending
     *  （保证下次入队 pending.add 成功，杜绝「pending 残留 → 永久不入队」）。 */
    private val pendingCaptureIds = ConcurrentHashMap.newKeySet<Int>()

    /** [2026-08-15 恢复 worker] 存活 worker 计数（0/1；单 worker 串行，pending 去重后同刻至多 1 任务）。
     *  读写均在 synchronized(this)（[kickElementCaptureWorker] 起线程 / [runElementCaptureWorker] finally
     *  复位+自续跑）内原子完成。 */
    @Volatile
    private var elementCaptureWorkerRunning = 0

    /** [2026-08-15 恢复 worker] 抓屏中标记（poll 出队 → capture 完成期间）：渲染兜底
     *  [requestScreenSnapshotAsync] 见此 SNAPSHOT_ID 即不重复入队（避免双路 captureDisplay）。 */
    private val captureInFlightIds = ConcurrentHashMap.newKeySet<Int>()

    /** [2026-08-14 spec/56] 主线程抓屏失败重试 postDelayed 单飞守卫（防重试与每帧触发重复堆积 post）。 */
    @Volatile
    private var captureRetryPosted = false

    /** [2026-08-14 spec/56] 快照就绪后的文字亮度全图扫描（原 worker 内联，540×1200 IntArray 2.6MB getPixels）
     *  拆为后台单飞执行（主线程直接抓屏时同步扫会卡 ANR）——单飞防并发扫描。 */
    @Volatile
    private var textScanRunning = false

    /** [歧义修复 2026-08-13] 每个 drawable 上次成功渲染的屏幕区域（bounds 兜底歧义时按位置匹配键） */
    private val lastRenderScreenRegion = ConcurrentHashMap<Int, RectF>()

    /** [歧义修复] 歧义 ABORTED 冷却记忆：id → 截止时间；期间直接回退系统模糊（防高频闪） */
    private val ambiguousUntil = ConcurrentHashMap<Int, Long>()

    @Volatile
    private var api: XposedInterface? = null

    // ---- 反射方法缓存（SystemUI classloader 下，进程内复用；挂载前解析） ----
    private var mDrawBlurShader: Method? = null
    private var mGetDrawableShaderPaint: Method? = null
    private var mGetDrawableShader: Method? = null
    private var mGetShaderOrNull: Method? = null
    private var mGetBounds: Method? = null
    private var mGetPaint: Method? = null
    private var mAutoGetViewBlurProxy: Method? = null
    private var mViewProxyGetView: Method? = null
    private var mViewProxyGetBlurDrawable: Method? = null
    private var mPlatformGetBlurDrawable: Method? = null
    private var mAutoDefaultDrawable: Field? = null
    private var mAutoGetBounds: Method? = null

    // ---- 任务 2/3：MaskBlurDrawable.getViewBlurProxy / MixColorTileDrawable.getDrawable ----
    private var mMaskGetViewBlurProxy: Method? = null
    private var mTileWrapperGetDrawable: Method? = null
    /** [折叠组头磨砂 2026-08-12] NotificationHeaderMaskColorDrawable.drawable 私有字段（内层 drawable） */
    private var mHeaderMaskInner: Field? = null
    /** [磨砂·修4] BaseDrawable.getEnableShader()——系统 enableShader=false 时 onDrawContent 走 drawBitmapContent 画模糊位图 */
    private var mGetEnableShader: Method? = null
    /** [磨砂·修3] ExtImp.clickAlpha（int，点击暗层 alpha）与 stackedNotificationMuskColor（int，堆叠/折叠色层） */
    private var mExtImpClickAlpha: Field? = null
    private var mExtImpStackedMuskColor: Field? = null    // [任务 T] MixColorTileDrawable.maskColor 字段（读状态色判 z；字段反射失败则恒走玻璃拦截兜底）
    private var mTileMaskColor: Field? = null
    // ---- 任务 spec/14 补映射：宿主类私有 viewBlurProxy / replacedDrawable 字段反射（独立 try-catch）----
    private var mCapsuleHelperViewBlurProxy: Field? = null
    private var mCapsuleHelperReplacedDrawable: Field? = null
    private var mCapsuleEarViewBlurProxy: Field? = null
    private var mMediaCarouselViewBlurProxy: Field? = null

    // ---- C1：通知卡片专属映射反射（NotificationBackgroundViewExt.getBgView/getViewBlurProxy） ----
    private var mNbvGetBgView: Method? = null
    private var mNbvGetViewBlurProxy: Method? = null

    // ---- 磨砂根因：ViewBlurManager.currentMaterialColor / PlatformBlurDrawable.blurMaskColor 字段 ----
    private var mNbvViewBlurManager: Field? = null
    private var mViewBlurManagerMaterialColor: Field? = null
    private var mPlatformBlurMaskColor: Field? = null

    // ---- 任务 D + 任务 B（spec/11）：圆角半径反射（ViewBlurProxy.getBlurConfig → BlurConfig 真实 cornerRadius） ----
    private var mViewProxyGetBlurConfig: Method? = null
    private var mBlurConfigGetCornerRadius: Method? = null
    private var mBlurConfigGetLeftTopCornerRadius: Method? = null
    // [spec/11] 多级策略新增：其余三角 getter / pathProvider（QS tile）/ gradientStrokeCornerParam（侧滑按钮/MetaBall）
    private var mBlurConfigGetLeftBottomCornerRadius: Method? = null
    private var mBlurConfigGetRightTopCornerRadius: Method? = null
    private var mBlurConfigGetRightBottomCornerRadius: Method? = null
    private var mBlurConfigGetPathProvider: Method? = null
    private var mBlurConfigGetGradientStrokeCornerParam: Method? = null
    private var mRoundRectOutlineProviderClass: Class<*>? = null
    private var mRoundRectOutlineProviderCornerRadiusField: Field? = null
    private var mCornerParamsGetRadius: Method? = null
    private var mCornerParamsGetType: Method? = null
    private var mCornerParamsGetWeight: Method? = null
    private var mBlurConfigGetRadiusWeight: Method? = null   // [spec/18] BlurConfig.getRadiusWeight()（setSmoothCorner 信号）
    private var mCornerTypeConic: Any? = null    // CornerType.CONIC 枚举常量（判等用；null=反射失败降级圆弧）
    private var mBaseRoundRectPathProviderClass: Class<*>? = null
    private var mBaseRoundRectPathProviderGetMaxRadius: Method? = null
    // ---- [spec/39 文字反色复用系统黑白通道] QsColorUtil.getQsColorState() 反射（public static final int；
    // applyLocalTextContrast 遮罩关闭分支采样来源，替代逐文字整屏快照 sampleInjectedColor；mountQsColorUtil 解析）----
    private var mQsColorUtilGetState: Method? = null
    // [spec/47 诊断] 上次记录的 getQsColorState() 值（值跳变才打一次日志，验证 heads-up 场景系统通道是否 0/1）。
    private var lastLoggedQsColorState = Int.MIN_VALUE
    // [spec/52 文字强制刷新] 上次 setQsColorState(int) 发布值（系统黑白通道状态跳变才 post 全局文字刷新，
    // 同值重复发布不重复触发；见 mountQsColorUtil 发布方 hook）。
    private var lastPublishedQsColorState = Int.MIN_VALUE
    // [spec/52] forceRefreshAllTextColors 全局刷新节流：触发点（收起/系统状态跳变）可能短时间多次回调，
    // 全树遍历 TextView 有成本，节流窗口内只 post 一次。
    private const val FORCE_REFRESH_TEXT_COLOR_MIN_INTERVAL_MS = 250L
    /** 上次 post 全局文字强制刷新时刻（主线程写；requestForceRefreshTextColors 节流用）。 */
    @Volatile
    private var lastForceRefreshTextColorMs = 0L
    // [spec/60 周期强制刷新] 遮罩/文字变色相关开关开启且有活跃玻璃宿主时，周期全树重扫文字颜色，
    // 兜底同屏文字统一白/取色（与事件触发共用 requestForceRefreshTextColors 的 250ms 节流，不重复跑）。
    /** 周期强制刷新 tick：文字变色相关开关全关 / 间隔 0 / 无活跃宿主 → 零成本跳过；按最新配置间隔续跑。 */
    private val textForceRefreshRunnable: Runnable = object : Runnable {
        override fun run() {
            try {
                // 文字变色相关开关全关（mask 关 + 文字反色关 + heads-up 跟随关）→ 停止周期调度
                if (!maskEnabled && !textContrastEnabled && !headsUpTextFollowEnabled) {
                    textForceRefreshScheduled = false
                    return
                }
                val interval = textForceRefreshIntervalMs()
                if (interval <= 0) {
                    // 0 = 关闭周期刷新（仅事件触发）
                    textForceRefreshScheduled = false
                    return
                }
                // 活跃玻璃宿主（shade 展开 / heads-up 活跃）才全树重扫，否则零成本跳过。
                // [spec/62 锁屏耗电优化 2026-08-26] 锁屏不再作活跃宿主——锁屏无需更新文字
                //（spec/62 决策 28 用户硬约束）。门控命中（锁屏 + 无 heads-up + 面板收起）打节流日志便于真机验证。
                if (panelExpansionActive || isHeadsUpHostActive()) {
                    requestForceRefreshTextColors()
                } else if (isKeyguardLockedNow()) {
                    logThrottled("lg-batt-text-lock-skip", Log.DEBUG) { "lg-batt: text refresh lock-screen skip" }
                }
                mainHandler().postDelayed(textForceRefreshRunnable, interval.toLong())
            } catch (t: Throwable) {
                Log.e(TAG, "text-contrast forceRefresh periodic tick error", t)
                textForceRefreshScheduled = false
            }
        }
    }
    /** 周期调度运行标志（防重复启动；[ensureTextForceRefreshLoop] @Synchronized 防重入）。 */
    @Volatile
    private var textForceRefreshScheduled = false

    /** [spec/60] 周期强制刷新间隔（ms）：Prefs 每次 tick 读最新值；0=关闭周期刷新（仅事件触发）；
     *  clamp 100~5000（防误配置超范围空转）。 */
    private fun textForceRefreshIntervalMs(): Int {
        val a = api ?: return Prefs.DEFAULT_TEXT_FORCE_REFRESH_INTERVAL_MS
        return try {
            Prefs.readIntCompat(Prefs.read(a), Prefs.KEY_TEXT_FORCE_REFRESH_INTERVAL_MS, Prefs.DEFAULT_TEXT_FORCE_REFRESH_INTERVAL_MS)
                .coerceIn(0, 5000)
        } catch (t: Throwable) {
            Prefs.DEFAULT_TEXT_FORCE_REFRESH_INTERVAL_MS
        }
    }

    /** [spec/60] 确保周期强制刷新循环在跑（幂等去重：已在跑直接返回；无需显式停止——tick 内部
     *  开关全关 / 间隔 0 / 无活跃宿主时轻量跳过或自停）。挂靠点：install 末尾 / setExpansionHeight /
     *  registerHostView / heads-up 活跃变化（triggerHeadsUpAppearanceCapture）。 */
    @Synchronized
    private fun ensureTextForceRefreshLoop() {
        if (textForceRefreshScheduled) return
        val interval = textForceRefreshIntervalMs()
        if (interval <= 0) return  // 周期刷新关闭（0），仅事件触发
        textForceRefreshScheduled = true
        try {
            mainHandler().postDelayed(textForceRefreshRunnable, interval.toLong())
        } catch (t: Throwable) {
            Log.e(TAG, "text-contrast forceRefresh loop start failed", t)
            textForceRefreshScheduled = false
        }
    }
    // [spec/53 状态栏文字变黑 2026-08-13] 状态栏区域底边（物理 px，framework status_bar_height dimen，
    // 首次调用缓存）。isOverGlassRegion 对 y < 该值的坐标恒判"非玻璃"——状态栏文字（时钟/图标 label 等）
    // 绝不被文字变色逻辑干预（状态栏文字颜色由系统 DarkIconDispatcher 等管理）。-1 = 解析失败（不启用排除）。
    private var statusBarBottomPx = -1

    // ---- [2026-08-13 反射结果缓存] registerScreenRegionCore 的 blurDrawable/target 解析结果缓存 ----
    // 同一 drawable 实例每次 draw 的 proxy 相同，getBlurDrawable + platformGetBlurDrawable 解析稳定，
    // 命中后跳过整条反射链（控制中心 20+ 元素每帧省 2-3 反射/元素 = 每帧省 40-60 次反射，防主线程
    // "算遮罩"反射累积超时）。key=identityHashCode(proxy)；drawable 重建 → 新 key → miss 重解析。
    private data class RegisterTargetCache(val target: Any)
    private val registerTargetCache = ConcurrentHashMap<Int, RegisterTargetCache>()
    private const val REGISTER_TARGET_CACHE_MAX = 512

    // ---- 侧滑按钮真实圆角 dimen：SystemUI R.dimen.notification_corner_radius（16dp，dimens.xml:3027 实证）----
    private var mNotificationCornerRadiusDimenId = 0

    // ---- [Bug 3] 侧滑按钮圆角级 2 兜底：NotificationMenuRowMetaBallController2.getBlurMenuCornerRadius() ----
    //（私有实例方法，内部 `context.getResources().getDimensionPixelSize(R.dimen.notification_corner_radius)`
    //  反编译实证 line 35-36；构造函数 public(Context)，new 实例 + setAccessible 调用。R 类字段反射
    //  在真机偶发失败（mNotificationCornerRadiusDimenId=0）时的正解兜底。）
    private var mBlurMenuCornerRadiusCtor: Constructor<*>? = null
    private var mBlurMenuCornerRadiusMethod: Method? = null

    // ---- [Bug 5] OplusCustomRow 横向滑动修正：mBackgroundNormal（私有 declared，玻璃宿主）----
    private var mCustomRowBgNormal: Field? = null

    // ---- [spec/19 Heads-Up 玻璃化] ViewBlurProxy.setBlurType + BlurType 枚举实例 + NotificationBackgroundView ----
    private var mViewProxySetBlurType: Method? = null
    private var mBlurTypeMotionInstance: Any? = null
    private var mBlurTypePlatformStaticInstance: Any? = null
    private var mNotifBackgroundViewClass: Class<*>? = null

    // ---- [spec/19] heads-up 窗口根 SurfaceControl 缓存（与 shadeSfcCache 分开解析，防误当 shade）----
    @Volatile
    private var headsUpSfcCache: SurfaceControl? = null
    /** [spec/19 修复 2026-08-13] 正在强制玻璃化的 heads-up NotificationBackgroundView（WeakReference）。
     *  setBlurType 强制 PlatformStatic 时登记；对应 view 的 isHeadsUpView 被 [mountHeadsUpContainerIsHeadsUpView]
     *  scoped 放行（返回 false）→ PlatformBlurHelper.ensureBlurDrawable 不再因 excludeRules 拒绝创建
     *  PlatformBlurDrawable。drawable 创建确认（recordNotificationCardRegion 命中）后清除；GC 自动失效。 */
    @Volatile
    private var headsUpGlassForceView: WeakReference<View>? = null

    // ---- [spec/51 2026-08-13] 通知（heads-up）首帧主线程抓屏 + 连抓 5~6 帧 + 持续期间兜底抓屏 ----
    /** 当前活跃 heads-up 通知背景 View（WeakReference 防泄漏）。通知出现时登记；消失（detach /
     *  rootView 不再 heads-up）时由 [isHeadsUpHostActive] 判定并清除。@Volatile：主线程写，
     *  连抓 worker / 兜底 Runnable（主线程）读。 */
    @Volatile
    private var headsUpActiveHost: WeakReference<View>? = null
    /** 连抓剩余帧数（通知出现首帧后 worker 快速补抓）。@Volatile：主线程（triggerHeadsUpAppearanceCapture）
     *  写，连抓 worker 线程读。 */
    @Volatile
    private var headsUpBurstRemaining = 0
    /** 连抓 worker 运行标志（防重复起线程；连抓多帧共享单 worker）。@Volatile：主线程写，worker finally 复位。 */
    @Volatile
    private var headsUpBurstRunning = false
    /** [2026-08-13 用户要求「通知持续期间必须一直抓屏」] heads-up 活跃期间不间断抓屏 worker 运行标志
     *  （防重复起线程；通知消失自动退出并复位）。@Volatile：主线程写，worker finally 复位。 */
    @Volatile
    private var headsUpContinuousRunning = false

    /** [2026-08-15 主动式持续抓屏] 面板（下拉通知栏）展开期间主动持续抓屏 worker 运行标志
     *  （防重复起线程；面板收起自动退出并复位）。@Volatile：主线程（onExpansionStarted /
     *  setExpansionHeight 展开分支）写，worker finally 复位。 */
    @Volatile
    private var panelContinuousRunning = false

    // ---- [2026-08-14 锁屏被动] 锁屏状态判定（KeyguardManager.isKeyguardLocked，Binder → system_server）----
    /** SystemUI 进程 Application context（Xposed 注入时 ActivityThread 已初始化）；lazy 惰性获取防 install
     *  早期不可用。ActivityThread 为 @hide（公共 SDK 不可直接引用）→ 反射 currentApplication。 */
    private val systemUiContext: android.content.Context? by lazy {
        try {
            val clazz = Class.forName("android.app.ActivityThread")
            val m = clazz.getMethod("currentApplication").apply { isAccessible = true }
            (m.invoke(null) as? android.app.Application)?.applicationContext
        } catch (t: Throwable) {
            null
        }
    }
    /** 锁屏状态缓存（volatile 读零开销；≥[KEYGUARD_STATE_CACHE_MS] 才重查 Binder，锁屏/解锁切换 ≤500ms 响应） */
    @Volatile
    private var cachedKeyguardLocked = false
    @Volatile
    private var cachedKeyguardLockedAt = 0L
    /** 锁屏状态缓存毫秒（持续 worker 每 tick 读缓存零 Binder 开销；500ms 内锁屏/解锁切换延迟可感知） */
    private const val KEYGUARD_STATE_CACHE_MS = 500L

    /** [2026-08-14 锁屏被动] 锁屏判定：`KeyguardManager.isKeyguardLocked()`（public，无需权限，Binder
     *  到 system_server 的 KeyguardService，反映当前设备锁屏状态；锁屏（含 occluded，如锁屏下打开相机）
     *  返回 true）。**判定失败保守返回缓存/ false**（不误拦持续抓屏）。500ms 缓存防 worker 每 tick Binder
     *  开销。锁屏期间主动持续抓屏（[startPanelContinuousCapture] / [startHeadsUpContinuousCapture]）
     *  **不启动 / 已运行退出**——锁屏与解锁桌面一样走**被动事件触发**（映射写入 / onBlurReady /
     *  setExpansionHeight 等），避免锁屏耗电/不必要抓屏。 */
    private fun isKeyguardLockedNow(): Boolean {
        val now = SystemClock.uptimeMillis()
        val cached = cachedKeyguardLocked
        if (now - cachedKeyguardLockedAt < KEYGUARD_STATE_CACHE_MS) return cached
        val v = try {
            val ctx = systemUiContext
            if (ctx == null) {
                // [spec/62 锁屏耗电优化 2026-08-26] 上下文获取失败 → **保守返回 true**（宁可不抓屏不误抓；
                // 原实现返回旧缓存可能是错误的 false → 门控永久失效）。即使 keyguard 恒 true，解锁后用户
                // 下拉面板 panelExpansionActive=true 或 heads-up 出现，门控仍放行，玻璃不会永久失效（符合设计）。
                // 节流打一次避免刷屏。审查加固：失败路径同样写缓存（保守 true），避免缓存过期后每次调用重试 Binder。
                logThrottled("lg-batt-keyguard-ctx-null", Log.WARN) { "lg-batt: keyguard ctx null fallback true" }
                cachedKeyguardLocked = true
                cachedKeyguardLockedAt = now
                return true
            }
            val km = ctx.getSystemService(android.content.Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
            if (km == null) {
                logThrottled("lg-batt-keyguard-ctx-null", Log.WARN) { "lg-batt: keyguard ctx null fallback true" }
                cachedKeyguardLocked = true
                cachedKeyguardLockedAt = now
                return true
            }
            km.isKeyguardLocked
        } catch (t: Throwable) {
            // 反射/调用异常同样保守返回 true（不依赖旧缓存，避免门控永久失效）；写缓存避免反复 Binder
            logThrottled("lg-batt-keyguard-ctx-null", Log.WARN) { "lg-batt: keyguard ctx null fallback true" }
            cachedKeyguardLocked = true
            cachedKeyguardLockedAt = now
            true
        }
        cachedKeyguardLocked = v
        cachedKeyguardLockedAt = now
        return v
    }

    /** [spec/62 锁屏耗电优化 2026-08-26] 注册锁屏状态缓存失效广播：屏幕开/关、用户解锁时把
     *  [cachedKeyguardLockedAt] 置 0（缓存过期 → 下次读取强制实测 KeyguardManager），消除
     *  [KEYGUARD_STATE_CACHE_MS] 竞态窗口（锁屏/解锁切换 ≤500ms 内门控误判）。
     *  **不用广播直接写死状态值**（SCREEN_OFF 不一定锁屏、USER_PRESENT 才必然解锁——语义由实测保证，
     *  避免 SCREEN_OFF 不锁屏等语义偏差）。
     *  注册用 systemUiContext（context.applicationContext.registerReceiver）；SYSTEM_UI 进程内该 context
     *  生命周期与进程一致，模块随 SystemUI 进程存续 → **无需 unregister**。context 解析失败则跳过注册并打日志。 */
    private fun registerKeyguardStateBroadcastReceiver() {
        val ctx = systemUiContext
        if (ctx == null) {
            Log.w(TAG, "lg-batt: keyguard broadcast receiver skipped, context null")
            return
        }
        try {
            val filter = android.content.IntentFilter().apply {
                addAction(android.content.Intent.ACTION_SCREEN_ON)
                addAction(android.content.Intent.ACTION_SCREEN_OFF)
                addAction(android.content.Intent.ACTION_USER_PRESENT)
            }
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                    try {
                        val action = intent?.action ?: return
                        // 缓存置过期 → 下次 isKeyguardLockedNow 强制实测（不直接写死状态值）
                        cachedKeyguardLockedAt = 0L
                        if (moduleLogEnabled) Log.d(TAG, "lg-batt: keyguard cache invalidated by broadcast $action")
                    } catch (t: Throwable) {
                        Log.e(TAG, "lg-batt: keyguard broadcast onReceive error", t)
                    }
                }
            }
            // API 33+ 显式声明不导出（仅注册受保护系统广播，RECEIVER_NOT_EXPORTED 即足够，防第三方伪造）；
            // SDK<33 无导出标志概念，两参版本。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.applicationContext.registerReceiver(receiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
            } else {
                ctx.applicationContext.registerReceiver(receiver, filter)
            }
            Log.i(TAG, "lg-batt: keyguard broadcast receiver registered")
        } catch (t: Throwable) {
            Log.e(TAG, "lg-batt: register keyguard broadcast receiver failed", t)
        }
    }

    // ---- [2026-08-13 captureDisplay 开始延迟优化] 抓屏热路径远程 Prefs 时间节流缓存 ----
    // bgCaptureScale()/bgCaptureMinIntervalMs() 原每次调用经 api.getRemotePreferences（LibXposed 框架侧
    // 存储，**跨进程 IPC**）读——每次抓屏/入队一次 IPC（1-10ms），是「触发 → captureDisplay 开始」路径上
    // 的非必要延迟。缓存 ≥CAPTURE_PREFS_CACHE_MS 才重读一次（同 readMaterialParams 思路），热路径零 IPC。
    @Volatile
    private var cachedBgCaptureScale = Prefs.DEFAULT_BG_CAPTURE_SCALE
    @Volatile
    private var cachedBgCaptureScaleTimeMs = 0L
    @Volatile
    private var cachedBgCaptureMinIntervalMs = Prefs.DEFAULT_BG_CAPTURE_MIN_INTERVAL_MS
    @Volatile
    private var cachedBgCaptureMinIntervalMsTimeMs = 0L
    /** [2026-08-15 主动式持续抓屏] 持续抓屏速率 Hz 缓存（[panelCaptureHz]；热路径 IPC 节流缓存同上） */
    @Volatile
    private var cachedPanelCaptureHz = Prefs.DEFAULT_PANEL_CAPTURE_HZ
    @Volatile
    private var cachedPanelCaptureHzTimeMs = 0L

    // ---- [2026-08-13 captureDisplay 开始延迟优化] sfc 反射方法/字段缓存 ----
    /** ViewRootImpl.getSurfaceControl（@SystemApi）缓存：所有 View 共享 View.getViewRootImpl 基类方法，
     *  getSurfaceControl 属 ViewRootImpl（framework 固定类）——各解析一次即可，避免每次抓屏遍历宿主反射。 */
    @Volatile
    private var mGetViewRootImpl: Method? = null
    @Volatile
    private var mViewRootImplGetSurfaceControl: Method? = null
    /** ViewRootImpl.getWindowAttributes / WindowManager.LayoutParams.type 字段缓存（reflectWindowType）。 */
    @Volatile
    private var mGetWindowAttributes: Method? = null
    @Volatile
    private var mWindowAttrsTypeField: Field? = null
    /** heads-up 窗口 sfc 解析失败（无 heads-up）负缓存截止时刻：此刻前 resolveHeadsUpSfc 直接返回 null，
     *  不重复遍历 registeredHostViews 反射窗口类型（每次抓屏一次遍历 + 每宿主反射的开销消除）。
     *  @Volatile：worker（resolveHeadsUpSfc）写，主线程 registerHostView（新宿主登记）清零。 */
    @Volatile
    private var headsUpSfcMissAt = 0L
    /** [2026-08-15 轻打扰折叠横幅] Simple Banner Window sfc 解析失败（无横幅）负缓存截止时刻：此刻前
     *  resolveSimpleBannerSfc 直接返回 null，不重复遍历 registeredHostViews 反射窗口标题/类型。 */
    @Volatile
    private var simpleBannerSfcMissAt = 0L
    /** [2026-08-15] Simple Banner Window 根 sfc 缓存（有效则直接复用，不重复遍历） */
    private var simpleBannerSfcCache: SurfaceControl? = null
    /** [2026-08-15] WindowManager.LayoutParams.getTitle() 反射缓存（reduce 每抓屏 getMethod 开销） */
    private var mWindowGetTitleMethod: Method? = null

    // ---- [spec/21 文字反转取色] 状态 ----
    /** 文字反转取色开关（install 时读 Prefs 缓存；false 不挂 QsColorUtil/TextView hook，走系统原逻辑）。
     *  @Volatile：install 线程写，hook 回调（主线程）读。 */
    @Volatile
    private var textContrastEnabled = true
    /** QS 面板区域平均亮度（-1=无快照；快照更新时 worker 线程计算；方案 B hook 读，判定 O(1)） */
    @Volatile
    private var qsPanelBrightness = -1
    /** 文字局部反转 per-TextView 缓存（WeakHashMap 防泄漏；setTextColor hook 主线程读写）。
     *  节流：快照换帧（snapshotId）/位置（x,y）/原色（originalColor）任一变化才重采样。 */
    private val textContrastCache = WeakHashMap<TextView, TextContrastCacheEntry>()
    private const val TEXT_CONTRAST_CACHE_MAX = 1024
    /** [spec/21 修复 2026-08-13] 防自循环标志：onDraw 重采样/快照更新重采样直接调 setTextColor(injected)
     *  时置 true，setTextColor hook 见 true 直接 proceed（不递归采样）。@Volatile：onDraw（主线程）与
     *  快照更新 post（主线程）同线程，保守标注。 */
    @Volatile
    private var textContrastResampling = false

    // ---- [spec/35 黑遮罩 + 白字方案 / spec/41 作用范围修正] 状态（install 时读 Prefs 缓存；改动需重启 SystemUI 生效） ----
    /** 黑遮罩总开关（KEY_MASK_ENABLE；true → SystemUI 下拉面板所有玻璃元素统一压暗背景采样 + 文字统一白/灰）。 */
    @Volatile
    private var maskEnabled = Prefs.DEFAULT_MASK_ENABLE
    /** 黑遮罩不透明度（KEY_MASK_ALPHA，0~1）。 */
    @Volatile
    private var maskAlpha = Prefs.DEFAULT_MASK_ALPHA
    /** 遮罩下文字统一颜色 ARGB（KEY_MASK_TEXT_COLOR，默认纯白）。 */
    @Volatile
    private var maskTextColor = Prefs.DEFAULT_MASK_TEXT_COLOR
    /** 文字强制白独立开关（KEY_TEXT_FORCE_WHITE_ENABLE，默认 true；spec/63 统一注入通道解耦
     *  KEY_MASK_ENABLE——遮罩只管背景压暗，强制白独立可配：可「只要白字不要遮罩」或「强制白单独可关」）。 */
    @Volatile
    private var textForceWhiteEnabled = Prefs.DEFAULT_TEXT_FORCE_WHITE_ENABLE
    // ---- [spec/48 遮罩走系统自带机制] 背景 scrim 纯黑 tint 相关 ----
    /** ScrimView.mScrimName（public String，16.1 反编译实证）——判定 behind_scrim（shade 面板背景）画黑 tint；
     *  notifications_scrim/front_scrim 保持透明（现状）。反射失败（null）→ 保守按 behind_scrim 处理（宁画不丢）。 */
    private var mScrimNameField: Field? = null
    /** ScrimView.mViewAlpha（public float，ScrimView.java:43 实证）——系统 scrim 动画逐帧 alpha 真源。
     *  setViewAlpha 每次写入、onDraw 时已是当前动画值；绘制路径直接反射读它，**不依赖 hook**（最稳）。 */
    private var mViewAlphaField: Field? = null
    /** ScrimViewEx.getScrimView()（ScrimViewEx.java:34，public）——从 ScrimViewEx/ExImp 反取宿主 ScrimView。 */
    private var mExtGetScrimView: Method? = null
    /** scrim 纯黑 tint Paint 单例（Style.FILL，颜色每次 draw 重设：alpha=behindScrimAlpha×maskAlpha）。 */
    private var scrimMaskPaint: Paint? = null
    /** [spec/48 过渡修复 2026-08-13] 最新 behind_scrim 真实 alpha（0~1，hook setViewAlpha/getViewAlpha 捕获，
     *  系统动画/拖动逐帧更新）。根因：AutoBlurDrawable.setAlpha 空实现 → Drawable.getAlpha() 恒 255 →
     *  原 spec/48 读 drawable.alpha 永远=1 → 黑 tint 无过渡；系统真实透明度走 blurConfig 颜色 alpha，
     *  mViewAlpha 是逐帧动画值。
     *  [加固 2026-08-13] 初始 1f（保守兜底：hook 挂载失败/未捕获时宁画不丢，防遮罩恒 0 消失；
     *  收起时 scrim 不绘制故无副作用，hook 捕获真实值后覆盖）。主路径 drawScrimBlackTint 已改
     *  优先反射读 ScrimView.mViewAlpha 字段（不依赖 hook），本字段供兜底路径/反射失败 fallback。 */
    @Volatile
    private var behindScrimAlpha = 1f
    /** [2026-08-14 同步修复] 系统 behind_scrim mViewAlpha 峰值（学习值）：真机实测 ColorOS 16 展开完全时
     *  scrimAlpha≈0.15（系统 behind_scrim 本来就是浅半透明，非 1）。归一化用：progress = scrimAlpha / scrimPeak
     *  → 空隙遮罩 alpha = maskAlpha × progress，动画保留（系统渐入曲线）且终值 = maskAlpha（与玻璃 shader 同步）。
     *  初始 0.15（实测），运行中自适应修正（每次出现更高值更新）。 */
    @Volatile
    private var scrimPeak = 0.15f

    // ---- [spec/47 弹出通知文字跟随整体颜色] 状态（install 时读 Prefs 缓存；改动需重启 SystemUI 生效） ----
    /** heads-up 文字跟随整体颜色独立开关（KEY_HEADS_UP_TEXT_FOLLOW；**与遮罩开关无关**）。 */
    @Volatile
    private var headsUpTextFollowEnabled = Prefs.DEFAULT_HEADS_UP_TEXT_FOLLOW

    /**
     * [spec/41 遮罩整个下拉面板统一] 返回该宿主应叠加的黑遮罩不透明度（0=不叠加）。
     * - **SystemUI 下拉面板所有玻璃元素统一取值**：不再按宿主 View 类名逐元素判定
     *   （spec/35 的 MASK_HOST_KEYWORDS 卡片判定已废弃）——通知卡/媒体卡/QS/seekbar/胶囊等
     *   全部压暗背景采样色，整个 shade 背景统一变暗，玻璃统一透过整体遮罩采底层。
     * - 本函数仅在 BlurDrawHook（SystemUI 进程）内调用；Launcher 进程走 LauncherHook，
     *   其 setUniforms 不传 maskAlpha（默认 0）→ 文件夹背景无遮罩，天然隔离。
     * - 遮罩开关/不透明度在 install 时缓存（改动需重启生效），O(1) 返回配置值。
     * - hostViewName 参数保留仅为兼容 3 处调用点签名（computeGeometry / buildLiquidShader
     *   ×2），不再参与判定。
     */
    private fun maskAlphaFor(hostViewName: String?): Float {
        if (!maskEnabled || maskAlpha <= 0f) return 0f
        return maskAlpha
    }

    /**
     * 每个 drawable（identityHashCode）一个液态玻璃 RuntimeShader 实例（防 uniform 串扰）。
     * 普通 Map：不做 LRU 淘汰（任务 H2：性能优化已删除，先保效果）。
     */
    private val shaderCache = ConcurrentHashMap<Int, RuntimeShader>()

    /** 降采样(1/4)原图缓存：按全分辨率源位图 identityHashCode 复用（防每 drawable 重复缩放） */
    @Volatile
    private var lowResBitmapCache: Bitmap? = null

    @Volatile
    private var lowResBitmapSourceId: Int = 0

    /** drawable(identityHashCode) → 屏幕区域 + 局部 bounds（AutoBlurDrawable.draw hook 建立，bounds 供兜底匹配） */
    private val screenRegionMap = ConcurrentHashMap<Int, RegionEntry>()

    /** [spec/38 2026-08-13] 背景 scrim 的 posteffect drawable identityHashCode 集合。
     *  scrim（ScrimView）恒透明不渲染玻璃 → **不写入 screenRegionMap**（全屏 region 令
     *  isOverGlassRegion 对状态栏时钟等一切坐标判"玻璃区域" → 文字变色误伤）；但渲染兜底
     *  isBackgroundScrimById（drawBlurShader 直绘路径 paint 置透明）仍需识别 scrim → 独立登记。 */
    private val scrimDrawableIds = ConcurrentHashMap.newKeySet<Int>()

    /**
     * 已应用到各 drawable shader 的 uniform 状态（R1：变化检测，避免幂等短路冻结 uniform）。
     * 普通 Map：不做 LRU 淘汰（任务 H2：性能优化已删除）。
     */
    private val appliedUniformsMap = ConcurrentHashMap<Int, AppliedKey>()

    /**
     * 任务 G（问题 13）：已登记屏幕区域映射的宿主 View（WeakReference 防泄漏）。
     * 运动事件源（QS 翻页 onPageScrolled / 面板展开 setExpansionHeight）触发时逐个 invalidate()，
     * 强制宿主 View 重录 display list → AutoBlurDrawable.draw 重跑 → screenRegionMap 更新为运动后
     * 新位置 → drawBlurShader 重跑 → 玻璃跟随。Root cause：控制中心滑动/翻页走容器 RenderNode
     * offset/translation，子 View display list 不重录，映射一直停在滑动前旧坐标 → srcRect 冻结。
     * 必须 invalidate 宿主 View 本身（ViewGroup.invalidate 不重录子节点 display list）。
     */
    private class HostEntry(view: View) {
        val ref = WeakReference(view)
    }

    private val registeredHostViews = LinkedHashSet<HostEntry>()

    // ---- [方案 B] NC↔CC 切换 / 锁屏展开：容器 View 缩放变换快照 ----

    /**
     * 容器 View 当前变换快照。
     * 字段全 @Volatile：主线程 setScaleX/setScaleY/setTranslationX/setTranslationY hook 写，
     * 渲染线程（draw hook → resolveRenderSource 折算 srcRect 前）读。
     * pivot 为**屏幕坐标**（hook 内 getLocationOnScreen(容器) + pivotX/Y 折算）：
     * region 已含容器 translation（getLocationOnScreen 累加 left+translation），故正向变换
     * R_drawn = s·R + (1-s)·pivotScreen 不需要单独平移项。
     */
    private class ContainerTransform(
        @Volatile var scaleX: Float = 1f,
        @Volatile var scaleY: Float = 1f,
        @Volatile var pivotScreenX: Float = 0f,
        @Volatile var pivotScreenY: Float = 0f,
    )

    /** 容器类列表（mountContainerTransformHooks 里 Class.forName 解析，安装期一次性填充、主线程读，
     *  hook 触发前已完成 → 无并发问题）。供 recordContainerTransform 按运行时类型过滤：3 个容器类均
     *  未 override setScaleX/setScaleY/setTranslationX/Y（继承 View），getMethod 解析到 View 继承方法
     *  → hook 落在**全局所有 View** 的 setScaleX 调用，必须过滤只处理容器实例（[2026-08-13 二次修复]）。 */
    private val containerTransformClasses = mutableListOf<Class<*>>()

    /** 容器 View identityHashCode → 变换快照。并发容器：hook（主线程）写，渲染线程读。
     *  容器 = NotificationPanelView / OplusQSRootView / NotificationStackScrollLayout，长生命周期，
     *  数量极小（≤3）；防膨胀上限 16，超出清空（下个 hook 调用自动重建）。
     *  [2026-08-13 二次修复] 有容器类过滤后只有容器写入（≤3），size 不会暴涨，clear 不会误清容器条目，
     *  仅作泄漏兜底保留。 */
    private val containerTransforms = ConcurrentHashMap<Int, ContainerTransform>()

    // [2026-09-17 追踪器整体移除] 通用运动追踪器（Choreographer 逐帧比对宿主屏幕位置/尺寸快照）已删除：
    // 真机实测关闭该开关后表现无差异（区域跟随已由 setTranslation / onPageScrolled / setExpansionHeight
    // 等事件源 hook 覆盖），逐帧 getLocationOnScreen 遍历属纯空转。
    // 其内嵌的 glassRetryPending 回退重试已迁到低频 Handler 循环，见 retryInvalidateRunnable。

    // ---- [doc/spec/33 持续抓屏 2026-08-13] 活跃玻璃宿主期间周期性整屏快照（动态背景实时刷新） ----
    /** 周期抓屏 Runnable 是否已 post（防重复 postDelayed；宿主全灭/功能关闭时停置 false） */
    @Volatile
    private var periodicCapturePosted = false

    // ---- 回退恢复（症状 1/2：冷启动/无映射回退系统模糊后，新原图到达自动重试玻璃） ----
    /** 有 drawable 因无原图/无映射回退系统模糊，等新帧到达后 invalidate 重试 */
    @Volatile
    private var glassRetryPending = false

    /** 回退恢复 invalidate 节流（同一批 onBlurReady 逐 drawable 回调 20+ 次，防反馈风暴） */
    private var lastRetryInvalidateAt = 0L

    /** [回退恢复②·退避] 重试当前间隔（250ms 起步指数退避到 4s 封顶）。防永久 map miss 的 drawable
     *  造成恒定高频 invalidate。[2026-09-17] 驱动方由逐帧追踪器改为 Handler 循环，退避语义不变。 */
    @Volatile
    private var retryIntervalMs = 250L

    /** [2026-09-17 追踪器移除] 回退重试 Runnable 是否已排（幂等防重复 postDelayed） */
    @Volatile
    private var retryLoopPosted = false

    /** 是否已成功替换过（首帧日志用） */
    @Volatile
    private var replacedOnce = false

    // ---- R3：screen-region map 命中率观测（节流日志，见 lookupScreenRegion） ----
    private val mapLookupCount = java.util.concurrent.atomic.AtomicLong(0L)
    private val mapHitCount = java.util.concurrent.atomic.AtomicLong(0L)

    @Volatile
    private var lastHitRateLoggedLookups = 0L
    /** [2026-08-13 日志节流] 命中率日志上次实际打印时间（uptimeMillis；计数节流之外加时间闸，
     *  ~30 宿主×60fps ≈ 1800 lookup/s，仅按 256 次节流仍 ~7 条/s，加 2s 时间闸降至 ~0.5 条/s） */
    @Volatile
    private var lastHitRateLoggedAt = 0L

    /** R1：refresh 重设 uniform 总次数（coord: refresh 节流日志用） */
    @Volatile
    private var refreshUniformCount = 0L
    /** [2026-08-13 日志节流] coord: refresh 日志上次实际打印时间（uptimeMillis；64 次计数之外加 2s 时间闸，
     *  滑动时多元素每帧重设 uniform → 纯计数节流仍可每秒数十条，加时间闸降至 ~0.5 条/s） */
    @Volatile
    private var lastRefreshCoordLogAt = 0L

    // ---- [2026-08-13 日志节流] 热路径高频日志降噪（静止近零、高频聚合，见 logThrottled）----
    /** 热路径日志节流间隔（毫秒）：同一 key 距上次实际打印 ≥ 此值才打；静止零日志，高频事件聚合到每 2s 一条 */
    private const val THROTTLE_LOG_INTERVAL_MS = 2000L

    /** 日志节流 lastTime 表（key → 上次实际打印的 uptimeMillis；>256 清空防膨胀） */
    private val logThrottleLastTimes = ConcurrentHashMap<String, Long>()

    /** [2026-08-13 用户要求·关闭日志选项] 模块日志总开关：install 时读 Prefs.KEY_ENABLE_LOGS 缓存。
     *  false → 热路径/渲染诊断日志静默（logThrottled / logRenderMode / coord 诊断均不走），
     *  保留挂载一次性日志与 Log.e 错误日志（错误诊断仍可用）。改动需重启 SystemUI 生效。 */
    @Volatile
    private var moduleLogEnabled = false

    // ---- [spec/44 流体云展开卡片玻璃化 2026-08-13] install 时读取并缓存（改动需重启 SystemUI 生效） ----
    /** 流体云展开卡片玻璃化总开关（Prefs.KEY_SEEDLING_CARD_GLASS_ENABLE，默认 false）。 */
    @Volatile
    private var seedlingCardGlassEnabled = false
    /** 展开大卡片判定高度系数（Prefs.KEY_SEEDLING_CARD_EXPAND_HEIGHT_RATIO，默认 0.6f：
     *  容器 AT_MOST 钳制子 View=容器高，原 1.2 阈值超容器高恒不命中，2026-08-15 修复）。 */
    @Volatile
    private var seedlingCardExpandHeightRatio = 0.6f
    /** 展开大卡片圆角启发式系数（Prefs.KEY_SEEDLING_CARD_CORNER_RATIO，默认 0.22f）。 */
    @Volatile
    private var seedlingCardCornerRatio = 0.22f
    /** 强制透明大卡片子 View background（Prefs.KEY_SEEDLING_CARD_FORCE_TRANSPARENT_BG，默认 true：
     *  [2026-08-15 语义变更] 背景透明为用户硬需求，默认开；install 读取时按 Prefs.DEFAULT 兜底）。 */
    @Volatile
    private var seedlingForceTransparentBg = false
    /** [2026-09-16 用户需求变更] 小胶囊（小岛）是否也玻璃化
     *  （Prefs.KEY_SEEDLING_CAPSULE_GLASS，默认 true）。 */
    @Volatile
    private var seedlingCapsuleGlassEnabled = true
    /** [2026-09-16] 上次渲染的小岛尺寸（仅用于尺寸变化去重，避免重复 register） */
    private var lastIslandW = -1
    private var lastIslandH = -1

    /** dispatchDraw hook 是否已挂（install 幂等） */
    private var seedlingDispatchDrawMounted = false
    /** 液态玻璃 canvas 绘制单例 Paint（避免每帧 new Paint） */
    private val seedlingGlassPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // ---- [spec/44b 流体云展开卡片玻璃化 2026-08-15] OplusCustomRow 宿主侧 hook 字段 ----
    /** NotificationBackgroundView.mBackground 字段（背景透明反射替换，resolveMethods 解析；null=反射失败跳过透明） */
    private var mNbvBackgroundField: Field? = null
    /** OplusCustomRow 玻璃 hook 是否已挂（install 幂等） */
    private var oplusRowGlassMounted = false
    /** [诊断] dispatchDraw 命中 OplusCustomRow 计数（节流日志：每 120 帧打一条，防刷屏） */
    private var oplusRowDispatchCount = 0
    // ---- [spec/44c 2026-08-15] CardBackgroundView 宿主侧 hook 字段 ----
    /** CardBackgroundView 玻璃 hook 是否已挂（install 幂等） */
    private var cardBgGlassMounted = false
    /** [诊断] CardBackgroundView.onDraw 命中计数（节流日志：每 120 帧打一条，防刷屏） */
    private var cardBgDrawCount = 0
    /** [spec/44c 2026-08-15] 流体云展开大卡圆角 px（round_corner_radius_fluid_cloud 资源 dimen，
     *  SystemUIPlugin.apk 资源，包 com.oplus.systemui.plugins；全库 9 处共用同一 dimen，不随状态变化）。
     *  一次解析并缓存（避免每帧 getIdentifier）；0=未解析。 */
    private var roundCornerFluidCloudPx = 0f
    /** [2026-08-15 持续抓屏对齐 heads-up] CardBackgroundView 活跃宿主（持续抓屏驱动；onDraw 命中时登记，
     *  卡片收起/窗口销毁自动清） */
    private var cardBackgroundActiveHost: WeakReference<View>? = null
    /** CardBackgroundView 持续抓屏 worker 运行标志（幂等） */
    private var cardBackgroundContinuousRunning = false
    // ---- [spec/61 2026-08-23] 流体云展开大卡 attach 强制启动持续抓屏 + 系统控制中心模糊力度 ----
    /** CardBackgroundView attach hook 是否已挂（install 幂等） */
    private var cardBgAttachMounted = false
    /** [2026-09-16 修复力度累积衰减] 面板背景 BlurConfig 的「系统原始模糊半径」缓存。
     *  key = System.identityHashCode(BlurConfig)，value = 首次见到的 blurRadius（= 系统原值）。
     *  **力度改写必须以原值为基准**：本 hook 每次调用都会重写 blurConfig.blurRadius，若拿「当前值」
     *  当基准，每调用一次就再乘一次比例 → strength<100 时半径指数衰减（几次调用归零，真机表现
     *  「要么透明、要么 100% 模糊，中间档位无效」）。改为原值基准后改写幂等，力度回 100 可精确复位。
     *  BlurConfig 由 ScrimControllerExImp.refreshBehindDrawable 重建 → 新 identityHashCode → 自动重记基准。 */
    private val ccBlurBaseRadius = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    /** BlurConfig.blurRadius public 字段（模糊力度调整反射写） */
    private var mBlurConfigBlurRadiusField: Field? = null
    /** [spec/61 修正] ViewBlurProxy.view 字段（面板背景 ScrimView 判定） */
    private var mViewBlurProxyViewField: Field? = null
    /** [spec/61 修正] ViewBlurProxy.blurConfig 字段（力度修改对象） */
    private var mViewBlurProxyBlurConfigField: Field? = null
    /** 系统控制中心模糊力度缓存（%）：100=系统原值；IPC 节流缓存同 CAPTURE_PREFS_CACHE_MS，改动 ≤1s 生效 */
    @Volatile
    private var cachedCcBlurStrength = Prefs.DEFAULT_CC_BLUR_STRENGTH
    @Volatile
    private var cachedCcBlurStrengthTimeMs = 0L
    /** 保留系统控制中心模糊开关缓存（默认 true） */
    @Volatile
    private var cachedKeepCcBlur = Prefs.DEFAULT_KEEP_SYSTEM_CC_BLUR
    @Volatile
    private var cachedKeepCcBlurTimeMs = 0L
    /** [2026-09-16 面板材质底色] 移除面板材质底色开关缓存（默认 true） */
    @Volatile
    private var cachedRemoveMixColor = Prefs.DEFAULT_REMOVE_PANEL_MIX_COLOR
    @Volatile
    private var cachedRemoveMixColorTimeMs = 0L
    /** [2026-09-16 面板材质底色] BlurConfig.get/setPlatformMixConfig（替换为 None 用） */
    private var mBlurConfigGetPlatformMixConfig: Method? = null
    private var mBlurConfigSetPlatformMixConfig: Method? = null
    /** [2026-09-16 面板材质底色] BlurMixConfig.None.INSTANCE 单例（**只读不写**，避免污染系统其它使用点） */
    private var mBlurMixNoneSingleton: Any? = null
    // ---- [2026-08-15 轻打扰折叠横幅玻璃化 方案 B] Simple Banner 宿主 hook 字段 ----
    /** Simple Banner glass hook 是否已挂（install 幂等） */
    private var simpleBannerGlassMounted = false
    /** [诊断] FullScreenBanner.dispatchDraw 命中计数（节流日志：每 120 帧打一条，防刷屏） */
    private var simpleBannerDispatchCount = 0
    /** Simple Banner 活跃宿主（持续抓屏驱动；dispatchDraw 命中时登记，横幅消失自动清） */
    private var simpleBannerActiveHost: WeakReference<View>? = null
    /** Simple Banner 持续抓屏 worker 运行标志（幂等） */
    private var simpleBannerContinuousRunning = false
    /** [2026-08-15 强制刷新] View.onAttachedToWindow hook 是否已挂（横幅一 attach 即强制启动持续抓屏） */
    private var simpleBannerAttachMounted = false

    // ---- 渲染模式日志（大幅简化：核心突出每个 drawable 的最终渲染结果）----
    // 每条 `render: <id> GLASS|BLUR` 表示该 drawable 最终渲染模式：
    //   GLASS = 液态玻璃 shader 替换成功并渲染（清晰）；BLUR = 回退系统模糊（磨砂）。
    // 节流：GLASS 只在实际模式切换（GLASS↔BLUR）时打一条，steady-state 完全静默，防刷屏；
    // BLUR 除切换/首态立即打外，持续处于 BLUR（无切换）时每 ~2s 重打一次（带 id + 原因），
    // 暴露持久磨砂的 drawable——"切换才打"会让持久 BLUR 静默，无法定位 14% 未命中对象。
    private const val BLUR_REPEAT_LOG_MS = 2000L

    private val lastRenderModeMap = ConcurrentHashMap<Int, String>()
    /** 每个 id 上次打 BLUR 日志的时间（uptimeMillis）；BLUR 持久时按 BLUR_REPEAT_LOG_MS 重打 */
    private val lastBlurLogTimeMap = ConcurrentHashMap<Int, Long>()

    /**
     * 渲染模式日志。BLUR 时附带定位信息（view 类名 + bounds），定位持久磨砂 drawable 是什么控件：
     * - view=：screenRegionMap 已有该 drawable 映射 → 打宿主 view 类名（RegionEntry.hostViewName）；
     * - 否则 drawable=：drawable 自身类名（持久 map miss 的控件未建立映射，用类名 + bounds 反查控件）；
     * - bounds=：drawable getBounds（元素自身尺寸）。GLASS 保持原样（id 即可，不附带防刷屏）。
     */
    private fun logRenderMode(id: Int, mode: String, reason: String? = null, drawable: Any? = null, bounds: Rect? = null) {
        if (!moduleLogEnabled) return
        val prev = lastRenderModeMap[id]
        val extra = if (mode == "BLUR") buildBlurInfo(id, drawable, bounds) else ""
        if (prev != mode) {
            // 模式切换（或首次）→ 立即打
            lastRenderModeMap[id] = mode
            if (mode == "BLUR") lastBlurLogTimeMap[id] = SystemClock.uptimeMillis()
            if (lastRenderModeMap.size > 512) {
                lastRenderModeMap.clear()
                lastBlurLogTimeMap.clear()
            }
            Log.i(TAG, "render: $id $mode" + (reason?.let { " ($it)" } ?: "") + extra)
            return
        }
        // 稳态：GLASS 静默（切换才打，不刷屏）；BLUR 每 ~2s 重打一次，暴露持久磨砂 drawable
        if (mode != "BLUR") return
        val now = SystemClock.uptimeMillis()
        if (now - (lastBlurLogTimeMap[id] ?: 0L) < BLUR_REPEAT_LOG_MS) return
        lastBlurLogTimeMap[id] = now
        Log.i(TAG, "render: $id $mode" + (reason?.let { " ($it)" } ?: "") + extra)
    }

    /** BLUR 定位信息：优先 screenRegionMap 已映射的宿主 view 类名，miss 则 drawable 类名 + bounds。 */
    private fun buildBlurInfo(id: Int, drawable: Any?, bounds: Rect?): String {
        val sb = StringBuilder()
        val entry = screenRegionMap[id]
        if (entry != null) {
            sb.append(" view=").append(entry.hostViewName)
        } else if (drawable != null) {
            sb.append(" drawable=").append(drawable.javaClass.simpleName)
        }
        if (bounds != null) {
            sb.append(" bounds=Rect(")
                .append(bounds.left).append(',').append(bounds.top).append('-')
                .append(bounds.right).append(',').append(bounds.bottom).append(')')
        }
        return sb.toString()
    }

    // ---- [spec/55 诊断日志 2026-08-14] hook/抓屏链路时间戳串联（仅诊断，不改逻辑）----
    /** hook 触发诊断日志（setExpansionHeight 每次回调 / onExpansionStarted）。距上次 hook 日志超
     *  [DIAG_CHAIN_RESET_MS] → 重置链路锚点（每次下拉/通知事件独立对比）。expansion 附带展开值；
     *  [spec/59 追加 2026-08-14] state 附带关键状态（panelWasCollapsed 分支判定前值，确认首帧状态机）。 */
    private fun diagHookLog(source: String, expansion: Float? = null, state: String? = null) {
        if (!moduleLogEnabled) return
        val now = SystemClock.uptimeMillis()
        if (diagChainStartMs == 0L || now - diagChainStartMs > DIAG_CHAIN_RESET_MS) {
            diagChainStartMs = now  // 新链路：锚点 = 本次 hook 触发
        }
        val extra = buildString {
            expansion?.let { append(" exp=$it") }
            state?.let { append(" $it") }
        }
        diagEmit(DIAG_TAG_HOOK, source, extra)
    }

    /** 抓屏链路诊断日志（入队 / captureDisplay 开始 / 完成）。sinceAnchor=距本次 hook 触发毫秒（核心对比指标） */
    private fun diagCapLog(point: String, extra: String = "") {
        diagEmit(DIAG_TAG_CAP, point, extra)
    }

    /** [spec/58 追加 2026-08-15] 主线程首帧同步链路诊断日志（lg-first）：只对下拉/通知出现瞬间的
     *  **主线程同步首帧路径**打（source 以 `-main-thread` 结尾 / 主线程 Looper 判定），定位
     *  「触发 → sfc → captureDisplay → 缓冲区替换」每段耗时竞态窗口。复用 [diagEmit]（时间戳 +
     *  sinceAnchor + sinceLast 格式与 lg-hook/lg-cap 一致）；worker 异步路径不打（防刷屏）。 */
    private fun diagFirstLog(point: String, extra: String = "") {
        diagEmit(DIAG_TAG_FIRST, point, extra)
    }

    /** [spec/59 追加 2026-08-14] 抓屏 worker 生命周期诊断日志（lg-wk）：worker 停摆/恢复/孤儿/慢 captureDisplay
     *  的每次事件点（低频，非每帧）。与 lg-first 同格式（sinceAnchor/sinceLast）；moduleLogEnabled=false 静默。 */
    private fun diagWorkerLog(point: String, extra: String = "") {
        diagEmit(DIAG_TAG_WK, point, extra)
    }

    /** [spec/58 追加 2026-08-15] 当前线程是否主线程（Looper 判定，零 Binder/零分配）：主线程首帧同步路径
     *  日志门控用（worker 线程调用 lg-first 辅助不打，防刷屏）。 */
    private fun isMainThreadNow(): Boolean =
        android.os.Looper.myLooper() === android.os.Looper.getMainLooper()

    /** 诊断日志核心：时间戳 + 距链路锚点 + 距上次同点间隔。Log.d 级别（logcat 默认隐藏，过滤 tag 可见）；
     *  moduleLogEnabled=false 时静默（与模块日志总开关一致）。 */
    private fun diagEmit(tag: String, point: String, extra: String) {
        if (!moduleLogEnabled) return
        val now = SystemClock.uptimeMillis()
        val sinceAnchor = if (diagChainStartMs > 0L) now - diagChainStartMs else 0L
        // key 带 tag 前缀防跨 tag 同名 point（lg-cap/lg-first 的 captureDisplay-start/ok 等）互相干扰
        // sinceLast 基准；同 tag 内 point 唯一性不变，输出格式不变。
        val key = "$tag|$point"
        val last = diagLastTimes[key]
        val sinceLast = if (last != null) now - last else -1L
        diagLastTimes[key] = now
        Log.d(tag, "$point up=${now}ms sinceAnchor=${sinceAnchor}ms sinceLast=${sinceLast}ms$extra")
    }

    /**
     * [2026-08-13 日志节流] 热路径日志节流包装：同 key 距上次实际打印 >= intervalMs 才调用 Log。
     * msg 延迟构造（未超时直接跳过，连字符串都不拼）——热路径高频日志（scrim 每帧 skip、映射写入
     * 每帧 updated、抓屏每次 ok/captured 等）降为每 intervalMs 一条，静止零日志。
     * ERROR 恒打不节流（错误必须保留）；DEBUG 级同样节流（避免每帧拼字符串的 CPU 开销）。
     * 保留诊断能力（超时后照常打印），只降频不删日志结构。
     *
     * inline：msg lambda 内联到调用点，热路径零 lambda 对象分配；未超时时字符串表达式根本不执行。
     */
    private inline fun logThrottled(
        key: String,
        level: Int,
        intervalMs: Long = THROTTLE_LOG_INTERVAL_MS,
        msg: () -> String,
    ) {
        // [2026-08-13 用户要求·关闭日志选项] 模块日志关闭 → 全部节流日志静默
        if (moduleLogEnabled) {
            if (level != Log.ERROR) {
                val now = SystemClock.uptimeMillis()
                val last = logThrottleLastTimes[key]
                if (last == null || now - last >= intervalMs) {
                    logThrottleLastTimes[key] = now
                    if (logThrottleLastTimes.size > 256) logThrottleLastTimes.clear()
                    val text = msg()
                    when (level) {
                        Log.WARN -> Log.w(TAG, text)
                        Log.DEBUG -> Log.d(TAG, text)
                        else -> Log.i(TAG, text)
                    }
                }
            } else {
                Log.e(TAG, msg())
            }
        }
    }

    // [coord 降级] 详细坐标只在映射/区域实际变化时打（tracker-driven 重录每帧回调不刷屏）
    private val lastCoordRenderKey = ConcurrentHashMap<Int, String>()
    private val lastLoggedCornerRadius = ConcurrentHashMap<Int, Float>()

    fun install(api: XposedInterface, classLoader: ClassLoader) {
        this.api = api
        // [2026-08-13 用户要求·关闭日志选项] install 时读取并缓存（热路径不每帧读 Prefs 防 binder 开销）
        moduleLogEnabled = try {
            Prefs.read(api).getBoolean(Prefs.KEY_ENABLE_LOGS, Prefs.DEFAULT_ENABLE_LOGS)
        } catch (t: Throwable) {
            // [2026-08-26 默认关日志] 读 Prefs 失败兜底为 false（与 Prefs.DEFAULT_ENABLE_LOGS=false 语义一致，
            // 避免兜底 true 意外开启刷屏日志）。
            false
        }
        // [spec/21 文字反转取色] install 时读取并缓存；false 时不挂 QsColorUtil/TextView hook（走系统原逻辑）。
        // 改动需重启 SystemUI 生效（同 KEY_ENABLE_LOGS 模式）。
        textContrastEnabled = try {
            Prefs.read(api).getBoolean(Prefs.KEY_TEXT_CONTRAST_ENABLE, Prefs.DEFAULT_TEXT_CONTRAST_ENABLE)
        } catch (t: Throwable) {
            Prefs.DEFAULT_TEXT_CONTRAST_ENABLE
        }
        // [spec/35 黑遮罩 + 白字方案] install 时读取并缓存（同 KEY_ENABLE_LOGS 模式，改动需重启生效）。
        maskEnabled = try {
            Prefs.read(api).getBoolean(Prefs.KEY_MASK_ENABLE, Prefs.DEFAULT_MASK_ENABLE)
        } catch (t: Throwable) {
            Prefs.DEFAULT_MASK_ENABLE
        }
        maskAlpha = try {
            Prefs.read(api).getFloat(Prefs.KEY_MASK_ALPHA, Prefs.DEFAULT_MASK_ALPHA).coerceIn(0f, 1f)
        } catch (t: Throwable) {
            Prefs.DEFAULT_MASK_ALPHA
        }
        maskTextColor = try {
            Prefs.read(api).getInt(Prefs.KEY_MASK_TEXT_COLOR, Prefs.DEFAULT_MASK_TEXT_COLOR)
        } catch (t: Throwable) {
            Prefs.DEFAULT_MASK_TEXT_COLOR
        }
        // [spec/63 文字可读性统一注入通道] install 时读取并缓存（同 KEY_MASK_ENABLE 模式，改动需重启生效）。
        textForceWhiteEnabled = try {
            Prefs.read(api).getBoolean(Prefs.KEY_TEXT_FORCE_WHITE_ENABLE, Prefs.DEFAULT_TEXT_FORCE_WHITE_ENABLE)
        } catch (t: Throwable) {
            Prefs.DEFAULT_TEXT_FORCE_WHITE_ENABLE
        }
        // [spec/47 弹出通知文字跟随整体颜色] install 时读取并缓存（同 KEY_MASK_ENABLE 模式，改动需重启生效）。
        headsUpTextFollowEnabled = try {
            Prefs.read(api).getBoolean(Prefs.KEY_HEADS_UP_TEXT_FOLLOW, Prefs.DEFAULT_HEADS_UP_TEXT_FOLLOW)
        } catch (t: Throwable) {
            Prefs.DEFAULT_HEADS_UP_TEXT_FOLLOW
        }
        // [spec/44 流体云展开卡片玻璃化] install 时读取并缓存（同 KEY_ENABLE_LOGS 模式，改动需重启生效）。
        seedlingCardGlassEnabled = try {
            Prefs.read(api).getBoolean(Prefs.KEY_SEEDLING_CARD_GLASS_ENABLE, Prefs.DEFAULT_SEEDLING_CARD_GLASS_ENABLE)
        } catch (t: Throwable) {
            Prefs.DEFAULT_SEEDLING_CARD_GLASS_ENABLE
        }
        seedlingCardExpandHeightRatio = try {
            Prefs.read(api).getFloat(Prefs.KEY_SEEDLING_CARD_EXPAND_HEIGHT_RATIO, Prefs.DEFAULT_SEEDLING_CARD_EXPAND_HEIGHT_RATIO)
        } catch (t: Throwable) {
            Prefs.DEFAULT_SEEDLING_CARD_EXPAND_HEIGHT_RATIO
        }.coerceAtLeast(1.01f)
        seedlingCardCornerRatio = try {
            Prefs.read(api).getFloat(Prefs.KEY_SEEDLING_CARD_CORNER_RATIO, Prefs.DEFAULT_SEEDLING_CARD_CORNER_RATIO)
        } catch (t: Throwable) {
            Prefs.DEFAULT_SEEDLING_CARD_CORNER_RATIO
        }.coerceIn(0.01f, 0.5f)
        seedlingForceTransparentBg = try {
            Prefs.read(api).getBoolean(Prefs.KEY_SEEDLING_CARD_FORCE_TRANSPARENT_BG, Prefs.DEFAULT_SEEDLING_CARD_FORCE_TRANSPARENT_BG)
        } catch (t: Throwable) {
            Prefs.DEFAULT_SEEDLING_CARD_FORCE_TRANSPARENT_BG
        }
        seedlingCapsuleGlassEnabled = try {
            Prefs.read(api).getBoolean(Prefs.KEY_SEEDLING_CAPSULE_GLASS, Prefs.DEFAULT_SEEDLING_CAPSULE_GLASS)
        } catch (t: Throwable) {
            Prefs.DEFAULT_SEEDLING_CAPSULE_GLASS
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(TAG, "Hook B skipped: SDK=${Build.VERSION.SDK_INT} < Q")
            return
        }
        resolveMethods(classLoader)
        mountAutoBlurDrawable(api, classLoader)
        // [spec/48 过渡修复 2026-08-13] hook ScrimView.setViewAlpha 捕获 behind_scrim 真实动画 alpha
        //（AutoBlurDrawable.setAlpha 空实现恒 255 → 原黑 tint 读 drawable.alpha 永远=1 无过渡）。
        mountScrimViewAlpha(api, classLoader)
        mountLightStyleStrokeDrawable(api, classLoader)
        mountMaskBlurDrawable(api, classLoader)
        mountCapsuleBackground(api, classLoader)
        mountCapsuleEar(api, classLoader)
        mountMediaCarousel(api, classLoader)
        mountNotifHeaderMaskColor(api, classLoader)
        mountMetaColorSuppress(api, classLoader)
        mountMixColorTileRegion(api, classLoader)
        mountNotificationBackground(api, classLoader)
        mountPlatformBlurMaskColor(api, classLoader)
        mountOnBlurReady(api, classLoader)
        mountAddBlurDrawable(api, classLoader)
        mountDrawBlurShader(api, classLoader)
        mountOnDrawContent(api, classLoader)
        mountMotionSources(api, classLoader)
        // [2026-08-15 偶发不抓屏根治] hook NotificationShadeWindowView.onAttachedToWindow/onDetachedFromWindow：
        // shade 窗口（重建后）重挂载时立即刷新 shadeRootViewCache（始终指向当前 attach 的 shade 根），
        // 根治「窗口重建后缓存失效 + 新根未登记 → resolveShadeSfc 三来源全空 → 一阵子不抓屏」。
        mountShadeRootAttach(api, classLoader)
        mountContainerTransformHooks(api, classLoader)
        // [spec/19 Heads-Up 玻璃化] hook ViewBlurProxy.setBlurType：heads-up 背景 Motion → PlatformStatic
        mountViewBlurProxySetBlurType(api, classLoader)
        // [spec/19 修复 2026-08-13] 纯白根因：系统 excludeRules = headsUpWindow.isHeadsUpView(view) 阻止
        // PlatformBlurHelper 创建 PlatformBlurDrawable。scoped 放行（仅强制玻璃化的 bgView 返回 false）。
        mountHeadsUpContainerIsHeadsUpView(api, classLoader)
        // [spec/44 流体云玻璃化] hook Seedling 宿主容器 dispatchDraw 注入玻璃（SeedlingPlugin 插件渲染，
        // 不走 posteffect）。
        // [2026-09-16 恢复] 原 2026-08-15 停用，理由是"展开大卡片不在本容器"——那条**仍然成立**
        // （展开大卡走 [mountCardBackgroundGlass] 的 CardBackgroundView.onDraw）。但用户新需求是
        // **小胶囊（小岛）玻璃化**，而小岛确实在本容器内。真机系统日志实证：
        //   `CapsulePluginContainerController-->location: HOME, screenWidth: 1440, capsuleWith: 432,
        //    statusBarHeight: 160, rect: Rect(504, 0 - 936, 160)`，容器 1440x160。
        // 两条路径互不干扰：大卡片走 onDraw（CardBackgroundView），小岛走 dispatchDraw（容器子 View）。
        // 注意容器宽 = 全屏宽，若子 View 尺寸异常（撑满容器）会把状态栏画满——渲染分支带尺寸日志，
        // 真机异常时按日志收紧判定。
        mountSeedlingCardContainer(api, classLoader)
        // [spec/44b 2026-08-15 OplusCustomRow 宿主侧方向 · 已停用] 真机实证上错位置：展开流体云大卡的
        // 真实 View 是 SystemUIPlugin.apk 的 CardContainer → CardView → CardBackgroundView（反编译实锤）；
        // OplusCustomRow 是锁屏流体云宿主（锁屏走 com.oplus.seedling.pluginapp 不同包），本 hook 命中
        // 锁屏（用户反馈"上错位置上到锁屏""遮住文字"）。整套函数保留注释停用，见文件 spec/44b 停用区。
        // mountOplusCustomRowGlass(api, classLoader)
        // [spec/44c 2026-08-15 宿主侧 CardBackgroundView 方向] hook CardBackgroundView.onDraw 画玻璃
        // （背景 blur 之上、内容层之下，不遮文字）。总开关沿用 KEY_SEEDLING_CARD_GLASS_ENABLE（默认 false）。
        mountCardBackgroundGlass(api, classLoader)
        // [2026-08-15 轻打扰折叠横幅玻璃化 方案 B] hook FullScreenBanner.dispatchDraw 画玻璃
        // （BlurProxy 替换路线已撤销——强制 PlatformStatic 后 ensurePlatformStaticBlurDrawable 失败背景
        // 变白；改 dispatchDraw 玻璃盖在系统 Motion 模糊背景之上、内容之下不遮文字）。
        // 总开关沿用 KEY_SEEDLING_CARD_GLASS_ENABLE（同 CardBackgroundView）。
        mountSimpleBannerGlass(api, classLoader)
        // [2026-08-15 强制刷新] 横幅一 attach 窗口即强制启动持续抓屏（比 dispatchDraw 绘制命中可靠：
        // attach 是必然事件、不受绘制频率影响；绘制稳定后 dispatchDraw 可能不再触发 → worker 永不启动）。
        mountSimpleBannerAttachHook(api, classLoader)
        // [spec/61 2026-08-23] 流体云展开大卡 attach 强制启动持续抓屏（同横幅：attach 必然事件比 onDraw 绘制命中可靠）
        mountCardBackgroundAttachHook(api, classLoader)
        // [spec/61 2026-08-23] 系统控制中心模糊力度调整 + 阻止界面缩小
        mountCcBlurHooks(api, classLoader)
        // [spec/21 文字反转取色 + spec/35 黑遮罩白字 + spec/63 统一通道] hook 挂载条件：
        // - textContrastEnabled：挂 QsColorUtil（亮度判定）+ TextView#setTextColor（反转取色）+ onDraw 节流重采样。
        // - 仅 maskEnabled / 仅 textForceWhiteEnabled（spec/63 强制白独立开关）：仍须挂 TextView#setTextColor
        //   （统一通道 resolveReadableTextColor 强制白/灰）。
        // - 三开关全关：不挂任何文字 hook。
        if (textContrastEnabled || maskEnabled || textForceWhiteEnabled) {
            if (textContrastEnabled) {
                mountQsColorUtil(api, classLoader)
                // [spec/21 修复 2026-08-13] onDraw 节流重采样：快照就绪/位置变化/attach 后重新注入（修复
                // "setTextColor 时刻快照缺失 → 永不反色"）。仅检查已登记 TextView（O(1) 快速跳过），非每帧采样。
                mountTextViewOnDraw(api, classLoader)
            } else {
                Log.i(TAG, "text-contrast disabled but mask/force-white enabled: only TextView#setTextColor mounted (force white/gray text)")
            }
            mountTextViewSetTextColor(api, classLoader)
        } else {
            Log.i(TAG, "text-contrast/mask/force-white disabled by prefs, QsColorUtil/TextView hooks skipped")
        }
        // [spec/48 遮罩走系统自带机制] 不再手动叠黑（spec/46 ScrimView.onDraw after 方案已移除）：
        // behind_scrim 黑 tint 由 AutoBlurDrawable.draw 拦截（主路径）+ drawBlurShader 兜底内绘制，
        // alpha 走 ScrimController 动画（setViewAlpha hook 捕获 → behindScrimAlpha），模块不额外驱动。
        // [spec/62 锁屏耗电优化 2026-08-26] 注册锁屏状态缓存失效广播（屏幕开/关、用户解锁 → 缓存过期
        // 强制实测），消除锁屏/解锁切换 ≤500ms 竞态窗口内门控误判。context 解析失败自动跳过（内部打日志）。
        registerKeyguardStateBroadcastReceiver()
        // [spec/60 周期强制刷新] 启动文字周期强制刷新循环（幂等去重；开关全关 / 无活跃宿主时
        // tick 轻量跳过，唯一成本是几微秒空 tick）
        ensureTextForceRefreshLoop()
    }

    // ------------------------------------------------------------ 反射解析

    private fun resolveMethods(classLoader: ClassLoader) {
        try {
            val base = Class.forName(CLASS_BASE_DRAWABLE, false, classLoader)
            mGetDrawableShaderPaint = base.getMethod(METHOD_GET_DRAWABLE_SHADER_PAINT)
            mGetDrawableShader = base.getMethod(METHOD_GET_DRAWABLE_SHADER)
            mGetEnableShader = base.getMethod(METHOD_GET_ENABLE_SHADER)
            mGetBounds = base.getMethod(METHOD_GET_BOUNDS)
            mGetPaint = base.getMethod(METHOD_GET_PAINT)
            mDrawBlurShader = base.getMethod(METHOD_DRAW_BLUR_SHADER, Canvas::class.java)
            val shaderCls = Class.forName(CLASS_DRAWABLE_SHADER, false, classLoader)
            mGetShaderOrNull = shaderCls.getMethod(METHOD_GET_SHADER_OR_NULL)
        } catch (t: Throwable) {
            Log.e(TAG, "Hook B resolve methods failed, Hook B disabled", t)
        }
        // 坐标映射 hook 反射（独立 try-catch：失败只影响映射，不影响其他 hook）
        try {
            val auto = Class.forName(CLASS_AUTO_BLUR_DRAWABLE, false, classLoader)
            mAutoGetViewBlurProxy = auto.getMethod(METHOD_GET_VIEW_BLUR_PROXY)
            mAutoDefaultDrawable = auto.getDeclaredField(FIELD_DEFAULT_DRAWABLE).apply { isAccessible = true }
            mAutoGetBounds = auto.getMethod(METHOD_GET_BOUNDS)
            val vbp = Class.forName(CLASS_VIEW_BLUR_PROXY, false, classLoader)
            mViewProxyGetView = vbp.getMethod(METHOD_GET_VIEW)
            mViewProxyGetBlurDrawable = vbp.getMethod(METHOD_GET_BLUR_DRAWABLE, Drawable::class.java)
            val pbd = Class.forName(CLASS_PLATFORM_BLUR_DRAWABLE, false, classLoader)
            mPlatformGetBlurDrawable = pbd.getMethod(METHOD_GET_BLUR_DRAWABLE)
        } catch (t: Throwable) {
            Log.e(TAG, "Hook B resolve AutoBlurDrawable methods failed, screen-region map disabled", t)
        }
        // C1：通知卡片专属映射反射（独立 try-catch：失败只影响通知卡片映射）
        try {
            val ext = Class.forName(CLASS_NBV_EXT, false, classLoader)
            mNbvGetBgView = ext.getMethod(METHOD_GET_BG_VIEW)
            mNbvGetViewBlurProxy = ext.getMethod(METHOD_GET_VIEW_BLUR_PROXY)
        } catch (t: Throwable) {
            Log.e(TAG, "Hook B resolve NotificationBackgroundView methods failed, notification card map disabled", t)
        }
        // 磨砂根因·修 1：ViewBlurManager.currentMaterialColor 字段（ExtImp.viewBlurManager 是 public final，
        // currentMaterialColor 是 public int；16.1 反编译 ViewBlurManager.java line 78/117 实证）。
        // 独立 try-catch：失败只禁用材质色层抑制（回退现状含磨砂），不影响其他 hook。
        try {
            val extImpCls = Class.forName(CLASS_NBV_EXT_IMP, false, classLoader)
            mNbvViewBlurManager = extImpCls.getField(FIELD_VIEW_BLUR_MANAGER).apply { isAccessible = true }
            val vbmCls = Class.forName(CLASS_VIEW_BLUR_MANAGER, false, classLoader)
            mViewBlurManagerMaterialColor = vbmCls.getField(FIELD_CURRENT_MATERIAL_COLOR).apply { isAccessible = true }
            Log.i(TAG, "Hook B ViewBlurManager.currentMaterialColor field resolved (frosted-glass suppress)")
        } catch (t: Throwable) {
            Log.e(TAG, "Hook B resolve ViewBlurManager.currentMaterialColor failed, material-color layer suppress disabled", t)
        }
        // 磨砂根因·修 3（agent 细读实证 2026-08-12，F:\jadx_extimp_out\NotificationBackgroundViewExtImp.java）：
        // ExtImp.draw 里两个未被抑制的叠加层——clickAlpha（public int，line 62，点击暗层
        // clickEffectDrawable 的 alpha，点击动画后归 0）与 stackedNotificationMuskColor（public int，
        // line 82，折叠/堆叠动画时 oplusColorBg 色层，progress→0 后消失）。置 0 拦截（finally 恢复）。
        // 独立 try-catch：失败只禁用对应抑制（回退现状），不影响其他 hook。
        try {
            val extImpCls = Class.forName(CLASS_NBV_EXT_IMP, false, classLoader)
            mExtImpClickAlpha = extImpCls.getField(FIELD_CLICK_ALPHA).apply { isAccessible = true }
            mExtImpStackedMuskColor = extImpCls.getField(FIELD_STACKED_MUSK_COLOR).apply { isAccessible = true }
            Log.i(TAG, "Hook B ExtImp clickAlpha/stackedNotificationMuskColor field resolved (click/stack overlay suppress)")
        } catch (t: Throwable) {
            Log.e(TAG, "Hook B resolve ExtImp click/stack overlay fields failed, suppress disabled", t)
        }
        // 磨砂根因·修 2：PlatformBlurDrawable.blurMaskColor 字段（public int，PlatformBlurDrawable.java
        // line 36 实证）。独立 try-catch：失败只禁用 blurMaskColor 层抑制（回退现状），不影响其他 hook。
        try {
            val pbdCls = Class.forName(CLASS_PLATFORM_BLUR_DRAWABLE, false, classLoader)
            mPlatformBlurMaskColor = pbdCls.getField(FIELD_BLUR_MASK_COLOR).apply { isAccessible = true }
            Log.i(TAG, "Hook B PlatformBlurDrawable.blurMaskColor field resolved (mask-layer suppress)")
        } catch (t: Throwable) {
            Log.e(TAG, "Hook B resolve PlatformBlurDrawable.blurMaskColor failed, mask-color layer suppress disabled", t)
        }
        // 任务 D + 任务 B（spec/11）：圆角半径反射（独立 try-catch：失败只回退启发式圆角，不影响映射）。
        // 字段名以 16.1 反编译源码核对：ViewBlurProxy.getBlurConfig() → BlurConfig.getCornerRadius()/
        // getLeftTopCornerRadius()/其余三角 getter/getPathProvider()/getGradientStrokeCornerParam()
        // （blurability\BlurConfig.java；spec/11）
        try {
            val vbpCls = Class.forName(CLASS_VIEW_BLUR_PROXY, false, classLoader)
            mViewProxyGetBlurConfig = vbpCls.getMethod(METHOD_GET_BLUR_CONFIG)
            val bc = Class.forName(CLASS_BLUR_CONFIG, false, classLoader)
            mBlurConfigGetCornerRadius = bc.getMethod(METHOD_GET_CORNER_RADIUS)
            mBlurConfigGetLeftTopCornerRadius = bc.getMethod(METHOD_GET_LEFT_TOP_CORNER_RADIUS)
            mBlurConfigGetLeftBottomCornerRadius = bc.getMethod(METHOD_GET_LEFT_BOTTOM_CORNER_RADIUS)
            mBlurConfigGetRightTopCornerRadius = bc.getMethod(METHOD_GET_RIGHT_TOP_CORNER_RADIUS)
            mBlurConfigGetRightBottomCornerRadius = bc.getMethod(METHOD_GET_RIGHT_BOTTOM_CORNER_RADIUS)
            mBlurConfigGetPathProvider = bc.getMethod(METHOD_GET_PATH_PROVIDER)
            mBlurConfigGetGradientStrokeCornerParam = bc.getMethod(METHOD_GET_GRADIENT_STROKE_CORNER_PARAM)
            mBlurConfigGetRadiusWeight = bc.getMethod(METHOD_GET_RADIUS_WEIGHT)
            Log.i(TAG, "Hook B BlurConfig cornerRadius reflection resolved (getCornerRadius/四角/pathProvider/gradientStrokeCornerParam/radiusWeight)")
        } catch (t: Throwable) {
            Log.e(TAG, "Hook B resolve BlurConfig cornerRadius failed, no valid cornerRadius (use square)", t)
        }
        // [spec/11] 级 3：RoundRectOutlineProvider.cornerRadius 字段（QS tile pathProvider 真实圆角，
        // 已是 OplusQsSmoothRoundUtil 映射后 shader 值）。独立 try-catch：失败只跳过该级，不影响已解析方法。
        try {
            mRoundRectOutlineProviderClass = Class.forName(CLASS_ROUND_RECT_OUTLINE_PROVIDER, false, classLoader)
            mRoundRectOutlineProviderCornerRadiusField = mRoundRectOutlineProviderClass?.getField(FIELD_CORNER_RADIUS)
            Log.i(TAG, "Hook B RoundRectOutlineProvider.cornerRadius field resolved (pathProvider 级)")
        } catch (t: Throwable) {
            Log.e(TAG, "Hook B resolve RoundRectOutlineProvider.cornerRadius failed, pathProvider 圆角级跳过", t)
        }
        // [spec/11] 级 4：CornerParams.getRadius()（侧滑按钮/MetaBall gradientStrokeCornerParam 真实圆角）。
        // 独立 try-catch：失败只跳过该级。
        try {
            val cpCls = Class.forName(CLASS_CORNER_PARAMS, false, classLoader)
            mCornerParamsGetRadius = cpCls.getMethod(METHOD_GET_RADIUS)
            Log.i(TAG, "Hook B CornerParams.getRadius() resolved (gradientStrokeCornerParam 级)")
        } catch (t: Throwable) {
            Log.e(TAG, "Hook B resolve CornerParams.getRadius() failed, gradientStrokeCornerParam 圆角级跳过", t)
        }
        // [spec/18 圆角过渡区深挖修正] 级 4b：CornerParams.getType()/getWeight() + CornerType.CONIC 判等
        //（CONIC 本体判定；c16.1_PJZ110 CornerParams.java/CornerType.java 反编译实证）。
        // 独立 try-catch：失败 → mCornerTypeConic=null → resolveCornerRadius 降级圆弧（不崩）。
        try {
            val cpCls = Class.forName(CLASS_CORNER_PARAMS, false, classLoader)
            mCornerParamsGetType = cpCls.getMethod(METHOD_GET_TYPE)
            mCornerParamsGetWeight = cpCls.getMethod(METHOD_GET_WEIGHT)
            val cornerTypeCls = Class.forName(CLASS_CORNER_TYPE, false, classLoader)
            mCornerTypeConic = cornerTypeCls.getField("CONIC").get(null)
            Log.i(TAG, "Hook B CornerParams.getType()/getWeight() + CornerType.CONIC resolved (CONIC 本体判定)")
        } catch (t: Throwable) {
            Log.e(TAG, "Hook B resolve CornerType.CONIC failed, conic 判定降级圆弧", t)
        }
        // [spec/18 Bug 1 二级菜单] 级 3b：BaseRoundRectPathProvider.getMaxRadius()（SmoothRoundRectPathProvider
        // 系 pathProvider 真实圆角；QsFlashLightBackgroundUtils.provideLevelBarBlurProxy 用它——反编译实证
        // line 73 `blurConfig.setPathProvider(new SmoothRoundRectPathProvider(...))`，只 set pathProvider
        // 不 set cornerRadius → 既有五级反射全 miss → 手电筒亮度档方角。getMaxRadius() 读 cornerRadii 最大值）。
        // 独立 try-catch：失败只跳过该级。
        try {
            val prCls = Class.forName(CLASS_BASE_ROUND_RECT_PATH_PROVIDER, false, classLoader)
            mBaseRoundRectPathProviderClass = prCls
            mBaseRoundRectPathProviderGetMaxRadius = prCls.getMethod(METHOD_GET_MAX_RADIUS)
            Log.i(TAG, "Hook B BaseRoundRectPathProvider.getMaxRadius() resolved (smooth-round pathProvider 级)")
        } catch (t: Throwable) {
            Log.e(TAG, "Hook B resolve BaseRoundRectPathProvider.getMaxRadius() failed, smooth-round pathProvider 圆角级跳过", t)
        }
        // 侧滑按钮真实圆角：SystemUI R.dimen.notification_corner_radius（16dp，dimens.xml:3027 实证；
        // NotificationMenuRowMetaBallController2.getBlurMenuCornerRadius() line 35-36 读它做按钮圆角）。
        // 独立 try-catch：失败只影响侧滑按钮圆角（回退正方形玻璃），不影响映射。
        try {
            val rDimen = Class.forName("com.android.systemui.R\$dimen", false, classLoader)
            mNotificationCornerRadiusDimenId = rDimen.getField("notification_corner_radius").getInt(null)
            Log.i(TAG, "Hook B notification_corner_radius dimen id resolved: $mNotificationCornerRadiusDimenId")
        } catch (t: Throwable) {
            Log.e(TAG, "Hook B resolve notification_corner_radius dimen id failed, swipe cornerRadius uses fallback", t)
        }
        // [Bug 3] 侧滑按钮圆角级 2 兜底：NotificationMenuRowMetaBallController2.getBlurMenuCornerRadius()
        //（私有实例方法，反编译实证内部 `context.getResources().getDimensionPixelSize(R.dimen.notification_corner_radius)`
        //  = 系统真实按钮圆角；构造函数 public(Context)，用宿主 context new 实例 + setAccessible 调用）。
        // 独立 try-catch：失败只降级到 getIdentifier 兜底，不影响其他 hook。
        try {
            val metaCls = Class.forName(
                "com.oplus.systemui.notification.row.NotificationMenuRowMetaBallController2", false, classLoader
            )
            mBlurMenuCornerRadiusCtor = metaCls.getConstructor(android.content.Context::class.java)
            mBlurMenuCornerRadiusMethod = metaCls.getDeclaredMethod("getBlurMenuCornerRadius").apply { isAccessible = true }
            Log.i(TAG, "Hook B NotificationMenuRowMetaBallController2.getBlurMenuCornerRadius() resolved (swipe cornerRadius fallback)")
        } catch (t: Throwable) {
            Log.e(TAG, "Hook B resolve getBlurMenuCornerRadius failed, swipe cornerRadius uses getIdentifier fallback", t)
        }
        // [Bug 5] 通知卡片横向滑动修正：OplusCustomRow.mBackgroundNormal（私有 declared，玻璃宿主）。
        // 独立 try-catch：失败只跳过横向滑动重采样（回退现状冻结），不影响其他 hook。
        try {
            val rowCls = Class.forName(CLASS_OPLUS_CUSTOM_ROW, false, classLoader)
            mCustomRowBgNormal = rowCls.getDeclaredField(FIELD_BG_NORMAL).apply { isAccessible = true }
            Log.i(TAG, "Hook B OplusCustomRow.mBackgroundNormal field resolved (custom-row swipe re-sample)")
        } catch (t: Throwable) {
            Log.e(TAG, "Hook B resolve OplusCustomRow fields failed, custom-row swipe re-sample disabled", t)
        }
        // [spec/44b] 流体云展开卡片背景透明：NotificationBackgroundView.mBackground 字段（public Drawable，
        // onDraw 直绘该字段，View.background 置空无效）。独立 try-catch：失败只跳过背景透明（玻璃仍画）。
        try {
            val nbvCls = Class.forName(CLASS_NOTIFICATION_BACKGROUND_VIEW, false, classLoader)
            mNbvBackgroundField = nbvCls.getDeclaredField(FIELD_NBV_BACKGROUND).apply { isAccessible = true }
            Log.i(TAG, "Hook B NotificationBackgroundView.mBackground field resolved (custom-row bg transparent)")
        } catch (t: Throwable) {
            Log.e(TAG, "Hook B resolve NotificationBackgroundView.mBackground failed, custom-row bg transparent disabled", t)
        }
        // 任务 2：侧滑按钮（MaskBlurDrawable）映射反射（独立 try-catch：失败只影响侧滑按钮映射）
        try {
            val mask = Class.forName(CLASS_MASK_BLUR_DRAWABLE, false, classLoader)
            mMaskGetViewBlurProxy = mask.getMethod(METHOD_GET_VIEW_BLUR_PROXY)
        } catch (t: Throwable) {
            Log.e(TAG, "Hook B resolve MaskBlurDrawable methods failed, swipe-menu map disabled", t)
        }
        // 任务 3：普通 tile（MixColorTileDrawable）内层 AutoBlurDrawable 取出反射（DrawableWrapper.getDrawable 公有继承）
        try {
            val mix = Class.forName(CLASS_MIX_COLOR_TILE_DRAWABLE, false, classLoader)
            mTileWrapperGetDrawable = mix.getMethod(METHOD_GET_DRAWABLE)
        } catch (t: Throwable) {
            Log.e(TAG, "Hook B resolve MixColorTileDrawable methods failed, tile map fallback disabled", t)
        }
        // [任务 T] maskColor 字段反射（反编译 :394 `this.maskColor = ...`，private int）。读取状态色
        // 合成 alpha 判 z；字段反射失败（null）→ 不做 z 判断，恒走"只画玻璃"拦截兜底
        try {
            val mix = Class.forName(CLASS_MIX_COLOR_TILE_DRAWABLE, false, classLoader)
            mTileMaskColor = mix.getDeclaredField("maskColor").apply { isAccessible = true }
        } catch (t: Throwable) {
            Log.w(TAG, "Hook B resolve MixColorTileDrawable.maskColor field failed, tile mask skip uses z-less path", t)
        }
        // 任务 spec/14 补映射：胶囊/胶囊耳/媒体横幅宿主私有字段反射（各自独立 try-catch：
        // 失败只禁用对应控件映射，不影响其他 hook；字段名以 16.1 反编译核对）
        try {
            val capsuleCls = Class.forName(CLASS_CAPSULE_BACKGROUND_HELPER, false, classLoader)
            mCapsuleHelperViewBlurProxy = capsuleCls.getDeclaredField(FIELD_VIEW_BLUR_PROXY).apply { isAccessible = true }
            mCapsuleHelperReplacedDrawable = capsuleCls.getDeclaredField(FIELD_REPLACED_DRAWABLE).apply { isAccessible = true }
            Log.i(TAG, "Hook B CapsuleBackgroundHelper.viewBlurProxy/replacedDrawable fields resolved (capsule map)")
        } catch (t: Throwable) {
            Log.e(TAG, "Hook B resolve CapsuleBackgroundHelper fields failed, capsule map disabled", t)
        }
        try {
            val earCls = Class.forName(CLASS_CAPSULE_EAR_VIEW, false, classLoader)
            mCapsuleEarViewBlurProxy = earCls.getDeclaredField(FIELD_VIEW_BLUR_PROXY).apply { isAccessible = true }
            Log.i(TAG, "Hook B CapsuleEarView.viewBlurProxy field resolved (capsule ear map)")
        } catch (t: Throwable) {
            Log.e(TAG, "Hook B resolve CapsuleEarView.viewBlurProxy failed, capsule ear map disabled", t)
        }
        try {
            val mediaCls = Class.forName(CLASS_MEDIA_CAROUSEL_VIEW, false, classLoader)
            mMediaCarouselViewBlurProxy = mediaCls.getDeclaredField(FIELD_VIEW_BLUR_PROXY).apply { isAccessible = true }
            Log.i(TAG, "Hook B OplusMediaCarouselView.viewBlurProxy field resolved (media banner map)")
        } catch (t: Throwable) {
            Log.e(TAG, "Hook B resolve OplusMediaCarouselView.viewBlurProxy failed, media banner map disabled", t)
        }
        // [spec/19 Heads-Up 玻璃化] ViewBlurProxy.setBlurType(BlurType) + BlurTypeMotion/PlatformStatic 实例
        // + NotificationBackgroundView 类。独立 try-catch：失败只禁用 heads-up 玻璃化（回退现状 Motion），不影响其他 hook。
        // 反编译核对（blurability\ViewBlurProxy.java）：setBlurType :592、
        // BlurType 抽象内部类 :66、BlurTypeMotion :86、BlurTypePlatformStatic :120（各含 static final INSTANCE）。
        try {
            val vbpCls = Class.forName(CLASS_VIEW_BLUR_PROXY, false, classLoader)
            val blurTypeCls = Class.forName(CLASS_VIEW_BLUR_TYPE, false, classLoader)
            mViewProxySetBlurType = vbpCls.getMethod(METHOD_SET_BLUR_TYPE, blurTypeCls)
            mBlurTypeMotionInstance = Class.forName(CLASS_BLUR_TYPE_MOTION, false, classLoader).getField("INSTANCE").get(null)
            mBlurTypePlatformStaticInstance = Class.forName(CLASS_BLUR_TYPE_PLATFORM_STATIC, false, classLoader).getField("INSTANCE").get(null)
            mNotifBackgroundViewClass = Class.forName(CLASS_NOTIFICATION_BACKGROUND_VIEW, false, classLoader)
            Log.i(TAG, "Hook B ViewBlurProxy.setBlurType(BlurType) resolved (heads-up Motion -> PlatformStatic)")
        } catch (t: Throwable) {
            Log.e(TAG, "Hook B resolve ViewBlurProxy.setBlurType failed, heads-up glass disabled", t)
        }
        // [spec/48 遮罩走系统自带机制] ScrimView.mScrimName 字段反射（public String，16.1 反编译实证）。
        // 独立 try-catch：失败只让 behind_scrim 判定走保守路径（宁画不丢），不影响其他 hook。
        try {
            val scrimCls = Class.forName(CLASS_SCRIM_VIEW, false, classLoader)
            mScrimNameField = scrimCls.getField(FIELD_SCRIM_NAME).apply { isAccessible = true }
            // [spec/48 过渡修复加固 2026-08-13] ScrimView.mViewAlpha（public float）——系统 scrim 动画逐帧
            // alpha 真源；绘制路径直接反射读它（不依赖 hook），最稳。ScrimViewEx.getScrimView() 用于
            // ScrimViewEx/ExImp hook 反取宿主 ScrimView（判 behind_scrim）。
            mViewAlphaField = try {
                scrimCls.getField(FIELD_SCRIM_VIEW_ALPHA).apply { isAccessible = true }
            } catch (t: Throwable) {
                Log.e(TAG, "Hook B resolve ScrimView.mViewAlpha failed, mask falls back to hook alpha", t)
                null
            }
            mExtGetScrimView = try {
                Class.forName(CLASS_SCRIM_VIEW_EX, false, classLoader)
                    .getMethod(METHOD_GET_SCRIM_VIEW).apply { isAccessible = true }
            } catch (t: Throwable) {
                Log.e(TAG, "Hook B resolve ScrimViewEx.getScrimView failed, ext hook uses conservative path", t)
                null
            }
            Log.i(TAG, "Hook B ScrimView.mScrimName/mViewAlpha field resolved (spec/48 behind-scrim black tint)")
        } catch (t: Throwable) {
            Log.e(TAG, "Hook B resolve ScrimView.mScrimName failed, behind-scrim mask uses conservative path", t)
        }
    }

    // ------------------------------------------------------------ Hook -1：AutoBlurDrawable.draw（坐标映射）

    /**
     * Hook AutoBlurDrawable.draw(Canvas)（16.1 源码实证）：
     * - `AutoBlurDrawable`（com.oplusos.systemui.common.blurability.drawable）包装 ViewBlurProxy，
     *   面板背景（ScrimControllerExImp.refreshBehindDrawable）与 tile 背景
     *   （LightStyleStrokeDrawable extends AutoBlurDrawable，PlatformStatic 时 strokeShaderProxy=null
     *   → super.draw(canvas) 走本方法）共用。
     * - draw 内部：`viewBlurProxy.getBlurDrawable(defaultDrawable)` → 运行时 PlatformBlurDrawable
     *   （PlatformStatic）→ 其内部 `getBlurDrawable()`（无参）返回 posteffect BlurDrawable——
     *   与 drawBlurShader hook 的 this 同一实例（identityHashCode 匹配）。
     * - hookBefore 建立映射：drawable(identityHashCode) → 屏幕区域（全屏像素 RectF），
     *   坐标 = hostView.getLocationOnScreen 左上角 + AutoBlurDrawable bounds 偏移。
     *   drawBlurShader 侧查表后 ×0.25 折算原图区域（原图 1/4 分辨率）。
     *
     * 反射/挂载失败只影响坐标映射（回退系统模糊），不影响其他 hook。
     */
    private fun mountAutoBlurDrawable(api: XposedInterface, classLoader: ClassLoader) {
        if (mAutoGetViewBlurProxy == null || mViewProxyGetView == null || mViewProxyGetBlurDrawable == null) {
            Log.w(TAG, "Hook B AutoBlurDrawable methods unresolved, screen-region map disabled")
            return
        }
        try {
            val clazz = Class.forName(CLASS_AUTO_BLUR_DRAWABLE, false, classLoader)
            val method = clazz.getMethod(METHOD_DRAW, Canvas::class.java)
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        recordScreenRegion(chain)
                    } catch (t: Throwable) {
                        Log.e(TAG, "AutoBlurDrawable.draw: intercept error", t)
                    }
                    // [spec/48 方案 A] 背景 scrim：不 proceed → 拦截整条背景模糊绘制链
                    // （AutoBlurDrawable.draw → PlatformBlurDrawable → posteffect BlurDrawable
                    // → drawBlurShader）。behind_scrim 改画纯黑 tint（遮罩走系统自带机制，
                    // alpha=behindScrimAlpha×maskAlpha，由 hook ScrimView.setViewAlpha 捕获系统动画值；
                    // ⚠️ 不能读 Drawable.getAlpha()——AutoBlurDrawable.setAlpha 空实现恒 255）；
                    // notifications_scrim/front_scrim 保持透明（现状）。仅 hostView=ScrimView
                    // 才跳过，控件（tile 等 AutoBlurDrawable 子类，hostView=OplusQSTileBaseView 等）不受影响。
                    if (isBackgroundScrimDrawable(chain)) {
                        // [spec/61 2026-08-23] 保留系统控制中心模糊（默认 true）：先 proceed 走系统模糊链
                        // （力度已由 applyBlurConfig hook 调整 + applyPanelMirrorScale 阻止缩小），再画黑 tint
                        // 压暗在模糊之上（mask 开时）；关闭（false）则短路背景模糊链（透明露壁纸，旧行为）。
                        if (keepSystemCcBlurEnabled()) {
                            chain.proceed()
                            try {
                                if (isBehindScrim(chain)) drawScrimBlackTint(chain)
                            } catch (t: Throwable) {
                                Log.e(TAG, "bg: draw scrim black tint error", t)
                            }
                            logThrottled("bg-scrim-keep", Log.INFO) { "bg: keep system cc blur (strength=${ccBlurStrength()}%), proceed system blur + black tint" }
                        } else {
                            try {
                                if (isBehindScrim(chain)) drawScrimBlackTint(chain)
                            } catch (t: Throwable) {
                                Log.e(TAG, "bg: draw scrim black tint error", t)
                            }
                            logThrottled("bg-scrim-skip", Log.INFO) { "bg: force transparent, skip AutoBlurDrawable.draw (background scrim, reveal wallpaper)" }
                        }
                        return@intercept null
                    }
                    chain.proceed()
                }
            Log.i(TAG, "Hook B mounted: $CLASS_AUTO_BLUR_DRAWABLE#$METHOD_DRAW(Canvas) (screen-region map)")
        } catch (t: Throwable) {
            Log.e(TAG, "Hook B mount FAILED: $CLASS_AUTO_BLUR_DRAWABLE#$METHOD_DRAW", t)
        }
    }

    /**
     * [任务 I] 描边 tile 映射 hook：LightStyleStrokeDrawable.draw(Canvas)。
     *
     * 反编译实证（16.1，com.oplus.systemui.qs.base.widget.LightStyleStrokeDrawable line 35-45）：
     * `strokeShaderProxy != null`（非 PlatformStatic 的描边 tile）时 draw() 直接
     * `strokeShaderProxy.drawShader(canvas, null); return`，**不回调 super.draw** →
     * AutoBlurDrawable.draw hook 从不触发 → screenRegionMap 无这些 tile 的映射 →
     * 内部 posteffect drawable 照样走 drawBlurShader（经 StrokeShaderProxy 的
     * setStrokeBackground lambda → viewBlurProxy.getBlurDrawable）→ 恒 map miss →
     * 回退系统模糊（真机"部分 tile 磨砂、部分清晰"根因；偶有玻璃是 C2 bounds 兜底碰巧唯一命中）。
     *
     * 修复：单独 hook 本方法，复用 recordScreenRegion（this 是 AutoBlurDrawable 子类，
     * getViewBlurProxy/defaultDrawable 反射链完全通用）。始终 proceed（不拦截绘制），
     * 挂载失败只影响描边 tile 映射（回退现状），不崩。
     */
    private fun mountLightStyleStrokeDrawable(api: XposedInterface, classLoader: ClassLoader) {
        if (mAutoGetViewBlurProxy == null || mViewProxyGetView == null || mViewProxyGetBlurDrawable == null) {
            Log.w(TAG, "Hook B AutoBlurDrawable methods unresolved, stroke-tile map disabled")
            return
        }
        try {
            val clazz = Class.forName(CLASS_LIGHT_STYLE_STROKE_DRAWABLE, false, classLoader)
            val method = clazz.getMethod(METHOD_DRAW, Canvas::class.java)
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        recordScreenRegion(chain)
                    } catch (t: Throwable) {
                        Log.e(TAG, "LightStyleStrokeDrawable.draw: intercept error", t)
                    }
                    chain.proceed()
                }
            Log.i(TAG, "Hook B mounted: $CLASS_LIGHT_STYLE_STROKE_DRAWABLE#$METHOD_DRAW(Canvas) (stroke-tile screen-region map)")
        } catch (t: Throwable) {
            Log.e(TAG, "Hook B mount FAILED: $CLASS_LIGHT_STYLE_STROKE_DRAWABLE#$METHOD_DRAW (stroke tiles keep system blur)", t)
        }
    }

    /**
     * [任务 2] 侧滑按钮映射 hook：MaskBlurDrawable.draw(Canvas)。
     * 逆向实证见 recordSwipeMenuRegion。侧滑按钮背景 drawable（MetaBallBlurDrawable）不经
     * AutoBlurDrawable.draw → 单独 hook 注册。始终 proceed（不拦截绘制），失败只影响侧滑按钮
     * 映射（回退系统模糊），不崩。
     */
    private fun mountMaskBlurDrawable(api: XposedInterface, classLoader: ClassLoader) {
        if (mMaskGetViewBlurProxy == null || mViewProxyGetView == null || mViewProxyGetBlurDrawable == null) {
            Log.w(TAG, "Hook B MaskBlurDrawable methods unresolved, swipe-menu map disabled")
            return
        }
        try {
            val clazz = Class.forName(CLASS_MASK_BLUR_DRAWABLE, false, classLoader)
            val method = clazz.getMethod(METHOD_DRAW, Canvas::class.java)
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        recordSwipeMenuRegion(chain)
                    } catch (t: Throwable) {
                        Log.e(TAG, "MaskBlurDrawable.draw: intercept error", t)
                    }
                    chain.proceed()
                }
            Log.i(TAG, "Hook B mounted: $CLASS_MASK_BLUR_DRAWABLE#$METHOD_DRAW(Canvas) (swipe-menu screen-region map)")
        } catch (t: Throwable) {
            Log.e(TAG, "Hook B mount FAILED: $CLASS_MASK_BLUR_DRAWABLE#$METHOD_DRAW (swipe buttons keep system blur)", t)
        }
    }

    // ------------------------------------------------------------ 任务 spec/14 补映射：直接绘制路径（不经 AutoBlurDrawable.draw）

    /**
     * [spec/14] 锁屏通知胶囊背景映射 hook：CapsuleBackgroundHelper.drawCapsuleBg(View, Canvas)。
     *
     * 逆向实证（16.1，CapsuleBackgroundHelper.java line 140-148 + CapsuleBackgroundView.java:114-115）：
     * ```
     * Drawable blurDrawable = this.replacedDrawable != null ? this.replacedDrawable : viewBlurProxy.getBlurDrawable(null);
     * blurDrawable.setBounds(clipRect); blurDrawable.draw(canvas);   // 直接 draw，不经 AutoBlurDrawable.draw
     * ```
     * 胶囊 MetaBallBlurDrawable 由 CapsuleBackgroundView.onDraw → drawCapsuleBg 直绘，
     * 从无 AutoBlurDrawable.draw 中间层 → screenRegionMap 恒无胶囊映射（drawBlurShader 查表 miss）。
     * CapsuleEarView.onDraw（line 160-168）与 CapsuleShardBackgroundDrawableManager（smooth-radius 时
     * replacedDrawable = shard PlatformBlurDrawable，仍经 drawCapsuleBg 绘制）同链。
     * 修复：hook drawCapsuleBg，反射 viewBlurProxy 字段 → registerPlatformBlurRegion 注册；
     * 顺带反射 replacedDrawable（shard 场景实际绘制它）单独注册。始终 proceed（不拦截绘制），
     * 失败只影响胶囊映射（回退系统模糊），不崩。
     */
    private fun mountCapsuleBackground(api: XposedInterface, classLoader: ClassLoader) {
        if (mCapsuleHelperViewBlurProxy == null || mViewProxyGetView == null || mViewProxyGetBlurDrawable == null) {
            Log.w(TAG, "Hook B CapsuleBackgroundHelper fields unresolved, capsule map disabled")
            return
        }
        try {
            val clazz = Class.forName(CLASS_CAPSULE_BACKGROUND_HELPER, false, classLoader)
            val method = clazz.getMethod(METHOD_DRAW_CAPSULE_BG, View::class.java, Canvas::class.java)
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        recordCapsuleBgRegion(chain)
                    } catch (t: Throwable) {
                        Log.e(TAG, "CapsuleBackgroundHelper.drawCapsuleBg: intercept error", t)
                    }
                    chain.proceed()
                }
            Log.i(TAG, "Hook B mounted: $CLASS_CAPSULE_BACKGROUND_HELPER#$METHOD_DRAW_CAPSULE_BG(View, Canvas) (capsule map)")
        } catch (t: Throwable) {
            Log.e(TAG, "Hook B mount FAILED: $CLASS_CAPSULE_BACKGROUND_HELPER#$METHOD_DRAW_CAPSULE_BG (capsule keeps system blur)", t)
        }
    }

    /** [spec/14] hookBefore：注册 drawCapsuleBg 实际绘制的 MetaBallBlurDrawable（含 smooth-radius shard 场景）。 */
    private fun recordCapsuleBgRegion(chain: XposedInterface.Chain) {
        val helper = try {
            chain.getThisObject()
        } catch (t: Throwable) {
            Log.e(TAG, "capsule: getThisObject failed", t); return
        } ?: return
        val view = try {
            chain.getArg(0) as? View
        } catch (t: Throwable) {
            Log.e(TAG, "capsule: getArg(0) failed", t); null
        } ?: return
        val proxy = try {
            mCapsuleHelperViewBlurProxy?.get(helper)
        } catch (t: Throwable) {
            Log.e(TAG, "capsule: read viewBlurProxy failed", t); null
        }
        // smooth-radius 模式：replacedDrawable（shard 的 PlatformBlurDrawable）被绘制 → 单独注册
        val replaced = try {
            mCapsuleHelperReplacedDrawable?.get(helper) as? Drawable
        } catch (t: Throwable) {
            Log.e(TAG, "capsule: read replacedDrawable failed", t); null
        }
        if (replaced != null) {
            registerPlatformBlurRegion(view, replaced, proxy, "capsule-shard")
            return
        }
        if (proxy != null) registerPlatformBlurRegion(view, null, proxy, "capsule")
    }

    /**
     * [spec/14] 锁屏胶囊耳（展开小耳朵）映射 hook：CapsuleEarView.onDraw(Canvas)。
     * 逆向实证（CapsuleEarView.java line 160-168）：onDraw 内 `viewBlurProxy.getBlurDrawable(null)`
     * 直接 setBounds(drawBound)+draw，不经 AutoBlurDrawable.draw。hook onDraw，反射 viewBlurProxy 注册。
     */
    private fun mountCapsuleEar(api: XposedInterface, classLoader: ClassLoader) {
        if (mCapsuleEarViewBlurProxy == null || mViewProxyGetView == null || mViewProxyGetBlurDrawable == null) {
            Log.w(TAG, "Hook B CapsuleEarView field unresolved, capsule ear map disabled")
            return
        }
        try {
            val clazz = Class.forName(CLASS_CAPSULE_EAR_VIEW, false, classLoader)
            val method = clazz.getMethod(METHOD_ON_DRAW, Canvas::class.java)
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        recordCapsuleEarRegion(chain)
                    } catch (t: Throwable) {
                        Log.e(TAG, "CapsuleEarView.onDraw: intercept error", t)
                    }
                    chain.proceed()
                }
            Log.i(TAG, "Hook B mounted: $CLASS_CAPSULE_EAR_VIEW#$METHOD_ON_DRAW(Canvas) (capsule ear map)")
        } catch (t: Throwable) {
            Log.e(TAG, "Hook B mount FAILED: $CLASS_CAPSULE_EAR_VIEW#$METHOD_ON_DRAW (capsule ear keeps system blur)", t)
        }
    }

    /** [spec/14] hookBefore：注册 CapsuleEarView 直接绘制的 MetaBallBlurDrawable。 */
    private fun recordCapsuleEarRegion(chain: XposedInterface.Chain) {
        val view = try {
            chain.getThisObject() as? View
        } catch (t: Throwable) {
            Log.e(TAG, "capsuleEar: getThisObject failed", t); return
        } ?: return
        val proxy = try {
            mCapsuleEarViewBlurProxy?.get(view)
        } catch (t: Throwable) {
            Log.e(TAG, "capsuleEar: read viewBlurProxy failed", t); return
        } ?: return
        registerPlatformBlurRegion(view, null, proxy, "capsuleEar")
    }

    /**
     * [spec/14] 锁屏/AOD 媒体横幅映射 hook：OplusMediaCarouselView.onDraw(Canvas)。
     * 逆向实证（OplusMediaCarouselView.java line 98-124）：onDraw 内
     * `viewBlurProxy.getBlurDrawable(defaultBgDrawable)` 后 setBounds + 直接 draw，不经
     * AutoBlurDrawable.draw。MediaBgAodWrapper（notification/aod/notification/）仅改色不独立绘制
     * （复用同 view 的 PlatformBlurDrawable）。hook onDraw，反射 viewBlurProxy 注册。
     */
    private fun mountMediaCarousel(api: XposedInterface, classLoader: ClassLoader) {
        if (mMediaCarouselViewBlurProxy == null || mViewProxyGetView == null || mViewProxyGetBlurDrawable == null) {
            Log.w(TAG, "Hook B OplusMediaCarouselView field unresolved, media banner map disabled")
            return
        }
        try {
            val clazz = Class.forName(CLASS_MEDIA_CAROUSEL_VIEW, false, classLoader)
            val method = clazz.getMethod(METHOD_ON_DRAW, Canvas::class.java)
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        recordMediaCarouselRegion(chain)
                    } catch (t: Throwable) {
                        Log.e(TAG, "OplusMediaCarouselView.onDraw: intercept error", t)
                    }
                    chain.proceed()
                }
            Log.i(TAG, "Hook B mounted: $CLASS_MEDIA_CAROUSEL_VIEW#$METHOD_ON_DRAW(Canvas) (media banner map)")
        } catch (t: Throwable) {
            Log.e(TAG, "Hook B mount FAILED: $CLASS_MEDIA_CAROUSEL_VIEW#$METHOD_ON_DRAW (media banner keeps system blur)", t)
        }
    }

    /** [spec/14] hookBefore：注册媒体横幅直接绘制的 PlatformBlurDrawable → posteffect。 */
    private fun recordMediaCarouselRegion(chain: XposedInterface.Chain) {
        val view = try {
            chain.getThisObject() as? View
        } catch (t: Throwable) {
            Log.e(TAG, "media: getThisObject failed", t); return
        } ?: return
        val proxy = try {
            mMediaCarouselViewBlurProxy?.get(view)
        } catch (t: Throwable) {
            Log.e(TAG, "media: read viewBlurProxy failed", t); return
        } ?: return
        registerPlatformBlurRegion(view, null, proxy, "mediaCarousel")
    }

    /**
     * [spec/14] 直绘路径注册核心：以宿主 View 屏幕位置 + posteffect drawable bounds 写入 screenRegionMap。
     *
     * 与 [registerScreenRegionCore] 的差异：入参是**已解析的 PlatformBlurDrawable**（或 proxy 链现取），
     * 而非 AutoBlurDrawable/MaskBlurDrawable 实例。胶囊/胶囊耳/媒体横幅的绘制链都是
     * `viewBlurProxy.getBlurDrawable(null)` 后直接 draw，不经 AutoBlurDrawable.draw。
     *
     * @param pbd 已解析的 PlatformBlurDrawable；null 时经 proxy.getBlurDrawable(null) 现取。
     *            仅 PlatformBlurDrawable（走 drawBlurShader 链）才注册；Motion/Static 等系统层
     *            drawable 注册无意义且可能污染 bounds 兜底匹配 → 直接跳过。
     * @param cornerProxy 圆角解析用 proxy（resolveCornerRadius）；null → 0（正方形玻璃，强兜底）。
     */
    private fun registerPlatformBlurRegion(hostView: View, pbd: Any?, cornerProxy: Any?, logTag: String) {
        if (mPlatformGetBlurDrawable == null || mViewProxyGetBlurDrawable == null) return
        val resolved: Any = if (pbd != null) {
            pbd
        } else {
            try {
                mViewProxyGetBlurDrawable?.invoke(cornerProxy, null) ?: return
            } catch (t: Throwable) {
                Log.e(TAG, "$logTag: getBlurDrawable failed", t); return
            }
        }
        if (!mPlatformGetBlurDrawable!!.declaringClass.isInstance(resolved)) return
        val target = try {
            mPlatformGetBlurDrawable?.invoke(resolved)
        } catch (t: Throwable) {
            Log.e(TAG, "$logTag: PlatformBlurDrawable.getBlurDrawable() failed", t); return
        } ?: return
        val targetBounds = try {
            mGetBounds?.invoke(target) as? Rect
        } catch (t: Throwable) {
            Log.e(TAG, "$logTag: posteffect getBounds failed", t); null
        }
        val bounds = targetBounds ?: Rect(0, 0, hostView.width, hostView.height)
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            logThrottled("$logTag-skip-empty-bounds", Log.DEBUG) { "$logTag: skip empty bounds $bounds, id=${System.identityHashCode(target)}" }
            return
        }
        registerHostView(hostView)
        val loc = IntArray(2)
        try {
            hostView.getLocationOnScreen(loc)
        } catch (t: Throwable) {
            Log.e(TAG, "$logTag: getLocationOnScreen failed", t); return
        }
        val region = RectF(
            (loc[0] + bounds.left).toFloat(),
            (loc[1] + bounds.top).toFloat(),
            (loc[0] + bounds.right).toFloat(),
            (loc[1] + bounds.bottom).toFloat(),
        )
        val key = System.identityHashCode(target)
        val corner = resolveCornerRadius(cornerProxy, bounds, hostView)
        val entry = RegionEntry(
            region, bounds, hostView.javaClass.simpleName, corner.radius, corner.source,
            cornerIsConic = corner.isConic, cornerWeight = corner.weight,
            hostViewRef = WeakReference(hostView),
        )
        val previous = screenRegionMap[key]
        // [doc/spec/24 任务 B 2026-08-13] 内容变化检测：仅新元素首次出现（previous==null）触发整屏快照
        // 重抓；位置/尺寸更新仅写表不重抓（背景为静态壁纸快照，srcRect 渲染侧实时折算已跟手，滑动无需
        // 每帧重抓整屏 → 运动中/AOD 静止零抓屏，消除 CPU 热点排序 #1）
        if (previous == null) {
            screenRegionMap[key] = entry
            Log.i(
                TAG,
                "coord: $logTag id=$key view=${hostView.javaClass.simpleName} loc=[${loc[0]},${loc[1]}] " +
                    "bounds=$bounds region=$region cornerRadius=${corner.radius} source=${corner.source}"
            )
            if (screenRegionMap.size > 1024) screenRegionMap.clear()
            // [doc/spec/07 + 2026-08-13] 新映射建立 = 内容变化信号 → 触发整屏快照重抓
            triggerBackgroundCapture()
        } else if (previous.region != region || previous.autoBounds != bounds) {
            screenRegionMap[key] = entry
            logThrottled("$logTag-region-updated", Log.DEBUG) { "$logTag: updated screen region id=$key region=$region bounds=$bounds" }
            if (screenRegionMap.size > 1024) screenRegionMap.clear()
            // 位置/尺寸更新仅写表不重抓（静态壁纸背景，srcRect 实时折算已跟手）
        }
    }

    /**
     * [任务 3] 普通 1x1 tile 映射兜底 hook：MixColorTileDrawable.draw(Canvas)。
     * 逆向实证见 recordMixColorTileRegion。该 wrapper 稳态可能跳过内层 AutoBlurDrawable.draw →
     * 取出内层 AutoBlurDrawable 强制注册。始终 proceed（不拦截绘制），失败只影响该 tile 映射
     * （回退系统模糊），不崩。
     */
    private fun mountMixColorTileRegion(api: XposedInterface, classLoader: ClassLoader) {
        if (mTileWrapperGetDrawable == null || mAutoGetViewBlurProxy == null ||
            mViewProxyGetView == null || mViewProxyGetBlurDrawable == null
        ) {
            Log.w(TAG, "Hook B MixColorTileDrawable methods unresolved, tile map fallback disabled")
            return
        }
        try {
            val clazz = Class.forName(CLASS_MIX_COLOR_TILE_DRAWABLE, false, classLoader)
            val method = clazz.getMethod(METHOD_DRAW, Canvas::class.java)
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        recordMixColorTileRegion(chain)
                    } catch (t: Throwable) {
                        Log.e(TAG, "MixColorTileDrawable.draw: intercept error", t)
                    }
                    // [任务 T] 移除玻璃之上的 maskColor 磨砂覆盖层（仅 z=true 时干预；失败回退原始含磨砂）
                    var handled = false
                    try {
                        handled = skipTileMaskColorLayer(chain)
                    } catch (t: Throwable) {
                        Log.e(TAG, "MixColorTileDrawable.draw: skipTileMaskColorLayer error", t)
                    }
                    if (!handled) chain.proceed()
                }
            Log.i(TAG, "Hook B mounted: $CLASS_MIX_COLOR_TILE_DRAWABLE#$METHOD_DRAW(Canvas) (tile map fallback + mask layer skip)")
        } catch (t: Throwable) {
            Log.e(TAG, "Hook B mount FAILED: $CLASS_MIX_COLOR_TILE_DRAWABLE#$METHOD_DRAW", t)
        }
    }

    /** hookBefore：读 viewBlurProxy → hostView 屏幕位置 + bounds → 存 drawable → 屏幕区域映射 */
    private fun recordScreenRegion(chain: XposedInterface.Chain) {
        val auto = try {
            chain.getThisObject()
        } catch (t: Throwable) {
            Log.e(TAG, "map: getThisObject failed", t); return
        } ?: return
        registerAutoBlurScreenRegion(auto)
    }

    /**
     * [任务 2/3] AutoBlurDrawable 子类（普通 tile / LightStyleStrokeDrawable）统一注册入口。
     * MixColorTileDrawable.draw（任务 3）取出内层 AutoBlurDrawable 后也走这里。
     */
    private fun registerAutoBlurScreenRegion(auto: Any): Boolean {
        val proxy = try {
            mAutoGetViewBlurProxy?.invoke(auto)
        } catch (t: Throwable) {
            Log.e(TAG, "map: getViewBlurProxy failed", t); return false
        } ?: return false
        val hostView = try {
            mViewProxyGetView?.invoke(proxy) as? View
        } catch (t: Throwable) {
            Log.e(TAG, "map: getView failed", t); return false
        } ?: return false
        val defaultDrawable = try {
            mAutoDefaultDrawable?.get(auto) as? Drawable
        } catch (t: Throwable) {
            null
        }
        val autoBounds = try {
            mAutoGetBounds?.invoke(auto) as? Rect
        } catch (t: Throwable) {
            Log.e(TAG, "map: getBounds(AutoBlurDrawable) failed", t); null
        }
        return registerScreenRegionCore(proxy, hostView, defaultDrawable, autoBounds, "map")
    }

    /**
     * [任务 2] 通知侧滑操作按钮（删除/设置/会话）专属映射。
     *
     * 逆向确认（16.1 反编译，notification\row\NotificationMenuRowExtImpl）：
     * - `getMenuItemBackground`（line 267）平台静态模糊档创建 `new MaskBlurDrawable(viewBlurProxy, drawable, 0)`
     *   （line 284）设为菜单项 View 背景。
     * - `MaskBlurDrawable.draw(Canvas)`（line 32-36）：`getDrawable() = viewBlurProxy.getBlurDrawable(null)`
     *   → PlatformBlurDrawable → posteffect **MetaBallBlurDrawable**（NotificationMenuRowMetaBallController2
     *   line 61-62 `tryGetMetaBallBlurDrawable(view.getBackground())` 实证），随后直接
     *   `drawable.setBounds(getBounds()); drawable.draw(canvas)` → drawBlurShader。
     *   路径**不经 AutoBlurDrawable.draw**（MaskBlurDrawable 不是 AutoBlurDrawable 子类）→
     *   recordScreenRegion 从不触发 → 侧滑按钮恒 map miss（真机 bounds=Rect(-69,0-309,262)）。
     * 修复：单独 hook `MaskBlurDrawable.draw(Canvas)`，复用 registerScreenRegionCore 注册。
     * 始终 proceed（不拦截绘制）；失败只影响侧滑按钮映射（回退系统模糊），不崩。
     */
    private fun recordSwipeMenuRegion(chain: XposedInterface.Chain) {
        val mask = try {
            chain.getThisObject()
        } catch (t: Throwable) {
            Log.e(TAG, "swipe: getThisObject failed", t); return
        } ?: return
        val proxy = try {
            mMaskGetViewBlurProxy?.invoke(mask)
        } catch (t: Throwable) {
            Log.e(TAG, "swipe: getViewBlurProxy failed", t); return
        } ?: return
        val hostView = try {
            mViewProxyGetView?.invoke(proxy) as? View
        } catch (t: Throwable) {
            Log.e(TAG, "swipe: getView failed", t); return
        } ?: return
        registerScreenRegionCore(proxy, hostView, null, null, "swipe")
    }

    /**
     * [任务 3] 普通 1x1 QS tile 背景映射兜底。
     *
     * 逆向确认（16.1，com.oplus.systemui.qs.base.res.drawable.MixColorTileDrawable）：
     * - tile mBg 背景 = TileDrawableWrapper（MixColorTileDrawable 等）包装 **AutoBlurDrawable**
     *   （`new MixColorTileDrawable(new AutoBlurDrawable(viewBlurProxy, null), ...)` line 383）。
     * - `MixColorTileDrawable.draw(Canvas)`（line 432）内部**条件调用** `super.draw(canvas)`
     *   （`(!maskAlphaNonZero || animator.isRunning()) && platformMixConfig!=None || isDeforming()`）：
     *   不透明纯色 mask 稳态时不调 super.draw → 只画 mask path，内层 AutoBlurDrawable.draw 不触发
     *   → 该 tile 的 posteffect BlurDrawable（真机 drawable=BlurDrawable bounds=192x192 圆开关）映射
     *   缺失/陈旧 → 恒 "no screen-region map"。
     * 修复：单独 hook `MixColorTileDrawable.draw(Canvas)`，取出内层 AutoBlurDrawable
     *   （TileDrawableWrapper/DrawableWrapper.getDrawable()）强制走 registerAutoBlurScreenRegion。
     * 与 AutoBlurDrawable.draw hook 可能同帧双注册（同 key 幂等覆盖，无副作用）。
     * 始终 proceed；失败只影响该 tile 映射（回退系统模糊），不崩。
     */
    private fun recordMixColorTileRegion(chain: XposedInterface.Chain) {
        val mix = try {
            chain.getThisObject()
        } catch (t: Throwable) {
            Log.e(TAG, "tile: getThisObject failed", t); return
        } ?: return
        val inner = try {
            mTileWrapperGetDrawable?.invoke(mix)
        } catch (t: Throwable) {
            Log.e(TAG, "tile: getDrawable failed", t); return
        } ?: return
        registerAutoBlurScreenRegion(inner)
    }

    /**
     * [任务 T] 移除 MixColorTileDrawable 玻璃之上的半透明 maskColor 磨砂覆盖层。
     *
     * 反编译实证（16.1，MixColorTileDrawable.draw line 431-446）：
     * ```
     * int i2 = ((i << 8) >>> 8) | ((((i >>> 24) * (alpha + (alpha >> 7))) >> 8) << 24);  // i = this.maskColor
     * boolean z = (i2 >>> 24) != 0;              // maskColor alpha 与 drawable alpha 合成后非透明
     * if (((!z || animator.isRunning()) && !zAreEqual) || isDeforming()) super.draw(canvas);  // 玻璃底座
     * if (z) { paint.setColor(i2); canvas.drawPath(path, paint); }  // 半透明 maskColor 磨砂覆盖层
     * ```
     * 模块只替换了模糊 shader（玻璃底座），没移除系统这层半透明状态色调 → 真机"磨砂盖在玻璃上"。
     *
     * 拦截策略：仅当 z=true（合成 alpha 非 0，系统会画覆盖层）时，不 proceed 原 draw，改为手动调内层
     * AutoBlurDrawable.draw(canvas)——等效 DrawableWrapper.super.draw（DrawableWrapper.draw 即
     * mDrawable.draw），只画玻璃底座并跳过 maskColor 层。z=false 时天然无覆盖层 → 返回 false 走原始。
     * maskColor 字段反射失败（null）→ 不做 z 判断，恒拦截（兜底：z=false 的 tile 画玻璃也无害）。
     * 任何失败返回 false（调用方 proceed 回退系统原样，含磨砂），强兜底不崩。
     *
     * @return true = 已拦截（玻璃已画、maskColor 覆盖层已跳过）；false = 未处理（调用方应 proceed）
     */
    private fun skipTileMaskColorLayer(chain: XposedInterface.Chain): Boolean {
        val mix = try {
            chain.getThisObject()
        } catch (t: Throwable) {
            Log.e(TAG, "tile: getThisObject failed", t); null
        } ?: return false
        val canvas = try {
            chain.getArg(0) as? Canvas
        } catch (t: Throwable) {
            Log.e(TAG, "tile: getArg(0) failed", t); null
        } ?: return false
        // 仅 z=true 才需干预：复刻 draw 内 z 判定（maskColor alpha 与 drawable alpha 合成后非透明）
        val maskColorField = mTileMaskColor
        if (maskColorField != null) {
            val z = try {
                val maskColor = maskColorField.getInt(mix)
                val alpha = (mix as? Drawable)?.alpha ?: 255
                val maskAlpha = (maskColor ushr 24) and 0xFF
                if (maskAlpha == 0 || alpha == 0) {
                    false
                } else {
                    // (maskAlpha * (alpha + (alpha shr 7))) shr 8 —— 与反编译 `i2 >>> 24 != 0` 等价
                    val compAlpha = (maskAlpha * (alpha + (alpha shr 7))) shr 8
                    (compAlpha and 0xFF) != 0
                }
            } catch (t: Throwable) {
                Log.e(TAG, "tile: read maskColor failed, treat as inactive (no skip)", t)
                false
            }
            if (!z) return false
        }
        val inner = try {
            mTileWrapperGetDrawable?.invoke(mix) as? Drawable
        } catch (t: Throwable) {
            Log.e(TAG, "tile: getDrawable failed", t); null
        } ?: return false
        return try {
            // 等效 super.draw(canvas)（DrawableWrapper.draw = mDrawable.draw）：只画内层玻璃底座
            inner.draw(canvas)
            logThrottled("tile-mask-skip", Log.DEBUG) { "tile: maskColor layer skipped, glass only (id=${System.identityHashCode(inner)})" }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "tile: direct glass draw failed, fallback original (with mask layer)", t)
            false
        }
    }

    /**
     * [折叠组头磨砂 2026-08-12] hook NotificationHeaderMaskColorDrawable.draw(Canvas)，跳过 maskColor 色层。
     *
     * 逆向实证（c16.1 NotificationChildrenContainerExtImp.java line 82-98）：折叠组头背景 =
     * NotificationHeaderMaskColorDrawable，draw() 画完内层 drawable 后 **动态叠加 maskColor 色层**
     * （`int v = maskColor.invoke(); canvas.drawColor(v)`，line 93-97）。折叠组展开动画时 maskColor
     * 非 0 → 整组头覆盖半透明磨砂，动画后归 0 → 变清晰（与用户观察"盖一层半透明磨砂，过会儿变清晰"吻合）。
     *
     * 拦截策略：不 proceed，手动 inner.draw（内层 drawable，玻璃/材质底座）跳过 maskColor 色层。
     * 内层反射 this.drawable 私有字段。失败 → proceed 原样（含磨砂），强兜底不崩。
     */
    private fun mountNotifHeaderMaskColor(api: XposedInterface, classLoader: ClassLoader) {
        try {
            val clazz = Class.forName(CLASS_NOTIF_HEADER_MASK, false, classLoader)
            mHeaderMaskInner = clazz.getDeclaredField("drawable").apply { isAccessible = true }
            val method = clazz.getMethod(METHOD_DRAW, Canvas::class.java)
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        if (!skipHeaderMaskColor(chain)) {
                            chain.proceed()
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "header-mask: intercept error", t)
                        chain.proceed()
                    }
                }
            Log.i(TAG, "Hook B mounted: $CLASS_NOTIF_HEADER_MASK#$METHOD_DRAW(Canvas) (fold-group maskColor skip)")
        } catch (t: Throwable) {
            Log.e(TAG, "Hook B mount FAILED: $CLASS_NOTIF_HEADER_MASK#$METHOD_DRAW (fold-group keeps mask layer)", t)
        }
    }

    /** [折叠组头磨砂] 辅助：手动 inner.draw（内层玻璃/材质底座）跳过 maskColor 色层。返回 true=已拦截。 */
    private fun skipHeaderMaskColor(chain: XposedInterface.Chain): Boolean {
        val d = try {
            chain.getThisObject()
        } catch (t: Throwable) {
            Log.e(TAG, "header-mask: getThisObject failed", t); null
        } ?: return false
        val canvas = try {
            chain.getArg(0) as? Canvas
        } catch (t: Throwable) {
            Log.e(TAG, "header-mask: getArg(0) failed", t); null
        } ?: return false
        val field = mHeaderMaskInner ?: return false
        val inner = try {
            field.get(d) as? Drawable
        } catch (t: Throwable) {
            Log.e(TAG, "header-mask: read inner drawable failed", t); null
        } ?: return false
        return try {
            val bounds = (d as? Drawable)?.bounds
            if (bounds != null) inner.setBounds(bounds)
            inner.draw(canvas)
            logThrottled("header-mask-skip", Log.INFO) { "header-mask: maskColor layer skipped, inner=${inner.javaClass.simpleName} bounds=${inner.bounds}" }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "header-mask: inner draw failed, fallback proceed (with mask layer)", t)
            false
        }
    }

    /**
     * [MetaBall gradient 色层 2026-08-12] hook MetaBallBlurDrawable#setMetaColor(int)，跳过。
     *
     * 逆向实证（c16.1 MetaBallBlurDrawable.java line 416-417 + PlatformBlurDrawable.java line 275-283）：
     * PlatformBlurDrawable.setOverlayColor → MetaBallBlurDrawable.setMetaColor(i) → setGradientColors(TOP_BOTTOM,[i])
     * → 写 shader uniform（setMetaBallGradientColorUniform）。这是**shader 内色层**，绕开所有独立
     * drawable.draw 的 hook 点。点击/展开卡片时系统走 OverlayColor 叠加 gradient 色罩，动画后恢复
     * config → 色层清除（"盖一层半透明磨砂、过会儿变清晰"吻合）。8 个独立 drawable 候选已全排除，
     * 这是 shader 内唯一未试的色层。失败 → 不挂，不影响其它。
     */
    private fun mountMetaColorSuppress(api: XposedInterface, classLoader: ClassLoader) {
        try {
            val clazz = Class.forName("com.oplus.posteffect.drawable.MetaBallBlurDrawable", false, classLoader)
            val method = clazz.getMethod("setMetaColor", Int::class.javaPrimitiveType)
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    Log.i(TAG, "meta-color: skip setMetaColor (gradient overlay suppress)")
                    null
                }
            Log.i(TAG, "Hook B mounted: MetaBallBlurDrawable#setMetaColor(int) (gradient overlay suppress)")
        } catch (t: Throwable) {
            Log.e(TAG, "Hook B mount FAILED: MetaBallBlurDrawable#setMetaColor (gradient layer keeps)", t)
        }
    }

    /**
     * [任务 2/3] screenRegionMap 注册核心（AutoBlurDrawable / MaskBlurDrawable 共用）。
     *
     * ①[MetaBallBlurDrawable 固有扩张] bounds 左/上可为负（侧滑按钮混合区左右各扩 ~69），是
     *   setBounds 固有扩张而非 View 在屏外——region **恒用 loc+bounds（含负偏移，不换 hostView
     *   矩形）**，srcRect 侧 clamp 兜底屏外边缘。
     * ②[C3] bounds 一致性：map 侧与 render 侧同源（posteffect drawable getBounds）。
     * ③ 任何一步失败返回 false（调用方忽略，回退系统模糊，不崩）。
     */
    private fun registerScreenRegionCore(
        proxy: Any,
        hostView: View,
        defaultDrawable: Drawable?,
        autoBounds: Rect?,
        logTag: String,
    ): Boolean {
        // [任务 G] 顺手登记宿主 View：运动事件源（QS 翻页/面板展开）触发时 invalidate 它，
        // 强制重录 draw → 映射随新位置更新（WeakReference 防泄漏，去重见 registerHostView）
        registerHostView(hostView)
        val loc = IntArray(2)
        try {
            hostView.getLocationOnScreen(loc)
        } catch (t: Throwable) {
            Log.e(TAG, "$logTag: getLocationOnScreen failed", t); return false
        }
        // [2026-08-13 反射结果缓存] proxy 的 blurDrawable/target 解析结果稳定（同一 drawable 实例每次
        // draw 相同），缓存命中后跳过 getBlurDrawable + platformGetBlurDrawable 反射链，主线程只做
        // getLocationOnScreen + getBounds + region 算术（省控制中心每帧 40-60 次反射）。
        val proxyKey = System.identityHashCode(proxy)
        val cachedTarget = registerTargetCache[proxyKey]?.target
        val target: Any
        if (cachedTarget != null) {
            target = cachedTarget
        } else {
            val blurDrawable = try {
                mViewProxyGetBlurDrawable?.invoke(proxy, defaultDrawable)
            } catch (t: Throwable) {
                Log.e(TAG, "$logTag: getBlurDrawable(Drawable) failed", t); return false
            } ?: return false
            // PlatformBlurDrawable（包装层）→ 取内部 posteffect BlurDrawable（drawBlurShader 的 this 同实例）
            val resolved = if (mPlatformGetBlurDrawable != null &&
                mPlatformGetBlurDrawable!!.declaringClass.isInstance(blurDrawable)
            ) {
                try {
                    mPlatformGetBlurDrawable?.invoke(blurDrawable) ?: blurDrawable
                } catch (t: Throwable) {
                    Log.e(TAG, "$logTag: PlatformBlurDrawable.getBlurDrawable() failed", t); return false
                }
            } else {
                blurDrawable
            }
            if (registerTargetCache.size >= REGISTER_TARGET_CACHE_MAX) registerTargetCache.clear()
            registerTargetCache[proxyKey] = RegisterTargetCache(resolved)
            target = resolved
        }
        // [spec/38 修复 2026-08-13] ScrimView（面板背景 scrim，ScrimControllerExImp.refreshBehindDrawable 的
        // hostView）恒透明不渲染玻璃：**不写入 screenRegionMap**——否则全屏 region 令 isOverGlassRegion 对
        // 状态栏时钟等一切坐标判"玻璃区域" → 文字变色（mask 强制白 / 采样分支注入黑）。region 无渲染用途：
        // 渲染侧 replaceShaderIfPossible 在 buildLiquidShader 之前就经 isBackgroundScrimById 把 paint 置透明短路。
        // 仅登记 posteffect drawable id 到 scrimDrawableIds，保住该渲染兜底（drawBlurShader 直绘路径不经验证
        // 拦截时 paint 置透明露出壁纸）。判定与 isBackgroundScrimDrawable 同款（full class name contains ScrimView）。
        if (hostView.javaClass.name.contains("ScrimView")) {
            scrimDrawableIds.add(System.identityHashCode(target))
            // 防膨胀：scrim drawable 面板开/关/主题变化会重建，id 累积；清空后下次 draw 自动重登记
            //（同 screenRegionMap >1024 clear 语义，见 registerScreenRegionCore）
            if (scrimDrawableIds.size > 1024) scrimDrawableIds.clear()
            return true
        }
        val targetBounds = try {
            mGetBounds?.invoke(target) as? Rect
        } catch (t: Throwable) {
            Log.e(TAG, "$logTag: getBounds(posteffect) failed", t); null
        }
        val bounds = targetBounds ?: autoBounds ?: Rect(0, 0, hostView.width, hostView.height)
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            logThrottled("$logTag-skip-empty-bounds", Log.DEBUG) { "$logTag: skip empty bounds auto=$autoBounds target=$targetBounds, id=${System.identityHashCode(target)}" }
            return false
        }
        // [MetaBallBlurDrawable 固有扩张] bounds 左/上可负（混合区扩张 ~69px）——region 恒用
        // loc+bounds（含负偏移，不换 hostView 矩形）；srcRect 侧 clamp 兜底屏外边缘
        val region = RectF(
            (loc[0] + bounds.left).toFloat(),
            (loc[1] + bounds.top).toFloat(),
            (loc[0] + bounds.right).toFloat(),
            (loc[1] + bounds.bottom).toFloat(),
        )
        val key = System.identityHashCode(target)
        // [任务 D] 真实圆角半径：反射 ViewBlurProxy.getBlurConfig()（tile 圆=w/2、胶囊=h/2、
        // 卡片≈60-84px），失败回退启发式（bounds 形状推断），全程 try-catch 不崩溃。
        // 侧滑按钮（swipe）例外：跳过 BlurConfig 反射（拿的是卡片圆角 60-84px，不对），改用
        // R.dimen.notification_corner_radius（16dp，NotificationMenuRowMetaBallController2:36 实证）。
        // [spec/18 修正] 非 swipe 元素 resolve 返回 CONIC 判定（cornerIsConic/cornerWeight）；swipe
        // 保持现状：本体 RBox 圆弧 + Kotlin 侧 ×1.5（isConic=false，避免 shader 内二次 ×1.5）。
        val corner = if (logTag == "swipe") {
            val (r, s) = resolveSwipeCornerRadius(hostView)
            // [2026-08-13 边缘闪+生硬切开修复] swipe 本体 CONIC 化：系统真实角 = CONIC（spec/12 实证
            // cc=1.5×notification_corner_radius=96、weight=1.3968，近乎方形）。此前本体恒 RBox 圆弧
            // （isConic=false）+ 掩码 CONIC → 掩码方角切本体圆弧 = "两侧生硬切开"；且本体 blend 渐变
            // 与掩码恒 CONIC 形状冲突 = 停止滑动后边缘闪。改本体 CONIC + weight 同掩码 → 本体≡掩码
            // （静止全 CONIC / 运动态全圆弧 / 中间同步渐混）→ 不切不闪。spec/12 :30「本体复刻 CONIC」落地。
            CornerInfo(r, s, isConic = r > 0f, weight = DEFAULT_CORNER_WEIGHT)
        } else {
            resolveCornerRadius(proxy, bounds, hostView)
        }
        // [2026-08-13 修复] swipe 本体不再 Kotlin 侧 ×1.5：shader CONIC 分支 sdBezierDistance 内自 ×1.5
        //（cc=1.5×uCornerRadius=96），与掩码 cc=1.5×uMaskCornerRadius 一致；圆弧路径（运动态 blend=0）
        // 本体角 = raw 64 也与掩码圆弧 64 同角不切边。非 swipe 保持原语义（CONIC shader ×1.5 / 圆弧原值）。
        val bodyCornerRadius = corner.radius
        val bodyCornerSource = corner.source
        // [侧滑按钮掩码] swipe 按钮：MetaBallBlurDrawable.setBounds 左右各扩 boundsExtension（真机 69px）
        // → viewport 含扩张区。掩码矩形 = 按钮实际区域（hostView 尺寸，viewport 局部坐标），扩张区
        // 之外 alpha=0（对齐系统 mix_c 裁剪语义）。其他 drawable 不裁剪（maskRect=null，shader 默认全视口）。
        val maskRect = if (logTag == "swipe") computeSwipeMaskRect(bounds, hostView) else null
        val entry = RegionEntry(
            region, bounds, hostView.javaClass.simpleName, bodyCornerRadius, bodyCornerSource,
            cornerIsConic = corner.isConic, cornerWeight = corner.weight,
            maskRect = maskRect,
            maskCornerRadius = if (maskRect != null) corner.radius else 0f,
            hostViewRef = WeakReference(hostView),
        )
        val previous = screenRegionMap[key]
        // [doc/spec/24 任务 B 2026-08-13] 内容变化检测：仅新元素首次出现（previous==null）触发整屏快照
        // 重抓；位置/尺寸更新仅写表不重抓（背景为静态壁纸快照，srcRect 渲染侧实时折算已跟手，滑动无需
        // 每帧重抓整屏 → 运动中/AOD 静止零抓屏，消除 CPU 热点排序 #1）
        if (previous == null) {
            screenRegionMap[key] = entry
            // [coord 降级] 详细坐标只在首次注册打（追踪器驱动重录时每帧回调不刷屏）；
            // 后续位置变化只打 Log.d 级 map: updated（默认 logcat 不可见）
            Log.i(
                TAG,
                "coord: $logTag id=$key view=${hostView.javaClass.simpleName} loc=[${loc[0]},${loc[1]}] " +
                    "autoBounds=$autoBounds targetBounds=$targetBounds bounds=$bounds region=$region " +
                    "cornerRadius=$bodyCornerRadius source=$bodyCornerSource"
            )
            // map 防膨胀：drawable 数量远小于此，仅防异常泄漏。阈值 1024（原 256 偏小：QS tile +
            // 通知卡片 + 侧滑按钮 + 各类控件易触发整体 clear → 稳态 tile 映射被清后再不重注册 → 永久
            // "no screen-region map"；1024 显著降低误清概率，内存仍有限界）
            if (screenRegionMap.size > 1024) screenRegionMap.clear()
            // [doc/spec/07 + 2026-08-13] 新映射建立 = 内容变化信号 → 触发整屏快照重抓
            //（registerHostView 时映射尚未写入，抓屏触发只能在此处；立即重抓见 triggerBackgroundCapture）
            triggerBackgroundCapture()
        } else if (previous.region != region || previous.autoBounds != bounds) {
            screenRegionMap[key] = entry
            logThrottled("$logTag-region-updated", Log.DEBUG) { "$logTag: updated screen region id=$key region=$region bounds=$bounds" }
            // map 防膨胀（同上）
            if (screenRegionMap.size > 1024) screenRegionMap.clear()
            // 位置/尺寸更新仅写表不重抓（静态壁纸背景，srcRect 实时折算已跟手）
        }
        return true
    }

    // ------------------------------------------------------------ 强制透明诊断档：背景 scrim（决策 7）

    /**
     * [强制透明诊断档] 判定 AutoBlurDrawable 是否为背景 scrim（ScrimView）。
     * 反编译实证（ScrimControllerExImp.refreshBehindDrawable line 1022-1023，16.1）：
     * `scrimBehind.setDrawable(new AutoBlurDrawable(viewBlurProxy, new MaskDrawable(...)))`，
     * hostView = `getScrimController().getScrimBehind()`（com.android.systemui.scrim.ScrimView）。
     * 命中 → AutoBlurDrawable.draw 拦截时不 proceed，跳过整条背景模糊链，背景完全透明露出壁纸。
     * 反射失败 → false（不拦截，回退现状），不崩溃。
     */
    private fun isBackgroundScrimDrawable(chain: XposedInterface.Chain): Boolean {
        val auto = try {
            chain.getThisObject()
        } catch (t: Throwable) {
            Log.e(TAG, "bg: getThisObject failed", t); return false
        } ?: return false
        val proxy = try {
            mAutoGetViewBlurProxy?.invoke(auto)
        } catch (t: Throwable) {
            Log.e(TAG, "bg: getViewBlurProxy failed", t); return false
        } ?: return false
        val hostView = try {
            mViewProxyGetView?.invoke(proxy) as? View
        } catch (t: Throwable) {
            Log.e(TAG, "bg: getView failed", t); return false
        } ?: return false
        return hostView.javaClass.name.contains("ScrimView")
    }

    /**
     * [强制透明诊断档] drawBlurShader 兜底判定：screenRegionMap 已登记 hostView 为 ScrimView 的
     * drawable（key=posteffect drawable identityHashCode，与 render 侧同源）。命中 → paint 置透明。
     * [spec/38 2026-08-13] scrim 不再写入 screenRegionMap → 改查独立 scrimDrawableIds（registerScreenRegionCore
     * 检测 ScrimView 时登记）；保留 map 判定作防御（历史条目 / 未来其他路径登记）。
     */
    private fun isBackgroundScrimById(drawable: Any): Boolean {
        val id = System.identityHashCode(drawable)
        return scrimDrawableIds.contains(id) ||
            screenRegionMap[id]?.hostViewName?.contains("Scrim") == true
    }

    // ------------------------------------------------------------ Hook -1b：通知卡片专属映射（C1）

    /**
     * C1（2026-08-12 真机实锤修复）：通知卡片模糊背景专属映射。
     *
     * 逆向确认（16.1 反编译源码，SystemUI\sources）：
     * - `NotificationBackgroundView.onDraw(Canvas)`（line 218-231）→ oplus 样式走
     *   `mExt.draw(canvas, mBackground)`（`NotificationBackgroundViewExt.draw` line 53 默认实现
     *   `getBgView().draw(canvas, drawable)`；`NotificationBackgroundViewExtImp.draw(Canvas, Drawable)`
     *   line 174 覆写，jadx `--show-bad-code` 实证）。
     * - ExtImp.draw 内部：`drawable2 = viewBlurProxy.getBlurDrawable(drawable)`（line 202-203，
     *   PlatformStatic 时返回 `PlatformBlurDrawable`）→ 各分支 `drawable2.setBounds(...)` +
     *   `drawable2.draw(canvas)`（line 298/334/365），**不经 AutoBlurDrawable.draw** →
     *   原 screenRegionMap 从无通知卡片映射 → 等宽卡片 bounds 兜底串图（全黑/同斑块）。
     * - `ViewBlurProxy.getBlurDrawable(Drawable)`（line 435）：PlatformStatic → `PlatformBlurDrawable`；
     *   `PlatformBlurDrawable.getBlurDrawable()`（line 210）→ posteffect `BlurDrawable`
     *   （drawBlurShader hook 的 this 同实例，identityHashCode 可作查表键）。
     * - 卡片区域：bgView（NotificationBackgroundView，getBgView() 返回）屏幕位置 + posteffect
     *   drawable getBounds（render 侧同款，C3 对齐）。
     *
     * 反射/挂载失败只影响通知卡片映射（回退系统模糊 = C2 宁缺毋滥安全默认），不影响其他 hook。
     */
    private fun mountNotificationBackground(api: XposedInterface, classLoader: ClassLoader) {
        if (mNbvGetBgView == null || mNbvGetViewBlurProxy == null ||
            mViewProxyGetBlurDrawable == null || mGetBounds == null
        ) {
            Log.w(TAG, "Hook B NotificationBackgroundView methods unresolved, notification card map disabled")
            return
        }
        try {
            val clazz = Class.forName(CLASS_NBV_EXT_IMP, false, classLoader)
            val method = clazz.getMethod(METHOD_DRAW_NOTIF_BG, Canvas::class.java, Drawable::class.java)
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        recordNotificationCardRegion(chain)
                    } catch (t: Throwable) {
                        Log.e(TAG, "NotificationBackgroundViewExtImp.draw: intercept error", t)
                    }
                    // [磨砂根因·修 1] proceed 前临时把 ViewBlurManager.currentMaterialColor 置透明 →
                    // 稳态分支 materialColorDrawable.setTint(透明) 不覆盖玻璃；suppress 内部
                    // try-finally 保证 proceed 后恢复原值（防状态污染）。反射失败 → proceed 原样（含磨砂）。
                    suppressNotificationMaterialColor(chain)
                }
            Log.i(TAG, "Hook B mounted: $CLASS_NBV_EXT_IMP#$METHOD_DRAW_NOTIF_BG(Canvas, Drawable) (notification card map)")
        } catch (t: Throwable) {
            Log.e(TAG, "Hook B mount FAILED: $CLASS_NBV_EXT_IMP#$METHOD_DRAW_NOTIF_BG", t)
        }
    }

    /**
     * [磨砂根因·修 1] 通知主背景磨砂移除：proceed 前临时把 ViewBlurManager.currentMaterialColor 置透明。
     *
     * 根因（反编译实证，NotificationBackgroundViewExtImp.java）：
     * draw(Canvas, Drawable) 稳态分支（isBPAnimLevel() && Color.alpha(currentMaterialColor)>0 &&
     * noColorized()）在玻璃（drawable2.draw）之后画 materialColorDrawable
     * （res/drawable/notification_stacking_material_color_bg.xml，16dp 圆角 shape）并
     * `setTint(currentMaterialColor)` 覆盖整卡 → 半透明材质色层盖在液态玻璃之上（真机磨砂根因）。
     * currentMaterialColor 存储于 `ViewBlurManager.currentMaterialColor`（public int，
     * ViewBlurManager.java line 117；setter setCurrentMaterialColor line 1118 会
     * notifyMaterialColorChanged → invalidate → 重跑 draw）。ExtImp.viewBlurManager 是 public final 字段。
     *
     * 拦截策略：proceed **前**把字段置 0x00000000（透明）→ 稳态分支 setTint(透明) → 覆盖层不渲染；
     * proceed **后** finally 恢复原值（防状态污染，其他消费方不受影响）。反射/字段任一环节失败 →
     * 直接 proceed 原样（含磨砂），强兜底不崩。注意：early-return 路径必须显式 proceed，
     * 否则会拦截掉整条通知卡背景绘制。
     */
    private fun suppressNotificationMaterialColor(chain: XposedInterface.Chain) {
        val extImp = try {
            chain.getThisObject()
        } catch (t: Throwable) {
            Log.e(TAG, "fuzzy: getThisObject failed", t)
            chain.proceed(); return
        }
        if (extImp == null) {
            chain.proceed(); return
        }
        val vbm = try {
            mNbvViewBlurManager?.get(extImp)
        } catch (t: Throwable) {
            Log.e(TAG, "fuzzy: read viewBlurManager failed", t); null
        }
        // [修3] clickAlpha（点击暗层 clickEffectDrawable alpha，点击动画后归 0）+ stackedNotificationMuskColor
        // （折叠/堆叠动画 oplusColorBg 色层，progress→0 后消失）——置 0 拦截，finally 恢复。独立于 vbm。
        val origClick = try { mExtImpClickAlpha?.getInt(extImp) } catch (t: Throwable) { Log.e(TAG, "fuzzy: read clickAlpha failed", t); null }
        val origStack = try { mExtImpStackedMuskColor?.getInt(extImp) } catch (t: Throwable) { Log.e(TAG, "fuzzy: read stackedNotificationMuskColor failed", t); null }
        if (origClick != null && origClick != 0) {
            try { mExtImpClickAlpha?.setInt(extImp, 0) } catch (t: Throwable) { Log.e(TAG, "fuzzy: clear clickAlpha failed", t) }
        }
        if (origStack != null && origStack != 0) {
            try { mExtImpStackedMuskColor?.setInt(extImp, 0) } catch (t: Throwable) { Log.e(TAG, "fuzzy: clear stackedNotificationMuskColor failed", t) }
        }
        val original = try {
            mViewBlurManagerMaterialColor?.getInt(vbm)
        } catch (t: Throwable) {
            Log.e(TAG, "fuzzy: read currentMaterialColor failed", t); null
        }
        if (original != null && original != 0) {
            try {
                mViewBlurManagerMaterialColor?.setInt(vbm, 0)
            } catch (t: Throwable) {
                Log.e(TAG, "fuzzy: clear currentMaterialColor failed", t)
            }
        }
        try {
            chain.proceed()
        } finally {
            if (original != null) {
                try { mViewBlurManagerMaterialColor?.setInt(vbm, original) } catch (t: Throwable) { Log.e(TAG, "fuzzy: restore currentMaterialColor failed", t) }
            }
            if (origClick != null) {
                try { mExtImpClickAlpha?.setInt(extImp, origClick) } catch (t: Throwable) { Log.e(TAG, "fuzzy: restore clickAlpha failed", t) }
            }
            if (origStack != null) {
                try { mExtImpStackedMuskColor?.setInt(extImp, origStack) } catch (t: Throwable) { Log.e(TAG, "fuzzy: restore stackedNotificationMuskColor failed", t) }
            }
        }
    }

    /**
     * [磨砂根因·修 2] 控件磨砂移除：hook PlatformBlurDrawable.draw(Canvas)，proceed 前把 blurMaskColor 置 0。
     *
     * 根因（反编译实证，platformblur\PlatformBlurDrawable.java line 201-208）：
     * ```
     * public void draw(Canvas canvas) {
     *     this.blurDrawable.setBounds(getBounds());
     *     this.blurDrawable.draw(canvas);            // 玻璃底座
     *     if (this.blurMaskColor == 0 || this.maskPaint.getAlpha() == 0 || !this.enableBlurMaskColor) return;
     *     canvas.drawRect(getBounds(), this.maskPaint);  // blurMaskColor 半透明覆盖层（磨砂）
     * }
     * ```
     * blurMaskColor（public int，line 36）仅在 BlurMixConfig.OverlayColor 场景被置非 0
     * （applyBlurConfig line 88 `setBlurMaskColor(blurColor)`）；通知卡 config=BlurMixMulti/WithShader
     * 时恒 0 不画，但 colorized 展开动画等可能短暂走 OverlayColor → 触发磨砂层。
     *
     * 拦截策略：proceed 前把 blurMaskColor 置 0 → draw 内部条件 `blurMaskColor == 0` 直接 return，
     * 跳过 drawRect；finally 恢复原值。失败 → proceed 原样（强兜底不崩）。
     */
    private fun mountPlatformBlurMaskColor(api: XposedInterface, classLoader: ClassLoader) {
        if (mPlatformBlurMaskColor == null) {
            Log.w(TAG, "Hook B PlatformBlurDrawable.blurMaskColor unresolved, mask-layer suppress disabled")
            return
        }
        try {
            val clazz = Class.forName(CLASS_PLATFORM_BLUR_DRAWABLE, false, classLoader)
            val method = clazz.getMethod(METHOD_DRAW, Canvas::class.java)
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    suppressPlatformBlurMaskColor(chain)
                }
            Log.i(TAG, "Hook B mounted: $CLASS_PLATFORM_BLUR_DRAWABLE#$METHOD_DRAW(Canvas) (blurMaskColor mask-layer suppress)")
        } catch (t: Throwable) {
            Log.e(TAG, "Hook B mount FAILED: $CLASS_PLATFORM_BLUR_DRAWABLE#$METHOD_DRAW (mask-color layer keeps)", t)
        }
    }

    /** [磨砂根因·修 2] 辅助：proceed 前置 blurMaskColor=0（跳过 drawRect 磨砂层），finally 恢复。 */
    private fun suppressPlatformBlurMaskColor(chain: XposedInterface.Chain) {
        val pbd = try {
            chain.getThisObject()
        } catch (t: Throwable) {
            Log.e(TAG, "fuzzy: getThisObject failed", t)
            chain.proceed(); return
        }
        if (pbd == null) {
            chain.proceed(); return
        }
        val original = try {
            mPlatformBlurMaskColor?.getInt(pbd)
        } catch (t: Throwable) {
            Log.e(TAG, "fuzzy: read blurMaskColor failed", t); null
        }
        if (original != null && original != 0) {
            try {
                mPlatformBlurMaskColor?.setInt(pbd, 0)
            } catch (t: Throwable) {
                Log.e(TAG, "fuzzy: clear blurMaskColor failed", t)
            }
        }
        try {
            chain.proceed()
        } finally {
            if (original != null) {
                try {
                    mPlatformBlurMaskColor?.setInt(pbd, original)
                } catch (t: Throwable) {
                    Log.e(TAG, "fuzzy: restore blurMaskColor failed", t)
                }
            }
        }
    }

    /** hookBefore：bgView 屏幕位置 + posteffect drawable bounds → 注册通知卡片屏幕区域 */
    private fun recordNotificationCardRegion(chain: XposedInterface.Chain) {
        val extImp = try {
            chain.getThisObject()
        } catch (t: Throwable) {
            Log.e(TAG, "nbv: getThisObject failed", t); return
        } ?: return
        val bgView = try {
            mNbvGetBgView?.invoke(extImp) as? View
        } catch (t: Throwable) {
            Log.e(TAG, "nbv: getBgView failed", t); return
        } ?: return
        val loc = IntArray(2)
        try {
            bgView.getLocationOnScreen(loc)
        } catch (t: Throwable) {
            Log.e(TAG, "nbv: getLocationOnScreen failed", t); return
        }
        val proxy = try {
            mNbvGetViewBlurProxy?.invoke(extImp)
        } catch (t: Throwable) {
            Log.e(TAG, "nbv: getViewBlurProxy failed", t); return
        } ?: return
        val drawableArg = try {
            chain.getArg(1) as? Drawable
        } catch (t: Throwable) {
            Log.e(TAG, "nbv: getArg(1) failed", t); null
        } ?: return
        val blurDrawable = try {
            mViewProxyGetBlurDrawable?.invoke(proxy, drawableArg) as? Drawable
        } catch (t: Throwable) {
            Log.e(TAG, "nbv: getBlurDrawable(Drawable) failed", t); return
        } ?: return
        // 仅 PlatformBlurDrawable 内部 posteffect BlurDrawable（走我们管线）才注册；
        // BackgroundBlurDrawable（framework 自带）不触发 drawBlurShader hook，注册无意义
        if (mPlatformGetBlurDrawable == null || !mPlatformGetBlurDrawable!!.declaringClass.isInstance(blurDrawable)) {
            return
        }
        // [spec/19 修复 2026-08-13] 该 heads-up bgView 的 PlatformBlurDrawable 已确认存在
        // （getBlurDrawable 返回了 PlatformBlurDrawable）→ 解除 isHeadsUpView 排除绕过，恢复系统原判定。
        // 仅当本次绘制的 bgView 正是强制玻璃化的那个 view 才清除（防其他卡片绘制误清）。
        if (headsUpGlassForceView?.get() === bgView) {
            headsUpGlassForceView = null
        }
        val target = try {
            mPlatformGetBlurDrawable?.invoke(blurDrawable)
        } catch (t: Throwable) {
            Log.e(TAG, "nbv: PlatformBlurDrawable.getBlurDrawable() failed", t); return
        } ?: return
        val bounds = try {
            mGetBounds?.invoke(target) as? Rect
        } catch (t: Throwable) {
            Log.e(TAG, "nbv: posteffect getBounds failed", t); null
        } ?: Rect(0, 0, bgView.width, bgView.height)
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            logThrottled("nbv-skip-empty-bounds", Log.DEBUG) { "nbv: skip empty bounds $bounds, id=${System.identityHashCode(target)}" }
            return
        }
        // [Bug 5 方向确认 2026-08-13] 登记 bgView：卡片尺寸/位置变化 → invalidate 宿主 → 重录 draw →
        // 本方法用最新位置/尺寸折算 region。[2026-09-17] 原逐帧追踪器（比对 lastX/lastY/lastW/lastH）
        // 已删除，尺寸变化链路改由 setTranslation / 布局 hook 覆盖。
        // → srcRect 及时跟上。横向滑动期间 setTranslation hook 已保证每帧 invalidate；此登记补全
        // 布局/动画/滚动等其他尺寸变化链路（原 recordNotificationCardRegion 未登记，卡片 region 可能冻结）。
        registerHostView(bgView)
        // [MetaBallBlurDrawable 固有扩张] bounds 左/上可负——region 恒用 loc+bounds（含负偏移）
        // [Bug 5 方向确认 2026-08-13] 玻璃背景固定（系统原样：OplusCustomRow 横向滑动背景固定、内容滑）：
        // 不再按 content translationX（swipeDx）平移 region——玻璃不跟手指走。region = bgView 屏幕位置 +
        // 当前 posteffect bounds。尺寸变化由 setTranslation hook / tracker 触发重录后本方法折算最新尺寸；
        // bounds 滞后一帧的修正由渲染侧 [reconcileRegion] 完成（渲染侧同帧读到的 posteffect bounds 已更新，
        // 按 delta 平移区域对齐最新尺寸——见 buildLiquidShader / refreshShaderUniforms）。
        val region = RectF(
            (loc[0] + bounds.left).toFloat(),
            (loc[1] + bounds.top).toFloat(),
            (loc[0] + bounds.right).toFloat(),
            (loc[1] + bounds.bottom).toFloat(),
        )
        val key = System.identityHashCode(target)
        // [任务 D] 真实圆角半径（反射 BlurConfig / 启发式）；hostView=bgView 供 seekbar 级 6 兜底判定。
        // [spec/18 修正] 通知卡 CONIC 本体判定（gradientStrokeCornerParam.type=CONIC → isConic=true）
        val corner = resolveCornerRadius(proxy, bounds, bgView)
        val entry = RegionEntry(
            region, bounds, "NotificationBackgroundView",
            corner.radius, corner.source,
            cornerIsConic = corner.isConic, cornerWeight = corner.weight,
            hostViewRef = WeakReference(bgView),
        )
        val previous = screenRegionMap[key]
        // [性能·日志节流] 映射变化才写表；coord 明细只在首次注册打（追踪器重录时每帧回调不刷屏）
        // [doc/spec/24 任务 B 2026-08-13] 内容变化检测：仅新卡片首次出现（previous==null）触发整屏快照
        // 重抓；位置/尺寸更新仅写表不重抓（背景为静态壁纸快照，srcRect 渲染侧实时折算已跟手，滚动无需
        // 每帧重抓整屏 → 通知卡滚动 CPU 热点排序 #1 消除）
        if (previous == null) {
            screenRegionMap[key] = entry
            Log.i(
                TAG,
                "coord: notif-map id=$key view=NotificationBackgroundView bgView=${bgView.javaClass.simpleName} " +
                    "loc=[${loc[0]},${loc[1]}] bounds=$bounds region=$region " +
                    "cornerRadius=${corner.radius} source=${corner.source}"
            )
            if (screenRegionMap.size > 1024) screenRegionMap.clear()
            // [doc/spec/07 + 2026-08-13] 新卡片映射建立 = 内容变化信号 → 触发整屏快照重抓（无节流立即）
            // [doc/spec/51 2026-08-13] heads-up 新卡 = 通知出现：主线程立即抓首帧 + 连抓 5~6 帧 +
            // 持续期间兜底抓屏（覆盖入场动画背景实时跟上）；shade 普通新卡仍走 worker 异步。
            if (isHeadsUpBgView(bgView)) {
                triggerHeadsUpAppearanceCapture(bgView)
            } else {
                triggerBackgroundCapture()
            }
        } else if (previous.region != region || previous.autoBounds != bounds) {
            screenRegionMap[key] = entry
            logThrottled("notif-map-updated", Log.DEBUG) { "coord: notif-map updated id=$key region=$region" }
            if (screenRegionMap.size > 1024) screenRegionMap.clear()
            // 位置/尺寸更新仅写表不重抓（静态壁纸背景，srcRect 实时折算已跟手）
            // [doc/spec/49 2026-08-13] heads-up 卡同卡内容更新（bounds/region 变化，如音乐进度条展开/
            // 通知卡尺寸变化）也触发整屏快照重抓——heads-up 实时内容需刷新背景；普通 shade 卡仍只写表
            // 不重抓。[2026-08-13 回退 spec/49 固定 200ms] 默认无节流立即抓（pending 去重防滚动风暴）。
            // [doc/spec/51 2026-08-13] 若该卡刚转入 heads-up（映射已存在但尚未进入出现抓屏，如 shade →
            // heads-up 场景）→ 按「通知出现」触发首帧连抓；已活跃的同一 heads-up 卡 → 仅内容变化抓屏
            //（连抓已由出现时启动覆盖入场动画，此处不重复触发避免双路首帧主线程抓屏）。
            if (isHeadsUpBgView(bgView)) {
                if (headsUpActiveHost?.get() !== bgView) {
                    triggerHeadsUpAppearanceCapture(bgView)
                } else {
                    triggerBackgroundCapture()
                }
            }
        }
    }

    // ------------------------------------------------------------ 任务 G：运动事件源 hook（问题 13：区域冻结修复）

    /**
     * 任务 G（问题 13）：控制中心滑动/翻页时玻璃区域"焊死"不跟随的修复。
     *
     * Root cause（实锤）：控制中心滑动/下拉/翻页走**容器 RenderNode 的 offset/translation**，
     * 子 View display list **不重录** → AutoBlurDrawable.draw（建映射）/drawBlurShader（渲染）
     * 全程不触发 → screenRegionMap 里永远是滑动前的旧坐标 → srcRect 冻结。通知卡正常是因为滚动
     * 触发行 invalidate 重录。
     *
     * 修复（单主线）：挂运动事件源（①QS 翻页 onPageScrolled ②面板展开 setExpansionHeight），
     * 事件触发 → 遍历 registeredHostViews 逐个 `invalidate()`（**必须宿主 View 本身**；
     * ViewGroup.invalidate 不重录子节点 display list）→ 下一帧宿主重录时 AutoBlurDrawable.draw
     * 重跑 → getLocationOnScreen 反映新位置 → 映射更新 → drawBlurShader 重跑 → 玻璃跟随。
     * invalidate 是异步重绘，事件回调高频（每帧）直接调即可；不在主线程做任何重活。
     *
     * 强兜底：两个运动事件源 hook 独立 try-catch + [ExceptionMode.PROTECTIVE]，挂载失败不影响
     * 主渲染（玻璃仍按旧映射渲染，只是运动期间不刷新区域）。
     */
    private fun mountMotionSources(api: XposedInterface, classLoader: ClassLoader) {
        mountQsPageScroll(api, classLoader)
        mountPanelExpansion(api, classLoader)
        mountCustomRowTranslation(api, classLoader)
    }

    /**
     * [2026-08-15 偶发不抓屏根治（resolveShadeSfc 三来源全空）] hook `NotificationShadeWindowView`
     * 的 `onAttachedToWindow`/`onDetachedFromWindow`：shade 窗口（重建后）重挂载时**立即**更新
     * [shadeRootViewCache]，始终指向当前 attach 的 shade 根。
     *
     * 根因（真机 logcat 铁证）：`resolveShadeSfc null/invalid → any-attached-host fallback null/invalid →
     * heads-up sfc null → shade sfc unavailable after fallback 3x → cooldown 1000ms`。面板状态变化 /
     * shade 窗口重建时：旧根 View detach（`shadeRootViewCache` 停留在旧根，`isAttachedToWindow=false` 被
     * [resolveShadeSfc] 跳过）；新根 View 未重新走 [registerHostView] 登记（新宿主 View 未 draw 拦截）；
     * `registeredHostViews` 内宿主 View 全部 detach（旧窗口销毁）→ 三来源全空 → 连续失败 + 1000ms 冷却
     * → 一阵子完全不抓屏。
     *
     * 修：`onAttachedToWindow`（窗口每次 attach/重建后触发，含初始创建）→ `shadeRootViewCache = 新根` +
     * 失效 `shadeSfcCache`（强制重解析新 SurfaceControl，防旧 handle 仍 valid 但已错误）；`onDetachedFromWindow`
     * → 若缓存指向本根则清空（防旧根 stale 长期残留）。shade 根解析从此不再依赖宿主登记时机 → 偶发失败消除。
     *
     * 挂载失败独立 try-catch（不影响其他 hook）。方法 public final：libxposed hook 后端对 final 方法的支持
     * 由后端决定，失败则回退现状（[resolveShadeSfc] 自愈清空 stale 缓存兜底，见 resolveShadeSfc）。
     */
    private fun mountShadeRootAttach(api: XposedInterface, classLoader: ClassLoader) {
        // getDeclaredMethod 锚定子类自身 override（反编译实证 NotificationShadeWindowView:415/:444 声明），
        // 防止 getMethod 解析到框架 View.onAttachedToWindow → hook 全 View 命中（性能/副作用灾难）。
        val clazz = try {
            Class.forName(CLASS_SHADE_ROOT_VIEW, false, classLoader)
        } catch (t: Throwable) {
            Log.e(TAG, "bg-source: shade root class not found $CLASS_SHADE_ROOT_VIEW", t)
            return
        }
        try {
            val attach = clazz.getDeclaredMethod(METHOD_ON_ATTACHED_TO_WINDOW)
            api.hook(attach)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        val view = try {
                            chain.getThisObject() as? View
                        } catch (t: Throwable) {
                            null
                        }
                        if (view != null && isShadeRootView(view)) {
                            // 窗口（重建后）重挂载 → 缓存刷新：shade 根解析不依赖宿主登记时机
                            shadeRootViewCache = view
                            shadeSfcCache = null  // 旧 sfc 在窗口重建后可能失效/错误，强制重解析
                            Log.i(TAG, "bg-source: shade root attached/reattached, cache refreshed ${view.javaClass.simpleName}")
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "bg-source: shade root onAttachedToWindow intercept error", t)
                    }
                    chain.proceed()
                }
            Log.i(TAG, "Hook mounted: $CLASS_SHADE_ROOT_VIEW#$METHOD_ON_ATTACHED_TO_WINDOW() (shade root cache refresh)")
        } catch (t: Throwable) {
            Log.e(TAG, "Hook mount FAILED: $CLASS_SHADE_ROOT_VIEW#$METHOD_ON_ATTACHED_TO_WINDOW", t)
        }
        try {
            val detach = clazz.getDeclaredMethod(METHOD_ON_DETACHED_FROM_WINDOW)
            api.hook(detach)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        val view = try {
                            chain.getThisObject() as? View
                        } catch (t: Throwable) {
                            null
                        }
                        // 仅清指向本根的缓存（窗口重建时旧根 detach → 清掉 stale 根，等新根 attach 重缓存；
                        // 若缓存已指向更新的根则不动）
                        if (view != null && shadeRootViewCache === view) {
                            shadeRootViewCache = null
                            shadeSfcCache = null
                            Log.i(TAG, "bg-source: shade root detached, cache cleared")
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "bg-source: shade root onDetachedFromWindow intercept error", t)
                    }
                    chain.proceed()
                }
            Log.i(TAG, "Hook mounted: $CLASS_SHADE_ROOT_VIEW#$METHOD_ON_DETACHED_FROM_WINDOW() (shade root cache clear)")
        } catch (t: Throwable) {
            Log.e(TAG, "Hook mount FAILED: $CLASS_SHADE_ROOT_VIEW#$METHOD_ON_DETACHED_FROM_WINDOW", t)
        }
    }

    /** 运动事件源①：QS 翻页滚动回调（PagedTileLayout$2 = ViewPager.OnPageChangeListener 匿名实现） */
    private fun mountQsPageScroll(api: XposedInterface, classLoader: ClassLoader) {
        try {
            val clazz = Class.forName(CLASS_PAGED_TILE_LAYOUT_LISTENER, false, classLoader)
            val method = clazz.getDeclaredMethod(
                METHOD_ON_PAGE_SCROLLED,
                Int::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        markMotion()
                        invalidateRegisteredHostViews()
                    } catch (t: Throwable) {
                        Log.e(TAG, "taskG: onPageScrolled invalidate error", t)
                    }
                    chain.proceed()
                }
            Log.i(TAG, "Hook G mounted: ${CLASS_PAGED_TILE_LAYOUT_LISTENER}#$METHOD_ON_PAGE_SCROLLED(int,float,int) (QS page flip -> invalidate hosts)")
        } catch (t: Throwable) {
            Log.e(TAG, "Hook G mount FAILED: ${CLASS_PAGED_TILE_LAYOUT_LISTENER}#$METHOD_ON_PAGE_SCROLLED", t)
        }
    }

    /** 运动事件源②：面板下拉/展开回调（QuickSettingsControllerImpl.setExpansionHeight，触摸拖动/动画均经它） */
    private fun mountPanelExpansion(api: XposedInterface, classLoader: ClassLoader) {
        try {
            val clazz = Class.forName(CLASS_QUICK_SETTINGS_CONTROLLER, false, classLoader)
            val method = clazz.getMethod(METHOD_SET_EXPANSION_HEIGHT, Float::class.javaPrimitiveType)
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        val expansion = try {
                            chain.getArg(0) as? Float
                        } catch (t: Throwable) {
                            null
                        } ?: 0f
                        // [spec/55 诊断日志] hook 触发时间戳（每次回调都打，Log.d 可关）——判断「hook 触发时机/频率」
                        // [spec/59 追加 2026-08-14] state 附带 panelWasCollapsed（本次分支判定前值，确认首帧状态机）
                        diagHookLog("setExpansionHeight", expansion, "panelWasCollapsed=$panelWasCollapsed")
                        // [2026-08-13 回退 spec/49 场景门控] 不再维护 isShadeExpanded 标志（误拦锁屏：锁屏时
                        // setExpansionHeight 不回调/回调 0 且无 heads-up → 门控把锁屏玻璃抓屏也停掉）。
                        // 「收起零抓屏」由周期抓屏的 idle-skip 空闲判定保证（收起且无内容变化信号 → 保持旧帧）。
                        markMotion()
                        invalidateRegisteredHostViews()
                        if (expansion > 0f) {
                            // [2026-08-13 延迟调查·竞争暂停] 面板展开中 → heads-up 连抓/持续 worker 暂停
                            // （避免与下拉抓屏并发锤 captureDisplay，SF 串行 → 主抓屏被排队拖后）
                            panelExpansionActive = true
                            // [2026-08-14 用户方案·移除主动持续抓屏 120Hz] 展开期背景抓屏改由 onBlurReady
                            //（系统重模糊 = 底层内容变化）驱动，不再按控件运动 120Hz 空转（降的是抓屏，
                            // 控件运动刷屏由跟踪器照旧跟手）。
                            // [doc/spec/50 + spec/51 2026-08-13] 刚下拉首帧（收起→展开转换第一帧，
                            // panelWasCollapsed=true）→ **立即触发抓屏**（[2026-08-13 回退：不再主线程
                            // 同步 captureDisplay，见 triggerBackgroundCaptureOnMainThread]——入队 worker
                            // 立即抓 + 连抓 worker 补帧，主线程零阻塞）。首帧之后（动画持续，
                            // panelWasCollapsed=false）走正常 triggerBackgroundCapture → worker 异步
                            // （防持续主线程阻塞 = ANR 冻结根因）。
                            if (panelWasCollapsed) {
                                panelWasCollapsed = false
                                triggerBackgroundCaptureOnMainThread()
                            } else {
                                // [doc/spec/07 + 2026-08-13] 面板展开（expansion>0）= 内容变化信号 → 触发整屏
                                // 快照重抓（[2026-08-13 回退 spec/49 固定 200ms] 默认无节流立即抓，worker 异步）。
                                // 收起（<=0）不触发：面板收起 = 无玻璃可见，保持旧帧零抓屏。
                                triggerBackgroundCapture()
                            }
                            kickPeriodicCapture()
                        } else {
                            // 面板收起 → 记录状态，下次下拉再触发首帧抓屏；heads-up worker 恢复抓屏
                            panelWasCollapsed = true
                            panelExpansionActive = false
                            // [spec/52 文字强制刷新] 收起 = 系统对文字重调 setTextColor(原色) 的时序（折叠变回
                            // 根因：折叠后坐标移出 screenRegionMap → isOverGlassRegion=false + getQsColorState
                            // ==2 放行原色 + 折叠不触发重采样）→ post 主线程强制刷新页面上所有玻璃区域内文字颜色。
                            // 不在此同步调用（防 setTextColor 重入 / 阻塞 setExpansionHeight 渲染回调）；
                            // requestForceRefreshTextColors 内部 250ms 节流（折叠动画末帧 <=0 可能多次回调）。
                            requestForceRefreshTextColors()
                        }
                        // [spec/60 周期强制刷新] 面板展开/收起都是活跃玻璃宿主信号 → 确保周期循环在跑
                        ensureTextForceRefreshLoop()
                    } catch (t: Throwable) {
                        Log.e(TAG, "taskG: setExpansionHeight invalidate error", t)
                    }
                    chain.proceed()
                }
            Log.i(TAG, "Hook G mounted: $CLASS_QUICK_SETTINGS_CONTROLLER#$METHOD_SET_EXPANSION_HEIGHT(float) (panel expand -> invalidate hosts)")

            // [2026-08-13 延迟调查·触发时机提前] 系统 QS 触摸追踪三重门控（isTracking/touchSlop/
            // shouldQuickSettingsIntercept，QuickSettingsControllerImpl.java:1024-1032）→ 触摸确认下拉后
            // 30-150ms 才首帧 setExpansionHeight(>0)。onExpansionStarted 在触摸确认下拉瞬间（setTracking(true)
            // 后立即，:1032）调用 → 在此**提前**触发首帧抓屏（替代 setExpansionHeight>0 首帧判定）。
            // panelWasCollapsed 在此消费后，setExpansionHeight 首帧分支不再重复触发；触摸取消/收起由
            // setExpansionHeight(<=0) 重置 panelWasCollapsed/panelExpansionActive。挂载失败独立 try-catch
            // （不影响上面 setExpansionHeight hook）。
            try {
                val onStartMethod = clazz.getMethod("onExpansionStarted")
                api.hook(onStartMethod)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        try {
                            // [spec/55 诊断日志] onExpansionStarted（触摸确认下拉瞬间）触发时间戳——对比
                            // setExpansionHeight 首帧回调晚多久（系统三重门控 30-150ms 延迟实证）
                            // [spec/59 追加 2026-08-14] state 附带 panelWasCollapsed（本次分支判定前值）
                            diagHookLog("onExpansionStarted", state = "panelWasCollapsed=$panelWasCollapsed")
                            panelExpansionActive = true
                            // [2026-08-14 用户方案·移除主动持续抓屏 120Hz] 同上：onBlurReady 驱动抓屏，
                            // 首帧由 triggerBackgroundCaptureOnMainThread（主线程同步）立即抓。
                            if (panelWasCollapsed) {
                                panelWasCollapsed = false
                                triggerBackgroundCaptureOnMainThread()
                            }
                        } catch (t: Throwable) {
                            Log.e(TAG, "taskG: onExpansionStarted first-frame error", t)
                        }
                        chain.proceed()
                    }
                Log.i(TAG, "Hook G mounted: $CLASS_QUICK_SETTINGS_CONTROLLER#onExpansionStarted() (panel expand start -> first-frame capture)")
            } catch (t: Throwable) {
                Log.e(TAG, "Hook G mount FAILED: $CLASS_QUICK_SETTINGS_CONTROLLER#onExpansionStarted", t)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Hook G mount FAILED: $CLASS_QUICK_SETTINGS_CONTROLLER#$METHOD_SET_EXPANSION_HEIGHT", t)
        }
    }

    /**
     * 运动事件源③（[Bug 5] 通知卡片横向滑动不跟手修复）：hook OplusCustomRow.setTranslation(float)。
     *
     * 反编译实证（customcard/OplusCustomRow.java）：
     * - setAllChildViewInTranslateable（:946-965）显式把 NotificationBackgroundView（玻璃宿主
     *   mBackgroundNormal）剔出 mTranslateableViews → custom-card 路径（mDismissUsingRowTranslationX=false）
     *   setTranslation（:1216-1235）行自身 setTranslationX(0)、仅内容子 View setTranslationX(f) →
     *   bgView getLocationOnScreen.x 不变 → tracker/重录检测不到 → region 冻结 → srcRect 不跟手。
     *   （标准卡 mDismissUsingRowTranslationX=true 行自身平移 → getLocationOnScreen 反映 → 跟手。）
     * - [2026-08-13 方向确认] 玻璃背景固定（系统原样：背景固定、内容滑）——不再按 content translationX
     *   折算 region（玻璃不跟手指走）。setTranslation 期间每帧调用（拖动 + 回弹/尺寸变化动画）→
     *   invalidate bgView → 重录 draw → recordNotificationCardRegion 用最新 bgView 位置/尺寸折算 region
     *   → 卡片尺寸变化时 srcRect 采样区及时跟上（滑动真因 = 尺寸变化未及时重采样，见 spec/18 Bug 5）。
     * 挂载失败独立 try-catch（不影响其他 hook）；字段反射失败则跳过（回退现状冻结，不崩）。
     */
    private fun mountCustomRowTranslation(api: XposedInterface, classLoader: ClassLoader) {
        if (mCustomRowBgNormal == null) {
            Log.w(TAG, "Hook B OplusCustomRow.mBackgroundNormal unresolved, custom-row swipe re-sample disabled")
            return
        }
        try {
            val clazz = Class.forName(CLASS_OPLUS_CUSTOM_ROW, false, classLoader)
            val method = clazz.getMethod(METHOD_SET_TRANSLATION, Float::class.javaPrimitiveType)
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        recordCustomRowTranslation(chain)
                    } catch (t: Throwable) {
                        Log.e(TAG, "customRow: setTranslation intercept error", t)
                    }
                    chain.proceed()
                }
            Log.i(TAG, "Hook B mounted: $CLASS_OPLUS_CUSTOM_ROW#$METHOD_SET_TRANSLATION(float) (custom-row swipe -> re-record region)")
        } catch (t: Throwable) {
            Log.e(TAG, "Hook B mount FAILED: $CLASS_OPLUS_CUSTOM_ROW#$METHOD_SET_TRANSLATION", t)
        }
    }

    /** hookBefore：setTranslation 期间 invalidate bgView，触发重录 → recordNotificationCardRegion 折算最新区域。 */
    private fun recordCustomRowTranslation(chain: XposedInterface.Chain) {
        val row = try {
            chain.getThisObject()
        } catch (t: Throwable) {
            Log.e(TAG, "customRow: getThisObject failed", t); return
        } ?: return
        val bgView = try {
            mCustomRowBgNormal?.get(row) as? View
        } catch (t: Throwable) {
            Log.e(TAG, "customRow: read mBackgroundNormal failed", t); null
        }
        // bgView 反射失败 → 兜底 invalidate 全部已登记宿主（内容变化 → 重录；不精确但强兜底）
        if (bgView == null) {
            markMotion()
            invalidateRegisteredHostViews()
            return
        }
        // [Bug 5 方向确认 2026-08-13] 玻璃背景固定：不再按 content translationX 折算 region（玻璃不跟
        // 手指走）。setTranslation 期间（自定义卡内容平移 / 标准卡行平移 / 尺寸变化动画每帧调用）只
        // invalidate bgView → 重录 draw → recordNotificationCardRegion 用最新 bgView 位置/尺寸折算 region
        // → 尺寸变化时 srcRect 采样区及时跟上（region 及时更新即够，整屏快照覆盖全屏无需重抓——背后内容未变）。
        try {
            markMotion()
            bgView.invalidate()
        } catch (t: Throwable) {
            Log.e(TAG, "customRow: invalidate bgView failed", t)
        }
    }

    // ------------------------------------------------------------ [方案 B] NC↔CC 切换 / 锁屏展开 容器缩放变换追踪（doc/spec/29）

    /**
     * [方案 B] 挂载容器 View 变换 hook：setScaleX/setScaleY/setTranslationX/setTranslationY。
     * 容器 = NotificationPanelView / OplusQSRootView（NC↔CC 切换动画的缩放对象，
     * OplusPanelViewPagerController.access$setAlphaAndTranslationXForScrollX :835-866）
     * 与 NotificationStackScrollLayout（锁屏展开缩放，NotificationStackScrollLayoutControllerExtImpl
     * .updateNotificationViewScale :869-898）。方法均为 View 继承方法，hook 在具体类上按运行时
     * 派发命中。任一容器类/方法解析失败只禁用对应 hook，不影响其余（强兜底）。
     */
    private fun mountContainerTransformHooks(api: XposedInterface, classLoader: ClassLoader) {
        containerTransformClasses.clear()
        try {
            containerTransformClasses.addAll(
                listOf(
                    Class.forName(CLASS_NOTIFICATION_PANEL_VIEW, false, classLoader),
                    Class.forName(CLASS_OPLUS_QS_ROOT_VIEW, false, classLoader),
                    Class.forName(CLASS_NOTIFICATION_STACK_SCROLL_LAYOUT, false, classLoader),
                )
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Hook B resolve container classes failed, NC↔CC ghost fix disabled", t)
            return
        }
        val transformMethods = listOf(METHOD_SET_SCALE_X, METHOD_SET_SCALE_Y, METHOD_SET_TRANSLATION_X, METHOD_SET_TRANSLATION_Y)
        for (clazz in containerTransformClasses) {
            for (m in transformMethods) {
                try {
                    val method = clazz.getMethod(m, Float::class.javaPrimitiveType)
                    api.hook(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept { chain ->
                            try {
                                recordContainerTransform(chain, m)
                            } catch (t: Throwable) {
                                Log.e(TAG, "transform: $m intercept error", t)
                            }
                            chain.proceed()
                        }
                } catch (t: Throwable) {
                    Log.e(TAG, "Hook B mount FAILED: ${clazz.simpleName}#$m", t)
                }
            }
        }
        Log.i(TAG, "Hook B mounted container transform hooks (NotificationPanelView/OplusQSRootView/NotificationStackScrollLayout setScaleX/Y/setTranslationX/Y)")
    }

    /** [方案 B] hookBefore：更新容器 View 的变换快照（scale + 屏幕坐标 pivot）。 */
    private fun recordContainerTransform(chain: XposedInterface.Chain, method: String) {
        val view = try {
            chain.getThisObject() as? View
        } catch (t: Throwable) {
            Log.e(TAG, "transform: getThisObject failed", t); return
        } ?: return
        // [2026-08-13 二次修复] 容器类过滤（核心修复）：3 个容器类均未 override setScaleX/setScaleY/
        // setTranslationX/Y（继承 View，反编译实证）→ getMethod 解析到 View 继承方法 → hook 落在
        // **全局所有 View** 的 setScaleX 调用。若不过滤，无关 View 的 setScaleX 也写 containerTransforms，
        // 数量暴涨触发 size>16 clear 把容器条目冲掉 → applyContainerTransform 沿父链找不到容器 →
        // 变换未应用 → 重影依旧。isAssignableFrom 含子类（运行时实际类，OplusQSRootView 等）。
        if (containerTransformClasses.none { it.isAssignableFrom(view.javaClass) }) return
        try {
            val id = System.identityHashCode(view)
            val t = containerTransforms[id] ?: ContainerTransform().also { containerTransforms[id] = it }
            // 本次调用设置的字段取参数值（hook 在 proceed 前，view 字段还是旧值 → 一帧滞后消除）；
            // 其余字段读 view 当前值（本次调用不涉及，view 值即最新）。
            val newVal = try {
                chain.getArgs()?.getOrNull(0) as? Float
            } catch (e: Throwable) {
                null
            }
            when (method) {
                METHOD_SET_SCALE_X -> t.scaleX = newVal ?: view.scaleX
                METHOD_SET_SCALE_Y -> t.scaleY = newVal ?: view.scaleY
            }
            if (method != METHOD_SET_SCALE_X) t.scaleX = view.scaleX
            if (method != METHOD_SET_SCALE_Y) t.scaleY = view.scaleY
            // 屏幕坐标 pivot = 容器 getLocationOnScreen + pivotX/Y（getLocationOnScreen 已含 translation）
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            // [2026-08-13 三次修复·pivot 默认中心——「映射没修好」真根因] AOSP View.setPivotX Javadoc
            // （SDK android-34 实证）：**未显式 setPivot（isPivotSet()==false）时系统默认以 View 中心为
            // 缩放/旋转 pivot**（RenderNode 未设 pivot → native 换算 center；此时 getPivotX() 返回 -1）。
            // NC↔CC 面板动画只 setScaleX/Y + setAlpha 不 setPivot（OplusPanelViewPagerController
            // .access$setAlphaAndTranslationXForScrollX :835-866 反编译实证）→ 旧代码
            // pivotScreen = loc + (-1) = 左上角偏左 1px → 缩放中心严重错（实际系统以面板中心缩）→
            // 正向映射 R_drawn = s·R+(1-s)·pivotScreen 方向虽对但 pivot 基准错 → srcRect 采样区与
            // 玻璃实际绘制区错位 → 重影依旧（spec/29 真机未消除的根因，非方向错）。
            // 修复：isPivotSet()==false → pivot = loc + width/2,height/2（View 中心，与系统默认一致）。
            // 锁屏展开显式 setPivot（NotificationStackScrollLayoutControllerExtImpl:887-894，setPivotX 先于
            // setScaleX）→ isPivotSet()==true → 用 view.pivotX/Y，行为不变。
            val pivotScreenX: Float
            val pivotScreenY: Float
            if (view.isPivotSet()) {
                pivotScreenX = loc[0] + view.pivotX
                pivotScreenY = loc[1] + view.pivotY
            } else {
                pivotScreenX = loc[0] + view.width / 2f
                pivotScreenY = loc[1] + view.height / 2f
            }
            t.pivotScreenX = pivotScreenX
            t.pivotScreenY = pivotScreenY
            // [2026-08-13 二次修复] 有容器类过滤后仅容器写入（≤3），size 不会暴涨，此处 clear 不再误清
            // 容器条目；保留作为泄漏兜底（异常路径下容器 identityHashCode 残留时防无限增长）。
            if (containerTransforms.size > 16) containerTransforms.clear()
        } catch (t: Throwable) {
            Log.e(TAG, "transform: recordContainerTransform failed", t)
        }
    }

    /**
     * [方案 B] 把「布局坐标」region 映射到容器缩放后的「实际绘制坐标」。
     * 根因：getLocationOnScreen 只累加 left+translation、不乘祖先 scale → 缩放动画期间 region 停在
     * 布局坐标，而玻璃实际绘制在缩放后位置（srcRect 采样区与绘制区错位 = 卡片重影）。
     * 正向变换：R_drawn = s·R + (1-s)·pivotScreen（region 已含容器 translation，故无独立平移项）。
     * 沿宿主父链找**最近**一个有非恒等变换的容器并应用（NC↔CC 每次只缩一个面板、锁屏展开只缩
     * 堆栈，单层足够；多层嵌套容器同时缩放不在本次范围，见 doc/spec/29）。无匹配/异常 → 原样
     * 返回（回退现状，强兜底）。主线程写变换、渲染线程读，读到的是最近一次 hook 的快照。
     */
    private fun applyContainerTransform(region: RectF, hostView: View?): RectF {
        if (hostView == null || containerTransforms.isEmpty()) return region
        try {
            var parent: ViewParent? = hostView.parent
            while (parent is View) {
                val t = containerTransforms[System.identityHashCode(parent)]
                if (t != null && (t.scaleX != 1f || t.scaleY != 1f)) {
                    val sx = t.scaleX
                    val sy = t.scaleY
                    val px = t.pivotScreenX
                    val py = t.pivotScreenY
                    // 缩放 → 绘制区域 = s·R + (1-s)·pivotScreen
                    return RectF(
                        sx * region.left + (1f - sx) * px,
                        sy * region.top + (1f - sy) * py,
                        sx * region.right + (1f - sx) * px,
                        sy * region.bottom + (1f - sy) * py,
                    )
                }
                parent = parent.parent
            }
        } catch (t: Throwable) {
            Log.e(TAG, "transform: applyContainerTransform failed, keep layout region", t)
        }
        return region
    }

    // ------------------------------------------------------------ [spec/19] Heads-Up 玻璃化：setBlurType 强制 PlatformStatic

    /**
     * [spec/19] hook ViewBlurProxy.setBlurType(BlurType)（单一漏斗，blurForHeadsUp:808 /
     * NotificationChildrenContainerExtImp:214 全汇聚）。
     *
     * 根因（实证）：heads-up 期间系统把背景强制切 BlurTypeMotion → getBlurDrawable() 返回 framework
     * BackgroundBlurDrawable（surface 级模糊，draw 空实现）→ posteffect drawBlurShader hook 够不到 →
     * 恒磨砂/无玻璃。hook 在此把 heads-up 背景（NotificationBackgroundView）的 Motion 强制改
     * PlatformStatic，回到我们管线。
     *
     * 判定（防误伤）：arg0 引用等于 BlurTypeMotion.INSTANCE 且 this.view 是 NotificationBackgroundView
     * （heads-up 背景；shade/锁屏走 PlatformStatic/BlendWallpaper → 不命中；FullScreenBackgroundView
     * extends View 非 NotificationBackgroundView 子类 → 全屏横幅不被误改）。
     */
    private fun mountViewBlurProxySetBlurType(api: XposedInterface, classLoader: ClassLoader) {
        val setBlurType = mViewProxySetBlurType
        val motion = mBlurTypeMotionInstance
        val platformStatic = mBlurTypePlatformStaticInstance
        val notifBg = mNotifBackgroundViewClass
        if (setBlurType == null || motion == null || platformStatic == null || notifBg == null) {
            Log.w(TAG, "headsUp: setBlurType reflection unresolved, skip")
            return
        }
        try {
            api.hook(setBlurType)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val arg0 = try {
                        chain.getArg(0)
                    } catch (t: Throwable) {
                        null
                    }
                    if (arg0 === motion) {
                        val proxy = try {
                            chain.getThisObject()
                        } catch (t: Throwable) {
                            null
                        }
                        val view = try {
                            if (proxy != null) mViewProxyGetView?.invoke(proxy) else null
                        } catch (t: Throwable) {
                            null
                        }
                        // [2026-08-15 轻打扰折叠横幅方案撤销] 曾对 FullScreenBanner 强制 BlurTypePlatformStatic，
                        // 但真机 logcat 实证：系统 `ensurePlatformStaticBlurDrawable failed`（静态模糊 drawable
                        // 创建失败）→ 背景丢失变白。**撤销 BlurProxy 替换路线**（恢复系统原 Motion 模糊背景，
                        // 不碰 BlurProxy）；横幅玻璃改走 mountSimpleBannerGlass（dispatchDraw 画玻璃，方案 B）。
                        if (proxy != null && view != null && notifBg.isInstance(view)) {
                            // [spec/19 修复 2026-08-13] 纯白根因：系统 excludeRules =
                            // `HeadsUpContainerWindow.isHeadsUpView(view)`（ViewBlurManager.requireBlurProxyForView
                            // :1286 实证）→ PlatformBlurHelper.ensureBlurDrawable 拒绝为 heads-up 创建
                            // PlatformBlurDrawable → getBlurDrawable 返回原白色 mBackground → 纯白。
                            // proceed 前登记 scoped 绕过（对应 bgView 的 isHeadsUpView 返回 false），
                            // 让 setBlurType(PlatformStatic) 内部 ensureBlurDrawable 能创建 drawable。
                            // drawable 创建确认后（recordNotificationCardRegion）自动清除；GC 兜底。
                            headsUpGlassForceView = WeakReference(view as View)
                            val args = chain.getArgs().toTypedArray()
                            args[0] = platformStatic
                            logThrottled("headsup-force-platformstatic", Log.INFO) {
                                "headsUp: force PlatformStatic (was Motion) for ${view.javaClass.simpleName}, exclude bypass armed"
                            }
                            return@intercept chain.proceed(args)
                        }
                    }
                    chain.proceed()
                }
            Log.i(TAG, "Hook B mounted: $CLASS_VIEW_BLUR_PROXY#$METHOD_SET_BLUR_TYPE(BlurType) (heads-up Motion -> PlatformStatic)")
        } catch (t: Throwable) {
            Log.e(TAG, "Hook B mount FAILED: $CLASS_VIEW_BLUR_PROXY#$METHOD_SET_BLUR_TYPE", t)
        }
    }

    /**
     * [spec/19 修复 2026-08-13] hook `HeadsUpContainerWindow.isHeadsUpView(View)`：
     * 对 [headsUpGlassForceView] 指向的 heads-up NotificationBackgroundView 返回 false（scoped 放行），
     * 让 `PlatformBlurHelper.ensureBlurDrawable` 的 excludeRules 检查通过，从而创建 PlatformBlurDrawable
     * （否则 getBlurDrawable 返回原白色 mBackground → 纯白）。
     *
     * 作用域安全：只影响「正在强制玻璃化的那个 bgView」实例——isHeadsUpView 的其他消费方
     * （OplusExpandableNotificationRowExImpl.isHeadsUpView() 传 row、NotificationChildrenContainerExtImp:214
     * 传 row）传的是 row 而非 bgView，不命中；excludeRules 传的正是 bgView，命中。drawable 创建确认后
     * [recordNotificationCardRegion] 清除绕过，恢复系统原判定。挂载失败只禁用本修复（回退现状纯白），不崩。
     */
    private fun mountHeadsUpContainerIsHeadsUpView(api: XposedInterface, classLoader: ClassLoader) {
        try {
            val clazz = Class.forName(CLASS_HEADS_UP_CONTAINER_WINDOW, false, classLoader)
            val method = clazz.getMethod(METHOD_IS_HEADS_UP_VIEW, android.view.View::class.java)
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val view = try {
                        chain.getArg(0) as? View
                    } catch (t: Throwable) {
                        null
                    }
                    if (view != null && headsUpGlassForceView?.get() === view) {
                        // scoped 放行：excludeRules 视该 bgView 为非 heads-up → 允许创建 PlatformBlurDrawable
                        return@intercept false
                    }
                    chain.proceed()
                }
            Log.i(TAG, "Hook B mounted: $CLASS_HEADS_UP_CONTAINER_WINDOW#$METHOD_IS_HEADS_UP_VIEW(View) (heads-up glass exclude bypass)")
        } catch (t: Throwable) {
            Log.e(TAG, "Hook B mount FAILED: $CLASS_HEADS_UP_CONTAINER_WINDOW#$METHOD_IS_HEADS_UP_VIEW", t)
        }
    }

    // ------------------------------------------------------------ [spec/44] 流体云展开卡片玻璃化（Seedling 宿主容器）

    /**
     * [spec/44 流体云展开卡片玻璃化 2026-08-13] hook Seedling 宿主容器 `CapsulePluginContainer`
     * （status_bar.xml 的 seeding_card_container，extends FrameLayout）的 `dispatchDraw(Canvas)`，
     * 在子 View 绘制前对**展开大卡片**区域注入液态玻璃。
     *
     * 背景：流体云（灵动岛）= Seedling（种子卡片）体系，UI 由 SeedlingPlugin 插件 APK 渲染，**不走
     * posteffect 模糊管线**（spec/20 实证：hook 不到 AutoBlurDrawable / drawBlurShader）。宿主容器 =
     * `CapsulePluginContainer`，插件经 `seedlingPlugin.onCreateView(0, container)` 把种子卡片 View
     * （缩小胶囊 + 展开大卡片）挂入（CapsulePluginContainerController.java:255/302 实证）。
     *
     * 玻璃化策略（方案 A）：hook 容器 dispatchDraw → 遍历子 View，识别**展开大卡片**（高度显著超过
     * 容器/状态栏高度，`KEY_SEEDLING_CARD_EXPAND_HEIGHT_RATIO` 系数）→ 复用液态玻璃管线
     * （[resolveRenderSource] 整屏快照 + srcRect 按元素区域折算 + [LiquidGlassShader]）画玻璃到 canvas
     * （子 View 之下，卡片内容画在玻璃之上）。缩小胶囊（≈状态栏高度）不处理。
     *
     * 局限：插件子 View 若画**不透明背景**会盖住玻璃（[KEY_SEEDLING_CARD_FORCE_TRANSPARENT_BG] 可
     * 反射置空 background 缓解，onDraw 自绘背景不受影响）；插件 APK 不在本地反编译产物，大卡片
     * 精确类名未知，用高度阈值判定。真机验证后再按需调整。
     */
    private fun mountSeedlingCardContainer(api: XposedInterface, classLoader: ClassLoader) {
        if (!seedlingCardGlassEnabled) {
            Log.i(TAG, "Seedling card glass disabled by prefs, skip mount")
            return
        }
        if (seedlingDispatchDrawMounted) return
        try {
            // 容器类可能尚未加载（状态栏布局惰性 inflate）：成功则 isInstance 判定，失败则运行时类名兜底，
            // 不阻塞 hook 挂载（ViewGroup.dispatchDraw 基类始终 hook，拦截体快速判定 this 是否是容器）。
            val containerCls = try {
                Class.forName(CLASS_CAPSULE_PLUGIN_CONTAINER, false, classLoader)
            } catch (t: Throwable) {
                Log.d(TAG, "Seedling container class not loaded yet (deferred), fallback to runtime name check: $CLASS_CAPSULE_PLUGIN_CONTAINER")
                null
            }
            // CapsulePluginContainer 未 override dispatchDraw → hook 基类 ViewGroup.dispatchDraw(Canvas)，
            // 拦截体里判定 this 是容器实例（避免 hook 全 ViewGroup 的性能/安全影响）。
            val vgCls = Class.forName(CLASS_VIEW_GROUP, false, classLoader)
            val method = vgCls.getDeclaredMethod(METHOD_DISPATCH_DRAW, Canvas::class.java)
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        val thisView = try {
                            chain.getThisObject() as? View
                        } catch (t: Throwable) {
                            null
                        }
                        if (thisView != null) {
                            val isContainer = containerCls?.isInstance(thisView)
                                ?: (thisView.javaClass.name == CLASS_CAPSULE_PLUGIN_CONTAINER)
                            if (isContainer) {
                                // [2026-09-16] 原每帧 dumpParents(thisView) 诊断已移除（逐帧刷屏）；
                                // 需要时用 dumpParents 单独排查
                                val canvas = try {
                                    chain.getArg(0) as? Canvas
                                } catch (t: Throwable) {
                                    null
                                }
                                // [2026-09-16 用户需求变更] 绘制顺序改回「**玻璃在子 View 之下**」：
                                // 先画玻璃，再 proceed 画子 View（岛）。岛是半透明深色底 → 玻璃透过它可见，
                                // 形成「岛原版颜色叠在玻璃之上」的层次（用户明确要求，且岛原版背景不再置空）。
                                // [对比 2026-08-15] 那次改成画在子 View 之上，是因为置空了岛背景后
                                // 插件不透明绘制把玻璃挡没了；现在**保留岛原版背景**并接受玻璃在下。
                                if (canvas != null) {
                                    try {
                                        renderSeedlingCardGlass(canvas, thisView)
                                    } catch (t: Throwable) {
                                        Log.e(TAG, "seedling-card: dispatchDraw glass failed", t)
                                    }
                                }
                                val result = chain.proceed()
                                // 拦截体内已 proceed，return 防 fall-through 到外层 chain.proceed() 重复画子 View
                                return@intercept result
                            }
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "seedling-card: dispatchDraw intercept error", t)
                    }
                    chain.proceed()
                }
            seedlingDispatchDrawMounted = true
            Log.i(TAG, "Seedling card glass mounted: $CLASS_CAPSULE_PLUGIN_CONTAINER#$METHOD_DISPATCH_DRAW(Canvas)")
        } catch (t: Throwable) {
            Log.e(TAG, "Seedling card glass mount FAILED: $CLASS_CAPSULE_PLUGIN_CONTAINER#$METHOD_DISPATCH_DRAW", t)
        }
    }

    /**
     * [2026-09-16 小岛玻璃化] 容器 dispatchDraw 拦截体：找容器内的**小胶囊（小岛）**并画液态玻璃。
     *
     * **本 hook 只负责小岛**；展开大卡片走 [mountCardBackgroundGlass]（`CardBackgroundView.onDraw`），
     * 两条路径互不干扰（真机实证：展开大卡在 NotificationShade，不在本容器）。
     *
     * 目标是 **`CapsuleView`**（胶囊本体，[collectCapsuleViews] 递归按类名找），不取外层的
     * `CapsuleContainer`——那只是"外框"，会把玻璃画大（用户反馈「玻璃比岛大、超出边缘」）。
     * 尺寸用 `CapsuleView` 的 `measuredWidth/Height`（真机 402x120；其 `getWidth()` 恒 0，
     * 插件未调 `setLeftTopRightBottom`），位置按**容器几何居中**算（CapsuleView 自身的
     * `getLocationOnScreen()` 是陈旧值，实测返回屏幕中心）。
     *
     * 由 `seedlingCapsuleGlassEnabled`（Prefs.KEY_SEEDLING_CAPSULE_GLASS，默认 true）控制；
     * 圆角用全圆 `min(w,h)/2`（真机 160 高 → 80，与系统路径实测 corner=80 一致）。
     * 绘制层次见 [mountSeedlingCardContainer]（玻璃在子 View 之下）。
     */
    private fun renderSeedlingCardGlass(canvas: Canvas, container: View) {
        val containerView = container as? android.view.ViewGroup ?: return
        val containerHeight = containerView.height
        if (containerHeight <= 0) {
            if (moduleLogEnabled) Log.i(TAG, "seedling-card: skip container height=$containerHeight ${container.javaClass.name}")
            return
        }
        if (moduleLogEnabled) {
            Log.i(TAG, "seedling-card: dispatch container=${container.javaClass.name} " +
                "w=${containerView.width} h=$containerHeight childCount=${containerView.childCount}")
        }
        if (!seedlingCapsuleGlassEnabled) {
            if (moduleLogEnabled) Log.i(TAG, "seedling-card: island glass off, skip")
            return
        }
        // [2026-09-16] 目标 = **CapsuleView（胶囊本体）**，不是 CapsuleContainer（外框）：
        // `CapsuleContainer.onMeasure`(:445-449) 宽度 = 子宽 + padding，`CapsuleContainer.q()`(:506-516)
        // 把 CapsuleView **居中**摆放 —— 拿外框当本体玻璃必然画大、超出胶囊（用户反馈「玻璃比岛大」）。
        val capsules = ArrayList<View>(4)
        collectCapsuleViews(containerView, capsules)
        if (capsules.isEmpty()) {
            // [2026-09-16 用户需求] 胶囊 ↔ 展开大卡片互转期间插件会把 CapsuleView 从容器移除
            // （真机实证），此时**玻璃不显示**——用户明确要求「动画期间干脆不显示」，不做补绘。
            lastIslandW = -1
            lastIslandH = -1
            return
        }
        val containerLoc = IntArray(2)
        try {
            containerView.getLocationOnScreen(containerLoc)
        } catch (t: Throwable) {
            return
        }
        for (capsule in capsules) {
            // 不可见的不画（插件动画期间会短暂置 INVISIBLE）。
            if (capsule.visibility != View.VISIBLE) {
                logThrottled("seedling-invis", Log.WARN, 500L) {
                    "seedling-card: skip invisible CapsuleView vis=${capsule.visibility}"
                }
                continue
            }
            // CapsuleView 由 setLeftTopRightBottom 摆放，getWidth() 偶为 0（布局未完成）→ 退回 measured
            val cvW = if (capsule.width > 0) capsule.width else capsule.measuredWidth
            val cvH = if (capsule.height > 0) capsule.height else capsule.measuredHeight
            if (cvW <= 0 || cvH <= 0) {
                logThrottled("seedling-zero", Log.WARN, 500L) {
                    "seedling-card: ABORT zero size cv=${capsule.width}x${capsule.height} " +
                        "measured=${capsule.measuredWidth}x${capsule.measuredHeight} vis=${capsule.visibility}"
                }
                continue
            }
            // ★ 可见胶囊宽度 = `CapsuleView.capsuleDrawableWidth`：背景/前景 Drawable 的 bounds 被限制在
            //   这个宽度内并**水平居中**（CapsuleView.setCapsuleDrawableWidth:249-255），
            //   高度取 CapsuleView 全高（bounds 用的就是 getMeasuredHeight()）。
            //   该字段由动画逐帧更新 → 玻璃尺寸天然跟随小岛动效。
            // 可见宽度直接用 CapsuleView 自身宽度。**不要读 Drawable bounds**：
            // 真机实测 background 被本函数置空后读不到，foreground 的 bounds 是 3x3（无关小图），
            // 拿它当宽度会让玻璃宽度在 402 与 3 之间跳 → 动画期间玻璃几乎不可见（实测）。
            // 402 为用户确认「平齐」的尺寸。
            val drawW = cvW
            // ★ **不能用 CapsuleView.getLocationOnScreen()**：真机实测返回 (720,80) = 屏幕中心
            //   （玻璃画在岛中心，用户反馈"岛偏了"）。原因在插件里——`CapsuleContainer.q()`
            //   计算居中位置时 CapsuleView 尚未测量（measuredWidth=0 → (1440-0)/2=720），
            //   之后测量完成但位置**不再更新** → 该值陈旧。
            //   改为按容器几何直接算居中：系统布局中胶囊水平/垂直居中于容器
            //   （CapsuleContainer 自身也居中于 CapsulePluginContainer，真机 432@504 = (1440-432)/2）。
            val left = containerLoc[0] + (containerView.width - drawW) / 2f
            val top = containerLoc[1] + (containerHeight - cvH) / 2f
            val region = RectF(left, top, left + drawW, top + cvH)
            if (region.width() <= 0f || region.height() <= 0f) {
                logThrottled("seedling-badregion", Log.WARN, 500L) {
                    "seedling-card: ABORT bad region=$region draw=${drawW}x$cvH"
                }
                continue
            }
            // [2026-09-16 用户需求] **去掉岛的原版渲染**：置空背景/前景 Drawable（内容 View 不受影响，
            // 图标/文字照常绘制），配合「玻璃画在子 View 之下」→ 小岛呈现为「纯液态玻璃 + 内容」。
            // 幂等（已是 null 则跳过）；失败静默（保持原渲染，不崩）。
            try {
                if (capsule.background != null) capsule.background = null
                if (capsule.foreground != null) capsule.foreground = null
            } catch (t: Throwable) {
            }
            // 胶囊全圆角：min(可见宽, 高)/2
            val cornerOverride = Math.min(drawW, cvH) / 2f
            val id = System.identityHashCode(capsule)
            registerSeedlingCardRegion(
                id, region, Rect(0, 0, drawW, cvH), capsule,
                cornerRadiusOverride = cornerOverride,
            )
            // canvas 原点是本容器左上角 → 偏移取胶囊可见区相对容器的位置
            val offX = left - containerLoc[0]
            val offY = top - containerLoc[1]
            drawSeedlingGlassOnCanvas(
                canvas, capsule, id, drawW, cvH, region,
                offsetX = offX, offsetY = offY,
            )
        }
    }

    /**
     * [2026-09-16 小岛玻璃化] 递归收集容器内所有 `CapsuleView`（胶囊本体）。
     *
     * View 层级（真机 dump + 插件反编译实证）：
     * ```
     * CapsulePluginContainer            w=1440 h=160   ← 本 hook 的容器
     *  └─ CapsuleContainerRoot          w=1440 h=160   ← 撑满状态栏
     *      └─ CapsuleContainer          w=432  h=160   ← 外框（onMeasure 宽 = 子宽 + padding）
     *          └─ CapsuleView           ← ★ 胶囊本体（CapsuleContainer.q() 把它居中摆放）
     *              └─ FrameLayout ...   ← 内容
     * ```
     * 插件 View 在独立 classloader → 用**字符串类名**比较（同 [mountCardBackgroundGlass]）。
     * 命中即停止下钻（CapsuleView 内部是内容 View，不是胶囊本体）。
     */
    private fun collectCapsuleViews(parent: View, out: MutableList<View>, depth: Int = 0) {
        if (depth > 8) return
        if (parent.javaClass.name == CLASS_CAPSULE_VIEW) {
            out.add(parent)
            return
        }
        val group = parent as? android.view.ViewGroup ?: return
        for (i in 0 until group.childCount) {
            // [2026-09-16] **不在此过滤 visibility**：插件动画期间会反复切 CapsuleView 的 visibility
            // （CapsuleView.c(hide) → setVisibility(hide ? INVISIBLE : VISIBLE)），过滤掉就分不清
            // 「树里根本没有」和「有但当前不可见」——两者处理方式不同。visibility 判定下移到渲染循环。
            collectCapsuleViews(group.getChildAt(i), out, depth + 1)
        }
    }


    /** [seedling 诊断 2026-08-15] 递归打印 View 树（类名/尺寸/屏幕位置），定位展开大卡片真实 View。 */
    private fun dumpViewTree(v: View, depth: Int, maxDepth: Int) {
        if (depth > maxDepth) return
        val loc = IntArray(2)
        try {
            v.getLocationOnScreen(loc)
        } catch (t: Throwable) {
            loc[0] = -1
            loc[1] = -1
        }
        Log.i(TAG, "seedling-card: ${"  ".repeat(depth)}${v.javaClass.name} w=${v.width} h=${v.height} loc=(${loc[0]},${loc[1]})")
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) {
                try {
                    dumpViewTree(v.getChildAt(i), depth + 1, maxDepth)
                } catch (t: Throwable) {
                }
            }
        }
    }

    /** [seedling 诊断 2026-08-15] 打印 CapsulePluginContainer 的 parent 链，定位展开大卡片所在容器/hook 点。 */
    private fun dumpParents(v: View) {
        var cur: ViewParent? = v.parent
        var depth = 0
        while (cur is View && depth < 10) {
            val loc = IntArray(2)
            try {
                cur.getLocationOnScreen(loc)
            } catch (t: Throwable) {
                loc[0] = -1
                loc[1] = -1
            }
            val cc = if (cur is android.view.ViewGroup) cur.childCount else -1
            Log.i(TAG, "seedling-card: parent[$depth] ${cur.javaClass.name} w=${cur.width} h=${cur.height} loc=(${loc[0]},${loc[1]}) childCount=$cc")
            cur = cur.parent
            depth++
        }
    }

    /**
     * [spec/44] 种子卡片展开大卡片区域注册：构造 [RegionEntry] 写入 [screenRegionMap]
     * （key = 大卡片子 View identityHashCode），供渲染侧查表。不走 posteffect → 无 BlurConfig 可反射，
     * 圆角用形状启发式（min(w,h) × [KEY_SEEDLING_CARD_CORNER_RATIO]，大卡片大圆角）。
     * 新元素首次出现 → 触发整屏快照重抓（复用现有内容变化驱动）。
     * @param source 元素来源标记（默认 "seedling-heuristic"；[spec/44b] OplusCustomRow 传 "seedling-oplusrow"，
     *  [spec/44c] CardBackgroundView 传 "seedling-cardview"），拼进 hostViewName 便于诊断（"source:类名"）。
     * @param cornerRadiusOverride 圆角显式覆盖 px（>0 时优先，跳过 min(w,h)×ratio 启发式）。[spec/44c]
     *  CardBackgroundView 用卡片真实圆角 round_corner_radius_fluid_cloud=20dp 换算 px（≈52px），
     *  启发式 min(w,h)×0.22（大卡 ≈132px）过大失真。
     */
    private fun registerSeedlingCardRegion(
        id: Int,
        screenRegion: RectF,
        bounds: Rect,
        hostView: View,
        source: String = "seedling-heuristic",
        cornerRadiusOverride: Float = 0f,
    ): Boolean {
        registerHostView(hostView)
        val cornerRadius = if (cornerRadiusOverride > 0f) {
            cornerRadiusOverride
        } else {
            Math.min(bounds.width(), bounds.height()).toFloat() * seedlingCardCornerRatio
        }
        val hostName = if (source.isEmpty()) hostView.javaClass.simpleName else "$source:${hostView.javaClass.simpleName}"
        val entry = RegionEntry(
            screenRegion, bounds, hostName,
            cornerRadius, "seedling-heuristic",
            cornerIsConic = true, cornerWeight = DEFAULT_CORNER_WEIGHT,
            hostViewRef = WeakReference(hostView),
        )
        val previous = screenRegionMap[id]
        if (previous == null) {
            screenRegionMap[id] = entry
            if (screenRegionMap.size > 1024) screenRegionMap.clear()
            triggerBackgroundCapture()
            Log.i(TAG, "seedling-card: region registered id=$id view=$hostName region=$screenRegion corner=$cornerRadius")
        } else if (previous.region != screenRegion || previous.autoBounds != bounds) {
            screenRegionMap[id] = entry
        }
        return true
    }

    /**
     * [spec/44] 把液态玻璃画到容器 dispatchDraw 的 canvas（子 View 之下）。
     * 复用整屏快照背景源 + srcRect 折算（[resolveRenderSource]）+ [LiquidGlassShader] 管线。
     * 坐标系：dispatchDraw 的 canvas 是容器局部坐标 → translate 到元素局部坐标后画（uViewport = 元素尺寸，
     * shader 采样坐标 = 元素局部坐标，与现有 drawable 渲染契约一致）。失败静默（保持插件原绘制）。
     * @param offsetX/offsetY 玻璃绘制平移量。默认 = host.left/host.top（[spec/44] 旧路径：canvas 是
     *  容器局部坐标，需平移到容器内子 View 的局部原点）。[spec/44b] OplusCustomRow 自身当 host 时
     *  dispatchDraw 的 canvas 原点本就是 row 左上角，**必须传 (0,0)**——再按 host.left/top 平移会
     *  把玻璃整体下移/右移、超出裁剪区被裁掉（glass 不可见）。
     */
    private fun drawSeedlingGlassOnCanvas(
        canvas: Canvas,
        host: View,
        id: Int,
        w: Int,
        h: Int,
        screenRegion: RectF,
        offsetX: Float = host.left.toFloat(),
        offsetY: Float = host.top.toFloat(),
    ) {
        if (w <= 0 || h <= 0) return
        val entry = screenRegionMap[id] ?: run {
            logThrottled("seedling-nomap", Log.WARN, 500L) { "seedling-card: ABORT no region map id=$id" }
            return
        }
        val renderSource = resolveRenderSource(id, screenRegion) ?: run {
            logThrottled("seedling-nosrc", Log.WARN, 500L) {
                "seedling-card: ABORT no render source id=$id region=$screenRegion"
            }
            return
        }
        val srcRect = renderSource.srcRect
        val source = renderSource.source
        val viewport = RectF(0f, 0f, w.toFloat(), h.toFloat())
        val cornerRadius = entry.cornerRadius.takeIf { it > 0f }
            ?: (Math.min(w, h).toFloat() * seedlingCardCornerRatio)
        val params = buildParams(w.toFloat(), h.toFloat(), cornerRadius, entry.cornerIsConic, entry.cornerWeight)
        val shader = shaderCache.getOrPut(id) { LiquidGlassShader.create() }
        val sourceShader = LiquidGlassShader.createSourceBitmapShader(source)
        LiquidGlassShader.setUniforms(
            shader, params, sourceShader, sourceShader,
            sourceRect = srcRect,
            lowResSourceRect = srcRect,
            debugCoord = 0f,
            maskRect = null,
            maskCornerRadius = 0f,
            cornerConicBlend = 1f,
            maskOffsetX = 0f,
            maskOffsetY = 0f,
            maskAlpha = 0f,  // 桌面流体云无 shade 遮罩（遮罩仅作用于 SystemUI 下拉）
        )
        appliedUniformsMap[id] = AppliedKey(
            frame = 0L, sourceBitmapId = System.identityHashCode(source),
            viewport = viewport, srcRect = srcRect, maskRect = null,
            cornerIsConic = entry.cornerIsConic, cornerWeight = entry.cornerWeight,
            cornerRadius = cornerRadius, cornerConicBlend = 1f, maskAlpha = 0f,
        )
        canvas.save()
        try {
            canvas.translate(offsetX, offsetY)
            seedlingGlassPaint.shader = shader
            val path = android.graphics.Path().apply {
                val r = cornerRadius
                addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r, android.graphics.Path.Direction.CW)
            }
            canvas.drawPath(path, seedlingGlassPaint)
        } catch (t: Throwable) {
            Log.e(TAG, "seedling-card: draw glass on canvas failed id=$id", t)
        } finally {
            // 清理 paint.shader（防止残留到下一帧其他绘制）；canvas.restore 还原 translate
            seedlingGlassPaint.shader = null
            canvas.restore()
        }
    }

    // ------------------------------------------------------------ [spec/44c] 流体云展开卡片玻璃化（CardBackgroundView 宿主）

    /**
     * [spec/44c 2026-08-15] hook `CardBackgroundView.onDraw(Canvas)`（SystemUIPlugin.apk 反编译实锤的
     * 展开大卡背景 View）画液态玻璃。
     *
     * 真机 View 树（tools/dump/SystemUIPlugin/sources/com/oplus/systemui/plugins/seedling/card/ui/view/）：
     * - 宿主容器 `CardContainer`（extends COUIRecyclerView，id card_container）
     * - 卡片根 `CardView`（FrameLayout，id seeding_card_view）
     * - 背景 `CardBackgroundView`（extends View，id seedling_card_bg）：背景 = viewRootManager.
     *   getBackgroundBlurDrawable()（BackgroundBlurDrawable，blurRadius 540，Oplus 材质 blend #ff585858 /
     *   mix #b3262626，圆角 20dp）；draw(Canvas) → super.draw（仅硬件加速路径）；**不 override onDraw**
     *   → hook 基类 View.onDraw(Canvas) + 拦截体判定 this 是 CardBackgroundView 实例（防全 View 影响）。
     *   （锁屏流体云走 com.oplus.seedling.pluginapp 不同包，本 hook 天然不碰锁屏。）
     *
     * 绘制顺序（View.draw 步骤）：drawBackground（blur 背景）→ onDraw（本 hook：先 proceed 空实现，
     * 再画玻璃盖在背景之上）→ CardView 内容子 View（index1 contentView）在玻璃之上 → 不遮文字。
     * 玻璃 shader 半透明采样壁纸（[resolveRenderSource] 整屏快照），覆盖系统 blur 背景。
     *
     * 区域登记：CardBackgroundView bounds → [registerSeedlingCardRegion]（source="seedling-cardview"，
     * CONIC=true，首现 triggerBackgroundCapture）；玻璃 = [drawSeedlingGlassOnCanvas]（自身作 host，
     * offsetX/Y=0——onDraw canvas 原点本就是 view 左上角，再按 host.left/top 平移会下移/右移被裁掉）。
     *
     * 总开关 [seedlingCardGlassEnabled]（KEY_SEEDLING_CARD_GLASS_ENABLE，默认 false，用户手动开）。
     * ExceptionMode.PROTECTIVE + 全 try-catch，挂载失败不影响其他 hook。
     *
     * [P1 2026-08-15] SystemUIPlugin.apk 是独立 system_ext APK（独立/惰性 classloader），install 时
     * Class.forName 可能失败（同 mountSeedlingCardContainer 前车之鉴）→ 失败**不 return**：View.onDraw
     * 无条件挂载，拦截体用 `javaClass.name == CLASS_CARD_BACKGROUND_VIEW` 字符串比较（跨 classloader
     * 安全）命中后才进完整逻辑；命中时惰性缓存 thisView.javaClass，后续用 isInstance。
     * [P2] 全局 View.onDraw 是 SystemUI 最高频框架方法 → 拦截体先字符串类名比较快路径，未命中立即跳过。
     */
    private fun mountCardBackgroundGlass(api: XposedInterface, classLoader: ClassLoader) {
        if (!seedlingCardGlassEnabled) {
            Log.i(TAG, "CardBackgroundView glass disabled by prefs, skip mount")
            return
        }
        if (cardBgGlassMounted) return
        // 仅作 install 时预加载尝试：成功则拦截体可直接 isInstance；失败走字符串类名兜底，不阻塞挂载。
        var bgCls: Class<*>? = try {
            Class.forName(CLASS_CARD_BACKGROUND_VIEW, false, classLoader)
        } catch (t: Throwable) {
            Log.d(TAG, "CardBackgroundView glass: class not loaded yet (deferred), fallback to runtime name check: $CLASS_CARD_BACKGROUND_VIEW")
            null
        }
        try {
            // CardBackgroundView 不 override onDraw → hook 基类 View.onDraw(Canvas)（protected →
            // getDeclaredMethod + setAccessible，同 mountTextViewOnDraw 风格），拦截体快速判定 this。
            val method = View::class.java.getDeclaredMethod(METHOD_ON_DRAW, Canvas::class.java).apply { isAccessible = true }
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        val thisView = try {
                            chain.getThisObject() as? View
                        } catch (t: Throwable) {
                            null
                        }
                        // [P2] 性能快路径：最高频方法，先字符串类名比较（跨 classloader 安全），未命中立即跳过
                        if (thisView != null && thisView.javaClass.name == CLASS_CARD_BACKGROUND_VIEW) {
                            // [P1] 惰性缓存：命中时缓存 thisView.javaClass（跨 classloader 实例），
                            // 后续同实例用 isInstance（比字符串比较更准）。
                            var cached = bgCls
                            if (cached == null) {
                                cached = thisView.javaClass
                                bgCls = cached
                            }
                            if (cached.isInstance(thisView)) {
                                // [诊断] onDraw 命中计数（节流：每 120 帧打一条，防刷屏）
                                if (moduleLogEnabled) {
                                    val count = ++cardBgDrawCount
                                    if (count % 120 == 1) {
                                        Log.i(TAG, "seedling-cardview: onDraw count=$count w=${thisView.width} h=${thisView.height}")
                                    }
                                }
                                // [2026-08-15 持续抓屏对齐 heads-up] 命中期间主动持续抓屏：流体云展开大卡是
                                // 持续显示窗口（非 heads-up/banner 瞬态），卡片显示期间以 panelCaptureHz 速率
                                // 循环 scheduleElementCaptures(force=true) 入队主 worker（背景永远最新，移动
                                // 下方背景立即抓屏）。**每次命中都重试启动**——首启失败（attach/root 未就绪）下帧
                                // 自动重试，不依赖一次性 `!==` 门控（否则首启失败后持续抓屏永不启动）。
                                if (cardBackgroundActiveHost?.get() !== thisView) {
                                    cardBackgroundActiveHost = WeakReference(thisView)
                                    if (moduleLogEnabled) {
                                        Log.i(TAG, "seedling-cardview: active host set (new instance) w=${thisView.width} h=${thisView.height}")
                                    }
                                }
                                if (!cardBackgroundContinuousRunning) {
                                    startCardBackgroundContinuousCapture()
                                }
                                val canvas = try {
                                    chain.getArg(0) as? Canvas
                                } catch (t: Throwable) {
                                    null
                                }
                                // 先 proceed（View.onDraw 基类空实现），再画玻璃盖在 blur 背景之上；
                                // 内容层（CardView 子 index1）在玻璃之上，不遮文字。
                                val result = chain.proceed()
                                if (canvas != null) {
                                    try {
                                        renderCardBackgroundGlass(canvas, thisView)
                                    } catch (t: Throwable) {
                                        Log.e(TAG, "seedling-cardview: onDraw glass failed", t)
                                    }
                                }
                                return@intercept result
                            }
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "seedling-cardview: onDraw intercept error", t)
                    }
                    chain.proceed()
                }
            cardBgGlassMounted = true
            Log.i(TAG, "Seedling card glass mounted: $CLASS_CARD_BACKGROUND_VIEW#$METHOD_ON_DRAW(Canvas)")
        } catch (t: Throwable) {
            Log.e(TAG, "Seedling card glass mount FAILED: $CLASS_CARD_BACKGROUND_VIEW#$METHOD_ON_DRAW", t)
        }
    }

    /**
     * [spec/44c] CardBackgroundView「容器即元素」玻璃绘制路径：背景 View 自身 bounds 登记 + 画玻璃。
     * onDraw canvas 原点 = view 左上角 → drawSeedlingGlassOnCanvas offsetX/Y=0（不平移，防玻璃整体
     * 下移/右移被裁掉）。新元素首现 registerSeedlingCardRegion 内部 triggerBackgroundCapture。
     * [2026-08-15] 圆角 = 运行时读资源 dimen `round_corner_radius_fluid_cloud`（20dp，SystemUIPlugin.apk
     * 资源），一次解析缓存（[roundCornerFluidCloudPx]），失败兜底 20dp×density；不是启发式 min(w,h)×ratio。
     */
    private fun renderCardBackgroundGlass(canvas: Canvas, bgView: View) {
        val w = bgView.width
        val h = bgView.height
        if (w <= 0 || h <= 0 || bgView.visibility != View.VISIBLE) return
        val loc = IntArray(2)
        try {
            bgView.getLocationOnScreen(loc)
        } catch (t: Throwable) {
            return
        }
        val screenRegion = RectF(
            loc[0].toFloat(), loc[1].toFloat(),
            (loc[0] + w).toFloat(), (loc[1] + h).toFloat(),
        )
        val id = System.identityHashCode(bgView)
        // 圆角：一次解析并缓存（避免每帧 getIdentifier）；SystemUIPlugin.apk 资源，包 com.oplus.systemui.plugins
        if (roundCornerFluidCloudPx <= 0f) {
            roundCornerFluidCloudPx = try {
                val resId = bgView.resources.getIdentifier(
                    "round_corner_radius_fluid_cloud", "dimen", "com.oplus.systemui.plugins",
                )
                if (resId != 0) {
                    bgView.resources.getDimensionPixelSize(resId).toFloat()
                } else {
                    20f * bgView.resources.displayMetrics.density
                }
            } catch (t: Throwable) {
                20f * bgView.resources.displayMetrics.density
            }
        }
        registerSeedlingCardRegion(id, screenRegion, Rect(0, 0, w, h), bgView, "seedling-cardview", roundCornerFluidCloudPx)
        drawSeedlingGlassOnCanvas(canvas, bgView, id, w, h, screenRegion, offsetX = 0f, offsetY = 0f)
    }

    /** [2026-08-15 持续抓屏对齐 heads-up] CardBackgroundView 活跃判定：活跃宿主存在且仍 attach、可见
     *  （展开大卡收起/窗口销毁 → detach 或不可见 → false）。供持续抓屏 worker 判断停止（不后台耗电）。
     *  流体云展开大卡是**持续显示窗口**（非 heads-up/banner 瞬态几秒），卡片收起后 worker 必须可靠
     *  停止否则 120Hz 空转耗电——收起由系统销毁窗口/隐藏宿主驱动（collapse 后 CardBackgroundView
     *  detach 或不可见，实测确认）。 */
    private fun isCardBackgroundHostActive(): Boolean {
        val host = cardBackgroundActiveHost?.get() ?: return false
        return try {
            host.isAttachedToWindow && host.visibility == View.VISIBLE && host.width > 0 && host.height > 0
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * [2026-08-15 持续抓屏对齐 heads-up] 流体云展开大卡显示期间主动持续抓屏 worker：命中期间以
     * [continuousCaptureIntervalMs]（[panelCaptureHz] 速率：运动 120Hz / 静止 60Hz）循环
     * [scheduleElementCaptures](force=true) → 入队主 worker 队列（captureDisplay 主 worker 串行，
     * 本线程零阻塞；pending 去重单通道）。停止：卡片收起/窗口销毁（[isCardBackgroundHostActive] false）
     * → 退出（零后台耗电）；锁屏期间不启动/退出。与 heads-up / simple-banner 持续源独立。
     */
    private fun startCardBackgroundContinuousCapture() {
        if (cardBackgroundContinuousRunning) return
        if (cardBackgroundActiveHost?.get() == null) return
        if (!isCardBackgroundHostActive()) return
        if (isKeyguardLockedNow()) return
        cardBackgroundContinuousRunning = true
        if (moduleLogEnabled) {
            Log.i(TAG, "seedling-cardview: continuous capture worker start (rate ${panelCaptureHz()}Hz)")
        }
        try {
            Thread {
                try {
                    while (true) {
                        // 卡片收起/窗口销毁（detach/隐藏）→ 停止（零后台耗电）
                        if (!isCardBackgroundHostActive()) {
                            cardBackgroundActiveHost = null
                            Log.i(TAG, "bg-element: seedling-cardview gone, continuous capture stopped")
                            break
                        }
                        if (isKeyguardLockedNow()) {
                            cardBackgroundActiveHost = null
                            Log.i(TAG, "bg-element: keyguard locked, seedling-cardview continuous capture stopped (passive only)")
                            break
                        }
                        markContentChanged()
                        scheduleElementCaptures(force = true)
                        logThrottled("seedling-cardview-continuous", Log.INFO) {
                            "seedling-cardview: continuous tick queued (single-channel)"
                        }
                        try {
                            Thread.sleep(continuousCaptureIntervalMs())
                        } catch (ignored: InterruptedException) {
                        }
                    }
                } finally {
                    cardBackgroundContinuousRunning = false
                }
            }.start()
        } catch (t: Throwable) {
            cardBackgroundContinuousRunning = false
            Log.e(TAG, "seedling-cardview: spawn continuous worker failed", t)
        }
    }

    /** [2026-08-15 持续抓屏对齐 heads-up] 流体云展开大卡窗口根 sfc：取活跃宿主 [cardBackgroundActiveHost]
     *  的 rootView 反射取 sfc（宿主即窗口内 View，rootView=seed card 窗口根）。仿 [resolveHeadsUpSfc]
     *  思路但直接用宿主定位（不需类名/标题判定）；宿主 null/未 attach 返回 null（不 exclude，无害——半透明
     *  玻璃自采样仅轻微糊化）。@Synchronized 防与 registerHostView 并发。 */
    @Synchronized
    private fun resolveCardBackgroundSfc(): SurfaceControl? {
        val host = cardBackgroundActiveHost?.get() ?: return null
        if (!host.isAttachedToWindow) return null
        return try {
            reflectViewRootSurfaceControl(host.rootView)
        } catch (t: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------ [2026-08-15 轻打扰折叠横幅玻璃化 方案 B] Simple Banner dispatchDraw 画玻璃

    /**
     * [2026-08-15 轻打扰折叠横幅玻璃化 方案 B] hook `FullScreenBanner.dispatchDraw(Canvas)` 画液态玻璃。
     *
     * 背景：BlurProxy 替换路线（方案 A）失败——强制 BlurTypePlatformStatic 后系统
     * `ensurePlatformStaticBlurDrawable failed`（静态模糊 drawable 创建失败）→ 背景丢失变白（logcat
     * 实证）→ **撤销**。本方案复用流体云 CardBackgroundView 已实证思路：不碰 BlurProxy，直接 hook
     * 容器 dispatchDraw 画玻璃盖在系统原 Motion 模糊背景之上。
     *
     * View 树（逆向实证）：FullScreenBannerContainer（LinearLayout）→ FullScreenBanner（LinearLayout，
     * 背景 setBackground(blurDrawable)）；窗口 type 2017 "Simple Banner Window"。
     *
     * 绘制顺序：**先画玻璃再 chain.proceed()**（dispatchDraw 前 drawBackground 已画系统 Motion 模糊背景
     * → 玻璃盖在背景之上；proceed 画内容子 View → 内容在玻璃之上不遮文字）。
     * 玻璃 = [drawSeedlingGlassOnCanvas]（FullScreenBanner 自身作 host，offsetX/Y=0——dispatchDraw
     * canvas 原点本就是 view 左上角，再按 host.left/top 平移会下移/右移被裁掉）。
     *
     * 区域登记：FullScreenBanner bounds → [registerSeedlingCardRegion]（source="simple-banner"，
     * CONIC=true，首现 triggerBackgroundCapture）；玻璃渲染 + 文字取色（isOverGlassRegion）都覆盖该区域。
     * 快照 exclude：Simple Banner Window 根 surface 已由 [resolveSimpleBannerSfc] 排除（防玻璃自采样）。
     *
     * 总开关 [seedlingCardGlassEnabled]（同 CardBackgroundView；改动需重启 SystemUI 生效）。
     * ExceptionMode.PROTECTIVE + 全 try-catch；挂载失败不影响其他 hook。
     */
    private fun mountSimpleBannerGlass(api: XposedInterface, classLoader: ClassLoader) {
        if (!seedlingCardGlassEnabled) {
            Log.i(TAG, "SimpleBanner glass disabled by prefs, skip mount")
            return
        }
        if (simpleBannerGlassMounted) return
        try {
            // FullScreenBanner 未 override dispatchDraw → hook 基类 ViewGroup.dispatchDraw(Canvas)，
            // 拦截体字符串类名比较判定（跨 classloader 安全，同 mountCardBackgroundGlass 判实例模式）。
            val vgCls = Class.forName(CLASS_VIEW_GROUP, false, classLoader)
            val method = vgCls.getDeclaredMethod(METHOD_DISPATCH_DRAW, Canvas::class.java)
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        val thisView = try {
                            chain.getThisObject() as? View
                        } catch (t: Throwable) {
                            null
                        }
                        if (thisView != null && thisView.javaClass.name == CLASS_FULL_SCREEN_BANNER) {
                            // [诊断] 命中计数（节流：每 120 帧打一条，防刷屏）
                            if (moduleLogEnabled) {
                                val count = ++simpleBannerDispatchCount
                                if (count % 120 == 1) {
                                    Log.i(TAG, "simple-banner: dispatch count=$count w=${thisView.width} h=${thisView.height}")
                                }
                            }
                            // [2026-08-15 持续抓屏驱动] 命中期间主动持续抓屏（banner 短暂窗口动画/背景
                            // 变化快照跟上；与 heads-up 持续源独立，复用 panelCaptureHz 速率/单通道调度）。
                            // 新实例登记宿主；**每次命中都重试启动**——首启失败（attach/root 未就绪）下帧
                            // 自动重试，不依赖一次性 `!==` 门控（否则首启失败后持续抓屏永不启动）。
                            if (simpleBannerActiveHost?.get() !== thisView) {
                                simpleBannerActiveHost = WeakReference(thisView)
                                // [2026-08-15 自愈] 同 attach hook：新实例强制重置 running（防卡死假阳性）
                                simpleBannerContinuousRunning = false
                                if (moduleLogEnabled) {
                                    Log.i(TAG, "simple-banner: active host set (new instance) w=${thisView.width} h=${thisView.height}")
                                }
                            }
                            // [2026-08-15 诊断] dispatch 触发 start 前后打 running 值（定位 worker 未启动原因：
                            // 是 start 未被调用 / running 卡 true / start 内部 return）
                            if (!simpleBannerContinuousRunning) {
                                if (moduleLogEnabled) {
                                    Log.i(TAG, "simple-banner: dispatch trigger start, running=$simpleBannerContinuousRunning host=${simpleBannerActiveHost?.get() != null}")
                                }
                                startSimpleBannerContinuousCapture(force = true)
                                if (moduleLogEnabled) {
                                    Log.i(TAG, "simple-banner: dispatch start returned, running now=$simpleBannerContinuousRunning")
                                }
                            } else if (moduleLogEnabled) {
                                Log.i(TAG, "simple-banner: dispatch skip start, already running")
                            }
                            val canvas = try {
                                chain.getArg(0) as? Canvas
                            } catch (t: Throwable) {
                                null
                            }
                            // 先画玻璃（盖在系统 Motion 模糊背景之上），再 proceed 画内容子 View（不遮文字）
                            if (canvas != null) {
                                try {
                                    renderSimpleBannerGlass(canvas, thisView)
                                } catch (t: Throwable) {
                                    Log.e(TAG, "simple-banner: dispatchDraw glass failed", t)
                                }
                            }
                            val result = chain.proceed()
                            return@intercept result
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "simple-banner: dispatchDraw intercept error", t)
                    }
                    chain.proceed()
                }
            simpleBannerGlassMounted = true
            Log.i(TAG, "Simple banner glass mounted: $CLASS_FULL_SCREEN_BANNER#$METHOD_DISPATCH_DRAW(Canvas)")
        } catch (t: Throwable) {
            Log.e(TAG, "Simple banner glass mount FAILED: $CLASS_FULL_SCREEN_BANNER#$METHOD_DISPATCH_DRAW", t)
        }
    }

    /**
     * [2026-08-15 强制刷新] hook 基类 `View.onAttachedToWindow()`：横幅 View（FullScreenBanner）attach
     * 窗口即触发——比 dispatchDraw 绘制命中可靠（attach 是必然事件，不受绘制频率影响；绘制稳定后
     * dispatchDraw 可能不再触发 → 依赖它的持续抓屏 worker 可能永不启动）。命中 → 登记活跃宿主 +
     * **强制**启动持续抓屏（force=true 跳过活跃/keyguard 判定：此刻就是横幅出现，立即刷新）。
     * 字符串类名比较快路径（全局高频方法，未命中立即 proceed）；ExceptionMode.PROTECTIVE + try-catch。
     */
    private fun mountSimpleBannerAttachHook(api: XposedInterface, classLoader: ClassLoader) {
        // [2026-08-15 诊断] 无条件入口日志：确认函数被调用 + 提前 return 原因（enabled/mounted 值）。
        // 此前真机 install 日志缺失「Simple banner attach hook mounted」且无 FAILED → 函数疑似未执行到
        // mounted（被 enabled/mounted return 挡住）或 install 调用未生效——本条日志定论。
        Log.i(TAG, "simple-banner: attach hook mount enter, enabled=$seedlingCardGlassEnabled mounted=$simpleBannerAttachMounted")
        if (!seedlingCardGlassEnabled) return
        if (simpleBannerAttachMounted) return
        try {
            // onAttachedToWindow 是 protected → getDeclaredMethod + setAccessible（同 mountCardBackgroundGlass 风格）
            val method = View::class.java.getDeclaredMethod(METHOD_ON_ATTACHED_TO_WINDOW).apply { isAccessible = true }
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        val thisView = try {
                            chain.getThisObject() as? View
                        } catch (t: Throwable) {
                            null
                        }
                        if (thisView != null && thisView.javaClass.name == CLASS_FULL_SCREEN_BANNER) {
                            // 横幅 attach → 登记宿主 + 强制启动（立即刷新，不等绘制）
                            if (simpleBannerActiveHost?.get() !== thisView) {
                                simpleBannerActiveHost = WeakReference(thisView)
                                // [2026-08-15 自愈] 新横幅实例 → 强制重置 running：防旧 worker 异常退出/卡死
                                // 导致 running 卡 true → 新实例 start 被 `!running` 挡住 → 持续抓屏永不启动
                                // （且旧版无 skip 日志，静默失败，现象 = attach host set 出现但 continuous 全无）
                                simpleBannerContinuousRunning = false
                                if (moduleLogEnabled) {
                                    Log.i(TAG, "simple-banner: attach host set (force refresh) w=${thisView.width} h=${thisView.height}")
                                }
                            }
                            if (!simpleBannerContinuousRunning) {
                                if (moduleLogEnabled) {
                                    Log.i(TAG, "simple-banner: attach trigger start, running=$simpleBannerContinuousRunning host=${simpleBannerActiveHost?.get() != null}")
                                }
                                startSimpleBannerContinuousCapture(force = true)
                                if (moduleLogEnabled) {
                                    Log.i(TAG, "simple-banner: attach start returned, running now=$simpleBannerContinuousRunning")
                                }
                            } else if (moduleLogEnabled) {
                                Log.i(TAG, "simple-banner: attach skip start, already running")
                            }
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "simple-banner: attach hook error", t)
                    }
                    chain.proceed()
                }
            simpleBannerAttachMounted = true
            Log.i(TAG, "Simple banner attach hook mounted: View#$METHOD_ON_ATTACHED_TO_WINDOW")
        } catch (t: Throwable) {
            Log.e(TAG, "Simple banner attach hook mount FAILED: View#$METHOD_ON_ATTACHED_TO_WINDOW", t)
        }
    }

    // ------------------------------------------------------------ [spec/61] 流体云展开大卡 attach 持续抓屏 + 系统模糊力度 ------------------------------------------------------------

    /**
     * [spec/61 2026-08-23] 流体云展开大卡 attach 强制启动持续抓屏（仿 Simple Banner attach hook）：
     * CardBackgroundView attach 窗口即触发——比 onDraw 绘制命中可靠（attach 是必然事件，绘制稳定后
     * onDraw 可能不再触发 → 依赖它的持续抓屏 worker 启动慢/不启动）。命中 → 登记活跃宿主 +
     * 强制启动持续抓屏（[startCardBackgroundContinuousCapture]，面板同款速率：运动 120Hz / 静止 60Hz）。
     * 字符串类名比较快路径（全局高频方法，未命中立即 proceed）；ExceptionMode.PROTECTIVE + try-catch。
     */
    private fun mountCardBackgroundAttachHook(api: XposedInterface, classLoader: ClassLoader) {
        if (!seedlingCardGlassEnabled) {
            Log.i(TAG, "seedling-cardview: attach hook skip, glass disabled by prefs")
            return
        }
        if (cardBgAttachMounted) return
        try {
            // onAttachedToWindow 是 protected → getDeclaredMethod + setAccessible（同 mountSimpleBannerAttachHook 风格）
            val method = View::class.java.getDeclaredMethod(METHOD_ON_ATTACHED_TO_WINDOW).apply { isAccessible = true }
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        val thisView = try {
                            chain.getThisObject() as? View
                        } catch (t: Throwable) {
                            null
                        }
                        if (thisView != null && thisView.javaClass.name == CLASS_CARD_BACKGROUND_VIEW) {
                            // 展开大卡 attach → 登记宿主 + 启动持续抓屏（不等绘制）
                            if (cardBackgroundActiveHost?.get() !== thisView) {
                                cardBackgroundActiveHost = WeakReference(thisView)
                                // [审查建议 2026-08-23] 新实例强制重置 running（同 Simple Banner 自愈）：
                                // 防旧 worker 异常退出/卡死导致 running 卡 true → 新实例 start 被挡住永不启动
                                cardBackgroundContinuousRunning = false
                                if (moduleLogEnabled) {
                                    Log.i(TAG, "seedling-cardview: attach host set (force refresh) w=${thisView.width} h=${thisView.height}")
                                }
                            }
                            if (!cardBackgroundContinuousRunning) {
                                startCardBackgroundContinuousCapture()
                            }
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "seedling-cardview: attach hook error", t)
                    }
                    chain.proceed()
                }
            cardBgAttachMounted = true
            Log.i(TAG, "Seedling card attach hook mounted: View#$METHOD_ON_ATTACHED_TO_WINDOW")
        } catch (t: Throwable) {
            Log.e(TAG, "Seedling card attach hook mount FAILED: View#$METHOD_ON_ATTACHED_TO_WINDOW", t)
        }
    }

    /** [spec/61 2026-08-23] 系统控制中心模糊力度（%）：100 = 系统原值；改动 ≤1s 生效（IPC 节流缓存）。 */
    private fun ccBlurStrength(): Int {
        val now = SystemClock.uptimeMillis()
        if (now - cachedCcBlurStrengthTimeMs < CAPTURE_PREFS_CACHE_MS) return cachedCcBlurStrength
        val a = api ?: return Prefs.DEFAULT_CC_BLUR_STRENGTH
        val v = try {
            Prefs.readIntCompat(Prefs.read(a), Prefs.KEY_CC_BLUR_STRENGTH, Prefs.DEFAULT_CC_BLUR_STRENGTH)
                .coerceIn(0, 200)
        } catch (t: Throwable) {
            Log.w(TAG, "prefs read cc_blur_strength failed, default 100", t)
            Prefs.DEFAULT_CC_BLUR_STRENGTH
        }
        cachedCcBlurStrength = v
        cachedCcBlurStrengthTimeMs = now
        return v
    }

    /** [spec/61 2026-08-23] 保留系统控制中心模糊开关（默认 true = 恢复系统模糊）。改动 ≤1s 生效。 */
    private fun keepSystemCcBlurEnabled(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - cachedKeepCcBlurTimeMs < CAPTURE_PREFS_CACHE_MS) return cachedKeepCcBlur
        val a = api ?: return Prefs.DEFAULT_KEEP_SYSTEM_CC_BLUR
        val v = try {
            Prefs.read(a).getBoolean(Prefs.KEY_KEEP_SYSTEM_CC_BLUR, Prefs.DEFAULT_KEEP_SYSTEM_CC_BLUR)
        } catch (t: Throwable) {
            Log.w(TAG, "prefs read keep_system_cc_blur failed, default true", t)
            Prefs.DEFAULT_KEEP_SYSTEM_CC_BLUR
        }
        cachedKeepCcBlur = v
        cachedKeepCcBlurTimeMs = now
        return v
    }

    /** [2026-09-16 面板材质底色] 移除面板 MixColor 材质底色开关（默认 true）。改动 ≤1s 生效。 */
    private fun removePanelMixColorEnabled(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - cachedRemoveMixColorTimeMs < CAPTURE_PREFS_CACHE_MS) return cachedRemoveMixColor
        val a = api ?: return Prefs.DEFAULT_REMOVE_PANEL_MIX_COLOR
        val v = try {
            Prefs.read(a).getBoolean(Prefs.KEY_REMOVE_PANEL_MIX_COLOR, Prefs.DEFAULT_REMOVE_PANEL_MIX_COLOR)
        } catch (t: Throwable) {
            Log.w(TAG, "prefs read remove_panel_mix_color failed, default true", t)
            Prefs.DEFAULT_REMOVE_PANEL_MIX_COLOR
        }
        cachedRemoveMixColor = v
        cachedRemoveMixColorTimeMs = now
        return v
    }

    /**
     * [spec/61 2026-08-23] 系统控制中心模糊力度调整 + 阻止界面缩小。
     *
     * 反编译实锤（2026-09-16 用 decompiled_new 重新核对，行号以新版为准）：
     * - 面板背景 BlurConfig 构造：`ScrimControllerExImp.refreshBehindDrawable`（:922-946）
     *   `new BlurConfig(NotifiAndQsPlatformBlurExKt.panelBlurRadius(context), 0, ...)`
     *   → 包成 `AutoBlurDrawable` + `ViewBlurProxy` 设给 scrimBehind。panelBlurRadius =
     *   `R.integer.blur_radius_platform`（NotifiAndQsPlatformBlurExKt:20-22，实测值 800）。
     * - 模糊力度应用：`ViewBlurProxy.applyConfigToPlatformBlur()`（:314-333）→
     *   `PlatformBlurDrawable.applyBlurConfig(this.blurConfig, this.blurAmount)`
     *   → `setBlurRadius((int)(blurConfig.getBlurRadius() * blurAmount))`。
     * - 界面缩小来源：`NotifiAndQsPlatformBlurExKt.applyPanelMirrorScale(boolean z, float f, ViewBlurProxy)`
     *   （:15-18）→ `setMirrorScale(z ? MathUtils.lerp(1.0f, 0.9f, f) : 1.0f)`——面板展开时 mirrorScale
     *   从 1.0 lerp 到 0.9（缩小 10%）；**全库唯一调用点 = ScrimViewExImp.setScaleAmount:195**，面板背景专用。
     *
     * hook 方案：
     * ① 阻止缩小：applyPanelMirrorScale hookBefore 把 arg0(z) 强制 false → setMirrorScale 恒 1.0。
     * ② 调力度：applyConfigToPlatformBlur hookBefore 判定面板背景（this.view 是 ScrimView）→
     *    反射改 BlurConfig.blurRadius（public 字段）为 `系统原值 × 力度%/100`（原值见 [ccBlurBaseRadius]）。
     */
    private fun mountCcBlurHooks(api: XposedInterface, classLoader: ClassLoader) {
        // 解析 BlurConfig.blurRadius public 字段（力度修改对象字段）
        try {
            val cfgCls = Class.forName(CLASS_BLUR_CONFIG, false, classLoader)
            mBlurConfigBlurRadiusField = cfgCls.getField("blurRadius")
        } catch (t: Throwable) {
            Log.w(TAG, "cc-blur: resolve BlurConfig.blurRadius field failed", t)
        }
        // 解析 ViewBlurProxy.view / blurConfig 字段（面板背景判定 + 力度修改）
        try {
            val vbpCls = Class.forName(CLASS_VIEW_BLUR_PROXY, false, classLoader)
            mViewBlurProxyViewField = vbpCls.getDeclaredField("view").apply { isAccessible = true }
            mViewBlurProxyBlurConfigField = vbpCls.getDeclaredField("blurConfig").apply { isAccessible = true }
        } catch (t: Throwable) {
            Log.w(TAG, "cc-blur: resolve ViewBlurProxy view/blurConfig fields failed", t)
        }
        mountApplyPanelMirrorScale(api, classLoader)
        // [2026-09-16 修正] 力度 hook 挂 ViewBlurProxy.applyConfigToPlatformBlur()——PlatformStatic 分支里
        // 真正把 blurRadius 交给 blurParam 的执行体（旧版挂 PlatformBlurDrawable.applyBlurConfig 实例方法，
        // 2026-08-23 改挂无参 applyBlurConfig()，覆盖面仍窄于本方法）。
        mountViewBlurProxyApplyBlurConfig(api, classLoader)
        // [2026-09-16 面板材质底色] 解析 MixColor 反射链（移除那层"灰"，在 applyConfigToPlatformBlur 内应用）
        resolveMixColorReflect(classLoader)
    }

    /**
     * [2026-09-16 面板材质底色] 移除面板背景的 MixColor 材质底色。
     *
     * **现象**：开启「保留系统模糊」后面板背景始终有一层灰，把「系统模糊力度」压到 8% 也不消退。
     *
     * **根因**（反编译逐层实证，详见 doc/spec/69）：
     * `PlatformBlurDrawable.applyBlurConfig`（PlatformBlurDrawable.java:66-113）里，
     * **模糊半径**与**材质底色**是两套独立参数：
     * ```
     * ① 底色：applyMixColorAndScale(blurParam, mixColor, mirrorScale, f)   // :51-53
     *          → setMaterialParams(mode, topLayerColor×f, bottomLayerColor×f, ...)   // f = blurAmount
     * ② 模糊：blurParam.setBlurRadius((int)(blurConfig.getBlurRadius() * f))          // :82
     * ```
     * 底色 alpha 只乘 `blurAmount`（面板展开进度），**不经过 blurRadius** → 力度滑杆管不到它。
     *
     * **修法**（2026-09-16 真机修正）：hook 点不用 `panelPlatformMixConfig`——**真机上该方法不存在**
     * （`NoSuchMethodException`，反编译产物与真机 SystemUI 版本不一致），改用**已确认存在**的
     * `ViewBlurProxy.applyConfigToPlatformBlur()`（模块已在用，见 [mountViewBlurProxyApplyBlurConfig]）：
     * 在那里判定面板背景（`isPanelBackgroundViewBlurProxy`）后，把 `BlurConfig.platformMixConfig`
     * **整体替换为 `BlurMixConfig.None.INSTANCE`** → 系统走自身 None 分支（只设模糊、不调
     * `setMaterialParams`）→ 模糊保留、底色消失。
     *
     * **为何不用「只改 MixColor 颜色 alpha=0」**：真机实测实际类型是
     * `BlurMixConfig$BlurMixSingleWithShader`（**反编译产物里没有这个类**，版本不一致），
     * 持色的类不是 `BlurMixSingle`，按字段反射取色必然失败。整体替换 None 不依赖任何私有字段。
     * `None.INSTANCE` 只读不写（不修改其字段，避免污染系统其它使用点）。
     *
     * 只影响面板背景（behind_scrim）；锁屏 bouncer 与元素混色不变。
     */
    private fun resolveMixColorReflect(classLoader: ClassLoader) {
        try {
            val mixCfgCls = Class.forName(CLASS_BLUR_MIX_CONFIG, false, classLoader)
            val cfgCls = Class.forName(CLASS_BLUR_CONFIG, false, classLoader)
            mBlurConfigGetPlatformMixConfig = cfgCls.getMethod("getPlatformMixConfig")
            mBlurConfigSetPlatformMixConfig = cfgCls.getMethod("setPlatformMixConfig", mixCfgCls)
            mBlurMixNoneSingleton = try {
                Class.forName(CLASS_BLUR_MIX_CONFIG_NONE, false, classLoader)
                    .getDeclaredField("INSTANCE").get(null)
            } catch (t: Throwable) {
                Log.w(TAG, "cc-mixcolor: None.INSTANCE unresolved, feature disabled", t)
                null
            }
            Log.i(TAG, "cc-mixcolor: reflect chain resolved (panelMixConfig -> None)")
        } catch (t: Throwable) {
            Log.w(TAG, "cc-mixcolor: resolve reflect chain failed, feature disabled", t)
        }
    }

    /** [2026-09-16 面板材质底色] 把面板背景 `BlurConfig.platformMixConfig` 整体替换为 `None`。
     *  在 [mountViewBlurProxyApplyBlurConfig] 的面板分支内调用——时机在 `PlatformBlurDrawable.applyBlurConfig`
     *  **之前**，故其读到的是替换后的 None → 走系统 None 分支（只设模糊、不调 setMaterialParams）。
     *  幂等（已是 None 直接返回），可逐帧调用。
     *
     *  **为什么不做「只把 MixColor 颜色 alpha 归零」**：真机上实际类型是
     *  `BlurMixConfig$BlurMixSingleWithShader`（**反编译产物里没有这个类**，与真机版本不一致），
     *  持有 `mixColor` 的类不是 `BlurMixSingle`，按字段反射取色必然失败（2026-09-16 真机实测）。
     *  整体替换 None 不依赖任何私有字段，是最稳的路径——真机验证效果正常。 */
    private fun applyRemovePanelMixColor(vbp: Any) {
        val cfg = try {
            mViewBlurProxyBlurConfigField?.get(vbp)
        } catch (t: Throwable) {
            null
        } ?: return
        val cur = try {
            mBlurConfigGetPlatformMixConfig?.invoke(cfg)
        } catch (t: Throwable) {
            null
        } ?: return
        if (cur.javaClass.name == CLASS_BLUR_MIX_CONFIG_NONE) return
        val none = mBlurMixNoneSingleton ?: return
        try {
            mBlurConfigSetPlatformMixConfig?.invoke(cfg, none)
            logThrottled("cc-mixcolor", Log.INFO, 1000L) {
                "cc-mixcolor: panel platformMixConfig -> None (was=${cur.javaClass.simpleName})"
            }
        } catch (t: Throwable) {
            Log.e(TAG, "cc-mixcolor: replace with None failed", t)
        }
    }

    /** [spec/61] 阻止面板背景缩小：hook `NotifiAndQsPlatformBlurExKt.applyPanelMirrorScale(boolean, float, ViewBlurProxy)`，
     *  hookBefore 把 arg0(z) 强制 false → 方法内 `setMirrorScale(z ? lerp(1,0.9,f) : 1.0)` = 1.0f（不缩小）。
     *  仅面板背景（ScrimViewExImp 调用方），不影响锁屏/通知卡。 */
    private fun mountApplyPanelMirrorScale(api: XposedInterface, classLoader: ClassLoader) {
        try {
            val clazz = Class.forName(CLASS_NOTIFI_QS_PLATFORM_BLUR_KT, false, classLoader)
            val vbpCls = Class.forName(CLASS_VIEW_BLUR_PROXY, false, classLoader)
            val method = clazz.getMethod(
                METHOD_APPLY_PANEL_MIRROR_SCALE,
                Boolean::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                vbpCls,
            )
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        if (keepSystemCcBlurEnabled() || ccBlurStrength() != Prefs.DEFAULT_CC_BLUR_STRENGTH) {
                            val args = chain.getArgs().toTypedArray()
                            args[0] = false
                            // 逐帧调用，必须节流（一次下拉 40+ 条会淹没 logcat）
                            logThrottled("cc-blur-mirror", Log.INFO, 1000L) {
                                "cc-blur: applyPanelMirrorScale block shrink (z->false)"
                            }
                            return@intercept chain.proceed(args)
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "cc-blur: applyPanelMirrorScale intercept error", t)
                    }
                    chain.proceed()
                }
            Log.i(TAG, "cc-blur: applyPanelMirrorScale mounted (block panel mirror shrink)")
        } catch (t: Throwable) {
            Log.w(TAG, "cc-blur: applyPanelMirrorScale mount FAILED", t)
        }
    }

    /** [spec/61 + 2026-09-16 修复] 系统控制中心模糊力度：hook `ViewBlurProxy.applyConfigToPlatformBlur()`
     *  （PlatformStatic 分支唯一执行体，ViewBlurProxy.java:314），hookBefore 判定面板背景（view 字段是
     *  ScrimView）→ 反射把 `blurConfig.blurRadius` 写成 **系统原值 × 力度%/100**（原值取本实例首次见到的
     *  值，见 [ccBlurBaseRadius]）→ proceed 后 `setBlurRadius(blurConfig.getBlurRadius() * blurAmount)` 用新半径。
     *  改写幂等：同一 strength 反复调用结果恒定。 */
    private fun mountViewBlurProxyApplyBlurConfig(api: XposedInterface, classLoader: ClassLoader) {
        if (mBlurConfigBlurRadiusField == null) {
            Log.w(TAG, "cc-blur: BlurConfig.blurRadius field unresolved, strength adjust disabled")
            return
        }
        try {
            val vbpCls = Class.forName(CLASS_VIEW_BLUR_PROXY, false, classLoader)
            val method = vbpCls.getMethod(METHOD_APPLY_CONFIG_TO_PLATFORM_BLUR)
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        val vbp = chain.getThisObject()
                        val isPanel = vbp != null && isPanelBackgroundViewBlurProxy(vbp)
                        val keep = keepSystemCcBlurEnabled()
                        // [诊断] 入口无条件日志：hook 是否被调用、面板判定结果、开关状态一次说清。
                        // 排查期日志缓冲常被进程重启清掉，故 runtime 证据必须靠这条（而非安装期 mounted 日志）。
                        logThrottled("cc-blur-enter", Log.INFO, 1000L) {
                            "cc-blur: ENTER this=${vbp?.javaClass?.name} view=${viewBlurProxyViewName(vbp)} isPanel=$isPanel keep=$keep strength=${ccBlurStrength()}%"
                        }
                        if (vbp != null && isPanel) {
                            if (keep) applyCcBlurStrength(vbp)
                            // [2026-09-16 面板材质底色] 去掉 MixColor 灰底（那层灰不随模糊力度缩放）。
                            // 与 keep 无关地保持状态一致；keep=false 时整链短路，改了也看不到。
                            if (removePanelMixColorEnabled()) applyRemovePanelMixColor(vbp)
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "cc-blur: applyConfigToPlatformBlur intercept error", t)
                    }
                    chain.proceed()
                }
            Log.i(TAG, "cc-blur: ViewBlurProxy.applyConfigToPlatformBlur mounted (strength adjust)")
        } catch (t: Throwable) {
            Log.w(TAG, "cc-blur: ViewBlurProxy.applyConfigToPlatformBlur mount FAILED", t)
        }
    }

    /** [2026-09-16] 把面板背景 `BlurConfig.blurRadius` 写成「系统原值 × 力度%/100」。
     *  原值按 BlurConfig 实例缓存（[ccBlurBaseRadius]）——**不能用当前值做基准**，否则每次调用都在
     *  上一次改写结果上再乘一次比例，指数衰减到 0（真机「要么透明要么 100%」的根因）。 */
    private fun applyCcBlurStrength(vbp: Any) {
        val blurConfig = mViewBlurProxyBlurConfigField?.get(vbp)
        if (blurConfig == null) {
            logThrottled("cc-blur-nocfg", Log.WARN, 1000L) {
                "cc-blur: blurConfig field null, strength skipped (fieldResolved=${mViewBlurProxyBlurConfigField != null})"
            }
            return
        }
        val key = System.identityHashCode(blurConfig)
        val cur = reflectBlurConfigRadius(blurConfig)
        val base: Int = ccBlurBaseRadius[key] ?: run {
            // 未初始化（≤0）→ 本轮不记基准，等系统写入原值后再记，避免把 0 锁死成基准
            if (cur <= 0) {
                logThrottled("cc-blur-nobase", Log.WARN, 1000L) {
                    "cc-blur: base unfixed, blurRadius=$cur (fieldResolved=${mBlurConfigBlurRadiusField != null}), skip"
                }
                return
            }
            if (ccBlurBaseRadius.size > 32) ccBlurBaseRadius.clear()
            ccBlurBaseRadius[key] = cur
            cur
        }
        val strength = ccBlurStrength()
        val want = (base * strength / 100.0).toInt().coerceIn(0, base * 3)
        if (cur != want) setBlurConfigRadius(blurConfig, want)
        // 诊断：下拉一次即应出现本行；不出现 = 上一级 ENTER 日志会说明原因
        logThrottled("cc-blur-strength", Log.INFO, 1000L) {
            "cc-blur: base=$base cur=$cur want=$want strength=$strength% ${if (cur != want) "WRITTEN" else "already-ok"}"
        }
    }

    /** [诊断] 读 ViewBlurProxy.view 的类名（面板背景判定依据）。反射失败返回 "?"。 */
    private fun viewBlurProxyViewName(vbp: Any?): String {
        if (vbp == null) return "null"
        return try {
            (mViewBlurProxyViewField?.get(vbp) as? View)?.javaClass?.name ?: "?"
        } catch (t: Throwable) {
            "?"
        }
    }

    /** [spec/61] 判定 ViewBlurProxy 是否为面板背景：view 字段（private final View）是 ScrimView。
     *  反射失败 → false（不调力度，保守回退）。 */
    private fun isPanelBackgroundViewBlurProxy(vbp: Any): Boolean {
        val f = mViewBlurProxyViewField ?: return false
        return try {
            val view = f.get(vbp) as? View
            view?.javaClass?.name?.contains("ScrimView") == true
        } catch (t: Throwable) {
            false
        }
    }

    /** [spec/61] 反射读 BlurConfig.blurRadius（public int）。失败返回 -1。 */
    private fun reflectBlurConfigRadius(config: Any): Int {
        val f = mBlurConfigBlurRadiusField ?: return -1
        return try {
            f.getInt(config)
        } catch (t: Throwable) {
            -1
        }
    }

    /** [spec/61] 反射写 BlurConfig.blurRadius（public int）。失败静默。 */
    private fun setBlurConfigRadius(config: Any, radius: Int) {
        val f = mBlurConfigBlurRadiusField ?: return
        try {
            f.setInt(config, radius)
        } catch (t: Throwable) {
            // 忽略：失败保留系统原值
        }
    }

    /** [2026-08-15] Simple Banner「容器即元素」玻璃绘制路径：FullScreenBanner 自身 bounds 登记 + 画玻璃。
     *  dispatchDraw canvas 原点 = view 左上角 → drawSeedlingGlassOnCanvas offsetX/Y=0（不平移）。
     *  首现：主线程同步抓屏（[triggerBackgroundCaptureOnMainThread]）——横幅 5s 短暂窗口不异步等待；
     *  registerSeedlingCardRegion 内部首现异步 triggerBackgroundCapture 被有效快照吸收（不再入队）。 */
    private fun renderSimpleBannerGlass(canvas: Canvas, banner: View) {
        val w = banner.width
        val h = banner.height
        if (w <= 0 || h <= 0 || banner.visibility != View.VISIBLE) return
        val loc = IntArray(2)
        try {
            banner.getLocationOnScreen(loc)
        } catch (t: Throwable) {
            return
        }
        val screenRegion = RectF(
            loc[0].toFloat(), loc[1].toFloat(),
            (loc[0] + w).toFloat(), (loc[1] + h).toFloat(),
        )
        val id = System.identityHashCode(banner)
        // 首现（banner 实例首次出现）→ 主线程同步抓屏：横幅短暂窗口，弹出一瞬即抓，不等异步 worker。
        if (screenRegionMap[id] == null) {
            try {
                triggerBackgroundCaptureOnMainThread()
            } catch (t: Throwable) {
                Log.e(TAG, "simple-banner: first sync capture failed", t)
            }
            if (moduleLogEnabled) {
                Log.i(TAG, "simple-banner: first appearance sync capture")
            }
        }
        registerSeedlingCardRegion(id, screenRegion, Rect(0, 0, w, h), banner, "simple-banner")
        drawSeedlingGlassOnCanvas(canvas, banner, id, w, h, screenRegion, offsetX = 0f, offsetY = 0f)
    }

    /** [2026-08-15] Simple Banner 活跃判定：活跃宿主存在且仍 attach。横幅消失（窗口销毁/detach）→ false。
     *  供持续抓屏 worker 判断停止（不后台耗电）。
     *  [2026-08-15 修复持续抓屏不生效] 原判定 `attach && isSimpleBannerRootView(rootView)` 再走 rootView
     *  类名/窗口 type 反射/窗口标题——`dispatchDraw` 命中已用字符串类名精确匹配 CLASS_FULL_SCREEN_BANNER
     *  （banner 本体），rootView 反射判定是**冗余防御**，且 `getWindowAttributes().type`（[reflectWindowType]）
     *  在部分系统窗口上反射失败返回 -1 → 活跃恒 false → 持续抓屏 worker 永不启动（真机日志仅 active host
     *  set + first sync capture，无 worker start）。放宽只判 attach：banner 窗口销毁即 detach → false →
     *  worker 退出，停止语义不变。 */
    private fun isSimpleBannerHostActive(): Boolean {
        val host = simpleBannerActiveHost?.get() ?: return false
        return try {
            host.isAttachedToWindow
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * [2026-08-15 轻打扰折叠横幅] 横幅显示期间主动持续抓屏 worker：命中期间以 [continuousCaptureIntervalMs]
     *  （[panelCaptureHz] 速率：运动 120Hz / 静止 60Hz）循环 [scheduleElementCaptures](force=true) →
     *  入队主 worker 队列（captureDisplay 主 worker 串行，本线程零阻塞；pending 去重单通道）。
     *  停止：横幅消失（detach/回 shade）→ [isSimpleBannerHostActive] false → 退出（零后台耗电）；
     *  锁屏期间不启动/退出（被动事件触发不受影响）。与 heads-up 持续源独立。
     */
    private fun startSimpleBannerContinuousCapture(force: Boolean = false) {
        if (simpleBannerContinuousRunning) {
            Log.i(TAG, "simple-banner: start return, already running")
            return
        }
        val host = simpleBannerActiveHost?.get()
        if (host == null) {
            Log.i(TAG, "simple-banner: skip start, active host null")
            return
        }
        // [2026-08-15 强制刷新] force=true（attach hook 触发：横幅必然出现）跳过活跃/keyguard 判定立即启动；
        // 非 force（dispatchDraw 命中兜底）仍做判定。
        if (!force) {
            if (!isSimpleBannerHostActive()) {
                val attachOk = try { host.isAttachedToWindow } catch (t: Throwable) { false }
                Log.i(TAG, "simple-banner: skip start, inactive attach=$attachOk")
                return
            }
            if (isKeyguardLockedNow()) {
                Log.i(TAG, "simple-banner: skip start, keyguard locked (passive only)")
                return
            }
        }
        simpleBannerContinuousRunning = true
        // [2026-08-15 诊断] panelCaptureHz 单独求值（避免 Log 参数求值异常吞掉 start 日志）；含 IPC 读 Prefs
        val hz = try {
            panelCaptureHz()
        } catch (t: Throwable) {
            Log.w(TAG, "simple-banner: panelCaptureHz failed, default 120", t)
            Prefs.DEFAULT_PANEL_CAPTURE_HZ
        }
        if (moduleLogEnabled) {
            Log.i(TAG, "simple-banner: continuous capture worker start (rate ${hz}Hz, force=$force)")
        }
        try {
            Thread {
                try {
                    if (moduleLogEnabled) {
                        Log.i(TAG, "simple-banner: continuous worker thread alive")
                    }
                    while (true) {
                        try {
                            // [2026-08-15 自愈] host 被新横幅实例接管 → 本 worker 退出（新 worker 接管刷新，
                            // 防双 worker 并发；running 由 finally 复位）
                            if (simpleBannerActiveHost?.get() !== host) {
                                if (moduleLogEnabled) {
                                    Log.i(TAG, "simple-banner: host changed, old worker exiting")
                                }
                                break
                            }
                            // 横幅消失（detach/回 shade）→ 停止（零后台耗电）
                            if (!isSimpleBannerHostActive()) {
                                simpleBannerActiveHost = null
                                Log.i(TAG, "bg-element: simple-banner gone, continuous capture stopped")
                                break
                            }
                            if (isKeyguardLockedNow()) {
                                simpleBannerActiveHost = null
                                Log.i(TAG, "bg-element: keyguard locked, simple-banner continuous capture stopped (passive only)")
                                break
                            }
                            markContentChanged()
                            scheduleElementCaptures(force = true)
                            logThrottled("simple-banner-continuous", Log.INFO) {
                                "simple-banner: continuous tick queued (single-channel)"
                            }
                            try {
                                Thread.sleep(continuousCaptureIntervalMs())
                            } catch (ignored: InterruptedException) {
                            }
                        } catch (t: Throwable) {
                            // [2026-08-15 诊断] 单轮异常不杀线程：打日志 + 短睡后继续（首轮 attach 时刻
                            // schedule 链路若异常，下轮重试；不再无日志静默退出）
                            Log.e(TAG, "simple-banner: continuous worker loop error", t)
                            try {
                                Thread.sleep(50)
                            } catch (ignored: InterruptedException) {
                            }
                        }
                    }
                } finally {
                    if (moduleLogEnabled) {
                        Log.i(TAG, "simple-banner: continuous worker exited, running reset")
                    }
                    simpleBannerContinuousRunning = false
                }
            }.start()
        } catch (t: Throwable) {
            simpleBannerContinuousRunning = false
            Log.e(TAG, "simple-banner: spawn continuous worker failed", t)
        }
    }

    // ------------------------------------------------------------ [spec/44b 停用区] 流体云展开卡片玻璃化（OplusCustomRow 宿主，真机实证上错位置停用）

    /*
     * [spec/44b 已停用 2026-08-15 真机实证上错位置] 展开流体云大卡真实 View = SystemUIPlugin.apk 的
     * CardContainer → CardView → CardBackgroundView（反编译实锤，tools/dump/SystemUIPlugin/sources/...）；
     * OplusCustomRow 是锁屏流体云宿主（锁屏走 com.oplus.seedling.pluginapp 不同包），本套 hook 命中
     * 锁屏（"上错位置上到锁屏""遮住文字"）。以下 6 个函数保留注释停用（不删除，同 mountSeedlingCardContainer
     * 风格），新方向见下方 [spec/44c] mountCardBackgroundGlass。
     */
    /*
    /**
     * [spec/44b 2026-08-15] hook `OplusCustomRow`（流体云展开大卡容器，extends ExpandableOutlineView →
     * ViewGroup，inflate notification_custom_container）玻璃化。
     *
     * 真机实证（spec/44 暂停 CapsulePluginContainer 方向时记录）：流体云展开大卡片 = NotificationShade
     * 通知面板里的 OplusCustomRow（宿主卡片背景 = mBackgroundNormal NotificationBackgroundView，
     * updateResource:738-758 对 custombackgroundNormal 调 setCustomBackground(custom_material_bg) + setTint）。
     * 插件内容经 `seedlingPlugin.onCreateView(2, getCustomRow())` → `setNewSeedlingView(View)` 挂入
     * （OplusNotificationSectionsManagerExImpl.onPluginAdded:271-281 实证）。
     *
     * 玻璃化策略：hook 基类 ViewGroup.dispatchDraw(Canvas)，拦截体判定 this 是 OplusCustomRow 实例
     * （防全 ViewGroup 性能/安全影响）→ **先 chain.proceed() 画插件子 View，再画玻璃**（玻璃在子 View
     * 之上，spec/44 根因 1 修好的约定）→ [renderSeedlingCardGlassOnRow] 按容器自身 bounds 登记 + 画玻璃。
     * OplusCustomRow 本身就是展开大卡容器 → **不需要**高度阈值判定（缩小胶囊不经此容器）。
     *
     * 配套：
     * - 背景透明 [applyOplusCustomRowTransparentBg]（用户硬需求默认开：Prefs.KEY_SEEDLING_CARD_FORCE_TRANSPARENT_BG
     *   默认 true，Prefs 注释已说明；反射替换 NotificationBackgroundView.mBackground 字段为透明 drawable）。
     * - 插件内容更新驱动 [hookOplusCustomRowSetNewSeedlingView]（hookAfter → invalidate + triggerBackgroundCapture）。
     * 总开关 [seedlingCardGlassEnabled]（KEY_SEEDLING_CARD_GLASS_ENABLE，默认 false，用户手动开）。
     *
     * 挂载失败独立 try-catch（不影响其他 hook）。dispatchDraw / updateResource / setNewSeedlingView
     * 各自独立 try-catch：任一失败只影响对应能力，玻璃仍能画。
     */
    private fun mountOplusCustomRowGlass(api: XposedInterface, classLoader: ClassLoader) {
        if (!seedlingCardGlassEnabled) {
            Log.i(TAG, "OplusCustomRow glass disabled by prefs, skip mount")
            return
        }
        if (oplusRowGlassMounted) return
        val rowCls = try {
            Class.forName(CLASS_OPLUS_CUSTOM_ROW, false, classLoader)
        } catch (t: Throwable) {
            Log.w(TAG, "OplusCustomRow glass: class not loaded, skip mount", t)
            return
        }
        hookOplusCustomRowDispatchDraw(api, classLoader, rowCls)
        hookOplusCustomRowUpdateResource(api, rowCls)
        hookOplusCustomRowSetNewSeedlingView(api, rowCls)
        oplusRowGlassMounted = true
        Log.i(TAG, "OplusCustomRow glass mounted: dispatchDraw + updateResource + setNewSeedlingView")
    }
    */

    /* [spec/44b 停用] hookOplusCustomRowDispatchDraw：OplusCustomRow dispatchDraw 玻璃绘制。 */
    /*
    /** hook ViewGroup.dispatchDraw(Canvas)：this 是 OplusCustomRow 时先 proceed 再画玻璃（玻璃在子 View 之上）。 */
    private fun hookOplusCustomRowDispatchDraw(api: XposedInterface, classLoader: ClassLoader, rowCls: Class<*>) {
        try {
            val vgCls = Class.forName(CLASS_VIEW_GROUP, false, classLoader)
            val method = vgCls.getDeclaredMethod(METHOD_DISPATCH_DRAW, Canvas::class.java)
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        val thisView = try {
                            chain.getThisObject() as? View
                        } catch (t: Throwable) {
                            null
                        }
                        if (thisView != null && rowCls.isInstance(thisView)) {
                            // [诊断] dispatch 命中计数（节流：每 120 帧打一条，防刷屏）
                            if (moduleLogEnabled) {
                                val count = ++oplusRowDispatchCount
                                if (count % 120 == 1) {
                                    Log.i(TAG, "seedling-row: dispatch count=$count w=${thisView.width} h=${thisView.height} cls=${thisView.javaClass.simpleName}")
                                }
                            }
                            val canvas = try {
                                chain.getArg(0) as? Canvas
                            } catch (t: Throwable) {
                                null
                            }
                            // [spec/44 根因 1 约定] 先 proceed 画插件子 View，再画玻璃覆盖其上
                            val result = chain.proceed()
                            if (canvas != null) {
                                try {
                                    renderSeedlingCardGlassOnRow(canvas, thisView)
                                } catch (t: Throwable) {
                                    Log.e(TAG, "seedling-row: dispatchDraw glass failed", t)
                                }
                            }
                            return@intercept result
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "seedling-row: dispatchDraw intercept error", t)
                    }
                    chain.proceed()
                }
            Log.i(TAG, "Hook B mounted: $CLASS_VIEW_GROUP#$METHOD_DISPATCH_DRAW(Canvas) (OplusCustomRow glass)")
        } catch (t: Throwable) {
            Log.e(TAG, "Hook B mount FAILED: $CLASS_VIEW_GROUP#$METHOD_DISPATCH_DRAW (OplusCustomRow glass)", t)
        }
    }
    */

    /* [spec/44b 停用] hookOplusCustomRowUpdateResource：OplusCustomRow 背景透明兜底。 */
    /*
    /** hook OplusCustomRow.updateResource()：系统重设卡片背景后 hookAfter 重贴透明 drawable（背景透明兜底）。 */
    private fun hookOplusCustomRowUpdateResource(api: XposedInterface, rowCls: Class<*>) {
        try {
            val method = rowCls.getMethod(METHOD_UPDATE_RESOURCE)
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        val row = chain.getThisObject() as? View
                        val result = chain.proceed()
                        if (row != null) applyOplusCustomRowTransparentBg(row)
                        return@intercept result
                    } catch (t: Throwable) {
                        Log.e(TAG, "seedling-row: updateResource intercept error", t)
                    }
                    chain.proceed()
                }
            Log.i(TAG, "Hook B mounted: $CLASS_OPLUS_CUSTOM_ROW#$METHOD_UPDATE_RESOURCE() (bg transparent re-apply)")
        } catch (t: Throwable) {
            Log.e(TAG, "Hook B mount FAILED: $CLASS_OPLUS_CUSTOM_ROW#$METHOD_UPDATE_RESOURCE (bg transparent re-apply)", t)
        }
    }
    */

    /* [spec/44b 停用] hookOplusCustomRowSetNewSeedlingView：插件内容更新驱动。 */
    /*
    /** hook OplusCustomRow.setNewSeedlingView(View)：插件内容挂载/更新后 invalidate 宿主 + 抓屏（玻璃背景及时刷新）。 */
    private fun hookOplusCustomRowSetNewSeedlingView(api: XposedInterface, rowCls: Class<*>) {
        try {
            val method = rowCls.getMethod(METHOD_SET_NEW_SEEDLING_VIEW, View::class.java)
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        val row = chain.getThisObject() as? View
                        val result = chain.proceed()
                        if (row != null) {
                            applyOplusCustomRowTransparentBg(row)
                            row.invalidate()
                            triggerBackgroundCapture()
                            if (moduleLogEnabled) {
                                Log.i(TAG, "seedling-row: setNewSeedlingView -> invalidate + triggerBackgroundCapture (row w=${row.width} h=${row.height})")
                            }
                        }
                        return@intercept result
                    } catch (t: Throwable) {
                        Log.e(TAG, "seedling-row: setNewSeedlingView intercept error", t)
                    }
                    chain.proceed()
                }
            Log.i(TAG, "Hook B mounted: $CLASS_OPLUS_CUSTOM_ROW#$METHOD_SET_NEW_SEEDLING_VIEW(View)")
        } catch (t: Throwable) {
            Log.e(TAG, "Hook B mount FAILED: $CLASS_OPLUS_CUSTOM_ROW#$METHOD_SET_NEW_SEEDLING_VIEW", t)
        }
    }
    */

    /*
     * [spec/44b 停用] renderSeedlingCardGlassOnRow：OplusCustomRow「容器即元素」玻璃绘制路径。
    /**
     * [spec/44b] OplusCustomRow「容器即元素」玻璃绘制路径：整行（容器自身 bounds）登记 + 画玻璃。
     * 不需要高度阈值判定（缩小胶囊不经此容器，此容器即展开大卡）。源标记 "seedling-oplusrow"。
     * 绘制顺序：dispatchDraw 拦截体已先 proceed 画完子 View，本函数只负责玻璃覆盖其上。
     */
    private fun renderSeedlingCardGlassOnRow(canvas: Canvas, row: View) {
        val w = row.width
        val h = row.height
        if (w <= 0 || h <= 0 || row.visibility != View.VISIBLE) return
        val loc = IntArray(2)
        try {
            row.getLocationOnScreen(loc)
        } catch (t: Throwable) {
            return
        }
        val screenRegion = RectF(
            loc[0].toFloat(), loc[1].toFloat(),
            (loc[0] + w).toFloat(), (loc[1] + h).toFloat(),
        )
        val id = System.identityHashCode(row)
        // 新元素首现/区域变化时 registerSeedlingCardRegion 内部打日志 + triggerBackgroundCapture
        registerSeedlingCardRegion(id, screenRegion, Rect(0, 0, w, h), row, "seedling-oplusrow")
        // offsetX/Y = 0：dispatchDraw 的 canvas 原点本就是 row 左上角，不能按 host.left/top 再平移
        // （会整体下移/右移、超裁剪区被裁掉 → 玻璃不可见），见 drawSeedlingGlassOnCanvas 注释。
        drawSeedlingGlassOnCanvas(canvas, row, id, w, h, screenRegion, offsetX = 0f, offsetY = 0f)
    }
    */

    /*
     * [spec/44b 停用] applyOplusCustomRowTransparentBg：宿主卡片背景透明。
    /**
     * [spec/44b] 宿主卡片背景透明：反射替换 NotificationBackgroundView.mBackground（public Drawable，
     * onDraw 直绘该字段；**View.background 置空无效**）为透明 ColorDrawable(0)。用透明 drawable 而非
     * null：setDrawableAlpha/setExpandAnimationRunning 等系统方法对 null 会 NPE，透明 drawable 全兼容。
     * 幂等：mBackground 已是透明 ColorDrawable 则跳过（每帧 dispatch 不重复置）。受
     * [seedlingForceTransparentBg] 门控（Prefs.KEY_SEEDLING_CARD_FORCE_TRANSPARENT_BG，默认 true）。
     */
    private fun applyOplusCustomRowTransparentBg(row: View) {
        if (!seedlingForceTransparentBg) return
        if (mCustomRowBgNormal == null || mNbvBackgroundField == null) {
            if (moduleLogEnabled) Log.w(TAG, "seedling-row: bg transparent fields unresolved, skip")
            return
        }
        val bgView = try {
            mCustomRowBgNormal?.get(row) as? View
        } catch (t: Throwable) {
            if (moduleLogEnabled) Log.e(TAG, "seedling-row: read mBackgroundNormal failed", t)
            null
        } ?: return
        try {
            val current = mNbvBackgroundField?.get(bgView) as? Drawable
            if (current !is ColorDrawable || current.color != 0) {
                mNbvBackgroundField?.set(bgView, ColorDrawable(0))
                bgView.invalidate()
                if (moduleLogEnabled) {
                    Log.i(TAG, "seedling-row: bg transparent applied ${bgView.javaClass.simpleName} (was ${current?.javaClass?.simpleName ?: "null"})")
                }
            }
        } catch (t: Throwable) {
            if (moduleLogEnabled) Log.e(TAG, "seedling-row: set bg transparent failed", t)
        }
    }
    */

    // ------------------------------------------------------------ [spec/21] 文字反转取色（方案 B + 方案 A）

    /**
     * [spec/21 方案 B·主] hook `QsColorUtil.isTextNeedUseLightColorWhenLight(Context, int)`（static 单点，
     * 反编译 :141）：返回基于 [screenSnapshot] 算的 QS 面板区域平均亮度（>196 → 黑字 false；≤196 → 白字 true）。
     *
     * 系统原逻辑：`isIconNeedUseLightColor(ctx,false) && state != 0`（state 由 OplusQsColorManager 按
     * StaticBlurManager 独立模糊截图亮度算出）。本 hook 用我们整屏快照的亮度判定（数据源更贴近玻璃实际
     * 背景），亮度在快照更新时缓存（[updateQsBrightness]），判定 O(1)。
     *
     * 开关：install 时 [textContrastEnabled] 缓存；false 不挂本 hook（走系统原逻辑）。
     */
    private fun mountQsColorUtil(api: XposedInterface, classLoader: ClassLoader) {
        try {
            val clazz = Class.forName("com.oplus.systemui.qs.base.util.QsColorUtil", false, classLoader)
            val method = clazz.getMethod(
                "isTextNeedUseLightColorWhenLight",
                android.content.Context::class.java, Int::class.javaPrimitiveType,
            )
            // [spec/39 文字反色复用系统黑白通道] 缓存 `getQsColorState()` 反射（public static final int 无参，
            // 反编译实证 QsColorUtil.java:92-94）：applyLocalTextContrast 遮罩关闭分支的采样来源。
            // 独立 try-catch：失败 → 采样来源回退原色（不注入），不影响 isTextNeedUseLightColorWhenLight hook。
            mQsColorUtilGetState = try {
                clazz.getMethod("getQsColorState")
            } catch (t: Throwable) {
                Log.e(TAG, "text-contrast B getQsColorState resolve FAILED (mask-off text uses original color)", t)
                null
            }
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    // [spec/35 黑遮罩 + 白字方案 / spec/63 独立开关 2026-08-26] 文字强制白开启 → 恒白字
                    // （系统侧明暗文字决策也统一由 textForceWhiteEnabled 决定，不再绑 maskEnabled——
                    // spec/63 遮罩只管背景压暗，强制白单独可关；亮度采样正是 spec/21 间歇性失效根因之一）
                    if (textForceWhiteEnabled) return@intercept true
                    val brightness = qsPanelBrightness
                    if (brightness < 0) {
                        // 快照未就绪 → 系统原逻辑（不干预）
                        chain.proceed()
                    } else {
                        // >196 背景亮 → 黑字（false）；≤196 背景暗 → 白字（true）
                        brightness <= TEXT_CONTRAST_BRIGHTNESS_THRESHOLD
                    }
                }
            Log.i(TAG, "text-contrast B mounted: QsColorUtil#isTextNeedUseLightColorWhenLight(Context, int)")
            // [spec/52 文字强制刷新] hook **发布方** `QsColorUtil.setQsColorState(int)`（public static final
            // void，反编译实证 QsColorUtil.java:145-147；OplusQsColorManager 按模糊位图亮度算出后静态发布：
            // 亮度>196→0 / ≤196→1 / null→2 不发布，OplusQsColorManager.java:107-131）。系统黑白通道状态跳变
            // = 全局刷新信号（补「折叠变回/状态跳变文字不跟随」缺口：折叠时 getQsColorState()==2 守卫失败
            // 放行原色 + 折叠不触发重采样是根因，见 spec/52）→ post 主线程 [forceRefreshAllTextColors]。
            // 值未变（同值重复发布）不重复触发；发布方可能不在主线程 → 必须 post 到主线程。
            try {
                val setStateMethod = clazz.getMethod("setQsColorState", Int::class.javaPrimitiveType)
                api.hook(setStateMethod)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        try {
                            val v = (chain.getArg(0) as? Int) ?: Int.MIN_VALUE
                            if (v != lastPublishedQsColorState) {
                                lastPublishedQsColorState = v
                                requestForceRefreshTextColors()
                            }
                        } catch (t: Throwable) {
                            Log.e(TAG, "text-contrast forceRefresh on setQsColorState error", t)
                        }
                        chain.proceed()
                    }
                Log.i(TAG, "text-contrast B mounted: QsColorUtil#setQsColorState(int) (state jump -> force refresh all text colors)")
            } catch (t: Throwable) {
                Log.e(TAG, "text-contrast B mount FAILED: QsColorUtil#setQsColorState(int)", t)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "text-contrast B mount FAILED: QsColorUtil#isTextNeedUseLightColorWhenLight", t)
        }
    }

    /**
     * [spec/21 方案 A·精修] hook `TextView.setTextColor(int)` 局部反转。
     * [2026-08-13 变更·spec/39 文字反色复用系统黑白通道] 采样来源从逐文字整屏快照采样（getLocationOnScreen
     * → 折算 [screenSnapshot] bitmap → getPixel → >196 黑 / ≤196 白）改为读系统
     * `QsColorUtil.getQsColorState()`（[systemQsColorInjected]）：0→深色字 / 1→白色字 / 2→保持原样。
     * 玻璃区域内判定（[isOverGlassRegion]）仍保留为"是否参与文字变色"的守卫。
     *
     * 节流：per-TextView 缓存（[textContrastCache]），快照换帧 / 位置变化 / 原色 / 系统状态变化才重采样（非每帧）。
     * 防自循环：拦截体内改 args[0] 后 proceed 原方法（无递归）；重采样注入走 [textContrastResampling] 标志。
     * colorized/app 自定义色（非近中性）不覆盖（[isNeutralTextColor]），尊重 app。
     * [2026-08-13 修复] 快照缺失 / 未 attach 时**不再跳过**，改为登记待重采样；[mountTextViewOnDraw]
     * （onDraw 节流）与快照更新 post（[resampleAllTextContrast]）在快照就绪/位置变化/attach 后重新注入。
     */
    private fun mountTextViewSetTextColor(api: XposedInterface, classLoader: ClassLoader) {
        try {
            val clazz = Class.forName("android.widget.TextView", false, classLoader)
            val method = clazz.getMethod("setTextColor", Int::class.javaPrimitiveType)
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        applyLocalTextContrast(chain)
                    } catch (t: Throwable) {
                        Log.e(TAG, "text-contrast A intercept error", t)
                        chain.proceed()
                    }
                }
            Log.i(TAG, "text-contrast A mounted: TextView#setTextColor(int)")
        } catch (t: Throwable) {
            Log.e(TAG, "text-contrast A mount FAILED: TextView#setTextColor(int)", t)
        }
    }

    /** [spec/63 文字可读性统一注入通道 2026-08-26] hook `TextView.setTextColor(int)`：系统每次设置文字颜色
     *  时统一走 [resolveReadableTextColor] 计算可读性注入色（文字强制白 / heads-up 跟随 / 文字反转取色
     *  单一入口，杜绝各路径独立判定「一个白一个黑」）。返回 null=不注入（保持系统色）。未 attach 登记待
     *  onDraw 重查。防自循环：[textContrastResampling] 标志。 */
    private fun applyLocalTextContrast(chain: XposedInterface.Chain): Any? {
        // 防自循环：onDraw/快照更新重采样直接调 setTextColor(injected) 时跳过（见 maybeResampleTextContrast）
        if (textContrastResampling) return chain.proceed()
        val textView = try {
            chain.getThisObject() as? TextView
        } catch (t: Throwable) {
            null
        } ?: return chain.proceed()
        val color = try {
            chain.getArg(0) as? Int
        } catch (t: Throwable) {
            null
        } ?: return chain.proceed()
        // [2026-08-13 状态栏排除] 状态栏文字（PhoneStatusBarView / StatusBarWindowView / KeyguardStatusBarView
        // 等祖先容器内，含时钟/日期/图标等）**绝不参与文字变色**（保持系统原样）。
        if (isInStatusBarArea(textView)) {
            textContrastCache.remove(textView)
            return chain.proceed()
        }
        // [2026-08-15 折叠组头误伤] 展开按钮数字（oplus_expand_button_number）原色灰色中性色，保持系统原色。
        if (isExpandButtonNumber(textView)) {
            textContrastCache.remove(textView)
            return chain.proceed()
        }
        // colorized / app 自定义色（非近中性灰度）不覆盖；移除缓存（onDraw 不再重注入），尊重 app
        if (!isNeutralTextColor(color)) {
            textContrastCache.remove(textView)
            return chain.proceed()
        }
        // [spec/63 统一注入通道] 前置排除后统一走 resolveReadableTextColor；返回 null=不注入（保持系统色）。
        if (textView.isAttachedToWindow && textView.width > 0 && textView.height > 0) {
            val loc = IntArray(2)
            try {
                textView.getLocationOnScreen(loc)
            } catch (t: Throwable) {
                return chain.proceed()
            }
            val injected = resolveReadableTextColor(
                textView, color,
                loc[0] + textView.width / 2, loc[1] + textView.height / 2
            ) ?: run {
                textContrastCache.remove(textView)
                return chain.proceed()
            }
            val snap = screenSnapshot
            val snapshotId = snap?.let { System.identityHashCode(it.bitmap) } ?: -1
            if (textContrastCache.size > TEXT_CONTRAST_CACHE_MAX) textContrastCache.clear()
            textContrastCache[textView] = TextContrastCacheEntry(
                snapshotId, loc[0], loc[1], color, injected, SystemClock.uptimeMillis()
            )
            if (injected == color) return chain.proceed()
            val args = chain.getArgs().toTypedArray()
            args[0] = injected
            return chain.proceed(args)
        } else {
            // 未 attach / 尺寸未知 → 登记待 onDraw 重查（injected=原色，不注入；onDraw 兜底重注入）
            val existing = textContrastCache[textView]
            if (existing == null || existing.originalColor != color) {
                textContrastCache[textView] = TextContrastCacheEntry(-1, Int.MIN_VALUE, Int.MIN_VALUE, color, color, 0)
            }
            return chain.proceed()
        }
    }


    /**
     * [spec/21 修复 2026-08-13] hook `TextView.onDraw(Canvas)`：对已登记（[textContrastCache]）的 TextView
     * 节流重采样（O(1) 快速跳过未登记），快照换帧 / 位置变化 / 刚 attach（x/y 未知）时重新采样注入。
     *
     * 节流：per-TextView 150ms（[TEXT_CONTRAST_RESAMPLE_INTERVAL_MS]），非每帧采样；仅检查不采样时无重活。
     * 防自循环：[textContrastResampling] 标志 + 直接调 setTextColor（不递归）。
     * 作用域：仅玻璃区域内文字才反转（[isOverGlassRegion]）。
     */
    private fun mountTextViewOnDraw(api: XposedInterface, classLoader: ClassLoader) {
        try {
            val clazz = Class.forName("android.widget.TextView", false, classLoader)
            // onDraw 是 protected → 必须 getDeclaredMethod + setAccessible（getMethod 只返回 public）
            val method = clazz.getDeclaredMethod("onDraw", android.graphics.Canvas::class.java).apply { isAccessible = true }
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        val tv = chain.getThisObject() as? TextView
                        if (tv != null) maybeResampleTextContrast(tv)
                    } catch (t: Throwable) {
                        Log.e(TAG, "text-contrast A onDraw error", t)
                    }
                    chain.proceed()
                }
            Log.i(TAG, "text-contrast A mounted: TextView#onDraw(Canvas) (throttled re-sample)")
        } catch (t: Throwable) {
            Log.e(TAG, "text-contrast A mount FAILED: TextView#onDraw(Canvas)", t)
        }
    }

    /** [spec/21 修复 2026-08-13] 单个 TextView 节流重采样（onDraw 兜底，150ms 节流）：已登记 TextView
     *  统一走 [resolveReadableTextColor]（spec/63）计算可读性注入色，颜色变化才 setTextColor（幂等不闪）。
     *  防自循环：[textContrastResampling] 标志 + 直接调 setTextColor（不递归）。 */
    private fun maybeResampleTextContrast(textView: TextView) {
        if (textContrastResampling) return
        val entry = textContrastCache[textView] ?: return  // 未登记 → O(1) 快速跳过
        // [2026-08-13 状态栏排除] 状态栏文字绝不参与文字变色（保持系统原样）
        if (isInStatusBarArea(textView)) {
            textContrastCache.remove(textView)
            return
        }
        // [2026-08-15 折叠组头误伤] 展开按钮数字保持系统原色（同 applyLocalTextContrast / forceRefreshTextColor）
        if (isExpandButtonNumber(textView)) {
            textContrastCache.remove(textView)
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - entry.lastCheckMs < TEXT_CONTRAST_RESAMPLE_INTERVAL_MS) return
        if (!textView.isAttachedToWindow || textView.width <= 0 || textView.height <= 0) return
        val loc = IntArray(2)
        try {
            textView.getLocationOnScreen(loc)
        } catch (t: Throwable) {
            entry.lastCheckMs = now
            return
        }
        val original = entry.originalColor
        // [spec/63 统一注入通道] 统一计算注入色（文字强制白 / heads-up 跟随 / 文字反转取色单一入口）
        val injected = resolveReadableTextColor(
            textView, original,
            loc[0] + textView.width / 2, loc[1] + textView.height / 2
        )
        entry.lastCheckMs = now
        if (injected == null) {
            // 不再需要注入（移出玻璃区域 / 相关开关关）→ 仅更新缓存注入色为原色，不强制改回（保持现状语义）
            val sid = screenSnapshot?.let { System.identityHashCode(it.bitmap) } ?: -1
            textContrastCache[textView] = TextContrastCacheEntry(sid, loc[0], loc[1], original, original, now)
            return
        }
        val snap = screenSnapshot
        val snapshotId = snap?.let { System.identityHashCode(it.bitmap) } ?: -1
        textContrastCache[textView] = TextContrastCacheEntry(snapshotId, loc[0], loc[1], original, injected, now)
        if (injected != entry.injectedColor) {
            // 防自循环：setTextColor hook 见 textContrastResampling=true 直接 proceed（不递归采样）
            textContrastResampling = true
            try {
                textView.setTextColor(injected)
            } catch (t: Throwable) {
                Log.e(TAG, "text-contrast A re-sample setTextColor failed", t)
            } finally {
                textContrastResampling = false
            }
        }
    }


    /** [spec/21 修复] 快照更新（worker → 主线程 post）后全量重采样已登记 TextView（兜底 onDraw 未覆盖场景）。 */
    private fun resampleAllTextContrast() {
        try {
            // textContrastCache = WeakHashMap<TextView, _>：key 即 TextView（弱键自动清理失效项），无需 .get()
            val iter = textContrastCache.entries.iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                val tv = entry.key
                maybeResampleTextContrast(tv)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "text-contrast A resampleAll error", t)
        }
    }

    // ------------------------------------------------------------ [spec/52 文字强制刷新：展开变色折叠变回根治]

    /**
     * [spec/52] 请求一次「页面上所有文字颜色强制刷新」（post 主线程，[FORCE_REFRESH_TEXT_COLOR_MIN_INTERVAL_MS]
     * 节流）。触发点：①面板收起（mountPanelExpansion setExpansionHeight<=0）②系统黑白通道状态跳变
     * （mountQsColorUtil hook QsColorUtil.setQsColorState(int) 发布方）。快照就绪路径（resampleAllTextContrast）
     * 保留不变——本函数是「折叠变回」缺口兜底：折叠时系统重调 setTextColor(原色)，守卫失败放行原色 +
     * 折叠不触发重采样，导致文字变回原色；此处遍历 View 树直接重注入。
     * 注意：全树遍历有成本，仅触发点调用（不每帧）；发布方可能不在主线程 → 统一 post 到主线程。
     */
    private fun requestForceRefreshTextColors() {
        val now = SystemClock.uptimeMillis()
        if (now - lastForceRefreshTextColorMs < FORCE_REFRESH_TEXT_COLOR_MIN_INTERVAL_MS) return
        lastForceRefreshTextColorMs = now
        try {
            mainHandler().post { forceRefreshAllTextColors() }
        } catch (t: Throwable) {
            Log.e(TAG, "text-contrast forceRefresh post failed", t)
        }
    }

    /** [spec/52] 强制刷新：遍历 shade 根（[shadeRootViewCache]，spec/45）与各已登记宿主 rootView
     *  （registeredHostViews，含 heads-up 窗口）的 View 子树收集全部 TextView，对中性色且玻璃区域
     *  （或 heads-up 跟随区域）内的文字按当前遮罩/系统通道重注入颜色，并更新 [textContrastCache]。
     *  与 [resampleAllTextContrast]（仅遍历已登记弱表）不同：本函数**重新发现**未登记 TextView
     *  （折叠变回后系统重调 setTextColor 可能绕过 hook 登记/把缓存覆盖成原色），是兜底全量刷新。
     *  主线程调用（requestForceRefreshTextColors post 到主线程）。
     */
    private fun forceRefreshAllTextColors() {
        // [spec/60] 加 headsUpTextFollowEnabled：mask 关 + 文字反色关但 heads-up 跟随开时，周期刷新
        // 仍须全树重扫 heads-up 文字（spec/47 跟随稳定）。
        if (!textContrastEnabled && !maskEnabled && !headsUpTextFollowEnabled && !textForceWhiteEnabled) return
        // [spec/62 锁屏耗电优化 2026-08-26] 廉价早退：锁屏时 screenRegionMap 为空（无玻璃区域映射）且无
        // heads-up、面板未展开 → 无文字需重注入，不遍历 shade 树（锁屏无需更新文字，spec/62 决策 28）。
        // 注意：requestForceRefreshTextColors 在面板收起/系统状态跳变时也会触发，早退仅拦「真无玻璃宿主」
        // 场景（screenRegionMap 非空则放行，正常路径不受影响）。
        if (screenRegionMap.isEmpty() && !isHeadsUpHostActive() && !panelExpansionActive) return
        val roots = LinkedHashSet<View>()
        try {
            shadeRootViewCache?.let { root ->
                if (root.isAttachedToWindow) roots.add(root)
            }
            collectRegisteredHostRoots(roots)
        } catch (t: Throwable) {
            Log.e(TAG, "text-contrast forceRefresh collect roots error", t)
        }
        if (roots.isEmpty()) return
        // TextView 不重写 equals → HashSet 按对象身份去重（同一 TextView 可能经 shade 根与宿主两条路径命中）
        val seen = HashSet<TextView>()
        for (root in roots) {
            try {
                collectTextViews(root, seen)
            } catch (t: Throwable) {
                Log.e(TAG, "text-contrast forceRefresh collect error", t)
            }
        }
        for (tv in seen) {
            try {
                forceRefreshTextColor(tv)
            } catch (t: Throwable) {
                // 单 View 失败不阻塞其余（强兜底不崩）
            }
        }
    }

    /** [spec/52] 收集 registeredHostViews 各存活宿主的窗口根（rootView；同一 rootView 多个宿主时去重）。
     *  @Synchronized：与 registerHostView/invalidateRegisteredHostViews/resolveShadeSfc 一致防遍历 CME。 */
    @Synchronized
    private fun collectRegisteredHostRoots(roots: MutableSet<View>) {
        val it = registeredHostViews.iterator()
        while (it.hasNext()) {
            val v = it.next().ref.get() ?: continue
            if (!v.isAttachedToWindow) continue
            try {
                val root = v.rootView
                if (root != null && root.isAttachedToWindow) roots.add(root)
            } catch (t: Throwable) {
                Log.e(TAG, "text-contrast forceRefresh host root error", t)
            }
        }
    }

    /** [spec/52] 递归遍历 View 子树收集所有 TextView（失败子节点忽略继续，强兜底不崩）。 */
    private fun collectTextViews(root: View, out: MutableSet<TextView>) {
        if (root is TextView) out.add(root)
        if (root is android.view.ViewGroup) {
            val n = root.childCount
            for (i in 0 until n) {
                try {
                    val child = root.getChildAt(i) ?: continue
                    collectTextViews(child, out)
                } catch (t: Throwable) {
                    // 单个子节点遍历失败忽略，继续遍历其余
                }
            }
        }
    }

    /** [spec/63 文字可读性统一注入通道 2026-08-26] 统一文字可读性颜色注入：所有可读性场景（文字强制白 /
     *  heads-up 跟随系统取色 / 文字反转取色）单一入口，杜绝各路径独立判定造成「一个白一个黑」。
     *  给定 TextView + 原色 + 屏幕坐标，按优先级返回注入色；返回 null = 不注入（保持系统色）。
     *  优先级：**文字强制白（[textForceWhiteEnabled] 独立开关，spec/63 解耦 KEY_MASK_ENABLE）>
     *  heads-up 跟随（[isHeadsUpTextFollowArea]）> 文字反转取色（[textContrastEnabled]）**。
     *  强制白开启时玻璃区域内 / heads-up 窗口内中性色文字恒白/灰（spec/60 语义保留），后两者不生效。
     *  调用方负责前置排除（状态栏 / 展开按钮 / app 彩色 / 未 attach）与缓存 / 防自循环注入尾。 */
    private fun resolveReadableTextColor(textView: TextView, originalColor: Int, screenX: Int, screenY: Int): Int? {
        // 1. 文字强制白（独立开关，spec/63 解耦 KEY_MASK_ENABLE）：玻璃区域内 / heads-up 窗口内中性色文字恒白/灰
        if (textForceWhiteEnabled) {
            if (isOverGlassRegion(screenX, screenY) || isInHeadsUpWindow(textView)) {
                val a = (originalColor ushr 24) and 0xFF
                return (a shl 24) or (maskTextColor and 0x00FFFFFF)
            }
        }
        // 2. heads-up 跟随系统取色（spec/47）：heads-up 窗口内中性色文字按系统黑白通道（深底白字 / 浅底黑字）
        if (isHeadsUpTextFollowArea(textView)) {
            return systemQsColorInjected(originalColor)
        }
        // 3. 文字反转取色（spec/21/39 系统黑白通道）：玻璃区域内中性色文字按系统黑白通道
        if (textContrastEnabled && isOverGlassRegion(screenX, screenY)) {
            return systemQsColorInjected(originalColor)
        }
        return null
    }
    /** [spec/52 强制刷新] 单个 TextView 周期全量重注入：统一走 [resolveReadableTextColor]（spec/63）计算
     *  注入色。原色取缓存 originalColor（折叠变回场景：系统重调 setTextColor(原色) 后缓存可能已覆盖），
     *  缓存缺失回退 currentTextColor。注入前更新 [textContrastCache]（onDraw 节流以此为准，幂等）。
     *  防自循环：[textContrastResampling] 标志 + 直接调 setTextColor。 */
    private fun forceRefreshTextColor(textView: TextView) {
        if (textContrastResampling) return
        // [2026-08-13 状态栏排除] 状态栏文字绝不参与文字变色（保持系统原样）：forceRefresh 遍历 shade 根
        // 全树会命中状态栏文字（其 rootView 即 shade 根 / 独立 StatusBarWindowView）→ 按祖先容器直接排除。
        if (isInStatusBarArea(textView)) return
        // [2026-08-15 折叠组头误伤] 展开按钮数字保持系统原色
        if (isExpandButtonNumber(textView)) {
            textContrastCache.remove(textView)
            return
        }
        val entry = textContrastCache[textView]
        val original = entry?.originalColor ?: textView.currentTextColor
        if (!isNeutralTextColor(original)) return
        if (!textView.isAttachedToWindow || textView.width <= 0 || textView.height <= 0) return
        val loc = IntArray(2)
        try {
            textView.getLocationOnScreen(loc)
        } catch (t: Throwable) {
            return
        }
        // [spec/63 统一注入通道] 统一计算注入色（文字强制白 / heads-up 跟随 / 文字反转取色单一入口）
        val injected = resolveReadableTextColor(
            textView, original,
            loc[0] + textView.width / 2, loc[1] + textView.height / 2
        ) ?: return
        val snap = screenSnapshot
        val snapshotId = snap?.let { System.identityHashCode(it.bitmap) } ?: -1
        if (textContrastCache.size > TEXT_CONTRAST_CACHE_MAX) textContrastCache.clear()
        textContrastCache[textView] = TextContrastCacheEntry(
            snapshotId, loc[0], loc[1], original, injected, SystemClock.uptimeMillis()
        )
        if (injected == textView.currentTextColor) return
        // 防自循环：setTextColor hook 见 textContrastResampling=true 直接 proceed（不递归采样）
        textContrastResampling = true
        try {
            textView.setTextColor(injected)
        } catch (t: Throwable) {
            Log.e(TAG, "text-contrast forceRefresh setTextColor failed", t)
        } finally {
            textContrastResampling = false
        }
    }


    /** 判定屏幕坐标是否落在任一已登记玻璃区域（screenRegionMap，主线程读写）。用于限定局部反转作用域。
     *  [spec/53 状态栏文字变黑 2026-08-13] 状态栏区域（y < [statusBarBottom]）恒判"非玻璃"：
     *  screenRegionMap 中控件（真机 coord 日志实证 ImageView/ImageButton 128x128 等，y=126-254 覆盖
     *  状态栏条带；控制中心快捷开关/胶囊等）的 region 可能覆盖状态栏区域 → 状态栏文字（时钟/图标 label）
     *  被误判"玻璃区域内" → 文字变色逻辑（mask 开强制白 / mask 关 systemQsColorInjected 注入黑）误伤。
     *  scoped：仅顶部状态栏条带；通知卡/媒体卡/QS tile 等玻璃文字（y ≥ statusBarBottom）不受影响，
     *  spec/38（ScrimView 不写 map）与 spec/47（heads-up 跟随）语义均保留。 */
    private fun isOverGlassRegion(screenX: Int, screenY: Int): Boolean {
        val sb = statusBarBottom()
        if (sb > 0 && screenY < sb) return false
        for (entry in screenRegionMap.values) {
            val r = entry.region
            if (screenX >= r.left && screenX <= r.right && screenY >= r.top && screenY <= r.bottom) return true
        }
        return false
    }

    /** [spec/53] 状态栏区域底边（物理 px）：framework `status_bar_height` dimen（Resources.getSystem()
     *  同文件 captureScreenSnapshot/captureElementBackground 同款，设备密度生效）。首次调用缓存到
     *  [statusBarBottomPx]；解析失败返回 -1（isOverGlassRegion 不启用排除，回退现状）。 */
    private fun statusBarBottom(): Int {
        var v = statusBarBottomPx
        if (v >= 0) return v
        v = try {
            val res = android.content.res.Resources.getSystem()
            val id = res.getIdentifier("status_bar_height", "dimen", "android")
            if (id <= 0) -1 else res.getDimensionPixelSize(id)
        } catch (t: Throwable) {
            Log.e(TAG, "text-contrast: resolve status_bar_height failed, status-bar strip exclusion disabled", t)
            -1
        }
        statusBarBottomPx = v
        if (v > 0) {
            Log.i(TAG, "text-contrast: status_bar_height=$v px, status-bar strip excluded from glass-region (text never recolored)")
        }
        return v
    }

    /**
     * [2026-08-13 状态栏排除] 状态栏文字判定：沿祖先链向上（含自身），任一类名命中状态栏容器即返回 true。
     * 反编译实证（c16.1_PJZ110）：
     * - shade 内状态栏：`PhoneStatusBarView`（status_bar.xml 根类，CollapsedStatusBarFragment mStatusBar，
     *   含 status_bar_contents → clock / system_icons），其 rootView=NotificationShadeWindowView
     *   （**非独立窗口**，spec/47 实证）→ 仅靠 rootView 类名无法识别，必须沿祖先链匹配。
     * - 独立状态栏窗口：`StatusBarWindowView`（com.android.systemui.statusbar.window，折叠态常驻）。
     * - 锁屏状态栏：`KeyguardStatusBarView`（keyguard_status_bar.xml 根类）。
     * - 状态图标区：`StatusIconContainer`（system_icons 内 StatusBarIconView 父容器）。
     * 作用域安全：这些类名均为状态栏专属（不匹配 QS 容器 QuickStatusBarHeader / 通知卡 / 时钟在别的布局
     * 的误判），不会误伤通知/QS 玻璃区域内文字；与 spec/38（ScrimView 不写 map）互补，双保险。
     * 调用方：applyLocalTextContrast / maybeResampleTextContrast / forceRefreshTextColor（文字变色全路径）。
     */
    /** [2026-09-17 性能] 类名 → 「是否状态栏区域宿主」判定缓存。
     *
     *  **为什么必须缓存**（simpleperf 火焰图实证）：`isInStatusBarArea` 对**每个 TextView 沿父链逐层**
     *  做最多 5 次 `contains`，而它被两条高频全屏路径调用（`resampleAllTextContrast` 与
     *  `forceRefreshAllTextColors`）→ 屏幕上百个 TextView × 父链十几层 × 5 次 = **上万次字符串搜索**，
     *  **且类名判定结果恒定、每次重算**。火焰图显示 `String.indexOf` 占主线程采样 **13.31%**，
     *  其中 78.81%(某路径) / 52.65%(另一路径) 经由本函数。
     *
     *  类名数量有限（几十个），缓存不膨胀；ConcurrentHashMap 兜住 worker 线程（文字扫描在后台单飞）并发。 */
    private val statusBarHostClassCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private fun isInStatusBarArea(textView: View): Boolean {
        var v: View? = textView
        while (v != null) {
            val name = v.javaClass.name
            val isHost = statusBarHostClassCache.getOrPut(name) {
                name.contains("PhoneStatusBarView") ||
                    name.contains("KeyguardStatusBarView") ||
                    name.contains("StatusBarWindowView") ||
                    name.contains("StatusBarContainer") ||
                    name.contains("StatusIconContainer")
            }
            if (isHost) return true
            val parent = v.parent
            v = if (parent is View) parent else null
        }
        return false
    }

    /** [2026-08-15 折叠组头误伤修复] 判定 TextView 是否是折叠组头展开按钮数字
     *  （resource-id com.android.systemui:id/oplus_expand_button_number，text="2"，位于折叠组头内）。
     *  该数字原色是灰色（中性色），文字变色/强制刷新把它当中性色强白 → 灰字变白看不清（用户确认根因）——
     *  **保持系统原色，不参与文字变色**。识别用资源名匹配（不依赖模块引用 SystemUI R.id；
     *  getResourceEntryName 对无 id/非资源 id 可能抛异常 → 捕获返回 false）。 */
    private fun isExpandButtonNumber(tv: View): Boolean {
        if (tv.id <= 0) return false
        return try {
            tv.resources.getResourceEntryName(tv.id) == EXPAND_BUTTON_NUMBER_RES_NAME
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * [spec/47] heads-up 窗口内文字判定：rootView=HeadsUpLayout/HeadsUpContainerWindow（type 2017，
     * 反编译实证 com.oplus.systemui.notification.headsup.windowframe）。heads-up 卡片背景即玻璃
     * （spec/19 强制 PlatformStatic 走我们管线），窗口内文字**恒视为玻璃区域文字**——不受
     * `screenRegionMap` 登记时序/坐标/快照刷新影响（否则 heads-up 文字可能被 [isOverGlassRegion]
     * 误判为非玻璃，遮罩关闭时不跟随整体颜色 / 遮罩开启时不统一白字）。
     *
     * 作用域安全：仅命中 rootView=heads-up 窗口的文字；状态栏时钟 rootView=shade 根
     * （NotificationShadeWindowView）→ 不命中 → spec/38 修复（ScrimView 不写 map）不受影响。
     */
    private fun isInHeadsUpWindow(textView: View): Boolean {
        return try {
            val root = textView.rootView
            root != null && isHeadsUpRootView(root)
        } catch (t: Throwable) {
            false
        }
    }

    /** [doc/spec/49 2026-08-13] bgView（NotificationBackgroundView）是否属于 heads-up 窗口（type 2017）：
     *  rootView=HeadsUpLayout/HeadsUpContainerWindow（判定同 [isInHeadsUpWindow]）。heads-up 卡同卡内容
     *  更新（bounds/region 变化）需触发整屏快照重抓；shade 内普通通知卡不命中（srcRect 折算已跟手）。 */
    private fun isHeadsUpBgView(view: View): Boolean = isInHeadsUpWindow(view)

    /** [spec/47] heads-up 文字跟随整体颜色作用域：独立开关 [headsUpTextFollowEnabled] 开启 && 在 heads-up
     *  窗口内（rootView=HeadsUpLayout/HeadsUpContainerWindow）。开关关 → 恒 false → 守卫回退现状
     *  （mask 开统一白 / mask 关原样），保证与遮罩无关、可独立关闭。 */
    private fun isHeadsUpTextFollowArea(textView: View): Boolean =
        headsUpTextFollowEnabled && isInHeadsUpWindow(textView)

    /** 近中性文字色判定：alpha ≥ 0x80 且 RGB 通道差 ≤24（系统 neutral #E6 90% 黑/白、QS tile label 等灰度）；
     *  饱和 app 自定义色（如绿色"已连接"）不匹配 → 不覆盖，尊重 app。 */
    private fun isNeutralTextColor(color: Int): Boolean {
        val a = (color ushr 24) and 0xFF
        if (a < 0x80) return false
        val r = (color ushr 16) and 0xFF
        val g = (color ushr 8) and 0xFF
        val b = color and 0xFF
        return abs(r - g) <= 24 && abs(g - b) <= 24 && abs(r - b) <= 24
    }

    /**
     * [spec/39 文字反色复用系统黑白通道] 读取 `QsColorUtil.getQsColorState()` 决定注入色（替代 spec/21
     * 逐文字整屏快照采样 `sampleInjectedColor`）。系统 `OplusQsColorManager`（SystemUI 进程内）监听通知/QS
     * 模糊管线完成 → `getBitmapBrightness`（native）→ `setQsColorState`：亮度 >196 → 0（亮背景/需深色字）；
     * ≤196 → 1（暗背景/需白色字）；null → 2。它采样的正是模块 hook 的同一模糊管线背景（语义=系统自己的明暗判定）。
     *   - 0 → 深色字（黑，保留原 alpha）
     *   - 1 → 白色字（保留原 alpha）
     *   - 2（null/无数据）/ 反射失败 → 返回 [original]（保持原样，不注入）
     */
    private fun systemQsColorInjected(original: Int): Int {
        val a = (original ushr 24) and 0xFF
        val aPrefix = a shl 24
        return when (readQsColorState()) {
            0 -> aPrefix or 0x000000   // 亮背景 → 深色字
            1 -> aPrefix or 0xFFFFFF   // 暗背景 → 白色字
            else -> original           // 2（null/无数据）或反射失败 → 保持原样
        }
    }

    /** [spec/39] 反射读取 `QsColorUtil.getQsColorState()`（public static final int，无参，反编译实证
     *  QsColorUtil.java:92-94；`mQsColorUtilGetState` 在 mountQsColorUtil 解析）。失败/未解析返回 -1。 */
    private fun readQsColorState(): Int {
        val m = mQsColorUtilGetState ?: return -1
        val v = try {
            (m.invoke(null) as? Number)?.toInt() ?: -1
        } catch (t: Throwable) {
            -1
        }
        // [spec/47 诊断] 系统黑白通道值跳变才打一次（0 亮背景→深色字 / 1 暗背景→白字 / 2 null→原样 / -1 反射失败）。
        if (v != lastLoggedQsColorState) {
            lastLoggedQsColorState = v
            Log.i(TAG, "text-contrast: QsColorUtil.getQsColorState()=$v (0 light→dark text / 1 dark→white text / 2 null / -1 reflect fail)")
        }
        return v
    }

    /** 像素亮度（0-255，Rec.601 加权）。 */
    private fun luminance(color: Int): Int {
        val r = (color ushr 16) and 0xFF
        val g = (color ushr 8) and 0xFF
        val b = color and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    /**
     * [spec/21] 快照更新时计算 QS 面板区域平均亮度（worker 线程，O(N/16) 步进采样，getPixels 单次 native 调用）。
     * 快照缺失/失败 → qsPanelBrightness = -1（hook 走系统原逻辑）。亮度缓存避免每次 QsColorUtil 判定全图重扫。
     */
    private fun updateQsBrightness(bg: ElementBackground?) {
        val bmp = bg?.bitmap ?: run { qsPanelBrightness = -1; return }
        try {
            val w = bmp.width
            val h = bmp.height
            if (w <= 0 || h <= 0) { qsPanelBrightness = -1; return }
            val pixels = IntArray(w * h)
            bmp.getPixels(pixels, 0, w, 0, 0, w, h)
            var sum = 0L
            var n = 0
            var y = 0
            while (y < h) {
                var x = 0
                while (x < w) {
                    sum += luminance(pixels[y * w + x])
                    n++
                    x += 4
                }
                y += 4
            }
            qsPanelBrightness = if (n > 0) (sum / n).toInt() else -1
            logThrottled("text-contrast-brightness", Log.DEBUG) { "text-contrast: QS panel avg brightness=$qsPanelBrightness (${w}x${h} @step4)" }
        } catch (t: Throwable) {
            qsPanelBrightness = -1
        }
    }

    /** 登记宿主 View（WeakReference 防泄漏）：先清理失效弱引用，再按 referent 去重。 */
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
        // [2026-08-13 captureDisplay 开始延迟优化] 新宿主登记（如 heads-up 通知卡首次出现）→ 清零
        // heads-up 窗口 sfc 负缓存，通知出现时 resolveHeadsUpSfc 立即重新遍历解析（不被负缓存挡住）。
        headsUpSfcMissAt = 0L
        // [2026-08-15 轻打扰折叠横幅] 同 heads-up：新宿主登记 → 清零 Simple Banner sfc 负缓存
        //（横幅出现时 resolveSimpleBannerSfc 立即重新遍历解析，不被负缓存挡住）。
        simpleBannerSfcMissAt = 0L
        // [spec/45] shade 根登记兜底：shade 窗口根 View 常驻，折叠期 registeredHostViews 内只剩
        // heads-up 卡片宿主（rootView=HeadsUpLayout 非 shade 根）遍历不到 → 此处额外缓存强引用，
        // 供 resolveShadeSfc 折叠期解析 sfc。
        try {
            val rootView = view.rootView
            if (rootView != null && isShadeRootView(rootView)) {
                shadeRootViewCache = rootView
                // [2026-08-15 偶发不抓屏根治] 新根登记 → 失效旧 sfc（窗口重建后旧 handle 可能仍 valid
                // 但已指向旧窗口，exclude 错窗口 → 快照含 shade 自采样），强制下次 resolveShadeSfc 重解析。
                shadeSfcCache = null
                Log.i(TAG, "bg-source: shade root view cached ${rootView.javaClass.simpleName}")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "bg-source: cache shade root view failed", t)
        }
        // 整屏快照重抓触发点不在此时——screenRegionMap 的映射由 registerScreenRegionCore
        // 在登记之后写入，抓屏触发已移到映射写入分支（见 registerScreenRegionCore）
        // [doc/spec/33 持续抓屏] 新宿主登记 = 有活跃玻璃显示 → 启动周期性抓屏（动态背景实时刷新；
        // 宿主全灭时 periodicCaptureRunnable 自动停止续跑）
        kickPeriodicCapture()
        // [spec/60 周期强制刷新] 新宿主登记 = 有活跃玻璃显示 → 确保文字周期强制刷新循环在跑
        ensureTextForceRefreshLoop()
    }

    /**
     * 运动事件触发：遍历 registeredHostViews，对每个存活 View 调 `invalidate()` 强制重录 draw。
     * 失效弱引用顺带清理。单 View 失败不阻塞其余（强兜底不崩）。
     */
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
                Log.e(TAG, "taskG: invalidate host view failed", t)
            }
        }
    }

    // ------------------------------------------------------------ [spec/24] 滑动降级 CONIC（运动态全局圆弧）+ [2026-08-13] 复位平滑过渡

    /** [2026-08-13 用户决定·角形状恒 CONIC] 运动态标志保留作状态（不再驱动角形状）。
     *  CONIC↔圆弧切换已整体废弃（spec/24 滑动降级 + spec/27 过渡动画）："停止滑动后边缘闪"根因
     *  = 两种角形状切换（CONIC 与圆弧 45° 处差 ~45px）——只保留系统真实角 CONIC 即不闪。
     *  [cornerConicBlend] 恒 1（shader 恒 CONIC），不再被 markMotion/动画改写。
     *  @Volatile：主线程写，渲染线程（draw hook）读。 */
    @Volatile
    private var isMotionActive = false

    /** [doc/spec/50 2026-08-13] 面板是否处于收起状态。@Volatile：主线程 setExpansionHeight hook 写/读。
     *  初始 true（SystemUI 启动面板收起）；expansion<=0 → true（再次收起）；expansion>0 且本标志 true =
     *  刚下拉首帧 → 置 false + 立即触发抓屏（[2026-08-13 回退：不再主线程同步 captureDisplay，入队
     *  worker + 连抓补帧，主线程零阻塞]）；后续帧（动画持续，标志 false）走 worker 异步抓屏。
     *  每次收起→展开转换触发一次首帧连抓（不跨帧保持，防持续主线程阻塞）。 */
    @Volatile
    private var panelWasCollapsed = true

    /** [2026-08-13 延迟调查·竞争暂停] 面板是否处于下拉展开中（setExpansionHeight > 0 或 onExpansionStarted
     *  确认下拉）。true 期间 heads-up 连抓/持续抓屏 worker **暂停**（避免与下拉抓屏并发锤 captureDisplay，
     *  SF 串行 → 主抓屏被排队拖后 50-150ms）；下拉收起（expansion<=0）恢复。@Volatile：主线程 hook 写，
     *  heads-up worker 线程读。 */
    @Volatile
    private var panelExpansionActive = false

    /** [spec/59 层 1+2 根治 + 2026-08-14 用户修正·去锁屏判断] 真收起判定：面板收起（panelExpansionActive=false）
     *  + 无通知横幅 = shade 不在屏幕渲染（sfc 必 invalid），抓屏必然失败且玻璃不可见 → 抑制抓屏（periodic
     *  收起期不入队 / sfc 失败强制抓全屏兜底）。**不判锁屏**——锁屏时 setExpansionHeight 不回调、panelExpansionActive
     *  保持 false，同样应视为「收起不抓屏」；锁屏玻璃（spec/43 时钟）走壁纸源不依赖抓屏背景。 */
    private fun isShadeTrulyCollapsed(): Boolean =
        !panelExpansionActive && headsUpActiveHost?.get() == null

    /** [2026-08-14 CPU 修复] 最近一次「shade sfc 有效」的整屏抓屏时刻（uptimeMillis）。worker 写
     *  （captureSnapshotImmediate 成功且 shade valid），持续抓屏线程读——sfc 长期无效 = shade 不在渲染
     *  （面板收起）→ 持续抓屏超时停止，兜底 panelExpansionActive 卡 true 导致 120Hz 空转。 */
    @Volatile
    private var lastValidSfcCaptureMs = 0L

    /** [2026-08-13 回退 spec/49 场景门控] isShadeExpanded 字段已删除：其门控（面板收起且无 heads-up 宿主
     *  → 停周期抓屏）误拦锁屏（锁屏时 setExpansionHeight 不回调/回调 0 且无 heads-up）→ 锁屏玻璃背景冻结。
     *  「收起零抓屏」改由 periodicCaptureRunnable 的 idle-skip 空闲判定保证。保留本条注释说明取舍，字段
     *  本身不再存在。 */

    /** 本体角 CONIC↔圆弧渐变权重（= uCornerConicBlend uniform 值；0=纯圆弧，1=纯 CONIC）。
     *  [2026-08-13] **恒 1**（用户决定只保留一种角 = CONIC，对齐系统 spec/12）：不再随运动态切换
     *  /动画驱动。
     *  @Volatile：主线程写，渲染线程（draw hook）读。 */
    @Volatile
    private var cornerConicBlend = 1f

    /** 运动态复位延迟：滑动停止后复位 isMotionActive（状态标志；角形状不再受其影响）。 */
    private const val MOTION_CONIC_RESET_MS = 300L

    /** 复位 Runnable（主线程 postDelayed）：复位运动态标志。[2026-08-13 用户决定] 角形状恒 CONIC，
     *  不再做 CONIC↔圆弧过渡动画——"停止滑动后边缘闪"根因就是两种角形状切换；只保留一种
     *  （CONIC）即不闪。markMotion 不再置 cornerConicBlend=0（恒 1）。
     *  [doc/spec/49 2026-08-13] 拖动结束补算 QS 亮度 + 文字反色：拖动期 worker 跳过了
     *  updateQsBrightness 全图扫描（见 runElementCaptureWorker），此处复位时用最新 screenSnapshot
     *  补算一次（worker 线程跑全图扫描，不阻塞主线程）。 */
    private val motionResetRunnable = Runnable {
        isMotionActive = false
        // [doc/spec/49] 拖动结束补算（读最新快照 @Volatile；全图扫描放 worker 线程）
        val bg = screenSnapshot
        if (textContrastEnabled && bg != null) {
            try {
                Thread {
                    updateQsBrightness(bg)
                    mainHandler().post { resampleAllTextContrast() }
                }.start()
            } catch (t: Throwable) {
                Log.e(TAG, "text-contrast: post-motion brightness resample failed", t)
            }
        }
    }

    // [2026-08-13 用户决定] CONIC↔圆弧过渡动画已删：角形状恒 CONIC（对齐系统 spec/12），
    // "停止滑动后边缘闪"根因 = 两种角形状切换，只保留一种即不闪。cornerConicBlend 恒 1。

    /** 标记运动态：[2026-08-13 用户决定] 角形状恒 CONIC 不再切换——markMotion 只维护运动态标志
     *  （isMotionActive 保留作状态；不再置 cornerConicBlend=0，CONIC 元素渲染恒 CONIC）。 */
    private fun markMotion() {
        isMotionActive = true
        try {
            mainHandler().removeCallbacks(motionResetRunnable)
            mainHandler().postDelayed(motionResetRunnable, MOTION_CONIC_RESET_MS)
        } catch (t: Throwable) {
            Log.e(TAG, "motion: markMotion failed", t)
        }
    }

    // ------------------------------------------------------------ 回退重试（原嵌在追踪器内，2026-09-17 迁出）

    /**
     * glassRetryPending 回退重试：元素背景缺失导致回退系统模糊（冷启动首帧背景未抓到、静止后无新元素
     * 背景 → 仅靠入队抓屏可能磨砂滞留数十秒）时，invalidate 宿主强制重录重试玻璃。
     *
     * [2026-09-17 追踪器移除] 原实现是「追踪器每帧检查 距上次重试 ≥ retryIntervalMs？」，追踪器整体删除
     * 后改由本 Runnable 承担——低频 Handler 循环，退避语义**完全不变**：250ms 起步、每次 ×2、4s 封顶
     *（永久 map miss 的 drawable 回退是 C2 设计行为，低频重试无感知）。
     *
     * 调度：渲染侧 buildLiquidShader 失败置 glassRetryPending 后调 [scheduleGlassRetry]（幂等）。
     * 停跑：执行时 pending 已清（渲染成功 / 别处已重试）→ 不再续排，零空转。
     */
    private val retryInvalidateRunnable = Runnable {
        retryLoopPosted = false
        if (!glassRetryPending) return@Runnable
        glassRetryPending = false
        lastRetryInvalidateAt = SystemClock.uptimeMillis()
        retryIntervalMs = (retryIntervalMs * 2).coerceAtMost(4000L)
        Log.i(TAG, "render: retry pending glass (handler-driven, interval=${retryIntervalMs}ms)")
        invalidateRegisteredHostViews()
    }

    /** 调度一次回退重试（幂等：已排则不重复排；主线程执行 invalidate）。
     *  渲染线程可调——[mainHandler] 是主 Looper 的 Handler，postDelayed 线程安全。 */
    private fun scheduleGlassRetry() {
        if (retryLoopPosted) return
        retryLoopPosted = true
        try {
            mainHandler().postDelayed(retryInvalidateRunnable, retryIntervalMs)
        } catch (t: Throwable) {
            retryLoopPosted = false
            Log.e(TAG, "render: schedule glass retry failed", t)
        }
    }

    // ---- [doc/spec/33 持续抓屏 2026-08-13] 活跃宿主期间周期性整屏快照（动态背景实时刷新） ----

    /**
     * 周期抓屏 Runnable（主线程 Handler postDelayed 自调度循环）。
     *
     * 背景：动态背景（壁纸滚动、动态壁纸、通知实时内容）不触发内容变化检测
     * （registerScreenRegionCore / recordNotificationCardRegion 仅 previous==null 新映射才
     * triggerBackgroundCapture）→ 玻璃背景冻结滞后。本调度每
     * KEY_BG_PERIODIC_CAPTURE_INTERVAL_MS（默认 500）触发一次 triggerBackgroundCapture 重抓整屏快照。
     *
     * [doc/spec/49 2026-08-13] **空闲不更新旧帧**：距上次内容变化信号（markContentChanged）超过
     * PERIODIC_CAPTURE_IDLE_SKIP_MS（1000ms）→ 视为空闲，**跳过抓屏保持旧帧**（内容变化驱动为主，
     * 周期仅作兜底）。权衡：动态壁纸滚动若系统无任何内容变化信号 → 背景滞后（spec/36 已记录，用户
     * 明确要求「空闲不更新」，按此实现）。
     *
     * [2026-08-13 回退 spec/49 场景门控] **收起零抓屏由本 idle-skip 判定保证**：面板收起且无内容变化
     * 信号 → 距上次内容变化 >IDLE_SKIP → 保持旧帧不抓（零 captureDisplay），不再用 isShadeExpanded/
     * hasHeadsUpActiveHost 门控（误拦锁屏，见字段处取舍说明）。锁屏内容变化（onBlurReady/映射写入）仍
     * 立即抓 + 周期兜底，锁屏不被拦。
     *
     * 与现有机制共存：内容变化立即抓（previous==null/事件源/onBlurReady/setExpansionHeight，[2026-08-13
     * 回退 200ms 节流] 默认无节流）保留，周期性是补充（内容变化后 IDLE_SKIP 窗口内兜底刷新）。
     *
     * 功耗：默认 500ms 一次整屏快照（空闲跳过），captureDisplay 在 worker 线程（不阻塞主线程/渲染线程），
     * pending 去重防重复入队；KEY_BG_PERIODIC_CAPTURE_ENABLE=false 恢复纯内容变化触发；**不每帧抓**。
     *
     * 停止条件：宿主全灭（registeredHostViews 无存活引用）→ 不续跑，周期抓屏自动停止
     * （静止零抓屏保持）；功能关闭 → 同上。独立于运动追踪器（2026-09-17 已整体移除）。
     */
    private val periodicCaptureRunnable = Runnable {
        periodicCapturePosted = false
        // [2026-09-17 用户要求·三种瞬态场景强制抓] 本 tick 是否处于「强制抓」场景（决定续跑间隔）
        var forceCaptureScene = false
        try {
            if (isPeriodicCaptureEnabled()) {
                // [spec/62 锁屏耗电优化 2026-08-26] 锁屏跳过：锁屏且无 heads-up 通知、面板未展开 → 无玻璃元素
                // 需要刷新，整屏抓屏无意义（锁屏时钟玻璃化未启用；未来启用时改为「锁屏但时钟玻璃开启才放行」）。
                // 锁屏 heads-up 通知横幅仍放行（通知玻璃需要背景源）。
                if (isKeyguardLockedNow() && !isHeadsUpHostActive() && !panelExpansionActive) {
                    logThrottled("lg-batt-periodic-lock-skip", Log.DEBUG) { "lg-batt: periodic lock-screen skip" }
                } else {
                    // [2026-09-17 三种瞬态场景强制抓] heads-up 横幅 / 流体云展开卡 / simple-banner
                    // 活跃期间，**绕过 idle-skip 无条件抓屏**，并以高频（continuousCaptureIntervalMs =
                    // panel_capture_hz）续跑本 Runnable。
                    //
                    // 为什么必须强制抓：这三种玻璃悬浮在内容之上、底层随时在变，而「内容变化信号」对它们
                    // 不可靠（横幅不走 onBlurReady —— 反编译实证：heads-up 默认走 BackgroundBlurDrawable
                    // 且被 excludeRules 拦，posteffect drawable 不注册）→ 静止期零抓屏 → 玻璃背景冻结。
                    //
                    // ⚠️ **[2026-09-17 需求变更·检查变化]** 抓屏照旧高频（保证底层一变就立刻跟上），
                    // 但**下游更新改为「检查变化」**：captureSnapshotImmediate 里做内容指纹比对，
                    // **内容没变则丢弃本帧、不更新快照、不 postInvalidateHosts** ——
                    // 省掉 invalidate → 重录 → RenderThread 重绘整条链（火焰图实证 RenderThread 占 45%）。
                    // 见 [snapshotContentFingerprint]。
                    //
                    // 非这三种场景仍走下方原 idle-skip（空闲不抓，spec/49 用户原要求，省电）。
                    forceCaptureScene = isHeadsUpHostActive() ||
                        isCardBackgroundHostActive() ||
                        isSimpleBannerHostActive()
                    if (forceCaptureScene) {
                        // 面板自身在渲染时让路（面板有它自己的抓屏通道覆盖），其余一律强制抓
                        if (!isShadeRenderingNow()) {
                            triggerBackgroundCapture(contentChange = false)
                        }
                    } else {
                        val now = SystemClock.uptimeMillis()
                        val idleMs = now - lastContentChangeTimeMs
                        // [spec/62 首次兜底] 内容变化信号从未到来（lastContentChangeTimeMs==0）且已运行超 IDLE_SKIP
                        // → 放行一次抓屏（防「从未收到内容变化信号 → 周期兜底永不抓第一帧」回归）；置时间戳防后续
                        // 重复放行（此后按正常 idle-skip 判定，内容变化会再次 re-arm）。
                        if (lastContentChangeTimeMs == 0L && idleMs > PERIODIC_CAPTURE_IDLE_SKIP_MS) {
                            lastContentChangeTimeMs = now
                            if (!isShadeRenderingNow()) {
                                triggerBackgroundCapture(contentChange = false)
                            }
                        } else if (idleMs > PERIODIC_CAPTURE_IDLE_SKIP_MS) {
                            // [doc/spec/49 2026-08-13] 空闲不更新旧帧（内容变化驱动为主，周期仅兜底）。
                            // [spec/62] 距上次内容变化超过 PERIODIC_CAPTURE_IDLE_SKIP_MS → 跳过抓屏保持旧帧
                            //（零 captureDisplay 成本）。
                            logThrottled("lg-batt-periodic-idle-skip", Log.DEBUG) { "lg-batt: periodic idle-skip (idleMs=$idleMs)" }
                        } else if (!isShadeRenderingNow()) {
                            // [2026-08-14 用户要求·只用于收起] 展开时面板抓屏 120Hz 已实时覆盖 → 周期兜底只在收起
                            // （shade 不在渲染）时按用户配置间隔抓一次（强兜底，sfc 拿不到强制全屏）。
                            triggerBackgroundCapture(contentChange = false)
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "periodic-capture: triggerBackgroundCapture error", t)
        }
        // 续跑（kickPeriodicCapture 内部检查 enabled + 宿主存活；无场景门控）
        // 强制抓场景 → 高频续跑（面板同款速率），保证背景实时跟手
        kickPeriodicCapture(forceCaptureScene)
    }

    /** 启动/续跑周期性抓屏（主线程调用；宿主全灭 / 功能关闭 → 不续跑）。改动即时生效（每周期重读 Prefs）。
     *  [2026-08-13 回退 spec/49 场景门控] 不再按面板展开/heads-up 停周期（误拦锁屏）；「收起零抓屏」由
     *  periodicCaptureRunnable 的 idle-skip 空闲判定保证（内容没变不抓，Runnable 空转成本可忽略）。 */
    private fun kickPeriodicCapture(highRate: Boolean = false) {
        if (!isPeriodicCaptureEnabled()) return
        if (periodicCapturePosted) return
        var hasLive = false
        synchronized(this) {
            for (entry in registeredHostViews) {
                if (entry.ref.get() != null) { hasLive = true; break }
            }
        }
        if (!hasLive) return
        // [2026-09-17 用户要求·三种瞬态场景强制抓] highRate=true（heads-up 横幅 / 流体云展开卡 /
        // simple-banner 活跃）→ 用**面板同款速率**续跑（continuousCaptureIntervalMs，panel_capture_hz，
        // 默认 120Hz），保证这三种悬浮玻璃的背景实时跟手；否则用普通周期间隔
        // （periodic_capture_interval_ms，默认 500ms 兜底）。
        val interval = if (highRate) continuousCaptureIntervalMs() else periodicCaptureIntervalMs().toLong()
        if (interval <= 0L) return
        periodicCapturePosted = true
        try {
            mainHandler().postDelayed(periodicCaptureRunnable, interval)
        } catch (t: Throwable) {
            periodicCapturePosted = false
            Log.e(TAG, "periodic-capture: postDelayed failed", t)
        }
    }

    // [2026-09-17] 追踪器开关读取（isTrackHostMotionEnabled）随追踪器一并删除。

    // ---- [spec/17 配置接线] 抓屏/空置/追踪参数读取（每处独立 try-catch 兜底默认值，改动即时生效；api 在 install 时缓存） ----

    /** 内容变化抓屏最小间隔毫秒（KEY_BG_CAPTURE_MIN_INTERVAL_MS，[2026-08-13 回退 spec/49] 默认
     *  0=无节流立即抓；用户手动配置 >0 仍按最小间隔限频）
     *  [2026-08-13 captureDisplay 开始延迟优化] 远程 Prefs 时间节流缓存（≥CAPTURE_PREFS_CACHE_MS 才重读，
     *  配置改动 ≤1s 生效；scheduleElementCaptures 每次入队原读一次 IPC） */
    private fun bgCaptureMinIntervalMs(): Int {
        val now = SystemClock.uptimeMillis()
        if (now - cachedBgCaptureMinIntervalMsTimeMs < CAPTURE_PREFS_CACHE_MS) return cachedBgCaptureMinIntervalMs
        val a = api ?: return Prefs.DEFAULT_BG_CAPTURE_MIN_INTERVAL_MS
        val v = try {
            Prefs.readIntCompat(Prefs.read(a), Prefs.KEY_BG_CAPTURE_MIN_INTERVAL_MS, Prefs.DEFAULT_BG_CAPTURE_MIN_INTERVAL_MS)
                .coerceAtLeast(0)
        } catch (t: Throwable) {
            Log.w(TAG, "prefs read bg_capture_min_interval_ms failed, default 0", t)
            Prefs.DEFAULT_BG_CAPTURE_MIN_INTERVAL_MS
        }
        cachedBgCaptureMinIntervalMs = v
        cachedBgCaptureMinIntervalMsTimeMs = now
        return v
    }

    /** [2026-08-15 主动式持续抓屏] 主动持续抓屏速率 Hz（KEY_PANEL_CAPTURE_HZ，默认 120，clamp 1~240）。
     *  [2026-08-14 captureDisplay 开始延迟优化] 远程 Prefs 时间节流缓存（≥CAPTURE_PREFS_CACHE_MS 才重读，
     *  配置改动 ≤1s 生效；持续 worker 每 tick 调用，热路径零 IPC）。 */
    private fun panelCaptureHz(): Int {
        val now = SystemClock.uptimeMillis()
        if (now - cachedPanelCaptureHzTimeMs < CAPTURE_PREFS_CACHE_MS) return cachedPanelCaptureHz
        val a = api ?: return Prefs.DEFAULT_PANEL_CAPTURE_HZ
        val v = try {
            Prefs.readIntCompat(Prefs.read(a), Prefs.KEY_PANEL_CAPTURE_HZ, Prefs.DEFAULT_PANEL_CAPTURE_HZ)
                .coerceIn(1, 240)
        } catch (t: Throwable) {
            Log.w(TAG, "prefs read panel_capture_hz failed, default 120", t)
            Prefs.DEFAULT_PANEL_CAPTURE_HZ
        }
        cachedPanelCaptureHz = v
        cachedPanelCaptureHzTimeMs = now
        return v
    }

    /** [2026-08-15 主动式持续抓屏 + 2026-08-14 运动节流] 主动持续抓屏 tick 间隔毫秒：**运动时满速
     *  [panelCaptureHz]（默认 120Hz → ~8ms），静止时减半（120×0.5=60Hz → ~16ms）**——用 [isMotionActive]
     *  运动标志判定（[markMotion] 由面板动画 / 内容变化 / 元素移动（QS 翻页 / setExpansionHeight /
     *  通知卡滑动）置位，复位延迟 300ms）。
     *  间隔 = 1000/有效Hz ms，clamp 4~1000ms（防过快打爆 SF / 过慢背景滞后）。**下拉面板与通知横幅共用
     *  同一套速率**（[startPanelContinuousCapture] / [startHeadsUpContinuousCapture]）。
     *  [2026-08-14 节流语义替换] `KEY_BG_CAPTURE_MIN_INTERVAL_MS`>0 减半逻辑**已移除**（该键只作用于
     *  [scheduleElementCaptures] 的普通内容变化节流，不再驱动持续抓屏速率）——改由运动状态判定。 */
    private fun continuousCaptureIntervalMs(): Long {
        val hz = panelCaptureHz()
        // 运动时满速（panelCaptureHz 默认 120Hz）、静止时减半 60Hz——isMotionActive 为 @Volatile 运动标志
        val effectiveHz = if (isMotionActive) hz else hz / 2
        val clamped = effectiveHz.coerceIn(1, 240)
        return (1000.0 / clamped).toLong().coerceIn(4L, 1000L)
    }

    /** 抓屏降采样比例（KEY_BG_CAPTURE_SCALE，clamp 0.25~1.0，默认 0.5f）
     *  [2026-08-13 captureDisplay 开始延迟优化] 远程 Prefs 时间节流缓存（≥CAPTURE_PREFS_CACHE_MS 才重读，
     *  配置改动 ≤1s 生效；captureElementBackground 每次抓屏原读一次 IPC = captureDisplay 开始前一次非必要延迟） */
    private fun bgCaptureScale(): Float {
        val now = SystemClock.uptimeMillis()
        if (now - cachedBgCaptureScaleTimeMs < CAPTURE_PREFS_CACHE_MS) return cachedBgCaptureScale
        val a = api ?: return Prefs.DEFAULT_BG_CAPTURE_SCALE
        val v = try {
            Prefs.read(a).getFloat(Prefs.KEY_BG_CAPTURE_SCALE, Prefs.DEFAULT_BG_CAPTURE_SCALE)
                .coerceIn(0.25f, 1.0f)
        } catch (t: Throwable) {
            Log.w(TAG, "prefs read bg_capture_scale failed, default 0.5", t)
            Prefs.DEFAULT_BG_CAPTURE_SCALE
        }
        cachedBgCaptureScale = v
        cachedBgCaptureScaleTimeMs = now
        return v
    }

    /** [doc/spec/33] 持续抓屏开关（KEY_BG_PERIODIC_CAPTURE_ENABLE，默认 true=活跃宿主期间持续抓） */
    private fun isPeriodicCaptureEnabled(): Boolean {
        val a = api ?: return Prefs.DEFAULT_BG_PERIODIC_CAPTURE_ENABLE
        return try {
            Prefs.read(a).getBoolean(Prefs.KEY_BG_PERIODIC_CAPTURE_ENABLE, Prefs.DEFAULT_BG_PERIODIC_CAPTURE_ENABLE)
        } catch (t: Throwable) {
            Log.w(TAG, "prefs read bg_periodic_capture_enable failed, default true", t)
            Prefs.DEFAULT_BG_PERIODIC_CAPTURE_ENABLE
        }
    }

    /** [doc/spec/33 + 2026-08-14 只用于收起] 周期兜底抓屏间隔（KEY_BG_PERIODIC_CAPTURE_INTERVAL_MS，
     *  clamp 100~5000，默认 500）。仅收起时（shade 不在渲染）生效——展开由面板抓屏 120Hz 覆盖。
     *  [2026-08-14 用户要求·专用于收起] 不再做收起放大：收起兜底直接按用户配置间隔。 */
    private fun periodicCaptureIntervalMs(): Int {
        val a = api ?: return Prefs.DEFAULT_BG_PERIODIC_CAPTURE_INTERVAL_MS
        return try {
            Prefs.readIntCompat(
                Prefs.read(a), Prefs.KEY_BG_PERIODIC_CAPTURE_INTERVAL_MS, Prefs.DEFAULT_BG_PERIODIC_CAPTURE_INTERVAL_MS
            ).coerceIn(Prefs.BG_PERIODIC_CAPTURE_INTERVAL_MIN_MS, Prefs.BG_PERIODIC_CAPTURE_INTERVAL_MAX_MS)
        } catch (t: Throwable) {
            Log.w(TAG, "prefs read bg_periodic_capture_interval_ms failed, default 500", t)
            Prefs.DEFAULT_BG_PERIODIC_CAPTURE_INTERVAL_MS
        }
    }

    /** 抓屏失败重试间隔毫秒（KEY_BG_CAPTURE_RETRY_MS，默认 320） */
    private fun bgCaptureRetryMs(): Int {
        val a = api ?: return Prefs.DEFAULT_BG_CAPTURE_RETRY_MS
        return try {
            Prefs.readIntCompat(Prefs.read(a),Prefs.KEY_BG_CAPTURE_RETRY_MS, Prefs.DEFAULT_BG_CAPTURE_RETRY_MS)
                .coerceAtLeast(0)
        } catch (t: Throwable) {
            Log.w(TAG, "prefs read bg_capture_retry_ms failed, default 320", t)
            Prefs.DEFAULT_BG_CAPTURE_RETRY_MS
        }
    }

    /** 抓屏失败重试次数上限（KEY_BG_CAPTURE_RETRY_LIMIT，默认 3） */
    private fun bgCaptureRetryLimit(): Int {
        val a = api ?: return Prefs.DEFAULT_BG_CAPTURE_RETRY_LIMIT
        return try {
            Prefs.readIntCompat(Prefs.read(a),Prefs.KEY_BG_CAPTURE_RETRY_LIMIT, Prefs.DEFAULT_BG_CAPTURE_RETRY_LIMIT)
                .coerceAtLeast(1)
        } catch (t: Throwable) {
            Log.w(TAG, "prefs read bg_capture_retry_limit failed, default 3", t)
            Prefs.DEFAULT_BG_CAPTURE_RETRY_LIMIT
        }
    }

    // [2026-09-17] 追踪器三个间隔读取（trackerMin/IdleIntervalFrames、trackerIdleAfterFrames）与
    // trackHostViewMotion 一并删除；其中的 glassRetryPending 重试已迁到 retryInvalidateRunnable。

    /** [2026-08-14 CPU 优化] shade 是否在渲染（面板展开）：最近「shade sfc 有效」抓屏是否在
     *  [CONTINUOUS_CAPTURE_STOP_SFC_STALE_MS] 内。收起时 sfc 长期拿不到（强制全屏，不更新
     *  lastValidSfcCaptureMs）→ false。主线程（periodic）读，worker 写。 */
    private fun isShadeRenderingNow(): Boolean =
        SystemClock.uptimeMillis() - lastValidSfcCaptureMs <= CONTINUOUS_CAPTURE_STOP_SFC_STALE_MS

    // ------------------------------------------------------------ 任务 D：圆角半径解析（问题 10）

    /**
     * 任务 D（问题 10）+ 圆角 fallback 修复：解析元素真实圆角半径。
     *
     * **只用反射正解，不用启发式瞎猜**：反射拿不到可信圆角 → 返回 (0f, "none")
     * → 渲染侧用 0（正方形玻璃），不跳过（任务 H1）。
     *
     * 反射正解（spec/11 通用安全读取顺序）：ViewBlurProxy.getBlurConfig() → BlurConfig 真实 cornerRadius：
     * 1. getCornerRadius()（仅 setCornerRadius 后非 0，覆盖亮度条/QS seekbar）
     * 2. 四角 getter（getLeftTop/RightTop/LeftBottom/RightBottomCornerRadius，构造即设，覆盖通知卡片/音量条）
     * 3. getPathProvider() → RoundRectOutlineProvider.cornerRadius（public 字段，覆盖 QS tile，
     *    已是 OplusQsSmoothRoundUtil 映射后 shader 实际值）
     * 3b. getPathProvider() → BaseRoundRectPathProvider.getMaxRadius()（SmoothRoundRectPathProvider 系，
     *     QsFlashLightBackgroundUtils 手电筒亮度档，spec/18 Bug 1 二级菜单方角）
     * 4. getGradientStrokeCornerParam().getRadius()（CornerParams，覆盖侧滑按钮/MetaBall）
     * 5. 级 6 兜底（spec/18 Bug 1）：五级全 0 且宿主是 seekbar 类 / bounds 长条 → min(w,h)×0.16
     *    （seekbar 专属兜底，非全局启发式；见下方注释）
     * 6. 仍拿不到 → (0f, "none")（正方形玻璃，现有兜底）
     * 值须在 (0, min(w,h)/2] 内才算可信，否则弃用（防 cornerRadius 未 set 的 0 / 异常/尺度不符值）。
     * 各级独立 try-catch，失败/无效自动降级到下一级。
     *
     * @param proxy ViewBlurProxy 实例（recordScreenRegion/recordNotificationCardRegion 反射链已持有）
     * @param bounds posteffect drawable getBounds（元素自身尺寸；级 6 seekbar 长条判定用）
     * @param hostView 映射宿主 View（级 6 seekbar 类名判定用；null 时只走 bounds 长条判定）
     * @return [CornerInfo]（cornerRadius px, 来源 reflect / pathProvider / smoothRoundPathProvider /
     *   cornerParams / seekbar-heuristic / none + CONIC 本体开关 + CONIC 角权重）；radius=0 表示无效
     *   （渲染侧用正方形玻璃）
     */
    /**
     * [doc/spec/24 任务 A1 2026-08-13] 真实圆角半径（反射 BlurConfig 5-6 级降级链）＋**结果缓存**。
     *
     * CPU 热点排序 #4：滑动/移动时每帧 draw → registerScreenRegionCore / recordNotificationCardRegion
     * 每帧调用本方法 → 无缓存时每帧 5-6 级反射（getCornerRadius/四角/pathProvider/getMaxRadius/
     * cornerParams/CONIC type）。blurConfig 不变时圆角稳定（proxy 绑定宿主 View/drawable，角半径仅
     * 构造时设置），故以 identityHashCode(proxy) 为 key 缓存；bounds 值变（元素尺寸变 → maxValid 变 →
     * 结果可能变）→ miss 重算。仅缓存 radius>0 的有效结果（blurConfig 反射失败返回 (0,"none") 不缓存，
     * 防永久方形）；限容 512（同 lastRenderScreenRegion 模式），超限整体清空防内存膨胀。
     */
    private fun resolveCornerRadius(proxy: Any?, bounds: Rect, hostView: View? = null): CornerInfo {
        if (mViewProxyGetBlurConfig == null) return CornerInfo(DEFAULT_CORNER_RADIUS_PX, "default")
        // 缓存命中：seekbar 级 6 兜底按量化 minSide 档位判定（active 拖动时填充高度每帧小幅变化，
        // 量化档位不变 → 命中缓存，避免每帧 5-6 级反射）；其他级（反射真实值）bounds 精确判定
        //（反射值本身不随高度变，bounds 变才重算是合理的）。
        val key = System.identityHashCode(proxy)
        val quantizedMinSide = quantizedSeekbarMinSide(bounds)
        cornerRadiusCache[key]?.let { cached ->
            if (cached.seekbarQuantizedMinSide != null) {
                if (cached.seekbarQuantizedMinSide == quantizedMinSide) return cached.info
            } else if (cached.bounds == bounds) {
                return cached.info
            }
        }
        val info = resolveCornerRadiusUncached(proxy, bounds, hostView)
        // 仅缓存有效结果（radius>0 且非默认兜底）：反射失败 / 默认 30px 回退（source="default"）不缓存，
        // 防锁死默认值——每次 draw 可重试反射，一旦反射恢复立即用真实圆角
        if (info.radius > 0f && info.source != "default") {
            if (cornerRadiusCache.size >= CORNER_CACHE_MAX) cornerRadiusCache.clear()
            // bounds 存副本：调用方 Rect 可能为系统内部缓存实例（getBounds()），后续被 mutate 不影响缓存判定
            cornerRadiusCache[key] = CornerCacheEntry(
                Rect(bounds), info,
                // 级 6 seekbar 兜底（source="seekbar-heuristic"）才量化 minSide；反射真实值保持 bounds 精确判定
                seekbarQuantizedMinSide = if (info.source == "seekbar-heuristic") quantizedMinSide else null,
            )
        }
        return info
    }

    /**
     * [2026-08-13 滑动延迟修复] 级 6 seekbar 兜底 minSide 量化档位：(minSide/STEP)×STEP（px）。
     * active 进度层拖动时填充高度每帧小幅变化，量化后档位不变 → resolveCornerRadius 缓存命中，
     * 消除每帧 5-6 级反射（CPU 热点 #4）。档位 = [SEEKBAR_CORNER_QUANT_STEP]px：半径误差上限
     * 16×0.16 ≈ 2.6px，视觉可忽略。仅用于级 6 兜底缓存判定（反射真实值不走量化）。
     */
    private fun quantizedSeekbarMinSide(bounds: Rect): Int {
        val minSide = Math.min(bounds.width(), bounds.height()).toFloat()
        if (minSide <= 0f) return 0
        return (minSide / SEEKBAR_CORNER_QUANT_STEP).toInt() * SEEKBAR_CORNER_QUANT_STEP
    }

    /** [2026-08-13 滑动延迟修复] seekbar 级 6 兜底缓存量化步长（px）。 */
    private const val SEEKBAR_CORNER_QUANT_STEP = 16

    /** 无缓存的原始解析（5-6 级降级链 + seekbar 启发式兜底；仅由 [resolveCornerRadius] 调用）。 */
    private fun resolveCornerRadiusUncached(proxy: Any?, bounds: Rect, hostView: View? = null): CornerInfo {
        if (mViewProxyGetBlurConfig == null) return CornerInfo(DEFAULT_CORNER_RADIUS_PX, "default")
        try {
            val blurConfig = mViewProxyGetBlurConfig?.invoke(proxy)
            if (blurConfig != null) {
                val maxValid = Math.min(bounds.width(), bounds.height()) / 2f
                var src = "reflect"
                // 级 1：getCornerRadius()（亮度条/QS seekbar 走了 setCornerRadius，恒非 0）
                var r = (mBlurConfigGetCornerRadius?.invoke(blurConfig) as? Number)?.toFloat() ?: 0f
                // 级 2：四角 getter（通知卡片/音量条：构造即设四角；取首个非零）
                if (r <= 0f) {
                    r = firstNonZeroRadius(
                        mBlurConfigGetLeftTopCornerRadius?.invoke(blurConfig),
                        mBlurConfigGetRightTopCornerRadius?.invoke(blurConfig),
                        mBlurConfigGetLeftBottomCornerRadius?.invoke(blurConfig),
                        mBlurConfigGetRightBottomCornerRadius?.invoke(blurConfig),
                    )
                }
                // 级 3：getPathProvider() → RoundRectOutlineProvider.cornerRadius（QS tile，已映射后值）
                if (r <= 0f) {
                    val pp = mBlurConfigGetPathProvider?.invoke(blurConfig)
                    if (pp != null && mRoundRectOutlineProviderClass?.isInstance(pp) == true) {
                        val ppR = mRoundRectOutlineProviderCornerRadiusField
                            ?.let { f -> try { (f.get(pp) as? Number)?.toFloat() ?: 0f } catch (t: Throwable) { 0f } }
                            ?: 0f
                        if (ppR > 0f) { r = ppR; src = "pathProvider" }
                    }
                }
                // 级 3b：getPathProvider() → BaseRoundRectPathProvider.getMaxRadius()
                //（SmoothRoundRectPathProvider 系，QsFlashLightBackgroundUtils 手电筒亮度档——只 set
                //  pathProvider 不 set cornerRadius，spec/18 Bug 1 二级菜单方角；反射失败/非该类跳过）
                if (r <= 0f) {
                    val pp2 = mBlurConfigGetPathProvider?.invoke(blurConfig)
                    if (pp2 != null && mBaseRoundRectPathProviderClass?.isInstance(pp2) == true) {
                        val ppR2 = try {
                            (mBaseRoundRectPathProviderGetMaxRadius?.invoke(pp2) as? Number)?.toFloat() ?: 0f
                        } catch (t: Throwable) { 0f }
                        if (ppR2 > 0f) { r = ppR2; src = "smoothRoundPathProvider" }
                    }
                }
                // 级 3c（spec/22）：getPathProvider() 类名含 OvalOutlineProvider/OvalRectOutlineProvider
                //（控制中心 WiFi/蓝牙桥内层 icon_bg 圆形 icon 磁贴 = OvalOutlineProvider，42dp；1x1 分离式
                //  tile 圆形外层同为 OvalOutlineProvider——继承 CornerOutlineProvider 非 RoundRectOutlineProvider，
                //  级 3/3b isInstance 均 miss → 方角）。圆角 = min(w,h)/2（正圆/胶囊 = 短边/2，与
                //  OvalOutlineProvider.getCornerRadius 返回 bounds.height()/2 一致）。类名 contains 双判断
                // 兜住两个 provider（"OvalRectOutlineProvider" 不含子串 "OvalOutlineProvider"，必须分列）。
                if (r <= 0f) {
                    val pp3 = mBlurConfigGetPathProvider?.invoke(blurConfig)
                    if (pp3 != null) {
                        val clsName = pp3.javaClass.name
                        if (clsName.contains("OvalOutlineProvider") || clsName.contains("OvalRectOutlineProvider")) {
                            val ovalR = Math.min(bounds.width(), bounds.height()) / 2f
                            if (ovalR > 0f) { r = ovalR; src = "ovalProvider" }
                        }
                    }
                }
                // 级 4：getGradientStrokeCornerParam().getRadius()（侧滑按钮/MetaBall）
                if (r <= 0f) {
                    val cp = mBlurConfigGetGradientStrokeCornerParam?.invoke(blurConfig)
                    if (cp != null && mCornerParamsGetRadius != null) {
                        val cpR = try {
                            (mCornerParamsGetRadius?.invoke(cp) as? Number)?.toFloat() ?: 0f
                        } catch (t: Throwable) { 0f }
                        if (cpR > 0f) { r = cpR; src = "cornerParams" }
                    }
                }
                if (r > 0f && r <= maxValid + 1f) {
                    // [spec/18 圆角过渡区深挖修正] 级 4b：CONIC 本体判定（通知卡/custom 卡/QS seekbar 的
                    // BlurConfig.gradientStrokeCornerParam.type=CONIC（CornerParams(int) 默认 i 偶数 → CONIC）
                    // → 本体用三次贝塞尔 sdBezierDistance + shader 内 1.5×半径；pathProvider 级（QS tile
                    // 弧线，系统 setPathProvider 覆盖 corner 为 RBox 圆弧）强制 isConic=false 保持圆弧。
                    // [spec/22] ovalProvider 级同理强制 isConic=false：正圆/胶囊须用 RBox 圆弧
                    //（radius=min/2 → 精确圆），CONIC 三次贝塞尔 cc=1.5×min/2 > halfSize 会破坏 SDF 定义域。
                    return if (src != "pathProvider" && src != "smoothRoundPathProvider" && src != "ovalProvider") {
                        val (c, w) = cornerConicInfo(blurConfig, r)
                        CornerInfo(r, src, c, w)
                    } else {
                        CornerInfo(r, src)
                    }
                }
                // 级 6（spec/18 Bug 1 真机方角兜底，2026-08-13 用户要求修）：
                // 控制中心 QS seekbar 的 **Active 进度 drawable**（QsSeekBarBlurManager.getSeekBarActiveDrawable
                // :47-55 反编译实证）BlurConfig 构造后全程**无 setCornerRadius/setLeftTop/setPathProvider** →
                // 五级反射恒 0 → 正方形玻璃（同一条 seekbar 背景 drawable getSeekBarBackground:57-77 有
                // setCornerRadius(f) → 背景圆、进度方）。此兜底是 **seekbar 专属（非全局启发式）**：
                // 宿主是 seekbar 类（OplusQsVerticalSeekBar/COUIVerticalSeekBar/OplusVolumeSeekBar，
                // 反编译类名实证）或 bounds 为长条（min/max < 0.5，覆盖横/竖向细条）时，取
                // min(w,h)×0.16（spec/09 实证 seekbar 圆角 20dp≈min 边×0.16，亮度/音量条宽 66dp/46dp）。
                // spec/11 "不用启发式"只针对非 seekbar 元素的全局猜测，此处是方角 bug 的有界兜底。
                val minSide = Math.min(bounds.width(), bounds.height()).toFloat()
                val maxSide = Math.max(bounds.width(), bounds.height()).toFloat()
                val hostName = hostView?.javaClass?.name.orEmpty()
                val isSeekbarHost = hostName.contains("OplusQsVerticalSeekBar") ||
                    hostName.contains("COUIVerticalSeekBar") ||
                    hostName.contains("OplusVolumeSeekBar")
                val isLongStrip = maxSide > 0f && minSide / maxSide < 0.5f
                if (minSide > 0f && (isSeekbarHost || isLongStrip)) {
                    val r6 = minSide * 0.16f
                    if (r6 > 0f && r6 <= maxValid + 1f) {
                        val (c, w) = cornerConicInfo(blurConfig, r6)
                        return CornerInfo(r6, "seekbar-heuristic", c, w)
                    }
                }
                // [2026-08-13 日志节流] 该日志在每帧 draw 的 resolveCornerRadius 路径上（方角元素每帧刷），
                // 节流聚合；级 6 命中后 seekbar 不再走这里，其他无效元素也最多 2s 一条
                logThrottled("corner-radius-invalid", Log.DEBUG) { "map: BlurConfig cornerRadius invalid r=$r maxValid=$maxValid, no valid cornerRadius (use square)" }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "map: resolve BlurConfig cornerRadius failed, no valid cornerRadius (use square)", t)
        }
        return CornerInfo(DEFAULT_CORNER_RADIUS_PX, "default")
    }

    /** CONIC 本体判定结果（resolveCornerRadius 返回）：radius + 来源 + CONIC 开关 + CONIC 角权重。 */
    private data class CornerInfo(
        val radius: Float,
        val source: String,
        val isConic: Boolean = false,
        val weight: Float = DEFAULT_CORNER_WEIGHT,
    )

    /** [任务 A1] resolveCornerRadius 缓存条目：bounds 快照（副本）+ 解析结果。 */
    private data class CornerCacheEntry(
        val bounds: Rect,
        val info: CornerInfo,
        /** [2026-08-13 滑动延迟修复] 级 6 seekbar 兜底的量化 minSide 档位（(minSide/16)×16）；
         *  非 null = 缓存命中按量化档位判定（active 拖动高度小幅变化 ±16px 内命中，避免每帧反射）；
         *  null = 反射真实值，按 bounds 精确判定。 */
        val seekbarQuantizedMinSide: Int? = null,
    )

    /** [任务 A1] resolveCornerRadius 缓存：key=identityHashCode(proxy)；仅缓存 radius>0 有效结果。 */
    private val cornerRadiusCache = ConcurrentHashMap<Int, CornerCacheEntry>()

    /** [任务 A1] 圆角缓存容量上限（同 lastRenderScreenRegion 512 模式），超限整体清空防内存膨胀。 */
    private const val CORNER_CACHE_MAX = 512

    /**
     * [spec/18 圆角过渡区深挖修正 + 方角根因修复] CONIC 本体判定，两个来源（任一命中即 CONIC）：
     *  分支 A：反射 BlurConfig.getGradientStrokeCornerParam() → CornerParams.getType()==CornerType.CONIC
     *    （通知卡/custom 卡/QS seekbar：BlurConfig 默认 gradientStrokeCornerParam = new CornerParams(int)
     *    i 偶数 → CONIC；spec/11 反编译实证）→ isConic=true，weight=原始 getWeight() 经
     *    CornerParamsKt fMax=(8·w)/(3·w+3) 变换后值（系统 shader u_weight 就是变换后值；卡 weight=1.1 → 1.3968）。
     *  分支 B（[spec/18 方角根因] 系统 setSmoothCorner 强制 CONIC）：BaseDrawable.setSmoothCorner
     *    （c16.1 :1028-1045/:1064-1080）内部无条件 `setCornerParams(new CornerParams(CornerType.CONIC,
     *    maxRadius, weight))` → 系统 shader u_corner=radius×1.5（CONIC），即使 gradientStrokeCornerParam
     *    被 GradientStrokeLineAdapter.adaptGradientStrokeParams（isSupportStroke=false →
     *    clearGradientStrokeParams :200-206）清成 FULL（type=FULL, radius=0）——seekbar base / QS 详情
     *    背景 / 媒体卡片（QsDetailBackgroundUtils → LightStyleStrokeDrawable）即此情形。判定：有效半径>0
     *    && getPathProvider()==null && BlurConfig.getRadiusWeight() 非 null（setRadiusWeight 在
     *    adaptGradientStrokeParams 前写入，clear 不清它）→ isConic 强制 true，weight 用 radiusWeight
     *    原值经同公式换算（1.1 → 1.3968）。
     *  反射失败 / 非 CONIC / radiusWeight=null → 圆弧兜底（不崩）。
     *  不误伤：pathProvider 元素（QS tile 弧线）getPathProvider()!=null → 不进分支 B 保持圆弧；
     *  ovalProvider 同理；seekbar active 层（radiusWeight=null）不进。
     * @param radius resolveCornerRadius 前级已解析的有效半径（>0 才可能走分支 B）
     * @return (isConic, 变换后 weight)；weight 恒 >0（DEFAULT_CORNER_WEIGHT 兜底）
     */
    private fun cornerConicInfo(blurConfig: Any?, radius: Float): Pair<Boolean, Float> {
        return try {
            // 分支 A：CornerParams.getType()==CONIC（通知卡/custom 卡默认 CONIC；spec/18）
            if (mCornerParamsGetType != null && mCornerTypeConic != null) {
                val cp = mBlurConfigGetGradientStrokeCornerParam?.invoke(blurConfig)
                if (cp != null && mCornerParamsGetType?.invoke(cp) == mCornerTypeConic) {
                    val w = (mCornerParamsGetWeight?.invoke(cp) as? Number)?.toFloat() ?: 0f
                    val weight = if (w > 0f) (8f * w) / (3f * w + 3f) else DEFAULT_CORNER_WEIGHT
                    return true to weight
                }
            }
            // [spec/18 方角根因修复] 分支 B：系统 setSmoothCorner 路径强制 CONIC（见函数 KDoc）
            if (radius > 0f && mBlurConfigGetPathProvider?.invoke(blurConfig) == null) {
                val rw = mBlurConfigGetRadiusWeight?.invoke(blurConfig) as? Number
                val rwf = rw?.toFloat()
                if (rwf != null && rwf > 0f) {
                    return true to ((8f * rwf) / (3f * rwf + 3f))
                }
            }
            false to DEFAULT_CORNER_WEIGHT
        } catch (t: Throwable) {
            Log.e(TAG, "map: resolve CornerParams getType/getWeight failed, conic 判定降级圆弧", t)
            false to DEFAULT_CORNER_WEIGHT
        }
    }

    /** 反射调用结果中取首个 >0 的半径（四角 getter 级；invoke 异常按 0 处理，跳过该角）。 */
    private fun firstNonZeroRadius(vararg values: Any?): Float {
        for (v in values) {
            val r = (v as? Number)?.toFloat() ?: 0f
            if (r > 0f) return r
        }
        return 0f
    }

    /**
     * 侧滑按钮真实圆角：反射 SystemUI resources 读 R.dimen.notification_corner_radius（16dp）。
     * 与 [resolveCornerRadius] 不同，**跳过 BlurConfig 反射**——那拿的是卡片/面板圆角（60-84px），
     * 与侧滑按钮真实圆角不符（按钮实际 240x262，圆角 = notification_corner_radius，16dp → px）。
     * [Bug 3] 真机实锤：编译期 `com.android.systemui.R$dimen` 字段反射偶发失败 → id=0 → 直接返回 0
     * → shader 走直角掩码 → 侧滑按钮硬角。三级兜底：
     *  级 1：编译期 R.dimen id（已解析时）getDimensionPixelSize；
     *  级 2：反射 NotificationMenuRowMetaBallController2.getBlurMenuCornerRadius()（spec/09：系统读
     *        notification_corner_radius 做按钮圆角的正解，私有实例方法，new 实例 + setAccessible 调用）；
     *  级 3：运行时 resources.getIdentifier 查 dimen id（不依赖编译期 R 类反射）。
     * 全失败 → 0（正方形玻璃，强兜底不崩）。
     */
    private fun resolveSwipeCornerRadius(hostView: View): Pair<Float, String> {
        // 级 1：编译期 R.dimen 反射解析的 id（真机该路径偶发失败 → id=0 走级 2/3）
        if (mNotificationCornerRadiusDimenId != 0) {
            val r = try {
                hostView.resources.getDimensionPixelSize(mNotificationCornerRadiusDimenId).toFloat()
            } catch (t: Throwable) {
                Log.e(TAG, "swipe: resolve notification_corner_radius dimen failed", t)
                0f
            }
            if (r > 0f) return r to "dimen"
        }
        // 级 2：反射 NotificationMenuRowMetaBallController2.getBlurMenuCornerRadius()
        val r2 = try {
            val inst = mBlurMenuCornerRadiusCtor?.newInstance(hostView.context)
            val v = mBlurMenuCornerRadiusMethod?.invoke(inst)
            (v as? Number)?.toFloat() ?: 0f
        } catch (t: Throwable) {
            Log.e(TAG, "swipe: getBlurMenuCornerRadius reflect failed", t)
            0f
        }
        if (r2 > 0f) return r2 to "getBlurMenuCornerRadius"
        // 级 3：运行时 getIdentifier 查 dimen id（不依赖编译期 R 类反射，类路径/字段名问题兜底）
        val r3 = try {
            val id = hostView.resources.getIdentifier(
                "notification_corner_radius", "dimen", hostView.context.packageName
            )
            if (id != 0) hostView.resources.getDimensionPixelSize(id).toFloat() else 0f
        } catch (t: Throwable) {
            Log.e(TAG, "swipe: getIdentifier notification_corner_radius failed", t)
            0f
        }
        if (r3 > 0f) return r3 to "getIdentifier"
        return 0f to "none"
    }

    /**
     * 计算侧滑按钮掩码矩形（viewport 局部坐标：xy=左上, zw=宽高）。
     *
     * MetaBallBlurDrawable.setBounds（反编译 line 263-277）blendMode==0 时左右各扩
     * boundsExtension（= BLEND_COLOR_RANGE(55)+blendRange，真机 69px）→ viewport = 扩张后尺寸
     * （真机 378x262），按钮实际区域（hostView 尺寸 240x262）在 viewport 内居中。掩码 = 该实际区域，
     * 扩张区之外 shader 输出 alpha=0（对齐系统 shader `outputCol *= mix_c * shape` 裁剪语义，
     * 系统不胖我们也不胖）。尺寸非法 → null（无裁剪，强兜底）。
     * 对称扩张公式对 blendMode==0（左右扩）/1（上下扩）均成立。
     */
    private fun computeSwipeMaskRect(bounds: Rect, hostView: View): RectF? {
        val vpW = bounds.width().toFloat()
        val vpH = bounds.height().toFloat()
        val hostW = hostView.width.toFloat()
        val hostH = hostView.height.toFloat()
        if (vpW <= 0f || vpH <= 0f || hostW <= 0f || hostH <= 0f) return null
        val maskLeft = ((vpW - hostW) / 2f).coerceAtLeast(0f)
        val maskTop = ((vpH - hostH) / 2f).coerceAtLeast(0f)
        val maskW = minOf(vpW, hostW)
        val maskH = minOf(vpH, hostH)
        return RectF(maskLeft, maskTop, maskLeft + maskW, maskTop + maskH)
    }

    // ------------------------------------------------------------ Hook 0：onBlurReady（面板活动信号）

    /**
     * Hook BlurDrawableManager.onBlurReady(List, HardwareBuffer, int, float)（16.1 line 935，主线程）。
     * [2026-08-13 用户决定] BlurService 端已全移除（不再注入未模糊原图），onBlurReady 收到的是系统
     * 模糊结果 buffer（不能作为玻璃源）。本 hook 仅保留「面板活动信号」：新帧到达 = shade 内容变化
     * → 确保运动追踪器在跑 + 触发整屏快照重抓（内容变化入口，无节流立即见 triggerBackgroundCapture）。
     * **不再 publishFrame 写全局帧缓存**
     * （避免空引用 + 浪费），**不 close hw**（引用归系统管理：BlurBufferInfo/BlurImageManager 持有
     * 或系统 close）。
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
                        captureOriginalFrame(chain)
                    } catch (t: Throwable) {
                        Log.e(TAG, "onBlurReady: intercept error", t)
                    }
                    chain.proceed()
                }
            Log.i(TAG, "Hook B mounted: $CLASS_BLUR_DRAWABLE_MANAGER#$METHOD_ON_BLUR_READY(List, HardwareBuffer, int, float)")
        } catch (t: Throwable) {
            Log.e(TAG, "Hook B mount FAILED: $CLASS_BLUR_DRAWABLE_MANAGER#$METHOD_ON_BLUR_READY", t)
        }
    }

    /** hookBefore：[2026-08-13] 不再读取/发布 buffer——BlurService 端已全移除，onBlurReady 收到的是
     *  系统模糊结果（不能当玻璃源）。仅作「面板活动信号」：新帧到达 = shade 内容变化 → 确保运动
     *  追踪器在跑 + 触发整屏快照重抓（内容变化入口，无节流立即；快照缺失 → 渲染异步请求，失败回退系统模糊）。 */
    private fun captureOriginalFrame(chain: XposedInterface.Chain) {
        // [spec/62 锁屏耗电优化 2026-08-26] 锁屏门控：锁屏无玻璃元素，onBlurReady 驱动的抓屏无意义 → 直接
        // 返回（锁屏无玻璃元素，抓屏无意义）。保留 panelExpansionActive/headsUp
        // 逃生通道（审查加固）：锁屏仍可能展开面板/弹横幅（背景需刷新），且 context 反射失败保守 true 时
        // 避免永久阻断 onBlurReady 抓屏。未来恢复锁屏时钟玻璃化时改为「锁屏但时钟玻璃开启才放行」。
        if (isKeyguardLockedNow() && !panelExpansionActive && !isHeadsUpHostActive()) {
            logThrottled("lg-batt-onblur-lock-skip", Log.DEBUG) { "lg-batt: onBlurReady lock-screen skip" }
            return
        }
        // [2026-08-14 排障·横幅静止刷新慢] onBlurReady 到达本 hook 的触发日志：过滤 lg-hook
        // 看系统重模糊回调是否实时到达（对比静止期 contentChange=true 是否缺失）
        diagHookLog("onBlurReady")
        // [2026-08-14 用户方案·onBlurReady 驱动抓屏] 系统重模糊 = 底层内容变化（视频播放/动态壁纸
        // 每帧重模糊）→ 每次 onBlurReady 都触发抓屏（用户确认无需节流，onBlurReady 频率本身可控）。
        // 静态底层（系统不重模糊）→ onBlurReady 停 → 零抓屏。替代移除的主动持续抓屏 120Hz tick。
        triggerBackgroundCapture()
    }

    // ------------------------------------------------------------ [M1] 统一注册入口：addBlurDrawable

    /**
     * [M1 · 方案 A 第一阶段] 统一注册入口 hook：ContinuousBlurDrawable.addBlurDrawable()。
     *
     * 反编译实证（16.1 SystemUI，ContinuousBlurDrawable.java line 129-134）：所有 SystemUI 模糊 drawable
     * （scrim/tile/通知卡/胶囊/媒体横幅/侧滑按钮）统一经此注册进 BlurDrawableManager，一次拿到
     * drawable 实例（= 渲染侧 drawBlurShader 的 this）+ drawableId + surfaceControl（→层名）+ params。
     *
     * 增量落地：只登记 BlurSceneRegistry 供渲染侧场景判定（黑名单层回退系统模糊），
     * **不删除/不改现有宿主 hook**。始终 proceed（不拦截注册），失败只禁用 registry，不影响现有。
     */
    private fun mountAddBlurDrawable(api: XposedInterface, classLoader: ClassLoader) {
        try {
            val clazz = Class.forName(CLASS_CONTINUOUS_BLUR_DRAWABLE, false, classLoader)
            val method = clazz.getMethod(METHOD_ADD_BLUR_DRAWABLE)
            // 反射方法缓存（失败 null 兜底：registerScene 跳过对应字段，不阻塞注册）
            val getDrawableId = try { clazz.getMethod(METHOD_GET_DRAWABLE_ID) } catch (t: Throwable) { null }
            val getSurfaceControl = try { clazz.getMethod(METHOD_GET_SURFACE_CONTROL) } catch (t: Throwable) { null }
            val getDrawingParam = try { clazz.getMethod(METHOD_GET_DRAWING_PARAM) } catch (t: Throwable) { null }
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        val drawable = chain.getThisObject()
                        if (drawable != null) {
                            registerScene(drawable, getDrawableId, getSurfaceControl, getDrawingParam)
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "addBlurDrawable: intercept error", t)
                    }
                    chain.proceed()
                }
            Log.i(TAG, "Hook B mounted: $CLASS_CONTINUOUS_BLUR_DRAWABLE#$METHOD_ADD_BLUR_DRAWABLE() (BlurSceneRegistry)")
        } catch (t: Throwable) {
            Log.e(TAG, "Hook B mount FAILED: $CLASS_CONTINUOUS_BLUR_DRAWABLE#$METHOD_ADD_BLUR_DRAWABLE (registry disabled, system blur kept)", t)
        }
    }

    /**
     * [M1] 从 drawable 反射提取 drawableId / 层名（SurfaceControl.getName()）/ params，
     * 登记到 BlurSceneRegistry。任一反射失败 → 对应字段 null/默认，**不阻塞注册**（层名缺失时
     * isGlassable 保守 true，沿用现有行为）。
     */
    private fun registerScene(
        drawable: Any,
        getDrawableId: Method?,
        getSurfaceControl: Method?,
        getDrawingParam: Method?,
    ) {
        var drawableId = -1
        var layerName: String? = null
        var params: Any? = null
        if (getDrawableId != null) {
            try {
                drawableId = (getDrawableId.invoke(drawable) as? Number)?.toInt() ?: -1
            } catch (t: Throwable) {
                // drawableId 失败 → 保持 -1（仅诊断用，不影响场景判定）
            }
        }
        if (getSurfaceControl != null) {
            try {
                val sfc = getSurfaceControl.invoke(drawable)
                if (sfc is SurfaceControl) layerName = reflectSurfaceControlName(sfc)
            } catch (t: Throwable) {
                // 层名失败 → null（isGlassable 保守 true）
            }
        }
        if (getDrawingParam != null) {
            try {
                params = getDrawingParam.invoke(drawable)
            } catch (t: Throwable) {
                // params 失败 → null（当前只存档不消费）
            }
        }
        BlurSceneRegistry.register(drawable, drawableId, layerName, params)
        logThrottled("registry-register-$drawableId", Log.INFO) {
            "registry: registered id=$drawableId layer=$layerName drawable=${drawable.javaClass.simpleName}"
        }
    }

    // ------------------------------------------------------------ Hook 1：drawBlurShader（主绘制）

    private fun mountDrawBlurShader(api: XposedInterface, classLoader: ClassLoader) {
        try {
            val clazz = Class.forName(CLASS_BASE_DRAWABLE, false, classLoader)
            val method = clazz.getDeclaredMethod(METHOD_DRAW_BLUR_SHADER, Canvas::class.java)
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val drawable = try {
                        chain.getThisObject()
                    } catch (t: Throwable) {
                        Log.e(TAG, "drawBlurShader: getThisObject failed", t); null
                    }
                    if (drawable != null) {
                        try {
                            replaceShaderIfPossible(drawable)
                        } catch (t: Throwable) {
                            Log.e(TAG, "render: drawBlurShader intercept error", t)
                        }
                    }
                    chain.proceed()
                }
            Log.i(TAG, "Hook B mounted: $CLASS_BASE_DRAWABLE#$METHOD_DRAW_BLUR_SHADER(Canvas)")
        } catch (t: Throwable) {
            Log.e(TAG, "Hook B mount FAILED: $CLASS_BASE_DRAWABLE#$METHOD_DRAW_BLUR_SHADER", t)
        }
    }

    // ------------------------------------------------------------ Hook 2：onDrawContent（shader 不可用兜底）

    private fun mountOnDrawContent(api: XposedInterface, classLoader: ClassLoader) {
        // 同时挂 BlendDrawable（面板/tile 主绘制，16.1）与 BaseDrawable（基类空实现兜底）
        for (clsName in arrayOf(CLASS_BLEND_DRAWABLE, CLASS_BASE_DRAWABLE)) {
            try {
                val clazz = Class.forName(clsName, false, classLoader)
                val method = clazz.getDeclaredMethod(METHOD_ON_DRAW_CONTENT, Canvas::class.java)
                api.hook(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val drawable = try {
                            chain.getThisObject()
                        } catch (t: Throwable) {
                            Log.e(TAG, "onDrawContent: getThisObject failed", t); null
                        }
                        val canvas = try {
                            chain.getArg(0) as? Canvas
                        } catch (t: Throwable) {
                            Log.e(TAG, "onDrawContent: getArg(0) failed", t); null
                        }
                        var handled = false
                        if (drawable != null && canvas != null) {
                            try {
                                handled = tryRenderOnDrawContent(drawable, canvas)
                            } catch (t: Throwable) {
                                Log.e(TAG, "onDrawContent: intercept error", t)
                            }
                        }
                        if (!handled) chain.proceed()
                    }
                Log.i(TAG, "Hook B mounted: $clsName#$METHOD_ON_DRAW_CONTENT(Canvas)")
            } catch (t: Throwable) {
                Log.e(TAG, "Hook B mount FAILED: $clsName#$METHOD_ON_DRAW_CONTENT", t)
            }
        }
    }

    /**
     * onDrawContent 兜底：仅当系统 shader 路径不可用（将走 drawBitmapContent 位图平铺）时接管。
     * 系统 shader 可用时返回 false，让系统 proceed → 内部走 drawBlurShader → Hook 1 替换。
     */
    private fun tryRenderOnDrawContent(drawable: Any, canvas: Canvas): Boolean {
        val drawableShader = try {
            mGetDrawableShader?.invoke(drawable)
        } catch (t: Throwable) {
            Log.e(TAG, "onDrawContent: getDrawableShader failed", t); null
        }
        if (drawableShader != null) {
            val sysShader = try {
                mGetShaderOrNull?.invoke(drawableShader)
            } catch (t: Throwable) {
                Log.e(TAG, "onDrawContent: getShaderOrNull failed", t); null
            }
            // [修4 2026-08-12] 系统 onDrawContent 三分支（BlendDrawable.java:52-65）：enableShader=false 时
            // 即使 sysShader 非 null 也走 drawBitmapContent（画模糊位图+foregroundBlend 色层）→ 磨砂。
            // 必须同时判 getEnableShader()，false 时接管画玻璃。反射失败默认 true（维持原放行语义）。
            val enableShader = try {
                (mGetEnableShader?.invoke(drawable) as? Boolean) ?: true
            } catch (t: Throwable) {
                Log.e(TAG, "onDrawContent: getEnableShader failed, assume true", t); true
            }
            if (sysShader != null && enableShader) return false  // 系统走 drawBlurShader，Hook 1 处理
        }
        // 系统 shader 不可用 → 接管：替换 shader 并手动画液态玻璃 path
        if (!replaceShaderIfPossible(drawable)) return false
        return try {
            mDrawBlurShader?.invoke(drawable, canvas)  // 触发 Hook 1（幂等跳过重建），画液态玻璃
            // 模拟系统 onDrawContent 结尾的 getPaint().reset()，防 paint 脏状态残留
            try {
                (mGetPaint?.invoke(drawable) as? Paint)?.reset()
            } catch (ignored: Throwable) {
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "onDrawContent: drawBlurShader invoke failed", t)
            false
        }
    }

    // ------------------------------------------------------------ 核心：替换 shader（强兜底）

    /**
     * 把 drawableShaderPaint 的 shader 替换为液态玻璃 RuntimeShader。
     *
     * 强兜底契约：**任何失败都不动 paint**（保持系统原样），返回 false → 调用方回退系统绘制。
     * 返回值 true 仅代表 paint.shader 已替换为液态玻璃（后续由系统 drawPath 完成实际绘制）。
     */
    private fun replaceShaderIfPossible(drawable: Any): Boolean {
        val id = System.identityHashCode(drawable)

        // [M1 方案 A] 场景判定（增量补充，registry 只是补充判定，现有逻辑为默认）：
        // 黑名单层（音量/关机/全局操作等非玻璃场景）→ 不动 paint 回退系统模糊；
        // 白名单/未知 → 继续现有液态玻璃逻辑。未注册 drawable isGlassable 保守 true。
        if (!BlurSceneRegistry.isGlassable(drawable)) {
            logRenderMode(id, "BLUR", "registry blacklist: non-glass scene", drawable)
            return false
        }

        val paint = try {
            mGetDrawableShaderPaint?.invoke(drawable) as? Paint
        } catch (t: Throwable) {
            Log.e(TAG, "render: getDrawableShaderPaint failed", t); null
        }
        if (paint == null) {
            logRenderMode(id, "BLUR", "getDrawableShaderPaint null", drawable)
            return false
        }

        // [spec/48 方案 A] 背景 scrim 兜底：AutoBlurDrawable.draw 拦截未覆盖的路径（背景 drawable
        // 不经 AutoBlurDrawable.draw 直接 drawBlurShader 等）时，把 shader paint 置纯黑 tint
        // （paint.shader=null + 纯黑）→ drawPath 画黑遮罩，不套玻璃也不走系统模糊。alpha =
        // behindScrimAlpha×maskAlpha（behindScrimAlpha 由 hook setViewAlpha 捕获系统动画逐帧值）。
        // mask 关 → paint 透明（不叠黑）。
        if (isBackgroundScrimById(drawable)) {
            try {
                // [spec/48 过渡修复 2026-08-13] 不再读 Drawable.getAlpha()（AutoBlurDrawable.setAlpha
                // 空实现恒 255 → 无过渡）→ 读 hook setViewAlpha 捕获的 behindScrimAlpha（系统动画逐帧值）。
                val drawableAlpha = behindScrimAlpha
                val maskPaint = scrimMaskPaintFor(drawableAlpha)
                paint.shader = null
                paint.color = maskPaint?.color ?: android.graphics.Color.TRANSPARENT
                logThrottled("bg-scrim-mask2", Log.INFO) { "bg: black tint mask (drawBlurShader fallback), drawableAlpha=$drawableAlpha maskAlpha=$maskAlpha id=${System.identityHashCode(drawable)}" }
            } catch (t: Throwable) {
                Log.e(TAG, "bg: scrim black tint paint failed", t)
            }
            return true
        }

        // 读当前 paint.shader（用于幂等刷新判断；不构成"整体短路"）
        val current = try {
            paint.shader
        } catch (t: Throwable) {
            null
        }

        // 1) bounds 校验（viewport 必须非空，否则 shader 无法渲染）
        val bounds = try {
            mGetBounds?.invoke(drawable) as? Rect
        } catch (t: Throwable) {
            Log.e(TAG, "render: getBounds failed", t); null
        }
        if (bounds == null || bounds.width() <= 0 || bounds.height() <= 0) {
            logRenderMode(id, "BLUR", "empty bounds", drawable, bounds)
            return false
        }

        // [R1 修复] paint.shader 已是本模块缓存的液态玻璃实例 → 不再整体短路：
        // 每帧重跑 uniform（uSource 跟随背景源最新帧、uViewport/uSourceRect 跟随
        // drawable 当前尺寸/位置），任一变化才真正重设；无变化直接返回 true（保持已替换状态）。
        if (current is RuntimeShader && shaderCache.containsValue(current)) {
            logRenderMode(id, "GLASS")
            return refreshShaderUniforms(paint, current, drawable, bounds)
        }

        // 2) 构造液态玻璃 shader（编译/反射/uniform/坐标映射任何异常或缺失 → 回退，不动 paint）
        val liquid = try {
            buildLiquidShader(drawable, bounds) ?: run {
                // [症状 2] map miss 或背景源缺失（元素背景未抓到且全局帧缓存无帧）：挂重试标记 +
                // 排一次低频退避重试（新帧/背景源到达时 worker 的 postInvalidateHosts 会更快重试）
                glassRetryPending = true
                scheduleGlassRetry()
                logRenderMode(id, "BLUR", "no map or source", drawable, bounds)
                return false
            }
        } catch (t: Throwable) {
            glassRetryPending = true
            scheduleGlassRetry()
            logRenderMode(id, "BLUR", "buildLiquidShader exception", drawable, bounds)
            Log.w(TAG, "render: buildLiquidShader exception (id=$id)", t)
            return false
        }

        // 4) 替换 shader（异常时恢复原 shader，保证系统绘制不脏）
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
            logRenderMode(id, "BLUR", "setShader failed", drawable, bounds)
            Log.w(TAG, "render: setShader failed (id=$id)", t)
            return false
        }
        logRenderMode(id, "GLASS")
        if (!replacedOnce) {
            replacedOnce = true
            Log.i(TAG, "render: first liquid glass replace, bounds=$bounds")
        }
        return true
    }

    // ------------------------------------------------------------ [doc/spec/31] 算遮罩并行化：后台几何/材质计算

    /**
     * [doc/spec/31 并行化] 后台线程计算的单元素「uniform 输入」结果。
     *
     * [doc/spec/32 origin 实时跟] **srcRect 已移出后台**：srcRect 依赖宿主屏幕 loc（滑动时 RenderNode
     * 平移不重录 → 注册 region 旧 loc 不跟 → 叠影），必须主线程每次 draw 实时 getLocationOnScreen 折算。
     * 后台只算**不依赖 loc** 的部分：几何（viewport/maskRect/offset/corner）+ 材质（buildParams）+
     * realRegionOffset（realRegion 相对 loc 的形状，主线程 loc + 形状 = 绝对屏幕区域）。
     * 主线程 drawBlurShader 读此结果 + 实时 srcRect → [LiquidGlassShader.setUniforms]。
     *
     * @param bounds 计算输入 bounds 快照（主线程校验 `cached.bounds == 当前 bounds` 值相等才用，过期即重算）
     * @param frame 恒 0（无全局帧缓存，源变化靠 sourceBitmapId，BlurService 端全移除）
     * @param viewport uViewport：真实绘制尺寸（局部坐标 0..realW × 0..realH）
     * @param maskRect 有效掩码矩形（null=全视口无裁剪）
     * @param maskCornerRadius 掩码圆角 px（isMaskRedundant 判定冗余时 0 → shader O(1) 直角路径）
     * @param maskOffsetX / maskOffsetY MetaBall 固有扩张偏移（uMaskOffset，真实大小绘制）
     * @param cornerIsConic / cornerWeight / cornerRadius / cornerConicBlend 本体角 uniform 输入
     * @param params buildParams 材质参数结果（折射/反射/高光/色散/vibrancy 等 Prefs 派生，1s 节流缓存）
     * @param isElementSource 渲染源类型（整屏快照恒 true；主线程低清分支判断用）
     * @param hostViewName 宿主 view 名（仅 coord 诊断日志用）
     * @param realRegionOffset realRegion 相对宿主 loc 的形状（真实卡片区域，含 MetaBall 扩张内缩；
     *  主线程实时 loc + 此形状 = 绝对屏幕区域 → 容器变换 → srcRect 折算基准。不依赖 loc，后台算）
     */
    private class ComputedGeometry(
        val bounds: Rect,
        val frame: Long,
        val viewport: RectF,
        val maskRect: RectF?,
        val maskCornerRadius: Float,
        val maskOffsetX: Float,
        val maskOffsetY: Float,
        val cornerIsConic: Boolean,
        val cornerWeight: Float,
        val cornerRadius: Float,
        val cornerConicBlend: Float,
        val params: LiquidGlassShader.Params,
        val isElementSource: Boolean,
        val hostViewName: String,
        val realRegionOffset: RectF,
        /** [spec/35 黑遮罩 / spec/41 作用范围修正] 该元素应叠加的黑遮罩不透明度
         *  （0=不叠加；SystemUI 下拉所有玻璃元素统一值，不再按 hostViewName 关键词判定） */
        val maskAlpha: Float,
    )

    /** [doc/spec/31] 后台计算结果缓存（每元素 id → 完整 uniform 输入）；主线程查缓存 → setUniforms。 */
    private val geometryCache = ConcurrentHashMap<Int, ComputedGeometry>()

    /** [doc/spec/31] 同 id in-flight 后台计算任务集合（提交去重，防高频滑动帧任务堆积）。 */
    private val geometryComputingIds = ConcurrentHashMap.newKeySet<Int>()

    /** [doc/spec/31] 后台几何/材质计算线程池（固定 2 线程；SystemUI 常驻，任务纯读并发容器 + @Volatile 无锁）。
     *  线程名 lg-geometry-N，daemon（不阻碍进程退出）。模块生命周期同 SystemUI，无需显式 shutdown。 */
    private val geometryPool = Executors.newFixedThreadPool(2) { r -> Thread(r, "lg-geometry").apply { isDaemon = true } }

    /**
     * [doc/spec/31 并行化] 提交单元素后台计算任务（主线程调用，非阻塞）。
     * - bounds 未变（缓存条目 bounds 快照 == 当前）→ 跳过（已有最新结果，无需重算）
     * - 同 id 已有 in-flight 任务 → 跳过（防任务堆积：滑动每帧提交只首次入队）
     * 后台任务纯读（entry/快照/containerTransforms 并发容器 + @Volatile），computeGeometry 内部 try-catch
     * 失败返回 null 不写缓存，异常绝不让主线程感知（主线程永远走缓存/回退旧逻辑）。
     */
    private fun submitGeometryCompute(id: Int, bounds: Rect) {
        try {
            val cached = geometryCache[id]
            if (cached != null && cached.bounds == bounds) return  // 已有同 bounds 最新结果
            if (!geometryComputingIds.add(id)) return              // 同 id in-flight 去重
            val taskBounds = Rect(bounds)  // 快照拷贝：系统 getBounds 返回内部可变 Rect，主线程后续可能复用
            try {
                geometryPool.execute {
                    try {
                        val cg = computeGeometry(id, taskBounds)
                        if (cg != null) geometryCache[id] = cg  // 原子替换写缓存
                    } catch (t: Throwable) {
                        Log.e(TAG, "geometry: background compute failed id=$id", t)
                    } finally {
                        geometryComputingIds.remove(id)
                    }
                }
            } catch (t: Throwable) {
                geometryComputingIds.remove(id)
                Log.e(TAG, "geometry: submit task failed id=$id", t)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "geometry: submit check failed id=$id", t)
        }
    }

    /**
     * [R1] 已替换的液态玻璃实例每帧刷新 uniform。
     *
     * [doc/spec/31 并行化] 本函数已改为**薄壳**：几何/材质计算（computeRealGeometry / isMaskRedundant /
     * buildParams）全部在后台线程池 [computeGeometry] 完成，结果存 [geometryCache]；主线程只 getBounds
     * → 查缓存（bounds 匹配）→ [applyComputedGeometry] 做 **srcRect 实时折算（spec/32 origin 实时跟）**
     * + changed 检测 + setUniforms（RuntimeShader 必须渲染线程）。未命中缓存 → 提交后台重算 + 回退同步算
     * （首次帧保正确性，一帧滞后可接受）。
     *
     * 不重建 RuntimeShader（避免重编译），只重跑 setUniforms；uSource 输入跟随整屏快照最新实例
     * ——快照换新 Bitmap 后必须重新 createSourceBitmapShader + setInputShader（旧 BitmapShader
     * 仍绑定旧快照 → 玻璃内容冻结）。[2026-08-13] 无全局 onBlurReady 帧缓存（BlurService 端全移除），
     * 源变化判断靠 sourceBitmapId（identityHashCode）；frame 恒 0。
     * viewport（drawable getBounds 自身尺寸）/ srcRect 任一变化同样重设。
     * [2026-08-13 优化] srcRect 变化不再重建 source shader：createSourceBitmapShader 是纯单位矩阵
     * CLAMP（无 localMatrix），srcRect 完全由 uSourceRect uniform 控制——滑动时仅 srcRect 变
     * （sourceBitmap 未变）只走 setUniforms 更新 uSourceRect/uSourceLowResRect，避免每帧新建
     * BitmapShader + setInputShader 的 CPU 峰值（doc/spec/18 问题 8）。
     *
     * 返回 true = shader 仍保持液态玻璃（即使刷新失败也不破坏现有绘制，强兜底）。
     */
    private fun refreshShaderUniforms(
        paint: Paint,
        shader: RuntimeShader,
        drawable: Any,
        bounds: Rect,
    ): Boolean {
        try {
            val id = System.identityHashCode(drawable)
            // [doc/spec/31 并行化] 几何/材质计算已在后台线程池完成（computeGeometry）。主线程只做三件事：
            // getBounds（上层已反射）→ 查结果缓存（bounds 匹配）→ setUniforms（RuntimeShader 必须渲染线程）。
            // 命中 → 只 applyComputedGeometry；未命中 → 提交后台重算 + 回退同步算（首次/缓存未就绪帧保正确性，
            // 一帧滞后可接受）。任何失败 → 保持现有液态玻璃实例（强兜底，渲染输出不变）。
            val cached = geometryCache[id]
            if (cached != null && cached.bounds == bounds) {
                return applyComputedGeometry(paint, shader, id, cached, bounds)
            }
            // 缓存未命中/过期（bounds 变）：提交后台重算（同 id in-flight 去重防任务堆积）
            submitGeometryCompute(id, bounds)
            // 回退：同步算（保首次渲染与缓存未就绪帧的正确性；结果写缓存预热，下帧命中走缓存路径）
            val cg = computeGeometry(id, Rect(bounds))
            if (cg == null) {
                // 纯 map miss（无 entry / bounds 兜底未命中）或快照缺失 → 保持现有液态玻璃实例
                //（C4 已删，容忍 srcRect 滞后；下帧 map/快照就绪后自动恢复跟随）
                logThrottled("refresh-map-miss", Log.WARN) { "render: map miss on refresh, keep previous liquid glass uniforms (id=$id)" }
                return true
            }
            return applyComputedGeometry(paint, shader, id, cg, bounds)
        } catch (t: Throwable) {
            Log.e(TAG, "render: refresh uniform failed, keep existing liquid glass shader", t)
            return true   // 保持现有液态玻璃实例，不让刷新异常破坏绘制
        }
    }

    /**
     * [doc/spec/31 并行化] 主线程应用后台计算结果：只做 **srcRect 实时折算（spec/32）** + changed 检测 +
     * setUniforms（RuntimeShader 必须渲染线程）。与原 refreshShaderUniforms 的 setUniforms 段行为完全一致
     * （轻量分支 srcRect-only / viewport+srcRect-only / 全量 setUniforms），仅几何/材质值改由 [cg]（后台
     * computeGeometry 结果）提供，**srcRect 改由主线程实时算**（依赖 loc，必须主线程；见 [resolveLiveSrcRect]）。
     * 源位图换新（快照新实例）时主线程用【当前】快照重建 source shader 绑定（srcRect 折算比例恒定，数值仍有效）。
     *
     * @return true = shader 仍保持液态玻璃（失败也不破坏现有绘制，强兜底）。
     */
    private fun applyComputedGeometry(
        paint: Paint,
        shader: RuntimeShader,
        id: Int,
        cg: ComputedGeometry,
        bounds: Rect,
    ): Boolean {
        try {
            // [doc/spec/32 origin 实时跟] srcRect 主线程实时折算：hostView getLocationOnScreen 实时 loc
            // → region → 容器缩放变换（重影 spec/29）→ 整屏快照坐标。消除注册 region 旧 loc 的滑动滞后
            // （滑动叠影"多张合在一起"根因，同款）。快照缺失（null）→ 保持上次 uniform（下帧
            // 快照就绪自动恢复），不破坏现有绘制。
            val srcRect = resolveLiveSrcRect(id, lookupScreenRegion(id, bounds), bounds, cg) ?: return true
            val applied = appliedUniformsMap[id]
            val frame = cg.frame
            // 源变化判断用主线程【当前】快照实例 id（后台计算后快照可能换新 → 立即重建绑定新图，玻璃内容不冻结）
            val currentBitmap = screenSnapshot?.bitmap
            val sourceBitmapId = if (currentBitmap != null) System.identityHashCode(currentBitmap) else 0
            val viewport = cg.viewport
            // [2026-08-13 优化] srcRect 已从 sourceChanged 拆出：滑动时 srcRect 每帧变但源位图未变，
            // 只重设 uniform（无需重建 BitmapShader / setInputShader）。只有快照换新 Bitmap 才重建。
            val sourceBitmapChanged = applied == null ||
                applied.frame != frame ||
                applied.sourceBitmapId != sourceBitmapId
            // uViewport（元素尺寸）变化 → 重设材质 uniform（setUniforms 在 source==null 时仍更新 uViewport）
            val viewportChanged = applied == null || applied.viewport != viewport
            // 掩码区域变化（侧滑按钮动效期间按钮尺寸/扩张区变化）→ 重设掩码 uniform
            val maskChanged = applied == null || applied.maskRect != cg.maskRect
            // srcRect 变化（滑动跟手）→ 重设 uSourceRect uniform；源位图未变时不重建 shader
            val srcRectChanged = applied == null || applied.srcRect != srcRect
            // [spec/18 修正] corner 变化（圆角反射 0→非0 / CONIC 开关 / weight）→ 重设 uCorner* uniform
            val cornerChanged = applied == null ||
                applied.cornerIsConic != cg.cornerIsConic ||
                applied.cornerWeight != cg.cornerWeight ||
                applied.cornerRadius != cg.cornerRadius ||
                // [2026-08-13] cornerConicBlend 恒 1（角形状恒 CONIC），该比对恒 false，保留作防御
                (cg.cornerIsConic && applied.cornerConicBlend != cg.cornerConicBlend)
            // [spec/35 黑遮罩 / spec/41 作用范围修正] 遮罩 uniform 变化检测：SystemUI 下拉所有元素
            // 统一值，配置/首次应用变化 → 走全量 setUniforms 重设 uMaskAlpha（禁用轻量分支）
            val maskOverlayChanged = applied == null || applied.maskAlpha != cg.maskAlpha
            // [2026-09-16 doc/spec/67] 材质参数指纹变化（任何材质滑杆改动）→ 必须走全量 setUniforms 重设。
            // 否则材质改动被 `if (!uniformsChanged) return true` 吃掉，直到几何变化才生效。
            val materialChanged = applied == null || applied.materialHash != cg.params.hashCode()
            val uniformsChanged = sourceBitmapChanged || viewportChanged || maskChanged || srcRectChanged || cornerChanged ||
                // [spec/35 黑遮罩 / spec/41 作用范围修正] 遮罩统一值变化（配置/首次应用）→ 走全量重设
                maskOverlayChanged ||
                materialChanged

            if (!uniformsChanged) return true

            val sourceShader = if (sourceBitmapChanged && currentBitmap != null) {
                LiquidGlassShader.createSourceBitmapShader(currentBitmap)
            } else {
                null
            }
            // 源位图未变（仅 srcRect 变）→ lowResShader=null：uSourceLowRes 保留上次绑定（与 sourceShader
            // 同实例，同一坐标空间），setUniforms 仍更新 uSourceRect/uSourceLowResRect → 跟手无需重建。
            val lowResShader: Shader?
            val lowResSrcRect: RectF
            if (sourceBitmapChanged) {
                if (cg.isElementSource && currentBitmap != null) {
                    lowResShader = sourceShader
                    lowResSrcRect = srcRect
                } else if (currentBitmap != null) {
                    val lowResResult = buildLowResShader(currentBitmap, srcRect)
                    lowResShader = lowResResult?.shader
                    lowResSrcRect = lowResResult?.lowResSrcRect ?: srcRect
                } else {
                    lowResShader = null
                    lowResSrcRect = srcRect
                }
            } else {
                lowResShader = null
                lowResSrcRect = srcRect
            }

            // [doc/spec/24 任务 A2 2026-08-13] 轻量分支 1：仅 srcRect 变化（滑动跟手）→ 只重设
            // uSourceRect/uSourceLowResRect（2 个 uniform），跳过全量 setUniforms。
            if (srcRectChanged && !sourceBitmapChanged && !viewportChanged && !maskChanged && !cornerChanged && !maskOverlayChanged) {
                LiquidGlassShader.setSourceRectOnly(shader, srcRect, lowResSrcRect)
                appliedUniformsMap[id] = applied!!.copy(srcRect = srcRect)
                // [coord 诊断] 与全量路径同一计数（每 64 次 + 距上次 ≥2s 打一条，验证 srcRect 跟手）
                refreshUniformCount++
                val coordRefreshNow = SystemClock.uptimeMillis()
                if (moduleLogEnabled && refreshUniformCount % 64L == 0L && coordRefreshNow - lastRefreshCoordLogAt >= THROTTLE_LOG_INTERVAL_MS) {
                    lastRefreshCoordLogAt = coordRefreshNow
                    Log.i(
                        TAG,
                        "coord: refresh id=$id view=${cg.hostViewName} frame=$frame " +
                            "srcRect=$srcRect viewport=$viewport " +
                            "(srcRect-only uniform #$refreshUniformCount)"
                    )
                }
                return true
            }

            // [2026-08-13 滑动延迟修复] 轻量分支 2：srcRect+viewport 变（拖动时 resize 的控件）→
            // 重设 uViewport + uSourceRect/uSourceLowResRect（3~4 个 uniform），跳过全量 setUniforms。
            if (srcRectChanged && !sourceBitmapChanged && !maskChanged && !cornerChanged && !maskOverlayChanged) {
                LiquidGlassShader.setViewportAndSourceRect(shader, viewport, srcRect, lowResSrcRect, cg.maskRect)
                appliedUniformsMap[id] = applied!!.copy(viewport = viewport, srcRect = srcRect)
                refreshUniformCount++
                val coordRefreshNow = SystemClock.uptimeMillis()
                if (moduleLogEnabled && refreshUniformCount % 64L == 0L && coordRefreshNow - lastRefreshCoordLogAt >= THROTTLE_LOG_INTERVAL_MS) {
                    lastRefreshCoordLogAt = coordRefreshNow
                    Log.i(
                        TAG,
                        "coord: refresh id=$id view=${cg.hostViewName} frame=$frame " +
                            "srcRect=$srcRect viewport=$viewport " +
                            "(viewport+srcRect-only uniform #$refreshUniformCount)"
                    )
                }
                return true
            }

            // 全量：材质参数（cg.params，后台 buildParams 结果）已在后台算好，主线程只传值 setUniforms
            LiquidGlassShader.setUniforms(
                shader, cg.params, sourceShader, lowResShader,
                sourceRect = srcRect,
                lowResSourceRect = lowResSrcRect,
                debugCoord = 0f,
                maskRect = cg.maskRect,
                maskCornerRadius = cg.maskCornerRadius,
                // [2026-08-13] 本体角 CONIC↔圆弧渐变权重（恒 1，角形状恒 CONIC）
                cornerConicBlend = cg.cornerConicBlend,
                // [任务 A] 真实大小绘制：viewport 已是真实尺寸，几何/采样坐标减扩张偏移对齐真实卡片
                maskOffsetX = cg.maskOffsetX,
                maskOffsetY = cg.maskOffsetY,
                // [spec/35 黑遮罩 / spec/41 作用范围修正] SystemUI 下拉所有玻璃元素统一传 uMaskAlpha
                //（= 配置值；uMaskAlpha=0 即遮罩关闭时 shader 内分支零开销跳过）
                maskAlpha = cg.maskAlpha,
            )
            appliedUniformsMap[id] = AppliedKey(
                frame, sourceBitmapId, viewport, srcRect, cg.maskRect,
                cornerIsConic = cg.cornerIsConic, cornerWeight = cg.cornerWeight, cornerRadius = cg.cornerRadius,
                cornerConicBlend = cg.cornerConicBlend,
                maskAlpha = cg.maskAlpha,
                materialHash = cg.params.hashCode(),
            )
            // [coord 诊断] refresh 重设 uniform 节流日志（每 64 次 + 距上次 ≥2s 打一条）
            refreshUniformCount++
            val coordRefreshNow = SystemClock.uptimeMillis()
            if (moduleLogEnabled && refreshUniformCount % 64L == 0L && coordRefreshNow - lastRefreshCoordLogAt >= THROTTLE_LOG_INTERVAL_MS) {
                lastRefreshCoordLogAt = coordRefreshNow
                Log.i(
                    TAG,
                    "coord: refresh id=$id view=${cg.hostViewName} frame=$frame " +
                        "srcRect=$srcRect viewport=$viewport " +
                        "(uniform reset #$refreshUniformCount)"
                )
            }
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "render: apply computed uniforms failed, keep existing liquid glass shader", t)
            return true   // 保持现有液态玻璃实例，不让刷新异常破坏绘制
        }
    }

    /**
     * [doc/spec/32 origin 实时跟] 主线程实时折算 srcRect（同款）：
     * - hostView 存活：getLocationOnScreen 实时读 → 实时真实区域 = loc + [ComputedGeometry.realRegionOffset]
     *   （形状）→ 容器缩放变换（重影 spec/29，应用在实时 region 上）→ 整屏快照坐标折算。
     * - hostView 弱引用已回收：回退注册 region + [reconcileRegion] 对齐本帧 bounds（旧逻辑，静止可接受）。
     * - 快照缺失 → 异步请求抓屏 + 返回 null（保持上次 uniform，下帧快照就绪自动恢复）。
     * 主线程/渲染线程调用（getLocationOnScreen 必须主线程）。
     */
    private fun resolveLiveSrcRect(id: Int, entry: RegionEntry?, bounds: Rect, cg: ComputedGeometry): RectF? {
        val hostView = entry?.hostViewRef?.get()
        val renderRegion: RectF
        if (hostView != null) {
            val loc = IntArray(2)
            hostView.getLocationOnScreen(loc)
            // cg.realRegionOffset = realRegion 相对 loc 的形状（含 MetaBall 扩张内缩）；loc + 形状 = 绝对屏幕区域
            val realRegion = RectF(
                loc[0] + cg.realRegionOffset.left,
                loc[1] + cg.realRegionOffset.top,
                loc[0] + cg.realRegionOffset.right,
                loc[1] + cg.realRegionOffset.bottom,
            )
            renderRegion = applyContainerTransform(realRegion, hostView)
        } else {
            if (entry == null) return null
            // hostView 弱引用已回收 → 回退注册 region + bounds delta 对齐（旧逻辑兜底，位置静止可接受）
            renderRegion = applyContainerTransform(reconcileRegion(entry, bounds), null)
        }
        return resolveSrcRectFromRegion(renderRegion)
    }

    /**
     * [doc/spec/32] region → 整屏快照坐标折算（复用 [resolveRenderSource] 的 srcRect 公式，主线程实时算）。
     * 快照缺失 → 异步请求抓屏（不阻塞渲染线程）+ null（保持上次 uniform，下帧恢复）。
     */
    private fun resolveSrcRectFromRegion(renderRegion: RectF): RectF? {
        val snap = screenSnapshot
        if (snap == null || snap.bitmap.width <= 0 || snap.bitmap.height <= 0 || snap.region.width() <= 0f) {
            requestScreenSnapshotAsync()
            return null
        }
        val scaleX = snap.bitmap.width.toFloat() / snap.region.width()
        val scaleY = snap.bitmap.height.toFloat() / snap.region.height()
        return RectF(
            (renderRegion.left - snap.region.left) * scaleX,
            (renderRegion.top - snap.region.top) * scaleY,
            (renderRegion.right - snap.region.left) * scaleX,
            (renderRegion.bottom - snap.region.top) * scaleY,
        )
    }

    /**
     * [doc/spec/31 并行化 + spec/32] 后台纯函数：计算单元素**不依赖 loc** 的 uniform 输入（几何 viewport/
     * mask/offset/corner + 材质 buildParams + realRegionOffset 形状），**不碰 RuntimeShader / paint /
     * setUniforms**（那些必须渲染线程）。**srcRect 已移主线程实时折算**（[resolveLiveSrcRect]，依赖 loc
     * 不能后台，见 doc/spec/32）。输入 entry（ConcurrentHashMap）/ containerTransforms（ConcurrentHashMap）
     * 均并发安全读；内部全 try-catch，失败返回 null（主线程用上次/回退旧逻辑，后台异常绝不上抛破坏主线程）。
     *
     * @param id drawable identityHashCode
     * @param bounds 主线程 getBounds 反射值快照（不可变副本；调用方保证非空且 w/h>0）
     * @return 几何+材质 uniform 输入；map miss / 任何异常 → null
     */
    private fun computeGeometry(id: Int, bounds: Rect): ComputedGeometry? {
        try {
            val entry = lookupScreenRegion(id, bounds)
            if (entry == null || entry.region.width() <= 0f || entry.region.height() <= 0f) return null
            // [doc/spec/32 origin 实时跟] screenRegion 不再用注册 region loc（滑动 RenderNode 平移不重录
            // → 旧 loc 不跟 → srcRect 停旧位 → 叠影），改由主线程 drawBlurShader 每帧 getLocationOnScreen
            // 实时构造。后台只算**形状**（相对 loc 的偏移）：screenRegion = loc + bounds → 形状即 bounds 自身，
            // computeRealGeometry 输出 realRegion = 相对 loc 的真实卡片区域形状（主线程 loc + 形状 = 绝对区域）。
            val screenRegionShape = RectF(
                bounds.left.toFloat(), bounds.top.toFloat(),
                bounds.right.toFloat(), bounds.bottom.toFloat(),
            )
            val geo = computeRealGeometry(bounds, entry, screenRegionShape)
            val viewport = geo.viewport
            // [任务 A] 真实大小掩码圆角：仅当检测到扩张（geo.offset>0）时，掩码才需要圆角匹配 body 几何
            //（swipe 用 entry.maskCornerRadius raw；通知卡等扩张元素用 entry.cornerRadius，与本体 CONIC
            //  同值 → 掩码与 body 几何一致，不切边）。无扩张 → entry.maskCornerRadius。
            val effMaskCornerRadius = if (geo.offsetX > 0f || geo.offsetY > 0f) {
                if (entry.maskCornerRadius > 0f) entry.maskCornerRadius else entry.cornerRadius
            } else {
                entry.maskCornerRadius
            }
            // [spec/24 修复方向 1] 掩码与本体 CONIC 几何全等 → 传 maskCornerRadius=0（shader 掩码走 O(1)
            // 直角路径，shapeAlpha 已兜底圆角外裁剪；零视觉变化，通知卡 CONIC 成本减半）
            val maskCornerRadius = if (isMaskRedundant(geo, entry, effMaskCornerRadius)) 0f else effMaskCornerRadius
            // [2026-08-13 用户决定·角形状恒 CONIC] 本体角 CONIC 分支恒保持，cornerConicBlend 恒 1
            //（shader 恒 CONIC，对齐系统 spec/12）。非 CONIC 元素（uCornerIsConic=0）blend 忽略。
            val effectiveCornerIsConic = entry.cornerIsConic
            val cornerConicBlend = if (effectiveCornerIsConic) this.cornerConicBlend else 1f
            val cornerRadius = if (entry.cornerRadius > 0f) entry.cornerRadius else 0f
            // [用户补充 2026-08-13] buildParams（材质参数 14 项 Prefs 派生：vibrancy/falloff/refraction/
            // highlight/depth/dispersion 等）一并后台算；readMaterialParams 1s 节流缓存 @Volatile 线程安全。
            val params = buildParams(viewport.width(), viewport.height(), cornerRadius, effectiveCornerIsConic, entry.cornerWeight)
            return ComputedGeometry(
                bounds = Rect(bounds),
                frame = 0L,  // [2026-08-13] 无全局帧概念（BlurService 端全移除）；源变化靠 sourceBitmapId
                viewport = viewport,
                maskRect = geo.maskRect,
                maskCornerRadius = maskCornerRadius,
                maskOffsetX = geo.offsetX,
                maskOffsetY = geo.offsetY,
                cornerIsConic = effectiveCornerIsConic,
                cornerWeight = entry.cornerWeight,
                cornerRadius = cornerRadius,
                cornerConicBlend = cornerConicBlend,
                params = params,
                isElementSource = true,  // 整屏快照源恒 true（doc/spec/07 + 2026-08-13 组合方案）
                hostViewName = entry.hostViewName,
                // [doc/spec/32] realRegion 相对 loc 的形状（含 MetaBall 扩张内缩）；srcRect 折算基准，
                // 主线程 loc + 形状 = 绝对屏幕区域 → 容器变换 → srcRect 实时折算
                realRegionOffset = geo.realRegion,
                // [spec/35 黑遮罩 / spec/41 作用范围修正] SystemUI 下拉所有玻璃元素统一遮罩值
                //（后台线程读 install 缓存字段，线程安全；不再按宿主 View 名关键词判定）
                maskAlpha = maskAlphaFor(entry.hostViewName),
            )
        } catch (t: Throwable) {
            Log.e(TAG, "geometry: compute failed id=$id bounds=$bounds", t)
            return null
        }
    }

    // ------------------------------------------------------------ 构建液态玻璃 shader

    private fun buildLiquidShader(drawable: Any, bounds: Rect): LiquidShaderBuild? {
        val id = System.identityHashCode(drawable)
        // 每个 drawable 只映射【自己屏幕位置对应的原图区域】——查 AutoBlurDrawable.draw hook
        // 建立的「drawable(identityHashCode) → 屏幕区域(全屏像素)」映射。映射缺失 → fallback 系统模糊。
        // [R3] 查表走 lookupScreenRegion：主键命中优先，主键 miss 时按 bounds 宽高 + 屏幕位置兜底匹配
        //（存表键=AutoBlurDrawable 解析出的实例，查表键=drawBlurShader 的 this，两链路不保证同实例）。
        val entry = lookupScreenRegion(id, bounds) ?: return null
        // [doc/spec/32 origin 实时跟] 首次构建也实时读 loc：形状用 bounds（相对 loc），绝对区域由主线程
        // 实时 getLocationOnScreen 构造；替代注册 region 旧 loc（滑动 RenderNode 平移不重录 → 旧 loc 不跟
        // → srcRect 停旧位 → 叠影，同款修复）。无扩张时形状即 bounds；有扩张时内缩扩张量。
        val shape = RectF(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat())
        if (shape.width() <= 0f || shape.height() <= 0f) return null
        // [任务 A] 几何解析（形状空间，不依赖 loc）：viewport/maskRect/offset 均与 loc 无关；
        // realRegion = 相对 loc 的真实卡片区域形状（主线程 loc + 形状 = 绝对区域）
        val geo = computeRealGeometry(bounds, entry, shape)
        // [R2] viewport = 真实绘制尺寸（元素自身尺寸；无扩张 = drawable getBounds 宽高）。符合已验证契约：
        // coord=元素局部坐标、uViewport=元素尺寸（不再用 canvas clipBounds 当 viewport——
        // RenderNode 录制 canvas 的 clipBounds 可能是整面板裁剪区或带非零偏移）。
        val viewport = geo.viewport
        // [doc/spec/32] 实时渲染区域：loc + realRegion 形状 → 容器缩放变换（重影 spec/29，应用在实时 region）
        val hostView = entry.hostViewRef?.get()
        val liveRegion: RectF = if (hostView != null) {
            val loc = IntArray(2)
            hostView.getLocationOnScreen(loc)
            applyContainerTransform(
                RectF(
                    loc[0] + geo.realRegion.left,
                    loc[1] + geo.realRegion.top,
                    loc[0] + geo.realRegion.right,
                    loc[1] + geo.realRegion.bottom,
                ),
                hostView,
            )
        } else {
            // hostView 弱引用已回收 → 回退注册 region + bounds delta 对齐（旧逻辑兜底，静止可接受）
            applyContainerTransform(reconcileRegion(entry, bounds), null)
        }
        // [整屏快照背景源 2026-08-13] 渲染源 = 共享整屏快照（0.5 降采样），srcRect = 元素【真实区域】
        //（实时 liveRegion）折算到快照坐标（移动实时跟手）；快照缺失 → null 回退系统模糊
        val renderSource = resolveRenderSource(id, liveRegion) ?: return null
        val srcRect = renderSource.srcRect
        val source = renderSource.source
        // [任务 A] 真实大小掩码圆角：仅当检测到扩张（geo.offset>0）时，掩码才需要圆角匹配 body 几何
        //（swipe 用 entry.maskCornerRadius raw 值，shader 侧 CONIC ×1.5；通知卡等扩张元素用
        //  entry.cornerRadius，与本体 CONIC 同值 → 掩码与 body 几何一致，不切边）。
        // 无扩张 → entry.maskCornerRadius（非 swipe 恒 0 → 掩码直角全视口，零变化，防回归）。
        val effMaskCornerRadius = if (geo.offsetX > 0f || geo.offsetY > 0f) {
            if (entry.maskCornerRadius > 0f) entry.maskCornerRadius else entry.cornerRadius
        } else {
            entry.maskCornerRadius
        }
        // [spec/24 修复方向 1] 掩码与本体 CONIC 几何全等 → maskCornerRadius=0（掩码走 O(1) 直角，shapeAlpha 兜底）
        val maskCornerRadius = if (isMaskRedundant(geo, entry, effMaskCornerRadius)) 0f else effMaskCornerRadius
        // [2026-08-13 用户决定·角形状恒 CONIC] 本体角 CONIC 分支恒保持，cornerConicBlend 恒 1
        //（shader 恒 CONIC；滑动降级 + 过渡动画已废弃——两种角切换 = 边缘闪根因，只保留一种即不闪）
        val effectiveCornerIsConic = entry.cornerIsConic
        val cornerConicBlend = if (effectiveCornerIsConic) this.cornerConicBlend else 1f
        val region = RenderRegion(
            id = id,
            screenRegion = liveRegion,
            srcRect = srcRect,
            viewport = viewport,
            hostViewName = entry.hostViewName,
            // [任务 H1] 圆角：反射拿到的真实圆角用它；拿不到 → 0（正方形玻璃），不 skip
            cornerRadius = if (entry.cornerRadius > 0f) entry.cornerRadius else 0f,
            cornerRadiusSource = entry.cornerRadiusSource,
            // [spec/18 修正] CONIC 本体角开关 + 权重（透传 RegionEntry）
            cornerIsConic = entry.cornerIsConic,
            cornerWeight = entry.cornerWeight,
            // [任务 A] 掩码 = 真实卡片区域（raw coord 空间，与 body 几何同形同位置）；无扩张 = entry.maskRect
            //（仅 swipe 设置）。扩张区之外 alpha=0，但不再"切边"——掩码形状即玻璃本体形状。
            maskRect = geo.maskRect,
            maskCornerRadius = maskCornerRadius,
        )
        // [歧义修复] 记录本次成功渲染的屏幕区域（bounds 兜底歧义时按位置匹配的键）；防膨胀
        lastRenderScreenRegion[id] = liveRegion
        if (lastRenderScreenRegion.size > 512) lastRenderScreenRegion.clear()
        // [coord 降级] 详细渲染坐标只在区域实际变化时打（srcRect/viewport 变才打，防每帧刷屏）；
        // 常态坐标核对用 Log.d 级 map: updated / 节流的 coord: refresh
        // [2026-08-13 用户要求·关闭日志选项] moduleLogEnabled=false 时连状态跟踪一并跳过（日志纯诊断用）
        if (moduleLogEnabled) {
            val coordKey = "src=${region.srcRect} vp=$viewport"
            if (lastCoordRenderKey[region.id] != coordKey) {
                lastCoordRenderKey[region.id] = coordKey
                Log.i(
                    TAG,
                    "coord: render drawable=${drawable.javaClass.simpleName} id=${region.id} " +
                        "view=${region.hostViewName} bounds=$bounds region=${region.screenRegion} " +
                        "srcRect=${region.srcRect} source=${source.width}x${source.height} " +
                        "elementSource=${renderSource.isElementSource} viewport=$viewport"
                )
            }
            // [任务 D/问题 10] 圆角半径来源只在变化/首次打（替代硬编码 28）
            if (lastLoggedCornerRadius[region.id] != region.cornerRadius) {
                lastLoggedCornerRadius[region.id] = region.cornerRadius
                Log.i(TAG, "coord: cornerRadius=${region.cornerRadius} source=${region.cornerRadiusSource} (id=${region.id})")
            }
        }
        val sourceShader = LiquidGlassShader.createSourceBitmapShader(source)
        // uSourceLowRes：整屏快照（已 0.5 降采样）直接作 lowRes（uSourceLowResRect = uSourceRect = 元素区域折算
        // 坐标，二者一致）；全局 1/4 降采样分支已废弃（恒不走）。
        // 失败 → null，setUniforms 回退全分辨率源（等效旧行为），液态玻璃渲染不中断
        val lowResShader: Shader?
        val lowResSrcRect: RectF
        if (renderSource.isElementSource) {
            lowResShader = sourceShader
            lowResSrcRect = srcRect
        } else {
            val lowResShaderResult = buildLowResShader(source, srcRect)
            lowResShader = lowResShaderResult?.shader
            lowResSrcRect = lowResShaderResult?.lowResSrcRect ?: srcRect
        }

        val shader = shaderCache.getOrPut(region.id) {
            // 每个 drawable 独立 RuntimeShader（AGSL 编译一次，进程内复用；uniform 各管各的）
            LiquidGlassShader.create()
        }
        // uViewport 必须与 shader coord 空间一致（main 里 halfSize=uViewport×0.5），用元素自身尺寸
        // [任务 D] uCornerRadius = RegionEntry 真实圆角（反射 BlurConfig / 启发式），替代硬编码 28；
        // [spec/18 修正] CONIC 本体（通知卡/custom 卡/seekbar）→ uCornerIsConic/uCornerWeight
        val params = buildParams(viewport.width(), viewport.height(), region.cornerRadius, effectiveCornerIsConic, region.cornerWeight)
        // uDebugCoord 实验开关：coord 决定性实验用。0=玻璃正常渲染；1=直接输出红/绿周期调试网格（不走玻璃管线）。
        // 正常关闭，需复测 coord 空间时临时开 1f。uDebugCoord 分支保留在 LiquidGlassShader.kt 供后续实验。
        LiquidGlassShader.setUniforms(
            shader, params, sourceShader, lowResShader,
            sourceRect = region.srcRect,
            lowResSourceRect = lowResSrcRect,
            debugCoord = 0f,
            maskRect = region.maskRect,
            maskCornerRadius = region.maskCornerRadius,
            // [2026-08-13] 本体角 CONIC↔圆弧渐变权重
            cornerConicBlend = cornerConicBlend,
            // [任务 A] 真实大小绘制：viewport 已是真实尺寸，几何/采样坐标减扩张偏移对齐真实卡片
            maskOffsetX = geo.offsetX,
            maskOffsetY = geo.offsetY,
            // [spec/35 黑遮罩 / spec/41 作用范围修正] SystemUI 下拉所有玻璃元素统一遮罩值
            //（不再按宿主 View 名关键词判定；uMaskAlpha=0 即遮罩关闭时 shader 内分支零开销跳过）
            maskAlpha = maskAlphaFor(entry.hostViewName),
        )
        // 记录已应用状态（R1：后续每帧 refresh 据此判断是否重设 uniform）
        appliedUniformsMap[region.id] = AppliedKey(
            frame = 0L,  // [2026-08-13] 无全局帧概念（BlurService 端全移除）；源变化靠 sourceBitmapId
            sourceBitmapId = System.identityHashCode(source),
            viewport = viewport,
            srcRect = region.srcRect,
            maskRect = region.maskRect,
            cornerIsConic = effectiveCornerIsConic, cornerWeight = region.cornerWeight, cornerRadius = region.cornerRadius,
            cornerConicBlend = cornerConicBlend,
            // [spec/35 黑遮罩] 与 setUniforms 传入的遮罩值一致（防下帧 maskOverlayChanged 误判多一次全量重设）
            maskAlpha = maskAlphaFor(entry.hostViewName),
        )
        return LiquidShaderBuild(shader, region.id)
    }

    // ------------------------------------------------------------ 坐标映射查表（R3）

    /**
     * [整屏快照 doc/spec/07 + 2026-08-13 组合方案] 解析渲染背景源：
     * - 共享整屏快照（captureDisplay 整屏排除 shade + 0.5 降采样，[screenSnapshot]）。
     * - **srcRect = 元素当前 screenRegion 折算到整屏快照坐标**（按「快照 bitmap 尺寸 / 快照覆盖区域尺寸」
     *   比例缩放）——元素移动/滑动时 screenRegion 变 → srcRect 实时变 → shader 采样坐标实时跟手，
     *   **不重抓背景**。这是「移动只更新 srcRect」的真正实现（每元素 crop 方案 srcRect 固定全尺寸 →
     *   移动折射旧位置内容 = 卡顿根因，已废弃）。
     * - 快照缺失 → [requestScreenSnapshotAsync] post 主线程直接抓屏（**[2026-08-14 spec/56] worker 队列
     *   停用**，渲染线程自身不 captureDisplay——渲染线程同步 captureDisplay = ANR 根因）；本帧 null →
     *   回退系统模糊，下帧主线程抓屏完成 postInvalidateHosts 后用快照，接受一帧延迟。
     */
    private fun resolveRenderSource(id: Int, screenRegion: RectF): RenderSource? {
        val snap = requestScreenSnapshotAsync()
        if (snap != null && snap.bitmap.width > 0 && snap.bitmap.height > 0 && snap.region.width() > 0f) {
            // srcRect = 元素当前区域折算到整屏快照坐标（0.5 降采样 → 坐标 × scale，坐标空间一致不错位）
            val scaleX = snap.bitmap.width.toFloat() / snap.region.width()
            val scaleY = snap.bitmap.height.toFloat() / snap.region.height()
            val srcRect = RectF(
                (screenRegion.left - snap.region.left) * scaleX,
                (screenRegion.top - snap.region.top) * scaleY,
                (screenRegion.right - snap.region.left) * scaleX,
                (screenRegion.bottom - snap.region.top) * scaleY,
            )
            // MetaBall 扩张/动画中 region 可能含屏外负偏移 → srcRect 可能越界；AGSL BitmapShader CLAMP 兜底
            return RenderSource(snap.bitmap, srcRect, isElementSource = true)
        }
        return null
    }

    /**
     * [2026-08-14 spec/56 更新 + 2026-08-15 恢复 worker] 渲染触发整屏快照请求：**异步**（不阻塞渲染线程/主线程
     * ——原「渲染线程同步 captureDisplay」是 ANR 冻结根因，已废弃）。快照缺失 → **[2026-08-15 恢复 worker]
     * 直接入队 worker 异步抓屏**（[enqueueElementCapture] + [kickElementCaptureWorker]；渲染线程零
     * captureDisplay + 零主线程 post 开销，入队即返回；spec/56 的「post 主线程直接抓屏」随 worker 恢复回退）。
     * 本帧返回最近快照（缺失 → null → 回退系统模糊，worker 抓屏完成 postInvalidateHosts 后一帧恢复玻璃）。
     * 防竞争：失败冷却 [elementCaptureQuitAt] → 冷却期内不重抓；[captureInFlightIds] + [pendingCaptureIds]
     * 防重复入队（worker 抓屏中或队列已待抓不重复加）。 */
    private fun requestScreenSnapshotAsync(): ElementBackground? {
        val snap = screenSnapshot
        val valid = snap != null && snap.bitmap.width > 0 && snap.bitmap.height > 0
        if (valid) return snap
        // 快照缺失 → 直接入队 worker 异步抓屏（不阻塞渲染线程）；失败冷却期内不重抓
        val now = SystemClock.uptimeMillis()
        // [2026-08-14 用户要求·去冷却] 移除失败冷却检查（不再设置冷却）
        if (!captureInFlightIds.contains(SNAPSHOT_ID) && pendingCaptureIds.add(SNAPSHOT_ID)) {
            elementCaptureQueue.add(SNAPSHOT_ID)
            kickElementCaptureWorker()
            Log.d(TAG, "bg-element: render-trigger async snapshot capture queued (render non-blocking, one-frame delay)")
        }
        return null
    }

    /**
     * screenRegionMap 查表 + 命中率观测。
     * 主键（drawable identityHashCode）命中优先；主键 miss 时按 bounds 宽高兜底匹配
     * （宽高差总和 ≤ 4px 视为同尺寸元素，取最接近者）。返回 miss 时回退系统模糊。
     */
    private fun lookupScreenRegion(id: Int, drawableBounds: Rect): RegionEntry? {
        mapLookupCount.incrementAndGet()
        screenRegionMap[id]?.let {
            mapHitCount.incrementAndGet()
            return it
        }
        // [歧义修复 2026-08-13] 歧义 ABORTED 冷却期：该 id 短时间（AMBIGUOUS_COOLDOWN_MS）内直接
        // 回退系统模糊，不反复查表 → 防折叠组展开等场景 GLASS/BLUR 高频切换闪磨砂。
        val now = SystemClock.uptimeMillis()
        if (now < (ambiguousUntil[id] ?: 0L)) {
            if (ambiguousUntil.size > 256) ambiguousUntil.clear()
            return null
        }
        // 主键 miss → bounds 宽高兜底匹配（C2 宁缺毋滥：候选必须恰好 1 个）
        // 真机实锤（2026-08-12）：通知卡片等宽等高 → 兜底匹配到同一条 entry（谁先注册谁被选）
        // → 所有卡采同一块深色区域（黑卡）。修复：候选 >1 时按屏幕位置匹配消歧，仍歧义回退系统模糊。
        val w = drawableBounds.width()
        val h = drawableBounds.height()
        val candidates = ArrayList<Pair<Int, RegionEntry>>()
        var bestDiff = Int.MAX_VALUE
        for ((k, e) in screenRegionMap) {
            val diff = Math.abs(e.autoBounds.width() - w) + Math.abs(e.autoBounds.height() - h)
            if (diff < bestDiff) {
                bestDiff = diff
                candidates.clear()
                candidates.add(k to e)
            } else if (diff == bestDiff) {
                candidates.add(k to e)
            }
        }
        if (bestDiff <= 4 && candidates.isNotEmpty()) {
            if (candidates.size == 1) {
                mapHitCount.incrementAndGet()
                // [日志降级] bounds 兜底命中是常规路径，Log.d 节流可见（不刷默认 logcat，聚合到 2s 一条）
                logThrottled("fallback-by-bounds", Log.DEBUG) {
                    "map: fallback by bounds id=$id -> key=${candidates[0].first} diff=$bestDiff " +
                        "bounds=$drawableBounds (autoBounds=${candidates[0].second.autoBounds})"
                }
                return candidates[0].second
            }
            // [歧义修复] 候选 >1：用「该 drawable 上次成功渲染的屏幕区域」做位置匹配，
            // 与候选 region 中心距离最近且唯一者胜出（折叠组展开动画时卡片相对位置稳定，
            // 上一帧位置仍接近正确候选，能区分等宽高卡片）。
            val prev = lastRenderScreenRegion[id]
            if (prev != null && prev.width() > 0f && prev.height() > 0f) {
                val pcx = prev.centerX()
                val pcy = prev.centerY()
                var bestKey = -1
                var bestDist = Float.MAX_VALUE
                var bestCount = 0
                for ((k, e) in candidates) {
                    val cx = e.region.centerX()
                    val cy = e.region.centerY()
                    val d = (cx - pcx) * (cx - pcx) + (cy - pcy) * (cy - pcy)
                    if (d < bestDist) {
                        bestDist = d
                        bestKey = k
                        bestCount = 1
                    } else if (d == bestDist) {
                        bestCount++
                    }
                }
                if (bestKey >= 0 && bestCount == 1) {
                    mapHitCount.incrementAndGet()
                    logThrottled("fallback-by-position", Log.DEBUG) {
                        "map: fallback by position id=$id -> key=$bestKey dist=$bestDist " +
                            "prev=${prev.toShortString()} bounds=$drawableBounds"
                    }
                    return screenRegionMap[bestKey]
                }
            }
            // 歧义仍无法消解：宁缺毋滥回退系统模糊 + 冷却记忆（防高频闪）；映射/位置更新后自然重试
            ambiguousUntil[id] = now + AMBIGUOUS_COOLDOWN_MS
            if (ambiguousUntil.size > 256) ambiguousUntil.clear()
            Log.i(
                TAG,
                "map: fallback ABORTED (ambiguous=${candidates.size} candidates) id=$id " +
                    "bounds=$drawableBounds, keep system blur"
            )
        }
        // [R3] 命中率观测：每累计 256 次 lookup **且距上次 ≥2s** 打一次节流日志
        //（2026-08-13：~30 宿主×60fps ≈ 1800 lookup/s，仅按次数节流仍 ~7 条/s，加时间闸）
        val lookups = mapLookupCount.get()
        val hitRateNow = SystemClock.uptimeMillis()
        if (lookups - lastHitRateLoggedLookups >= 256 && hitRateNow - lastHitRateLoggedAt >= THROTTLE_LOG_INTERVAL_MS) {
            lastHitRateLoggedLookups = lookups
            lastHitRateLoggedAt = hitRateNow
            val hits = mapHitCount.get()
            val pct = if (lookups > 0) hits * 100 / lookups else 0
            Log.i(TAG, "map: screen-region map hit rate $hits/$lookups ($pct%)")
        }
        return null
    }

    /**
     * [Bug 5 方向确认 2026-08-13] 尺寸变化及时重采样：把映射侧 region 对齐到渲染侧「本帧最新」的 posteffect
     * bounds。映射写入点（registerScreenRegionCore / registerPlatformBlurRegion / recordNotificationCardRegion）
     * 都满足不变式 **region = hostLoc + autoBounds**（bounds 含 MetaBall 负向扩张偏移）；渲染侧 drawBlurShader
     * 读到的是系统本帧 setBounds 后的最新 bounds——尺寸/位移变化帧内，映射侧 region 仍是上一帧 bounds 折算，
     * 与渲染侧 viewport（本帧 bounds）一帧错位（「玻璃内容与卡片尺寸不匹配」的微观机制）。此处按
     * freshBounds 与 autoBounds 的 delta 平移区域右/下边缘，使 srcRect 与 viewport 同一帧一致。
     *
     * @param entry lookupScreenRegion 结果（region = 上一帧/最近一次映射折算）
     * @param freshBounds 渲染侧 posteffect getBounds（本帧，系统 setBounds 已更新）
     * @return 对齐后的渲染区域（freshBounds 与 autoBounds 一致时原样返回 entry.region，零开销）
     */
    private fun reconcileRegion(entry: RegionEntry, freshBounds: Rect): RectF {
        val ab = entry.autoBounds
        if (ab.left == freshBounds.left && ab.top == freshBounds.top &&
            ab.width() == freshBounds.width() && ab.height() == freshBounds.height()
        ) {
            return entry.region
        }
        val dx = freshBounds.left - ab.left
        val dy = freshBounds.top - ab.top
        return RectF(
            entry.region.left + dx,
            entry.region.top + dy,
            entry.region.left + dx + freshBounds.width(),
            entry.region.top + dy + freshBounds.height(),
        )
    }

    // ------------------------------------------------------------ [任务 A] 真实大小绘制（替代 maskRect 裁剪）

    /**
     * [任务 A] MetaBallBlurDrawable 固有扩张的"真实大小绘制"几何解析结果。
     *
     * @param viewport  真实尺寸（局部坐标 0..realW × 0..realH；作为 uViewport 传给 shader）
     * @param offsetX   x 扩张偏移（shader uMaskOffset.x = coord→真实元素局部坐标需减值；默认 0）
     * @param offsetY   y 扩张偏移（shader uMaskOffset.y；默认 0）
     * @param maskRect  有效掩码矩形（raw coord 空间，与 body 几何同形同位置）；无扩张 = entry.maskRect 原样兜底
     * @param realRegion 真实卡片区域（screenRegion 内缩扩张量；srcRect 折算基准，排除扩张区。
     *  screenRegion 为绝对屏幕区域时即绝对区域；为相对 loc 的形状（spec/32 origin 实时跟）时即相对形状，
     *  主线程 loc + 形状 = 绝对屏幕区域）
     */
    private data class RealGeometry(
        val viewport: RectF,
        val offsetX: Float,
        val offsetY: Float,
        val maskRect: RectF?,
        val realRegion: RectF,
    )

    /**
     * [任务 A] 解析"真实大小绘制"几何（spec/18 方向纠正：不裁剪，用真实大小绘制）。
     *
     * MetaBallBlurDrawable.setBounds（反编译实证）blendMode==0 时左右各扩 boundsExtension
     * （= BLEND_COLOR_RANGE(55)+blendRange，真机 69px；`bounds=Rect(-69, 0 - 1295, 340)` 日志实证）、
     * blendMode==1 上下扩 → bounds.left/top 为负。渲染侧 viewport 若直接用扩张尺寸，玻璃画满扩张区 →
     * 通知卡片/侧滑按钮滑动时变胖。修复：viewport 用真实尺寸 + shader 几何/采样坐标减扩张偏移
     * （uMaskOffset），使玻璃形状与真实卡片对齐；掩码 = 真实卡片区域（与 body 同形，不再切边）。
     *
     * 检测：bounds.left<0 / bounds.top<0（MetaBall 固有扩张，对称：单侧扩张 = 负数绝对值）。
     * 无扩张 → 零变化（viewport=bounds 尺寸、offset=(0,0)、maskRect=entry.maskRect、realRegion=原区域）。
     * 扩张异常（realW/realH≤1，扩张 ≥ 尺寸）→ 无法确定真实尺寸，兜底原 bounds + entry.maskRect。
     *
     * @param bounds 渲染侧 posteffect getBounds（系统本帧 setBounds 后的最新值，含负向扩张）
     * @param entry screenRegionMap 条目（maskRect 兜底来源；仅 swipe 注册时设置）
     * @param screenRegion 渲染区域（reconcileRegion 对齐后，扩张坐标系）
     */
    private fun computeRealGeometry(bounds: Rect, entry: RegionEntry, screenRegion: RectF): RealGeometry {
        val offsetX = if (bounds.left < 0) (-bounds.left).toFloat() else 0f
        val offsetY = if (bounds.top < 0) (-bounds.top).toFloat() else 0f
        val vpW = bounds.width().toFloat()
        val vpH = bounds.height().toFloat()
        if (offsetX <= 0f && offsetY <= 0f) {
            // 无扩张：零变化（viewport=bounds 尺寸、offset=0、maskRect=entry.maskRect、realRegion=原区域）
            return RealGeometry(RectF(0f, 0f, vpW, vpH), 0f, 0f, entry.maskRect, screenRegion)
        }
        // 对称扩张：真实尺寸 = 扩张后 - 2×单侧扩张
        val realW = vpW - 2f * offsetX
        val realH = vpH - 2f * offsetY
        if (realW <= 1f || realH <= 1f) {
            // 扩张异常（扩张 ≥ 尺寸）：无法确定真实尺寸，兜底原 bounds + entry.maskRect（强兜底不崩）
            return RealGeometry(RectF(0f, 0f, vpW, vpH), 0f, 0f, entry.maskRect, screenRegion)
        }
        val realRegion = RectF(
            screenRegion.left + offsetX,
            screenRegion.top + offsetY,
            screenRegion.right - offsetX,
            screenRegion.bottom - offsetY,
        )
        // 真实卡片区域（raw coord 空间）：left=offsetX, top=offsetY, 宽=realW 高=realH —— 与 body 几何
        //（uViewport=真实尺寸 + uMaskOffset）同形同位置。掩码 smoothstep(0,-2,sdMask) 仅在形状外沿
        // 羽化 2px（对齐系统 shape，BLEND_ANTI_ALIASING=2.0），不再把玻璃边/折射/高光整块切掉。
        val maskRect = RectF(offsetX, offsetY, offsetX + realW, offsetY + realH)
        return RealGeometry(RectF(0f, 0f, realW, realH), offsetX, offsetY, maskRect, realRegion)
    }

    /**
     * [spec/24 遮罩/本体 CONIC 贝塞尔滞后·修复方向 1] 判定掩码是否冗余（掩码与本体几何全等）。
     *
     * 通知卡（MetaBall 扩张元素）：掩码 rect = 真实卡区 = 本体几何区域（同尺寸同位置），掩码 CONIC 角
     * == 本体 CONIC 角（effMaskCornerRadius 此时 = entry.cornerRadius）→ `sdMask ≡ sdRaw`，同一贝塞尔
     * 每像素算 2 遍。冗余时传 maskCornerRadius=0 → shader 掩码走 O(1) 直角路径（sdMask 在卡片区内
     * 恒 ≤0 → mask≈1 不裁剪），圆角外裁剪已由 shapeAlpha 兜底 → **零视觉变化，CONIC 成本减半**。
     *
     * 判定（全过才算冗余，最保守）：
     * 1. 掩码矩形 == 本体几何区域（同尺寸同位置；本体经 uMaskOffset 平移占据 raw coord 空间
     *    [offsetX, offsetX+realW]×[offsetY, offsetY+realH]，掩码 rect 即此区域）；
     * 2. 本体是 CONIC（cornerIsConic=true）——圆弧本体 + CONIC 掩码 = swipe 的必要裁剪（spec/12），不删；
     * 3. 掩码 CONIC 角 == 本体 CONIC 角（raw 值相等，shader 侧各 ×1.5）。
     */
    private fun isMaskRedundant(geo: RealGeometry, entry: RegionEntry, effMaskCornerRadius: Float): Boolean {
        val mr = geo.maskRect ?: return false
        if (mr.width() <= 0f || mr.height() <= 0f) return false
        // 1. 同尺寸同位置（eps 0.5px 容忍浮点折算误差）
        if (abs(mr.width() - geo.viewport.width()) > 0.5f || abs(mr.height() - geo.viewport.height()) > 0.5f) return false
        if (abs(mr.left - geo.offsetX) > 0.5f || abs(mr.top - geo.offsetY) > 0.5f) return false
        // 2. 本体必须 CONIC（"同一贝塞尔算 2 遍"才构成冗余）
        if (!entry.cornerIsConic) return false
        // 3. 掩码 CONIC 角 == 本体 CONIC 角（raw 相等 → shader 侧 ×1.5 后 cc 相等）
        return abs(effMaskCornerRadius - entry.cornerRadius) < 0.5f
    }

    /** buildLiquidShader 返回值：液态玻璃 shader + region.id（appliedUniformsMap 键）。 */
    private data class LiquidShaderBuild(
        val shader: RuntimeShader,
        val regionId: Int,
    )

    /** buildLowResShader 结果：低分辨率 BitmapShader + 对应低分辨率 srcRect（全分辨率 srcRect ÷ scale）。 */
    private data class LowResShaderResult(
        val shader: BitmapShader,
        val lowResSrcRect: RectF,
    )

    /** 构造 uSourceLowRes 降采样输入（1/4 原图 + 单位矩阵）及低分辨率 srcRect。失败返回 null（不中断渲染）。 */
    private fun buildLowResShader(source: Bitmap, srcRect: RectF): LowResShaderResult? {
        val lowRes = try {
            getLowResBitmap(source)
        } catch (t: Throwable) {
            Log.e(TAG, "render: getLowResBitmap failed", t)
            null
        } ?: return null
        return try {
            val scaleX = source.width.toFloat() / lowRes.width
            val scaleY = source.height.toFloat() / lowRes.height
            // 低分辨率 srcRect：全分辨率 srcRect ÷ 缩放比（AGSL 侧 srcCoord 用 uSourceLowResRect 折算）
            LowResShaderResult(
                shader = LiquidGlassShader.createLowResSourceBitmapShader(lowRes),
                lowResSrcRect = RectF(
                    srcRect.left / scaleX,
                    srcRect.top / scaleY,
                    srcRect.right / scaleX,
                    srcRect.bottom / scaleY,
                ),
            )
        } catch (t: Throwable) {
            Log.e(TAG, "render: createLowResSourceBitmapShader failed", t)
            null
        }
    }

    /**
     * 取 1/4 降采样原图（`Bitmap.createScaledBitmap(w/4, h/4, true)`）。
     * 按源位图实例缓存：帧变更（onBlurReady 新原图 → 新 Bitmap 实例）自动重建。
     */
    private fun getLowResBitmap(source: Bitmap): Bitmap? {
        val id = System.identityHashCode(source)
        lowResBitmapCache?.let { cached ->
            if (lowResBitmapSourceId == id) return cached
        }
        return try {
            // [doc/spec/68] 逐级 1/2 降采样（等效 mipmap）：一次 4× 双线性只取邻近 2×2 源像素，
            // **不是区域平均** → 2~4px 的细笔画（文字/图标细节）被随机采样成孤立点
            // （用户反馈「点状文字」的根因）。分两次各降一半、每级双线性都做一次平均
            // → 等效 4×4 区域平均，走样大幅减少。仅在快照更新时执行一次，**零渲染开销**。
            var cur = source
            // [doc/spec/68] 降采样级数 **1/4**（逐级 1/2 两次）—— 用户指定。
            // 三轮真机实证记录（供后续调参参考）：
            //   1/4 + 5×5 → 明显网格；1/8 + 5×5 → 更重的方块；1/2 + 7×7 → 网格依旧。
            // 结论：网格感主因在 **shader 侧高斯核形状**（σ 相对覆盖范围过大、只覆盖 ±1.33σ，
            // 核边缘权重 0.41 ≈ 方框模糊），不在降采样级别 → 降采样回到内存/开销最优的 1/4。
            repeat(2) {
                val nw = (cur.width / 2).coerceAtLeast(1)
                val nh = (cur.height / 2).coerceAtLeast(1)
                if (nw >= cur.width && nh >= cur.height) return@repeat   // 已无法再降
                val next = Bitmap.createScaledBitmap(cur, nw, nh, true)
                // 回收中间产物（不回收源快照；尺寸相同时 createScaledBitmap 会返回同一对象，须判等）
                if (cur !== source && next !== cur) cur.recycle()
                cur = next
            }
            cur.also {
                lowResBitmapCache = it
                lowResBitmapSourceId = id
                Log.i(TAG, "render: lowRes bitmap created ${it.width}x${it.height} (src ${source.width}x${source.height}, 2-step)")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "render: createScaledBitmap(1/4 x2) failed", t)
            null
        }
    }

    /**
     * [2026-08-13 滑动延迟修复] buildParams 材质参数（Prefs 派生字段）时间节流缓存。
     * 材质参数仅用户调整模块设置时变；全量路径每帧调用 buildParams 时原来每帧 11 次远程 Prefs IPC
     * 读（模块↔SystemUI 跨进程），动画期（mask/corner 变化每帧走全量）开销可观。按
     * [MATERIAL_PREFS_CACHE_MS] 时间节流：≥1s 才重读一次，动画期 IPC 读降至 ~1 次/s，
     * 配置改动 ≤1s 生效（无需重启）。渲染输出不变（同值复用，仅读取频率降低）。
     */
    private data class CachedMaterialParams(
        val blurRadius: Float,
        val refractionAmount: Float,
        val depth: Float,
        val dispersion: Float,
        val highlight: Float,
        val highlightWidth: Float,
        val highlightFalloff: Float,
        val vibrancy: Float,
        val lightAngleDegrees: Float,
        val parallaxX: Float,
        val parallaxY: Float,
        /** [2026-09-16 弧线高光修复] 高光方向因子保底。放末尾并带默认值：位置参数构造，
         *  避免重排既有实参（兜底分支不传即用配置默认）。 */
        val highlightFloor: Float = Prefs.DEFAULT_HIGHLIGHT_FLOOR,
    )

    @Volatile
    private var cachedMaterialParamsCache: CachedMaterialParams? = null

    @Volatile
    private var cachedMaterialParamsCacheTimeMs = 0L

    /** buildParams 材质参数缓存刷新间隔（ms）：动画期每帧 IPC 读 → 每 1s 读一次；配置改动 ≤1s 生效。 */
    private const val MATERIAL_PREFS_CACHE_MS = 1000L

    /**
     * [2026-08-13 滑动延迟修复] 读取材质参数（时间节流缓存 ≥[MATERIAL_PREFS_CACHE_MS] 才重读远程 Prefs）。
     * prefs 不可用 / 读取失败 → 默认值（与改动前 def 兜底语义一致）；返回值与改动前逐帧读取结果相同
     * （仅读取频率降低，渲染输出不变）。
     */
    private fun readMaterialParams(): CachedMaterialParams {
        val now = SystemClock.uptimeMillis()
        cachedMaterialParamsCache?.let { cached ->
            if (now - cachedMaterialParamsCacheTimeMs < MATERIAL_PREFS_CACHE_MS) return cached
        }
        val def = LiquidGlassShader.Params(0f, 0f)
        val prefs = try {
            api?.let { Prefs.read(it) }
        } catch (t: Throwable) {
            Log.e(TAG, "buildParams: prefs read failed", t); null
        }
        val result = if (prefs == null) {
            CachedMaterialParams(
                Prefs.DEFAULT_BLUR_RADIUS,
                def.refractionAmount, def.depth, def.dispersion,
                def.highlight, def.highlightWidth, Prefs.DEFAULT_HIGHLIGHT_FALLOFF, Prefs.DEFAULT_VIBRANCY,
                def.lightAngleDegrees, def.parallaxX, def.parallaxY,
            )
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
                    prefs.getFloat(Prefs.KEY_VIBRANCY, Prefs.DEFAULT_VIBRANCY),
                    prefs.getFloat(Prefs.KEY_LIGHT_ANGLE, def.lightAngleDegrees),
                    prefs.getFloat(Prefs.KEY_PARALLAX_X, def.parallaxX),
                    prefs.getFloat(Prefs.KEY_PARALLAX_Y, def.parallaxY),
                    prefs.getFloat(Prefs.KEY_HIGHLIGHT_FLOOR, Prefs.DEFAULT_HIGHLIGHT_FLOOR),
                )
            } catch (t: Throwable) {
                Log.e(TAG, "buildParams: prefs float read failed, use defaults", t)
                CachedMaterialParams(
                    Prefs.DEFAULT_BLUR_RADIUS,
                    def.refractionAmount, def.depth, def.dispersion,
                    def.highlight, def.highlightWidth, Prefs.DEFAULT_HIGHLIGHT_FALLOFF, Prefs.DEFAULT_VIBRANCY,
                    def.lightAngleDegrees, def.parallaxX, def.parallaxY,
                )
            }
        }
        cachedMaterialParamsCache = result
        cachedMaterialParamsCacheTimeMs = now
        return result
    }

    private fun buildParams(
        width: Float,
        height: Float,
        cornerRadius: Float,
        cornerIsConic: Boolean = false,
        cornerWeight: Float = DEFAULT_CORNER_WEIGHT,
    ): LiquidGlassShader.Params {
        val def = LiquidGlassShader.Params(viewportWidth = width, viewportHeight = height)
        return try {
            // [2026-08-13 滑动延迟修复] 材质参数（与 viewport/corner 无关）时间节流缓存：全量路径每帧
            // 调用本方法时原来每帧 11 次远程 Prefs IPC 读 → 现 ≥MATERIAL_PREFS_CACHE_MS 才重读一次，
            // 动画期 IPC 读降至 ~1 次/s；配置改动 ≤1s 生效（无需重启）。渲染输出不变（同值复用）。
            val m = readMaterialParams()
            LiquidGlassShader.Params(
                viewportWidth = width,
                viewportHeight = height,
                // SDF 圆角半径：[任务 D/问题 10] 用 RegionEntry 真实圆角（反射 BlurConfig / 启发式推断，
                // 替代硬编码 28）；模糊半径读 KEY_BLUR_RADIUS（新管线默认 0=清晰透镜，内部轻模糊可调）；
                // 鲜艳度/高光衰减读 KEY_VIBRANCY/KEY_HIGHLIGHT_FALLOFF
                cornerRadius = cornerRadius,
                // [spec/18 修正] CONIC 本体（通知卡/custom 卡/QS seekbar）：uCornerIsConic=1 →
                // shader 内 1.5×uCornerRadius + sdBezierDistance；uCornerWeight=CornerParamsKt 变换后值
                cornerIsConic = cornerIsConic,
                cornerWeight = cornerWeight,
                // [2026-09-16 恢复配置化] 撤回原「强制透明诊断档」的硬编码 0，改回读 KEY_BLUR_RADIUS
                // （默认 8px）。用户反馈「横幅太透」即因长期被强制成清晰透镜。
                blurRadius = m.blurRadius,
                // [2026-08-14 折射自适应] 折射环带高度恒 = 元素短边一半（min(width,height)/2，每元素自适应——
                // 元素大环带宽/元素小环带窄，比例统一）；不再读 refraction_height 配置（已废弃）。
                // 折射强度读 KEY_REFRACTION_AMOUNT（默认 def=28）。
                refractionHeight = minOf(width, height) / 2f,
                refractionAmount = m.refractionAmount,
                depth = m.depth,
                dispersion = m.dispersion,
                highlight = m.highlight,
                highlightWidth = m.highlightWidth,
                highlightFalloff = m.highlightFalloff,
                highlightFloor = m.highlightFloor,
                vibrancy = m.vibrancy,
                lightAngleDegrees = m.lightAngleDegrees,
                parallaxX = m.parallaxX,
                parallaxY = m.parallaxY,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "buildParams: prefs float read failed, use defaults", t)
            def
        }
    }

    // ------------------------------------------------------------ 整屏快照抓屏（doc/spec/07 + 2026-08-13 组合方案）

    /**
     * [2026-08-13 组合方案·整屏快照] SystemUI 进程自维护「整屏干净背景快照」：反射
     * `IWindowManager.captureDisplay(0, CaptureArgs + setExcludeLayers([shadeSfc]) + setFrameScale(0.5), listener)`
     * 抓整屏（sourceCrop=全屏）排除 shade 的干净背景（壁纸+桌面+后台 app），0.5 降采样存 [screenSnapshot]，
     * **共享给所有玻璃元素**。替代每元素 crop（元素移动/尺寸变化后 crop 失效 → 玻璃折射旧位置内容 = 卡顿根因）：
     * 整屏快照覆盖全屏，元素移动只更新 srcRect（渲染侧按当前 region 折算到快照坐标），玻璃内容实时跟手，不重抓。
     *
     * 反射签名（BlurCaptureHook.captureCleanFullscreen 已验证）：
     * - ServiceManager.getService("window") → IWindowManager$Stub.asInterface → captureDisplay(int, CaptureArgs, listener)
     * - CaptureArgs.Builder() 无参构造 + setSourceCrop(Rect=全屏) + setExcludeLayers(SurfaceControl[]) + setFrameScale(0.5f) + build()
     * - ScreenCapture.createSyncCaptureListener() → listener.getBuffer() → getHardwareBuffer()
     *
     * 引用纪律：抓屏 hw 是模块自建引用（非系统 buffer）→ wrap + copy 出独立软件位图（像素与 GPU 显存解耦）
     * → copy 完成后 close hw 释放 GPU 内存。快照不主动 recycle（GC，防 GPU 引用崩溃）。
     * 兜底铁律：shadeSfc 拿不到 / 抓屏失败 → 静默（玻璃回退系统模糊，不影响 SystemUI）。
     *
     * 调度（[triggerBackgroundCapture] / [scheduleElementCaptures] / [requestScreenSnapshotAsync]）：
     * **[2026-08-14 spec/56] worker 队列停用（worker 卡死 → 完全不抓屏根因）**——内容变化触发 + 无节流立即抓，
     * 主线程触发路径（下拉/通知出现/内容变化）**主线程直接同步抓屏**（captureDisplay 20-50ms 单次，
     * ANR 取舍见 spec/56）；连抓/持续为后台循环（非用户下拉触发）保留后台线程直接抓屏（主线程零阻塞）；
     * 静止零抓屏；移动只更新 srcRect 不重抓；渲染兜底（快照缺失）post 主线程异步请求，不阻塞渲染线程。
     */

    /** 缓存的 shade 窗口根 SurfaceControl（宿主 View.getViewRootImpl().getSurfaceControl()；窗口重建时刷新） */
    @Volatile
    private var shadeSfcCache: SurfaceControl? = null

    /** [spec/45] shade 窗口根 View 强引用缓存（registerHostView 登记 shade 根宿主时记录）。
     *  shade 窗口 View 常驻（折叠期 registeredHostViews 内只剩 heads-up 卡片宿主，遍历不到 shade 根），
     *  resolveShadeSfc 优先经此解析 sfc。旧根重建后 detach → isAttachedToWindow 检查自然跳过。
     *  @Volatile：主线程（registerHostView）写，抓屏 worker 线程（resolveShadeSfc）读。 */
    @Volatile
    private var shadeRootViewCache: View? = null

    /**
     * [2026-08-13 用户决定·协调者补充 + 2026-08-14 spec/56 + 2026-08-15 恢复 worker] 内容变化 → 触发**整屏
     * 快照**重抓（**节流 KEY_BG_CAPTURE_MIN_INTERVAL_MS，默认 0=无节流立即抓**；[2026-08-13 回退 spec/49
     * 固定 200ms]）。
     * - **内容变化立即抓（无节流）**：内容变化事件（面板开/关 mountPanelExpansion、onBlurReady 新帧
     *   captureOriginalFrame、映射写入 registerScreenRegionCore / recordNotificationCardRegion）到达**立即**
     *   触发抓屏。「屏幕内容变化」节流 = 内容没变不触发、内容变了立即抓（不设固定时间窗限频）。
     * - **[2026-08-15 恢复 worker] 异步 worker 消费（入队 → worker）**：spec/56 曾改主线程直接抓屏（worker
     *   卡死 → 完全不抓屏根因），现 worker 队列恢复使用（根因 A 孤儿竞态 + 根因 B 失败快速路径已修复，见
     *   spec/56 追加）——本方法入队 [scheduleElementCaptures] → [enqueueElementCapture] +
     *   [kickElementCaptureWorker]：captureDisplay 在 worker 线程同步执行，**主线程零阻塞**（spec/56 ANR
     *   取舍解除，下拉动画逐帧触发不再每帧主线程同步 captureDisplay）。
     * - **静止零抓屏**：无内容变化事件即不调用本方法（原逐帧追踪器触发已于 2026-09-17 移除），静止不抓。
     * - **[doc/spec/49]** contentChange=true（默认）同时记录内容变化信号（[markContentChanged]），供周期抓屏
     *   判断空闲/活动；周期抓屏自身调用时传 false（周期不是内容变化，不得污染时间戳）。
     * 主线程调用（registerScreenRegionCore / recordNotificationCardRegion / mountPanelExpansion /
     * captureOriginalFrame）。**不由元素移动触发**（移动只更新 srcRect 折算，不重抓背景）。
     */
    private fun triggerBackgroundCapture(contentChange: Boolean = true) {
        // [spec/55 诊断日志] 触发时间戳（t2）——「hook 触发 → 抓屏请求」延迟（hook 可靠否）；contentChange 附带调用来源
        diagCapLog("enqueue", if (contentChange) " contentChange=true" else " contentChange=false")
        if (contentChange) markContentChanged()
        // [2026-08-15 恢复 worker] 触发抓屏入队 → worker 消费（异步，主线程零阻塞；captureDisplay 由 worker
        // 线程同步执行 [captureSnapshotImmediate]；失败重试重新入队）。
        scheduleElementCaptures()
    }

    /**
     * [doc/spec/50 + spec/51 2026-08-13 + 2026-08-14 spec/56 + 2026-08-15 恢复 worker] 刚下拉首帧抓屏触发。
     * **2026-08-13 回退：主线程不再同步 captureDisplay**（用户对照实验实证：8920762 worker 异步抓屏跟手无延迟；
     * 主线程同步 captureDisplay Binder 20-50ms 阻塞 setExpansionHeight/渲染回调 100-200ms → 玻璃背景延迟）。
     * **2026-08-14 spec/56 反转 → 2026-08-15 恢复 worker（用户决定）**：spec/56 曾把触发抓屏改主线程直接执行
     * （worker 卡死 → 完全不抓屏根因），现 worker 队列恢复（根因 A 孤儿竞态 + 根因 B 失败快速路径已修复）——
     * 当前语义 = **首帧立即入队 worker（异步抓屏，主线程零阻塞）+ 连抓 worker 后台补帧
     * （连抓 5~6 帧，45ms 间隔覆盖入场动画）**。
     *
     * 方法名保留（[mountPanelExpansion] 首帧分支调用点不动）。首帧仍走 sfc 解析（spec/45
     * [resolveShadeSfcOrFallback] 兜底）+ 排除逻辑（captureElementBackground 内 exclude shade + heads-up sfc）。
     *
     * 文字亮度/重采样（updateQsBrightness/resampleAllTextContrast，540×1200 IntArray 2.6MB 全图扫描）
     * 由 [runTextContrastScanAsync] 后台单飞完成（worker 抓屏时不内联做，防卡）。
     */
    private fun triggerBackgroundCaptureOnMainThread() {
        // [doc/spec/50 + spec/51 用户补充 + 2026-08-15 用户要求「下拉展开瞬间主线程立即截屏，什么都不等」]
        // 下拉首帧：**主线程同步**执行 [captureSnapshotImmediate]（同步 captureDisplay 20-50ms，首帧一次
        // 可接受，立即写 screenSnapshot + push shader 替换渲染缓冲区，不等后台 worker）+ 连抓 5~6 帧
        // 覆盖入场动画。停止条件恒 false（连抓跑完即止，短时有限突发）；pauseDuringPanelExpansion=false
        // （下拉自身连抓不暂停，覆盖入场动画）。后续主动持续抓屏 worker（[startPanelContinuousCapture]，
        // onExpansionStarted / setExpansionHeight>0 分支已启动）照常维持背景最新。
        triggerFirstFrameCaptureWithBurst(
            burstStop = { false },
            logTag = "panel-first",
            pauseDuringPanelExpansion = false,
            mainThreadSyncFirstFrame = true,
        )
    }

    /**
     * [doc/spec/50 + doc/spec/51 用户补充·统一逻辑 + 2026-08-14 spec/56 + 2026-08-15 恢复 worker +
     * 2026-08-15 用户要求主线程立即截屏] **首帧立即触发抓屏 + 连抓 5~6 帧**。下拉首帧
     * （[triggerBackgroundCaptureOnMainThread]）与通知首帧（[triggerHeadsUpAppearanceCapture]）共用本方法：
     * **[2026-08-15] 首帧主线程同步截屏**（[mainThreadSyncFirstFrame]=true 时在调用点主线程直接执行
     * [captureSnapshotImmediate]：resolveShadeSfcOrFallback → captureScreenSnapshot → screenSnapshot = bg
     * + postInvalidateHosts，**同步 captureDisplay 20-50ms 立即替换渲染缓冲区，什么都不等**）
     * + 连抓 worker 后台快速补帧（帧间隔 45ms）→ 覆盖首帧后的入场/展开动画窗口 ~300ms。
     * [mainThreadSyncFirstFrame]=false（默认）时触发 = 入队 worker 立即抓（异步，主线程零阻塞）。
     *
     * 用户硬约束：「刚下拉/横幅通知弹出必须立马抓，宁可拉高负载不拖」+「首帧多抓几帧 5~6 帧」+
     * 「通知横幅在出现的时候立马截一张屏，什么都不等」（2026-08-15）。
     *
     * **2026-08-13 回退主线程同步（2026-08-14 spec/56 反转 → 2026-08-15 恢复 worker → 2026-08-15 用户要求
     * 首帧主线程同步）**：曾因对照实验回退为 worker 异步（主线程零阻塞）；spec/56 曾改主线程直接抓屏（worker
     * 卡死根因）；2026-08-15 worker 队列恢复（根因 A/B 已修复）。现按用户要求**首帧单次主线程同步**（20-50ms
     * 可接受），后续帧（连抓/持续/内容变化）全部入队 worker 异步——首帧立即替换缓冲区，持续后台零阻塞。
     *
     * **[2026-08-13 通知抓屏单通道收敛 → spec/56 取消 → 2026-08-15 恢复]** 连抓/持续 worker 作为节拍器周期性
     * [scheduleElementCaptures] 入队主 worker（SNAPSHOT_ID 同一队列，pending 去重 + 单 worker 串行 = 天然
     * 单通道，captureDisplay 仅主 worker 一路执行），spec/51 单通道收敛语义恢复。主线程同步首帧不入队
     * （无 pending 冲突），连抓第一 tick 入队被 pending 去重吸收 / worker 串行 = 不双路并发。
     *
     * 失败回退：首帧失败（sfc 解析 / capture）走 [captureSnapshotImmediate] 内 [scheduleCaptureRetry] 快速
     * 重试（重新入队 worker，force=true 不被冷却拦）→ 回退 worker 持续抓屏，不阻塞；通知场景另有
     * [startHeadsUpContinuousCapture] 持续 worker 兜底。
     *
     * @param burstStop 连抓停止条件：通知=通知已消失（[isHeadsUpHostActive] false）提前停；
     *  下拉=恒 false（连抓跑完即止，短时有限突发）。
     * @param logTag 日志前缀（panel-first=下拉首帧 / heads-up=通知首帧）。
     * @param mainThreadSyncFirstFrame true=首帧在调用点（主线程）同步 [captureSnapshotImmediate] 立即替换
     *  缓冲区（什么都不等）；false=触发入队 worker 异步抓首帧。
     */
    private fun triggerFirstFrameCaptureWithBurst(
        burstStop: () -> Boolean,
        logTag: String,
        pauseDuringPanelExpansion: Boolean,
        mainThreadSyncFirstFrame: Boolean = false,
    ) {
        markContentChanged()
        if (mainThreadSyncFirstFrame) {
            // [spec/58 追加 2026-08-15 诊断日志] 主线程同步首帧链路起点（lg-first）：进入本方法即主线程
            // 同步开始（对比 lg-hook 的 onExpansionStarted/setExpansionHeight 触发点看 hook → 首帧抓屏延迟）
            diagFirstLog("main-thread-start", " logTag=$logTag")
            // [2026-08-15 用户要求·下拉/通知出现瞬间主线程立即截屏，什么都不等] 首帧**主线程同步**执行
            // [captureSnapshotImmediate]（resolveShadeSfcOrFallback → captureScreenSnapshot →
            // screenSnapshot = bg + postInvalidateHosts，**直接替换渲染缓冲区，不等后台 worker**）。
            // 调用线程 = 本方法调用点（mountPanelExpansion 首帧分支 / recordNotificationCardRegion heads-up
            // 新卡分支，均主线程）→ 同步 captureDisplay 单次 20-50ms（sfc 已缓存）首帧一次可接受；
            // 失败（sfc 解析 / capture）走 [captureSnapshotImmediate] 内 [scheduleCaptureRetry]（主线程
            // postDelayed 60ms/320ms 快速重试 → 重新入队 worker，force=true 不被失败冷却硬拦）快速回退
            // worker 持续抓屏，不阻塞。
            // **防双路**：主线程同步截屏不入队（pending 无 SNAPSHOT_ID），后续连抓/持续 worker tick 入队
            // 被 [pendingCaptureIds] 去重吸收 + 单 worker 串行 = 同刻至多一路 captureDisplay（spec/51
            // 单通道收敛语义保持）。
            captureSnapshotImmediate("$logTag-main-thread")
        } else {
            // [2026-08-15 恢复 worker 默认路径] 触发 = 入队 worker 立即抓（异步，主线程零阻塞；
            // captureDisplay 由 worker 线程同步执行 [captureSnapshotImmediate]）。
            triggerBackgroundCapture()
        }
        // 连抓 5~6 帧（用户要求保留）：专用连抓 worker 后台快速补帧（首帧已由主线程同步抓取 / 或触发路径
        // 入队，连抓全部帧覆盖后续入场/展开动画窗口），主线程零阻塞。
        // pauseDuringPanelExpansion：通知场景 true（下拉展开期间暂停，避免与下拉抓屏竞争 captureDisplay）；
        // 下拉场景 false（下拉自身连抓照跑，覆盖入场动画）。
        scheduleHeadsUpBurstWorker(
            HEADS_UP_BURST_TOTAL_FRAMES,
            burstStop,
            logTag,
            pauseDuringPanelExpansion,
        )
    }

    // ---- [spec/51 2026-08-13] 通知（heads-up）首帧主线程抓屏 + 连抓 5~6 帧 + 持续期间兜底抓屏 ----

    /**
     * [doc/spec/51 2026-08-13] 通知（heads-up 横幅）出现：主线程立即抓首帧 + 连抓 5~6 帧（与下拉首帧
     * **共用 [triggerFirstFrameCaptureWithBurst] 统一逻辑**），覆盖入场动画背景实时跟上；通知持续期间
     * 持续兜底抓屏；消失自动停止。
     *
     * 用户硬约束：「横幅通知弹出时立马抓，宁可高负载不拖」+「通知出现时多抓几帧 5~6 帧」+
     * 「通知持续期间必须一直抓屏」。
     *
     * 触发点：[recordNotificationCardRegion] 新卡（previous==null）且 bgView 属 heads-up 窗口，
     * 或 shade 卡转入 heads-up 窗口（映射已存在但刚变 heads-up）。
     *
     * **主线程零阻塞（2026-08-13 回退）**：不再主线程同步 captureDisplay（对照实验：8920762 worker
     * 异步抓屏跟手无延迟；主线程同步首帧阻塞 100-200ms → 玻璃延迟）——触发 = 入队 worker 立即抓 +
     * 连抓节拍器周期性入队（帧间隔 45ms，覆盖入场动画窗口 ~300ms）+ [startHeadsUpContinuousCapture]
     * 持续不间断节拍器（覆盖持续期间每一帧变化）。
     *
     * **[2026-08-13 通知抓屏单通道收敛] 首帧/连抓/持续三路全部收敛为「入队主 worker 队列」单通道**：
     * 连抓/持续 worker 仅作为节拍器周期性 [scheduleElementCaptures] 入队（SNAPSHOT_ID 同一队列，
     * pending 去重 + 单 worker 串行 = 同刻至多一路 captureDisplay），杜绝通知出现时三路并发锤
     * captureDisplay → SF 串行排队卡顿。captureDisplay 执行、写 screenSnapshot、postInvalidateHosts
     * 全在主 worker 一路完成；首帧立即性不变（triggerBackgroundCapture 首帧即入队）。
     *
     * 停止：通知消失（bgView detach / rootView 不再 heads-up）→ 连抓节拍器与持续节拍器各自
     * 检测 [isHeadsUpHostActive] 停止，不后台耗电。
     */
    private fun triggerHeadsUpAppearanceCapture(bgView: View) {
        headsUpActiveHost = WeakReference(bgView)
        // [doc/spec/50 + spec/51 统一逻辑 + 2026-08-15 用户要求「通知横幅出现立马截一张屏，什么都不等」]
        // 通知出现首帧 = **主线程同步**截屏（[captureSnapshotImmediate] 主线程执行 = 同步 captureDisplay
        // 20-50ms，立即替换缓冲区）+ 连抓 5~6 帧覆盖入场动画。停止条件：通知消失
        // （isHeadsUpHostActive false）提前停；pauseDuringPanelExpansion=true（下拉展开期间
        // 暂停通知连抓，避免与下拉抓屏竞争 captureDisplay）。
        triggerFirstFrameCaptureWithBurst(
            burstStop = { !isHeadsUpHostActive() },
            logTag = "heads-up",
            pauseDuringPanelExpansion = true,
            mainThreadSyncFirstFrame = true,
        )
        // [2026-08-14 横幅主动源·恢复持续抓屏] 横幅不走 onBlurReady（反编译铁证：heads-up 默认走
        // BackgroundBlurDrawable/被 excludeRules 拦，posteffect drawable 不注册 → onBlurReady 不触发），
        // 静止期零抓屏 → 背景冻结。恢复 [startHeadsUpContinuousCapture] 主动持续抓屏（captureDisplay +
        // 运动节流：运动 120Hz / 静止 60Hz，可配 panel_capture_hz）——**仅横幅场景用主动源**；
        // 下拉面板展开仍走 onBlurReady 系统驱动（不恢复 startPanelContinuousCapture）。通知消失自动停止。
        startHeadsUpContinuousCapture()
        // [spec/60 周期强制刷新] heads-up 活跃（新通知出现）→ 确保文字周期强制刷新循环在跑
        ensureTextForceRefreshLoop()
    }

    /** [spec/51] heads-up 活跃判定：活跃宿主存在且仍 attach、rootView 仍为 heads-up 窗口
     *  （HeadsUpLayout/HeadsUpContainerWindow / type 2017）。通知消失（detach 或回到 shade）→ false。
     *  供连抓 worker / 兜底 Runnable 判断停止（不后台耗电）。 */
    private fun isHeadsUpHostActive(): Boolean {
        val host = headsUpActiveHost?.get() ?: return false
        return try {
            host.isAttachedToWindow && isHeadsUpRootView(host.rootView)
        } catch (t: Throwable) {
            false
        }
    }

    /** [spec/50 + spec/51 统一 + 2026-08-14 spec/56 + 2026-08-15 恢复 worker] 启动/续跑首帧连抓 worker
     *  （首帧后剩余帧的快速周期性刷新；**[2026-08-13 回退主线程同步后，连抓 tick 承担全部 5~6 个节拍含首帧**，
     *  第一 tick 立即执行）。**[2026-08-15 恢复 worker] 每 tick 入队主 worker 队列**
     *  （[scheduleElementCaptures] → [enqueueElementCapture] + [kickElementCaptureWorker]；pending 去重 +
     *  单 worker 串行 = 同刻至多一路 captureDisplay，**恢复 spec/51 单通道收敛**：连抓/持续/主线程触发全走
     *  同一主 worker，captureDisplay 由主 worker 串行执行，杜绝多路并发锤 SF）。
     *  每 tick 先查 [burstStop]——通知消失（[isHeadsUpHostActive] false）立即停止，不后台耗电；
     *  下拉场景 [burstStop] 恒 false（连抓跑完即止）。
     *  [2026-08-13 竞争暂停]：通知场景（pauseDuringPanelExpansion=true）下拉展开期间暂停连抓（下拉优先，
     *  captureDisplay 让路）；下拉场景 false 不暂停（下拉自身连抓照跑覆盖入场动画）。 */
    private fun scheduleHeadsUpBurstWorker(
        frames: Int,
        burstStop: () -> Boolean,
        logTag: String,
        pauseDuringPanelExpansion: Boolean,
    ) {
        if (frames <= 0) return
        headsUpBurstRemaining = frames
        if (headsUpBurstRunning) return
        headsUpBurstRunning = true
        try {
            Thread {
                try {
                    while (true) {
                        // [2026-08-13 延迟调查·竞争暂停] 通知场景（pauseDuringPanelExpansion=true）且面板
                        // 下拉展开中（panelExpansionActive）→ 暂停连抓，避免与下拉抓屏并发锤 captureDisplay
                        // （SF 串行 → 主抓屏被排队拖后 50-150ms）。短等后重查（不消耗剩余帧数），
                        // 下拉结束恢复补帧。
                        if (pauseDuringPanelExpansion && panelExpansionActive) {
                            try {
                                Thread.sleep(HEADS_UP_BURST_RETRY_MS)
                            } catch (ignored: InterruptedException) {
                            }
                            continue
                        }
                        val n = headsUpBurstRemaining
                        if (n <= 0) break
                        headsUpBurstRemaining = n - 1
                        // 停止条件满足（通知已消失等）→ 提前停止连抓
                        if (burstStop()) {
                            Log.i(TAG, "bg-element: $logTag burst stopped, condition met")
                            break
                        }
                        // [2026-08-15 恢复 worker] 连抓 tick 入队主 worker 队列（scheduleElementCaptures →
                        // enqueueElementCapture + kick；captureDisplay 由主 worker 串行执行，本线程零阻塞；
                        // pending 去重 = 与主线程触发/持续 tick 合并为单通道）。markContentChanged 维护
                        // lastContentChangeTimeMs（周期抓屏 idle-skip 不误判）。
                        // [2026-08-15 主动式持续抓屏] force=true：连抓高频补帧不被失败冷却/内容变化节流拦截
                        // （连抓覆盖入场动画窗口 ~300ms，必须每 tick 都尝试入队）。
                        markContentChanged()
                        scheduleElementCaptures(force = true)
                        logThrottled("$logTag-burst-tick", Log.INFO) {
                            "bg-element: $logTag burst tick ${HEADS_UP_BURST_TOTAL_FRAMES - n + 1}/$HEADS_UP_BURST_TOTAL_FRAMES queued to worker (single-channel)"
                        }
                        // 帧间隔（快速连抓覆盖首帧后动画；captureDisplay 自身耗时也计入实际帧率）
                        try {
                            Thread.sleep(HEADS_UP_BURST_FRAME_INTERVAL_MS)
                        } catch (ignored: InterruptedException) {
                        }
                    }
                } finally {
                    headsUpBurstRunning = false
                }
            }.start()
        } catch (t: Throwable) {
            headsUpBurstRunning = false
            Log.e(TAG, "bg-element: spawn $logTag burst worker failed", t)
        }
    }

    /** [2026-08-13 用户要求「通知持续期间必须一直抓屏」+ 2026-08-14 spec/56 + 2026-08-15 恢复 worker +
     *  2026-08-15 主动式持续抓屏 + 2026-08-14 锁屏被动] 启动通知持续期间**不间断**抓屏 worker：heads-up
     *  活跃期间以 [continuousCaptureIntervalMs]（**默认满速 120Hz ~8ms，静止时 60Hz ~16ms，
     *  [isMotionActive] 运动判定**，与下拉面板同一套速率）间隔循环 [scheduleElementCaptures]（**force=true**）→
     *  **[2026-08-15 恢复 worker] 入队主 worker 队列**（pending 去重 + 单 worker 串行 = 单通道，
     *  captureDisplay 由主 worker 串行执行，本线程零阻塞；恢复 spec/51 单通道收敛）。
     *  worker 线程执行，主线程零阻塞（**[2026-08-14 spec/56] worker 队列停用已回退**：持续抓屏为后台循环
     *  （非用户下拉触发），若改主线程会每帧阻塞主线程 = ANR 冻结风险，见 spec/56）。
     *  **[2026-08-15 主动式持续抓屏]** 失败短暂冷却感知：连续失败达 [bgCaptureRetryLimit] 后
     *  [handleElementCaptureFailure] 设冷却 [elementCaptureQuitAt] → worker 睡到冷却结束再重试
     *  （防 spin；冷却已缩短至 300ms，不冻结旧帧）。
     *  通知消失（[isHeadsUpHostActive] false）→ 退出循环并清除活跃宿主（零后台耗电）。 */
    private fun startHeadsUpContinuousCapture() {
        // [2026-08-14 横幅主动源排障] 诊断：isHeadsUpHostActive 拆分量化（host/attach/rootView），
        // 定位主动源启动失败或中途停止根因（日志过滤 heads-up-capture）
        val host = headsUpActiveHost?.get()
        if (host == null) {
            Log.i(TAG, "heads-up-capture: skip start, active host null")
            return
        }
        val attachOk = try { host.isAttachedToWindow } catch (t: Throwable) { false }
        val rootOk = try { isHeadsUpRootView(host.rootView) } catch (t: Throwable) { false }
        if (!attachOk || !rootOk) {
            val rv = try { host.rootView } catch (t: Throwable) { null }
            Log.i(TAG, "heads-up-capture: skip start, attach=$attachOk rootOk=$rootOk root=${rv?.javaClass?.name} type=${rv?.let { reflectWindowType(it) }}")
            return
        }
        if (headsUpContinuousRunning) return
        // [2026-08-14 锁屏被动] 锁屏期间不启动通知持续抓屏——锁屏与解锁桌面一样只走被动事件触发
        // （映射写入 / onBlurReady 等），避免锁屏耗电/不必要抓屏。
        if (isKeyguardLockedNow()) {
            Log.i(TAG, "bg-element: keyguard locked, heads-up continuous capture skipped (passive only)")
            return
        }
        headsUpContinuousRunning = true
        try {
            Thread {
                try {
                    while (true) {
                        // 通知消失（detach/回 shade）→ 停止（零后台耗电）
                        if (!isHeadsUpHostActive()) {
                            val h = headsUpActiveHost?.get()
                            Log.i(TAG, "heads-up-capture: stop, active false host=${h?.javaClass?.simpleName} attach=${try { h?.isAttachedToWindow } catch (t: Throwable) { null }} root=${try { h?.rootView?.javaClass?.name } catch (t: Throwable) { null }}")
                            headsUpActiveHost = null
                            Log.i(TAG, "bg-element: heads-up gone, continuous capture stopped")
                            break
                        }
                        // [2026-08-14 锁屏被动] 运行期间进入锁屏 → 退出主动持续抓屏（被动事件触发不受影响）
                        if (isKeyguardLockedNow()) {
                            headsUpActiveHost = null
                            Log.i(TAG, "bg-element: keyguard locked, heads-up continuous capture stopped (passive only)")
                            break
                        }
                        // [2026-08-14 横幅主动源·根因修复] 移除 panelExpansionActive 暂停：ColorOS QS 收起时
                        // expansion 可能回调不到 0 → panelExpansionActive 卡 true（真机日志实证 "paused,
                        // panelExpansionActive=true"）→ 横幅主动源被永久暂停、静止期零抓屏。横幅场景面板收起、
                        // 无下拉抓屏并发（下拉走 onBlurReady 系统驱动）；且单通道收敛（pending 去重 + 单 worker
                        // 串行）天然防并发锤 SF。主动源停止只靠 isHeadsUpHostActive（通知消失）+ 锁屏。
                        // [2026-08-14 横幅主动源] 移除 sfc stale 兜底停止：横幅出现时 shade 面板通常收起
                        // （shade sfc invalid → force full 抓屏仍工作，但 lastValidSfcCaptureMs 不更新）→
                        // 原逻辑 500ms 后误停，主动源起不来。横幅主动源停止只靠 isHeadsUpHostActive（通知
                        // 消失）+ 锁屏 + panelExpansion 暂停；横幅瞬态（几秒）+ 静止节流 60Hz，CPU 可控。
                        // [2026-08-14 用户要求·去冷却] 移除失败冷却感知（不再设置冷却）
                        // [2026-08-15 恢复 worker] 持续 tick 入队主 worker 队列（scheduleElementCaptures →
                        // enqueueElementCapture + kick；captureDisplay 由主 worker 串行执行，本线程零阻塞；
                        // pending 去重 = 与连抓/主线程触发合并为单通道）。markContentChanged 维护
                        // lastContentChangeTimeMs（周期抓屏 idle-skip 不误判空闲）。
                        // [2026-08-15 主动式持续抓屏] force=true 绕过冷却/内容变化节流；速率可配
                        // [continuousCaptureIntervalMs]（运动满速 120Hz / 静止 60Hz，2026-08-14）。
                        markContentChanged()
                        scheduleElementCaptures(force = true)
                        logThrottled("heads-up-capture-tick", Log.INFO, 500) {
                            "heads-up-capture: tick ok (not paused), panelExpansionActive=$panelExpansionActive"
                        }
                        logThrottled("heads-up-continuous", Log.INFO) {
                            "bg-element: heads-up continuous tick queued to worker (single-channel)"
                        }
                        try {
                            Thread.sleep(continuousCaptureIntervalMs())
                        } catch (ignored: InterruptedException) {
                        }
                    }
                } finally {
                    headsUpContinuousRunning = false
                }
            }.start()
        } catch (t: Throwable) {
            headsUpContinuousRunning = false
            Log.e(TAG, "heads-up-capture: spawn continuous worker failed", t)
        }
    }

    /** [2026-08-15 主动式持续抓屏 + 2026-08-14 锁屏被动] 启动面板（下拉通知栏）展开期间**主动持续抓屏**
     *  worker：面板展开（[panelExpansionActive] true，由 [mountPanelExpansion] 的 onExpansionStarted /
     *  setExpansionHeight>0 置位）期间以 [continuousCaptureIntervalMs]（**默认满速 120Hz ~8ms，
     *  静止时 60Hz ~16ms，[isMotionActive] 运动判定**）间隔循环 [scheduleElementCaptures]
     *  （force=true）→ 入队主 worker 队列（captureDisplay 由主 worker 串行执行，本线程零阻塞；
     *  pending 去重单通道，spec/51 单通道收敛语义）。
     *  **不等事件**——下拉出现立即启动持续抓屏（背景永远最新）；面板收起（setExpansionHeight<=0 置
     *  [panelExpansionActive] false）→ 退出循环（零后台耗电）。
     *  失败短暂冷却感知（同 [startHeadsUpContinuousCapture]）：连续失败达 [bgCaptureRetryLimit] 后睡到
     *  冷却结束再重试（防 spin；冷却 300ms 不冻结旧帧）。
     *  与首帧连抓（[scheduleHeadsUpBurstWorker]）/内容变化触发共存：pending 去重 + 单 worker 串行 = 同刻
     *  至多一路 captureDisplay。 */
    private fun startPanelContinuousCapture() {
        if (panelContinuousRunning) return
        if (!panelExpansionActive) return  // 面板未展开不启动
        // [2026-08-14 锁屏被动] 锁屏期间不启动主动持续抓屏——锁屏与解锁桌面一样只走被动事件触发
        // （映射写入 / onBlurReady / setExpansionHeight 等），避免锁屏耗电/不必要抓屏。
        if (isKeyguardLockedNow()) {
            Log.i(TAG, "bg-element: keyguard locked, panel continuous capture skipped (passive only)")
            return
        }
        panelContinuousRunning = true
        try {
            Thread {
                try {
                    while (true) {
                        // 面板收起（setExpansionHeight<=0）→ 停止（零后台耗电）
                        if (!panelExpansionActive) {
                            Log.i(TAG, "bg-element: panel collapsed, continuous capture stopped")
                            break
                        }
                        // [2026-08-14 CPU 修复] sfc 长期无效兜底停止：panelExpansionActive 卡 true（收起时
                        // setExpansionHeight<=0 未回调）时，sfc 一直拿不到（shade 不在渲染）→ 超时停止，
                        // 杜绝 120Hz 空转耗电。sfc 恢复（下次真正下拉 attach）由 startPanelContinuousCapture
                        // 重新启动。
                        if (SystemClock.uptimeMillis() - lastValidSfcCaptureMs > CONTINUOUS_CAPTURE_STOP_SFC_STALE_MS) {
                            Log.i(TAG, "bg-element: shade sfc stale (not rendering), panel continuous capture stopped (CPU guard)")
                            break
                        }
                        // [2026-08-14 锁屏被动] 运行期间进入锁屏（锁屏下拉被系统拦截 / 锁屏过程中面板仍
                        // 展开）→ 退出主动持续抓屏（被动事件触发不受影响），解锁后下次下拉再启动。
                        if (isKeyguardLockedNow()) {
                            Log.i(TAG, "bg-element: keyguard locked, panel continuous capture stopped (passive only)")
                            break
                        }
                        // [2026-08-14 用户要求·去冷却] 移除失败冷却感知（不再设置冷却）
                        markContentChanged()
                        scheduleElementCaptures(force = true)
                        logThrottled("panel-continuous", Log.INFO) {
                            "bg-element: panel continuous tick queued to worker (single-channel)"
                        }
                        try {
                            Thread.sleep(continuousCaptureIntervalMs())
                        } catch (ignored: InterruptedException) {
                        }
                    }
                } finally {
                    panelContinuousRunning = false
                }
            }.start()
        } catch (t: Throwable) {
            panelContinuousRunning = false
            Log.e(TAG, "bg-element: spawn panel continuous capture worker failed", t)
        }
    }

    /** [doc/spec/49 2026-08-13] 内容变化信号：记录当前时刻到 [lastContentChangeTimeMs]（周期抓屏据此判空闲）。
     *  所有内容变化触发点（新映射写入 / onBlurReady 新帧 / setExpansionHeight / heads-up 卡同卡更新）经
     *  [triggerBackgroundCapture]（contentChange=true）更新；周期抓屏自身不算内容变化（传 false 不更新）。 */
    private fun markContentChanged() {
        lastContentChangeTimeMs = SystemClock.uptimeMillis()
    }

    /** 内容变化调度：**[2026-08-15 恢复 worker] 入队 → worker 消费**（[enqueueElementCapture] +
     *  [kickElementCaptureWorker]，异步，主线程零阻塞；captureDisplay 在 worker 线程同步执行
     *  [captureSnapshotImmediate]，失败走 [scheduleCaptureRetry] 快速重试）。
     *  **spec/56 worker 停用（主线程直接抓屏）已回退**——根因 A（孤儿竞态）+ 根因 B（失败快速路径）已修复，
     *  worker 队列恢复为「入队 → worker 消费」单通道（pending 去重 + 单 worker 串行 = 同刻至多一路
     *  captureDisplay，连抓/持续 worker 入队也被吸收，恢复 spec/51 单通道收敛语义）。
     *  仍保留 [spec/17] KEY_BG_CAPTURE_MIN_INTERVAL_MS 节流（默认 0=无节流立即抓）+ 失败冷却
     *  [elementCaptureQuitAt]（冷却期跳过不抓）。
     *
     *  **[2026-08-15 主动式持续抓屏·移除失败冷却硬拦截]** `force=true`（主动持续抓屏 tick / 失败快速重试）：
     *  绕过失败冷却 [elementCaptureQuitAt] 与内容变化节流 [KEY_BG_CAPTURE_MIN_INTERVAL_MS]——失败后 60ms
     *  快速重试不冻结 1s，背景不冻结旧帧（持续抓屏速率由 worker 自身 [continuousCaptureIntervalMs] 控制，
     *  不应被内容变化节流二次限频）。`force=false`（普通内容变化触发 [triggerBackgroundCapture] / 空置抓屏
     *  [maybeIdleCapture]）：保留冷却/节流检查，但冷却已缩短至 [ELEMENT_CAPTURE_COOLDOWN_MS]=300ms 短暂
     *  冷却（连续失败达 [bgCaptureRetryLimit] 才进入，防无限 spin 且不长时间冻结）。 */
    private fun scheduleElementCaptures(force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        // [2026-08-14 用户要求·去冷却] 移除失败冷却检查——sfc 拿不到改为强制抓全屏（不进入失败路径），
        // capture 失败静默放弃靠事件重试，无需 300ms 冷却等待。
        // [spec/17 配置接线] 内容变化节流：距上次实际入队 < 最小间隔 → 跳过（[2026-08-13 回退 spec/49
        // 固定 200ms] 默认 0=无节流立即抓；用户手动配置 >0 仍按最小间隔限频；force=true 绕过——持续抓屏
        // 速率由 worker 自控）
        val minInterval = bgCaptureMinIntervalMs()
        if (!force && minInterval > 0) {
            if (now - lastCaptureTime < minInterval) return
            lastCaptureTime = now
        }
        // [2026-08-15 恢复 worker] 入队 → kick 起 worker 消费（异步，主线程零阻塞）。无论 enqueue 是否实际
        // 入队都 kick（pending 去重命中 = 队列已有任务待消费或 worker 正在抓，kick 内部 running>0 / 队列空
        // 早退无副作用；孤儿残留场景也能经 kick 的「队列非空 + running=0 → 重新起 worker」恢复）。
        enqueueElementCapture(SNAPSHOT_ID)
        kickElementCaptureWorker()
    }

    /** [2026-09-17 需求变更·检查变化] 上一帧整屏快照的内容指纹（0 = 尚无 / 位图不可读）。 */
    private var lastSnapshotFingerprint = 0

    /** [2026-09-17 需求变更·检查变化] 快照内容指纹：24×24 均匀网格采样像素值滚动散列。
     *
     *  用途：三种瞬态场景高频强制抓时，**内容没变就不更新快照、不通知重绘** —— 省掉
     *  invalidate → 重录 → RenderThread 绘制整条链（火焰图实证 RenderThread 占 45%）。
     *  代价 ~576 次 `getPixel`（软件位图、worker 线程，亚毫秒级），相对一次 captureDisplay
     *  （20-50ms）可忽略。
     *
     *  返回 0 = 读不了（硬件位图等）→ 调用方按「有变化」处理，保证不错过更新。
     *  ⚠️ 网格取 24 是权衡：太稀会漏掉局部变化（背景卡住），太密则抬高每次抓屏耗时。 */
    private fun snapshotContentFingerprint(bmp: android.graphics.Bitmap): Int {
        return try {
            val stepX = (bmp.width / 24).coerceAtLeast(1)
            val stepY = (bmp.height / 24).coerceAtLeast(1)
            var h = 17
            var y = 0
            while (y < bmp.height) {
                var x = 0
                while (x < bmp.width) {
                    h = h * 31 + bmp.getPixel(x, y)
                    x += stepX
                }
                y += stepY
            }
            h
        } catch (t: Throwable) {
            0
        }
    }

    /** **[2026-08-15 恢复 worker] 整屏快照抓屏执行体**（由抓屏 worker 调用，spec/56 保留逻辑复用）：
     *  当前线程**同步** resolveShadeSfcOrFallback（spec/45 兜底）→ captureScreenSnapshot（复用
     *  captureElementBackground 的排除 shade + heads-up 逻辑 + 降采样）→ 立即 screenSnapshot = bg →
     *  postInvalidateHosts（下帧渲染用新快照）。
     *
     *  - **调用线程**：[runElementCaptureWorker] 消费队列时在 worker 线程调用（主线程零阻塞）；
     *    连抓/持续 worker 入队后也由主 worker 串行执行（单通道，spec/51 收敛语义恢复）。
     *  - **失败路径（根因 B 修复）**：shade sfc 解析失败 → 60ms 短延迟快速重试（[scheduleCaptureRetry]，
     *    连续失败达 [bgCaptureRetryLimit] 才走冷却，非 320ms 长 sleep）；capture 失败 →
     *    [handleElementCaptureFailure]（快速重试 + 冷却，冷却 [ELEMENT_CAPTURE_COOLDOWN_MS] 已缩短至 1s）。
     *  - **文字亮度/重采样**（updateQsBrightness 2.6MB 全图扫描）：同步做会卡 → 拆后台单飞
     *    [runTextContrastScanAsync]（不阻塞抓屏线程/主线程/渲染；拖动期 isMotionActive 跳过，spec/49 C 保留）。
     */
    private fun captureSnapshotImmediate(source: String) {
        // [spec/58 追加 2026-08-15 诊断日志] 主线程同步首帧链路判定：source 以 `-main-thread` 结尾 =
        // 下拉/通知首帧主线程同步路径（worker 路径 source="worker" 不打 lg-first，防刷屏）
        val mainThreadSync = source.endsWith("-main-thread")
        if (mainThreadSync) diagFirstLog("sfc-start", " source=$source")
        // [2026-09-17 修复·强制抓被「让路」逻辑吃掉] **区分「真 shade」与「fallback 兜底层」**：
        // resolveShadeSfcOrFallback 在真 shade 解析不到时，会用 fallback（heads-up / simple-banner 等
        // 玻璃窗口）顶替。若拿它去更新 lastValidSfcCaptureMs，则 isShadeRenderingNow() 会在
        // **面板其实已收起**时恒为 true → periodicCaptureRunnable 的三种场景强制抓被
        // 「if (!isShadeRenderingNow())」全部吃掉（真机实证：lg-scene 打出 force=true 但
        // shadeRendering=true，横幅活跃期间一次都没抓）。
        // 故先探真 shade，只有**真 shade** 有效才算「shade 在渲染」。
        val realShade = resolveShadeSfc()
        val realShadeValid = realShade != null && realShade.isValid
        val shade = if (realShadeValid) realShade else resolveShadeSfcOrFallback()
        if (mainThreadSync) diagFirstLog("sfc-done", if (shade != null && shade.isValid) " valid" else " null/invalid")
        // [2026-08-14 用户要求·sfc 拿不到强制抓全屏] shade 可空传入 captureScreenSnapshot：有效 → exclude shade
        // 抓（防自采样）；null/invalid（shade 不在渲染）→ 无 exclude 强制抓全屏——快照天然不含 shade（干净），
        // 不再重试/冷却/静默放弃。shade 在渲染但 sfc 短暂 invalid 时轻微自采样，sfc 恢复后 exclude 抓屏覆盖。
        val bg = captureScreenSnapshot(shade)
        if (bg != null) {
            sfcFailCount = 0
            elementCaptureRetries[SNAPSHOT_ID] = 0
            elementCaptureQuitAt.remove(SNAPSHOT_ID)
            // [2026-08-14 CPU 修复] shade sfc 有效 = shade 在渲染（面板展开）→ 记录时刻，持续抓屏据此
            // 判断是否该停（sfc 长期无效 = 收起 → 停止 120Hz 空转）
            // [2026-09-17 修复] 只用**真 shade** 更新（fallback 兜底层不算「shade 在渲染」，见上方说明）
            if (realShadeValid) lastValidSfcCaptureMs = SystemClock.uptimeMillis()
            // [2026-09-17 需求变更·检查变化] 内容指纹比对：**内容未变 → 丢弃本帧**。
            // 三种瞬态场景（heads-up / 流体云卡 / simple-banner）走高频强制抓，但其中大量帧内容完全相同
            //（悬浮玻璃底下没动）—— 若照旧更新快照 + postInvalidateHosts，就会白白驱动
            // invalidate → 重录 → RenderThread 重绘整条链（火焰图实证 RenderThread 占 45%）。
            // 用户要求：「改成检查变化」。代价 = 576 次 getPixel（<1ms，worker 线程），
            // 远小于一次 captureDisplay（20-50ms）。
            // fp==0 = 位图不可读（硬件位图等）→ 按「有变化」处理，保证不错过更新。
            val fp = snapshotContentFingerprint(bg.bitmap)
            if (fp != 0 && fp == lastSnapshotFingerprint) {
                logThrottled("snapshot-unchanged", Log.DEBUG) {
                    "bg-element: snapshot unchanged, frame dropped (no invalidate)"
                }
                return
            }
            lastSnapshotFingerprint = fp
            screenSnapshot = bg
            if (mainThreadSync) diagFirstLog("snapshot-replace", " source=$source")
            if (textContrastEnabled && !isMotionActive) {
                runTextContrastScanAsync(bg)
            }
            val exclTag = if (shade?.isValid == true) "excl shade" else "force full"
            logThrottled("snapshot-captured", Log.INFO) { "bg-element: snapshot captured ${bg.bitmap.width}x${bg.bitmap.height} region=${bg.region.toShortString()} ($exclTag, source=$source)" }
            postInvalidateHosts()
        } else {
            // [2026-08-14 用户要求·去冷却] capture 失败 → 静默放弃，不重试不冷却，靠后续事件触发重新发起
            if (mainThreadSync) diagFirstLog("fail-capture", " source=$source")
            logThrottled("capture-fail-skip", Log.DEBUG) { "bg-element: capture failed, skip (no retry/cooldown, wait event)" }
        }
    }

    /** [2026-08-14 spec/56 + 2026-08-15 恢复 worker + 2026-08-15 主动式持续抓屏] 抓屏重试调度（延迟后重新走
     *  [scheduleElementCaptures] → 入队 worker 消费，[2026-08-15 恢复 worker] 重试重新入队，不再主线程直接
     *  抓屏；**[2026-08-15 主动式持续抓屏] force=true 绕过失败冷却**——失败快速重试（60ms/320ms）不被
     *  [elementCaptureQuitAt] 冷却硬拦截，背景不冻结旧帧）：
     *  - 主线程 → mainHandler().postDelayed 延迟重试（**不 sleep 阻塞主线程**，单飞防重复 post）；
     *  - 后台线程（worker 内 / 连抓/持续 worker）→ 就地 sleep 后直接 [scheduleElementCaptures]
     *    （入队 + kick：当前 worker 在 running 时 kick 早退、worker 主循环 poll 到重试任务立即重试；
     *    worker 已退出时 kick 重新起 worker）。 */
    private fun scheduleCaptureRetry(delayMs: Long) {
        // [spec/58 追加 2026-08-15 + spec/59 追加 2026-08-14] 失败/重试路径（lg-first）：主线程同步首帧失败后
        // 的快速重试回退 worker——「fail-sfc/fail-capture → retry-scheduled」= 失败被捕获后多久安排重试。
        // [spec/59] 去掉 isMainThreadNow 门控（worker 也打）：worker 卡 sfc 失败重试循环时每 320ms 一条
        // retry-scheduled（thread=worker）= 识别竞态窗口候选根因 A（sfc 失败重试循环）。
        diagFirstLog("retry-scheduled", " delay=${delayMs}ms thread=${if (isMainThreadNow()) "main" else "worker"}")
        if (android.os.Looper.myLooper() === android.os.Looper.getMainLooper()) {
            if (captureRetryPosted) return
            captureRetryPosted = true
            try {
                mainHandler().postDelayed({
                    captureRetryPosted = false
                    scheduleElementCaptures(force = true)
                }, delayMs)
            } catch (t: Throwable) {
                captureRetryPosted = false
            }
        } else {
            try {
                Thread.sleep(delayMs)
            } catch (ignored: InterruptedException) {
            }
            scheduleElementCaptures(force = true)
        }
    }

    /** [2026-08-14 spec/56] 快照就绪后的文字亮度全图扫描拆后台单飞执行（原 worker 内联：
     *  updateQsBrightness 2.6MB getPixels + resampleAllTextContrast）。主线程直接抓屏时同步做会卡 ANR；
     *  单飞防并发扫描。 */
    private fun runTextContrastScanAsync(bg: ElementBackground) {
        if (textScanRunning) return
        textScanRunning = true
        try {
            Thread {
                try {
                    updateQsBrightness(bg)
                    mainHandler().post { resampleAllTextContrast() }
                } catch (t: Throwable) {
                    Log.e(TAG, "bg-element: text contrast scan failed", t)
                } finally {
                    textScanRunning = false
                }
            }.start()
        } catch (t: Throwable) {
            textScanRunning = false
            Log.e(TAG, "bg-element: spawn text contrast scan thread failed", t)
        }
    }

    /** [2026-08-15 恢复 worker] 入队待抓（pending 去重：同 id 已在队列/处理中则跳过，防重复入队积压）。
     *  worker 出队时移除；失败重试走 [scheduleCaptureRetry] 重新入队。@return 是否实际入队
     *  （false = 已在队列/处理中——此时队列必有任务待消费或 worker 正在抓，无需重复入队）。 */
    private fun enqueueElementCapture(id: Int): Boolean {
        if (pendingCaptureIds.add(id)) {
            elementCaptureQueue.add(id)
            return true
        }
        return false
    }

    /** [2026-08-15 恢复 worker + 根因 A 修复] 启动/续跑抓屏 worker（固定单 worker 串行：队列当前唯一任务 =
     *  SNAPSHOT_ID 整屏快照，pendingCaptureIds 去重后同刻最多 1 个任务，并发无意义；单线程串行抓屏与
     *  一致）。synchronized(this) 内原子检查：
     *  - `running>0` 早退：已有 worker 在跑（会继续消费队列），不重复起线程；
     *  - `queue.isEmpty()` 早退：无任务不起空 worker（防止 enqueue 返回 false 的孤儿残留场景空转）；
     *  - **队列非空但 running==0**：上次 worker 退出竞态窗口残留（poll==null 后新任务入队、kick 见
     *    running=1 早退、worker 减计数退出）→ 重新起 worker 消费，杜绝「孤儿队列 + pending 残留 +
     *    running=0 → 永久不消费」。 */
    private fun kickElementCaptureWorker() {
        synchronized(this) {
            if (elementCaptureWorkerRunning > 0) return  // 已有 worker 在跑，不重复起线程
            if (elementCaptureQueue.isEmpty()) {
                // [spec/59 追加 2026-08-14 诊断] 队列空但 pending 残留 = 孤儿场景（kick 起不了 worker 但任务
                // 卡住，永久不消费）——竞态窗口根因候选 C。正常「队列空 + pending 空」静默不打。
                if (pendingCaptureIds.isNotEmpty()) {
                    diagWorkerLog("kick-orphan", " pending=$pendingCaptureIds running=$elementCaptureWorkerRunning")
                }
                return  // 无任务不起空 worker
            }
            elementCaptureWorkerRunning++
            diagWorkerLog("worker-spawn", " running=$elementCaptureWorkerRunning queue=${elementCaptureQueue.size} pending=$pendingCaptureIds")
            try {
                Thread { runElementCaptureWorker() }.start()
            } catch (t: Throwable) {
                elementCaptureWorkerRunning--
                Log.e(TAG, "bg-element: spawn worker failed", t)
            }
        }
    }

    /** [2026-08-15 恢复 worker] 抓屏 worker：消费待抓队列。整屏快照任务（SNAPSHOT_ID）由
     *  [captureSnapshotImmediate] 执行（spec/56 保留逻辑：resolveShadeSfcOrFallback → captureScreenSnapshot
     *  → screenSnapshot = bg → postInvalidateHosts；sfc 失败 60ms 快速重试 / capture 失败
     *  [handleElementCaptureFailure] 快速重试 + 冷却）。pendingCaptureIds 去重保证同任务不被重复抓；
     *  单 worker 串行（每元素 crop 任务已废弃，仅 SNAPSHOT_ID）。
     *
     *  **[根因 A 修复·finally 原子清理 + 自续跑]** 孤儿竞态：worker 在 `poll()==null` → finally 减计数之间
     *  running 仍=1；此窗口主线程 [enqueueElementCapture] 入队 + kick 见 running=1 早退 → worker 减到 0 退出
     *  → 队列留 SNAPSHOT_ID、pendingCaptureIds 卡住、running=0、无 worker → 以后每次入队 pending.add 返回
     *  false → **永久不消费**。修复 = finally 在**同一 synchronized(this)** 内原子完成：running-- 复位 +
     *  队列非空则**自续跑**（竞态窗口入队的任务立即被新 worker 消费）+ 队列空则清空残留 pending
     *  （保证下次入队 pending.add 成功）。 */
    private fun runElementCaptureWorker() {
        try {
            while (true) {
                val id = elementCaptureQueue.poll() ?: break
                pendingCaptureIds.remove(id)  // 出队即移除：处理中/失败重试均可重新入队
                if (id != SNAPSHOT_ID) continue  // 仅整屏快照任务（每元素 crop 任务已废弃）
                // 抓屏中标记（poll 出队 → capture 完成期间）：渲染触发异步请求（requestScreenSnapshotAsync）
                // 见此 SNAPSHOT_ID 即不重复入队（避免双路 captureDisplay）
                captureInFlightIds.add(id)
                try {
                    captureSnapshotImmediate("worker")
                } finally {
                    captureInFlightIds.remove(id)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "bg-element: worker error", t)
        } finally {
            synchronized(this) {
                if (elementCaptureWorkerRunning > 0) elementCaptureWorkerRunning--
                if (elementCaptureQueue.isEmpty()) {
                    // 队列空（正常消费完）→ 清任何残留 pending（防御：极端时序下 poll 出队后、pending.remove
                    // 前有并发入队又被本 worker 消费，或竞态窗口入队但未入队成功的残留；保证下次入队
                    // pending.add 成功）。正常路径 pending 应为空，此分支仅日志不误删在途任务。
                    if (pendingCaptureIds.isNotEmpty()) {
                        Log.w(TAG, "bg-element: worker exit, clear orphan pending=$pendingCaptureIds")
                        pendingCaptureIds.clear()
                    }
                    // [spec/59 追加 2026-08-14 诊断] worker 正常退出（队列空）——对比 worker-spawn 看 worker
                    // 存活时长；「短命 worker」= 消费完即退出，靠下次入队重新 kick（正常）；「长期无 exit」=
                    // worker 存活/卡住（与 captureDisplay-start 间隔对照判断是否卡 Binder 或 sfc 重试循环）
                    diagWorkerLog("worker-exit", " queue-empty running=$elementCaptureWorkerRunning")
                } else {
                    // 队列仍有未消费任务（poll==null 到 running-- 之间竞态窗口入队 + kick 早退残留）
                    // → 自续跑新 worker 消费，杜绝「孤儿队列 + pending 残留 + running=0 永久不消费」
                    elementCaptureWorkerRunning++
                    diagWorkerLog("worker-respawn", " queue=${elementCaptureQueue.size} running=$elementCaptureWorkerRunning")
                    try {
                        Thread { runElementCaptureWorker() }.start()
                    } catch (t: Throwable) {
                        elementCaptureWorkerRunning--
                        Log.e(TAG, "bg-element: respawn worker failed", t)
                    }
                }
            }
        }
    }

    /** [spec/45 提取 + 2026-08-14 spec/56 + 2026-08-15 恢复 worker] 抓屏失败重试路径（capture 失败 /
     *  shade sfc 解析失败共用）：RETRY_LIMIT 次/RETRY_MS 间隔（默认 3 次/320ms），用尽冷却
     *  [ELEMENT_CAPTURE_COOLDOWN_MS]（[2026-08-15 根因 B 修复] 2000 → 1000ms，不长时间挡住新内容变化触发）。
     *  重试经 [scheduleCaptureRetry]（主线程 postDelayed 不阻塞 / 后台线程就地 sleep 后）→
     *  [scheduleElementCaptures] 重新入队 worker（[2026-08-15 恢复 worker]）。 */
    private fun handleElementCaptureFailure(id: Int, reason: String) {
        val retryLimit = bgCaptureRetryLimit()
        val retryMs = bgCaptureRetryMs()
        val n = (elementCaptureRetries[id] ?: 0) + 1
        elementCaptureRetries[id] = n
        if (n < retryLimit) {
            Log.w(TAG, "bg-element: snapshot $reason, retry $n/$retryLimit")
            scheduleCaptureRetry(retryMs.toLong())
        } else {
            elementCaptureRetries[id] = 0
            elementCaptureQuitAt[id] = SystemClock.uptimeMillis() + ELEMENT_CAPTURE_COOLDOWN_MS
            Log.w(TAG, "bg-element: snapshot $reason $retryLimit x, cooldown ${ELEMENT_CAPTURE_COOLDOWN_MS}ms")
            // [spec/58 追加 2026-08-15 诊断日志] 主线程首帧路径连续失败进冷却（lg-first）：确认失败重试用尽、
            // 走短暂冷却（300ms 防 spin），之后 force=true 持续抓屏恢复
            if (isMainThreadNow()) diagFirstLog("retry-cooldown", " reason=$reason n=$n")
        }
    }

    /**
     * [spec/45] 解析抓屏排除用 sfc（非静默）：优先 shade 根 sfc（[resolveShadeSfc]）；null/invalid 时
     *  打日志并兜底「任一已 attach 宿主窗口根 surface」（[resolveAnyAttachedHostSfc]，参考
     *  LauncherHook.resolveLauncherSfc 思路）→ 再兜底 heads-up 窗口 sfc（[resolveHeadsUpSfc]，通知到达时
     *  heads-up 卡宿主 attach）。全部失败返回 null（worker 走失败重试路径，不再裸 continue）。
     *  @Synchronized：registeredHostViews 遍历防 CME（worker 线程调用；内部再调 @Synchronized 方法为
     *  可重入，无死锁）。
     */
    @Synchronized
    private fun resolveShadeSfcOrFallback(): SurfaceControl? {
        // [spec/59 追加 2026-08-14 诊断] 解析前快照缓存引用（resolveShadeSfc 内部 self-heal 可能清缓存，
        // 先拍快照才能定位「到底哪路断」；name 反射等重活放 logThrottled lambda 内打印时才做）
        val preSfc = shadeSfcCache
        val preRoot = shadeRootViewCache
        val preRootAttached = preRoot?.isAttachedToWindow
        val shade = resolveShadeSfc()
        if (shade != null && shade.isValid) return shade
        // [2026-08-14 用户要求·不刷屏] sfc 拿不到现为常态（收起/锁屏 shade 不在渲染）→ 强制抓全屏兜底，
        // 两条 fallback 日志降为节流（真异常/低频保留诊断，持续抓屏不再刷屏）
        logThrottled("sfc-fallback-anyhost", Log.DEBUG) { "bg-element: resolveShadeSfc null/invalid, trying any-attached-host-root fallback" }
        val host = resolveAnyAttachedHostSfc()
        if (host != null && host.isValid) {
            Log.i(TAG, "bg-element: fallback sfc from attached host root, name=${reflectSurfaceControlName(host)}")
            return host
        }
        logThrottled("sfc-fallback-headsup", Log.DEBUG) { "bg-element: any-attached-host fallback null/invalid, trying heads-up window sfc" }
        val hu = resolveHeadsUpSfc()
        if (hu != null && hu.isValid) {
            Log.i(TAG, "bg-element: fallback sfc from heads-up window, name=${reflectSurfaceControlName(hu)}")
            return hu
        }
        // [spec/59 追加 2026-08-14 诊断] 三路全断：输出解析前缓存状态，定位 sfc 失败根因
        //（sfcCache 被 detach 清空 / rootCache stale 或 hook 没挂上 / 宿主全 detached）。
        // 用 diagWorkerLog（lg-wk，无节流，带 sinceAnchor/sinceLast）——失败重试循环每轮必打，不被 logThrottle 吞
        diagWorkerLog(
            "sfc-miss",
            "before={sfcCache=${preSfc?.let { if (it.isValid) "valid(${reflectSurfaceControlName(it)})" else "invalid" } ?: "null"} " +
                "rootCache=${preRoot?.let { "${it.javaClass.simpleName}(attached=$preRootAttached)" } ?: "null"} " +
                "hostsAttached=${registeredHostViews.count { e -> e.ref.get()?.isAttachedToWindow == true }}} " +
                "after={anyHost=${host?.let { if (it.isValid) "valid(${reflectSurfaceControlName(host)})" else "invalid" } ?: "null"} " +
                "headsUp=${hu?.let { if (it.isValid) "valid(${reflectSurfaceControlName(hu)})" else "invalid" } ?: "null"}}"
        )
        return null
    }

    /** [spec/45] 兜底解析「任一已 attach 宿主窗口根」surface：遍历 registeredHostViews，取第一个 attach
     *  宿主的 rootView surface（参考 LauncherHook.resolveLauncherSfc 思路）。shade 折叠期 shade 根
     *  遍历不到时，heads-up 卡宿主 attach → 命中 heads-up 窗口根。失败 null（worker 走重试）。 */
    @Synchronized
    private fun resolveAnyAttachedHostSfc(): SurfaceControl? {
        for (entry in registeredHostViews) {
            val v = entry.ref.get() ?: continue
            if (!v.isAttachedToWindow) continue
            val rootView = try {
                v.rootView
            } catch (t: Throwable) {
                null
            }
            if (rootView == null) continue
            // [2026-09-17 修复·玻璃内容冻结/不跟手] **只认「玻璃自身窗口」**。
            // 原实现取「第一个 attached 宿主」的 rootView surface 且**不判类名**，而 registeredHostViews
            // 里登记着桌面（Launcher）等无关窗口 → 一旦它排在前面，返回的 sfc 就是桌面窗口根 →
            // 被 captureElementBackground 塞进 excludes → **抓屏把桌面挖掉** → 玻璃里没有底层内容，
            // 滑桌面也永远不变（真机实证：滑桌面时 chg 仍≈0，玻璃冻结/更新慢）。
            // 本函数的设计意图（见上方注释）是「shade 折叠期用 heads-up 窗口当排除层防自采样」，
            // 只有玻璃自己的窗口才是合法排除对象；其余一律跳过，让抓屏抓全屏。
            if (!isGlassOwnWindowRoot(rootView)) {
                logThrottled("anyhost-skip", Log.INFO) {
                    "bg-element: any-host fallback skip ${rootView.javaClass.simpleName} (not a glass window)"
                }
                continue
            }
            val sfc = reflectViewRootSurfaceControl(rootView)
            if (sfc != null && sfc.isValid) {
                Log.i(TAG, "bg-element: fallback sfc from attached host root, root=${rootView.javaClass.name}")
                return sfc
            }
        }
        return null
    }

    /** [2026-09-17] 「玻璃自身窗口」判定：只有这些窗口允许作为抓屏排除层（防玻璃自采样）。
     *  非玻璃窗口（Launcher / 其他 app 窗口）被误排除 → 抓回来的画面缺失该层 → 玻璃内容冻结/不跟手。
     *
     *  ⚠️ **必须纯类名匹配、零反射**：本函数在 `resolveAnyAttachedHostSfc` 里**逐个宿主**调用，
     *  而后者在抓屏热路径上（每 ~8ms 一次）。早先版本复用了 [isHeadsUpRootView] /
     *  [isSimpleBannerRootView]，它们在类名不匹配时会继续走 `reflectWindowType` /
     *  `reflectWindowTitle`（各含 getViewRootImpl→getWindowAttributes→取值 多次反射）——
     *  对**每个非玻璃窗口**都要把这几套反射跑一遍 → 每秒上万次反射调用 → **下拉控制中心卡顿**
     *  （真机实证：关掉运动追踪器后卡顿依旧，定位到这里）。
     *
     *  **代价可接受**：类名不匹配者（原本靠窗口 type 2017 兜底命中的）现在**不排除** ——
     *  后果只是"少排一层"（轻微自采样），远好于卡顿；且绝不会再误排除桌面层。 */
    private fun isGlassOwnWindowRoot(rootView: View): Boolean {
        val name = rootView.javaClass.name
        return name.contains("NotificationShadeWindowView") ||
            name.contains("HeadsUpLayout") ||
            name.contains("HeadsUpContainerWindow") ||
            name.contains("FullScreenBanner") ||
            name.contains("SimpleBanner")
    }

    /** 主线程 Handler（惰性创建一次；worker → 主线程 post invalidate 用） */
    @Volatile
    private var mainHandler: android.os.Handler? = null

    private fun mainHandler(): android.os.Handler {
        mainHandler?.let { return it }
        return synchronized(this) {
            mainHandler ?: android.os.Handler(android.os.Looper.getMainLooper()).also { mainHandler = it }
        }
    }

    /** [worker → 主线程] 新元素背景就绪且存在回退标记时，主线程 invalidate 宿主强制重录重试玻璃。 */
    private fun postInvalidateHosts() {
        try {
            mainHandler().post { invalidateRegisteredHostViews() }
        } catch (t: Throwable) {
            Log.e(TAG, "bg-element: postInvalidateHosts failed", t)
        }
    }

    /**
     * 解析 shade 窗口根 SurfaceControl：优先缓存；否则遍历已登记宿主 View（registerHostView 登记的
     * tile/卡片），取 attach 宿主 rootView（= NotificationShadeWindowView）→
     * ViewRootImpl.getSurfaceControl()（shade 窗口根）。@SystemApi 反射调用，失败返回 null（静默）。
     *
     * [spec/19 修正] **只接受 NotificationShadeWindowView 作为 shade 根**：原实现遍历取"第一个
     * attached rootView"，heads-up（独立窗口 type 2017）注册后其 rootView=HeadsUpLayout 可能先被
     * 命中而误当 shade → 快照排除错窗口、玻璃自采样。shade 与 heads-up 两个窗口 surface 分开解析
     * （[resolveHeadsUpSfc]）、分别进 exclude 数组（见 [captureElementBackground]）。
     *
     * @Synchronized：主线程（registerHostView 等）与抓屏 worker 线程并发调用，
     * 防 registeredHostViews（LinkedHashSet）遍历 CME。
     */
    @Synchronized
    private fun resolveShadeSfc(): SurfaceControl? {
        shadeSfcCache?.let { if (it.isValid) return it }
        // [spec/45] shade 根 View 强引用缓存兜底：shade 窗口 View 常驻，折叠期 registeredHostViews
        // 遍历不到也能解析 sfc（isAttachedToWindow 兜底旧根重建失效）。
        // [2026-08-15 偶发不抓屏根治] 缓存根已失效（旧根 detach / 窗口重建后未重新 attach）→ **自愈清空**：
        // 清掉 stale 根 + 失效 sfc，等 attach hook（mountShadeRootAttach）或新宿主登记（registerHostView）
        // 重新填充；若 attach hook 因 final 方法挂载失败，此处保证 stale 根不会长期挡住重解析。
        shadeRootViewCache?.let { root ->
            if (!isShadeRootView(root) || !root.isAttachedToWindow) {
                if (shadeRootViewCache === root) {
                    shadeRootViewCache = null
                    shadeSfcCache = null
                    Log.i(TAG, "bg-source: shade root cache self-healed (stale root detached), will re-resolve")
                }
            } else {
                val sfc = reflectViewRootSurfaceControl(root)
                if (sfc != null && sfc.isValid) {
                    shadeSfcCache = sfc
                    Log.i(TAG, "bg-source: shade sfc resolved from cached root ${root.javaClass.simpleName}, name=${reflectSurfaceControlName(sfc)}")
                    return sfc
                }
            }
        }
        // 只遍历不 remove（可能在 registerHostView 等外层迭代中调用，内层 remove 会触发 CME；
        // 失效弱引用由 registerHostView / invalidateRegisteredHostViews 的正常清理路径负责）
        for (entry in registeredHostViews) {
            val v = entry.ref.get() ?: continue
            if (!v.isAttachedToWindow) continue
            val rootView = try {
                v.rootView  // public API：返回窗口根 View（shade 窗口 = NotificationShadeWindowView）
            } catch (t: Throwable) {
                null
            }
            if (rootView == null) continue
            // [spec/19] 只认 shade 根（NotificationShadeWindowView）；heads-up 的 HeadsUpLayout 不是 shade
            if (!isShadeRootView(rootView)) continue
            val sfc = reflectViewRootSurfaceControl(rootView)
            if (sfc != null && sfc.isValid) {
                shadeSfcCache = sfc
                Log.i(TAG, "bg-source: shade sfc resolved from ${rootView.javaClass.simpleName}, name=${reflectSurfaceControlName(sfc)}")
                return sfc
            }
        }
        return null
    }

    /** [spec/19] shade 窗口根 View 判定：类名含 NotificationShadeWindowView（com.android.systemui.shade 实证）。 */
    private fun isShadeRootView(rootView: View): Boolean =
        rootView.javaClass.name.contains(SHADE_ROOT_VIEW_CLASS_HINT)

    /**
     * [spec/19] 解析 heads-up 窗口（type 2017）根 SurfaceControl：遍历已登记宿主（heads-up 卡片
     * NotificationBackgroundView 也会被 recordNotificationCardRegion 登记），找 rootView=HeadsUpLayout
     * 的窗口根。与 shade 分开解析（resolveShadeSfc 已不再接受非 shade 根），两者独立进 exclude 数组。
     * 无 heads-up → null（快照只排除 shade）。@Synchronized 防遍历 CME。
     */
    @Synchronized
    private fun resolveHeadsUpSfc(): SurfaceControl? {
        headsUpSfcCache?.let { if (it.isValid) return it }
        // [2026-08-13 captureDisplay 开始延迟优化] 负缓存：无 heads-up 遍历失败后 [HEADS_UP_SFC_NEG_CACHE_MS]
        // 内直接返回 null，不重复遍历 registeredHostViews（每宿主一次窗口类型反射 = 每次抓屏额外开销）。
        // 新宿主登记（registerHostView）清零 → 通知出现时立即重新解析。
        val now = SystemClock.uptimeMillis()
        if (now < headsUpSfcMissAt) return null
        for (entry in registeredHostViews) {
            val v = entry.ref.get() ?: continue
            if (!v.isAttachedToWindow) continue
            val rootView = try {
                v.rootView
            } catch (t: Throwable) {
                null
            }
            if (rootView == null) continue
            if (!isHeadsUpRootView(rootView)) continue
            val sfc = reflectViewRootSurfaceControl(rootView)
            if (sfc != null && sfc.isValid) {
                headsUpSfcCache = sfc
                Log.i(TAG, "bg-source: heads-up sfc resolved from ${rootView.javaClass.simpleName}, name=${reflectSurfaceControlName(sfc)}")
                return sfc
            }
        }
        headsUpSfcMissAt = now + HEADS_UP_SFC_NEG_CACHE_MS
        return null
    }

    /** [spec/19] heads-up 窗口根 View 判定：rootView=HeadsUpLayout（HeadsUpContainerWindow ccView，
     *  反编译实证 com.oplus.systemui.notification.headsup.windowframe.HeadsUpLayout）；
     *  兜底按窗口类型 type==2017（TYPE_STATUS_BAR_SUB_PANEL，HeadsUpContainerWindow lp 实证）。 */
    /** [2026-09-17 性能] rootView → 「窗口类型是否 heads-up」的反射结果缓存。
     *
     *  `reflectWindowType` 每次要三跳反射（getViewRootImpl → getWindowAttributes → 读 type 字段），
     *  而本函数被 `isInHeadsUpWindow` 对**每个文字 View** 调用（类名不匹配时走反射兜底）
     *  → 火焰图实证：`resolveReadableTextColor → isHeadsUpRootView → reflectWindowType` 一路烧到
     *  `art::Class_getDeclaredMethodInternal`（占主线程采样 1.66%）。
     *
     *  窗口类型对**同一个窗口根**恒定 → 按 rootView 实例缓存即可，无需失效策略；
     *  WeakHashMap 防泄漏（rootView 销毁即自动移除）。 */
    private val headsUpWindowTypeCache = java.util.WeakHashMap<View, Boolean>()

    private fun isHeadsUpRootView(rootView: View): Boolean {
        val name = rootView.javaClass.name
        if (name.contains("HeadsUpLayout") || name.contains("HeadsUpContainerWindow")) return true
        synchronized(headsUpWindowTypeCache) {
            headsUpWindowTypeCache[rootView]?.let { return it }
        }
        val result = reflectWindowType(rootView) == WINDOW_TYPE_HEADS_UP
        synchronized(headsUpWindowTypeCache) { headsUpWindowTypeCache[rootView] = result }
        return result
    }

    /** [2026-08-15 轻打扰折叠横幅玻璃化] Simple Banner Window 根 sfc 解析：遍历 registeredHostViews，
     *  rootView 命中 [isSimpleBannerRootView]（类名含 FullScreenBanner/SimpleBanner 或窗口标题含
     *  "Simple Banner Window"，type 2017 实证）→ 反射取 sfc。仿照 [resolveHeadsUpSfc]；独立解析
     *  （与 heads-up sfc 分开进 exclude 数组），防横幅玻璃自采样。失败返回 null。
     *  [负缓存] 无横幅时 [simpleBannerSfcMissAt] 内直接返回 null（不重复遍历）；[simpleBannerSfcCache]
     *  命中有效 sfc 直接复用（不重复反射窗口标题）；@Synchronized 防与 registerHostView 并发遍历 CME。 */
    @Synchronized
    private fun resolveSimpleBannerSfc(): SurfaceControl? {
        simpleBannerSfcCache?.let { if (it.isValid) return it }
        val now = SystemClock.uptimeMillis()
        if (now < simpleBannerSfcMissAt) return null
        for (entry in registeredHostViews) {
            val v = entry.ref.get() ?: continue
            if (!v.isAttachedToWindow) continue
            val rootView = try {
                v.rootView
            } catch (t: Throwable) {
                null
            }
            if (rootView == null) continue
            if (!isSimpleBannerRootView(rootView)) continue
            val sfc = reflectViewRootSurfaceControl(rootView)
            if (sfc != null && sfc.isValid) {
                simpleBannerSfcCache = sfc
                Log.i(TAG, "bg-source: simple-banner sfc resolved from ${rootView.javaClass.simpleName}, name=${reflectSurfaceControlName(sfc)}")
                return sfc
            }
        }
        simpleBannerSfcMissAt = now + HEADS_UP_SFC_NEG_CACHE_MS
        return null
    }

    /** [2026-08-15] Simple Banner Window 根 View 判定：rootView 类名含 FullScreenBanner/SimpleBanner，
     *  或窗口标题含 "Simple Banner Window"（轻打扰折叠横幅窗口，type 2017 实证）。
     *  [2026-08-15 对齐 heads-up] 窗口类型 2017 兜底（同 [isHeadsUpRootView] 模式）：类名/标题解析失败
     *  仍命中——Simple Banner 窗口 lp.type=2017 实证；保证 [isSimpleBannerHostActive]（持续抓屏 worker
     *  alive 判定）与 [resolveSimpleBannerSfc]（快照 exclude）可靠。 */
    private fun isSimpleBannerRootView(rootView: View): Boolean {
        val name = rootView.javaClass.name
        if (name.contains("FullScreenBanner") || name.contains("SimpleBanner")) return true
        // [2026-09-17 性能] 反射兜底（窗口 type + 窗口标题）按 rootView 实例缓存。
        // 该分支原先每次都要跑 reflectWindowType + reflectWindowTitle（各三跳反射），
        // 而本函数在遍历宿主表时逐个调用。
        // ⚠️ **必须用独立缓存**：本函数语义 = 「type 是 heads-up **或** 标题含 Simple Banner」，
        // 与 [isHeadsUpRootView]（只看 type）**不同** —— 共用缓存会把 heads-up 的 false 结果
        // 污染成 banner 的 false（或反之）。
        synchronized(simpleBannerRootCache) {
            simpleBannerRootCache[rootView]?.let { return it }
        }
        val result = try {
            reflectWindowType(rootView) == WINDOW_TYPE_HEADS_UP ||
                (reflectWindowTitle(rootView)?.contains("Simple Banner") == true)
        } catch (t: Throwable) {
            false
        }
        synchronized(simpleBannerRootCache) { simpleBannerRootCache[rootView] = result }
        return result
    }

    /** [2026-09-17 性能] Simple Banner 窗口根判定缓存（**独立于 [headsUpWindowTypeCache]**，语义不同，见上）。 */
    private val simpleBannerRootCache = java.util.WeakHashMap<View, Boolean>()

    /** [2026-08-15] 反射读窗口根 View 的窗口标题（ViewRootImpl.getWindowAttributes().getTitle()）。失败 null。
     *  getTitle Method 缓存到 [mWindowGetTitleMethod]（reduce 每抓屏 getMethod 开销）。 */
    private fun reflectWindowTitle(rootView: View): CharSequence? {
        return try {
            var m1 = mGetViewRootImpl
            if (m1 == null) {
                m1 = View::class.java.getMethod("getViewRootImpl")
                mGetViewRootImpl = m1
            }
            val vri = m1.invoke(rootView) ?: return null
            var m2 = mGetWindowAttributes
            if (m2 == null) {
                m2 = vri.javaClass.getMethod("getWindowAttributes")
                mGetWindowAttributes = m2
            }
            val lp = m2.invoke(vri) ?: return null
            var m3 = mWindowGetTitleMethod
            if (m3 == null) {
                m3 = lp.javaClass.getMethod("getTitle")
                mWindowGetTitleMethod = m3
            }
            m3.invoke(lp) as? CharSequence
        } catch (t: Throwable) {
            null
        }
    }

    /** 反射读窗口根 View 的窗口类型（ViewRootImpl.getWindowAttributes().type）。失败 -1（静默）。
     *  [2026-08-13 captureDisplay 开始延迟优化] getViewRootImpl/getWindowAttributes/type 字段反射缓存
     *  （View.getViewRootImpl 基类方法 + framework 固定类），避免 resolveHeadsUpSfc 遍历每个宿主时反复
     *  getMethod/getField（无 heads-up 时每宿主一次反射）。 */
    private fun reflectWindowType(rootView: View): Int {
        return try {
            var m1 = mGetViewRootImpl
            if (m1 == null) {
                m1 = View::class.java.getMethod("getViewRootImpl")
                mGetViewRootImpl = m1
            }
            val vri = m1.invoke(rootView) ?: return -1
            var m2 = mGetWindowAttributes
            if (m2 == null) {
                m2 = vri.javaClass.getMethod("getWindowAttributes")
                mGetWindowAttributes = m2
            }
            val lp = m2.invoke(vri) ?: return -1
            var f = mWindowAttrsTypeField
            if (f == null) {
                f = lp.javaClass.getField("type")
                mWindowAttrsTypeField = f
            }
            (f.get(lp) as? Int) ?: -1
        } catch (t: Throwable) {
            -1
        }
    }

    /** 反射 View.getViewRootImpl() → ViewRootImpl.getSurfaceControl()（@SystemApi/@UnsupportedAppUsage，
     *  SystemUI 进程宽松但统一反射）。失败返回 null。
     *  [2026-08-13 captureDisplay 开始延迟优化] getViewRootImpl / getSurfaceControl 反射缓存（基类方法 +
     *  framework 固定类），避免每次抓屏遍历宿主时反复 getMethod。 */
    private fun reflectViewRootSurfaceControl(rootView: View): SurfaceControl? {
        return try {
            var m1 = mGetViewRootImpl
            if (m1 == null) {
                m1 = View::class.java.getMethod("getViewRootImpl")
                mGetViewRootImpl = m1
            }
            val vri = m1.invoke(rootView) ?: return null
            var m2 = mViewRootImplGetSurfaceControl
            if (m2 == null) {
                m2 = vri.javaClass.getMethod("getSurfaceControl")
                mViewRootImplGetSurfaceControl = m2
            }
            val sfc = m2.invoke(vri) as? SurfaceControl ?: return null
            if (!sfc.isValid) return null
            sfc
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

    /**
     * [2026-08-13 组合方案] 整屏快照抓屏：captureDisplay 整屏（region=全屏 → sourceCrop=全屏）排除 shade
     * + 0.5 降采样，wrap+copy 出独立软件位图。共享给所有玻璃元素；内容变化时立即重抓（无节流，异步控负载），
     * 移动只更新 srcRect（渲染侧按元素 region 折算到快照坐标）不重抓。失败返回 null（静默，兜底铁律）。
     */
    private fun captureScreenSnapshot(shade: SurfaceControl?): ElementBackground? {
        val dm = android.content.res.Resources.getSystem().displayMetrics
        val region = RectF(0f, 0f, dm.widthPixels.toFloat(), dm.heightPixels.toFloat())
        return captureElementBackground(shade, region)
    }

    /**
     * 反射 IWindowManager.captureDisplay 抓【region 屏幕区域】排除 shade → 干净背景 HardwareBuffer
     * （0.5 降采样），wrap+copy 出独立软件位图后 close hw。整屏快照传全屏 region。失败返回 null（静默，兜底铁律）。
     */
    private fun captureElementBackground(shade: SurfaceControl?, region: RectF): ElementBackground? {
        // sourceCrop 必须落在显示范围内（MetaBall 扩张/动画中 region 可能含屏外边缘），clamp 兜底
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
                    // [spec/17 配置接线] 降采样比例读 Prefs（KEY_BG_CAPTURE_SCALE，clamp 0.25~1.0，替代硬编码 0.5f）
                    // [doc/spec/49 回退 2026-08-13] 拖动期不再强制 0.25 降采样：运动时保持配置清晰度
                    // （快照分辨率减半=运动变糊根因）。拖动期降载只保留跳过 QS 亮度全图扫描 + 文字重采样
                    // （见 runElementCaptureWorker / motionResetRunnable），不再降低抓屏分辨率。
                    val scale = bgCaptureScale()
                    builderCls.getMethod("setSourceCrop", Rect::class.java).invoke(builder, crop)
                    // [spec/19 Heads-Up 玻璃化] 排除数组追加 heads-up 窗口根 surface（防快照含横幅自采样磨砂）。
                    // shade 与 heads-up 两个窗口 surface 分开解析、分别进 exclude 数组
                    //（resolveShadeSfc 只认 NotificationShadeWindowView；heads-up=HeadsUpLayout，type 2017）。
                    val excludes = ArrayList<SurfaceControl>(2)
                    // [2026-08-14 用户要求·强制抓全屏] shade 可空：有效则排除（防自采样）；null/invalid（shade
                    // 不在渲染）→ 不排除直接抓全屏（快照天然不含 shade，干净）
                    if (shade != null && shade.isValid) excludes.add(shade)
                    try {
                        resolveHeadsUpSfc()?.let { if (it.isValid) excludes.add(it) }
                    } catch (t: Throwable) {
                        Log.e(TAG, "bg-element: resolve heads-up sfc failed", t)
                    }
                    // [2026-08-15 轻打扰折叠横幅玻璃化] 追加 Simple Banner Window 根 surface（防横幅玻璃自采样）
                    try {
                        resolveSimpleBannerSfc()?.let { if (it.isValid) excludes.add(it) }
                    } catch (t: Throwable) {
                        Log.e(TAG, "bg-element: resolve simple-banner sfc failed", t)
                    }
                    // [2026-08-15 持续抓屏对齐 heads-up] 追加流体云展开大卡窗口根 surface（防玻璃自采样）
                    try {
                        resolveCardBackgroundSfc()?.let { if (it.isValid) excludes.add(it) }
                    } catch (t: Throwable) {
                        Log.e(TAG, "bg-element: resolve seedling-cardview sfc failed", t)
                    }
                    builderCls.getMethod("setExcludeLayers", Array<SurfaceControl>::class.java)
                        .invoke(builder, excludes.toTypedArray())
                    builderCls.getMethod("setFrameScale", Float::class.javaPrimitiveType).invoke(builder, scale)
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
                            // [spec/55 诊断日志] captureDisplay 开始前时间戳（t3）——「入队 → captureDisplay 开始」延迟
                            // （队列等待/排队：pending 去重 + 单 worker 串行 + SF 串行，抓屏慢的主嫌疑）
                            // [spec/59 追加 2026-08-14 诊断] capStartUptime 记录 captureDisplay 开始时刻，
                            // ok 处算 start→ok 间隔 = Binder + wrap + copy 固有耗时（识别 Binder 阻塞候选 B）
                            val capStartUptime = SystemClock.uptimeMillis()
                            diagCapLog("captureDisplay-start", " crop=$crop scale=$scale")
                            // [spec/58 追加 2026-08-15 诊断日志] 主线程首帧同步路径的 captureDisplay 开始（lg-first）
                            // ——主线程 Looper 判定（worker 异步路径不打，防刷屏）；「sfc-done → captureDisplay-start」
                            // = exclude 数组/降采样 CaptureArgs 反射构建耗时
                            if (isMainThreadNow()) diagFirstLog("captureDisplay-start", " crop=$crop scale=$scale")
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
                                                    logThrottled("capture-display-ok", Log.INFO) { "bg-element: IWindowManager.captureDisplay ok hw=${hb.width}x${hb.height} crop=$crop (${scale}x, excl shade)" }
                                                    // [spec/55 诊断日志] captureDisplay 完成时间戳（t4）——「hook → 抓屏完成」总延迟
                                                    // （对比 captureDisplay-start：完成快慢= captureDisplay Binder 固有耗时）
                                                    // [spec/59 追加 2026-08-14] elapsed = start→ok 间隔（Binder + wrap + copy）
                                                    val capElapsed = SystemClock.uptimeMillis() - capStartUptime
                                                    diagCapLog("captureDisplay-ok", " hw=${hb.width}x${hb.height} elapsed=${capElapsed}ms")
                                                    // [spec/58 追加 2026-08-15 诊断日志] 主线程首帧同步路径 captureDisplay 完成（lg-first）
                                                    // ——「captureDisplay-start → captureDisplay-ok」= captureDisplay Binder 固有耗时
                                                    // （20-50ms 正常；远大于此 = captureDisplay 自身慢/SF 负载）
                                                    if (isMainThreadNow()) diagFirstLog("captureDisplay-ok", " hw=${hb.width}x${hb.height} elapsed=${capElapsed}ms")
                                                    // [spec/59 追加 2026-08-14 诊断] captureDisplay Binder 阻塞告警（根因候选 B）：
                                                    // start→ok 超 500ms = SF 端忙/同步 Binder 阻塞（worker 卡住期间入队积压 = 竞态窗口）
                                                    if (capElapsed > 500) diagWorkerLog("capture-slow", " elapsed=${capElapsed}ms crop=$crop")
                                                    ElementBackground(copy, region)
                                                }
                                            } finally {
                                                // wrap 出的硬件位图用完即回收（像素已 copy 独立）
                                                try {
                                                    wrapped.recycle()
                                                } catch (ignored: Throwable) {
                                                }
                                            }
                                        }
                                    } finally {
                                        // 引用纪律：抓屏 hw 为模块自建引用（非系统 buffer），copy 后 close 释放
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

    // ------------------------------------------------------------ [spec/48] 背景 scrim 纯黑 tint（遮罩走系统自带机制）

    /**
     * [spec/48 方案 A] 判定背景 scrim 是否为 behind_scrim（shade 面板背景，画纯黑遮罩目标）。
     * notifications_scrim/front_scrim 保持透明（现状，不叠黑）。
     * 反射失败（mScrimNameField null / 拿不到 hostView）→ 保守 true（behind_scrim 是主路径，宁画不丢）。
     */
    private fun isBehindScrim(chain: XposedInterface.Chain): Boolean {
        return isBehindScrimHost(resolveScrimHostView(chain))
    }

    /** [spec/48 过渡修复加固] 从 chain 反查 hostView（ScrimView）：this（AutoBlurDrawable）→ getViewBlurProxy →
     *  getView()。反射失败 → null（调用方走保守路径）。 */
    private fun resolveScrimHostView(chain: XposedInterface.Chain): View? {
        val auto = try {
            chain.getThisObject()
        } catch (t: Throwable) {
            return null
        } ?: return null
        val proxy = try {
            mAutoGetViewBlurProxy?.invoke(auto)
        } catch (t: Throwable) {
            return null
        } ?: return null
        return try {
            mViewProxyGetView?.invoke(proxy) as? View
        } catch (t: Throwable) {
            null
        }
    }

    /** [spec/48 过渡修复加固] hostView 是否为 behind_scrim（mScrimName=="behind_scrim"）。
     *  hostView null / mScrimNameField null / 反射失败 → 保守 true（behind_scrim 是主路径，宁画不丢）。 */
    private fun isBehindScrimHost(view: View?): Boolean {
        if (view == null) return true
        val field = mScrimNameField ?: return true
        return try {
            field.get(view) as? String == "behind_scrim"
        } catch (t: Throwable) {
            true
        }
    }

    /** [spec/48 过渡修复加固] 反射读 ScrimView.mViewAlpha（public float，系统 scrim 动画逐帧 alpha 真源，
     *  onDraw 时已由 setViewAlpha 写入）。**不依赖 hook**——即使 setViewAlpha hook 全部失败，绘制路径
     *  也能拿到正确动画 alpha（最稳）。反射失败 → null（调用方 fallback behindScrimAlpha）。 */
    private fun readScrimViewAlpha(view: View?): Float? {
        val field = mViewAlphaField ?: return null
        if (view == null) return null
        return try {
            (field.get(view) as? Float)?.coerceIn(0f, 1f)
        } catch (t: Throwable) {
            null
        }
    }

    /** [spec/48 方案 A] scrim 纯黑 tint Paint：alpha = progress × maskAlpha。
     *  [2026-08-14 同步修复] 系统 behind_scrim mViewAlpha 峰值仅 ~0.15（真机实测，系统本来就浅半透明）——
     *  "多加点"：除以峰值归一化（progress = scrimAlpha / scrimPeak，峰值自适应学习）→ 下拉动画保留（系统
     *  渐入曲线），展开完全 progress=1 → alpha = maskAlpha，与玻璃 shader uMaskAlpha 同步（maskAlpha=1 两边全黑）。
     *  保留原 scrimAlpha×maskAlpha 的动画手感，仅把系数放大到最终对齐 maskAlpha。 */
    private fun scrimMaskPaintFor(scrimAlpha: Float): Paint? {
        if (!maskEnabled || maskAlpha <= 0f) return null
        val a = scrimAlpha.coerceIn(0f, 1f)
        if (a <= 0f) return null
        if (a > scrimPeak) scrimPeak = a
        val progress = (a / scrimPeak).coerceIn(0f, 1f)
        val paint = scrimMaskPaint ?: Paint().also { p ->
            p.style = Paint.Style.FILL
            scrimMaskPaint = p
        }
        paint.color = android.graphics.Color.argb((progress * maskAlpha.coerceIn(0f, 1f) * 255f).toInt(), 0, 0, 0)
        return paint
    }

    /** [spec/48 方案 A] 在 AutoBlurDrawable.draw 拦截处（不 proceed 短路整条背景模糊链）画纯黑 tint：
     *  canvas = ScrimView 的 canvas（drawable.bounds 全屏、z-order 最低），alpha = scrimAlpha×maskAlpha。
     *  [过渡修复加固 2026-08-13] scrimAlpha **优先反射读 ScrimView.mViewAlpha**（public float 字段，
     *  系统 setViewAlpha 每次写入、onDraw 时已是当前动画值，**不依赖 hook**——最稳）；反射失败才
     *  fallback behindScrimAlpha（hook setViewAlpha/getViewAlpha 捕获）。mask 关 / alpha 0 → 不画。 */
    private fun drawScrimBlackTint(chain: XposedInterface.Chain) {
        if (!maskEnabled || maskAlpha <= 0f) return
        val drawable = try {
            chain.getThisObject() as? Drawable
        } catch (t: Throwable) {
            null
        } ?: return
        val canvas = try {
            chain.getArg(0) as? Canvas
        } catch (t: Throwable) {
            null
        } ?: return
        val hostView = resolveScrimHostView(chain)
        val scrimAlpha = readScrimViewAlpha(hostView) ?: behindScrimAlpha
        val paint = scrimMaskPaintFor(scrimAlpha) ?: return
        val bounds = drawable.bounds
        if (bounds.width() <= 0 || bounds.height() <= 0) return
        canvas.drawRect(bounds, paint)
        logThrottled("bg-scrim-mask", Log.INFO) {
            "bg: black tint mask (spec/48): scrimAlpha=$scrimAlpha behindScrimAlpha=$behindScrimAlpha maskAlpha=$maskAlpha bounds=$bounds"
        }
    }

    /**
     * [spec/48 过渡修复加固 2026-08-13] hook 捕获 behind_scrim 真实动画 alpha，多路真源。
     *
     * 反编译实证（16.1）：ScrimController 动画链
     * setScrimAlpha → ValueAnimator 补间 → updateScrimColor → `ScrimView.setViewAlpha(f)`（ScrimView.java:221），
     * 基类内部 `mViewAlpha=f`（public float）+ `mDrawable.setAlpha((int)(f*255))` + `getExt().setViewAlpha(f)`
     * （→ ScrimViewExImp.setViewAlpha，ScrimViewExImp.java:228，系统真实透明度走它）。
     * 上一轮只 hook 基类 ScrimView.setViewAlpha 但真机 behindScrimAlpha 恒 0（遮罩消失）——不确定原因
     * （hook 挂载/触发/类型），因此本修复**多路加固**：
     *   ① ScrimView.setViewAlpha(float)（基类，主路径）；
     *   ② ScrimViewEx.setViewAlpha(float)（委托基类）；
     *   ③ ScrimViewExImp.setViewAlpha(float)（实际实现，系统真实透明度驱动链）；
     *   ④ ScrimView.getViewAlpha()（读返回值 getter——proceed() 返回 mViewAlpha，ScrimController
     *      updateScrims/状态判定多处调用，getter 最可靠）。
     * 任一命中（behind_scrim 判定，反射失败保守全记）→ 写 @Volatile behindScrimAlpha。
     * **主路径 drawScrimBlackTint 已改为绘制时反射读 mViewAlpha 字段（不依赖 hook）**，hook 是兜底
     * （drawBlurShader 兜底路径）与日志用。挂载失败只让兜底退化，不崩。
     */
    private fun mountScrimViewAlpha(api: XposedInterface, classLoader: ClassLoader) {
        mountScrimAlphaHook(api, classLoader, CLASS_SCRIM_VIEW, false)
        mountScrimAlphaHook(api, classLoader, CLASS_SCRIM_VIEW_EX, true)
        mountScrimAlphaHook(api, classLoader, CLASS_SCRIM_VIEW_EX_IMP, true)
        mountScrimGetViewAlphaHook(api, classLoader)
    }

    /** 挂载单个 setViewAlpha(float) hook（className = ScrimView / ScrimViewEx / ScrimViewExImp）。
     *  @param isExt this 是 ScrimViewEx/ExImp（非 ScrimView），需经 mExtGetScrimView 反取宿主 ScrimView；
     *               false 时 this 就是 ScrimView 本身（**注意不是 AutoBlurDrawable**，不能用
     *               resolveScrimHostView 那条 AutoBlurDrawable→viewBlurProxy→hostView 反射链）。 */
    private fun mountScrimAlphaHook(
        api: XposedInterface,
        classLoader: ClassLoader,
        className: String,
        isExt: Boolean
    ) {
        try {
            val clazz = Class.forName(className, false, classLoader)
            val method = clazz.getMethod(METHOD_SET_VIEW_ALPHA, Float::class.javaPrimitiveType)
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        val alpha = (chain.getArg(0) as? Float) ?: return@intercept chain.proceed()
                        val host = if (isExt) {
                            resolveExtHostView(chain)
                        } else {
                            try { chain.getThisObject() as? View } catch (t: Throwable) { null }
                        }
                        if (isBehindScrimHost(host)) behindScrimAlpha = alpha.coerceIn(0f, 1f)
                    } catch (t: Throwable) {
                        Log.e(TAG, "scrimViewAlpha: $className#$METHOD_SET_VIEW_ALPHA intercept error", t)
                    }
                    chain.proceed()
                }
            Log.i(TAG, "Hook B mounted: $className#$METHOD_SET_VIEW_ALPHA(float) (behind-scrim mask transition)")
        } catch (t: Throwable) {
            Log.e(TAG, "Hook B mount FAILED: $className#$METHOD_SET_VIEW_ALPHA", t)
        }
    }

    /** [spec/48 过渡修复加固] hook ScrimView.getViewAlpha() 读返回值（mViewAlpha，public getter）：
     *  ScrimController.updateScrims/状态判定每次读它，返回值即逐帧动画 alpha。 */
    private fun mountScrimGetViewAlphaHook(api: XposedInterface, classLoader: ClassLoader) {
        try {
            val clazz = Class.forName(CLASS_SCRIM_VIEW, false, classLoader)
            val method = clazz.getMethod(METHOD_GET_VIEW_ALPHA)
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    // 先 proceed 拿原返回值（mViewAlpha）；失败返回 0f（原方法返回原始 float，不能返回 null）
                    val result = try {
                        chain.proceed()
                    } catch (t: Throwable) {
                        Log.e(TAG, "scrimViewAlpha: $CLASS_SCRIM_VIEW#$METHOD_GET_VIEW_ALPHA proceed error", t)
                        0f
                    }
                    try {
                        val alpha = result as? Float
                        val host = try { chain.getThisObject() as? View } catch (t: Throwable) { null }
                        if (alpha != null && isBehindScrimHost(host)) behindScrimAlpha = alpha.coerceIn(0f, 1f)
                    } catch (t: Throwable) {
                        Log.e(TAG, "scrimViewAlpha: $CLASS_SCRIM_VIEW#$METHOD_GET_VIEW_ALPHA intercept error", t)
                    }
                    result // 返回原值，不改变系统行为
                }
            Log.i(TAG, "Hook B mounted: $CLASS_SCRIM_VIEW#$METHOD_GET_VIEW_ALPHA() (behind-scrim mask transition, getter)")
        } catch (t: Throwable) {
            Log.e(TAG, "Hook B mount FAILED: $CLASS_SCRIM_VIEW#$METHOD_GET_VIEW_ALPHA", t)
        }
    }

    /** [spec/48 过渡修复加固] ScrimViewEx/ExImp.setViewAlpha 的 this 反取宿主 ScrimView（getScrimView()）。
     *  反射失败 → null（保守全记）。 */
    private fun resolveExtHostView(chain: XposedInterface.Chain): View? {
        val ext = try {
            chain.getThisObject()
        } catch (t: Throwable) {
            return null
        } ?: return null
        val m = mExtGetScrimView ?: return null
        return try {
            m.invoke(ext) as? View
        } catch (t: Throwable) {
            null
        }
    }
}
