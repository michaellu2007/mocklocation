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
                parseRoute(arr.getJSONObject(i).toString())
            }
        }.getOrDefault(emptyList())
    }

    /** 同名覆盖,其余保持原顺序 */
    fun add(context: Context, route: SavedRoute) {
        val all = list(context).filterNot { it.name == route.name } + route
        file(context).writeText(encodeAll(all))
    }

    fun remove(context: Context, name: String) {
        val all = list(context).filterNot { it.name == name }
        file(context).writeText(encodeAll(all))
    }

    fun encodeAll(all: List<SavedRoute>): String {
        val arr = JSONArray()
        all.forEach { arr.put(encode(it)) }
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
