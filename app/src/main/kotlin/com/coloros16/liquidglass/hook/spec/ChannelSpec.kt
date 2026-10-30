package com.coloros16.liquidglass.hook.spec

/**
 * 通道类型：background | onDraw | familyThroat | attach | dispatchDraw | text | signal |
 * after | before | replace | render | renderFallback | suppress。
 * order：before | after | replace；scope：global | class | family | instance。
 */
data class ChannelSpec(
    val type: String,
    val hookPoint: String? = null,
    val scope: String = "global",
    val order: String = "before",
)
