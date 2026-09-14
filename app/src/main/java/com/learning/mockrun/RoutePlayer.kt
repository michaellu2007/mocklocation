package com.learning.mockrun

import java.util.Random
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** WGS-84 坐标点(注入和路线统一用这个坐标系) */
data class GeoPoint(val lat: Double, val lng: Double)

/** 每个喂点时刻的完整运动状态 */
data class MockMotion(
    val lat: Double,
    val lng: Double,
    val bearingDeg: Float,
    val speedMps: Float,
    val accuracyM: Float,
    val altitudeM: Double,
)

/**
 * 轨迹回放引擎。
 *
 * 数学模型(参考公开的 GPS 噪声建模方法,按推模型重写):
 * - 位置: 沿折线按 baseSpeed×elapsed 匀速推进;闭合环线取模循环,开线往返
 * - 漂移: Ornstein-Uhlenbeck 随机游走
 *     X(t+dt) = X(t) + sigma*sqrt(dt)*N(0,1) - alpha*X(t)*dt
 *   高斯白噪声频谱均匀,FFT 检测不出单频峰;均值回归项(0.05/s)模拟真实 GPS
 *   滤波器把异常漂移拉回,使偏移有界
 * - 步频横向抖动: 每步 ~0.15m 高斯偏移,垂直于前进方向
 * - Accuracy/Altitude/速度: 独立的小幅高斯漂移,消除固定常数的机器痕迹
 *
 * 推模型契约: 服务每个喂点周期调一次 at(),传入 SystemClock.elapsedRealtimeNanos()
 * (严格单调递增);内部噪声状态按调用序列演化,测试可注入固定种子。
 */
class RoutePlayer(
    route: List<GeoPoint>,
    private val baseSpeedMps: Double,
    private val startElapsedNanos: Long,
    private val wobble: Boolean = true,
    seed: Long = System.nanoTime(),
) {
    private val rng = Random(seed)

    // 单点模式(路线只有 1 点): 无步频抖动、速度为 0,但漂移让静止点"活着"
    private val staticPoint: GeoPoint?

    private val pts: List<GeoPoint>
    private val cum: DoubleArray
    private val totalLengthM: Double
    private val closedLoop: Boolean

    init {
        require(route.isNotEmpty()) { "route 不能为空" }
        if (route.size < 2) {
            staticPoint = route[0]
            pts = emptyList()
            cum = DoubleArray(0)
            totalLengthM = 0.0
            closedLoop = false
        } else {
            staticPoint = null
            // 首尾几乎重合 → 闭合环线,去掉重复尾点,循环靠取模
            val clean = if (haversine(route.first(), route.last()) <= 5.0) route.dropLast(1) else route
            pts = clean
            val c = DoubleArray(clean.size)
            var total = 0.0
            for (i in 0 until clean.size - 1) {
                total += haversine(clean[i], clean[i + 1])
                c[i + 1] = total
            }
            cum = c
            val gap = haversine(clean.first(), clean.last())
            closedLoop = gap <= 5.0
            totalLengthM = if (closedLoop) total + gap else total
        }
    }

    /** 当前时刻的运动状态 */
    fun at(elapsedRealtimeNanos: Long): MockMotion {
        val elapsedSec = (elapsedRealtimeNanos - startElapsedNanos).coerceAtLeast(0) / 1e9

        if (staticPoint != null) {
            val n = advanceNoise(elapsedRealtimeNanos, moving = false, bearingDeg = 0f)
            return MockMotion(
                lat = staticPoint.lat + n.dLatDeg,
                lng = staticPoint.lng + n.dLngDeg,
                bearingDeg = 0f, speedMps = 0f,
                accuracyM = n.accuracy, altitudeM = n.altitude,
            )
        }

        val dist = baseSpeedMps * elapsedSec
        val cycleLen = if (closedLoop) totalLengthM else totalLengthM * 2
        val distInCycle = if (cycleLen > 0) dist % cycleLen else 0.0
        val forward = closedLoop || distInCycle <= totalLengthM
        val target = if (closedLoop) distInCycle else if (forward) distInCycle else cycleLen - distInCycle

        var seg = 0
        while (seg < pts.size - 2 && cum[seg + 1] < target) seg++
        val from = pts[seg]
        val to = pts[seg + 1]
        val segLen = (cum[seg + 1] - cum[seg]).coerceAtLeast(1e-4)
        val ratio = ((target - cum[seg]) / segLen).coerceIn(0.0, 1.0)
        val lat = from.lat + (to.lat - from.lat) * ratio
        val lng = from.lng + (to.lng - from.lng) * ratio
        val bearing = (if (forward) bearingDeg(from, to) else bearingDeg(to, from)).toFloat()

        val n = advanceNoise(elapsedRealtimeNanos, moving = true, bearingDeg = bearing)
        return MockMotion(
            lat = lat + n.dLatDeg,
            lng = lng + n.dLngDeg,
            bearingDeg = bearing,
            speedMps = n.speed,
            accuracyM = n.accuracy,
            altitudeM = n.altitude,
        )
    }

    // ---- 噪声状态(OU 过程) ----
    private var driftLatM = 0.0
    private var driftLngM = 0.0
    private var accuracyDrift = 0.0
    private var altitudeDrift = 0.0
    private var speedOu = 0.0
    private var lastNanos = 0L

    private class Noise(val dLatDeg: Double, val dLngDeg: Double, val speed: Float, val accuracy: Float, val altitude: Double)

    private fun advanceNoise(nanos: Long, moving: Boolean, bearingDeg: Float): Noise {
        val dt = if (lastNanos > 0) ((nanos - lastNanos) / 1e9).coerceIn(0.01, 5.0) else 1.0
        lastNanos = nanos

        if (!wobble) {
            return Noise(0.0, 0.0, baseSpeedMps.toFloat(), 5f, 10.0)
        }

        // OU 漂移: sigma = jitterRadius/3 (3-sigma 法则),alpha = 0.05 均值回归
        val sigma = jitterRadiusM / 3.0
        val alpha = 0.05
        driftLatM += sigma * sqrt(dt) * rng.nextGaussian() - alpha * driftLatM * dt
        driftLngM += sigma * sqrt(dt) * rng.nextGaussian() - alpha * driftLngM * dt

        // 步频横向抖动: 垂直于前进方向的小步偏移
        var lateralM = 0.0
        if (moving && baseSpeedMps > 0.3) lateralM = 0.15 * rng.nextGaussian()
        val br = Math.toRadians(bearingDeg.toDouble())
        val perpLatM = lateralM * cos(br + PI / 2)
        val perpLngM = lateralM * sin(br + PI / 2)

        val latDeg = metersToDegLat(driftLatM + perpLatM)
        val lngDeg = metersToDegLng(driftLngM + perpLngM, Math.toRadians(latDeg))

        // 速度小幅波动: 真实跑者的瞬时速度不是常数
        speedOu += 0.12 * rng.nextGaussian() - 0.08 * speedOu
        val speed = (baseSpeedMps + if (moving) speedOu else 0.0).coerceAtLeast(0.0)

        // Accuracy: GDOP 缓慢变化,基准 jitterRadius+2,限 [2,50]
        accuracyDrift += 0.3 * rng.nextGaussian() - 0.02 * accuracyDrift
        val accuracy = (jitterRadiusM + 2.0 + accuracyDrift).coerceIn(2.0, 50.0).toFloat()

        // Altitude: 垂直精度比水平差,漂移幅度更大,限 [0,100]
        altitudeDrift += 0.5 * rng.nextGaussian() - 0.01 * altitudeDrift
        val altitude = (10.0 + altitudeDrift).coerceIn(0.0, 100.0)

        return Noise(latDeg, lngDeg, speed.toFloat(), accuracy, altitude)
    }

    /** 漂移半径随速度分档: 步行 5m / 跑步 8m / 更快 3m */
    private val jitterRadiusM: Double
        get() = when {
            baseSpeedMps < 2.0 -> 5.0
            baseSpeedMps < 6.0 -> 8.0
            else -> 3.0
        }

    companion object {
        const val EARTH_R = 6378137.0
        private const val DEG_PER_M = 180.0 / (PI * EARTH_R)

        fun haversine(a: GeoPoint, b: GeoPoint): Double {
            val dLat = Math.toRadians(b.lat - a.lat)
            val dLng = Math.toRadians(b.lng - a.lng)
            val h = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2).pow(2)
            return 2 * EARTH_R * atan2(sqrt(h), sqrt(1 - h))
        }

        fun bearingDeg(from: GeoPoint, to: GeoPoint): Double {
            val lat1 = Math.toRadians(from.lat)
            val lat2 = Math.toRadians(to.lat)
            val dLng = Math.toRadians(to.lng - from.lng)
            val y = sin(dLng) * cos(lat2)
            val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
            return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
        }

        fun metersToDegLat(m: Double) = m * DEG_PER_M
        fun metersToDegLng(m: Double, latRad: Double) = m * DEG_PER_M / cos(latRad)
    }
}
