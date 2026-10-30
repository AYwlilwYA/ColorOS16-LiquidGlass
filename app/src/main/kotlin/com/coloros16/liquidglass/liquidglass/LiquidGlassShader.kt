package com.coloros16.liquidglass.liquidglass

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Color
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * 液态玻璃 AGSL RuntimeShader 组装。
 *
 * M1 重构（2026-08-12）：Kyant 分层液态玻璃 —— **清晰内部透镜 + 内侧折射环带（可选色散）+ vibrancy
 * 鲜艳度 + 定向边缘高光**。此前版本"全区域高斯模糊 +
 * 正向（向外）折射"实为普通毛玻璃（demo 截图实证：灰蒙暗色块、无透镜感、无边缘弯折），已废弃；
 * 模糊降级为内部可选轻效果（uBlurRadius 默认 0）。算法对齐 Kyant0/AndroidLiquidGlass
 * （KMP/Skiko → Android 原生 AGSL）：
 * - `backdrop/.../internal/Shaders.kt`：RoundedRectSDF（sdRoundedRect/gradSdRoundedRect）、
 *   RoundedRectRefraction（circleMap + 折射位移）、RoundedRectRefractionWithDispersion（7 通道光谱色散）、
 *   DefaultHighlightShader（定向高光）
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
        // - [2026-10-10 doc/spec/97] 折射方向场改走 gradRefract（最近边界特征法线 soft-min）：
        //   旧的 gradSdRoundedRect(mix)+球面径向在宽元素内部转成"从中心径向"（偏离局部法线 70~80°）
        //   → 玻璃内部出现奇怪扭曲图案；gradSdRoundedRect 自本版起**只喂高光 hl**。
        // - 高斯模糊：自研 5x5 可分离，仅作内部可选轻效果（uBlurRadius=0 时完全不采样 uSourceLowRes）
        // ============================================================================
        uniform shader uSource;          // 未模糊原图（全分辨率；折射/色散/锐化采样用，Hook A 喂入）
        uniform shader uSourceLowRes;    // 未模糊原图 1/4 降采样（高斯模糊采样用；消除全分辨率欠采样网格伪影）
        uniform vec4  uSourceRect;       // uSource 原图 srcRect（xy=原点, zw=尺寸）
        uniform vec4  uSourceLowResRect; // uSourceLowRes 低分辨率 srcRect（xy=原点, zw=尺寸）
        uniform vec2  uSourceSize;       // uSource 位图像素尺寸；(0,0)=调用方未喂尺寸 → 越界判定只判负方向
        uniform vec2  uViewport;         // 面板绘制尺寸 px（fragCoord ∈ [0,W]×[0,H]，local 坐标）
        uniform float uCornerRadius;     // SDF 圆角半径 px（几何/折射/高光用，与模糊半径解耦）
        uniform float uCornerIsConic;    // 本体角 CONIC 开关（1=三次贝塞尔 sdBezierDistance + shader 内 1.5×半径；0=RBox 圆弧）
        uniform float uCornerWeight;     // 本体 CONIC 角权重（系统 CornerParamsKt fMax=(8·w)/(3·w+3) 变换后值；uCornerIsConic=0 时忽略）
        uniform float uCornerConicBlend; // [2026-08-13] 本体角 CONIC↔圆弧平滑过渡权重（0=纯圆弧 sdRoundedRect，1=纯 CONIC sdBezierDistance；
                                         // 运动态=0 → 跳过贝塞尔省 CONIC 迭代；复位时 Kotlin 侧动画 0→1 平滑恢复，防角形状瞬间突变闪）
        uniform float uBlurRadius;       // 内部可选轻模糊半径 px（0=清晰透镜；>0 仅作用内部，不糊折射环带）
        uniform float uRefractionHeight; // 折射区高度 px（自边缘向内；= 元素短边 × refraction_height_ratio，默认 0.5=短边一半，见 doc/spec/95）
        uniform float uRefractionAmount; // 折射强度 px（边缘最大位移；正值，AGSL 侧向内侧采样）
        uniform float uDepth;            // 深度：法线的径向分量强度（Kyant depthEffect，0 或 1）
        uniform float uDispersion;       // 色散强度（光谱采样缩放；0=关闭，建议 0~0.3）
        uniform float uHighlight;        // 高光强度（加性，建议 0.3~0.6；定向亮边而非整体发白）
        uniform float uHighlightWidth;   // 高光带宽度 px（边缘内外各此宽，模拟 Kyant 描边+模糊）
        uniform float uHighlightFalloff; // 高光方向衰减指数（Kyant ControlCenter=2；越大越聚光）
        uniform float uHighlightFloor;   // [2026-09-16 弧线高光修复] 方向因子保底：圆角弧线法线扫过 90°
                                         // 必然与光源垂直（dot=0）→ 原实现该处高光归零。0=完全回退原行为
        uniform float uHighlightInnerAtt; // [2026-10-13] 内侧高光衰减 0~1：band 内半向玻璃深处再乘此衰减，
                                         // 0=无内侧衰减（现状）、1=内半几乎归零（只剩贴边一条线）
        uniform float uInnerGlow;        // 内侧光 0~1：边缘向中心的全向发光强度（区别于定向边缘高光）；0=关
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

        // ---------- [doc/spec/100 方案2] 系统 metaball 黏连桥 uniform（全部带默认值） ----------
        // uMetaballEnable 默认 0 → 下方融合段整段不执行 → 非 metaball 元素（普通通知卡/QS/文件夹）
        // 逐像素零变化（回归基准）。值由 BlurDrawHook 从系统 MetaBallBlurDrawable 的 metaBallParams
        // （MetaBallBlurDrawable.syncToShader 已同步好的那份）反射喂入。
        uniform float uMetaballEnable; // 0=关（默认）；1=启用 metaball 融合
        uniform vec4  uBodyRect;       // 主球（= 本体）：xy=中心（viewport 局部）, zw=半尺寸
        uniform vec4  uBall1;          // 伴球 1：xy=中心（viewport 局部）, zw=半尺寸；半尺寸<0.001=无此球
        uniform vec4  uBall2;          // 伴球 2（同上）
        uniform vec4  uBall3;          // 伴球 3（同上；系统 MAX_METABALL_COUNT=3 含主球，故至多 2 颗伴球）
        uniform vec4  uMetaballParam;  // x=blendShape（主体/伴球同值，见 spec/100 §2.2）
                                       // y=blendStrength（系统 shader 常量 BLEND_STRENGTH=15.0）
                                       // z=blendMode（0=横向扩张 / 1=纵向扩张） w=保留

        // ---------- [doc/spec/112] 通知卡倾斜（路线 B 玻璃内假透视） ----------
        // 只斜玻璃不斜文字、不碰 region/触摸；驱动 = 通知弹出/收回动画（BlurDrawHook.mountNotificationTilt）。
        // x=sin(θ)、y=cos(θ)（θ = 强度×动画进度×基准角，Kotlin 侧算好 → shader 内零三角函数），
        // z=透视距离 px（Kotlin 侧常量），w=启用（1=开；0=默认 → tiltCoord 恒等返回 = 逐像素零变化）。
        uniform vec4  uTilt;

        // ---------- SDF 圆角矩形（Kyant0 RoundedRectSDF 移植） ----------
        // 元素局部 coord → bitmap 像素坐标（uViewport=元素尺寸；不依赖 BitmapShader localMatrix，
        // AGSL shader.eval 忽略 localMatrix，直接以 coord 为像素索引，故采样坐标须手动折算）
        float2 srcCoord(float2 c, float4 rect) {
            return rect.xy + (c / max(uViewport, float2(1.0))) * rect.zw;
        }

        // 采样坐标是否越出快照位图有效区：负方向恒判；正方向仅在 uSourceSize>0（Kotlin 已喂位图尺寸）时判
        bool srcOutOfSource(float2 s) {
            if (s.x < 0.0 || s.y < 0.0) return true;
            if (uSourceSize.x > 0.0 && s.x > uSourceSize.x) return true;
            if (uSourceSize.y > 0.0 && s.y > uSourceSize.y) return true;
            return false;
        }

        float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
            float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
            float outside = length(max(cornerCoord, 0.0)) - radius;
            float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
            return outside + inside;
        }

        // 归一化 SDF 梯度：边缘/角部指向外，内部为方向场。**全定义域连续**（见下）。
        // ★ [2026-10-10 spec/97] 本函数自本版起**只喂高光 hl**（`dot(sdfGrad, lightDir)`）——
        //   折射方向已改走下面的 [gradRefract]。原因：折射带宽 = 0.5×短边（spec/95），带深入到
        //   元素内部，而本函数内部取「径向」（离局部边界法线最远 70~80°）→ 内部扭曲图案；
        //   高光带只有 uHighlightWidth（12px）宽、恒在边界附近（t≈1 → 精确边缘法线），不受影响。
        //   spec/67 的 floor 与 spec/71 的「深度用 SDF 真值」两条修复**原样保留**。
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
            // 深度 = SDF 真值（边界 0、内部为负），用半径的一半作过渡带宽，避免过渡区过窄。
            // [2026-09-16 顶边高光「圆角-亮-暗-亮-圆角」修复] 原用 `min(cornerCoord.x, cornerCoord.y)`
            // 度量深度，但该量**只在角区才等于深度**：宽元素（w >> h）顶边主体的
            // cornerCoord = (|x|-(w-r), r)，其 x 分量恒为 -(w-r) 这种大负数 →
            // **整条顶边被判成「内部」** → 取 dirInner（径向梯度）而非 dirEdge（竖直法线）。
            // 径向方向随 x 一路旋转，与光源夹角必然扫过 90°：顶边处
            // dot = (x·cosθ − h·sinθ)/L，零点在 x = h·tanθ（1312×248、θ=43° → x≈±116）→
            // 高光在顶边裂成亮-暗-亮两处断点（真机实证）。高 > 宽 的元素则是左右边。
            float depth = sdRoundedRect(coord, halfSize, radius);
            float t = smoothstep(-max(radius, 1.0) * 0.5, 0.0, depth);
            return sign(coord) * normalize(mix(dirInner, dirEdge, t));
        }

        // ---------- 折射方向场 [2026-10-10 doc/spec/97]：最近边界特征法线的 soft-min 加权平均 ----------
        // 为什么换：折射带宽 = 短边一半（uRefractionHeight，spec/95），带会深入元素内部；旧场
        // （上面的 gradSdRoundedRect 内部径向 + uDepth×球面径向）在宽元素内部给出的几乎是
        // 「从元素中心指向外」的方向，与局部边界法线能差 70~80° —— 1312×248 上边 x=500、深度 30px 处
        // 旧方向离水平仅 10.6°（=离竖直 79°，实测位移 横向 9.6px / 竖向 1.8px），内容被朝中心横向拽 →
        // 用户实证「分界线没了，但效果奇怪、不是玻璃该有的效果」。
        // 真玻璃（边缘倒角 / 平板玻璃）观感 = **边缘法线折射为主、越往中心越平缓**，故改为：
        //   三条边界特征（abs 对称，只需算第一象限）——
        //     ① 上/下直边：线段法线 (0,1)   ② 左/右直边：线段法线 (1,0)   ③ 角弧：圆心径向
        //   权重 w = 1/(d+k)^3（幂律 soft-min，k=3px）——
        //     · 近边界：最近特征权重压倒性占优 → 等于**精确边界法线**（高光带/最大位移区不变形）；
        //     · 内部：多特征竞争 → 连续平均，方向被限制在「最近几个真实墙面法线」的凸包内，
        //       不会再甩到径向那种离谱方向；
        //   **返回值的幅值 = 方向可信度 |v/z| ∈ [0,1]**：medial axis（中轴线）上两墙面法线反向相消
        //   → 幅值→0 → 位移同步→0，中轴处的 180° 方向翻转被幅值归零掩盖，不产生撕裂。
        // 两个仿真抓出来的病态点（必须处理，否则内部打旋）：
        //   · 角弧只在自己的角象限有效（q.x>=0 && q.y>=0，即径向投影落在弧段内）；否则该处最近特征
        //     是直边，用 |q|-radius 会给出偏小的假距离 → 方向被弧带偏；
        //   · |q|→0（角弧圆心 = 三特征重合的 medial axis）时 normalize(q) 不可导、方向随 1px 任意转
        //     → smoothstep(0, radius*0.5, |q|) 淡出，交回直边特征（|q|→0 时弧权重→0，方向由直边接管）。
        // 仿真 4 种几何（宽/方/高/胶囊）实测：相邻像素最大方向角跳 14.3°，全部落在位移 ~1px 的中轴区；
        // 位移向量最大相邻跳变 5.0~5.7px，与旧场同几何差 ≤0.01px（该跳变本就来自 circleMap 边界陡坡）
        // → 无新增撕裂、零 NaN。
        float2 gradRefract(float2 coord, float2 halfSize, float radius) {
            float2 a = abs(coord);
            float2 c = halfSize - float2(radius);      // 角弧圆心（core 角点）
            float2 q = a - c;                          // 指向角弧圆心的偏移（a-space）
            // 到线段的距离：角区外投影到线段端点（线段端点 = 角弧切点，两者天然衔接）
            float dy = (a.x > c.x) ? length(float2(q.x, a.y - halfSize.y)) : (halfSize.y - a.y);
            float dx = (a.y > c.y) ? length(float2(a.x - halfSize.x, q.y)) : (halfSize.x - a.x);
            float dc = length(q);
            float k = 3.0;
            float ty = max(dy, 0.0) + k;
            float tx = max(dx, 0.0) + k;
            float ta = abs(dc - radius) + k;
            bool arcValid = (q.x >= 0.0 && q.y >= 0.0 && dc > 0.0001);
            // 权重写成 (tmin/t)^3 ∈ (0,1]（tmin = 最近特征的 d+k）：尺度在 v 与 z 中相消、
            // 结果与直接用 1/(d+k)^3 完全相同，但全程不产生 1e-9 级极小量（免精度/NaN 风险），
            // 且 z ≥ ~1。**不能**用 1e-20 这类下限兜底：fp16 会把它冲成 0 → 除零。
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

        // ================= [doc/spec/100 方案2] 系统 metaball 黏连桥（smooth-min 移植） =================
        // @LG-MB-START （spec/119 拆源：BASE 源整段剥离，仅 FULL 源保留）
        // 移植对象：reference/systemui-16.99.12 的
        //   com/oplus/posteffect/agsl/BlurDrawableShaderMetaBallStringKt.java
        //   · :22 平滑并集   h = clamp(0.5 + 0.5*(d1-d2)/k, 0, 1); d = mix(d1,d2,h) - k*h*(1-h)
        //   · :5  强度公式   k = BLEND_STRENGTH * gain(smoothstep(0,1,diff_between), BLEND_PROCESS_CONTROL) * mask
        //   · PART_3/4      shape-change 外扩：本体与伴球各自朝对方平移 blendShape*range_d → 沙漏收腰
        //   · :23 常量表（BLEND_SHAPE_CHANGE=6 / STRENGTH_SMOOTH=1 / PROCESS_CONTROL=0.3 /
        //         EDGE_FADE=20 / ARC_START=1 / ARC_END=0.85 / ARC_RANGE=5）
        //   · rectProessX/Y（RECT_PROCESS_X/Y :2-:43）：shortLen / 点_c / 方向 / dis
        // shapeRangeFactorX/Y = 1.2 / 2.0（MetaBallBlurDrawable 构造常量，:61/:62 与
        // MetaBallBlendManager:97/:104 实证）。
        // **uMetaballEnable == 0（默认）时本段完全不执行** → 非 metaball 元素逐像素零变化。
        const float MB_SHAPE_CHANGE = 6.0;
        const float MB_STRENGTH_SMOOTH = 1.0;
        const float MB_PROCESS_CONTROL = 0.3;
        const float MB_EDGE_FADE = 20.0;
        const float MB_ARC_START = 1.0;
        const float MB_ARC_END = 0.85;
        const float MB_ARC_RANGE = 5.0;
        const float MB_SRFX = 1.2;
        const float MB_SRFY = 2.0;

        // 系统 BLUR_DRAWABLE_SHADER_GAIN_METHOD（:11）
        float mbGain(float x, float k) {
            float a = 0.5 * pow(2.0 * ((x < 0.5) ? x : (1.0 - x)), k);
            return (x < 0.5) ? a : (1.0 - a);
        }
        // @LG-MB-END （mbGain 前一段剥离；mbShapeSDF 主渲染无条件使用，BASE 必须保留）

        // 本体 / 伴球共用的圆角矩形 SDF —— 与 main() 改动前的 sdRaw 段**逐行等价**
        // （[spec/18] 本体角 CONIC：cc=1.5×radius、圆弧↔CONIC 平滑过渡、半径≥短边一半退化为胶囊）。
        // 抽成函数是为了让伴球用与本体**完全同一套角模型**（主体与伴球在系统里同源于 setCornerParams）。
        float mbShapeSDF(float2 p, float2 hs) {
            float r = uCornerRadius;
            float d = sdRoundedRect(p, hs, r);
            if (uCornerIsConic > 0.5) {
                float cc = 1.5 * r;
                float minHalf = min(hs.x, hs.y);
                if (r >= minHalf) {
                    d = sdRoundedRect(p, hs, minHalf);
                } else {
                    float blend = clamp(uCornerConicBlend, 0.0, 1.0);
                    if (blend > 0.001) {
                        float sdConic = sdBezierDistance(p, hs, cc, uCornerWeight);
                        d = mix(d, sdConic, blend);
                    }
                }
            }
            return d;
        }

        // @LG-MB-START （MbPair + mbFuse 仅 metaball 需要，BASE 剥离）
        // 单个「本体 ↔ 伴球」pairwise 融合的结果：SDF + 两个方向场（高光 / 折射）
        struct MbPair {
            float d;
            float2 g;
            float2 r;
        };

        // 系统 rectProessX/Y + PART_3/4/5 的 pairwise 分支（i=本体、j=伴球）。
        //   w        viewport 局部坐标（已含 uParallax/uMaskOffset 修正）
        //   bodyC/H  本体中心 / 半尺寸；ballC/H 伴球中心 / 半尺寸
        //   corner   圆角（本体与伴球同用 uCornerRadius，见 mbShapeSDF 注释）
        //   bsBody / bsBall  各自的 blendShape（两个调用方实证同值，Kotlin 侧传同一份）
        //   strength blendStrength（系统 15）；mode blendMode（0=X 横向 / 1=Y 纵向）
        //   bodyGradR 本体方向场所需 gradRadius（与 main() 的 gradRadius 同源）
        // 返回 min(原始伴球, smooth-min(位移后本体, 位移后伴球)) 及其梯度 / 折射方向。
        // 球不存在（半尺寸 <0.001）→ d=1e6 永不参与 union（系统 `all(greaterThanEqual(...,0.001))` 同义）。
        MbPair mbFuse(
            float2 w,
            float2 bodyC, float2 bodyH,
            float2 ballC, float2 ballH,
            float corner, float bsBody, float bsBall,
            float strength, float mode, float bodyGradR
        ) {
            MbPair o;
            o.d = 1000000.0;
            o.g = float2(0.0, 1.0);
            o.r = float2(0.0, 1.0);
            if (!all(greaterThanEqual(ballH, vec2(0.001)))) {
                return o;
            }
            bool xMode = mode < 0.5;

            // ---- combineRect + shortLen（RECT_PROCESS_X:3-:11 / RECT_PROCESS_Y 同构）----
            vec2 pl = min(bodyC - bodyH, ballC - ballH);
            vec2 pr = max(bodyC + bodyH, ballC + ballH);
            vec2 cC = 0.5 * (pl + pr);
            vec2 hC = 0.5 * (pr - pl);
            float shortLen = xMode
                ? (bodyH.x + ballH.x - hC.x) * 2.0
                : (bodyH.y + ballH.y - hC.y) * 2.0;
            float c0 = max(corner, 1.0);   // 防 smoothstep(edge0==edge1) 除零（角=0 时）
            float axisH = xMode ? hC.x : hC.y;
            float axisMax = xMode ? max(bodyH.x, ballH.x) : max(bodyH.y, ballH.y);
            float perpDiff = xMode ? abs(bodyH.y - ballH.y) : abs(bodyH.x - ballH.x);
            float dualP = smoothstep(-c0, c0, shortLen);
            float unDualP = smoothstep(c0, 0.0, axisH - axisMax);
            float pAlign = mix(dualP, unDualP, smoothstep(0.0, c0, perpDiff));

            // ---- 分离量 dis / point_c / dir_c（RECT_PROCESS_X:12-:42；系统 dy 恒 0，本实现同构）----
            float cmpDeep = xMode ? min(bodyH.x, ballH.x) : min(bodyH.y, ballH.y);
            vec2 cut = xMode ? vec2(cmpDeep, 0.0) : vec2(0.0, cmpDeep);
            vec2 aMin = bodyC - (bodyH - cut);
            vec2 aMax = bodyC + (bodyH - cut);
            vec2 bMin = ballC - (ballH - cut);
            vec2 bMax = ballC + (ballH - cut);
            bool oX = aMax.x >= bMin.x && bMax.x >= aMin.x;
            bool oY = aMax.y >= bMin.y && bMax.y >= aMin.y;
            float dis = 0.0;
            vec2 pointC = vec2(0.0);
            vec2 dirC = xMode ? vec2(1.0, 0.0) : vec2(0.0, 1.0);
            if (!(oX && oY)) {
                pointC = vec2(
                    min(aMax.x, bMax.x) + max(aMin.x, bMin.x),
                    min(aMax.y, bMax.y) + max(aMin.y, bMin.y)) * 0.5;
                float sep = 0.0;
                if (xMode) {
                    if (aMax.x < bMin.x) { sep = bMin.x - aMax.x; }
                    else if (bMax.x < aMin.x) { sep = -(aMin.x - bMax.x); }
                    if (sep != 0.0) { dirC = vec2(sep > 0.0 ? 1.0 : -1.0, 0.0); }
                } else {
                    if (aMax.y < bMin.y) { sep = bMin.y - aMax.y; }
                    else if (bMax.y < aMin.y) { sep = -(aMin.y - bMax.y); }
                    if (sep != 0.0) { dirC = vec2(0.0, sep > 0.0 ? 1.0 : -1.0); }
                }
                dis = abs(sep);
            }

            // ---- shapeChange + 朝对方外扩位移（系统 PART_3 :24-:58）----
            float space = max(-shortLen, 0.0);
            float rangeBlend = bsBody + bsBall;
            float shapeChange = step(0.0001, dis) *
                smoothstep(rangeBlend + MB_SHAPE_CHANGE, rangeBlend, space);
            float2 pL = w - bodyC;
            float2 qL = w - ballC;
            if (shapeChange > 0.001) {
                vec2 pw = w - pointC;
                float disDirY = abs(dot(pw, dirC));
                float disDirX = abs(dot(pw, vec2(dirC.y, -dirC.x)));
                float minHalf = xMode ? min(bodyH.y, ballH.y) : min(bodyH.x, ballH.x);
                float minHalfAxis = xMode ? min(bodyH.x, ballH.x) : min(bodyH.y, ballH.y);
                float disBetween = -shapeChange * rangeBlend;   // 必须在 ×shapeChangePlus 之前（系统 :41→:43）
                shapeChange *= smoothstep(minHalfAxis, disBetween, shortLen);
                float uS = mix(MB_ARC_START, MB_ARC_END,
                    smoothstep(MB_ARC_RANGE, 0.0, abs(disBetween - shortLen)));
                float fadeX = mbGain(smoothstep(max(minHalf * MB_SRFX, 0.001), 0.0, disDirX), uS);
                float win1 = max(bsBody * MB_SRFY + space * 0.5, 0.001);
                float s1 = bsBody * smoothstep(win1, 0.0, disDirY) * fadeX * shapeChange;
                if (s1 > 0.001) { pL = pL - dirC * s1; }        // 本体朝伴球外扩（p1 += -dir*strength）
                float win2 = max(bsBall * MB_SRFY + space * 0.5, 0.001);
                float s2 = bsBall * smoothstep(win2, 0.0, disDirY) * fadeX * shapeChange;
                if (s2 > 0.001) { qL = qL + dirC * s2; }        // 伴球朝本体外扩（p2 += +dir*strength）
            }

            // ---- mask + blendStrength（系统 PART_5 :2-:13）----
            float shapeD = sdRoundedRect(w - cC, hC, c0);
            float mask = 1.0 - smoothstep(MB_EDGE_FADE, 0.0, abs(shapeD)) * pAlign;
            float diffBetween = shortLen + shapeChange * rangeBlend;
            float k = strength *
                mbGain(smoothstep(0.0, MB_STRENGTH_SMOOTH, diffBetween), MB_PROCESS_CONTROL) * mask;

            // ---- smooth-min（系统 PART_5 :14-:20）+ 梯度凸组合 ----
            float ballGradR = min(uCornerRadius * 1.5, min(ballH.x, ballH.y));
            float d1 = mbShapeSDF(pL, bodyH);
            float d2 = mbShapeSDF(qL, ballH);
            float2 gBody = gradSdRoundedRect(pL, bodyH, bodyGradR);
            float2 gBall = gradSdRoundedRect(qL, ballH, ballGradR);
            float2 rBody = gradRefract(pL, bodyH, bodyGradR);
            float2 rBall = gradRefract(qL, ballH, ballGradR);
            float h = 0.0;
            float dPair = 0.0;
            if (k > 0.001) {
                h = clamp(0.5 + 0.5 * (d1 - d2) / k, 0.0, 1.0);
                dPair = mix(d1, d2, h) - k * h * (1.0 - h);
            } else {
                h = (d2 < d1) ? 1.0 : 0.0;   // 系统 else 分支 d = min(d1,d2) 的等价权重
                dPair = min(d1, d2);
            }
            // smooth-min 梯度性质：∇smin = (1-h)·∇d1 + h·∇d2（全定义域连续，含 clamp 边界）
            float2 gPair = (1.0 - h) * gBody + h * gBall;
            float2 rPair = (1.0 - h) * rBody + h * rBall;

            // 系统 PART_2 的 d_value 初始化语义：**原始（未位移）伴球**也并进 union
            float2 q0 = w - ballC;
            float dRaw = mbShapeSDF(q0, ballH);
            if (dRaw < dPair) {
                o.d = dRaw;
                o.g = gradSdRoundedRect(q0, ballH, ballGradR);
                o.r = gradRefract(q0, ballH, ballGradR);
            } else {
                o.d = dPair;
                o.g = gPair;
                o.r = rPair;
            }
            return o;
        }
        // @LG-MB-END

        // 圆形边缘映射：x∈[0,1] → [0,1]，先快后缓（折射位移包络）
        float circleMap(float x) {
            return 1.0 - sqrt(1.0 - x * x);
        }

        // ---------- 高斯模糊（5x5 可分离；BLUR_RANGE=2 → 25 次采样） ----------
        float gauss(float x, float sigma) {
            return exp(-0.5 * (x / sigma) * (x / sigma));
        }

        float4 gaussianBlur(float2 coord, float spread) {
            // 采样 uSourceLowRes（1/4 或 1/16 降采样源，档位由 hook 侧按 σ_viewport 选）：其 BitmapShader
            // 矩阵已把 viewport 折算到低分辨率 srcRect，tapStep 在低分辨率图内等效缩小，配合双线性插值——
            // 消除全分辨率欠采样（图标细节周期 2~10px << 原 tapStep 14px）产生的网格/莫尔纹伪影；
            // 低分辨率图模糊计算量也远小于全分辨率。最远 tap 在 ±spread(=uBlurRadius) 视口 px。
            // [doc/spec/68 模糊质感修复] 5×5 → **7×7**（σ_idx 1.5 → 2.25；tapStep spread/2 → spread/3）：
            // 原 5×5 在 σ_idx=1.5 时只覆盖 ±2 tap = ±1.33σ，**核边缘权重仍有 0.41**（远未衰减到 0）
            // → 实际接近「方框模糊」而非高斯（观感：平、脏、不像系统那层柔和的雾）；
            // 且 tapStep = spread/2（模糊 32px 时 = 16 视口 px）过疏 → 网格/点阵伪影。
            // 7×7 + tapStep=spread/3：**覆盖范围不变**（±3 × spread/3 = ±spread）但**采样密度提高 50%**；
            // σ_phys 保持 2.25 × spread/3 = 0.75 × spread 不变 → 模糊强度不变、采样更密、核更接近高斯。
            // 代价：采样 25 → 49 tap（模糊只作用于折射环带，非全屏）。
            // 两次串联同参数高斯 = 单次 σ×√2（方差相加）：tapStep 放大 √2 同步加宽物理 σ 与
            // 采样窗口，核形状 σ_idx=2.25 不变 → 一次 49 tap 得到两次模糊的磨砂强度
            float tapStep = spread * 1.41421356 / 3.0;
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

        // ================= [doc/spec/112] 通知卡倾斜：屏幕坐标 → 平面（采样）坐标 逆映射 =================
        // 模型：卡片平面绕过 [c] 的水平轴旋转 θ（θ>0 = 顶边靠近观察者、底边远去），透视距离 D；
        // 正变换 screenY = v·cosθ·D/(D + v·sinθ)、screenX = u·D/(D + v·sinθ)，此处解其逆：
        //   v = Y·D / (D·cosθ − Y·sinθ)、u = X·(D + v·sinθ)/D   （d = p − c，X=d.x, Y=d.y）
        // θ≤6° 时分母 ≥ D·cos6° − |d.y|max·sin6° 恒 >0（另有 <1.0 夹紧兜底）。
        // w=0（默认）或 |sinθ|<1e-6（强度 0 / 动画结束）→ 原样返回 = 逐像素零变化。
        float2 tiltCoord(float2 p, float2 c) {
            if (uTilt.w < 0.5) return p;
            float sn = uTilt.x;
            if (abs(sn) < 0.000001) return p;
            float cs = uTilt.y;
            float D = max(uTilt.z, 1.0);
            float2 d = p - c;
            float denom = D * cs - d.y * sn;
            if (denom < 1.0) denom = 1.0;
            float v = d.y * D / denom;
            float u = d.x * (D + v * sn) / D;
            return float2(c.x + u, c.y + v);
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
            // [doc/spec/100] metaball 元素：视口保持**全尺寸**（±69px 桥区就是画布，不许再收成「真实
            // 卡片」），本体改用 uBodyRect（系统主球矩形）。非 metaball 元素 uMetaballEnable=0 →
            // bodyCenter=halfSize=viewport/2，与改动前**逐位一致**（回归基准）。
            float2 halfSize = uViewport * 0.5;
            float2 bodyCenter = halfSize;
            // @LG-MB-START （本体矩形改写仅 metaball 需要，BASE 剥离 → 恒 viewport/2）
            if (uMetaballEnable > 0.5) {
                bodyCenter = uBodyRect.xy;
                halfSize = uBodyRect.zw;
            }
            // @LG-MB-END
            // [doc/spec/112 通知卡倾斜] 一次性把 coord 变换到倾斜坐标系：作用于下方 SDF（centeredCoord）
            // 与背景采样（coord − uMaskOffset）两处，二者同变换才不撕裂（spec/94 §三 路线 B）。
            // 顺序 = 先 tilt 后 maskOffset/parallax（spec/94 §五.2）：pivot 取玻璃中心的 raw coord 位置
            // （bodyCenter − uParallax + uMaskOffset），恒等时 coord 原样 = 逐像素零变化。
            if (uTilt.w > 0.5) {
                coord = tiltCoord(coord, bodyCenter - uParallax + uMaskOffset);
            }
            float2 centeredCoord = (coord + uParallax - uMaskOffset) - bodyCenter;
            float radius = uCornerRadius;
            // [spec/18 圆角过渡区深挖修正] 本体角 CONIC —— 逻辑已抽到 mbShapeSDF（cc=1.5×radius、
            // 圆弧↔CONIC 平滑过渡 uCornerConicBlend、半径≥短边一半退化为圆/胶囊），此处只算方向场半径。
            // 原实证：cc=1.5×radius 必然 > halfSize → 贝塞尔角区判定 any(|p|<=b-cc) 恒 false →
            // 整个元素被当角区送进按 [0,cc]² 定义的贝塞尔 → sdRaw 失真 → 高光带/圆角裁剪全部错位。
            float sdRaw = mbShapeSDF(centeredCoord, halfSize);
            // 本体原始 SDF（union 前快照）：掩码段用它分离「本体」与「桥区/伴球」——
            // 桥区豁免 = 融合场 ∧ 本体之外，见掩码段 max(sdRaw, -sdBody)。
            float sdBody = sdRaw;
            float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));

            // [doc/spec/100 方案2] metaball 并集：本体 ↔ ≤3 伴球 pairwise smooth-min 融合。
            // uMetaballEnable == 0（默认）→ 整段不执行 → sdRaw / sdfGrad / nBlend 与改动前逐位一致。
            float2 mbGrad = float2(0.0, 1.0);
            float2 mbRefr = float2(0.0, 1.0);
            float mbOn = 0.0;
            // @LG-MB-START （union 调用段仅 metaball 需要，BASE 剥离；mbGrad/mbRefr/mbOn 声明保留在外）
            if (uMetaballEnable > 0.5) {
                float2 w = coord + uParallax - uMaskOffset;
                float mbBs = max(uMetaballParam.x, 0.0);
                float mbStrength = max(uMetaballParam.y, 0.0001);
                float mbMode = uMetaballParam.z;
                float dAcc = sdRaw;
                float2 gAcc = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);
                float2 rAcc = gradRefract(centeredCoord, halfSize, gradRadius);
                MbPair pr;
                pr = mbFuse(w, uBodyRect.xy, uBodyRect.zw, uBall1.xy, uBall1.zw,
                            uCornerRadius, mbBs, mbBs, mbStrength, mbMode, gradRadius);
                if (pr.d < dAcc) { dAcc = pr.d; gAcc = pr.g; rAcc = pr.r; }
                pr = mbFuse(w, uBodyRect.xy, uBodyRect.zw, uBall2.xy, uBall2.zw,
                            uCornerRadius, mbBs, mbBs, mbStrength, mbMode, gradRadius);
                if (pr.d < dAcc) { dAcc = pr.d; gAcc = pr.g; rAcc = pr.r; }
                pr = mbFuse(w, uBodyRect.xy, uBodyRect.zw, uBall3.xy, uBall3.zw,
                            uCornerRadius, mbBs, mbBs, mbStrength, mbMode, gradRadius);
                if (pr.d < dAcc) { dAcc = pr.d; gAcc = pr.g; rAcc = pr.r; }
                sdRaw = dAcc;
                mbGrad = gAcc;
                mbRefr = rAcc;
                mbOn = 1.0;
            }
            // @LG-MB-END
            float sd = min(sdRaw, 0.0);   // Kyant：外部像素 clamp 到 0（circleMap 定义域 [0,1] 防 NaN）
            // 纯 SDF 梯度（高光专用；Kyant 高光不混 depth 径向分量）
            float2 sdfGrad = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);
            if (mbOn > 0.5) {
                // [doc/spec/100] 融合场梯度 = 各分量梯度的 h 凸组合（mbFuse 内已合成），全定义域连续
                // → 高光 band 沿融合轮廓连续、**不在桥上断裂**；幅值≈0（两侧法线对消的中轴）时保留
                // 本体方向，避免 normalize 小量产生任意方向。
                float mbLen = length(mbGrad);
                if (mbLen > 0.0001) { sdfGrad = mbGrad / mbLen; }
            }

            float4 glass = float4(0.0);

            if (-sdRaw >= uRefractionHeight) {
                // 2a. 内部：清晰透镜（Kyant 折射 shader 早退等价：content.eval(coord) 原样输出）。
                //     uBlurRadius>0 时走可选轻模糊（采样 1/4 降采样图，5x5 高斯）。
                if (srcOutOfSource(srcCoord(coord - uMaskOffset, uSourceRect))) {
                    // 主采样坐标越出快照位图：不画玻璃透出底层，避免 CLAMP 钳到快照边缘行色
                    return half4(0.0);
                }
                if (uBlurRadius > 0.01) {
                    glass = gaussianBlur(coord - uMaskOffset, uBlurRadius);
                } else {
                    glass = float4(uSource.eval(srcCoord(coord - uMaskOffset, uSourceRect)));
                }
            } else {
                // 2b. 折射环带：边缘位移最大，向内趋 0（包络 circleMap 与 Kyant 语义不变）
                float d = circleMap(1.0 - -sd / max(uRefractionHeight, 1.0)) * uRefractionAmount;
                // [2026-10-10 spec/97] 方向场改走 gradRefract（返回值幅值 = 方向可信度），
                // 替代旧的 `sdfGrad + uDepth×球面径向` —— 旧场在宽元素内部退化成「从中心径向」，
                // 与局部边界法线差 70~80° → 内容被横向拽向中心 → 玻璃内部奇怪扭曲图案。
                float2 nBlend = gradRefract(centeredCoord, halfSize, gradRadius);
                if (mbOn > 0.5) {
                    // [doc/spec/100] 折射方向场同样换成融合场（mbFuse 内按同一 h 凸组合），
                    // 桥区因此进入折射环带分支并获得方向；腰线处两侧法线相消 → 幅值（方向可信度）
                    // 趋 0 → 位移同步趋 0，是平滑过渡而非撕裂（与 spec/97 的中轴处理同语义）。
                    nBlend = mbRefr;
                }
                // uDepth 穹顶分量：球面径向 → **按宽高比校正的椭圆梯度** ∇(x²/hx² + y²/hy²)。
                // 旧球面径向在宽元素上边随 |x| 转到 79°（1312×248、x=500 处），与法线叠加后
                // 把合成方向拉偏 31° → 横向拉扯；椭圆穹顶同点只偏 5°、全域 ≤12°。
                // 正方元素 hx==hy 时椭圆梯度 ≡ 球面径向 → **逐像素零回归**（QS 磁贴等不变）。
                float2 dome = centeredCoord / max(halfSize * halfSize, float2(0.0001));
                float2 domeN = float2(0.0);
                // 判零必须对 centeredCoord 做，**不能**对除过 halfSize² 的 dome 做：
                // dome 幅值 ~ |p|/430336，离中心 32px 处就只有 ~1e-4，用 1e-4 当阈值会把
                // 中心一大片的穹顶项静默丢掉（仿真交叉验证实测：refrVec 差 3.1e-4px）。
                if (length(centeredCoord) > 0.0001) {
                    domeN = dome / length(dome);
                }
                float2 gradCombined = nBlend + uDepth * domeN;
                float2 n = float2(0.0);
                if (length(gradCombined) > 0.0001) {
                    n = gradCombined / length(gradCombined);
                }
                // 采样点向元素【内侧】位移（负方向）= Kyant Lens.kt refractionAmount 取负的语义 → 透镜弯折。
                // 末尾再乘 |nBlend|（方向可信度）：内部多墙面法线相消处位移同步衰减 →
                // medial axis 上的 180° 方向翻转被幅值归零掩盖，不产生撕裂线。
                float2 refrVec = -d * n * length(nBlend);
                // 位移幅值按元素半短边封顶：配置值超过半短边时（小元素）采样点最多到中心、不跨到对面
                float dispCap = min(halfSize.x, halfSize.y);
                float dispMag = length(refrVec);
                if (dispMag > dispCap && dispMag > 0.0001) {
                    refrVec *= dispCap / dispMag;
                }
                float2 refractedCoord = (coord - uMaskOffset) + refrVec;
                if (srcOutOfSource(srcCoord(refractedCoord, uSourceRect))) {
                    // 主采样坐标越出快照位图：不画玻璃透出底层，避免 CLAMP 钳到快照边缘行色
                    return half4(0.0);
                }
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

            // 内侧光【放底层】：加在遮罩压暗（2.5）之前，压暗同时作用于背景与光（遮罩拉高压得住）；
            // shapeAlpha 限形状内（形状外=0，发光不外溢）；
            // uHighlightInnerAtt = 内侧光由外向内的衰减强度：越大越快向内衰减、越贴边，0 = glowT² 现状；
            // hw/bandCenter/dIn/innerT/innerAtt 与定向高光共用（band 中心保底内移 2px 防细带被羽化吃掉）
            float hw = max(uHighlightWidth, 1.0);
            float bandCenter = max(hw * 0.5, 2.0);
            float dIn = -sdRaw;
            float innerT = clamp((dIn - bandCenter) / max(hw * 0.5, 1.0), 0.0, 1.0);
            float innerAtt = 1.0 - uHighlightInnerAtt * innerT;
            float shapeAlpha = smoothstep(0.0, -2.0, sdRaw);
            float glowT = 1.0 - clamp(max(dIn, 0.0) / max(uRefractionHeight, 1.0), 0.0, 1.0);
            glass.rgb += uInnerGlow * pow(glowT, 2.0 + uHighlightInnerAtt * 3.0) * innerAtt * shapeAlpha;

            // 2.5 遮罩压暗 [2026-08-14 恢复 spec/41 语义]：把玻璃采到的背景色向 uMaskColor 压暗
            //     （遮罩→底层背景，玻璃区域与卡片间 scrim 黑 tint 展开态同为 `壁纸×(1-maskAlpha)` 统一）。
            //     uMaskAlpha=0 时 mix 系数 0 → 零开销跳过。历史：spec/48 曾整体移除（改为系统 scrim 黑 tint
            //     单一来源），快照叠黑方案验证无效后用户决定恢复 shader 内压暗（uMaskAlpha/uMaskColor 直接生效）。
            glass.rgb = mix(glass.rgb, uMaskColor.rgb, uMaskAlpha * uMaskColor.a);

            // 3. vibrancy：饱和度提升（Kyant colorControls saturation=1.5 的矩阵等价：mix(luma, rgb, s)）
            float luma = dot(glass.rgb, float3(0.213, 0.715, 0.072));
            glass.rgb = mix(float3(luma), glass.rgb, uVibrancy);

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
                float maskMinHalf = min(maskHalf.x, maskHalf.y);
                if (cc >= maskMinHalf) {
                    // cc 超短边一半时 bezier squircle 数学退化（角区判定失效向内啃）——与本体 mbShapeSDF 同款守卫，退化为短边封顶胶囊
                    sdMask = sdRoundedRect(coord - maskCenter, maskHalf, maskMinHalf);
                } else {
                    float blend = clamp(uCornerConicBlend, 0.0, 1.0);
                    float sdArc = sdRoundedRect(coord - maskCenter, maskHalf, uMaskCornerRadius);
                    if (blend <= 0.001) {
                        sdMask = sdArc;
                    } else {
                        float sdConic = sdBezierDistance(coord - maskCenter, maskHalf, cc, uMaskWeight);
                        sdMask = mix(sdArc, sdConic, blend);
                    }
                }
            } else {
                sdMask = sdRoundedRect(coord - maskCenter, maskHalf, 0.0);
            }
            // [doc/spec/141] 桥区豁免：融合场（本体∪伴球∪桥）中本体之外的部分不被掩码裁剪——黏连桥画进扩充区。
            // max(sdRaw,-sdBody)≤0 ⟺ 形内 ∧ 本体外（桥区）；非 metaball 恒不进本分支；
            // metaball 无有效伴球时 sdRaw≡sdBody → |sdBody|≥0 → min 不变（零回归）。
            if (uMetaballEnable > 0.5) {
                sdMask = min(sdMask, max(sdRaw, -sdBody));
            }
            glass *= smoothstep(0.0, -2.0, sdMask);

            // [2026-08-13 修复] 圆角外裁剪：对齐系统 `outputCol *= shape`（shape=smoothstep(0,-2,sdf)）——
            // 圆角 SDF 之外（sdRaw>0，正方形角区域）alpha 归 0，消除"圆角外多余直角玻璃边"。
            // 这是所有玻璃元素的通用裁剪（不只侧滑）；maskRect 掩码（侧滑专用）在上一行已乘，
            // 此处再乘 shapeAlpha（两者 alpha 相乘），对侧滑按钮的 MetaBall 扩张区裁切职责不变。
            glass *= shapeAlpha;

            // 4. 定向边缘高光：加在圆角裁剪【之后】，band 中心偏移到圆角内侧 uHighlightWidth×0.5 处——
            //    [2026-08-23 修复] 原实现带中心在边界（abs(sdRaw)），而 shapeAlpha 在边界（sdRaw=0）=0，
            //    圆角边缘高光被裁掉（外半全裁 + 内半 0~-2 羽化削弱）。偏移内侧后高光带中心落在 alpha=1
            //    区域，完整可见、不超圆角；直边元素同样内侧亮边（不跨边界）。
            float band = 1.0 - smoothstep(0.0, hw, abs(dIn - bandCenter));
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
            glass.rgb += float3(hl * band * innerAtt * uHighlight);

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

    /** [doc/spec/112] 通知卡倾斜透视距离 px（uTilt.z）。越小透视越强；400 ≈ 常见卡片半高的 2 倍量级，
     *  6° 时顶/底边缘缩放差实测顶 +3.8% / 底 −4.5%（上下差约 8.7%，观感安全档）。 */
    private const val TILT_PERSPECTIVE_PX = 400f

    /** 液态玻璃材质参数（uniform 镜像，见 AGSL_SOURCE 各 uniform 注释）。 */
    data class Params(
        val viewportWidth: Float,
        val viewportHeight: Float,
        val cornerRadius: Float = 28f,   // SDF 圆角半径 px（uCornerRadius；几何/折射/高光用）
        val cornerIsConic: Boolean = false,  // 本体角 CONIC 开关（true=三次贝塞尔 + shader 内 1.5×半径；false=RBox 圆弧）
        val cornerWeight: Float = DEFAULT_MASK_WEIGHT, // 本体 CONIC 角权重（系统 CornerParamsKt (8·w)/(3·w+3) 变换后值；默认 1.3968）
        val blurRadius: Float = 0f,      // 内部可选轻模糊 px（uBlurRadius；0=清晰透镜=iOS 26 观感；>0 仅作用内部，不糊折射环带）
        /** 折射区高度 px（uRefractionHeight，自边缘向内）。[doc/spec/95] hook 端与预览端恒按
         *  「元素短边 × 短边比例」算（Prefs.KEY_REFRACTION_HEIGHT_RATIO，默认 0.5 = 短边一半）；
         *  48f 仅作漏传兜底，不参与实际渲染。 */
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
        /** [2026-10-13] 内侧高光衰减 0~1：band 内半向玻璃深处再衰减，0=现状、1=只剩贴边亮线。 */
        val highlightInnerAtt: Float = 0f,
        val vibrancy: Float = 1.5f,      // 鲜艳度/饱和度倍数（Kyant vibrancy=1.5；1=不变）
        val lightAngleDegrees: Float = 45f,
        val parallaxX: Float = 0f,
        val parallaxY: Float = 0f,
        /** 内侧光 0~1（uInnerGlow；0=关）。放末尾带默认值，既有位置实参不受影响。 */
        val innerGlow: Float = 0f,
    )

    /**
     * [doc/spec/100 方案2] 单颗球（本体主球或伴球）的 uniform 几何。
     * 中心/半尺寸一律 **viewport 局部坐标**（与 AGSL `coord` 同空间）。
     */
    data class Ball(
        val cx: Float,
        val cy: Float,
        val halfW: Float,
        val halfH: Float,
    )

    /**
     * [doc/spec/100 方案2] 系统 metaball 黏连桥的 uniform 输入。
     *
     * **全部字段带默认值**：`body == null`（默认）时 [setUniforms] 把 `uMetaballEnable` 置 0 →
     * AGSL 融合段整段不执行 → 非 metaball 元素（普通通知卡 / QS / 文件夹）**逐像素零变化**。
     * 数据来源 = 系统 `MetaBallBlurDrawable` 的 `metaBallParams`（`syncToShader()` 已同步好的那份），
     * 由 BlurDrawHook 反射读出，字段名全部为字节码真名（jadx 名不可信，见记忆 reference_jadx-visibility-artifact）。
     *
     * @param body 主球（= 本体）矩形；null = 不启用
     * @param balls 伴球（已剔除主球），至多 3 颗；空列表 = 该元素虽是 metaball 但当前无邻球
     * @param blendShape 系统 `MetaBall.blendShape`（= `setBlendRange` 的值）：驱动桥的作用域
     *                   （range_blend = 2×此值）与 shape-change 外扩幅度
     * @param blendStrength 系统 shader 常量 `BLEND_STRENGTH`（= 15.0）
     * @param mode 系统 `blendMode`：0=横向扩张（左右）、1=纵向扩张（上下）
     */
    data class MetaBalls(
        val body: Ball? = null,
        val balls: List<Ball> = emptyList(),
        val blendShape: Float = 0f,
        val blendStrength: Float = 15f,
        val mode: Float = 0f,
    )

    @Volatile
    private var cachedShader: RuntimeShader? = null

    /** 编译一个新的液态玻璃 RuntimeShader（每次调用都会重新编译 AGSL，开销大；常规绘制请用 [getShader]）。 */
    /**
     * [doc/spec/119 拆源] BASE 源 = AGSL_SOURCE 剥离全部 @LG-MB-START/END 段
     * （mbGain/MbPair/mbFuse/union 调用/本体矩形改写 ≈31% 纯代码）。
     * mbShapeSDF（主渲染无条件使用）与 mbGrad/mbOn 声明保留在标记外，BASE 语义完整。
     * 小元素（QS/音量条/非 MetaBall drawable）走此源，编译量立减；通知卡等 metaball 元素走 FULL。
     */
    val AGSL_SOURCE_BASE: String by lazy { stripMetaballSections(AGSL_SOURCE) }

    /** 剥离所有 @LG-MB-START … @LG-MB-END 区段（含标记行）。配对标记缺失 → 返回原文（安全兜底）。 */
    private fun stripMetaballSections(src: String): String {
        val sb = StringBuilder(src.length)
        var i = 0
        var stripping = false
        while (i < src.length) {
            if (!stripping && src.startsWith(MB_START_MARK, i)) {
                // 跳过标记行到行尾
                while (i < src.length && src[i] != '\n') i++
                if (i < src.length) i++
                stripping = true
                continue
            }
            if (stripping && src.startsWith(MB_END_MARK, i)) {
                while (i < src.length && src[i] != '\n') i++
                if (i < src.length) i++
                stripping = false
                continue
            }
            if (!stripping) sb.append(src[i])
            i++
        }
        return if (stripping) src else sb.toString() // 未闭合 → 原文兜底
    }

    private const val MB_START_MARK = "// @LG-MB-START"
    private const val MB_END_MARK = "// @LG-MB-END"

    /**
     * @param metaball true = FULL 源（通知卡/侧滑等 MetaBall 元素，需黏连桥）；
     *                 false = BASE 源（小元素，编译量 -31%）。默认 BASE。
     */
    fun create(metaball: Boolean = false): RuntimeShader =
        RuntimeShader(if (metaball) AGSL_SOURCE else AGSL_SOURCE_BASE)

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
     *               null 时保留上一次的 uSource 输入（uSourceRect 等 uniform 仍更新——srcRect 变化无需
     *               重建/重绑 shader，仅重设 uniform 即可跟手）；首次为 null 则 shader 无输入，画面为黑
     *               ——调用方须先喂帧。
     * @param lowResSource uSourceLowRes 输入：降采样原图的 BitmapShader（见
     *               [createLowResSourceBitmapShader]）。**非 null 时无条件重绑 uSourceLowRes**（独立于
     *               [source] 是否为 null：miss/档位重绑帧源位图未变、source 传 null，低分辨率绑定仍须
     *               真下发，否则旧 ÷N 绑定配新 rect 出晶格/放大）；null 且 [source] 非 null 时回退使用
     *               [source]（等效旧的全分辨率模糊，渲染不中断）；双 null 保留上次输入。
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
     * @param metaBalls [doc/spec/100 方案2] metaball 黏连桥输入（本体 + ≤3 伴球 + blendStrength）。
     *               null（默认）→ `uMetaballEnable=0`，AGSL 融合段整段不执行 = 非 metaball 元素
     *               逐像素零变化。启用时调用方须把 viewport 设为**全 bounds 尺寸**、maskOffset 置 0、
     *               maskRect 置 null（否则 ±69px 桥区仍会被裁掉，见 spec/100 §5）。
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
        /** [doc/spec/100 方案2] metaball 黏连桥输入；null = 不启用（默认）。**只在末尾追加，
         *  上述既有参数名一个都没改**（LauncherHook / BlurDrawHook 既有调用方按名传参，不受影响）。 */
        metaBalls: MetaBalls? = null,
        /** [doc/spec/112] 通知卡倾斜角 θ（弧度；0 = 关闭 = uTilt 置 0 恒等）。已含强度×动画进度，
         *  调用方按帧算好传入；同样只在末尾追加，既有调用方零改动（默认 0f = 逐像素零变化）。 */
        tiltRadians: Float = 0f,
        /** uSource 快照位图像素尺寸（越界判定上界用）；默认 0f = 未喂 → shader 只判负方向。 */
        sourceSizeX: Float = 0f,
        sourceSizeY: Float = 0f,
    ) {
        if (source != null) {
            shader.setInputShader("uSource", source)
        }
        // uSourceLowRes 重绑意图独立于 uSource：lowResSource 非 null 即须真下发（miss/档位重绑帧
        // 源位图未变、source 传 null，若整体挂 source 门则旧 ÷N 绑定配新 rect = 晶格/放大）；
        // lowResSource 为 null 时回退 source；双 null 保留上次输入（滑动跟手热路径不受影响）
        if (lowResSource != null) {
            shader.setInputShader("uSourceLowRes", lowResSource)
        } else if (source != null) {
            shader.setInputShader("uSourceLowRes", source)
        }
        // uSourceRect/uSourceLowResRect 只影响 AGSL 侧 srcCoord() 坐标换算（shader.eval 忽略 localMatrix），
        // 与 BitmapShader 实例无关（createSourceBitmapShader 是纯单位矩阵 CLAMP，无 localMatrix）。因此
        // source==null（复用上次输入、仅 srcRect 变化）时也必须更新——滑动跟手只需重设 uniform，
        // 无需重建/重绑 shader，避免滑动热路径每帧 createSourceBitmapShader + setInputShader 的 CPU 峰值。
        shader.setFloatUniform("uSourceRect", sourceRect.left, sourceRect.top, sourceRect.width(), sourceRect.height())
        shader.setFloatUniform("uSourceLowResRect", lowResSourceRect.left, lowResSourceRect.top, lowResSourceRect.width(), lowResSourceRect.height())
        shader.setFloatUniform("uViewport", max(params.viewportWidth, 1f), max(params.viewportHeight, 1f))
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
        shader.setFloatUniform("uHighlightInnerAtt", params.highlightInnerAtt)
        shader.setFloatUniform("uInnerGlow", params.innerGlow)
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
        // [doc/spec/100 方案2] metaball 黏连桥：全量路径**无条件**下发这 6 个 uniform。
        // shader 实例按 drawable id 缓存复用 —— 若只在启用时下发，元素从 metaball 退回普通时会
        // 残留上一帧的 uMetaballEnable=1 → 形状错；无条件下发则关闭路径恒 0 → 非 metaball 零变化。
        val mb = metaBalls
        val mbBody = mb?.body
        if (mbBody != null) {
            shader.setFloatUniform("uMetaballEnable", 1f)
            shader.setFloatUniform("uBodyRect", mbBody.cx, mbBody.cy, mbBody.halfW, mbBody.halfH)
            val b1 = mb.balls.getOrElse(0) { Ball(0f, 0f, 0f, 0f) }
            val b2 = mb.balls.getOrElse(1) { Ball(0f, 0f, 0f, 0f) }
            val b3 = mb.balls.getOrElse(2) { Ball(0f, 0f, 0f, 0f) }
            shader.setFloatUniform("uBall1", b1.cx, b1.cy, b1.halfW, b1.halfH)
            shader.setFloatUniform("uBall2", b2.cx, b2.cy, b2.halfW, b2.halfH)
            shader.setFloatUniform("uBall3", b3.cx, b3.cy, b3.halfW, b3.halfH)
            shader.setFloatUniform("uMetaballParam", mb.blendShape, mb.blendStrength, mb.mode, 0f)
        } else {
            shader.setFloatUniform("uMetaballEnable", 0f)
            shader.setFloatUniform("uBodyRect", 0f, 0f, 0f, 0f)
            shader.setFloatUniform("uBall1", 0f, 0f, 0f, 0f)
            shader.setFloatUniform("uBall2", 0f, 0f, 0f, 0f)
            shader.setFloatUniform("uBall3", 0f, 0f, 0f, 0f)
            shader.setFloatUniform("uMetaballParam", 0f, 0f, 0f, 0f)
        }
        // [doc/spec/112 通知卡倾斜] 无条件下发（与 uMetaball* 同款「防残留」契约）：
        // θ=0 → uTilt=(0,0,0,0) → AGSL tiltCoord 恒等 = 逐像素零变化；元素退出动画后靠这里把
        // 上一帧的 sinθ 清掉（applied.tilt 变化检测同步会走全量路径，见 BlurDrawHook.applyComputedGeometry）。
        if (tiltRadians != 0f) {
            shader.setFloatUniform("uTilt", sin(tiltRadians), cos(tiltRadians), TILT_PERSPECTIVE_PX, 1f)
        } else {
            shader.setFloatUniform("uTilt", 0f, 0f, 0f, 0f)
        }
        // uSourceSize 无条件下发（与 uMetaball* 同款「防残留」契约）：(0,0)=只判负方向
        shader.setFloatUniform("uSourceSize", sourceSizeX, sourceSizeY)
    }

    /**
     * [doc/spec/24 任务 A2 2026-08-13] 轻量 uniform 更新：仅重设 uSourceRect / uSourceLowResRect。
     *
     * 滑动热路径专用——srcRect 每帧变（元素移动 → AGSL 采样坐标实时折算），但源位图 / viewport /
     * 掩码 / 材质 uniform（uVibrancy/uHighlight 等）均与 srcRect 无关，无需全量重设。
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
     * 每帧变，但材质 uniform（uVibrancy/uHighlight/uRefraction* 等）与 viewport/srcRect 无关，
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
        BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            // RuntimeShader 子 shader 默认最近邻采样，位图被放大时出现方块马赛克
            if (Build.VERSION.SDK_INT >= 31) {
                setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
            }
        }

    /**
     * 构造 uSourceLowRes 用的 BitmapShader：1/4 降采样原图 + 单位矩阵（不做 setRectToRect 映射）。
     * 坐标映射与 [createSourceBitmapShader] 同理已移到 AGSL 侧 srcCoord(coord, uSourceLowResRect)，
     * 低分辨率 srcRect（全分辨率 srcRect ÷ scale）由 [setUniforms] 的 lowResSourceRect 传入。
     *
     * @param lowResBitmap 1/4 降采样原图（原图 `Bitmap.createScaledBitmap(w/4, h/4, true)`）。
     */
    fun createLowResSourceBitmapShader(lowResBitmap: Bitmap): BitmapShader =
        createSourceBitmapShader(lowResBitmap)
}
