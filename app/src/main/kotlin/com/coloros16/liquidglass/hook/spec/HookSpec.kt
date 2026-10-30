package com.coloros16.liquidglass.hook.spec

/** 一条 hook 规格：挂什么（match）、走哪条通道（channel）、做什么（effect）、怎么兜底（fallback/log/guard）。 */
data class HookSpec(
    val id: String,
    val enabled: Boolean = true,
    val priority: Int = 0,
    val group: String? = null,
    /** 采集路线键："pixelcopy"|"redraw"|"offscreen"|"sf"|null（null=不采集=RenderEffect 现状）。 */
    val capture: String? = null,
    val match: MatchSpec,
    val channel: ChannelSpec,
    val effect: EffectSpec,
    val suppress: SuppressSpec = SuppressSpec(),
    val probe: ProbeSpec? = null,
    val restore: RestoreSpec = RestoreSpec(),
    val fallback: FallbackSpec = FallbackSpec(),
    val log: LogSpec = LogSpec(),
    val guard: GuardSpec = GuardSpec(),
)

/** 类/方法匹配条件；params 用类型名（"android.graphics.Canvas"、"int"、"kotlin.jvm.functions.Function0"）。 */
data class MatchSpec(
    val className: String? = null,
    val classNameContains: List<String> = emptyList(),
    val classNameSuffix: List<String> = emptyList(),
    val superClassContains: List<String> = emptyList(),
    val methodName: String? = null,
    val methodNames: List<String> = emptyList(),
    val params: List<String> = emptyList(),
    val returnType: String? = null,
    val classLoader: String = "host",
    val declared: Boolean = false,
    val accessible: Boolean = false,
    val viewIdResName: String? = null,
    val viewClassSuffix: String? = null,
    val rootViewClassContains: List<String> = emptyList(),
    val windowType: Int? = null,
    val parentChainContains: List<String> = emptyList(),
    val drawableClassContains: List<String> = emptyList(),
    val hostViewNameContains: List<String> = emptyList(),
    val sourceTag: String? = null,
)

/** 还原策略：关闭/省电/玻璃总开关/热重载时按 actions 回写。 */
data class RestoreSpec(
    val onDisable: Boolean = true,
    val onPowerSave: Boolean = true,
    val onGlassOff: Boolean = true,
    val onHotReload: Boolean = true,
    val actions: List<String> = emptyList(),
)

/** 失败兜底：effect 异常走 proceed（原行为），匹配/反射失败按策略降级。 */
data class FallbackSpec(
    val onException: String = "proceed",
    val onMatchFail: String = "proceed",
    val onReflectFail: String = "disableSpec",
)

/** 挂载/运行日志：throttleMs 内同 spec 的 effect 错误只打一条。 */
data class LogSpec(
    val tag: String = "LiquidGlass",
    val throttleMs: Long = 2000,
    val level: String = "INFO",
    val logOnChangeOnly: Boolean = true,
)

/** 守卫：架构相关挂载条件（旧架构专属条目置 true）。 */
data class GuardSpec(
    val legacyPosteffect: Boolean = false,
)
