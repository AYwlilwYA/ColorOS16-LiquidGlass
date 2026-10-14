package com.coloros16.liquidglass.liquidglass

import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue

/**
 * [doc/spec/118] RuntimeShader 实例池：AGSL 源 50k 字符（spec/100 metaball 内联后 +59%），
 * 每元素 [RuntimeShader] 构造都要在**调用线程**（渲染线程）做一遍 AGSL 解析 ——
 * 「增加玻璃元素更卡」的线性 CPU 成本。本池在 idle 后台线程预构造实例，渲染线程 miss 时
 * 直接 poll，把解析成本从渲染关键路径挪走。
 *
 * 与已停用的 ShaderPreheat（spec/111 GPU 预热）不同：预热赌的是 driver program 缓存可跨
 * HardwareRenderer/真实 UI 共享（spec/118 TOP2 存疑=很可能白做）；本池只做 **CPU 侧构造**，
 * 与绘制路径同一 [LiquidGlassShader.create] 入口，产物语义完全一致、零 GPU 假设。
 *
 * 线程安全：[LinkedBlockingQueue] 自带同步；refill 单线程 daemon。池空时调用方回退
 * 同步 create()（行为与无池时相同，只是回到旧成本）。
 */
object ShaderInstancePool {

    private const val TAG = "LiquidGlass"

    /** 池容量上限（预构造缓冲；LRU 语义不需要，实例等价）。 */
    private const val CAPACITY = 12

    /** 低于此数触发补货。 */
    private const val REFILL_BELOW = 4

    private val pool = LinkedBlockingQueue<RuntimeShader>(CAPACITY)

    @Volatile
    private var refillScheduled = false

    /** 预构造一个实例（与渲染路径 create() 同一入口，语义零差异）。 */
    fun pollOrCreate(): RuntimeShader {
        pool.poll()?.let {
            scheduleRefill()
            return it
        }
        scheduleRefill()
        return LiquidGlassShader.create()
    }

    /** 池空/将空时起一次补货（幂等节流：同一时刻最多一个补货线程）。 */
    private fun scheduleRefill() {
        if (pool.size >= REFILL_BELOW) return
        if (refillScheduled) return
        synchronized(this) {
            if (refillScheduled) return
            refillScheduled = true
        }
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                refillScheduled = false
                return
            }
            Thread({
                try {
                    while (pool.size < CAPACITY) {
                        pool.offer(LiquidGlassShader.create())
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "shader-pool: refill failed", t)
                } finally {
                    refillScheduled = false
                }
            }, "lg-shader-pool").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }.start()
        } catch (t: Throwable) {
            refillScheduled = false
            Log.w(TAG, "shader-pool: start refill failed", t)
        }
    }
}
