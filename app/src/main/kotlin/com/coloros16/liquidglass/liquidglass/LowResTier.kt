package com.coloros16.liquidglass.liquidglass

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RectF
import com.coloros16.liquidglass.log.Lg
import java.util.concurrent.ConcurrentHashMap

/**
 * σ 分档降采样源四件套（SystemUI / Launcher 两 hook 各持独立进程实例，零状态串扰）：
 * [tierOf] σ→档位、[prebuild] 发布链逐级预构建、[tierShaderOf] 档位 shader、[divRectOf] 轻量折算。
 * 缓存按 source identityHashCode 分立多槽（区域/整屏两条发布链互不挤兑）。
 */
object LowResTier {

    private const val TAG = "LiquidGlass"

    /** prebuild 预构建日志门控（与 hook 进程「运行日志」开关同步）。 */
    @Volatile
    var logEnabled: Boolean = false

    /** 档位缓存：source identityHashCode → 降采样位图（上限 4 槽，超限整表清，重发布再填）。 */
    private val tier4Cache = ConcurrentHashMap<Int, Bitmap>()
    private val tier8Cache = ConcurrentHashMap<Int, Bitmap>()
    private val tier16Cache = ConcurrentHashMap<Int, Bitmap>()

    private const val MAX_SLOTS = 4

    /** 档位降采样 shader + 折算后的低分辨率 srcRect。 */
    data class Result(
        val shader: BitmapShader,
        val lowResSrcRect: RectF,
    )

    /**
     * σ_viewport（=0.807×blurRadius，视口 px）三档降采样源选择（texel = 2×tier 视口 px，抓屏 0.5 scale）。
     * 金发姑娘带：texel ∈ [0.83σ, 1.33σ] 且 tap 间距 ≤0.7 纹理 px——texel 偏细则核在纹理空间过窄
     * （亚 texel 文字笔画残留小点），偏粗则 tap 间距 ≥1（晶格点阵）。
     * /4 texel=8vp：σ<10；/8 texel=16vp：10≤σ<20；/16 texel=32vp：σ≥20。
     */
    fun tierOf(sigmaViewport: Float): Int = when {
        sigmaViewport < 10f -> 4
        sigmaViewport < 20f -> 8
        else -> 16
    }

    /** 按档位取预构建缓存（源实例不匹配或未预构建 → null，调用方回退现状源）。 */
    fun tierBitmapOf(source: Bitmap, tier: Int): Bitmap? {
        val id = System.identityHashCode(source)
        return when (tier) {
            4 -> tier4Cache[id]
            8 -> tier8Cache[id]
            16 -> tier16Cache[id]
            else -> null
        }
    }

    /** 档位 shader：取预构建位图 → BitmapShader + srcRect ÷ 实际缩放比折算（无写死比例）。 */
    fun tierShaderOf(source: Bitmap, srcRect: RectF, tier: Int): Result? {
        val low = tierBitmapOf(source, tier) ?: return null
        return try {
            val scaleX = source.width.toFloat() / low.width
            val scaleY = source.height.toFloat() / low.height
            Result(
                shader = LiquidGlassShader.createLowResSourceBitmapShader(low),
                lowResSrcRect = RectF(
                    srcRect.left / scaleX,
                    srcRect.top / scaleY,
                    srcRect.right / scaleX,
                    srcRect.bottom / scaleY,
                ),
            )
        } catch (t: Throwable) {
            Lg.e(TAG, "render: tierShaderOf failed tier=$tier", t)
            null
        }
    }

    /** 已绑档位时按当前 srcRect 折算低分辨率 rect（轻量 uniform 分支用；缓存 miss → null）。 */
    fun divRectOf(source: Bitmap, srcRect: RectF, tier: Int): RectF? {
        val low = tierBitmapOf(source, tier) ?: return null
        val scaleX = source.width.toFloat() / low.width
        val scaleY = source.height.toFloat() / low.height
        return RectF(srcRect.left / scaleX, srcRect.top / scaleY, srcRect.right / scaleX, srcRect.bottom / scaleY)
    }

    /**
     * 发布链预构建三档降采样源：逐级 1/2 降采样（等效 mipmap 区域平均），
     * step==2 存 /4、step==3 存 /8、step==4 存 /16；中间产物回收，档位产物入缓存不回收
     * （可能仍被已绑 shader 引用）。挂 worker/抓屏线程，渲染线程只取引用零缩放。
     * 同一 source 已预构建过则直接返回（壁纸平移等只换 region 的路径零重算）。
     */
    fun prebuild(source: Bitmap) {
        try {
            val sid = System.identityHashCode(source)
            if (tier4Cache.containsKey(sid) || tier8Cache.containsKey(sid) || tier16Cache.containsKey(sid)) return
            var cur = source
            var step = 0
            var quarter: Bitmap? = null
            var eight: Bitmap? = null
            while (step < 4) {
                val nw = (cur.width / 2).coerceAtLeast(1)
                val nh = (cur.height / 2).coerceAtLeast(1)
                if (nw >= cur.width && nh >= cur.height) break
                val next = Bitmap.createScaledBitmap(cur, nw, nh, true)
                if (cur !== source && cur !== quarter && cur !== eight && next !== cur) cur.recycle()
                cur = next
                step++
                if (step == 2) quarter = cur
                if (step == 3) eight = cur
            }
            put(tier4Cache, sid, quarter)
            put(tier8Cache, sid, eight)
            if (step >= 4) put(tier16Cache, sid, cur)
            if (logEnabled) {
                Lg.i(
                    TAG,
                    "render: lowRes chain prebuilt " +
                        "/4=${quarter?.width}x${quarter?.height} " +
                        "/8=${eight?.width}x${eight?.height} " +
                        "/16=${tier16Cache[sid]?.let { "${it.width}x${it.height}" } ?: "skip"} " +
                        "(src ${source.width}x${source.height})"
                )
            }
        } catch (t: Throwable) {
            Lg.e(TAG, "render: lowRes prebuild failed", t)
        }
    }

    private fun put(cache: ConcurrentHashMap<Int, Bitmap>, sid: Int, bmp: Bitmap?) {
        if (bmp == null) return
        if (cache.size >= MAX_SLOTS && !cache.containsKey(sid)) cache.clear()
        cache[sid] = bmp
    }

    /** 全部档位缓存清空（省电切换等状态复位用）。 */
    fun clear() {
        tier4Cache.clear()
        tier8Cache.clear()
        tier16Cache.clear()
    }
}
