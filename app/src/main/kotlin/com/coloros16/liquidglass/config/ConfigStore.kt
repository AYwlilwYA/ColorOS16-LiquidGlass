package com.coloros16.liquidglass.config

import com.coloros16.liquidglass.hook.spec.ChannelSpec
import com.coloros16.liquidglass.hook.spec.CornerSpec
import com.coloros16.liquidglass.hook.spec.EffectSpec
import com.coloros16.liquidglass.hook.spec.EngineLog
import com.coloros16.liquidglass.hook.spec.FieldSpec
import com.coloros16.liquidglass.hook.spec.FallbackSpec
import com.coloros16.liquidglass.hook.spec.GuardSpec
import com.coloros16.liquidglass.hook.spec.HookSpec
import com.coloros16.liquidglass.hook.spec.LogSpec
import com.coloros16.liquidglass.hook.spec.MatchSpec
import com.coloros16.liquidglass.log.Lg
import com.coloros16.liquidglass.hook.spec.ProbeSpec
import com.coloros16.liquidglass.hook.spec.RestoreSpec
import com.coloros16.liquidglass.hook.spec.SuppressSpec

/** hook 规格装配：出厂预置走 Kotlin 常量表，user 覆盖走 JSON（org.json，数组形与 parseSpecs 同构）。 */
object ConfigStore {

    private const val TAG = "LiquidGlass"

    fun loadDefault(): List<HookSpec> = DefaultHooksJson.DEFAULT_HOOK_SPECS

    /**
     * 出厂表 + 用户 JSON 覆盖合并（install 期一次）：user 条目按 id **整条替换**出厂条目
     * （user JSON 需携带完整字段，缺省字段走 parseSpecs 默认值）；user 独有 id 追加。
     * 读失败 / 空键 → 纯出厂表（零变化）。
     */
    fun loadMerged(prefs: android.content.SharedPreferences?): List<HookSpec> {
        val factory = DefaultHooksJson.DEFAULT_HOOK_SPECS
        val userJson = try {
            prefs?.getString(Prefs.KEY_USER_HOOK_SPECS, null)
        } catch (t: Throwable) {
            null
        }
        if (userJson.isNullOrBlank()) {
            EngineLog.log("ConfigStore", "loadMerged user-json=none factory=${factory.size}")
            return factory
        }
        val user = try {
            parseSpecs(userJson)
        } catch (t: Throwable) {
            Lg.w(TAG, "user hook specs parse failed, factory only", t)
            EngineLog.log("ConfigStore", "loadMerged user-json parse-failed ${t.javaClass.simpleName}")
            return factory
        }
        val userById = user.associateBy { it.id }
        val merged = factory.map { userById[it.id] ?: it } + user.filter { u -> factory.none { it.id == u.id } }
        EngineLog.log("ConfigStore", "loadMerged factory=${factory.size} user=${user.size} merged=${merged.size}")
        for (u in user) EngineLog.log("ConfigStore", "user-override id=${u.id} enabled=${u.enabled}")
        return merged
    }

    /** 按 group 过滤出厂表；null = 全量。 */
    fun load(group: String? = null): List<HookSpec> {
        val all = DefaultHooksJson.DEFAULT_HOOK_SPECS
        val out = if (group == null) all else all.filter { it.group == group }
        EngineLog.log("ConfigStore", "load group=${group ?: "all"} total=${all.size} matched=${out.size}")
        for (s in out) EngineLog.log("ConfigStore", "spec id=${s.id} group=${s.group ?: "-"}")
        return out
    }

    /** 解析 JSON 规格数组（"[{...},{...}]"），字段名与 HookSpec 数据类属性同名。 */
    fun parseSpecs(json: String): List<HookSpec> {
        val arr = org.json.JSONArray(json)
        val out = ArrayList<HookSpec>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(parseSpec(o))
        }
        EngineLog.log("ConfigStore", "parseSpecs user-json count=${out.size}")
        return out
    }

    private fun parseSpec(o: org.json.JSONObject): HookSpec {
        val matchO = o.optJSONObject("match") ?: org.json.JSONObject()
        val channelO = o.optJSONObject("channel") ?: org.json.JSONObject()
        val effectO = o.optJSONObject("effect") ?: org.json.JSONObject()
        return HookSpec(
            id = o.getString("id"),
            enabled = o.optBoolean("enabled", true),
            priority = o.optInt("priority", 0),
            group = o.optStringOrNull("group"),
            capture = o.optStringOrNull("capture"),
            match = MatchSpec(
                className = matchO.optStringOrNull("className"),
                methodName = matchO.optStringOrNull("methodName"),
                params = matchO.optJSONArray("params").toStringList(),
                returnType = matchO.optStringOrNull("returnType"),
                classLoader = matchO.optString("classLoader", "host"),
                declared = matchO.optBoolean("declared", false),
                accessible = matchO.optBoolean("accessible", false),
            ),
            channel = ChannelSpec(
                type = channelO.getString("type"),
                hookPoint = channelO.optStringOrNull("hookPoint"),
                scope = channelO.optString("scope", "global"),
                order = channelO.optString("order", "before"),
            ),
            effect = EffectSpec(
                type = effectO.getString("type"),
                shaderVariant = effectO.optStringOrNull("shaderVariant"),
                fallbackOnError = effectO.optString("fallbackOnError", "proceed"),
            ),
            suppress = parseSuppress(o.optJSONObject("suppress")),
            probe = parseProbe(o.optJSONObject("probe")),
            restore = RestoreSpec(),
            fallback = FallbackSpec(),
            log = LogSpec(),
            guard = GuardSpec(),
            corner = parseCorner(o.optJSONObject("corner")),
        )
    }

    /** 解析 corner 节（缺省/空对象 → 全默认 CornerSpec，与出厂常量表同构）。 */
    fun parseCorner(o: org.json.JSONObject?): CornerSpec {
        if (o == null) return CornerSpec()
        return CornerSpec(
            mode = o.optString("mode", "write-through"),
            adaptiveClamp = o.optBoolean("adaptiveClamp", true),
            externalSync = o.optBoolean("externalSync", true),
            valueDp = o.optDouble("valueDp", 0.0).toFloat(),
            shapeClamp = o.optString("shapeClamp", "auto"),
            channels = o.optJSONArray("channels").toStringList(),
            logKey = o.optString("logKey", "corner"),
        )
    }

    private fun parseSuppress(o: org.json.JSONObject?): SuppressSpec {
        if (o == null) return SuppressSpec()
        val fields = ArrayList<FieldSpec>()
        val fieldsArr = o.optJSONArray("fields")
        if (fieldsArr != null) {
            for (i in 0 until fieldsArr.length()) {
                val fo = fieldsArr.optJSONObject(i) ?: continue
                fields.add(
                    FieldSpec(
                        name = fo.getString("name"),
                        ownerClass = fo.optStringOrNull("ownerClass"),
                        neutralValue = fo.optStringOrNull("neutralValue"),
                    )
                )
            }
        }
        return SuppressSpec(
            mode = o.optString("mode", "none"),
            fields = fields,
            neutralValue = o.optStringOrNull("neutralValue"),
            restoreInFinally = o.optBoolean("restoreInFinally", true),
            onlyWhen = o.optStringOrNull("onlyWhen"),
        )
    }

    private fun parseProbe(o: org.json.JSONObject?): ProbeSpec? {
        if (o == null) return null
        return ProbeSpec(
            group = o.getString("group"),
            role = o.getString("role"),
            ttlMs = o.optLong("ttlMs", 5000),
            scope = o.optString("scope", "instance"),
        )
    }

    private fun org.json.JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val v = optString(key, "")
        return v.ifEmpty { null }
    }

    private fun org.json.JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val out = ArrayList<String>(length())
        for (i in 0 until length()) {
            optString(i)?.let { if (it.isNotEmpty()) out.add(it) }
        }
        return out
    }
}
