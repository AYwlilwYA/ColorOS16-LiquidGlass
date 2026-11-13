package com.coloros16.liquidglass.hook

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Only for module-owned work: never wraps or retries the system's original draw callback. */
object RuntimeWorkPolicy {
    const val BLUR_INTERVAL_MS = 100L
    fun captureIntervalMs(hz: Int, moving: Boolean, immediate: Boolean): Long {
        val requested = hz.coerceIn(1, 120)
        val effective = if (immediate) {
            if (moving) requested else (requested / 2).coerceAtLeast(1)
        } else minOf(requested, if (moving) 30 else 12)
        return (1000L / effective).coerceAtLeast(4L)
    }
    fun failureDelayMs(error: Throwable): Long = if (error is OutOfMemoryError) 30_000L else 2_000L

    /** Callback/logging failures must not escape an injected Handler or background thread. */
    fun guarded(onFailure: (Throwable) -> Unit, action: () -> Unit): Boolean = try {
        action(); true
    } catch (error: Throwable) {
        try { onFailure(error) } catch (_: Throwable) { }
        false
    }

    fun executor(name: String): ThreadPoolExecutor = ThreadPoolExecutor(
        1, 1, 15L, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>(1),
        { task -> Thread(task, name).apply { isDaemon = true } }
    ).apply { allowCoreThreadTimeOut(true) }
}
