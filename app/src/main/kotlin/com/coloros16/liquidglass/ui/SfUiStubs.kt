package com.coloros16.liquidglass.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.coloros16.liquidglass.R
import com.coloros16.liquidglass.log.Lg
import com.coloros16.liquidglass.sf.SfGlassChannel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/**
 * SF 通道的设置页入口：测试框直调 SF 下发 + 系统模块两路获取。
 * 消费端探测/declare 下发/su 执行走 [SfGlassChannel]；模块打包走 [SfModulePacker]。
 */
object SfModuleInstaller {
    private const val TAG = "SfModuleInstaller"
    private const val INSTALL_TIMEOUT_MS = 60_000L

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "sf-installer-io").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var hostRef: WeakReference<Activity>? = null

    /** 注入宿主 Activity（打包要读 assets、写下载目录，都需要 Context）。 */
    fun attach(activity: Activity) {
        hostRef = WeakReference(activity)
    }

    /** root 直接安装：本机打包模块 zip → magisk --install-module 官方通道装入。 */
    fun rootInstall(onDone: (String) -> Unit) {
        io.execute {
            val msg = performRootInstall()
            main.post { onDone(msg) }
        }
    }

    /** 获取模块文件到下载目录：打包出唯一一个模块 zip 落 Download。 */
    fun exportToDownloads(onDone: (String) -> Unit) {
        io.execute {
            val msg = performExport()
            main.post { onDone(msg) }
        }
    }

    private fun host(): Activity? =
        hostRef?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }

    private fun performRootInstall(): String {
        val host = host()
        if (host == null) {
            Lg.w(TAG, "root install skipped: host activity gone")
            return "安装失败：设置页未就绪，请重新打开设置页再试"
        }
        val pack = SfModulePacker.pack(host)
        if (!pack.ok) {
            Lg.w(TAG, "root install aborted: ${pack.message}")
            return pack.message
        }
        val run = SfGlassChannel.runSu(
            "if command -v magisk >/dev/null 2>&1; then magisk --install-module '${pack.path}'; " +
                "elif [ -x /data/adb/magisk/magisk ]; then /data/adb/magisk/magisk --install-module '${pack.path}'; " +
                "else echo MAGISK_CLI_MISSING; exit 127; fi",
            timeoutMs = INSTALL_TIMEOUT_MS,
        )
        Lg.i(TAG, "magisk install ok=${run.ok} out=${run.out.trim().take(400)} err=${run.err}")
        val verify = SfGlassChannel.runSu(
            "test -f /data/adb/modules/lg_sf_channel/module.prop && echo INSTALLED || echo MISSING",
        )
        val installed = verify.out.contains("INSTALLED")
        return when {
            run.ok && installed -> "已通过 Magisk 安装模块，重启手机后生效：${pack.name}"
            run.out.contains("MAGISK_CLI_MISSING") -> "安装失败：设备上找不到 Magisk 命令"
            run.ok -> "Magisk 安装返回成功但模块目录未生成，请提供日志排查"
            else -> "安装失败：${run.err ?: run.out.trim().take(200).ifBlank { "(无输出)" }}"
        }
    }

    private fun performExport(): String {
        val host = host()
        if (host == null) {
            Lg.w(TAG, "export skipped: host activity gone")
            return "导出失败：设置页未就绪，请重新打开设置页再试"
        }
        val pack = SfModulePacker.pack(host)
        if (!pack.ok) {
            Lg.w(TAG, "export aborted: ${pack.message}")
            return pack.message
        }
        Lg.i(TAG, "export ok ${pack.path} size=${pack.sizeBytes} symbols=${pack.symbolCount}")
        val kb = pack.sizeBytes / 1024
        return "已生成模块安装包到下载目录：${pack.name}（$kb KB），用 Magisk 管理器刷入即可安装。"
    }
}

/** SF 玻璃测试框：直调 SF 渲染下发一帧测试几何，回执经 onDone 返回（文案提示随框弹出）。 */
object SfGlassTest {
    private const val TAG = "SfGlassTest"

    /** 宿主 Activity 弱引用（弹框需要 Activity 上下文）。 */
    @Volatile
    private var hostRef: WeakReference<Activity>? = null

    private val main = Handler(Looper.getMainLooper())

    /** 由设置页注入宿主 Activity（绑定时调用，重复调用只更新引用）。 */
    fun attach(activity: Activity) {
        hostRef = WeakReference(activity)
    }

    /** 下发测试玻璃框（屏幕中央 70%×30%），探测与下发结果回执 onDone 并随提示框显示。 */
    fun show(onDone: (String) -> Unit) {
        val host = hostRef?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
        if (host == null) {
            onDone("下发失败：没有可用的设置页（宿主 Activity 未注入）")
            return
        }
        val dm = host.resources.displayMetrics
        val w = dm.widthPixels * 0.7f
        val h = dm.heightPixels * 0.3f
        val l = (dm.widthPixels - w) / 2f
        val t = dm.heightPixels * 0.35f
        val corner = minOf(w, h) / 4f
        SfGlassChannel.postIo {
            val msg = if (!SfGlassChannel.probeConsumerSync()) {
                "未检测到 SF 消费端（lgdeclare 未运行）——未安装模块或不支持"
            } else {
                val r = SfGlassChannel.sendDeclareSync("on", l, t, w, h, corner, note="test-frame")
                if (r.ok) {
                    "测试框已下发（${w.toInt()}x${h.toInt()} 居中），观察该区域是否出现玻璃效果"
                } else {
                    "下发失败：${r.err}"
                }
            }
            Lg.i(TAG, "test frame: $msg")
            main.post {
                try {
                    val boxText = host.getString(R.string.settings_sf_test_box)
                    MaterialAlertDialogBuilder(host)
                        .setMessage("$boxText\n$msg")
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                } catch (t: Throwable) {
                    Lg.w(TAG, "show test box failed ${t.javaClass.simpleName}", t)
                }
                onDone(msg)
            }
        }
    }
}
