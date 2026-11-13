package com.coloros16.liquidglass.hook

/** A two-job burst removes artificial spacing between first materials without an unbounded
 * GPU burst. Over time the whole worker still receives only one credit per 50 ms. */
internal class BackdropWorkBudget {
    private var creditMs=100L
    private var lastRefill=Long.MIN_VALUE

    fun waitMs(now:Long):Long {
        if(lastRefill==Long.MIN_VALUE)lastRefill=now
        val elapsed=(now-lastRefill).coerceAtLeast(0L)
        creditMs=(creditMs+elapsed.coerceAtMost(100L)).coerceAtMost(100L)
        lastRefill=maxOf(lastRefill,now)
        return (50L-creditMs).coerceAtLeast(0L)
    }

    fun started(now:Long) {
        check(waitMs(now)==0L)
        creditMs-=50L
    }
}
