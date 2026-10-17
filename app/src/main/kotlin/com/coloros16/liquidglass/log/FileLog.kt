package com.coloros16.liquidglass.log

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 应用内日志文件写入端（每个进程一份静态实例）。
 *
 * 三通道并联，任何单点失效都不丢证据：
 * A = LogProvider 管道写进模块 app 私有目录（设置页「分享日志」的主文件）；
 * B = 本进程 filesDir 直写镜像（进程写自己的目录，物理上不可能失败）；
 * C = 框架 prefs 心跳回传（设置页「通道状态」仪表盘 + A 失败时携带日志尾部）。
 * 队列满与刷屏熔断（同 tag+同消息 10s 一条）直接丢弃并计数；门控读取异常默认开。
 */
object FileLog {

    const val LEVEL_DEBUG = 3
    const val LEVEL_INFO = 4
    const val LEVEL_WARN = 5
    const val LEVEL_ERROR = 6

    /** 日志写入端 authority（LogProvider 在 manifest 里注册的 uri） */
    private const val AUTHORITY = "com.coloros16.liquidglass.lgfile"
    private val LOG_URI: Uri = Uri.parse("content://$AUTHORITY/log")

    private const val QUEUE_CAPACITY = 512
    private const val BATCH_MAX = 64
    private const val BATCH_WINDOW_MS = 300L
    // 心跳线程空闲 tick：对齐失败心跳 5s 节奏；过短会频繁唤醒拖住深度睡眠
    private const val IDLE_WAIT_MS = 5_000L

    private const val FAIL_STREAK_DROP_LIMIT = 32

    private const val FAIL_RETRY_BACKOFF_MS = 1_000L
    private const val GATE_TTL_MS = 1000L
    private const val ERROR_LOG_INTERVAL_MS = 10_000L
    private const val DEDUP_TTL_MS = 10_000L
    private const val HB_OK_INTERVAL_MS = 15_000L
    private const val HB_FAIL_INTERVAL_MS = 5_000L
    private const val ROTATE_BYTES = 2L * 1024 * 1024
    private const val TAIL_MAX = 4000
    private const val TAG = "FileLog"

    private class Entry(val level: Int, val tag: String, val msg: String, val timeMs: Long)

    private val queue = LinkedBlockingQueue<Entry>(QUEUE_CAPACITY)
    private val droppedCount = AtomicLong(0)
    private val totalFail = AtomicLong(0)
    private val lastEmitAt = ConcurrentHashMap<String, Long>()
    private val tailBuf = StringBuilder()

    @Volatile
    private var writerStarted = false

    @Volatile
    private var context: Context? = null

    @Volatile
    private var gateReader: (() -> Boolean)? = null

    @Volatile
    private var gateValue = false

    @Volatile
    private var gateAtMs = 0L

    @Volatile
    private var lastErrorLogAtMs = 0L

    @Volatile
    private var diagWriter: ((String, String, String) -> Unit)? = null

    @Volatile
    private var lastOkAtMs = 0L

    @Volatile
    private var failStreak = 0

    @Volatile
    private var lastHbPublishAtMs = 0L

    /** 固定写入用的模块侧 Context（模块 app 进程在 Application 里设置）。 */
    fun setContext(ctx: Context?) {
        context = ctx?.applicationContext ?: ctx
    }

    /** 注入「运行日志」开关读取器（hook 进程读框架侧 Prefs，模块进程读自身 Prefs）。 */
    fun installGateReader(reader: () -> Boolean) {
        gateReader = reader
        gateAtMs = 0L
    }

    /** 注入心跳回传通道（C）：参数 = 进程 slug、心跳串、日志尾部。 */
    fun installDiagWriter(writer: (slug: String, hb: String, chunk: String) -> Unit) {
        diagWriter = writer
        lastHbPublishAtMs = 0L
    }

    /** 入队一条日志（非阻塞；刷屏熔断与队列满丢弃并计数）。 */
    fun enqueue(level: Int, tag: String, msg: String) {
        if (!gateOpen(level)) return
        val now = System.currentTimeMillis()
        val key = "$level|$tag|${msg.hashCode()}"
        val last = lastEmitAt[key]
        if (last != null && now - last < DEDUP_TTL_MS) {
            droppedCount.incrementAndGet()
            return
        }
        lastEmitAt[key] = now
        if (lastEmitAt.size > 512) {
            val it = lastEmitAt.entries.iterator()
            while (it.hasNext()) {
                if (now - it.next().value >= DEDUP_TTL_MS) it.remove()
            }
        }
        if (!queue.offer(Entry(level, tag, msg, now))) {
            droppedCount.incrementAndGet()
        }
        startWriter()
    }

    /** 取当前进程 Context 的兜底链：Xposed AndroidAppHelper → ActivityThread Application → 系统 Context。 */
    fun processContext(): Context? {
        try {
            val ctx = Class.forName("de.robv.android.xposed.AndroidAppHelper")
                .getMethod("currentApplication").invoke(null) as? Context
            if (ctx != null) return ctx
        } catch (_: Throwable) {
        }
        try {
            val ctx = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null) as? Context
            if (ctx != null) return ctx
        } catch (_: Throwable) {
        }
        return try {
            val at = Class.forName("android.app.ActivityThread")
            val thread = at.getMethod("currentActivityThread").invoke(null)
            (at.getMethod("getSystemContext").invoke(thread) as? Context)?.takeIf {
                // system context 的 opPackageName 是 "android"，跨进程 openFileDescriptor 会因包名与 uid 不一致被拒；宁可等 Application 就绪后重取
                it.packageName != "android"
            }
        } catch (t: Throwable) {
            null
        }
    }

    /** 开关状态（1s TTL 缓存）：e 级恒放行；未装读取器或读取异常一律默认开，杜绝静默丢日志。 */
    private fun gateOpen(level: Int): Boolean {
        if (level >= LEVEL_ERROR) return true
        val reader = gateReader ?: return true
        val now = SystemClock.uptimeMillis()
        if (now - gateAtMs < GATE_TTL_MS) return gateValue
        val v = try {
            reader()
        } catch (t: Throwable) {
            true
        }
        gateValue = v
        gateAtMs = now
        return v
    }

    private fun startWriter() {
        if (writerStarted) return
        synchronized(this) {
            if (writerStarted) return
            val thread = Thread({ writerLoop() }, "lg-filelog")
            thread.isDaemon = true
            thread.start()
            writerStarted = true
        }
    }

    /** 单线程攒批：攒满 [BATCH_MAX] 条或等满 [BATCH_WINDOW_MS] 毫秒写一次。 */
    private fun writerLoop() {
        val batch = ArrayList<Entry>(BATCH_MAX)
        while (true) {
            batch.clear()
            val first = try {
                queue.poll(IDLE_WAIT_MS, TimeUnit.MILLISECONDS)
            } catch (t: InterruptedException) {
                null
            }
            if (first == null) {
                // 空闲也要按节奏发心跳，否则静默进程在仪表盘上像死的
                publishDiag(failStreak == 0)
                continue
            }
            batch.add(first)
            val deadline = SystemClock.uptimeMillis() + BATCH_WINDOW_MS
            while (batch.size < BATCH_MAX) {
                val remain = deadline - SystemClock.uptimeMillis()
                if (remain <= 0) break
                val more = try {
                    queue.poll(remain, TimeUnit.MILLISECONDS)
                } catch (t: InterruptedException) {
                    null
                } ?: break
                batch.add(more)
            }
            queue.drainTo(batch, BATCH_MAX - batch.size)
            flush(batch)
        }
    }

    /** 一批日志：B 镜像恒写 → A 管道尽力写 → C 心跳按节奏回传。 */
    private fun flush(batch: List<Entry>) {
        val bytes = buildText(batch)
        val text = String(bytes, Charsets.UTF_8)
        mirrorWrite(text)
        synchronized(tailBuf) {
            tailBuf.append(text)
            if (tailBuf.length > TAIL_MAX) tailBuf.delete(0, tailBuf.length - TAIL_MAX)
        }
        val ctx = context ?: processContext()?.also { context = it }
        var ok = false
        if (ctx == null) {
            // Application 就绪前（onModuleLoaded 窗口期）不丢批：放回队尾等 context 可用后补写
            queue.addAll(batch)
            return
        } else {
            try {
                val pfd = ctx.contentResolver.openFileDescriptor(LOG_URI, "wa")
                    ?: throw IOException("openFileDescriptor returned null")
                ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { it.write(bytes) }
                ok = true
            } catch (t: Throwable) {
                totalFail.incrementAndGet()
                failStreak++
                logThrottled("write failed: $t")
                // 瞬态失败（模块进程/provider 未起等）回队退避重试；连续失败过多才真丢
                if (failStreak < FAIL_STREAK_DROP_LIMIT) {
                    queue.addAll(batch)
                    try {
                        Thread.sleep(FAIL_RETRY_BACKOFF_MS)
                    } catch (_: InterruptedException) {
                    }
                } else {
                    droppedCount.addAndGet(batch.size.toLong())
                }
            }
        }
        if (ok) {
            lastOkAtMs = System.currentTimeMillis()
            failStreak = 0
        }
        publishDiag(ok)
    }

    /** B 通道：本进程 filesDir 直写镜像（进程写自己的目录，不会失败）。 */
    private fun mirrorWrite(text: String) {
        try {
            val ctx = context ?: processContext()?.also { context = it } ?: return
            val dir = File(ctx.filesDir, "logs")
            dir.mkdirs()
            val name = "lg-${slug()}.log"
            val f = File(dir, name)
            if (f.length() > ROTATE_BYTES) {
                val old = File(dir, "$name.1")
                if (old.exists()) old.delete()
                f.renameTo(old)
            }
            f.appendText(text)
        } catch (t: Throwable) {
            logThrottled("mirror write failed: $t")
        }
    }

    /** C 通道：A 失败 5s 一发、正常 15s 一发，携带心跳与日志尾部。 */
    private fun publishDiag(ok: Boolean) {
        val w = diagWriter ?: return
        val now = System.currentTimeMillis()
        val interval = if (ok) HB_OK_INTERVAL_MS else HB_FAIL_INTERVAL_MS
        if (now - lastHbPublishAtMs < interval) return
        lastHbPublishAtMs = now
        val hb = "ok=$lastOkAtMs fail=$failStreak total=${totalFail.get()} drop=${droppedCount.get()}"
        val chunk = synchronized(tailBuf) { tailBuf.toString() }
        try {
            w(slug(), hb, chunk)
        } catch (t: Throwable) {
        }
    }

    /** 进程标识：按包名归一，设置页仪表盘与 B 镜像文件共用。 */
    private fun slug(): String {
        val pkg = context?.packageName ?: return "unknown"
        return when (pkg) {
            "com.android.systemui" -> "systemui"
            "com.android.launcher" -> "launcher"
            else -> "app"
        }
    }

    /** 把一批日志拼成文件文本（带丢弃计数前缀）。 */
    private fun buildText(batch: List<Entry>): ByteArray {
        val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
        val pid = Process.myPid()
        val sb = StringBuilder(batch.size * 96)
        val dropped = droppedCount.getAndSet(0L)
        if (dropped > 0) {
            sb.append(fmt.format(Date())).append(" W/FileLog: ")
                .append(dropped).append(" records dropped\n")
        }
        for (e in batch) {
            sb.append(fmt.format(Date(e.timeMs))).append(' ')
                .append(levelChar(e.level)).append('/').append(e.tag)
                .append('(').append(pid).append("): ")
                .append(e.msg).append('\n')
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun levelChar(level: Int): Char = when (level) {
        LEVEL_DEBUG -> 'D'
        LEVEL_INFO -> 'I'
        LEVEL_WARN -> 'W'
        else -> 'E'
    }

    /** 自身报错走 logcat，10 秒最多一条，避免刷屏。 */
    private fun logThrottled(msg: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastErrorLogAtMs < ERROR_LOG_INTERVAL_MS) return
        lastErrorLogAtMs = now
        Log.e(TAG, msg)
    }
}
