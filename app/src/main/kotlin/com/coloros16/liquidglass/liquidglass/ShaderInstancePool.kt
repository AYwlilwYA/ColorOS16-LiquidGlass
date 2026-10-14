package com.coloros16.liquidglass.liquidglass

import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue

/**
 * [doc/spec/118 + spec/119 拆源] RuntimeShader 实例池（双源）。
 * AGSL FULL ≈50k 字符（metaball 融合内联）；BASE 剥离后 ≈-31% 纯代码。
 * 通知卡等 MetaBall 元素走 full 池；QS/音量条等小元素走 base 池，互不混用。
 * idle 后台预构造，渲染线程 miss 直接 poll；池空回退同步 create()（与无池相同）。
 */
object ShaderInstancePool {

    private const val TAG = "LiquidGlass"

    /** 每源池容量（FULL 编译更贵，保留更多；BASE 更轻）。 */
    private const val CAPACITY_FULL = 8
    private const val CAPACITY_BASE = 8

    /** 低于此数触发补货。 */
    private const val REFILL_BELOW = 2

    private val fullPool = LinkedBlockingQueue<RuntimeShader>(CAPACITY_FULL)
    private val basePool = LinkedBlockingQueue<RuntimeShader>(CAPACITY_BASE)

    @Volatile private var fullRefilling = false
    @Volatile private var baseRefilling = false

    /**
     * @param metaball true=FULL 源（MetaBall 黏连桥）；false=BASE 源（小元素）。
     */
    fun pollOrCreate(metaball: Boolean = false): RuntimeShader {
        val pool = if (metaball) fullPool else basePool
        pool.poll()?.let {
            scheduleRefill(metaball)
            return it
        }
        scheduleRefill(metaball)
        return LiquidGlassShader.create(metaball)
    }

    private fun scheduleRefill(metaball: Boolean) {
        val pool = if (metaball) fullPool else basePool
        val cap = if (metaball) CAPACITY_FULL else CAPACITY_BASE
        if (pool.size >= REFILL_BELOW) return
        if (metaball && fullRefilling) return
        if (!metaball && baseRefilling) return
        synchronized(this) {
            if (metaball && fullRefilling) return
            if (!metaball && baseRefilling) return
            if (metaball) fullRefilling = true else baseRefilling = true
        }
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                if (metaball) fullRefilling = false else baseRefilling = false
                return
            }
            val flagRef = if (metaball) ({ fullRefilling = false }) else ({ baseRefilling = false })
            Thread({
                try {
                    while (pool.size < cap) {
                        pool.offer(LiquidGlassShader.create(metaball))
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "shader-pool: refill failed metaball=$metaball", t)
                } finally {
                    flagRef()
                }
            }, if (metaball) "lg-shader-pool-full" else "lg-shader-pool-base").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }.start()
        } catch (t: Throwable) {
            if (metaball) fullRefilling = false else baseRefilling = false
            Log.w(TAG, "shader-pool: start refill failed metaball=$metaball", t)
        }
    }
}
