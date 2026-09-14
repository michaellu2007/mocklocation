package com.learning.mockrun

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordinateConverterTest {

    // 北京、上海、广州、成都各取一点,验证转换性质而非背书的"真值"
    private val chinaPoints = listOf(
        39.9042 to 116.4074,
        31.2304 to 121.4737,
        23.1291 to 113.2644,
        30.5728 to 104.0668
    )

    @Test
    fun `wgs84-gcj02 往返误差在1米级`() {
        for ((lat, lng) in chinaPoints) {
            val gcj = CoordinateConverter.wgs84ToGcj02(lat, lng)
            val wgs = CoordinateConverter.gcj02ToWgs84(gcj.lat, gcj.lng)
            assertEquals(lat, wgs.lat, 1e-4)
            assertEquals(lng, wgs.lng, 1e-4)
        }
    }

    @Test
    fun `gcj02-bd09 往返误差可忽略`() {
        for ((lat, lng) in chinaPoints) {
            val bd = CoordinateConverter.gcj02ToBd09(lat, lng)
            val gcj = CoordinateConverter.bd09ToGcj02(bd.lat, bd.lng)
            assertEquals(lat, gcj.lat, 1e-6)
            assertEquals(lng, gcj.lng, 1e-6)
        }
    }

    @Test
    fun `wgs-gcj-bd-wgs 全链路往返一致`() {
        // 高德/百度选点后注入的真实链路逆过程:wgs → gcj → bd → (注入前)bd → wgs
        for ((lat, lng) in chinaPoints) {
            val gcj = CoordinateConverter.wgs84ToGcj02(lat, lng)
            val bd = CoordinateConverter.gcj02ToBd09(gcj.lat, gcj.lng)
            val wgs = CoordinateConverter.bd09ToWgs84(bd.lat, bd.lng)
            assertEquals(lat, wgs.lat, 1e-4)
            assertEquals(lng, wgs.lng, 1e-4)
        }
    }

    @Test
    fun `gcj02 偏移量级在一百至七百米`() {
        // 中国境内偏移应在 ~0.001 ~ 0.01 度之间;若转换没生效或算错,这个量级会露馅
        for ((lat, lng) in chinaPoints) {
            val gcj = CoordinateConverter.wgs84ToGcj02(lat, lng)
            assertTrue(abs(gcj.lat - lat) in 0.001..0.02)
            assertTrue(abs(gcj.lng - lng) in 0.001..0.02)
        }
    }

    @Test
    fun `中国大陆范围外不做偏移`() {
        val tokyo = CoordinateConverter.wgs84ToGcj02(35.6812, 139.7671)
        assertEquals(35.6812, tokyo.lat, 0.0)
        assertEquals(139.7671, tokyo.lng, 0.0)
    }
}
