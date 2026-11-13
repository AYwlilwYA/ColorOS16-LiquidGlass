package com.coloros16.liquidglass.hook

/** Denser change detection with bulk row reads: fewer JNI crossings than getPixel on a 24x24 grid. */
object SnapshotFingerprint {
    fun hash(width:Int,height:Int,readRow:(Int,IntArray)->Unit):Int {
        require(width>0 && height>0)
        val row=IntArray(width)
        val dx=(width/96).coerceAtLeast(1);val dy=(height/96).coerceAtLeast(1)
        var hash=17;var y=0
        while(y<height) {
            readRow(y,row)
            var x=0
            while(x<width) { hash=hash*31+row[x];x+=dx }
            y+=dy
        }
        return hash
    }
}
