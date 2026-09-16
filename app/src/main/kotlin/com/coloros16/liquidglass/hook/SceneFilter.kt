package com.coloros16.liquidglass.hook

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode

/**
 * Hook C（可选）：场景过滤 —— 仅对控制中心(scrim)、QS tile、通知卡片生效，
 * 隔离音量面板、关机弹窗等不应玻璃化的场景。
 *
 * 设计依据：doc/spec/04-技术方案.md 第 3 节（方案三 Hook C）
 * - 目标：PlatformBlurDrawable.getHostViewName() / drawableId
 * - 配置：Prefs 场景白名单（KEY_SCENE_WHITELIST，默认关闭过滤）
 *
 * ⚠️ 占位阶段：目标类名/方法名来自旧版反编译，待新版核对后再落地。
 */
object SceneFilter {

    fun install(api: XposedInterface, classLoader: ClassLoader) {
        try {
            val clazz = Class.forName(CLASS_PLATFORM_BLUR_DRAWABLE, false, classLoader)
            val method = clazz.getDeclaredMethod(METHOD_GET_HOST_VIEW_NAME)
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    // TODO(M3)：读取 getHostViewName() 返回值，按 Prefs 白名单放行/拦截
                    chain.proceed()
                }
        } catch (t: Throwable) {
            // 占位阶段：类/方法签名不匹配即跳过
        }
    }

    // ---- 旧版反编译确认，待新版核对 ----
    private const val CLASS_PLATFORM_BLUR_DRAWABLE = "com.oplus.posteffect.drawable.PlatformBlurDrawable"
    private const val METHOD_GET_HOST_VIEW_NAME = "getHostViewName"
}
