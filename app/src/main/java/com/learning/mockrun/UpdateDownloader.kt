package com.learning.mockrun

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 流式下载更新 APK:按源链逐源尝试(镜像→原链),任一源成功即止。
 * 不用 DownloadManager:失败/回退/取消语义完全可控,也不牵扯通知权限。
 * 纯 JVM 无 Android 依赖;回调都在后台线程,切主线程由调用方负责。
 */
object UpdateDownloader {

    /** 下载句柄:cancel() 让进行中的读流尽快中断 */
    class Handle {
        @Volatile
        var cancelled = false
            private set

        fun cancel() {
            cancelled = true
        }
    }

    /**
     * sources 有序候选(见 UpdateChecker.apkSources);expectedSize>0 时下载完核对
     * 字节数,不符当失败换下一源。onProgress(0..100)/onFinished 均在后台线程回调。
     */
    fun download(
        sources: List<String>,
        target: File,
        expectedSize: Long = 0L,
        onProgress: (Int) -> Unit = {},
        onFinished: (ok: Boolean, file: File?, error: String?) -> Unit,
    ): Handle {
        val handle = Handle()
        Thread {
            val part = File(target.parentFile, target.name + ".part")
            var lastError = "无可用下载源"
            for (src in sources) {
                if (handle.cancelled) break
                val outcome = runCatching { fetch(src, target, expectedSize, handle, onProgress) }
                outcome.getOrNull()?.let {
                    onFinished(true, it, null)
                    return@Thread
                }
                lastError = outcome.exceptionOrNull()?.message ?: "下载失败"
                part.delete()
            }
            part.delete()
            onFinished(false, null, if (handle.cancelled) "已取消" else lastError)
        }.start()
        return handle
    }

    private fun fetch(
        src: String,
        target: File,
        expectedSize: Long,
        handle: Handle,
        onProgress: (Int) -> Unit,
    ): File {
        val conn = URL(src).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.setRequestProperty("User-Agent", "beagleincampus")
        try {
            if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: expectedSize
            val part = File(target.parentFile, target.name + ".part")
            target.parentFile?.mkdirs()
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        if (handle.cancelled) throw IllegalStateException("已取消")
                        val n = input.read(buf)
                        if (n == -1) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt().coerceAtMost(100)
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }
            if (expectedSize > 0 && part.length() != expectedSize) {
                throw IllegalStateException("大小不符(${part.length()}≠$expectedSize)")
            }
            if (!part.renameTo(target)) {
                target.delete()
                if (!part.renameTo(target)) throw IllegalStateException("落盘失败")
            }
            return target
        } finally {
            conn.disconnect()
        }
    }
}
