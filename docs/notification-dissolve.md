# 删除通知时的 GPU 粒子飘散

基于上游 `10d40ce`，保留作者当前的 4/8/16 多尺度 GPU 模糊、版本号、其他开关和原始删除流程。
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

- 只挂 SystemUI 的 `SwipeHelper.dismissChild` 终端重载，并验证 View 是 ExpandableNotificationRow。
  滑动删除及经此路径清空通知可触发；不监听解锁、QS、普通卡片移除、通知到期或面板收起。
- 原方法始终执行，参数、返回、原生取消和通知服务回调均不替换、不延迟。视觉为临时 root.overlay drawable，
  不新增 Window、不拦截触摸，也不改通知 View 的 alpha、visibility 或布局。
- DOWN 仅记录一次未拖离的卡片，后台 HardwareRenderer 产出硬件纹理；删除时消费，非删除手势很快释放。
  按需运行单线程、有界排队；至多三个短时图像与三个活动效果，单图最多 768×384。
  没有持续抓屏，没有 CPU 像素读取、CPU 缩放或逐粒子 View。
- AGSL 负责漂移、随机微粒、颜色采样、消散；CPU 每帧只更新进度 uniform。绘制上限约 30fps。
  速度同时改变运动与消散的时间尺度，单次约 0.37–3.14 秒。
- snapChild 取消、根窗口离开、完成、熄屏/省电和禁用系统动画会停止；硬件失败跳过效果并退避 30 秒。
  不使用 CPU 模糊回退，原系统行为继续。已交给 shader 的图像不主动 recycle。

## 验证

见 `docs/validation/notification-dissolve/`。合成卡片用于像素/清理回归，参考视频不随源码分发。
普通 Android 模拟器可以证明 AGSL 编译、硬件纹理与 overlay 生命周期，不能证明厂商 SwipeHelper
在 ColorOS16/17 中仍使用相同入口；真实滑删、清空、分组通知、其他插件共存和耗电仍需真机验收。
上游现有 Lint 问题会与未改动的相同提交比较，不通过关闭 Lint 掩盖。
