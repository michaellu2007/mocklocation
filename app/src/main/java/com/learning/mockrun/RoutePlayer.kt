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
 * 双通道分离(参考实现同款):
 * - 注入/传输(at()): 交给系统 test provider 的位置 = 干净基准 + 有界扰动
 * - 自家显示(cleanMotion()): 纯净理想轨迹,零噪声
 *
 * 数学模型:
 * - 位置: 沿折线按 baseSpeed×elapsed 匀速推进;闭合环线取模循环,开线往返;stopAtEnd 跑完即停
 * - 扰动: 有界 Ornstein-Uhlenbeck(照抄参考实现 hook 层)
 *     X(t+dt) = clamp( X(t) + sigma*sqrt(dt)*N(0,1) - alpha*X(t)*dt )
 *   只叠加在输出上,永不反馈进里程/位置状态,所以不存在误差累计跑偏
 * - 步频横向抖动: 每步 ~0.15m 高斯偏移,垂直于前进方向(可单独关闭)
 * - Accuracy/Altitude/速度: 独立的小幅高斯漂移,消除固定常数的机器痕迹
 *
 * 推模型契约: 服务每个喂点周期调一次 at(),传入 SystemClock.elapsedRealtimeNanos()
 * (严格单调递增);内部噪声状态按调用序列演化,测试可注入固定种子。
 */
class RoutePlayer(
    route: List<GeoPoint>,
    baseSpeedMps: Double,
    private val startElapsedNanos: Long,
    private val wobble: Boolean = true,
    /** 用户勾选的环线开关: true=强制按闭环循环(首尾间缺口自动补一段),false=开线往返 */
    private val loopClosed: Boolean? = null,
    /** 跑完暂停: true=到达终点(环线=回到起点)后停在原地;false=循环/往返不停 */
    private val stopAtEnd: Boolean = false,
    /** 步幅横向抖动(参考实现 enableJitter 默认开,此处拆成独立开关) */
    private val stepJitter: Boolean = true,
    seed: Long = System.nanoTime(),
) {
    private val rng = Random(seed)

    // ---- 受控推进状态(T1a: 暂停/继续/中途调速) ----
    // 里程用累加器而不是 baseSpeed×elapsed: 每段推进只在自己那段区间内线性累加,
    // 所以 pause/resume/setSpeed 都只是移动累加基准,已走里程不受影响,位置天然连续。
    private var currentSpeedMps: Double = baseSpeedMps
    private var accumulatedM: Double = 0.0
    private var anchorNanos: Long = startElapsedNanos
    private var paused = false

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
            // 环线判定: 用户勾了环线开关则强制闭环;未指定时按首尾距离自动判断
            closedLoop = loopClosed ?: (gap <= 5.0)
            totalLengthM = if (closedLoop) total + gap else total
        }
    }

    /** 最近一次 at() 的无噪声基准位置 */
    private var lastClean: MockMotion? = null

    /**
     * 理想轨迹位置(零漂移零抖动),自家 UI 回放显示用。
     * 参考实现同款分离:App 自己地图上是干净插值,噪声只叠加在交给外部 App 的定位上。
     */
    fun cleanMotion(): MockMotion? = lastClean

    /** 当前时刻的运动状态 */
    fun at(elapsedRealtimeNanos: Long): MockMotion {
        if (staticPoint != null) {
            lastClean = MockMotion(staticPoint.lat, staticPoint.lng, 0f, 0f, 5f, 10.0)
            val n = advanceNoise(elapsedRealtimeNanos, moving = false, bearingDeg = 0f)
            return MockMotion(
                lat = staticPoint.lat + n.dLatDeg,
                lng = staticPoint.lng + n.dLngDeg,
                bearingDeg = 0f, speedMps = 0f,
                accuracyM = n.accuracy, altitudeM = n.altitude,
            )
        }

        val dist = advanceDistance(elapsedRealtimeNanos)
        // 跑完暂停: 停在终点(环线=回到起点),速度归零,漂移继续让点"活着"
        if (stopAtEnd && dist >= totalLengthM) {
            val end = if (closedLoop) pts.first() else pts.last()
            lastClean = MockMotion(end.lat, end.lng, 0f, 0f, 5f, 10.0)
            val n = advanceNoise(elapsedRealtimeNanos, moving = false, bearingDeg = 0f)
            return MockMotion(
                lat = end.lat + n.dLatDeg,
                lng = end.lng + n.dLngDeg,
                bearingDeg = 0f, speedMps = 0f,
                accuracyM = n.accuracy, altitudeM = n.altitude,
            )
        }
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
        lastClean = MockMotion(
            lat = lat, lng = lng, bearingDeg = bearing,
            speedMps = (if (paused) 0.0 else currentSpeedMps).toFloat(),
            accuracyM = 5f, altitudeM = 10.0,
        )

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

    /** 冻结推进: 由主线程在喂点间隙调用。幂等,未开始/重复调用均无副作用 */
    fun pause() {
        if (paused) return
        paused = true
    }

    /**
     * 从暂停点继续: 把时间线基准平移到现在,累计里程和当前速度都不变,
     * 所以恢复点严格等于暂停点(暂停多久都不影响)。
     * 未暂停时调用是 no-op。
     */
    fun resume(elapsedRealtimeNanos: Long) {
        if (!paused) return
        anchorNanos = elapsedRealtimeNanos
        paused = false
    }

    /**
     * 中途调速: 先把已走过的里程结算进累计值,再换速度并重置时间基准,
     * 因此切换瞬间位移为 0,只影响之后的推进(已走里程不被重算)。
     * newSpeed 若非有限值则忽略;0 是合法值(原地静止)。
     */
    fun setSpeed(newSpeed: Double, elapsedRealtimeNanos: Long) {
        if (!newSpeed.isFinite()) return
        accumulatedM += pendingDistance(elapsedRealtimeNanos)
        anchorNanos = elapsedRealtimeNanos
        currentSpeedMps = newSpeed.coerceAtLeast(0.0)
    }

    /** 已推进的总里程(米)。暂停期间保持不变 */
    private fun advanceDistance(nanos: Long): Double {
        accumulatedM += pendingDistance(nanos)
        anchorNanos = nanos
        return accumulatedM
    }

    /** 自 anchor 起本段已推进的里程,不改变任何状态 */
    private fun pendingDistance(nanos: Long): Double {
        if (paused) return 0.0
        val dtSec = ((nanos - anchorNanos).coerceAtLeast(0L)) / 1e9
        return currentSpeedMps * dtSec
    }

    // ---- 噪声状态(有界 OU;单位=度,数值照抄参考实现 hook 层 xposed/utils/CoordinateConverter) ----
    private var driftLatDeg = 0.0
    private var driftLngDeg = 0.0
    private var accuracyDrift = 0.0
    private var altitudeDrift = 0.0
    private var speedOu = 0.0
    private var lastNanos = 0L

    /** 路线所在纬度(米↔度换算用;路线都是局部小范围,取首点足够) */
    private val latRadRef: Double
        get() = Math.toRadians((pts.firstOrNull() ?: staticPoint)?.lat ?: 39.9)

    private class Noise(val dLatDeg: Double, val dLngDeg: Double, val speed: Float, val accuracy: Float, val altitude: Double)

    private fun advanceNoise(nanos: Long, moving: Boolean, bearingDeg: Float): Noise {
        val dt = if (lastNanos > 0) ((nanos - lastNanos) / 1e9).coerceIn(0.01, 5.0) else 1.0
        lastNanos = nanos

        if (!wobble) {
            return Noise(0.0, 0.0, (if (moving && !paused) currentSpeedMps else 0.0).toFloat(), 5f, 10.0)
        }

        // 抄 hook 层原样: sigma=0.000002°(≈0.2m),alpha=0.05 每秒拉回 5%,硬钳 ±0.00004°(≈±4.4m)。
        // 扰动只叠加在输出上且始终有界;里程/位置推进(advanceDistance)完全不碰噪声,不会累计跑偏。
        val sigma = 0.000002
        val alpha = 0.05
        driftLatDeg = (driftLatDeg + sigma * sqrt(dt) * rng.nextGaussian() - alpha * driftLatDeg * dt)
            .coerceIn(-0.00004, 0.00004)
        driftLngDeg = (driftLngDeg + sigma * sqrt(dt) * rng.nextGaussian() - alpha * driftLngDeg * dt)
            .coerceIn(-0.00004, 0.00004)

        // 步频横向抖动: 垂直于前进方向的小步偏移(用户可单独关掉)
        var dLatDeg = driftLatDeg
        var dLngDeg = driftLngDeg
        if (stepJitter && moving && currentSpeedMps > 0.3) {
            val lateralM = TUNING.stepLateralM * rng.nextGaussian()
            val br = Math.toRadians(bearingDeg.toDouble())
            dLatDeg += metersToDegLat(lateralM * cos(br + PI / 2))
            dLngDeg += metersToDegLng(lateralM * sin(br + PI / 2), latRadRef)
        }

        // 速度小幅波动: 真实跑者的瞬时速度不是常数(暂停时速度必须归零,漂移仍可继续)
        val movingNow = moving && !paused
        speedOu += TUNING.speedOuStep * rng.nextGaussian() - TUNING.speedOuAlpha * speedOu
        val speed = (if (movingNow) currentSpeedMps + speedOu else 0.0).coerceAtLeast(0.0)

        // Accuracy: 抄 hook 层 getJitteredAccuracy — 2.2m 基准微弱起伏,对外呈"满格强信号"
        accuracyDrift += 0.1 * rng.nextGaussian() - 0.05 * accuracyDrift
        val accuracy = (2.2 + accuracyDrift).coerceIn(1.5, 3.5).toFloat()

        // Altitude: 垂直精度比水平差,漂移幅度更大,限 [0,100]
        altitudeDrift += 0.5 * rng.nextGaussian() - 0.01 * altitudeDrift
        val altitude = (10.0 + altitudeDrift).coerceIn(0.0, 100.0)

        return Noise(dLatDeg, dLngDeg, speed.toFloat(), accuracy, altitude)
    }

    /** 波动参数集中处:步频/速度波动 */
    private object TUNING {
        const val stepLateralM = 0.15  // 每步横向白噪声 std(米),参考实现同款
        const val speedOuStep = 0.05   // 瞬时速度波动步长(m/s),稳态 std≈0.11
        const val speedOuAlpha = 0.10
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
