/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

import java.util.WeakHashMap

/** Keep other consumers' last good frame while a newly revealed overlay waits
 * for its own scene. The identity is the captured buffer, never its geometry.
 * Weak keys do not extend a buffer's lifetime. */
internal class OverlayFrameEpochs {
    private val frames = WeakHashMap<Any, Long>()
    @Synchronized fun publish(buffer: Any, epoch: Long) { frames[buffer] = epoch }
    @Synchronized fun accepts(buffer: Any, requiredEpoch: Long): Boolean =
        requiredEpoch == Long.MIN_VALUE || (frames[buffer] ?: Long.MIN_VALUE) >= requiredEpoch

    /** A stale regional crop must not shadow a newer full-display capture. */
    fun <T : Any> select(region: T?, full: T?, requiredEpoch: Long, buffer: (T) -> Any): T? =
        region?.takeIf { accepts(buffer(it), requiredEpoch) }
            ?: full?.takeIf { accepts(buffer(it), requiredEpoch) }
}
