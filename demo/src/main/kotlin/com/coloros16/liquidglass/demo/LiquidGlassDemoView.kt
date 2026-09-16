package com.coloros16.liquidglass.demo

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.coloros16.liquidglass.demo.liquidglass.LiquidGlassShader
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 液态玻璃隔离验证视图（仿 SystemUI 控制中心元素）。
 *
 * 组成：
 * - 动态背景：ValueAnimator 每帧生成渐变背景（线性渐变 + 移动径向光斑 + 移动色块），
 *   模拟动态壁纸，作为 uSource 原图喂入液态玻璃 shader；
 * - 玻璃元素（都用 LiquidGlassShader 渲染）：
 *   1. 两个大圆角矩形卡片（仿控制中心网络卡/媒体卡，半透明深灰着色 MIX）
 *   2. 2×4 圆形快捷开关（仿快捷开关区）
 *   3. 一条圆角长条（仿亮度条）
 *
 * 坐标模式：统一「元素局部坐标」——每元素 canvas.translate(el.left,el.top) 后以局部坐标
 * (0,0,w,h) 绘制，uViewport = 元素尺寸，uSource/srcRect = 元素 rect 对应背景区域。
 * 与 shader main() 坐标契约（coord = 元素局部坐标、几何以 uViewport 中心为基准）一致，
 * 保证玻璃圆角/折射/高光精确落在卡片/开关/亮度条自身区域，不扩散到卡片外。
 * 早期「VIEW 坐标」（coord = 全 View 绝对坐标、共享全屏 viewport、全图 srcRect）与 shader
 * 契约矛盾，导致所有元素几何锚定屏幕中心、折射/高光跑到屏幕边缘，为错误用法，已移除。
 *
 * 长按切换 uDebugCoord（shader 输出 fract(coord/500) 位置色，验证 coord 空间与元素覆盖范围）。
 *
 * 注意：RuntimeShader 需 Android 13+（API 33）。PJZ110 为 Android 16，可直接运行。
 */
class LiquidGlassDemoView(context: Context) : View(context) {

    // ---------- 玻璃元素描述 ----------
    private class Element(
        val name: String,
        val rect: RectF,
        val cornerRadius: Float,
        val params: LiquidGlassShader.Params,
        // 每元素独立 RuntimeShader 实例：共享 getShader() 单例会导致 GPU 录制层 uniform 跨元素
        // 互相污染（后元素 setUniforms 覆盖前元素输入）→ 内容错位/串。per-element 实例彻底隔离。
        val shader: RuntimeShader,
    )

    // ---------- 状态 ----------
    private var debugCoord = false

    // 动态背景 bitmap（双缓冲：每帧交替画入 bgBitmaps[bgIndex]，RenderThread 上一帧读的是
    // bgBitmaps[1-bgIndex]，UI 写与 GPU 读错开 → 消除硬件加速下共享位图的竞争闪烁/撕裂。
    // 尺寸变化/换 buffer 时不主动 recycle：旧块可能仍被 RenderThread 引用，留给 GC 收。）
    private val bgBitmaps = arrayOfNulls<Bitmap>(2)
    private var bgIndex = 0
    private val bgCanvas = Canvas()
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // uSourceLowRes 低分辨率源图（1/4 降采样，消除全分辨率欠采样网格伪影）——随 bg 双缓冲，
    // lowResBitmaps[bgIndex] 与 bgBitmaps[bgIndex] 同帧成对交替，保证绘制与 shader BitmapShader 引用同一 buffer。
    private val lowResBitmaps = arrayOfNulls<Bitmap>(2)
    private val lowResCanvas = Canvas()
    private val lowResPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true   // 双线性缩放，避免降采样锯齿
    }

    // ---------- Paint 缓存（消除 buildBackground 每帧 new Paint 的 GC 抖动；渐变参数随 t 变，
    // LinearGradient/RadialGradient 对象仍需每帧重建，但 Paint 实例复用） ----------
    private val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG)   // 基础线性渐变底
    private val radialPaint = Paint(Paint.ANTI_ALIAS_FLAG)     // 移动径向光斑
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)        // 移动色块
    private val stripePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x26FFFFFF.toInt()   // 白 15%
        strokeWidth = 4f
    }
    private val gridTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x40FFFFFF.toInt()   // 白 25%
        textSize = 20f
    }
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 30f
        isFakeBoldText = true
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAAFFFFFF.toInt()
        textSize = 22f
    }

    private val elements = ArrayList<Element>()

    // 动画：驱动背景渐变位置/颜色（0~1 循环）
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 6000L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
        startDelay = 100L
        addUpdateListener { invalidate() }
    }

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                debugCoord = !debugCoord
                invalidate()
            }
        },
    )

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Build.VERSION.SDK_INT >= 33) {
            animator.start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancel()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // layoutElements 内会为每元素 create() 编译 RuntimeShader（需 API 33+），故此处需 API 保护
        if (Build.VERSION.SDK_INT >= 33 && w > 0 && h > 0) layoutElements(w.toFloat(), h.toFloat())
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return true
    }

    // ---------- 元素布局（仿 SystemUI 控制中心） ----------

    private fun layoutElements(W: Float, H: Float) {
        elements.clear()
        val pad = W * 0.045f

        // 1) 顶部两个大圆角矩形卡片（网络卡 / 媒体卡）：半透明深灰着色
        val cardW = (W - 3f * pad) / 2f
        val cardH = H * 0.17f
        val cardTop = H * 0.06f
        elements += Element(
            "card1",
            RectF(pad, cardTop, pad + cardW, cardTop + cardH),
            28f,
            baseParams(W, H).copy(
                mixColorA = 0x0DFFFFFF,          // 近透明白（MixColor 不再叠深色，压暗交给背景层）
                cornerRadius = 28f,
                refractionHeight = 56f,
                refractionAmount = 36f,
                dispersion = 0.15f,              // 唯一开色散的元素：观察轻微彩虹边
                highlightWidth = 12f,
            ),
            LiquidGlassShader.create(),
        )
        elements += Element(
            "card2",
            RectF(2f * pad + cardW, cardTop, 2f * pad + 2f * cardW, cardTop + cardH),
            28f,
            baseParams(W, H).copy(
                mixColorA = 0x0DFFFFFF,
                cornerRadius = 28f,
                refractionHeight = 56f,
                refractionAmount = 36f,
                dispersion = 0f,
                highlightWidth = 12f,
            ),
            LiquidGlassShader.create(),
        )

        // 2) 2×4 圆形快捷开关（drawRoundRect 半径=直径/2 → 视觉为圆）
        val swSize = W * 0.082f
        val swTop = H * 0.30f
        val rowGap = swSize * 1.45f
        val gap = (W - 2f * pad - 4f * swSize) / 3f
        for (row in 0..1) {
            for (col in 0..3) {
                val left = pad + col * (swSize + gap)
                val top = swTop + row * rowGap
                elements += Element(
                    "switch_${row}_$col",
                    RectF(left, top, left + swSize, top + swSize),
                    swSize / 2f,
                    baseParams(W, H).copy(
                        mixColorA = 0x0DFFFFFF,   // 近透明（不再叠亮色）
                        cornerRadius = swSize / 2f,
                        refractionHeight = 40f,
                        refractionAmount = 26f,
                        dispersion = 0f,
                        highlightWidth = 10f,
                    ),
                    LiquidGlassShader.create(),
                )
            }
        }

        // 3) 亮度长条
        val barH = H * 0.045f
        val barTop = H * 0.62f
        val barW = W - 2f * pad
        elements += Element(
            "brightness",
            RectF(pad, barTop, pad + barW, barTop + barH),
            barH / 2f,
            baseParams(W, H).copy(
                mixColorA = 0x0DFFFFFF,
                cornerRadius = barH / 2f,
                refractionHeight = 48f,
                refractionAmount = 28f,
                dispersion = 0f,
                highlightWidth = 12f,
            ),
            LiquidGlassShader.create(),
        )
    }

    private fun baseParams(W: Float, H: Float) = LiquidGlassShader.Params(
        viewportWidth = W,
        viewportHeight = H,
        blendMode = LiquidGlassShader.BlendMode.MIX,
    )

    // 每元素 uniform：viewport = 元素尺寸（coord 为元素局部坐标，与 shader main() 契约一致）
    private fun uniformsFor(el: Element): LiquidGlassShader.Params = el.params.copy(
        viewportWidth = el.rect.width(),
        viewportHeight = el.rect.height(),
    )

    // ---------- 低分辨率源图（uSourceLowRes） ----------

    private companion object {
        // 1/4 降采样（AGSL 高斯模糊在低分辨率图上稀疏采样，消除全分辨率欠采样网格/莫尔纹）
        const val LOW_RES_DIV = 4
    }

    /**
     * 同步 uSourceLowRes 用的 1/4 降采样背景图（每帧背景重建后调用）。
     * 尺寸不变时复用缓存 bitmap，把全分辨率背景等比缩进低分辨率图（双线性）。
     */
    private fun ensureLowRes(bmp: Bitmap, idx: Int): Bitmap {
        val lw = (bmp.width / LOW_RES_DIV).coerceAtLeast(1)
        val lh = (bmp.height / LOW_RES_DIV).coerceAtLeast(1)
        // 尺寸不变 → 复用当前 index 的 lowRes buffer；尺寸变化/首次 → 重建，旧块不 recycle（留 GC 收）
        val low = lowResBitmaps[idx]?.takeIf { it.width == lw && it.height == lh }
            ?: Bitmap.createBitmap(lw, lh, Bitmap.Config.ARGB_8888).also { lowResBitmaps[idx] = it }
        lowResCanvas.setBitmap(low)
        lowResCanvas.drawBitmap(
            bmp,
            Rect(0, 0, bmp.width, bmp.height),
            RectF(0f, 0f, lw.toFloat(), lh.toFloat()),
            lowResPaint,
        )
        return low
    }

    // ---------- 动态背景（模拟动态壁纸） ----------

    /**
     * 重建/复用当前 index 的动态背景位图并绘制（渐变 + 光斑 + 色块 + 细斜条纹 + 小字网格）。
     * @return 本帧使用的背景位图（bgBitmaps[bgIndex]，与后续 ensureLowRes/drawBitmap 同 index）。
     */
    private fun buildBackground(t: Float, W: Int, H: Int): Bitmap {
        val idx = bgIndex
        // 尺寸不变 → 复用当前 buffer；尺寸变化/首次 → 重建，旧块不 recycle（可能仍被 RenderThread 引用）
        val bmp = bgBitmaps[idx]?.takeIf { it.width == W && it.height == H }
            ?: Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888).also { bgBitmaps[idx] = it }
        bgCanvas.setBitmap(bmp)
        val w = W.toFloat()
        val h = H.toFloat()
        val angle = t * 2f * PI.toFloat()
        val s = sin(angle)
        val c = cos(angle)

        // 基础线性渐变（深蓝 ⇄ 深紫，随 t 缓慢过渡）——Paint 缓存复用，仅每帧重建渐变对象
        gradientPaint.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(
                lerpColor(0xFF16243B.toInt(), 0xFF2B1055.toInt(), 0.5f + 0.5f * s),
                lerpColor(0xFF0D1526.toInt(), 0xFF1A0933.toInt(), 0.5f + 0.5f * c),
            ),
            null,
            Shader.TileMode.CLAMP,
        )
        bgCanvas.drawRect(0f, 0f, w, h, gradientPaint)

        // 移动径向光斑（亮色，模糊/折射可见的强对比区域）
        radialPaint.shader = RadialGradient(
            w * (0.5f + 0.35f * c),
            h * (0.5f + 0.30f * s),
            w * 0.5f,
            intArrayOf(0xAA5AC8FA.toInt(), 0x448F6FFF.toInt(), 0x00000000),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        bgCanvas.drawRect(0f, 0f, w, h, radialPaint)

        // 一个清晰移动色块（检验折射位移/色散的清晰度）
        dotPaint.color = lerpColor(0xFF00E5FF.toInt(), 0xFFFF6B9D.toInt(), 0.5f + 0.5f * sin(angle * 1.7f))
        dotPaint.alpha = 200
        bgCanvas.drawCircle(
            w * (0.25f + 0.40f * cos(angle + 1.2f)),
            h * (0.25f + 0.35f * sin(angle + 1.2f)),
            w * 0.055f,
            dotPaint,
        )

        // 细斜条纹（间距 24px、线宽 4px、白色 15%；随 t 缓慢平移，折射弯折的直接可见载体）
        // 平移偏移取整：消除高频条纹的亚像素游动（浮点偏移每帧 0~1px 抖动聚合为视觉闪烁/撕裂）
        val stripeShift = (t * 60f % 24f).roundToInt().toFloat()
        var stripeX = -h - stripeShift
        while (stripeX < w) {
            bgCanvas.drawLine(stripeX, 0f, stripeX + h, h, stripePaint)  // 45° 斜线
            stripeX += 24f
        }

        // 小字网格（~20px "Aa 123" 按 ~120px 网格平铺，白色 25%；随 t 轻微漂移，
        // 验证玻璃内清晰度：内部应清晰可读，边缘应随折射弯折）——偏移同样取整
        val gridShiftX = (t * 40f % 120f).roundToInt().toFloat()
        val gridShiftY = (t * 30f % 120f).roundToInt().toFloat()
        var gridX = -120f + gridShiftX
        while (gridX < w) {
            var gridY = -120f + gridShiftY
            while (gridY < h) {
                bgCanvas.drawText("Aa 123", gridX, gridY, gridTextPaint)
                gridY += 120f
            }
            gridX += 120f
        }
        return bmp
    }

    private fun lerpColor(a: Int, b: Int, f: Float): Int {
        val k = f.coerceIn(0f, 1f)
        fun l(x: Int, y: Int) = (x + (y - x) * k).toInt()
        return Color.rgb(l(Color.red(a), Color.red(b)), l(Color.green(a), Color.green(b)), l(Color.blue(a), Color.blue(b)))
    }

    // ---------- 绘制 ----------

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (Build.VERSION.SDK_INT < 33 || width <= 0 || height <= 0) {
            canvas.drawColor(0xFF000000.toInt())
            canvas.drawText("LiquidGlass demo requires Android 13+ (RuntimeShader)", 40f, 80f, hintPaint)
            return
        }
        if (elements.isEmpty()) layoutElements(width.toFloat(), height.toFloat())

        val t = (animator.animatedValue as? Float) ?: 0f

        // 1. 动态背景（uSource 原图）→ 同步 1/4 降采样图（uSourceLowRes）。
        //    buildBackground / ensureLowRes / drawBitmap / 各元素 shader BitmapShader 全部引用
        //    bgIndex 同一 buffer（bg + lowRes 成对），本帧内自洽。
        val bmp = buildBackground(t, width, height)
        canvas.drawBitmap(bmp, 0f, 0f, bgPaint)
        val lowRes = ensureLowRes(bmp, bgIndex)

        // 2. 玻璃元素：统一「元素局部坐标」——每元素 translate 后以局部坐标 (0,0,w,h) 绘制，
        //    uViewport = 元素尺寸，uSource/srcRect = 元素 rect 对应背景区域；与 shader main()
        //    坐标契约一致，保证玻璃圆角/折射/高光精确落在元素自身区域。
        val debugF = if (debugCoord) 1f else 0f
        val scaleX = bmp.width.toFloat() / lowRes.width
        val scaleY = bmp.height.toFloat() / lowRes.height
        for (el in elements) {
            val params = uniformsFor(el)
            canvas.save()
            canvas.translate(el.rect.left, el.rect.top)
            val elSource = LiquidGlassShader.createSourceBitmapShader(bmp)
            val elLowRes = LiquidGlassShader.createLowResSourceBitmapShader(lowRes)
            // 采样区域（原图坐标）：uSourceRect = 元素 rect 对应背景区域；uSourceLowResRect =
            // 同区域折算到 1/4 低分辨率位图坐标（÷scale）。AGSL 侧 srcCoord() 用它们手动换算采样点。
            val elSrcRect = RectF(el.rect.left, el.rect.top, el.rect.right, el.rect.bottom)
            val elLowResRect = RectF(
                elSrcRect.left / scaleX, elSrcRect.top / scaleY,
                elSrcRect.right / scaleX, elSrcRect.bottom / scaleY,
            )
            // 用该元素自己的 RuntimeShader 实例（每帧重设 uniform，uSource 每帧重建的
            // BitmapShader 只喂给本元素实例，杜绝跨元素 uniform 串扰）
            LiquidGlassShader.setUniforms(el.shader, params, elSource, elLowRes, elSrcRect, elLowResRect, debugF)
            glassPaint.shader = el.shader
            canvas.drawRoundRect(0f, 0f, el.rect.width(), el.rect.height(), el.cornerRadius, el.cornerRadius, glassPaint)
            canvas.restore()
        }
        glassPaint.shader = null

        // 3. 状态文字（普通绘制，不参与玻璃验证）
        canvas.drawText(
            "mode=ElementCoord | debug=${if (debugCoord) "ON" else "off"} | t=${"%.2f".format(t)} | elems=${elements.size}",
            24f, 34f, textPaint,
        )
        canvas.drawText("long-press: debug coord (fract coord/500)", 24f, 66f, hintPaint)

        // 帧末切换双缓冲 index：下帧画入另一块。RenderThread 正在/即将读的本帧 buffer
        // 在下一帧不再被 UI 改写，竞争窗口至少拉开一帧 → 消除闪烁/撕裂。
        bgIndex = 1 - bgIndex
    }
}
