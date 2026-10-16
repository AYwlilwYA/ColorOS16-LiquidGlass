package com.coloros16.liquidglass.log

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 应用内日志文件写入端（每个进程一份静态实例）。
 *
 * 写入链路：入队（不阻塞调用线程）→ 单线程攒批 → 经 [LOG_URI] 的 LogProvider 管道
 * 写进模块 app 私有目录（设置页「分享日志」读同一份文件）。
 * 队列满直接丢弃并计数，绝不阻塞 hook / 渲染线程；拿不到 Context 时静默降级。
 * 门控复用模块「运行日志」开关（[installGateReader] 注入读取器，1s TTL 缓存）：
 * 开 = i/d/w/e 全量入文件，关 = 只有 e 级入文件。
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
    private const val IDLE_WAIT_MS = 60_000L
    private const val GATE_TTL_MS = 1000L
    private const val ERROR_LOG_INTERVAL_MS = 10_000L
    private const val TAG = "FileLog"

    private class Entry(val level: Int, val tag: String, val msg: String, val timeMs: Long)

    private val queue = LinkedBlockingQueue<Entry>(QUEUE_CAPACITY)
    private val droppedCount = AtomicLong(0)

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

    /** 固定写入用的模块侧 Context（模块 app 进程在 Application 里设置）。 */
    fun setContext(ctx: Context?) {
        context = ctx?.applicationContext ?: ctx
    }

    /** 注入「运行日志」开关读取器（hook 进程读框架侧 Prefs，模块进程读自身 Prefs）。 */
    fun installGateReader(reader: () -> Boolean) {
        gateReader = reader
        gateAtMs = 0L
    }

    /** 入队一条日志（非阻塞；队列满丢弃并计数）。 */
    fun enqueue(level: Int, tag: String, msg: String) {
        if (!gateOpen(level)) return
        if (!queue.offer(Entry(level, tag, msg, System.currentTimeMillis()))) {
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
            at.getMethod("getSystemContext").invoke(thread) as? Context
        } catch (_: Throwable) {
            null
        }
    }

    /** 开关状态（1s TTL 缓存）：e 级恒放行，其余看「运行日志」开关。 */
    private fun gateOpen(level: Int): Boolean {
        if (level >= LEVEL_ERROR) return true
        val reader = gateReader ?: return false
        val now = SystemClock.uptimeMillis()
        if (now - gateAtMs < GATE_TTL_MS) return gateValue
        val v = try {
            reader()
        } catch (t: Throwable) {
            false
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
            } ?: continue
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

    /** 一批日志一次管道写入（开一次 provider、写完即关）。 */
    private fun flush(batch: List<Entry>) {
        val ctx = context ?: processContext()?.also { context = it }
        if (ctx == null) {
            droppedCount.addAndGet(batch.size.toLong())
            logThrottled("no context, drop ${batch.size} records")
            return
        }
        try {
            val pfd = ctx.contentResolver.openFileDescriptor(LOG_URI, "wa")
                ?: throw IOException("openFileDescriptor returned null")
            ParcelFileDescriptor.AutoCloseOutputStream(pfd).use {
                it.write(buildText(batch))
            }
        } catch (t: Throwable) {
            droppedCount.addAndGet(batch.size.toLong())
            logThrottled("write failed: $t")
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
                .append(dropped).append(" records dropped, queue full\n")
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
        Log.w(TAG, "lg file log: $msg")
    }
}
