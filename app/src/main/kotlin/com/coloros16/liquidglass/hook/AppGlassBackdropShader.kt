package com.coloros16.liquidglass.hook

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build

/**
 * 背板单 input 液态玻璃（[spec/146] 路线 A，BlurButtonWrapperView 五钮等 A 族元素专用）。
 *
 * input = 本窗口内该节点下方已绘内容（`setBackgroundRenderEffect` 挂 RenderNode，每帧 GPU
 * 自动喂入，零抓屏零每帧 CPU）。主干 LiquidGlassShader 的裁剪版：保留 SDF 圆角（RBox +
 * CONIC + 短边退化守卫）、折射环带（gradRefract 方向场）、3 通道色散、vibrancy、定向边缘
 * 高光（方向 floor）；砍掉双源高斯、metaball、tilt、parallax、mask 扩张、调试 uniform。
 * 背板已是清晰采样源 → uBlurRadius 语义不存在（清晰透镜，iOS 26 观感）。
 */
internal object AppGlassBackdropShader {

    private const val AGSL = """
        uniform shader uBackdrop;        // 背板：节点下方已绘内容（每帧 GPU 自动喂入）
        uniform float2 uSize;            // 元素尺寸 px
        uniform float uCornerRadius;     // SDF 圆角半径 px
        uniform float uCornerIsConic;    // 本体角 CONIC 开关（1=贝塞尔 squircle）
        uniform float uCornerWeight;     // CONIC 角权重
        uniform float uRefractionHeight; // 折射区高度 px（自边缘向内）
        uniform float uRefractionAmount; // 折射强度 px（边缘最大位移）
        uniform float uDepth;            // 穹顶深度分量 0~1
        uniform float uDispersion;       // 色散强度（0=关）
        uniform float uHighlight;        // 高光强度
        uniform float uHighlightWidth;   // 高光带宽 px
        uniform float uHighlightFalloff; // 方向衰减指数
        uniform float uHighlightFloor;   // 方向因子保底（弧线高光不归零）
        uniform float uVibrancy;         // 饱和度倍数（1=不变）
        uniform float uLightAngle;       // 光角度（弧度）

        float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
            float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
            float outside = length(max(cornerCoord, 0.0)) - radius;
            float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
            return outside + inside;
        }

        // 归一化 SDF 梯度：边缘/角部精确法线，内部按进入深度平滑过渡到径向（全定义域连续）
        float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
            float2 a = abs(coord);
            float2 cornerCoord = a - (halfSize - float2(radius));
            float2 dirEdge = normalize(max(cornerCoord, 0.0) + float2(0.0001));
            float2 dirInner = normalize(a + float2(0.0001));
            float depth = sdRoundedRect(coord, halfSize, radius);
            float t = smoothstep(-max(radius, 1.0) * 0.5, 0.0, depth);
            return sign(coord) * normalize(mix(dirInner, dirEdge, t));
        }

        // 折射方向场：三边界特征（上/下直边、左/右直边、角弧）幂律 soft-min 加权，
        // 返回幅值 = 方向可信度（medial axis 处相消归零 → 位移同步归零，无撕裂）
        float2 gradRefract(float2 coord, float2 halfSize, float radius) {
            float2 a = abs(coord);
            float2 c = halfSize - float2(radius);
            float2 q = a - c;
            float dy = (a.x > c.x) ? length(float2(q.x, a.y - halfSize.y)) : (halfSize.y - a.y);
            float dx = (a.y > c.y) ? length(float2(a.x - halfSize.x, q.y)) : (halfSize.x - a.x);
            float dc = length(q);
            float k = 3.0;
            float ty = max(dy, 0.0) + k;
            float tx = max(dx, 0.0) + k;
            float ta = abs(dc - radius) + k;
            bool arcValid = (q.x >= 0.0 && q.y >= 0.0 && dc > 0.0001);
            float tmin = min(ty, tx);
            if (arcValid && ta < tmin) {
                tmin = ta;
            }
            float sy = tmin / ty;
            float wy = sy * sy * sy;
            float sx = tmin / tx;
            float wx = sx * sx * sx;
            float wa = 0.0;
            if (arcValid) {
                float fade = smoothstep(0.0, max(radius, 1.0) * 0.5, dc);
                float sav = tmin / ta;
                wa = fade * sav * sav * sav;
            }
            float2 v = float2(wx, wy);
            if (wa > 0.0) {
                v += wa * (q / dc);
            }
            float z = max(wy + wx + wa, 1e-4);
            return sign(coord) * (v / z);
        }

        float cubicBezierDistance(float2 p, float2 p0, float2 p1, float2 p2, float2 p3) {
            float2 c0 = p0;
            float2 c1 = 3.0 * (p1 - p0);
            float2 c2 = 3.0 * (p2 - 2.0 * p1 + p0);
            float2 c3 = p3 - 3.0 * p2 + 3.0 * p1 - p0;
            float t = 0.5;
            for (int i = 0; i < 6; i++) {
                float2 Bt   = ((c3 * t + c2) * t + c1) * t + c0;
                float2 dBt  = (3.0 * c3 * t + 2.0 * c2) * t + c1;
                float2 ddBt = 6.0 * c3 * t + 2.0 * c2;
                float2 r    = Bt - p;
                float f1 = dot(dBt, r);
                float f2 = dot(ddBt, r) + dot(dBt, dBt);
                float denom = max(f2, 1e-4);
                t = clamp(t - f1 / denom, 0.0, 1.0);
            }
            float2 Bt = ((c3 * t + c2) * t + c1) * t + c0;
            float2 re = Bt - p;
            float2 tangent = (3.0 * c3 * t + 2.0 * c2) * t + c1;
            float2 normal = float2(-tangent.y, tangent.x);
            float2 normalizedNormal = normalize(normal);
            float dis = length(re);
            return dot(re, normalizedNormal) > 0.0 ? dis : -dis;
        }

        float sdBezierDistance(float2 p, float2 b, float corner, float weight) {
            float sdf = 0.0;
            float cc = corner;
            if (any(lessThanEqual(abs(p), b - cc))) {
                sdf = sdRoundedRect(p, b, cc);
            } else {
                float point = max(cc * weight * 0.5, 0.001);
                sdf = cubicBezierDistance(
                    abs(p) - b + cc,
                    float2(cc, 0.0), float2(cc, point), float2(point, cc), float2(0.0, cc));
            }
            return sdf;
        }

        float circleMap(float x) {
            return 1.0 - sqrt(1.0 - x * x);
        }

        // 背板采样：越出节点边界回退贴边钳制（防背板裁在节点边界时环带采到透明露黑）
        half4 sampleBackdrop(float2 c) {
            float2 lim = max(uSize - float2(1.0), float2(0.0));
            return uBackdrop.eval(clamp(c, float2(0.0), lim));
        }

        half4 main(float2 coord) {
            float2 halfSize = uSize * 0.5;
            if (halfSize.x <= 1.0 || halfSize.y <= 1.0) {
                return half4(0.0);
            }
            float2 centeredCoord = coord - halfSize;
            float radius = uCornerRadius;
            // SDF：CONIC squircle + 短边退化守卫（cc 超短边一半时 bezier 向内啃 → 退化胶囊）
            float sdRaw = sdRoundedRect(centeredCoord, halfSize, radius);
            if (uCornerIsConic > 0.5) {
                float cc = 1.5 * radius;
                float minHalf = min(halfSize.x, halfSize.y);
                if (radius >= minHalf) {
                    sdRaw = sdRoundedRect(centeredCoord, halfSize, minHalf);
                } else {
                    float sdConic = sdBezierDistance(centeredCoord, halfSize, cc, uCornerWeight);
                    sdRaw = mix(sdRaw, sdConic, 1.0);
                }
            }
            float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
            float sd = min(sdRaw, 0.0);
            float4 glass;
            if (-sdRaw >= uRefractionHeight) {
                glass = float4(sampleBackdrop(coord));
            } else {
                float d = circleMap(1.0 - -sd / max(uRefractionHeight, 1.0)) * uRefractionAmount;
                float2 nBlend = gradRefract(centeredCoord, halfSize, gradRadius);
                float2 dome = centeredCoord / max(halfSize * halfSize, float2(0.0001));
                float2 domeN = float2(0.0);
                if (length(centeredCoord) > 0.0001) {
                    domeN = dome / length(dome);
                }
                float2 gradCombined = nBlend + uDepth * domeN;
                float2 n = float2(0.0);
                if (length(gradCombined) > 0.0001) {
                    n = gradCombined / length(gradCombined);
                }
                float2 refrVec = -d * n * length(nBlend);
                float dispCap = min(halfSize.x, halfSize.y);
                float dispMag = length(refrVec);
                if (dispMag > dispCap && dispMag > 0.0001) {
                    refrVec *= dispCap / dispMag;
                }
                float2 refractedCoord = coord + refrVec;
                if (uDispersion > 0.001) {
                    // 3 通道差分色散（主干 7 通道对按钮尺度过重；角区最强、轴上为 0）
                    float dispersionIntensity =
                        uDispersion * ((centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y));
                    float2 disp = refrVec * dispersionIntensity;
                    half4 rS = sampleBackdrop(refractedCoord + disp);
                    half4 gS = sampleBackdrop(refractedCoord);
                    half4 bS = sampleBackdrop(refractedCoord - disp);
                    glass = half4(rS.r, gS.g, bS.b, (rS.a + gS.a + bS.a) / 3.0);
                } else {
                    glass = sampleBackdrop(refractedCoord);
                }
            }
            // vibrancy（饱和度提升）
            float luma = dot(glass.rgb, float3(0.213, 0.715, 0.072));
            glass.rgb = mix(float3(luma), glass.rgb, uVibrancy);
            // 圆角外裁剪（2px 羽化对齐系统 shape）
            float shapeAlpha = smoothstep(0.0, -2.0, sdRaw);
            glass *= shapeAlpha;
            // 定向边缘高光：带中心内侧偏移（不跨边界）+ 方向 floor（弧线不归零）
            float hw = max(uHighlightWidth, 1.0);
            float bandCenter = max(hw * 0.5, 2.0);
            float dIn = -sdRaw;
            float band = 1.0 - smoothstep(0.0, hw, abs(dIn - bandCenter));
            float2 sdfGrad = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);
            float2 lightDir = float2(cos(uLightAngle), sin(uLightAngle));
            float floorK = clamp(uHighlightFloor, 0.0, 1.0);
            float dirDot = pow(abs(dot(sdfGrad, lightDir)), uHighlightFalloff);
            float hl = floorK + (1.0 - floorK) * dirDot;
            glass.rgb += float3(hl * band * uHighlight);
            return half4(clamp(glass, 0.0, 1.0));
        }
    """

    /** 背板玻璃效果参数（[spec/146] §1.2，按元素尺度缩放；角权重同系统 CONIC）。 */
    private const val CORNER_WEIGHT = 1.3968f
    private const val DISPERSION = 0.15f
    private const val HIGHLIGHT = 0.56f
    private const val HIGHLIGHT_WIDTH_PX = 8f
    private const val HIGHLIGHT_FALLOFF = 4f
    private const val HIGHLIGHT_FLOOR = 0.16f
    private const val VIBRANCY = 1.54f
    private const val DEPTH = 0.72f
    private const val LIGHT_ANGLE_RAD = 0.75f
    /** 折射强度 = 短边 × 0.12，钳 [6, 32] px（面板默认 100px 对小元素过大）。 */
    private const val REFRACTION_SCALE = 0.12f
    private const val REFRACTION_MIN_PX = 6f
    private const val REFRACTION_MAX_PX = 32f

    /**
     * 按元素尺寸与圆角构造背板玻璃 RenderEffect。
     *
     * @return API<33 或尺寸无效时返回 null（调用方保持原效果）；shader 编译失败向上抛。
     */
    fun createEffect(width: Int, height: Int, cornerRadius: Float, cornerIsConic: Boolean): RenderEffect? {
        if (Build.VERSION.SDK_INT < 33 || width <= 1 || height <= 1) return null
        val shader = RuntimeShader(AGSL)
        val short = minOf(width, height).toFloat()
        shader.setFloatUniform("uSize", width.toFloat(), height.toFloat())
        shader.setFloatUniform("uCornerRadius", cornerRadius.coerceIn(0f, short))
        shader.setFloatUniform("uCornerIsConic", if (cornerIsConic) 1f else 0f)
        shader.setFloatUniform("uCornerWeight", CORNER_WEIGHT)
        shader.setFloatUniform("uRefractionHeight", short * 0.5f)
        shader.setFloatUniform(
            "uRefractionAmount",
            (short * REFRACTION_SCALE).coerceIn(REFRACTION_MIN_PX, REFRACTION_MAX_PX),
        )
        shader.setFloatUniform("uDepth", DEPTH)
        shader.setFloatUniform("uDispersion", DISPERSION)
        shader.setFloatUniform("uHighlight", HIGHLIGHT)
        shader.setFloatUniform("uHighlightWidth", HIGHLIGHT_WIDTH_PX)
        shader.setFloatUniform("uHighlightFalloff", HIGHLIGHT_FALLOFF)
        shader.setFloatUniform("uHighlightFloor", HIGHLIGHT_FLOOR)
        shader.setFloatUniform("uVibrancy", VIBRANCY)
        shader.setFloatUniform("uLightAngle", LIGHT_ANGLE_RAD)
        return RenderEffect.createRuntimeShaderEffect(shader, "uBackdrop")
    }
}
