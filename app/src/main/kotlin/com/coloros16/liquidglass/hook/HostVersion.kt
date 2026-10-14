package com.coloros16.liquidglass.hook

import android.content.Context
import android.os.Build
import android.util.Log

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
            Log.w(TAG, "hostVersionCode: read $pkg failed: $t")
            -1L
        }
    }

    /** Xposed 进程内便捷重载：自动取 Application context 后读 versionCode。 */
    fun hostVersionCode(pkg: String): Long = hostVersionCode(appContextOrNull(), pkg)

    /** 取当前进程 Context：先试 AndroidAppHelper 再反射 ActivityThread，SystemUI 场景用 getSystemContext 兜底，都失败返回 null。 */
    private fun appContextOrNull(): Context? {
        try {
            val ctx = Class.forName("de.robv.android.xposed.AndroidAppHelper")
                .getMethod("currentApplication").invoke(null) as? Context
            if (ctx != null) return ctx
        } catch (_: Throwable) {
        }
        try {
            val ctx = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null) as? Context
            if (ctx != null) return ctx
        } catch (_: Throwable) {
        }
        return try {
            val at = Class.forName("android.app.ActivityThread")
            val thread = at.getMethod("currentActivityThread").invoke(null)
            at.getMethod("getSystemContext").invoke(thread) as? Context
        } catch (_: Throwable) {
            null
        }
    }
}
