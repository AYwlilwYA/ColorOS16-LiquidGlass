package com.coloros16.liquidglass.log

import android.os.SystemClock
import android.util.Log

/**
 * 日志出口：logcat 单通道。「运行日志」开关作 logcat 详细度门控——
 * 开 = 全量（d/i/w/e），关 = 仅 e 级。门控读取器由 App（模块进程）/ XposedEntry（hook 进程）注入。
 */
object Lg {

    private const val GATE_TTL_MS = 1000L

    /** 「运行日志」开关读取器（hook 进程读框架侧 Prefs，模块进程读自身 Prefs）。 */
    @Volatile
    private var gateReader: (() -> Boolean)? = null

    @Volatile
    private var gateValue = true

    @Volatile
    private var gateAtMs = 0L

    /** 注入开关读取器（进程启动时一次）。 */
    fun installGateReader(reader: () -> Boolean) {
        gateReader = reader
        gateAtMs = 0L
    }

    /** 取当前进程 Context 的兜底链：Xposed AndroidAppHelper → ActivityThread Application → 系统 Context。 */
    fun processContext(): android.content.Context? {
        try {
            val ctx = Class.forName("de.robv.android.xposed.AndroidAppHelper")
                .getMethod("currentApplication").invoke(null) as? android.content.Context
            if (ctx != null) return ctx
        } catch (_: Throwable) {
        }
        try {
            val ctx = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null) as? android.content.Context
            if (ctx != null) return ctx
        } catch (_: Throwable) {
        }
        return try {
            val at = Class.forName("android.app.ActivityThread")
            val thread = at.getMethod("currentActivityThread").invoke(null)
            (at.getMethod("getSystemContext").invoke(thread) as? android.content.Context)?.takeIf {
                // system context 的 opPackageName 是 "android"，拿它开文件/查询会因 uid 不一致被拒；宁可等 Application 就绪后重取
                it.packageName != "android"
            }
        } catch (t: Throwable) {
            null
        }
    }

    /** 开关状态（1s TTL 缓存；未注入或读取异常一律默认开，杜绝静默丢日志）。 */
    private fun gateOpen(): Boolean {
        val reader = gateReader ?: return true
        val now = SystemClock.uptimeMillis()
        if (now - gateAtMs < GATE_TTL_MS) return gateValue
        val v = try {
            reader()
        } catch (t: Throwable) {
            true
        }
        gateValue = v
        gateAtMs = now
        return v
    }

    fun d(tag: String, msg: String) {
        if (gateOpen()) Log.d(tag, msg)
    }

    fun d(tag: String, msg: String, tr: Throwable?) {
        if (gateOpen()) Log.d(tag, msg, tr)
    }

    fun i(tag: String, msg: String) {
        if (gateOpen()) Log.i(tag, msg)
    }

    fun i(tag: String, msg: String, tr: Throwable?) {
        if (gateOpen()) Log.i(tag, msg, tr)
    }

    fun w(tag: String, msg: String) {
        if (gateOpen()) Log.w(tag, msg)
    }

    fun w(tag: String, msg: String, tr: Throwable?) {
        if (gateOpen()) Log.w(tag, msg, tr)
    }

    /** e 级恒写（开关关也保留错误证据）。 */
    fun e(tag: String, msg: String) {
        Log.e(tag, msg)
    }

    fun e(tag: String, msg: String, tr: Throwable?) {
        Log.e(tag, msg, tr)
    }
}
