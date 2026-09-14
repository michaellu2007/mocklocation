package com.learning.mockrun

import org.junit.Assert.assertTrue
import org.junit.Test

class PresetRoutesTest {

    private val anchor = GeoPoint(39.9042, 116.4074)

    private fun length(route: List<GeoPoint>): Double {
        var d = 0.0
        for (i in 0 until route.size - 1) d += RoutePlayer.haversine(route[i], route[i + 1])
        return d
    }

    @Test
    fun `各预设周长在标称值±5%内`() {
        for (preset in PresetRoutes.ALL) {
            val route = PresetRoutes.generate(preset, anchor)
            val len = length(route)
            val expect = if (preset.shape == PresetRoutes.Shape.TRACK_400) 398.0 else preset.lengthM.toDouble()
            assertTrue(
                "${preset.label} 实际 ${"%.0f".format(len)}m 偏离标称 $expect",
                len in expect * 0.95..expect * 1.05
            )
        }
    }

    @Test
    fun `环线闭合且采样密度均匀`() {
        for (preset in PresetRoutes.ALL) {
            val route = PresetRoutes.generate(preset, anchor)
            assertTrue(route.size >= 10)
            // 闭合
            assertTrue(
                "${preset.label} 未闭合",
                RoutePlayer.haversine(route.first(), route.last()) <= 5.0
            )
            // 相邻点间距 ≤ 20m(采样密度,保证插值平滑)
            for (i in 0 until route.size - 1) {
                val d = RoutePlayer.haversine(route[i], route[i + 1])
                assertTrue("${preset.label} 第${i}段间距 ${"%.1f".format(d)}m 过大", d <= 20.0)
            }
            // 所有点都在锚点合理范围内(最大环 3.5km → 半径 ~560m)
            val maxR = RoutePlayer.haversine(anchor, route.maxByOrNull { RoutePlayer.haversine(anchor, it) }!!)
            assertTrue("${preset.label} 离锚点过远 ${"%.0f".format(maxR)}m", maxR < preset.lengthM / (2 * Math.PI) * 1.2 + 5)
        }
    }
}
