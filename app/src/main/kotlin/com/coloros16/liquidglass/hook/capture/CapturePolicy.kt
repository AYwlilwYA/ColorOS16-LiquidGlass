package com.coloros16.liquidglass.hook.capture

import android.os.SystemClock
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.log.Lg
import io.github.libxposed.api.XposedInterface

/**
 * 抓屏参数读取收拢（api 在 install 时缓存）：模式 / 速率 / 降采样 / 周期 / 重试阈值。
 * 远程 Prefs 时间节流缓存（≥CAPTURE_PREFS_CACHE_MS 才重读，热路径零 IPC），读 Prefs 照旧。
 */
object CapturePolicy {

    private const val TAG = "LiquidGlass"
    private const val CAPTURE_PREFS_CACHE_MS = 1000L

    private var api: XposedInterface? = null
    private var motionActive: () -> Boolean = { false }

    private var cachedBgCaptureScale = Prefs.DEFAULT_BG_CAPTURE_SCALE
    private var cachedBgCaptureScaleTimeMs = 0L
    private var cachedPanelCaptureHz = Prefs.DEFAULT_PANEL_CAPTURE_HZ
    private var cachedPanelCaptureHzTimeMs = 0L
    private var cachedBgCaptureMinIntervalMs = Prefs.DEFAULT_BG_CAPTURE_MIN_INTERVAL_MS
    private var cachedBgCaptureMinIntervalMsTimeMs = 0L
    private var cachedLegacyPeriodicEnabled = Prefs.DEFAULT_BG_PERIODIC_CAPTURE_ENABLE
    private var cachedLegacyPeriodicEnabledTimeMs = 0L
    private var cachedPeriodicCaptureIntervalMs = Prefs.DEFAULT_BG_PERIODIC_CAPTURE_INTERVAL_MS
    private var cachedPeriodicCaptureIntervalTimeMs = 0L

    fun attach(api: XposedInterface, motionActive: () -> Boolean) {
        this.api = api
        this.motionActive = motionActive
    }

    /** 抓屏模式：0=即时 / 1=变化 / 2=静态。≤1s TTL 缓存，读失败/越界回默认 1（变化）。 */
    fun captureMode(): Int = Prefs.captureMode(api)

    /** 即时抓屏速率 Hz（KEY_PANEL_CAPTURE_HZ，默认 60，clamp 30~120）。
     *  存量越界值读取时夹到 30~120。 */
    fun panelCaptureHz(): Int {
        val now = SystemClock.uptimeMillis()
        if (now - cachedPanelCaptureHzTimeMs < CAPTURE_PREFS_CACHE_MS) return cachedPanelCaptureHz
        val a = api ?: return Prefs.DEFAULT_PANEL_CAPTURE_HZ
        val v = try {
            Prefs.readIntCompat(Prefs.read(a), Prefs.KEY_PANEL_CAPTURE_HZ, Prefs.DEFAULT_PANEL_CAPTURE_HZ)
                .coerceIn(Prefs.PANEL_CAPTURE_HZ_MIN, Prefs.PANEL_CAPTURE_HZ_MAX)
        } catch (t: Throwable) {
            Lg.w(TAG, "prefs read panel_capture_hz failed, default ${Prefs.DEFAULT_PANEL_CAPTURE_HZ}", t)
            Prefs.DEFAULT_PANEL_CAPTURE_HZ
        }
        cachedPanelCaptureHz = v
        cachedPanelCaptureHzTimeMs = now
        return v
    }

    /** 持续抓屏 tick 间隔毫秒：运动时满速 [panelCaptureHz]、静止时减半。
     *  间隔 = 1000/有效Hz ms，clamp 4~1000ms（防过快打爆 SF / 过慢背景滞后）。 */
    fun continuousCaptureIntervalMs(): Long {
        val hz = panelCaptureHz()
        // 运动时满速、静止时减半——motionActive 为运动标志（面板动画/内容变化置位，复位延迟）
        val effectiveHz = if (motionActive()) hz else hz / 2
        val clamped = effectiveHz.coerceIn(1, Prefs.PANEL_CAPTURE_HZ_MAX)
        return (1000.0 / clamped).toLong().coerceIn(4L, 1000L)
    }

    /** 抓屏降采样比例（KEY_BG_CAPTURE_SCALE，clamp 0.25~1.0，默认 0.5f）。 */
    fun bgCaptureScale(): Float {
        val now = SystemClock.uptimeMillis()
        if (now - cachedBgCaptureScaleTimeMs < CAPTURE_PREFS_CACHE_MS) return cachedBgCaptureScale
        val a = api ?: return Prefs.DEFAULT_BG_CAPTURE_SCALE
        val v = try {
            Prefs.read(a).getFloat(Prefs.KEY_BG_CAPTURE_SCALE, Prefs.DEFAULT_BG_CAPTURE_SCALE)
                .coerceIn(0.25f, 1.0f)
        } catch (t: Throwable) {
            Lg.w(TAG, "prefs read bg_capture_scale failed, default 0.5", t)
            Prefs.DEFAULT_BG_CAPTURE_SCALE
        }
        cachedBgCaptureScale = v
        cachedBgCaptureScaleTimeMs = now
        return v
    }

    /** 周期抓屏是否启用：静态模式恒关；变化模式兼容存量「持续抓屏」开关；即时模式恒开。 */
    fun isPeriodicCaptureEnabled(): Boolean {
        val mode = captureMode()
        if (mode == Prefs.BG_CAPTURE_MODE_STATIC) return false
        if (mode == Prefs.BG_CAPTURE_MODE_ON_CHANGE && !legacyPeriodicCaptureEnabled()) return false
        return true
    }

    /** 存量「持续抓屏」开关（KEY_BG_PERIODIC_CAPTURE_ENABLE，默认 true）。 */
    private fun legacyPeriodicCaptureEnabled(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - cachedLegacyPeriodicEnabledTimeMs < CAPTURE_PREFS_CACHE_MS) return cachedLegacyPeriodicEnabled
        val a = api ?: return Prefs.DEFAULT_BG_PERIODIC_CAPTURE_ENABLE
        val v = try {
            Prefs.read(a).getBoolean(Prefs.KEY_BG_PERIODIC_CAPTURE_ENABLE, Prefs.DEFAULT_BG_PERIODIC_CAPTURE_ENABLE)
        } catch (t: Throwable) {
            Lg.w(TAG, "prefs read bg_periodic_capture_enable failed, default true", t)
            Prefs.DEFAULT_BG_PERIODIC_CAPTURE_ENABLE
        }
        cachedLegacyPeriodicEnabled = v
        cachedLegacyPeriodicEnabledTimeMs = now
        return v
    }

    /** 存量变化节流间隔 ms（KEY_BG_CAPTURE_MIN_INTERVAL_MS，默认 0=不节流）。 */
    fun bgCaptureMinIntervalMs(): Int {
        val now = SystemClock.uptimeMillis()
        if (now - cachedBgCaptureMinIntervalMsTimeMs < CAPTURE_PREFS_CACHE_MS) return cachedBgCaptureMinIntervalMs
        val a = api ?: return Prefs.DEFAULT_BG_CAPTURE_MIN_INTERVAL_MS
        val v = try {
            Prefs.readIntCompat(
                Prefs.read(a), Prefs.KEY_BG_CAPTURE_MIN_INTERVAL_MS, Prefs.DEFAULT_BG_CAPTURE_MIN_INTERVAL_MS
            ).coerceAtLeast(0)
        } catch (t: Throwable) {
            Lg.w(TAG, "prefs read bg_capture_min_interval_ms failed, default 0", t)
            Prefs.DEFAULT_BG_CAPTURE_MIN_INTERVAL_MS
        }
        cachedBgCaptureMinIntervalMs = v
        cachedBgCaptureMinIntervalMsTimeMs = now
        return v
    }

    /** 周期兜底抓屏间隔（KEY_BG_PERIODIC_CAPTURE_INTERVAL_MS，clamp 100~5000，默认 500）。
     *  仅收起时（shade 不在渲染）生效——展开由面板抓屏覆盖。 */
    fun periodicCaptureIntervalMs(): Int {
        val now = SystemClock.uptimeMillis()
        if (now - cachedPeriodicCaptureIntervalTimeMs < CAPTURE_PREFS_CACHE_MS) return cachedPeriodicCaptureIntervalMs
        val a = api ?: return Prefs.DEFAULT_BG_PERIODIC_CAPTURE_INTERVAL_MS
        val v = try {
            Prefs.readIntCompat(
                Prefs.read(a), Prefs.KEY_BG_PERIODIC_CAPTURE_INTERVAL_MS, Prefs.DEFAULT_BG_PERIODIC_CAPTURE_INTERVAL_MS
            ).coerceIn(Prefs.BG_PERIODIC_CAPTURE_INTERVAL_MIN_MS, Prefs.BG_PERIODIC_CAPTURE_INTERVAL_MAX_MS)
        } catch (t: Throwable) {
            Lg.w(TAG, "prefs read bg_periodic_capture_interval_ms failed, default 500", t)
            Prefs.DEFAULT_BG_PERIODIC_CAPTURE_INTERVAL_MS
        }
        cachedPeriodicCaptureIntervalMs = v
        cachedPeriodicCaptureIntervalTimeMs = now
        return v
    }

    /** 抓屏失败重试开关（KEY_BG_CAPTURE_RETRY_ENABLED，默认 false=失败等下次事件）。 */
    fun bgCaptureRetryEnabled(): Boolean = Prefs.captureRetryEnabled(api)

    /** 区域级抓屏实验开关（KEY_BG_REGION_CAPTURE_EXPERIMENTAL，默认 false）。 */
    fun regionCaptureExperimentalEnabled(): Boolean = Prefs.regionCaptureExperimental(api)

    /** 存量重试间隔 ms（KEY_BG_CAPTURE_RETRY_MS，默认 320，clamp 100~1000）。 */
    fun bgCaptureRetryMs(): Int {
        val a = api ?: return Prefs.DEFAULT_BG_CAPTURE_RETRY_MS
        return try {
            Prefs.readIntCompat(Prefs.read(a), Prefs.KEY_BG_CAPTURE_RETRY_MS, Prefs.DEFAULT_BG_CAPTURE_RETRY_MS)
                .coerceIn(100, 1000)
        } catch (t: Throwable) {
            Prefs.DEFAULT_BG_CAPTURE_RETRY_MS
        }
    }

    /** 存量重试次数（KEY_BG_CAPTURE_RETRY_LIMIT，默认 3，clamp 1~5）。 */
    fun bgCaptureRetryLimit(): Int {
        val a = api ?: return Prefs.DEFAULT_BG_CAPTURE_RETRY_LIMIT
        return try {
            Prefs.readIntCompat(Prefs.read(a), Prefs.KEY_BG_CAPTURE_RETRY_LIMIT, Prefs.DEFAULT_BG_CAPTURE_RETRY_LIMIT)
                .coerceIn(1, 5)
        } catch (t: Throwable) {
            Prefs.DEFAULT_BG_CAPTURE_RETRY_LIMIT
        }
    }
}
