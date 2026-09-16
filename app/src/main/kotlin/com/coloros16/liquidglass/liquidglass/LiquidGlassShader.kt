package com.coloros16.liquidglass.liquidglass

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
        uniform float uCornerIsConic;    // 本体角 CONIC 开关（1=三次贝塞尔 sdBezierDistance + shader 内 1.5×半径；0=RBox 圆弧）
        uniform float uCornerWeight;     // 本体 CONIC 角权重（系统 CornerParamsKt fMax=(8·w)/(3·w+3) 变换后值；uCornerIsConic=0 时忽略）
        uniform float uCornerConicBlend; // [2026-08-13] 本体角 CONIC↔圆弧平滑过渡权重（0=纯圆弧 sdRoundedRect，1=纯 CONIC sdBezierDistance；
                                         // 运动态=0 → 跳过贝塞尔省 CONIC 迭代；复位时 Kotlin 侧动画 0→1 平滑恢复，防角形状瞬间突变闪）
        uniform float uBlurRadius;       // 内部可选轻模糊半径 px（0=清晰透镜；>0 仅作用内部，不糊折射环带）
        uniform float uRefractionHeight; // 折射区高度 px（自边缘向内）
        uniform float uRefractionAmount; // 折射强度 px（边缘最大位移；正值，AGSL 侧向内侧采样）
        uniform float uDepth;            // 深度：法线的径向分量强度（Kyant depthEffect，0 或 1）
        uniform float uDispersion;       // 色散强度（光谱采样缩放；0=关闭，建议 0~0.3）
        uniform float uHighlight;        // 高光强度（加性，建议 0.3~0.6；定向亮边而非整体发白）
        uniform float uHighlightWidth;   // 高光带宽度 px（边缘内外各此宽，模拟 Kyant 描边+模糊）
        uniform float uHighlightFalloff; // 高光方向衰减指数（Kyant ControlCenter=2；越大越聚光）
        uniform float uHighlightFloor;   // [2026-09-16 弧线高光修复] 方向因子保底：圆角弧线法线扫过 90°
                                         // 必然与光源垂直（dot=0）→ 原实现该处高光归零。0=完全回退原行为
        uniform float uVibrancy;         // 鲜艳度/饱和度倍数（Kyant vibrancy=1.5；1=不变）
        uniform float uLightAngle;       // 光角度（弧度；Kotlin 侧由角度转弧度）
        uniform vec2  uParallax;         // 视差（面板几何中心偏移，可接传感器）
        uniform float uDebugCoord;       // 调试：1 = 输出 coord/uViewport 位置色（坐标可视化）
        uniform float uDebugHighlight;   // [临时诊断 高光] 逐像素可视化：0=关；1=hl 灰度；2=band 灰度；3=sdRaw 等值线；4=sdfGrad 方向色
        uniform vec4  uMaskRect;         // 掩码矩形（viewport 局部坐标：xy=左上, zw=宽高；默认全视口=无裁剪，侧滑按钮专用）
        uniform float uMaskCornerRadius; // 掩码圆角半径 px（按钮真实圆角 = notification_corner_radius dimen，未×1.5；0=直角裁剪）
        uniform float uMaskWeight;       // 掩码 CONIC 角权重（侧滑按钮 = (8×1.1)/(3×1.1+3)=1.3968；uMaskCornerRadius<=0 时忽略）
        uniform vec2  uMaskOffset;       // [任务 A] MetaBallBlurDrawable 固有扩张偏移（viewport 局部坐标；默认 0=无扩张）。
                                         // 渲染侧把 uViewport 折算为真实尺寸时，几何 centeredCoord / 内部采样坐标需减去此偏移，
                                         // 使玻璃形状/采样对齐真实卡片（替代 maskRect 整块裁剪，spec/18 方向纠正）；uMaskOffset=(0,0) 时零变化。
        uniform float uMaskAlpha;        // [spec/48 修正 2026-08-13] 卡片内压暗已移除——本 uniform 保留仅供
                                         // BlurDrawHook 兼容传参，shader 不再使用（不再压暗卡片内部背景采样）。
                                         // 遮罩由系统 scrim 背景黑 tint 提供（spec/48 方案 A，BlurDrawHook 侧
                                         // `behind_scrim` 纯黑 tint，z-order 最底）。历史（spec/40/41）：曾用于
                                         // 把背景采样色向 uMaskColor 压暗（步骤 2.5）。
        uniform vec4  uMaskColor;        // [spec/48 修正 2026-08-13] 同上，卡片内压暗已移除——uniform 保留仅供
                                         // BlurDrawHook 兼容传参，shader 不再使用（原为遮罩颜色 straight rgba，
                                         // 默认纯黑；曾参与步骤 2.5 的压暗 mix）。

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

        // 归一化 SDF 梯度：边缘/角部指向外，内部为方向场。**全定义域连续**（见下）。
        // [2026-09-16 错位/分界线修复] 原实现是 if/else 硬切换：
        //   边缘/角区 → normalize(max(cornerCoord,0))；内部 → 轴向 (1,0)/(0,1)。
        // 两处硬分界（内部方块边界 + 对角线）都会**突变**，而该梯度经
        // `gradCombined = sdfGrad + uDepth*径向` 归一化后**驱动折射方向** →
        // 玻璃内容跨线错位/撕裂（用户真机实证：方形磁贴中间一个「叉」+ 中心区/折射区一条分界线）。
        // 数学上圆角矩形 SDF 在内部的梯度本就是分段常量，**换任何分段公式都必然有分界** ——
        // 正解是平滑过渡：按「进入内部的深度」在 径向 ↔ 边缘方向 之间 mix，全定义域连续。
        float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
            float2 a = abs(coord);
            float2 cornerCoord = a - (halfSize - float2(radius));
            // 边缘/角区方向（角区 = 角心径向；+1e-4 防 normalize(0) 产生 NaN）
            float2 dirEdge = normalize(max(cornerCoord, 0.0) + float2(0.0001));
            // 内部方向：平滑径向（无轴向跳变）
            float2 dirInner = normalize(a + float2(0.0001));
            // 深度：0 = 恰在边界，越负越深入内部；用半径的一半作过渡带宽，避免过渡区过窄
            float depth = min(cornerCoord.x, cornerCoord.y);
            float t = smoothstep(-max(radius, 1.0) * 0.5, 0.0, depth);
            return sign(coord) * normalize(mix(dirInner, dirEdge, t));
        }

        // ---------- CONIC（三次贝塞尔）圆角 SDF（系统 BlurDrawableShaderCornerStringKt.sdBezierDistance 移植） ----------
        // 侧滑按钮 MetaBall 系统真实形状 = CONIC 圆角矩形（比同半径圆弧方得多，近乎方形），掩码必须复刻它，
        // 否则圆弧 45° 处被多裁约 4 倍 → 两侧顶底角"生硬切开"。算法照搬系统反编译源码（含牛顿迭代）。
        float cubicBezierDistance(float2 p, float2 p0, float2 p1, float2 p2, float2 p3) {
            // 将贝塞尔转换为幂基形式：B(t) = c0 + c1 t + c2 t^2 + c3 t^3
            float2 c0 = p0;
            float2 c1 = 3.0 * (p1 - p0);
            float2 c2 = 3.0 * (p2 - 2.0 * p1 + p0);
            float2 c3 = p3 - 3.0 * p2 + 3.0 * p1 - p0;
            float t = 0.5; // 初始值
            // [spec/24 修复方向 2] 牛顿迭代 10 → 6 次：SDF 只需 ~1px 精度即可满足
            // smoothstep(0,-2) 的 2px 羽化带，6 次二次收敛足够（4 次可能不稳，6 保守）。
            // 二次收敛每次翻倍精度：t=0.5 起步，6 次后局部残差 ~0.5^(2^6)≈0，远低于 1px；
            // 角区每像素 -40% 迭代成本（10→6）。
            for (int i = 0; i < 6; i++) {
                float2 Bt   = ((c3 * t + c2) * t + c1) * t + c0;
                float2 dBt  = (3.0 * c3 * t + 2.0 * c2) * t + c1;
                float2 ddBt = 6.0 * c3 * t + 2.0 * c2;
                float2 r    = Bt - p;
                // E(t) = |B(t)-p|^2 的一阶、二阶导
                float f1 = dot(dBt, r);
                float f2 = dot(ddBt, r) + dot(dBt, dBt);
                // 避免除零
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
                sdf = sdRoundedRect(p, b, cc);   // 远离角区域：RBox 圆弧
            } else {
                float point = max(cc * weight * 0.5, 0.001);   // 角区：三次贝塞尔（45° 处比圆弧方得多）
                sdf = cubicBezierDistance(
                    abs(p) - b + cc,
                    float2(cc, 0.0), float2(cc, point), float2(point, cc), float2(0.0, cc));
            }
            return sdf;
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
            // [doc/spec/68 模糊质感修复] 5×5 → **7×7**（σ_idx 1.5 → 2.25；tapStep spread/2 → spread/3）：
            // 原 5×5 在 σ_idx=1.5 时只覆盖 ±2 tap = ±1.33σ，**核边缘权重仍有 0.41**（远未衰减到 0）
            // → 实际接近「方框模糊」而非高斯（观感：平、脏、不像系统那层柔和的雾）；
            // 且 tapStep = spread/2（模糊 32px 时 = 16 视口 px）过疏 → 网格/点阵伪影。
            // 7×7 + tapStep=spread/3：**覆盖范围不变**（±3 × spread/3 = ±spread）但**采样密度提高 50%**；
            // σ_phys 保持 2.25 × spread/3 = 0.75 × spread 不变 → 模糊强度不变、采样更密、核更接近高斯。
            // 代价：采样 25 → 49 tap（模糊只作用于折射环带，非全屏）。
            float tapStep = spread / 3.0;
            float4 sum = float4(0.0);
            float total = 0.0;
            for (int y = -3; y <= 3; y++) {
                float wy = gauss(float(y), 2.25);
                float4 row = float4(0.0);
                float rowTotal = 0.0;
                for (int x = -3; x <= 3; x++) {
                    float wx = gauss(float(x), 2.25);
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

            // 1. SDF 几何（uParallax 作 Kyant 的 offset，移动玻璃几何中心；uMaskOffset 作 MetaBall 固有
            //    扩张偏移——uViewport=真实尺寸时把几何中心平移到真实卡片区域，spec/18 任务 A）
            float2 halfSize = uViewport * 0.5;
            float2 centeredCoord = (coord + uParallax - uMaskOffset) - halfSize;
            float radius = uCornerRadius;
            // [spec/18 圆角过渡区深挖修正] 本体角 CONIC：对齐系统 CornerParamsKt.setCornerUniform
            // （CONIC 元素——通知卡/custom 卡/QS seekbar——Java 侧 radius×1.5 + weight 变换
            // (8·w)/(3·w+3)，shader 侧 sdBezierDistance 三次贝塞尔 45° 处比 RBox 圆弧方 ~45px；
            // 此前本体一律 RBox 圆弧 → 各种卡片角比系统方 = 圆角不一致根源）。FULL/tile
            // （pathProvider 弧线，uCornerIsConic=0）保持 RBox 圆弧。
            float sdRaw = sdRoundedRect(centeredCoord, halfSize, radius);
            float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
            if (uCornerIsConic > 0.5) {
                float cc = 1.5 * radius;               // 对齐 CornerParamsKt radius×1.5
                gradRadius = min(cc, min(halfSize.x, halfSize.y));
                // [2026-08-13 修复] 圆弧↔CONIC 平滑过渡：uCornerConicBlend（0=纯圆弧，1=纯 CONIC）。
                // 修复背景：滑动降级 CONIC（spec/24 修复方向 3）isMotionActive 复位时 uCornerIsConic
                // 瞬间 true → 角形状突变（CONIC 与圆弧 45° 处差 ~45px）→ "停止滑动后圆角边缘闪"。
                // 线性 mix 两个 SDF 近似（视觉足够平滑，无需严格距离场运算）；blend<=0.001 时跳过
                // sdBezierDistance（运动中圆弧省 CONIC 迭代，成本同 uCornerIsConic=0）。
                // 注：gradRadius 两分支恒等（均 min(1.5×radius, halfSize)），无需随 blend 过渡。
                float blend = clamp(uCornerConicBlend, 0.0, 1.0);
                // [2026-09-16 圆角自动合并修复] 对齐系统语义：半径达到短边一半时退化为圆/胶囊
                // （实证：QsViewOutlineProvider.getRoundParams:247-250 —— 半径 >= 高度一半即返回
                //  null → 圆形；OplusQsSmoothRoundUtil 的 circleParams 同理）。原实现无此退化，
                //  而 cc=1.5×radius 必然 > halfSize → sdBezierDistance 的角区判定
                //  any(|p| <= b-cc) 因 b-cc 为负而恒 false → 整个元素被当角区送进按 [0,cc]² 定义的
                //  贝塞尔、坐标系被压扁 → sdRaw 失真 → 建立其上的高光带/圆角裁剪全部错位
                //  （用户实证：细高光带在转弯处消失；圆形磁贴与细横幅最明显）。
                float minHalf = min(halfSize.x, halfSize.y);
                if (radius >= minHalf) {
                    sdRaw = sdRoundedRect(centeredCoord, halfSize, minHalf);
                } else if (blend > 0.001) {
                    float sdConic = sdBezierDistance(centeredCoord, halfSize, cc, uCornerWeight);
                    sdRaw = mix(sdRaw, sdConic, blend);
                }
            }
            float sd = min(sdRaw, 0.0);   // Kyant：外部像素 clamp 到 0（circleMap 定义域 [0,1] 防 NaN）
            // 纯 SDF 梯度（高光专用；Kyant 高光不混 depth 径向分量）
            float2 sdfGrad = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);

            float4 glass = float4(0.0);

            if (-sdRaw >= uRefractionHeight) {
                // 2a. 内部：清晰透镜（Kyant 折射 shader 早退等价：content.eval(coord) 原样输出）。
                //     uBlurRadius>0 时走可选轻模糊（采样 1/4 降采样图，5x5 高斯）。
                if (uBlurRadius > 0.01) {
                    glass = gaussianBlur(coord - uMaskOffset, uBlurRadius);
                } else {
                    glass = float4(uSource.eval(srcCoord(coord - uMaskOffset, uSourceRect)));
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
                float2 refractedCoord = (coord - uMaskOffset) + refrVec;
                // [2026-09-16 整元素模糊（B 方案）] uBlurRadius>0 时环带也走模糊采样，折射位移作用在
                // 模糊图上 → 玻璃整体呈磨砂。原实现环带恒读 uSource（全分辨率清晰图）不读 uBlurRadius，
                // 而环带宽度 = 短边一半、常占满细长元素（实测 1312x248 元素 refrH=124 = 半高，
                // 「内部」区退化为零）→ 整个玻璃一点磨砂都没有，与「背景已模糊、玻璃却清晰」的观感割裂。
                // 色散让位：spectralDispersion 需 7 次全分辨率采样，与 25-tap 模糊叠加 = 175 tap，
                // 对控制中心 20+ 元素不可接受；且糊图上的通道分离视觉上也难以分辨。需要色散时把
                // 「模糊半径」调回 0 即恢复原「清晰透镜 + 边缘色散」观感。
                if (uBlurRadius > 0.01) {
                    glass = gaussianBlur(refractedCoord, uBlurRadius);
                } else if (uDispersion > 0.001) {
                    // 光谱色散（仅环带；Kyant 同一强度公式：四角最强、轴上为 0）
                    float dispersionIntensity = uDispersion * ((centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y));
                    glass = spectralDispersion(refractedCoord, refrVec * dispersionIntensity);
                } else {
                    glass = float4(uSource.eval(srcCoord(refractedCoord, uSourceRect)));
                }
            }

            // 2.5 遮罩压暗 [2026-08-14 恢复 spec/41 语义]：把玻璃采到的背景色向 uMaskColor 压暗
            //     （遮罩→底层背景，玻璃区域与卡片间 scrim 黑 tint 展开态同为 `壁纸×(1-maskAlpha)` 统一）。
            //     uMaskAlpha=0 时 mix 系数 0 → 零开销跳过。历史：spec/48 曾整体移除（改为系统 scrim 黑 tint
            //     单一来源），快照叠黑方案验证无效后用户决定恢复 shader 内压暗（uMaskAlpha/uMaskColor 直接生效）。
            glass.rgb = mix(glass.rgb, uMaskColor.rgb, uMaskAlpha * uMaskColor.a);

            // 3. vibrancy：饱和度提升（Kyant colorControls saturation=1.5 的矩阵等价：mix(luma, rgb, s)）
            float luma = dot(glass.rgb, float3(0.213, 0.715, 0.072));
            glass.rgb = mix(float3(luma), glass.rgb, uVibrancy);

            // 5. MixColor 双层着色（默认透明→无操作；压暗交给面板背景层，不在玻璃内叠加深色）
            glass = doBlend(glass, uBlendMode, uMixColorA);
            glass = doBlend(glass, uBlendMode, uMixColorB);

            // 5.5 [2026-08-14 恢复] 遮罩压暗已在上方步骤 2.5 恢复（uMaskAlpha/uMaskColor 生效）；
            //     玻璃内部压暗与卡片间 scrim 黑 tint 展开态统一（`壁纸×(1-maskAlpha)`）。

            // 6. 掩码裁剪（侧滑按钮 MetaBallBlurDrawable 扩张区透明化）：对齐系统 shader
            //    `outputCol *= metaBallResult.mix_c * shape` 的裁剪语义——按钮实际区域（uMaskRect）
            //    之外输出 alpha=0，避免我们的玻璃画满系统 setBounds 扩张区（真机每边多 69px）变胖。
            //    [spec/12] 角 SDF 复刻系统 CONIC（sdBezierDistance 三次贝塞尔），替换圆弧近似——圆弧
            //    45° 处多裁约 4 倍导致"两侧顶底角生硬切开"；corner=1.5×uMaskCornerRadius、weight=uMaskWeight
            //    （Kotlin 侧传 1.3968），smoothstep(0,-2,sd) 对齐系统 shape（BLEND_ANTI_ALIASING=2.0）。
            //    uMaskCornerRadius<=0 → 直角矩形兜底（现有行为）。
            //    uMaskRect=全视口（默认）时 sdMask<=0 恒成立 → 掩码≈1 → 仅视口最外圈轻微羽化，不影响其他控件。
            float2 maskCenter = uMaskRect.xy + uMaskRect.zw * 0.5;
            float2 maskHalf = uMaskRect.zw * 0.5;
            float sdMask;
            if (uMaskCornerRadius > 0.0) {
                // [2026-08-13 修复] 掩码角随 uCornerConicBlend 渐混（对齐本体 spec/27）：运动态（blend=0）
                // 掩码圆弧（corner=uMaskCornerRadius raw，与本体圆弧同角不切边，O(1) 省 CONIC 迭代）；
                // 静止（blend=1）掩码 CONIC（cc=1.5×uMaskCornerRadius，对齐系统 spec/12）；中间线性渐混，
                // 本体≡掩码形状全程一致 → 消除"本体 CONIC blend 但掩码恒 CONIC"的形状冲突（生硬切开+边缘闪）。
                float cc = 1.5 * uMaskCornerRadius;   // CONIC corner = 1.5 × notification_corner_radius（=96）
                float blend = clamp(uCornerConicBlend, 0.0, 1.0);
                float sdArc = sdRoundedRect(coord - maskCenter, maskHalf, uMaskCornerRadius);
                if (blend <= 0.001) {
                    sdMask = sdArc;
                } else {
                    float sdConic = sdBezierDistance(coord - maskCenter, maskHalf, cc, uMaskWeight);
                    sdMask = mix(sdArc, sdConic, blend);
                }
            } else {
                sdMask = sdRoundedRect(coord - maskCenter, maskHalf, 0.0);
            }
            glass *= smoothstep(0.0, -2.0, sdMask);

            // [2026-08-13 修复] 圆角外裁剪：对齐系统 `outputCol *= shape`（shape=smoothstep(0,-2,sdf)）——
            // 圆角 SDF 之外（sdRaw>0，正方形角区域）alpha 归 0，消除"圆角外多余直角玻璃边"。
            // 这是所有玻璃元素的通用裁剪（不只侧滑）；maskRect 掩码（侧滑专用）在上一行已乘，
            // 此处再乘 shapeAlpha（两者 alpha 相乘），对侧滑按钮的 MetaBall 扩张区裁切职责不变。
            float shapeAlpha = smoothstep(0.0, -2.0, sdRaw);
            glass *= shapeAlpha;

            // 4. 定向边缘高光：加在圆角裁剪【之后】，band 中心偏移到圆角内侧 uHighlightWidth×0.5 处——
            //    [2026-08-23 修复] 原实现带中心在边界（abs(sdRaw)），而 shapeAlpha 在边界（sdRaw=0）=0，
            //    圆角边缘高光被裁掉（外半全裁 + 内半 0~-2 羽化削弱）。偏移内侧后高光带中心落在 alpha=1
            //    区域，完整可见、不超圆角；直边元素同样内侧亮边（不跨边界）。
            // [2026-09-16 细高光被裁修复] 带中心原为 W/2，W 小时（<4）会落进 shapeAlpha 的
            // 2px 羽化区（smoothstep(0,-2,sdRaw)）被削弱直至消失——真机反馈「高光带细时边缘
            // 完全消失、转弯处最明显」。改为带中心保底内移到 2px（=羽化宽度），W>=4 时行为不变。
            float hw = max(uHighlightWidth, 1.0);
            float band = 1.0 - smoothstep(0.0, hw, abs(-sdRaw - max(hw * 0.5, 2.0)));
            // [2026-09-16 圆角弧线高光修复·方案 C] `sdfGrad` 是**边缘法线**：直边法线固定（与光源夹角恒定
            // → 高光恒定），而圆角弧线法线要**旋转 90°**，其中**必然经过与光源垂直的位置** → 原
            // `pow(abs(dot),fo)` 在该处精确归零，弧线约 60% 跨度高光消失（真机实证：192×192 圆磁贴整圈
            // 无高光；`light=43°`、`fo=2` 时弧线中段夹角 88° → hl=0.001）。此前三轮修复调的都是 band
            // （带的位置/宽度），而实测 band 在弧线处恒为 1 —— **改错了地方**。
            // 修法：给方向因子加保底 floor —— 弧线保留基础亮度，直边方向对比保留。floor=0 完全回退原行为。
            float2 lightDir = float2(cos(uLightAngle), sin(uLightAngle));
            float floorK = clamp(uHighlightFloor, 0.0, 1.0);
            float dirDot = pow(abs(dot(sdfGrad, lightDir)), uHighlightFalloff);
            float hl = floorK + (1.0 - floorK) * dirDot;
            glass.rgb += float3(hl * band * uHighlight);

            // [临时诊断 高光] 逐像素可视化（需 uDebugHighlight>0.5）。
            // R = hl（方向因子）、G = band（带包络）、B = sdfGrad.x 映射到 [0,1]（梯度方向）。
            // 读图法：黄/橙=两者都强（直边）；纯绿=有带无方向（圆角弧线，即本 bug 现场）；
            // 纯红=有方向无带；黑=都没有（元素内部）。
            if (uDebugHighlight > 0.5) {
                return half4(clamp(float3(hl, band, sdfGrad.x * 0.5 + 0.5), 0.0, 1.0), 1.0);
            }

            // 7. 输出
            return half4(clamp(glass, 0.0, 1.0));
        }
    """.trimIndent()

    /** [临时诊断 高光] 逐像素可视化模式（AGSL `uDebugHighlight`），**编译期常量**：
     *  0=关（正常玻璃渲染）；1=输出 R=`hl`、G=`band`、B=`sdfGrad.x` 映射到 [0,1]。
     *  ⚠️ **必须在所有 uniform 下发路径（含 [setSourceRectOnly]/[setViewportAndSourceRect] 增量路径）
     *  都写** —— shader 缓存命中时走增量路径（只重设 srcRect/viewport），漏写则该 uniform 保持默认 0，
     *  调试开关**静默失效**（2026-09-16 实测踩过，见 doc/spec/67 §五）。 */
    const val HL_DEBUG_MODE = 0f

    /** 最近一次下发的 `uHighlightFloor` 值。[setSourceRectOnly]/[setViewportAndSourceRect] 增量路径
     *  **没有 params 入参**，靠它把值补发到 shader —— 否则 shader 缓存命中时该 uniform 停留在
     *  GLSL 默认 0，弧线高光修复**静默失效**（2026-09-16 实测踩过：装机后用户反馈「高光没有效果」）。
     *  所有元素共用同一份全局配置值，用静态字段安全。 */
    @Volatile
    private var lastHighlightFloor = 0.4f

    /** 掩码 CONIC 角权重默认值：(8×1.1)/(3×1.1+3) = 1.3968（侧滑按钮 MetaBall weight=1.1 经系统
     *  CornerParamsKt 变换 fMax=(8·w)/(3·w+3)；spec/12 实证）。 */
    private const val DEFAULT_MASK_WEIGHT = (8.0f * 1.1f) / (3.0f * 1.1f + 3.0f)

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
        val cornerIsConic: Boolean = false,  // 本体角 CONIC 开关（true=三次贝塞尔 + shader 内 1.5×半径；false=RBox 圆弧）
        val cornerWeight: Float = DEFAULT_MASK_WEIGHT, // 本体 CONIC 角权重（系统 CornerParamsKt (8·w)/(3·w+3) 变换后值；默认 1.3968）
        val blurRadius: Float = 0f,      // 内部可选轻模糊 px（uBlurRadius；0=清晰透镜=iOS 26 观感；>0 仅作用内部，不糊折射环带）
        val refractionHeight: Float = 48f,
        val refractionAmount: Float = 28f, // 折射强度 px（正值；AGSL 侧向内侧位移 = Kyant 负值约定）
        val depth: Float = 1f,           // 深度：法线径向分量（Kyant depthEffect=true → 1）
        val dispersion: Float = 0f,      // 色散强度（默认关；0.05~0.3 彩虹边，过强显廉价）
        val highlight: Float = 0.5f,     // 高光强度（加性≈Kyant 白色 0.5 Plus 混合）
        val highlightWidth: Float = 12f, // 高光带宽度 px
        val highlightFalloff: Float = 2f,// 高光方向衰减指数（Kyant ControlCenter=2）
        /** [2026-09-16 弧线高光修复] 方向因子保底（AGSL uHighlightFloor）：0=原行为（弧线归零），
         *  0.4=弧线保留基础高光不再断开。见 shader 内步骤 4 说明。 */
        val highlightFloor: Float = 0.4f,
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
     *               null 时保留上一次的输入（uSourceRect/uSourceLowResRect 仍更新——srcRect 变化无需
     *               重建/重绑 shader，仅重设 uniform 即可跟手）；首次为 null 则 shader 无输入，画面为黑
     *               ——调用方须先喂帧。
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
     * @param maskCornerRadius 掩码圆角半径 px（侧滑按钮真实圆角 = notification_corner_radius dimen，**未×1.5**；
     *               CONIC 的 ×1.5 由 shader 侧 sdBezierDistance 内部完成）。maskRect=null 时忽略。
     * @param maskWeight 掩码 CONIC 角权重（侧滑按钮 MetaBall weight=1.1 经 CornerParamsKt 变换
     *               (8·w)/(3·w+3) = 1.3968，默认值即此）；maskRect=null / uMaskCornerRadius<=0 时忽略。
     * @param maskOffsetX maskOffsetY [任务 A] MetaBallBlurDrawable 固有扩张偏移 px（默认 0=无扩张）。
     *               当 uViewport 被渲染侧折算为真实尺寸（bounds-2×扩张）时，shader 几何/采样坐标
     *               需减此偏移对齐真实卡片（替代 maskRect 裁剪）。(0,0) 时几何与采样零变化。
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
        maskWeight: Float = DEFAULT_MASK_WEIGHT, // 掩码 CONIC 角权重（默认 1.3968）
        cornerConicBlend: Float = 1f,  // [2026-08-13] 本体角 CONIC↔圆弧渐变权重（0=纯圆弧，1=纯 CONIC；uCornerIsConic=1 时生效）
        maskOffsetX: Float = 0f,       // [任务 A] 扩张偏移 x（uMaskOffset.x；默认 0）
        maskOffsetY: Float = 0f,       // [任务 A] 扩张偏移 y（uMaskOffset.y；默认 0）
        maskAlpha: Float = 0f,         // [spec/48 修正 2026-08-13] 参数保留仅供 BlurDrawHook 兼容传参，
                                       // shader 不再使用（卡片内压暗已移除；遮罩由系统 scrim 黑 tint 提供）。
                                       // 历史（spec/40/41）：遮罩不透明度，SystemUI 下拉所有元素统一传配置值。
        maskColor: Int = Color.BLACK,  // [spec/48 修正 2026-08-13] 同上，参数保留仅供兼容，shader 不再使用
                                       // （原为遮罩颜色 ARGB，默认黑）
    ) {
        if (source != null) {
            shader.setInputShader("uSource", source)
            shader.setInputShader("uSourceLowRes", lowResSource ?: source)
        }
        // uSourceRect/uSourceLowResRect 只影响 AGSL 侧 srcCoord() 坐标换算（shader.eval 忽略 localMatrix），
        // 与 BitmapShader 实例无关（createSourceBitmapShader 是纯单位矩阵 CLAMP，无 localMatrix）。因此
        // source==null（复用上次输入、仅 srcRect 变化）时也必须更新——滑动跟手只需重设 uniform，
        // 无需重建/重绑 shader，避免滑动热路径每帧 createSourceBitmapShader + setInputShader 的 CPU 峰值。
        shader.setFloatUniform("uSourceRect", sourceRect.left, sourceRect.top, sourceRect.width(), sourceRect.height())
        shader.setFloatUniform("uSourceLowResRect", lowResSourceRect.left, lowResSourceRect.top, lowResSourceRect.width(), lowResSourceRect.height())
        shader.setFloatUniform("uViewport", max(params.viewportWidth, 1f), max(params.viewportHeight, 1f))
        shader.setFloatUniform("uMixColorA", argbToFloat4(params.mixColorA))
        shader.setFloatUniform("uMixColorB", argbToFloat4(params.mixColorB))
        shader.setIntUniform("uBlendMode", params.blendMode)
        shader.setFloatUniform("uCornerRadius", params.cornerRadius)
        shader.setFloatUniform("uCornerIsConic", if (params.cornerIsConic) 1f else 0f)
        shader.setFloatUniform("uCornerWeight", params.cornerWeight)
        shader.setFloatUniform("uCornerConicBlend", cornerConicBlend)
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
        shader.setFloatUniform("uDebugHighlight", HL_DEBUG_MODE)
        lastHighlightFloor = params.highlightFloor
        shader.setFloatUniform("uHighlightFloor", params.highlightFloor)
        // 掩码（侧滑按钮裁剪）：默认全视口 → sdMask<=0 恒成立 → step=1 → 无裁剪（不影响其他控件）
        val mr = maskRect ?: RectF(0f, 0f, max(params.viewportWidth, 1f), max(params.viewportHeight, 1f))
        shader.setFloatUniform("uMaskRect", mr.left, mr.top, mr.width(), mr.height())
        shader.setFloatUniform("uMaskCornerRadius", maskCornerRadius)
        shader.setFloatUniform("uMaskWeight", maskWeight)
        shader.setFloatUniform("uMaskOffset", maskOffsetX, maskOffsetY)
        // [spec/48 修正 2026-08-13] 遮罩 uniform 保留传值（BlurDrawHook 仍传 maskAlpha/maskColor），
        // shader 内已不再用于卡片内压暗（原步骤 2.5 mix 已移除）——传值仅保持 uniform 接口兼容。
        shader.setFloatUniform("uMaskAlpha", maskAlpha)
        shader.setFloatUniform("uMaskColor", argbToFloat4(maskColor))
    }

    /**
     * [doc/spec/24 任务 A2 2026-08-13] 轻量 uniform 更新：仅重设 uSourceRect / uSourceLowResRect。
     *
     * 滑动热路径专用——srcRect 每帧变（元素移动 → AGSL 采样坐标实时折算），但源位图 / viewport /
     * 掩码 / 材质 uniform（uVibrancy/uHighlight/uMixColor 等）均与 srcRect 无关，无需全量重设。
     * 替代 [setUniforms]（~25 次 setFloatUniform + setInputShader + 掩码）→ 每帧仅 2 次 setFloatUniform，
     * 降低滑动时 uniform 重设的 CPU 开销（doc/spec/24 CPU 热点排序 #3）。
     *
     * 前置条件（调用方 refreshShaderUniforms 保证）：uSource/uSourceLowRes 输入 shader 已绑定且
     * 源位图未变、viewport / 掩码 / 圆角 uniform 未变；若 sourceRect 也未变则调用方直接 return（零调用）。
     *
     * @param sourceRect uSourceRect：原图 srcRect（同 [setUniforms] 语义）。
     * @param lowResSourceRect uSourceLowResRect：低分辨率 srcRect；源位图未变时恒等于 sourceRect。
     */
    fun setSourceRectOnly(
        shader: RuntimeShader,
        sourceRect: RectF,
        lowResSourceRect: RectF = sourceRect,
    ) {
        shader.setFloatUniform("uSourceRect", sourceRect.left, sourceRect.top, sourceRect.width(), sourceRect.height())
        shader.setFloatUniform("uSourceLowResRect", lowResSourceRect.left, lowResSourceRect.top, lowResSourceRect.width(), lowResSourceRect.height())
        // 调试开关 / 弧线高光保底必须在增量路径也下发（否则 shader 缓存命中时保持 GLSL 默认值）
        shader.setFloatUniform("uDebugHighlight", HL_DEBUG_MODE)
        shader.setFloatUniform("uHighlightFloor", lastHighlightFloor)
    }

    /**
     * [2026-08-13 滑动延迟修复] 轻量 uniform 更新：重设 uViewport + uSourceRect / uSourceLowResRect。
     *
     * 拖动时 resize 的控件（音量条 active 进度层填充高度每帧变、媒体卡展开动画、二级菜单）viewport
     * 每帧变，但材质 uniform（uVibrancy/uHighlight/uMixColor/uRefraction* 等）与 viewport/srcRect 无关，
     * 无需全量重设。替代 [setUniforms]（~25 次 setFloatUniform + 掩码 + buildParams 读 Prefs）→
     * 每帧仅 3~4 次 setFloatUniform（比 [setSourceRectOnly] 多一次 uViewport）。
     *
     * 前置条件（调用方 refreshShaderUniforms 保证）：uSource/uSourceLowRes 输入 shader 已绑定且源位图
     * 未变、掩码/圆角/材质 uniform 未变（buildParams 参数未变）。
     *
     * @param viewport uViewport：元素绘制尺寸 px（同 [setUniforms] params.viewportWidth/Height 语义）。
     * @param sourceRect uSourceRect：原图 srcRect（同 [setUniforms] 语义）。
     * @param lowResSourceRect uSourceLowResRect：低分辨率 srcRect；源位图未变时恒等于 sourceRect。
     * @param maskRect 调用方当前掩码矩形（同 [setUniforms] maskRect 语义）。null = 全视口无裁剪掩码，
     *              必须随 viewport 同步（否则拖拽 resize 时旧掩码把新增长区域裁掉，玻璃不跟手）；
     *              非 null（swipe/扩张元素有真实掩码）→ 调用方 maskChanged=false 保证掩码未变，跳过。
     */
    fun setViewportAndSourceRect(
        shader: RuntimeShader,
        viewport: RectF,
        sourceRect: RectF,
        lowResSourceRect: RectF = sourceRect,
        maskRect: RectF? = null,
    ) {
        shader.setFloatUniform("uSourceRect", sourceRect.left, sourceRect.top, sourceRect.width(), sourceRect.height())
        shader.setFloatUniform("uSourceLowResRect", lowResSourceRect.left, lowResSourceRect.top, lowResSourceRect.width(), lowResSourceRect.height())
        shader.setFloatUniform("uViewport", max(viewport.width(), 1f), max(viewport.height(), 1f))
        // 调试开关 / 弧线高光保底必须在增量路径也下发（否则 shader 缓存命中时保持 GLSL 默认值）
        shader.setFloatUniform("uDebugHighlight", HL_DEBUG_MODE)
        shader.setFloatUniform("uHighlightFloor", lastHighlightFloor)
        // [2026-08-13 滑动延迟修复] 全视口掩码（maskRect=null，非 swipe/无扩张元素的默认）实际值
        // = viewport（见 setUniforms `maskRect ?: 全视口`）；viewport 随拖动变化时掩码必须同步，
        // 否则 shader 侧 `glass *= smoothstep(0,-2,sdMask)` 会把旧掩码外（新增填充区）的玻璃裁掉。
        // 有真实掩码的元素（maskRect!=null，maskChanged=false 保证未变）不在此重设。
        if (maskRect == null) {
            shader.setFloatUniform("uMaskRect", 0f, 0f, max(viewport.width(), 1f), max(viewport.height(), 1f))
        }
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
