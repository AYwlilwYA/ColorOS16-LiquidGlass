package com.coloros16.liquidglass.hook.spec

import com.coloros16.liquidglass.log.Lg
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field

/**
 * 字段抑制驱动：proceed 前把绑定字段置中性值（跳过磨砂/材质覆盖层），finally 恢复原值。
 * 每个绑定独立：owner 返回 null 或读失败 = 该绑定跳过（不抑制不恢复），不影响其余绑定。
 */
object SuppressPolicy {

    private const val TAG = "LiquidGlass"

    /** 一条字段绑定：name 进日志（与原 fuzzy: read/clear/restore 文案对齐），owner 每次调用现算。 */
    class FieldBinding(
        val name: String,
        val field: Field,
        val owner: () -> Any?,
        val neutralValue: Int = 0,
        val onlyWhenNonZero: Boolean = true,
    )

    /**
     * 前序清（非 0 才置中性值）→ chain.proceed() → finally 恢复（读到过的才恢复）。
     * 返回 proceed 的结果；恢复顺序 = 绑定逆序（独立字段，无行为后果）。
     */
    fun suppressFieldsThenProceed(chain: XposedInterface.Chain, bindings: List<FieldBinding>): Any? {
        val saved = ArrayList<Int?>(bindings.size)
        for (b in bindings) {
            val host = try {
                b.owner()
            } catch (t: Throwable) {
                null
            }
            if (host == null) {
                saved.add(null)
                continue
            }
            val orig = try {
                b.field.getInt(host)
            } catch (t: Throwable) {
                Lg.e(TAG, "fuzzy: read ${b.name} failed", t)
                null
            }
            if (orig != null && (!b.onlyWhenNonZero || orig != 0)) {
                try {
                    b.field.setInt(host, b.neutralValue)
                } catch (t: Throwable) {
                    Lg.e(TAG, "fuzzy: clear ${b.name} failed", t)
                }
            }
            saved.add(orig)
        }
        try {
            return chain.proceed()
        } finally {
            for (i in bindings.indices.reversed()) {
                val orig = saved[i] ?: continue
                val b = bindings[i]
                val host = try {
                    b.owner()
                } catch (t: Throwable) {
                    null
                } ?: continue
                try {
                    b.field.setInt(host, orig)
                } catch (t: Throwable) {
                    Lg.e(TAG, "fuzzy: restore ${b.name} failed", t)
                }
            }
        }
    }
}
