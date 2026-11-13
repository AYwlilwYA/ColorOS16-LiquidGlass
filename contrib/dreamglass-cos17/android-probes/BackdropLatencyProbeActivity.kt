package com.dreamglass.cos17

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.widget.TextView
import com.coloros16.liquidglass.hook.ButtonBackdropWorker
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/** Isolated emulator fixture. Readbacks assert output correctness, never implement blur. */
class BackdropLatencyProbeActivity:Activity() {
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        val label=TextView(this).apply {text="Backdrop latency / latest frame / demand regression…"}
        setContentView(label)
        Thread({
            val result=JSONObject().put("physicalDeviceTested",false)
            val active=AtomicBoolean(true);val draws=AtomicInteger()
            val error=AtomicReference<Throwable>()
            val worker=ButtonBackdropWorker({draws.incrementAndGet()},{error.set(it)},active={active.get()})
            try {
                fun source(color:Int)=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888).apply {eraseColor(color)}
                val screen=RectF(0f,0f,64f,64f)
                val red=source(Color.RED);val blue=source(Color.BLUE)
                fun await(input:Bitmap,radius:Float,color:Int):Bitmap {
                    val until=SystemClock.uptimeMillis()+5000L
                    while(SystemClock.uptimeMillis()<until) {
                        error.get()?.let {throw AssertionError("GPU worker failed",it)}
                        val bitmap=worker.resolve(input,screen,radius)
                        if(bitmap!=null) {
                            check(bitmap.config==Bitmap.Config.HARDWARE)
                            val copy=bitmap.copy(Bitmap.Config.ARGB_8888,false)!!
                            val pixel=copy.getPixel(copy.width/2,copy.height/2);copy.recycle()
                            if(pixel==color)return bitmap
                        }
                        Thread.sleep(5)
                    }
                    error("Timed out waiting for latest GPU image")
                }
                val started=SystemClock.uptimeMillis()
                check(worker.resolve(red,screen,8f)==null)
                worker.resolve(red,screen,12f)
                val retained=await(red,8f,Color.RED)
                await(red,12f,Color.RED)
                result.put("firstTwoMaterialsMs",SystemClock.uptimeMillis()-started)
                    .put("bothInitialMaterialsRenderedOnGpu",true)
                val changed=SystemClock.uptimeMillis()
                repeat(20) {worker.resolve(if(it==19)blue else red,screen,8f)}
                await(blue,8f,Color.BLUE)
                result.put("latestFrameMs",SystemClock.uptimeMillis()-changed)
                    .put("latestPendingFrameWins",true)
                worker.clear(releaseHardware=false)
                check(worker.resolve(blue,screen,8f)==null)
                await(blue,8f,Color.BLUE)
                check(!retained.isRecycled)
                result.put("sceneChangeDropsOldPixels",true).put("publishedImageRetained",true)
                active.set(false)
                // Allow an already published callback to finish before checking idle demand.
                Thread.sleep(80)
                val before=draws.get()
                repeat(20) {check(worker.resolve(red,screen,8f)==null)}
                Thread.sleep(120)
                check(draws.get()==before)
                result.put("hiddenDemandDoesNotComputeOrInvalidate",true).put("passed",true)
            } catch(t:Throwable) {
                result.put("passed",false).put("error",t.stackTraceToString())
            } finally {
                active.set(false);worker.clear()
                File(filesDir,"backdrop-latency-probe.json").writeText(result.toString(2))
                runOnUiThread {label.text=result.toString(2)}
            }
        },"DreamGlass-latency-probe").start()
    }
}
