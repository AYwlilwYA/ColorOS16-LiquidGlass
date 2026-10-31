package com.coloros16.liquidglass.hook.spec

import com.coloros16.liquidglass.log.Lg
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 还原触发编排：四个时机（DISABLE/POWER_SAVE/GLASS_OFF/HOT_RELOAD）登记全局还原动作，
 * fire(timing) 统一执行（逐条 try-catch 不外泄）。现有懒还原调用点不动，本引擎只收口调度。
 */
object RestoreEngine {

    private const val TAG = "LiquidGlass"

    enum class Timing { DISABLE, POWER_SAVE, GLASS_OFF, HOT_RELOAD }

    private val actions = ConcurrentHashMap<Timing, CopyOnWriteArrayList<() -> Unit>>()

    fun register(timing: Timing, action: () -> Unit) {
        actions.getOrPut(timing) { CopyOnWriteArrayList() }.add(action)
        EngineLog.log("RestoreEngine", "register $timing n=${actions[timing]?.size ?: 0}")
    }

    fun fire(timing: Timing) {
        val list = actions[timing] ?: return
        EngineLog.log("RestoreEngine", "fire $timing n=${list.size}")
        var done = 0
        for (action in list) {
            try {
                action()
                done++
            } catch (t: Throwable) {
                EngineLog.log("RestoreEngine", "action-failed $timing ${t.javaClass.simpleName}")
                Lg.w(TAG, "RestoreEngine[$timing] action failed", t)
            }
        }
        EngineLog.log("RestoreEngine", "fire-done $timing ok=$done/${list.size}")
    }

    fun start(specs: List<HookSpec>) {
        val restorable = specs.count {
            it.restore.onDisable || it.restore.onPowerSave || it.restore.onGlassOff || it.restore.onHotReload
        }
        Lg.i(TAG, "RestoreEngine started (${specs.size} specs, $restorable restorable)")
    }
}
