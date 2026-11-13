# DreamGlass — 第三方代码、来源与许可

本地构建日期：2026-10-30；对应版本：1.1.4。DreamGlass 是独立产品名称，For ColorOS 17 仅说明适配范围。

## 1. 整体许可与派生基础

本软件基于 AYwlilwYA / ColorOS16-LiquidGlass 的代码持续开发，并非完全从零编写。上游地址：https://github.com/AYwlilwYA/ColorOS16-LiquidGlass ，原始基线提交 `aa96e1aeb98f4e3777f4bced265a1b86bf3e10cd`（0.2.1）；本版同步至 `1bb018615af3261d3e9de0165169a0e75917d2fe`（0.3.0及后续修复）。原项目界面中的作者署名为 AlanYan，原有版权继续归原作者和贡献者。

DreamGlass 作为整体按 GNU General Public License version 3 发布，全文见 LICENSE；本声明不移除第三方代码自身的版权或许可条件。新界面、品牌、适配代码和产品流程不改变 GPLv3 派生作品的许可义务。软件无任何担保；接收者有权在 GPLv3 下使用、研究、修改和再分发。

主要修改包括：独立产品界面与导航；ColorOS 17 实际接口适配；通知/控制中心内容录制和场景隔离；音量面板原生保护；滑杆轮廓；材质精修；框架配置、诊断回传、隐藏图标及用户确认的进程重启。日期和修改文件见 MODIFICATIONS.md。上游介绍保存在 docs/UPSTREAM_README.md。

APK 的每次正式发布均同时提供对应完整源码 ZIP、Gradle 包装器、构建脚本、本地依赖及构建说明。社区转发时应让 APK 的接收者同样获得源码与许可，不能只给出无法访问的私有仓库链接。

## 2. 部分着色算法来源：Kyant0 / AndroidLiquidGlass

来源：https://github.com/Kyant0/AndroidLiquidGlass ，Apache License 2.0；全文见 licenses/Apache-2.0.txt。

原始算法版权：Copyright 2025 Kyant。原始 `backdrop/.../internal/Shaders.kt` 的 Apache 许可头已保留在涉及的着色器源文件中。涉及主模块和 demo 中的 LiquidGlassShader.kt。

ColorOS16-LiquidGlass 已将部分 SDF、折射、色散、鲜艳度和定向高光处理移植为 Android AGSL。DreamGlass 继续修改 COS17 的采样接入、圆角、边缘能量与特定场景材质。相关文件明确标记修改及日期。此次核查的上游 kmp 树未提供独立 NOTICE 文件；我们保留原始版权头并在 NOTICE 中增加来源说明。原始 Apache 许可条件与整体 GPLv3 分发条件同时保留。

## 3. 实际分发的库

AndroidX、Material Components、Kotlin 标准库、Kotlin Coroutines、JetBrains Annotations、Error Prone Annotations、JSpecify、Guava ListenableFuture，以及 LibXposed service/interface，均按各自 Apache License 2.0 条件分发。

精确坐标和版本由实际 releaseRuntimeClasspath 解析生成，见 licenses/dependency-inventory.json；APK 内有同一清单、Apache 全文及从所用归档中提取的原始 LICENSE/NOTICE/COPYRIGHT。原始附件见 licenses/third-party/，提取条目与摘要在清单中记录，不以一条笼统说明代替原件。

两个本地 LibXposed 102.0.0 AAR 的修改仅为 aar-metadata.properties 中 minCompileSdk 从 37 改为 36，类及资源保持原样。来源与重建方式见 app/libs/README.md。LibXposed API 为 compileOnly，不打入 APK；它的 Apache 2.0 来源也在清单中说明。

Gradle wrapper 是源码包内的构建工具，Apache 2.0；其原始归档声明同样保留。JUnit、Kotlin 编译器、Android SDK、桌面 Skia、Pillow、模拟器属于构建/验证工具，未捆绑进 APK。这里不将它们误称为应用运行库。

## 4. 未复制的参考内容

ElbowHitColor 及其适配分支只用于互操作接口和处理思路参考，包括透明色层、低压暗与图标底层分离。DreamGlass 未复制或打包其实现、着色器、原生库、界面资源、图标或配置；不据此声称取得其代码许可。关于已进行的核查以本段为准，取代旧声明中“没有反编译”的不准确表述。

Z841973620/ColorOSLiquidGlass 仅用于公开介绍参考，本项目未复制其代码。DreamGlass 的三层图形标记为本项目绘制；不随包分发上游联系人入口或收款二维码。

以上声明不表示原作者、库作者或 ColorOS 权利人对 DreamGlass 的背书。

## Android 硬件模糊示例

`HardwareBackdropBlur.kt` 与 `ParticleDissolveHook.kt` 的 RenderNode / HardwareRenderer / ImageReader 处理顺序参考并改写自 Android Developers / Android Open Source Project 的 [Migrate from RenderScript — Image blur on Android 12+ rendered into a Bitmap](https://developer.android.com/guide/topics/renderscript/migrate)。文档示例代码按 [Apache License 2.0](https://developer.android.com/license) 提供；许可证全文已随包保留为 Apache-2.0.txt。DreamGlass 额外实现每结果独立缓冲队列、取消、失败回退及有界调度，修改日期为2026-10-29。未引入新的二进制依赖。
