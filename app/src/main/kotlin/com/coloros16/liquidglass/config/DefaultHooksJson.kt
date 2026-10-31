package com.coloros16.liquidglass.config

import com.coloros16.liquidglass.hook.BlurDrawHook
import com.coloros16.liquidglass.hook.spec.ChannelSpec
import com.coloros16.liquidglass.hook.spec.EffectSpec
import com.coloros16.liquidglass.hook.spec.HookSpec
import com.coloros16.liquidglass.hook.spec.MatchSpec
import com.coloros16.liquidglass.hook.spec.ProbeSpec

/** 出厂预置 hook 规格表（Kotlin 常量表形态；JSON 覆盖解析见 ConfigStore.parseSpecs）。 */
object DefaultHooksJson {

    private const val CANVAS = "android.graphics.Canvas"
    private const val DRAWABLE = "android.graphics.drawable.Drawable"
    private const val VIEW = "android.view.View"
    private const val CONTEXT = "android.content.Context"
    private const val INT = "int"

    val DEFAULT_HOOK_SPECS: List<HookSpec> = listOf(
        HookSpec(
            id = "autoBlur.map",
            group = SpecGroups.CC,
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
            group = SpecGroups.NOTIF,
            match = MatchSpec(
                className = BlurDrawHook.CLASS_NBV_EXT_IMP,
                methodName = BlurDrawHook.METHOD_DRAW_NOTIF_BG,
                params = listOf(CANVAS, DRAWABLE),
            ),
            channel = ChannelSpec(type = "familyThroat", hookPoint = "draw"),
            effect = EffectSpec(type = "registerNotifCardSuppressMaterial"),
            probe = ProbeSpec(group = BlurDrawHook.HEADSUP_PROBE_GROUP, role = "clear"),
        ),
        HookSpec(
            id = "platform.suppressBlurMaskColor",
            group = SpecGroups.CC,
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
            group = SpecGroups.POSTEFFECT,
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
            group = SpecGroups.POSTEFFECT,
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
            group = SpecGroups.POSTEFFECT,
            match = MatchSpec(
                className = BlurDrawHook.CLASS_BASE_DRAWABLE,
                methodName = BlurDrawHook.METHOD_ON_DRAW_CONTENT,
                params = listOf(CANVAS),
                declared = true,
            ),
            channel = ChannelSpec(type = "renderFallback", hookPoint = "onDrawContent"),
            effect = EffectSpec(type = "glassFallback"),
        ),
        HookSpec(
            id = "cc.tileRegionAndMaskSkip",
            group = SpecGroups.CC,
            match = MatchSpec(
                className = BlurDrawHook.CLASS_MIX_COLOR_TILE_DRAWABLE,
                methodName = BlurDrawHook.METHOD_DRAW,
                params = listOf(CANVAS),
            ),
            channel = ChannelSpec(type = "background", hookPoint = "draw"),
            effect = EffectSpec(type = "registerRegionSuppressReturn"),
        ),
        HookSpec(
            id = "notif.headerMask",
            group = SpecGroups.NOTIF,
            match = MatchSpec(
                className = BlurDrawHook.CLASS_NOTIF_HEADER_MASK,
                methodName = BlurDrawHook.METHOD_DRAW,
                params = listOf(CANVAS),
            ),
            channel = ChannelSpec(type = "suppress", hookPoint = "draw"),
            effect = EffectSpec(type = "suppressReturn"),
        ),
        HookSpec(
            id = "notif.metaColorSuppress",
            group = SpecGroups.NOTIF,
            match = MatchSpec(
                className = BlurDrawHook.CLASS_META_BALL_DRAWABLE,
                methodName = BlurDrawHook.METHOD_SET_META_COLOR,
                params = listOf(INT),
            ),
            channel = ChannelSpec(type = "suppress", hookPoint = "setMetaColor"),
            effect = EffectSpec(type = "suppressReturn"),
        ),
        HookSpec(
            id = "headsup.forcePlatformStatic",
            group = SpecGroups.HEADSUP,
            match = MatchSpec(
                className = BlurDrawHook.CLASS_VIEW_BLUR_PROXY,
                methodName = BlurDrawHook.METHOD_SET_BLUR_TYPE,
            ),
            channel = ChannelSpec(type = "probe", hookPoint = "setBlurType"),
            effect = EffectSpec(type = "forceBlurType"),
            probe = ProbeSpec(
                group = BlurDrawHook.HEADSUP_PROBE_GROUP,
                role = "arm",
                ttlMs = BlurDrawHook.HEADSUP_PROBE_TTL_MS,
            ),
        ),
        HookSpec(
            id = "headsup.bypassIsHeadsUpView",
            group = SpecGroups.HEADSUP,
            match = MatchSpec(
                className = BlurDrawHook.CLASS_HEADS_UP_CONTAINER_WINDOW,
                methodName = BlurDrawHook.METHOD_IS_HEADS_UP_VIEW,
                params = listOf(VIEW),
            ),
            channel = ChannelSpec(type = "probe", hookPoint = "isHeadsUpView"),
            effect = EffectSpec(type = "bypassPredicate"),
            probe = ProbeSpec(group = BlurDrawHook.HEADSUP_PROBE_GROUP, role = "consume"),
        ),
        HookSpec(
            id = "text.qsColorNeedLight",
            group = SpecGroups.TEXT,
            match = MatchSpec(
                className = BlurDrawHook.CLASS_QS_COLOR_UTIL,
                methodName = "isTextNeedUseLightColorWhenLight",
                params = listOf(CONTEXT, INT),
            ),
            channel = ChannelSpec(type = "text", hookPoint = "isTextNeedUseLightColorWhenLight"),
            effect = EffectSpec(type = "textQsNeedLight"),
        ),
        HookSpec(
            id = "text.qsColorStatePublish",
            group = SpecGroups.TEXT,
            match = MatchSpec(
                className = BlurDrawHook.CLASS_QS_COLOR_UTIL,
                methodName = "setQsColorState",
                params = listOf(INT),
            ),
            channel = ChannelSpec(type = "text", hookPoint = "setQsColorState"),
            effect = EffectSpec(type = "textQsStatePublish"),
        ),
        HookSpec(
            id = "text.setTextColorContrast",
            group = SpecGroups.TEXT,
            match = MatchSpec(
                className = BlurDrawHook.CLASS_TEXT_VIEW,
                methodName = "setTextColor",
                params = listOf(INT),
            ),
            channel = ChannelSpec(type = "text", hookPoint = "setTextColor"),
            effect = EffectSpec(type = "textSetTextColorContrast"),
        ),
        HookSpec(
            id = "text.onDrawResample",
            group = SpecGroups.TEXT,
            match = MatchSpec(
                className = BlurDrawHook.CLASS_TEXT_VIEW,
                methodName = "onDraw",
                params = listOf(CANVAS),
                declared = true,
                accessible = true,
            ),
            channel = ChannelSpec(type = "text", hookPoint = "onDraw"),
            effect = EffectSpec(type = "textOnDrawResample"),
        ),
        HookSpec(
            id = "cc.lightStyleStrokeMap",
            group = SpecGroups.CC,
            match = MatchSpec(
                className = BlurDrawHook.CLASS_LIGHT_STYLE_STROKE_DRAWABLE,
                methodName = BlurDrawHook.METHOD_DRAW,
                params = listOf(CANVAS),
            ),
            channel = ChannelSpec(type = "background", hookPoint = "draw"),
            effect = EffectSpec(type = "registerScreenRegion"),
        ),
        HookSpec(
            id = "cc.maskBlurMap",
            group = SpecGroups.CC,
            match = MatchSpec(
                className = BlurDrawHook.CLASS_MASK_BLUR_DRAWABLE,
                methodName = BlurDrawHook.METHOD_DRAW,
                params = listOf(CANVAS),
            ),
            channel = ChannelSpec(type = "background", hookPoint = "draw"),
            effect = EffectSpec(type = "registerSwipeMenuRegion"),
        ),
        HookSpec(
            id = "seedling.capsuleBgMap",
            group = SpecGroups.SEEDLING,
            match = MatchSpec(
                className = BlurDrawHook.CLASS_CAPSULE_BACKGROUND_HELPER,
                methodName = BlurDrawHook.METHOD_DRAW_CAPSULE_BG,
                params = listOf(VIEW, CANVAS),
            ),
            channel = ChannelSpec(type = "background", hookPoint = "drawCapsuleBg"),
            effect = EffectSpec(type = "registerCapsuleBgRegion"),
        ),
        HookSpec(
            id = "seedling.capsuleEarMap",
            group = SpecGroups.SEEDLING,
            match = MatchSpec(
                className = BlurDrawHook.CLASS_CAPSULE_EAR_VIEW,
                methodName = BlurDrawHook.METHOD_ON_DRAW,
                params = listOf(CANVAS),
            ),
            channel = ChannelSpec(type = "background", hookPoint = "onDraw"),
            effect = EffectSpec(type = "registerCapsuleEarRegion"),
        ),
        HookSpec(
            id = "seedling.mediaCarouselMap",
            group = SpecGroups.SEEDLING,
            match = MatchSpec(
                className = BlurDrawHook.CLASS_MEDIA_CAROUSEL_VIEW,
                methodName = BlurDrawHook.METHOD_ON_DRAW,
                params = listOf(CANVAS),
            ),
            channel = ChannelSpec(type = "background", hookPoint = "onDraw"),
            effect = EffectSpec(type = "registerMediaCarouselRegion"),
        ),
        HookSpec(
            id = "volume.panelBackdrop",
            group = SpecGroups.VOLUME,
            match = MatchSpec(
                className = BlurDrawHook.CLASS_VOLUME_DIALOG_VIEW,
                methodName = BlurDrawHook.METHOD_EXPAND_PANEL,
            ),
            channel = ChannelSpec(type = "after", hookPoint = "expandPanel", order = "after"),
            effect = EffectSpec(type = "volumePanelBackdrop"),
        ),
        HookSpec(
            id = "seedling.deviceCardAttach",
            group = SpecGroups.SEEDLING,
            match = MatchSpec(
                className = VIEW,
                methodName = BlurDrawHook.METHOD_ON_ATTACHED_TO_WINDOW,
                declared = true,
                accessible = true,
            ),
            channel = ChannelSpec(type = "attach", hookPoint = "onAttachedToWindow"),
            effect = EffectSpec(type = "deviceCardAttachRegister"),
        ),
    )
}
