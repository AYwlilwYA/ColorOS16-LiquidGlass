# ColorOS 17 锁屏玻璃源码参考

这七个 `.kt` 文件直接复制自 DreamGlass `75c96f36fddccef80d8c7a99c8839201dd125a27`（2.0.1-debug8）的锁屏实现，保留原包名与原始实现，便于逐项复核。它们位于贡献目录，不属于当前上游 app 的编译源集，也没有替换上游 `LockScreenClockHook`。这不是已经完成的上游运行时集成。

## 文件与职责

- `KeyguardGlassHook.kt`：实际插件视图发现、时钟/组件/快捷按钮角色绑定、壁纸代次、可见性/亮屏/省电准入与可逆恢复。
- `KeyguardGlassPolicy.kt`：角色筛选及壁纸到屏幕的几何映射。
- `KeyguardGlassMaterial.kt`：GPU 字形遮罩、边缘折射与玻璃着色器；保留系统实际字体和字形。
- `KeyguardGlassAttachment.kt`：合成已有 RenderEffect、背景或 ImageView 图层，保留并恢复系统状态。
- `KeyguardGlyphDraw.kt`：主题自绘时钟的独立 RenderNode 绘制出口，失败保留本帧原生字形。
- `KeyguardNativeMounts.kt`：主题包装后的最终父层效果入口，避免只修改被遮盖的子节点。
- `KeyguardLayeredShortcut.kt`：原生相机/手电等按钮合成层，在不替换原生点击行为及图标的前提下加入玻璃背景。

## 依赖闭包

共享实现需随本方案查看：`RuntimeWorkPolicy.kt`、`HardwareBackdropBlur.kt`、`LockWallpaperBackdrop.kt`。后者独立于可选时钟效果，接受原生锁屏壁纸，并为通知/控制中心等锁屏表面提供自己的 GPU 壁纸源；不要将普通应用抓屏缓存当作锁屏源。

宿主接口：LibXposed API 102、Android 13/API 33 的 RuntimeShader / RenderNode / RenderEffect、上游已有 `config.Prefs` 与 `log.Lg`。源码引用的 `RuntimeDiagnostics.mark/update` 仅上报诊断事件，接入时可绑定上游日志，不能为其引入常驻屏幕抓取。

`Prefs` 所需接口为 `read(api)`、`readIntCompat(...)` 及 `KEY_MASTER`、`KEY_GLASS_ENABLE`、`KEY_LOCKSCREEN_CLOCK_BLUR`。另有 DreamGlass 自身的 `Prefs.memberAllowed(api)`：这是下游应用的设置权限策略，**不是提供给上游的收费要求**；接入时替换为上游自己的功能准入策略。无需复制 DreamGlass 的会员页、赞赏码、品牌或支付逻辑。

调试构建还会通过反射尝试调用 `com.dreamglass.cos17.KeyguardGlyphAudit`。该调用可在集成时去掉；缺少类时已有异常保护，不是生产效果依赖，不能把它描述为上游已经获得的调试功能。

## 接入位置与配置

在目标 `com.android.systemui` 进程的类加载器可用后，且确认采用 ColorOS 17/API 33+ 路径时，调用 `KeyguardGlassHook.install(api, loader)`。先审查并处理上游现有锁屏 Hook 的相同对象，不能在未经核对的情况下同时启用两个竞争的效果替换器。安装发现入口不等于启用效果：源码主开关默认关闭。

| 配置项 | 含义 | 默认值 |
| --- | --- | --- |
| `keyguard_glass_enable` | 锁屏玻璃总开关 | false |
| `keyguard_glass_text` | 时钟/日期等字形 | true |
| `keyguard_glass_cards` | 锁屏组件卡片 | true |
| `keyguard_glass_shortcuts` | 锁屏快捷按钮 | true |
| `keyguard_glass_strength` | 材质强度，整数百分比 | 100，限制 0–200 |
| `lockscreen_clock_glass_blur` | 壁纸 GPU 模糊半径 | 6，限制 0–24 |

源码不替换字体，不重绘一套数字，不修改按钮点击；源不可用时保留原生外观。锁屏源优先使用原生壁纸回调/控制器，其次在具备系统内部权限时读取当前用户 `FLAG_LOCK`；不使用锁屏前应用画面。AOD、熄屏、隐藏和省电状态不新增抓屏或持续计算。

## 验证边界

本次贡献只提供已有 ColorOS 17 方案。下游调试目标为 SystemUI 17.99.02，主题插件和设备实现可能不同。复制完整源码不等于在最新上游或 ColorOS 16 上做过真机验收。本目录未包含厂商 APK、反编译系统组件、用户截图、私人日志或签名材料。
