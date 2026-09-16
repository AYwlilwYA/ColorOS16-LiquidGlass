// ============================================================================
// 来源：app 模块 `com.coloros16.liquidglass.liquidglass.LiquidGlassShader`（主模块共用实现）
// 拷贝时间：2026-08-12。demo 独立验证用，改 demo 版不影响主模块；主模块改动后如需要
// 同步此副本（保持 shader uniform 接口一致）。
// ============================================================================
package com.coloros16.liquidglass.demo.liquidglass

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Color
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.max

/**
 * 液态玻璃 AGSL RuntimeShader 组装。
 *
 * M1 重构（2026-08-12）：Kyant 分层液态玻璃 —— **清晰内部透镜 + 内侧折射环带（可选色散）+ vibrancy
 * 鲜艳度 + 定向边缘高光** + ColorOS MixColor 双层着色（默认透明）。此前版本"全区域高斯模糊 +
 * 正向（向外）折射"实为普通毛玻璃（demo 截图实证：灰蒙暗色块、无透镜感、无边缘弯折），已废弃；
 * 模糊降级为内部可选轻效果（uBlurRadius 默认 0）。算法对齐 Kyant0/AndroidLiquidGlass
 * （KMP/Skiko → Android 原生 AGSL）：
 * - `backdrop/.../internal/Shaders.kt`：RoundedRectSDF（sdRoundedRect/gradSdRoundedRect）、
 *   RoundedRectRefraction（circleMap + 折射位移）、RoundedRectRefractionWithDispersion（7 通道光谱色散）、
 *   DefaultHighlightShader（定向高光）
 * - MixColor 混合：移植 ColorOS 反编译 `com/oplus/posteffect/agsl/BlurDrawableShaderStringKt`
 *   （GLSL→AGSL，保留 blendMode 枚举 0~10 语义；uMixColorA/B = BlurParam.blendColorA/B）
 *
 * uniform 接口按 doc/spec/04-技术方案.md 第 4 节；采样为 AGSL 的 `uSource.eval(coord)`（shader 类型采样，
 * 非 GLSL texture()）。AGSL 差异处理：float/int 严格（循环计数器须 cast 为 float 再参与向量运算）、
 * 动态向量索引 dst[i] 改显式 .r/.g/.b、避免 normalize(0) 产生 NaN。
 *
 * 性能：内部仅 1 次采样（清晰透镜；uBlurRadius>0 时才走 5x5=25 次可选模糊）；色散 7 次采样仅在
 * 折射环带执行（分支隔离）。原图采样坐标由 AGSL 侧 srcCoord() 手动折算（shader.eval 忽略
 * BitmapShader localMatrix，本地矩阵方案已废弃）。
 */
object LiquidGlassShader {

    /** AGSL 液态玻璃核心源码。 */
    val AGSL_SOURCE: String = """
        // ============================================================================
        // 液态玻璃 AGSL 核心（2026-08-12 重构：Kyant 分层结构）
        // - 结构：清晰内部透镜（早退等价）+ 内侧折射环带（+可选光谱色散）+ vibrancy + 定向边缘高光
        // - 折射方向：采样点向元素【内侧】位移（= Kyant Lens.kt 对 refractionAmount 取负的语义；
        //   旧版向外位移导致边缘"漏背景"，非透镜弯折，已废弃）
        // - 高斯模糊：自研 5x5 可分离，仅作内部可选轻效果（uBlurRadius=0 时完全不采样 uSourceLowRes）
        // - MixColor：ColorOS 反编译 BlendMode 语义（BlurDrawableShaderStringKt，GLSL→AGSL），默认透明无操作
        // ============================================================================
        uniform shader uSource;          // 未模糊原图（全分辨率；折射/色散/锐化采样用，Hook A 喂入）
        uniform shader uSourceLowRes;    // 未模糊原图 1/4 降采样（高斯模糊采样用；消除全分辨率欠采样网格伪影）
        uniform vec4  uSourceRect;       // uSource 原图 srcRect（xy=原点, zw=尺寸）
        uniform vec4  uSourceLowResRect; // uSourceLowRes 低分辨率 srcRect（xy=原点, zw=尺寸）
        uniform vec2  uViewport;         // 面板绘制尺寸 px（fragCoord ∈ [0,W]×[0,H]，local 坐标）
        uniform vec4  uMixColorA;        // 上层着色（BlurParam.blendColorA，straight rgba）
        uniform vec4  uMixColorB;        // 下层着色（BlurParam.blendColorB，straight rgba）
        uniform int   uBlendMode;        // ColorOS BlendMode 枚举 0~10（见 Kotlin BlendMode）
        uniform float uCornerRadius;     // SDF 圆角半径 px（几何/折射/高光用，与模糊半径解耦）
        uniform float uBlurRadius;       // 内部可选轻模糊半径 px（0=清晰透镜；>0 仅作用内部，不糊折射环带）
        uniform float uRefractionHeight; // 折射区高度 px（自边缘向内）
        uniform float uRefractionAmount; // 折射强度 px（边缘最大位移；正值，AGSL 侧向内侧采样）
        uniform float uDepth;            // 深度：法线的径向分量强度（Kyant depthEffect，0 或 1）
        uniform float uDispersion;       // 色散强度（光谱采样缩放；0=关闭，建议 0~0.3）
        uniform float uHighlight;        // 高光强度（加性，建议 0.3~0.6；定向亮边而非整体发白）
        uniform float uHighlightWidth;   // 高光带宽度 px（边缘内外各此宽，模拟 Kyant 描边+模糊）
        uniform float uHighlightFalloff; // 高光方向衰减指数（Kyant ControlCenter=2；越大越聚光）
        uniform float uVibrancy;         // 鲜艳度/饱和度倍数（Kyant vibrancy=1.5；1=不变）
        uniform float uLightAngle;       // 光角度（弧度；Kotlin 侧由角度转弧度）
        uniform vec2  uParallax;         // 视差（面板几何中心偏移，可接传感器）
        uniform float uDebugCoord;       // 调试：1 = 输出 coord/uViewport 位置色（坐标可视化）
        uniform vec4  uMaskRect;         // 掩码矩形（viewport 局部坐标：xy=左上, zw=宽高；默认全视口=无裁剪，侧滑按钮专用）
        uniform float uMaskCornerRadius; // 掩码圆角半径 px（按钮真实圆角 = notification_corner_radius dimen；0=直角裁剪）

        // ---------- SDF 圆角矩形（Kyant0 RoundedRectSDF 移植） ----------
        // 元素局部 coord → bitmap 像素坐标（uViewport=元素尺寸；不依赖 BitmapShader localMatrix，
        // AGSL shader.eval 忽略 localMatrix，直接以 coord 为像素索引，故采样坐标须手动折算）
        float2 srcCoord(float2 c, float4 rect) {
            return rect.xy + (c / max(uViewport, float2(1.0))) * rect.zw;
        }

        float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
            float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
            float outside = length(max(cornerCoord, 0.0)) - radius;
            float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
            return outside + inside;
        }

        // 归一化 SDF 梯度：边缘/角部指向外侧，内部退化为方向场（高光用）
        float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
            float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
            if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
                // +1e-4 防 normalize(0) 产生 NaN
                return sign(coord) * normalize(max(cornerCoord, 0.0) + float2(0.0001));
            } else {
                float gradX = step(cornerCoord.y, cornerCoord.x);
                return sign(coord) * float2(gradX, 1.0 - gradX);
            }
        }

        // 圆形边缘映射：x∈[0,1] → [0,1]，先快后缓（折射位移包络）
        float circleMap(float x) {
            return 1.0 - sqrt(1.0 - x * x);
        }

        // ---------- 高斯模糊（5x5 可分离；BLUR_RANGE=2 → 25 次采样） ----------
        float gauss(float x, float sigma) {
            return exp(-0.5 * (x / sigma) * (x / sigma));
        }

        float4 gaussianBlur(float2 coord, float spread) {
            // 采样 1/4 降采样图（uSourceLowRes）：其 BitmapShader 矩阵已把 viewport 折算到
            // 低分辨率 srcRect（等价于「在 1/4 图上取 coord/4 处采样」），tapStep 在低分辨率
            // 图内等效缩小 4 倍（视口 5px / 低分辨率图 1.25px），配合双线性插值——消除
            // 全分辨率欠采样（图标细节周期 2~10px << 原 tapStep 14px）产生的网格/莫尔纹伪影；
            // 低分辨率图模糊计算量也远小于全分辨率。最远 tap 在 ±spread(=uBlurRadius) 视口 px。
            float tapStep = spread / 2.0;
            float4 sum = float4(0.0);
            float total = 0.0;
            for (int y = -2; y <= 2; y++) {
                float wy = gauss(float(y), 1.5);
                float4 row = float4(0.0);
                float rowTotal = 0.0;
                for (int x = -2; x <= 2; x++) {
                    float wx = gauss(float(x), 1.5);
                    row += float4(uSourceLowRes.eval(srcCoord(coord + float2(float(x), float(y)) * tapStep, uSourceLowResRect))) * wx;
                    rowTotal += wx;
                }
                sum += (row / rowTotal) * wy;   // 每行先归一化（x 方向高斯）
                total += wy;                     // 再按 y 加权（可分离 2-pass 语义）
            }
            return sum / total;
        }

        // ---------- 光谱色散（Kyant0 RoundedRectRefractionWithDispersion 移植） ----------
        // 7 通道采样（红橙黄绿青蓝紫），权重使零偏移时还原原色（色散=0 → 输出 == 源色）
        float4 spectralDispersion(float2 refractedCoord, float2 dispersedCoord) {
            float4 color = float4(0.0);

            float4 red = float4(uSource.eval(srcCoord(refractedCoord + dispersedCoord, uSourceRect)));
            color.r += red.r / 3.5;
            color.a += red.a / 7.0;

            float4 orange = float4(uSource.eval(srcCoord(refractedCoord + dispersedCoord * (2.0 / 3.0), uSourceRect)));
            color.r += orange.r / 3.5;
            color.g += orange.g / 7.0;
            color.a += orange.a / 7.0;

            float4 yellow = float4(uSource.eval(srcCoord(refractedCoord + dispersedCoord * (1.0 / 3.0), uSourceRect)));
            color.r += yellow.r / 3.5;
            color.g += yellow.g / 3.5;
            color.a += yellow.a / 7.0;

            float4 green = float4(uSource.eval(srcCoord(refractedCoord, uSourceRect)));
            color.g += green.g / 3.5;
            color.a += green.a / 7.0;

            float4 cyan = float4(uSource.eval(srcCoord(refractedCoord - dispersedCoord * (1.0 / 3.0), uSourceRect)));
            color.g += cyan.g / 3.5;
            color.b += cyan.b / 3.0;
            color.a += cyan.a / 7.0;

            float4 blue = float4(uSource.eval(srcCoord(refractedCoord - dispersedCoord * (2.0 / 3.0), uSourceRect)));
            color.b += blue.b / 3.0;
            color.a += blue.a / 7.0;

            float4 purple = float4(uSource.eval(srcCoord(refractedCoord - dispersedCoord, uSourceRect)));
            color.r += purple.r / 7.0;
            color.b += purple.b / 3.0;
            color.a += purple.a / 7.0;

            return color;
        }

        // ---------- ColorOS MixColor BlendMode 移植（BlurDrawableShaderStringKt 反编译） ----------
        // 语义：doBlend(col, mode, blendColor)：col=玻璃色，blendColor=straight rgba（BlurParam 颜色）
        // 原 GLSL 用 dst[i] 动态索引，AGSL 改为显式 .r/.g/.b
        float4 colorDodgeBlend(float4 src, float4 dst) {
            float alphaOut = src.a + dst.a - src.a * dst.a;
            float r = 0.0;
            if (dst.r == 0.0) {
                r = src.r * (1.0 - dst.a);
            } else {
                float cR = abs(src.a - src.r);
                if (cR == 0.0) {
                    r = src.a * dst.a + src.r * (1.0 - dst.a) + dst.r * (1.0 - src.a);
                } else {
                    cR = min(dst.a, (dst.r * src.a) / (cR + 0.00000001));
                    r = (cR * src.a + src.r * (1.0 - dst.a)) + dst.r * (1.0 - src.a);
                }
            }
            float g = 0.0;
            if (dst.g == 0.0) {
                g = src.g * (1.0 - dst.a);
            } else {
                float cG = abs(src.a - src.g);
                if (cG == 0.0) {
                    g = src.a * dst.a + src.g * (1.0 - dst.a) + dst.g * (1.0 - src.a);
                } else {
                    cG = min(dst.a, (dst.g * src.a) / (cG + 0.00000001));
                    g = (cG * src.a + src.g * (1.0 - dst.a)) + dst.g * (1.0 - src.a);
                }
            }
            float b = 0.0;
            if (dst.b == 0.0) {
                b = src.b * (1.0 - dst.a);
            } else {
                float cB = abs(src.a - src.b);
                if (cB == 0.0) {
                    b = src.a * dst.a + src.b * (1.0 - dst.a) + dst.b * (1.0 - src.a);
                } else {
                    cB = min(dst.a, (dst.b * src.a) / (cB + 0.00000001));
                    b = (cB * src.a + src.b * (1.0 - dst.a)) + dst.b * (1.0 - src.a);
                }
            }
            return float4(r, g, b, alphaOut);
        }

        float colorDodge(float s, float d) {
            return d / (1.0 - s + 0.00000001);
        }

        float3 colorDodge3(float3 s, float3 d) {
            return float3(colorDodge(s.x, d.x), colorDodge(s.y, d.y), colorDodge(s.z, d.z));
        }

        float4 lighter(float4 src, float4 dst) {
            float r = (1.0 - dst.a) * src.r + (1.0 - src.a) * dst.r + max(src.r, dst.r);
            float g = (1.0 - dst.a) * src.g + (1.0 - src.a) * dst.g + max(src.g, dst.g);
            float b = (1.0 - dst.a) * src.b + (1.0 - src.a) * dst.b + max(src.b, dst.b);
            float a = src.a + dst.a - src.a * dst.a;
            return float4(r, g, b, a);
        }

        float3 luminosity(float3 s, float3 d) {
            float dLum = dot(d, float3(0.3, 0.59, 0.11));
            float sLum = dot(s, float3(0.3, 0.59, 0.11));
            float lum = sLum - dLum;
            float3 c = d + lum;
            float minC = min(min(c.x, c.y), c.z);
            float maxC = max(max(c.x, c.y), c.z);
            if (minC < 0.0) {
                return sLum + ((c - sLum) * sLum) / (sLum - minC);
            } else if (maxC > 1.0) {
                return sLum + ((c - sLum) * (1.0 - sLum)) / (maxC - sLum);
            } else {
                return c;
            }
        }

        float overlay(float s, float d) {
            if (d < 0.5) {
                return 2.0 * s * d;
            } else {
                return 1.0 - 2.0 * (1.0 - s) * (1.0 - d);
            }
        }

        float3 overlay3(float3 s, float3 d) {
            return float3(overlay(s.x, d.x), overlay(s.y, d.y), overlay(s.z, d.z));
        }

        float4 plusDarker(float4 src, float4 dst) {
            return float4(
                clamp(src.r + dst.r - 1.0, 0.0, 1.0),
                clamp(src.g + dst.g - 1.0, 0.0, 1.0),
                clamp(src.b + dst.b - 1.0, 0.0, 1.0),
                clamp(src.a + dst.a - 1.0, 0.0, 1.0)
            );
        }

        float4 plusLighter(float4 src, float4 dst) {
            return float4(
                clamp(src.r + dst.r, 0.0, 1.0),
                clamp(src.g + dst.g, 0.0, 1.0),
                clamp(src.b + dst.b, 0.0, 1.0),
                clamp(src.a + dst.a, 0.0, 1.0)
            );
        }

        float3 rgb2hsv(float3 c) {
            float4 K = float4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
            float4 p = mix(float4(c.bg, K.wz), float4(c.gb, K.xy), step(c.b, c.g));
            float4 q = mix(float4(p.xyw, c.r), float4(c.r, p.yzx), step(p.x, c.r));
            float d = q.x - min(q.w, q.y);
            float e = 1.0e-10;
            return float3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
        }

        float3 hsv2rgb(float3 c) {
            float4 K = float4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
            float3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
            return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
        }

        float4 saturation(float4 src, float4 dst) {
            float a = src.a + dst.a - src.a * dst.a;
            float3 srcHsv = rgb2hsv(src.rgb);
            float3 dstHsv = rgb2hsv(dst.rgb);
            dstHsv = float3(dstHsv.x, srcHsv.y, dstHsv.z);
            return float4(hsv2rgb(dstHsv), a);
        }

        float softLight(float2 s, float2 d) {
            if (2.0 * s.x <= s.y) {
                return (d.x * d.x * (s.y - 2.0 * s.x) / (d.y + 0.00000001)) + (1.0 - d.y) * s.x + d.x * (-s.y + 2.0 * s.x + 1.0);
            } else if (4.0 * d.x <= d.y) {
                float DSqd = d.x * d.x;
                float DCub = DSqd * d.x;
                float DaSqd = d.y * d.y;
                float DaCub = DaSqd * d.y;
                return (DaSqd * (s.x - d.x * (3.0 * s.y - 6.0 * s.x - 1.0)) + 12.0 * d.y * DSqd * (s.y - 2.0 * s.x) - 16.0 * DCub * (s.y - 2.0 * s.x) - DaCub * s.x) / (DaSqd + 0.00000001);
            } else {
                return d.x * (s.y - 2.0 * s.x + 1.0) + s.x - sqrt(d.y * d.x) * (s.y - 2.0 * s.x) - d.y * s.x;
            }
        }

        float4 soft_light(float4 src, float4 dst) {
            if (dst.a == 0.0) {
                return src;
            }
            float a = src.a + (1.0 - src.a) * dst.a;
            float r = softLight(float2(src.r, src.a), float2(dst.r, dst.a));
            float g = softLight(float2(src.g, src.a), float2(dst.g, dst.a));
            float b = softLight(float2(src.b, src.a), float2(dst.b, dst.a));
            return float4(clamp(r, 0.0, 1.0), clamp(g, 0.0, 1.0), clamp(b, 0.0, 1.0), a);
        }

        // ColorOS BlendMode 枚举（0~10）；主函数对 uMixColorA/B 各调一次
        float4 doBlend(float4 col, int blendMode, float4 blendColor) {
            if (blendMode == 1) {              // MIX
                col = float4(mix(col.rgb, blendColor.rgb, blendColor.a), col.a);
            } else if (blendMode == 2) {       // OVERLAY
                float3 overlayCol = overlay3(blendColor.rgb, col.rgb);
                col = float4(mix(col.rgb, overlayCol, blendColor.a), col.a);
            } else if (blendMode == 3) {       // DODGE_BG
                float3 dodgeCol = colorDodge3(blendColor.rgb, col.rgb);
                col = float4(mix(col.rgb, dodgeCol, blendColor.a), col.a);
            } else if (blendMode == 4) {       // DODGE_FG
                col = colorDodgeBlend(blendColor, col);
            } else if (blendMode == 5) {       // LUMINOSITY
                float3 lumCol = luminosity(blendColor.rgb, col.rgb);
                col = float4(mix(col.rgb, lumCol, blendColor.a), col.a);
            } else if (blendMode == 6) {       // LIGHTEN
                col = lighter(blendColor, col);
            } else if (blendMode == 7) {       // SATURATION
                col = saturation(blendColor, col);
            } else if (blendMode == 8) {       // SOFT_LIGHT
                col = soft_light(blendColor, col);
            } else if (blendMode == 9) {       // PLUS_DARKER
                col = plusDarker(blendColor, col);
            } else if (blendMode == 10) {      // PLUS_LIGHTER
                col = plusLighter(blendColor, col);
            }
            return col;
        }

        // ================= main 管线（Kyant 分层结构：清晰内部 + 折射环带 + vibrancy + 边缘高光） =================
        // iOS 26 观感 = 内部清晰透镜 + 边缘环带弯折 + 鲜亮色彩（vibrancy）+ 定向边缘亮线。
        // 注意：整体模糊 = 普通毛玻璃（2026-08-12 重构前的错误形态，已废弃）。
        half4 main(float2 coord) {
            // [调试] uDebugCoord>0.5 → 决定性实验：coord/500 取小数。
            // 像素坐标(0~1440) → fract(coord/500) 周期渐变（每 500px 红/绿各循环一次，
            // 1440 宽 → 红通道约 3 段 0~1 循环）；归一化 [0,1] → coord/500≈0 → 全黑。
            if (uDebugCoord > 0.5) {
                return half4(fract(coord.x / 500.0), fract(coord.y / 500.0), 0.0, 1.0);
            }

            // 1. SDF 几何（uParallax 作 Kyant 的 offset，移动玻璃几何中心）
            float2 halfSize = uViewport * 0.5;
            float2 centeredCoord = (coord + uParallax) - halfSize;
            float radius = uCornerRadius;
            float sdRaw = sdRoundedRect(centeredCoord, halfSize, radius);
            float sd = min(sdRaw, 0.0);   // Kyant：外部像素 clamp 到 0（circleMap 定义域 [0,1] 防 NaN）
            float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
            // 纯 SDF 梯度（高光专用；Kyant 高光不混 depth 径向分量）
            float2 sdfGrad = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);

            float4 glass = float4(0.0);

            if (-sdRaw >= uRefractionHeight) {
                // 2a. 内部：清晰透镜（Kyant 折射 shader 早退等价：content.eval(coord) 原样输出）。
                //     uBlurRadius>0 时走可选轻模糊（采样 1/4 降采样图，5x5 高斯）。
                if (uBlurRadius > 0.01) {
                    glass = gaussianBlur(coord, uBlurRadius);
                } else {
                    glass = float4(uSource.eval(srcCoord(coord, uSourceRect)));
                }
            } else {
                // 2b. 折射环带：边缘位移最大，向内趋 0
                float d = circleMap(1.0 - -sd / max(uRefractionHeight, 1.0)) * uRefractionAmount;
                // 法线 = 归一化(SDF 梯度 + uDepth×径向)（Kyant：grad + depthEffect*normalize(centeredCoord)）
                float2 gradCombined = sdfGrad + uDepth * (centeredCoord / max(length(centeredCoord), 0.0001));
                float2 n = float2(0.0);
                if (length(gradCombined) > 0.0001) {
                    n = gradCombined / length(gradCombined);
                }
                // 采样点向元素【内侧】位移（负方向）= Kyant Lens.kt refractionAmount 取负的语义 → 透镜弯折
                float2 refrVec = -d * n;
                float2 refractedCoord = coord + refrVec;
                if (uDispersion > 0.001) {
                    // 光谱色散（仅环带；Kyant 同一强度公式：四角最强、轴上为 0）
                    float dispersionIntensity = uDispersion * ((centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y));
                    glass = spectralDispersion(refractedCoord, refrVec * dispersionIntensity);
                } else {
                    glass = float4(uSource.eval(srcCoord(refractedCoord, uSourceRect)));
                }
            }

            // 3. vibrancy：饱和度提升（Kyant colorControls saturation=1.5 的矩阵等价：mix(luma, rgb, s)）
            float luma = dot(glass.rgb, float3(0.213, 0.715, 0.072));
            glass.rgb = mix(float3(luma), glass.rgb, uVibrancy);

            // 4. 定向边缘高光：纯 SDF 梯度 · 光方向（pow 衰减，Kyant ControlCenter falloff=2）
            //    × 边缘带（|sd|<uHighlightWidth，内外对称，模拟 Kyant 描边+模糊层）× 强度（加性≈Plus 混合）
            float band = 1.0 - smoothstep(0.0, max(uHighlightWidth, 1.0), abs(sdRaw));
            float2 lightDir = float2(cos(uLightAngle), sin(uLightAngle));
            float hl = pow(abs(dot(sdfGrad, lightDir)), uHighlightFalloff);
            glass.rgb += float3(hl * band * uHighlight);

            // 5. MixColor 双层着色（默认透明→无操作；压暗交给面板背景层，不在玻璃内叠加深色）
            glass = doBlend(glass, uBlendMode, uMixColorA);
            glass = doBlend(glass, uBlendMode, uMixColorB);

            // 6. 掩码裁剪（侧滑按钮 MetaBallBlurDrawable 扩张区透明化）：对齐系统 shader
            //    `outputCol *= metaBallResult.mix_c * shape` 的裁剪语义——按钮实际区域（uMaskRect）
            //    之外输出 alpha=0，避免我们的玻璃画满系统 setBounds 扩张区（真机每边多 69px）变胖。
            //    边界用 smoothstep 渐变淡出（MASK_EDGE px 内 alpha 1→0），替代硬 step，消除"生硬切开"。
            //    uMaskRect=全视口（默认）时 sdMask<=0 恒成立 → 掩码≈1 → 仅视口最外圈轻微羽化，不影响其他控件。
            float2 maskCenter = uMaskRect.xy + uMaskRect.zw * 0.5;
            float2 maskHalf = uMaskRect.zw * 0.5;
            float maskRadius = min(uMaskCornerRadius, min(maskHalf.x, maskHalf.y));
            float sdMask = sdRoundedRect(coord - maskCenter, maskHalf, maskRadius);
            // 平滑渐变掩码：边界内侧 MASK_EDGE px 内 alpha 1→0 自然淡出（smoothstep 替代硬 step，
            // 消除"生硬切开"；sdMask<=-MASK_EDGE 全保留，sdMask>=0 全透明，语义与 step 一致）
            const float MASK_EDGE = 3.0;
            glass *= 1.0 - smoothstep(-MASK_EDGE, 0.0, sdMask);

            // 7. 输出
            return half4(clamp(glass, 0.0, 1.0));
        }
    """.trimIndent()

    /** ColorOS BlendMode 枚举（事实依据：反编译 BlurDrawableShaderStringKt，doBlend 分支 0~10）。 */
    object BlendMode {
        const val NONE = 0         // 不混合（doBlend 原样返回）
        const val MIX = 1          // col.xyz = mix(col.xyz, blendColor.xyz, blendColor.a)
        const val OVERLAY = 2
        const val DODGE_BG = 3
        const val DODGE_FG = 4
        const val LUMINOSITY = 5
        const val LIGHTEN = 6
        const val SATURATION = 7
        const val SOFT_LIGHT = 8
        const val PLUS_DARKER = 9
        const val PLUS_LIGHTER = 10
    }

    /** 液态玻璃材质参数（uniform 镜像，见 AGSL_SOURCE 各 uniform 注释）。 */
    data class Params(
        val viewportWidth: Float,
        val viewportHeight: Float,
        val mixColorA: Int = 0,          // ARGB（BlurParam.blendColorA）
        val mixColorB: Int = 0,          // ARGB（BlurParam.blendColorB）
        val blendMode: Int = BlendMode.MIX,
        val cornerRadius: Float = 28f,   // SDF 圆角半径 px（uCornerRadius；几何/折射/高光用）
        val blurRadius: Float = 0f,      // 内部可选轻模糊 px（uBlurRadius；0=清晰透镜=iOS 26 观感；>0 仅作用内部，不糊折射环带）
        val refractionHeight: Float = 48f,
        val refractionAmount: Float = 28f, // 折射强度 px（正值；AGSL 侧向内侧位移 = Kyant 负值约定）
        val depth: Float = 1f,           // 深度：法线径向分量（Kyant depthEffect=true → 1）
        val dispersion: Float = 0f,      // 色散强度（默认关；0.05~0.3 彩虹边，过强显廉价）
        val highlight: Float = 0.5f,     // 高光强度（加性≈Kyant 白色 0.5 Plus 混合）
        val highlightWidth: Float = 12f, // 高光带宽度 px
        val highlightFalloff: Float = 2f,// 高光方向衰减指数（Kyant ControlCenter=2）
        val vibrancy: Float = 1.5f,      // 鲜艳度/饱和度倍数（Kyant vibrancy=1.5；1=不变）
        val lightAngleDegrees: Float = 45f,
        val parallaxX: Float = 0f,
        val parallaxY: Float = 0f,
    )

    @Volatile
    private var cachedShader: RuntimeShader? = null

    /** 编译一个新的液态玻璃 RuntimeShader（每次调用都会重新编译 AGSL，开销大；常规绘制请用 [getShader]）。 */
    fun create(): RuntimeShader = RuntimeShader(AGSL_SOURCE)

    /** 兼容别名（BlurDrawHook 注释引用 createShader()，语义同 [create]）。 */
    fun createShader(): RuntimeShader = create()

    /** 返回缓存的液态玻璃 RuntimeShader（懒创建，进程内复用，避免每帧重编译 AGSL）。 */
    fun getShader(): RuntimeShader {
        cachedShader?.let { return it }
        return synchronized(this) {
            cachedShader?.let { return it }
            RuntimeShader(AGSL_SOURCE).also { cachedShader = it }
        }
    }

    /**
     * 统一设置全部 uniform（含输入原图）。每帧重绘前调用。
     *
     * @param source uSource 输入：未模糊原图（全分辨率）的 BitmapShader（见 [createSourceBitmapShader]）。
     *               null 时保留上一次的输入；首次为 null 则 shader 无输入，画面为黑——调用方须先喂帧。
     * @param lowResSource uSourceLowRes 输入：1/4 降采样原图的 BitmapShader（见
     *               [createLowResSourceBitmapShader]）。null 时回退使用 [source]（等效旧的全分辨率模糊，
     *               渲染不中断）。
     * @param sourceRect uSourceRect：uSource 原图 srcRect（xy=元素区域在 bitmap 中的原点, zw=尺寸 px）。
     *               AGSL 侧用它把元素局部 coord 手动换算到 bitmap 像素坐标（shader.eval 忽略 localMatrix）。
     * @param lowResSourceRect uSourceLowResRect：uSourceLowRes 低分辨率 srcRect（低分辨率位图坐标，
     *               全分辨率 srcRect ÷ scale）。
     * @param debugCoord 调试开关：>0.5 时 shader 输出 coord/uViewport 位置色（坐标空间实证），默认关闭。
     * @param maskRect 掩码矩形（viewport 局部坐标：xy=左上, zw=宽高）。null=全视口（无裁剪）。
     *               侧滑按钮（MetaBallBlurDrawable 扩张区）传按钮实际区域，扩张区之外 alpha=0。
     * @param maskCornerRadius 掩码圆角半径 px（侧滑按钮真实圆角 = notification_corner_radius dimen）。
     *               maskRect=null 时忽略。
     */
    fun setUniforms(
        shader: RuntimeShader,
        params: Params,
        source: Shader?,
        lowResSource: Shader? = null,
        sourceRect: RectF = RectF(0f, 0f, 1f, 1f),       // uSourceRect（原图坐标 srcRect）
        lowResSourceRect: RectF = sourceRect,             // uSourceLowResRect（低分辨率坐标 srcRect）
        debugCoord: Float = 0f,
        maskRect: RectF? = null,       // 掩码矩形（viewport 局部坐标：xy=左上, zw=宽高）；null=全视口无裁剪
        maskCornerRadius: Float = 0f,  // 掩码圆角半径 px（按钮真实圆角）；maskRect=null 时忽略
    ) {
        if (source != null) {
            shader.setInputShader("uSource", source)
            shader.setInputShader("uSourceLowRes", lowResSource ?: source)
            shader.setFloatUniform("uSourceRect", sourceRect.left, sourceRect.top, sourceRect.width(), sourceRect.height())
            shader.setFloatUniform("uSourceLowResRect", lowResSourceRect.left, lowResSourceRect.top, lowResSourceRect.width(), lowResSourceRect.height())
        }
        shader.setFloatUniform("uViewport", max(params.viewportWidth, 1f), max(params.viewportHeight, 1f))
        shader.setFloatUniform("uMixColorA", argbToFloat4(params.mixColorA))
        shader.setFloatUniform("uMixColorB", argbToFloat4(params.mixColorB))
        shader.setIntUniform("uBlendMode", params.blendMode)
        shader.setFloatUniform("uCornerRadius", params.cornerRadius)
        shader.setFloatUniform("uBlurRadius", params.blurRadius)
        shader.setFloatUniform("uRefractionHeight", params.refractionHeight)
        shader.setFloatUniform("uRefractionAmount", params.refractionAmount)
        shader.setFloatUniform("uDepth", params.depth)
        shader.setFloatUniform("uDispersion", params.dispersion)
        shader.setFloatUniform("uHighlight", params.highlight)
        shader.setFloatUniform("uHighlightWidth", params.highlightWidth)
        shader.setFloatUniform("uHighlightFalloff", params.highlightFalloff)
        shader.setFloatUniform("uVibrancy", params.vibrancy)
        shader.setFloatUniform("uLightAngle", (params.lightAngleDegrees * PI / 180.0).toFloat())
        shader.setFloatUniform("uParallax", params.parallaxX, params.parallaxY)
        shader.setFloatUniform("uDebugCoord", debugCoord)
        // 掩码（侧滑按钮裁剪）：默认全视口 → sdMask<=0 恒成立 → step=1 → 无裁剪（不影响其他控件）
        val mr = maskRect ?: RectF(0f, 0f, max(params.viewportWidth, 1f), max(params.viewportHeight, 1f))
        shader.setFloatUniform("uMaskRect", mr.left, mr.top, mr.width(), mr.height())
        shader.setFloatUniform("uMaskCornerRadius", maskCornerRadius)
    }

    /** ARGB int → AGSL straight rgba float4（与 ColorOS BlurParam.toFloatArray 的 /255.0 一致）。 */
    fun argbToFloat4(color: Int): FloatArray = floatArrayOf(
        Color.red(color) / 255f,
        Color.green(color) / 255f,
        Color.blue(color) / 255f,
        Color.alpha(color) / 255f,
    )

    /**
     * 构造 uSource 用的 BitmapShader：单位矩阵（不做 setRectToRect 映射）。
     * 注意：AGSL `uSource.eval(coord)` 忽略 BitmapShader 的 localMatrix，直接用 coord 当 bitmap
     * 像素索引——坐标映射已移到 AGSL 侧 `srcCoord(coord, uSourceRect)` 手动完成（uSourceRect
     * 由 [setUniforms] 传入），此处仅提供 CLAMP 位图采样。
     */
    fun createSourceBitmapShader(bitmap: Bitmap): BitmapShader =
        BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)

    /**
     * 构造 uSourceLowRes 用的 BitmapShader：1/4 降采样原图 + 单位矩阵（不做 setRectToRect 映射）。
     * 坐标映射与 [createSourceBitmapShader] 同理已移到 AGSL 侧 srcCoord(coord, uSourceLowResRect)，
     * 低分辨率 srcRect（全分辨率 srcRect ÷ scale）由 [setUniforms] 的 lowResSourceRect 传入。
     *
     * @param lowResBitmap 1/4 降采样原图（原图 `Bitmap.createScaledBitmap(w/4, h/4, true)`）。
     */
    fun createLowResSourceBitmapShader(lowResBitmap: Bitmap): BitmapShader =
        BitmapShader(lowResBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
}
