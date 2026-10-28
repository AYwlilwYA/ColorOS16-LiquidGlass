package com.coloros16.liquidglass.log

import android.content.Context
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * logcat 流式抓取（仅模块 app 进程使用）：`su -c logcat` 子进程 stdout 直写
 * filesDir/captures/lg-capture-<时间戳>.txt。会话归本对象持有——退出设置页不停止；
 * app 进程死亡时子进程随进程组终止，已写入内容保留。
 */
object LogCapture {

    private const val TAG = "LogCapture"
    private const val FILE_PREFIX = "lg-capture-"
    private const val FILE_SUFFIX = ".txt"

    @Volatile
    private var process: Process? = null

    @Volatile
    private var writerThread: Thread? = null

    /** 当前/最近一次抓取的文件（抓取中 = 正在写入）。 */
    @Volatile
    var currentFile: File? = null
        private set

    /** su 可用性探测结果缓存（进程生命周期内一次即可；root 授予状态不会运行时翻转）。 */
    @Volatile
    private var suAvailable: Boolean? = null

    fun isRunning(): Boolean = process?.let { it.isAlive } == true

    /** 抓取文件目录（filesDir/captures）。 */
    fun captureDir(context: Context): File = File(context.filesDir, "captures")

    /** 已抓文件列表（新→旧）。 */
    fun listCaptures(context: Context): List<File> {
        val dir = captureDir(context)
        return dir.listFiles { f -> f.isFile && f.name.startsWith(FILE_PREFIX) && f.name.endsWith(FILE_SUFFIX) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /** 探测 su 可用（阻塞，调用方放后台线程；仅成功结果缓存，失败下次重探）。 */
    fun probeSu(): Boolean {
        suAvailable?.let { return it }
        val ok = try {
            val p = ProcessBuilder("su", "-c", "id")
                .redirectErrorStream(true)
                .start()
            val outHolder = arrayOfNulls<String>(1)
            val reader = Thread {
                outHolder[0] = p.inputStream.bufferedReader().readText()
            }.apply { isDaemon = true }
            reader.start()
            val finished = p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                p.destroyForcibly()
                reader.join(1000)
                false
            } else {
                (outHolder[0] ?: "").contains("uid=0")
            }
        } catch (t: Throwable) {
            false
        }
        if (ok) suAvailable = true
        return ok
    }

    /**
     * 开始抓取：起 logcat 子进程流式写新文件。已在抓取中返回 true（无操作）。
     * @param includeAll true=全量（*:V）；false=仅模块标签（LiquidGlass/AppRuntime）
     * @return 是否处于抓取中。
     */
    @Synchronized
    fun start(context: Context, includeAll: Boolean): Boolean {
        if (isRunning()) return true
        val dir = captureDir(context)
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmssSSS", Locale.US).format(Date())
        val file = File(dir, "$FILE_PREFIX$stamp$FILE_SUFFIX")
        val filter = if (includeAll) {
            "'*:V'"
        } else {
            "'LiquidGlass:V' 'AppRuntime:V' '*:S'"
        }
        val proc = try {
            // su -c 的参数经 sh 解析，过滤串加引号防 *:S 被 glob 展开
            ProcessBuilder("su", "-c", "logcat -v threadtime $filter")
                .redirectErrorStream(true)
                .start()
        } catch (t: Throwable) {
            return false
        }
        val sink = try {
            BufferedOutputStream(FileOutputStream(file), 16 * 1024)
        } catch (t: Throwable) {
            proc.destroy()
            return false
        }
        val pump = Thread({
            try {
                val input = proc.inputStream
                val buf = ByteArray(8 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    sink.write(buf, 0, n)
                    sink.flush()
                }
            } catch (t: Throwable) {
            } finally {
                try {
                    sink.close()
                } catch (t: Throwable) {
                }
            }
        }, "lg-capture-pump").apply { isDaemon = true }
        process = proc
        writerThread = pump
        currentFile = file
        pump.start()
        return true
    }

    /** 停止抓取：杀 logcat 子进程（泵线程读到 EOF 自行关文件落盘）。 */
    @Synchronized
    fun stop() {
        val p = process ?: return
        process = null
        try {
            p.destroy()
            if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
        } catch (t: Throwable) {
        }
        writerThread = null
    }
}
