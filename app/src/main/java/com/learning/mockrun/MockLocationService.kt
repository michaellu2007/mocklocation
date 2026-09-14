package com.learning.mockrun

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * 前台服务:按固定间隔向 GPS + NETWORK 两个 test provider 注入同一坐标。
 *
 * 关键约束(踩过的坑,别改):
 * - 注入顺序固定:remove 旧的 → addTestProvider → setTestProviderEnabled(true) → 喂点
 * - elapsedRealtimeNanos 必须用 SystemClock.elapsedRealtimeNanos() 严格单调递增,
 *   用 currentTimeMillis 或不设都会被系统丢弃,表现为"注入没反应"
 * - Location 的 accuracy/time/speed/bearing 必须给值,只设经纬度会被部分消费方当无效点
 * - Android 12+ 禁止后台启动前台服务:只能由 MainActivity 的按钮触发到这里
 * - API 29+ 必须用三参 startForeground 并带 FOREGROUND_SERVICE_TYPE_LOCATION
 */
class MockLocationService : Service() {

    private lateinit var lm: LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private var lat = 0.0
    private var lon = 0.0
    private var running = false

    private val feedTask = object : Runnable {
        override fun run() {
            if (!running) return
            val wallTime = System.currentTimeMillis()
            val elapsed = SystemClock.elapsedRealtimeNanos()
            pushPoint(LocationManager.GPS_PROVIDER, ACCURACY_GPS, wallTime, elapsed)
            pushPoint(LocationManager.NETWORK_PROVIDER, ACCURACY_NET, wallTime, elapsed)
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lat = intent?.getDoubleExtra(EXTRA_LAT, DEFAULT_LAT) ?: DEFAULT_LAT
        lon = intent?.getDoubleExtra(EXTRA_LON, DEFAULT_LON) ?: DEFAULT_LON

        startInForeground()

        if (!installProviders()) {
            // 未拿到 mock_location 授权时 addTestProvider 会抛 SecurityException
            Log.e(TAG, "installProviders 失败(多半是未授权),服务退出")
            stopSelf()
            return START_NOT_STICKY
        }

        running = true
        handler.post(feedTask)
        Log.i(TAG, "开始注入 $lat,$lon")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        for (provider in INJECTED_PROVIDERS) {
            try {
                lm.removeTestProvider(provider)
            } catch (e: Exception) {
                // 系统可能已替我们移除,忽略
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    /** 对已存在的 provider 直接 add 会抛 IAE,先 remove 再 add。 */
    private fun installProviders(): Boolean {
        return try {
            for (provider in INJECTED_PROVIDERS) {
                try {
                    lm.removeTestProvider(provider)
                } catch (e: IllegalArgumentException) {
                    // 本来就不存在,正常
                }
                lm.addTestProvider(
                    provider,
                    false, false, false, false,
                    true, true, true,   // 支持海拔/速度/方位
                    // 旧常量挂在 LocationProvider 上,已被新 SDK 从公共 API 剪掉,
                    // 现搬到 API 31 新增的 android.location.provider.ProviderProperties;
                    // 编译期常量会内联,minSdk 26 的设备运行时不受影响
                    android.location.provider.ProviderProperties.POWER_USAGE_LOW,
                    android.location.provider.ProviderProperties.ACCURACY_FINE
                )
                lm.setTestProviderEnabled(provider, true)
            }
            true
        } catch (e: SecurityException) {
            false
        }
    }

    private fun pushPoint(provider: String, accuracy: Float, wallTime: Long, elapsed: Long) {
        try {
            @Suppress("DEPRECATION") // Location(String) 在 API 31 起废弃,但 Builder 是 31+,minSdk 26 只能用它
            val loc = Location(provider).apply {
                latitude = lat
                longitude = lon
                altitude = 50.0
                this.accuracy = accuracy
                speed = 0f
                bearing = 0f
                time = wallTime
                elapsedRealtimeNanos = elapsed
            }
            lm.setTestProviderLocation(provider, loc)
        } catch (e: Exception) {
            Log.e(TAG, "注入失败($provider): ${e.message}")
            running = false
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startInForeground() {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val text = getString(R.string.notif_text, "%.5f".format(lat), "%.5f".format(lon))
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_pin)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    companion object {
        private const val TAG = "MockLocationService"
        private const val CHANNEL_ID = "mockrun_channel"
        private const val NOTIF_ID = 1
        private const val INTERVAL_MS = 1000L
        private const val ACCURACY_GPS = 5f
        private const val ACCURACY_NET = 25f
        const val EXTRA_LAT = "extra_lat"
        const val EXTRA_LON = "extra_lon"
        const val DEFAULT_LAT = 39.9042
        const val DEFAULT_LON = 116.4074
        private val INJECTED_PROVIDERS = arrayOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

        fun start(context: Context, lat: Double, lon: Double) {
            val intent = Intent(context, MockLocationService::class.java)
                .putExtra(EXTRA_LAT, lat)
                .putExtra(EXTRA_LON, lon)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MockLocationService::class.java))
        }
    }
}
