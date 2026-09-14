package com.learning.mockrun

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 坐标系转换。
 *
 * 系统定位/GPS 注入用 WGS-84;高德地图选点返回 GCJ-02,百度选点返回 BD-09。
 * 地图上选的点必须转回 WGS-84 才能喂给 LocationManager,否则真实位置偏移数百米。
 *
 * WGS-84↔GCJ-02 算法是公开的标准偏移公式;GCJ-02→WGS-84 用迭代求逆,精度约 1e-6 度(<1m)。
 */
object CoordinateConverter {

    data class LatLng(val lat: Double, val lng: Double)

    private const val A = 6378245.0               // 克拉索夫斯基椭球长半轴
    private const val EE = 0.00669342162296594323 // 第一偏心率平方

    /** 粗略的"中国大陆及沿海"范围判断,范围外不做 GCJ-02 偏移。 */
    private fun outOfChina(lat: Double, lng: Double): Boolean {
        return lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271
    }

    fun wgs84ToGcj02(lat: Double, lng: Double): LatLng {
        if (outOfChina(lat, lng)) return LatLng(lat, lng)
        val d = delta(lat, lng)
        return LatLng(lat + d.lat, lng + d.lng)
    }

    fun gcj02ToWgs84(lat: Double, lng: Double): LatLng {
        if (outOfChina(lat, lng)) return LatLng(lat, lng)
        // 迭代求逆:解 f(wgs) = gcj - (wgs + delta(wgs)) = 0
        var wgs = LatLng(lat, lng)
        repeat(3) {
            val gcj = wgs84ToGcj02(wgs.lat, wgs.lng)
            wgs = LatLng(wgs.lat - (gcj.lat - lat), wgs.lng - (gcj.lng - lng))
        }
        return wgs
    }

    fun gcj02ToBd09(lat: Double, lng: Double): LatLng {
        val x = lng
        val y = lat
        val z = sqrt(x * x + y * y) + 0.00002 * sin(y * PI * 3000.0 / 180.0)
        val theta = atan2(y, x) + 0.000003 * cos(x * PI * 3000.0 / 180.0)
        return LatLng(z * sin(theta) + 0.006, z * cos(theta) + 0.0065)
    }

    fun bd09ToGcj02(lat: Double, lng: Double): LatLng {
        val x = lng - 0.0065
        val y = lat - 0.006
        val z = sqrt(x * x + y * y) - 0.00002 * sin(y * PI * 3000.0 / 180.0)
        val theta = atan2(y, x) - 0.000003 * cos(x * PI * 3000.0 / 180.0)
        return LatLng(z * sin(theta), z * cos(theta))
    }

    /** 百度选点 → 注入坐标 */
    fun bd09ToWgs84(lat: Double, lng: Double): LatLng {
        val gcj = bd09ToGcj02(lat, lng)
        return gcj02ToWgs84(gcj.lat, gcj.lng)
    }

    /** 高德选点 → 注入坐标 */
    fun gcj02ToWgs84(point: LatLng): LatLng = gcj02ToWgs84(point.lat, point.lng)

    private fun delta(lat: Double, lng: Double): LatLng {
        var dLat = transformLat(lng - 105.0, lat - 35.0)
        var dLng = transformLon(lng - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        dLng = (dLng * 180.0) / (A / sqrtMagic * cos(radLat) * PI)
        return LatLng(dLat, dLng)
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLon(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }
}
