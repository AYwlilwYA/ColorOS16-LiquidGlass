package com.dreamglass.cos17
import android.app.Activity
import android.os.*
import android.graphics.*
import android.graphics.drawable.*
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.view.*
import android.widget.*
import android.util.TypedValue
import android.util.Log
import com.coloros16.liquidglass.hook.*
import java.io.File
import org.json.JSONObject

/** Debug-only GPU render and restore checks. All images are synthetic, never phone wallpaper. */
class KeyguardGlassProbeActivity:Activity() {
 private val ui=Handler(Looper.getMainLooper())
 private lateinit var root:FrameLayout
 private lateinit var label:TextView
 private val targets=ArrayList<Pair<View,Int>>()
 private val attachments=ArrayList<KeyguardGlassAttachment>()
 private var clicks=0
 private lateinit var texture:Bitmap
 private lateinit var before:Bitmap
 private val output by lazy { File(filesDir,"keyguard-probe").apply { mkdirs() } }
 private fun text(value:String,size:Float)=TextView(this).apply { text=value;setTextColor(Color.WHITE);setTextSize(TypedValue.COMPLEX_UNIT_PX,size);includeFontPadding=false }
 override fun onCreate(saved:Bundle?) {
  super.onCreate(saved);window.setDecorFitsSystemWindows(false)
  root=FrameLayout(this);setContentView(root)
  root.post { window.insetsController?.hide(WindowInsets.Type.systemBars());root.postDelayed({try { setup() } catch(t:Throwable){fail(t)}},250) }
 }
 private fun fail(t:Throwable) { Log.e("KeyguardGlassProbe","FAIL",t);if(::label.isInitialized)label.text="FAIL: $t" }
 private fun setup() {
  val w=root.width;val h=root.height
  val input=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);val c=Canvas(input);val p=Paint(Paint.ANTI_ALIAS_FLAG)
  p.shader=LinearGradient(0f,0f,w.toFloat(),h.toFloat(),intArrayOf(Color.rgb(7,16,29),Color.rgb(33,85,112),Color.rgb(99,75,126),Color.rgb(5,14,26)),null,Shader.TileMode.CLAMP);c.drawPaint(p);p.shader=null
  p.color=Color.rgb(41,74,91);c.drawCircle(w*.18f,h*.52f,w*.85f,p)
  p.color=Color.rgb(71,103,118);c.drawCircle(w*.18f,h*.52f,w*.82f,p)
  for(i in 0..35) { p.color=Color.argb(22,4,17,28);c.drawCircle((i*83%w).toFloat(),h*.33f+(i*97%(h/3)),(12+i%7*5).toFloat(),p) }
  root.background=BitmapDrawable(resources,input)
  fun add(v:View,x:Float,y:Float,width:Float,height:Float,role:Int) { root.addView(v,FrameLayout.LayoutParams(width.toInt(),height.toInt()).apply { leftMargin=x.toInt();topMargin=y.toInt() });targets.add(v to role) }
  val clock=FrameLayout(this).apply {
   addView(object:View(this@KeyguardGlassProbeActivity) {
    val glyphPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.WHITE;textSize=w*.36f;typeface=Typeface.create("sans-serif-condensed",Typeface.BOLD);textAlign=Paint.Align.CENTER }
    override fun onDraw(c:Canvas) { c.drawText("15",width*.5f,height*.44f,glyphPaint);c.drawText("36",width*.5f,height*.90f,glyphPaint) }
   },FrameLayout.LayoutParams(-1,-1))
  }
  add(clock,w*.03f,h*.13f,w*.62f,h*.55f,KeyguardGlassPolicy.TEXT)
  add(text("10月29日\n星期四\n九月二十",w*.058f),w*.07f,h*.70f,w*.55f,h*.15f,KeyguardGlassPolicy.TEXT)
  for((i,name) in listOf("3062\n步数","+\n选设备","☀\n20°","◷\n07:00").withIndex()) {
   val card=FrameLayout(this).apply { background=GradientDrawable().apply { setColor(Color.rgb(44,53,68));cornerRadius=w*.045f };setOnClickListener { clicks++ } }
   card.addView(text(name,w*.041f).apply { gravity=android.view.Gravity.CENTER },FrameLayout.LayoutParams(-1,-1))
   add(card,w*.75f,h*(.15f+i*.135f),w*.19f,w*.19f,KeyguardGlassPolicy.CARD)
  }
  for((i,name) in listOf("灯","相机").withIndex()) {
   val button=text(name,w*.040f).apply { gravity=android.view.Gravity.CENTER;background=GradientDrawable().apply { shape=GradientDrawable.OVAL;setColor(Color.rgb(43,52,66)) };setOnClickListener { clicks++ } }
   add(button,w*(if(i==0).07f else .79f),h*.90f,w*.14f,w*.14f,KeyguardGlassPolicy.SHORTCUT)
  }
  label=text("DreamGlass · synthetic lockscreen GPU test",w*.029f);root.addView(label,FrameLayout.LayoutParams(-1,70).apply { leftMargin=30;topMargin=25 })
  root.postDelayed({ try {
   before=render(root);save(before,"native.png")
   val clockView=targets[0].first
   var nativeClockPixels=0
   for(y in clockView.top until clockView.bottom)for(x in clockView.left until clockView.right) {
    val colour=before.getPixel(x,y)
    if(Color.red(colour)>220 && Color.green(colour)>220 && Color.blue(colour)>220)nativeClockPixels++
   }
   check(nativeClockPixels>20000) { "Native custom clock not visible: $nativeClockPixels" }
   Thread({ try { texture=HardwareBackdropBlur().use { it.render(input,w,h,6f) { false } };ui.post { try { applyAll() } catch(t:Throwable){fail(t)} } } catch(t:Throwable){ui.post {fail(t)}} },"Keyguard-GPU-test").start()
  } catch(t:Throwable){fail(t)} },250)
 }
 private fun render(v:View,effect:RenderEffect?=null):Bitmap {
  val w=v.width;val h=v.height
  val reader=ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,1,HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
  val renderer=HardwareRenderer();val node=RenderNode("Keyguard regression")
  try {
   renderer.isOpaque=false;renderer.setSurface(reader.surface);renderer.setContentRoot(node);node.setPosition(0,0,w,h);node.setRenderEffect(effect)
   val c=node.beginRecording(w,h);c.drawColor(Color.TRANSPARENT,BlendMode.CLEAR);v.draw(c);node.endRecording()
   renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
   val image=reader.acquireNextImage()!!
   try { val buffer=image.hardwareBuffer!!;try { return Bitmap.wrapHardwareBuffer(buffer,ColorSpace.get(ColorSpace.Named.SRGB))!!.copy(Bitmap.Config.ARGB_8888,false)!! } finally {buffer.close()} } finally {image.close()}
  } finally {renderer.destroy();reader.close();node.discardDisplayList()}
 }
 private fun save(b:Bitmap,name:String) { File(output,name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) } }
 private fun applyAll() {
  for((v,role) in targets) {
   val native=v.background;val pad=listOf(v.paddingLeft,v.paddingTop,v.paddingRight,v.paddingBottom)
   val a=KeyguardGlassAttachment(v,role);attachments.add(a)
   val m=Matrix();v.transformMatrixToGlobal(m);val values=FloatArray(9);m.getValues(values)
   a.apply(texture,values,root.width,root.height,1f,if(role==3)v.width*.5f else v.width*.24f)
   check(pad==listOf(v.paddingLeft,v.paddingTop,v.paddingRight,v.paddingBottom))
   if(role!=1)check(v.background!==native)
   if(role!=1)v.performClick()
  }
  check(clicks==6)
  root.invalidate()
  ui.postDelayed({try {
   val glass=render(root);save(glass,"glass.png")
   var white=0;var retained=0
   for((v,role) in targets)if(role!=1)for(y in v.top until v.bottom)for(x in v.left until v.right) {
    val old=before.getPixel(x,y);if(Color.red(old)>245 && Color.green(old)>245 && Color.blue(old)>245) {
     white++;val now=glass.getPixel(x,y);if(Color.red(now)>240 && Color.green(now)>240 && Color.blue(now)>240)retained++
    }
   }
   check(white>100 && retained.toFloat()/white>.995f) { "Widget foreground changed: $retained/$white" }
   val custom=targets[0].first
   val nativeMask=render(custom)
   val identity=RuntimeShader("uniform shader content; half4 main(float2 p) { return content.eval(p); }")
   val referenceMask=render(custom,RenderEffect.createRuntimeShaderEffect(identity,"content"))
   val glassMask=render(custom,attachments[0].compose(null))
   var customAlphaDelta=0;var customCoverage=0;var identityNativeDelta=0;var glassNativeDelta=0
   for(y in 0 until nativeMask.height)for(x in 0 until nativeMask.width) {
    val a=Color.alpha(nativeMask.getPixel(x,y));if(a>200)customCoverage++
    val reference=Color.alpha(referenceMask.getPixel(x,y));val actual=Color.alpha(glassMask.getPixel(x,y))
    identityNativeDelta=maxOf(identityNativeDelta,kotlin.math.abs(a-reference))
    glassNativeDelta=maxOf(glassNativeDelta,kotlin.math.abs(a-actual))
    customAlphaDelta=maxOf(customAlphaDelta,kotlin.math.abs(reference-actual))
   }
   check(customCoverage>20000 && customAlphaDelta<=1) { "Custom glyph coverage $customCoverage alpha delta $customAlphaDelta identity/native=$identityNativeDelta glass/native=$glassNativeDelta" }
   val report=glyphMask().put("nativeGlyphDrawSlot",glyphDrawSlot()).put("nativeParentComposition",parentComposition()).put("customClockGlyphPixels",customCoverage).put("customClockAlphaMaxDeltaVsIdentityEffect",customAlphaDelta).put("customClockIdentityVsNativeAlphaMaxDelta",identityNativeDelta).put("customClockGlassVsNativeAlphaMaxDelta",glassNativeDelta).put("customCanvasClockInPluginRoot",targets[0].first is FrameLayout).put("cardForegroundRetention",retained.toDouble()/white).put("clickCallbacksPreserved",clicks).put("paddingPreserved",true)
   attachments.forEach {it.restore()};root.invalidate()
   ui.postDelayed({try {
    val restored=render(root);save(restored,"restored.png")
    var delta=0
    for(y in 0 until root.height)for(x in 0 until root.width) { val a=before.getPixel(x,y);val b=restored.getPixel(x,y);delta=maxOf(delta,kotlin.math.abs(Color.red(a)-Color.red(b)),kotlin.math.abs(Color.green(a)-Color.green(b)),kotlin.math.abs(Color.blue(a)-Color.blue(b))) }
    check(delta<=1) {"Restore delta $delta"}
    report.put("nativeRestorationMaxDelta",delta).put("gpuWallpaper",texture.config==Bitmap.Config.HARDWARE).put("physicalDeviceTested",false)
    File(output,"summary.json").writeText(report.toString(2));Log.i("KeyguardGlassProbe","PASS "+report);label.text="PASS · GPU lockscreen glass / native restored"
   } catch(t:Throwable){fail(t)}},250)
  } catch(t:Throwable){fail(t)}},300)
 }
 private fun parentComposition():JSONObject {
  val parent=FrameLayout(this)
  val glyph=text("88",105f)
  parent.addView(glyph,FrameLayout.LayoutParams(220,140))
  parent.measure(View.MeasureSpec.makeMeasureSpec(220,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(140,View.MeasureSpec.EXACTLY))
  parent.layout(0,0,220,140)
  val nativeShader=RuntimeShader("""
   uniform shader content;
   half4 main(float2 p) { return half4(0.12,0.20,0.42,1.0)*content.eval(p).a; }
  """)
  val native=RenderEffect.createRuntimeShaderEffect(nativeShader,"content")
  val coords=floatArrayOf(1f,0f,100f,0f,1f,350f,0f,0f,1f)
  val leaf=KeyguardGlassMaterial(true)
  leaf.update(texture,coords,220f,140f,root.width,root.height,1f,0f)
  // Reproduce the failure: an opaque native parent recolours an already glass child.
  val reference=render(parent,native)
  val filteredChild=render(parent,RenderEffect.createChainEffect(native,leaf.effect!!))
  var lostDelta=0
  for(y in 0 until 140)for(x in 0 until 220) {
   val a=reference.getPixel(x,y);val b=filteredChild.getPixel(x,y)
   lostDelta=maxOf(lostDelta,kotlin.math.abs(Color.red(a)-Color.red(b)))
  }
  check(lostDelta<=1) { "Probe did not reproduce parent recolouring" }
  var applied:RenderEffect?=native
  val attachment=KeyguardGlassAttachment(parent,KeyguardGlassPolicy.TEXT,native,
   setEffect={_,effect->applied=effect},outerGlyph=true)
  attachment.apply(texture,coords,root.width,root.height,1f,0f)
  val glass=render(parent,applied)
  var alphaDelta=0;var glassPixels=0
  for(y in 0 until 140)for(x in 0 until 220) {
   val a=reference.getPixel(x,y);val b=glass.getPixel(x,y)
   alphaDelta=maxOf(alphaDelta,kotlin.math.abs(Color.alpha(a)-Color.alpha(b)))
   if(Color.alpha(a)>230 && kotlin.math.abs(Color.red(a)-Color.red(b))>20)glassPixels++
  }
  check(alphaDelta<=1 && glassPixels>500) { "Final-parent composition lost mask or glass: $alphaDelta/$glassPixels" }
  // A later native transition must become the restored effect, rather than a stale snapshot.
  val latest=RenderEffect.createOffsetEffect(0f,0f,native)
  attachment.originalEffect=latest;applied=attachment.compose(latest)
  attachment.restore();check(applied===latest && !attachment.applied)
  val restored=render(parent,applied);var restoreDelta=0
  for(y in 0 until 140)for(x in 0 until 220) {
   val a=reference.getPixel(x,y);val b=restored.getPixel(x,y)
   restoreDelta=maxOf(restoreDelta,kotlin.math.abs(Color.red(a)-Color.red(b)),kotlin.math.abs(Color.alpha(a)-Color.alpha(b)))
  }
  check(restoreDelta<=1)
  save(reference,"native-parent-recolour.png");save(glass,"final-parent-glass.png")
  return JSONObject().put("childGlassOverwrittenMaxDelta",lostDelta).put("finalGlassPixels",glassPixels)
   .put("nativeAlphaMaxDelta",alphaDelta).put("latestNativeEffectRestored",true).put("restoreMaxDelta",restoreDelta)
 }
 private fun glyphDrawSlot():JSONObject {
  val glyph=text("88",105f).apply { layoutParams=FrameLayout.LayoutParams(220,140);layout(0,0,220,140) }
  val face=glyph.typeface
  val material=KeyguardGlassMaterial(true)
  material.update(texture,floatArrayOf(1f,0f,100f,0f,1f,350f,0f,0f,1f),220f,140f,root.width,root.height,1f,0f)
  val slot=KeyguardGlyphDraw.Slot().apply { effect=material.effect }
  var result:Any?=null
  val wrapper=object:View(this) {
   override fun onDraw(c:Canvas) {
    result=slot.draw(c,width,height) { nativeCanvas ->
     check(!slot.available()) { "Nested recording not guarded" }
     glyph.draw(nativeCanvas);"native-return"
    }
   }
  }.apply { layout(0,0,220,140) }
  val native=render(glyph);val glass=render(wrapper)
  check(slot.nativeDraws==1 && result=="native-return")
  var alphaDelta=0;var colourChanged=0
  for(y in 0 until 140)for(x in 0 until 220) {
   val a=native.getPixel(x,y);val b=glass.getPixel(x,y)
   alphaDelta=maxOf(alphaDelta,kotlin.math.abs(Color.alpha(a)-Color.alpha(b)))
   if(Color.alpha(a)>230 && kotlin.math.abs(Color.red(a)-Color.red(b))>20)colourChanged++
  }
  check(alphaDelta<=4 && colourChanged>500) { "Native draw node changed mask or missed glass: $alphaDelta/$colourChanged" }
  glyph.text="11"
  val changed=render(wrapper);val nextNative=render(glyph);var maskChanged=0
  check(slot.nativeDraws==2)
  for(y in 0 until 140)for(x in 0 until 220)
   if(kotlin.math.abs(Color.alpha(changed.getPixel(x,y))-Color.alpha(glass.getPixel(x,y)))>50)maskChanged++
  check(maskChanged>500) { "Digit content cached after a minute change" }
  slot.effect=null
  val restored=render(wrapper);var restoredDelta=0
  for(y in 0 until 140)for(x in 0 until 220) {
   val a=restored.getPixel(x,y);val b=nextNative.getPixel(x,y)
   restoredDelta=maxOf(restoredDelta,kotlin.math.abs(Color.alpha(a)-Color.alpha(b)),kotlin.math.abs(Color.red(a)-Color.red(b)))
  }
  check(restoredDelta<=1 && slot.nativeDraws==3 && glyph.typeface===face)
  slot.close();check(!slot.available())
  save(glass,"native-glyph-slot.png")
  return JSONObject().put("nativeDrawCalledOncePerFrame",true).put("maskAlphaMaxDelta",alphaDelta)
   .put("glassPixels",colourChanged).put("changedDigitMaskPixels",maskChanged)
   .put("noEffectRestorationMaxDelta",restoredDelta).put("nativeFontAndReturnPreserved",true)
 }
 private fun glyphMask():JSONObject {
  val v=object:TextView(this) { override fun onDraw(c:Canvas) { paint.shader=null;super.onDraw(c) } }
  v.text="15:36";v.setTextColor(Color.WHITE);v.setTextSize(TypedValue.COMPLEX_UNIT_PX,140f);v.includeFontPadding=false
  v.measure(View.MeasureSpec.makeMeasureSpec(550,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(190,View.MeasureSpec.EXACTLY));v.layout(0,0,550,190)
  val native=render(v)
  val material=KeyguardGlassMaterial(true);val m=floatArrayOf(1f,0f,70f,0f,1f,200f,0f,0f,1f)
  material.update(texture,m,550f,190f,root.width,root.height,1f,0f)
  val changed=render(v,material.effect);save(changed,"glyph.png")
  var maxAlpha=0;var changedRgb=0
  for(y in 0 until v.height)for(x in 0 until v.width) { val a=native.getPixel(x,y);val b=changed.getPixel(x,y);maxAlpha=maxOf(maxAlpha,kotlin.math.abs(Color.alpha(a)-Color.alpha(b)));if(Color.alpha(a)>240 && kotlin.math.abs(Color.red(a)-Color.red(b))>20)changedRgb++ }
  check(maxAlpha<=1 && changedRgb>1000) {"Glyph alpha/RGB $maxAlpha / $changedRgb"}
  val red=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
  material.update(red,m,550f,190f,root.width,root.height,1f,0f)
  val redGlyph=render(v,material.effect)
  var sourceSensitive=0
  for(y in 0 until v.height)for(x in 0 until v.width)if(Color.alpha(native.getPixel(x,y))>240 &&
      Color.red(redGlyph.getPixel(x,y))>200 && kotlin.math.abs(Color.red(redGlyph.getPixel(x,y))-Color.red(changed.getPixel(x,y)))>60)sourceSensitive++
  check(sourceSensitive>1000) { "Wallpaper did not reach glyph shader: $sourceSensitive" }
  material.update(texture,m,550f,190f,root.width,root.height,0f,0f)
  val flat=render(v,material.effect);var bevel=0
  for(y in 0 until v.height)for(x in 0 until v.width)if(kotlin.math.abs(Color.red(flat.getPixel(x,y))-Color.red(changed.getPixel(x,y)))>5)bevel++
  check(bevel>100) { "Glyph rim missing: $bevel" }
  material.update(texture,m,550f,190f,root.width,root.height,1f,0f)

  val matrix=ColorMatrix(floatArrayOf(1f,0f,0f,0f,0f,0f,1f,0f,0f,0f,0f,0f,1f,0f,0f,0f,0f,0f,.5f,0f))
  val prior=RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(matrix))
  val composed=render(v,RenderEffect.createChainEffect(material.effect!!,prior));var maxComposed=0
  for(y in 0 until v.height)for(x in 0 until v.width)maxComposed=maxOf(maxComposed,kotlin.math.abs(Color.alpha(composed.getPixel(x,y))-(Color.alpha(native.getPixel(x,y))*.5f).toInt()))
  check(maxComposed<=1)
  val broken=object:FrameLayout(this) {
   override fun setBackgroundDrawable(bg:Drawable?) {
    super.setBackgroundDrawable(bg)
    if(bg is KeyguardGlassMaterial.Background)throw IllegalStateException("injected partial vendor assignment")
   }
  }
  val original=ColorDrawable(Color.DKGRAY);broken.background=original;broken.layout(0,0,100,100)
  val partial=KeyguardGlassAttachment(broken,KeyguardGlassPolicy.CARD)
  var rejected=false
  try { partial.apply(texture,m,root.width,root.height,1f,20f) } catch(_:IllegalStateException) { rejected=true }
  check(rejected && !partial.applied && broken.background===original)
  return JSONObject().put("partialVendorFailureRestored",true).put("wallpaperSensitiveGlyphPixels",sourceSensitive).put("bevelPixelsChanged",bevel).put("nativeGlyphAlphaMaxDelta",maxAlpha).put("glassPixelsChanged",changedRgb).put("nativeEffectAlphaPreserved",maxComposed<=1)
 }
}
