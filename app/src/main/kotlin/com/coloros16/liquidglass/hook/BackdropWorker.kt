/* Dense preblur contributed from DreamGlass, GPL-3.0; 2026-10-28. */
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
internal object BackdropBinding {
    data class Bound(val bitmap: Bitmap, val sourceRect: RectF,
        val params: LiquidGlassShader.Params, val pending: Boolean = false)

    fun resolve(worker: BackdropWorker, source: Bitmap, screen: RectF, sourceRect: RectF,
                sourceIsSnapshot: Boolean, params: LiquidGlassShader.Params): Bound {
        if (!sourceIsSnapshot) return Bound(source, sourceRect, params)
        val sigma = BackdropBlur.materialSigma(params.blurRadius)
        if (!sigma.isFinite() || sigma <= 0f) return Bound(source, sourceRect, params)
        val bitmap = worker.resolve(source, screen, sigma)
        if (bitmap == null) {
            // The old wide sparse kernel itself makes a repeated grid. During initial computation
            // use a compact, subpixel-spaced kernel rather than displaying that artifact for a frame.
            val fallback = minOf(params.blurRadius, 1.5f)
            return Bound(source, sourceRect, params.copy(blurRadius = fallback, sourcePreblurred = false), true)
        }
        val sx = bitmap.width.toFloat()/source.width; val sy = bitmap.height.toFloat()/source.height
        val rect = RectF(sourceRect.left*sx, sourceRect.top*sy, sourceRect.right*sx, sourceRect.bottom*sy)
        // Preserve the positive blur branch so dispersion does not change when the worker finishes.
        return Bound(bitmap, rect, params.copy(sourcePreblurred = true))
    }
}

/** Four bounded style slots, one running computation, latest pending capture per style.
 * All pixels/filtering are on a background thread; published F16 bitmaps are never recycled. */
internal class BackdropWorker(private val invalidate:()->Unit,private val failed:(Throwable)->Unit) {
    private fun failureDelayMs(error:Throwable) = if(error is OutOfMemoryError)30_000L else 2_000L
    private fun guarded(report:(Throwable)->Unit, action:()->Unit) {
        try { action() } catch(error:Throwable) { try { report(error) } catch(_:Throwable) { } }
    }
    private data class Geometry(val width:Int,val height:Int,val left:Float,val top:Float,val screenWidth:Float,val screenHeight:Float)
    private data class Style(val geometry:Geometry,val radius:Float)
    private data class Key(val source:Bitmap,val generation:Int,val style:Style)
    private data class Request(val key:Key,val source:Bitmap,val epoch:Long)
    private val lock=Any()
    private val executor=java.util.concurrent.ThreadPoolExecutor(1,1,15L,java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.LinkedBlockingQueue<Runnable>(1),
        { task -> Thread(task,"LiquidGlass-dense-blur").apply { isDaemon=true } }).apply { allowCoreThreadTimeOut(true) }
    private val requested=LinkedHashMap<Style,Key>(4,.75f,true)
    private val pending=LinkedHashMap<Style,Request>()
    private val ready=HashMap<Style,Bitmap>()
    private var geometry:Geometry?=null
    private var running=false
    @Volatile private var epoch=0L
    private var lastComputeAtMs=0L
    @Volatile private var retryAtMs=0L

    fun resolve(source:Bitmap,screen:RectF,radius:Float):Bitmap? {
        if(source.isRecycled || screen.width()<=0 || screen.height()<=0 || !radius.isFinite() || radius<=0)return null
        if(SystemClock.uptimeMillis()<retryAtMs)return null
        val geo=Geometry(source.width,source.height,screen.left,screen.top,screen.width(),screen.height())
        val style=Style(geo,radius);val key=Key(source,source.generationId,style)
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
                        retryAtMs=SystemClock.uptimeMillis()+failureDelayMs(t)
                        guarded({}) { failed(t) }
                    }
                }
            }
            return ready[style]
        }
    }

    /** Drop references only: published bitmaps may still be used by recorded RenderNodes. */
    fun clear() = synchronized(lock) {
        epoch++;geometry=null;requested.clear();pending.clear();ready.clear()
    }

    private fun drain() {
        guarded({}) { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
        // Keep the last good image while coalescing new frames. Cap expensive dense filtering at
        // ten jobs/second for the whole worker, not ten per button. First request is immediate.
        try {
            while(true) {
                val wait=100L-(SystemClock.uptimeMillis()-lastComputeAtMs)
                if(wait>0)Thread.sleep(wait)
                val request=synchronized(lock) {
                    val next=pending.entries.firstOrNull() ?: return
                    pending.remove(next.key);next.value
                }
                if(request.epoch!=epoch)continue
                lastComputeAtMs=SystemClock.uptimeMillis()
                try {
                    val bitmap=compute(request)
                    val publish=synchronized(lock) {
                        val style=request.key.style
                        if(request.epoch==epoch && geometry==style.geometry && requested.containsKey(style)) {
                            ready[style]=bitmap;true
                        } else false
                    }
                    if(publish) guarded(failed, invalidate)
                    else bitmap.recycle() // Never published, therefore no shader can own it.
                } catch(_: java.util.concurrent.CancellationException) {
                    // Screen-off/geometry change: abandon intermediate pixels, no retry or log.
                } catch(t:Throwable) {
                    retryAtMs=SystemClock.uptimeMillis()+failureDelayMs(t)
                    clear()
                    guarded({}) { failed(t) }
                }
            }
        } catch(t:Throwable) {
            retryAtMs=SystemClock.uptimeMillis()+failureDelayMs(t)
            clear()
            guarded({}) { failed(t) }
        } finally {
            synchronized(lock) {
                running=false
                // resolve() may enqueue between the empty poll and this finally.
                if(pending.isNotEmpty()) {
                    running=true
                    try { executor.execute(::drain) } catch(t:Throwable) {
                        running=false;clear()
                        retryAtMs=SystemClock.uptimeMillis()+failureDelayMs(t)
                        guarded({}) { failed(t) }
                    }
                }
            }
        }
    }

    private fun compute(request:Request):Bitmap {
        val source=request.source;val style=request.key.style;val geo=style.geometry
        var small=if(source.config==Bitmap.Config.HARDWARE)(source.copy(Bitmap.Config.ARGB_8888,false) ?: error("Hardware snapshot copy unavailable")) else source
        try {
            val size=BackdropBlur.workingSize(source.width,source.height,geo.screenWidth,geo.screenHeight,style.radius)
            while(small.width/2>=size[0] && small.height/2>=size[1] && (small.width>size[0] || small.height>size[1])) {
                val next=Bitmap.createScaledBitmap(small,(small.width/2).coerceAtLeast(1),(small.height/2).coerceAtLeast(1),true)
                if(small!==source && small!==next)small.recycle();small=next
            }
            if(small.width!=size[0] || small.height!=size[1]) {
                val next=Bitmap.createScaledBitmap(small,size[0],size[1],true)
                if(small!==source && small!==next)small.recycle();small=next
            }
            val pixels=IntArray(small.width*small.height)
            small.getPixels(pixels,0,small.width,0,0,small.width,small.height)
            val sigma=style.radius*(small.width/geo.screenWidth+small.height/geo.screenHeight)*.5f
            return FloatBackdropBitmap.create(BackdropBlur.applyPremultiplied(pixels,small.width,small.height,sigma) { request.epoch!=epoch },small.width,small.height)
        } finally { if(small!==source)small.recycle() }
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
