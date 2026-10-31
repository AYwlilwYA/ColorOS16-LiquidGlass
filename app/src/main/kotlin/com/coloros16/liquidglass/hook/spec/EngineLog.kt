package com.coloros16.liquidglass.hook.spec

import android.content.SharedPreferences
import android.os.SystemClock
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.log.Lg

/**
 * 引擎全量日志门控：默认关 = 零输出；开 = 全量流水（绕过一切节流）进 logcat。
 * 格式 `[E|<specId或引擎名>] <event> k=v ...`（specId 恒在行内，防按 id grep 假阴）。
 * 500ms TTL 重读 Prefs，运行中开关热切换即生效。
 */
object EngineLog {

    private const val TAG = "LiquidGlass"
    private const val TTL_MS = 500L

    @Volatile
    private var prefsProvider: (() -> SharedPreferences?)? = null

    @Volatile
    private var cachedEnabled = false

    @Volatile
    private var cachedAtMs = 0L

    /** 注入 Prefs 读取器（install 期一次）。 */
    fun attach(provider: () -> SharedPreferences?) {
        prefsProvider = provider
        cachedAtMs = 0L
    }

    /** 全量日志开关（500ms TTL；读失败视为关）。 */
    fun fullLogEnabled(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - cachedAtMs < TTL_MS) return cachedEnabled
        cachedAtMs = now
        cachedEnabled = try {
            prefsProvider?.invoke()?.getBoolean(Prefs.KEY_ENGINE_FULL_LOG, Prefs.DEFAULT_ENGINE_FULL_LOG) ?: false
        } catch (t: Throwable) {
            false
        }
        return cachedEnabled
    }

    /** 全量流水一行（开关关 = 零输出）。 */
    fun log(source: String, event: String) {
        if (!fullLogEnabled()) return
        Lg.i(TAG, "[E|$source] $event")
    }
}
