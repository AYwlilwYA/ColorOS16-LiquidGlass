# ColorOS16 Liquid Glass

为 **ColorOS 16** 的通知中心 / 控制中心实现 **iOS 26 风格液态玻璃（Liquid Glass）** 动效的 LSPosed 模块。

不改系统 APK、不替换 View 结构 —— 通过 hook ColorOS 自带的模糊管线，拦截未模糊原图，用自研 AGSL 着色器完整渲染液态玻璃效果（模糊 + SDF 圆角折射 + 色散 + 高光）。

> **当前状态**：开发中 / 实验性。所有功能默认关闭，开启前行为与未安装时完全一致。

---

## 效果

针对通知中心 / 控制中心的模糊控件（通知卡片、快捷开关、媒体卡、滑块胶囊等）渲染液态玻璃材质：内部清晰透镜、边缘折射环带、光谱色散、定向高光、vibrancy 增艳。

## 实现原理

```
ColorOS 模糊管线（com.oplus.posteffect）
        │
        ├─ hook 拦截「未模糊原图」（HardwareBuffer）
        │
        ↓
   自研 AGSL RuntimeShader
   （SDF 圆角矩形 + 折射位移 + 色散 + 高光 + vibrancy）
        │
        ↓
   替换系统「纯模糊 + 混合」的绘制输出
```

要点：

- **只改造绘制，不改结构**：不替换 View、不加覆盖层、不侵入系统内部实现，兼容性最好。
- **背景源自维护**：整屏快照 + 按元素区域折算采样坐标，元素移动时实时折算，静止时不抓屏。
- **AGSL 着色器**：基于 Android `RuntimeShader`，算法派生自 [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)（Apache-2.0，详见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)）。

## 功能

模块内置配置界面，各项均可独立开关，**默认全部关闭**：

| 分类 | 内容 |
|------|------|
| 液态玻璃 | 折射强度、模糊半径、鲜艳度、高光强度 / 衰减、圆角等参数 |
| 遮罩与文字 | 背景压暗遮罩、文字可读性配色（统一注入通道，避免跳色） |
| 桌面动画 | 应用开 / 关 / 打断动画的弹簧刚度与阻尼、图标透明度时长 |
| 倾斜与透视 | 应用切换的 3D 倾斜动效（实验性） |
| 背景与抓屏 | 抓屏速率、降采样倍率等 |
| 其他 | 锁屏时钟玻璃化、流体云卡片玻璃化等 |

## 环境要求

- **系统**：ColorOS 16（Android 16），已在 OnePlus PJZ110 / PDEM30 验证
- **框架**：LSPosed（支持 LibXposed API）
- **作用域**：`com.android.systemui`、`com.android.launcher`

## 构建

```bash
# 需要 JDK 17+ 与 Gradle 8.14+
gradle -p . :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

> 独立 Demo 模块：`demo/`，用于隔离验证着色器效果。

## 致谢

- [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) —— 液态玻璃着色算法来源（Apache-2.0）

## 许可证

[GNU General Public License v3.0](LICENSE)

本项目包含派生自 Apache-2.0 许可项目的代码，相关声明见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。
