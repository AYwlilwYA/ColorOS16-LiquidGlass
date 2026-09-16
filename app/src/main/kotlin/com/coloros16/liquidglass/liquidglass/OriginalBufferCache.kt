package com.coloros16.liquidglass.liquidglass

import android.graphics.Bitmap
import android.util.Log

/**
 * 未模糊原图缓存：Hook A 拦截到的未模糊原图 → Bitmap，供 Hook B 的液态玻璃 shader 采样。
 *
 * M0 阶段（本实现）：
 * - 只做"最近一帧原图"缓存（`latestBitmap`），不做复杂 LRU / 引用计数。
 * - Hook A（BlurCaptureHook）已在本缓存之外完成 `Bitmap.wrapHardwareBuffer(HardwareBuffer, ColorSpace)`
 *   + `copy(ARGB_8888, false)`，存入的是**独立软件内存 Bitmap**（不依赖 HardwareBuffer 生命周期），
 *   本缓存只负责持有与替换，不做任何降采样——先验证能拿到原图；降采样、面板自身层裁剪等
 *   性能优化留到后续（见 doc/spec/04 第 6 节）。
 * - 写入新帧时回收旧 Bitmap。注意：Hook B 尚未启用绘制，此时回收安全；
 *   后续 Hook B 采样旧帧时需引入引用计数，避免 recycle 正在被 shader 引用的 Bitmap。
 *
 * 线程安全：写入/清空加锁（BlurService 的 c.s.b 可能来自 binder 线程），读取走 [@Volatile] 无锁。
 */
object OriginalBufferCache {

    private const val TAG = "LiquidGlass"

    @Volatile
    private var latestBitmap: Bitmap? = null

    /**
     * 写入最新一帧原图（BlurService 进程 Hook A 用）。
     *
     * 调用方（BlurCaptureHook）已在 hook 内完成 `Bitmap.wrapHardwareBuffer(hw)` +
     * `copy(ARGB_8888, false)`，传入的是**独立软件内存 Bitmap**（不依赖 HardwareBuffer
     * 生命周期，无需再 close 任何 buffer）。本方法直接持有并替换旧帧。
     *
     * @param bmp 未模糊原图软件位图（1/4 分辨率 RGBA_8888，已 copy 出独立内存）
     * @return 是否成功缓存了一帧
     */
    fun cacheFromBitmap(bmp: Bitmap): Boolean {
        try {
            setLatest(bmp)
            Log.i(TAG, "cache: captured ${bmp.width}x${bmp.height} config=${bmp.config}")
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "cache: store bitmap failed", t)
            return false
        }
    }

    private fun setLatest(bmp: Bitmap) {
        synchronized(this) {
            val old = latestBitmap
            latestBitmap = bmp
            old?.recycle()
        }
    }

    /** 取当前最新原图，供液态玻璃 shader 采样；暂无帧时返回 null。 */
    fun getLatestBitmap(): Bitmap? = latestBitmap

    /** 清空缓存（面板关闭 / hook 卸载时调用）。 */
    fun clear() {
        synchronized(this) {
            latestBitmap?.recycle()
            latestBitmap = null
        }
    }
}
