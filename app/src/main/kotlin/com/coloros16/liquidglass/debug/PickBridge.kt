package com.coloros16.liquidglass.debug

import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.coloros16.liquidglass.log.Lg
import org.json.JSONArray
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 带 shareIdentity 发广播：不带它系统不给接收方传 sender，
 * getSentFromPackage/getSentFromUid 恒 null/-1，鉴权侧会把合法广播全拒掉。
 */
internal fun sendSharingBroadcast(ctx: Context, intent: Intent) {
    if (Build.VERSION.SDK_INT >= 34) {
        val opts = BroadcastOptions.makeBasic().setShareIdentityEnabled(true)
        ctx.sendBroadcast(intent, null, opts.toBundle())
    } else {
        ctx.sendBroadcast(intent)
    }
}

/**
 * 点选桥：随 root 球存活拉起，500ms 轮询 pick.report；
 * 状态翻转起五目标扫描，tap 翻转起冻结，框表/EXIT 经 pick.cmd 回写球端。
 */
object PickBridge {

    private const val TAG = "PickBridge"
    private const val POLL_MS = 500L
    private const val SCAN_WINDOW_MS = 1200L
    private const val FREEZE_WAIT_MS = 3000L
    private const val THROTTLE_MS = 5000L
    private const val MAX_BOXES = 200

    private const val REPORT_BASELINE = "v1 state=NORMAL stateSeq=0 tapSeq=0\n"

    private val main = Handler(Looper.getMainLooper())
    private val cmdSeq = AtomicInteger(0)

    @Volatile private var running = false
    @Volatile private var appContext: Context? = null

    private var receiver: BroadcastReceiver? = null
    private var cmdFile: File? = null
    private var reportFile: File? = null
    private var declareFile: File? = null

    // 主线程独用（poll / receiver 同走 main，无并发）
    private var sawFirstReport = false
    private var lastStateSeq = 0
    private var lastTapSeq = 0
    private var scanPending = false
    private var scanDeadlineAt = 0L
    private var chosenPkg: String? = null
    private var chosenItems: JSONArray? = null
    private var freezeWaitUntil = 0L
    private var declareSeq = 0

    private val throttleAt = HashMap<String, Long>()

    private val pollTask = object : Runnable {
        override fun run() {
            if (!running) return
            poll()
            main.postDelayed(this, POLL_MS)
        }
    }

    /** 球活桥才活的拉起点（幂等）。 */
    fun ensureRunning() {
        main.post {
            if (running) return@post
            val ctx = resolveContext()
            if (ctx == null) {
                throttledLog("no-ctx", "start skipped: no application context")
                return@post
            }
            appContext = ctx
            val boxDir = File(ctx.filesDir, "lgbox")
            if (!boxDir.isDirectory && !boxDir.mkdirs()) {
                Lg.w(TAG, "mkdir fail path=${boxDir.absolutePath}")
            }
            val report = File(boxDir, "pick.report")
            if (!report.exists()) {
                try {
                    report.writeText(REPORT_BASELINE)
                    Lg.i(TAG, "report baseline created")
                } catch (t: Throwable) {
                    Lg.w(TAG, "baseline write fail", t)
                }
            }
            File(boxDir, "bridge.ready").takeIf { !it.exists() }?.let {
                runCatching { it.writeText("1") }
            }
            reportFile = report
            val cmd = File(boxDir, "pick.cmd")
            // 继承文件末次 seq：球经 relay 只见过该文件，进程重启后从文件续账防 seq 回退被拒
            runCatching {
                if (cmd.exists()) {
                    val head = cmd.useLines { lines -> lines.firstOrNull() }
                    val n = head?.split(' ')?.firstNotNullOfOrNull { p ->
                        if (p.startsWith("seq=")) p.removePrefix("seq=").toIntOrNull() else null
                    }
                    if (n != null && n > cmdSeq.get()) cmdSeq.set(n)
                }
            }.onFailure { Lg.w(TAG, "cmd seq inherit fail", it) }
            cmdFile = cmd
            val declare = File(boxDir, "declare.json")
            // 继承 declare 末次 seq：进程重启后从文件续账，防 seq 回退被申报器拒
            runCatching {
                if (declare.exists()) {
                    val m = Regex("\"seq\"\\s*:\\s*(\\d+)").find(declare.readText())
                    val n = m?.groupValues?.get(1)?.toIntOrNull()
                    if (n != null && n > declareSeq) declareSeq = n
                }
            }.onFailure { Lg.w(TAG, "declare seq inherit fail", it) }
            declareFile = declare
            sawFirstReport = false
            lastStateSeq = 0
            lastTapSeq = 0
            scanPending = false
            scanDeadlineAt = 0L
            chosenPkg = null
            chosenItems = null
            freezeWaitUntil = 0L
            registerResultReceiver(ctx)
            running = true
            main.post(pollTask)
            Lg.i(TAG, "bridge started dir=${boxDir.absolutePath}")
        }
    }

    /** 球停时收桥（幂等）；顺手撤申报（state=off）。 */
    fun shutdown() {
        main.post {
            if (!running) return@post
            running = false
            main.removeCallbacks(pollTask)
            unregisterResultReceiver()
            runCatching { writeDeclare("off", null) }
                .onFailure { Lg.w(TAG, "declare off write fail", it) }
            Lg.i(TAG, "bridge stopped")
        }
    }

    // ---------- 轮询 ----------

    private fun poll() {
        val report = reportFile ?: run {
            throttledLog("no-report-file", "report file missing")
            return
        }
        val text = try {
            report.readText()
        } catch (t: Throwable) {
            throttledLog("read-fail", "report read fail: ${t.javaClass.simpleName}")
            return
        }
        val snap = parseReport(text) ?: run {
            throttledLog("parse-fail", "report parse fail")
            return
        }
        if (!sawFirstReport) {
            sawFirstReport = true
            lastTapSeq = snap.tapSeq
            if (snap.state == "PICK") {
                lastStateSeq = snap.stateSeq
                Lg.i(TAG, "start mid-pick, catch-up scan stateSeq=${snap.stateSeq}")
                startScan()
            } else {
                lastStateSeq = snap.stateSeq
            }
        } else {
            // 用 != 而非「变大」：球进程重启 stateSeq rebase 时按翻转重扫一次是安全方向
            if (snap.stateSeq != lastStateSeq) {
                lastStateSeq = snap.stateSeq
                if (snap.state == "PICK") {
                    startScan()
                } else if (scanPending) {
                    scanPending = false
                    chosenPkg = null
                    chosenItems = null
                    Lg.i(TAG, "scan cancelled: state->NORMAL seq=${snap.stateSeq}")
                }
            }
            if (snap.tapSeq != lastTapSeq) {
                val isNewTap = snap.tapSeq > lastTapSeq
                lastTapSeq = snap.tapSeq
                if (isNewTap) startFreeze(snap.tapX, snap.tapY)
            }
        }
        if (scanPending && SystemClock.uptimeMillis() >= scanDeadlineAt) {
            finishScanByDeadline()
        }
        if (freezeWaitUntil > 0L && SystemClock.uptimeMillis() >= freezeWaitUntil) {
            freezeWaitUntil = 0L
            Lg.i(TAG, "freeze no reply")
        }
    }

    private class ReportSnap(
        val state: String,
        val stateSeq: Int,
        val tapSeq: Int,
        val tapX: Int,
        val tapY: Int,
    )

    private fun parseReport(text: String): ReportSnap? {
        val line = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return null
        val parts = line.split(' ')
        if (parts.size < 4 || parts[0] != "v1") return null
        var state = ""
        var stateSeq = -1
        var tapSeq = -1
        var tapX = 0
        var tapY = 0
        for (p in parts) {
            val eq = p.indexOf('=')
            if (eq <= 0) continue
            val k = p.substring(0, eq)
            val v = p.substring(eq + 1)
            when (k) {
                "state" -> state = v
                "stateSeq" -> stateSeq = v.toIntOrNull() ?: return null
                "tapSeq" -> tapSeq = v.toIntOrNull() ?: return null
                "tap" -> {
                    val xy = v.split(',')
                    if (xy.size >= 2) {
                        tapX = xy[0].toIntOrNull() ?: 0
                        tapY = xy[1].toIntOrNull() ?: 0
                    }
                }
            }
        }
        if (state != "PICK" && state != "NORMAL") return null
        if (stateSeq < 0 || tapSeq < 0) return null
        return ReportSnap(state, stateSeq, tapSeq, tapX, tapY)
    }

    // ---------- 扫描流 ----------

    private fun startScan() {
        scanPending = true
        scanDeadlineAt = SystemClock.uptimeMillis() + SCAN_WINDOW_MS
        chosenPkg = null
        chosenItems = null
        val ctx = appContext ?: run {
            throttledLog("no-ctx", "scan send skipped: no context")
            return
        }
        var sent = 0
        for ((pkg, _) in GlobalGlassBall.TARGETS) {
            try {
                sendSharingBroadcast(ctx, Intent(ACTION_SCAN_REQUEST).setPackage(pkg).putExtra("pkg", pkg))
                sent++
            } catch (t: Throwable) {
                Lg.w(TAG, "scan send fail pkg=$pkg", t)
            }
        }
        Lg.i(TAG, "scan -> $sent targets")
    }

    private fun onScanResult(intent: Intent) {
        if (!running) return
        if (!scanPending) {
            throttledLog("scan-late", "scan result while no pending scan")
            return
        }
        val pkg = intent.getStringExtra("pkg")
        val resumed = intent.getBooleanExtra("resumed", false)
        val itemsText = intent.getStringExtra("items")
        val items = try {
            itemsText?.let { JSONArray(it) }
        } catch (t: Throwable) {
            Lg.w(TAG, "scan items parse fail pkg=$pkg", t)
            null
        }
        val n = items?.length() ?: 0
        if (items == null || n == 0) {
            Lg.i(TAG, "scan reply pkg=$pkg items=$n resumed=$resumed (no candidate)")
            return
        }
        if (resumed) {
            chosenPkg = pkg
            chosenItems = items
            scanPending = false
            freezeWaitUntil = 0L
            writeEnter(items)
            Lg.i(TAG, "scan chosen pkg=$pkg resumed=1 items=$n")
        } else if (chosenPkg == null && chosenItems == null) {
            chosenPkg = pkg
            chosenItems = items
            Lg.i(TAG, "scan interim pkg=$pkg resumed=0 items=$n (await resumed)")
        } else {
            throttledLog("scan-second", "second interim reply pkg=$pkg items=$n")
        }
    }

    /** 扫描窗耗尽仍无定案：有过渡候选就用，全空即 EXIT 放触摸回流。 */
    private fun finishScanByDeadline() {
        scanPending = false
        val pkg = chosenPkg
        val items = chosenItems
        if (pkg != null && items != null) {
            writeEnter(items)
            Lg.i(TAG, "scan deadline, interim used pkg=$pkg items=${items.length()}")
        } else {
            Lg.w(TAG, "no scan target, exit")
            writeExit()
        }
    }

    // ---------- 冻结流 ----------

    private fun startFreeze(x: Int, y: Int) {
        val pkg = chosenPkg
        Lg.i(TAG, "tap x=$x y=$y target=$pkg")
        if (pkg != null) {
            try {
                val ac = appContext ?: error("no app context")
                sendSharingBroadcast(
                    ac,
                    Intent(ACTION_FREEZE_REQUEST).setPackage(pkg)
                        .putExtra("pkg", pkg)
                        .putExtra("x", x)
                        .putExtra("y", y)
                        .putExtra("w", 0)
                        .putExtra("h", 0),
                )
                freezeWaitUntil = SystemClock.uptimeMillis() + FREEZE_WAIT_MS
                Lg.i(TAG, "freeze sent pkg=$pkg")
            } catch (t: Throwable) {
                freezeWaitUntil = 0L
                Lg.w(TAG, "freeze send fail pkg=$pkg", t)
            }
        } else {
            Lg.w(TAG, "tap without scan target, skip freeze")
        }
        writeExit()
    }

    private fun onFreezeResult(intent: Intent) {
        freezeWaitUntil = 0L
        val ok = intent.getBooleanExtra("ok", false)
        val label = intent.getStringExtra("label") ?: ""
        val reason = intent.getStringExtra("reason") ?: ""
        if (ok) {
            Lg.i(TAG, "freeze ok label=$label")
            val gx = intent.getIntExtra("gx", Int.MIN_VALUE)
            val gy = intent.getIntExtra("gy", Int.MIN_VALUE)
            val gw = intent.getIntExtra("gw", Int.MIN_VALUE)
            val gh = intent.getIntExtra("gh", Int.MIN_VALUE)
            val corner = intent.getIntExtra("corner", Int.MIN_VALUE)
            val shape = intent.getIntExtra("shape", Int.MIN_VALUE)
            if (gx == Int.MIN_VALUE || gy == Int.MIN_VALUE || gw == Int.MIN_VALUE ||
                gh == Int.MIN_VALUE || corner == Int.MIN_VALUE || shape == Int.MIN_VALUE
            ) {
                Lg.w(TAG, "freeze ok without geom extras, declare skipped")
            } else {
                writeDeclare("on", intArrayOf(gx, gy, gw, gh, corner, shape))
            }
        } else {
            Lg.w(TAG, "freeze fail reason=$reason")
        }
    }

    // ---------- pick.cmd 写入 ----------

    private fun writeEnter(items: JSONArray) {
        val seq = cmdSeq.incrementAndGet()
        val sb = StringBuilder("v1 ENTER seq=").append(seq).append('\n')
        val n = minOf(items.length(), MAX_BOXES)
        for (i in 0 until n) {
            val o = items.optJSONObject(i) ?: continue
            sb.append("{\"i\":").append(i)
                .append(",\"x\":").append(o.optInt("x"))
                .append(",\"y\":").append(o.optInt("y"))
                .append(",\"w\":").append(o.optInt("w"))
                .append(",\"h\":").append(o.optInt("h"))
                .append(",\"f\":").append(o.optInt("fixed"))
                .append("}\n")
        }
        writeCmd(sb.toString(), "ENTER boxes=$n")
    }

    private fun writeExit() {
        val seq = cmdSeq.incrementAndGet()
        writeCmd("v1 EXIT seq=$seq\n", "EXIT")
    }

    /** 整文件原子重写（tmp+rename），seq 单调防 relay 重拷重放。 */
    private fun writeCmd(text: String, what: String) {
        val f = cmdFile ?: run {
            throttledLog("no-cmd-file", "cmd write skipped ($what): no file")
            return
        }
        try {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) {
                Lg.w(TAG, "cmd rename fail, non-atomic fallback $what")
                f.writeText(text)
                tmp.delete()
            }
            Lg.i(TAG, "cmd written $what seq-line=${text.lineSequence().first()}")
        } catch (t: Throwable) {
            Lg.w(TAG, "cmd write fail $what", t)
        }
    }

    /**
     * declare.json 原子写（tmp+rename）：on=固化几何申报、off=撤申报。
     * schema 照 spec/150 §六 6.1；seq 单调递增、进程重启从文件续账。
     */
    private fun writeDeclare(state: String, geom: IntArray?) {
        val f = declareFile ?: run {
            throttledLog("no-declare-file", "declare write skipped ($state): no file")
            return
        }
        val seq = ++declareSeq
        val sb = StringBuilder(192)
        sb.append("{\"seq\":").append(seq).append(",\"state\":\"").append(state).append('"')
        if (geom != null && geom.size >= 6) {
            sb.append(",\"x\":").append(geom[0])
                .append(",\"y\":").append(geom[1])
                .append(",\"w\":").append(geom[2])
                .append(",\"h\":").append(geom[3])
                .append(",\"corner\":").append(geom[4])
                .append(",\"shape\":").append(geom[5])
            sb.append(",\"params\":{\"refract\":0.5,\"dispersion\":0.3,\"highlight\":0.6}")
        }
        sb.append('}')
        try {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(sb.toString())
            if (!tmp.renameTo(f)) {
                Lg.w(TAG, "declare rename fail, non-atomic fallback state=$state")
                f.writeText(sb.toString())
                tmp.delete()
            }
            Lg.i(TAG, "declare written state=$state seq=$seq geom=${geom != null}")
        } catch (t: Throwable) {
            Lg.w(TAG, "declare write fail state=$state", t)
        }
    }

    // ---------- 结果广播 ----------

    private fun registerResultReceiver(ctx: Context) {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                try {
                    val sender = if (Build.VERSION.SDK_INT >= 34) getSentFromPackage() else null
                    if (sender == null || sender !in GlobalGlassBall.TARGET_PKG_SET) {
                        throttledLog("reject", "reject result action=${intent.action} sender=$sender")
                        return
                    }
                    when (intent.action) {
                        ACTION_SCAN_RESULT -> onScanResult(intent)
                        ACTION_FREEZE_RESULT -> onFreezeResult(intent)
                    }
                } catch (t: Throwable) {
                    Lg.w(TAG, "onReceive error action=${intent.action}", t)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_SCAN_RESULT)
            addAction(ACTION_FREEZE_RESULT)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                ctx.registerReceiver(r, filter)
            }
            receiver = r
        } catch (t: Throwable) {
            Lg.w(TAG, "register result receiver failed", t)
        }
    }

    private fun unregisterResultReceiver() {
        val r = receiver ?: return
        receiver = null
        try {
            appContext?.unregisterReceiver(r)
        } catch (t: Throwable) {
            Lg.w(TAG, "unregister receiver failed", t)
        }
    }

    // ---------- 工具 ----------

    private fun resolveContext(): Context? {
        appContext?.let { return it }
        return try {
            val app = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? Context
            app?.applicationContext
        } catch (t: Throwable) {
            Lg.w(TAG, "resolve context fail", t)
            null
        }
    }

    private fun throttledLog(key: String, msg: String) {
        val now = SystemClock.uptimeMillis()
        val last = throttleAt[key] ?: 0L
        if (now - last < THROTTLE_MS) return
        throttleAt[key] = now
        Lg.i(TAG, "$msg (key=$key)")
    }
}
