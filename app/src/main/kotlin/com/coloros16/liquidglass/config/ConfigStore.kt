package com.coloros16.liquidglass.config

import com.coloros16.liquidglass.hook.spec.ChannelSpec
import com.coloros16.liquidglass.hook.spec.EffectSpec
import com.coloros16.liquidglass.hook.spec.FieldSpec
import com.coloros16.liquidglass.hook.spec.FallbackSpec
import com.coloros16.liquidglass.hook.spec.GuardSpec
import com.coloros16.liquidglass.hook.spec.HookSpec
import com.coloros16.liquidglass.hook.spec.LogSpec
import com.coloros16.liquidglass.hook.spec.MatchSpec
import com.coloros16.liquidglass.hook.spec.ProbeSpec
import com.coloros16.liquidglass.hook.spec.RestoreSpec
import com.coloros16.liquidglass.hook.spec.SuppressSpec

/** hook 规格装配：出厂预置走 Kotlin 常量表，user 覆盖走 JSON（org.json，数组形与 parseSpecs 同构）。 */
object ConfigStore {

    fun loadDefault(): List<HookSpec> = DefaultHooksJson.DEFAULT_HOOK_SPECS

    /** 按 group 过滤出厂表；null = 全量。 */
    fun load(group: String? = null): List<HookSpec> {
        val all = DefaultHooksJson.DEFAULT_HOOK_SPECS
        return if (group == null) all else all.filter { it.group == group }
    }

    /** 解析 JSON 规格数组（"[{...},{...}]"），字段名与 HookSpec 数据类属性同名。 */
    fun parseSpecs(json: String): List<HookSpec> {
        val arr = org.json.JSONArray(json)
        val out = ArrayList<HookSpec>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(parseSpec(o))
        }
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
