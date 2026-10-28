package com.coloros16.liquidglass.blur

import android.app.Activity
import android.os.Bundle
import android.graphics.*
import android.util.Log
import android.view.View
import com.coloros16.liquidglass.hook.BackdropBinding
import com.coloros16.liquidglass.hook.BackdropWorker
import com.coloros16.liquidglass.hook.FloatBackdropBitmap
import com.coloros16.liquidglass.liquidglass.LiquidGlassShader

/** Debug only: exercises production float packing, worker fairness and both Android AGSL variants. */
class BackdropBlurProbeActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(object : View(this) {
            val input=Bitmap.createBitmap(384,384,Bitmap.Config.ARGB_8888).apply {
                for(y in 0 until height)for(x in 0 until width)
                    setPixel(x,y,if((x/6+y/6)%2==0)Color.WHITE else Color.BLACK)
            }
            val worker=BackdropWorker({postInvalidateOnAnimation()},{throw AssertionError(it)})
            val routeWorker=BackdropWorker({postInvalidateOnAnimation()},{throw AssertionError(it)})
            val routeParams=LiquidGlassShader.Params(viewportWidth=320f,viewportHeight=320f,
                cornerRadius=160f,blurRadius=64f)
            var passed=false
            init {
                val rect=RectF(48f,64f,304f,320f);val screen=RectF(0f,0f,768f,768f)
                val pending=BackdropBinding.resolve(routeWorker,input,screen,rect,true,routeParams)
                check(pending.pending && pending.params.blurRadius<=1.5f && !pending.params.sourcePreblurred)
                val bypass=BackdropBinding.resolve(routeWorker,input,screen,rect,false,routeParams)
                check(bypass.bitmap===input && bypass.params===routeParams && !bypass.pending)
                val zero=BackdropBinding.resolve(routeWorker,input,screen,rect,true,routeParams.copy(blurRadius=0f))
                check(zero.bitmap===input && !zero.pending && !zero.params.sourcePreblurred)


                val packed=FloatBackdropBitmap.create(floatArrayOf(255f,123.456f,11.25f,45.875f),1,1)
                check(packed.config==Bitmap.Config.RGBA_F16)
                val color=packed.getColor(0,0)
                check(kotlin.math.abs(color.red()*255-123.456f)<.08f)
                check(kotlin.math.abs(color.red()*255-kotlin.math.round(color.red()*255))>.2f)
                val translucent=FloatBackdropBitmap.create(floatArrayOf(128f,64f,32f,16f),1,1).getColor(0,0)
                check(kotlin.math.abs(translucent.alpha()-128f/255)<.001f)
                check(kotlin.math.abs(translucent.red()-.5f)<.001f)
            }
            override fun onDraw(canvas:Canvas) {
                canvas.drawColor(Color.rgb(12,17,32))
                val filtered=listOf(2f,8f,16f,32f).map { worker.resolve(input,RectF(0f,0f,384f,384f),it) }
                val binding=BackdropBinding.resolve(routeWorker,input,RectF(0f,0f,768f,768f),
                    RectF(48f,64f,304f,320f),true,routeParams)
                val ready=filtered.all { it!=null } && !binding.pending
                if(!binding.pending) {
                    check(binding.bitmap.config==Bitmap.Config.RGBA_F16 && binding.params.sourcePreblurred)
                    check(binding.sourceRect==RectF(48f,64f,304f,320f))
                }
                val label=Paint().apply { color=Color.WHITE;textSize=24f }
                canvas.drawText("DENSE F16 PREBLUR / ORIGINAL OPTICS",24f,100f,label)
                val size=minOf((width-72f)/2,384f)
                for(i in 0..3) {
                    val bitmap=filtered[i]?:input
                    val shader=LiquidGlassShader.create(metaball=true)
                    val params=LiquidGlassShader.Params(viewportWidth=size,viewportHeight=size,
                        cornerRadius=size/2,blurRadius=16f,sourcePreblurred=filtered[i]!=null)
                    val src=LiquidGlassShader.createSourceBitmapShader(bitmap)
                    LiquidGlassShader.setUniforms(shader,params,src,src,sourceRect=RectF(0f,0f,384f,384f))
                    val save=canvas.save();canvas.translate(24f+(i%2)*(size+24),160f+(i/2)*(size+60))
                    canvas.drawRect(0f,0f,size,size,Paint().apply { this.shader=shader })
                    canvas.restoreToCount(save)
                }
                canvas.drawText("OLD SPARSE GRID / PRODUCTION SNAPSHOT BINDING",24f,1120f,label)
                for(i in 0..1) {
                    val bitmap=if(i==0)input else binding.bitmap
                    val params=if(i==0)routeParams else binding.params
                    val shader=LiquidGlassShader.create()
                    val src=LiquidGlassShader.createSourceBitmapShader(bitmap)
                    LiquidGlassShader.setUniforms(shader,params,src,src,sourceRect=RectF(0f,0f,384f,384f))
                    val save=canvas.save();canvas.translate(24f+i*400f,1160f)
                    canvas.drawRect(0f,0f,320f,320f,Paint().apply { this.shader=shader })
                    canvas.restoreToCount(save)
                }
                if(ready && !passed) {
                    // Also compile the BASE variant used when metaballs are not available.
                    RuntimeShader(LiquidGlassShader.AGSL_SOURCE_BASE)
                    passed=true;Log.i("DenseBlurProbe","PASS F16 fractional/alpha; four worker styles; FULL/BASE AGSL; snapshot binding true/false/zero/pending/ready/crop")
                }
                if(!ready)postInvalidateDelayed(100)
            }
        })
    }
}
