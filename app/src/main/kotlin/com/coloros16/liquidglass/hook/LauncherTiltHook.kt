package com.coloros16.liquidglass.hook

import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import com.coloros16.liquidglass.config.Prefs
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/**
 * [doc/spec/65 iOS 动态倾斜/透视 2026-09-16] Launcher 进程：应用启动/退出/打断动画期间的 3D 倾斜。
 *
 * **实验性功能，默认关闭**（[Prefs.KEY_ANIM_TILT_ENABLED] = false），关闭时零干预。
 *
 * 实现方式：LibXposed hook 原版类，**不修改系统 APK**。对照修改版 ZuyQA 16.6.20 的 `ZuyqaWindowTilt`
 * 语义等价实现 —— 修改版给原版类**新增方法/字段**（`SurfaceProperties.zuyqaSetTilt`、
 * `RectTransformHelper.mZuyqaTilt` + 7 个方法），而 Xposed **无法给已有类添加新方法/字段**，
 * 故本实现用**模块侧旁路存储**等价替代：
 *
 * | 修改版 | 本模块等价物 |
 * |--------|-------------|
 * | `RectTransformHelper.mZuyqaTilt` 字段 | [paramsToTilt]（TransformParams → WindowTilt 弱引用表） |
 * | `ZuyqaWindowTilt.bind(params, tilt)` | 同上，`paramsToTilt[params] = tilt` |
 * | `SurfaceProperties.zuyqaSetTilt(float[])` | [applyTilt]（反射调用原版 `SurfaceProperties#setMatrix4x4(float[])`） |
 * | `ZuyqaWindowTilt.sByTask` / `sOwners` | [taskToTilt] / [tiltedSurfaces] |
 * | 6 处 `zuyqaConfigureTilt()` 调用 | hook 各 lambda 内部类的**构造函数**（`proceed()` 后读 `$` 合成字段） |
 *
 * hook 点（全部 Launcher 进程）：
 * 1. 6 个动画配置 lambda 的构造函数（见 [CONFIGURE_SITES]）→ 建 tilt + 绑 params
 * 2. `RectTransformHelper#applySurfaceParams$lambda*`（静态合成，**按名字前缀 + 参数表匹配**，
 *    因为 lambda 序号在不同版本不同：原版 16.6.11 是 `$lambda$8`，修改版 16.6.20 是 `$lambda$9`）
 * 3. `RectTransformHelper#applyThumbSurfaceParams$lambda*`
 * 4. `IconLayerUpdater#updateIconLayer(TransformParams, boolean, RectF, SurfaceControl, SurfaceTransaction, boolean, Matrix)`
 * 5. `OplusBaseSwipeUpHandler#createBreakAppOpenAnim(AnimationRecord)` + `TransformParams#<init>()`
 *    （打断动画的 tilt 接力；修改版把 `snapshotRecord`/`zuyqaContinueBreak` 插在方法中部，
 *    本实现用「挂起槽位 + 捕获紧随其后的 TransformParams 构造」等价）
 *
 * 6. `AppLaunchAnimUtil$getLightWindowAnimation$1#onUpdate$lambda*`（轻动画通用通道，静态合成，
 *    16 参）→ 通用单例 tilt（等价修改版 `sGeneric`），方向由 `args[5]`(y) 与 rect 高推导
 * 7. 动画结束清理（等价修改版 `watch` → AnimatorListener → postDelayed(32ms) → `cleanupOwnedSurfaces`）：
 *    在配置点反射调用原版 public `CustomRectFSpringAnim#addAnimatorListener(NullableAnimatorListener)`，
 *    传入动态代理监听器；结束/取消时把本 tilt 拥有的 surface 矩阵复位为单位矩阵
 *    （用新建的原版 `SurfaceTransaction` + `getTransaction().apply()`，**全程无 framework 隐藏 API**）。
 *
 * 安全：全部 try-catch 全包 + [ExceptionMode.PROTECTIVE]；默认关；类/方法解析失败只禁用本功能。
 * 日志 tag [TAG]（`lg-ltilt`），受模块「模块日志」开关控制。
 */
object LauncherTiltHook {

    private const val TAG = "lg-ltilt"

    // ---- 目标类 ----
    private const val CLASS_RECT_TRANSFORM_HELPER = "com.android.quickstep.util.animation.RectTransformHelper"
    private const val CLASS_TRANSFORM_PARAMS = "com.android.quickstep.util.animation.RectTransformHelper\$TransformParams"
    private const val CLASS_ICON_LAYER_UPDATER = "com.android.quickstep.util.animation.IconLayerUpdater"
    private const val CLASS_SURFACE_PROPERTIES = "com.android.quickstep.util.SurfaceTransaction\$SurfaceProperties"
    private const val CLASS_SWIPE_UP_HANDLER = "com.oplus.quickstep.gesture.OplusBaseSwipeUpHandler"
    private const val CLASS_ANIMATION_RECORD = "com.android.quickstep.util.animation.AnimationRecord"
    private const val CLASS_TARGET_COMPAT = "com.android.systemui.shared.system.RemoteAnimationTargetCompat"
    private const val CLASS_THUMB_UTILS = "com.android.launcher3.anim.ThumbSurfaceControlUtils"
    private const val CLASS_APP_LAUNCH_ANIM_LIGHT =
        "com.android.launcher3.anim.light.AppLaunchAnimUtil\$getLightWindowAnimation\$1"
    private const val CLASS_NULLABLE_ANIMATOR_LISTENER = "com.android.launcher3.anim.NullableAnimatorListener"
    private const val CLASS_SURFACE_TRANSACTION = "com.android.quickstep.util.SurfaceTransaction"
    private const val CLASS_SPRING_ANIM = "com.android.quickstep.util.animation.CustomRectFSpringAnim"

    // ---- 方法/字段名（原版 16.6.11 与修改版 16.6.20 双侧 smali 实证一致） ----
    private const val METHOD_SET_MATRIX_4X4 = "setMatrix4x4"
    private const val METHOD_GET_PROGRESS = "getProgress"
    private const val METHOD_GET_IS_TASK_VIEW = "getIsTaskView"
    private const val METHOD_UPDATE_ICON_LAYER = "updateIconLayer"
    private const val METHOD_CREATE_BREAK_APP_OPEN_ANIM = "createBreakAppOpenAnim"
    private const val METHOD_GET_ANIM_TOP_TASK_ID = "getAnimTopTaskId"
    private const val METHOD_GET_M_IS_TASK_VIEW = "getMIsTaskView"
    private const val METHOD_FOR_SURFACE = "forSurface"
    private const val METHOD_GET_S_APP_THUMB = "getSAppThumbSurfaceControl"
    private const val METHOD_ADD_ANIMATOR_LISTENER = "addAnimatorListener"
    private const val METHOD_GET_TRANSACTION = "getTransaction"
    private const val PREFIX_APPLY_SURFACE_LAMBDA = "applySurfaceParams\$lambda"
    private const val PREFIX_APPLY_THUMB_LAMBDA = "applyThumbSurfaceParams\$lambda"
    /** 轻动画 `onUpdate$lambda$0`（静态合成，16 参：实测 smali 参数表见 doc/spec/65 §3.4） */
    private const val PREFIX_ON_UPDATE_LAMBDA = "onUpdate\$lambda"
    private const val ON_UPDATE_LAMBDA_PARAM_COUNT = 16
    /** `onUpdate$lambda$0` 中 4 个可用实参的下标（smali 实证：p0=target, p1=transaction, p5=y, p12=progress） */
    private const val ARG_TARGET = 0
    private const val ARG_TRANSACTION = 1
    private const val ARG_Y = 5
    private const val ARG_PROGRESS = 12
    private const val FIELD_M_CLIP_RECT = "mClipRect"
    private const val FIELD_M_SOURCE_WINDOW_RECT = "mSourceWindowRect"
    private const val FIELD_M_ICON_LAYER_CROP = "mIconLayerCrop"
    private const val FIELD_LEASH = "leash"
    private const val FIELD_TASK_ID = "taskId"
    private const val FIELD_SCREEN_SPACE_BOUNDS = "screenSpaceBounds"
    private const val FIELD_M_SURFACE = "mSurface"
    private const val FIELD_REF_ELEMENT = "element"
    /** 清理延迟毫秒（照抄修改版 `onAnimationEnd` 的 `postDelayed(this, 32L)`） */
    private const val CLEANUP_DELAY_MS = 32L
    /** 被取消时清理重试的间隔毫秒（照抄修改版 `run()` 里重新走 `onAnimationEnd` 的节奏） */
    private const val CLEANUP_FRAME_SETTLE_RETRY_MS = 32L
    /** 被取消时的清理重试上限（修改版为无限重试；本实现加界防 post 死循环，超限强制清理） */
    private const val CLEANUP_MAX_RETRY = 10

    // ---- 倾斜算法常数（照抄 ZuyqaWindowTilt） ----
    private const val TILT_MAX_ANGLE = 0.62831855f
    private const val TILT_ARC_SCALE = 1.2566371f
    private const val PERSPECTIVE_BASE = 1.2f
    private const val CLOSING_BOOST = 1.25f
    private const val CARRY_TIMEOUT_MS = 1000L
    private const val CARRY_FADE_MS = 220f
    private const val CONFIG_TTL_MS = 250L

    // ---- 运行态 ----
    @Volatile
    private var api: XposedInterface? = null

    @Volatile
    private var logEnabled = false

    /** TransformParams → WindowTilt（= 修改版 `ZuyqaWindowTilt.sByParams` + `RectTransformHelper.mZuyqaTilt`） */
    private val paramsToTilt: MutableMap<Any, WindowTilt> =
        Collections.synchronizedMap(WeakHashMap<Any, WindowTilt>())

    /** taskId → WindowTilt（= 修改版 `sByTask` LRU，跨动画角度接力） */
    private val taskToTilt: MutableMap<Int, WeakReference<WindowTilt>> =
        Collections.synchronizedMap(object : LinkedHashMap<Int, WeakReference<WindowTilt>>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, WeakReference<WindowTilt>>?): Boolean =
                size > 32
        })

    /** 打断动画挂起槽：`createBreakAppOpenAnim` 内建好、等紧随其后的 TransformParams 构造来绑定 */
    @Volatile
    private var pendingBreakTilt: WindowTilt? = null

    @Volatile
    private var pendingBreakThreadId = -1

    /** 轻动画通用 tilt 单例（= 修改版 `ZuyqaWindowTilt.sGeneric`） */
    @Volatile
    private var genericTilt: WindowTilt? = null

    /** 主线程 Handler（清理延迟投递用，与修改版 `new Handler(Looper.getMainLooper())` 等价） */
    @Volatile
    private var mainHandler: android.os.Handler? = null

    // ---- 反射缓存 ----
    private var mSetMatrix4x4: Method? = null
    private var mGetProgress: Method? = null
    private var mGetIsTaskView: Method? = null
    private var mForSurface: Method? = null
    private var mGetSAppThumb: Method? = null
    private var fMClipRect: Field? = null
    private var fMSourceWindowRect: Field? = null
    private var fMIconLayerCrop: Field? = null
    private var fLeash: Field? = null
    private var fScreenSpaceBounds: Field? = null
    private var fMSurface: Field? = null
    private var fRefElement: Field? = null
    private var mAddAnimatorListener: Method? = null
    private var mGetTransaction: Method? = null
    private var mSurfaceTransactionCtor: java.lang.reflect.Constructor<*>? = null
    private var nullableAnimatorListenerCls: Class<*>? = null

    /** 单位 4x4 矩阵（复位用，等价修改版 `sIdentity`） */
    private val identity4x4 = FloatArray(16).also {
        it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f
    }

    // ---- 配置 ----
    private class TiltConfig(val enabled: Boolean, val strength: Float, val perspective: Float)

    @Volatile
    private var cfg = TiltConfig(false, 1f, 1f)

    @Volatile
    private var cfgReadAtMs = 0L

    // ------------------------------------------------------------ 入口

    fun install(api: XposedInterface, classLoader: ClassLoader) {
        this.api = api
        logEnabled = try {
            Prefs.read(api).getBoolean(Prefs.KEY_ENABLE_LOGS, Prefs.DEFAULT_ENABLE_LOGS)
        } catch (t: Throwable) {
            false
        }
        resolveReflection(classLoader)
        mountConfigureSites(api, classLoader)
        mountApplyLambdas(api, classLoader)
        mountIconLayerUpdater(api, classLoader)
        mountBreakCarry(api, classLoader)
        mountLightWindowPath(api, classLoader)
        Log.i(
            TAG,
            "LauncherTiltHook install done (matrix4x4=${mSetMatrix4x4 != null}, " +
                "listener=${mAddAnimatorListener != null}, " +
                "cleanupTx=${mSurfaceTransactionCtor != null && mGetTransaction != null})",
        )
    }

    // ------------------------------------------------------------ 反射解析

    private fun resolveReflection(classLoader: ClassLoader) {
        try {
            val props = Class.forName(CLASS_SURFACE_PROPERTIES, false, classLoader)
            mSetMatrix4x4 = props.getMethod(METHOD_SET_MATRIX_4X4, FloatArray::class.java)
            Log.i(TAG, "resolved $CLASS_SURFACE_PROPERTIES#$METHOD_SET_MATRIX_4X4(float[])")
        } catch (t: Throwable) {
            Log.e(TAG, "resolve setMatrix4x4 failed, tilt apply disabled", t)
        }
        try {
            val params = Class.forName(CLASS_TRANSFORM_PARAMS, false, classLoader)
            mGetProgress = params.getMethod(METHOD_GET_PROGRESS)
            mGetIsTaskView = params.getMethod(METHOD_GET_IS_TASK_VIEW)
            Log.i(TAG, "resolved TransformParams getProgress/getIsTaskView")
        } catch (t: Throwable) {
            Log.e(TAG, "resolve TransformParams accessors failed, tilt update disabled", t)
        }
        try {
            val helper = Class.forName(CLASS_RECT_TRANSFORM_HELPER, false, classLoader)
            fMClipRect = helper.getDeclaredField(FIELD_M_CLIP_RECT).apply { isAccessible = true }
            fMSourceWindowRect = helper.getDeclaredField(FIELD_M_SOURCE_WINDOW_RECT).apply { isAccessible = true }
            Log.i(TAG, "resolved RectTransformHelper mClipRect/mSourceWindowRect")
        } catch (t: Throwable) {
            Log.e(TAG, "resolve RectTransformHelper rect fields failed, applySurfaceParams tilt disabled", t)
        }
        try {
            val updater = Class.forName(CLASS_ICON_LAYER_UPDATER, false, classLoader)
            fMIconLayerCrop = updater.getDeclaredField(FIELD_M_ICON_LAYER_CROP).apply { isAccessible = true }
            Log.i(TAG, "resolved IconLayerUpdater.mIconLayerCrop")
        } catch (t: Throwable) {
            Log.e(TAG, "resolve IconLayerUpdater field failed, icon tilt disabled", t)
        }
        try {
            val tx = Class.forName("com.android.quickstep.util.SurfaceTransaction", false, classLoader)
            mForSurface = tx.getMethod(METHOD_FOR_SURFACE, android.view.SurfaceControl::class.java)
        } catch (t: Throwable) {
            Log.e(TAG, "resolve SurfaceTransaction.forSurface failed, tilt apply disabled", t)
        }
        try {
            val thumb = Class.forName(CLASS_THUMB_UTILS, false, classLoader)
            mGetSAppThumb = thumb.getMethod(METHOD_GET_S_APP_THUMB)
        } catch (t: Throwable) {
            Log.e(TAG, "resolve ThumbSurfaceControlUtils failed, thumb tilt disabled", t)
        }
        try {
            val target = Class.forName(CLASS_TARGET_COMPAT, false, classLoader)
            fLeash = target.getField(FIELD_LEASH)
            fScreenSpaceBounds = target.getField(FIELD_SCREEN_SPACE_BOUNDS)
        } catch (t: Throwable) {
            Log.e(TAG, "resolve RemoteAnimationTargetCompat fields failed", t)
        }
        // 清理路径（缺口 2）：SurfaceProperties.mSurface + 原版 SurfaceTransaction 新建事务 + getTransaction().apply()
        try {
            val props = Class.forName(CLASS_SURFACE_PROPERTIES, false, classLoader)
            fMSurface = props.getDeclaredField(FIELD_M_SURFACE).apply { isAccessible = true }
        } catch (t: Throwable) {
            Log.e(TAG, "resolve SurfaceProperties.mSurface failed, tilt cleanup disabled", t)
        }
        try {
            val st = Class.forName(CLASS_SURFACE_TRANSACTION, false, classLoader)
            mSurfaceTransactionCtor = st.getConstructor()
            mGetTransaction = st.getMethod(METHOD_GET_TRANSACTION)
        } catch (t: Throwable) {
            Log.e(TAG, "resolve SurfaceTransaction ctor/getTransaction failed, tilt cleanup disabled", t)
        }
        try {
            val spring = Class.forName(CLASS_SPRING_ANIM, false, classLoader)
            nullableAnimatorListenerCls = Class.forName(CLASS_NULLABLE_ANIMATOR_LISTENER, false, classLoader)
            mAddAnimatorListener = spring.getMethod(METHOD_ADD_ANIMATOR_LISTENER, nullableAnimatorListenerCls)
        } catch (t: Throwable) {
            Log.e(TAG, "resolve addAnimatorListener failed, tilt cleanup disabled", t)
        }
        try {
            fRefElement = Class.forName("kotlin.jvm.internal.Ref\$ObjectRef", false, classLoader)
                .getField(FIELD_REF_ELEMENT)
        } catch (t: Throwable) {
            Log.e(TAG, "resolve Ref.ObjectRef.element failed, watch skipped", t)
        }
    }

    // ------------------------------------------------------------ Hook：6 个配置点

    /** 单个配置点：类名 + 各 `$` 字段名（null = 该点没有此字段）。 */
    private class ConfigureSite(
        val className: String,
        val helperField: String,
        val startRectField: String?,
        val secondRectField: String?,
        val paramsField: String?,
        val markClosing: Boolean,
        val carryTargetsField: String?,
        /** `Ref.ObjectRef` 字段名，其 `element` = 该动画的 CustomRectFSpringAnim（null = 该点不 watch） */
        val springAnimField: String?,
        /** params 字段的备用名（打断动画 lambda 有 `$surfaceParam` / `$transformParams` 两个，按命中取） */
        val paramsFieldAlt: String? = null,
        /** true = 本点是「建 tilt + 绑 params」的配置点；false = 只反查已有 tilt（打断动画 watch-only 点） */
        val createTilt: Boolean = true,
    )

    private val CONFIGURE_SITES = listOf(
        // 原版 16.6.11 与修改版 16.6.20 合成字段名双侧实证一致
        ConfigureSite(
            "com.android.launcher3.anim.OplusLauncherAppTransitionHelper\$startAppLaunchWindowAnim\$7",
            "\$rectTransformHelper", "\$startRect", "\$screenRectF", "\$surfaceParams", false, null,
            "\$rectFSpringAnim",
        ),
        ConfigureSite(
            "com.android.launcher3.anim.OplusLauncherAppTransitionHelper\$startAppCloseWindowAnim\$8",
            "\$rectTransformHelper", "\$targetRect", "\$screenRectF", "\$surfaceParams", true, null,
            "\$rectFSpringAnim",
        ),
        // IntegrationRemoteAnimManager 两处：修改版只 configureTilt、不 watch（保持一致）
        ConfigureSite(
            "com.oplus.quickstep.integration.IntegrationRemoteAnimManager\$startAppLaunchAnim\$4",
            "\$rectTransformHelper", "\$startRectF", "\$targetRectF", "\$transformParams", false, null,
            null,
        ),
        ConfigureSite(
            "com.oplus.quickstep.integration.IntegrationRemoteAnimManager\$startAppCloseAnimIfNeeded\$6",
            "\$rectTransformHelper", "\$targetRectF", "\$startRectF", "\$transformParams", true, null,
            null,
        ),
        ConfigureSite(
            "com.oplus.quickstep.gesture.OplusBaseSwipeUpHandler\$createAppToHomeAnimation\$5",
            "\$rectTransformHelper", "\$targetRect", "\$screenRectF", "\$surfaceParams", true, "\$targets",
            "\$rectFSpringAnim",
        ),
        // 打断动画 lambda：只 watch（tilt 接力在 OplusBaseSwipeUpHandler#createBreakAppOpenAnim 里完成绑定）
        ConfigureSite(
            "com.oplus.quickstep.gesture.OplusBaseSwipeUpHandler\$createBreakAppOpenAnim\$4",
            "\$transformHelper", null, null, "\$surfaceParam", false, null,
            "\$breakAppOpenAnim", "\$transformParams", createTilt = false,
        ),
    )

    private fun mountConfigureSites(api: XposedInterface, classLoader: ClassLoader) {
        for (site in CONFIGURE_SITES) {
            try {
                val clazz = Class.forName(site.className, false, classLoader)
                val ctor = clazz.declaredConstructors.firstOrNull() ?: run {
                    Log.w(TAG, "no constructor on ${site.className}, tilt configure skipped")
                    null
                } ?: continue
                ctor.isAccessible = true
                // 字段句柄提前解析（构造后直接读，避免热路径反射查字段）
                val helperF = tryField(clazz, site.helperField)
                val startF = site.startRectField?.let { tryField(clazz, it) }
                val secondF = site.secondRectField?.let { tryField(clazz, it) }
                val paramsF = site.paramsField?.let { tryField(clazz, it) }
                val paramsAltF = site.paramsFieldAlt?.let { tryField(clazz, it) }
                val carryF = site.carryTargetsField?.let { tryField(clazz, it) }
                val springAnimF = site.springAnimField?.let { tryField(clazz, it) }
                api.hook(ctor)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val result = chain.proceed()
                        try {
                            if (config().enabled) {
                                val self = chain.getThisObject()
                                // 打断动画 lambda 没有 helper/起始 rect：tilt 已在 createBreakAppOpenAnim
                                // 建好并在 TransformParams 构造时绑定，这里按两个候选 params 字段反查
                                val existing = listOfNotNull(paramsF, paramsAltF)
                                    .firstNotNullOfOrNull { f -> f.get(self)?.let { key -> paramsToTilt[key] } }
                                val tilt = if (existing != null) {
                                    existing
                                } else if (site.createTilt && helperF != null && paramsF != null) {
                                    val created = WindowTilt(
                                        startF?.get(self) as? RectF,
                                        secondF?.get(self) as? RectF,
                                    )
                                    bindTilt(paramsF.get(self), created)
                                    if (site.markClosing) created.setClosingBoost()
                                    if (carryF != null) {
                                        @Suppress("UNCHECKED_CAST")
                                        created.carryFromTargets(carryF.get(self) as? Array<Any?>)
                                    }
                                    created
                                } else {
                                    null
                                }
                                // 缺口 2：注册结束清理（等价修改版 zuyqaWatchTilt）
                                if (tilt != null && springAnimF != null) {
                                    watch(springAnimF.get(self), tilt)
                                }
                                logD { "configure ${site.className} bound=${tilt != null}" }
                            }
                        } catch (t: Throwable) {
                            Log.e(TAG, "configure site ${site.className} intercept error", t)
                        }
                        result
                    }
                Log.i(TAG, "mounted configure: ${site.className}<init>")
            } catch (t: Throwable) {
                Log.e(TAG, "mount configure FAILED: ${site.className}", t)
            }
        }
    }

    private fun tryField(clazz: Class<*>, name: String): Field? = try {
        clazz.getDeclaredField(name).apply { isAccessible = true }
    } catch (t: Throwable) {
        Log.e(TAG, "resolve field $name on ${clazz.name} failed", t)
        null
    }

    // ------------------------------------------------------------ Hook：RectTransformHelper 的 apply lambda

    private fun mountApplyLambdas(api: XposedInterface, classLoader: ClassLoader) {
        val helperCls = try {
            Class.forName(CLASS_RECT_TRANSFORM_HELPER, false, classLoader)
        } catch (t: Throwable) {
            Log.e(TAG, "mount apply lambdas FAILED: class not found", t)
            return
        }
        for (m in helperCls.declaredMethods) {
            val isSurface = m.name.startsWith(PREFIX_APPLY_SURFACE_LAMBDA) && m.parameterCount == 8
            val isThumb = m.name.startsWith(PREFIX_APPLY_THUMB_LAMBDA) && m.parameterCount == 4
            if (!isSurface && !isThumb) continue
            try {
                m.isAccessible = true
                api.hook(m)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val result = chain.proceed()
                        try {
                            val args = chain.getArgs()
                            if (isSurface) {
                                // (RemoteAnimationTargetCompat, RectTransformHelper, SurfaceTransaction,
                                //  TransformParams, boolean, BreakParam, float, RectF)
                                applyForTarget(
                                    target = args?.getOrNull(0),
                                    helper = args?.getOrNull(1),
                                    transaction = args?.getOrNull(2),
                                    params = args?.getOrNull(3),
                                )
                            } else {
                                // (RectTransformHelper, TransformParams, RectF, SurfaceTransaction)
                                applyForThumb(
                                    params = args?.getOrNull(1),
                                    transaction = args?.getOrNull(3),
                                )
                            }
                        } catch (t: Throwable) {
                            Log.e(TAG, "apply lambda ${m.name} intercept error", t)
                        }
                        result
                    }
                Log.i(TAG, "mounted apply lambda: RectTransformHelper#${m.name}")
            } catch (t: Throwable) {
                Log.e(TAG, "mount apply lambda FAILED: ${m.name}", t)
            }
        }
    }

    // ------------------------------------------------------------ Hook：IconLayerUpdater

    private fun mountIconLayerUpdater(api: XposedInterface, classLoader: ClassLoader) {
        if (fMIconLayerCrop == null) {
            Log.w(TAG, "mIconLayerCrop unresolved, icon layer tilt skipped")
            return
        }
        try {
            val clazz = Class.forName(CLASS_ICON_LAYER_UPDATER, false, classLoader)
            val method = clazz.declaredMethods.firstOrNull {
                it.name == METHOD_UPDATE_ICON_LAYER && it.parameterCount == 7
            } ?: run {
                Log.w(TAG, "updateIconLayer not found, icon layer tilt skipped")
                null
            } ?: return
            method.isAccessible = true
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    try {
                        if (config().enabled) {
                        val args = chain.getArgs()
                        val params = args?.getOrNull(0)
                        val transaction = args?.getOrNull(4)
                        val surface = args?.getOrNull(3) as? android.view.SurfaceControl
                        val rect = fMIconLayerCrop?.get(chain.getThisObject()) as? Rect
                        updateForParams(params, surfacePropertiesOf(transaction, surface), rect)
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "updateIconLayer intercept error", t)
                    }
                    result
                }
            Log.i(TAG, "mounted: $CLASS_ICON_LAYER_UPDATER#$METHOD_UPDATE_ICON_LAYER")
        } catch (t: Throwable) {
            Log.e(TAG, "mount updateIconLayer FAILED", t)
        }
    }

    // ------------------------------------------------------------ Hook：轻动画通用通道（缺口 1）

    /**
     * `AppLaunchAnimUtil$getLightWindowAnimation$1#onUpdate$lambda$0`（静态合成，16 参）。
     *
     * smali 实证（原版 16.6.11 与修改版 16.6.20 一致）：方法内
     * ```
     * v1 = transaction.forSurface(target.leash)          // SurfaceProperties
     * v5 = new Rect(target.screenSpaceBounds); offsetTo(0,0)
     * v6 = p12 (progress) ; v0 = p5 (y)
     * invoke-virtual {v1, matrix}, SurfaceProperties->setMatrix(Matrix)
     * invoke-static {v1, v5, v6, v0}, ZuyqaWindowTilt->updateGenericAtY(SurfaceProperties, Rect, F, F)
     * ```
     * 四个实参全部来自参数表 ⇒ hook 层完全可复现（[ARG_TARGET]/[ARG_TRANSACTION]/[ARG_Y]/[ARG_PROGRESS]）。
     * 修改版用静态单例 `sGeneric` + `updateGenericAtY`（方向由 y 与 rect 高推导），本实现等价。
     */
    private fun mountLightWindowPath(api: XposedInterface, classLoader: ClassLoader) {
        try {
            val clazz = Class.forName(CLASS_APP_LAUNCH_ANIM_LIGHT, false, classLoader)
            var mounted = 0
            for (m in clazz.declaredMethods) {
                if (!m.name.startsWith(PREFIX_ON_UPDATE_LAMBDA)) continue
                if (m.parameterCount != ON_UPDATE_LAMBDA_PARAM_COUNT) continue
                try {
                    m.isAccessible = true
                    api.hook(m)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept { chain ->
                            val result = chain.proceed()
                            try {
                                applyGenericAtY(chain.getArgs())
                            } catch (t: Throwable) {
                                Log.e(TAG, "light window intercept error", t)
                            }
                            result
                        }
                    mounted++
                    Log.i(TAG, "mounted light window path: ${clazz.simpleName}#${m.name}")
                } catch (t: Throwable) {
                    Log.e(TAG, "mount light window method FAILED: ${m.name}", t)
                }
            }
            if (mounted == 0) Log.w(TAG, "$CLASS_APP_LAUNCH_ANIM_LIGHT: no matching onUpdate\$lambda method")
        } catch (t: Throwable) {
            Log.e(TAG, "mount light window path FAILED (class not found)", t)
        }
    }

    /** 等价修改版 `ZuyqaWindowTilt.updateGenericAtY(props, rect, progress, y)`（走通用单例）。 */
    private fun applyGenericAtY(args: List<Any?>?) {
        if (!config().enabled || args == null) return
        if (args.size <= maxOf(ARG_TRANSACTION, ARG_Y, ARG_PROGRESS)) return
        val target = args[ARG_TARGET] ?: return
        val transaction = args[ARG_TRANSACTION] ?: return
        val progress = args[ARG_PROGRESS] as? Float ?: return
        val y = args[ARG_Y] as? Float ?: return
        val bounds = fScreenSpaceBounds?.get(target) as? Rect ?: return
        val surface = fLeash?.get(target) as? android.view.SurfaceControl ?: return
        val rect = Rect(bounds)
        rect.offsetTo(0, 0)
        val height = rect.height()
        if (height <= 0) return
        val direction = (((y / height) * 2f) - 1f).coerceIn(-1f, 1f)
        val props = surfacePropertiesOf(transaction, surface) ?: return
        genericTilt().updateWithDirection(props, rect, progress, direction)
    }

    /** 通用 tilt 单例（= 修改版 `ZuyqaWindowTilt.sGeneric`）。 */
    private fun genericTilt(): WindowTilt {
        genericTilt?.let { return it }
        return synchronized(this) {
            genericTilt ?: WindowTilt(null, null).also { genericTilt = it }
        }
    }

    // ------------------------------------------------------------ 动画结束清理（缺口 2）

    /**
     * 等价修改版 `ZuyqaWindowTilt.watch(CustomRectFSpringAnim)`：
     * 给动画注册一个动态代理监听器（原版 public `addAnimatorListener(NullableAnimatorListener)`，
     * 原版 `AsyncAnimCallbacks` 会在动画结束时回调 `onAnimationEnd`），
     * 结束时经 32ms 延迟把本 tilt 拥有的 surface 矩阵复位（`cleanupOwnedSurfaces`）。
     */
    private fun watch(animRef: Any?, tilt: WindowTilt) {
        val add = mAddAnimatorListener ?: return
        val iface = nullableAnimatorListenerCls ?: return
        val anim = try {
            fRefElement?.get(animRef)
        } catch (t: Throwable) {
            null
        } ?: return
        if (!tilt.markWatched()) return
        try {
            // ⚠️ handler 必须为 hashCode/equals 返回正确装箱类型：原版 `AsyncAnimCallbacks.addListeners`
            // 会调 `ArrayList.contains(listener)` → 触发代理的 equals，返回 null 会 Proxy 拆箱 NPE。
            val handler = java.lang.reflect.InvocationHandler { proxy, method, margs ->
                try {
                    when (method.name) {
                        "onAnimationStart" -> {
                            tilt.onAnimationStart(); null
                        }

                        "onAnimationEnd" -> {
                            tilt.onAnimationEnd(); null
                        }

                        "onAnimationCancel" -> {
                            tilt.onAnimationCancel(); null
                        }

                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === margs?.getOrNull(0)
                        "toString" -> "lg-tilt-listener"
                        else -> null
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "tilt listener ${method.name} error", t)
                    when (method.name) {
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> false
                        "toString" -> "lg-tilt-listener"
                        else -> null
                    }
                }
            }
            val listener = java.lang.reflect.Proxy.newProxyInstance(
                iface.classLoader, arrayOf(iface), handler,
            )
            add.invoke(anim, listener)
            logD { "watch registered for tilt" }
        } catch (t: Throwable) {
            Log.e(TAG, "watch failed, tilt cleanup disabled for this anim", t)
        }
    }

    private fun mainHandler(): android.os.Handler {
        mainHandler?.let { return it }
        return synchronized(this) {
            mainHandler ?: android.os.Handler(android.os.Looper.getMainLooper()).also { mainHandler = it }
        }
    }

    // ------------------------------------------------------------ Hook：打断动画 tilt 接力

    private fun mountBreakCarry(api: XposedInterface, classLoader: ClassLoader) {
        // 5a. TransformParams 构造（打断动画里紧随 RectTransformHelper 构造之后）
        try {
            val paramsCls = Class.forName(CLASS_TRANSFORM_PARAMS, false, classLoader)
            val ctor = paramsCls.getDeclaredConstructor().apply { isAccessible = true }
            api.hook(ctor)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val pending = pendingBreakTilt
                        if (pending != null && System.identityHashCode(Thread.currentThread()) == pendingBreakThreadId) {
                            pendingBreakTilt = null
                            pendingBreakThreadId = -1
                            bindTilt(chain.getThisObject(), pending)
                            logD { "break carry bound to TransformParams" }
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "TransformParams ctor intercept error", t)
                    }
                    result
                }
            Log.i(TAG, "mounted: $CLASS_TRANSFORM_PARAMS<init>() [break carry]")
        } catch (t: Throwable) {
            Log.e(TAG, "mount TransformParams ctor FAILED", t)
        }
        // 5b. createBreakAppOpenAnim：进方法前放挂起槽，出方法后清理
        try {
            val recCls = Class.forName(CLASS_ANIMATION_RECORD, false, classLoader)
            val clazz = Class.forName(CLASS_SWIPE_UP_HANDLER, false, classLoader)
            val method = clazz.declaredMethods.firstOrNull {
                it.name == METHOD_CREATE_BREAK_APP_OPEN_ANIM && it.parameterCount == 1
            } ?: run {
                Log.w(TAG, "$METHOD_CREATE_BREAK_APP_OPEN_ANIM not found, break carry skipped")
                null
            } ?: return
            method.isAccessible = true
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        val record = chain.getArgs()?.getOrNull(0)
                        pendingBreakTilt = createBreakTilt(record, recCls)
                        pendingBreakThreadId = System.identityHashCode(Thread.currentThread())
                    } catch (t: Throwable) {
                        Log.e(TAG, "createBreakAppOpenAnim pre intercept error", t)
                    }
                    val result = chain.proceed()
                    // 若方法内没构造 TransformParams（异常/分支），槽位不残留
                    try {
                        if (System.identityHashCode(Thread.currentThread()) == pendingBreakThreadId) {
                            pendingBreakTilt = null
                            pendingBreakThreadId = -1
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "createBreakAppOpenAnim post intercept error", t)
                    }
                    result
                }
            Log.i(TAG, "mounted: $CLASS_SWIPE_UP_HANDLER#$METHOD_CREATE_BREAK_APP_OPEN_ANIM(AnimationRecord)")
        } catch (t: Throwable) {
            Log.e(TAG, "mount createBreakAppOpenAnim FAILED", t)
        }
    }

    /** 打断动画的 tilt：从 AnimationRecord 取 taskId 做角度接力（照抄修改版 snapshotRecord 语义）。 */
    private fun createBreakTilt(record: Any?, recCls: Class<*>): WindowTilt? = try {
        if (!config().enabled) {
            null
        } else if (record == null) {
            null
        } else {
            val isTaskView = recCls.getMethod(METHOD_GET_M_IS_TASK_VIEW).invoke(record) as? Boolean ?: false
            if (isTaskView) {
                null
            } else {
                val taskId = recCls.getMethod(METHOD_GET_ANIM_TOP_TASK_ID).invoke(record) as? Int ?: -1
                WindowTilt(null, null).also { it.carryFromTask(taskId, true) }
            }
        }
    } catch (t: Throwable) {
        Log.e(TAG, "createBreakTilt failed", t)
        null
    }

    // ------------------------------------------------------------ 应用逻辑

    private fun applyForTarget(target: Any?, helper: Any?, transaction: Any?, params: Any?) {
        // 默认关时零影响：先判开关再做任何反射（逐帧热路径）
        if (!config().enabled) return
        if (target == null || helper == null || params == null) return
        if (isTaskView(params)) return
        val surface = fLeash?.get(target) as? android.view.SurfaceControl ?: return
        val props = surfacePropertiesOf(transaction, surface) ?: return
        val rect = rectOf(helper)
        updateForParams(params, props, rect)
        // 修改版 zuyqaRememberTask：记录 taskId → tilt，供后续动画角度接力
        val tilt = tiltOf(params) ?: return
        val taskId = try {
            val f = target.javaClass.getField(FIELD_TASK_ID)
            f.getInt(target)
        } catch (t: Throwable) {
            -1
        }
        tilt.rememberTask(taskId)
    }

    private fun applyForThumb(params: Any?, transaction: Any?) {
        if (!config().enabled) return
        if (params == null || isTaskView(params)) return
        val surface = try {
            mGetSAppThumb?.invoke(null) as? android.view.SurfaceControl
        } catch (t: Throwable) {
            null
        } ?: return
        val props = surfacePropertiesOf(transaction, surface) ?: return
        updateForParams(params, props, null)
    }

    /** 修改版 `RectTransformHelper.zuyqaSetMatrix` 里的 rect 选择：`mClipRect` 为空则用 `mSourceWindowRect`。 */
    private fun rectOf(helper: Any): Rect? = try {
        val clip = fMClipRect?.get(helper) as? Rect
        if (clip != null && !clip.isEmpty) {
            clip
        } else {
            fMSourceWindowRect?.get(helper) as? Rect
        }
    } catch (t: Throwable) {
        null
    }

    private fun surfacePropertiesOf(transaction: Any?, surface: android.view.SurfaceControl?): Any? {
        if (transaction == null || surface == null) return null
        return try {
            mForSurface?.invoke(transaction, surface)
        } catch (t: Throwable) {
            Log.e(TAG, "forSurface failed", t)
            null
        }
    }

    private fun isTaskView(params: Any?): Boolean = try {
        (mGetIsTaskView?.invoke(params) as? Boolean) ?: false
    } catch (t: Throwable) {
        false
    }

    private fun progressOf(params: Any?): Float = try {
        (mGetProgress?.invoke(params) as? Float) ?: 0f
    } catch (t: Throwable) {
        0f
    }

    private fun tiltOf(params: Any?): WindowTilt? = paramsToTilt[params]

    private fun bindTilt(params: Any?, tilt: WindowTilt) {
        if (params == null) return
        paramsToTilt[params] = tilt
    }

    private fun updateForParams(params: Any?, props: Any?, rect: Rect?) {
        if (!config().enabled) return
        val tilt = tiltOf(params) ?: return
        tilt.update(props, rect, progressOf(params))
    }

    /** 把 4x4 矩阵写到 SurfaceProperties 持有的 surface（= 修改版 `zuyqaSetTilt`）。
     *  走原版 public `SurfaceProperties#setMatrix4x4(float[])`（内部自带 try-catch + 标记 modified），
     *  同时把该 surface 登记进 tilt 的拥有表（= 修改版 `zuyqaTrackOwner`，供结束时复位）。 */
    private fun applyTilt(props: Any, matrix: FloatArray, tilt: WindowTilt) {
        try {
            mSetMatrix4x4?.invoke(props, matrix)
            val surface = fMSurface?.get(props)
            if (surface != null) tilt.trackSurface(surface)
        } catch (t: Throwable) {
            Log.e(TAG, "applyTilt failed", t)
        }
    }

    /** 把一个（已不再使用的）surface 矩阵复位为单位矩阵：新建原版 `SurfaceTransaction` 一次性提交。
     *  = 修改版 `cleanupOwnedSurfaces` 里的 `new SurfaceControl.Transaction()` + `apply()`，
     *  但全程走**原版 app 类的 public 方法**，不碰 framework 隐藏 API。 */
    private fun resetSurfaces(surfaces: List<Any>) {
        if (surfaces.isEmpty()) return
        val ctor = mSurfaceTransactionCtor ?: return
        val getTx = mGetTransaction ?: return
        val forSurface = mForSurface ?: return
        val setM4 = mSetMatrix4x4 ?: return
        try {
            val tx = ctor.newInstance()
            for (surface in surfaces) {
                try {
                    val props = forSurface.invoke(tx, surface) ?: continue
                    setM4.invoke(props, identity4x4)
                } catch (t: Throwable) {
                    Log.e(TAG, "reset surface props failed", t)
                }
            }
            val rawTx = getTx.invoke(tx) as? android.view.SurfaceControl.Transaction
            rawTx?.apply()
        } catch (t: Throwable) {
            Log.e(TAG, "resetSurfaces failed", t)
        }
    }

    // ------------------------------------------------------------ 配置

    private fun config(): TiltConfig {
        val now = SystemClock.uptimeMillis()
        if (now - cfgReadAtMs > CONFIG_TTL_MS) {
            cfg = try {
                val prefs = api?.let { Prefs.read(it) }
                val enabled = prefs?.getBoolean(Prefs.KEY_ANIM_TILT_ENABLED, Prefs.DEFAULT_ANIM_TILT_ENABLED)
                    ?: false
                val strength = prefs?.let {
                    Prefs.readIntCompat(it, Prefs.KEY_ANIM_TILT_STRENGTH, Prefs.DEFAULT_ANIM_TILT_STRENGTH)
                } ?: Prefs.DEFAULT_ANIM_TILT_STRENGTH
                val perspective = prefs?.let {
                    Prefs.readIntCompat(it, Prefs.KEY_ANIM_TILT_PERSPECTIVE, Prefs.DEFAULT_ANIM_TILT_PERSPECTIVE)
                } ?: Prefs.DEFAULT_ANIM_TILT_PERSPECTIVE
                TiltConfig(
                    enabled,
                    strength.coerceIn(0, Prefs.ANIM_TILT_STRENGTH_MAX) / 100f,
                    perspective.coerceIn(0, Prefs.ANIM_TILT_PERSPECTIVE_MAX) / 100f,
                )
            } catch (t: Throwable) {
                Log.e(TAG, "read tilt config failed, treat as disabled", t)
                TiltConfig(false, 1f, 1f)
            }
            cfgReadAtMs = now
        }
        return cfg
    }

    private inline fun logD(msg: () -> String) {
        if (logEnabled) Log.d(TAG, msg())
    }

    // ------------------------------------------------------------ 倾斜状态机（ZuyqaWindowTilt 等价移植）

    /**
     * 单个动画的倾斜状态（等价修改版 `ZuyqaWindowTilt` 实例）。
     * 角度/矩阵算法与原版逐行一致（见 doc/spec/65）。
     */
    private class WindowTilt(startRect: RectF?, screenRect: RectF?) {

        private var direction = 0f
        private var directionKnown = false

        private var carryActive = false
        private var carryAngle = 0f
        private var carryOnly = false
        private var carryPending = false
        private var carryProgress = 0f
        private var carryStartedTime = 0L

        private var closingBoost = false
        private var trigValid = false
        private var trigAngle = 0f
        private var trigCos = 0f
        private var trigSin = 0f

        private var lastAngle = 0f
        private var lastTime = 0L
        private var taskKey: Int? = null

        /** 动画帧计数 / 结束帧 / 已取消 / 已注册监听（= 修改版 mFrame/mEndFrame/mCancelled/mWatched） */
        private var frame = 0
        private var endFrame = 0
        private var cancelled = false
        private var watched = false
        private var cleanupRetries = 0

        /** 本 tilt 施加过矩阵的 surface（= 修改版 `sOwners` 中 value==this 的部分），结束时复位 */
        private val ownedSurfaces: MutableMap<Any, Boolean> =
            Collections.synchronizedMap(WeakHashMap<Any, Boolean>())

        private val matrix = FloatArray(16)

        init {
            if (startRect != null && screenRect != null && !startRect.isEmpty && screenRect.height() > 0f) {
                var d = ((startRect.centerY() - screenRect.top) / screenRect.height()) * 2f - 1f
                if (d < -1f) d = -1f
                direction = if (d > 1f) 1f else d
                directionKnown = true
            }
        }

        fun setClosingBoost() {
            closingBoost = true
        }

        fun update(props: Any?, rect: Rect?, progress: Float) {
            synchronized(this) { updateLocked(props, rect, progress) }
        }

        /** 等价修改版 `updateWithDirection`：先定方向再更新（轻动画通用通道用）。 */
        fun updateWithDirection(props: Any?, rect: Rect?, progress: Float, dir: Float) {
            synchronized(this) {
                direction = dir
                directionKnown = true
                updateLocked(props, rect, progress)
            }
        }

        /** 登记一个被本 tilt 施加过矩阵的 surface（= 修改版 `ZuyqaWindowTilt.trackSurface`）。 */
        fun trackSurface(surface: Any) {
            if (!ownedSurfaces.containsKey(surface)) ownedSurfaces[surface] = true
        }

        /** 只登记一次 AnimatorListener（= 修改版 `mWatched` 去重）。返回 true = 本次需要注册。 */
        fun markWatched(): Boolean {
            if (watched) return false
            watched = true
            return true
        }

        fun onAnimationStart() {
            cancelled = false
            frame++
        }

        fun onAnimationCancel() {
            cancelled = true
        }

        /**
         * 等价修改版 `onAnimationEnd`：记录结束帧 + 32ms 后投递 [tick]。
         * 动画可能还在最后一帧收尾，故延迟一拍再比对帧号。
         */
        fun onAnimationEnd() {
            endFrame = frame
            try {
                mainHandler().postDelayed({ tick() }, CLEANUP_DELAY_MS)
            } catch (t: Throwable) {
                Log.e(TAG, "postDelayed cleanup failed", t)
            }
        }

        /** 等价修改版 `run()`：帧号未变才真正清理；被取消则重试一次。 */
        private fun tick() {
            try {
                if (frame != endFrame) {
                    // 被取消（动画中途打断）：修改版会无限重试直到帧号稳定；
                    // 本实现加 [CLEANUP_MAX_RETRY] 上限后强制清理，保证矩阵一定被复位
                    if (cancelled && cleanupRetries < CLEANUP_MAX_RETRY) {
                        cleanupRetries++
                        try {
                            mainHandler().postDelayed({ tick() }, CLEANUP_FRAME_SETTLE_RETRY_MS)
                        } catch (t: Throwable) {
                            Log.e(TAG, "retry cleanup post failed", t)
                        }
                        return
                    }
                    if (!cancelled) return
                }
                lastAngle = 0f
                carryAngle = 0f
                carryActive = false
                carryPending = false
                cleanupOwnedSurfaces()
            } catch (t: Throwable) {
                Log.e(TAG, "tilt cleanup tick failed", t)
            }
        }

        /** 等价修改版 `cleanupOwnedSurfaces`：把本 tilt 施加过矩阵的 surface 全部复位为单位矩阵。 */
        private fun cleanupOwnedSurfaces() {
            val surfaces = synchronized(ownedSurfaces) {
                val copy = ArrayList<Any>(ownedSurfaces.keys)
                ownedSurfaces.clear()
                copy
            }
            if (surfaces.isEmpty()) return
            logD { "cleanup ${surfaces.size} tilted surface(s)" }
            resetSurfaces(surfaces)
        }

        fun rememberTask(taskId: Int) {
            if (taskId < 0) return
            if (taskKey != taskId) taskKey = taskId
            taskToTilt[taskId] = WeakReference(this)
        }

        fun carryFromTask(taskId: Int, only: Boolean) {
            carryOnly = only
            if (!config().enabled) return
            val other = taskToTilt[taskId]?.get() ?: return
            if (other === this) return
            if (SystemClock.uptimeMillis() - other.lastTime > CARRY_TIMEOUT_MS) return
            val angle = other.lastAngle
            carryAngle = angle
            if (angle != 0f) {
                carryPending = true
                carryActive = true
                logD { "carry handoff angle=$angle" }
            }
        }

        fun carryFromTargets(targets: Array<Any?>?) {
            targets ?: return
            for (t in targets) {
                if (t == null) continue
                val id = try {
                    t.javaClass.getField(FIELD_TASK_ID).getInt(t)
                } catch (e: Throwable) {
                    -1
                }
                carryFromTask(id, false)
                if (carryActive) return
            }
        }

        private fun updateLocked(props: Any?, rect: Rect?, rawProgress: Float) {
            val c = config()
            if (!c.enabled || props == null || rect == null) return
            var progress = rawProgress.coerceIn(0f, 1f)
            val height = rect.height()
            if (height <= 0) return

            var dir = direction
            if (directionKnown) dir = shapeDirection(dir)
            var angle = (1f - progress) * progress * TILT_ARC_SCALE * dir * c.strength
            if (closingBoost) angle *= CLOSING_BOOST
            angle = continuousAngle(angle, progress)
            if (angle > -1.0E-6f && angle < 1.0E-6f) {
                java.util.Arrays.fill(matrix, 0f)
                matrix[0] = 1f
                matrix[5] = 1f
                matrix[10] = 1f
                matrix[15] = 1f
                applyTilt(props, matrix, this)
                return
            }
            if (!trigValid || angle != trigAngle) {
                trigAngle = angle
                trigCos = Math.cos(angle.toDouble()).toFloat()
                trigSin = Math.sin(angle.toDouble()).toFloat()
                trigValid = true
            }
            val cos = trigCos
            val sin = trigSin
            val persp = (c.perspective * PERSPECTIVE_BASE) / height.toFloat()
            val cx = rect.exactCenterX()
            val cy = rect.exactCenterY()
            val m = matrix
            java.util.Arrays.fill(m, 0f)
            m[0] = 1f
            val a = cx * persp * sin
            m[1] = a
            m[3] = -(a * cy)
            m[2] = cx * persp * cos
            val b = cy * persp * sin
            m[5] = cos + b
            m[15] = 1f - b
            m[7] = ((1f - cos) * cy) - (b * cy)
            m[6] = ((cy * persp) * cos) - sin
            m[9] = sin
            m[10] = cos
            m[11] = -(cy * sin)
            m[13] = persp * sin
            m[14] = persp * cos
            applyTilt(props, m, this)
        }

        /** 照抄修改版 `continuousAngle`：接力角度的 220ms 平滑过渡 + NaN 防护 + ±0.628 钳制。 */
        private fun continuousAngle(raw: Float, progress: Float): Float {
            var angle = raw
            val now = SystemClock.uptimeMillis()
            if (carryOnly) angle = 0f
            if (carryPending) {
                carryProgress = progress
                carryStartedTime = now
                carryPending = false
            }
            if (carryActive) {
                var remaining = carryProgress
                var delta = progress - remaining
                if (delta >= 0f) {
                    remaining = 1f - remaining
                } else {
                    delta = -delta
                }
                val t = Math.max(
                    Math.max(Math.min(delta / Math.max(remaining, 1.0E-4f), 1f), 0f),
                    Math.min(Math.max((now - carryStartedTime) / CARRY_FADE_MS, 0f), 1f),
                )
                if (t >= 1f) {
                    carryActive = false
                    carryAngle = 0f
                } else {
                    val w = 1f - t
                    val s = w * w * (t * 2f + 1f)
                    angle = (angle * (1f - s)) + (carryAngle * s)
                }
            }
            if (angle.isNaN() || angle.isInfinite()) {
                angle = 0f
                carryActive = false
                carryPending = false
                carryAngle = 0f
            }
            angle = angle.coerceIn(-TILT_MAX_ANGLE, TILT_MAX_ANGLE)
            lastAngle = angle
            lastTime = now
            return angle
        }

        private fun shapeDirection(d: Float): Float {
            val v = (Math.min(Math.abs(d), 1f) * 0.5f) + 0.5f
            return if (d <= 0f) -v else v
        }
    }
}
