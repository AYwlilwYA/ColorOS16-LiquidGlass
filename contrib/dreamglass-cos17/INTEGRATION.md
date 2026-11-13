# ColorOS 17 接入位置

来源固定为 DreamGlass `75c96f36fddccef80d8c7a99c8839201dd125a27`。本说明用于把方案接入上游当前管线，并非已经生效的补丁。

## 锁屏

在 `com.android.systemui` 实际类加载器就绪后安装 `KeyguardGlassHook.install(api, classLoader)`。由上游的显式开关决定启用，不同时启用旧的 `LockScreenClockHook`；旧实现仍可原样保留供其他版本使用。

`keyguard/` 包含运行时插件发现、字形绘制、原生 RenderEffect 合成及底部按钮图层处理。`shared/RuntimeWorkPolicy.kt` 和 `HardwareBackdropBlur.kt` 用于后台 GPU 壁纸准备；`shared/LockWallpaperBackdrop.kt`／`LockWallpaperGeometry.kt` 是通知与控制中心在锁屏场景下的独立壁纸输入，不受时钟会员开关支配。

接入需将 `Prefs.memberAllowed(api)` 替换为上游自己的功能开关，把 RuntimeDiagnostics 映射为现有日志或可选诊断回调。不是把会员逻辑拷入上游。明确验证主题插件自绘字形、日期、快捷按钮，以及 AOD／密码页不被接管。

## 展开流体云与轻打扰横幅

源码摘录中的成员保持原始内容。按以下次序接入，避免只移植着色器却没有背景或新帧：

1. **真实挂载。** `mountCardBackgroundGlass`、`mountCardBackgroundAttachHook` 识别 `com.oplus.systemui.plugins.seedling.card.ui.view.CardBackgroundView`；先执行原生 attach，再补挂，零尺寸时等待有效 layout。使用其实际背景 Drawable／绘制出口，范围限制在目标宿主。
2. **可逆背景。** `expandedCloudBackground`／`ExpandedCloudGlassDrawable` 连接 `BoundedGlassDrawable`。成功画出玻璃前保留原生背景；成功后再归零原生背景 alpha，以停止其单独注册到合成器的模糊区域。不能只跳过 native draw，也不能未拿到新帧就提前变成透明或黑底。
3. **显示代次。** 每次新展开增加捕获代次，写入 `cloudRequiredCaptureEpoch`／`bannerRequiredCaptureEpoch`；捕获完成时给该 bitmap 发布代次。`OverlayFrameEpochs.select` 必须筛掉旧区域帧，再选择合格的全屏帧；`requestScreenSnapshotAsync` 对过期图发出新请求。
4. **生命周期。** `VisibleCaptureSession` 由宿主身份与代次维持所有权。旧任务退出时不能清除替换宿主的状态。收起、detach、锁屏及省电立即终止可见期捕获需求。
5. **GPU 源。** `drawSeedlingGlassOnCanvas` 在生产 AGSL 绘制前调用 `bindQualityBackdrop`。GPU 预模糊输出须同步缩放 sourceRect，并避免着色器再次进行宽范围模糊。`gpu/LiquidGlassShader.kt` 展示下游 Params 及输入协议，保留上游自己的 SF／CaptureEngine 路由。
6. **回调保护。** `BoundedGlassDrawable` 不只保护 draw；还隔离原生 `setColorFilter(null)`、getOutline、状态和可见性等回调异常，转发无效化并保留原生 bounds、padding、逻辑 alpha。关闭或缺源恢复原生层；禁止异常递归调用。
7. **轻打扰。** `mountSimpleBannerGlass` 和 attach 片段使用同样的代次、背景与 GPU 准备协议。它们展示共享实现，不要求本次贡献接管普通通知的其他分支。

胶囊收起部分参看 `renderSeedlingCardGlass`：保持原生内容与黑底，展开后才折射背后画面。

## 调度约束

- 统一串行抓屏队列，只有一次 admission；禁止主线程／渲染线程同步抓屏。
- 新显示事件可唤醒空闲等待；160 ms 短窗口内最短 33 ms，结束后恢复原有预算：变化模式至少 100 ms、静态背景退至 167 ms，低频设置不被覆盖。持续像素变化不延长短窗口。
- GPU 准备共享两个初始额度，之后每 50 ms 一个额度；每个材质只保留最新待处理输入，优先填补缺图材质。
- 避免复活下游历史 CPU 模糊；`ButtonBackdropBlur` 中保留的 CPU 方法为历史测试参考，当前 `ButtonBackdropWorker.compute` 只调用硬件路径。上游已有新的 GPU 模糊可以继续使用，不必整份替换。
- 普通页面切换且无玻璃消费者时不抓屏；锁屏／AOD 从锁屏壁纸通道取输入，不复用解锁前的显示截图。壁纸源缺失时回退原生而非取旧帧。

## 对接表

| 下游引用 | 上游需要提供／映射 |
| --- | --- |
| `Prefs.memberAllowed`、`seedlingRuntimeAllowed` | 上游显式功能开关，不引入会员限制 |
| `RuntimeDiagnostics` | 可选日志／诊断回调 |
| `LiquidGlassShader.Params.sourcePreblurred` | 预模糊纹理协议，防二次大范围采样 |
| `LiquidGlassShader.Params.surfacePolish` | 上游材质参数的等效映射，不能直接复制不存在的字段 |
| `ElementBackground`／`RenderSource`、`screenRegionMap` | 上游对应的截图几何与宿主登记，保证逻辑屏幕与纹理坐标对应 |
| 私有截图／Surface 方法、排除规则 | 接上游当前 CaptureEngine／CapturePolicy；保留排除自身窗口、源代次与可见需求判断 |
| `KEY_SEEDLING_CARD_GLASS_ENABLE` 等配置 | 上游已有流体云开关及参数 |

## 建议验收

先确认挂载入口和几何，再测普通展开／收起、快速二次展开、跨应用后展开、锁屏和 AOD、横幅到达、缺源回退、关闭开关与进场透明度。验证旧原生模糊区域没有在玻璃后残留，尤其不支持颜色滤镜的 Drawable。最后再测功耗和后台停工。
