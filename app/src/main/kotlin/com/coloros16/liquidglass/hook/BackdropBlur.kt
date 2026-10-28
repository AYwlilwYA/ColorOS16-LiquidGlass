/* Dense preblur contributed from DreamGlass, GPL-3.0; 2026-10-28. */
package com.coloros16.liquidglass.hook

import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** DreamGlass 2026-10-25: five fractional Gaussian-approximation passes, double accumulation.
 * Production returns premultiplied float channels without an intervening 8-bit conversion. */
object BackdropBlur {
    private const val PASSES = 5

    /** Match the previous sparse kernel's measured sigma (~0.8*spread), including finish scaling. */
    fun materialSigma(radius: Float): Float =
        if (!radius.isFinite() || radius <= 0f) 0f else radius * .80f

    /** Preserve capture density; only cap exceptional images to 1M pixels / 2048px longest edge. */
    fun workingSize(w: Int, h: Int, screenW: Float, screenH: Float, radius: Float): IntArray {
        require(w > 0 && h > 0 && screenW > 0 && screenH > 0)
        val scale = minOf(1.0, 2048.0 / maxOf(w, h), sqrt(1048576.0 / (w.toDouble() * h)))
        return intArrayOf(floor(w * scale).toInt().coerceAtLeast(1), floor(h * scale).toInt().coerceAtLeast(1))
    }

    /** Compatibility/test export only. The Android production path uses applyPremultiplied(). */
    fun apply(pixels: IntArray, width: Int, height: Int, sigma: Float): IntArray {
        require(width > 0 && height > 0 && pixels.size == width * height)
        if (!sigma.isFinite() || sigma <= 0f) return pixels.copyOf()
        val result = applyPremultiplied(pixels,width,height,sigma)
        return IntArray(pixels.size) { i ->
            val j=i*4;val alpha=result[j].coerceIn(0f,255f);val a=alpha.roundToInt()
            fun straight(c:Int)=if(a==0)0 else (result[j+c]*255f/alpha).roundToInt().coerceIn(0,255)
            (a shl 24) or (straight(1) shl 16) or (straight(2) shl 8) or straight(3)
        }
    }

    @JvmOverloads
    fun applyPremultiplied(pixels: IntArray, width: Int, height: Int, sigma: Float, cancelled: () -> Boolean = { false }): FloatArray {
        require(width > 0 && height > 0 && pixels.size == width * height)
        if(cancelled())throw java.util.concurrent.CancellationException()
        var source=FloatArray(pixels.size*4)
        for(i in pixels.indices) {
            val p=pixels[i];val a=(p ushr 24).toFloat();val j=i*4
            source[j]=a;source[j+1]=(p ushr 16 and 255)*a/255f
            source[j+2]=(p ushr 8 and 255)*a/255f;source[j+3]=(p and 255)*a/255f
        }
        if(!sigma.isFinite() || sigma<=0f)return source
        val variance=sigma.coerceAtMost(2048f).toDouble().let { it*it }/PASSES
        val r=floor((sqrt(1.0+12.0*variance)-1.0)/2.0).toInt()
        val count=2*r+1
        val edge=((variance*count-r.toDouble()*(r+1)*count/3.0)/
            (2.0*((r+1).toDouble()*(r+1)-variance))).coerceIn(0.0,1.0)
        var target=FloatArray(source.size)
        repeat(PASSES) {
            if(cancelled())throw java.util.concurrent.CancellationException()
            box(source,target,width,height,r,edge,true)
            val swap=source;source=target;target=swap
            box(source,target,width,height,r,edge,false)
            val swap2=source;source=target;target=swap2
        }
        return source
    }

    private fun box(src:FloatArray,dst:FloatArray,w:Int,h:Int,r:Int,edge:Double,horizontal:Boolean) {
        val length=if(horizontal)w else h;val lines=if(horizontal)h else w
        val weight=2*r+1+2*edge;val sums=DoubleArray(4)
        for(line in 0 until lines) {
            fun index(x:Int)=(if(horizontal)line*w+x.coerceIn(0,length-1) else x.coerceIn(0,length-1)*w+line)*4
            sums.fill(0.0)
            for(x in -r..r) { val j=index(x);for(c in 0..3)sums[c]+=src[j+c] }
            for(x in 0 until length) {
                val j=index(x);val lo=index(x-r-1);val hi=index(x+r+1)
                val remove=index(x-r);val add=index(x+r+1)
                for(c in 0..3) {
                    dst[j+c]=((sums[c]+edge*(src[lo+c]+src[hi+c]))/weight).toFloat()
                    sums[c]+=src[add+c].toDouble()-src[remove+c].toDouble()
                }
            }
        }
    }
}
