package com.dreamglass.cos17;
import com.coloros16.liquidglass.hook.KeyguardGlassPolicy;
import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;
public class KeyguardGlassPolicyTest {
 private int role(String parent,String id,boolean text,boolean bg,boolean click,float aspect) {
  return KeyguardGlassPolicy.INSTANCE.role(Arrays.asList(parent),id,text,bg,click,aspect);
 }
 @Test public void realCos17ClockBasePackageIncludesUi() { assertEquals(1,role("com.oplus.keyguard.ui.OplusKeyguardStyleClock","",true,false,false,1)); }
 @Test public void customClockAndDateAreBothIncluded() { for(String id:new String[]{"hour","date","weekday","lunar_date"})assertEquals(1,role("com.oplus.systemui.keyguard.view.CustomOplusKeyguardStyleClock",id,true,false,false,1)); }
 @Test public void squareWidgetCardsIncludedWithoutTouchingWholeColumn() {
  assertEquals(2,role("com.oplus.keyguard.widget.WidgetCard","weather",false,true,true,1));
  assertEquals(0,role("com.oplus.keyguard.widget.WidgetColumn","widget_column",false,true,false,.2f));
 }
 @Test public void flashlightAndCameraOnlyUseExplicitButtons() { for(String id:new String[]{"start_button","end_button","flashlight_button","camera_button"})assertEquals(3,role("com.oplus.systemui.keyguard.ui.view.OplusKeyguardBottomAreaView",id,false,true,true,1)); }
 @Test public void nativeAffordanceBackgroundIsSeparateFromIcon() {
  assertEquals(3,role("com.android.systemui.keyguard.ui.view.KeyguardRootView","affordance_background_circle",false,false,false,1));
  assertEquals(0,role("com.android.systemui.keyguard.ui.view.KeyguardRootView","affordance_foreground_icon",false,false,false,1));
 }
 @Test public void pluginBottomSlotsAreIncluded() {
  for(String id:new String[]{"BOTTOM_START","BOTTOM_END"})assertEquals(3,role("com.oplus.keyguard.ui.base.view.BottomAffordanceContainer",id,false,false,true,1));
 }
 @Test public void themeWrapperKeepsNativeShortcutRoleWithoutOriginalId() {
  assertEquals(3,KeyguardGlassPolicy.INSTANCE.role(Arrays.asList("theme.MyCustomizedImageView",
      "com.oplus.keyguard.ui.base.widget.OplusLaunchableImageView"),"",false,false,true,1));
 }
 @Test public void pluginClockRootDoesNotIncludeSharedKeyguardHost() {
  assertTrue(KeyguardGlassPolicy.INSTANCE.isClockGlyphRoot("com.oplus.keyguard.clock.base.ui.view.ClockViewRoot"));
  assertFalse(KeyguardGlassPolicy.INSTANCE.isClockGlyphRoot("com.oplus.keyguard.ui.OplusKeyguardStyleClock"));
 }
 @Test public void statusBarClockDoesNotCountAsLockscreenClockSuccess() {
  assertEquals(0,role("com.oplus.systemui.keyguard.view.OplusKeyguardStatusBarClock","clock",true,false,false,1));
 }
 @Test public void authenticationAndAodNeverReceiveMaterials() { for(String parent:new String[]{"com.oplus.systemui.aod.aodclock.Clock","com.oplus.keyguard.security.NumericKeyboardWidget","com.android.keyguard.bouncer.BouncerView"})assertEquals(0,role(parent,"clock",true,true,true,1)); }
 @Test public void fingerprintAndNotificationsStayNative() { assertEquals(0,role("com.android.keyguard.KeyguardRootView","fingerprint",false,true,true,1));assertEquals(0,role("com.android.systemui.statusbar.notification.row.ExpandableNotificationRow","date",true,false,false,1)); }
 @Test public void genericWidgetsOutsideKeyguardStayNative() { assertEquals(0,role("com.android.launcher.WidgetCard","widget",false,true,true,1)); }
 @Test public void coverMappingNeverStretchesImage() { float[] m=KeyguardGlassPolicy.INSTANCE.mapping(2400,3200,1440,3200);assertArrayEquals(new float[]{1,480,0},m,.0001f); }
 @Test public void portraitCropIsCentered() { float[] m=KeyguardGlassPolicy.INSTANCE.mapping(1000,2000,1000,1000);assertArrayEquals(new float[]{1,0,500},m,.0001f); }
}
