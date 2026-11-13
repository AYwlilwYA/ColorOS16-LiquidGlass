package com.coloros16.liquidglass.hook

/** Screen coverage of a center-cropped lock wallpaper. Preserves aspect, including rotation. */
object LockWallpaperGeometry {
    @JvmStatic fun cover(width:Int,height:Int,screenWidth:Int,screenHeight:Int):FloatArray? {
        if(width<=0 || height<=0 || screenWidth<=0 || screenHeight<=0)return null
        val scale=maxOf(screenWidth.toFloat()/width,screenHeight.toFloat()/height)
        val w=width*scale;val h=height*scale
        val x=(screenWidth-w)*.5f;val y=(screenHeight-h)*.5f
        return floatArrayOf(x,y,x+w,y+h)
    }
}
