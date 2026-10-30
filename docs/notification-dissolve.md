# 删除通知时的 GPU 粒子飘散

基于上游 `64148b8`，保留作者当前的 4/8/16 多尺度 GPU 模糊、版本号、其他开关和原始删除流程。
**本改动不引入 CPU 模糊或之前的 dense-snapshot-blur worker。LiquidGlassShader.kt 无修改。**

## 接入与配置

生产效果全部位于 `hook/ParticleDissolveHook.kt`。主 Hook 仅增加一行：

```kotlin
ParticleDissolveHook.install(api, classLoader)
```

「倾斜与透视」新增独立的「删除通知时粒子飘散」，默认关闭；速度 0.35×–3×，默认 1×，越高越快。
偏好键 `notification_dissolve_enabled` / `notification_dissolve_speed_percent`，走原框架配置读写与备份。
Android 13 以下该选项禁用。关闭系统动画或省电模式不播放粒子。

## 运行边界

- 按真实参数类型匹配 SwipeHelper、NotificationSwipeHelper 与 COS17 控制器的具体方法，跳过抽象 snap 回调，单个入口失败不会终止其余安装。PREPARE/REQUEST 不代表删除；ACCEPT/COMMIT 才接管视觉，ABORT 终止，普通 snap 不掐断已确认的效果。
- 从原生当前滑动位置和方向连续切入粒子，按 getActualHeight() 裁剪短卡片。GPU overlay 真正开始后才暂时隐藏原行，结束只恢复自身仍持有的 alpha，不改布局、visibility 或点击。
- 原生删除路径与回调仍执行，不替换通知服务调用。已确认的 COS17 一键清除完成 callback 在请求收起且有该窗口的效果时，等待剩余消散时间的一部分（最多 900ms）再执行原收起分支；不保存 Xposed Chain。手动收起/detach 取消过期任务，禁用立即释放。未识别的 ROM callback 保留原生收起，不猜测接口。
- 按需运行单线程、有界排队；至多三个短时图像与三个活动效果，单图最多 768×384。一次 HardwareRenderer 快照，没有持续抓屏、CPU 像素读取、CPU 模糊或逐粒子 View。
- AGSL 负责漂移、随机微粒、颜色采样、消散；CPU 每帧只更新进度 uniform。绘制上限约 30fps。
  速度同时改变运动与消散的时间尺度，单次约 0.37–3.14 秒。
- snapChild 取消、根窗口离开、完成、熄屏/省电和禁用系统动画会停止；硬件失败跳过效果并退避 30 秒。
  不使用 CPU 模糊回退，原系统行为继续。已交给 shader 的图像不主动 recycle。

## 验证

见 `docs/validation/notification-dissolve/`。合成卡片用于像素/清理回归，参考视频不随源码分发。
普通 Android 模拟器可以证明 AGSL 编译、硬件纹理与 overlay 生命周期，不能证明厂商 SwipeHelper
在 ColorOS16/17 中仍使用相同入口；真实滑删、清空、分组通知、其他插件共存和耗电仍需真机验收。
上游现有 Lint 问题会与未改动的相同提交比较，不通过关闭 Lint 掩盖。

## 2026-10-30 兼容修订

已合入作者最新 main，保留其现有 GPU 多尺度模糊、LowResTier 与着色器；没有引入 DreamGlass 的 CPU/F16 滤波 worker、品牌、独立 UI、实验性锁屏或侧边栏代码。生产效果仍为单文件、一行接入。

DreamGlass 的 COS17 实机记录确认 REQUEST/ACCEPT/COMMIT、硬件纹理和一键清除等待路径执行；测试图片与设备日志不随 PR 分发。这里的原包为 COS16，尚无 COS16 实机验证，一键清除的专用完成回调只在签名和字段布局匹配时使用。不得把 COS17 日志当作 COS16 真机验收或功耗证明。

构建验证：基于 64148b8 的 Debug/Release 构建及 11 项单元回归通过。完整 Lint 与未修改的相同基线均为 177 Error，新增 Error 为 0；PR 为 240 Warning，基线为 233，未屏蔽。记录见 docs/validation/notification-dissolve/compatibility-20261030.json。
