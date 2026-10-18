package com.coloros16.liquidglass.glass

import android.content.SharedPreferences
import android.os.SystemClock
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.log.Lg
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 玻璃效果预设与玻璃类探测注册表（hook 侧与设置页共用）。
 *
 * 三块职责：
 * - [bind]：绘制对象出现时登记其类名（进程内判重，热路径只做一次查表），
 *   聚合后按「集合有变化或超过刷新间隔」写入 [Prefs.KEY_GLASS_CLASSES] 缓存。
 * - [overridesFor]：按元素所属类取预设参数覆盖表（500ms TTL 解析缓存，热路径零 IPC）。
 * - [parsePresets] / [encodePresets] / [parseClasses] / [encodeClasses]：两端共用的 JSON 编解码。
 *
 * 每进程一份实例状态：SystemUI / 桌面 / 设置页各持一份，互不共享。
 */
object GlassRegistry {

    private const val TAG = "GlassPreset"

    /** 一个玻璃效果预设：[params] 逐键覆盖全局值，未设的键在渲染侧回落全局。 */
    data class Preset(
        val id: String,
        val name: String,
        val classes: List<String>,
        val params: Map<String, Float>,
    )

    /** 探测缓存里的一条玻璃类记录。 */
    data class DetectedClass(val cls: String, val lastSeenMs: Long)

    /** 预设可覆盖的参数键（滑杆与渲染分发共用同一份名单）。 */
    val PARAM_KEYS = listOf(
        Prefs.KEY_BLUR_RADIUS,
        Prefs.KEY_REFRACTION_AMOUNT,
        Prefs.KEY_DISPERSION,
        Prefs.KEY_DEPTH,
        Prefs.KEY_HIGHLIGHT,
        Prefs.KEY_INNER_GLOW,
        Prefs.KEY_VIBRANCY,
        Prefs.KEY_PARALLAX_X,
        Prefs.KEY_PARALLAX_Y,
    )

    // ------------------------------------------------------------ JSON 编解码（两端共用） ------------------------------------------------------------

    /** 解析 [Prefs.KEY_GLASS_PRESETS]；空串 / 格式错误 → 空表（不抛）。 */
    fun parsePresets(json: String?): List<Preset> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            val arr = JSONArray(json)
            val out = ArrayList<Preset>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isEmpty()) continue
                val name = o.optString("name").ifEmpty { id }
                val clsArr = o.optJSONArray("classes")
                val classes = ArrayList<String>()
                if (clsArr != null) {
                    for (j in 0 until clsArr.length()) {
                        val c = clsArr.optString(j)
                        if (c.isNotEmpty()) classes.add(c)
                    }
                }
                val params = HashMap<String, Float>()
                val pObj = o.optJSONObject("params")
                if (pObj != null) {
                    for (key in PARAM_KEYS) {
                        if (!pObj.has(key)) continue
                        val v = pObj.optDouble(key, Double.NaN).toFloat()
                        if (!v.isNaN()) params[key] = v
                    }
                }
                out.add(Preset(id, name, classes, params))
            }
            out
        } catch (t: Throwable) {
            Lg.w(TAG, "parse glass_presets failed", t)
            emptyList()
        }
    }

    /** 序列化预设列表（字段顺序固定，同内容恒产出同字节，供「无变化不写」比对）。 */
    fun encodePresets(list: List<Preset>): String {
        val arr = JSONArray()
        for (p in list) {
            val o = JSONObject()
            o.put("id", p.id)
            o.put("name", p.name)
            val ca = JSONArray()
            for (c in p.classes) ca.put(c)
            o.put("classes", ca)
            val po = JSONObject()
            for ((k, v) in p.params) po.put(k, v.toDouble())
            o.put("params", po)
            arr.put(o)
        }
        return arr.toString()
    }

    /** 解析 [Prefs.KEY_GLASS_CLASSES]；空串 / 格式错误 → 空表（不抛）。 */
    fun parseClasses(json: String?): List<DetectedClass> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            val arr = JSONArray(json)
            val out = ArrayList<DetectedClass>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val cls = o.optString("cls")
                if (cls.isEmpty()) continue
                out.add(DetectedClass(cls, o.optLong("lastSeenMs", 0L)))
            }
            out
        } catch (t: Throwable) {
            Lg.w(TAG, "parse glass_classes failed", t)
            emptyList()
        }
    }

    /** 序列化探测缓存（按类名排序，保证同内容同字节）。 */
    fun encodeClasses(list: Collection<DetectedClass>): String {
        val sorted = list.sortedBy { it.cls }
        val arr = JSONArray()
        for (d in sorted) {
            val o = JSONObject()
            o.put("cls", d.cls)
            o.put("lastSeenMs", d.lastSeenMs)
            arr.put(o)
        }
        return arr.toString()
    }

    // ------------------------------------------------------------ 探测登记（hook 侧） ------------------------------------------------------------

    private class Bound(val clazz: Class<*>, val name: String, val cardClass: String?)

    /** 元素 identityHashCode → 类快照（热路径只查这张表，避免每帧取类名）。 */
    private val bound = ConcurrentHashMap<Int, Bound>()

    /** 类名 → 最近一次出现时间（ms，uptime）。 */
    private val seen = ConcurrentHashMap<String, Long>()

    /** mount 期静态登记的类名（无实例触达，flush 时恒保留不按 24h 过期清理）。 */
    private val staticNames = java.util.concurrent.CopyOnWriteArraySet<String>()

    @Volatile
    private var dirty = false

    @Volatile
    private var lastFlushAttemptMs = 0L

    @Volatile
    private var prefsProvider: (() -> SharedPreferences?)? = null

    private const val BIND_MAP_MAX = 1024

    /** 同一类刷新最近出现时间的最小间隔（毫秒）。 */
    private const val TOUCH_INTERVAL_MS = 60_000L

    /** 回写探测缓存的最小间隔（毫秒）。 */
    private const val FLUSH_MIN_INTERVAL_MS = 60_000L

    /** 探测条目保留时长（毫秒）：未被任何预设引用且超期未出现才清理。 */
    private const val CLASS_KEEP_MS = 24L * 60 * 60 * 1000

    /** 预设解析缓存 TTL（毫秒），渲染热路径按此周期才重读一次。 */
    private const val PRESET_TTL_MS = 500L

    /** 注入框架侧存储读取器（hook 安装时调用一次）。 */
    fun attach(provider: () -> SharedPreferences?) {
        prefsProvider = provider
    }

    /** 登记当前绘制对象所属类为玻璃元素；类未变时只刷新最近出现时间。
     *  [cardClass] = 该元素所属的卡片语义类（如通知卡/磁贴），null 表示不升级，
     *  已有卡片类不会被后到的无卡片类登记抹掉。 */
    fun bind(id: Int, target: Any, cardClass: String? = null) {
        val clazz = target.javaClass
        val cur = bound[id]
        if (cur != null && cur.clazz === clazz) {
            if (cardClass != null && cardClass != cur.cardClass) {
                bound[id] = Bound(clazz, cur.name, cardClass)
                touch(cardClass)
            }
            touch(cur.name)
            return
        }
        if (bound.size >= BIND_MAP_MAX) bound.clear()
        val name = clazz.name
        bound[id] = Bound(clazz, name, cardClass)
        touch(name)
        if (cardClass != null) touch(cardClass)
    }

    /** mount 期按类名静态登记（无实例）：进清单并恒驻（不受 24h 未出现清理）。 */
    fun registerClass(name: String) {
        if (name.isEmpty()) return
        staticNames.add(name)
        touch(name)
    }

    /** 按元素 identityHashCode 取类全名；未登记 → null（渲染侧回落全局参数）。 */
    fun classOf(elementId: Int): String? = bound[elementId]?.name

    private fun touch(name: String) {
        try {
            val now = SystemClock.uptimeMillis()
            val prev = seen[name]
            if (prev != null && now - prev < TOUCH_INTERVAL_MS) return
            seen[name] = now
            if (prev == null) Lg.i(TAG, "detected glass class $name")
            dirty = true
            maybeFlush(now)
        } catch (t: Throwable) {
            Lg.w(TAG, "record glass class failed", t)
        }
    }

    /** 把探测缓存写回框架侧存储：先与已有条目合并（两进程互不覆盖），内容无变化则零写入。 */
    private fun maybeFlush(now: Long) {
        if (!dirty) return
        if (now - lastFlushAttemptMs < FLUSH_MIN_INTERVAL_MS) return
        lastFlushAttemptMs = now
        val prefs = prefsOrNull() ?: return
        try {
            val presets = parsePresets(prefs.getString(Prefs.KEY_GLASS_PRESETS, null))
            val referenced = HashSet<String>()
            for (p in presets) referenced.addAll(p.classes)
            val raw = prefs.getString(Prefs.KEY_GLASS_CLASSES, null)
            // 并入已有条目：SystemUI 与桌面是两个进程各写各的，直接覆盖会让对方的清单丢失
            val merged = HashMap<String, Long>()
            for (d in parseClasses(raw)) merged[d.cls] = d.lastSeenMs
            for ((cls, last) in seen) merged[cls] = maxOf(merged[cls] ?: 0L, last)
            val cutoff = now - CLASS_KEEP_MS
            val kept = ArrayList<DetectedClass>(merged.size)
            val keep = HashSet<String>()
            for ((cls, last) in merged) {
                if (last >= cutoff || referenced.contains(cls) || staticNames.contains(cls)) {
                    kept.add(DetectedClass(cls, last))
                    keep.add(cls)
                }
            }
            seen.keys.retainAll(keep)
            val json = encodeClasses(kept)
            if (json != raw) {
                prefs.edit().putString(Prefs.KEY_GLASS_CLASSES, json).apply()
            }
            dirty = false
        } catch (t: Throwable) {
            Lg.w(TAG, "flush glass_classes failed", t)
        }
    }

    private fun prefsOrNull(): SharedPreferences? = try {
        prefsProvider?.invoke()
    } catch (t: Throwable) {
        Lg.w(TAG, "glass prefs open failed", t)
        null
    }

    // ------------------------------------------------------------ 预设参数查询（渲染热路径） ------------------------------------------------------------

    @Volatile
    private var presetCache: Map<String, Map<String, Float>> = emptyMap()

    @Volatile
    private var presetCacheAtMs = 0L

    /**
     * 按元素所属类取预设参数覆盖表；无归属预设 / 未登记类 → null（调用方回落全局值）。
     * [PRESET_TTL_MS] 内零 IPC，TTL 到点才重读一次。
     */
    fun overridesFor(elementId: Int): Map<String, Float>? {
        if (elementId == 0) return null
        val b = bound[elementId] ?: return null
        val map = snapshotOverrides()
        if (map.isEmpty()) return null
        // 卡片类优先精确命中，未配则回落按绘制对象类命中
        return map[b.cardClass ?: b.name] ?: map[b.name]
    }

    private fun snapshotOverrides(): Map<String, Map<String, Float>> {
        val now = SystemClock.uptimeMillis()
        if (now - presetCacheAtMs < PRESET_TTL_MS) return presetCache
        presetCacheAtMs = now
        val prefs = prefsOrNull() ?: return presetCache
        try {
            val presets = parsePresets(prefs.getString(Prefs.KEY_GLASS_PRESETS, null))
            val map = HashMap<String, Map<String, Float>>()
            for (p in presets) {
                if (p.params.isEmpty()) continue
                for (c in p.classes) map[c] = p.params
            }
            presetCache = map
        } catch (t: Throwable) {
            Lg.w(TAG, "read glass_presets failed", t)
        }
        return presetCache
    }
}
