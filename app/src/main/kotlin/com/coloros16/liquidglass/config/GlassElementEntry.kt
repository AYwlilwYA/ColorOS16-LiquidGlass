package com.coloros16.liquidglass.config

/**
 * 点选固化元素条目：锚（类名/id/父链/尺寸区间）+ 挂载语义 + 参数。
 * 锚匹配通过才挂玻璃；anchorKey = 类名 + "#" + viewId（viewId 可空）。
 */
data class GlassElementEntry(
    val label: String,
    val anchorClassName: String,
    val anchorViewId: String?,
    val anchorIdPackage: String?,
    val anchorParentChain: List<String>,
    val anchorSizeMinW: Int,
    val anchorSizeMaxW: Int,
    val anchorSizeMinH: Int,
    val anchorSizeMaxH: Int,
    /** BACKGROUND = drawable 背景直挂；DRAW_HOOK / THROAT = onDraw 抑制桥 + 背景挂载。 */
    val mountType: String,
    /** NONE = 不抑制；THROAT = 咽喉方法条件化抑制；FULL = 该类 onDraw 整体跳过。 */
    val suppressMode: String,
    val throatClass: String?,
    val throatMethod: String?,
    /** 分号分隔多方法锚，如 "c(Canvas,Path,float);d(Canvas,Path,tc.b)"；全部命中才挂。 */
    val throatSignature: String?,
    val presetKey: String,
    val overrides: Map<String, Float>,
    val enabled: Boolean,
) {
    val anchorKey: String get() = "$anchorClassName#${anchorViewId ?: ""}"

    companion object {
        const val MOUNT_BACKGROUND = "BACKGROUND"
        const val MOUNT_DRAW_HOOK = "DRAW_HOOK"
        const val MOUNT_THROAT = "THROAT"

        const val SUPPRESS_NONE = "NONE"
        const val SUPPRESS_THROAT = "THROAT"
        const val SUPPRESS_FULL = "FULL"
    }
}
