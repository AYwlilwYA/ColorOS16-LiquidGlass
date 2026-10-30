package com.coloros16.liquidglass.hook

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable

/**
 * B 族元素边缘光学装饰层：方向性 rim light 描边（上亮下暗 + 四角高光）。
 *
 * 纯覆盖绘制（不采样背景、不重算模糊），经 View.overlay 叠加在元素内容之上，
 * 系统 SF 模糊保持原样当底。整体低 alpha，读作光学反射而非框线。
 */
internal class AppGlassDecorDrawable(density: Float) : Drawable() {

    private val radiusPx = RADIUS_DP * density

    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = maxOf(STROKE_DP * density, 1f)
    }

    private val rect = RectF()

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val inset = rimPaint.strokeWidth / 2f + 1f
        rect.set(
            bounds.left + inset,
            bounds.top + inset,
            bounds.right - inset,
            bounds.bottom - inset,
        )
        // sweep 角度色表：0°=右，顺时针；上/顶角亮、下边缘暗
        if (rect.isEmpty) {
            rimPaint.shader = null
            return
        }
        rimPaint.shader = SweepGradient(
            rect.centerX(),
            rect.centerY(),
            intArrayOf(
                Color.argb(RIM_SIDE, WHITE, WHITE, WHITE),
                Color.argb(RIM_BOTTOM_CORNER, WHITE, WHITE, WHITE),
                Color.argb(RIM_BOTTOM, WHITE, WHITE, WHITE),
                Color.argb(RIM_BOTTOM_CORNER, WHITE, WHITE, WHITE),
                Color.argb(RIM_SIDE, WHITE, WHITE, WHITE),
                Color.argb(RIM_TOP_CORNER, WHITE, WHITE, WHITE),
                Color.argb(RIM_TOP, WHITE, WHITE, WHITE),
                Color.argb(RIM_TOP_CORNER, WHITE, WHITE, WHITE),
            ),
            floatArrayOf(0f, 0.125f, 0.25f, 0.375f, 0.5f, 0.625f, 0.75f, 0.875f),
        )
    }

    override fun draw(canvas: Canvas) {
        if (rect.isEmpty) return
        val r = minOf(radiusPx, minOf(rect.width(), rect.height()) / 2f)
        canvas.drawRoundRect(rect, r, r, rimPaint)
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Drawable")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        private const val RADIUS_DP = 24f
        private const val STROKE_DP = 1.25f
        private const val WHITE = 0xFF

        /** rim light 各方位 alpha：顶亮、侧中、底暗。 */
        private const val RIM_TOP = 0x4A
        private const val RIM_TOP_CORNER = 0x40
        private const val RIM_SIDE = 0x30
        private const val RIM_BOTTOM_CORNER = 0x20
        private const val RIM_BOTTOM = 0x18
    }
}
