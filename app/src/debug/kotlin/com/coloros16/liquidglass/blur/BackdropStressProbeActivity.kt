package com.coloros16.liquidglass.blur

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.util.Log
import android.widget.TextView
import com.coloros16.liquidglass.hook.BackdropWorker
import java.util.concurrent.atomic.AtomicInteger

/** Own emulator only. Synthetic callback failures, epoch cancellation and frame/geometry churn. */
class BackdropStressProbeActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val label=TextView(this).apply { text="Dense backdrop blur\nRunning worker stress…";textSize=22f;setTextColor(Color.WHITE) }
        setContentView(label)
        Thread({
            try {
                val reports=AtomicInteger();val notifications=AtomicInteger()
                val worker=BackdropWorker({ notifications.incrementAndGet();error("injected redraw failure") },
                    { reports.incrementAndGet();error("injected reporter failure") })
                fun source(size:Int,color:Int)=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
                fun ready(input:Bitmap,radius:Float):Bitmap {
                    val deadline=SystemClock.uptimeMillis()+5000
                    while(SystemClock.uptimeMillis()<deadline) {
                        worker.resolve(input,RectF(0f,0f,input.width.toFloat(),input.height.toFloat()),radius)?.let { return it }
                        Thread.sleep(10)
                    }
                    error("worker stalled after callback/cancel")
                }
                val first=source(96,Color.RED)
                val retained=ready(first,8f)
                check(retained.config==Bitmap.Config.RGBA_F16 && reports.get()>=1)
                worker.clear()
                // Previously published bitmap must remain usable by a recorded shader.
                check(!retained.isRecycled && retained.getColor(0,0).red()>.9f)
                check(ready(source(80,Color.BLUE),8f).getColor(0,0).blue()>.9f)
                // Rapid requests retain only the latest frame per style. No queue growth per button.
                repeat(120) { frame ->
                    val input=source(64+(frame%3)*8,if(frame%2==0)Color.GREEN else Color.BLUE)
                    for(radius in listOf(2f,8f,16f,32f)) worker.resolve(input,
                        RectF(0f,0f,input.width.toFloat(),input.height.toFloat()),radius)
                    if(frame%7==0) worker.clear()
                    Thread.sleep(5)
                }
                worker.clear()
                val final=source(72,Color.GREEN)
                for(radius in listOf(2f,8f,16f,32f)) check(ready(final,radius).getColor(0,0).green()>.9f)
                Thread.sleep(100)
                check(notifications.get()>=6 && reports.get()==notifications.get())
                // Cancel an in-flight dense filter, then request a different geometry.
                worker.clear()
                val large=source(1024,Color.RED)
                worker.resolve(large,RectF(0f,0f,1024f,1024f),64f)
                Thread.sleep(15);worker.clear()
                check(ready(source(48,Color.BLUE),8f).width==48)
                check(!retained.isRecycled)
                worker.clear()
                Log.i("DenseBlurStressProbe","PASS callback+reporter exceptions contained; 120 frame/geometry churn; four styles recover; clear cancels; published bitmap retained; F16")
                runOnUiThread { label.text="PASS\nCallback fault containment\n120-frame churn\nFour styles recover\nClear/cancel and bitmap ownership\nF16 precision preserved" }
            } catch(t:Throwable) { Log.e("DenseBlurStressProbe","FAIL",t);runOnUiThread { label.text="FAIL: $t" } }
        },"DenseBlur-stress-probe").start()
    }
}
