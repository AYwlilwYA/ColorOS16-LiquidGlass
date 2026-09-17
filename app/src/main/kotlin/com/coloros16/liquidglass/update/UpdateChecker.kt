package com.coloros16.liquidglass.update

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 版本升级检查：请求 GitHub Releases API 取最新 tag，与当前 versionName 比对。
 *
 * **外部依赖（2026-09-17 实测确认）**：
 * `https://api.github.com/repos/AYwlilwYA/ColorOS16-LiquidGlass/releases/latest`
 * 返回 JSON 关键字段 —— `tag_name`（实测 `"v0.1.1"`）、`html_url`（release 页地址）、
 * `assets[].name` / `assets[].browser_download_url`（APK 直链）。
 * GitHub 要求请求带 `User-Agent`，缺失会拿到 403。
 *
 * **线程约定**：[fetchLatest] 是阻塞调用，**必须在子线程执行**；本类不碰主线程，
 * 回调切换由调用方负责。
 *
 * 失败一律返回 null（网络不通 / 超时 / 非 200 / JSON 结构变了都不抛给调用方）——
 * 升级提示是锦上添花，绝不能因为它让设置界面出问题。
 */
object UpdateChecker {

    private const val TAG = "LiquidGlassUpdate"
    private const val API_URL =
        "https://api.github.com/repos/AYwlilwYA/ColorOS16-LiquidGlass/releases/latest"
    private const val CONNECT_TIMEOUT_MS = 5000
    private const val READ_TIMEOUT_MS = 5000

    /** 一次检查的结果。 */
    data class Release(
        /** 归一化后的版本号（去掉前缀 v），如 `"0.1.2"` */
        val version: String,
        /** release 页面地址（`html_url`） */
        val pageUrl: String,
        /** APK 直链；该 release 没有 .apk 资产时为 null */
        val apkUrl: String?,
    )

    /** 拉取最新 release。**阻塞**，须在子线程调用；失败返回 null（不抛）。 */
    fun fetchLatest(): Release? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", "LiquidGlass-UpdateCheck")
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "update check http $code")
                return null
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            parse(body)
        } catch (t: Throwable) {
            Log.w(TAG, "update check failed", t)
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    private fun parse(body: String): Release? = try {
        val o = JSONObject(body)
        val tag = o.optString("tag_name").trim()
        if (tag.isEmpty()) {
            null
        } else {
            val assets = o.optJSONArray("assets")
            var apk: String? = null
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                        apk = a.optString("browser_download_url")
                        break
                    }
                }
            }
            Release(
                version = normalize(tag),
                pageUrl = o.optString("html_url"),
                apkUrl = apk?.takeIf { it.isNotEmpty() },
            )
        }
    } catch (t: Throwable) {
        Log.w(TAG, "update check parse failed", t)
        null
    }

    /** 去掉前导 `v`/`V`，便于与 `versionName` 比较（`"v0.1.1"` → `"0.1.1"`）。 */
    fun normalize(v: String): String {
        val t = v.trim()
        return if (t.startsWith("v") || t.startsWith("V")) t.substring(1) else t
    }

    /**
     * latest 是否比 current 新。按 `.` 分段逐段数值比较，段数不齐时缺位补 0
     * （`"1.2"` vs `"1.2.0"` → 不更新）；非数字段按 0 处理（预发布后缀如 `1.0-beta` 不参与比较）。
     */
    fun isNewer(latest: String, current: String): Boolean {
        val a = normalize(latest).split(".")
        val b = normalize(current).split(".")
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrNull(i)?.toIntOrNull() ?: 0
            val y = b.getOrNull(i)?.toIntOrNull() ?: 0
            if (x != y) return x > y
        }
        return false
    }
}
