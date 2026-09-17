package com.coloros16.liquidglass.entry

import android.os.Build
import android.util.Log
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.hook.BlurDrawHook
import com.coloros16.liquidglass.hook.LauncherAnimHook
import com.coloros16.liquidglass.hook.LauncherHook
import com.coloros16.liquidglass.hook.LauncherTiltHook
import com.coloros16.liquidglass.hook.LockScreenClockHook
import com.coloros16.liquidglass.hook.SceneFilter
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * LibXposed 模块入口。
 *
 * 关于 @XposedEntry：本工程依赖的 LibXposed 102.0.0（本地缓存）【不存在】该注解。
 * 已实证（api 源码 src/main/java/io/github/libxposed/api/package-info.java 的
 * "Getting Started / Entry Registration" 章节）：
 * - 模块入口 = 继承 [XposedModule] 的类（保留无参构造，框架反射实例化）
 * - 入口类注册于 META-INF/xposed/java_init.list（一行一个全限定类名）
 * - annotation 1.0.0 仅含 @SinceApi / @InternalApi，无 XposedEntry
 * 故本类采用继承 XposedModule 方式；若未来框架引入 @XposedEntry 注解可平滑切换。
 *
 * 生命周期（XposedModuleInterface，可重写）：
 * - onModuleLoaded：模块注入目标进程时调用一次（此时 SystemUI 类未就绪）
 * - onPackageLoaded：目标包默认 classLoader 就绪后调用（本工程在此安装 hook）
 */
class XposedEntry : XposedModule() {

    override fun onModuleLoaded(param: io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam) {
        // 模块注入完成（SystemUI 类尚未加载）。标记入口加载成功，便于真机确认模块生效。
        Log.i(TAG, "module loaded, process=${param.processName}, isSystemServer=${param.isSystemServer()}")
    }

    // ⛔ [spec/82 已停用 2026-09-17] 曾在此实现 `onSystemServerStarting`，向 system_server 挂
    // `WindowManagerService#mirrorWallpaperSurface` 通道 hook。**真机实证该注入会导致显示管线卡死**
    // （屏幕 ON、`mWakefulness=Awake`，但 SurfaceFlinger 合成全黑；撤掉后恢复）。故整条注入撤除。
    // 详见 doc/spec/82 §九。

    override fun onPackageLoaded(param: PackageLoadedParam) {
        // 框架可能注入超出 scope 的包，必须按包名过滤。
        // [2026-08-13 用户决定] BlurService 端全移除：仅 SystemUI 进程安装 hook（com.oplus.blur
        // 进程不再注入，系统模糊管线完全原样，玻璃完全靠 SystemUI 端每元素背景源）。
        // [2026-08-13 spec/10] Launcher 进程（com.android.launcher）加入：桌面文件夹背景液态玻璃化，
        // 与 SystemUI 共用同一套 posteffect drawBlurShader hook（FQCN 相同，两份独立 hook 实例）。
        if (param.packageName != PACKAGE_SYSTEMUI && param.packageName != PACKAGE_LAUNCHER) return
        // 模块默认全关（Prefs.masterEnabled=false 直接跳过，零侵入）。
        if (!Prefs.masterEnabled(this)) {
            Log.d(TAG, "package loaded but master disabled, skip install")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(TAG, "device SDK=${Build.VERSION.SDK_INT} < 29(Q), skip install")
            return
        }
        val classLoader = param.getDefaultClassLoader() ?: return
        Log.i(TAG, "installing hooks into ${param.packageName}")
        when (param.packageName) {
            // SystemUI：Hook B 绘制替换 / Hook C 场景过滤（占位实现，保持现状）。
            // [2026-08-13 用户决定] BlurService 端全移除：模块不再干预 com.oplus.blur 进程，
            // 系统模糊管线完全原样；玻璃完全靠 SystemUI 端每元素背景源。
            PACKAGE_SYSTEMUI -> installHooks(classLoader)
            // [spec/10] Launcher：桌面文件夹背景液态玻璃化（闭合文件夹图标 + 打开文件夹卡片）。
            // [spec/64] Launcher：桌面动画参数调节（弹簧刚度/阻尼 + 图标透明度时长，默认全关）。
            // [spec/65] Launcher：iOS 动态倾斜/透视（实验性，默认关）。
            PACKAGE_LAUNCHER -> {
                LauncherHook.install(this, classLoader)
                LauncherAnimHook.install(this, classLoader)
                LauncherTiltHook.install(this, classLoader)
            }
        }
    }

    private fun installHooks(classLoader: ClassLoader) {
        // 仅 SystemUI 进程：Hook B 绘制替换 / Hook C 场景过滤（占位实现；类名/方法名来自旧版反编译，待新版核对）。
        // Hook A（BlurService 端原图注入）已随 [2026-08-13 用户决定] 全移除——系统模糊管线原样，
        // 玻璃源 = SystemUI 端每元素背景源（BlurDrawHook 内 captureDisplay）。
        BlurDrawHook.install(this, classLoader)
        // [spec/81 2026-09-17 停用] 锁屏时钟玻璃化：真机品红二分实证 `View.draw` hook 未命中
        // 时钟数字 View（数字仍是系统原样），接管本身没发生 → 保持停用，设置页入口已移除。
        // LockScreenClockHook.install(this, classLoader)
        SceneFilter.install(this, classLoader)
        // [spec/82] ⛔ 曾有 display-root 通道探针挂在这里，**已删除** —— 见 DisplayRootChannel 头部说明：
        // 它在屏幕暗/AOD 时会连续 120 秒每 5 秒抓一次整屏，与 UI 合成抢 SF，
        // 造成「进桌面卡顿」「SystemUI 重启后异常」。通道本身已验证通过，不再需要常驻探针。
    }

    companion object {
        /** SystemUI 进程（通知中心/控制中心）：Hook B 绘制替换 / Hook C 场景过滤。
         *  [2026-08-13 用户决定] BlurService 进程（com.oplus.blur）已全移除干预，不再有 PACKAGE_BLUR 常量。 */
        const val PACKAGE_SYSTEMUI = "com.android.systemui"
        /** [spec/10] Launcher 进程（桌面）：文件夹背景液态玻璃化（OplusPreviewBackground 闭合图标 +
         *  Folder.dispatchDraw 打开卡片）。与 SystemUI 共用同一套 posteffect drawBlurShader hook。 */
        const val PACKAGE_LAUNCHER = "com.android.launcher"
        private const val TAG = "LiquidGlass"
    }
}
