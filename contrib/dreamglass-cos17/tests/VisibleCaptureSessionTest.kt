package com.coloros16.liquidglass
import com.coloros16.liquidglass.hook.VisibleCaptureSession
import org.junit.Assert.*
import org.junit.Test
class VisibleCaptureSessionTest {
    @Test fun oneWorkerPerVisibleHost() {
        val session=VisibleCaptureSession();val host=Any()
        val token=session.begin(host)!!
        assertNull(session.begin(host));assertTrue(session.current(token))
    }
    @Test fun oldWorkerCannotClearReplacement() {
        val session=VisibleCaptureSession();val first=Any();val next=Any()
        val old=session.begin(first)!!;val current=session.begin(next)!!
        assertFalse(session.current(old));assertFalse(session.finish(old));assertTrue(session.current(current))
    }
    @Test fun sameNativeHostRevealStartsFreshSessionAfterHide() {
        val session=VisibleCaptureSession();val host=Any();val old=session.begin(host)!!
        assertTrue(session.finish(old));val fresh=session.begin(host)!!
        assertNotEquals(old,fresh);assertFalse(session.current(old));assertTrue(session.current(fresh))
    }
}
