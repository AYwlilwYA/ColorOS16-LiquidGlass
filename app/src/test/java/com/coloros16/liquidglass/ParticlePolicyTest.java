package com.coloros16.liquidglass;
import com.coloros16.liquidglass.hook.ParticleDissolveHook;
import com.coloros16.liquidglass.hook.NotificationClearAllContract;
import java.util.function.Consumer;
import org.junit.Test;
import static org.junit.Assert.*;
public class ParticlePolicyTest {
 static class StockClose implements Consumer<Object> {
  Object stack; boolean close; Consumer<Object> finish;
  public void accept(Object value) {}
 }
 static class ChangedClose implements Consumer<Object> {
  Object stack; boolean close,other; Consumer<Object> finish;
  public void accept(Object value) {}
 }
 @Test public void clearAllWaitTracksSpeedAndNeverExceedsNineHundredMs() {
  assertEquals(748,ParticleDissolveHook.INSTANCE.clearAllHoldMs(100,0));
  assertEquals(548,ParticleDissolveHook.INSTANCE.clearAllHoldMs(100,200));
  assertEquals(900,ParticleDissolveHook.INSTANCE.clearAllHoldMs(35,0));
  assertEquals(0,ParticleDissolveHook.INSTANCE.clearAllHoldMs(300,1000));
 }
 @Test public void clearAllCallbackRequiresConfirmedOwnerAndUnambiguousCloseFlag() {
  assertNotNull(NotificationClearAllContract.INSTANCE.resolve(StockClose.class,Object.class));
  assertNull(NotificationClearAllContract.INSTANCE.resolve(ChangedClose.class,Object.class));
  assertNull(NotificationClearAllContract.INSTANCE.resolve(StockClose.class,String.class));
 }
 @Test public void handoffStartsAtCurrentSwipeInsteadOfOriginalCardPosition() {
  assertEquals(350f,ParticleDissolveHook.INSTANCE.handoffX(50,0,300),.001f);
  assertEquals(350f,ParticleDissolveHook.INSTANCE.handoffX(150,100,300),.001f);
  assertEquals(-250f,ParticleDissolveHook.INSTANCE.handoffX(50,0,-300),.001f);
 }
 @Test public void acceptedSwipeStartsDissolvingImmediatelyAndStaysBounded() {
  assertTrue(ParticleDissolveHook.INSTANCE.handoffProgress(300,1000)>.14f);
  assertEquals(.32f,ParticleDissolveHook.INSTANCE.handoffProgress(10000,1000),.001f);
 }
 @Test public void normalSpeedLasts1100ms() { assertEquals(1100,ParticleDissolveHook.INSTANCE.durationMs(100)); }
 @Test public void slowAndFastAreBounded() { assertEquals(3142,ParticleDissolveHook.INSTANCE.durationMs(0));assertEquals(366,ParticleDissolveHook.INSTANCE.durationMs(10000)); }
 @Test public void increasingSpeedAlwaysReducesDuration() { long last=Long.MAX_VALUE;for(int s=35;s<=300;s++){long next=ParticleDissolveHook.INSTANCE.durationMs(s);assertTrue(next<=last);last=next;} }
 @Test public void disabledByDefaultHasIndependentKeys() { assertEquals("notification_dissolve_enabled",ParticleDissolveHook.KEY_ENABLED);assertNotEquals(ParticleDissolveHook.KEY_ENABLED,ParticleDissolveHook.KEY_SPEED);assertEquals(3,ParticleDissolveHook.MAX_ACTIVE); }
}
