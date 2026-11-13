/* DreamGlass contributors. GPL-3.0. Wallpaper input only; never captures a display. */
package com.coloros16.liquidglass.hook

import android.app.WallpaperManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.os.*
import io.github.libxposed.api.XposedInterface
import com.coloros16.liquidglass.diagnostics.RuntimeDiagnostics
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/** Independent from optional clock/member glass. Notifications and QS also need lock wallpaper. */
internal object LockWallpaperBackdrop {
    private val ui=Handler(Looper.getMainLooper())
    private val worker=RuntimeWorkPolicy.executor("DreamGlass-lock-wallpaper")
    private val loading=AtomicBoolean(false)
    @Volatile private var image:Bitmap?=null
    @Volatile private var generation=0
    @Volatile private var retryAt=0L
    private var controller:WeakReference<Any>?=null
    private var copy:java.lang.reflect.Method?=null
    private var nativeImage:WeakReference<Bitmap>?=null
    private var installed=false
    private var invalidate:()->Unit={}
    private var context:Context?=null

    fun install(api:XposedInterface,loader:ClassLoader,onReady:()->Unit) {
        if(installed)return
        installed=true;invalidate=onReady
        runCatching {
            val type=Class.forName("com.oplus.systemui.keyguard.wallpaper.OplusLockScreenWallpaperController",false,loader)
            val method=type.getDeclaredMethod("copyWallpaperBitmap").apply { isAccessible=true };copy=method
            for(member in type.declaredConstructors.toList()+type.declaredMethods.filter {
                it.name in setOf("copyWallpaperBitmap","getWallpaperType","onKeyguardDone","onConfigChanged")
            }) api.hook(member).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                val result=chain.proceed()
                chain.getThisObject()?.let { controller=WeakReference(it) }
                if(member==method)(result as? Bitmap)?.let(::accept)
                result
            }
        }.onFailure { RuntimeDiagnostics.update("lock_wallpaper_route","原生壁纸入口不可用，使用当前用户 FLAG_LOCK 文件") }
    }
    fun changed() {
        generation++;image=null;nativeImage=null;retryAt=0L
    }
    /** Native callbacks retain only a reference. GPU work begins only when a lock surface draws. */
    fun accept(bitmap:Bitmap) {
        if(Looper.myLooper()!=Looper.getMainLooper()) { ui.post { accept(bitmap) };return }
        if(bitmap.isRecycled || bitmap.width<=0 || bitmap.height<=0 || nativeImage?.get()===bitmap)return
        nativeImage=WeakReference(bitmap)
        generation++;image=null;retryAt=0L
        invalidate()
    }
    private fun record(bitmap:Bitmap):RenderNode {
        val node=RenderNode("DreamGlass lock wallpaper source")
        node.setPosition(0,0,bitmap.width,bitmap.height)
        val canvas=node.beginRecording(bitmap.width,bitmap.height)
        try { canvas.drawBitmap(bitmap,0f,0f,Paint(Paint.FILTER_BITMAP_FLAG)) } finally { node.endRecording() }
        return node
    }
    /** Finish on the UI Looper so publication and admission cannot race each other. */
    private fun finish(version:Int,result:Bitmap?) {
        ui.post {
            loading.set(false)
            if(version!=generation) {
                // A native change happened in-flight. Its earlier invalidate saw loading=true;
                // wake the current generation once, without adding any timer or idle work.
                retryAt=0L
                invalidate()
            } else if(result!=null) {
                image=result;retryAt=0L
                RuntimeDiagnostics.update("lock_wallpaper_source","使用锁屏壁纸 GPU 输入；未抓取应用/息屏画面")
                invalidate()
            }
        }.also { accepted -> if(!accepted)loading.set(false) }
    }
    /** Nonblocking draw-thread read. A missing source schedules at most one event-driven load. */
    fun get(ctx:Context):Bitmap? {
        context=ctx.applicationContext
        image?.takeUnless { it.isRecycled }?.let { return it }
        if(SystemClock.uptimeMillis()<retryAt || !loading.compareAndSet(false,true))return null
        retryAt=SystemClock.uptimeMillis()+5_000L
        ui.post {
            try {
                val current=controller?.get()
                if(current!=null) (copy?.invoke(current) as? Bitmap)?.let(::accept)
            } catch(_:Throwable) { }
            val version=generation
            val native=nativeImage?.get()?.takeUnless { it.isRecycled }
            val node=try { native?.let(::record) } catch(_:Throwable) { null }
            try { worker.execute {
                var prepared:Bitmap?=null
                try {
                    val raw=if(native!=null && node!=null) {
                        val scale=minOf(1f,1600f/native.width,1600f/native.height)
                        HardwareBackdropBlur().use { it.render(node,native.width,native.height,
                            (native.width*scale).toInt().coerceAtLeast(1),(native.height*scale).toInt().coerceAtLeast(1),3f) { generation!=version } }
                    } else read(context ?: ctx)
                    if(raw!=null && version==generation) {
                        val result=if(node!=null)raw else {
                            val scale=maxOf(ctx.resources.displayMetrics.widthPixels.toFloat()/raw.width,
                                ctx.resources.displayMetrics.heightPixels.toFloat()/raw.height)
                            HardwareBackdropBlur().use { it.render(raw,raw.width,raw.height,6f/scale) { generation!=version } }
                        }
                        prepared=result
                    }
                } catch(error:Throwable) {
                    RuntimeDiagnostics.update("lock_wallpaper_source","锁屏壁纸加载待重试: "+error.javaClass.simpleName)
                } finally {
                    node?.discardDisplayList()
                    finish(version,prepared)
                }
            } } catch(error:Throwable) {
                node?.discardDisplayList()
                loading.set(false)
                RuntimeDiagnostics.update("lock_wallpaper_source","锁屏壁纸准备待重试: "+error.javaClass.simpleName)
            }
        }.also { accepted -> if(!accepted)loading.set(false) }
        return null
    }
    @android.annotation.SuppressLint("MissingPermission")
    private fun read(ctx:Context):Bitmap? {
        if(ctx.checkSelfPermission("android.permission.READ_WALLPAPER_INTERNAL")!=PackageManager.PERMISSION_GRANTED)return null
        val user=Class.forName("android.app.ActivityManager").getMethod("getCurrentUser").invoke(null) as Int
        val owner=if(user==android.os.Process.myUid()/100000)ctx else Context::class.java
            .getMethod("createContextAsUser",UserHandle::class.java,Integer.TYPE)
            .invoke(ctx,UserHandle.getUserHandleForUid(user*100000),0) as Context
        val wm=WallpaperManager.getInstance(owner)
        // A distinct lock image takes precedence. Only a shared static wallpaper may use SYSTEM.
        val fd=wm.getWallpaperFile(WallpaperManager.FLAG_LOCK)
            ?: if(wm.wallpaperInfo==null)wm.getWallpaperFile(WallpaperManager.FLAG_SYSTEM) else null
        fd ?: return null
        fd.use {
            val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
            fd.dup().use { BitmapFactory.decodeFileDescriptor(it.fileDescriptor,null,bounds) }
            if(bounds.outWidth<=0 || bounds.outHeight<=0)return null
            var sample=1
            while(maxOf(bounds.outWidth,bounds.outHeight)/sample>1600)sample*=2
            return fd.dup().use { BitmapFactory.decodeFileDescriptor(it.fileDescriptor,null,
                BitmapFactory.Options().apply { inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888 }) }
        }
    }
}
