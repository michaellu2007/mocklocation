package com.learning.mockrun

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 收藏位置(filesDir 单 JSON,与 RouteStore 同款原子写)。
 * 收藏的是 WGS-84 坐标,列表点击 = 设为锚点并飞过去。
 */
object FavoriteStore {

    data class Favorite(val name: String, val lat: Double, val lng: Double, val createdAt: Long)

    private fun file(context: Context) = File(context.filesDir, "favorites.json")

    fun list(context: Context): List<Favorite> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Favorite(
                    o.optString("name", "未命名"),
                    o.getDouble("lat"),
                    o.getDouble("lng"),
                    o.optLong("time", 0L),
                )
            }
        }.getOrDefault(emptyList())
    }

    /** 同名覆盖 */
    fun add(context: Context, fav: Favorite) {
        val all = list(context).filterNot { it.name == fav.name } + fav
        writeAll(context, encodeAll(all))
    }

    fun remove(context: Context, name: String) {
        val all = list(context).filterNot { it.name == name }
        writeAll(context, encodeAll(all))
    }

    private fun writeAll(context: Context, text: String) {
        val f = file(context)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.writeText(text)
            tmp.delete()
        }
    }

    fun encodeAll(all: List<Favorite>): String {
        val arr = JSONArray()
        all.forEach {
            arr.put(
                JSONObject()
                    .put("name", it.name)
                    .put("lat", it.lat)
                    .put("lng", it.lng)
                    .put("time", it.createdAt)
            )
        }
        return arr.toString(2)
    }
}
