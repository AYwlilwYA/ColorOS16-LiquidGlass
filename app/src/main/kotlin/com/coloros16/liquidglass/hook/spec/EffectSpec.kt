package com.coloros16.liquidglass.hook.spec

/**
 * 效果声明：type ∈ registerRegionScrim | glassDraw | glassFallback | suppressField |
 * registerNotifCardSuppressMaterial | glassFallback 族后续扩展。
 * params 为 effect 私有参数；shaderVariant 预留（base/full/metaball/lowres/tilt）。
 */
data class EffectSpec(
    val type: String,
    val params: Map<String, Any?> = emptyMap(),
    val shaderVariant: String? = null,
    val fallbackOnError: String = "proceed",
)
