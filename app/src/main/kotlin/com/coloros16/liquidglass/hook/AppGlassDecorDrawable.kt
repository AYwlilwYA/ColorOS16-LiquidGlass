package com.coloros16.liquidglass.hook

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * B 族元素边缘光学装饰层：圆角矩形高光描边 + 内发光。
 *
 * 纯覆盖绘制（不采样背景、不重算模糊），经 View.overlay 叠加在元素内容之上，
 * 系统 SF 模糊保持原样当底。
 */
internal class AppGlassDecorDrawable(density: Float) : Drawable() {

    private val radiusPx = RADIUS_DP * density

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = maxOf(STROKE_DP * density, 1f)
        color = Color.argb(0x99, 0xFF, 0xFF, 0xFF)
    }

    /** 内发光：由内向外数层加宽递减 alpha 的描边模拟。 */
    private val glowPaints = GLOW_LAYERS.map { (dp, alpha) ->
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = maxOf(dp * density, 1f)
            color = Color.argb(alpha, 0xFF, 0xFF, 0xFF)
        }
    }

    private val rect = RectF()

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val inset = strokePaint.strokeWidth / 2f + 1f
        rect.set(
            bounds.left + inset,
            bounds.top + inset,
            bounds.right - inset,
            bounds.bottom - inset,
        )
    }

    override fun draw(canvas: Canvas) {
        if (rect.isEmpty) return
        val r = minOf(radiusPx, minOf(rect.width(), rect.height()) / 2f)
        for (p in glowPaints) canvas.drawRoundRect(rect, r, r, p)
        canvas.drawRoundRect(rect, r, r, strokePaint)
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Drawable")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        private const val RADIUS_DP = 24f
        private const val STROKE_DP = 1f

        /** 内发光层：dp 宽度 to alpha（0~255）。 */
        private val GLOW_LAYERS = listOf(
            2f to 0x38,
            5f to 0x22,
            11f to 0x14,
        )
    }
}
