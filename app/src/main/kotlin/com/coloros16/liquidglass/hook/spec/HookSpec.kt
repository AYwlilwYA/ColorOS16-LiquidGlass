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
    val corner: CornerSpec = CornerSpec(),
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

/** 圆角链配置：写穿/渲染轨分工 + 形状感知钳制（与玻璃同一 spec 体系驱动）。 */
data class CornerSpec(
    /** "write-through"=写穿轨生效 | "sync-external"=仅跟随外部 | "none"=本类不参与圆角覆盖。 */
    val mode: String = "write-through",
    /** 「圆角自适应」：覆盖值超形状上限钳回（矩形/胶囊钳短边一半，异形/未知不钳）。 */
    val adaptiveClamp: Boolean = true,
    /** 「玻璃圆角跟随外部形状」：开=玻璃 SDF 圆角跟外部实测；关=可独立自定义。 */
    val externalSync: Boolean = true,
    /** spec 级出厂圆角默认（dp；运行期覆盖表 miss 时回落，0=无出厂默认）。 */
    val valueDp: Float = 0f,
    /** 形状档："auto"=运行时判定 | "rect"=矩形卡 | "capsule"=胶囊/圆 | "free"=异形不钳。 */
    val shapeClamp: String = "auto",
    /** 生效通道预留："layerA"|"layerB"|"nbv"|"outline"（nbv=setRadius 二期欠账字段）。 */
    val channels: List<String> = emptyList(),
    /** EngineLog FULL 的 source 名（specId 恒在行内，防按 id grep 假阴）。 */
    val logKey: String = "corner",
)
