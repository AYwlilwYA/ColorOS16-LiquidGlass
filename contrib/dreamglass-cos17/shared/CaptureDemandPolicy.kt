package com.coloros16.liquidglass.hook

/** Capture admission is based on visible consumers, never foreground app/page changes. */
object CaptureDemandPolicy {
    fun allowed(interactive: Boolean, locked: Boolean, shade: Boolean, headsUp: Boolean,
                cloud: Boolean, banner: Boolean, menu: Boolean): Boolean =
        interactive && !locked && (shade || headsUp || cloud || banner || menu)

    fun continuous(immediate: Boolean): Boolean = immediate
    fun minimumIntervalMs(immediate: Boolean): Long = if (immediate) 66L else 100L
    fun due(now: Long, previous: Long, immediate: Boolean): Boolean =
        previous == 0L || now - previous >= minimumIntervalMs(immediate)

    /** One admission deadline for the serial capture lane, rather than two independent gates. */
    fun waitMs(now: Long, previous: Long, interval: Long): Long =
        if(previous == 0L)0L else (interval-(now-previous).coerceAtLeast(0L)).coerceAtLeast(0L)
}
