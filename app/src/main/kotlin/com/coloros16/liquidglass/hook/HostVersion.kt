package com.coloros16.liquidglass.hook

import android.content.Context
import android.os.Build
import com.coloros16.liquidglass.log.Lg

/** 宿主包 versionCode 读取公共工具（Launcher/SystemUI 通用；失败返回 -1 表示未知，按老版本路径处理）。 */
object HostVersion {

    private const val TAG = "lg-hook"

    /** 读取指定宿主包的 versionCode；context 或读取失败一律返回 -1，绝不抛出。 */
    fun hostVersionCode(context: Context?, pkg: String): Long {
        if (context == null) return -1L
        return try {
            val info = context.packageManager.getPackageInfo(pkg, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        } catch (t: Throwable) {
            Lg.w(TAG, "hostVersionCode: read $pkg failed: $t")
            -1L
        }
    }

    /** Xposed 进程内便捷重载：自动取 Application context 后读 versionCode。 */
    fun hostVersionCode(pkg: String): Long = hostVersionCode(appContextOrNull(), pkg)

    /** 取当前进程 Context：复用 [Lg.processContext] 的三级兜底链（失败返回 null）。 */
    private fun appContextOrNull(): Context? = Lg.processContext()
}
