package com.learning.mockrun

import android.content.Context

/**
 * 地图引擎,运行时可切换,选择持久化到 SharedPreferences。
 */
enum class MapEngine {
    AMAP,
    BAIDU;

    val displayName: String
        get() = when (this) {
            AMAP -> "高德"
            BAIDU -> "百度"
        }

    /** 该引擎的选点页是否已接入(百度等 aar 到位后在接入点改这里) */
    val available: Boolean
        get() = when (this) {
            AMAP -> true
            BAIDU -> false
        }

    companion object {
        private const val PREFS = "settings"
        private const val KEY_ENGINE = "map_engine"

        fun load(context: Context): MapEngine {
            val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ENGINE, AMAP.name)
            return entries.firstOrNull { it.name == name } ?: AMAP
        }

        fun save(context: Context, engine: MapEngine) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_ENGINE, engine.name).apply()
        }
    }
}
