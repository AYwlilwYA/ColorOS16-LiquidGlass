package com.coloros16.liquidglass.hook

import android.content.Intent
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.os.Bundle
import android.util.Log
import android.view.SurfaceControl
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Hook A（BlurService 进程版，onBlurReady 通道）：拦截未模糊原图并经**系统 onBlurReady Binder
 * 通道**传回 SystemUI，弃用跨进程 ContentProvider（M2a onBlurReady 方案）。
 *
 * 16.1 面板模糊机制（反编译实证，BlurService 混淆类 c.s / c.w / f.c）：
 * - `c.s.b(String layerName, HardwareBuffer hw, int rotation, boolean importantFrame)`：
 *   binder 调用（implements a.d.c），arg1 = 未模糊原图（1/4 分辨率），post 到 Blur handler 线程
 *   执行抓屏任务（c.k），任务内 `w.f193k = hw`（当前窗口原图，换帧时 close 旧帧）。
 * - `c.w.b(boolean blurSuccess)`（onBlurComplete，implements b.a）：Blur handler 线程，
 *   模糊完成后经 `f.c.b(Bundle, List<List<Integer>>, List<HardwareBuffer>, int)` 把 drawableIds +
 *   模糊结果 buffer 塞入 Bundle，再 `IBlurConnection.a(Bundle)` Binder 传回 SystemUI
 *   → `BlurDrawableManager.onBlurReady(Bundle)` → 主线程 `onBlurReady(List, HardwareBuffer, int, float)`。
 *
 * 本 hook 三件套（全 try-catch + PROTECTIVE，任何失败回退系统行为，绝不影响 BlurService）：
 * 1. `c.s.b` hookBefore：原图存线程安全 map `layerName -> HardwareBuffer` + [latestOriginalBuffer]
 *    + [latestLayerName]（持引用，不 close）。
 * 2. `c.w.b` hookBefore：从 [latestOriginalBuffer]（c.s.b 刚存的最新一帧原图，同一模糊周期内有效）
 *    取 → 模块全局 [pendingOriginalBuffer]，层名同步 [pendingLayerName]；hookAfter 清空（不 close，归系统）。
 *    不反射混淆字段 f193k——jadx 反编译字段名 ≠ 运行时真实混淆名，硬编码不可靠。
 * 3. `f.c.b` hookBefore：pendingOriginalBuffer 非空且层名属 SystemUI 场景（[isSystemUiLayer]）时把
 *    `args[2]`（blurBuffers）每个元素替换为原图，再 `chain.proceed(newArgs)` → Bundle 装的就是原图，
 *    随系统 Binder+Parcel 零拷贝回 SystemUI。原图不可用（null/closed）或层名非 SystemUI（Launcher 文件夹
 *    等）→ 不替换，保留系统模糊结果（避免自采样白块 + 跨层串图）。
 *
 * 引用计数铁律：替换路径任何地方不 close 任何 buffer——
 * - 原图：latestOriginalBuffer 引用直接塞 bundle，Parcel 传输 SystemUI 侧自动 acquire；BlurService 侧引用归系统管理。
 * - 被替换的模糊结果 buffer：不 close，仍被系统缓存（c.w$a.f205c / c.w.f200r）引用。
 * - c.s.b 存的 map / latestOriginalBuffer 引用：不 close（与系统 f193k 同源，系统换帧/关窗时统一 close）。
 *
 * 锚定链路（防 OTA 混淆漂移，不硬编码脱离锚定）：
 * 1. 主锚点 hook `com.oplus.blur.BlurService#onBind(Intent)`（类名稳定未混淆）→ binder 实例
 *    class（当前 16.1 为 c.s）→ 按签名找 (String, HardwareBuffer, int, boolean) 动态 hook。
 * 2. 解析 c.w：反射 `c.s.f148b` 字段泛型（Map<String, c.w>）拿 c.w 类；失败退回候选 "c.w"；
 *    再失败在 c.s.b hook 内从实例 f148b map 取 value 的 class 延迟解析。
 * 3. c.w 类确定后：hook `b(boolean)`；`Class.forName("f.c")` 找 `b(Bundle, List, List, int)` hook。
 *
 * 目标方法均 @Volatile 去重，动态解析与兜底共用。
 */
object BlurCaptureHook {

    private const val TAG = "LiquidGlass"

    private const val CLASS_BLUR_SERVICE = "com.oplus.blur.BlurService"
    private const val METHOD_ON_BIND = "onBind"

    /** BlurService 抓屏封装混淆类（e.a）：LayerCaptureArgs 构造 + captureLayers 入口 */
    private const val CLASS_CAPTURE_UTILS = "e.a"
    /** 全分辨率 frameScale（替代系统默认 0.25f） */
    private const val FRAME_SCALE_FULL = 1.0f
    /** android.window.ScreenCapture$LayerCaptureArgs（@SystemApi，编译期不可直接引用，仅按类名匹配） */
    private const val CLASS_LAYER_CAPTURE_ARGS = "android.window.ScreenCapture\$LayerCaptureArgs"

    /** c.s -> Map<String, c.w>（layerName -> WindowBlurSession） */
    private const val FIELD_SESSION_MAP = "f148b"
    /** c.w.b(boolean)：onBlurComplete（implements b.a） */
    private const val METHOD_ON_BLUR_COMPLETE = "b"
    /** f.c.b(Bundle, List, List, int)：把 drawableIds + blurBuffers 塞 Bundle */
    private const val CLASS_BLUR_BUFFER_ENCODER = "f.c"
    private const val METHOD_BLUR_BUFFER_ENCODE = "b"

    /** 兜底候选类名：当前 16.1 设备实证的混淆类名；OTA 可能变，
     *  需沿 BlurService.onBind → c.s → c.w → f.c 链路重新锚定 */
    private val fallbackClasses = arrayOf("c.s")

    /** 模块全局原图（c.w.b hookBefore 写，f.c.b hookBefore 读；Blur handler 单线程，无并发） */
    @Volatile
    private var pendingOriginalBuffer: HardwareBuffer? = null

    /** 模块全局原图对应层名（c.w.b hookBefore 写，f.c.b hookBefore 读；与 pendingOriginalBuffer 配对）。
     *  用于场景过滤：只对 SystemUI 层替换原图，Launcher 文件夹等保留系统模糊（避免自采样白块 + 跨层串图）。 */
    @Volatile
    private var pendingLayerName: String? = null

    /** 未模糊原图 map（c.s.b 写，layerName -> HardwareBuffer；持引用不 close，与系统 f193k 同源） */
    private val originalBufferMap = ConcurrentHashMap<String, HardwareBuffer>()

    /**
     * 最新一帧未模糊原图（c.s.b 写，@Volatile 保证 c.w.b / f.c.b 跨线程可见；持引用不 close）。
     * 替换路径（f.c.b）经 pendingOriginalBuffer 用本字段，替代反射混淆字段 f193k（硬编码不可靠）。
     */
    @Volatile
    private var latestOriginalBuffer: HardwareBuffer? = null

    /** 最新一帧未模糊原图对应层名（c.s.b 写，@Volatile；与 latestOriginalBuffer 同帧配对）。
     *  c.s.b 的 arg0 即 layerName，如 SystemUI 的 NotificationShade / Launcher 的桌面文件夹层。 */
    @Volatile
    private var latestLayerName: String? = null

    /** 已成功挂载的目标方法（动态解析与兜底共用去重） */
    @Volatile
    private var mountedCaptureMethod: Method? = null

    /** 全分辨率 frameScale hook（e.a.c / e.a.a）是否已挂载 */
    @Volatile
    private var mountedFrameScaleHooks = false

    // [自采样根治 v2-v4 2026-08-12/13 已移除] captureDisplay 整屏排除替换方向废弃（doc/spec/06）：
    // 以下字段/函数均已删除——currentShadeSfc / shadeSfc / currentCaptureIsSysui / currentCaptureSfcName /
    // mountedShadeSfcHooks / mountShadeSfcHooks / cacheShadeSfc / captureCleanFullscreen /
    // replaceWithCleanFullscreen / rebuildWithSelfExclude / applySelfExclude / CLASS_OPLUS_* 常量。
    // 系统抓屏恢复原样（自采样 buffer），玻璃源完全走 SystemUI 端背景源抓屏。

    /** 已挂载的 c.w.b(boolean) 方法 */
    @Volatile
    private var mountedBlurCompleteMethod: Method? = null

    /** 已挂载的 c.w.e()（onBlurPrepared）方法：模糊输入前截取未模糊 f193k */
    @Volatile
    private var mountedPreparedMethod: Method? = null

    /** 已挂载的 f.c.b(Bundle, List, List, int) 方法 */
    @Volatile
    private var mountedEncodeMethod: Method? = null

    /** 会话类（c.w / f.c）是否已解析成功 */
    @Volatile
    private var sessionResolved = false

    @Volatile
    private var apiRef: XposedInterface? = null

    fun install(api: XposedInterface, classLoader: ClassLoader) {
        apiRef = api
        // 全分辨率原图：hook e.a.c / e.a.a 强制 LayerCaptureArgs frameScale=1.0f（不依赖 c.s 解析，直接挂）
        mountFrameScaleHooks(api, classLoader)
        // 主锚点：BlurService.onBind（类名稳定），hookAfter 动态解析真实混淆类
        mountOnBindAnchor(api, classLoader)
        // 兜底：硬编码候选混淆类名逐个尝试
        mountFallback(api, classLoader)
        // [验证 dump 2026-08-13] BlurService 进程广播 receiver（adb am broadcast -a com.coloros16.liquidglass.DUMP 触发 dump 原始 captureLayers）
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ registerDumpReceiver() }, 5000L)
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------ 主锚点（动态解析）

    private fun mountOnBindAnchor(api: XposedInterface, classLoader: ClassLoader) {
        try {
            val clazz = Class.forName(CLASS_BLUR_SERVICE, false, classLoader)
            val method = clazz.getDeclaredMethod(METHOD_ON_BIND, Intent::class.java)
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    // hookAfter 语义：proceed() 拿原方法返回值（binder 实例），再动态解析
                    val binder = chain.proceed()
                    try {
                        if (binder != null) {
                            resolveAndHook(api, binder.javaClass)
                        } else {
                            Log.w(TAG, "BlurService.onBind returned null, dynamic resolve skipped")
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "BlurService.onBind dynamic resolve error", t)
                    }
                    binder
                }
            Log.i(TAG, "Hook A(BlurService) anchor mounted: $CLASS_BLUR_SERVICE#$METHOD_ON_BIND(Intent)")
        } catch (t: Throwable) {
            Log.e(TAG, "Hook A(BlurService) anchor mount FAILED: $CLASS_BLUR_SERVICE#$METHOD_ON_BIND(Intent)", t)
        }
    }

    /** 在 root 及其继承层次（父类链 + 接口）中找目标方法；优先具体类方法（hook 实现类更可靠） */
    private fun resolveAndHook(api: XposedInterface, root: Class<*>): Boolean {
        // 1) 具体类链：root → 父类，找签名匹配的具体方法
        var c: Class<*>? = root
        while (c != null) {
            if (!c.isInterface) {
                val m = findCaptureMethod(c)
                if (m != null) {
                    val ok = hookCaptureMethod(api, "${c.name}.${m.name}", m)
                    if (ok) {
                        Log.i(TAG, "Hook A(BlurService) resolved via BlurService.onBind -> ${c.name}.${m.name}")
                        // 新增：c.s 已确定，锚定 c.w / f.c（onBlurReady 通道剩余 hook）
                        resolveSessionClasses(api, c)
                    }
                    return ok
                }
            }
            c = c.superclass
        }
        // 2) 接口：root + 各级父接口（BFS），兜底接口声明的方法
        val visited = HashSet<Class<*>>()
        val queue = ArrayDeque<Class<*>>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val k = queue.removeFirst()
            if (!visited.add(k)) continue
            for (itf in k.interfaces) {
                val m = findCaptureMethod(itf)
                if (m != null) {
                    val ok = hookCaptureMethod(api, "${itf.name}.${m.name}", m)
                    if (ok) {
                        Log.i(TAG, "Hook A(BlurService) resolved via BlurService.onBind -> ${itf.name}.${m.name}")
                        resolveSessionClasses(api, itf)
                    }
                    return ok
                }
                queue.add(itf)
            }
        }
        Log.w(TAG, "Hook A(BlurService) dynamic resolve: no (String, HardwareBuffer, int, boolean) method in ${root.name} hierarchy")
        return false
    }

    // ------------------------------------------------------------ 兜底（混淆类名候选）

    private fun mountFallback(api: XposedInterface, classLoader: ClassLoader) {
        for (candidate in fallbackClasses) {
            try {
                val clazz = Class.forName(candidate, false, classLoader)
                val m = findCaptureMethod(clazz)
                if (m == null) {
                    Log.w(TAG, "Hook A(BlurService) fallback: $candidate has no (String, HardwareBuffer, int, boolean) method")
                    continue
                }
                val ok = hookCaptureMethod(api, "$candidate.${m.name}", m)
                if (ok) {
                    Log.i(TAG, "Hook A(BlurService) fallback anchor hit: $candidate")
                    resolveSessionClasses(api, clazz)
                }
                return
            } catch (t: Throwable) {
                Log.e(TAG, "Hook A(BlurService) fallback mount FAILED: $candidate", t)
            }
        }
    }

    // ------------------------------------------------------------ 公共：方法查找 / 安装

    /** 按参数签名 [String, HardwareBuffer, int, boolean] 查找目标方法 */
    private fun findCaptureMethod(clazz: Class<*>): Method? =
        clazz.declaredMethods.firstOrNull { m ->
            val p = m.parameterTypes
            p.size == 4 &&
                p[0] == String::class.java &&
                p[1] == HardwareBuffer::class.java &&
                p[2] == Int::class.javaPrimitiveType &&
                p[3] == Boolean::class.javaPrimitiveType
        }

    private fun hookCaptureMethod(api: XposedInterface, label: String, method: Method): Boolean {
        val already = mountedCaptureMethod
        if (already != null && already == method) {
            Log.i(TAG, "Hook A(BlurService) capture method already mounted: $label, skip")
            return true
        }
        try {
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        captureOriginalBuffer(chain)
                    } catch (t: Throwable) {
                        Log.e(TAG, "$label intercept error", t)
                    }
                    chain.proceed()
                }
            mountedCaptureMethod = method
            Log.i(TAG, "Hook A(BlurService) mounted: $label(String, HardwareBuffer, int, boolean)")
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "Hook A(BlurService) mount FAILED: $label", t)
            return false
        }
    }

    /** hook 1（c.s.b）：arg1 = 未模糊原图 HardwareBuffer → 存线程安全 map（持引用，不 close） */
    private fun captureOriginalBuffer(chain: XposedInterface.Chain) {
        val layerName = try {
            chain.getArg(0) as? String
        } catch (t: Throwable) {
            Log.e(TAG, "capture getArg(0) failed", t)
            null
        }
        val hw = try {
            chain.getArg(1)
        } catch (t: Throwable) {
            Log.e(TAG, "capture getArg(1) failed", t)
            return
        }
        if (hw !is HardwareBuffer) {
            Log.w(TAG, "capture arg1 is not HardwareBuffer: ${hw?.javaClass?.name ?: "null"}")
            return
        }
        // 原图存 map + latestOriginalBuffer（持引用不 close，与系统 f193k 同源：系统换帧/关窗时统一 close）。
        // 替换路径（f.c.b）从 latestOriginalBuffer 取原图（经 pendingOriginalBuffer），不用本 map。
        if (layerName != null) {
            originalBufferMap[layerName] = hw
        }
        latestOriginalBuffer = hw
        latestLayerName = layerName
        Log.i(TAG, "capture original stored, layer=$layerName, hw=${hw.width}x${hw.height}")
        // [原图清晰度验证 2026-08-12] 低频采样抓屏 buffer 相邻亮度差均值，判定"blur 传回是否模糊图"
        sampleSharpness(hw, layerName)
        // 会话类未解析时，从 c.s 实例延迟解析 c.w（f148b map value 的 class）
        if (!sessionResolved) {
            try {
                val self = chain.getThisObject()
                if (self != null) {
                    val api = apiRef ?: return
                    resolveSessionClasses(api, self.javaClass)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "capture: resolveSessionClasses from instance failed", t)
            }
        }
    }

    /** [原图清晰度验证] 低频计数器（每 8 帧采样一次，控 IO） */
    private var sharpnessCounter = 0

    /**
     * [原图清晰度验证 2026-08-12] c.s.b 抓屏 buffer 采样相邻像素亮度差均值，判定"blur 传回原图是否模糊"。
     * wrapHardwareBuffer → getPixel 采样 40x40 网格相邻差；meanDiff 低（<8）= 模糊图，高（>20）= 清晰。
     * 用于验证用户怀疑"blur 传回即模糊图"——若坐实，根因在 BlurService 抓屏源（c.s.b buffer 即模糊），
     * 需从真正未模糊源取原图。失败静默（不影响抓屏链）。
     */
    private fun sampleSharpness(hw: HardwareBuffer, layerName: String?) {
        if ((++sharpnessCounter and 7) != 0) return
        try {
            val bmp = Bitmap.wrapHardwareBuffer(hw, null) ?: return
            // HARDWARE bitmap 不支持 getPixel（IllegalStateException）→ copy 成软件位图再采样；wrap 出的硬件位图用完即回收
            val copy = bmp.copy(Bitmap.Config.ARGB_8888, false) ?: run { bmp.recycle(); return }
            bmp.recycle()
            val w = copy.width
            val h = copy.height
            if (w <= 0 || h <= 0) { copy.recycle(); return }
            val stepX = maxOf(1, w / 40)
            val stepY = maxOf(1, h / 40)
            var sum = 0.0
            var n = 0
            var y = 0
            while (y < h) {
                var x = 0
                while (x < w) {
                    if (x + stepX < w) {
                        val c1 = copy.getPixel(x, y)
                        val c2 = copy.getPixel(x + stepX, y)
                        sum += Math.abs(lum(c1) - lum(c2)); n++
                    }
                    x += stepX
                }
                y += stepY
            }
            copy.recycle()
            if (n > 0) {
                val mean = sum / n
                Log.i(TAG, "capture sharpness: layer=$layerName hw=${w}x${h} meanDiff=${String.format(Locale.US, "%.1f", mean)} (low<8=blur, high>20=sharp)")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "capture sharpness: sample failed", t)
        }
    }

    private fun lum(color: Int): Int {
        val r = (color ushr 16) and 0xFF
        val g = (color ushr 8) and 0xFF
        val b = color and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    // ------------------------------------------------------------ 会话锚定：c.w / f.c

    /**
     * c.s 类确定后，解析 c.w 类并安装 onBlurReady 通道剩余 hook：
     * - c.w.b(boolean)：hookBefore 从 c.s.b 缓存取原图 → pendingOriginalBuffer
     * - f.c.b(Bundle, List, List, int)：hookBefore 把 blurBuffers 替换为原图
     */
    private fun resolveSessionClasses(api: XposedInterface, cSClass: Class<*>) {
        if (sessionResolved) return
        val wClass = resolveWClass(cSClass)
        if (wClass == null) {
            Log.w(TAG, "Hook A(BlurService) resolve c.w class FAILED, blur-complete hook disabled")
            return
        }
        hookBlurComplete(api, wClass)
        hookWPrepared(api, wClass)
        hookBufferEncode(api, wClass.classLoader ?: return)
        sessionResolved = true
        Log.i(TAG, "Hook A(BlurService) onBlurReady session resolved: c.w=${wClass.name}, encoder=${CLASS_BLUR_BUFFER_ENCODER}")
    }

    /** 解析 c.w 类：优先 f148b 字段泛型（Map<String, c.w>），退回候选类名 "c.w" */
    private fun resolveWClass(cSClass: Class<*>): Class<*>? {
        try {
            val f = cSClass.getDeclaredField(FIELD_SESSION_MAP)
            val g = f.genericType
            if (g is ParameterizedType) {
                val t = g.actualTypeArguments.getOrNull(1) as? Class<*>
                if (t != null) return t
            }
        } catch (t: Throwable) {
            Log.e(TAG, "resolveWClass via f148b generic failed", t)
        }
        try {
            return Class.forName("c.w", false, cSClass.classLoader)
        } catch (t: Throwable) {
            Log.e(TAG, "resolveWClass via candidate c.w failed", t)
        }
        return null
    }

    /** hook 2（c.w.b）：hookBefore 从 c.s.b 缓存取原图；hookAfter 清空 pendingOriginalBuffer */
    private fun hookBlurComplete(api: XposedInterface, wClass: Class<*>) {
        val already = mountedBlurCompleteMethod
        if (already != null) {
            Log.i(TAG, "Hook A(BlurService) blur-complete already mounted, skip")
            return
        }
        val method = wClass.declaredMethods.firstOrNull { m ->
            val p = m.parameterTypes
            p.size == 1 && p[0] == Boolean::class.javaPrimitiveType
        }
        if (method == null) {
            Log.w(TAG, "Hook A(BlurService) blur-complete method b(boolean) not found in ${wClass.name}")
            return
        }
        try {
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = try {
                        onBlurCompleteBefore(chain)
                        chain.proceed()
                    } finally {
                        // hookAfter：清空全局原图与层名（不 close——f193k 归系统管理）
                        pendingOriginalBuffer = null
                        pendingLayerName = null
                    }
                    result
                }
            mountedBlurCompleteMethod = method
            Log.i(TAG, "Hook A(BlurService) mounted blur-complete: ${wClass.name}.b(boolean)")
        } catch (t: Throwable) {
            Log.e(TAG, "Hook A(BlurService) blur-complete mount FAILED: ${wClass.name}.b(boolean)", t)
        }
    }

    /**
     * [未模糊原图截取 2026-08-12] hook c.w.e()（onBlurPrepared）：模糊输入前 f193k 是**未模糊原图**
     * （c/w.java e() line 486-503 把 f193k 传给 RenderEngine 做模糊输入）。模糊完成 b() 时系统可能
     * 复用同一 buffer 写入模糊结果 → f193k 变模糊（"一会儿清晰一会儿模糊"）。e() 时机截取未模糊态。
     */
    private fun hookWPrepared(api: XposedInterface, wClass: Class<*>) {
        val already = mountedPreparedMethod
        if (already != null) return
        val method = wClass.declaredMethods.firstOrNull { m -> m.parameterCount == 0 && m.name == "e" }
        if (method == null) {
            Log.w(TAG, "Hook A(BlurService) prepared method e() not found in ${wClass.name}")
            return
        }
        try {
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        captureUnblurredOriginal(chain)
                    } catch (t: Throwable) {
                        Log.e(TAG, "capture unblurred: intercept error", t)
                    }
                    chain.proceed()
                }
            mountedPreparedMethod = method
            Log.i(TAG, "Hook A(BlurService) mounted prepared: ${wClass.name}.e() (capture unblurred f193k)")
        } catch (t: Throwable) {
            Log.e(TAG, "Hook A(BlurService) prepared mount FAILED: ${wClass.name}.e()", t)
        }
    }

    /** c.w.e() hookBefore：此刻 f193k 是未模糊原图（RenderEngine 模糊输入前）→ 存 map（按层）+ latest。 */
    private fun captureUnblurredOriginal(chain: XposedInterface.Chain) {
        val winst = try {
            chain.getThisObject()
        } catch (t: Throwable) {
            Log.e(TAG, "capture unblurred: getThisObject failed", t); null
        } ?: return
        val layerName = reflectLayerNameField(winst)
        val hw = reflectOriginalBuffer(winst)
        if (hw == null || hw.isClosed) {
            Log.w(TAG, "capture unblurred: f193k not available (null/closed)")
            return
        }
        if (layerName != null) originalBufferMap[layerName] = hw
        latestOriginalBuffer = hw
        latestLayerName = layerName
        Log.i(TAG, "capture unblurred original (f193k @prepared), layer=$layerName hw=${hw.width}x${hw.height}")
    }

    /**
     * hook 2 hookBefore：c.w.b(boolean) 是**当前模糊窗口**完成回调。c.w 实例持有当前层名
     * （f183a，public final String，c/w.java:37）与**未模糊原图 f193k**（HardwareBuffer，RenderEngine
     * 模糊输入，c/w.java e() line 486-503）。反射遍历取这两个字段，替换与层绑定且用未模糊原图。
     *
     * [根因修复 2026-08-12] 此前用 c.s.b 抓屏 buffer（originalBufferMap）——用户看图证实该 buffer
     * **本身模糊**（c.s.b 抓到的可能是模糊后结果），替换成功也是模糊图。c.w.f193k 是 RenderEngine
     * 模糊输入（未模糊原图），优先使用。反射失败/无效 → fallback originalBufferMap（按层）→ 全局最新。
     */
    private fun onBlurCompleteBefore(chain: XposedInterface.Chain) {
        var layerName: String? = null
        var hw: HardwareBuffer? = null
        try {
            val winst = chain.getThisObject()
            if (winst != null) {
                layerName = reflectLayerNameField(winst)
                // [未模糊截取 2026-08-12] 优先 e()（onBlurPrepared，模糊输入前）截取的 f193k（未模糊）；
                // fallback b()（模糊完成）反射 f193k（此时可能已被系统写回模糊结果 → 间歇）。
                hw = if (layerName != null) originalBufferMap[layerName] else null
                if (hw != null && !hw.isClosed) {
                    Log.i(TAG, "onBlurComplete: using unblurred f193k (captured @prepared), layer=$layerName hw=${hw.width}x${hw.height}")
                } else {
                    Log.w(TAG, "onBlurComplete: prepared-captured unavailable, fallback reflect b() f193k")
                    hw = reflectOriginalBuffer(winst)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "onBlurComplete: reflect layer/original from w instance failed", t)
        }
        if (hw == null || hw.isClosed) {
            val latest = latestOriginalBuffer
            if (latest == null || latest.isClosed) {
                Log.w(TAG, "onBlurComplete: original buffer unavailable (null/closed), keep system blur result")
                pendingLayerName = null
                return
            }
            hw = latest
            if (layerName == null) layerName = latestLayerName
        }
        pendingOriginalBuffer = hw
        pendingLayerName = layerName
        Log.i(TAG, "onBlurComplete: original buffer pending, layer=$layerName, hw=${hw.width}x${hw.height}")
        // 确保 f.c.b hook 已安装（兜底延迟安装，防止 f.c 类加载时序早于 resolveSessionClasses）
        try {
            ensureEncodeHook(chain.getExecutable())
        } catch (t: Throwable) {
            Log.e(TAG, "onBlurComplete: ensureEncodeHook failed", t)
        }
    }

    /** 反射取 c.w 实例的未模糊原图（HardwareBuffer 字段 = f193k，RenderEngine 模糊输入，c/w.java:67）。
     *  遍历字段找 HardwareBuffer 类型，取第一个非 closed；失败/无 → null（调用方 fallback）。 */
    private fun reflectOriginalBuffer(winst: Any): HardwareBuffer? {
        var clazz: Class<*>? = winst.javaClass
        while (clazz != null && clazz != Any::class.java) {
            for (f in clazz.declaredFields) {
                if (f.type == HardwareBuffer::class.java) {
                    try {
                        if (!f.isAccessible) f.isAccessible = true
                        val v = f.get(winst) as? HardwareBuffer
                        if (v != null && !v.isClosed) return v
                    } catch (_: Throwable) {
                    }
                }
            }
            clazz = clazz.superclass
        }
        return null
    }

    /** 反射取 c.w 实例的层名字段：遍历字段找 String 类型（c.w 仅 f183a 一个 String 字段，
     *  c/w.java:37）。不依赖混淆字段名 f183a（jadx 重命名 ≠ 运行时真实名，会漂移）。 */
    private fun reflectLayerNameField(winst: Any): String? {
        var clazz: Class<*>? = winst.javaClass
        while (clazz != null && clazz != Any::class.java) {
            for (f in clazz.declaredFields) {
                if (f.type == String::class.java) {
                    try {
                        if (!f.isAccessible) f.isAccessible = true
                        val v = f.get(winst) as? String
                        if (v != null && v.isNotEmpty()) return v
                    } catch (_: Throwable) {
                    }
                }
            }
            clazz = clazz.superclass
        }
        return null
    }

    /** 兜底：f.c.b hook 未安装时延迟安装（用 c.w.b 方法的 classLoader） */
    private fun ensureEncodeHook(executable: Executable) {
        if (mountedEncodeMethod != null) return
        val api = apiRef ?: return
        try {
            hookBufferEncode(api, executable.declaringClass.classLoader ?: return)
        } catch (t: Throwable) {
            Log.e(TAG, "ensureEncodeHook failed", t)
        }
    }

    /** hook 3（f.c.b）：hookBefore 把 blurBuffers（args[2]）替换为 pendingOriginalBuffer 原图 */
    private fun hookBufferEncode(api: XposedInterface, classLoader: ClassLoader) {
        val already = mountedEncodeMethod
        if (already != null) return
        val clazz = try {
            Class.forName(CLASS_BLUR_BUFFER_ENCODER, false, classLoader)
        } catch (t: Throwable) {
            Log.w(TAG, "Hook A(BlurService) encode class $CLASS_BLUR_BUFFER_ENCODER not found", t)
            return
        }
        val method = clazz.declaredMethods.firstOrNull { m ->
            val p = m.parameterTypes
            p.size == 4 &&
                p[0] == Bundle::class.java &&
                p[1] == List::class.java &&
                p[2] == List::class.java &&
                p[3] == Int::class.javaPrimitiveType
        }
        if (method == null) {
            Log.w(TAG, "Hook A(BlurService) encode method b(Bundle, List, List, int) not found in ${clazz.name}")
            return
        }
        try {
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    replaceBlurBuffersWithOriginal(chain)
                }
            mountedEncodeMethod = method
            Log.i(TAG, "Hook A(BlurService) mounted encode: ${clazz.name}.b(Bundle, List, List, int)")
        } catch (t: Throwable) {
            Log.e(TAG, "Hook A(BlurService) encode mount FAILED: ${clazz.name}.b(Bundle, List, List, int)", t)
        }
    }

    /**
     * hook 3 hookBefore：把 f.c.b 的 args[2]（blurBuffers 列表）每个元素替换为原图。
     *
     * 场景过滤（修复 Launcher 文件夹自采样白块 + 跨层串图）：仅当当前模糊完成对应的层名
     * [pendingLayerName] 属于 SystemUI 场景（[isSystemUiLayer]）时才替换；Launcher 文件夹层
     * 与未知层名一律跳过 → [chain.proceed]() 保留系统模糊结果，同时避免文件夹层误用其它窗口原图。
     *
     * 引用计数：被替换的模糊结果 buffer 不 close（系统缓存仍引用）；原图引用直接塞 bundle，
     * Parcel 传输 SystemUI 侧自动 acquire。原图不可用（null/closed）→ 不替换（保留系统模糊结果）。
     */
    private fun replaceBlurBuffersWithOriginal(chain: XposedInterface.Chain): Any? {
        val original = pendingOriginalBuffer
        val layerName = pendingLayerName
        if (original == null || original.isClosed) {
            if (original == null) {
                Log.d(TAG, "cross-process: original unavailable, keep system blur result")
            }
            return chain.proceed()
        }
        // 场景过滤：非 SystemUI 场景（Launcher 文件夹层等）或层名未知 → 保留系统模糊结果。
        if (!isSystemUiLayer(layerName)) {
            Log.i(TAG, "cross-process: skip replace for layer=$layerName (non-SystemUI), keep system blur result")
            return chain.proceed()
        }
        val blurBuffers = try {
            chain.getArg(2) as? List<*>
        } catch (t: Throwable) {
            Log.e(TAG, "cross-process: getArg(2) failed", t)
            null
        }
        if (blurBuffers == null || blurBuffers.isEmpty()) {
            return chain.proceed()
        }
        // 构造替换列表：每个元素都是原图（f.c.b 内部 putParcelable 会把原图塞进 Bundle）
        val replaced = ArrayList<Any>(blurBuffers.size)
        for (i in 0 until blurBuffers.size) {
            replaced.add(original)
        }
        // LibXposed Chain 无 setArg，构造新参数数组走 proceed(newArgs)
        val args = chain.getArgs().toTypedArray()
        args[2] = replaced
        Log.i(TAG, "cross-process: original replaced for layer=$layerName, count=${blurBuffers.size}, hw=${original.width}x${original.height}")
        return chain.proceed(args)
    }

    /**
     * 场景白名单判断：层名是否属于 SystemUI 场景（才允许替换未模糊原图）。
     *
     * 规则（保守优先，判断不了就不替换）：
     * - null → false（层名未知，保守保留系统模糊，打日志）
     * - 含 "launcher"（忽略大小写）→ false（Launcher 桌面/文件夹背景层，保留系统模糊，
     *   避免文件夹 captureLayers 自采样白块 + 跨层串图）
     * - 含 "systemui" 或 "notificationshade"（忽略大小写）→ true（通知中心/控制中心等）
     * - 其它未知层名 → false（保守不替换，保留系统模糊结果）
     */
    private fun isSystemUiLayer(layerName: String?): Boolean {
        if (layerName == null) return false
        val name = layerName.lowercase(Locale.ROOT)
        if (name.contains("launcher")) return false
        return name.contains("systemui") || name.contains("notificationshade")
    }

    // ------------------------------------------------------------ 全分辨率原图（frameScale 1.0f）

    /**
     * 全分辨率原图：hook BlurService 抓屏封装 `e.a`，把 ScreenCapture.LayerCaptureArgs 的 frameScale
     * 从系统默认 0.25f 改为 1.0f（全分辨率，1440x3168）。
     *
     * 反编译实证（BlurService\sources\e\a.java）：
     * - `e.a.c(SurfaceControl, Float, Integer, Long)`：构造 LayerCaptureArgs（主抓屏路径，
     *   `a.d.c()` captureScreenInternal 传 frameScale=0.25f；内部
     *   `if (f2 != null) builder.setFrameScale(f2.floatValue())`，f2=null 时用 Builder 默认 1.0f）。
     * - `e.a.a(Object)`：captureLayers 入口（`ScreenCapture.captureLayers((LayerCaptureArgs) obj)`
     *   .getHardwareBuffer()），所有抓屏（含 `e.a.e` 反射兜底路径）最终都进这里。
     *
     * hook 策略（双保险，全 try-catch + PROTECTIVE）：
     * 1. 主 hook：`e.a.c` hookBefore 把 args[1]（Float frameScale）改为 1.0f → 系统 builder
     *    setFrameScale(1.0f)，全分辨率（主路径，覆盖所有 e.a.c 构造点：a.d.c() 0.25f / a.d.g() null）。
     * 2. 兜底 hook：`e.a.a` hookBefore 检查 args[0] 是 LayerCaptureArgs 且 frameScale != 1.0f 时，
     *    反射直接改字段为 1.0f（ColorOS16 无 mSurface 字段、Builder 拒绝 null layer，不重建）。
     * 任何失败 → 保留系统默认（0.25f），打日志，绝不崩 BlurService。
     *
     * 性能影响：所有 BlurService 抓屏（系统所有模糊 drawable 原图）都变全分辨率，内存/带宽开销大，
     * 用户已知并接受（先测）。坐标联动：SystemUI 端 BlurDrawHook.SCREEN_FRAME_SCALE=0.25f 折算
     * 待 M2b 一并改为 1.0f（本次不动）。
     */
    private fun mountFrameScaleHooks(api: XposedInterface, classLoader: ClassLoader) {
        if (mountedFrameScaleHooks) return
        val clazz = try {
            Class.forName(CLASS_CAPTURE_UTILS, false, classLoader)
        } catch (t: Throwable) {
            Log.w(TAG, "FrameScale: capture utils class $CLASS_CAPTURE_UTILS not found, full-res capture disabled", t)
            return
        }
        // 1) 主 hook：e.a.c(SurfaceControl, Float, Integer, Long) 构造 LayerCaptureArgs
        val createMethod = clazz.declaredMethods.firstOrNull { m ->
            val p = m.parameterTypes
            p.size == 4 &&
                p[0] == SurfaceControl::class.java &&
                p[1] == java.lang.Float::class.java &&
                p[2] == java.lang.Integer::class.java &&
                p[3] == java.lang.Long::class.java
        }
        if (createMethod == null) {
            Log.w(TAG, "FrameScale: create args method c(SurfaceControl, Float, Integer, Long) not found in $CLASS_CAPTURE_UTILS")
        } else {
            try {
                api.hook(createMethod)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val result = try {
                            forceFullFrameScaleOnArgs(chain)
                        } catch (t: Throwable) {
                            Log.e(TAG, "FrameScale: e.a.c intercept error, keep original args", t)
                            null
                        }
                        result ?: chain.proceed()
                    }
                Log.i(TAG, "FrameScale: mounted create-args hook: $CLASS_CAPTURE_UTILS.c(SurfaceControl, Float, Integer, Long)")
            } catch (t: Throwable) {
                Log.e(TAG, "FrameScale: mount create-args hook FAILED: $CLASS_CAPTURE_UTILS.c", t)
            }
        }
        // 2) 兜底 hook：e.a.a(Object) -> HardwareBuffer（captureLayers 入口，覆盖 e.a.e 反射路径）
        //    [自采样修复 v2 2026-08-12] 追加 oplus 整屏排除 shade 的干净原图替换（根治自采样磨砂）：
        //    反射调 OplusScreenCapture.captureDisplay(DisplayCaptureArgs excl=shade) 拿干净整屏
        //    HardwareBuffer，成功则替换 e.a.a 返回值（沿下游 f193k/c.s.b/f.c.b 原图通道回传 SystemUI）；
        //    任何失败 keep 原结果（PROTECTIVE 兜底，不影响 BlurService 抓屏链）。
        val captureMethod = clazz.declaredMethods.firstOrNull { m ->
            val p = m.parameterTypes
            p.size == 1 && p[0] == Any::class.java && m.returnType == HardwareBuffer::class.java
        }
        if (captureMethod == null) {
            Log.w(TAG, "FrameScale: capture method a(Object) not found in $CLASS_CAPTURE_UTILS")
        } else {
            try {
                api.hook(captureMethod)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        // [doc/spec/06] captureDisplay 整屏排除替换已移除（自采样根治 v2-v4 方向废弃），
                        // e.a.a 只做 frameScale 全分辨率兜底：forceFullFrameScaleOnCapture 内部可能已
                        // proceed（返回非 null）也可能未 proceed（frameScale 已 1.0 / 字段漂移，返回 null）。
                        var result: Any? = try {
                            forceFullFrameScaleOnCapture(chain)
                        } catch (t: Throwable) {
                            Log.e(TAG, "FrameScale: e.a.a intercept error, keep original args", t)
                            null
                        }
                        if (result == null) {
                            // 原逻辑 proceed 拿原返回值
                            result = try {
                                chain.proceed()
                            } catch (t: Throwable) {
                                Log.e(TAG, "FrameScale: e.a.a proceed failed, return null", t)
                                null
                            }
                        }
                        // [验证 dump] 缓存最新 captureLayers buffer（广播 com.coloros16.liquidglass.DUMP 触发 dump）
                        if (result is HardwareBuffer) latestRawCapture = result
                        result
                    }
                Log.i(TAG, "FrameScale: mounted capture-args hook: $CLASS_CAPTURE_UTILS.a(Object)")
            } catch (t: Throwable) {
                Log.e(TAG, "FrameScale: mount capture-args hook FAILED: $CLASS_CAPTURE_UTILS.a", t)
            }
        }
        mountedFrameScaleHooks = true
    }

    /** [验证 dump 2026-08-13] 最新原始 captureLayers buffer（e.a.a proceed 结果，广播触发 dump 用）。不 close（归系统）。 */
    @Volatile
    private var latestRawCapture: HardwareBuffer? = null

    /** [验证 dump] BlurService 进程广播 receiver：adb `am broadcast -a com.coloros16.liquidglass.DUMP` 触发 dump。
     *  与 SystemUI 端 BlurDrawHook 的 receiver 同 action（两进程各自收）。 */
    private fun registerDumpReceiver() {
        try {
            val ctx = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null) as? android.content.Context ?: run {
                Log.w(TAG, "capture-dump: ActivityThread.currentApplication() null")
                return
            }
            val filter = android.content.IntentFilter("com.coloros16.liquidglass.DUMP")
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                    dumpRawCapture()
                }
            }
            ctx.registerReceiver(receiver, filter)
            Log.i(TAG, "capture-dump: broadcast receiver mounted (adb: am broadcast -a com.coloros16.liquidglass.DUMP)")
        } catch (t: Throwable) {
            Log.e(TAG, "capture-dump: register receiver failed", t)
        }
    }

    private fun dumpRawCapture() {
        val hw = latestRawCapture ?: run {
            Log.w(TAG, "capture-dump: latestRawCapture null, nothing to dump")
            return
        }
        try {
            val bmp = Bitmap.wrapHardwareBuffer(hw, null)
            if (bmp != null) {
                Thread {
                    try {
                        val f = java.io.File("/data/user/0/com.oplus.blur/files/lg_capture_dump.png")
                        f.parentFile?.mkdirs()
                        java.io.FileOutputStream(f).use { out ->
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                        Log.i(TAG, "capture-dump: dumped original captureLayers $f ${bmp.width}x${bmp.height} layer=$latestLayerName")
                    } catch (t: Throwable) {
                        Log.e(TAG, "capture-dump: write failed", t)
                    }
                }.start()
            } else {
                Log.w(TAG, "capture-dump: wrapHardwareBuffer null")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "capture-dump: wrap failed", t)
        }
    }

    /** 主 hook hookBefore：e.a.c 的 args[1]（Float frameScale，可能 null/0.25f）→ 统一 1.0f */
    private fun forceFullFrameScaleOnArgs(chain: XposedInterface.Chain): Any? {
        val args = try {
            chain.getArgs().toTypedArray()
        } catch (t: Throwable) {
            Log.e(TAG, "FrameScale: getArgs failed", t)
            return null
        }
        if (args.size < 2) return null
        // [doc/spec/06] 自采样根治 v2-v4 的层判断/排除层缓存（currentShadeSfc/currentCaptureSfcName/
        // currentCaptureIsSysui）已随整屏排除替换移除，e.a.c 只负责 frameScale 全分辨率。
        val original = args[1]
        var result: Any?
        if (original == null || original == 1.0f) {
            // null：系统 Builder 默认 frameScale 本就 1.0f，无需改；1.0f：已是全分辨率
            result = chain.proceed(args)
        } else {
            args[1] = 1.0f
            Log.i(TAG, "FrameScale: create-args frameScale $original -> 1.0f (full-res original)")
            result = chain.proceed(args)
        }
        // [doc/spec/06] 排除自身层/整屏排除替换（rebuildWithSelfExclude / oplus 整屏排除）均已移除，
        // e.a.a 只做 frameScale 全分辨率。
        return result
    }

    /** 兜底 hook hookBefore：e.a.a 的 args[0]（LayerCaptureArgs）frameScale != 1.0f 时兜底为全分辨率。
     *  android.window.ScreenCapture$LayerCaptureArgs 是 @SystemApi，compileSdk android.jar 不可直接引用，
     *  全反射读写字段。任何反射失败 → 保留原 args（系统默认），打日志。
     *
     *  surface null 路径根因（2026-08-11，真机 16.1.48 field dump 实证）：
     *  ColorOS16 / Android16 的 ScreenCapture 已重构——`mSurface` 字段**不存在**（被 `mNativeLayer`
     *  long native handle 替代），`mFrameScale` 拆为 **`mFrameScaleX/mFrameScaleY`**。旧实现反射读
     *  `mSurface`/`mFrameScale` 全部返回 null → 误判 "surface null" 并 keep（日志极频繁）；实测 args
     *  的 `mFrameScaleX=1.0/mFrameScaleY=1.0`（主 hook e.a.c 已把 frameScale 改 1.0），分辨率本就是
     *  全分辨率，`keep original` 不产生 1/4 buffer。
     *
     *  Builder 重建在 ColorOS16 不可行（field dump 实证）：LayerCaptureArgs 无 SurfaceControl 字段
     *  （只有 mNativeLayer），且 `Builder.build()` 对 null surface 抛 `Can't take screenshot with
     *  null layer`。故本 hook 不再 Builder 重建，改为**反射直接改字段**：
     *  1. 读 `mFrameScaleX/mFrameScaleY`（兼容旧 `mFrameScale`）。
     *  2. 已是 (1.0, 1.0) → 直接返回（无日志噪音）。
     *  3. 非 1.0 → 反射 set 字段为 1.0f（原地改 args，复用原参数 proceed）；失败 keep original。
     *  4. 字段名全漂移读不到 → 保持原样（主 hook 已统一）+ 一次性字段 dump 诊断。 */
    private fun forceFullFrameScaleOnCapture(chain: XposedInterface.Chain): Any? {
        val obj = try {
            chain.getArg(0)
        } catch (t: Throwable) {
            Log.e(TAG, "FrameScale: getArg(0) failed", t)
            return null
        }
        val argsCls = obj?.javaClass ?: return null
        if (argsCls.name != CLASS_LAYER_CAPTURE_ARGS) {
            return null  // 非 LayerCaptureArgs，不处理
        }
        // [doc/spec/06] 排除自身层逻辑（applySelfExclude / oplus 整屏排除）已随方向废弃移除，
        // 此处仅保留一次性字段 dump 诊断。
        logCaptureArgsFieldsOnce(argsCls, obj)
        // 读取 X/Y frameScale（ColorOS16/Android16 新字段 mFrameScaleX/Y；兼容旧 mFrameScale）。
        // field dump 实证（16.1.48）：mFrameScaleX=1.0, mFrameScaleY=1.0（主 hook 已改）。
        val scaleX = try {
            (reflectField(argsCls, obj, "mFrameScaleX") as? Float)
        } catch (t: Throwable) {
            null
        }
        val scaleY = try {
            (reflectField(argsCls, obj, "mFrameScaleY") as? Float)
        } catch (t: Throwable) {
            null
        }
        val scaleOld = try {
            (reflectField(argsCls, obj, "mFrameScale") as? Float)
        } catch (t: Throwable) {
            null
        }
        if (scaleX == null && scaleY == null && scaleOld == null) {
            // 字段名全漂移：无法判断分辨率，保持原样（主 hook e.a.c 已统一 frameScale）。
            // 一次性打印字段名列表，确认字段名（只打一次防高频刷屏）。
            logCaptureArgsFieldsOnce(argsCls, obj)
            return null
        }
        val x = scaleX ?: scaleOld
        val y = scaleY ?: scaleOld
        if (x != null && y != null && x == 1.0f && y == 1.0f) {
            return null  // 已全分辨率，无需处理（避免日志噪音）
        }
        // 非全分辨率：反射直接改字段（LayerCaptureArgs 无 SurfaceControl 字段、Builder 拒绝 null
        // layer，重建不可行）。改字段失败 → keep original（fallback 安全）。
        var changed = false
        if (scaleX != null && scaleX != 1.0f) {
            changed = setReflectField(argsCls, obj, "mFrameScaleX", FRAME_SCALE_FULL) || changed
        }
        if (scaleY != null && scaleY != 1.0f) {
            changed = setReflectField(argsCls, obj, "mFrameScaleY", FRAME_SCALE_FULL) || changed
        }
        if (scaleOld != null && scaleOld != 1.0f && scaleX == null && scaleY == null) {
            changed = setReflectField(argsCls, obj, "mFrameScale", FRAME_SCALE_FULL) || changed
        }
        if (changed) {
            Log.i(TAG, "FrameScale: set CaptureArgs frameScale ($x, $y) -> (1.0, 1.0) via field reflect (full-res original)")
            return chain.proceed()  // 原地改字段，复用原 args
        }
        Log.w(TAG, "FrameScale: frameScale ($x, $y) not 1.0 but field set failed, keep original")
        return null
    }

    /** 反射写字段（含父类链），成功返回 true。Android ART 允许对 final 实例字段反射写；
     *  任何失败返回 false（由调用方 keep original）。 */
    private fun setReflectField(clazz: Class<*>, instance: Any, name: String, value: Any): Boolean {
        var c: Class<*>? = clazz
        while (c != null) {
            try {
                val f = c.getDeclaredField(name)
                f.isAccessible = true
                f.set(instance, value)
                return true
            } catch (t: Throwable) {
                c = c.superclass
            }
        }
        return false
    }

    /** 一次性诊断日志：frameScale 字段名全漂移读不到时打印 LayerCaptureArgs 全部字段名（含父类）
     *  与值，确认 ColorOS framework 实际字段名（只打一次，防高频刷屏）。 */
    @Volatile
    private var captureArgsFieldsLogged = false

    private fun logCaptureArgsFieldsOnce(clazz: Class<*>, instance: Any) {
        if (captureArgsFieldsLogged) return
        captureArgsFieldsLogged = true
        try {
            val sb = StringBuilder("FrameScale: LayerCaptureArgs field dump (class=").append(clazz.name).append("):")
            var c: Class<*>? = clazz
            while (c != null) {
                for (f in c.declaredFields) {
                    val v = try {
                        f.isAccessible = true
                        val value = f.get(instance)
                        if (value != null) "${value.javaClass.simpleName}($value)" else "null"
                    } catch (t: Throwable) {
                        "<unreadable:${t.javaClass.simpleName}>"
                    }
                    sb.append(" [").append(c.name).append("].").append(f.name).append("=").append(v)
                }
                c = c.superclass
            }
            Log.w(TAG, sb.toString())
        } catch (t: Throwable) {
            Log.e(TAG, "FrameScale: field dump failed", t)
        }
    }

    /** 反射读字段（含父类链）：LayerCaptureArgs 的 mSurface 在子类，mFrameScale/mPixelFormat/mUid 在父类 CaptureArgs */
    private fun reflectField(clazz: Class<*>, instance: Any, name: String): Any? {
        var c: Class<*>? = clazz
        while (c != null) {
            try {
                val f = c.getDeclaredField(name)
                f.isAccessible = true
                return f.get(instance)
            } catch (t: Throwable) {
                c = c.superclass
            }
        }
        return null
    }
}
