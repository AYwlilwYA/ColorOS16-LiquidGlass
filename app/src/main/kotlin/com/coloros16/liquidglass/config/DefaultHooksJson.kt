package com.coloros16.liquidglass.config

import com.coloros16.liquidglass.hook.BlurDrawHook
import com.coloros16.liquidglass.hook.spec.ChannelSpec
import com.coloros16.liquidglass.hook.spec.EffectSpec
import com.coloros16.liquidglass.hook.spec.HookSpec
import com.coloros16.liquidglass.hook.spec.MatchSpec

/** 出厂预置 hook 规格表（Kotlin 常量表形态；JSON 覆盖解析见 ConfigStore.parseSpecs）。 */
object DefaultHooksJson {

    private const val CANVAS = "android.graphics.Canvas"
    private const val DRAWABLE = "android.graphics.drawable.Drawable"

    val DEFAULT_HOOK_SPECS: List<HookSpec> = listOf(
        HookSpec(
            id = "autoBlur.map",
            match = MatchSpec(
                className = BlurDrawHook.CLASS_AUTO_BLUR_DRAWABLE,
                methodName = BlurDrawHook.METHOD_DRAW,
                params = listOf(CANVAS),
            ),
            channel = ChannelSpec(type = "background", hookPoint = "draw"),
            effect = EffectSpec(type = "registerRegionScrim"),
        ),
        HookSpec(
            id = "notif.cardMap",
            match = MatchSpec(
                className = BlurDrawHook.CLASS_NBV_EXT_IMP,
                methodName = BlurDrawHook.METHOD_DRAW_NOTIF_BG,
                params = listOf(CANVAS, DRAWABLE),
            ),
            channel = ChannelSpec(type = "familyThroat", hookPoint = "draw"),
            effect = EffectSpec(type = "registerNotifCardSuppressMaterial"),
        ),
        HookSpec(
            id = "platform.suppressBlurMaskColor",
            match = MatchSpec(
                className = BlurDrawHook.CLASS_PLATFORM_BLUR_DRAWABLE,
                methodName = BlurDrawHook.METHOD_DRAW,
                params = listOf(CANVAS),
            ),
            channel = ChannelSpec(type = "suppress", hookPoint = "draw"),
            effect = EffectSpec(type = "suppressField"),
        ),
        HookSpec(
            id = "posteffect.drawBlurShader",
            match = MatchSpec(
                className = BlurDrawHook.CLASS_BASE_DRAWABLE,
                methodName = BlurDrawHook.METHOD_DRAW_BLUR_SHADER,
                params = listOf(CANVAS),
                declared = true,
            ),
            channel = ChannelSpec(type = "render", hookPoint = "drawBlurShader"),
            effect = EffectSpec(type = "glassDraw"),
        ),
        HookSpec(
            id = "posteffect.onDrawContent.blend",
            match = MatchSpec(
                className = BlurDrawHook.CLASS_BLEND_DRAWABLE,
                methodName = BlurDrawHook.METHOD_ON_DRAW_CONTENT,
                params = listOf(CANVAS),
                declared = true,
            ),
            channel = ChannelSpec(type = "renderFallback", hookPoint = "onDrawContent"),
            effect = EffectSpec(type = "glassFallback"),
        ),
        HookSpec(
            id = "posteffect.onDrawContent.base",
            match = MatchSpec(
                className = BlurDrawHook.CLASS_BASE_DRAWABLE,
                methodName = BlurDrawHook.METHOD_ON_DRAW_CONTENT,
                params = listOf(CANVAS),
                declared = true,
            ),
            channel = ChannelSpec(type = "renderFallback", hookPoint = "onDrawContent"),
            effect = EffectSpec(type = "glassFallback"),
        ),
    )
}
