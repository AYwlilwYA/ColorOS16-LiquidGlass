package com.coloros16.liquidglass.hook.spec

import android.graphics.Bitmap

/** 采集请求占位：字段随各路线实现批次定义。 */
class CaptureRequest

/**
 * 底层内容采集抽象（[spec/149] 五路线）：hook/元素条目按 capture 键选路
 * （"pixelcopy"|"redraw"|"offscreen"|"sf"|null；null=不采集=RenderEffect 现状）。
 * 骨架期全部未实现：isSupported 保守 false、capture 返回 null。
 */
interface CaptureSource {

    /** 采集底层内容；不支持/失败返回 null。 */
    fun capture(request: CaptureRequest): Bitmap?

    /** 当前进程/系统是否支持本路线。 */
    fun isSupported(): Boolean
}

/**
 * ① PixelCopy：窗口拍照 + sourceRect 裁剪。
 * 代价：异步 ~145ms（4K 机型 0.6~1.7s）；自采样高（拍到含玻璃自己）；改布局=否；API 26+。
 */
object PixelCopySource : CaptureSource {
    override fun capture(request: CaptureRequest): Bitmap? = null
    override fun isSupported(): Boolean = false
}

/**
 * ② BlurView 式软件 Canvas 重绘底层 root。
 * 代价：同步跟手；软件 Canvas 拿不到独立 SurfaceView；O(root 子树) + 多玻璃重入防护；改布局=选最近祖先 root；API 任意。
 */
object SoftwareRedrawSource : CaptureSource {
    override fun capture(request: CaptureRequest): Bitmap? = null
    override fun isSupported(): Boolean = false
}

/**
 * ③ 离屏 RenderNode / HardwareBufferRenderer（只渲染底层兄弟）。
 * 代价：同步；兄弟是 SurfaceView 也行；**零自采样**（底层兄弟单独进离屏，永远拍不到玻璃）；必须改布局（自定义 ViewGroup 接管 dispatchDraw）；API 29+（RenderNode+HardwareRenderer）/ 34+（HardwareBufferRenderer）。
 */
object OffscreenRenderNodeSource : CaptureSource {
    override fun capture(request: CaptureRequest): Bitmap? = null
    override fun isSupported(): Boolean = false
}

/**
 * ④ RenderEffect：**不采集**——只作用于已喂入 View 的内容（背景 drawable / View 自身），是效果方案。
 * isSupported 恒 false；API 31+。
 */
object NativeRenderEffectSource : CaptureSource {
    override fun capture(request: CaptureRequest): Bitmap? = null
    override fun isSupported(): Boolean = false
}

/**
 * ⑤ SF DisplayCapture（OPPO captureDisplay + display token + excludes）：SystemUI 进程专属（Xposed + OPPO 私有 API）。
 * 代价：主线程同步首帧 + worker 连抓；跨窗/视频可拍（SF 合成层）；改布局=否；自采样中（排除表白名单）。
 */
object SurfaceFlingerCaptureSource : CaptureSource {
    override fun capture(request: CaptureRequest): Bitmap? = null
    override fun isSupported(): Boolean = false
}
