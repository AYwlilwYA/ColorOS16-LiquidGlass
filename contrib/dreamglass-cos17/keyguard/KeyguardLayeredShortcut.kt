/* DreamGlass contributors, 2026-10-30. GPL-3.0. COS17 shortcut composition. */
package com.coloros16.liquidglass.hook
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.widget.ImageView
import com.coloros16.liquidglass.diagnostics.RuntimeDiagnostics
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Keep native layer objects/types and their animation/controller state intact. A component
 * overlay composites the glass circle and the exact native foreground icon on top. No global
 * Drawable hooks, bitmap readback, CPU blur, new window, or repeating animation callback.
 */
internal object KeyguardLayeredShortcut {
    private val owners=WeakHashMap<View,Composite>()
    private const val NATIVE="com.oplus.keyguard.ui.base.widget.OplusLaunchableImageView"
    private fun nativeType(v:View):Class<*>? {
        var type:Class<*>?=v.javaClass
        while(type!=null) { if(type.name==NATIVE)return type;type=type.superclass };return null
    }
    fun supported(v:View)=v is ImageView && nativeType(v)!=null &&
        (v.drawable as? LayerDrawable)?.numberOfLayers?.let { it>=3 }==true
    fun bind(view:ImageView,replacement:Drawable,prepare:(Rect)->Unit):Boolean {
        if(!supported(view))return false
        return bindLayers(view,replacement,prepare)
    }
    // Same compositor exercised by the synthetic vendor-layer probe; no host hooks required.
    internal fun bindLayers(view:ImageView,replacement:Drawable,prepare:(Rect)->Unit):Boolean {
        if((view.drawable as? LayerDrawable)?.numberOfLayers?.let { it>=3 }!=true)return false
        val old=owners[view]
        if(old!=null && old.material===replacement) {
            old.prepare=prepare;old.setBounds(0,0,view.width,view.height);old.invalidateSelf();return true
        }
        unbind(view)
        val composite=Composite(view,replacement,prepare)
        composite.setBounds(0,0,view.width,view.height);owners[view]=composite
        view.overlay.add(composite)
        RuntimeDiagnostics.mark("keyguard_shortcut_layers","按钮合成层已绑定：GPU 玻璃背景与原生图标")
        return true
    }
    fun unbind(view:View) { owners.remove(view)?.let { view.overlay.remove(it) } }
    private class Composite(view:ImageView,val material:Drawable,var prepare:(Rect)->Unit):Drawable() {
        val owner=WeakReference(view)
        var drawAlpha=255
        val imageScale=runCatching { (nativeType(view) ?: view.javaClass).getMethod("getImageScale") }.getOrNull()
        val circleRadius=runCatching { (nativeType(view) ?: view.javaClass).getMethod("getCircleRadius") }.getOrNull()
        override fun draw(canvas:Canvas) {
            val view=owner.get() ?: return
            if(!canvas.isHardwareAccelerated || !view.isShown || drawAlpha==0)return
            // The plugin switches to a different morphing coordinate system when launching.
            // Let its original launch renderer own that short transition completely.
            val scale=runCatching { (imageScale?.invoke(view) as? Number)?.toFloat() ?: 1f }.getOrDefault(1f)
            val radius=runCatching { (circleRadius?.invoke(view) as? Number)?.toFloat() ?: 0f }.getOrDefault(0f)
            if(kotlin.math.abs(scale-1f)>.02f || radius>maxOf(view.width,view.height)*.75f)return
            val native=view.drawable as? LayerDrawable ?: return
            if(native.numberOfLayers<3)return
            val background=native.getDrawable(0);val rect=background.bounds
            // COS17's native material deliberately clears the legacy background layer.
            // That alpha is a renderer ownership flag, not the visibility of this button.
            if(rect.isEmpty || view.imageAlpha==0)return
            val outer=canvas.save()
            try {
                canvas.translate(view.paddingLeft.toFloat(),view.paddingTop.toFloat());canvas.concat(view.imageMatrix)
                prepare(rect)
                val alpha=view.imageAlpha*drawAlpha/255
                val save=if(alpha<255)canvas.saveLayerAlpha(rect.left.toFloat(),rect.top.toFloat(),rect.right.toFloat(),rect.bottom.toFloat(),alpha) else canvas.save()
                try {
                    canvas.translate(rect.left.toFloat(),rect.top.toFloat())
                    material.setBounds(0,0,rect.width(),rect.height());material.draw(canvas)
                } finally { canvas.restoreToCount(save) }
                native.getDrawable(2).draw(canvas)
                RuntimeDiagnostics.mark("keyguard_shortcut_draw","按钮玻璃背景与原生图标已实际合成绘制")
            } catch(error:Throwable) {
                RuntimeDiagnostics.mark("keyguard_shortcut_error","${error.javaClass.simpleName}: ${error.message}")
            } finally { canvas.restoreToCount(outer) }
        }
        override fun setAlpha(alpha:Int) { drawAlpha=alpha;invalidateSelf() }
        override fun setColorFilter(filter:ColorFilter?) {}
        @Deprecated("Deprecated in Android") override fun getOpacity()=PixelFormat.TRANSLUCENT
    }
}
