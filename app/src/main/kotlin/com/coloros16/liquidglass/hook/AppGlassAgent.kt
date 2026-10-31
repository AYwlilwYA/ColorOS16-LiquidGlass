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
import com.coloros16.liquidglass.debug.ACTION_FREEZE_REQUEST
import com.coloros16.liquidglass.debug.AppGlassWorkbench
import com.coloros16.liquidglass.debug.ACTION_FREEZE_RESULT
import com.coloros16.liquidglass.debug.ACTION_SCAN_REQUEST
import com.coloros16.liquidglass.debug.ACTION_SCAN_RESULT
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
                        return
                    }
                    when (intent.action) {
                        ACTION_SCAN_REQUEST -> handleScanRequest()
                        ACTION_FREEZE_REQUEST -> handleFreezeRequest(intent)
                    }
                } catch (t: Throwable) {
                    Lg.w(TAG, "onReceive error action=${intent.action}", t)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_SCAN_REQUEST)
            addAction(ACTION_FREEZE_REQUEST)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.applicationContext.registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                ctx.applicationContext.registerReceiver(r, filter)
            }
            receiverRegistered = true
            Lg.i(TAG, "receiver registered (SCAN/FREEZE)")
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
            return
        }
        lastScanAtMs = now
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
            return
        }
        lastFreezeAtMs = now
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
