package com.coloros16.liquidglass.particles
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.*
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.util.Log
import com.coloros16.liquidglass.hook.ParticleDissolveHook
import java.io.File
import org.json.JSONObject

/** Synthetic images only; readback is restricted to this debug test, never production. */
class ParticleProbeActivity:Activity() {
 private val ui=Handler(Looper.getMainLooper())
 private lateinit var label:TextView
 private lateinit var card:View
 private fun fail(error:Throwable) { Log.e("ParticleProbe","FAIL",error);label.text="FAIL: $error" }
 override fun onCreate(state:Bundle?) {
  super.onCreate(state)
  val box=FrameLayout(this).apply { setBackgroundColor(Color.rgb(12,20,28)) }
  label=TextView(this).apply { text="GPU particle regression…";setTextColor(Color.WHITE);textSize=18f;setPadding(25,30,25,0) }
  card=object:View(this) {
   val p=Paint(Paint.ANTI_ALIAS_FLAG)
   override fun onDraw(c:Canvas) {
    p.shader=LinearGradient(0f,0f,width.toFloat(),height.toFloat(),Color.rgb(54,185,162),Color.rgb(40,69,144),Shader.TileMode.CLAMP)
    c.drawRoundRect(0f,0f,width.toFloat(),height.toFloat(),30f,30f,p);p.shader=null
    p.color=Color.WHITE;p.textSize=27f;c.drawText("DreamGlass",30f,55f,p);p.textSize=20f;c.drawText("Notification deleted",30f,95f,p)
   }
  }
  box.addView(card,FrameLayout.LayoutParams(640,260).apply { leftMargin=60;topMargin=300 })
  box.addView(label,FrameLayout.LayoutParams(-1,220));setContentView(box)
  box.post { try { renderTests() } catch(e:Throwable) { fail(e) } }
 }
 private fun rendered(shader:RuntimeShader,w:Int,h:Int):Bitmap {
  val reader=ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,1,HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
  val renderer=HardwareRenderer();val node=RenderNode("Particle shader test")
  try {
   renderer.isOpaque=false;renderer.setSurface(reader.surface);renderer.setContentRoot(node);node.setPosition(0,0,w,h)
   val c=node.beginRecording(w,h);c.drawColor(Color.TRANSPARENT,BlendMode.CLEAR);c.drawRect(0f,0f,w.toFloat(),h.toFloat(),Paint().apply { this.shader=shader });node.endRecording()
   renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
   val image=reader.acquireNextImage()!!
   try { val buffer=image.hardwareBuffer!!;try { return Bitmap.wrapHardwareBuffer(buffer,ColorSpace.get(ColorSpace.Named.SRGB))!!.copy(Bitmap.Config.ARGB_8888,false)!! } finally { buffer.close() } } finally { image.close() }
  } finally { renderer.destroy();reader.close();node.discardDisplayList() }
 }
 private fun renderTests() {
  val source=Bitmap.createBitmap(360,180,Bitmap.Config.ARGB_8888)
  val c=Canvas(source);val p=Paint().apply { shader=LinearGradient(0f,0f,360f,180f,Color.CYAN,Color.BLUE,Shader.TileMode.CLAMP) };c.drawRoundRect(0f,0f,360f,180f,25f,25f,p)
  p.shader=null;p.color=Color.WHITE;p.textSize=24f;c.drawText("Notification",35f,70f,p)
  val shader=RuntimeShader(ParticleDissolveHook.Api33.SHADER)
  shader.setInputShader("uImage",BitmapShader(source,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP).apply { setFilterMode(BitmapShader.FILTER_MODE_LINEAR) })
  shader.setFloatUniform("uSize",360f,180f);shader.setFloatUniform("uImageScale",1f,1f);shader.setFloatUniform("uPad",100f);shader.setFloatUniform("uCell",2f)
  val counts=ArrayList<Int>();var escaped=0
  val dir=File(filesDir,"particle-probe").apply { mkdirs() }
  for((i,t) in listOf(0f,.25f,.55f,1f).withIndex()) {
   shader.setFloatUniform("uProgress",t);val b=rendered(shader,560,380)
   var count=0
   for(y in 0 until b.height)for(x in 0 until b.width) {
    if(Color.alpha(b.getPixel(x,y))>8) { count++;if(t==.55f && (x<100 || x>=460 || y<100 || y>=280))escaped++ }
   }
   counts.add(count);File(dir,"phase-$i.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) }
   if(t==0f)check(b.getPixel(200,200)==source.getPixel(100,100))
  }
  check(counts[0]>60000 && counts[2] in 1000 until counts[0] && counts[3]==0 && escaped>0) { "Coverage $counts / escaped=$escaped" }
  shader.setFloatUniform("uProgress",0f)
  val half=Bitmap.createScaledBitmap(source,180,90,true)
  shader.setInputShader("uImage",BitmapShader(half,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP).apply { setFilterMode(BitmapShader.FILTER_MODE_LINEAR) })
  shader.setFloatUniform("uImageScale",.5f,.5f)
  val scaled=rendered(shader,560,380)
  val old=source.getPixel(200,100);val actual=scaled.getPixel(300,200)
  check(kotlin.math.abs(Color.green(actual)-Color.green(old))<5 && kotlin.math.abs(Color.blue(actual)-Color.blue(old))<5)
  lifecycle(JSONObject().put("scaledSnapshotCoordinates",true).put("coverage",counts).put("escapedPixels",escaped).put("shaderCompiled",true).put("zeroProgressIdentity",true).put("endTransparent",true))
 }
 private fun lifecycle(report:JSONObject) {
  val api=ParticleDissolveHook.Api33
  val type=api.javaClass
  fun method(name:String)=type.getDeclaredMethod(name,View::class.java).apply { isAccessible=true }
  val prepare=method("prepare");val dismiss=method("dismiss");val cancel=method("cancel")
  val cap=prepare.invoke(api,card)!!
  val field=cap.javaClass.getDeclaredField("bitmap").apply { isAccessible=true }
  var tries=0
  fun waitReady() {
   try {
    if(field.get(cap)==null) { check(tries++<60);ui.postDelayed({waitReady()},50);return }
    val captured=field.get(cap) as Bitmap
    check(captured.config==Bitmap.Config.HARDWARE)
    val readback=captured.copy(Bitmap.Config.ARGB_8888,false)!!
    check(Color.alpha(readback.getPixel(readback.width/2,readback.height/2))>200)
    readback.recycle()
    dismiss.invoke(api,card)
    ui.postDelayed({try {
      val effects=type.getDeclaredField("effects").apply { isAccessible=true }.get(api) as Map<*,*>
      check(effects.size==1);cancel.invoke(api,card);check(effects.isEmpty())
      dismiss.invoke(api,card)
      ui.postDelayed({try {
       check(effects.size==1)
       ui.postDelayed({try { check(effects.isEmpty())
        var detached=0
        val dust=ParticleDissolveHook.Api33.Dust(card,captured,0f,0f,card.width,card.height,1f,100) { detached++ }
        dust.start();(card.parent as android.view.ViewGroup).removeView(card);check(detached==1)
        report.put("detachReleasesOverlay",true).put("hardwareSnapshot",true).put("cancelReleasesOverlay",true).put("completionReleasesOverlay",true).put("physicalDeviceTested",false)
        Log.i("ParticleProbe","PASS "+report);label.text="PASS\n"+report.toString(2)
       } catch(e:Throwable){fail(e)}},1400)
      } catch(e:Throwable){fail(e)}},300)
    } catch(e:Throwable){fail(e)}},300)
   } catch(e:Throwable) { fail(e) }
  }
  waitReady()
 }
}
