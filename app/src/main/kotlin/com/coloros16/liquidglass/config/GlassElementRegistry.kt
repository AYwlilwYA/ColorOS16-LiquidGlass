package com.coloros16.liquidglass.config

import android.content.SharedPreferences
import android.view.View
import com.coloros16.liquidglass.log.Lg
import io.github.libxposed.api.XposedInterface
import org.json.JSONArray
import org.json.JSONObject
import java.util.WeakHashMap

/**
 * 点选固化配置层：出厂预置（编译内常量表）+ 用户条目（框架侧存储 JSON），按锚匹配。
 *
 * - 存储：[Prefs.KEY_ELEMENT_GLASS_ENTRIES]，形状 {"entries":[...], "removed":["key",...]}。
 * - 合并：预置先入表（被 remove 的跳过），用户条目按 anchorKey 覆盖（enabled=false 即禁用预置）。
 * - 缓存：load 500ms TTL；写操作（append/updateEnabled/remove）即刻刷新缓存并 bump 版本。
 * - 匹配：[matchAnchor] 按类名全等 + id（含包） + 父链 + 尺寸区间逐项校验，
 *   按 View 弱引用缓存结果，条目版本变化 / 尺寸变化时重算。
 */
object GlassElementRegistry {

    private const val TAG = "GlassElement"

    private const val LOAD_TTL_MS = 500L

    // ------------------------------------------------------------ 出厂预置（编译内常量表；hook 进程无法便捷读模块 assets） ------------------------------------------------------------

    private fun entry(
        label: String,
        cls: String,
        viewId: String?,
        idPkg: String?,
        minW: Int, maxW: Int, minH: Int, maxH: Int,
        mountType: String,
        suppressMode: String = GlassElementEntry.SUPPRESS_NONE,
        throatClass: String? = null,
        throatMethod: String? = null,
        throatSignature: String? = null,
        overrides: Map<String, Float> = emptyMap(),
        enabled: Boolean = true,
    ) = GlassElementEntry(
        label = label,
        anchorClassName = cls,
        anchorViewId = viewId,
        anchorIdPackage = idPkg,
        anchorParentChain = emptyList(),
        anchorSizeMinW = minW,
        anchorSizeMaxW = maxW,
        anchorSizeMinH = minH,
        anchorSizeMaxH = maxH,
        mountType = mountType,
        suppressMode = suppressMode,
        throatClass = throatClass,
        throatMethod = throatMethod,
        throatSignature = throatSignature,
        presetKey = "default",
        overrides = overrides,
        enabled = enabled,
    )

    private const val THROAT_SIG_CL =
        "c(android.graphics.Canvas,android.graphics.Path,float);" +
            "d(android.graphics.Canvas,android.graphics.Path,tc.b);" +
            "i(float,int,int)"

    /** 出厂预置：调查钉死的挂载目标；尺寸为宽松区间（调查未给精确 px）。 */
    private val DEFAULT_ENTRIES: List<GlassElementEntry> = listOf(
        entry(
            label = "负一屏卡片底板",
            cls = "com.coloros.common.widget.HighlightShadowConstraintLayout",
            viewId = "shortcut_card",
            idPkg = "com.coloros.assistantscreen",
            minW = 0, maxW = 4000, minH = 0, maxH = 3000,
            mountType = GlassElementEntry.MOUNT_DRAW_HOOK,
            suppressMode = GlassElementEntry.SUPPRESS_THROAT,
            throatClass = "tc.u",
            throatMethod = "c",
            throatSignature = THROAT_SIG_CL,
            overrides = mapOf("cornerRadiusDp" to 28f),
        ),
        entry(
            label = "负一屏搜索条与圆钮底板",
            cls = "com.coloros.common.widget.HighlightShadowImageView",
            viewId = null,
            idPkg = null,
            minW = 0, maxW = 2000, minH = 0, maxH = 2000,
            mountType = GlassElementEntry.MOUNT_DRAW_HOOK,
            suppressMode = GlassElementEntry.SUPPRESS_THROAT,
            throatClass = "tc.u",
            throatMethod = "c",
            throatSignature = THROAT_SIG_CL,
        ),
        entry(
            label = "推荐服务大卡",
            cls = "com.oplus.assistantscreen.cardcontainer.ui.MainPageBannerFixedCardView",
            viewId = null,
            idPkg = null,
            minW = 200, maxW = 4000, minH = 100, maxH = 3000,
            mountType = GlassElementEntry.MOUNT_BACKGROUND,
            overrides = mapOf("cornerRadiusDp" to 28f),
        ),
        entry(
            label = "搜索页应用建议卡",
            cls = "com.heytap.quicksearchbox.ui.card.AppSuggestCardView",
            viewId = null,
            idPkg = null,
            minW = 200, maxW = 3000, minH = 100, maxH = 3000,
            mountType = GlassElementEntry.MOUNT_BACKGROUND,
            overrides = mapOf("cornerRadiusDp" to 28f),
        ),
        entry(
            label = "内存优化卡",
            cls = "com.oplus.phonemanager.cardview.optimize.AIOptimizeView",
            viewId = null,
            idPkg = null,
            minW = 200, maxW = 2000, minH = 100, maxH = 1500,
            mountType = GlassElementEntry.MOUNT_BACKGROUND,
            overrides = mapOf("cornerRadiusDp" to 28f),
        ),
        entry(
            label = "相册按钮背板",
            cls = "com.oplus.gallery.basebiz.widget.BlurButtonWrapperView",
            viewId = null,
            idPkg = null,
            minW = 20, maxW = 800, minH = 20, maxH = 800,
            mountType = GlassElementEntry.MOUNT_BACKGROUND,
        ),
    )

    // ------------------------------------------------------------ 状态 ------------------------------------------------------------

    @Volatile
    private var api: XposedInterface? = null

    @Volatile
    private var cached: List<GlassElementEntry> = DEFAULT_ENTRIES

    @Volatile
    private var cachedAtMs = 0L

    @Volatile
    private var version = 0L

    private class MatchCache(var entry: GlassElementEntry?, var version: Long, var w: Int, var h: Int)

    /** View 弱引用 → 匹配结果缓存（全主线程访问）。 */
    private val matchCache = WeakHashMap<View, MatchCache>()

    /** 注入框架侧存储读取器（引擎 install 时调用一次）。 */
    fun attach(prefsApi: XposedInterface) {
        api = prefsApi
    }

    /** 条目版本：每次写操作自增；引擎据此补挂 onDraw / 咽喉 hook。 */
    fun currentVersion(): Long = version

    // ------------------------------------------------------------ 合并读取 ------------------------------------------------------------

    private data class UserStore(val entries: List<GlassElementEntry>, val removed: Set<String>)

    private fun parseStore(json: String?): UserStore {
        if (json.isNullOrEmpty()) return UserStore(emptyList(), emptySet())
        return try {
            val obj = JSONObject(json)
            val entries = ArrayList<GlassElementEntry>()
            val arr = obj.optJSONArray("entries")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    parseEntry(o)?.let { entries.add(it) }
                }
            }
            val removed = HashSet<String>()
            val rm = obj.optJSONArray("removed")
            if (rm != null) {
                for (i in 0 until rm.length()) {
                    val k = rm.optString(i)
                    if (k.isNotEmpty()) removed.add(k)
                }
            }
            UserStore(entries, removed)
        } catch (t: Throwable) {
            Lg.w(TAG, "parse user store failed", t)
            UserStore(emptyList(), emptySet())
        }
    }

    private fun parseEntry(o: JSONObject): GlassElementEntry? {
        val cls = o.optString("anchorClassName")
        if (cls.isEmpty()) return null
        val chain = ArrayList<String>()
        val ca = o.optJSONArray("anchorParentChain")
        if (ca != null) {
            for (i in 0 until ca.length()) {
                val c = ca.optString(i)
                if (c.isNotEmpty()) chain.add(c)
            }
        }
        val overrides = HashMap<String, Float>()
        val po = o.optJSONObject("overrides")
        if (po != null) {
            for (key in po.keys()) {
                val v = po.optDouble(key, Double.NaN).toFloat()
                if (!v.isNaN()) overrides[key] = v
            }
        }
        return GlassElementEntry(
            label = o.optString("label").ifEmpty { cls.substringAfterLast('.') },
            anchorClassName = cls,
            anchorViewId = o.optString("anchorViewId").takeIf { it.isNotEmpty() },
            anchorIdPackage = o.optString("anchorIdPackage").takeIf { it.isNotEmpty() },
            anchorParentChain = chain,
            anchorSizeMinW = o.optInt("anchorSizeMinW", 0),
            anchorSizeMaxW = o.optInt("anchorSizeMaxW", 0),
            anchorSizeMinH = o.optInt("anchorSizeMinH", 0),
            anchorSizeMaxH = o.optInt("anchorSizeMaxH", 0),
            mountType = o.optString("mountType").ifEmpty { GlassElementEntry.MOUNT_BACKGROUND },
            suppressMode = o.optString("suppressMode").ifEmpty { GlassElementEntry.SUPPRESS_NONE },
            throatClass = o.optString("throatClass").takeIf { it.isNotEmpty() },
            throatMethod = o.optString("throatMethod").takeIf { it.isNotEmpty() },
            throatSignature = o.optString("throatSignature").takeIf { it.isNotEmpty() },
            presetKey = o.optString("presetKey").ifEmpty { "default" },
            overrides = overrides,
            enabled = o.optBoolean("enabled", true),
        )
    }

    private fun encodeEntry(e: GlassElementEntry): JSONObject {
        val o = JSONObject()
        o.put("label", e.label)
        o.put("anchorClassName", e.anchorClassName)
        e.anchorViewId?.let { o.put("anchorViewId", it) }
        e.anchorIdPackage?.let { o.put("anchorIdPackage", it) }
        val ca = JSONArray()
        for (c in e.anchorParentChain) ca.put(c)
        o.put("anchorParentChain", ca)
        o.put("anchorSizeMinW", e.anchorSizeMinW)
        o.put("anchorSizeMaxW", e.anchorSizeMaxW)
        o.put("anchorSizeMinH", e.anchorSizeMinH)
        o.put("anchorSizeMaxH", e.anchorSizeMaxH)
        o.put("mountType", e.mountType)
        o.put("suppressMode", e.suppressMode)
        e.throatClass?.let { o.put("throatClass", it) }
        e.throatMethod?.let { o.put("throatMethod", it) }
        e.throatSignature?.let { o.put("throatSignature", it) }
        o.put("presetKey", e.presetKey)
        val po = JSONObject()
        for ((k, v) in e.overrides) po.put(k, v.toDouble())
        o.put("overrides", po)
        o.put("enabled", e.enabled)
        return o
    }

    private fun encodeStore(store: UserStore): String {
        val obj = JSONObject()
        val arr = JSONArray()
        for (e in store.entries) arr.put(encodeEntry(e))
        obj.put("entries", arr)
        val rm = JSONArray()
        for (k in store.removed) rm.put(k)
        obj.put("removed", rm)
        return obj.toString()
    }

    private fun prefsOrNull(): SharedPreferences? = try {
        api?.let { Prefs.read(it) }
    } catch (t: Throwable) {
        Lg.w(TAG, "prefs open failed", t)
        null
    }

    /** 合并表读取（500ms TTL；空 api = 只有预置，设置页等无 hook 上下文时可用）。 */
    fun load(): List<GlassElementEntry> {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - cachedAtMs < LOAD_TTL_MS) return cached
        cachedAtMs = now
        val prefs = prefsOrNull() ?: return cached
        try {
            cached = merge(parseStore(prefs.getString(Prefs.KEY_ELEMENT_GLASS_ENTRIES, null)))
        } catch (t: Throwable) {
            Lg.w(TAG, "read user store failed, keep last", t)
        }
        return cached
    }

    private fun merge(store: UserStore): List<GlassElementEntry> {
        val map = LinkedHashMap<String, GlassElementEntry>()
        for (d in DEFAULT_ENTRIES) {
            if (d.anchorKey !in store.removed) map[d.anchorKey] = d
        }
        for (e in store.entries) map[e.anchorKey] = e
        return map.values.toList()
    }

    private fun writeStore(transform: (UserStore) -> UserStore) {
        val prefs = prefsOrNull() ?: return
        synchronized(this) {
            try {
                val cur = parseStore(prefs.getString(Prefs.KEY_ELEMENT_GLASS_ENTRIES, null))
                val next = transform(cur)
                prefs.edit().putString(Prefs.KEY_ELEMENT_GLASS_ENTRIES, encodeStore(next)).apply()
                cached = merge(next)
                cachedAtMs = android.os.SystemClock.uptimeMillis()
                version++
                matchCache.clear()
            } catch (t: Throwable) {
                Lg.w(TAG, "write user store failed", t)
            }
        }
    }

    // ------------------------------------------------------------ 写通道 ------------------------------------------------------------

    /** 固化写入：按 anchorKey 覆盖用户表（同 key 旧条目替换）。 */
    fun append(entry: GlassElementEntry) {
        writeStore { store ->
            val kept = store.entries.filterNot { it.anchorKey == entry.anchorKey } + entry
            UserStore(kept, store.removed - entry.anchorKey)
        }
        Lg.i(TAG, "element appended key=${entry.anchorKey} label=${entry.label} enabled=${entry.enabled}")
    }

    /** 启停条目：写用户覆盖条目（预置条目被禁用 = 用户表同 key enabled=false）。 */
    fun updateEnabled(anchorKey: String, on: Boolean) {
        val target = load().firstOrNull { it.anchorKey == anchorKey } ?: run {
            Lg.w(TAG, "updateEnabled miss key=$anchorKey")
            return
        }
        writeStore { store ->
            val kept = store.entries.filterNot { it.anchorKey == anchorKey } +
                if (on) target.copy(enabled = true) else target.copy(enabled = false)
            UserStore(kept, store.removed - anchorKey)
        }
        Lg.i(TAG, "element enabled key=$anchorKey on=$on")
    }

    /** 删除条目：用户表移除 + 记入 removed（预置条目删除后不再回来）。 */
    fun remove(anchorKey: String) {
        writeStore { store ->
            UserStore(
                store.entries.filterNot { it.anchorKey == anchorKey },
                store.removed + anchorKey,
            )
        }
        Lg.i(TAG, "element removed key=$anchorKey")
    }

    // ------------------------------------------------------------ 锚匹配 ------------------------------------------------------------

    /** 类名全等 + id（含包） + 父链 + 尺寸区间；enabled=false / 未布局不命中。 */
    fun matchAnchor(view: View): GlassElementEntry? {
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) return null
        val ver = version
        val hit = matchCache[view]
        if (hit != null && hit.version == ver && hit.w == w && hit.h == h) return hit.entry
        val entry = computeMatch(view, w, h)
        matchCache[view] = MatchCache(entry, ver, w, h)
        return entry
    }

    private fun computeMatch(view: View, w: Int, h: Int): GlassElementEntry? {
        val cls = view.javaClass.name
        for (e in load()) {
            if (!e.enabled) continue
            if (e.anchorClassName != cls) continue
            if (!idMatches(view, e)) continue
            if (!parentChainMatches(view, e.anchorParentChain)) continue
            if (!sizeMatches(w, h, e)) continue
            return e
        }
        return null
    }

    private fun idMatches(view: View, e: GlassElementEntry): Boolean {
        if (e.anchorViewId.isNullOrEmpty()) return true
        val id = view.id
        if (id == View.NO_ID) return false
        return try {
            val full = view.resources.getResourceName(id)
            val slash = full.indexOf('/')
            if (slash < 0) return false
            val entryName = full.substring(slash + 1)
            if (entryName != e.anchorViewId) return false
            if (e.anchorIdPackage.isNullOrEmpty()) return true
            val pkgType = full.substring(0, slash)
            val colon = pkgType.indexOf(':')
            val pkg = if (colon > 0) pkgType.substring(0, colon) else pkgType
            pkg == e.anchorIdPackage
        } catch (_: Throwable) {
            false
        }
    }

    private fun parentChainMatches(view: View, chain: List<String>): Boolean {
        if (chain.isEmpty()) return true
        var p: Any? = view.parent
        var i = 0
        while (i < chain.size) {
            if (p !is View) return false
            if (p.javaClass.name != chain[i]) return false
            p = p.parent
            i++
        }
        return true
    }

    private fun sizeMatches(w: Int, h: Int, e: GlassElementEntry): Boolean {
        if (e.anchorSizeMinW > 0 && w < e.anchorSizeMinW) return false
        if (e.anchorSizeMaxW > 0 && w > e.anchorSizeMaxW) return false
        if (e.anchorSizeMinH > 0 && h < e.anchorSizeMinH) return false
        if (e.anchorSizeMaxH > 0 && h > e.anchorSizeMaxH) return false
        return true
    }

    /** 引擎补挂 hook 用：当前启用且带咽喉锚的条目，按咽喉类去重。 */
    fun enabledThroats(): List<GlassElementEntry> =
        load().filter {
            it.enabled &&
                it.throatClass != null &&
                it.mountType != GlassElementEntry.MOUNT_BACKGROUND &&
                it.suppressMode == GlassElementEntry.SUPPRESS_THROAT
        }

    /** 引擎补挂 onDraw 桥用：启用且需抑制的条目类（DRAW_HOOK / THROAT）。 */
    fun suppressedClasses(): Set<String> =
        load().filter {
            it.enabled &&
                it.mountType != GlassElementEntry.MOUNT_BACKGROUND &&
                it.suppressMode != GlassElementEntry.SUPPRESS_NONE
        }.map { it.anchorClassName }.toSet()
}
