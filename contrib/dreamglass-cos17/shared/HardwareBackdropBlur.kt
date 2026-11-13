/* Accelerated bitmap-blur sequence adapted from Android Developers / AOSP,
 * https://developer.android.com/guide/topics/renderscript/migrate
 * Documentation code samples: Apache License 2.0, https://developer.android.com/license
 * DreamGlass adaptation: fresh output queues, cancellation and bounded worker, 2026-10-29.
 */
package com.coloros16.liquidglass.hook

import android.graphics.*
import android.hardware.HardwareBuffer
import android.media.ImageReader
import java.util.concurrent.CancellationException

/** Android's accelerated bitmap-blur path, used only on the serial backdrop worker.
 * Each result owns a fresh buffer queue: published hardware bitmaps are never producer-reused.
 * Balanced mode has an RGBA8888 output; explicit precision mode retains the CPU/F16 path.
 */
internal class HardwareBackdropBlur : AutoCloseable {
    private var renderer: HardwareRenderer? = null
    private val node = RenderNode("DreamGlass backdrop")
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun render(source: Bitmap, width: Int, height: Int, sigma: Float, cancelled: () -> Boolean): Bitmap =
        render(width,height,sigma,cancelled) { canvas ->
            canvas.drawBitmap(source,null,Rect(0,0,width,height),paint)
        }
    /** Recorded on the UI thread while a borrowed vendor bitmap is valid. The display list
     * retains its native image; no worker may dereference a recycled host Bitmap later. */
    fun render(source:RenderNode,sourceWidth:Int,sourceHeight:Int,width:Int,height:Int,sigma:Float,cancelled:()->Boolean):Bitmap =
        render(width,height,sigma,cancelled) { canvas ->
            canvas.scale(width.toFloat()/sourceWidth,height.toFloat()/sourceHeight)
            canvas.drawRenderNode(source)
        }
    private fun render(width:Int,height:Int,sigma:Float,cancelled:()->Boolean,record:(Canvas)->Unit):Bitmap {
        if (cancelled()) throw CancellationException()
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        val engine = renderer ?: HardwareRenderer().apply {
            setName("DreamGlass backdrop");isOpaque=false;setContentRoot(node);renderer=this
        }
        try {
            engine.setSurface(reader.surface)
            node.setPosition(0, 0, width, height)
            node.setRenderEffect(RenderEffect.createBlurEffect(sigma.coerceAtLeast(.01f),
                sigma.coerceAtLeast(.01f), Shader.TileMode.CLAMP))
            val canvas = node.beginRecording(width, height)
            try {
                canvas.drawColor(Color.TRANSPARENT, BlendMode.CLEAR)
                record(canvas)
            } finally { node.endRecording() }
            if (cancelled()) throw CancellationException()
            val result=engine.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            check(result and (HardwareRenderer.SYNC_LOST_SURFACE_REWARD_IF_FOUND or
                HardwareRenderer.SYNC_CONTEXT_IS_STOPPED or HardwareRenderer.SYNC_FRAME_DROPPED)==0) {
                "Hardware backdrop draw failed: $result"
            }
            val image=reader.acquireNextImage() ?: error("Hardware backdrop image unavailable")
            try {
                val buffer=image.hardwareBuffer ?: error("Hardware backdrop buffer unavailable")
                try {
                    if(cancelled())throw CancellationException()
                    return Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB))
                        ?: error("Hardware backdrop bitmap unavailable")
                } finally { buffer.close() }
            } finally { image.close() }
        } finally {
            engine.setSurface(null)
            reader.close();node.discardDisplayList()
        }
    }
    override fun close() { renderer?.destroy();renderer=null;node.discardDisplayList() }
}
