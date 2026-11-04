package com.coloros16.liquidglass.debug

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Handler
import android.os.Looper
import com.coloros16.liquidglass.log.Lg
import java.util.concurrent.Executors

/**
 * root 悬浮球控制端：app 域无 su 通道（exec 实测 EACCES），起球/停止/状态
 * 经 abstract socket（lgballd）交 root daemon 代执行；daemon 未运行时回显
 * root 单行启动命令。
 */
object RootBallLauncher {

    private const val TAG = "RootBallLauncher"
    private const val SOCK_NAME = "lgballd"
    private const val BIN_DEV = "/data/local/tmp/lgball"
    private const val LOG_DEV = "/data/local/tmp/lgball.log"

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun start(onDone: (String) -> Unit) = send("START", onDone)

    fun stop(onDone: (String) -> Unit) = send("STOP", onDone)

    fun status(onDone: (String) -> Unit) = send("STATUS", onDone)

    /** 单行协议收发；按钮频率低不节流，成功/失败全链留日志。 */
    private fun send(cmd: String, onDone: (String) -> Unit) {
        io.execute {
            val msg = runCatching {
                LocalSocket().use { s ->
                    s.connect(LocalSocketAddress(SOCK_NAME, LocalSocketAddress.Namespace.ABSTRACT))
                    s.outputStream.write("$cmd\n".toByteArray(Charsets.UTF_8))
                    s.outputStream.flush()
                    val reply = s.inputStream.bufferedReader().readLine() ?: ""
                    Lg.i(TAG, "$cmd -> ${reply.ifBlank { "(空)" }}")
                    syncBridge(cmd, reply)
                    humanize(reply)
                }
            }.getOrElse { t ->
                Lg.w(TAG, "$cmd socket failed", t)
                val why = t.message ?: t.javaClass.simpleName
                "连不上球服务（$why）；若服务未起：root 执行 $BIN_DEV daemon &"
            }
            main.post { onDone(msg) }
        }
    }

    /** 球活桥才活：正答拉起点选桥，停球成功收桥（状态查询为正答也拉起，恢复进程被杀后的桥）。 */
    private fun syncBridge(cmd: String, reply: String) {
        val ballAlive = reply.startsWith("OK pid=") ||
            reply.startsWith("OK running pid=") ||
            reply.startsWith("ERR running pid=")
        val ballStopped = reply.startsWith("OK stopped pid=") || reply == "OK not-running"
        when {
            cmd == "STOP" && ballStopped -> PickBridge.shutdown()
            ballAlive -> PickBridge.ensureRunning()
            cmd == "STOP" -> Lg.i(TAG, "stop not confirmed, keep bridge pid-reply=$reply")
        }
    }

    /** daemon 原始回复 → 调试页人话。 */
    private fun humanize(reply: String): String = when {
        reply.startsWith("OK pid=") -> "已启动 pid=${reply.removePrefix("OK pid=")}"
        reply.startsWith("OK stopped pid=") -> "已停止（原 pid=${reply.removePrefix("OK stopped pid=")}）"
        reply == "OK not-running" -> "未在运行"
        reply.startsWith("OK running pid=") -> "运行中 pid=${reply.removePrefix("OK running pid=")}"
        reply.startsWith("ERR running pid=") -> "已在运行 pid=${reply.removePrefix("ERR running pid=")}"
        reply == "ERR spawn" -> "启动失败：daemon 拉不起球，看 $LOG_DEV"
        reply.startsWith("ERR alive pid=") -> "停止失败，仍有 pid=${reply.removePrefix("ERR alive pid=")}"
        reply.isEmpty() -> "daemon 空回复"
        else -> reply
    }
}
