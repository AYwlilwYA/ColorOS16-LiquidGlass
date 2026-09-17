package com.coloros16.liquidglass.hook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.util.Log
import android.view.Display
import android.view.SurfaceControl

/**
 * [spec/82] display 抓屏源：**进程内静态 `ScreenCapture.captureDisplay` + OPPO display token**。
 *
 * ## 要解决的问题
 *
 * `IWindowManager.captureDisplay` 每抓一次，system_server 的
 * `WindowManagerService.captureDisplay` 就打一行 `D WindowManager: captureDisplay`
 * （方法第一行、无条件、无节流）→ 高频抓屏即刷屏。
 *
 * ## ⛔ 第一版方案（已废弃，教训必须留着）
 *
 * 原设计：注入 system_server，hook `WindowManagerService#mirrorWallpaperSurface`，
 * 用**暗号 displayId** 区分我方调用（命中返回 display 根层，其余走原逻辑）。
 *
 * **真机实证它会让显示管线卡死**：屏幕 ON、`mWakefulness=Awake`、进程全在，
 * 但 SurfaceFlinger 合成全黑（截屏恒为 24,856 字节的全黑图），只能靠
 * `setprop ctl.restart surfaceflinger` 恢复。撤掉注入后恢复正常。
 *
 * **更致命的是第二条**：暗号的安全性**完全依赖服务端那个 hook**。一旦 hook 不在而客户端还在
 * 发暗号，WMS 原逻辑拿到非法 displayId → `getDisplayContent()` 返回 null → NPE
 * （真机实测刷出 **68 条** `E WindowManager: Window Manager Crash`，并导致 system_server 重启）。
 * ⇒ **hook 与客户端接线必须同生共死，绝不能只拆一半。**
 *
 * 故 `android` 作用域已永久移除（`scope.list` 里留了「不要再加回来」的警告）。
 *
 * ## 现在的方案
 *
 * 不注入任何进程，改走 OPPO 自己的 Binder：
 *
 * ```
 * ① physicalDisplayId —— 纯本地：Display.getAddress() as DisplayAddress.Physical
 * ② displayToken      —— OplusDisplayManager.getPhysicalDisplayToken(physId)，Binder → DMS（一次，可缓存）
 * ③ 抓屏              —— ScreenCapture.captureDisplay(DisplayCaptureArgs)，静态 / 同步 / 进程内
 * ```
 *
 * ## 验证状态（`reference/sys-services-16.0.9.402/`，设备 16.0.9.402）
 *
 * | 环节 | 状态 |
 * |---|---|
 * | `OplusDisplayManager.getInstance()` public static / `getPhysicalDisplayToken(J)` public 实例 | ✅ smali 实证 |
 * | `ScreenCapture.captureDisplay(DisplayCaptureArgs) : ScreenshotHardwareBuffer` 静态同步 | ✅ smali 实证 |
 * | `DisplayCaptureArgs extends CaptureArgs` → 继承 `setExcludeLayers` | ✅ smali 实证 |
 * | `DisplayCaptureArgs.Builder(IBinder)` / `setSize(II)` | ✅ smali 实证 |
 * | `setExcludeLayers` 在 display capture 上是否真生效 | ⚠️ 未验证 |
 * | 抓屏是否需设 uid / 权限 | ⚠️ 未验证 |
 *
 * AOSP 的正路（`SurfaceControl.getInternalDisplayToken` / `DisplayControl`）在 Android 16 上
 * **已被全部删除**（穷举 `SurfaceControl` 全部方法确认），故这是仅剩的一条。
 *
 * 失败一律返回 null —— 调用方**必须回退**旧的 `IWindowManager.captureDisplay` 路径。
 */
object DisplayRootChannel {

    private const val TAG = "LiquidGlass"

    private const val CLASS_OPLUS_DISPLAY_MANAGER = "android.hardware.display.OplusDisplayManager"
    private const val METHOD_GET_INSTANCE = "getInstance"

    /** display token 缓存：display 不变即长期有效（屏幕重建时 token 失效，`captureDisplay` 会失败 → 回退）。 */
    @Volatile
    private var cachedToken: IBinder? = null

    /**
     * 取 display token：`OplusDisplayManager.getPhysicalDisplayToken(physicalDisplayId)`。
     *
     * 走 **DisplayManagerService** 的 Binder（不是 WMS），**只调一次**（拿到即缓存）。
     * 失败返回 null（调用方回退）。
     */
    /**
     * 拿一个可用 Context。
     *
     * ⚠️ 真机实证：SystemUI 里 `ActivityThread.currentApplication()` **恒为 null**
     * （模块现有 `systemUiContext` 就是这条，导致 `lg-batt: keyguard ctx null fallback true` 一直刷）。
     * 故这里补一条兜底：`ActivityThread.currentActivityThread().getSystemContext()`（系统 context，同样带 DisplayManager）。
     */
    private fun resolveContext(ctx: Context?): Context? {
        ctx?.let { return it }
        return try {
            val at = Class.forName("android.app.ActivityThread")
            val thread = at.getMethod("currentActivityThread").invoke(null)
            (at.getMethod("getSystemContext").invoke(thread) as? Context)?.also {
                Log.i(TAG, "display-token: 用 ActivityThread.getSystemContext() 兜底")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "display-token: getSystemContext() 兜底失败", t)
            null
        }
    }

    fun displayToken(ctx: Context?): IBinder? {
        cachedToken?.let { return it }
        return try {
            val c = resolveContext(ctx)
            if (c == null) {
                Log.w(TAG, "display-token: 无可用 Context（systemUiContext 与 getSystemContext 皆空）")
                return null
            }
            val svc = c.getSystemService(Context.DISPLAY_SERVICE)
            val dm = svc as? DisplayManager
            if (dm == null) {
                Log.w(TAG, "display-token: getSystemService(DISPLAY_SERVICE) -> ${svc?.javaClass?.name ?: "null"}")
                return null
            }
            val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
            if (display == null) {
                Log.w(TAG, "display-token: getDisplay(DEFAULT_DISPLAY) 返回 null")
                return null
            }
            // `Display.getAddress()` 与 `DisplayAddress.Physical.getPhysicalDisplayId()` 都是 @SystemApi
            // （compileSdk 36 不可见）→ 走反射。非物理显示地址（如虚拟显示）拿不到 → 返回 null 回退。
            val address = try {
                display.javaClass.getMethod("getAddress").invoke(display)
            } catch (t: Throwable) {
                Log.e(TAG, "display-token: Display.getAddress() 反射失败", t)
                null
            }
            val physId = try {
                address?.javaClass?.getMethod("getPhysicalDisplayId")?.invoke(address) as? Long
            } catch (t: Throwable) {
                null
            }
            if (physId == null) {
                Log.w(TAG, "display-token: 非物理显示地址（address=$address），放弃")
                return null
            }
            val cls = Class.forName(CLASS_OPLUS_DISPLAY_MANAGER)
            val instance = cls.getMethod(METHOD_GET_INSTANCE).invoke(null)
            val token = cls.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType)
                .invoke(instance, physId) as? IBinder
            if (token == null) {
                Log.w(TAG, "display-token: getPhysicalDisplayToken 返回 null (physId=$physId)")
                return null
            }
            Log.i(TAG, "display-token: acquired physId=$physId")
            cachedToken = token
            token
        } catch (t: Throwable) {
            Log.e(TAG, "display-token: 获取失败", t)
            null
        }
    }

    /**
     * 进程内静态抓屏：`ScreenCapture.captureDisplay(DisplayCaptureArgs)`。
     *
     * **零 WMS Binder、零 `WindowManager: captureDisplay` 记账日志。**
     * 失败返回 null（调用方回退旧路径）。
     *
     * 返回的是**自有软件位图**（wrap + copy 后已 close hw），调用方负责 recycle。
     */
    fun captureViaDisplayToken(
        ctx: Context?,
        crop: Rect,
        frameScale: Float,
        excludes: Array<SurfaceControl>,
    ): Bitmap? {
        val token = displayToken(ctx) ?: return null
        return try {
            val dm = android.content.res.Resources.getSystem().displayMetrics
            val builderCls = Class.forName("android.window.ScreenCapture\$DisplayCaptureArgs\$Builder")
            val baseBuilderCls = Class.forName("android.window.ScreenCapture\$CaptureArgs\$Builder")
            val argsCls = Class.forName("android.window.ScreenCapture\$DisplayCaptureArgs")

            val builder = builderCls.getConstructor(IBinder::class.java).newInstance(token)
            // setSourceCrop / setExcludeLayers 继承自 CaptureArgs.Builder
            baseBuilderCls.getMethod("setSourceCrop", Rect::class.java).invoke(builder, crop)
            baseBuilderCls.getMethod("setExcludeLayers", Array<SurfaceControl>::class.java)
                .invoke(builder, excludes)
            // ⚠️ `DisplayCaptureArgs` 的输出尺寸由 `setSize()` 决定，**不是 `setFrameScale`** ——
            // 真机实测：只设 `setFrameScale(0.25)` 时仍返回 1440x3168 全分辨率，
            // 每次抓屏要 wrap+copy 一张 18MB 位图（旧路径同参数只有 1.1MB），高频抓屏下 GC 压力大。
            // 故这里直接给缩放后的尺寸；`frameScale` 保持默认 1.0，避免二次缩放。
            val outW = (dm.widthPixels * frameScale).toInt().coerceAtLeast(1)
            val outH = (dm.heightPixels * frameScale).toInt().coerceAtLeast(1)
            builderCls.getMethod(
                "setSize", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
            ).invoke(builder, outW, outH)

            val args = builderCls.getMethod("build").invoke(builder) ?: return null
            val shot = Class.forName("android.window.ScreenCapture")
                .getMethod("captureDisplay", argsCls)
                .invoke(null, args) ?: return null
            val hb = shot.javaClass.getMethod("getHardwareBuffer").invoke(shot) as? HardwareBuffer
                ?: return null
            // 取到 buffer 后立刻 wrap+copy 出自有软件位图，再关掉 hb（避免泄漏）。
            val bitmap = Bitmap.wrapHardwareBuffer(hb, null)?.copy(Bitmap.Config.ARGB_8888, false)
            hb.close()
            // 不打成功日志：本函数在抓屏热路径上（面板拖动期每秒数十次），由调用方按需节流记录。
            bitmap
        } catch (t: Throwable) {
            Log.e(TAG, "display-token: captureDisplay 失败", t)
            null
        }
    }
}
