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
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
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
 * - [doc/spec/110] onHotReloading / onHotReloaded：热重载两段回调（见下）
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
        val classLoader = param.getDefaultClassLoader() ?: return
        // [doc/spec/110] 存宿主 classLoader：同 ClassLoader 复用重载时 onHotReloaded 直接取用；
        // 新 ClassLoader 场景下新类静态为空，由 resolveClassLoader 的兜底链接管。
        cacheClassLoader(param.packageName, classLoader)
        doInstall(param.packageName, classLoader)
    }

    /**
     * [doc/spec/110 热重载] 第一段：**旧类**回调，Drip 据此决定是否继续重载（default 恒 false ⇒
     * 不 override 则整条热重载在第一道门就被 refused，1~3ms 瞬败且无 message —— 实测根因）。
     *
     * 返回 true 前必须停掉模块自有常驻异步（几何线程池 / 主线程延时回调 / 单飞标志），
     * 否则旧类线程随每次重载泄漏、旧延时回调会带着旧逻辑穿插新代码。
     */
    override fun onHotReloading(param: HotReloadingParam): Boolean {
        // 注意：HotReloadingParam **不**继承 ModuleLoadedParam，只暴露 extras/savedState，拿不到 processName
        Log.i(TAG, "hot-reload: approving (old-class callback)")
        try {
            BlurDrawHook.shutdownForHotReload()
        } catch (t: Throwable) {
            Log.w(TAG, "hot-reload: shutdown step failed", t)
        }
        return true
    }

    /**
     * [doc/spec/110 热重载] 第二段：**新类**回调。
     *
     * ⚠️ 两个动作缺一不可（只 opt-in onHotReloading 不够）：
     * - 框架 default 本方法 = `oldHookHandles.forEach { unhook }`：**不 override 也会卸光旧 hook**
     *   （但不会重装 ⇒ reload 后模块全灭）；这里显式复刻卸载 + 立即重装成**新代码**的 hook。
     * - 装载回调（onPackageLoaded）**不会重放**，必须在此手动重跑安装。
     *
     * 保守顺序：**先解析 classLoader，失败则一条旧 hook 都不卸** —— 宁可继续跑旧代码，
     * 也不接受「卸了装不回」的全灭状态（本项目一贯原则：宁可不生效也不失效）。
     */
    override fun onHotReloaded(param: HotReloadedParam) {
        val pkg = param.processName
        if (pkg == null) {
            Log.e(TAG, "hot-reload: null processName, keep old hooks")
            return
        }
        if (pkg != PACKAGE_SYSTEMUI && pkg != PACKAGE_LAUNCHER) {
            // 非目标进程：按框架语义卸掉旧 hook（这些进程本就不该有模块 hook）
            param.oldHookHandles.forEach { h ->
                try {
                    h.unhook()
                } catch (t: Throwable) {
                }
            }
            return
        }
        val cl = resolveClassLoader(pkg, param)
        if (cl == null) {
            Log.e(TAG, "hot-reload: classLoader unresolved for $pkg, KEEP OLD HOOKS (no unhook)")
            return
        }
        // 旧 hook → 新 hook 的交接（等价框架 default，再立即重装）
        var unhooked = 0
        param.oldHookHandles.forEach { h ->
            try {
                h.unhook()
                unhooked++
            } catch (t: Throwable) {
            }
        }
        Log.i(TAG, "hot-reload: old hooks unhooked=$unhooked/$pkg, reinstalling into $pkg")
        doInstall(pkg, cl)
        Log.i(TAG, "hot-reload: reinstall done for $pkg, version=$MODULE_VERSION")
    }

    /** [doc/spec/110] 安装主入口（onPackageLoaded 与 onHotReloaded 共用同一套门槛与分发）。 */
    private fun doInstall(packageName: String, classLoader: ClassLoader) {
        // 模块默认全关（Prefs.masterEnabled=false 直接跳过，零侵入）。
        if (!Prefs.masterEnabled(this)) {
            Log.d(TAG, "package loaded but master disabled, skip install")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(TAG, "device SDK=${Build.VERSION.SDK_INT} < 29(Q), skip install")
            return
        }
        Log.i(TAG, "installing hooks into $packageName")
        when (packageName) {
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

    // ------------------------------------------------------------ [doc/spec/110] 热重载 classLoader 解析链 ------------------------------------------------------------
    // onHotReloaded 跑在**新加载的类**上 ⇒ 新类静态为空，onPackageLoaded 存的缓存多半取不到，
    // 兜底链按「宿主线程 contextClassLoader → 宿主已知 Application 类」逐级退化。
    // [2026-10-13 修复] 实测三级全空 → KEEP OLD HOOKS（新代码永远进不了进程）：
    // ① 新类静态缓存必空 ② TCCL 在 Drip 重载线程上不可靠 ③ Class.forName 用的还是同一个不可靠 TCCL。
    // 现把 **①.5 旧 hook 反查**插到最前：HookHandle.getExecutable().declaringClass.classLoader ——
    // hook 目标方法声明在宿主类（com.android.systemui.* / com.oplus.*）里，其 classLoader 就是宿主
    // 本尊，不依赖任何静态字段或线程状态（boot 类返回 null 由 firstNotNullOfOrNull 自动跳过）。

    private fun resolveClassLoader(pkg: String, param: HotReloadedParam): ClassLoader? {
        cachedClassLoader(pkg)?.let { return it }
        param.oldHookHandles.firstNotNullOfOrNull { h ->
            try {
                h.executable?.declaringClass?.classLoader
            } catch (t: Throwable) {
                null
            }
        }?.let { return it }
        try {
            Thread.currentThread().contextClassLoader?.let { return it }
        } catch (t: Throwable) {
        }
        return try {
            val hostCls = when (pkg) {
                PACKAGE_SYSTEMUI -> "com.android.systemui.SystemUIApplication"
                PACKAGE_LAUNCHER -> "com.android.launcher3.LauncherApplication"
                else -> null
            }
            hostCls?.let { Class.forName(it, false, Thread.currentThread().contextClassLoader) }?.classLoader
        } catch (t: Throwable) {
            null
        }
    }

    private fun cacheClassLoader(pkg: String, cl: ClassLoader) {
        synchronized(loaderLock) {
            when (pkg) {
                PACKAGE_SYSTEMUI -> cachedSystemUiLoader = cl
                PACKAGE_LAUNCHER -> cachedLauncherLoader = cl
            }
        }
    }

    private fun cachedClassLoader(pkg: String): ClassLoader? = synchronized(loaderLock) {
        when (pkg) {
            PACKAGE_SYSTEMUI -> cachedSystemUiLoader
            PACKAGE_LAUNCHER -> cachedLauncherLoader
            else -> null
        }
    }

    companion object {
        /** SystemUI 进程（通知中心/控制中心）：Hook B 绘制替换 / Hook C 场景过滤。
         *  [2026-08-13 用户决定] BlurService 进程（com.oplus.blur）已全移除干预，不再有 PACKAGE_BLUR 常量。 */
        const val PACKAGE_SYSTEMUI = "com.android.systemui"
        /** [spec/10] Launcher 进程（桌面）：文件夹背景液态玻璃化（OplusPreviewBackground 闭合图标 +
         *  Folder.dispatchDraw 打开卡片）。与 SystemUI 共用同一套 posteffect drawBlurShader hook。 */
        const val PACKAGE_LAUNCHER = "com.android.launcher"
        /** [doc/spec/110] 宿主 classLoader 缓存（onPackageLoaded 存 / onHotReloaded 读）。 */
        private val loaderLock = Any()
        private var cachedSystemUiLoader: ClassLoader? = null
        private var cachedLauncherLoader: ClassLoader? = null
        /** [doc/spec/110] 模块版本标记（热重载完成日志用，便于 daemon 侧对账 loaded version）。 */
        private const val MODULE_VERSION = "spec-113"
        private const val TAG = "LiquidGlass"
    }
}
