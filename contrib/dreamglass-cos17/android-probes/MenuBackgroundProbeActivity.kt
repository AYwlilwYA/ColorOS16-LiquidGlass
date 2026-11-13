package com.dreamglass.cos17

import android.app.Activity
import android.os.Bundle
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.view.View
import android.widget.TextView
import com.coloros16.liquidglass.hook.BoundedGlassDrawable
import com.coloros16.liquidglass.liquidglass.LiquidGlassShader
import java.io.File
import org.json.JSONObject

class MenuBackgroundProbeActivity:Activity() {
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        val label=TextView(this).apply {text="Menu GPU geometry / fallback regression…";setPadding(24,60,24,0)}
        setContentView(label)
        label.post {try {label.text=probe().toString(2)} catch(e:Throwable) {label.text="FAIL $e";android.util.Log.e("MenuBackgroundProbe","FAIL",e)}}
    }
    private fun render(drawable:BoundedGlassDrawable):Bitmap {
        val reader=ImageReader.newInstance(300,200,PixelFormat.RGBA_8888,1,HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
        val renderer=HardwareRenderer();val node=RenderNode("Menu background geometry")
        try {
            renderer.isOpaque=false;renderer.setSurface(reader.surface);renderer.setContentRoot(node);node.setPosition(0,0,300,200)
            val canvas=node.beginRecording(300,200);canvas.drawColor(Color.TRANSPARENT,BlendMode.CLEAR);drawable.draw(canvas);node.endRecording()
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            val image=reader.acquireNextImage()!!
            try {val buffer=image.hardwareBuffer!!
                try {val hardware=Bitmap.wrapHardwareBuffer(buffer,ColorSpace.get(ColorSpace.Named.SRGB))!!
                    try {return hardware.copy(Bitmap.Config.ARGB_8888,false)!!} finally {hardware.recycle()}
                } finally {buffer.close()}
            } finally {image.close()}
        } finally {renderer.destroy();reader.close();node.discardDisplayList()}
    }
    private fun probe():JSONObject {
        val host=View(this).apply {layout(0,0,300,200)}
        val native=object:ColorDrawable(Color.GREEN) {
            override fun getPadding(rect:Rect):Boolean {rect.set(13,17,19,23);return true}
        }.apply {bounds=Rect(7,8,9,10)}
        var enabled=true;var available=true;var fail=false;var calls=0;var painted=0
        var passed:Rect?=null
        val drawable=BoundedGlassDrawable(host,native,{enabled},{canvas,_,rect ->
            calls++;passed=Rect(rect)
            if(fail)throw IllegalStateException("synthetic missing source")
            if(available) {canvas.drawColor(Color.RED);true} else false
        },{painted++})
        val area=Rect(40,30,240,150);drawable.bounds=area
        val actual=render(drawable)
        check(passed==area && painted==1 && actual.getPixel(150,90)==Color.RED)
        check(Color.alpha(actual.getPixel(20,90))==0 && Color.alpha(actual.getPixel(150,180))==0)
        available=false;val missing=render(drawable)
        check(missing.getPixel(150,90)==Color.GREEN && native.bounds==Rect(7,8,9,10) && painted==1)
        fail=true;val fallback=render(drawable)
        check(fallback.getPixel(150,90)==Color.GREEN && calls==3 && painted==1)
        enabled=false;val disabled=render(drawable)
        check(disabled.getPixel(150,90)==Color.GREEN && calls==3)
        drawable.alpha=128;val faded=render(drawable)
        check(Color.alpha(faded.getPixel(150,90)) in 127..129)
        // Regression: copying a native entrance alpha must not multiply it again
        // when the fresh capture is not available yet.
        native.alpha=128
        drawable.alpha=native.alpha
        val nativeFaded=render(drawable)
        check(Color.alpha(nativeFaded.getPixel(150,90)) in 127..129)
        drawable.alpha=0;val hidden=render(drawable);check(Color.alpha(hidden.getPixel(150,90))==0 && calls==3)
        var invalidations=0
        val forwarded=BoundedGlassDrawable(host,native,{false},{_,_,_->false},forwardNativeCallbacks=true)
        forwarded.callback=object:android.graphics.drawable.Drawable.Callback {
            override fun invalidateDrawable(who:android.graphics.drawable.Drawable) {invalidations++}
            override fun scheduleDrawable(who:android.graphics.drawable.Drawable,what:Runnable,`when`:Long) {}
            override fun unscheduleDrawable(who:android.graphics.drawable.Drawable,what:Runnable) {}
        }
        forwarded.bounds=area
        native.invalidateSelf()
        check(invalidations>0)
        val padding=Rect();check(drawable.getPadding(padding) && padding==Rect(13,17,19,23))
        // BackgroundBlurDrawable rejects even setColorFilter(null). Reproduce
        // that platform contract without depending on hidden API access.
        var filterCalls=0;var nativeDraws=0;var nativeAlpha=255
        val nativePaint=Paint().apply {color=Color.GREEN}
        val strictNative=object:Drawable() {
            override fun draw(canvas:Canvas) {nativeDraws++;nativePaint.alpha=nativeAlpha;canvas.drawRect(bounds,nativePaint)}
            override fun setAlpha(value:Int) {nativeAlpha=value}
            override fun getAlpha():Int=nativeAlpha
            override fun setColorFilter(value:ColorFilter?) {filterCalls++;throw IllegalArgumentException("not implemented")}
            @Deprecated("Deprecated in Java") override fun getOpacity():Int=PixelFormat.TRANSLUCENT
        }
        check(runCatching {strictNative.colorFilter=null}.exceptionOrNull() is IllegalArgumentException)
        filterCalls=0
        var fresh=false;var nativeFailures=0
        val strictWrapper=BoundedGlassDrawable(host,strictNative,{true},{canvas,_,_->
            if(fresh) {canvas.drawColor(Color.RED);true} else false
        },failure={nativeFailures++},forwardNativeCallbacks=true).apply {bounds=area}
        val strictMissing=render(strictWrapper)
        check(strictMissing.getPixel(150,90)==Color.GREEN && filterCalls==0 && nativeDraws==1)
        strictWrapper.alpha=128
        val strictAlpha=render(strictWrapper)
        check(Color.alpha(strictAlpha.getPixel(150,90)) in 127..129)
        strictWrapper.colorFilter=PorterDuffColorFilter(Color.BLUE,PorterDuff.Mode.SRC_IN)
        val unsupportedFilter=render(strictWrapper)
        check(Color.green(unsupportedFilter.getPixel(150,90))==255 && filterCalls==1 && nativeFailures==1)
        strictWrapper.colorFilter=null;strictWrapper.alpha=255;fresh=true
        val strictReady=render(strictWrapper)
        check(strictReady.getPixel(150,90)==Color.RED && nativeDraws==3)
        strictNative.alpha=255
        val layered=BoundedGlassDrawable(host,LayerDrawable(arrayOf(strictNative)),{false},{_,_,_->false}).apply {bounds=area}
        val layeredFallback=render(layered)
        check(layeredFallback.getPixel(150,90)==Color.GREEN && filterCalls==1)
        val broken=object:ColorDrawable(Color.GREEN) {
            override fun draw(canvas:Canvas) {throw IllegalArgumentException("vendor native draw failure")}
        }.apply {bounds=area}
        var contained=0
        val protected=BoundedGlassDrawable(host,broken,{false},{_,_,_->false},failure={contained++}).apply {bounds=area}
        val brokenFallback=render(protected)
        check(contained==1 && Color.alpha(brokenFallback.getPixel(150,90))==0)
        // Native compositor regions survive omitted draw calls. Suppress them
        // only after a successful GPU frame, restore on a source miss/disable.
        var glassReady=false
        val cloudNative=ColorDrawable(Color.GREEN).apply {bounds=area}
        val cloud=BoundedGlassDrawable(host,cloudNative,{true},{canvas,_,_->
            if(glassReady){canvas.drawColor(Color.RED);true}else false
        },hideNativeBlurOnGlass=true).apply {bounds=area}
        val cloudMissing=render(cloud)
        check(cloudNative.alpha==255 && cloudMissing.getPixel(150,90)==Color.GREEN)
        glassReady=true;val cloudReady=render(cloud)
        check(cloudNative.alpha==0 && cloud.alpha==255 && cloudReady.getPixel(150,90)==Color.RED)
        cloud.alpha=128;val cloudFaded=render(cloud)
        check(cloudNative.alpha==0 && Color.alpha(cloudFaded.getPixel(150,90)) in 127..129)
        glassReady=false;val cloudLost=render(cloud)
        check(cloudNative.alpha==128 && Color.alpha(cloudLost.getPixel(150,90)) in 127..129)
        glassReady=true;cloud.alpha=255;render(cloud).recycle();cloud.alpha=0
        check(cloudNative.alpha==0)
        val replacementNative=ColorDrawable(Color.BLUE)
        cloud.orig=replacementNative
        check(cloudNative.alpha==0) // logical opacity is zero during detach/replacement
        cloud.alpha=255;glassReady=false;val replaced=render(cloud)
        check(replaced.getPixel(150,90)==Color.BLUE)

        // These callbacks run outside Drawable.draw(), including notification
        // appearance/outline passes. A vendor exception must remain contained.
        var lifecycleFailures=0
        val hostile=object:Drawable() {
            override fun draw(canvas:Canvas) {}
            override fun setAlpha(value:Int) {}
            override fun setColorFilter(value:ColorFilter?) {}
            override fun getOutline(outline:Outline) {throw IllegalArgumentException("outline unavailable")}
            override fun getPadding(rect:Rect):Boolean {throw IllegalArgumentException("padding unavailable")}
            override fun onStateChange(state:IntArray):Boolean {throw IllegalArgumentException("state unavailable")}
            override fun onLevelChange(level:Int):Boolean {throw IllegalArgumentException("level unavailable")}
            override fun setVisible(visible:Boolean,restart:Boolean):Boolean {throw IllegalArgumentException("visibility unavailable")}
            override fun setHotspot(x:Float,y:Float) {throw IllegalArgumentException("hotspot unavailable")}
            override fun setHotspotBounds(l:Int,t:Int,r:Int,b:Int) {throw IllegalArgumentException("hotspot bounds unavailable")}
            @Deprecated("Deprecated in Java") override fun getOpacity():Int=PixelFormat.TRANSLUCENT
        }.apply {bounds=area}
        val lifecycle=BoundedGlassDrawable(host,hostile,{false},{_,_,_->false},failure={lifecycleFailures++},forwardNativeCallbacks=true).apply {bounds=area}
        val outline=Outline();lifecycle.getOutline(outline)
        val outlineRect=Rect();check(outline.getRect(outlineRect) && outlineRect==area && hostile.bounds==area)
        check(!lifecycle.getPadding(Rect()))
        lifecycle.state=intArrayOf(android.R.attr.state_pressed);lifecycle.level=2
        lifecycle.setVisible(false,false);lifecycle.setHotspot(10f,10f);lifecycle.setHotspotBounds(1,2,3,4)
        check(lifecycleFailures==7)
        // Render the actual AGSL material, not just a colored stand-in, while
        // exercising the same bounded replacement and compositor handoff.
        val source=Bitmap.createBitmap(300,200,Bitmap.Config.ARGB_8888)
        Canvas(source).apply {
            drawColor(Color.rgb(18,45,95))
            drawCircle(80f,90f,75f,Paint().apply {color=Color.CYAN})
            drawRect(165f,0f,300f,200f,Paint().apply {color=Color.rgb(210,70,100)})
        }
        val shader=LiquidGlassShader.create()
        LiquidGlassShader.setUniforms(shader,LiquidGlassShader.Params(200f,120f,cornerRadius=36f,blurRadius=2f,
            refractionHeight=28f,refractionAmount=14f,surfacePolish=true),
            LiquidGlassShader.createSourceBitmapShader(source),sourceRect=RectF(40f,30f,240f,150f))
        val material=BoundedGlassDrawable(host,ColorDrawable(Color.BLACK),{true},{canvas,_,rect->
            canvas.save();try {canvas.translate(rect.left.toFloat(),rect.top.toFloat());canvas.drawRect(0f,0f,200f,120f,Paint().apply {this.shader=shader})}finally {canvas.restore()};true
        },hideNativeBlurOnGlass=true).apply {bounds=area}
        val glass=render(material)
        check(Color.alpha(glass.getPixel(150,90))>240 && glass.getPixel(90,90)!=glass.getPixel(210,90))
        check(Color.alpha(glass.getPixel(20,90))==0 && Color.alpha(glass.getPixel(40,30))<20)
        var reentrantCalls=0
        lateinit var reentrant:BoundedGlassDrawable
        reentrant=BoundedGlassDrawable(host,null,{true},{canvas,_,_->
            reentrantCalls++;reentrant.draw(canvas);canvas.drawColor(Color.RED);true
        }).apply {bounds=area}
        render(reentrant).recycle();render(reentrant).recycle();check(reentrantCalls==2)
        val result=JSONObject().put("offsetBoundsPassedToMaterial",true).put("canvasClippedToBounds",true)
            .put("missingSourceKeepsNativeBackground",true).put("renderFailureKeepsNativeBackground",true)
            .put("constantStateBackgroundIsolated",true).put("nativePaddingPreserved",true)
            .put("alphaAndDisabledStatesPreserved",true).put("nativeCallbackNotReplayed",true)
            .put("nativeFallbackAlphaAppliedOnce",true).put("nativeInvalidationForwarded",true)
            .put("oldNullFilterCrashReproduced",true).put("unsupportedNullFilterNeverCalled",true)
            .put("unsupportedFilterRetainsNativeDrawing",true).put("strictNativeEntranceAlphaAppliedOnce",true)
            .put("nativeFallbackTransitionsToGlass",true).put("layeredNativeBlurCompatible",true)
            .put("nativeDrawExceptionContained",true)
            .put("nativeBlurSuppressedOnlyAfterGlass",true).put("nativeBlurRestoredOnCaptureMiss",true)
            .put("suppressedNativeEntranceAlphaAppliedOnce",true).put("nativeBlurHiddenAtZeroAlpha",true)
            .put("replacementNativeRemainsUsable",true).put("nativeLifecycleExceptionsContained",true)
            .put("outlineQueryDoesNotMoveNativeBounds",true).put("productionAgslMaterialRendered",true)
            .put("productionMaterialRoundedAndSpatiallyVarying",true).put("reentrantDrawContainedAndGuardReleased",true)
            .put("physicalDeviceTested",false).put("vendorHookTested",false)
        val folder=File(filesDir,"menu-background-probe").apply {mkdirs()}
        File(folder,"glass.png").outputStream().use {glass.compress(Bitmap.CompressFormat.PNG,100,it)}
        File(folder,"report.json").writeText(result.toString(2))
        for(b in listOf(cloudMissing,cloudReady,cloudFaded,cloudLost,replaced,source,glass))b.recycle()
        for(b in listOf(actual,missing,fallback,disabled,faded,nativeFaded,hidden,strictMissing,strictAlpha,unsupportedFilter,strictReady,layeredFallback,brokenFallback))b.recycle()
        android.util.Log.i("MenuBackgroundProbe","PASS $result");return result
    }
}
