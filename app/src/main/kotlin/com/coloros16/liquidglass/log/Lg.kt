package com.coloros16.liquidglass.log

import android.util.Log

/**
 * 日志双通道出口：照常打 logcat，同一份内容再进 [FileLog] 文件通道。
 * 文件侧按模块「运行日志」开关门控（开 = 全量，关 = 只留 e 级）；logcat 侧行为不变。
 */
object Lg {

    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        FileLog.enqueue(FileLog.LEVEL_DEBUG, tag, msg)
    }

    fun d(tag: String, msg: String, tr: Throwable?) {
        Log.d(tag, msg, tr)
        FileLog.enqueue(FileLog.LEVEL_DEBUG, tag, withTrace(msg, tr))
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        FileLog.enqueue(FileLog.LEVEL_INFO, tag, msg)
    }

    fun i(tag: String, msg: String, tr: Throwable?) {
        Log.i(tag, msg, tr)
        FileLog.enqueue(FileLog.LEVEL_INFO, tag, withTrace(msg, tr))
    }

    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
        FileLog.enqueue(FileLog.LEVEL_WARN, tag, msg)
    }

    fun w(tag: String, msg: String, tr: Throwable?) {
        Log.w(tag, msg, tr)
        FileLog.enqueue(FileLog.LEVEL_WARN, tag, withTrace(msg, tr))
    }

    fun e(tag: String, msg: String) {
        Log.e(tag, msg)
        FileLog.enqueue(FileLog.LEVEL_ERROR, tag, msg)
    }

    fun e(tag: String, msg: String, tr: Throwable?) {
        Log.e(tag, msg, tr)
        FileLog.enqueue(FileLog.LEVEL_ERROR, tag, withTrace(msg, tr))
    }

    /** 文件里的异常行附带完整堆栈（logcat 侧由原生 Log 自己渲染）。 */
    private fun withTrace(msg: String, tr: Throwable?): String =
        if (tr == null) msg else msg + "\n" + Log.getStackTraceString(tr)
}
