package com.dailynotes.app.data

import android.os.Build
import android.util.Log
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque

/**
 * 诊断日志：内存环形缓冲（500 条），关键路径打点。
 * 用途：真机不在 adb 范围时，用户点「我」→「上传诊断日志」把缓冲 PUT 到服务端 logs/，
 * 开发者从服务端直接拉取分析（logs/ 不在 TOP_DIRS，不会被同步逻辑拉回手机端）。
 * 注意：只打 relPath/耗时/状态，禁止记录正文内容。
 * 线程安全：log() 会从主线程与并发 IO 线程（pager 相邻页预取）同时调用——
 * 时间戳用不可变 DateTimeFormatter（SimpleDateFormat 非线程安全，review M1）。
 */
object DiagLog {
    private const val CAP = 500
    private val buf = ArrayDeque<String>(CAP)
    private val fmt: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")
    private val zone = ZoneId.systemDefault()

    private fun now(): String = fmt.format(Instant.ofEpochMilli(System.currentTimeMillis()).atZone(zone))

    fun log(tag: String, msg: String) {
        val line = "${now()} [$tag] $msg"
        synchronized(buf) {
            if (buf.size >= CAP) buf.removeFirst()
            buf.addLast(line)
        }
        Log.d("DIAG", line)
    }

    fun dump(): String = synchronized(buf) {
        buildString {
            appendLine("== dailynotes diag ==")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})")
            appendLine("app: ${com.dailynotes.app.BuildConfig.VERSION_NAME} (${com.dailynotes.app.BuildConfig.VERSION_CODE})")
            appendLine("captured: ${now()}")
            appendLine("== ${buf.size} entries ==")
            buf.forEach(::appendLine)
        }
    }
}
