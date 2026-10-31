package com.coloros16.liquidglass.hook.spec

import android.os.SystemClock
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * 探针注册表：arm 登记目标（WeakReference + ttl）→ matches 命中判定（过期顺带清除）→
 * clearIfMatch 仅当绘制目标就是被登记者才清（防其他卡片绘制误清）。
 */
object ProbeRegistry {

    private class Token(
        val ref: WeakReference<Any>,
        val armedAtMs: Long,
        val ttlMs: Long,
    ) {
        fun expired(): Boolean = ttlMs > 0 && SystemClock.uptimeMillis() - armedAtMs >= ttlMs
    }

    private val tokens = ConcurrentHashMap<String, Token>()

    fun arm(group: String, target: Any, ttlMs: Long) {
        tokens[group] = Token(WeakReference(target), SystemClock.uptimeMillis(), ttlMs)
        EngineLog.log("Probe", "arm group=$group ttl=$ttlMs target=${target.javaClass.name.substringAfterLast('.')}")
    }

    fun matches(group: String, target: Any?): Boolean {
        if (target == null) return false
        val t = tokens[group] ?: return false
        if (t.expired()) {
            tokens.remove(group, t)
            EngineLog.log("Probe", "expire group=$group")
            return false
        }
        return t.ref.get() === target
    }

    fun clearIfMatch(group: String, target: Any?) {
        if (target == null) return
        val t = tokens[group] ?: return
        if (t.ref.get() === target) {
            tokens.remove(group, t)
            EngineLog.log("Probe", "consume group=$group")
        }
    }

    fun clear(group: String) {
        if (tokens.remove(group) != null) {
            EngineLog.log("Probe", "clear group=$group")
        }
    }
}
