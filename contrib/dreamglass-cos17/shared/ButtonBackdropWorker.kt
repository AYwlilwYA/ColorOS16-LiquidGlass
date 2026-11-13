package com.coloros16.liquidglass.hook

import android.graphics.Bitmap
import android.annotation.SuppressLint
import android.graphics.ColorSpace
import android.graphics.RectF
import android.os.SystemClock
import android.util.Half
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.coloros16.liquidglass.liquidglass.LiquidGlassShader

/** 2026-10-26: one source-binding path for first draw, refresh and Android regression probes. */
internal object ButtonBackdropBinding {
    data class Bound(val bitmap: Bitmap, val sourceRect: RectF,
        val params: LiquidGlassShader.Params, val pending: Boolean = false)

    fun resolve(worker: ButtonBackdropWorker, source: Bitmap, screen: RectF, sourceRect: RectF,
                sourceIsSnapshot: Boolean, params: LiquidGlassShader.Params, panelRadius: Float? = null): Bound {
        if (!sourceIsSnapshot) return Bound(source, sourceRect, params)
        val sigma = panelRadius ?: ButtonBackdropBlur.materialSigma(params.blurRadius, params.surfacePolish)
        if (!sigma.isFinite() || sigma <= 0f) return Bound(source, sourceRect,
            if (panelRadius != null) params.copy(blurRadius = 0f, sourcePreblurred = false) else params)
        val bitmap = worker.resolve(source, screen, sigma)
        if (bitmap == null) {
            // The old wide sparse kernel itself makes a repeated grid. During initial computation
            // use a compact, subpixel-spaced kernel rather than displaying that artifact for a frame.
            val fallback = if (panelRadius != null) 1.5f else minOf(params.blurRadius, 1.5f)
            return Bound(source, sourceRect, params.copy(blurRadius = fallback, sourcePreblurred = false), true)
        }
        val sx = bitmap.width.toFloat()/source.width; val sy = bitmap.height.toFloat()/source.height
        val rect = RectF(sourceRect.left*sx, sourceRect.top*sy, sourceRect.right*sx, sourceRect.bottom*sy)
        // Preserve the positive blur branch so dispersion does not change when the worker finishes.
        return Bound(bitmap, rect, params.copy(blurRadius = if (panelRadius != null) 1f else params.blurRadius,
            sourcePreblurred = true))
    }
}

/** Four bounded style slots, one running computation, latest pending capture per style.
 * Filtering runs off the UI thread; published hardware/F16 bitmaps are never recycled. */
internal class ButtonBackdropWorker(private val invalidate:()->Unit,private val failed:(Throwable)->Unit, private val engine:()->Int = { 0 }, private val active:()->Boolean = { true }) {
    private data class Geometry(val width:Int,val height:Int,val left:Float,val top:Float,val screenWidth:Float,val screenHeight:Float)
    private data class Style(val geometry:Geometry,val radius:Float,val engine:Int)
    private data class Key(val source:Bitmap,val generation:Int,val style:Style)
    private data class Request(val key:Key,val source:Bitmap,val epoch:Long)
    private val lock=Any()
    private val executor=RuntimeWorkPolicy.executor("DreamGlass-backdrop")
    private val requested=LinkedHashMap<Style,Key>(4,.75f,true)
    private val pending=LinkedHashMap<Style,Request>()
    private val ready=HashMap<Style,Bitmap>()
    private var geometry:Geometry?=null
    private var running=false
    @Volatile private var epoch=0L
    private val workBudget=BackdropWorkBudget()
    private val hardware=HardwareBackdropBlur()
    @Volatile private var hardwareRetryAtMs=0L
    @Volatile private var trimHardware=false
    @Volatile private var retryAtMs=0L

    fun resolve(source:Bitmap,screen:RectF,radius:Float):Bitmap? {
        if(!active())return null
        if(source.isRecycled || screen.width()<=0 || screen.height()<=0 || !radius.isFinite() || radius<=0)return null
        if(SystemClock.uptimeMillis()<retryAtMs)return null
        val geo=Geometry(source.width,source.height,screen.left,screen.top,screen.width(),screen.height())
        val style=Style(geo,radius,0);val key=Key(source,source.generationId,style)
        synchronized(lock) {
            if(geometry!=geo) { geometry=geo;epoch++;requested.clear();pending.clear();ready.clear() }
            if(requested[style]!=key) {
                if(!requested.containsKey(style) && requested.size>=4) {
                    val oldest=requested.keys.first();requested.remove(oldest);pending.remove(oldest);ready.remove(oldest)
                }
                requested[style]=key;pending[style]=Request(key,source,epoch)
                if(!running) {
                    running=true
                    try { executor.execute(::drain) } catch(t:Throwable) {
                        running=false;requested.clear();pending.clear()
                        retryAtMs=SystemClock.uptimeMillis()+RuntimeWorkPolicy.failureDelayMs(t)
                        RuntimeWorkPolicy.guarded({}) { failed(t) }
                    }
                }
            }
            return ready[style]
        }
    }

    /** Drop references only: published bitmaps may still be used by recorded RenderNodes. */
    fun clear(releaseHardware:Boolean=true) = synchronized(lock) {
        epoch++;geometry=null;requested.clear();pending.clear();ready.clear()
        if(releaseHardware)trimHardware=true
        if(!running) {
            running=true
            try { executor.execute(::drain) } catch(t:Throwable) {
                running=false
                RuntimeWorkPolicy.guarded({}) { failed(t) }
            }
        }
    }

    private fun drain() {
        RuntimeWorkPolicy.guarded({}) { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
        // Keep the last good image while coalescing new frames. The entire worker shares one
        // Shared GPU budget: two initial credits, then 20 jobs/s, only with visible demand.
        try {
            while(true) {
                if(!active()) { synchronized(lock) {pending.clear()};return }
                if(synchronized(lock) {pending.isEmpty()})return
                val wait=workBudget.waitMs(SystemClock.uptimeMillis())
                if(wait>0) {Thread.sleep(wait);continue}
                val request=synchronized(lock) {
                    // A surface with no prepared image takes precedence over refreshing one
                    // already showing a good image. Keep only the latest input per style.
                    val next=pending.entries.firstOrNull { !ready.containsKey(it.key) }
                        ?: pending.entries.firstOrNull() ?: return
                    pending.remove(next.key);next.value
                }
                if(request.epoch!=epoch || !active())continue
                workBudget.started(SystemClock.uptimeMillis())
                try {
                    val bitmap=compute(request)
                    val publish=synchronized(lock) {
                        val style=request.key.style
                        if(active() && request.epoch==epoch && geometry==style.geometry && requested.containsKey(style)) {
                            ready[style]=bitmap;true
                        } else false
                    }
                    if(publish) RuntimeWorkPolicy.guarded(failed, invalidate)
                    else bitmap.recycle() // Never published, therefore no shader can own it.
                } catch(_: java.util.concurrent.CancellationException) {
                    // Screen-off/geometry change: abandon intermediate pixels, no retry or log.
                } catch(t:Throwable) {
                    retryAtMs=SystemClock.uptimeMillis()+RuntimeWorkPolicy.failureDelayMs(t)
                    clear()
                    RuntimeWorkPolicy.guarded({}) { failed(t) }
                }
            }
        } catch(t:Throwable) {
            retryAtMs=SystemClock.uptimeMillis()+RuntimeWorkPolicy.failureDelayMs(t)
            clear()
            RuntimeWorkPolicy.guarded({}) { failed(t) }
        } finally {
            if(trimHardware) { RuntimeWorkPolicy.guarded({}) { hardware.close() };trimHardware=false }
            synchronized(lock) {
                running=false
                // resolve() may enqueue between the empty poll and this finally.
                if(pending.isNotEmpty()) {
                    running=true
                    try { executor.execute(::drain) } catch(t:Throwable) {
                        running=false;clear()
                        retryAtMs=SystemClock.uptimeMillis()+RuntimeWorkPolicy.failureDelayMs(t)
                        RuntimeWorkPolicy.guarded({}) { failed(t) }
                    }
                }
            }
        }
    }

    private fun compute(request:Request):Bitmap {
        val source=request.source;val style=request.key.style;val geo=style.geometry
        if(SystemClock.uptimeMillis()<hardwareRetryAtMs)throw IllegalStateException("GPU backdrop is backing off")
        try {
            val size=ButtonBackdropBlur.workingSize(source.width,source.height,geo.screenWidth,geo.screenHeight,style.radius)
            val sigma=style.radius*(size[0]/geo.screenWidth+size[1]/geo.screenHeight)*.5f
            val image=hardware.render(source,size[0],size[1],sigma) { request.epoch!=epoch || !active() }
            com.coloros16.liquidglass.diagnostics.RuntimeDiagnostics.mark("blur_hardware", "GPU 背景模糊已产出图像")
            return image
        } catch(cancel:java.util.concurrent.CancellationException) {throw cancel}
          catch(error:Throwable) {
            hardwareRetryAtMs=SystemClock.uptimeMillis()+30_000L
            RuntimeWorkPolicy.guarded({}) {hardware.close()}
            throw error // Keep the last valid input; never run CPU blur as fallback.
        }
    }
}

/** RGBA half floats in the bitmap's declared sRGB space, with premultiplied channels. */
internal object FloatBackdropBitmap {
    // These are raw IEEE-754 binary16 bits written to a ByteBuffer, not widened numeric halves.
    // Android's debug probe verifies the resulting fractional channels and premultiplied alpha.
    @SuppressLint("HalfFloat")
    fun create(argb:FloatArray,width:Int,height:Int):Bitmap {
        require(argb.size==width*height*4)
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.RGBA_F16,true,ColorSpace.get(ColorSpace.Named.SRGB))
        try {
        val buffer=ByteBuffer.allocate(bitmap.byteCount).order(ByteOrder.nativeOrder())
        for(y in 0 until height) {
            buffer.position(y*bitmap.rowBytes)
            for(x in 0 until width) {
                val i=(y*width+x)*4;val alpha=argb[i].coerceIn(0f,255f)
                for(c in 1..3)buffer.putShort(Half.toHalf(argb[i+c].coerceIn(0f,alpha)/255f))
                buffer.putShort(Half.toHalf(alpha/255f))
            }
        }
        buffer.rewind();bitmap.copyPixelsFromBuffer(buffer)
        return bitmap
        } catch(t:Throwable) { bitmap.recycle();throw t }
    }
}
