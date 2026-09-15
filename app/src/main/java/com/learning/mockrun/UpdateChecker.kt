package com.learning.mockrun

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 在线检查更新:查询 GitHub Releases 最新版(用户点按钮才发起,单次请求)。
 * repo 形如 "owner/repo";release 需附 .apk 资产(下载按钮直达)或仅取 releases 页链接。
 */
object UpdateChecker {

    data class Result(
        val ok: Boolean,          // 请求与解析是否成功
        val hasUpdate: Boolean,   // latest 是否比本地新
        val latestVersion: String,
        val notes: String,        // release 说明(正文,可长)
        val apkUrl: String,       // .apk 资产直链(无则空)
        val releasesUrl: String,  // releases 页面(兜底跳转)
        val error: String?
    )

    fun check(repo: String, currentVersion: String, onResult: (Result) -> Unit) {
        Thread {
            onResult(runCatching {
                val conn = URL("https://api.github.com/repos/$repo/releases/latest")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", "beagleincampus")
                val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                conn.disconnect()
                val obj = JSONObject(body)
                val latest = obj.optString("tag_name", "").removePrefix("v").removePrefix("V")
                var apk = ""
                obj.optJSONArray("assets")?.let { assets ->
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.optString("name", "").endsWith(".apk")) {
                            apk = a.optString("browser_download_url", "")
                            break
                        }
                    }
                }
                Result(
                    ok = true,
                    hasUpdate = isNewer(latest, currentVersion),
                    latestVersion = latest,
                    notes = obj.optString("body", ""),
                    apkUrl = apk,
                    releasesUrl = "https://github.com/$repo/releases/latest",
                    error = null
                )
            }.getOrElse {
                Result(false, false, "", "", "",
                    "https://github.com/$repo/releases/latest", it.message ?: "网络异常")
            })
        }.start()
    }

    /** 版本号比较:按 '.' 分段取数字比较(容忍 v 前缀与非数字尾巴) */
    fun isNewer(latest: String, current: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").removePrefix("V")
            .split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val a = parts(latest)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
