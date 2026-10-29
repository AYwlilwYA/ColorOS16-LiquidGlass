package com.coloros16.liquidglass;
import com.coloros16.liquidglass.hook.ParticleDissolveHook;
import org.junit.Test;
import static org.junit.Assert.*;
public class ParticlePolicyTest {
 @Test public void normalSpeedLasts1100ms() { assertEquals(1100,ParticleDissolveHook.INSTANCE.durationMs(100)); }
 @Test public void slowAndFastAreBounded() { assertEquals(3142,ParticleDissolveHook.INSTANCE.durationMs(0));assertEquals(366,ParticleDissolveHook.INSTANCE.durationMs(10000)); }
 @Test public void increasingSpeedAlwaysReducesDuration() { long last=Long.MAX_VALUE;for(int s=35;s<=300;s++){long next=ParticleDissolveHook.INSTANCE.durationMs(s);assertTrue(next<=last);last=next;} }
 @Test public void disabledByDefaultHasIndependentKeys() { assertEquals("notification_dissolve_enabled",ParticleDissolveHook.KEY_ENABLED);assertNotEquals(ParticleDissolveHook.KEY_ENABLED,ParticleDissolveHook.KEY_SPEED);assertEquals(3,ParticleDissolveHook.MAX_ACTIVE); }
}
