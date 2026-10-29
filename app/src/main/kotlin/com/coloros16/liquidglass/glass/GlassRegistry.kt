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
 * 四块职责：
 * - [bind]：绘制对象出现时登记其类名（进程内判重，热路径只做一次查表），
 *   聚合后按「集合有变化或超过刷新间隔」写入 [Prefs.KEY_GLASS_CLASSES] 缓存。
 * - [overridesFor]：按元素所属类取预设参数覆盖表（500ms TTL 解析缓存，热路径零 IPC）。
 * - [cornerOverrideFor]：按元素所属类取圆角覆盖值（同款 TTL；缺失 = 跟随实测圆角）。
 * - [parsePresets] / [encodePresets] / [parseClasses] / [encodeClasses] /
 *   [parseCornerOverrides] / [encodeCornerOverrides]：两端共用的 JSON 编解码。
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
        Prefs.KEY_HIGHLIGHT_FALLOFF,
        Prefs.KEY_HIGHLIGHT_WIDTH,
        Prefs.KEY_HIGHLIGHT_FLOOR,
        Prefs.KEY_HIGHLIGHT_INNER_ATT,
        Prefs.KEY_LIGHT_ANGLE,
        Prefs.KEY_REFRACTION_HEIGHT_RATIO,
        Prefs.KEY_REFRACTION_HEIGHT_PX,
        Prefs.KEY_MASK_GLASS_DIM,
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

    /** 解析 [Prefs.KEY_CORNER_OVERRIDES]（类全名 → 圆角半径 px）；空串 / 格式错误 → 空表（不抛）。 */
    fun parseCornerOverrides(json: String?): Map<String, Float> {
        if (json.isNullOrEmpty()) return emptyMap()
        return try {
            val obj = JSONObject(json)
            val out = HashMap<String, Float>(obj.length())
            for (key in obj.keys()) {
                val v = obj.optDouble(key, Double.NaN).toFloat()
                if (!v.isNaN()) out[key] = v
            }
            out
        } catch (t: Throwable) {
            Lg.w(TAG, "parse glass_corner_overrides failed", t)
            emptyMap()
        }
    }

    /** 序列化圆角覆盖表（按类名排序，同内容同字节）。 */
    fun encodeCornerOverrides(map: Map<String, Float>): String {
        val obj = JSONObject()
        for (key in map.keys.sorted()) obj.put(key, map[key]!!.toDouble())
        return obj.toString()
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

    /** 审计日志每 id 最小打印间隔（毫秒）。 */
    private const val AUDIT_PER_ID_INTERVAL_MS = 1000L

    /** 预设查表审计（每 id 每秒最多一条，定位绑定类/命中键） */
    private val lookupAudit = ConcurrentHashMap<Int, Long>()

    /** 预设查表时未绑定的元素 id（每元素一条，抓注册 id 与渲染 id 错配） */
    private val unboundAudit = ConcurrentHashMap.newKeySet<Int>()

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
        if (bound.size >= BIND_MAP_MAX) evictBoundBeforeInsert()
        val name = clazz.name
        bound[id] = Bound(clazz, name, cardClass)
        touch(name)
        if (cardClass != null) touch(cardClass)
    }

    /** 水位淘汰：优先删无卡片语义的条目保住高价值条目；无可删才整表清空兜底。 */
    private fun evictBoundBeforeInsert() {
        val toRemove = ArrayList<Int>()
        for ((id, b) in bound) {
            if (b.cardClass == null) toRemove.add(id)
        }
        if (toRemove.isEmpty()) {
            bound.clear()
        } else {
            for (id in toRemove) bound.remove(id)
        }
    }

    /** mount 期按类名静态登记（无实例）：进清单并恒驻（不受 24h 未出现清理）。 */
    fun registerClass(name: String) {
        if (name.isEmpty()) return
        staticNames.add(name)
        touch(name)
    }

    /** 按元素 identityHashCode 取类全名；未登记 → null（渲染侧回落全局参数）。 */
    fun classOf(elementId: Int): String? = bound[elementId]?.name

    /** 类全名 → 中文标签（语义来自各 hook mount 处的反编译确认）。 */
    private val CLASS_LABELS = mapOf(
        "com.oplus.posteffect.BlurDrawable" to "通用模糊背景（多卡共用）",
        "com.oplus.posteffect.drawable.BaseDrawable" to "桌面玻璃基类",
        "com.oplus.posteffect.drawable.MetaBallBlurDrawable" to "通知灯泡圆球",
        "com.oplus.posteffect.drawable.ContinuousBlurDrawable" to "连续模糊层",
        "com.oplusos.systemui.common.blurability.drawable.MaskDrawable" to "侧滑操作菜单",
        "com.oplusos.systemui.common.blurability.drawable.AutoBlurDrawable" to "磁贴模糊背景",
        "com.oplus.systemui.qs.base.res.drawable.MixColorTileDrawable" to "快捷开关磁贴",
        "com.oplus.systemui.qs.base.widget.LightStyleStrokeDrawable" to "描边磁贴",
        "com.oplus.systemui.statusbar.notification.row.NotificationBackgroundViewExtImp" to "通知卡片",
        "com.oplus.systemui.statusbar.notification.customcard.OplusCustomRow" to "自定义通知卡行",
        "com.oplus.systemui.notification.lockscreen.capsule.CapsuleBackgroundHelper" to "锁屏胶囊背景",
        "com.oplus.systemui.notification.lockscreen.capsule.CapsuleEarView" to "锁屏胶囊耳朵",
        "com.oplus.systemui.media.controls.ui.OplusMediaCarouselView" to "媒体控制横幅",
        "com.android.launcher3.folder.OplusPreviewBackground" to "文件夹背景",
        "com.android.launcher3.folder.FolderRoundImageView" to "文件夹图标",
        "com.android.launcher3.OplusHotseat" to "Dock 底栏",
        "com.android.launcher.pageindicators.OplusPageIndicator" to "问小布搜索栏入口",
        "com.android.launcher3.card.CardBackgroundBlurManager" to "桌面卡片 / 小组件背景",
        "com.oplus.systemui.volume.OplusVolumeSeekBar" to "音量条",
        "com.oplus.systemui.qs.base.seek.OplusQsVerticalSeekBar" to "控制中心亮度音量滑杆",
        "com.oplus.systemui.qs.widget.OplusToggleSeekBar" to "横向亮度滑条",
        "com.oplus.deviceplugin.sdk.ui.view.separatecardview.SquareDeviceCardView" to "设备中心卡片（方形）",
        "com.oplus.deviceplugin.sdk.ui.view.separatecardview.RectangleDeviceCardView" to "设备中心卡片（长条）",
        "com.oplus.gallery.photo_page.widget.materialblur.blur.BlendPhotoBlurView" to "相册照片页背景",
        "com.oplus.gallery.basebiz.widget.BackgroundBlurView" to "相册通用背景模糊层",
        "com.android.internal.graphics.drawable.BackgroundBlurDrawable" to "窗口背景模糊层",
        "com.oplus.smartsidebar.panelview.edgepanel.mainpanel.UserPanelView" to "智能侧边栏面板",
        "com.coui.appcompat.seekbar.COUIVerticalSeekBar" to "垂直滑条基类",
        "com.coui.appcompat.seekbar.COUISeekBar" to "横向滑条基类",
        "com.coui.appcompat.poplist.COUIPopupListWindow" to "通知长按菜单",
        "com.android.launcher.views.PressFeedbackButton" to "最近任务清除按钮",
    )

    /** 类全名 → 位置描述的 strings.xml 资源名（「在哪里的」一句话；UI 侧经 [classLocationKey] 取用）。 */
    private val CLASS_LOCATION_KEYS = mapOf(
        "com.oplus.posteffect.BlurDrawable" to "loc_blur_drawable",
        "com.oplus.posteffect.drawable.BaseDrawable" to "loc_base_drawable",
        "com.oplus.posteffect.drawable.MetaBallBlurDrawable" to "loc_metaball",
        "com.oplus.posteffect.drawable.ContinuousBlurDrawable" to "loc_continuous",
        "com.oplusos.systemui.common.blurability.drawable.MaskDrawable" to "loc_mask_drawable",
        "com.oplusos.systemui.common.blurability.drawable.AutoBlurDrawable" to "loc_auto_blur",
        "com.oplus.systemui.qs.base.res.drawable.MixColorTileDrawable" to "loc_mix_color_tile",
        "com.oplus.systemui.qs.base.widget.LightStyleStrokeDrawable" to "loc_light_stroke",
        "com.oplus.systemui.statusbar.notification.row.NotificationBackgroundViewExtImp" to "loc_notif_card",
        "com.oplus.systemui.statusbar.notification.customcard.OplusCustomRow" to "loc_custom_row",
        "com.oplus.systemui.notification.lockscreen.capsule.CapsuleBackgroundHelper" to "loc_capsule_bg",
        "com.oplus.systemui.notification.lockscreen.capsule.CapsuleEarView" to "loc_capsule_ear",
        "com.oplus.systemui.media.controls.ui.OplusMediaCarouselView" to "loc_media_carousel",
        "com.android.launcher3.folder.OplusPreviewBackground" to "loc_folder_bg",
        "com.android.launcher3.folder.FolderRoundImageView" to "loc_folder_icon",
        "com.android.launcher3.OplusHotseat" to "loc_hotseat",
        "com.android.launcher.pageindicators.OplusPageIndicator" to "loc_page_indicator",
        "com.android.launcher3.card.CardBackgroundBlurManager" to "loc_launcher_card",
        "com.oplus.systemui.volume.OplusVolumeSeekBar" to "loc_volume_seekbar",
        "com.oplus.systemui.qs.base.seek.OplusQsVerticalSeekBar" to "loc_qs_brightness",
        "com.oplus.systemui.qs.widget.OplusToggleSeekBar" to "loc_toggle_seekbar",
        "com.oplus.deviceplugin.sdk.ui.view.separatecardview.SquareDeviceCardView" to "loc_device_card_square",
        "com.oplus.deviceplugin.sdk.ui.view.separatecardview.RectangleDeviceCardView" to "loc_device_card_rect",
        "com.oplus.gallery.photo_page.widget.materialblur.blur.BlendPhotoBlurView" to "loc_gallery_photo_bg",
        "com.oplus.gallery.basebiz.widget.BackgroundBlurView" to "loc_gallery_bg_view",
        "com.android.internal.graphics.drawable.BackgroundBlurDrawable" to "loc_window_blur",
        "com.oplus.smartsidebar.panelview.edgepanel.mainpanel.UserPanelView" to "loc_smart_sidebar",
        "com.coui.appcompat.seekbar.COUIVerticalSeekBar" to "loc_vertical_seekbar",
        "com.coui.appcompat.seekbar.COUISeekBar" to "loc_horizontal_seekbar",
        "com.coui.appcompat.poplist.COUIPopupListWindow" to "loc_notification_popup_menu",
        "com.android.launcher.views.PressFeedbackButton" to "loc_clear_all_button",
    )

    /** 清单/对话框展示用：有标签显示「标签（简单类名）」，无标签只显示简单类名。 */
    fun classLabel(cls: String): String {
        val simple = cls.substringAfterLast('.')
        val label = CLASS_LABELS[cls] ?: return simple
        return "$label（$simple）"
    }

    /** 清单/对话框展示用：该类的位置描述字符串资源名；未登记 → null。 */
    fun classLocationKey(cls: String): String? = CLASS_LOCATION_KEYS[cls]

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
        val b = bound[elementId] ?: run {
            if (unboundAudit.size <= 128 && unboundAudit.add(elementId)) {
                Lg.i(TAG, "preset-lookup: id=$elementId UNBOUND")
            }
            return null
        }
        val map = snapshotOverrides()
        val auditNowMs = SystemClock.uptimeMillis()
        val auditPrevMs = lookupAudit[elementId] ?: 0L
        if (auditNowMs - auditPrevMs >= AUDIT_PER_ID_INTERVAL_MS) {
            if (lookupAudit.size > 512) lookupAudit.clear()
            lookupAudit[elementId] = auditNowMs
            val hit = when {
                b.cardClass != null && map.containsKey(b.cardClass) -> b.cardClass
                map.containsKey(b.name) -> b.name
                else -> null
            }
            Lg.i(
                TAG, "preset-lookup: id=$elementId name=${b.name.substringAfterLast('.')} " +
                    "card=${b.cardClass?.substringAfterLast('.')} hit=$hit presets=${map.size}"
            )
        }
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

    // ------------------------------------------------------------ 圆角覆盖查询（渲染热路径） ------------------------------------------------------------

    @Volatile
    private var cornerCache: Map<String, Float> = emptyMap()

    @Volatile
    private var cornerCacheAtMs = 0L

    /**
     * 按元素所属类取圆角覆盖值（px）；无覆盖 / 未登记类 → null（调用方回落实测圆角）。
     * 命中顺序与 [overridesFor] 一致：卡片类优先，回落绘制对象类。
     * [PRESET_TTL_MS] 内零 IPC，TTL 到点才重读一次。
     */
    fun cornerOverrideFor(elementId: Int): Float? {
        if (elementId == 0) return null
        val b = bound[elementId] ?: return null
        val map = snapshotCornerOverrides()
        if (map.isEmpty()) return null
        return map[b.cardClass ?: b.name] ?: map[b.name]
    }

    private fun snapshotCornerOverrides(): Map<String, Float> {
        val now = SystemClock.uptimeMillis()
        if (now - cornerCacheAtMs < PRESET_TTL_MS) return cornerCache
        cornerCacheAtMs = now
        val prefs = prefsOrNull() ?: return cornerCache
        try {
            cornerCache = parseCornerOverrides(prefs.getString(Prefs.KEY_CORNER_OVERRIDES, null))
        } catch (t: Throwable) {
            Lg.w(TAG, "read glass_corner_overrides failed", t)
        }
        return cornerCache
    }
}
