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
 * 前台服务:把 RoutePlayer 输出的运动状态持续注入 GPS + NETWORK 两个 test provider。
 *
 * 关键约束(踩过的坑,别改):
 * - 注入顺序固定:remove 旧的 → addTestProvider → setTestProviderEnabled(true) → 喂点
 * - elapsedRealtimeNanos 必须用 SystemClock.elapsedRealtimeNanos() 严格单调递增
 * - Location 的 accuracy/time/speed/bearing 必须给值
 * - Android 12+ 禁止后台启动前台服务:只能由 MainActivity 的按钮触发到这里
 * - API 29+ 必须用三参 startForeground 并带 FOREGROUND_SERVICE_TYPE_LOCATION
 */
class MockLocationService : Service() {

    private lateinit var lm: LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private var player: RoutePlayer? = null
    private var running = false

    private val feedTask = object : Runnable {
        override fun run() {
            if (!running) return
            val p = player ?: return
            val motion = p.at(SystemClock.elapsedRealtimeNanos())
            currentMotion = motion
            pushPoint(LocationManager.GPS_PROVIDER, motion, accuracyBonus = 0f)
            pushPoint(LocationManager.NETWORK_PROVIDER, motion, accuracyBonus = 20f)
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
        val pts = intent?.getDoubleArrayExtra(EXTRA_ROUTE)
            ?: doubleArrayOf(MockLocationService.DEFAULT_LAT, DEFAULT_LON)
        val speed = intent?.getDoubleExtra(EXTRA_SPEED, 3.0) ?: 3.0
        val name = intent?.getStringExtra(EXTRA_NAME) ?: getString(R.string.notif_default_name)

        startInForeground(name, speed)

        if (!installProviders()) {
            Log.e(TAG, "installProviders 失败(多半是未授权),服务退出")
            stopSelf()
            return START_NOT_STICKY
        }

        player = RoutePlayer(
            route = pts.toList().chunked(2).map { GeoPoint(it[0], it[1]) },
            baseSpeedMps = speed,
            startElapsedNanos = SystemClock.elapsedRealtimeNanos(),
            wobble = intent?.getBooleanExtra(EXTRA_WOBBLE, true) ?: true,
            loopClosed = intent?.getBooleanExtra(EXTRA_LOOP, false),
        )
        activeName = name
        running = true
        handler.post(feedTask)
        Log.i(TAG, "开始模拟「$name」 speed=$speed pts=${pts.size / 2}")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        player = null
        currentMotion = null
        activeName = null
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

    private fun pushPoint(provider: String, motion: MockMotion, accuracyBonus: Float) {
        try {
            @Suppress("DEPRECATION") // Location(String) 在 API 31 起废弃,但 Builder 是 31+,minSdk 26 只能用它
            val loc = Location(provider).apply {
                latitude = motion.lat
                longitude = motion.lng
                altitude = motion.altitudeM
                accuracy = motion.accuracyM + accuracyBonus
                speed = motion.speedMps
                bearing = motion.bearingDeg
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
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

    private fun startInForeground(name: String, speed: Double) {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_paw)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_route_text, name, "%.1f".format(speed)))
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
        const val DEFAULT_LAT = 39.9042
        const val DEFAULT_LON = 116.4074

        private const val EXTRA_ROUTE = "route_pts"
        private const val EXTRA_SPEED = "speed_mps"
        private const val EXTRA_NAME = "route_name"
        private const val EXTRA_WOBBLE = "wobble"
        private const val EXTRA_LOOP = "loop"

        private val INJECTED_PROVIDERS = arrayOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

        /** 供地图上的比格犬实时刷新(同进程直接读) */
        @Volatile
        var currentMotion: MockMotion? = null
            private set

        @Volatile
        var activeName: String? = null
            private set

        /**
         * @param routePts DoubleArray: lat,lng,lat,lng,...(WGS-84)。
         *   只有 1 个点 → 静止点模式(带漂移),>=2 个点 → 轨迹回放
         */
        fun start(context: Context, name: String, speedMps: Double, routePts: DoubleArray, wobble: Boolean = true, loopClosed: Boolean = false) {
            val intent = Intent(context, MockLocationService::class.java)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_SPEED, speedMps)
                .putExtra(EXTRA_ROUTE, routePts)
                .putExtra(EXTRA_WOBBLE, wobble)
                .putExtra(EXTRA_LOOP, loopClosed)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MockLocationService::class.java))
        }
    }
}
