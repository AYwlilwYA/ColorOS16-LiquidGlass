package com.coloros16.liquidglass.hook
import java.lang.ref.WeakReference
/** One owner per visible overlay. Old workers cannot tear down a replacement overlay. */
internal class VisibleCaptureSession {
    private var host:WeakReference<Any>?=null
    private var generation=0L
    private var running=false
    @Synchronized fun begin(owner:Any):Long? {
        if(running && host?.get()===owner)return null
        host=WeakReference(owner);running=true
        return ++generation
    }
    @Synchronized fun current(token:Long)=running && token==generation
    @Synchronized fun finish(token:Long):Boolean {
        if(token!=generation)return false
        running=false;host=null;return true
    }
}
