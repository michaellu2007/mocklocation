package com.learning.mockrun

import android.app.Application
import com.amap.api.maps.MapsInitializer
import com.amap.api.services.core.ServiceSettings

/**
 * 进程入口:在任何地图初始化之前,把用户自己的高德 Key 注入 SDK;
 * 同时累计启动次数(公共 Key 额度提醒用)。
 */
class MockRunApp : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        prefs.edit().putInt("launch_count", prefs.getInt("launch_count", 0) + 1).apply()

        prefs.getString("user_amap_key", "")?.takeIf { it.isNotBlank() }?.let { key ->
            runCatching {
                MapsInitializer.setApiKey(key)
                ServiceSettings.getInstance().setApiKey(key)
            }
        }
    }
}
