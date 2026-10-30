package com.coloros16.liquidglass.hook.spec

/** 抑制声明：mode ∈ none | throat | all；fields 为置中性值的字段清单，restoreInFinally 保证还原。 */
data class SuppressSpec(
    val mode: String = "none",
    val fields: List<FieldSpec> = emptyList(),
    val neutralValue: String? = null,
    val restoreInFinally: Boolean = true,
    val onlyWhen: String? = null,
)

/** 单个被抑制字段：name 为字段名，ownerClass 限定宿主（null = hook 目标类）。 */
data class FieldSpec(
    val name: String,
    val ownerClass: String? = null,
    val neutralValue: String? = null,
)
