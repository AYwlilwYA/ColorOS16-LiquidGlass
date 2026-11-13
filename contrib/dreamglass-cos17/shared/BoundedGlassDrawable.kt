/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.View
import java.lang.ref.WeakReference

/** Background replacement must obey Drawable geometry/state and fall back if the
 * GPU material has no source. Clone ordinary shared backgrounds when necessary;
 * an uncloneable native backdrop keeps the current bounds, since rolling back its
 * live RenderNode before playback would move the already-recorded background.
 */
internal open class BoundedGlassDrawable(
    host:View,
    original:Drawable?,
    private val enabled:()->Boolean,
    private val render:(Canvas,View,Rect)->Boolean,
    private val rendered:()->Unit={},
    private val failure:(Throwable)->Unit={},
    private val forwardNativeCallbacks:Boolean=false,
    private val hideNativeBlurOnGlass:Boolean=false,
):Drawable(), Drawable.Callback {
    private fun <T> nativeValue(fallback:T, action:()->T):T = try {action()} catch(error:Throwable) {
        runCatching {failure(error)};fallback
    }
    var orig:Drawable?=original
        set(value) {
            if (field===value || value===this) return
            if (nativeSuppressed) nativeValue(Unit) {field?.alpha=opacity;Unit}
            nativeSuppressed=false
            if(field!==value && field?.callback===this)field?.callback=null
            field=value
            bindNativeCallback()
        }
    private val target=WeakReference(host)
    private var opacity=nativeValue(255) {original?.alpha ?: 255}
    private var filter:ColorFilter?=nativeValue(null as ColorFilter?) {original?.colorFilter}
    private var nativeSuppressed=false
    private val drawing=ThreadLocal<Boolean>()
    private val layerPaint=Paint()
    private fun bindNativeCallback() {if(forwardNativeCallbacks)orig?.callback=this}
    override fun onBoundsChange(bounds:Rect) {bindNativeCallback()}
    override fun invalidateDrawable(who:Drawable) {invalidateSelf()}
    override fun scheduleDrawable(who:Drawable, what:Runnable, `when`:Long) {scheduleSelf(what,`when`)}
    override fun unscheduleDrawable(who:Drawable, what:Runnable) {unscheduleSelf(what)}
    override fun draw(canvas:Canvas) {
        val area=Rect(bounds)
        if(area.isEmpty || opacity==0 || drawing.get()==true)return
        drawing.set(true)
        var save=-1
        try {
            bindNativeCallback()
            save=canvas.save()
            canvas.clipRect(area)
            val host=target.get()
            // Apply opacity/filter once. Native fallback already owns its own paint,
            // so it must not be drawn inside this GPU material's alpha layer.
            val effectSave=if(opacity<255 || filter!=null) {
                layerPaint.alpha=opacity;layerPaint.colorFilter=filter
                canvas.saveLayer(RectF(area),layerPaint)
            } else canvas.save()
            val success=try {
                if(host!=null && canvas.isHardwareAccelerated && enabled())render(canvas,host,area) else false
            } catch(error:Throwable) {runCatching {failure(error)};false}
            finally {canvas.restoreToCount(effectSave)}
            if(success) {
                // BackgroundBlurDrawable registers a separate compositor blur region.
                // Omitting draw() alone does not retire the previous native region.
                if(hideNativeBlurOnGlass) orig?.let {native ->
                    nativeValue(Unit) {if(native.alpha!=0)native.alpha=0;nativeSuppressed=true}
                }
                runCatching {rendered()}
            } else orig?.let {native ->
                // Native blur drawables reject setColorFilter, including null.
                // A capture miss is normal during expansion: never let optional
                // paint synchronization prevent the original backdrop drawing.
                fun attempt(action:()->Unit) {
                    nativeValue(Unit,action)
                }
                if(nativeSuppressed) attempt {native.alpha=opacity;nativeSuppressed=false}
                val fallback=if(native.bounds==area)native else
                    runCatching {native.constantState?.newDrawable()?.mutate()}.getOrNull() ?: native
                if(fallback!==native) {
                    attempt {fallback.state=native.state;fallback.level=native.level}
                }
                attempt {if(fallback.alpha!=opacity)fallback.alpha=opacity}
                attempt {if(fallback.colorFilter!==filter)fallback.colorFilter=filter}
                attempt {fallback.bounds=area;fallback.draw(canvas)}
            }
        } finally {try {if(save>=0)canvas.restoreToCount(save)} finally {drawing.remove()}}
    }
    override fun setAlpha(alpha:Int) {
        opacity=alpha.coerceIn(0,255)
        if(opacity==0 && hideNativeBlurOnGlass) nativeValue(Unit) {orig?.alpha=0;Unit}
        invalidateSelf()
    }
    override fun getAlpha():Int=opacity
    override fun setColorFilter(colorFilter:ColorFilter?) {filter=colorFilter;invalidateSelf()}
    override fun getColorFilter():ColorFilter?=filter
    override fun setVisible(visible:Boolean,restart:Boolean):Boolean {
        val changed=super.setVisible(visible,restart)
        val nativeChanged=if(forwardNativeCallbacks)nativeValue(false) {orig?.setVisible(visible,restart)==true} else false
        return changed || nativeChanged
    }
    override fun setHotspot(x:Float,y:Float) {if(forwardNativeCallbacks)nativeValue(Unit) {orig?.setHotspot(x,y);Unit}}
    override fun setHotspotBounds(left:Int,top:Int,right:Int,bottom:Int) {
        super.setHotspotBounds(left,top,right,bottom)
        if(forwardNativeCallbacks)nativeValue(Unit) {orig?.setHotspotBounds(left,top,right,bottom);Unit}
    }
    override fun getPadding(padding:Rect):Boolean=nativeValue(false) {orig?.getPadding(padding) ?: super.getPadding(padding)}
    override fun getIntrinsicWidth():Int=nativeValue(-1) {orig?.intrinsicWidth ?: -1}
    override fun getIntrinsicHeight():Int=nativeValue(-1) {orig?.intrinsicHeight ?: -1}
    override fun isStateful():Boolean=nativeValue(false) {orig?.isStateful==true}
    override fun onStateChange(state:IntArray):Boolean=nativeValue(false) {orig?.setState(state)==true}
    override fun onLevelChange(level:Int):Boolean=nativeValue(false) {orig?.setLevel(level)==true}
    override fun getOutline(outline:Outline) {
        // Outline queries can occur before a background has any bounds and outside
        // draw's exception boundary. Never move a live native blur RenderNode here.
        outline.setRect(bounds)
        nativeValue(Unit) {orig?.takeIf {it.bounds==bounds}?.getOutline(outline);Unit}
    }
    @Deprecated("Deprecated in Java") override fun getOpacity():Int=PixelFormat.TRANSLUCENT
}
