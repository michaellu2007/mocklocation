package com.learning.mockrun

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 自定义路线的本地存取(filesDir 下单个 JSON 文件,够用且可手改)。
 * 文件交换格式(导出/导入同构):
 * {"name":"...","loop":true,"points":[[lat,lng],[lat,lng],...]}
 */
object RouteStore {

    data class SavedRoute(val name: String, val loop: Boolean, val points: List<GeoPoint>)

    private fun file(context: Context) = File(context.filesDir, "saved_routes.json")

    fun list(context: Context): List<SavedRoute> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                // 旧版 bug 曾把对象编码成字符串写入,这里顺手救回;真正的坏条目跳过
                val text = when (val raw = arr.get(i)) {
                    is JSONObject -> raw.toString()
                    is String -> raw
                    else -> return@mapNotNull null
                }
                parseRoute(text)
            }
        }.getOrDefault(emptyList())
    }

    /** 同名覆盖,其余保持原顺序 */
    fun add(context: Context, route: SavedRoute) {
        val all = list(context).filterNot { it.name == route.name } + route
        writeAll(context, encodeAll(all))
    }

    fun remove(context: Context, name: String) {
        val all = list(context).filterNot { it.name == name }
        writeAll(context, encodeAll(all))
    }

    /** 原位重命名(不打乱顺序);新名与既有路线重复或旧名不存在时返回 false */
    fun rename(context: Context, oldName: String, newName: String): Boolean {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return false
        val all = list(context)
        if (all.none { it.name == oldName }) return false
        if (oldName != trimmed && all.any { it.name == trimmed }) return false
        writeAll(context, encodeAll(all.map {
            if (it.name == oldName) it.copy(name = trimmed) else it
        }))
        return true
    }

    /**
     * 原子写:临时文件 + rename,避免写到一半被杀导致整个路线库损坏。
     * rename 失败(跨文件系统等极端情况)退回直接写。
     */
    private fun writeAll(context: Context, text: String) {
        val f = file(context)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.writeText(text)
            tmp.delete()
        }
    }

    fun encodeAll(all: List<SavedRoute>): String {
        val arr = JSONArray()
        all.forEach { arr.put(JSONObject(encode(it))) }  // 必须放对象;put(String) 会让 list() 永远读回空
        return arr.toString(2)
    }

    fun encode(r: SavedRoute): String {
        val pts = JSONArray()
        r.points.forEach { pts.put(JSONArray().put(it.lat).put(it.lng)) }
        return JSONObject()
            .put("name", r.name)
            .put("loop", r.loop)
            .put("points", pts)
            .toString()
    }

    fun parseRoute(text: String): SavedRoute? {
        return runCatching {
            val obj = if (text.trimStart().startsWith("[")) {
                val arr = JSONArray(text)
                if (arr.length() == 0) return null
                arr.getJSONObject(0)
            } else {
                JSONObject(text)
            }
            val name = obj.optString("name", "未命名路线")
            val loop = obj.optBoolean("loop", true)
            val ptsArr = obj.getJSONArray("points")
            val pts = (0 until ptsArr.length()).map { i ->
                val p = ptsArr.getJSONArray(i)
                GeoPoint(p.getDouble(0), p.getDouble(1))
            }
            if (pts.size < 2) return null
            SavedRoute(name, loop, pts)
        }.getOrNull()
    }
}
