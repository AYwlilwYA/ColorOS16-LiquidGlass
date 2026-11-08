package com.coloros16.liquidglass.sf

import android.os.SystemClock
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.log.Lg
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * SF 玻璃通道客户端：渲染模式读取、消费端探测、declare.json 几何下发（spec/150）。
 * 回退语义：模式非 sf / 探测不过当帧回现役四腿；下发失败下帧起回四腿（负缓存 + 节流日志）。
 */
object SfGlassChannel {

    private const val TAG = "SfGlassChannel"
    private const val BOX_PATH = "/data/local/tmp/lgbox_sf/declare.json"
    private const val MODE_TTL_MS = 500L
    private const val PROBE_OK_TTL_MS = 1000L
    private const val PROBE_FAIL_TTL_MS = 3000L
    private const val OFFER_MIN_INTERVAL_MS = 250L
    private const val SESSION_IDLE_OFF_MS = 5000L
    private const val WATCHDOG_TICK_MS = 2000L
    private const val SU_TIMEOUT_MS = 8000L

    /** su 执行结果：ok=exit 0，out=合并输出，err=失败原因（ok 时 null）。 */
    internal data class SuResult(val ok: Boolean, val out: String, val err: String?) {
        val summary: String get() = out.trim().take(400).ifBlank { "(无输出)" }
    }

    private class Geom(val id: Int, val l: Float, val t: Float, val w: Float, val h: Float, val corner: Float)

    @Volatile
    private var modeCache: String = Prefs.DEFAULT_RENDER_MODE

    @Volatile
    private var modeCacheAtMs = 0L

    @Volatile
    private var consumerAlive = false

    @Volatile
    private var probeAtMs = 0L

    @Volatile
    private var probeRunning = false

    @Volatile
    private var lastOfferKey = ""

    @Volatile
    private var lastOfferAttemptAtMs = 0L

    @Volatile
    private var lastClaimAtMs = 0L

    @Volatile
    private var sessionOn = false

    private val pendingOffer = AtomicReference<Geom?>(null)
    private val offerScheduled = AtomicBoolean(false)
    private val seqBase = AtomicLong(0L)

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "sf-glass-io").apply { isDaemon = true }
    }

    private val watchdog = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "sf-glass-watchdog").apply { isDaemon = true }
    }

    init {
        watchdog.scheduleWithFixedDelay(
            { idleOffTick() },
            WATCHDOG_TICK_MS, WATCHDOG_TICK_MS, TimeUnit.MILLISECONDS,
        )
    }

    // ---------- 渲染模式 ----------

    /**
     * 渲染模式（500ms TTL 热读）：`app`=现役四腿 / `sf`=SF 下发链，变化打 `lg-sf-mode`；
     * sf→app 翻转顺手撤申报（防 SF 层残留）。
     */
    fun renderMode(api: XposedInterface?): String {
        if (api == null) return Prefs.DEFAULT_RENDER_MODE
        val now = SystemClock.uptimeMillis()
        if (now - modeCacheAtMs < MODE_TTL_MS) return modeCache
        modeCacheAtMs = now
        val v = try {
            Prefs.read(api).getString(Prefs.KEY_RENDER_MODE, Prefs.DEFAULT_RENDER_MODE)
                ?: Prefs.DEFAULT_RENDER_MODE
        } catch (t: Throwable) {
            Lg.w(TAG, "render_mode read failed, default ${Prefs.DEFAULT_RENDER_MODE}", t)
            Prefs.DEFAULT_RENDER_MODE
        }
        if (v != modeCache) {
            val prev = modeCache
            modeCache = v
            Lg.i(TAG, "lg-sf-mode mode=$v prev=$prev")
            if (v == Prefs.RENDER_MODE_APP && prev == Prefs.RENDER_MODE_SF) {
                sessionGlassOff("mode-switch-app")
            }
        }
        return v
    }

    // ---------- 会话撤层（关玻璃/省电/热重载/模式翻转共用） ----------

    /**
     * 会话玻璃被关（总开关关 / 省电开 / hot-reload / 切回应用级）：本会话发过 on 才发 off，
     * 防残留层盖屏；异步执行不阻塞调用线程。
     */
    fun sessionGlassOff(reason: String) {
        if (!sessionOn) return
        sessionOn = false
        pendingOffer.set(null)
        io.execute {
            // 复检：off 在途期间若有新认领则撤回本次 off（新认领的 on 由其 flush 发出）
            if (sessionOn) return@execute
            lastOfferKey = ""
            val r = writeDeclare("off", 0f, 0f, 0f, 0f, 0f, "session-off")
            if (r.ok) {
                Lg.i(TAG, "lg-sf-session-off reason=$reason")
            } else {
                throttled("lg-sf-session-off-fail", "撤申报失败 reason=$reason: ${r.err}")
            }
        }
    }

    /** 空闲看门狗：本会话有 on、连续无认领达阈值 → 自动撤申报（元素停画防残留浮窗）。 */
    private fun idleOffTick() {
        try {
            if (!sessionOn) return
            val now = SystemClock.uptimeMillis()
            if (now - lastClaimAtMs < SESSION_IDLE_OFF_MS) return
            sessionOn = false
            pendingOffer.set(null)
            // 并入 io 单线程执行，防与下发并发写同一 declare 临时文件
            io.execute {
                // 复检：排队期间有新认领则撤回本次 idle-off
                if (sessionOn) return@execute
                lastOfferKey = ""
                val r = writeDeclare("off", 0f, 0f, 0f, 0f, 0f, "idle-off")
                Lg.i(
                    TAG,
                    "lg-sf-idle-off 无认领满 ${SESSION_IDLE_OFF_MS}ms 自动撤申报" +
                        (if (r.ok) "" else "（失败:${r.err}）"),
                )
            }
        } catch (t: Throwable) {
            Lg.w(TAG, "idle off tick failed", t)
        }
    }

    // ---------- 绘制点认领 ----------

    /** 把任务投进通道 io 单线程：declare.json 的读写（探测/下发/撤层/测试框）全走这里串行化。 */
    internal fun postIo(task: () -> Unit) {
        io.execute { task() }
    }

    /**
     * 绘制点认领：消费端在 → 下发本元素几何并返回 true（绘制点跳过四腿、交 SF 渲染）；
     * 探测不过 → 返回 false（现役四腿原样），守卫各配节流日志。
     * 调用前须已判 [renderMode] == [Prefs.RENDER_MODE_SF]（app 档零开销零变化）。
     */
    fun claimForDraw(id: Int, left: Float, top: Float, width: Float, height: Float, corner: Float): Boolean {
        if (!consumerUp()) {
            throttled("lg-sf-down", "消费端未在，回退现役四腿 id=$id")
            return false
        }
        lastClaimAtMs = SystemClock.uptimeMillis()
        sessionOn = true
        offer(id, left, top, width, height, corner)
        return true
    }

    // ---------- 消费端探测（试调用语义：热路径只读缓存，miss 先四腿 + 异步填缓存） ----------

    fun consumerUp(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (probeAtMs != 0L) {
            val ttl = if (consumerAlive) PROBE_OK_TTL_MS else PROBE_FAIL_TTL_MS
            if (now - probeAtMs < ttl) return consumerAlive
        }
        if (probeRunning) return consumerAlive
        probeRunning = true
        io.execute {
            val up = probeConsumerSync()
            consumerAlive = up
            probeAtMs = SystemClock.uptimeMillis()
            probeRunning = false
            throttled("lg-sf-probe", "alive=$up")
        }
        return false
    }

    /** 同步探测消费端（lgdeclare poll 进程在场）；阻塞，只许后台线程调。 */
    fun probeConsumerSync(): Boolean {
        val r = runSu("pidof lgdeclare")
        return r.ok && r.out.any { it.isDigit() }
    }

    // ---------- declare.json 下发（全局 250ms 节流 + 最新几何单槽） ----------

    private fun offer(id: Int, left: Float, top: Float, width: Float, height: Float, corner: Float) {
        pendingOffer.set(Geom(id, left, top, width, height, corner))
        if (offerScheduled.compareAndSet(false, true)) {
            io.execute { flushOffers() }
        }
    }

    private fun flushOffers() {
        try {
            while (true) {
                val g = pendingOffer.getAndSet(null) ?: break
                val key = keyOf(g)
                if (key == lastOfferKey) continue
                val wait = OFFER_MIN_INTERVAL_MS - (SystemClock.uptimeMillis() - lastOfferAttemptAtMs)
                if (wait > 0) {
                    try {
                        Thread.sleep(wait)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
                lastOfferAttemptAtMs = SystemClock.uptimeMillis()
                val r = writeDeclare("on", g.l, g.t, g.w, g.h, g.corner, "element id=${g.id}")
                if (r.ok) {
                    lastOfferKey = key
                    throttled(
                        "lg-sf-declare",
                        "已下发 id=${g.id} geom=(${g.l.toInt()},${g.t.toInt()},${g.w.toInt()}x${g.h.toInt()}) corner=${g.corner.toInt()}",
                    )
                } else {
                    consumerAlive = false
                    probeAtMs = 0L
                    throttled("lg-sf-declare-fail", "下发失败 id=${g.id}: ${r.err}（下帧起回现役四腿）")
                }
            }
        } finally {
            offerScheduled.set(false)
            if (pendingOffer.get() != null && offerScheduled.compareAndSet(false, true)) {
                io.execute { flushOffers() }
            }
        }
    }

    private fun keyOf(g: Geom): String =
        "${g.id}:${g.l.toInt()},${g.t.toInt()},${g.w.toInt()},${g.h.toInt()},${g.corner.toInt()}"

    /** 同步下发一帧 declare.json（阻塞，只许后台线程调）；seq 取时间戳保跨进程单调。 */
    internal fun sendDeclareSync(
        state: String,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        corner: Float,
        note: String = "",
    ): SuResult = writeDeclare(state, left, top, width, height, corner, note)

    private fun writeDeclare(
        state: String,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        corner: Float,
        note: String = "",
    ): SuResult {
        val seq = seqBase.updateAndGet { prev -> maxOf(prev + 1, System.currentTimeMillis()) }
        val json = buildString {
            append("{\"seq\":").append(seq)
            append(",\"state\":\"").append(state).append('"')
            if (state == "on") {
                append(",\"x\":").append(left.toInt())
                    .append(",\"y\":").append(top.toInt())
                    .append(",\"w\":").append(width.toInt())
                    .append(",\"h\":").append(height.toInt())
                    .append(",\"corner\":").append(corner.toInt())
                    .append(",\"shape\":0")
                    .append(",\"params\":{}")
            }
            append('}')
        }
        // 脚本经 stdin 喂 sh（引号由管道承载，不经任何命令行展开）；JSON 只含数字与双引号，单引号包裹安全
        val script = """
            mkdir -p /data/local/tmp/lgbox_sf
            printf '%s' '$json' > $BOX_PATH.tmp
            mv $BOX_PATH.tmp $BOX_PATH
        """.trimIndent()
        val r = runSu(script)
        if (r.ok) {
            Lg.i(TAG, "lg-sf-declare state=$state seq=$seq${if (note.isNotEmpty()) " $note" else ""}")
        } else {
            Lg.w(TAG, "lg-sf-declare fail state=$state seq=$seq: ${r.err}")
        }
        return r
    }

    // ---------- su 执行底座 ----------

    /**
     * su 执行脚本：`su -c sh` 起单层 shell、脚本整体经 stdin 喂入 —— `;`/`&&`/`>`/`$()`
     * 全部由该层 sh 原生解析，无多层命令行引号剥离坑；超时强杀防挂。
     */
    internal fun runSu(script: String, timeoutMs: Long = SU_TIMEOUT_MS): SuResult {
        return try {
            val p = ProcessBuilder("su", "-c", "sh")
                .redirectErrorStream(true)
                .start()
            // 读线程先于写 stdin 启动：脚本灌满管道缓冲时读侧不消费会互等
            val out = StringBuilder()
            val reader = Thread {
                try {
                    p.inputStream.bufferedReader().forEachLine { line ->
                        synchronized(out) { out.append(line).append('\n') }
                    }
                } catch (_: Throwable) {
                }
            }.apply { isDaemon = true; start() }
            try {
                p.outputStream.bufferedWriter().use { w ->
                    w.write(script)
                    if (!script.endsWith('\n')) w.write("\n")
                }
            } catch (t: Throwable) {
                // stdin 写失败多为 su 已拒/授权超时，继续走 wait 收尸拿 exit
            }
            val done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!done) {
                p.destroyForcibly()
                reader.join(500)
                val text = synchronized(out) { out.toString() }
                return SuResult(false, text, "su 超时 ${timeoutMs}ms（root 授权未通过或进程挂起）")
            }
            reader.join(500)
            val text = synchronized(out) { out.toString() }
            if (p.exitValue() == 0) SuResult(true, text, null)
            else SuResult(false, text, "exit=${p.exitValue()} ${text.trim().take(200).ifBlank { "(无输出)" }}")
        } catch (t: Throwable) {
            SuResult(false, "", "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // ---------- 节流日志 ----------

    private val throttleAt = ConcurrentHashMap<String, Long>()

    private fun throttled(key: String, msg: String, intervalMs: Long = 2000L) {
        val now = SystemClock.uptimeMillis()
        val last = throttleAt[key]
        if (last != null && now - last < intervalMs) return
        throttleAt[key] = now
        if (throttleAt.size > 32) throttleAt.clear()
        // key 回显进文本，按 key grep 可命中
        Lg.i(TAG, "[$key] $msg")
    }
}
