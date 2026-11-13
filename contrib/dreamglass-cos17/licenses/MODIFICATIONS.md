# DreamGlass 2.0.1-debug8 — 修改声明

修改者：DreamGlass contributors；维护者：V0idream。修改日期：2026-10-22 至 2026-11-11。

本作品基于 AYwlilwYA / ColorOS16-LiquidGlass，原始基础 `aa96e1aeb98f4e3777f4bced265a1b86bf3e10cd`，
已合并至 `1bb018615af3261d3e9de0165169a0e75917d2fe`。整体按 GNU GPLv3 分发，原作者与第三方版权、
许可均保留。此次合并保留双方提交历史，兼容取舍见 docs/UPSTREAM_SYNC_0.3.md。

## 主要修改

- 2026-11-11，2.0.1-debug8：合并串行抓屏的重复限速；展开时 160 ms 内采用最短 33 ms 的短暂更新窗口，并可唤醒空闲等待任务，随后恢复既有频率；GPU 背景共享两个初始额度与每 50 ms 一个的持续预算，优先准备缺图材质；新通知清除场景像素时复用 GPU 渲染器。343 项测试、构建／Release Lint、27 项原有 GPU 契约与新增最新帧／场景清理／隐藏停工检查通过。无新增依赖或厂商文件；用户反馈试用良好，未测量实体设备延迟与功耗。

- 2026-11-10，2.0.1-debug7：修正展开场景缓存优先级、过期帧请求和挂载时序；流体云与轻打扰横幅接入共用 GPU 背景准备，成功绘制后抑制原生模糊区域、失败恢复；保护共用背景包装器的原生生命周期回调及重入。338 项测试、构建／Release Lint、27 项本地 Android GPU 契约通过。无新增依赖或厂商文件，未操作手机或完成真机通知崩溃验收。

- 2026-11-10，2.0.1-debug6：修复流体云原生背景回退无条件设置颜色滤镜的崩溃路径，并隔离原生绘制异常；保留 GPU 玻璃与原生首帧回退。334 项测试、构建／Release Lint、17 项隔离 Android GPU 回退契约通过；无新增依赖，未包含厂商文件，未操作实体设备或宣称真机验收。

- 2026-11-10，2.0.1-debug5：展开流体云与轻打扰横幅替换实际背景出口，避免叠加深色原生模糊；顶部通知替换原生选中背景，保留原生分支、动画裁剪、内容及回调。新展开场景要求新帧，保留其他消费者有效缓存；修复原生回退透明度重复叠加及背景回调转发。无新增依赖或厂商文件；本地构建、334 项单元测试与 Release Lint 通过，真机及功耗未验收。

- 2026-11-10，2.0.1-debug4：补齐 COS17 新通知在主题 Non 材质及延后挂载时的玻璃入口，保持失败时原生绘制；按可见需求抓屏并在隐藏／锁屏／熄屏取消，锁屏采用独立壁纸来源与 GPU 准备；修正保存面板原生背景绘制和 overlay、清理按钮原生屏蔽期及混合常驻通知删除提交；降低独立音量面板原生模糊上限至 16 px；会员确认改为跨版本保存且按功能触发。无新增依赖，不包含厂商 APK、反编译源码、用户截图或日志。329 项单元测试及构建／Lint 通过，厂商真机视觉与功耗未验收。

- 2026-11-04，2.0.1-debug1：相册保存面板的实际子控件前绘制、状态背景租用与宽类型原生节点读取；清理按钮入场准备与共同边缘；混合常驻通知的集合级提交粒子。保留原生保存、常驻通知与清理回调，背景刷新更改按要求撤回至 2.0。275 项回归与 15 项隔离 GPU 断言通过，无新增依赖、无实体设备操作或验收。

- 2026-11-03，2.0：以 debug5 当前实现作正式 Release 发行，更新版本与发布说明，保留原生签名升级与完整许可／源码提供方式。没有新增依赖，没有把本地检查当作实体设备验收；已知视觉与时序问题继续列明。

- 2026-11-03，1.1.9-debug5：清理按钮使用自身 Canvas 在文字前直接绘制共用玻璃和轻底色，缺少或透明输入保持可见，不改原生 Paint 与点击／任务回调；取消以 native 录制成功决定底层可见性的假设。相册保存弹窗关联明确的 Activity 来源，以真实屏幕原点处理跨窗口矩阵。265 项 JVM 与 11 项隔离 GPU 核心断言通过，无实体设备验收，无新增依赖。

- 2026-11-03，1.1.9-debug4：清理按钮保留原生 LayerBlurDrawable 取样和录制生命周期，临时着色后恢复原生 Paint；相册底栏引用真实兄弟内容节点，保存大面板引用独立 Activity 内容，避免祖先／子孙循环并统一局部坐标。按现有帧更新，无截图、CPU 模糊或持续刷新定时器。265 项回归与隔离 Android GPU 核心检查通过，厂商源发现和真机效果未验收。无新增依赖。

- 2026-11-03，1.1.9-debug3：尝试修正原生对话框透明背景与overlay、粒子监听器迭代期间移除、旧卡片采样坐标；新增小布建议真实圆角和COS17清理按钮适配。当前仍有清理按钮背景消失、相册底栏和保存面板问题；无完成真机验收结论，无新增依赖。

- 2026-11-03，1.1.9-debug2：保留玻璃功能，修正 COUIButton 转发背景的循环图层风险、相册固定蒙版与原生局部坐标，补齐单图工具条；负一屏顶部配对模糊层按原生 Path 裁剪，小布建议按其专用背景识别。清空后台补上堆叠逐卡辅助函数并保留原生任务过滤、GPU 粒子上限与回调。新增无环图、几何和动画契约回归及程序背景的桌面 AGSL 栅格对照；未执行 ADB 或厂商真机验收。无新增第三方依赖。

- 2026-11-03，1.1.9-debug1：移除计算器所有功能适配和 COS17 无效旧功能；重组 UI，将倾斜归入会员。诚信确认按版本名／版本号绑定，集中限制配置与实际渲染，配置导入不能授权；不执行真实支付验证。新增按实际 COS17 类、资源和原生背景通道识别的桌面大文件夹、负一屏搜索／服务卡片与相册多个场景，保留前景和输入。背景模糊移除 CPU 回退，继续 GPU 渲染；无 ADB／真机视觉验收。

- 2026-11-02，1.1.8-debug4 候选：依据实际 COS17 APK 重新区分计算器 SVG 前景与填充、相册 wrapper／COUI 独立材质、负一屏真实 Path 底色宿主；使用正常 setter 释放不透明背景缓存并在关闭／异常时恢复原生。按本动画实际图标端点确定窗口倾斜方向，保留原生轨迹。本轮未连接 ADB，实际厂商合成效果待验证。

- 2026-11-02，1.1.8-debug3 候选：按当前计算器 17.2.16、相册 17.10.41、负一屏 17.14.60 的实际接口接入共用 GPU 材质，识别控件与原生背景，在挂载后局部抑制不透明填充；通知菜单补齐 Drawable 边界、padding、alpha 和缺源回退。借鉴上游 AppPageGlassMounter 的挂载后抑制思路，独立实现 COS17 契约；真机视觉待验收，详见 `docs/APPS_AND_MENU_COS17_1.1.8.md`。

- 2026-11-02，1.1.8-debug 候选：继续修复窗口倾斜，改用当前原生仿射矩阵合成的平行投影兼容模式，不恢复有问题的独立 4×4 事务与定时复位；设备可见效果尚未验收。
- 2026-11-02，参考上游 `468b40c` 的渲染区域调试思路和青色约定，在共用 GPU 材质内增加实际渲染边界开关；适配方式及范围见 `docs/UPSTREAM_DEBUG_BOUNDS.md`。未复制系统 APK、用户截图、日志或厂商代码进入本源码。

- 1.1.2场景补齐：锁屏从原生时钟/组件插件返回值发现实际View，支持自绘字形组；原生壁纸传递与控制器缓存通过GPU录制取得稳定输入，不再只依赖壁纸文件权限。锁屏组件仅处理背景，保留前景。
- 删除相册/负一屏工具栏的额外装饰外框，第三方背景通道使用同一LiquidGlassShader、全局/IOS风/组件覆盖；智能侧边栏共用该GPU通道，缺少OEM能力时保留原生而非画假描边。

- 1.1.2：原厂 COS17 窗口矩阵及通知行 RenderNode 倾斜兼容；通知删除粒子的手势准备/请求/确认/取消分离，兼容清空全部与重排的 View 参数。新增实现不移植修改版 Launcher APK，不包含系统包或用户视频。

- 独立产品名称、标记、应用ID与深色青绿UI：四页导航、材质工作室、分组参数卡与离线来源说明；
  替换旧联系/赞助/升级入口。源码明确披露派生关系，不将整个项目宣称为从零开发。
- COS17 初始化时机、内容绘制入口、抓屏坐标、滑杆轮廓、横向图标底层、通知/按钮玻璃与音量场景隔离。
- 有限厚度边缘透镜、方向性高光、IOS风可逆参数层、按局部区域文字/中性图标黑白适配。
- 高密度密集CPU浮点滤波与F16输出（1.0.6）；正反来源绑定修复（1.0.7）。
  1.1.0新增默认RenderEffect/HardwareRenderer硬件输出，可选精度模式，重用多样式缓存。
- 后台首帧抓屏、最新请求合并、异常隔离/退避、取消代次、内存压力清理与熄屏/隐藏停工；
  按实际背景变化而非只看手势调速，更密的批量行指纹，按需预览与诊断。
- 单文件GPU通知删除粒子、独立开关/速度、硬件纹理快照、有界任务和overlay清理。
- 保留需要认证的无Root状态/类清单回传，升级为五个宿主；日志采集转为用户显式启动logcat。
- 三个应用保留各自原生背景并用按类边缘装饰接入，不复活生产相册测试探针。
- 隐藏桌面入口、配置备份及用户确认的应用内Root重启；不自动申请Root或重启手机进程。
- 两份LibXposed AAR仅修改minCompileSdk元数据37→36；保留原组件版本、源码和版权附件。

本版独立修改与上游原始实现可通过公开Git提交与合并父提交逐文件比对。历史修改见CHANGELOG.md。
部分光学算法来自 Copyright 2025 Kyant / AndroidLiquidGlass（Apache-2.0），原许可头保留。
Android Developers/AOSP硬件位图示例顺序用于HardwareBackdropBlur和ParticleDissolveHook，已补来源与修改日期。
参考视频、手机截图、用户配置、签名私钥和本机构建路径不属于发行源码。

## 1.1.1 锁屏与同步增量

新增GPU字形遮罩、可逆背景替换与静态锁屏壁纸提供器，覆盖时钟/日期、组件卡片和底部快捷按钮；
保留系统原字体/子视图/点击以及深度遮挡所在的原视图层级。未复制主题插件或用户截图素材。
实际RenderEffect快照/坐标、开关恢复与GPU像素响应均纳入合成场景测试；厂商主题命中仍需真机。
最新同步 a8d3f84、4360a02、5beac91，包括负一屏。详见 docs/KEYGUARD_GLASS.md。


## 2026-11-01 — classic optical response

DreamGlass restores its v1.0.7 refraction band, circleMap displacement and rim/volume finish as the default shared AGSL material. Current GPU/F16 backdrop handling, linear sampling, lifecycle guards and compatibility routes are retained; existing saved parameter values are not reset. Synthetic GPU comparisons against the v1.0.7 reference cover default and IOS profiles across 24 geometry/variant cases. No new external dependency was added.


## 2026-11-01 — selectable optics and COS17 media geometry

Preserved the v1.1.4 compact material behind a global opt-in switch; classic v1.0.7 remains the default. Shared shader callers and the preview receive the same selection without resetting saved parameters. Media card clipping no longer changes the optical viewport or interprets scroll offsets as symmetric padding. Added bounded final-transaction tilt submission with merge/close handling and a visible-phase notification envelope. GPU regression assets are synthetic; proprietary binaries, private device captures and signing keys are excluded. No new external dependency was added.


## 2026-11-01 — 1.1.6 readability, media and honor-support UI

Added opt-in GPU neutral backing and a shade slider, consistent explicit dim priority and tint-aware foreground decisions. Added conditional media-decoration suppression, preserved both supplied launcher callback contracts and revised notification pivot/camera distance. Collapsed native capsules use black while expanded cards retain their renderer. Added a local-only honor support page and member grouping; no payment SDK, network verification or new dependencies. The appreciation image was supplied by the project owner for inclusion; the original PNG is preserved and saving does not rewrite it. Private phone captures and proprietary APKs remain outside source distribution.


## 2026-11-02 — 1.1.7 native animation regression hotfix

Quarantined all module window-tilt installers after reports of disappearing native app launch animation. Removed the COS17 transaction hooks, 4x4 writes and asynchronous identity reset from the compatibility installer. Existing enabled preferences cannot reactivate this path; the UI reports the unavailable capability. The pure projection helpers remain diagnostic math only. No shader, payment, notification-tilt or component behavior was changed. No new dependency was added.
