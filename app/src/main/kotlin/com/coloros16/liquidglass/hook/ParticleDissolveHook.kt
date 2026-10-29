/* DreamGlass contributors, 2026-10-29. GPL-3.0, as the surrounding project.
 * GPU snapshot lifecycle follows the Android Developers HardwareRenderer example:
 * https://developer.android.com/guide/topics/renderscript/migrate (Apache-2.0 sample).
 * Particle motion/dissolution below is an independent implementation; no video assets are bundled.
 */
package com.coloros16.liquidglass.hook

import android.animation.ValueAnimator
import android.app.Application
import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.log.Lg
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One-line integration: ParticleDissolveHook.install(api, classLoader).
 * Only explicit SwipeHelper dismissals of notification rows; never unlock, heads-up timeout or QS.
 * No CPU blur, pixel readback, per-particle View, repeating timer or replacement deletion callback.
 */
object ParticleDissolveHook {
    const val KEY_ENABLED = "notification_dissolve_enabled"
    const val KEY_SPEED = "notification_dissolve_speed_percent"
    const val DEFAULT_SPEED = 100
    const val MIN_SPEED = 35
    const val MAX_SPEED = 300
    const val MAX_ACTIVE = 3
    fun durationMs(speed: Int): Long = 110_000L / speed.coerceIn(MIN_SPEED, MAX_SPEED)

    fun install(api: XposedInterface, classLoader: ClassLoader) {
        if (Build.VERSION.SDK_INT < 33 || Application.getProcessName() != "com.android.systemui") return
        try { Api33.install(api, classLoader) }
        catch (error: Throwable) { Lg.w("LiquidGlass", "notification particles unavailable", error) }
    }

    @RequiresApi(33)
    internal object Api33 {
        private const val ROW = "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow"
        private val ui = Handler(Looper.getMainLooper())
        private val captures = WeakHashMap<View, Capture>() // UI thread only; at most MAX_ACTIVE.
        private val effects = WeakHashMap<View, Dust>()
        private var installed = false
        private var enabled = false
        private var speed = DEFAULT_SPEED
        private var readAt = -1L
        private var retryAt = 0L
        private val worker = ThreadPoolExecutor(0, 1, 15, TimeUnit.SECONDS,
            ArrayBlockingQueue<Runnable>(MAX_ACTIVE), { task ->
                Thread(task, "LiquidGlass-particle-snapshot").apply { isDaemon = true }
            }, ThreadPoolExecutor.AbortPolicy())

        fun install(api: XposedInterface, loader: ClassLoader) {
            if (installed) return
            val row = Class.forName(ROW, false, loader)
            val helper = Class.forName("com.android.systemui.SwipeHelper", false, loader)
            // Select the terminal overload: wrappers must not emit a second effect.
            val dismiss = helper.declaredMethods.filter {
                it.name == "dismissChild" && it.parameterCount >= 3 &&
                    View::class.java.isAssignableFrom(it.parameterTypes[0])
            }.maxByOrNull { it.parameterCount } ?: return
            api.hook(dismiss).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val view = chain.getArg(0) as? View
                if (view != null && row.isInstance(view)) safe {
                    if (allowed(api, view.context)) dismiss(view)
                }
                chain.proceed() // Native deletion/animation/cancellation is always authoritative.
            }
            // Retain an undragged image, once per touch. No capture while scrolling every frame.
            val dispatch = ViewGroup::class.java.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java)
            api.hook(dispatch).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val view = chain.getThisObject() as? View
                if (view != null && row.isInstance(view)) safe {
                    val event = chain.getArg(0) as? MotionEvent
                    if (event?.actionMasked == MotionEvent.ACTION_DOWN && allowed(api, view.context)) prepare(view)
                    else if (event?.actionMasked == MotionEvent.ACTION_UP || event?.actionMasked == MotionEvent.ACTION_CANCEL) {
                        val capture=captures[view];val weak=WeakReference(view)
                        if (capture!=null) ui.postDelayed({ weak.get()?.let { row ->
                            if(captures[row]===capture && !capture.requested) {
                                captures.remove(row);capture.cancelled=true
                            }
                        } },400L)
                    }
                }
                chain.proceed()
            }
            // Aborted swipe: stop the visual immediately; never touch the host animator.
            helper.declaredMethods.filter { it.name == "snapChild" && it.parameterCount > 0 &&
                View::class.java.isAssignableFrom(it.parameterTypes[0]) }.forEach { method ->
                api.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                    safe { (chain.getArg(0) as? View)?.let(::cancel) };chain.proceed()
                }
            }
            installed = true
        }

        private inline fun safe(block: () -> Unit) {
            try { block() } catch (error: Throwable) {
                retryAt = SystemClock.uptimeMillis() + 30_000L
                try { Lg.w("LiquidGlass", "notification particles skipped; native dismissal retained", error) }
                catch (_: Throwable) { }
            }
        }
        private fun allowed(api: XposedInterface, context: Context): Boolean {
            if (Looper.myLooper() != Looper.getMainLooper()) return false
            val now = SystemClock.uptimeMillis()
            if (now < retryAt) return false
            if (readAt < 0 || now - readAt >= 500) {
                readAt = now
                val prefs = Prefs.read(api)
                enabled = prefs.getBoolean(Prefs.KEY_MASTER, false) && prefs.getBoolean(KEY_ENABLED, false)
                speed = Prefs.readIntCompat(prefs, KEY_SPEED, DEFAULT_SPEED).coerceIn(MIN_SPEED, MAX_SPEED)
                if (!enabled) effects.values.toList().forEach { it.stop() }
            }
            val power = context.getSystemService(PowerManager::class.java)
            return enabled && ValueAnimator.areAnimatorsEnabled() && power?.isInteractive == true && !power.isPowerSaveMode
        }

        private class Capture(val root: WeakReference<View>, val x: Float, val y: Float,
            val width: Int, val height: Int, val density: Float, val node: RenderNode,
            val createdAt: Long = SystemClock.uptimeMillis()) {
            @Volatile var bitmap: Bitmap? = null
            @Volatile var cancelled = false
            var requested = false
            var consumed = false
            var finished: (() -> Unit)? = null
        }
        private fun prepare(view: View): Capture? {
            captures.remove(view)?.cancelled = true
            val root = view.rootView
            if (!view.isShown || !view.isHardwareAccelerated || root == view ||
                view.width <= 0 || view.height <= 0 || captures.size >= MAX_ACTIVE || effects.size >= MAX_ACTIVE) return null
            val pos = IntArray(2);val parent = IntArray(2)
            view.getLocationInWindow(pos);root.getLocationInWindow(parent)
            if (pos[1] + view.height <= parent[1] || pos[1] >= parent[1] + root.height) return null
            val node = RenderNode("LiquidGlass dismissed notification")
            node.setPosition(0, 0, view.width, view.height)
            val canvas = node.beginRecording(view.width, view.height)
            try { view.draw(canvas) } finally { node.endRecording() }
            val c = Capture(WeakReference(root), (pos[0]-parent[0]).toFloat(), (pos[1]-parent[1]).toFloat(),
                view.width, view.height, view.resources.displayMetrics.density, node)
            captures[view] = c
            val weak = WeakReference(view)
            ui.postDelayed({ safe { weak.get()?.let { row ->
                if (captures[row] === c) { captures.remove(row);c.cancelled = true }
            } } }, 5000L)
            try {
                worker.execute {
                    try {
                        if (!c.cancelled) {
                            val image = snapshot(c)
                            // Never recycle an image that may have reached a recorded shader.
                            if (!c.cancelled) c.bitmap = image
                        }
                    } catch (error: Throwable) {
                        ui.post { safe { throw error } }
                    } finally {
                        c.node.discardDisplayList()
                        ui.post { safe { c.finished?.invoke() } }
                    }
                }
            } catch (error: Throwable) {
                captures.remove(view);c.cancelled = true;node.discardDisplayList();throw error
            }
            return c
        }
        private fun dismiss(view: View) {
            if (effects.containsKey(view)) return
            var c = captures[view]
            if (c == null || c.cancelled || SystemClock.uptimeMillis()-c.createdAt > 4950) c = prepare(view)
            val image = c ?: return
            if (image.requested) return
            image.requested = true
            val weak = WeakReference(view)
            image.finished = { weak.get()?.let { row -> start(row, image) } }
            if (image.bitmap != null) start(view, image)
        }
        private fun start(view: View, c: Capture) {
            if (c.cancelled || c.consumed || !c.requested) return
            val bitmap = c.bitmap ?: return
            val root = c.root.get() ?: return
            val power = root.context.getSystemService(PowerManager::class.java)
            if (!root.isAttachedToWindow || !root.isShown || power?.isInteractive != true ||
                power.isPowerSaveMode || !ValueAnimator.areAnimatorsEnabled() || effects.size >= MAX_ACTIVE) {
                captures.remove(view);c.cancelled = true;return
            }
            c.consumed = true;captures.remove(view)
            val dust = Dust(root, bitmap, c.x, c.y, c.width, c.height, c.density, speed) {
                effects.remove(view)
            }
            effects[view] = dust
            dust.start()
        }
        private fun cancel(view: View) {
            captures.remove(view)?.cancelled = true
            effects.remove(view)?.stop()
        }

        /** Pure GPU render-to-texture. No getPixels, software Canvas, CPU scaling or blur. */
        private fun snapshot(c: Capture): Bitmap {
            val scale = minOf(1f, 768f/c.width, 384f/c.height)
            val w = (c.width*scale).toInt().coerceAtLeast(1);val h = (c.height*scale).toInt().coerceAtLeast(1)
            val reader = ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,1,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
            val renderer = HardwareRenderer()
            val node = RenderNode("LiquidGlass particle snapshot")
            try {
                renderer.isOpaque = false;renderer.setSurface(reader.surface);renderer.setContentRoot(node)
                node.setPosition(0,0,w,h)
                val canvas = node.beginRecording(w,h)
                try { canvas.drawColor(Color.TRANSPARENT,BlendMode.CLEAR);canvas.scale(scale,scale);canvas.drawRenderNode(c.node) }
                finally { node.endRecording() }
                val status = renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
                check(status and (HardwareRenderer.SYNC_LOST_SURFACE_REWARD_IF_FOUND or
                    HardwareRenderer.SYNC_CONTEXT_IS_STOPPED or HardwareRenderer.SYNC_FRAME_DROPPED) == 0)
                val image = reader.acquireNextImage() ?: error("Particle image unavailable")
                try {
                    val buffer = image.hardwareBuffer ?: error("Particle buffer unavailable")
                    try { return Bitmap.wrapHardwareBuffer(buffer,ColorSpace.get(ColorSpace.Named.SRGB))
                        ?: error("Particle bitmap unavailable") }
                    finally { buffer.close() }
                } finally { image.close() }
            } finally { renderer.destroy();reader.close();node.discardDisplayList() }
        }

        internal class Dust(private val root: View, bitmap: Bitmap, x: Float, y: Float,
            width: Int, height: Int, density: Float, speed: Int, private val ended: () -> Unit)
            : Drawable(), Choreographer.FrameCallback, View.OnAttachStateChangeListener {
            private val shader = RuntimeShader(SHADER)
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = this@Dust.shader }
            private val born = SystemClock.uptimeMillis()
            private val duration = durationMs(speed)
            private var stopped = false
            private val frameClock = Choreographer.getInstance()
            private val pad = minOf(width*.4f,180f*density)
            private var lastDraw = 0L
            private var lastPowerCheck = 0L
            private var powerAllowed = true
            init {
                setBounds((x-pad).toInt(),(y-pad).toInt(),(x+width+pad).toInt(),(y+height+pad).toInt())
                val source = BitmapShader(bitmap,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP)
                source.setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
                shader.setInputShader("uImage",source)
                shader.setFloatUniform("uSize",width.toFloat(),height.toFloat())
                // Child-shader sampling uses explicit coordinates; do not rely on localMatrix.
                shader.setFloatUniform("uImageScale",bitmap.width.toFloat()/width,bitmap.height.toFloat()/height)
                shader.setFloatUniform("uPad",pad)
                shader.setFloatUniform("uCell",(1.0f*density).coerceAtLeast(1.2f))
                shader.setFloatUniform("uProgress",0f)
            }
            fun start() { root.overlay.add(this);root.addOnAttachStateChangeListener(this);frameClock.postFrameCallback(this) }
            fun stop() {
                if (stopped) return
                stopped = true;frameClock.removeFrameCallback(this)
                root.overlay.remove(this);root.removeOnAttachStateChangeListener(this);ended()
            }
            override fun doFrame(time: Long) {
                try {
                    if (stopped) return
                    val now = SystemClock.uptimeMillis()
                    val elapsed = now-born
                    if (now-lastPowerCheck >= 200L) {
                        lastPowerCheck=now
                        val power = root.context.getSystemService(PowerManager::class.java)
                        powerAllowed=power?.isInteractive == true && !power.isPowerSaveMode && ValueAnimator.areAnimatorsEnabled()
                    }
                    if (elapsed >= duration || !root.isShown || root.windowVisibility != View.VISIBLE || !powerAllowed) { stop();return }
                    if (time-lastDraw >= 30_000_000L) {
                        lastDraw=time;shader.setFloatUniform("uProgress",elapsed.toFloat()/duration);invalidateSelf()
                    }
                    frameClock.postFrameCallback(this)
                } catch(error:Throwable) { stop();safe { throw error } }
            }
            override fun draw(canvas: Canvas) {
                if (stopped || !canvas.isHardwareAccelerated) return
                val saved=canvas.save()
                try { canvas.translate(bounds.left.toFloat(),bounds.top.toFloat())
                    canvas.drawRect(0f,0f,bounds.width().toFloat(),bounds.height().toFloat(),paint)
                } catch(error:Throwable) { ui.post { stop();safe { throw error } } }
                finally { canvas.restoreToCount(saved) }
            }
            override fun setAlpha(alpha: Int) { paint.alpha=alpha;invalidateSelf() }
            override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter=filter;invalidateSelf() }
            @Deprecated("Deprecated in Android") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
            override fun onViewAttachedToWindow(v: View) { }
            override fun onViewDetachedFromWindow(v: View) { stop() }
        }

        // All per-fragment motion, grain coverage, source colour and fading execute in AGSL on GPU.
        // Smooth inverse advection avoids a CPU particle buffer or a sparse blur sampling lattice.
        internal const val SHADER = """
            uniform shader uImage;
            uniform float2 uSize;
            uniform float2 uImageScale;
            uniform float uProgress;
            uniform float uCell;
            uniform float uPad;
            float hash(float2 p) { return fract(sin(dot(p,float2(127.1,311.7)))*43758.5453); }
            float2 flow(float2 p, float t) {
                float2 n=p/max(uSize,float2(1.0));
                return float2((0.35+0.18*sin(n.y*9.0+n.x*4.0))*uSize.x,
                    (-0.28+0.18*sin(n.x*8.0+n.y*3.0))*uSize.y)*t*t;
            }
            half4 main(float2 pos) {
                float t=clamp(uProgress,0.0,1.0);
                if(t>=0.999)return half4(0.0);
                float2 p=pos-uPad;
                float2 q=p;
                for(int i=0;i<3;i++)q=p-flow(q,t);
                float2 id=floor(q/uCell);
                float rnd=hash(id);
                float wave=0.24*(1.0-q.y/uSize.y)+0.10*rnd;
                float dust=smoothstep(wave,wave+0.13,t);
                float2 centre=0.15+0.70*float2(hash(id+17.0),hash(id+41.0));
                float2 grain=fract(q/uCell)-centre;
                float radius=0.10+0.15*hash(id+71.0);
                float dotMask=1.0-smoothstep(radius-0.10,radius+0.10,length(grain));
                float fade=1.0-smoothstep(0.36+0.28*rnd,0.78+0.20*rnd,t);
                float inside=(q.x>=0.0 && q.y>=0.0 && q.x<uSize.x && q.y<uSize.y)?1.0:0.0;
                half4 motes=uImage.eval((id+centre)*uCell*uImageScale)*half(dotMask*dust*fade*inside);
                half4 solid=half4(0.0);
                if(p.x>=0.0 && p.y>=0.0 && p.x<uSize.x && p.y<uSize.y) {
                    float localWave=0.24*(1.0-p.y/uSize.y)+0.10*hash(floor(p/uCell));
                    float keep=1.0-smoothstep(localWave,localWave+0.13,t);
                    solid=uImage.eval(p*uImageScale)*half(keep);
                }
                return motes+solid*(1.0-motes.a);
            }
        """
    }
}
