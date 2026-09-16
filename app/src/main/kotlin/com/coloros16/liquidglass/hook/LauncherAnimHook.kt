package com.coloros16.liquidglass.hook

import android.util.Log
import com.coloros16.liquidglass.config.Prefs
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * [doc/spec/64 桌面动画参数调节 2026-09-16] Launcher 进程：可调桌面过渡动画的弹簧参数。
 *
 * 实现方式：LibXposed hook 原版类，**不修改系统 APK**（对照修改版 ZuyQA 16.6.20 的插桩语义等价实现）。
 *
 * hook 点（Launcher 进程 `com.android.launcher`，与 LauncherHook 同一 classLoader）：
 * 1. `CustomRectFSpringAnim#setAnimParamByType(AnimType, boolean)`（public final）
 *    - 原版流程：该方法内逐项 `setSpringHolderParamByType` **写入** 8 个弹簧字段（stiffness 先过
 *      `getRateStiffness()` 缩放）；随后 `start()` → `initAllAnimations()` **读取**这些字段配置
 *      SpringForce。⇒ 覆盖必须发生在本方法 `proceed()` 之后（本类实现如此）。
 * 2. `CustomRectFSpringAnim#initAllAnimations()`（private final，无参）
 *    - 本类在 `proceed()` **之前**写字段，使本次 SpringForce 配置直接读配置值（覆盖修改版把插桩
 *      放在函数末尾、对本次调用已无效的位置；见 doc/spec/64 §2.1）。
 * 3. `LauncherContentAnimManager#getLauncherViewAnimParam(AnimType)`（private final，返回 float[]）
 *    - 原版 `[0] = AbsAnimation.getScaledStiffness(param[0])`；本类按
 *      `stiffness = (6283.185f / durationMs)²` 替换后再走**原版** `getScaledStiffness` 缩放链。
 *
 * 安全约束（doc/spec/64 §7）：
 * - 所有 hook 回调 try-catch 全包 + [ExceptionMode.PROTECTIVE]，异常绝不外传（不崩 Launcher）。
 * - 全部开关默认 false；组开关关闭时回调首行 return（零副作用）。
 * - 类/字段反射失败只禁用对应功能，不影响模块其它 hook。
 *
 * 日志：tag [TAG]（`lg-lanim`），受模块「模块日志」开关（[Prefs.KEY_ENABLE_LOGS]，默认 false）控制；
 * 挂载/失败日志不受开关控制（与模块既有风格一致）。
 */
object LauncherAnimHook {

    private const val TAG = "lg-lanim"

    // ---- 目标类/方法/字段 ----
    private const val CLASS_SPRING_ANIM = "com.android.quickstep.util.animation.CustomRectFSpringAnim"
    private const val CLASS_ANIM_TYPE = "com.android.quickstep.util.animation.CustomRectFSpringAnim\$AnimType"
    private const val METHOD_INIT_ALL_ANIMATIONS = "initAllAnimations"
    private const val METHOD_SET_ANIM_PARAM_BY_TYPE = "setAnimParamByType"
    private const val FIELD_M_ANIM_TYPE = "mAnimType"

    private const val CLASS_CONTENT_ANIM_MANAGER = "com.oplus.quickstep.anim.LauncherContentAnimManager"
    private const val METHOD_GET_LAUNCHER_VIEW_ANIM_PARAM = "getLauncherViewAnimParam"

    private const val CLASS_ABS_ANIMATION = "com.android.quickstep.util.animation.AbsAnimation"
    private const val METHOD_GET_SCALED_STIFFNESS = "getScaledStiffness"

    /** 图标透明度时长 → 刚度换算常数（照抄修改版 `6283.1855f / duration`，再平方） */
    private const val FADE_OMEGA = 6283.185f

    /** 配置缓存 TTL 毫秒（修改版 250ms 刷新；本模块取 500ms，覆盖类调用非每帧，够用且更省） */
    private const val CONFIG_TTL_MS = 500L

    /** 参数名 → CustomRectFSpringAnim 字段名（顺序与 [Prefs.ANIM_SPRING_PARAMS] 无关，独立映射） */
    private val SPRING_FIELD_NAMES = linkedMapOf(
        Prefs.ANIM_PARAM_X_DAMPING to "mCenterXDamping",
        Prefs.ANIM_PARAM_X_STIFFNESS to "mCenterXStiffness",
        Prefs.ANIM_PARAM_Y_DAMPING to "mRectYDamping",
        Prefs.ANIM_PARAM_Y_STIFFNESS to "mRectYStiffness",
        Prefs.ANIM_PARAM_WIDTH_DAMPING to "mWidthDamping",
        Prefs.ANIM_PARAM_WIDTH_STIFFNESS to "mWidthStiffness",
        Prefs.ANIM_PARAM_HEIGHT_DAMPING to "mRadioDamping",
        Prefs.ANIM_PARAM_HEIGHT_STIFFNESS to "mRadioStiffness",
    )

    // ---- 运行态 ----
    @Volatile
    private var api: XposedInterface? = null

    @Volatile
    private var logEnabled = false

    /** 参数名 → 该参数在 [Cfg.values] 中的下标（顺序 = [SPRING_FIELD_NAMES] 声明顺序，构建期固定） */
    private val paramIndex: Map<String, Int> = SPRING_FIELD_NAMES.keys.withIndex().associate { it.value to it.index }

    /** 参数名 → 已解析字段（Launcher classLoader 下解析，进程内复用；保持 [SPRING_FIELD_NAMES] 顺序） */
    private val springFields = LinkedHashMap<String, Field>()

    private var mAnimTypeField: Field? = null
    private var mGetScaledStiffness: Method? = null

    /** 组配置缓存（TTL [CONFIG_TTL_MS]；热路径不每次读框架 Prefs）。 */
    @Volatile
    private var cfgCache: Map<String, Cfg> = emptyMap()

    @Volatile
    private var cfgReadAtMs = 0L

    /**
     * 单组配置：[values] 顺序同 [SPRING_FIELD_NAMES]（NaN = 该项不覆盖），
     * [fadeDuration] <= 0 = 不覆盖。
     */
    private class Cfg(val enabled: Boolean, val values: FloatArray, val fadeDuration: Int)

    // ------------------------------------------------------------ 入口

    fun install(api: XposedInterface, classLoader: ClassLoader) {
        this.api = api
        logEnabled = try {
            Prefs.read(api).getBoolean(Prefs.KEY_ENABLE_LOGS, Prefs.DEFAULT_ENABLE_LOGS)
        } catch (t: Throwable) {
            false
        }
        resolveReflection(classLoader)
        mountSetAnimParamByType(api, classLoader)
        mountInitAllAnimations(api, classLoader)
        mountGetLauncherViewAnimParam(api, classLoader)
        Log.i(TAG, "LauncherAnimHook install done (springFields=${springFields.size})")
    }

    // ------------------------------------------------------------ 反射解析

    private fun resolveReflection(classLoader: ClassLoader) {
        try {
            val clazz = Class.forName(CLASS_SPRING_ANIM, false, classLoader)
            var ok = 0
            for ((param, fieldName) in SPRING_FIELD_NAMES) {
                try {
                    springFields[param] = clazz.getDeclaredField(fieldName).apply { isAccessible = true }
                    ok++
                } catch (t: Throwable) {
                    Log.e(TAG, "resolve field $fieldName failed, param=$param skipped", t)
                }
            }
            mAnimTypeField = try {
                clazz.getDeclaredField(FIELD_M_ANIM_TYPE).apply { isAccessible = true }
            } catch (t: Throwable) {
                Log.e(TAG, "resolve $FIELD_M_ANIM_TYPE failed, initAllAnimations override uses arg type only", t)
                null
            }
            Log.i(TAG, "resolved $CLASS_SPRING_ANIM spring fields: $ok/${SPRING_FIELD_NAMES.size}")
        } catch (t: Throwable) {
            Log.e(TAG, "resolve $CLASS_SPRING_ANIM failed, spring override disabled", t)
        }
        try {
            val absAnim = Class.forName(CLASS_ABS_ANIMATION, false, classLoader)
            mGetScaledStiffness = absAnim.getMethod(METHOD_GET_SCALED_STIFFNESS, Float::class.javaPrimitiveType)
            Log.i(TAG, "resolved $CLASS_ABS_ANIMATION#$METHOD_GET_SCALED_STIFFNESS(float)")
        } catch (t: Throwable) {
            Log.e(TAG, "resolve $CLASS_ABS_ANIMATION#$METHOD_GET_SCALED_STIFFNESS failed, fade duration disabled", t)
        }
    }

    // ------------------------------------------------------------ Hook 1：setAnimParamByType（proceed 后覆盖）

    private fun mountSetAnimParamByType(api: XposedInterface, classLoader: ClassLoader) {
        try {
            val clazz = Class.forName(CLASS_SPRING_ANIM, false, classLoader)
            val animTypeCls = Class.forName(CLASS_ANIM_TYPE, false, classLoader)
            val method = clazz.getMethod(
                METHOD_SET_ANIM_PARAM_BY_TYPE,
                animTypeCls, Boolean::class.javaPrimitiveType,
            )
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val animType = try {
                            chain.getArgs()?.getOrNull(0)
                        } catch (t: Throwable) {
                            null
                        }
                        applySpringOverride(chain.getThisObject(), animTypeName(animType))
                    } catch (t: Throwable) {
                        Log.e(TAG, "setAnimParamByType intercept error", t)
                    }
                    result
                }
            Log.i(TAG, "mounted: $CLASS_SPRING_ANIM#$METHOD_SET_ANIM_PARAM_BY_TYPE(AnimType, boolean) [after proceed]")
        } catch (t: Throwable) {
            Log.e(TAG, "mount FAILED: $CLASS_SPRING_ANIM#$METHOD_SET_ANIM_PARAM_BY_TYPE", t)
        }
    }

    // ------------------------------------------------------------ Hook 2：initAllAnimations（proceed 前覆盖）

    private fun mountInitAllAnimations(api: XposedInterface, classLoader: ClassLoader) {
        try {
            val clazz = Class.forName(CLASS_SPRING_ANIM, false, classLoader)
            val method = clazz.getDeclaredMethod(METHOD_INIT_ALL_ANIMATIONS).apply { isAccessible = true }
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val target = try {
                        chain.getThisObject()
                    } catch (t: Throwable) {
                        null
                    }
                    try {
                        // 本方法内只【读取】8 个弹簧字段配置 SpringForce（不写），故在 proceed 前覆盖即可生效
                        applySpringOverride(target, animTypeNameFromField(target))
                    } catch (t: Throwable) {
                        Log.e(TAG, "initAllAnimations intercept error", t)
                    }
                    chain.proceed()
                }
            Log.i(TAG, "mounted: $CLASS_SPRING_ANIM#$METHOD_INIT_ALL_ANIMATIONS() [before proceed]")
        } catch (t: Throwable) {
            Log.e(TAG, "mount FAILED: $CLASS_SPRING_ANIM#$METHOD_INIT_ALL_ANIMATIONS", t)
        }
    }

    // ------------------------------------------------------------ Hook 3：图标透明度时长

    private fun mountGetLauncherViewAnimParam(api: XposedInterface, classLoader: ClassLoader) {
        if (mGetScaledStiffness == null) {
            Log.w(TAG, "getScaledStiffness unresolved, fade duration hook skipped")
            return
        }
        try {
            val clazz = Class.forName(CLASS_CONTENT_ANIM_MANAGER, false, classLoader)
            val animTypeCls = Class.forName(CLASS_ANIM_TYPE, false, classLoader)
            val method = clazz.getDeclaredMethod(METHOD_GET_LAUNCHER_VIEW_ANIM_PARAM, animTypeCls)
                .apply { isAccessible = true }
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val arr = result as? FloatArray
                        if (arr != null && arr.isNotEmpty()) {
                            val animType = try {
                                chain.getArgs()?.getOrNull(0)
                            } catch (t: Throwable) {
                                null
                            }
                            val group = groupFor(animTypeName(animType))
                            val cfg = group?.let { config(it) }
                            if (group != null && cfg != null && cfg.enabled && cfg.fadeDuration > 0) {
                                val omega = FADE_OMEGA / cfg.fadeDuration
                                val raw = omega * omega
                                val scaled = scaledStiffness(raw)
                                if (scaled > 0f) {
                                    if (logEnabled) {
                                        Log.d(
                                            TAG,
                                            "fade group=$group dur=${cfg.fadeDuration}ms raw=${"%.2f".format(raw)} -> scaled=${"%.2f".format(scaled)} (was ${"%.2f".format(arr[0])})",
                                        )
                                    }
                                    arr[0] = scaled
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "getLauncherViewAnimParam intercept error", t)
                    }
                    result
                }
            Log.i(TAG, "mounted: $CLASS_CONTENT_ANIM_MANAGER#$METHOD_GET_LAUNCHER_VIEW_ANIM_PARAM(AnimType) [after proceed]")
        } catch (t: Throwable) {
            Log.e(TAG, "mount FAILED: $CLASS_CONTENT_ANIM_MANAGER#$METHOD_GET_LAUNCHER_VIEW_ANIM_PARAM", t)
        }
    }

    /** 反射调用原版 `AbsAnimation.getScaledStiffness(float)`；失败返回 0（= 不覆盖）。 */
    private fun scaledStiffness(raw: Float): Float = try {
        (mGetScaledStiffness?.invoke(null, raw) as? Float) ?: 0f
    } catch (t: Throwable) {
        Log.e(TAG, "getScaledStiffness invoke failed", t)
        0f
    }

    // ------------------------------------------------------------ 覆盖逻辑

    /**
     * 按动画类型覆盖 8 个弹簧字段。任何异常都被吞掉（只打日志），绝不外传。
     *
     * @param target CustomRectFSpringAnim 实例
     * @param typeName AnimType 名（null → 退回读实例的 mAnimType 字段）
     */
    private fun applySpringOverride(target: Any?, typeName: String?) {
        if (target == null) return
        val name = typeName ?: animTypeNameFromField(target) ?: return
        val group = groupFor(name) ?: return
        val cfg = config(group)
        if (!cfg.enabled) return
        var applied = 0
        var skipped = 0
        for ((param, field) in springFields) {
            val idx = paramIndex[param] ?: -1
            val v = if (idx in cfg.values.indices) cfg.values[idx] else Float.NaN
            if (v.isNaN()) {
                skipped++
                continue
            }
            try {
                field.setFloat(target, v)
                applied++
            } catch (t: Throwable) {
                skipped++
                Log.e(TAG, "set ${field.name}=$v failed", t)
            }
        }
        if (logEnabled) {
            Log.d(TAG, "spring override animType=$name group=$group applied=$applied skipped=$skipped")
        }
    }

    /** 动画类型名 → 配置组（照抄修改版 getPrefix 映射；返回 null = 跳过不覆盖）。 */
    private fun groupFor(animTypeName: String?): String? = when (animTypeName) {
        "OPEN_FROM_HOME" -> Prefs.ANIM_GROUP_OPEN
        "REVERSE_TO_OPEN", "REVERSE_TO_OPEN_REMOTE" -> Prefs.ANIM_GROUP_BREAK
        "SWIPE_TO_HOME", "SWIPE_TO_HOME_ASSISTANT",
        "REMOTE_CLOSE_TO_HOME", "REMOTE_CLOSE_TO_HOME_ASSISTANT",
        -> Prefs.ANIM_GROUP_CLOSE
        else -> null
    }

    private fun animTypeName(animType: Any?): String? = (animType as? Enum<*>)?.name

    private fun animTypeNameFromField(target: Any?): String? {
        val field = mAnimTypeField ?: return null
        return try {
            animTypeName(field.get(target))
        } catch (t: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------ 配置读取

    /** 取组配置（TTL 缓存）。失败返回「全关」配置（等价于不干预）。 */
    private fun config(group: String): Cfg {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - cfgReadAtMs > CONFIG_TTL_MS) {
            cfgCache = try {
                readAllConfig()
            } catch (t: Throwable) {
                Log.e(TAG, "read config failed, treat as disabled", t)
                emptyMap()
            }
            cfgReadAtMs = now
        }
        return cfgCache[group] ?: DISABLED_CFG
    }

    private fun readAllConfig(): Map<String, Cfg> {
        val xposed = api ?: return emptyMap()
        val prefs = Prefs.read(xposed)
        val out = HashMap<String, Cfg>(Prefs.ANIM_GROUPS.size)
        for (group in Prefs.ANIM_GROUPS) {
            val enabled = try {
                prefs.getBoolean(Prefs.animEnabledKey(group), Prefs.DEFAULT_ANIM_GROUP_ENABLED)
            } catch (t: Throwable) {
                false
            }
            val values = FloatArray(SPRING_FIELD_NAMES.size) { Float.NaN }
            if (enabled) {
                for ((param, i) in paramIndex) {
                    val raw = Prefs.readIntCompat(prefs, Prefs.animKey(group, param), -1)
                    values[i] = convert(param, raw)
                }
            }
            val fadeRaw = if (enabled) {
                Prefs.readIntCompat(prefs, Prefs.animKey(group, Prefs.ANIM_PARAM_FADE_DURATION), -1)
            } else {
                -1
            }
            val fade = if (fadeRaw in 1..Int.MAX_VALUE) {
                fadeRaw.coerceIn(Prefs.ANIM_FADE_DURATION_MIN, Prefs.ANIM_FADE_DURATION_MAX)
            } else {
                -1
            }
            out[group] = Cfg(enabled, values, fade)
        }
        return out
    }

    /**
     * 配置值换算（照抄修改版 `ZuyqaAnimationTuning.getFloat`）：
     * - 原始值 <= 0 / 键不存在 → NaN（不覆盖，保持原值）
     * - 阻尼 → ÷100 后 clamp [0.05, 5.0]
     * - 刚度 → clamp [1.0, 5000.0]
     */
    private fun convert(param: String, raw: Int): Float {
        if (raw <= 0) return Float.NaN
        return if (param.endsWith("_damping")) {
            val v = raw / Prefs.ANIM_DAMPING_DIVISOR
            v.coerceIn(Prefs.ANIM_DAMPING_RUNTIME_MIN, Prefs.ANIM_DAMPING_RUNTIME_MAX)
        } else {
            raw.toFloat().coerceIn(Prefs.ANIM_STIFFNESS_RUNTIME_MIN, Prefs.ANIM_STIFFNESS_RUNTIME_MAX)
        }
    }

    /** 全关配置（enabled=false，等价于不干预）。 */
    private val DISABLED_CFG = Cfg(false, FloatArray(SPRING_FIELD_NAMES.size) { Float.NaN }, -1)
}
