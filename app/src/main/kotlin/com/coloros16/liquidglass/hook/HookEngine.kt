package com.coloros16.liquidglass.hook

import com.coloros16.liquidglass.glass.GlassRegistry
import com.coloros16.liquidglass.hook.spec.HookSpec
import com.coloros16.liquidglass.hook.spec.Matcher
import com.coloros16.liquidglass.log.Lg
import android.os.SystemClock
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.util.concurrent.ConcurrentHashMap

/**
 * Hook 挂载引擎：resolve（类/方法/参数）→ hook 包装链。
 * Effect 全权拥有 proceed 决策；effect 异常重新抛出，交给 ExceptionMode.PROTECTIVE 兜底走原方法，绝不吞绘制。
 */
object HookEngine {

    private const val TAG = "LiquidGlass"

    /** hook 回调体：返回值即 intercept 返回值（null = void / 跳过）。 */
    fun interface HookEffect {
        fun intercept(chain: XposedInterface.Chain): Any?
    }

    /** 一条待挂载请求：spec（声明）+ effect（执行）+ 挂载期元数据。 */
    class MountRequest(
        val spec: HookSpec,
        val effect: HookEffect,
        val precondition: (() -> Boolean)? = null,
        val preconditionWarn: String? = null,
        val mountedLog: String,
        val failedLog: String,
        val registerClass: String? = null,
    )

    private val effectErrorAt = ConcurrentHashMap<String, Long>()

    fun mountAll(api: XposedInterface, classLoader: ClassLoader, requests: List<MountRequest>) {
        for (request in requests) {
            mount(api, classLoader, request)
        }
    }

    fun mount(api: XposedInterface, classLoader: ClassLoader, request: MountRequest) {
        val spec = request.spec
        if (!spec.enabled) {
            Lg.i(TAG, "engine: skip disabled spec ${spec.id}")
            return
        }
        if (request.precondition?.invoke() == false) {
            request.preconditionWarn?.let { Lg.w(TAG, it) }
            return
        }
        try {
            val method = Matcher.resolveMethod(classLoader, spec.match)
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        request.effect.intercept(chain)
                    } catch (t: Throwable) {
                        logEffectError(spec, t)
                        throw t
                    }
                }
            Lg.i(TAG, request.mountedLog)
            request.registerClass?.let { GlassRegistry.registerClass(it) }
        } catch (t: Throwable) {
            Lg.e(TAG, request.failedLog, t)
        }
    }

    private fun logEffectError(spec: HookSpec, t: Throwable) {
        val now = SystemClock.uptimeMillis()
        val last = effectErrorAt[spec.id] ?: 0L
        if (now - last < spec.log.throttleMs) return
        effectErrorAt[spec.id] = now
        Lg.i(TAG, "engine ${spec.id}: effect error, PROTECTIVE proceeds original: $t")
    }
}
