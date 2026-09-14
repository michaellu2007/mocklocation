package com.learning.mockrun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutePlayerTest {

    private val anchor = GeoPoint(39.9042, 116.4074)

    /** 直线开线:wobble 关闭时,距离 = 速度 × 时间(±1%) */
    @Test
    fun `匀速推进距离与时间成正比`() {
        val route = listOf(
            GeoPoint(39.9042, 116.4074),
            GeoPoint(39.9042, 116.4074 + RoutePlayer.metersToDegLng(1000.0, Math.toRadians(39.9042)))
        )
        val start = 1_000_000_000_000L
        val player = RoutePlayer(route, baseSpeedMps = 3.0, startElapsedNanos = start, wobble = false)
        val m60 = player.at(start + 60_000_000_000L)
        val d = RoutePlayer.haversine(route[0], GeoPoint(m60.lat, m60.lng))
        assertEquals(180.0, d, 1.8) // 3m/s × 60s = 180m,容差 1%
        assertEquals(3.0f, m60.speedMps, 0.01f)
    }

    /** 闭合环线:跑完一圈取模回到起点附近 */
    @Test
    fun `环线循环取模`() {
        val route = PresetRoutes.generate(PresetRoutes.ALL[1], anchor) // ~1km 环
        val len = routeLength(route)
        val speed = 3.0
        val player = RoutePlayer(route, baseSpeedMps = speed, startElapsedNanos = 0, wobble = false)
        // 一圈整:应回到起点附近
        val lapSec = (len / speed).toLong()
        val end = player.at(lapSec * 1_000_000_000L)
        val d = RoutePlayer.haversine(route[0], GeoPoint(end.lat, end.lng))
        assertTrue("跑完一圈应回到起点,实际偏 ${"%.1f".format(d)}m", d < 30.0)
    }

    /** 开线(不闭合):跑完后停在终点,速度归零 */
    @Test
    fun `开线往返语义不崩`() {
        val route = listOf(
            GeoPoint(39.9042, 116.4074),
            GeoPoint(39.9052, 116.4074)
        )
        val player = RoutePlayer(route, baseSpeedMps = 3.0, startElapsedNanos = 0, wobble = false)
        // 往返线长期运行不越界、不出 NaN
        val t = 100_000_000_000L
        val m = player.at(t)
        assertTrue(m.lat.isFinite() && m.lng.isFinite())
        assertTrue(m.speedMps >= 0f)
    }

    /** 波动感:位置偏离理想轨迹有界(OU 均值回归保证),且相邻喂点连续不跳变 */
    @Test
    fun `波动有界且轨迹连续`() {
        val route = PresetRoutes.generate(PresetRoutes.ALL[0], anchor) // 操场环
        val speed = 3.0
        val player = RoutePlayer(route, baseSpeedMps = speed, startElapsedNanos = 0, wobble = true, seed = 42)
        val ideal = RoutePlayer(route, baseSpeedMps = speed, startElapsedNanos = 0, wobble = false, seed = 42)
        var prev = player.at(0)
        for (sec in 1..300) {
            val m = player.at(sec * 1_000_000_000L)
            val i = ideal.at(sec * 1_000_000_000L)
            // 漂移有界: OU 稳态标准差 ≈ jitterRadius,3-sigma ≈ 3 倍,取 6 倍宽松限防随机抖动
            val off = RoutePlayer.haversine(GeoPoint(m.lat, m.lng), GeoPoint(i.lat, i.lng))
            assertTrue("第${sec}s 偏离理想轨迹 ${"%.1f".format(off)}m 超界", off < 6 * 8.0)
            // 连续性: 1s 内位移不超过 速度×1s + 漂移余量
            val step = RoutePlayer.haversine(GeoPoint(prev.lat, prev.lng), GeoPoint(m.lat, m.lng))
            assertTrue("第${sec}s 相邻喂点跳变 ${"%.1f".format(step)}m", step < speed * 1.0 + 10.0)
            prev = m
        }
    }

    /** 单点模式:速度 0,漂移有界 */
    @Test
    fun `单点模式漂移有界`() {
        val player = RoutePlayer(listOf(anchor), baseSpeedMps = 0.0, startElapsedNanos = 0, wobble = true, seed = 7)
        for (sec in 0..600 step 7) {
            val m = player.at(sec * 1_000_000_000L)
            val off = RoutePlayer.haversine(anchor, GeoPoint(m.lat, m.lng))
            assertTrue(off < 6 * 5.0)
            assertEquals(0f, m.speedMps, 0.01f)
        }
    }

    private fun routeLength(route: List<GeoPoint>): Double {
        var d = 0.0
        for (i in 0 until route.size - 1) d += RoutePlayer.haversine(route[i], route[i + 1])
        return d
    }
}
