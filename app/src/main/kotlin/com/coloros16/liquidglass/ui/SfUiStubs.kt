package com.coloros16.liquidglass.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.coloros16.liquidglass.R
import com.coloros16.liquidglass.log.Lg
import com.coloros16.liquidglass.sf.SfGlassChannel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/**
 * SF 通道的设置页入口：测试框直调 SF 下发 + 系统模块两路获取。
 * 消费端探测/declare 下发/ su 执行走 [SfGlassChannel]。
 */
object SfModuleInstaller {
    private const val TAG = "SfModuleInstaller"
    private const val INSTALL_SCRIPT_PATH = "/data/local/tmp/lg_sf_install.sh"
    private const val EXPORT_DIR = "/sdcard/Download/lg-sf-channel"

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "sf-installer-io").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    /** root 直接安装：查 su 通道 → 查既有装载件（l26 既有方式：探针+真身 bind）→ 落脚本执行装载。 */
    fun rootInstall(onDone: (String) -> Unit) {
        io.execute {
            val msg = performRootInstall()
            main.post { onDone(msg) }
        }
    }

    /** 导出模块文件到下载目录：列查设备上 SF 通道既有装载件与打包件，能导尽导、缺项列明。 */
    fun exportToDownloads(onDone: (String) -> Unit) {
        io.execute {
            val msg = performExport()
            main.post { onDone(msg) }
        }
    }

    private fun performRootInstall(): String {
        val su = SfGlassChannel.runSu("id")
        if (!su.ok) return "root 不可用，未执行安装：${su.err}"

        val probe = SfGlassChannel.runSu(
            "test -f /data/local/tmp/lgt_probe.so && echo PROBE_OK || echo PROBE_MISSING; " +
                "test -f /data/local/tmp/ppex_real/libdisplayppex.so && echo REAL_OK || echo REAL_MISSING",
        )
        val probeOk = probe.out.contains("PROBE_OK")
        val realOk = probe.out.contains("REAL_OK")
        if (!probeOk || !realOk) {
            val missing = buildList {
                if (!probeOk) add("探针 trampoline /data/local/tmp/lgt_probe.so")
                if (!realOk) add("真身 /data/local/tmp/ppex_real/libdisplayppex.so")
            }.joinToString("、")
            return "缺装载件，未执行安装：$missing。模块文件还没生成，需先在电脑上生成一次再安装。"
        }

        // 既有装载链 = tools/lgdeclare/l26_setup.sh：prop + SELinux 放行 + 双 bind + conf + 刷 SF
        val script = """
            RP=$(command -v resetprop 2>/dev/null || echo /data/adb/ap/bin/resetprop)
            MP=$(command -v magiskpolicy 2>/dev/null || echo /data/adb/ap/bin/magiskpolicy)
            ${'$'}RP ro.surface_flinger.supports_background_blur 1
            echo "PROP=$(getprop ro.surface_flinger.supports_background_blur)"
            ${'$'}MP --live "allow surfaceflinger shell_data_file file { read open getattr map execute }"
            ${'$'}MP --live "allow surfaceflinger shell_data_file dir { search getattr open read }"
            ${'$'}MP --live "allow surfaceflinger vendor_default_prop file { read open getattr map }"
            rm -f /data/local/tmp/lg_glass.state
            mount --bind /data/local/tmp/lgt_probe.so /system_ext/lib64/oplusex/libdisplayppex.so
            echo "BIND_PROBE=$?"
            mount --bind /data/local/tmp/ppex_real/libdisplayppex.so /system_ext/lib64/oplusex/libOplusDualHeadPhoneEx.so
            echo "BIND_REAL=$?"
            echo 'on=1 bx=200 by=200 bw=512 bh=512 br=99 timeout_ms=8 overtrip=10 rf=28.0 ds=0.00 hg=0.50 ag=45.0 vb=1.50 ib=0.0' > /data/local/tmp/lg_glass.conf
            setprop vendor.debug.lg.glass 1
            setprop vendor.debug.sf.restart 0
            setprop vendor.debug.sf.restart 1
            echo SF_RESTART_SENT
        """.trimIndent()

        val writeFile = SfGlassChannel.runSu("cat > $INSTALL_SCRIPT_PATH <<'LGEOF'\n$script\nLGEOF")
        if (!writeFile.ok) return "装载脚本写入失败：${writeFile.err}"

        val run = SfGlassChannel.runSu("sh $INSTALL_SCRIPT_PATH")
        val out = run.out
        val bindProbe = out.substringAfter("BIND_PROBE=", "").substringBefore('\n')
        val bindReal = out.substringAfter("BIND_REAL=", "").substringBefore('\n')
        val prop = out.substringAfter("PROP=", "").substringBefore('\n')
        val restarted = out.contains("SF_RESTART_SENT")
        return if (run.ok && bindProbe == "0" && bindReal == "0") {
            // 装完顺手拉起消费端（已在跑则不重复起），否则 sf 档探测恒空回四腿
            val poll = SfGlassChannel.runSu(
                "pidof lgdeclare >/dev/null 2>&1 || " +
                    "nohup /data/local/tmp/lgdeclare poll /data/local/tmp/lgbox_sf/declare.json " +
                    ">/data/local/tmp/lgdeclare_poll.log 2>&1 & " +
                    "sleep 1; pidof lgdeclare",
            )
            val pollUp = poll.out.any { it.isDigit() }
            "安装完成：探针 bind=0、真身 bind=0（blur prop=$prop），SF 已下发重启；" +
                if (pollUp) "消费端已在运行。" else "注意：消费端未启动，系统级渲染不会生效。" +
                "约 2~5 秒生效，验证看 logcat（LG-GLASS / lgdeclare 日志）。脚本留档 $INSTALL_SCRIPT_PATH"
        } else {
            "安装未完成：bind 探针=$bindProbe 真身=$bindReal SF重启=$restarted；输出：${out.trim().take(300).ifBlank { "(空)" }}${run.err?.let { "；$it" } ?: ""}"
        }
    }

    private fun performExport(): String {
        val su = SfGlassChannel.runSu("id")
        if (!su.ok) return "导出失败：root 不可用（${su.err}）"

        val exportables = listOf(
            "/data/local/tmp/lgt_probe.so",
            "/data/local/tmp/ppex_real/libdisplayppex.so",
            "/data/local/tmp/lgdeclare",
            "/data/local/tmp/lg_glass.conf",
        )
        val packed = listOf(
            "trampoline.so",
            "libdisplayppex.real.so",
            "symbols.txt",
            "module.prop",
        )

        val check = SfGlassChannel.runSu(
            exportables.joinToString("; ") { "test -f $it && echo HAS:$it || echo NO:$it" },
        )
        val found = exportables.filter { check.out.contains("HAS:$it") }

        val packCheck = SfGlassChannel.runSu(
            "ls /data/adb/modules/ 2>/dev/null | while read m; do " +
                "for f in trampoline.so libdisplayppex.real.so symbols.txt module.prop; do " +
                "test -f \"/data/adb/modules/\$m/\$f\" && echo \"HAS:/data/adb/modules/\$m/\$f\"; " +
                "done; done; true",
        )
        val packedFound = packCheck.out.lineSequence()
            .mapNotNull { it.removePrefix("HAS:").trim().takeIf { p -> p.isNotEmpty() } }
            .toList()

        if (found.isEmpty() && packedFound.isEmpty()) {
            return "导出失败：设备上没有可导出的模块文件（装载件与打包件都没有）——" +
                "模块文件还没生成，需先在电脑上生成一次。"
        }

        val mk = SfGlassChannel.runSu("mkdir -p $EXPORT_DIR")
        if (!mk.ok) return "导出失败：创建 $EXPORT_DIR 被拒（${mk.err}）"

        val toCopy = found + packedFound
        val copy = SfGlassChannel.runSu(
            toCopy.joinToString("; ") { "cp -f $it $EXPORT_DIR/ 2>/dev/null || echo COPY_FAIL:$it" },
        )
        val failed = copy.out.lineSequence()
            .mapNotNull { it.removePrefix("COPY_FAIL:").trim().takeIf { p -> p.isNotEmpty() } }
            .toList()
        val okNames = (toCopy - failed.toSet()).map { it.substringAfterLast('/') }
        val missingPacked = packed - packedFound.map { it.substringAfterLast('/') }.toSet()

        val sb = StringBuilder()
        sb.append("已导出 ").append(okNames.size).append(" 个文件到 ").append(EXPORT_DIR).append("：")
        sb.append(okNames.joinToString("、"))
        if (missingPacked.isNotEmpty()) {
            sb.append("。缺模块文件 ").append(missingPacked.joinToString("、"))
            sb.append("（还没生成，需先在电脑上生成一次）")
        }
        if (failed.isNotEmpty()) {
            sb.append("。复制失败：").append(failed.joinToString("、"))
        }
        return sb.toString()
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
