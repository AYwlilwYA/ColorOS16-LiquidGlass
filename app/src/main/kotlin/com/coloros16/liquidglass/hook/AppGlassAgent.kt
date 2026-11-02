package com.coloros16.liquidglass.hook

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import com.coloros16.liquidglass.config.GlassElementEntry
import com.coloros16.liquidglass.config.GlassElementRegistry
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.debug.ACTION_FREEZE_REQUEST
import com.coloros16.liquidglass.debug.AppGlassWorkbench
import com.coloros16.liquidglass.debug.ACTION_FREEZE_RESULT
import com.coloros16.liquidglass.debug.ACTION_SCAN_REQUEST
import com.coloros16.liquidglass.debug.ACTION_SCAN_RESULT
import com.coloros16.liquidglass.debug.ACTION_TOOL_CAPTURE_REQUEST
import com.coloros16.liquidglass.debug.ACTION_TOOL_CAPTURE_RESULT
import com.coloros16.liquidglass.debug.ACTION_TOOL_LIST_REQUEST
import com.coloros16.liquidglass.debug.ACTION_TOOL_LIST_RESULT
import com.coloros16.liquidglass.debug.ACTION_TOOL_TREE_REQUEST
import com.coloros16.liquidglass.debug.ACTION_TOOL_TREE_RESULT
import com.coloros16.liquidglass.hook.spec.EngineLog
import com.coloros16.liquidglass.log.Lg
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * 注入进程代理（全局悬浮球的进程内执行端）：
 * 收模块进程的 [ACTION_SCAN_REQUEST] → decorView 遍历回传控件框；
 * 收 [ACTION_FREEZE_REQUEST] → 屏幕坐标命中 → 采锚固化写 [GlassElementRegistry] → 引擎热挂 → 回传结果。
 * 应用级 [AppGlassWorkbench] 已停启动（备查保留），扫描经 [AppGlassWorkbench.layerView] 兜底排除。
 */
object AppGlassAgent {

    private const val TAG = "AppGlassAgent"

    private const val MODULE_PKG = "com.coloros16.liquidglass"

    private const val MAX_SCAN_DEPTH = 8
    private const val MAX_SCAN_NODES = 2000
    private const val MAX_REPORT_ITEMS = 200
    private const val PARENT_CHAIN_LEVELS = 3
    private const val SIZE_TOLERANCE = 0.3f

    private const val MOUNT_BACKGROUND = "BACKGROUND"
    private const val MOUNT_DRAW_HOOK = "DRAW_HOOK"
    private const val MOUNT_THROAT = "THROAT"

    private const val SUPPRESS_NONE = "NONE"
    private const val SUPPRESS_THROAT = "THROAT"

    private const val THROAT_CLASS_DEFAULT = "tc.u"
    private const val THROAT_METHOD_DEFAULT = "c"
    private const val THROAT_SIGNATURE_DEFAULT = "c(Canvas,Path,float)"

    private const val REQUEST_THROTTLE_MS = 1000L

    private val mainHandler = Handler(Looper.getMainLooper())

    private var receiverRegistered = false
    private var resumeHooked = false

    private var currentActivity = WeakReference<Activity>(null)
    private var appContext: Context? = null

    @Volatile
    private var lastScanAtMs = 0L

    @Volatile
    private var lastFreezeAtMs = 0L

    private val loggedOnce = ConcurrentHashMap.newKeySet<String>()

    /** AppGlassHook.install 尾启动：挂 resume 取上下文，Activity 到位后注册广播接收。 */
    fun ensureStarted(api: XposedInterface, packageName: String) {
        Lg.i(TAG, "agent started in $packageName")
        EngineLog.attach { Prefs.read(api) }
        EngineLog.log("AppAgent", "started pkg=$packageName")
        hookResume(api)
        tryRegister()
    }

    private fun tryRegister() {
        val ctx = resolveContext()
        if (ctx == null) {
            mainHandler.postDelayed({ tryRegister() }, 1000L)
            return
        }
        appContext = ctx.applicationContext
        if (receiverRegistered) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                try {
                    val sender = if (Build.VERSION.SDK_INT >= 34) getSentFromPackage() else null
                    if (sender != MODULE_PKG) {
                        Lg.w(TAG, "reject broadcast action=${intent.action} sender=$sender")
                        EngineLog.log("AppAgent", "reject action=${intent.action} sender=$sender")
                        return
                    }
                    EngineLog.log("AppAgent", "broadcast action=${intent.action} sender=$sender")
                    when (intent.action) {
                        ACTION_SCAN_REQUEST -> handleScanRequest()
                        ACTION_FREEZE_REQUEST -> handleFreezeRequest(intent)
                        ACTION_TOOL_CAPTURE_REQUEST -> handleCaptureRequest(intent)
                        ACTION_TOOL_TREE_REQUEST -> handleTreeRequest()
                        ACTION_TOOL_LIST_REQUEST -> handleListRequest()
                    }
                } catch (t: Throwable) {
                    Lg.w(TAG, "onReceive error action=${intent.action}", t)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_SCAN_REQUEST)
            addAction(ACTION_FREEZE_REQUEST)
            addAction(ACTION_TOOL_CAPTURE_REQUEST)
            addAction(ACTION_TOOL_TREE_REQUEST)
            addAction(ACTION_TOOL_LIST_REQUEST)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.applicationContext.registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                ctx.applicationContext.registerReceiver(r, filter)
            }
            receiverRegistered = true
            Lg.i(TAG, "receiver registered (SCAN/FREEZE/TOOL)")
        } catch (t: Throwable) {
            Lg.w(TAG, "register receiver failed, retry later", t)
            mainHandler.postDelayed({ tryRegister() }, 2000L)
        }
    }

    /** 上下文链：ActivityThread.currentApplication → 已捕获 Activity 的 applicationContext。 */
    private fun resolveContext(): Context? {
        try {
            val app = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? Context
            if (app != null) return app
        } catch (_: Throwable) {
        }
        return currentActivity.get()?.let { it.applicationContext }
    }

    // ---------- 扫描 ----------

    private fun handleScanRequest() {
        val now = SystemClock.uptimeMillis()
        if (now - lastScanAtMs < REQUEST_THROTTLE_MS) {
            Lg.i(TAG, "[scan-throttle] scan request throttled")
            EngineLog.log("AppAgent", "scan-request throttled")
            return
        }
        lastScanAtMs = now
        EngineLog.log("AppAgent", "scan-request accepted")
        mainHandler.post {
            val decor = decorOrNull()
            if (decor == null) {
                sendScanResult(JSONArray(), 0)
                logOnce("scan:no-window", "scan requested but no activity window")
                return@post
            }
            val items = JSONArray()
            var collected = 0
            var truncated = 0
            val workbenchLayer = try {
                AppGlassWorkbench.layerView()
            } catch (_: Throwable) {
                null
            }
            fun walk(view: View, depth: Int) {
                if (depth > MAX_SCAN_DEPTH) return
                if (collected + truncated >= MAX_SCAN_NODES) return
                if (view === workbenchLayer) return
                if (view.isShown && view.width > 0 && view.height > 0) {
                    val loc = IntArray(2)
                    view.getLocationOnScreen(loc)
                    val obj = JSONObject().apply {
                        put("cls", view.javaClass.name)
                        put("id", resolveIdEntry(view))
                        put("x", loc[0])
                        put("y", loc[1])
                        put("w", view.width)
                        put("h", view.height)
                        put("simpleLabel", view.javaClass.simpleName)
                        put("fixed", if (matchFixed(view)) 1 else 0)
                    }
                    if (collected < MAX_REPORT_ITEMS) {
                        items.put(obj)
                        collected++
                    } else {
                        truncated++
                    }
                }
                if (view is ViewGroup) {
                    for (i in 0 until view.childCount) {
                        walk(view.getChildAt(i), depth + 1)
                    }
                }
            }
            for (i in 0 until decor.childCount) {
                walk(decor.getChildAt(i), 1)
            }
            sendScanResult(items, truncated)
            logOnce("scan:done", "scan done items=$collected truncated=$truncated")
        }
    }

    private fun decorOrNull(): ViewGroup? {
        val act = currentActivity.get() ?: return null
        if (!act.isFinishing && !act.isDestroyed) {
            return act.window?.decorView as? ViewGroup
        }
        return null
    }

    private fun resolveIdEntry(view: View): String {
        if (view.id == View.NO_ID) return ""
        return try {
            view.resources.getResourceEntryName(view.id)
        } catch (_: Throwable) {
            "0x" + Integer.toHexString(view.id)
        }
    }

    private fun matchFixed(view: View): Boolean = try {
        GlassElementRegistry.matchAnchor(view) != null
    } catch (_: Throwable) {
        false
    }

    private fun sendScanResult(items: JSONArray, truncated: Int) {
        val ctx = appContext ?: return
        EngineLog.log("AppAgent", "scan-result items=${items.length()} truncated=$truncated")
        try {
            ctx.sendBroadcast(
                Intent(ACTION_SCAN_RESULT).setPackage(MODULE_PKG)
                    .putExtra("pkg", ctx.packageName)
                    .putExtra("items", items.toString())
                    .putExtra("truncated", truncated),
            )
        } catch (t: Throwable) {
            Lg.w(TAG, "send scan result failed", t)
        }
    }

    // ---------- 固化 ----------

    private fun handleFreezeRequest(intent: Intent) {
        val now = SystemClock.uptimeMillis()
        if (now - lastFreezeAtMs < REQUEST_THROTTLE_MS) {
            Lg.i(TAG, "[freeze-throttle] freeze request throttled")
            EngineLog.log("AppAgent", "freeze-request throttled")
            return
        }
        lastFreezeAtMs = now
        EngineLog.log("AppAgent", "freeze-request x=${intent.getIntExtra("x", Int.MIN_VALUE)} y=${intent.getIntExtra("y", Int.MIN_VALUE)}")
        val rawX = intent.getIntExtra("x", Int.MIN_VALUE)
        val rawY = intent.getIntExtra("y", Int.MIN_VALUE)
        if (rawX == Int.MIN_VALUE || rawY == Int.MIN_VALUE) {
            sendFreezeResult(false, "", "missing coords")
            return
        }
        val cx = rawX + intent.getIntExtra("w", 0) / 2
        val cy = rawY + intent.getIntExtra("h", 0) / 2
        mainHandler.post {
            val decor = decorOrNull()
            if (decor == null) {
                sendFreezeResult(false, "", "no activity window")
                return@post
            }
            val hit = findDeepestAt(decor, cx, cy)
            if (hit == null) {
                sendFreezeResult(false, "", "no view at point")
                logOnce("freeze:miss", "freeze miss at ($cx,$cy)")
                return@post
            }
            val entry = buildEntry(hit)
            try {
                GlassElementRegistry.append(entry)
            } catch (t: Throwable) {
                Lg.w(TAG, "append failed", t)
                sendFreezeResult(false, entry.label, "append failed: ${t.javaClass.simpleName}")
                return@post
            }
            try {
                PageGlassEngine.rescanNow()
            } catch (t: Throwable) {
                Lg.w(TAG, "rescan failed", t)
            }
            sendFreezeResult(true, entry.label, "")
            Lg.i(
                TAG,
                "element-frozen label=${entry.label} cls=${entry.anchorClassName} " +
                    "mount=${entry.mountType} suppress=${entry.suppressMode}",
            )
            EngineLog.log("AppAgent", "frozen label=${entry.label} cls=${entry.anchorClassName} mount=${entry.mountType} suppress=${entry.suppressMode}")
        }
    }

    /** 按屏幕点找最深命中 View（后序：子优先于父）。 */
    private fun findDeepestAt(root: ViewGroup, x: Int, y: Int): View? {
        var hit: View? = null
        fun walk(view: View, depth: Int) {
            if (depth > MAX_SCAN_DEPTH) return
            if (view.isShown && view.width > 0 && view.height > 0) {
                val loc = IntArray(2)
                view.getLocationOnScreen(loc)
                if (x >= loc[0] && x < loc[0] + view.width &&
                    y >= loc[1] && y < loc[1] + view.height
                ) {
                    hit = view
                }
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    walk(view.getChildAt(i), depth + 1)
                }
            }
        }
        walk(root, 0)
        return hit
    }

    /** 采锚四件套（类名/id/父链3级/尺寸±30%）构造固化条目；mount/suppress 自动判定。 */
    private fun buildEntry(view: View): GlassElementEntry {
        val cls = view.javaClass
        val idEntry = resolveIdEntry(view).ifEmpty { null }
        val idPkg = if (view.id != View.NO_ID) {
            try {
                view.resources.getResourcePackageName(view.id)
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }
        val chain = ArrayList<String>(PARENT_CHAIN_LEVELS)
        var p = view.parent as? View
        var n = 0
        while (p != null && n < PARENT_CHAIN_LEVELS) {
            chain.add(p.javaClass.name)
            p = p.parent as? View
            n++
        }
        val bgName = try {
            view.background?.javaClass?.name
        } catch (_: Throwable) {
            null
        }
        val isHighlight = cls.name.contains("HighlightShadow")
        val mount = when {
            bgName != null -> MOUNT_BACKGROUND
            isHighlight -> MOUNT_THROAT
            else -> MOUNT_DRAW_HOOK
        }
        val suppress = if (isHighlight) SUPPRESS_THROAT else SUPPRESS_NONE
        return GlassElementEntry(
            label = cls.simpleName,
            anchorClassName = cls.name,
            anchorViewId = idEntry,
            anchorIdPackage = idPkg,
            anchorParentChain = chain,
            anchorSizeMinW = (view.width * (1f - SIZE_TOLERANCE)).toInt().coerceAtLeast(1),
            anchorSizeMaxW = (view.width * (1f + SIZE_TOLERANCE)).toInt().coerceAtLeast(1),
            anchorSizeMinH = (view.height * (1f - SIZE_TOLERANCE)).toInt().coerceAtLeast(1),
            anchorSizeMaxH = (view.height * (1f + SIZE_TOLERANCE)).toInt().coerceAtLeast(1),
            mountType = mount,
            suppressMode = suppress,
            throatClass = if (isHighlight) THROAT_CLASS_DEFAULT else null,
            throatMethod = if (isHighlight) THROAT_METHOD_DEFAULT else null,
            throatSignature = if (isHighlight) THROAT_SIGNATURE_DEFAULT else null,
            presetKey = "default",
            overrides = emptyMap(),
            enabled = true,
        )
    }

    private fun sendFreezeResult(ok: Boolean, label: String, reason: String) {
        val ctx = appContext ?: return
        EngineLog.log("AppAgent", "freeze-result ok=$ok label=$label reason=$reason")
        try {
            ctx.sendBroadcast(
                Intent(ACTION_FREEZE_RESULT).setPackage(MODULE_PKG)
                    .putExtra("ok", ok)
                    .putExtra("label", label)
                    .putExtra("reason", reason),
            )
        } catch (t: Throwable) {
            Lg.w(TAG, "send freeze result failed", t)
        }
    }

    // ---------- 工具（抓取 / UI 树 / 玻璃清单） ----------

    @Volatile
    private var lastToolAtMs = 0L

    private fun toolThrottled(tool: String): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastToolAtMs < REQUEST_THROTTLE_MS) {
            Lg.i(TAG, "[tool-throttle] $tool request throttled")
            EngineLog.log("AppAgent", "$tool-request throttled")
            return true
        }
        lastToolAtMs = now
        return false
    }

    private fun handleCaptureRequest(intent: Intent) {
        val x = intent.getIntExtra("x", Int.MIN_VALUE)
        val y = intent.getIntExtra("y", Int.MIN_VALUE)
        EngineLog.log("AppAgent", "capture-request x=$x y=$y")
        if (x == Int.MIN_VALUE || y == Int.MIN_VALUE) {
            Lg.i(TAG, "capture rejected, missing coords")
            sendToolResult(ACTION_TOOL_CAPTURE_RESULT, false, "missing coords")
            return
        }
        if (toolThrottled("capture")) return
        mainHandler.post {
            val decor = decorOrNull()
            if (decor == null) {
                Lg.i(TAG, "capture no activity window")
                sendToolResult(ACTION_TOOL_CAPTURE_RESULT, false, "no activity window")
                return@post
            }
            val hit = findDeepestAt(decor, x, y)
            if (hit == null) {
                Lg.i(TAG, "capture miss at ($x,$y)")
                sendToolResult(ACTION_TOOL_CAPTURE_RESULT, false, "未命中任何控件")
                return@post
            }
            val fixed = matchFixed(hit)
            val text = buildCaptureText(hit, fixed)
            val loc = IntArray(2)
            try {
                hit.getLocationOnScreen(loc)
            } catch (_: Throwable) {
            }
            val box = intArrayOf(loc[0], loc[1], hit.width, hit.height)
            sendToolResult(ACTION_TOOL_CAPTURE_RESULT, true, text, box)
        }
    }

    private fun handleTreeRequest() {
        EngineLog.log("AppAgent", "tree-request accepted")
        if (toolThrottled("tree")) return
        mainHandler.post {
            val text = try {
                buildTreeText()
            } catch (t: Throwable) {
                Lg.w(TAG, "build tree failed", t)
                sendToolResult(ACTION_TOOL_TREE_RESULT, false, "UI 树读取失败：${t.javaClass.simpleName}")
                return@post
            }
            sendToolResult(ACTION_TOOL_TREE_RESULT, true, text)
        }
    }

    private fun handleListRequest() {
        EngineLog.log("AppAgent", "list-request accepted")
        if (toolThrottled("list")) return
        mainHandler.post {
            val entries = try {
                GlassElementRegistry.load()
            } catch (t: Throwable) {
                Lg.w(TAG, "load entries failed", t)
                sendToolResult(ACTION_TOOL_LIST_RESULT, false, "读取失败：${t.javaClass.simpleName}")
                return@post
            }
            val active = HashSet<String>()
            decorOrNull()?.let { decor ->
                fun walk(v: View, depth: Int) {
                    if (depth > MAX_SCAN_DEPTH) return
                    if (v.isShown && v.width > 0 && v.height > 0) {
                        GlassElementRegistry.matchAnchor(v)?.let { e -> active.add(e.anchorKey) }
                    }
                    if (v is ViewGroup) {
                        for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
                    }
                }
                for (i in 0 until decor.childCount) walk(decor.getChildAt(i), 1)
            }
            val sb = StringBuilder()
            if (entries.isEmpty()) {
                sb.append("（本进程无固化条目）")
            } else {
                for (e in entries) {
                    sb.append(e.label).append(' ')
                        .append(e.anchorClassName.substringAfterLast('.')).append(' ')
                        .append(e.mountType)
                        .append(
                            when {
                                !e.enabled -> " 停用"
                                e.anchorKey in active -> " 活跃"
                                else -> " 待机"
                            },
                        )
                        .append('\n')
                }
            }
            sendToolResult(ACTION_TOOL_LIST_RESULT, true, sb.toString())
        }
    }

    private fun sendToolResult(action: String, ok: Boolean, text: String, box: IntArray? = null) {
        val ctx = appContext ?: run {
            Lg.w(TAG, "send tool result skipped, no context action=$action")
            return
        }
        EngineLog.log("AppAgent", "tool-result action=$action ok=$ok bytes=${text.length}")
        try {
            val i = Intent(action).setPackage(MODULE_PKG)
                .putExtra("pkg", ctx.packageName)
                .putExtra("ok", ok)
                .putExtra("text", text)
            if (box != null) {
                i.putExtra("hasBox", true)
                    .putExtra("bx", box[0])
                    .putExtra("by", box[1])
                    .putExtra("bw", box[2])
                    .putExtra("bh", box[3])
            }
            ctx.sendBroadcast(i)
        } catch (t: Throwable) {
            Lg.w(TAG, "send tool result failed action=$action", t)
        }
    }

    /** 抓取输出文本（命中详情：类名/id/bounds/窗口/父链/背景/固化态）。 */
    private fun buildCaptureText(v: View, fixed: Boolean): String {
        val loc = IntArray(2)
        try {
            v.getLocationOnScreen(loc)
        } catch (_: Throwable) {
        }
        val sb = StringBuilder()
        sb.append("[进程] ").append(appContext?.packageName ?: "?").append('\n')
        sb.append("[命中] ").append(v.javaClass.name)
            .append("  id=").append(idNameOf(v))
            .append("  bounds=").append(loc[0]).append(',').append(loc[1])
            .append(' ').append(v.width).append('×').append(v.height)
            .append("  attach=").append(v.isAttachedToWindow)
            .append("  via=").append(if (fixed) "registry" else "viewtree").append('\n')
        val root = v.rootView
        if (root != null) {
            sb.append("[窗口] root=").append(root.javaClass.name)
                .append(" type=").append(reflectWindowType(root))
                .append(" title=").append(reflectWindowTitle(root)?.toString() ?: "—")
                .append('\n')
        }
        sb.append("[父链] ").append(viewChain(v)).append('\n')
        sb.append("[背景] ").append(try {
            v.background?.javaClass?.name
        } catch (_: Throwable) {
            null
        } ?: "null").append('\n')
        sb.append("[固化] ").append(if (fixed) "已固化" else "未固化").append('\n')
        return sb.toString()
    }

    private fun viewChain(v: View): String {
        val parts = ArrayList<String>(8)
        var cur: View? = v
        var depth = 0
        while (cur != null && depth < 12) {
            parts.add(cur.javaClass.name.substringAfterLast('.'))
            cur = cur.parent as? View
            depth++
        }
        return parts.joinToString(" → ")
    }

    private fun idNameOf(v: View): String {
        if (v.id == View.NO_ID) return "#-"
        return try {
            "#" + v.resources.getResourceEntryName(v.id)
        } catch (_: Throwable) {
            "#" + Integer.toHexString(v.id)
        }
    }

    // ---------- UI 树 ----------

    private fun buildTreeText(): String {
        val entries = enumerateWindows()
        if (entries.isEmpty()) return "（未枚举到窗口）"
        val sb = StringBuilder()
        val counter = IntArray(1)
        for ((i, entry) in entries.withIndex()) {
            sb.append("[窗口").append(i + 1).append("] type=").append(entry.type)
                .append(" title=").append(truncate(entry.title ?: "—", 40))
                .append(" root=").append(entry.decor.javaClass.name.substringAfterLast('.'))
                .append('\n')
            if (counter[0] >= 800) {
                sb.append("…(truncated)\n")
                break
            }
            if (entry.decor is ViewGroup) {
                val decor = entry.decor as ViewGroup
                for (ci in 0 until decor.childCount) {
                    if (counter[0] >= 800) {
                        sb.append("…(truncated)\n")
                        break
                    }
                    appendTreeNode(sb, decor.getChildAt(ci), 1, counter)
                }
            }
        }
        return sb.toString()
    }

    private fun appendTreeNode(sb: StringBuilder, v: View, depth: Int, counter: IntArray) {
        if (counter[0] >= 800 || depth > 8) return
        counter[0]++
        val indent = "  ".repeat(depth)
        sb.append(indent).append(shortName(v)).append(' ').append(treeIdLabel(v)).append(' ')
        val loc = IntArray(2)
        try {
            v.getLocationOnScreen(loc)
        } catch (_: Throwable) {
        }
        sb.append(loc[0]).append(',').append(loc[1]).append(' ')
            .append(v.width).append('×').append(v.height)
        if (v.visibility != View.VISIBLE) sb.append(" [不可见]")
        sb.append('\n')
        if (v is ViewGroup && v.childCount > 0 && v.visibility != View.GONE) {
            for (i in 0 until v.childCount) {
                if (counter[0] >= 800 || depth + 1 > 8) {
                    sb.append(indent).append("  …(truncated)\n")
                    return
                }
                appendTreeNode(sb, v.getChildAt(i), depth + 1, counter)
            }
        }
    }

    private fun shortName(v: View): String {
        val simple = v.javaClass.simpleName
        return if (simple.isEmpty()) v.javaClass.name.substringAfterLast('.') else simple
    }

    private fun treeIdLabel(v: View): String {
        val id = if (v.id == View.NO_ID) {
            "-"
        } else {
            try {
                v.resources.getResourceEntryName(v.id)
            } catch (_: Throwable) {
                Integer.toHexString(v.id)
            }
        }
        return "#$id@${Integer.toHexString(System.identityHashCode(v))}"
    }

    private fun truncate(s: String, cap: Int): String =
        if (s.length <= cap) s else s.substring(0, cap) + "…"

    // ---------- 窗口枚举 / 窗口反射 ----------

    private class WindowEntry(val decor: View, val type: Int, val title: String?)

    @Suppress("UNCHECKED_CAST")
    private fun enumerateWindows(): List<WindowEntry> {
        val cls = Class.forName("android.view.WindowManagerGlobal")
        val inst = cls.getMethod("getInstance").invoke(null)
        val views = cls.getDeclaredField("mViews").apply { isAccessible = true }
            .get(inst) as? ArrayList<View>
        if (views == null) {
            logOnce("agent:enum-views", "enumerate windows: mViews unreadable, empty result")
            return emptyList()
        }
        val params = try {
            cls.getDeclaredField("mParams").apply { isAccessible = true }
                .get(inst) as? ArrayList<android.view.WindowManager.LayoutParams>
        } catch (_: Throwable) {
            null
        }
        if (params == null) {
            logOnce("agent:enum-params", "enumerate windows: mParams unreadable, fallback per-window reflect")
        }
        val snapshot = ArrayList(views)
        val out = ArrayList<WindowEntry>(snapshot.size)
        for (i in snapshot.indices) {
            val decor = snapshot[i] ?: continue
            val lp = params?.getOrNull(i)
            val type = lp?.type ?: reflectWindowType(decor)
            out.add(WindowEntry(decor, type, lp?.title?.toString()))
        }
        return out
    }

    private fun reflectWindowType(rootView: View): Int {
        return try {
            val vri = View::class.java.getMethod("getViewRootImpl").invoke(rootView)
            if (vri == null) {
                logOnce("agent:reflect-type-null", "reflect window type: view root missing")
                return -1
            }
            val lp = vri.javaClass.getMethod("getWindowAttributes").invoke(vri)
            if (lp == null) {
                logOnce("agent:reflect-type-null", "reflect window type: window attrs missing")
                return -1
            }
            val type = lp.javaClass.getField("type").get(lp) as? Int
            if (type == null) {
                logOnce("agent:reflect-type-err", "reflect window type: type field unreadable")
                return -1
            }
            type
        } catch (t: Throwable) {
            logOnce("agent:reflect-type-err", "reflect window type failed: ${t.javaClass.simpleName}")
            -1
        }
    }

    private fun reflectWindowTitle(rootView: View): CharSequence? {
        return try {
            val vri = View::class.java.getMethod("getViewRootImpl").invoke(rootView)
            if (vri == null) {
                logOnce("agent:reflect-title-null", "reflect window title: view root missing")
                return null
            }
            val lp = vri.javaClass.getMethod("getWindowAttributes").invoke(vri)
            if (lp == null) {
                logOnce("agent:reflect-title-null", "reflect window title: window attrs missing")
                return null
            }
            lp.javaClass.getMethod("getTitle").invoke(lp) as? CharSequence
        } catch (t: Throwable) {
            logOnce("agent:reflect-title-err", "reflect window title failed: ${t.javaClass.simpleName}")
            null
        }
    }

    // ---------- Activity 上下文 ----------

    /** 挂 Activity#onResume 捕获前台 Activity（与工作台同法，互不依赖）。 */
    private fun hookResume(api: XposedInterface) {
        if (resumeHooked) return
        resumeHooked = true
        try {
            val onResume = Activity::class.java.getDeclaredMethod("onResume")
            api.hook(onResume)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    try {
                        (chain.getThisObject() as? Activity)?.let { act ->
                            currentActivity = WeakReference(act)
                            if (!receiverRegistered) mainHandler.post { tryRegister() }
                        }
                    } catch (t: Throwable) {
                        Lg.w(TAG, "resume capture error", t)
                    }
                    result
                }
            Lg.i(TAG, "mounted resume capture: android.app.Activity#onResume")
        } catch (t: Throwable) {
            Lg.w(TAG, "Activity#onResume hook FAILED", t)
        }
    }

    private fun logOnce(key: String, msg: String) {
        if (!loggedOnce.add(key)) return
        if (loggedOnce.size > 256) loggedOnce.clear()
        Lg.i(TAG, msg)
    }
}
