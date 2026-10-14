package com.coloros16.liquidglass.hook

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode

/**
 * Hook C（可选）：场景过滤 —— 仅对控制中心(scrim)、QS tile、通知卡片生效，
 * 隔离音量面板、关机弹窗等不应玻璃化的场景。
 *
 * 设计依据：doc/spec/04-技术方案.md 第 3 节（方案三 Hook C）
 * - 目标：PlatformBlurDrawable.getHostViewName() / drawableId
 * - 配置：待定（原 Prefs 场景白名单键已于 2026-09-17 作为死键清理，见 Prefs.kt）
 *
 * [spec/114 #49] 原常量 com.oplus.posteffect.drawable.PlatformBlurDrawable 三版都不存在。
 * 实际类是 oplusos 平台包，但它只有 `Function0 getHostViewName` **字段**、无同名方法，
 * 故本版本只做候选链解析 + 日志，不挂载（场景过滤逻辑仍是 M3 TODO，现役零回归）。
 */
object SceneFilter {

    private const val TAG = "LiquidGlass"

    // [spec/114 #49] 三版实际存在的类（oplusos 平台包）→ 旧 FQCN（三版都不存在，保留为候选）
    private val CLASS_PLATFORM_BLUR_DRAWABLE_CHAIN = arrayOf(
        "com.oplusos.systemui.common.blurability.platformblur.PlatformBlurDrawable",
        "com.oplus.posteffect.drawable.PlatformBlurDrawable",
    )
    private const val METHOD_GET_HOST_VIEW_NAME = "getHostViewName"

    fun install(api: XposedInterface, classLoader: ClassLoader) {
        val clazz = resolveClass(classLoader) ?: run {
            Log.i(TAG, "lg-hook: SceneFilter 目标类缺失，降级（不挂载）")
            return
        }
        val method = try {
            clazz.getDeclaredMethod(METHOD_GET_HOST_VIEW_NAME)
        } catch (t: Throwable) {
            Log.i(TAG, "lg-hook: SceneFilter ${clazz.name} 无 $METHOD_GET_HOST_VIEW_NAME() 方法（只有 Function0 字段），M3 未启用，不挂载")
            return
        }
        try {
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    // TODO(M3)：读取 getHostViewName() 返回值，按 Prefs 白名单放行/拦截
                    chain.proceed()
                }
            Log.i(TAG, "lg-hook: SceneFilter 挂载 @ ${clazz.name}#$METHOD_GET_HOST_VIEW_NAME（逻辑仍为 M3 TODO，透传）")
        } catch (t: Throwable) {
            // 类/方法签名不匹配即跳过
            Log.i(TAG, "lg-hook: SceneFilter 挂载失败，降级（不挂载）")
        }
    }

    private fun resolveClass(classLoader: ClassLoader): Class<*>? {
        for (name in CLASS_PLATFORM_BLUR_DRAWABLE_CHAIN) {
            try {
                return Class.forName(name, false, classLoader)
            } catch (t: Throwable) {
                // 候选 miss，试下一候选
            }
        }
        return null
    }
}
