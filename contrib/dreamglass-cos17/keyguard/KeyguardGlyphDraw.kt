/* DreamGlass contributors. GPL-3.0. Native glyph commands, private GPU effect node. */
package com.coloros16.liquidglass.hook

import android.graphics.Canvas
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import com.coloros16.liquidglass.diagnostics.RuntimeDiagnostics
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap

/** The COS17 theme maintains its own RT text paint/display lists. Record the actual native
 * onDraw once into our own child node, filter that node, and submit it in the same draw call.
 * No font reconstruction, duplicate glyph, pixel readback, bitmap snapshot, worker or timer.
 * The native View's effects and its parents continue owning transitions and visibility.
 */
internal object KeyguardGlyphDraw {
    private const val NATIVE="com.oplus.keyguard.clock.digital.widget.MyCustomizedTextView"
    private var api:XposedInterface?=null
    private val methods=HashSet<Method>()
    private val owners=WeakHashMap<View,Slot>()
    fun install(api:XposedInterface) { this.api=api }
    fun owns(view:View?)=view!=null && owners.containsKey(view)
    private fun supported(view:View):Boolean {
        if(view !is TextView)return false
        var type:Class<*>?=view.javaClass
        while(type!=null) { if(type.name==NATIVE)return true;type=type.superclass }
        return false
    }
    private fun drawMethod(view:View):Method? {
        var type:Class<*>?=view.javaClass
        while(type!=null && !type.name.startsWith("android.")) {
            val method=runCatching { type!!.getDeclaredMethod("onDraw",Canvas::class.java) }.getOrNull()
            if(method!=null && !Modifier.isAbstract(method.modifiers) && !Modifier.isStatic(method.modifiers))return method
            type=type.superclass
        }
        return null
    }
    fun bind(view:View,effect:RenderEffect):Boolean {
        if(!supported(view) || Looper.myLooper()!=Looper.getMainLooper())return false
        val api=api ?: return false
        if(!owners.containsKey(view) && owners.size>=64)return false
        val method=drawMethod(view) ?: return false
        if(method !in methods) {
            if(methods.size>=32)return false
            api.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val target=chain.getThisObject() as? View
                val canvas=chain.getArg(0) as? Canvas
                val slot=owners[target]
                if(slot==null || target==null || canvas==null || !canvas.isHardwareAccelerated ||
                    Looper.myLooper()!=Looper.getMainLooper() || !slot.available())return@intercept chain.proceed()
                var invoked=false
                try {
                    val result=slot.draw(canvas,target.width,target.height) { recording ->
                        invoked=true
                        val args=chain.getArgs().toTypedArray();args[0]=recording
                        chain.proceed(args)
                    }
                    RuntimeDiagnostics.mark("keyguard_theme_glyph_draw","主题原生字形已在独立 GPU 节点完成玻璃合成")
                    result
                } catch(error:Throwable) {
                    slot.block()
                    RuntimeDiagnostics.mark("keyguard_theme_glyph_error","${error.javaClass.simpleName}: ${error.message}")
                    // Only invoke the native callback if recording failed before it ran.
                    // Repeating an already executed native onDraw can mutate vendor RT state.
                    if(!invoked)chain.proceed() else throw error
                }
            }
            methods.add(method)
            api.deoptimize(method)
        }
        val slot=owners[view] ?: Slot().also { owners[view]=it }
        slot.effect=effect
        view.invalidate()
        return true
    }
    fun unbind(view:View) {
        owners.remove(view)?.let {
            it.close()
            // Its old display list can still reference the now-cleared child node.
            // Force a native re-record even if the View's own effect was already null.
            view.invalidate()
        }
    }

    /** Shared production/probe renderer: original draw exactly once, no repeated cache capture. */
    internal class Slot:AutoCloseable {
        private val node=RenderNode("DreamGlass theme glyph")
        var effect:RenderEffect?=null
        private var recording=false
        private var retryAt=0L
        var nativeDraws=0;private set
        fun available()=!recording && effect!=null && SystemClock.uptimeMillis()>=retryAt
        fun block() { retryAt=SystemClock.uptimeMillis()+30_000L }
        fun draw(canvas:Canvas,width:Int,height:Int,native:(Canvas)->Any?):Any? {
            check(canvas.isHardwareAccelerated && width>0 && height>0 && !recording)
            node.setPosition(0,0,width,height)
            val raw=node.beginRecording(width,height)
            val result:Any?
            recording=true
            try { nativeDraws++;result=native(raw) }
            finally { try { node.endRecording() } finally { recording=false } }
            try {
                node.setRenderEffect(effect);canvas.drawRenderNode(node)
            } catch(error:Throwable) {
                // Keep the already recorded original glyph; never call native onDraw twice.
                block();node.setRenderEffect(null);canvas.drawRenderNode(node)
                RuntimeDiagnostics.mark("keyguard_theme_glyph_fallback","GPU 材质提交失败，已回退本帧原生字形")
            }
            return result
        }
        override fun close() { effect=null;node.setRenderEffect(null);node.discardDisplayList() }
    }
}
