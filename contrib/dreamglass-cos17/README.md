# DreamGlass 的 ColorOS 17 锁屏／流体云玻璃方案

提供 DreamGlass 已发布版本的实现，供上游审查、复用和接入。**本目录是参考源码贡献，不是已接入上游生产 Hook 的新功能。** 没有改动上游应用、设置、抓屏引擎或 SF 模式，也没有进行 ColorOS 16 移植。

- 来源：[DreamGlass 75c96f3](https://github.com/V0idream/DreamGlass/tree/75c96f36fddccef80d8c7a99c8839201dd125a27)，对应 [2.0.1-debug8](https://github.com/V0idream/DreamGlass/releases/tag/v2.0.1-debug8)。用户反馈该候选试用良好。
- 目标上游：`AYwlilwYA/What-Color-OS`，基于 `38bd6c5` 整理；原 `ColorOS16-LiquidGlass` 地址为仓库改名后的重定向。
- 方案来源设备：ColorOS 17、SystemUI 17.99.02；Android 13+ 的 RuntimeShader／RenderEffect 路径。未宣称覆盖所有主题、动态壁纸或不同 ROM，也没有新增真机验收。

## 文件导航

| 目录 | 内容 |
| --- | --- |
| `keyguard/` | 完整锁屏发现、字形、相机／手电和卡片材质源码，另见 `keyguard/KEYGUARD.md` |
| `cloud/BlurDrawHook.excerpts.kt.txt` | 38 个原始成员的精确源码摘录：展开挂载、原生背景包装、可见期更新、GPU 输入、场景与帧代次选择 |
| `cloud/excerpts-index.json` | 摘录在原始提交中的文件、行号和 SHA-256 |
| `shared/` | 原生 Drawable 保护、独立锁屏壁纸、GPU 准备、场景代次与有界调度辅助实现 |
| `gpu/` | 下游着色器的完整参考版本；不是对上游当前着色器的替换 |
| `tests/`、`android-probes/` | 对应纯逻辑测试和 Android GPU 验证源文件；不注册到上游应用 |
| `validation/` | 原候选的验证记录；记录内 `uploaded=false` 是采集时的发布前状态 |
| `licenses/` | 来源版本的许可、版权和修改声明 |

接入重点见 [INTEGRATION.md](INTEGRATION.md)。`source-manifest.json` 固定来源和文件摘要；在本目录运行 `python verify.py` 校验贡献包，追加 `--source-tree <DreamGlass checkout>` 可对照原提交核验源码与摘录。

## 需要一起保留的行为

1. 锁屏使用当前用户的锁屏壁纸输入，优先原生壁纸控制器、再尝试 `FLAG_LOCK`；不把锁屏前应用截图或息屏显示画面当成壁纸。拿不到可用输入时恢复原生外观，不填充纯黑来伪装成功。
2. 时钟通过实际插件视图和字形绘制出口处理，保留原生布局、字体、点击、过渡及父层遮挡。PIN、密码、指纹和 AOD 不是本方案处理对象。
3. 展开云卡在真实背景出口应用材质，并在原生 attach 完成、布局有效后挂载。单纯在空 `onDraw` 中增加一层不能保证覆盖原生背景。
4. 新展开场景需要新帧；旧区域缓存不能遮住更新的全屏帧。GPU 材质真正绘制成功后才抑制原生独立模糊区域，缺源、关闭或失败时恢复原生背景。
5. `BackgroundBlurDrawable` 的颜色滤镜、状态、轮廓、透明度等非 draw 回调同样需要保护，且不能破坏进场动画或重入状态。
6. 收起云胶囊保持黑底；展开才使用玻璃。只有可见消费者需要时才更新，隐藏、锁屏、静态模式或省电时停止显示抓屏；模糊交给 GPU，不增加普通页面切换监控。

## 接入和验证边界

上游已新增 CaptureEngine／CapturePolicy 和 SF 路径，直接覆盖下游整个 `BlurDrawHook` 会丢失这些更新。摘录保留原始私有成员及依赖以便审查，**不能单独编译或直接替换整个上游文件**。完整上下文始终可从固定来源提交查看。

DreamGlass 的会员判断、诊断和预设属于下游产品策略，不是贡献的使用条件；接入时映射到上游自己的开关和日志，勿引入会员页面或付款门槛。下游 Shader Params 的 `sourcePreblurred`、`surfacePolish` 也不能直接假设存在于上游。

来源版本通过 343 项单元测试、Release lint、27 项 Android GPU 背景契约及 GPU 最新帧／隐藏停工检查。它们证明来源版本的本地回归，不代表本目录已经接入或验证了上游应用。未测量实体设备功耗或延迟，也未添加厂商 APK、反编译源码、用户截图或支付图片。

## 来源和许可

原始上游作者 AYwlilwYA、DreamGlass contributors 和相应第三方的版权与许可均保留。组合实现按 GPLv3 条件提供；Kyant 着色器的 Apache-2.0 原始声明保留，GPU 迁移示例的 Android Developers／AOSP 来源与 Apache-2.0 声明保留。详见各源码文件头、上游根目录 LICENSE，以及本目录 `licenses/`。本贡献无新增库依赖。
