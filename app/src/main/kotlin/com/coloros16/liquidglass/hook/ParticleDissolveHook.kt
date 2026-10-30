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
import android.view.View
import androidx.annotation.RequiresApi
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.log.Lg
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.reflect.Method
import java.lang.reflect.Field
import java.lang.reflect.Modifier
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
    fun handoffX(capturedX:Float,recordRowTranslation:Float,currentTranslation:Float):Float =
        if(currentTranslation.isFinite() && recordRowTranslation.isFinite())capturedX+currentTranslation-recordRowTranslation else capturedX
    fun handoffProgress(translation:Float,width:Int):Float =
        if(width>0 && translation.isFinite())(.14f+.24f*(kotlin.math.abs(translation)/width).coerceIn(0f,1f)).coerceAtMost(.32f) else .14f
    fun durationMs(speed: Int): Long = 110_000L / speed.coerceIn(MIN_SPEED, MAX_SPEED)
    // Let most of the dissolve be seen, without making slow presets hold the shade for seconds.
    fun clearAllHoldMs(speed:Int,elapsed:Long):Long =
        ((durationMs(speed)*.68).toLong()-elapsed.coerceAtLeast(0)).coerceIn(0L,900L)

    private var reporter: ((String,String)->Unit)? = null
    private fun report(stage:String,detail:String) { runCatching { reporter?.invoke("particles_$stage",detail) } }
    fun install(api: XposedInterface, classLoader: ClassLoader, diagnostics: ((String,String)->Unit)? = null) {
        reporter=diagnostics
        if (Build.VERSION.SDK_INT < 33 || Application.getProcessName() != "com.android.systemui") return
        try { Api33.install(api, classLoader) }
        catch (error: Throwable) { report("error","install: ${error.javaClass.simpleName}: ${error.message}");Lg.w("LiquidGlass", "notification particles unavailable", error) }
    }

    @RequiresApi(33)
    internal object Api33 {
        private const val ROW = "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow"
        private val ui = Handler(Looper.getMainLooper())
        private val captures = WeakHashMap<View, Capture>() // UI thread only; at most MAX_ACTIVE.
        private val effects = WeakHashMap<View, Dust>()
        private val shadeCloses = WeakHashMap<View, ShadeClose>()
        private var replayingClose=false
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
            // COS17 overrides both the gesture and menu paths in NotificationSwipeHelper.
            // Hook every concrete declaration; Capture.requested/consumed deduplicates wrappers.
            val helpers = listOfNotNull(helper, runCatching {
                Class.forName("com.android.systemui.statusbar.notification.stack.NotificationSwipeHelper", false, loader)
            }.getOrNull(), runCatching {
                // Confirmed stock COS17 callback. Its start/commit events cover clear-all too;
                // clearNotifications() does not call row.performDismiss() for every row.
                Class.forName("com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController\$10", false, loader)
            }.getOrNull()).distinct()
            var mounted = 0
            for (type in helpers) for (route in NotificationMotionContract.routes(type)) {
              try {
                api.hook(route.method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                    val view = chain.getArg(route.index) as? View
                    if (view != null && row.isInstance(view)) safe {
                        if (route.event == NotificationMotionContract.Event.CANCEL ||
                            route.event == NotificationMotionContract.Event.ABORT || allowed(api, view.context))
                            onGesture(view, route.event)
                    }
                    chain.proceed()
                }
                mounted++
              } catch(error:Throwable) { report("route_error","${route.method.name}: ${error.javaClass.simpleName}") }
            }
            // Callers compiled before installation can inline the small callback wrappers.
            for(type in helpers) for(method in type.declaredMethods) {
                if(method.name in setOf("onTouchEvent","onInterceptTouchEvent","dismissChild","superDismissChild","dismiss","onDownUpdate"))
                    runCatching { api.deoptimize(method) }
            }
            // Native row deletion is the commit point. An attempted swipe/menu open is not deletion.
            val commit = row.getDeclaredMethod("performDismiss", Boolean::class.javaPrimitiveType)
            api.hook(commit).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val view = chain.getThisObject() as? View
                if (view != null) safe { if (allowed(api, view.context)) request(view) }
                val result = chain.proceed()
                if (view != null) safe { if (allowed(api, view.context)) dismiss(view) }
                result
            }
            Lg.i("LiquidGlass", "particle gesture routes=$mounted; native row commit installed")
            report("hook","gesture routes=$mounted; native commit installed")
            // This optional route must not prevent the gesture routes from installing.
            runCatching { installClearAllClose(api,loader) }.onFailure {
                report("clear_all_route","原生收起保留：${it.javaClass.simpleName}")
            }
            installed = true
        }

        private fun installClearAllClose(api:XposedInterface,loader:ClassLoader) {
            val stack=Class.forName("com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout",false,loader)
            val callback=Class.forName(stack.name+"\$\$ExternalSyntheticLambda7",false,loader)
            // Verified COS17 callback: the close=true branch ONLY calls animateCollapseShade(0).
            // The native post-collapse action still owns notification removal and completion.
            val route=NotificationClearAllContract.resolve(callback,stack) ?: error("Unknown clear-all callback contract")
            val expanded=stack.getDeclaredField("mIsExpanded").apply { isAccessible=true }
            val needsClose=stack.getDeclaredField("mShadeNeedsToClose").apply { isAccessible=true }
            api.hook(route.method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                if(replayingClose || Looper.myLooper()!=Looper.getMainLooper())return@intercept chain.proceed()
                val receiver=chain.getThisObject() ?: return@intercept chain.proceed()
                val owner=route.owner.get(receiver) as? View ?: return@intercept chain.proceed()
                var deferred=false
                safe {
                    if(route.close.getBoolean(receiver) && expanded.getBoolean(owner) && needsClose.getBoolean(owner) && allowed(api,owner.context)) {
                        val now=SystemClock.uptimeMillis()
                        val delay=effects.values.filter { it.belongsTo(owner.rootView) }.maxOfOrNull { it.shadeHoldMs(now) }
                            ?: captures.values.filter { it.committed && !it.cancelled && it.root.get()===owner.rootView }
                                .maxOfOrNull { clearAllHoldMs(speed,0) } ?: 0L
                        if(delay>0 || shadeCloses.containsKey(owner)) {
                            val argument=chain.getArg(0)
                            deferred=deferClearAll(owner,delay) {
                                replayingClose=true
                                try { route.method.invoke(receiver,argument) } finally { replayingClose=false }
                            }
                        }
                    }
                }
                if(deferred)null else chain.proceed()
            }
            // Manual shade closing takes priority. Never collapse a newly reopened panel later.
            stack.getDeclaredMethod("setIsExpanded",Boolean::class.javaPrimitiveType).let { method ->
                api.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                    if(chain.getArg(0)==false && Looper.myLooper()==Looper.getMainLooper())
                        (chain.getThisObject() as? View)?.let(::cancelShadeClose)
                    chain.proceed()
                }
            }
            stack.declaredMethods.filter { it.name=="clearNotifications" }.forEach { runCatching { api.deoptimize(it) } }
            report("clear_all_route","一键清除收起回调已接入；只在粒子播放期间短暂等待")
        }

        /** Own one bounded callback, never an Xposed Chain. Also used by the lifecycle probe. */
        internal fun deferClearAll(owner:View,delay:Long,completion:()->Unit):Boolean {
            if(shadeCloses.containsKey(owner))return true
            if(delay<=0 || !owner.isAttachedToWindow || !owner.isShown)return false
            val close=ShadeClose(owner,completion)
            shadeCloses[owner]=close
            owner.addOnAttachStateChangeListener(close)
            ui.postDelayed(close,delay.coerceAtMost(900L))
            report("clear_all_wait","等待粒子播放 ${delay.coerceAtMost(900L)}ms 后由系统收起")
            return true
        }
        internal fun cancelShadeClose(owner:View) { shadeCloses[owner]?.finish(false) }
        private class ShadeClose(owner:View,private var completion:(()->Unit)?):Runnable,View.OnAttachStateChangeListener {
            private val owner=WeakReference(owner)
            override fun run() { finish(true) }
            fun finish(run:Boolean) {
                val callback=completion ?: return
                completion=null;ui.removeCallbacks(this)
                val view=owner.get()
                if(view!=null) { shadeCloses.remove(view);view.removeOnAttachStateChangeListener(this) }
                if(run && view?.isAttachedToWindow==true) safe { callback() }
            }
            override fun onViewAttachedToWindow(v:View) {}
            override fun onViewDetachedFromWindow(v:View) { finish(false) }
        }

        internal fun onGesture(view: View, event: NotificationMotionContract.Event) {
            if (Looper.myLooper() != Looper.getMainLooper()) return
            report("event_${event.name.lowercase()}","${event.name}: ${view.javaClass.simpleName}")
            when (event) {
                NotificationMotionContract.Event.PREPARE -> {
                    val old = captures[view]
                    if (old == null || old.cancelled || SystemClock.uptimeMillis()-old.createdAt>100L) prepare(view)
                }
                NotificationMotionContract.Event.REQUEST -> request(view)
                NotificationMotionContract.Event.ACCEPT -> dismiss(view)
                NotificationMotionContract.Event.CANCEL -> cancel(view)
                NotificationMotionContract.Event.COMMIT -> if (captures[view]?.requested == true) dismiss(view)
                NotificationMotionContract.Event.ABORT -> abort(view)
            }
        }

        private inline fun safe(block: () -> Unit) {
            try { block() } catch (error: Throwable) {
                retryAt = SystemClock.uptimeMillis() + 30_000L
                report("error","${error.javaClass.simpleName}: ${error.message}")
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
                if (!enabled) stopAll()
                report("enabled",if(enabled)"enabled" else "disabled")
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
            var committed = false
            var consumed = false
            var rowTranslation=0f
            var releaseX=x;var releaseY=y
            var direction=1f;var initialProgress=0f
            var hiddenAlpha:Float?=null
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
            // ExpandableNotificationRow may measure its tallest content layout even while a
            // short card is visible. Record the native actual-height, not that offscreen area.
            val actualHeight=runCatching { (view.javaClass.getMethod("getActualHeight").invoke(view) as Number).toInt() }
                .getOrDefault(view.height).coerceIn(1,view.height)
            val node = RenderNode("LiquidGlass dismissed notification")
            node.setPosition(0, 0, view.width, actualHeight)
            val canvas = node.beginRecording(view.width, actualHeight)
            try {
                // COS17 slides mTranslateableViews instead of the row itself. Compensate only
                // that child offset in the recording canvas; never mutate the host View.
                val contentShift = runCatching {
                    (view.javaClass.getMethod("getTranslation").invoke(view) as Number).toFloat() - view.translationX
                }.getOrDefault(0f)
                if (contentShift.isFinite()) canvas.translate(-contentShift, 0f)
                view.draw(canvas)
            } finally { node.endRecording() }
            val c = Capture(WeakReference(root), (pos[0]-parent[0]).toFloat(), (pos[1]-parent[1]).toFloat(),
                view.width, actualHeight, view.resources.displayMetrics.density, node)
            captures[view] = c
            c.rowTranslation=view.translationX
            report("capture","GPU recorded ${c.width}x${c.height}; root=${root.javaClass.simpleName}")
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
                            if (!c.cancelled) { c.bitmap = image;report("snapshot","hardware texture ${image.width}x${image.height}") }
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
        private fun request(view: View): Capture? {
            if (effects.containsKey(view)) return null
            val old = captures[view]
            val c = if (old == null || old.cancelled || SystemClock.uptimeMillis()-old.createdAt > 4950)
                prepare(view) else old
            c?.requested = true
            return c
        }
        private fun dismiss(view: View) {
            val image = request(view) ?: return
            if (image.committed) return
            image.committed = true
            val translation=runCatching { (view.javaClass.getMethod("getTranslation").invoke(view) as Number).toFloat() }.getOrDefault(view.translationX)
            image.releaseX=handoffX(image.x,image.rowTranslation,translation)
            image.direction=if(translation<0f)-1f else 1f
            image.initialProgress=handoffProgress(translation,image.width)
            // Keep the committed row until the bounded GPU job completes; native removal can
            // otherwise release its last strong reference before the image is ready.
            image.finished = { start(view, image) }
            if (image.bitmap != null) start(view, image)
        }
        private fun start(view: View, c: Capture) {
            if (c.cancelled || c.consumed || !c.committed) return
            val bitmap = c.bitmap ?: return
            val root = c.root.get() ?: return
            val power = root.context.getSystemService(PowerManager::class.java)
            if (!root.isAttachedToWindow || !root.isShown || power?.isInteractive != true ||
                power.isPowerSaveMode || !ValueAnimator.areAnimatorsEnabled() || effects.size >= MAX_ACTIVE) {
                captures.remove(view);c.cancelled = true;return
            }
            c.consumed = true;captures.remove(view)
            if(view.isAttachedToWindow) {
                val current=runCatching { (view.javaClass.getMethod("getTranslation").invoke(view) as Number).toFloat() }.getOrDefault(view.translationX)
                val currentX=handoffX(c.x,c.rowTranslation,current)
                if(current.isFinite() && (currentX-c.releaseX)*c.direction>=0f)c.releaseX=currentX
            }
            val dust = Dust(root, bitmap, c.releaseX, c.releaseY, c.width, c.height, c.density, speed,c.direction,c.initialProgress) {
                c.hiddenAlpha?.let { old -> if(view.alpha==0f)view.alpha=old };c.hiddenAlpha=null
                effects.remove(view)
            }
            effects[view] = dust
            dust.start()
            c.hiddenAlpha=view.alpha;view.alpha=0f
            report("handoff","已从滑动位置接管：x=${c.releaseX.toInt()}；原生删除回调继续运行")
            report("playing","GPU dust started")
        }
        private fun cancel(view: View) {
            val c = captures[view]
            if (c?.committed == true || effects.containsKey(view)) return
            captures.remove(view)?.cancelled = true
        }
        private fun abort(view: View) {
            captures.remove(view)?.cancelled = true
            effects.remove(view)?.stop()
        }
        internal fun stopAll() {
            if (Looper.myLooper() != Looper.getMainLooper()) { ui.post { stopAll() };return }
            captures.values.forEach { it.cancelled = true };captures.clear()
            effects.values.toList().forEach { it.stop() };effects.clear()
            // Disabling particles releases native completion immediately, without another hold.
            shadeCloses.values.toList().forEach { it.finish(true) }
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
            width: Int, height: Int, density: Float, private val speed: Int, direction:Float=1f,
            private val initialProgress:Float=0f,private val ended: () -> Unit)
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
            fun belongsTo(view:View)=root===view
            fun shadeHoldMs(now:Long)=if(stopped)0L else clearAllHoldMs(speed,now-born)
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
                shader.setFloatUniform("uProgress",initialProgress)
                shader.setFloatUniform("uDirection",direction)
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
                        lastDraw=time;shader.setFloatUniform("uProgress",initialProgress+(1f-initialProgress)*elapsed.toFloat()/duration);invalidateSelf()
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
            uniform float uDirection;
            float hash(float2 p) { return fract(sin(dot(p,float2(127.1,311.7)))*43758.5453); }
            float2 flow(float2 p, float t) {
                float2 n=p/max(uSize,float2(1.0));
                return float2((uDirection<0.0?-1.0:1.0)*(0.35+0.18*sin(n.y*9.0+n.x*4.0))*uSize.x,
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

/** Reject changed OEM callback layouts rather than guessing which boolean controls collapse. */
object NotificationClearAllContract {
    data class Route(val method:Method,val owner:Field,val close:Field)
    fun resolve(callback:Class<*>,stack:Class<*>):Route? {
        if(!java.util.function.Consumer::class.java.isAssignableFrom(callback))return null
        val fields=callback.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
        val owner=fields.singleOrNull { it.type==stack } ?: return null
        val close=fields.singleOrNull { it.type==Boolean::class.javaPrimitiveType } ?: return null
        if(fields.size!=3 || fields.none { java.util.function.Consumer::class.java.isAssignableFrom(it.type) })return null
        val method=callback.declaredMethods.singleOrNull { it.name=="accept" && it.returnType==Void.TYPE &&
            it.parameterTypes.contentEquals(arrayOf(Any::class.java)) && !Modifier.isAbstract(it.modifiers) } ?: return null
        owner.isAccessible=true;close.isAccessible=true;method.isAccessible=true
        return Route(method,owner,close)
    }
}

/** Match the actual View argument, including COS17 snapChild(float,float,View).
 * Kept separate from Xposed so ROM-shaped fixtures can exercise the same resolver. */
object NotificationMotionContract {
    enum class Event { PREPARE, REQUEST, ACCEPT, CANCEL, COMMIT, ABORT }
    data class Route(val method: Method, val index: Int, val event: Event)
    fun routes(type: Class<*>): List<Route> = type.declaredMethods.mapNotNull { method ->
        val event = when (method.name) {
            "onDownUpdate" -> Event.PREPARE
            "dismissChild", "dismiss", "superDismissChild" -> Event.REQUEST
            "snapChild", "snapClosed", "snapOpen", "onChildSnappedBack" -> Event.CANCEL
            "onSwipeOutAnimStart" -> Event.ACCEPT
            "onChildDismissed", "handleChildViewDismissed" -> Event.COMMIT
            "onChildNotDismissed" -> Event.ABORT
            else -> return@mapNotNull null
        }
        val index = method.parameterTypes.indexOfFirst { View::class.java.isAssignableFrom(it) }
        if (index < 0 || method.returnType != Void.TYPE || Modifier.isAbstract(method.modifiers)) null else Route(method, index, event)
    }
}
