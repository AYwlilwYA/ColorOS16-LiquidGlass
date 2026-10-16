package com.coloros16.liquidglass.log

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * 日志文件读写通道：物理文件 = 模块 app `filesDir/logs/lg.log`。
 *
 * hook 进程（SystemUI / 桌面）经 content uri 把日志写进模块自身数据目录，
 * 设置页分享入口读同一份文件。日志含界面类名，故 exported 但逐入口校验调用方。
 */
class LogProvider : ContentProvider() {

    companion object {
        private const val LOG_DIR = "logs"
        private const val LOG_FILE = "lg.log"
        /** 单个日志文件上限，超过即轮转（主文件 + .1 共约 4MB） */
        private const val ROTATE_BYTES = 2L * 1024 * 1024

        /** 允许访问的调用方（日志含界面类名，不放开给任意 app） */
        private val ALLOWED_PACKAGES = setOf(
            "com.android.systemui",
            "com.android.launcher",
            "com.coloros16.liquidglass",
        )
    }

    override fun onCreate(): Boolean = true

    /** 只放行白名单包名（日志含界面类名，不放开给任意 app）。 */
    private fun checkCaller() {
        // 本进程调用直接放行，避免读到上一次远程调用留下的包名
        if (Binder.getCallingUid() == android.os.Process.myUid()) return
        callingPackage?.let { if (it in ALLOWED_PACKAGES) return }
        val uid = Binder.getCallingUid()
        val pkgs = try {
            context?.packageManager?.getPackagesForUid(uid)
        } catch (t: Throwable) {
            null
        }
        if (pkgs != null && pkgs.any { it in ALLOWED_PACKAGES }) return
        throw SecurityException("lg log access denied: uid=$uid pkg=$callingPackage")
    }

    private fun logFile(): File {
        val root = context?.filesDir ?: throw IllegalStateException("no context")
        return File(File(root, LOG_DIR), LOG_FILE)
    }

    /** 写入打开前轮转：主文件超上限就改名成 .1（旧 .1 直接丢弃）。 */
    private fun rotateIfNeeded(file: File) {
        if (file.length() <= ROTATE_BYTES) return
        val old = File(file.parentFile, "$LOG_FILE.1")
        try {
            if (old.exists()) old.delete()
            file.renameTo(old)
        } catch (t: Throwable) {
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        checkCaller()
        if (uri.path != "/log") throw FileNotFoundException("unknown path: $uri")
        val file = logFile()
        file.parentFile?.mkdirs()
        if (mode.contains('w')) rotateIfNeeded(file)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        checkCaller()
        return null
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        checkCaller()
        return null
    }

    override fun getType(uri: Uri): String = "text/plain"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
