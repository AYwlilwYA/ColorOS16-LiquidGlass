/* DreamGlass contributors. GPL-3.0. COS17 native clock composition adapter. */
package com.coloros16.liquidglass.hook

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.coloros16.liquidglass.diagnostics.RuntimeDiagnostics
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.util.WeakHashMap

/** Discover the OEM's explicit clock/date effect mounts, rather than guessing from a
 * full-screen container's name. Keep the OEM shader as the inner effect: it may own
 * animated masks and transition alpha, but must not recolour our final glass output.
 * No OEM implementation or shader is copied here.
 */
internal object KeyguardNativeMounts {
    private val installed=WeakHashMap<Class<*>,Boolean>()
    private const val PREFIX="com.oplus.keyguard.clock."
    fun install(api:XposedInterface,loader:ClassLoader,register:(View)->Unit) {
        val coordinator=runCatching { Class.forName(PREFIX+"digital.ui.controller.DigitalClockColorCoordinator",false,loader) }.getOrNull()
        val helper=runCatching { Class.forName(PREFIX+"common.view.livecontent.effect.shader.glass.GlassEffectHelper",false,loader) }.getOrNull()
        if(coordinator!=null && installed[coordinator]!=true)runCatching {
            val method=coordinator.getDeclaredMethod("resolveGlassEffectTargets")
            api.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val result=chain.proceed()
                if(Looper.myLooper()==Looper.getMainLooper())(result as? List<*>)?.take(16)?.forEach { target ->
                    (target as? View)?.takeIf(::supported)?.let(register)
                }
                result
            }
            installed[coordinator]=true;api.deoptimize(method)
            coordinator.declaredMethods.filter { it.name=="buildColorRequests" }.forEach { api.deoptimize(it) }
        }.onFailure { report(it) }
        if(helper!=null && installed[helper]!=true)runCatching {
            val field=helper.getDeclaredField("effectMap").apply { isAccessible=true }
            val method=helper.getDeclaredMethod("setRenderEffectImpl")
            api.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                // Register before native View.setRenderEffect, so its latest shader is
                // captured and composed by the existing reversible setter interception.
                if(Looper.myLooper()==Looper.getMainLooper())runCatching {
                    (field.get(chain.getThisObject()) as? Map<*,*>)?.keys?.take(16)?.forEach { target ->
                        (target as? View)?.takeIf(::supported)?.let(register)
                    }
                }.onFailure { report(it) }
                chain.proceed()
            }
            installed[helper]=true;api.deoptimize(method)
            helper.declaredMethods.filter { it.name=="access\$setRenderEffectImpl" }.forEach { api.deoptimize(it) }
            runCatching {
                val caller=Class.forName(helper.name+"\$refreshRenderEffect\$applied\$1",false,loader)
                caller.declaredMethods.filter { it.name=="invoke" }.forEach { api.deoptimize(it) }
            }
            RuntimeDiagnostics.mark("keyguard_native_mount_hook","已接入主题时钟最终父层效果入口；实际画面待核验")
        }.onFailure { report(it) }
    }
    private fun supported(v:View)=v is ViewGroup && v.javaClass.name==PREFIX+"digital.widget.MyCustomizedFrameLayout" &&
        Application.getProcessName()=="com.android.systemui" && v.background==null
    private fun report(t:Throwable) { RuntimeDiagnostics.mark("keyguard_native_mount_error","${t.javaClass.simpleName}: ${t.message}") }
}
