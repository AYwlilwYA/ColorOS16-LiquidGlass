package com.coloros16.liquidglass.hook

import android.util.Log
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * [M1 · 方案 A 第一阶段] SystemUI 进程内统一注册入口的登记表。
 *
 * 反编译实证（16.1 SystemUI，ContinuousBlurDrawable.java line 129-139）：所有 SystemUI 模糊 drawable
 * （scrim/tile/通知卡/胶囊/媒体横幅/侧滑按钮）都经 `ContinuousBlurDrawable.addBlurDrawable()` 统一注册，
 * 该 hook 一次拿到 drawable 实例 + drawableId + surfaceControl（→层名）+ params。
 *
 * 本对象把这批注册信息统一登记，渲染侧 `BlurDrawHook.replaceShaderIfPossible` 查本表做**场景判定**：
 * - 白名单层（通知阴影/媒体/胶囊等已知玻璃场景）→ 玻璃化；
 * - 黑名单层（音量/关机/全局操作等非玻璃场景）→ 回退系统模糊（不动 paint）；
 * - 未知（未注册 / 无层名 / 层名不在两表）→ **保守 true**（沿用现有行为，不误伤）。
 *
 * key = identityHashCode(drawable)，与渲染侧 replaceShaderIfPossible 的映射键完全一致。
 * 独立对象 + 全 try-catch 兜底：任何失败只影响 registry，不影响现有宿主 hook。
 */
object BlurSceneRegistry {

    private const val TAG = "LiquidGlass"

    /** 防膨胀上限：超过即整体清空（弱引用场景，注册表只作短期场景判定缓存，清空后会自动重新登记）。 */
    private const val MAX_ENTRIES = 512

    /** 单个 drawable 的登记条目。params 暂只存档不消费（渲染侧当前不用，供后续收缩阶段读取）。 */
    data class SceneEntry(
        val drawable: WeakReference<Any>,
        val drawableId: Int,
        val layerName: String?,
        val params: Any?,
    )

    private val entries = ConcurrentHashMap<Int, SceneEntry>()

    /** 白名单：已知玻璃场景宿主层（命中 → 玻璃化）。与默认 true 一致，显式列出便于诊断与未来收紧。 */
    private val WHITELIST = listOf(
        "notificationshade",  // 通知阴影面板
        "launcher",           // 桌面/文件夹
        "media",              // 媒体横幅宿主层
        "capsule",            // 灵动胶囊宿主层
        "notification",       // 通知卡片
        "quick_settings",     // 快捷设置面板
        "qs_",                // QS tile 层
        "metaball",           // 侧滑按钮 MetaBall 宿主层
    )

    /** 黑名单：明确非玻璃场景（命中 → 回退系统模糊）。层名匹配不区分大小写。 */
    private val BLACKLIST = listOf(
        "volumedialog",   // 音量条
        "shutdown",       // 关机界面
        "globalactions",  // 全局操作/电源菜单
        "global_actions", // 全局操作（下划线命名变体）
    )

    /**
     * 登记一条注册。防膨胀：超过 MAX_ENTRIES 清空重来（弱引用 + 短期缓存，清空后可自动重建）。
     * 全 try-catch，失败静默（只影响本表，不影响注册 hook 的 proceed）。
     */
    fun register(drawable: Any, drawableId: Int, layerName: String?, params: Any? = null) {
        try {
            if (entries.size >= MAX_ENTRIES) {
                Log.w(TAG, "registry: size=$MAX_ENTRIES reached, clear to prevent bloat")
                entries.clear()
            }
            entries[System.identityHashCode(drawable)] = SceneEntry(
                WeakReference(drawable), drawableId, layerName, params
            )
        } catch (t: Throwable) {
            Log.e(TAG, "registry: register failed", t)
        }
    }

    /** 按 identityHashCode(drawable) 查条目。弱引用已失效 → 顺手清理并返回 null。 */
    fun lookup(drawable: Any): SceneEntry? {
        val key = System.identityHashCode(drawable)
        val entry = entries[key] ?: return null
        if (entry.drawable.get() == null) {
            entries.remove(key)
            return null
        }
        return entry
    }

    /**
     * 场景判定：该 drawable 是否应玻璃化。
     * - 白名单层 → true；
     * - 黑名单层 → false（回退系统模糊）；
     * - 未注册 / 无层名 / 未知层 → 保守 true（不误伤，沿用现有行为）。
     */
    fun isGlassable(drawable: Any): Boolean {
        val entry = lookup(drawable) ?: return true
        val layer = entry.layerName ?: return true
        val lower = layer.lowercase()
        if (WHITELIST.any { lower.contains(it) }) return true
        if (BLACKLIST.any { lower.contains(it) }) return false
        return true
    }
}
