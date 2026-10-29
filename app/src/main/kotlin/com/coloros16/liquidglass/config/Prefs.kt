package com.coloros16.liquidglass.config

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import com.coloros16.liquidglass.log.Lg
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.service.XposedService

/**
 * 模块配置。
 *
 * 配置原则（2026-08-10 已定决策）：【所有配置项可调】；【默认全关】——
 * 模块默认不生效，用户手动开启，防误伤/崩溃。
 *
 * 读写模型（2026-08-11 真机实证修正）：
 * - hook 端（SystemUI 进程）：经 [XposedInterface.getRemotePreferences] 读【框架侧存储】
 *   （LibXposed 服务端实现，LSPosed 等框架存于其内部数据库，见 LSPModuleService.requestRemotePreferences）。
 * - 模块 UI 进程（SettingsActivity）：必须写【同一框架侧存储】才能被 hook 端读到，
 *   做法是经 [App.serviceRef]（XposedService）→ [XposedService.getRemotePreferences] → edit().apply()，
 *   底层走 IXposedService.updateRemotePreferences 写框架侧存储。
 * - ⚠️ 本地 SharedPreferences(XML)（[read] 的 [Context.getSharedPreferences]）与框架侧存储【不是同一份数据】：
 *   仅作 UI 回显兜底/降级用，单独写 XML 无法被 hook 端读到。
 *
 * 两端统一：同一文件名 [PREFS_NAME]、键完全一致。
 */
object Prefs {

    const val PREFS_NAME = "liquid_glass_config"

    // ---------- 总开关 ----------
    /** 模块总开关，默认关闭（false = 模块完全不生效） */
    const val KEY_MASTER = "master_enabled"

    /** [2026-10-13] 玻璃渲染总开关：false = 自研液态玻璃 shader 完全不套（回退系统模糊），
     *  其余功能（倾斜/飞入/热重载等）不受影响。默认开（保持现行为）。500ms TTL 直读生效。 */
    const val KEY_GLASS_ENABLE = "glass_enable"
    const val DEFAULT_GLASS_ENABLE = true

    // ---------- 玻璃效果预设 ----------
    /** 玻璃预设列表（JSON 数组，元素含 id/name/classes/params，params 逐键覆盖全局值）。 */
    const val KEY_GLASS_PRESETS = "glass_presets"

    /** 探测到的玻璃类缓存（JSON 数组，元素含 cls/lastSeenMs，供设置页列出可选类）。 */
    const val KEY_GLASS_CLASSES = "glass_classes"

    /** 按类圆角覆盖表（JSON 对象，类全名 → 圆角半径 px；缺失 = 该类跟随实测圆角）。 */
    const val KEY_CORNER_OVERRIDES = "glass_corner_overrides"

    /** 预设序号：新建预设时自增写回，保证预设 id 不与历史 id 复用。 */
    const val KEY_GLASS_PRESET_SEQ = "glass_preset_seq"

    /** 设置页顶栏当前选中的预设 id（存模块本地 XML，仅 UI 态，不进框架侧）。 */
    const val KEY_UI_GLASS_PRESET = "ui_glass_preset_selected"

    /** [KEY_UI_GLASS_PRESET] 的默认取值 = 编辑全局参数。 */
    const val GLASS_PRESET_DEFAULT_ID = "default"

    // [2026-09-17 死键清理] 背景档位已删除：KEY_BACKGROUND_MODE + BACKGROUND_TRANSPARENT/LIGHT/SYSTEM
    // 三个取值 + Prefs.backgroundMode()，全部零调用方（档位语义早已被材质参数逐项取代）。

    // ---------- 液态玻璃材质参数（默认全关/零，随 M1 渲染 PoC 逐个启用） ----------
    const val KEY_BLUR_RADIUS = "blur_radius"
    /** 模糊半径 UI 上限（px）：shader 为固定 5×5 tap，间隔 = spread/2，过大将出采样条带 */
    const val BLUR_RADIUS_UI_MAX = 32
    const val KEY_REFRACTION_AMOUNT = "refraction_amount"
    /** 折射强度滑杆 UI 上限（px）；shader 侧位移按元素半短边封顶，小元素不会跨区采样 */
    const val REFRACTION_AMOUNT_UI_MAX = 250
    /** [2026-09-17 死键清理] 旧键 KEY_REFRACTION_HEIGHT（"refraction_height"，绝对 px）已删除、勿复活。
     *  [doc/spec/95 2026-10-10] 改为**新键 + 新语义**：折射环带高度 = 元素短边 × 本比例
     *  （相对比例，非绝对 px，故键名/语义均与旧键不冲突）。clamp [REFRACTION_HEIGHT_RATIO_MIN,
     *  [REFRACTION_HEIGHT_RATIO_MAX]]；默认 [DEFAULT_REFRACTION_HEIGHT_RATIO] = 短边一半 = 改动前恒等式，
     *  默认零视觉变化。 */
    const val KEY_REFRACTION_HEIGHT_RATIO = "refraction_height_ratio"
    /** [doc/spec/96 2026-10-10] 折射区域计算方式：0=相对（短边比例，默认，现行为零变化）1=绝对（像素）。
     *  ⚠️ 与历史死键 `refraction_height`（spec/74 已删，绝对 px 语义）**不是一回事**：
     *  本键只存「模式」，绝对像素值另存 [KEY_REFRACTION_HEIGHT_PX]，键名互不冲突。 */
    const val KEY_REFRACTION_HEIGHT_MODE = "refraction_height_mode"
    /** [doc/spec/96] 折射区域绝对像素值（仅 [KEY_REFRACTION_HEIGHT_MODE]=1 时生效）。
     *  ⚠️ 新键 `refraction_height_px` ≠ 死键 `refraction_height`（旧默认 48f，spec/74 已清理），
     *  语义与键名都不冲突，勿合并。clamp [REFRACTION_HEIGHT_PX_MIN]~[REFRACTION_HEIGHT_PX_MAX]。 */
    const val KEY_REFRACTION_HEIGHT_PX = "refraction_height_px"
    const val KEY_DEPTH = "depth"
    const val KEY_DISPERSION = "dispersion"
    const val KEY_HIGHLIGHT = "highlight"
    const val KEY_HIGHLIGHT_WIDTH = "highlight_width"
    const val KEY_HIGHLIGHT_FALLOFF = "highlight_falloff"
    /** [2026-09-16 弧线高光修复] 高光方向因子保底（0~1，默认 0.4）。圆角弧线的法线旋转 90°，
     *  必然经过与光源垂直处 → 原实现该处高光精确归零（弧线约 60% 跨度消失）。保底后弧线保留
     *  基础亮度、不再断开；0 = 完全回退旧行为。详见 doc/spec/67。 */
    const val KEY_HIGHLIGHT_FLOOR = "highlight_floor"
    /** [2026-10-13] 内侧高光衰减（0~1，默认 0=现状）：band 内半向玻璃深处再衰减，1=只剩贴边亮线。 */
    const val KEY_HIGHLIGHT_INNER_ATT = "highlight_inner_att"
    const val DEFAULT_HIGHLIGHT_INNER_ATT = 0f
    /** 内侧光（0~1，默认 0=关）：边缘向中心的全向发光（区别于定向边缘高光），发光区 = 折射环带。
     *  调整后 ≤1s 生效（readMaterialParams TTL 缓存）。 */
    const val KEY_INNER_GLOW = "inner_glow"
    const val DEFAULT_INNER_GLOW = 0f
    const val KEY_VIBRANCY = "vibrancy"
    const val KEY_LIGHT_ANGLE = "light_angle"
    const val KEY_PARALLAX_X = "parallax_x"
    const val KEY_PARALLAX_Y = "parallax_y"

    // 新管线（2026-08-12 Kyant 分层：清晰内部透镜 + 内侧折射环带 + vibrancy + 高光）默认值
    /** 内部可选轻模糊半径 px（0=清晰透镜；>0 仅作用内部，不糊折射环带）。
     *  [2026-09-16] 0f → 8f：原 0 使每块玻璃成完全清晰透镜（用户反馈「横幅太透、看得见背后文字」）。 */
    const val DEFAULT_BLUR_RADIUS = 13f
    /** 鲜艳度/饱和度倍数（Kyant vibrancy=1.5；1=不变） */
    const val DEFAULT_VIBRANCY = 1.54f
    /** 高光方向衰减指数（Kyant ControlCenter=2；越大越聚光） */
    const val DEFAULT_HIGHLIGHT_FALLOFF = 4f
    // [2026-09-17 死键清理遗留] DEFAULT_REFRACTION_HEIGHT（48f 绝对 px）已随旧键语义一并删除，
    // 见 doc/spec/95 —— 新语义是短边比例（DEFAULT_REFRACTION_HEIGHT_RATIO），两者不可混用。
    /** 折射强度默认 px（边缘最大位移，正值；= LiquidGlassShader.Params 默认） */
    const val DEFAULT_REFRACTION_AMOUNT = 100f
    /** [doc/spec/95] 折射环带高度比例默认值（0.5 = 元素短边一半，与改动前 `min(w,h)/2f` 完全一致） */
    const val DEFAULT_REFRACTION_HEIGHT_RATIO = 0.5f
    /** [doc/spec/95] 折射环带高度比例下限（0.1，clamp；过小会让折射带细到看不出效果） */
    const val REFRACTION_HEIGHT_RATIO_MIN = 0.1f
    /** [doc/spec/95] 折射环带高度比例上限（1.0 = 短边整条都是折射带） */
    const val REFRACTION_HEIGHT_RATIO_MAX = 1.0f
    /** [doc/spec/96] 模式取值：相对（短边比例）——默认，与 spec/95 现行为逐像素一致 */
    const val REFRACTION_HEIGHT_MODE_RATIO = 0
    /** [doc/spec/96] 模式取值：绝对（像素） */
    const val REFRACTION_HEIGHT_MODE_ABS = 1
    /** [doc/spec/96] 模式默认：相对（0），保证升级后零视觉变化 */
    const val DEFAULT_REFRACTION_HEIGHT_MODE = REFRACTION_HEIGHT_MODE_RATIO
    /** [doc/spec/96] 绝对模式默认 64px */
    const val DEFAULT_REFRACTION_HEIGHT_PX = 64f
    /** [doc/spec/96] 绝对模式下限（px）：再小折射带看不出效果 */
    const val REFRACTION_HEIGHT_PX_MIN = 8f
    /** [doc/spec/96] 绝对模式上限（px）：超过常见元素短边没有意义 */
    const val REFRACTION_HEIGHT_PX_MAX = 400f
    // 2026-08-15 材质参数默认值（= LiquidGlassShader.Params 默认，readMaterialParams 兜底同源；
    // 设置界面 bindFloatSeekBar 回显用）
    /** 深度默认（法线径向分量，Kyant depthEffect=true → 1） */
    const val DEFAULT_DEPTH = 0.72f
    /** 色散默认（0 = 关闭；0.05~0.3 彩虹边） */
    const val DEFAULT_DISPERSION = 0.3f
    /** 高光强度默认（加性≈Kyant 白色 0.5 Plus 混合） */
    const val DEFAULT_HIGHLIGHT = 0.56f
    /** 高光带宽度默认 px */
    const val DEFAULT_HIGHLIGHT_WIDTH = 8f
    /** [2026-09-16 弧线高光修复] 高光方向因子保底默认值（= LiquidGlassShader.Params 同源）。
     *  0.4 = 弧线处保留 40% 基础高光（直边 0.53→0.72，弧线 0→0.40）。 */
    const val DEFAULT_HIGHLIGHT_FLOOR = 0.16f
    /** 光角度默认（度） */
    const val DEFAULT_LIGHT_ANGLE = 51f
    /** 视差 X 默认 px（0 = 关闭视差） */
    const val DEFAULT_PARALLAX_X = 0f
    /** 视差 Y 默认 px（0 = 关闭视差） */
    const val DEFAULT_PARALLAX_Y = 0f

    // [2026-09-17 死键清理] 原图 dump 键（KEY_DUMP_ORIGINAL）已删除：dump 诊断改走触发文件机制
    //（/data/local/tmp/lg_dump_trigger → lg_dump.png），本键零调用方。

    // ---------- 回退恢复（配置接线，2026-08-12） ----------
    // [2026-09-17] 通用运动追踪器键（KEY_TRACK_HOST_MOTION / DEFAULT_TRACK_HOST_MOTION）已随
    // 追踪器整体删除：真机实测关闭后表现无差异，逐帧 Choreographer 遍历属纯空转。

    // [2026-09-17 死键清理] 回退恢复节流键（KEY_RETRY_THROTTLE_MS / DEFAULT_RETRY_THROTTLE_MS）
    // 已删除：重试节流现由 BlurDrawHook 的 retryIntervalMs（250ms 起步、×2、4s 封顶）承担。

    // [2026-09-17 死键清理] 每元素背景容量（KEY/DEFAULT_ELEMENT_BG_CAP）与内容变化重抓节流
    //（KEY/DEFAULT_BG_REFRESH_MS）已删除：均自述废弃且零调用方——整屏快照方案下元素背景已移除，
    // 内容变化改「无节流立即刷新」。

    // ---------- 抓屏/空置/追踪参数全配置化（doc/spec/17，2026-08-13） ----------
    // A. 抓屏速率 / 空置速率（用户点名三项）
    /** 内容变化触发抓屏的最小间隔毫秒（节流）。0=无节流立即抓；>0=限频（如 16=按帧、100、200、300）。
     * 改动即时生效（scheduleElementCaptures 每次读 Prefs）。**仅作用于普通内容变化触发**
     * （force=false 路径，[scheduleElementCaptures]）；**[2026-08-14 运动节流] 不再驱动主动持续抓屏速率**
     * （持续抓屏速率改由 [KEY_PANEL_CAPTURE_HZ] 运动判定控制：运动满速 / 静止减半，见该键注释）。 */
    const val KEY_BG_CAPTURE_MIN_INTERVAL_MS = "bg_capture_min_interval_ms"
    /** 内容变化抓屏节流默认毫秒（[2026-08-13 回退 spec/49 固定 200ms] **0=无节流立即抓**——用户要求
     *  「下拉 / 横幅通知弹出必须立马抓，宁可高负载不拖」；限频由「屏幕内容变化」驱动（内容没变不触发、
     *  内容变了立即抓）+ pending 去重防重，不设固定时间窗） */
    const val DEFAULT_BG_CAPTURE_MIN_INTERVAL_MS = 0

    /** 抓屏模式：0=即时（固定速率连抓）1=变化（内容变化触发 + 周期兜底）2=静态（零抓屏，冻结现有快照）。
     *  改动 ≤1s 生效（captureMode TTL 缓存）。 */
    const val KEY_BG_CAPTURE_MODE = "bg_capture_mode"
    /** 抓屏模式取值：即时 */
    const val BG_CAPTURE_MODE_IMMEDIATE = 0
    /** 抓屏模式取值：变化（默认） */
    const val BG_CAPTURE_MODE_ON_CHANGE = 1
    /** 抓屏模式取值：静态（零抓屏） */
    const val BG_CAPTURE_MODE_STATIC = 2
    /** 抓屏模式默认值（变化） */
    const val DEFAULT_BG_CAPTURE_MODE = BG_CAPTURE_MODE_ON_CHANGE

    /** 即时抓屏速率 Hz：瞬态场景（横幅/流体云/simple-banner）主动持续抓屏与「即时」模式周期抓屏共用。
     *  clamp [PANEL_CAPTURE_HZ_MIN]~[PANEL_CAPTURE_HZ_MAX]，存量越界值读取时夹紧。运动满速/静止减半
     *  由 [isMotionActive] 判定驱动；间隔 = 1000/有效Hz ms。改动 ≤1s 生效（hook 端 IPC 时间节流缓存）。 */
    const val KEY_PANEL_CAPTURE_HZ = "panel_capture_hz"
    /** 即时抓屏速率下限 Hz */
    const val PANEL_CAPTURE_HZ_MIN = 30
    /** 即时抓屏速率上限 Hz */
    const val PANEL_CAPTURE_HZ_MAX = 120
    /** 即时抓屏速率默认 Hz */
    const val DEFAULT_PANEL_CAPTURE_HZ = 60

    /** 抓屏降采样比例（0.25~1.0），档位 0.25/0.5/0.75/1.0。改动即时生效（captureElementBackground 每次读 Prefs）。 */
    const val KEY_BG_CAPTURE_SCALE = "bg_capture_scale"
    /** 抓屏降采样默认比例（0.5f，现硬编码值） */
    const val DEFAULT_BG_CAPTURE_SCALE = 0.25f

    // ---------- 文件夹背景抓屏只抓壁纸层（doc/spec/42，2026-08-13） ----------
    /** Launcher 抓屏 UID 过滤开关：true=CaptureArgs.setUid(壁纸层 UID) 只抓壁纸层。setUid 语义 =
     *  只抓指定 UID 的层（SDK android-34 ScreenCapture.java:434-440「skip any surfaces that don't
     *  belong to the specified uid」）；壁纸层 UID 动态解析（静态壁纸=SystemUI，SF dumpsys 实证
     *  ImageWallpaper 内容层 uid=10161；动态壁纸=WallpaperInfo.serviceInfo 包名 UID）。
     *  开启后快照不再混入非壁纸窗口层（正在退出的 app 窗口轨迹、shade、文件夹浮层/图标等）——
     *  文件夹背景=纯净底层壁纸。false=不过滤（现行为，整屏 exclude [launcherSfc]）。
     *  改动即时生效（captureElementBackground 每次读 Prefs）。 */
    const val KEY_BG_CAPTURE_UID_FILTER = "bg_capture_uid_filter"
    /** 抓屏 UID 过滤开关默认值（true = 默认开启：文件夹背景只抓壁纸层） */
    const val DEFAULT_BG_CAPTURE_UID_FILTER = true

    // ---------- Launcher 文件夹背景源 = 壁纸文件解码（doc/spec/52，2026-08-13） ----------
    /** Launcher 文件夹背景源开关：true=**壁纸文件解码**（`WallpaperManager.getWallpaperFile` → PFD →
     *  inSampleSize 解码 → 按桌面滚动 offset 裁剪屏幕可见区域作背景源）。静态壁纸**零持续抓屏**——
     *  背景 = 壁纸内容，只在壁纸变化（id 变）时重新解码；滚动壁纸视差跟手（hook
     *  `WallpaperManager.setWallpaperOffsets`）。false=回退 `captureDisplay` 抓屏（spec/42 方案，
     *  整屏 setUid 只抓壁纸层）。动态壁纸自动回退抓屏。改动即时生效（triggerBackgroundCapture 每次读）。 */
    const val KEY_BG_WALLPAPER_FILE_SOURCE = "bg_wallpaper_file_source"
    /** 壁纸文件解码开关默认值（true = 默认壁纸文件解码） */
    const val DEFAULT_BG_WALLPAPER_FILE_SOURCE = true

    /** 壁纸整幅解码最大边长像素（inSampleSize 降采样上限，防大壁纸解码内存过高；裁剪精度足够）。
     *  clamp 512~8192。改动即时生效（decodeWallpaperSource 每次读）。 */
    const val KEY_BG_WALLPAPER_MAX_DIM = "bg_wallpaper_max_dim"
    /** 壁纸整幅解码最大边长默认值（2048px：2875×3168 壁纸 inSampleSize=2 → ~1437×1584 ≈ 9MB） */
    const val DEFAULT_BG_WALLPAPER_MAX_DIM = 2048

    // B. 抓屏并发 / 重试
    // [2026-09-17 死键清理] 抓屏 worker 并发上限键（KEY/DEFAULT_BG_CAPTURE_WORKERS）已删除：
    // 自述废弃（整屏快照单任务下并发无意义，worker 固定单线程）且零调用方。

    /** 抓屏失败重试间隔毫秒（3 次/320ms）。改动即时生效（runElementCaptureWorker 读 Prefs）。 */
    const val KEY_BG_CAPTURE_RETRY_MS = "bg_capture_retry_ms"
    /** 抓屏失败重试间隔默认毫秒（320ms，现硬编码） */
    const val DEFAULT_BG_CAPTURE_RETRY_MS = 320

    /** 抓屏失败重试次数上限（用尽后冷却 ELEMENT_CAPTURE_COOLDOWN_MS）。改动即时生效（runElementCaptureWorker 读 Prefs）。 */
    const val KEY_BG_CAPTURE_RETRY_LIMIT = "bg_capture_retry_limit"
    /** 抓屏失败重试次数默认上限（3，现硬编码） */
    const val DEFAULT_BG_CAPTURE_RETRY_LIMIT = 3

    /** 抓屏失败重试总开关：true=失败后按固定间隔（[DEFAULT_BG_CAPTURE_RETRY_MS]）重试
     *  [DEFAULT_BG_CAPTURE_RETRY_LIMIT] 次；false=失败即放弃，等下次事件。改动 ≤1s 生效。 */
    const val KEY_BG_CAPTURE_RETRY_ENABLED = "bg_capture_retry_enabled"
    /** 抓屏失败重试总开关默认值（false = 失败即等下次事件，不重试） */
    const val DEFAULT_BG_CAPTURE_RETRY_ENABLED = false

    // C. 追踪器（spec/16 状态机 + 降频）
    // [2026-09-17] 三个追踪器间隔键（tracker_min/idle_interval_frames、tracker_idle_after_frames）
    // 已随追踪器整体删除。

    // ---------- 持续抓屏周期性刷新（doc/spec/33，2026-08-13；spec/36 后仅 SystemUI 侧使用） ----------
    /** 持续抓屏开关：true=活跃玻璃宿主（通知/控件）期间周期性触发整屏快照重抓，
     *  动态背景（壁纸滚动、动态壁纸、通知实时内容）不触发内容变化检测 → 玻璃背景冻结滞后，需周期刷新。
     *  false=纯内容变化触发（现行为，静止零抓屏）。改动即时生效（periodicCaptureRunnable 每周期读 Prefs）。
     *  [doc/spec/36 2026-08-13] **仅 SystemUI（BlurDrawHook 通知场景）读取**；Launcher 侧已移除周期
     *  抓屏（改跟随系统模糊活动 onBlurReady 触发，见 LauncherHook.mountOnBlurReady），不再读本键。 */
    const val KEY_BG_PERIODIC_CAPTURE_ENABLE = "bg_periodic_capture_enable"
    /** 持续抓屏开关默认值（true = 默认开启：动态背景基本实时） */
    const val DEFAULT_BG_PERIODIC_CAPTURE_ENABLE = true

    /** 持续抓屏间隔毫秒（仅 KEY_BG_PERIODIC_CAPTURE_ENABLE=true 时生效），如 250/500/1000。
     *  [doc/spec/36 2026-08-13] 仅 SystemUI（BlurDrawHook）读取，Launcher 不再使用。 */
    const val KEY_BG_PERIODIC_CAPTURE_INTERVAL_MS = "bg_periodic_capture_interval_ms"
    /** 持续抓屏间隔默认毫秒（500ms：动态背景基本实时、功耗可接受；不每帧抓） */
    const val DEFAULT_BG_PERIODIC_CAPTURE_INTERVAL_MS = 500
    /** 持续抓屏间隔下限毫秒（100ms，防过度抓屏） */
    const val BG_PERIODIC_CAPTURE_INTERVAL_MIN_MS = 100
    /** 持续抓屏间隔上限毫秒（5000ms） */
    const val BG_PERIODIC_CAPTURE_INTERVAL_MAX_MS = 5000

    /** 区域级抓屏（实验性）：true=瞬态场景（横幅/流体云/simple-banner）强制抓时只抓活跃瞬态区域并集，
     *  存入独立缓冲供瞬态元素采样；false（默认）=恒整屏抓屏。shade 展开时不接管。改动 ≤1s 生效。 */
    const val KEY_BG_REGION_CAPTURE_EXPERIMENTAL = "bg_region_capture_experimental"
    /** 区域级抓屏默认值（false = 默认关闭） */
    const val DEFAULT_BG_REGION_CAPTURE_EXPERIMENTAL = false

    // ---------- 模块日志（2026-08-13 用户要求「提供关闭日志的选项」） ----------
    /** 模块日志开关：true=打日志；false=热路径/渲染诊断日志静默（保留挂载一次性日志与 Log.e 错误日志）。
     *  改动需重启 SystemUI 生效（hook 端 install 时读取并缓存，热路径不每帧读 Prefs 防 binder 开销）。 */
    const val KEY_ENABLE_LOGS = "enable_logs"
    /** 模块日志开关默认值（2026-08-26 用户要求默认关：false = 默认关日志，需要调试时设置里手动开） */
    const val DEFAULT_ENABLE_LOGS = false

    // ---------- 调试绘制（悬浮球调试工具） ----------
    /** 调试绘制开关：true=显示悬浮球调试工具（抓取控件/输出区/玻璃清单/UI 树）；默认 false。
     *  hook 端 500ms TTL 重读，≤0.5s 生效，无需重启 SystemUI。 */
    const val KEY_DEBUG_DRAW_ENABLE = "debug_draw_enable"
    /** 调试绘制开关默认值（默认关） */
    const val DEFAULT_DEBUG_DRAW_ENABLE = false

    // ---------- 文字背景反转取色（doc/spec/21，2026-08-13） ----------
    /** 文字反转取色总开关：true=开启（默认）；false=不 hook QsColorUtil/TextView，走系统原逻辑。
     *  改动需重启 SystemUI 生效（hook 端 install 时读取并缓存）。 */
    const val KEY_TEXT_CONTRAST_ENABLE = "text_contrast_enable"
    /** 文字反转取色开关默认值（true = 默认开启） */
    const val DEFAULT_TEXT_CONTRAST_ENABLE = true

    // ---------- 黑遮罩 + 白字（doc/spec/35 + spec/40 + spec/41，2026-08-13；替代 spec/21 逐文字采样反色） ----------
    /** 黑遮罩总开关：true=开启（默认，保证玻璃上文字可读性）；false=不叠加黑遮罩压暗背景。
     *  [spec/63 2026-08-26 语义收窄] 仅控制**背景压暗**（shader uMaskAlpha / scrim 黑 tint）；
     *  **文字强制白已独立为 [KEY_TEXT_FORCE_WHITE_ENABLE]**（不再依赖本开关，spec/63 统一通道）。
     *  [spec/41 作用范围修正] 开启时对 SystemUI 下拉面板**所有**玻璃元素统一叠加黑色半透明遮罩
     *  （压暗底层背景采样色，整个 shade 背景统一变暗；不再按宿主 View 名逐卡片判定，Launcher
     *  文件夹不受影响）。改动需重启 SystemUI 生效（hook 端 install 时读取并缓存）。 */
    const val KEY_MASK_ENABLE = "mask_enable"
    /** 黑遮罩总开关默认值（true = 默认开启：压暗背景保证可读性） */
    const val DEFAULT_MASK_ENABLE = false

    /** 黑遮罩不透明度（0~1，clamp；默认 0.45 较明显）。仅 KEY_MASK_ENABLE=true 时生效。
     *  改动需重启 SystemUI 生效（hook 端 install 时读取并缓存）。 */
    const val KEY_MASK_ALPHA = "mask_alpha"
    /** 黑遮罩不透明度默认值（0.45f；[2026-08-14 用户要求"叠黑增强"：0.35 → 0.45]） */
    const val DEFAULT_MASK_ALPHA = 0.45f

    /** 玻璃内压暗（0~1，clamp；默认 0 = 不压暗）：喂 shader uMaskAlpha（步骤 2.5 混黑），
     *  与 [KEY_MASK_ENABLE] 开关、[KEY_MASK_ALPHA] 面板黑遮罩**相互独立**（后者只管面板底层
     *  scrim tint）。调整后 ≤1s 生效（渲染端 TTL 直读）。 */
    const val KEY_MASK_GLASS_DIM = "mask_glass_dim"
    /** 玻璃内压暗默认值（0 = 不压暗，玻璃保持亮） */
    const val DEFAULT_MASK_GLASS_DIM = 0f

    /** 遮罩下文字统一颜色（ARGB int，默认纯白 0xFFFFFFFF）。仅对中性色文字生效（app 彩色字尊重 app）。
     *  改动需重启 SystemUI 生效（hook 端 install 时读取并缓存）。 */
    const val KEY_MASK_TEXT_COLOR = "mask_text_color"
    /** 遮罩下文字统一颜色默认值（纯白） */
    const val DEFAULT_MASK_TEXT_COLOR = 0xFFFFFFFF.toInt()

    /** 文字强制白独立开关（doc/spec/63 统一注入通道，2026-08-26）：true=开启（默认）；false=不强制白。
     *  [spec/63 解耦] 与遮罩开关（[KEY_MASK_ENABLE]）无关——可「只要白字不要遮罩压暗」或「强制白单独可关」
     *  （控制中心文字高频闪动逃生通道）。开启时玻璃区域内 / heads-up 窗口内中性色文字恒白/灰
     *  （[KEY_MASK_TEXT_COLOR]，保留原 alpha），app 彩色字尊重 app。所有可读性场景（文字强制白 /
     *  heads-up 跟随系统取色 / 文字反转取色）统一走 `resolveReadableTextColor` 通道，杜绝「一个白一个黑」。
     *  改动需重启 SystemUI 生效（hook 端 install 时读取并缓存）。 */
    const val KEY_TEXT_FORCE_WHITE_ENABLE = "text_force_white_enable"
    /** 文字强制白独立开关默认值（true = 默认开启，与现状遮罩开启时强制白语义一致） */
    const val DEFAULT_TEXT_FORCE_WHITE_ENABLE = true

    // ---------- 系统控制中心模糊力度（doc/spec/61，2026-08-23） ----------
    /** 系统控制中心背景模糊力度（%，100 = 系统原值，0 = 不模糊，>100 增强）。改动即时生效。
     *  hook 端按 `panelBlurRadius × strength/100` 替换面板背景 BlurConfig.blurRadius
     *  （PlatformBlurDrawable.applyBlurConfig hook）。 */
    const val KEY_CC_BLUR_STRENGTH = "cc_blur_strength"
    /** 系统控制中心模糊力度默认值（100 = 系统原值，不干预） */
    const val DEFAULT_CC_BLUR_STRENGTH = 8

    /** 保留系统控制中心背景模糊：true = 恢复系统模糊（力度可调、阻止缩小）；
     *  false = 短路背景模糊链（背景透明露壁纸）。
     *  默认 **false**（2026-10-10 用户拍板「把关闭系统模糊默认开」，原默认 true）。改动需重启 SystemUI 生效。 */
    const val KEY_KEEP_SYSTEM_CC_BLUR = "keep_system_cc_blur"
    /** 保留系统控制中心模糊默认值（false = 默认关闭系统模糊，背景透明露壁纸） */
    const val DEFAULT_KEEP_SYSTEM_CC_BLUR = false

    /** [2026-09-16 面板材质底色] 移除系统面板背景的 MixColor 材质底色（用户反馈的"一层灰遮罩"）。
     *
     *  **根因**（反编译实证）：`PlatformBlurDrawable.applyBlurConfig`（PlatformBlurDrawable.java:51-53/79-83）
     *  里材质底色与模糊是两套独立参数 —— 底色 alpha 由 `applyMixColorAndScale(..., f)` 的 `f = blurAmount`
     *  决定，**完全不经过 blurRadius**。故「系统模糊力度」滑杆压不下这层灰（真机：力度 8% 灰照旧）。
     *
     *  **修法**：hook `NotifiAndQsPlatformBlurExKt.panelPlatformMixConfig(Context, boolean)` 返回
     *  `BlurMixConfig.None` 实例 → 系统走已有的 None 分支（PlatformBlurDrawable.java:72-78）
     *  只设 `setBlurRadius`、**不调 setMaterialParams** → 模糊保留、底色消失。
     *  None 是系统内置合法分支（非异常路径），无 ClassCastException 风险。
     *
     *  默认 **true**（用户明确反馈该底色是问题）。详见 doc/spec/69。 */
    const val KEY_REMOVE_PANEL_MIX_COLOR = "remove_panel_mix_color"
    /** 移除面板材质底色默认值（true = 移除那层灰） */
    const val DEFAULT_REMOVE_PANEL_MIX_COLOR = true

    // ---------- 桌面 Dock 栏液态玻璃化（doc/spec/66，2026-09-16） ----------
    /** 桌面 Dock 栏（常驻图标条）液态玻璃背景。默认 **false**（与模块「默认全关」原则一致）。
     *  直板机上系统原本因 `ScreenUtils.isSupportDockerExpandScreen()` 门控从不给 Dock 装配任何背景，
     *  开启后本模块解开该门控 + 让 Dock 拿到 LayerBlurDrawable + 用自研玻璃 shader 渲染。
     *  改动即时生效（hook 端 TTL 500ms 重读 Prefs，无需重启 Launcher）。 */
    const val KEY_DOCK_GLASS_ENABLE = "dock_glass_enable"
    /** 桌面 Dock 栏玻璃默认值（false = 不干预，Dock 保持系统原样透明） */
    const val DEFAULT_DOCK_GLASS_ENABLE = false

    // ---------- 桌面「问小布」入口液态玻璃化（doc/spec/76） ----------
    /** 桌面底部「✨问小布」入口（= `OplusPageIndicator` 的 BREENO 态）液态玻璃背景。
     *  默认 **false**（与模块「默认全关」原则一致）。该控件与页面小圆点是同一个 View 的两个互斥
     *  state，系统已自建 LayerBlurDrawable，开启后由本模块接管渲染。改动即时生效
     *  （hook 端 TTL 500ms 重读 Prefs，无需重启 Launcher）。 */
    const val KEY_PAGE_INDICATOR_GLASS = "page_indicator_glass_enable"
    /** 问小布入口玻璃默认值（false = 不干预，保持系统原样磨砂） */
    const val DEFAULT_PAGE_INDICATOR_GLASS = false

    // ---------- 桌面卡片/小组件液态玻璃化（doc/spec/78） ----------
    /** 桌面卡片 / 分组卡片 / 小组件背景液态玻璃。默认 **false**。这些元素的背景模糊由
     *  `CardBackgroundBlurManager.createBlurBackground()` 统一产生，开启后由本模块接管渲染。
     *  改动即时生效（hook 端 TTL 500ms 重读 Prefs，无需重启 Launcher）。 */
    const val KEY_CARD_GLASS_ENABLE = "card_glass_enable"
    /** 桌面卡片玻璃默认值（false = 不干预，保持系统原样磨砂） */
    const val DEFAULT_CARD_GLASS_ENABLE = false

    // ---------- 最近任务清除按钮液态玻璃化 ----------
    /** 最近任务页底部「清除全部」按钮液态玻璃。默认 **false**。该按钮是 launcher 进程内
     *  View 级平色 Paint 填充（不走系统模糊管线），开启后由本模块接管渲染。改动即时生效
     *  （hook 端 TTL 500ms 重读 Prefs，无需重启桌面）。 */
    const val KEY_CLEAR_ALL_BTN_GLASS = "clear_all_btn_glass_enable"
    /** 最近任务清除按钮玻璃默认值（false = 不干预，保持系统原样平色胶囊） */
    const val DEFAULT_CLEAR_ALL_BTN_GLASS = false

    // ---------- 音量条液态玻璃化（doc/spec/09） ----------
    /** 音量条（垂直滑条）液态玻璃。默认 **false**。音量条背景走 framework 窗口模糊
     *  （BackgroundBlurDrawable，draw 空实现），现有 drawable 替换链打不中，需按 spec/09
     *  的 hook 点在 `COUIVerticalSeekBar.draw` 上盖玻璃。改动即时生效（TTL 500ms 重读）。 */
    const val KEY_VOLUME_SEEKBAR_GLASS = "volume_seekbar_glass_enable"
    /** 音量条玻璃默认值（false = 不干预，保持系统原样） */
    const val DEFAULT_VOLUME_SEEKBAR_GLASS = false

    /** 进度条亮白填充：四滑杆白进度改近不透明亮磨砂填充（带顶边高光）。默认 **false**。
     *  与音量条玻璃、面板去白底开关相互独立。改动即时生效（TTL 500ms 重读）。 */
    const val KEY_VOLUME_PANEL_CLEAN = "volume_panel_clean_enable"
    /** 进度条亮白填充默认值（false = 保持系统原样进度色） */
    const val DEFAULT_VOLUME_PANEL_CLEAN = false

    /** 音量面板去白底：展开面板大背景（后面那层模糊）改纯模糊（去白/去调色），模糊保留。
     *  默认 **false**；关 = 系统原样（下一次展开自动恢复）。改动即时生效（TTL 500ms 重读）。 */
    const val KEY_VOLUME_PANEL_BACKDROP = "volume_panel_backdrop_enable"
    /** 音量面板去白底默认值（false = 保持系统原样含发白底色） */
    const val DEFAULT_VOLUME_PANEL_BACKDROP = false

    // ---------- 通知长按菜单玻璃化（doc/spec/135） ----------
    /** 通知长按菜单卡液态玻璃。默认 **false**；关 = 系统原样纯色菜单卡。
     *  改动即时生效（TTL 500ms 重读）。 */
    const val KEY_NOTIFICATION_POPUP_GLASS = "notification_popup_glass_enable"
    /** 通知长按菜单玻璃默认值（false = 不干预，保持系统原样） */
    const val DEFAULT_NOTIFICATION_POPUP_GLASS = false

    // ---------- 控制中心组件玻璃（设备空间入口卡 / 快捷开关展开卡） ----------
    /** 设备空间入口方块（控制中心设备空间 1×1 入口卡）玻璃。默认 **false**；
     *  开关切换即时生效（TTL 500ms 重读）。 */
    const val KEY_DEVICE_ENTRANCE_GLASS = "device_entrance_glass_enable"
    /** 设备空间入口方块玻璃默认值（false = 不干预，保持系统原样） */
    const val DEFAULT_DEVICE_ENTRANCE_GLASS = false

    /** 快捷开关展开大卡（控制中心点开 WiFi/蓝牙等磁贴后的展开面板）玻璃。默认 **false**；
     *  开关切换即时生效（TTL 500ms 重读）。 */
    const val KEY_QS_DETAIL_GLASS = "qs_detail_glass_enable"
    /** 快捷开关展开大卡玻璃默认值（false = 不干预，保持系统原样） */
    const val DEFAULT_QS_DETAIL_GLASS = false

    // ---------- 第三方 App 玻璃化（相册/计算器/智能侧边栏） ----------
    /** 相册玻璃化：拦截 App 内的视图背景模糊（RenderEffect / 窗口模糊 drawable）改画液态玻璃。
     *  默认 **false**；开启后需要进程内背景源（抓屏）通道可用，抓不到时保持系统原样模糊。
     *  改动即时生效（TTL 500ms 重读）。 */
    const val KEY_GALLERY_GLASS = "gallery_glass_enable"
    /** 相册玻璃化默认值（false = 不干预） */
    const val DEFAULT_GALLERY_GLASS = false

    /** 计算器玻璃化（对话框/弹出菜单背景）。默认 **false**，语义同 [KEY_GALLERY_GLASS]。 */
    const val KEY_CALCULATOR_GLASS = "calculator_glass_enable"
    /** 计算器玻璃化默认值（false = 不干预） */
    const val DEFAULT_CALCULATOR_GLASS = false

    /** 智能侧边栏玻璃化（侧边栏卡片/面板背景）。默认 **false**，语义同 [KEY_GALLERY_GLASS]。 */
    const val KEY_SMART_SIDEBAR_GLASS = "smart_sidebar_glass_enable"
    /** 智能侧边栏玻璃化默认值（false = 不干预） */
    const val DEFAULT_SMART_SIDEBAR_GLASS = false

    /** 速览（负一屏）玻璃化（卡片/控件背景模糊）。默认 **false**，语义同 [KEY_GALLERY_GLASS]。 */
    const val KEY_ASSISTANT_SCREEN_GLASS = "assistant_screen_glass_enable"
    /** 速览（负一屏）玻璃化默认值（false = 不干预） */
    const val DEFAULT_ASSISTANT_SCREEN_GLASS = false

    /** 相册背景采样范围探针（实验）：把 A 族视图背景 RenderEffect 临时替换为越界采样染色探针，
     * 肉眼判定 shader input 是否含节点外像素（折射环带可行性）。默认 **false**；
     * 开启时相册原模糊观感被探针覆盖（主体透传=清晰背景 + 边缘色环）。改动即时生效（TTL 500ms 重读）。 */
    const val KEY_GALLERY_SHADER_PROBE = "gallery_shader_probe_enable"
    /** 相册背景采样范围探针默认值（false = 不干预） */
    const val DEFAULT_GALLERY_SHADER_PROBE = false

    // ---------- 弹出通知（heads-up）文字跟随整体颜色（doc/spec/47，2026-08-13） ----------
    /** heads-up 通知文字跟随整体颜色独立开关：true=开启（默认）；false=不放开（heads-up 文字回退
     *  mask/系统原逻辑）。**与遮罩开关无关**：开启时无论遮罩开/关，heads-up 窗口内中性色文字都按系统
     *  黑白通道 [KEY_MASK_TEXT_COLOR 无关] 判定深/浅色（深底白字 / 浅底黑字，复用 spec/39 的
     *  `QsColorUtil.getQsColorState()`）；关闭时 heads-up 文字不受 spec/47 影响（mask 开统一白 /
     *  mask 关回退现状）。改动需重启 SystemUI 生效（hook 端 install 时读取并缓存）。 */
    const val KEY_HEADS_UP_TEXT_FOLLOW = "heads_up_text_follow"
    /** heads-up 文字跟随整体颜色开关默认值（true = 默认开启） */
    const val DEFAULT_HEADS_UP_TEXT_FOLLOW = true

    // ---------- 文字周期强制刷新间隔（doc/spec/60，2026-08-14） ----------
    /** 文字周期强制刷新间隔（毫秒）：遮罩/随系统取色开启时有活跃宿主时全树重扫，兜底同屏文字统一。100~5000。
     *  0=关闭周期刷新（仅事件触发：收起 / 系统黑白通道跳变 / 快照就绪）。改动即时生效（hook 端 tick 每次读最新值）。 */
    const val KEY_TEXT_FORCE_REFRESH_INTERVAL_MS = "text_force_refresh_interval_ms"
    /** 文字周期强制刷新间隔默认值（500ms：同屏漏注入文字 ≤500ms 内补刷白/取色，功耗可接受） */
    const val DEFAULT_TEXT_FORCE_REFRESH_INTERVAL_MS = 500

    // ---------- 流体云（灵动岛）展开卡片玻璃化（doc/spec/44，2026-08-13） ----------
    /** 流体云（Seedling）液态玻璃化总开关：true=开启（大卡片 + 小胶囊都玻璃化）；
     *  false=默认关闭（配置默认全关原则，用户手动开启）。仅 SystemUI 进程生效。
     *  改动需重启 SystemUI 生效（install 时读取并缓存）。
     *  ⚠️ 原常量误为 true（与本注释及 6 处调用点注释「默认关」矛盾，spec/90 审计发现），
     *  2026-10-10 用户拍板修复为 false。 */
    const val KEY_SEEDLING_CARD_GLASS_ENABLE = "seedling_card_glass_enable"
    /** 流体云玻璃化默认值（false = 默认关闭） */
    const val DEFAULT_SEEDLING_CARD_GLASS_ENABLE = false

    /** [2026-09-16 用户需求变更] 流体云**小胶囊**（迷你种子卡片/小岛）也玻璃化。
     *
     *  原实现只处理「展开大卡片」，高度 < 阈值的缩小胶囊被显式跳过（当时用户需求是"只大卡片"）。
     *  现用户要求小岛也加玻璃 → 本开关控制是否一并渲染小胶囊。
     *  与总开关的关系：总开关关 → 全部不渲染；总开关开 + 本项开 → 大卡片与小胶囊都渲染。
     *
     *  **小胶囊用全圆角**（`min(w,h)/2`，胶囊形），不复用大卡片的 `corner_ratio` 比例
     *  （小胶囊 min=状态栏高，0.22 比例只会得到小圆角矩形，不是胶囊）。 */
    const val KEY_SEEDLING_CAPSULE_GLASS = "seedling_capsule_glass"
    /** 流体云小胶囊玻璃化默认值（**false = 默认关**，2026-10-10 用户拍板；与 BlurDrawHook 侧注释已统一） */
    const val DEFAULT_SEEDLING_CAPSULE_GLASS = false

    /** 展开大卡片判定高度系数：子卡片 View 高度 ≥（容器高度 × 本系数）判定展开大卡片，缩小胶囊跳过。
     *  ⚠️ [2026-08-15 修复] 容器（CapsulePluginContainer）FrameLayout AT_MOST 钳制子 View 高度 = 容器高
     *  （真机：容器 160 = 子 View 160），原默认 1.2 → 阈值 192 超容器高恒不命中。改为 0.6（阈值 96，
     *  展开 160 命中，缩小胶囊 <96 跳过）。默认 0.6f。 */
    const val KEY_SEEDLING_CARD_EXPAND_HEIGHT_RATIO = "seedling_card_expand_height_ratio"
    /** 展开大卡片判定高度系数默认值（0.6f） */
    const val DEFAULT_SEEDLING_CARD_EXPAND_HEIGHT_RATIO = 0.6f

    /** 展开大卡片圆角半径启发式系数：cornerRadius = min(w,h) × 本系数
     *  （种子卡片不走 posteffect，无 BlurConfig 可反射，用形状启发式推断大圆角）。默认 0.22f。 */
    const val KEY_SEEDLING_CARD_CORNER_RATIO = "seedling_card_corner_ratio"
    /** 展开大卡片圆角系数默认值（0.22f） */
    const val DEFAULT_SEEDLING_CARD_CORNER_RATIO = 0.22f

    /** 流体云展开卡片背景透明：宿主卡片背景（OplusCustomRow 内 customBackgroundNormal =
     *  NotificationBackgroundView，onDraw 直绘 mBackground 字段）置透明，让液态玻璃透出。
     *  ⚠️ [2026-08-15 语义变更] 原语义「先观察被遮盖再开」已废弃——本需求是**用户硬需求**：
     *  默认改为 true（默认透明）。实现用反射替换 NotificationBackgroundView.mBackground 字段为透明
     *  drawable（View.background 置空无效，反编译 onDraw 实证）。关闭本项则恢复系统半透明背景
     *  （custom_material_bg），此时玻璃被背景压暗。 */
    const val KEY_SEEDLING_CARD_FORCE_TRANSPARENT_BG = "seedling_card_force_transparent_bg"
    /** 强制透明背景默认值（true = 默认透明，用户硬需求） */
    const val DEFAULT_SEEDLING_CARD_FORCE_TRANSPARENT_BG = true

    // ---------- 锁屏大时钟液态玻璃化（doc/spec/43，2026-08-13） ----------
    /** 锁屏大时钟液态玻璃化开关：true=把锁屏大时钟数字文字用液态玻璃 shader 填充（采样时钟壁纸衬底位图，
     *  数字呈玻璃质感，模糊/折射/vibrancy 玻璃效果作用在文字字形上）；false=系统原数字颜色。
     *  改动需重启 SystemUI 生效（hook 端 install 时读取并缓存）。
     *  ⚠️ 与下拉面板玻璃（BlurDrawHook）相互独立：锁屏时钟走 BlurBitmapFactory 位图投递链
     *  （KeyguardWallpaperDeliveryController → setBlurWallpaperBitmap → ThemePlugin），不经 posteffect
     *  drawBlurShader 链，本开关独立控制。 */
    const val KEY_LOCKSCREEN_CLOCK_GLASS = "lockscreen_clock_glass_enable"
    /** 锁屏大时钟液态玻璃化开关默认值（false = 默认关闭，防误伤） */
    const val DEFAULT_LOCKSCREEN_CLOCK_GLASS = false

    /** 锁屏大时钟玻璃数字的内部轻模糊半径 px（0=清晰透镜（壁纸原样透过字形）；>0=磨砂玻璃质感，
     *  采样 1/4 降采样图做 5x5 高斯，默认 6 轻微磨砂保证可读性）。改动即时生效（每次绘制读 Prefs）。 */
    const val KEY_LOCKSCREEN_CLOCK_BLUR = "lockscreen_clock_glass_blur"
    /** 锁屏大时钟玻璃数字内部轻模糊半径默认值（6px 轻微磨砂） */
    const val DEFAULT_LOCKSCREEN_CLOCK_BLUR = 6f

    // ---------- 桌面（Launcher）动画参数调节（doc/spec/64，2026-09-16） ----------
    /** 动画参数分组：从桌面打开应用（对应 AnimType.OPEN_FROM_HOME） */
    const val ANIM_GROUP_OPEN = "open"
    /** 动画参数分组：应用退出返回桌面（SWIPE_TO_HOME / REMOTE_CLOSE_TO_HOME 系） */
    const val ANIM_GROUP_CLOSE = "close"
    /** 动画参数分组：打断动画（REVERSE_TO_OPEN 系） */
    const val ANIM_GROUP_BREAK = "break"
    /** 全部动画参数分组（UI 顺序 = 打开 / 关闭 / 打断） */
    val ANIM_GROUPS = listOf(ANIM_GROUP_OPEN, ANIM_GROUP_CLOSE, ANIM_GROUP_BREAK)

    /** 参数名：中心 X 轴刚度（→ CustomRectFSpringAnim.mCenterXStiffness） */
    const val ANIM_PARAM_X_STIFFNESS = "x_stiffness"
    /** 参数名：中心 X 轴阻尼（→ mCenterXDamping，运行时 ÷100） */
    const val ANIM_PARAM_X_DAMPING = "x_damping"
    /** 参数名：中心 Y 轴刚度（→ mRectYStiffness） */
    const val ANIM_PARAM_Y_STIFFNESS = "y_stiffness"
    /** 参数名：中心 Y 轴阻尼（→ mRectYDamping，运行时 ÷100） */
    const val ANIM_PARAM_Y_DAMPING = "y_damping"
    /** 参数名：宽度缩放刚度（→ mWidthStiffness） */
    const val ANIM_PARAM_WIDTH_STIFFNESS = "width_stiffness"
    /** 参数名：宽度缩放阻尼（→ mWidthDamping，运行时 ÷100） */
    const val ANIM_PARAM_WIDTH_DAMPING = "width_damping"
    /** 参数名：宽高比刚度（→ mRadioStiffness；修改版沿用 height_ 命名） */
    const val ANIM_PARAM_HEIGHT_STIFFNESS = "height_stiffness"
    /** 参数名：宽高比阻尼（→ mRadioDamping，运行时 ÷100；修改版沿用 height_ 命名） */
    const val ANIM_PARAM_HEIGHT_DAMPING = "height_damping"
    /** 参数名：图标透明度淡入淡出时长（ms，作用于 LauncherContentAnimManager） */
    const val ANIM_PARAM_FADE_DURATION = "fade_duration"

    /** 全部弹簧参数名（UI 展示顺序 = X / Y / 宽度 / 宽高比，每项先阻尼后刚度） */
    val ANIM_SPRING_PARAMS = listOf(
        ANIM_PARAM_X_DAMPING, ANIM_PARAM_X_STIFFNESS,
        ANIM_PARAM_Y_DAMPING, ANIM_PARAM_Y_STIFFNESS,
        ANIM_PARAM_WIDTH_DAMPING, ANIM_PARAM_WIDTH_STIFFNESS,
        ANIM_PARAM_HEIGHT_DAMPING, ANIM_PARAM_HEIGHT_STIFFNESS,
    )

    /** 组开关键（`anim_<组>_enabled`），默认 false（不改原版动画） */
    fun animEnabledKey(group: String): String = "anim_${group}_enabled"

    /** 组内参数键（`anim_<组>_<参数>`） */
    fun animKey(group: String, param: String): String = "anim_${group}_$param"

    /** 组开关默认值（false = 默认关闭，与原版动画一致） */
    const val DEFAULT_ANIM_GROUP_ENABLED = true
    /** 刚度默认值（open/break：580） */
    const val DEFAULT_ANIM_STIFFNESS_OPEN = 220
    /** 刚度默认值（close：120） */
    const val DEFAULT_ANIM_STIFFNESS_CLOSE = 220
    /** 阻尼默认值（open/break：140 → 运行时 1.40） */
    const val DEFAULT_ANIM_DAMPING_OPEN = 100
    /** 阻尼默认值（close：87 → 运行时 0.87） */
    const val DEFAULT_ANIM_DAMPING_CLOSE = 100
    /** 图标透明度时长默认值（open/break：340ms） */
    const val DEFAULT_ANIM_FADE_DURATION_OPEN = 420
    /** 图标透明度时长默认值（close：510ms） */
    const val DEFAULT_ANIM_FADE_DURATION_CLOSE = 420
    /** 刚度可调范围下限（UI 滑杆） */
    const val ANIM_STIFFNESS_UI_MIN = 1
    /** 刚度可调范围上限（UI 滑杆；运行时 clamp 上限 5000） */
    const val ANIM_STIFFNESS_UI_MAX = 3000
    /** 阻尼可调范围下限（UI 滑杆；运行时 ÷100 后 clamp 0.05） */
    const val ANIM_DAMPING_UI_MIN = 10
    /** 阻尼可调范围上限（UI 滑杆；运行时 ÷100 后 clamp 5.0） */
    const val ANIM_DAMPING_UI_MAX = 300
    /** 图标透明度时长范围（ms） */
    const val ANIM_FADE_DURATION_MIN = 50
    /** 图标透明度时长范围（ms） */
    const val ANIM_FADE_DURATION_MAX = 1500
    /** 图标透明度时长步进（ms） */
    const val ANIM_FADE_DURATION_STEP = 10
    /** 刚度运行时 clamp 上限（照抄修改版 getFloat 的 5000f） */
    const val ANIM_STIFFNESS_RUNTIME_MAX = 5000f
    /** 阻尼运行时除以的倍数（照抄修改版：配置值 ÷100） */
    const val ANIM_DAMPING_DIVISOR = 100f
    /** 阻尼运行时 clamp 范围（照抄修改版 0.05~5.0） */
    const val ANIM_DAMPING_RUNTIME_MIN = 0.05f
    /** 阻尼运行时 clamp 范围（照抄修改版 0.05~5.0） */
    const val ANIM_DAMPING_RUNTIME_MAX = 5.0f
    /** 刚度运行时 clamp 下限（照抄修改版 1.0f） */
    const val ANIM_STIFFNESS_RUNTIME_MIN = 1.0f

    /** 组默认刚度（close 组与其他两组不同） */
    fun animDefaultStiffness(group: String): Int =
        if (group == ANIM_GROUP_CLOSE) DEFAULT_ANIM_STIFFNESS_CLOSE else DEFAULT_ANIM_STIFFNESS_OPEN

    /** 组默认阻尼（close 组与其他两组不同） */
    fun animDefaultDamping(group: String): Int =
        if (group == ANIM_GROUP_CLOSE) DEFAULT_ANIM_DAMPING_CLOSE else DEFAULT_ANIM_DAMPING_OPEN

    /** 组默认图标透明度时长（close 组与其他两组不同） */
    fun animDefaultFadeDuration(group: String): Int =
        if (group == ANIM_GROUP_CLOSE) DEFAULT_ANIM_FADE_DURATION_CLOSE else DEFAULT_ANIM_FADE_DURATION_OPEN

    // ---------- iOS 动态倾斜 / 透视（doc/spec/65，2026-09-16；实验性，默认关） ----------
    /** 动态倾斜总开关：true=应用启动/退出/打断动画期间对 app 窗口与图标施加 3D 倾斜（近大远小透视）。
     *  **实验性功能**，默认 false；关闭时完全不干预（零影响）。改动需重启桌面进程生效。 */
    const val KEY_ANIM_TILT_ENABLED = "anim_tilt_enabled"
    /** 倾斜总开关默认值（false = 实验性功能默认关闭） */
    const val DEFAULT_ANIM_TILT_ENABLED = true

    /** 倾斜强度（%，0~200，默认 100）。0 = 不倾斜；200 = 两倍角度。运行时 ÷100 作倍率。 */
    const val KEY_ANIM_TILT_STRENGTH = "anim_tilt_strength_percent"
    /** 倾斜强度默认值（100 = 原值） */
    const val DEFAULT_ANIM_TILT_STRENGTH = 100
    /** 倾斜强度上限（200%，超过则 clamp） */
    const val ANIM_TILT_STRENGTH_MAX = 200

    /** 透视扭曲程度（%，0~150，默认 100）。调节上下宽度差；0 = 无近大远小。运行时 ÷100 作倍率。 */
    const val KEY_ANIM_TILT_PERSPECTIVE = "anim_tilt_perspective_percent"
    /** 透视扭曲默认值（100 = 原值） */
    const val DEFAULT_ANIM_TILT_PERSPECTIVE = 100
    /** 透视扭曲上限（150%，超过则 clamp） */
    const val ANIM_TILT_PERSPECTIVE_MAX = 150

    // [2026-09-17 死键清理] 场景过滤键（KEY_SCENE_FILTER_ENABLED / KEY_SCENE_WHITELIST）已删除：
    // 零调用方（SceneFilter.kt 尚处占位阶段，仅注释提及）。

    // ---------- 读取（hook 端，SystemUI 进程） ----------
    fun read(api: XposedInterface): SharedPreferences = api.getRemotePreferences(PREFS_NAME)

    /**
     * int 配置兼容读取：先 getInt；**存量数据可能是 Float**（2026-08-13 前 UI 用 bindFloatSeekBar 写
     * int 键 → framework prefs 存了 Float）→ getInt 抛 ClassCastException → 回退 getFloat 转 Int。
     * 新写入为 Int 后仍兼容老 Float 值，无需用户重设。
     */
    fun readIntCompat(prefs: SharedPreferences, key: String, default: Int): Int {
        return try {
            prefs.getInt(key, default)
        } catch (t: Throwable) {
            try {
                prefs.getFloat(key, default.toFloat()).toInt()
            } catch (t2: Throwable) {
                default
            }
        }
    }

    fun masterEnabled(api: XposedInterface): Boolean = read(api).getBoolean(KEY_MASTER, false)

    @Volatile
    private var glassEnableCache: Boolean = DEFAULT_GLASS_ENABLE

    @Volatile
    private var glassEnableCacheAtMs = 0L

    /** 玻璃渲染总开关（500ms TTL 缓存，渲染热路径零 IPC；关 = 不套自研 shader、回退系统模糊）。 */
    fun glassEnabled(api: XposedInterface?): Boolean {
        if (api == null) return DEFAULT_GLASS_ENABLE
        val now = SystemClock.uptimeMillis()
        if (now - glassEnableCacheAtMs < 500L) return glassEnableCache
        glassEnableCacheAtMs = now
        val v = try {
            read(api).getBoolean(KEY_GLASS_ENABLE, DEFAULT_GLASS_ENABLE)
        } catch (t: Throwable) {
            Lg.w(TAG, "prefs read glass_enable failed, default $DEFAULT_GLASS_ENABLE", t)
            DEFAULT_GLASS_ENABLE
        }
        glassEnableCache = v
        return v
    }

    @Volatile
    private var debugDrawCache: Boolean = DEFAULT_DEBUG_DRAW_ENABLE

    @Volatile
    private var debugDrawCacheAtMs = 0L

    /** 调试绘制开关（500ms TTL 缓存；关 = 无窗口无 tick，仅本 volatile 读）。 */
    fun debugDrawEnabled(api: XposedInterface?): Boolean {
        if (api == null) return DEFAULT_DEBUG_DRAW_ENABLE
        val now = SystemClock.uptimeMillis()
        if (now - debugDrawCacheAtMs < 500L) return debugDrawCache
        debugDrawCacheAtMs = now
        val v = try {
            read(api).getBoolean(KEY_DEBUG_DRAW_ENABLE, DEFAULT_DEBUG_DRAW_ENABLE)
        } catch (t: Throwable) {
            Lg.w(TAG, "prefs read debug_draw_enable failed, default $DEFAULT_DEBUG_DRAW_ENABLE", t)
            DEFAULT_DEBUG_DRAW_ENABLE
        }
        debugDrawCache = v
        return v
    }

    @Volatile
    private var deviceEntranceCache: Boolean = DEFAULT_DEVICE_ENTRANCE_GLASS

    @Volatile
    private var deviceEntranceCacheAtMs = 0L

    /** 设备空间入口方块玻璃开关（500ms TTL 缓存；注册链每次 draw 读）。 */
    fun deviceEntranceGlassEnabled(api: XposedInterface?): Boolean {
        if (api == null) return DEFAULT_DEVICE_ENTRANCE_GLASS
        val now = SystemClock.uptimeMillis()
        if (now - deviceEntranceCacheAtMs < 500L) return deviceEntranceCache
        deviceEntranceCacheAtMs = now
        val v = try {
            read(api).getBoolean(KEY_DEVICE_ENTRANCE_GLASS, DEFAULT_DEVICE_ENTRANCE_GLASS)
        } catch (t: Throwable) {
            Lg.w(TAG, "prefs read device_entrance_glass_enable failed, default $DEFAULT_DEVICE_ENTRANCE_GLASS", t)
            DEFAULT_DEVICE_ENTRANCE_GLASS
        }
        deviceEntranceCache = v
        return v
    }

    @Volatile
    private var qsDetailCache: Boolean = DEFAULT_QS_DETAIL_GLASS

    @Volatile
    private var qsDetailCacheAtMs = 0L

    /** 快捷开关展开大卡玻璃开关（500ms TTL 缓存；注册链每次 draw 读）。 */
    fun qsDetailGlassEnabled(api: XposedInterface?): Boolean {
        if (api == null) return DEFAULT_QS_DETAIL_GLASS
        val now = SystemClock.uptimeMillis()
        if (now - qsDetailCacheAtMs < 500L) return qsDetailCache
        qsDetailCacheAtMs = now
        val v = try {
            read(api).getBoolean(KEY_QS_DETAIL_GLASS, DEFAULT_QS_DETAIL_GLASS)
        } catch (t: Throwable) {
            Lg.w(TAG, "prefs read qs_detail_glass_enable failed, default $DEFAULT_QS_DETAIL_GLASS", t)
            DEFAULT_QS_DETAIL_GLASS
        }
        qsDetailCache = v
        return v
    }

    // ---------- 折射区域可调（doc/spec/95，2026-10-10） ----------

    /** 热路径重读 TTL（ms）：照抄 [KEY_DOCK_GLASS_ENABLE] 的 500ms 模式 —— 改动 ≤0.5s 生效，
     *  TTL 内直接返回 @Volatile 缓存，渲染热路径零 IPC。 */
    private const val REFRACTION_RATIO_TTL_MS = 500L

    private const val TAG = "LiquidGlass"

    @Volatile
    private var refractionRatioCache: Float = DEFAULT_REFRACTION_HEIGHT_RATIO

    @Volatile
    private var refractionRatioCacheAtMs = 0L

    /**
     * 折射环带高度比例（元素短边 × 本值 = uRefractionHeight）。[TTL][REFRACTION_RATIO_TTL_MS] 节流缓存，
     * 供 BlurDrawHook / LauncherHook 的 buildParams 在渲染热路径调用：TTL 内零 IPC，超时才重读一次框架侧
     * Prefs 并回写缓存。读取失败 / api 不可用 → 保持默认 0.5（= 改动前 `min(w,h)/2f`）。
     *
     * 返回值恒已 clamp 到 [REFRACTION_HEIGHT_RATIO_MIN]~[REFRACTION_HEIGHT_RATIO_MAX]。
     * 缓存按对象级共享（SystemUI 进程一份、Launcher 进程一份），两端各自计时互不影响。
     */
    fun refractionHeightRatio(api: XposedInterface?): Float {
        if (api == null) return DEFAULT_REFRACTION_HEIGHT_RATIO
        val now = SystemClock.uptimeMillis()
        if (now - refractionRatioCacheAtMs < REFRACTION_RATIO_TTL_MS) return refractionRatioCache
        refractionRatioCacheAtMs = now
        val raw = try {
            read(api).getFloat(KEY_REFRACTION_HEIGHT_RATIO, DEFAULT_REFRACTION_HEIGHT_RATIO)
        } catch (t: Throwable) {
            Lg.w(TAG, "prefs read refraction_height_ratio failed, default $DEFAULT_REFRACTION_HEIGHT_RATIO", t)
            DEFAULT_REFRACTION_HEIGHT_RATIO
        }
        val v = raw.coerceIn(REFRACTION_HEIGHT_RATIO_MIN, REFRACTION_HEIGHT_RATIO_MAX)
        if (v != raw) {
            Lg.w(TAG, "refraction_height_ratio=$raw out of range, clamp to $v")
        }
        refractionRatioCache = v
        return v
    }

    // ---------- 折射区域双模式：相对（短边比例）/ 绝对（像素）（doc/spec/96，2026-10-10） ----------

    @Volatile
    private var refractionModeCache: Int = DEFAULT_REFRACTION_HEIGHT_MODE

    @Volatile
    private var refractionModeCacheAtMs = 0L

    @Volatile
    private var refractionAbsPxCache: Float = DEFAULT_REFRACTION_HEIGHT_PX

    @Volatile
    private var refractionAbsPxCacheAtMs = 0L

    /**
     * 折射区域模式：[REFRACTION_HEIGHT_MODE_RATIO] 相对（短边比例）/ [REFRACTION_HEIGHT_MODE_ABS] 绝对（像素）。
     * [TTL][REFRACTION_RATIO_TTL_MS] 节流缓存（与 [refractionHeightRatio] 同一 TTL，两进程各一份）。
     * 读失败 / 越界 → 回默认 0（相对，= spec/95 现行为）。
     */
    fun refractionHeightMode(api: XposedInterface?): Int {
        if (api == null) return DEFAULT_REFRACTION_HEIGHT_MODE
        val now = SystemClock.uptimeMillis()
        if (now - refractionModeCacheAtMs < REFRACTION_RATIO_TTL_MS) return refractionModeCache
        refractionModeCacheAtMs = now
        val raw = try {
            readIntCompat(read(api), KEY_REFRACTION_HEIGHT_MODE, DEFAULT_REFRACTION_HEIGHT_MODE)
        } catch (t: Throwable) {
            Lg.w(TAG, "prefs read refraction_height_mode failed, default $DEFAULT_REFRACTION_HEIGHT_MODE", t)
            DEFAULT_REFRACTION_HEIGHT_MODE
        }
        val v = if (raw == REFRACTION_HEIGHT_MODE_ABS) REFRACTION_HEIGHT_MODE_ABS else REFRACTION_HEIGHT_MODE_RATIO
        if (v != raw) Lg.w(TAG, "refraction_height_mode=$raw unknown, fallback to $v")
        refractionModeCache = v
        return v
    }

    /**
     * 折射区域绝对像素值（仅 [REFRACTION_HEIGHT_MODE_ABS] 模式生效）。[TTL][REFRACTION_RATIO_TTL_MS] 节流缓存，
     * 返回值恒已 clamp 到 [REFRACTION_HEIGHT_PX_MIN]~[REFRACTION_HEIGHT_PX_MAX]。
     */
    fun refractionHeightAbsolutePx(api: XposedInterface?): Float {
        if (api == null) return DEFAULT_REFRACTION_HEIGHT_PX
        val now = SystemClock.uptimeMillis()
        if (now - refractionAbsPxCacheAtMs < REFRACTION_RATIO_TTL_MS) return refractionAbsPxCache
        refractionAbsPxCacheAtMs = now
        val raw = try {
            read(api).getFloat(KEY_REFRACTION_HEIGHT_PX, DEFAULT_REFRACTION_HEIGHT_PX)
        } catch (t: Throwable) {
            Lg.w(TAG, "prefs read refraction_height_px failed, default $DEFAULT_REFRACTION_HEIGHT_PX", t)
            DEFAULT_REFRACTION_HEIGHT_PX
        }
        val v = raw.coerceIn(REFRACTION_HEIGHT_PX_MIN, REFRACTION_HEIGHT_PX_MAX)
        if (v != raw) Lg.w(TAG, "refraction_height_px=$raw out of range, clamp to $v")
        refractionAbsPxCache = v
        return v
    }

    /**
     * **统一出口**：折射环带高度（px，= `uRefractionHeight`）。SystemUI（BlurDrawHook）、桌面（LauncherHook）
     * 与设置页预览三处一律走本函数，保证同一条恒等式：
     *
     * - 相对模式：`元素短边 × refractionHeightRatio`（= spec/95 原式，默认 0.5 → 零变化）
     * - 绝对模式：`refractionHeightAbsolutePx`（固定 px，不随元素大小变）
     *
     * 模式或值变化都会改变返回值 → 参与 `Params.hashCode()` / `AppliedKey` 比对 → 触发 uniform 重设。
     * 热路径调用安全：三项各自带 500ms TTL，TTL 内零 IPC。
     */
    fun refractionHeightPx(shortSide: Float, api: XposedInterface?): Float {
        return if (refractionHeightMode(api) == REFRACTION_HEIGHT_MODE_ABS) {
            refractionHeightAbsolutePx(api)
        } else {
            shortSide * refractionHeightRatio(api)
        }
    }

    // ---------- 统一界面圆角（doc/spec/101，2026-10-11） ----------
    //
    // 需求（用户原话）：「有人反馈统一圆角好看，建议加一个统一界面圆角，打开以后控制中心和通知中心
    // 都使用同一个圆角，只有这两页，和通知卡片，其他的不要误伤。」
    //
    // ⚠️ 键名/语义与以下历史项**均无关**，勿合并、勿联想：
    // - 与 doc/spec/91 的「锁屏岛圆角回退」无关（那是反射 BlurConfig 失败后的兜底策略，不涉配置键）；
    // - 与已删除死键 `refraction_height`（spec/74 清理）无关（那是折射环带高度，不是本体圆角）；
    // - 与 [KEY_SEEDLING_CARD_CORNER_RATIO] 无关（流体云大卡启发式系数，本键不作用于流体云）。
    //
    // 默认 **false** = 完全不生效（[BlurDrawHook.RegionEntry.unifiedScope] 恒 false 的元素 +
    // 开关关时 computeGeometry 走原路径）→ 升级后逐像素零变化。

    /** 统一界面圆角开关：true=作用域内元素本体圆角统一为 [KEY_UNIFIED_CORNER_PX]（仍受短边一半封顶）；
     *  false（默认）= 不干预，全部走元素自身已解析圆角（= 改动前行为，零变化）。
     *  作用域白名单在注册期判定（BlurDrawHook.isUnifiedCornerScope → RegionEntry.unifiedScope），
     *  只覆盖：通知中心/控制中心面板层 + 通知卡片 + 音量/亮度滑杆宿主；
     *  Launcher / 锁屏胶囊系 / 侧滑按钮 / 文件夹 / Taskbar 一律不进白名单（防误伤）。
     *  改动 ≤1s 生效（TTL 500ms 重读 + geometryCache 1s 强制重算，无需重启 SystemUI）。 */
    const val KEY_UNIFIED_CORNER_ENABLE = "unified_corner_enable"
    /** 统一界面圆角开关默认值（false = 默认关闭，防误伤；符合「默认全关」原则） */
    const val DEFAULT_UNIFIED_CORNER_ENABLE = false

    /** 统一圆角半径（px）。仅 [KEY_UNIFIED_CORNER_ENABLE]=true 时生效。
     *  clamp [UNIFIED_CORNER_PX_MIN]~[UNIFIED_CORNER_PX_MAX]。
     *  ⚠️ 这是**半径**（shader 侧 uCornerRadius 语义）；滑杆文案亦为「统一圆角（px）」。 */
    const val KEY_UNIFIED_CORNER_PX = "unified_corner_px"
    /** 统一圆角默认半径（48f px ≈ 13dp@3x，通知/控制中心卡片观感接近的中值） */
    const val DEFAULT_UNIFIED_CORNER_PX = 48f
    /** 统一圆角下限（8f px：再小看不出与默认的差别） */
    const val UNIFIED_CORNER_PX_MIN = 8f
    /** 统一圆角上限（200f px：超过常见元素短边一半后会被短边封顶，再大无意义） */
    const val UNIFIED_CORNER_PX_MAX = 200f

    /**
     * [doc/spec/101] 统一圆角半径规则（防误伤核心，纯函数、无副作用）：
     *
     *     最终圆角 = min( max(统一半径, 元素自身已解析圆角), min(w, h) / 2 )
     *
     * 三类元素代入验证（统一半径 U=48）：
     * - 矩形卡片（自身角 r=40 < U，短边一半=50）→ min(max(48,40),50) = **48** ✓ 抬到统一值
     * - 圆形磁贴（自身角 r = min/2 = 50）→ min(max(48,50),50) = **50** ✓ 保持圆形，不被压扁
     * - 药丸滑杆（自身角 r = 半高 20，min(w,h)/2 = 20）→ min(max(48,20),20) = **20** ✓ 保持药丸
     * - 小元素封顶（U=200，min/2=30，自身 10）→ min(max(200,10),30) = **30** ✓ 不越几何上限
     * - 反射失败的方角元素（自身 r=0）→ min(max(48,0),min/2) = min(48, min/2) ✓ 方角也被统一
     *
     * @param unifiedPx 统一半径（已 clamp 过的 [KEY_UNIFIED_CORNER_PX]）
     * @param ownRadius 元素自身已解析圆角（RegionEntry.cornerRadius，可为 0=方角）
     * @param w / @param h 元素 viewport 宽高 px（>0，调用方已保证）
     */
    fun unifiedCornerRadius(unifiedPx: Float, ownRadius: Float, w: Float, h: Float): Float {
        val cap = minOf(w, h) * 0.5f
        return minOf(maxOf(unifiedPx, ownRadius), cap)
    }

    /** 热路径重读 TTL（ms）：沿用 [refractionHeightRatio] 的 500ms 模式 —— 改动 ≤0.5s 生效，
     *  TTL 内直接返回 @Volatile 缓存，渲染热路径零 IPC。 */
    private const val UNIFIED_CORNER_TTL_MS = 500L

    @Volatile
    private var unifiedCornerEnableCache: Boolean = DEFAULT_UNIFIED_CORNER_ENABLE

    @Volatile
    private var unifiedCornerEnableCacheAtMs = 0L

    @Volatile
    private var unifiedCornerPxCache: Float = DEFAULT_UNIFIED_CORNER_PX

    @Volatile
    private var unifiedCornerPxCacheAtMs = 0L

    /**
     * 统一界面圆角开关（[TTL][UNIFIED_CORNER_TTL_MS] 节流缓存，与 [refractionHeightRatio] 同款骨架，
     * 两进程各一份）。读取失败 / api 不可用 → 默认 false（= 不干预，零变化）。
     */
    fun unifiedCornerEnabled(api: XposedInterface?): Boolean {
        if (api == null) return DEFAULT_UNIFIED_CORNER_ENABLE
        val now = SystemClock.uptimeMillis()
        if (now - unifiedCornerEnableCacheAtMs < UNIFIED_CORNER_TTL_MS) return unifiedCornerEnableCache
        unifiedCornerEnableCacheAtMs = now
        val v = try {
            read(api).getBoolean(KEY_UNIFIED_CORNER_ENABLE, DEFAULT_UNIFIED_CORNER_ENABLE)
        } catch (t: Throwable) {
            Lg.w(TAG, "prefs read unified_corner_enable failed, default $DEFAULT_UNIFIED_CORNER_ENABLE", t)
            DEFAULT_UNIFIED_CORNER_ENABLE
        }
        unifiedCornerEnableCache = v
        return v
    }

    /**
     * 统一圆角半径 px（[TTL][UNIFIED_CORNER_TTL_MS] 节流缓存）。
     * 返回值恒已 clamp 到 [UNIFIED_CORNER_PX_MIN]~[UNIFIED_CORNER_PX_MAX]。
     */
    fun unifiedCornerPx(api: XposedInterface?): Float {
        if (api == null) return DEFAULT_UNIFIED_CORNER_PX
        val now = SystemClock.uptimeMillis()
        if (now - unifiedCornerPxCacheAtMs < UNIFIED_CORNER_TTL_MS) return unifiedCornerPxCache
        unifiedCornerPxCacheAtMs = now
        val raw = try {
            read(api).getFloat(KEY_UNIFIED_CORNER_PX, DEFAULT_UNIFIED_CORNER_PX)
        } catch (t: Throwable) {
            Lg.w(TAG, "prefs read unified_corner_px failed, default $DEFAULT_UNIFIED_CORNER_PX", t)
            DEFAULT_UNIFIED_CORNER_PX
        }
        val v = raw.coerceIn(UNIFIED_CORNER_PX_MIN, UNIFIED_CORNER_PX_MAX)
        if (v != raw) Lg.w(TAG, "unified_corner_px=$raw out of range, clamp to $v")
        unifiedCornerPxCache = v
        return v
    }

    // [2026-09-17 死键清理] backgroundMode / isSceneFilterEnabled / sceneWhitelist 三个读取辅助
    // 已随对应死键一并删除（零调用方）。

    // ---------- [doc/spec/112] 通知卡倾斜（路线 B 玻璃内假透视；驱动 = 弹出/收回动画，手指不参与） ----------

    /** 通知卡倾斜开关：true = 通知卡**弹出/收回动画期间**玻璃短暂倾斜入场/出场，到位自动回正
     *  （只斜玻璃不斜文字；驱动与角度见 [KEY_NOTIF_TILT_DEG]）。
     *  **默认 false**（模块「默认全关 + 每个效果一个开关」铁律）；关闭时渲染侧恒 0 → 逐像素零变化。
     *  改动 ≤0.5s 生效（TTL 500ms 重读，无需重启 SystemUI）。 */
    const val KEY_NOTIF_TILT_ENABLE = "notif_tilt_enable"
    /** 通知卡倾斜开关默认值（false = 默认关闭） */
    const val DEFAULT_NOTIF_TILT_ENABLE = false

    /** 通知卡倾斜角度（°，0~45，默认 3）。**用户 2026-10-13 拍板：自由调整，废除 spec/94 §六 6° 安全档**
     *  —— 45° 时透视分母 D·cosθ−Y·sinθ ≈168>0（D=400、半高 163）仍安全，shader 另有 denom<1 夹紧兜底；
     *  更大角度畸变由用户自行权衡。0 = 不倾斜。仅 [KEY_NOTIF_TILT_ENABLE]=true 时生效。 */
    const val KEY_NOTIF_TILT_DEG = "notif_tilt_deg"
    /** 通知卡倾斜角度默认值（3°，沿用 spec/94 基准档） */
    const val DEFAULT_NOTIF_TILT_DEG = 3
    /** 通知卡倾斜角度上限（45°；用户拍板放开，见 [KEY_NOTIF_TILT_DEG] 注释） */
    const val NOTIF_TILT_DEG_MAX = 45

    /** [spec/113] 横幅 3D 飞入缩放幅度（0~80%，默认 75）：起始缩到 (1-幅度)，落位 1.0。
     *  仅弹出/收回动画期间生效（setAnimationMatrix 网格矩阵）。 */
    const val KEY_NOTIF_FLY_SCALE = "notif_fly_scale"
    const val DEFAULT_NOTIF_FLY_SCALE = 75

    /** [doc/spec/112] 热路径重读 TTL（ms）：沿用 [refractionHeightRatio] 的 500ms 模式 ——
     *  改动 ≤0.5s 生效，TTL 内直接返回 @Volatile 缓存，渲染热路径零 IPC。 */
    private const val NOTIF_TILT_TTL_MS = 500L

    @Volatile
    private var notifTiltEnableCache: Boolean = DEFAULT_NOTIF_TILT_ENABLE

    @Volatile
    private var notifTiltEnableCacheAtMs = 0L

    @Volatile
    private var notifTiltDegCache: Int = DEFAULT_NOTIF_TILT_DEG

    @Volatile
    private var notifTiltDegCacheAtMs = 0L

    /**
     * 通知卡倾斜开关（[TTL][NOTIF_TILT_TTL_MS] 节流缓存，与 [unifiedCornerEnabled] 同款骨架）。
     * 读取失败 / api 不可用 → 默认 false（= 不倾斜，零变化）。
     */
    fun notifTiltEnabled(api: XposedInterface?): Boolean {
        if (api == null) return DEFAULT_NOTIF_TILT_ENABLE
        val now = SystemClock.uptimeMillis()
        if (now - notifTiltEnableCacheAtMs < NOTIF_TILT_TTL_MS) return notifTiltEnableCache
        notifTiltEnableCacheAtMs = now
        val v = try {
            read(api).getBoolean(KEY_NOTIF_TILT_ENABLE, DEFAULT_NOTIF_TILT_ENABLE)
        } catch (t: Throwable) {
            Lg.w(TAG, "prefs read notif_tilt_enable failed, default $DEFAULT_NOTIF_TILT_ENABLE", t)
            DEFAULT_NOTIF_TILT_ENABLE
        }
        notifTiltEnableCache = v
        return v
    }

    /**
     * 通知卡倾斜角度°（[TTL][NOTIF_TILT_TTL_MS] 节流缓存）。返回值恒已 clamp 到 0~[NOTIF_TILT_DEG_MAX]。
     * 用 [readIntCompat]（老 UI 曾把 int 键写成 Float，防 ClassCastException）。
     */
    fun notifTiltDeg(api: XposedInterface?): Int {
        if (api == null) return DEFAULT_NOTIF_TILT_DEG
        val now = SystemClock.uptimeMillis()
        if (now - notifTiltDegCacheAtMs < NOTIF_TILT_TTL_MS) return notifTiltDegCache
        notifTiltDegCacheAtMs = now
        val raw = try {
            readIntCompat(read(api), KEY_NOTIF_TILT_DEG, DEFAULT_NOTIF_TILT_DEG)
        } catch (t: Throwable) {
            Lg.w(TAG, "prefs read notif_tilt_deg failed, default $DEFAULT_NOTIF_TILT_DEG", t)
            DEFAULT_NOTIF_TILT_DEG
        }
        val v = raw.coerceIn(0, NOTIF_TILT_DEG_MAX)
        if (v != raw) Lg.w(TAG, "notif_tilt_deg=$raw out of range, clamp to $v")
        notifTiltDegCache = v
        return v
    }

    @Volatile
    private var notifFlyScaleCache: Int = DEFAULT_NOTIF_FLY_SCALE

    @Volatile
    private var notifFlyScaleCacheAtMs = 0L

    /** 横幅 3D 飞入缩放幅度 %（0~80，TTL 缓存同 notifTiltDeg）。 */
    fun notifFlyScale(api: XposedInterface?): Int {
        if (api == null) return DEFAULT_NOTIF_FLY_SCALE
        val now = SystemClock.uptimeMillis()
        if (now - notifFlyScaleCacheAtMs < NOTIF_TILT_TTL_MS) return notifFlyScaleCache
        notifFlyScaleCacheAtMs = now
        val raw = try {
            readIntCompat(read(api), KEY_NOTIF_FLY_SCALE, DEFAULT_NOTIF_FLY_SCALE)
        } catch (t: Throwable) {
            Lg.w(TAG, "prefs read notif_fly_scale failed, default $DEFAULT_NOTIF_FLY_SCALE", t)
            DEFAULT_NOTIF_FLY_SCALE
        }
        val v = raw.coerceIn(0, 80)
        notifFlyScaleCache = v
        return v
    }

    // ---------- 抓屏模式 / 重试开关 / 区域抓屏（hook 端读取，TTL 缓存） ----------

    /** 抓屏参数热路径重读 TTL（ms）：同 [UNIFIED_CORNER_TTL_MS] 模式，TTL 内零 IPC。 */
    private const val CAPTURE_PARAM_TTL_MS = 500L

    @Volatile
    private var captureModeCache: Int = DEFAULT_BG_CAPTURE_MODE

    @Volatile
    private var captureModeCacheAtMs = 0L

    /**
     * 抓屏模式：[BG_CAPTURE_MODE_IMMEDIATE] 即时 / [BG_CAPTURE_MODE_ON_CHANGE] 变化 / [BG_CAPTURE_MODE_STATIC] 静态。
     * [TTL][CAPTURE_PARAM_TTL_MS] 节流缓存（hook 与 Launcher 进程各一份）。读失败 / 越界 → 回默认 1（变化）。
     */
    fun captureMode(api: XposedInterface?): Int {
        if (api == null) return DEFAULT_BG_CAPTURE_MODE
        val now = SystemClock.uptimeMillis()
        if (now - captureModeCacheAtMs < CAPTURE_PARAM_TTL_MS) return captureModeCache
        captureModeCacheAtMs = now
        val raw = try {
            readIntCompat(read(api), KEY_BG_CAPTURE_MODE, DEFAULT_BG_CAPTURE_MODE)
        } catch (t: Throwable) {
            Lg.w(TAG, "prefs read bg_capture_mode failed, default $DEFAULT_BG_CAPTURE_MODE", t)
            DEFAULT_BG_CAPTURE_MODE
        }
        val v = when (raw) {
            BG_CAPTURE_MODE_IMMEDIATE, BG_CAPTURE_MODE_STATIC -> raw
            else -> BG_CAPTURE_MODE_ON_CHANGE
        }
        captureModeCache = v
        return v
    }

    @Volatile
    private var captureRetryEnabledCache: Boolean = DEFAULT_BG_CAPTURE_RETRY_ENABLED

    @Volatile
    private var captureRetryEnabledCacheAtMs = 0L

    /** 抓屏失败重试总开关（固定 320ms × 3 次，仅受本开关门控）。[TTL][CAPTURE_PARAM_TTL_MS] 缓存。 */
    fun captureRetryEnabled(api: XposedInterface?): Boolean {
        if (api == null) return DEFAULT_BG_CAPTURE_RETRY_ENABLED
        val now = SystemClock.uptimeMillis()
        if (now - captureRetryEnabledCacheAtMs < CAPTURE_PARAM_TTL_MS) return captureRetryEnabledCache
        captureRetryEnabledCacheAtMs = now
        val v = try {
            read(api).getBoolean(KEY_BG_CAPTURE_RETRY_ENABLED, DEFAULT_BG_CAPTURE_RETRY_ENABLED)
        } catch (t: Throwable) {
            Lg.w(TAG, "prefs read bg_capture_retry_enabled failed, default $DEFAULT_BG_CAPTURE_RETRY_ENABLED", t)
            DEFAULT_BG_CAPTURE_RETRY_ENABLED
        }
        captureRetryEnabledCache = v
        return v
    }

    @Volatile
    private var regionCaptureExperimentalCache: Boolean = DEFAULT_BG_REGION_CAPTURE_EXPERIMENTAL

    @Volatile
    private var regionCaptureExperimentalCacheAtMs = 0L

    /** 区域级抓屏实验开关。[TTL][CAPTURE_PARAM_TTL_MS] 缓存。 */
    fun regionCaptureExperimental(api: XposedInterface?): Boolean {
        if (api == null) return DEFAULT_BG_REGION_CAPTURE_EXPERIMENTAL
        val now = SystemClock.uptimeMillis()
        if (now - regionCaptureExperimentalCacheAtMs < CAPTURE_PARAM_TTL_MS) return regionCaptureExperimentalCache
        regionCaptureExperimentalCacheAtMs = now
        val v = try {
            read(api).getBoolean(KEY_BG_REGION_CAPTURE_EXPERIMENTAL, DEFAULT_BG_REGION_CAPTURE_EXPERIMENTAL)
        } catch (t: Throwable) {
            Lg.w(TAG, "prefs read bg_region_capture_experimental failed, default $DEFAULT_BG_REGION_CAPTURE_EXPERIMENTAL", t)
            DEFAULT_BG_REGION_CAPTURE_EXPERIMENTAL
        }
        regionCaptureExperimentalCache = v
        return v
    }

    @Volatile
    private var panelCaptureHzCache: Int = DEFAULT_PANEL_CAPTURE_HZ

    @Volatile
    private var panelCaptureHzCacheAtMs = 0L

    /**
     * 即时抓屏速率 Hz（clamp [PANEL_CAPTURE_HZ_MIN]~[PANEL_CAPTURE_HZ_MAX]，存量越界值读取时夹紧）。
     * [TTL][CAPTURE_PARAM_TTL_MS] 缓存。
     */
    fun panelCaptureHz(api: XposedInterface?): Int {
        if (api == null) return DEFAULT_PANEL_CAPTURE_HZ
        val now = SystemClock.uptimeMillis()
        if (now - panelCaptureHzCacheAtMs < CAPTURE_PARAM_TTL_MS) return panelCaptureHzCache
        panelCaptureHzCacheAtMs = now
        val raw = try {
            readIntCompat(read(api), KEY_PANEL_CAPTURE_HZ, DEFAULT_PANEL_CAPTURE_HZ)
        } catch (t: Throwable) {
            Lg.w(TAG, "prefs read panel_capture_hz failed, default $DEFAULT_PANEL_CAPTURE_HZ", t)
            DEFAULT_PANEL_CAPTURE_HZ
        }
        val v = raw.coerceIn(PANEL_CAPTURE_HZ_MIN, PANEL_CAPTURE_HZ_MAX)
        panelCaptureHzCache = v
        return v
    }

    // ---------- 读写（模块 UI 进程，SettingsActivity） ----------
    /** 框架侧存储（与 hook 端 getRemotePreferences 同一份数据，可写）。 */
    fun remote(service: XposedService): SharedPreferences = service.getRemotePreferences(PREFS_NAME)

    /**
     * 本地 XML 存储兜底（仅 UI 回显/降级用，hook 端读不到）。
     * 正常路径应写 [remote]，仅在 [App.serviceRef] 不可用时降级本方法。
     */
    fun read(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun write(context: Context): SharedPreferences.Editor = read(context).edit()
}
