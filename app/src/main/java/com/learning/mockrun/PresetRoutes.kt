package com.learning.mockrun

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 预设校园跑路线生成器:以锚点为中心按周长生成环线(WGS-84)。
 * 形状是本质,锚点由用户在地图上长按指定——这样任何校园都能用。
 */
object PresetRoutes {

    enum class Shape { TRACK_400, RING_1K, CAMPUS_2K, BIG_3K }

    data class Preset(val shape: Shape, val label: String, val lengthM: Int)

    val ALL = listOf(
        Preset(Shape.TRACK_400, "标准操场环 400m", 400),
        Preset(Shape.RING_1K, "小环线 ~1km", 1000),
        Preset(Shape.CAMPUS_2K, "校园环线 ~2km", 2000),
        Preset(Shape.BIG_3K, "大环线 ~3.5km", 3500),
    )

    /** 生成路线点(首点≈尾点,RoutePlayer 按闭合环线处理) */
    fun generate(preset: Preset, anchor: GeoPoint): List<GeoPoint> {
        return when (preset.shape) {
            Shape.TRACK_400 -> track(anchor)
            else -> ring(anchor, preset.lengthM.toDouble())
        }
    }

    /**
     * 标准 400m 跑道:两条 84.39m 直道 + 两个半径 36.5m 半圆,
     * 周长 2×84.39 + 2π×36.5 ≈ 398m。逆时针连续绕一圈。
     */
    private fun track(anchor: GeoPoint, straightM: Double = 84.39, radiusM: Double = 36.5): List<GeoPoint> {
        val latRad = Math.toRadians(anchor.lat)
        val mPerDegLat = 1.0 / RoutePlayer.metersToDegLat(1.0)
        val mPerDegLng = 1.0 / RoutePlayer.metersToDegLng(1.0, latRad)
        fun pt(xM: Double, yM: Double) = GeoPoint(
            lat = anchor.lat + yM / mPerDegLat,
            lng = anchor.lng + xM / mPerDegLng,
        )

        val pts = ArrayList<GeoPoint>()
        // 局部坐标(米): x 向东,y 向北
        val step = 8.0
        // 北直道: (-L/2, R) → (L/2, R)
        var x = -straightM / 2
        while (x < straightM / 2) { pts += pt(x, radiusM); x += step }
        pts += pt(straightM / 2, radiusM)
        // 东半圆: 圆心 (L/2,0),角度 +90° → -90°(经过 0° 正东)
        var a = PI / 2
        val arcStep = step / radiusM
        while (a > -PI / 2) { a = maxOf(a - arcStep, -PI / 2); pts += pt(straightM / 2 + radiusM * cos(a), radiusM * sin(a)) }
        // 南直道: (L/2, -R) → (-L/2, -R)
        x = straightM / 2
        while (x > -straightM / 2) { pts += pt(x, -radiusM); x -= step }
        pts += pt(-straightM / 2, -radiusM)
        // 西半圆: 圆心 (-L/2,0),角度 -90° → -270°(经过 180° 正西)
        a = -PI / 2
        while (a > -3 * PI / 2) { a = maxOf(a - arcStep, -3 * PI / 2); pts += pt(-straightM / 2 + radiusM * cos(a), radiusM * sin(a)) }
        // 闭合回北直道起点
        pts += pts.first()
        return pts
    }

    /** 圆环:给定周长,~15m 采样一圈 */
    private fun ring(anchor: GeoPoint, circumferenceM: Double): List<GeoPoint> {
        val r = circumferenceM / (2 * PI)
        val latRad = Math.toRadians(anchor.lat)
        val mPerDegLat = 1.0 / RoutePlayer.metersToDegLat(1.0)
        val mPerDegLng = 1.0 / RoutePlayer.metersToDegLng(1.0, latRad)
        val step = 15.0
        val n = (circumferenceM / step).toInt().coerceAtLeast(24)
        val pts = ArrayList<GeoPoint>(n + 1)
        for (i in 0..n) {
            val a = 2 * PI * i / n
            pts += local(r * cos(a), r * sin(a), anchor, mPerDegLat, mPerDegLng)
        }
        return pts
    }

    /** 局部米坐标 → WGS-84 */
    private fun local(xM: Double, yM: Double, anchor: GeoPoint, mPerDegLat: Double, mPerDegLng: Double): GeoPoint {
        return GeoPoint(
            lat = anchor.lat + yM / mPerDegLat,
            lng = anchor.lng + xM / mPerDegLng,
        )
    }
}
