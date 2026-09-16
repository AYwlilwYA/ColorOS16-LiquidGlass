package com.coloros16.liquidglass.liquidglass

import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/**
 * SystemUI 进程：未模糊原图帧缓存（M2a onBlurReady 通道版）。
 *
 * 数据源：`BlurDrawableManager.onBlurReady(List, HardwareBuffer, int, float)` hook 拦截到
 * 的 HardwareBuffer（BlurService 端 f.c.b hook 已把原图塞进 Bundle，经系统 Binder+Parcel
 * 零拷贝传回，此处拿到即【未模糊原图】），`Bitmap.wrapHardwareBuffer` 后
 * `copy(ARGB_8888)` 转为独立软件位图，缓存供液态玻璃 shader
 * （LiquidGlassShader.createSourceBitmapShader）采样。
 *
 * 帧缓存策略（任务 E 内存修复 + 任务 H2 最简 publishFrame + 任务 F2 回归防护）：
 * 每次 wrap + copy 得到独立软件位图存为缓存（换帧时旧位图不主动 recycle，留给 GC）。
 * - **每帧强制截取更新（同帧去重已删除）**：`onBlurReady` 按 drawable **逐个回调**，一次
 *   面板刷新 20+ 个模糊 drawable 传的是【同一个 HardwareBuffer 对象】且内容相同，但滑动/
 *   动画时同一对象再次回调携带的已是**最新帧内容**（Binder 零拷贝传回）→ 不去重，每帧
 *   都 wrap + copy 落帧，玻璃内容实时跟手。
 * - **F2 帧尺寸过滤已恢复（2026-08-12 回归防护）**：publishFrame 入口拒绝宽度 ≤720px 的
 *   小图帧（音量等非白名单层推来的系统模糊 1/4 buffer 360×792 污染回归防护），全分辨率帧
 *   （1440）正常。**F3 限频已删除**（任务 H2：不限频，先保跟手）。
 *
 * ⚠️ 为什么不能画进常驻软件 Bitmap（2026-08-12 真机实锤）：`wrapHardwareBuffer` 得到的
 * 是**硬件位图**（引用 GPU 显存），`Canvas.drawBitmap` 画进软件 Canvas 抛
 * `Software rendering doesn't support hardware bitmaps` → onBlurReady 拦截异常 → 帧缓存
 * 永不更新 → shader 拿不到新帧。故回退 wrap→copy 方案。
 *
 * HardwareBuffer 生命周期（引用计数铁律）：
 * - hw 引用归系统管理（BlurDrawableManager.onBlurReady 内部 BlurBufferInfo/BlurImageManager
 *   持有或 drawables 为空时 close），本类【不 close】；仅回收 wrap 出的硬件位图
 *   （copy 完成后像素已独立）。
 *
 * Bitmap 生命周期：缓存位图常驻，**不主动 recycle**——旧帧的 BitmapShader 可能
 * 仍被 RenderNode 录制引用（GPU 异步绘制），主动 recycle 可能引发崩溃；换帧时旧位图
 * 失去强引用后由 GC 回收（留 GC，纪律）。
 */
object OriginalBufferBridge {

    private const val TAG = "LiquidGlass"

    /** [F4] 原图 dump 目标路径（SystemUI 有 shell 写权限；仅 dumpEnabled 时写一次） */
    private const val DUMP_PATH = "/data/local/tmp/lg_dump.png"

    /** [原图清晰度验证 2026-08-12] 覆盖写 dump 最新缓存原图（触发式，点击顶部触发区）。写 SystemUI 内部
     *  目录（system uid 可写；/data/local/tmp 是 shell uid，SystemUI 无权写）。adb pull 需 root。 */
    private const val DUMP_LATEST = "/data/user/0/com.android.systemui/files/lg_orig_dump.png"

    /** [原图清晰度验证] 触发式 dump：立即 dump 当前缓存原图到 DUMP_LATEST（后台线程写 PNG）。 */
    fun dumpCurrentFrame() {
        val bmp = sourceBitmap ?: run {
            Log.w(TAG, "orig-dump: sourceBitmap null, nothing to dump")
            return
        }
        try {
            Thread {
                try {
                    val f = java.io.File(DUMP_LATEST)
                    f.parentFile?.mkdirs()
                    java.io.FileOutputStream(f).use { out ->
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    Log.i(TAG, "orig-dump: current frame dumped $DUMP_LATEST ${bmp.width}x${bmp.height}")
                } catch (t: Throwable) {
                    Log.e(TAG, "orig-dump: write failed", t)
                }
            }.start()
        } catch (t: Throwable) {
            Log.e(TAG, "orig-dump: start thread failed", t)
        }
    }

    /** [F4·触发式] dump 触发文件：adb touch 该路径 → 下一次渲染时 dump 当前缓存原图（覆盖写）。
     *  解决"第一帧 dump 抓不到磨砂时刻帧"：复现磨砂 → touch → 滑动触发一帧渲染 → dump 当帧。 */
    private const val DUMP_TRIGGER_PATH = "/data/local/tmp/lg_dump_trigger"

    /** [F4·触发式] 触发检查节流（渲染每帧高频，避免每次 File.exists IO；500ms 足够响应） */
    private var lastDumpTriggerCheck = 0L

    private val frameCounter = AtomicLong(0L)

    /** 已落帧号（-1 = 尚无帧） */
    @Volatile
    private var cachedFrame: Long = -1

    /** 当前缓存原图（独立软件位图，供 shader 采样；换帧时旧位图不主动 recycle，留 GC） */
    @Volatile
    private var sourceBitmap: Bitmap? = null

    /** [F4] 原图 dump 诊断开关（默认 false；BlurDrawHook 挂载时从 Prefs 读入，重启 SystemUI 生效） */
    @Volatile
    var dumpEnabled: Boolean = false

    /** [F4] dump 只写一次标志（避免每帧 IO） */
    private var dumpDone: Boolean = false

    /** [F4·触发式] 渲染路径调用：触发文件存在则 dump 当前缓存原图（覆盖 lg_dump.png，删触发文件）。
     *  后台线程写 PNG 避免阻塞渲染线程；sourceBitmap 每次换帧是新对象，dump 持引用安全。 */
    fun checkDumpTrigger() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastDumpTriggerCheck < 500L) return
        lastDumpTriggerCheck = now
        val trigger = java.io.File(DUMP_TRIGGER_PATH)
        if (!trigger.exists()) return
        val bmp = sourceBitmap
        if (bmp == null) {
            trigger.delete()
            return
        }
        try {
            trigger.delete()
            Thread {
                try {
                    java.io.FileOutputStream(DUMP_PATH).use { out ->
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    Log.i(TAG, "dump: triggered original dumped $DUMP_PATH ${bmp.width}x${bmp.height}")
                } catch (t: Throwable) {
                    Log.e(TAG, "dump: triggered dump write failed", t)
                }
            }.start()
        } catch (t: Throwable) {
            Log.e(TAG, "dump: trigger processing failed", t)
        }
    }

    /** 最近一次喂入的帧号（Hook B 日志用） */
    @Volatile
    var lastFrame: Long = -1
        private set

    /** 最近一次喂入的原图尺寸（Hook B 日志用） */
    @Volatile
    var lastSize: String = ""
        private set

    // ---- 同批去重（性能：一批 onBlurReady 逐 drawable 回调 20+ 次，同一 hw 对象，
    //  每次 wrap+copy 全分辨率位图 ≈18MB 主线程拷贝，是帧延迟与卡顿大头）----
    /** 不能按对象永久去重：BlurService 可能复用同一 buffer 对象推新内容（任务 H2 教训）。
     *  33ms 时间窗：同批回调间隔为微秒级，新抓屏周期间隔 ≥100ms，安全区分。 */
    @Volatile
    private var lastPublishedHwId = 0

    @Volatile
    private var lastPublishedAt = 0L

    /**
     * SystemUI 进程：BlurDrawableManager.onBlurReady hook 喂入未模糊原图（主线程）。
     *
     * wrap + copy(ARGB_8888) 得到独立软件位图存为缓存；**不 close hw**（引用归系统管理）。
     * 每帧强制截取更新（同帧去重已删除）：同一 HardwareBuffer 对象再次回调也重新
     * wrap + copy 落帧，滑动/动画时内容实时跟手。
     * [任务 F2] 帧尺寸过滤：宽度 ≤720px 的小图帧直接丢弃（音量 1/4 buffer 污染回归防护），
     * 全分辨率帧正常；**不限频**（任务 H2 删除 F3，先保跟手）。
     *
     * @param hw 未模糊原图 HardwareBuffer（此刻有效，主线程调用，系统尚未 close）
     */
    fun publishFrame(hw: HardwareBuffer) {
        if (hw.isClosed) {
            Log.d(TAG, "onBlurReady: hw already closed, skip")
            return
        }

        // [F2 恢复·2026-08-12] 帧尺寸过滤：只接受全分辨率原图（白名单层经 BlurService f.c.b
        // 替换为原图，1440×3168 级），拒绝非白名单层（音量面板等）推来的系统模糊 1/4 小图
        // （360×792 级）。小图顶掉全分辨率原图 → srcRect clamp 到 1/4 图边缘拉伸 + 图本身已模糊
        // → 真机"磨砂/清晰逐帧振荡 + 卡片拉伸错图"根因。拒绝时保留上一帧好原图（滞后远优于污染）。
        if (hw.width <= 720 || hw.height <= 720) {
            Log.d(TAG, "onBlurReady: reject small blurred frame ${hw.width}x${hw.height} (non-whitelist layer), keep last full-res original")
            return
        }

        // 同批去重：同一 hw 对象 33ms 内只落首帧（一批 20+ 次回调省 20×18MB 拷贝）
        val now = android.os.SystemClock.uptimeMillis()
        val hwId = System.identityHashCode(hw)
        if (hwId == lastPublishedHwId && now - lastPublishedAt < 33L) {
            return
        }

        // [任务 F2 回归防护·恢复] 帧尺寸过滤：拒绝宽度 ≤720px 的小图帧。
        // 音量等非白名单层推来的系统模糊 1/4 buffer（360×792）会顶掉 NotificationShade
        // 全分辨率原图 → 玻璃元素采到"含白色滑块的模糊小图" → 闪白+全局错位（srcRect clamp
        // 到 360×792）。全分辨率帧（1440×3168）宽度 1440 > 720 正常接收。直接丢，不 close hw
        // （引用归系统管理，红线）。
        if (hw.width <= 720) {
            Log.i(TAG, "frame filtered hw=${hw.width}x${hw.height} (small buffer, skip)")
            return
        }

        val wrapped = try {
            Bitmap.wrapHardwareBuffer(hw, null)
        } catch (t: Throwable) {
            Log.e(TAG, "onBlurReady: wrapHardwareBuffer failed", t)
            null
        }
        if (wrapped == null) {
            Log.w(TAG, "onBlurReady: wrapHardwareBuffer returned null, hw=${hw.width}x${hw.height}")
            return
        }

        try {
            // 硬件位图 → 独立软件位图（ARGB_8888），此后像素与 GPU 显存解耦
            val copy = wrapped.copy(Bitmap.Config.ARGB_8888, false)
            if (copy == null) {
                Log.w(TAG, "onBlurReady: copy returned null, hw=${hw.width}x${hw.height}")
                return
            }

            // 换帧：旧位图不主动 recycle，失去强引用后留 GC（纪律）
            sourceBitmap = copy

            // [F4] 原图 dump 诊断开关：开启后写一次 PNG（后台线程避免阻塞主线程），
            // 供真机拉出看抓屏内容是否混着面板自身旧影。默认关闭（避免 IO 开销）。
            if (dumpEnabled && !dumpDone) {
                dumpDone = true
                try {
                    Thread {
                        try {
                            java.io.FileOutputStream(DUMP_PATH).use { out ->
                                copy.compress(Bitmap.CompressFormat.PNG, 100, out)
                            }
                            Log.i(TAG, "onBlurReady: original dumped $DUMP_PATH ${copy.width}x${copy.height}")
                        } catch (t: Throwable) {
                            Log.e(TAG, "onBlurReady: dump original failed", t)
                        }
                    }.start()
                } catch (t: Throwable) {
                    Log.e(TAG, "onBlurReady: start dump thread failed", t)
                }
            }

            val frame = frameCounter.incrementAndGet()
            cachedFrame = frame
            lastFrame = frame
            lastSize = "${hw.width}x${hw.height}"
            lastPublishedHwId = hwId
            lastPublishedAt = now

            Log.i(TAG, "onBlurReady: original frame captured #$frame bitmap=${hw.width}x${hw.height} config=${copy.config}")
        } finally {
            // wrap 出的硬件位图用完即回收（像素已 copy 独立；hw 引用归系统，不 close）
            try {
                wrapped.recycle()
            } catch (ignored: Throwable) {
            }
        }
    }

    /**
     * 获取当前未模糊原图位图（供液态玻璃 shader 采样）。
     *
     * @return 可采样 Bitmap（独立软件内存）；尚无帧时返回 null。
     *         调用方拿到后**不得 recycle**（所有权在缓存）。
     */
    fun acquireSourceBitmap(): Bitmap? = sourceBitmap
}
