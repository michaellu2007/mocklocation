package com.learning.mockrun

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃捕获:未捕获异常时把堆栈+环境信息写成文件(filesDir/crash/),保留最近 5 份,
 * 然后交回系统默认处理器(照常结束进程,不做僵尸续命)。
 * 用户在设置页「导出诊断信息」一键生成诊断包,经分享面板发给作者。
 */
object CrashReporter {

    private const val KEEP = 5

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeReport(app, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun crashDir(context: Context) = File(context.filesDir, "crash").apply { mkdirs() }

    fun latestCrashes(context: Context, n: Int = KEEP): List<File> =
        crashDir(context).listFiles()?.sortedByDescending { it.name }?.take(n) ?: emptyList()

    private fun writeReport(context: Context, thread: Thread, throwable: Throwable) {
        val name = "crash-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date()) + ".txt"
        File(crashDir(context), name).writeText(buildText(context, throwable, thread.name))
        latestCrashes(context, KEEP + 1).drop(KEEP).forEach { it.delete() }
    }

    /** 诊断全文:环境信息 + (可选)崩溃堆栈 + 本应用 logcat 尾部(第三方应用只能读自己的日志) */
    fun buildText(context: Context, throwable: Throwable? = null, threadName: String = "main"): String {
        val sb = StringBuilder()
        sb.appendLine("== MockRun 诊断 ==")
        sb.appendLine("时间: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
        sb.appendLine("版本: " + runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrDefault("?"))
        sb.appendLine("机型: " + Build.MANUFACTURER + " " + Build.MODEL)
        sb.appendLine("系统: Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")")
        if (throwable != null) {
            sb.appendLine()
            sb.appendLine("== 崩溃堆栈 (线程: $threadName) ==")
            sb.appendLine(throwable.stackTraceToString())
        }
        runCatching {
            val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "--pid=" + android.os.Process.myPid(), "-t", "300"))
            val out = proc.inputStream.readBytes().toString(Charsets.UTF_8)
            if (out.isNotBlank()) {
                sb.appendLine()
                sb.appendLine("== 应用日志尾部 ==")
                sb.appendLine(out)
            }
        }
        return sb.toString()
    }
}
