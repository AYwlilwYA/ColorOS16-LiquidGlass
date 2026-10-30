package com.coloros16.liquidglass;

import android.view.View;
import com.coloros16.liquidglass.hook.NotificationMotionContract;
import org.junit.Test;
import static org.junit.Assert.*;

public class ParticleMotionContractTest {
    // Signatures from supplied stock SystemUI 17.99.02; no Android View is instantiated.
    public static class Stock17Swipe {
        public void onDownUpdate(View v) {}
        public void dismiss(View v, float velocity) {}
        public void snapChild(float x, float velocity, View v) {}
        public void onChildSnappedBack(float x, View v) {}
        public void snapOpen(View v, int slot, float velocity) {}
        public void forceResetSwipeState(View v) {}
        public void onSwipeOutAnimStart(View v) {}
        public void onChildDismissed(View v) {}
        public void onChildNotDismissed(View v, boolean cancelled, boolean removed) {}
        public void dismiss(String unrelated) {}
        public void onTouchEvent(Object event) {}
    }
    public static class LegacySwipe {
        public void snapChild(View v, float x, float velocity) {}
        public void dismissChild(View v, float velocity, boolean animate) {}
    }
    public abstract static class AbstractSwipe {
        public abstract void onChildSnappedBack(float x, View v);
        public void dismissChild(View v, float velocity, boolean animate) {}
    }
    @Test public void abstractHookTargetDoesNotAbortOtherParticleRoutes() {
        var routes=NotificationMotionContract.INSTANCE.routes(AbstractSwipe.class);
        assertEquals(1,routes.size());assertEquals("dismissChild",routes.get(0).getMethod().getName());
    }
    @Test public void stock17RoutesFindReorderedViewAndIgnoreUnrelatedMethods() {
        var routes=NotificationMotionContract.INSTANCE.routes(Stock17Swipe.class);
        assertEquals(8,routes.size());
        var snap=routes.stream().filter(r -> r.getMethod().getName().equals("snapChild")).findFirst().get();
        assertEquals(NotificationMotionContract.Event.ACCEPT,routes.stream().filter(r -> r.getMethod().getName().equals("onSwipeOutAnimStart")).findFirst().get().getEvent());
        assertEquals(NotificationMotionContract.Event.ABORT,routes.stream().filter(r -> r.getMethod().getName().equals("onChildNotDismissed")).findFirst().get().getEvent());
        assertEquals(2,snap.getIndex());assertEquals(NotificationMotionContract.Event.CANCEL,snap.getEvent());
        assertEquals(NotificationMotionContract.Event.PREPARE,routes.stream().filter(r -> r.getMethod().getName().equals("onDownUpdate")).findFirst().get().getEvent());
        assertEquals(NotificationMotionContract.Event.REQUEST,routes.stream().filter(r -> r.getMethod().getName().equals("dismiss")).findFirst().get().getEvent());
    }
    @Test public void legacyAndSubclassDeclarationsRemainSupported() {
        var routes=NotificationMotionContract.INSTANCE.routes(LegacySwipe.class);
        assertEquals(2,routes.size());assertTrue(routes.stream().allMatch(r -> r.getIndex()==0));
    }
}
