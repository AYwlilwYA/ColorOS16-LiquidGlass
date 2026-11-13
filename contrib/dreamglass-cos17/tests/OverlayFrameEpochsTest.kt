package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.OverlayFrameEpochs
import org.junit.Assert.*
import org.junit.Test

class OverlayFrameEpochsTest {
    @Test fun staleRegionalFrameDoesNotShadowFreshFullFrame() {
        val frames=OverlayFrameEpochs();val region=Any();val full=Any()
        frames.publish(region,40);frames.publish(full,41)
        assertSame(full,frames.select(region,full,41){it})
    }

    @Test fun noCurrentFrameUsesNativeUntilFreshCaptureArrives() {
        val frames=OverlayFrameEpochs();val region=Any();val full=Any()
        frames.publish(region,40);frames.publish(full,40)
        assertNull(frames.select(region,full,41){it})
        frames.publish(full,41)
        assertSame(full,frames.select(region,full,41){it})
    }

    @Test fun currentRegionPreferredAndOtherConsumerKeepsItsFrame() {
        val frames=OverlayFrameEpochs();val region=Any();val full=Any()
        frames.publish(region,41);frames.publish(full,42)
        assertSame(region,frames.select(region,full,41){it})
        assertSame(full,frames.select(region,full,42){it})
    }

    @Test fun frozenModeSelectsExplicitRegionWithoutEpoch() {
        val region=Any()
        assertSame(region,OverlayFrameEpochs().select(region,Any(),Long.MIN_VALUE){it})
    }
    @Test fun newCloudCannotShowPreviousAppsBuffer() {
        val frames = OverlayFrameEpochs(); val previous = Any(); val fresh = Any()
        frames.publish(previous, 10)
        assertFalse(frames.accepts(previous, 11))
        assertFalse(frames.accepts(fresh, 11))
        frames.publish(fresh, 11)
        assertTrue(frames.accepts(fresh, 11))
        assertFalse(frames.accepts(previous, 11))
        // A different, already visible consumer can still finish its old frame.
        assertTrue(frames.accepts(previous, 10))
    }

    @Test fun regionAndFullScreenBuffersMustEachBelongToCurrentScene() {
        val frames = OverlayFrameEpochs(); val region = Any(); val full = Any()
        frames.publish(full, 20); frames.publish(region, 21)
        assertTrue(frames.accepts(region, 21)); assertFalse(frames.accepts(full, 21))
        frames.publish(full, 22)
        assertTrue(frames.accepts(full, 21))
    }

    @Test fun staticModeAndNonOverlayConsumersCanKeepExplicitFrozenSource() {
        assertTrue(OverlayFrameEpochs().accepts(Any(), Long.MIN_VALUE))
    }
}
