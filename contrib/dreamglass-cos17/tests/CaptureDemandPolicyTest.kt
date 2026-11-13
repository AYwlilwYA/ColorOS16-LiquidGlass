package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.CaptureDemandPolicy as P
import org.junit.Assert.*
import org.junit.Test

class CaptureDemandPolicyTest {
    @Test fun ordinaryNavigationNeverCaptures() {
        repeat(1000) { assertFalse(P.allowed(true, false, false, false, false, false, false)) }
    }
    @Test fun onlyVisibleConsumerAdmitsCapture() {
        for (i in 0..4) {
            val visible = BooleanArray(5) { it == i }
            assertTrue(P.allowed(true, false, visible[0], visible[1], visible[2], visible[3], visible[4]))
        }
    }
    @Test fun dozeAndLockAlwaysRejectEvenStaleVisibleFlags() {
        for (interactive in listOf(false, true)) for (locked in listOf(false, true)) {
            if (!interactive || locked) assertFalse(P.allowed(interactive, locked, true, true, true, true, true))
        }
    }
    @Test fun eventModeDoesNotStartMonitoring() {
        assertFalse(P.continuous(false)); assertTrue(P.continuous(true))
    }
    @Test fun rapidNativeEventsCoalesceBeforeCapture() {
        assertTrue(P.due(1000, 0, false))
        for (now in 1001L..1099L) assertFalse(P.due(now, 1000, false))
        assertTrue(P.due(1100, 1000, false))
        assertFalse(P.due(1065, 1000, true)); assertTrue(P.due(1066, 1000, true))
    }
}
