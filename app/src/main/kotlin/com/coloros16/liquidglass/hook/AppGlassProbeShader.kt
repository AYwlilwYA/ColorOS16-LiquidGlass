package com.coloros16.liquidglass.hook

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build

/**
 * A 族 shader input 覆盖范围探针。
 *
 * 把目标 View 的背景 RenderEffect 临时换成该探针：主体透传 input（原本模糊处变清晰，
 * 顺带证明 backdrop 喂入通道工作），边缘环带向外偏移采样 input 并按结果染色：
 * - 红 = 界外采样到与贴边钳制不同的真实内容（input 覆盖超出节点，折射环带可行）
 * - 绿 = 界外采样透明（input 裁在节点边界内）
 * - 黄 = 界外非透明但与贴边钳制采样相同（背景均匀时的歧义档，换复杂背景复测）
 */
internal object AppGlassProbeShader {

    private const val AGSL = """
        uniform shader input;
        uniform float2 uSize;
        uniform float uRing;
        uniform float uShift;

        half4 main(float2 coord) {
            if (uSize.x <= 1.0 || uSize.y <= 1.0) {
                return input.eval(coord);
            }
            half4 base = input.eval(coord);
            float2 d = min(coord, uSize - coord);
            float edge = min(d.x, d.y);
            if (edge > uRing) {
                return base;
            }
            float2 n = d.x < d.y
                ? float2(coord.x < uSize.x * 0.5 ? -1.0 : 1.0, 0.0)
                : float2(0.0, coord.y < uSize.y * 0.5 ? -1.0 : 1.0);
            float2 farCoord = coord + n * uShift;
            half4 far = input.eval(farCoord);
            float2 clamped = clamp(farCoord, float2(0.0), uSize - float2(1.0));
            half4 edgeSample = input.eval(clamped);
            float diff = length(far.rgb - edgeSample.rgb) + abs(far.a - edgeSample.a);
            if (far.a > 0.5 && diff > 0.15) {
                return half4(1.0, 0.0, 0.0, 1.0);
            }
            if (far.a > 0.5) {
                return half4(1.0, 1.0, 0.0, 1.0);
            }
            return half4(0.0, 1.0, 0.0, 1.0);
        }
    """

    /** 环带宽 / 向外探距（px，节点尺度）。 */
    private const val RING_PX = 28f
    private const val SHIFT_PX = 56f

    /**
     * 按当前 View 尺寸构造探针 RenderEffect。
     *
     * @return API<33 或尺寸无效时返回 null（调用方保留原效果）；shader 编译失败向上抛（调用方记日志后回退）。
     */
    fun createEffect(width: Int, height: Int): RenderEffect? {
        if (Build.VERSION.SDK_INT < 33 || width <= 1 || height <= 1) return null
        val shader = RuntimeShader(AGSL)
        shader.setFloatUniform("uSize", width.toFloat(), height.toFloat())
        shader.setFloatUniform("uRing", RING_PX)
        shader.setFloatUniform("uShift", SHIFT_PX)
        return RenderEffect.createRuntimeShaderEffect(shader, "input")
    }
}
