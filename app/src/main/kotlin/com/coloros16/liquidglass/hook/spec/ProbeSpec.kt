package com.coloros16.liquidglass.hook.spec

/** 探针互斥：group 内 arm/consume/clear 协同；ttlMs 过期自动失效。 */
data class ProbeSpec(
    val group: String,
    val role: String,
    val ttlMs: Long = 5000,
    val scope: String = "instance",
)
