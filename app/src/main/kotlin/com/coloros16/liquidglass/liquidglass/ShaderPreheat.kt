package com.coloros16.liquidglass.liquidglass

import android.graphics.Bitmap
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.RenderNode
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.coloros16.liquidglass.log.Lg
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [doc/spec/111] AGSL 启动预热：把「第一次下拉控制中心卡 1.4s」的一次性 GPU 编译成本，
 * 挪到 SystemUI 启动后的 idle 期离屏付掉（用户拍板方案 B：启动预热）。
 *
 * 根因（真机 HWUI Davey 分解，见 doc/spec/111）：帧1 duration=1433ms = 排队 500 + CPU 全链 16
 * + **GPU 执行（IssueDrawCommands→SwapBuffers）912ms** + 收尾 4；关模块开关后首下拉不卡 ⇒
 * 912ms = 液态玻璃 RuntimeShader **首次上 GPU**（driver 编译 program + 首绘管线创建）的一次性
 * 成本，第二次编译缓存已建就不卡。
 *
 * ⚠️ 必须走**真 GPU 路径**：`Canvas(bitmap)` / `drawXxx` 到 Bitmap 是软件渲染（Skia CPU），
 * RuntimeShader 在 CPU 执行，**不触发 driver 编译**。这里用 `ImageReader` surface +
 * [HardwareRenderer] + [RenderNode] 录制离屏帧，再同步等本帧 GPU 提交完成 ⇒ 编译完成。
 *
 * 预热对象 = [LiquidGlassShader.AGSL_SOURCE] 唯一那一份 AGSL：metaball（mbFuse/mbShapeSDF）与
 * 掩码裁剪段都**内联在同一个 program** 里，编译一次全覆盖；复用 [LiquidGlassShader.create] /
 * [LiquidGlassShader.setUniforms] 既有公开入口，**不改 AGSL 源、不动任何现有渲染语义**。
 *
 * 幂等：进程内一次（[scheduled]）；热重载（spec/110）后新类新静态 ⇒ 会再预热一次 = 正确行为
 *（新代码需要新编译）。
 *
 * 失败容忍：全 try-catch，任何失败只打日志（受 moduleLogEnabled 总开关门控）后 return，
 * 绝不影响现有渲染 / hook。
 * ⚠️ [doc/spec/118 2026-10-14] **已停用**（schedule 无调用方）：TOP2 独立 HardwareRenderer 与
 * 真实 UI 大概率不共享 driver program 缓存（白做）+ TOP3 不覆盖每元素 create()。替代 =
 * [ShaderInstancePool]（CPU 侧实例预构造）。本文件保留作 spec/111 历史方案参考，勿复活。
 */
object ShaderPreheat {

    private const val TAG = "LiquidGlass"

    /** 启动后延迟多久预热：避开 SystemUI 启动高峰（首帧、图标加载、hook 挂载）。 */
    private const val PREHEAT_DELAY_MS = 5000L

    /** 离屏尺寸：够小（整帧几十 ms），够触发 driver program 编译即可。 */
    private const val SIZE = 64

    /** 预热路径圆角：只影响录制的 Path 形状（与 program 内容无关），取系统常见量级。 */
    private const val RADIUS = 28f

    /** 进程内只调度一次；热重载换新类 = 新静态 = 重新预热（正确）。 */
    private val scheduled = AtomicBoolean(false)

    /**
     * 调度预热（install 收尾处调用，幂等）。
     *
     * @param logEnabled 模块日志总开关（BlurDrawHook.moduleLogEnabled）；本类**所有**日志受它门控，
     *                   不留无门控后门。
     */
    fun schedule(logEnabled: Boolean) {
        if (!scheduled.compareAndSet(false, true)) return
        // HardwareRenderer / RenderNode 为 API 29+（本工程 minSdk=26，仅作兜底；
        // XposedEntry.doInstall 已在 SDK<29 时整体跳过 install，正常设备不会走到这里）。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (logEnabled) Lg.d(TAG, "preheat: skip (spec/111), SDK=${Build.VERSION.SDK_INT} < 29")
            return
        }
        try {
            // 只负责延迟：syncAndDraw 阻塞，真正干活在下面的子线程（绝不在主线程跑）。
            Handler(Looper.getMainLooper()).postDelayed({ startWorker(logEnabled) }, PREHEAT_DELAY_MS)
        } catch (t: Throwable) {
            if (logEnabled) Lg.w(TAG, "preheat: schedule failed (spec/111)", t)
        }
    }

    /** 起预热线程（daemon；失败只打日志）。 */
    private fun startWorker(logEnabled: Boolean) {
        try {
            Thread({ runPreheat(logEnabled) }, "lg-shader-preheat").apply { isDaemon = true }.start()
        } catch (t: Throwable) {
            if (logEnabled) Lg.w(TAG, "preheat: start worker failed (spec/111)", t)
        }
    }

    /**
     * 离屏跑一遍真实 shader：建实例 → 喂真实输入/uniform → ImageReader + HardwareRenderer 同步渲染。
     * 全程本线程内完成，全 try-catch。
     */
    private fun runPreheat(logEnabled: Boolean) {
        val t0 = SystemClock.uptimeMillis()
        var bitmapForCleanup: Bitmap? = null
        var readerForCleanup: ImageReader? = null
        var rendererForCleanup: HardwareRenderer? = null
        try {
            val size = SIZE.toFloat()

            // 1) 复用真实生效的 AGSL 源建实例（源码与渲染路径同一份 ⇒ 编译缓存按 program 匹配才有效）。
            //    用新实例而非 getShader()：不碰渲染线程正在用的那份缓存 shader 的 uniform。
            val shader = LiquidGlassShader.create()

            // 2) 喂真实输入 + 全量 uniform（声明布局/绑定与真机渲染一致，避免缺输入 shader 的未定义行为）。
            val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            bitmapForCleanup = bmp
            bmp.eraseColor(-0x1)
            val source = LiquidGlassShader.createSourceBitmapShader(bmp)
            val rect = RectF(0f, 0f, size, size)
            LiquidGlassShader.setUniforms(
                shader = shader,
                params = LiquidGlassShader.Params(viewportWidth = size, viewportHeight = size),
                source = source,
                lowResSource = source,
                sourceRect = rect,
                lowResSourceRect = rect,
            )

            // 3) 离屏真 GPU 帧：ImageReader 提供 surface，HardwareRenderer 渲染 RenderNode 内容。
            val rd = ImageReader.newInstance(SIZE, SIZE, PixelFormat.RGBA_8888, 2)
            readerForCleanup = rd
            val node = RenderNode("spec111-preheat")
            node.setPosition(0, 0, SIZE, SIZE)
            val canvas = node.beginRecording()
            try {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                paint.shader = shader
                // 与真实绘制同构：系统 BaseDrawable.drawBlurShader = drawPath(圆角路径, AA paint)，
                // seedling 路径同为 AA roundRect path。Skia 程序 key 与绘制形态相关（AA path / rect
                // 分支的 coverage 不同），照抄形态才吃得到编译缓存。
                val round = Path().apply {
                    addRoundRect(RectF(0f, 0f, size, size), RADIUS, RADIUS, Path.Direction.CW)
                }
                canvas.drawPath(round, paint)
                // 直角路径兜底：元素圆角为 0 时 Skia 对 isRect 路径会走 drawRect 分支（另一套 key）。
                val rectPath = Path().apply { addRect(0f, 0f, size, size, Path.Direction.CW) }
                canvas.drawPath(rectPath, paint)
            } finally {
                node.endRecording()
            }
            val hw = HardwareRenderer()
            rendererForCleanup = hw
            hw.setName("lg-spec111-preheat")
            hw.setOpaque(false)
            hw.setSurface(rd.surface)
            hw.setContentRoot(node)

            // 4) 同步等本帧 GPU 执行并提交完成 ⇒ driver program 编译完成（预热的意义所在）。
            //    ⚠️ 任务原写 `createRenderRequest().syncAndThrow()` —— 实测本工程 compileSdk 36 的
            //    android.jar（API 34/36/37 三份均查过）**无 syncAndThrow**，等价 API 为
            //    `setWaitForPresent(true)` + `syncAndDraw()`（源码语义：不为 SYNC_FRAME_DROPPED 时
            //    等到本帧已提交到 Surface 才返回）。
            val sync = hw.createRenderRequest().setWaitForPresent(true).syncAndDraw()

            val cost = SystemClock.uptimeMillis() - t0
            if (logEnabled) {
                Lg.i(
                    TAG,
                    "preheat: ok (spec/111) cost=${cost}ms sync=0x${Integer.toHexString(sync)} " +
                        "shaders=liquid-glass(AGSL_SOURCE, metaball+mask 内联同一 program)",
                )
            }
        } catch (t: Throwable) {
            // 失败完全容忍：只打日志（受 moduleLogEnabled 门控），绝不影响渲染。
            if (logEnabled) {
                Lg.w(TAG, "preheat: failed (spec/111) cost=${SystemClock.uptimeMillis() - t0}ms", t)
            }
        } finally {
            try {
                rendererForCleanup?.destroy()
            } catch (ignored: Throwable) {
            }
            try {
                readerForCleanup?.close()
            } catch (ignored: Throwable) {
            }
            try {
                bitmapForCleanup?.recycle()
            } catch (ignored: Throwable) {
            }
        }
    }
}
