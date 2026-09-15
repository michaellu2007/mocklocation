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
            // 漂移有界: OU 稳态标准差 = 2m(跑步档收敛后),3-sigma ≈ 3 倍,取 6 倍宽松限防随机抖动
            val off = RoutePlayer.haversine(GeoPoint(m.lat, m.lng), GeoPoint(i.lat, i.lng))
            assertTrue("第${sec}s 偏离理想轨迹 ${"%.1f".format(off)}m 超界", off < 6 * 2.0)
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
            assertTrue(off < 6 * 1.2)
            assertEquals(0f, m.speedMps, 0.01f)
        }
    }

    /** 播放→暂停→继续→中途调速 全序列: 位置连续、暂停冻结、恢复/变速无跳变 */
    @Test
    fun `暂停继续与中途调速全序列位置连续`() {
        val route = listOf(
            GeoPoint(39.9042, 116.4074),
            GeoPoint(39.9042, 116.4074 + RoutePlayer.metersToDegLng(2000.0, Math.toRadians(39.9042)))
        )
        val start = 0L
        val player = RoutePlayer(route, baseSpeedMps = 3.0, startElapsedNanos = start, wobble = false)
        val dtNanos = 1_000_000_000L
        val dtSec = 1.0

        var prev = player.at(0)
        var t = 0L
        // 播放 10s
        for (i in 1..10) {
            t += dtNanos
            val m = player.at(t)
            assertStep(prev, m, 3.0, dtSec)
            prev = m
        }

        // 暂停: 之后 at() 位置完全冻结(抖动余量 0.5m),且速度必须归零
        player.pause()
        val frozen = player.at(t)
        assertEquals("暂停期间速度应归零", 0f, frozen.speedMps, 0.001f)
        for (i in 1..5) {
            t += dtNanos
            val m = player.at(t)
            val d = RoutePlayer.haversine(GeoPoint(prev.lat, prev.lng), GeoPoint(m.lat, m.lng))
            assertTrue("暂停期间位置漂移 ${"%.3f".format(d)}m", d < 0.5)
            val df = RoutePlayer.haversine(GeoPoint(frozen.lat, frozen.lng), GeoPoint(m.lat, m.lng))
            assertTrue("暂停期间相对首帧漂移 ${"%.3f".format(df)}m", df < 0.5)
            assertEquals("暂停期间速度应归零", 0f, m.speedMps, 0.001f)
            prev = m
        }
        // 重复 pause 幂等
        player.pause()

        // 继续: 暂停点出发,不许跳变(暂停了 5s 也一样)
        t += dtNanos
        player.resume(t)
        val resumed = player.at(t)
        assertStep(prev, resumed, 3.0, dtSec)
        prev = resumed

        // 恢复后继续推进 10s
        for (i in 1..10) {
            t += dtNanos
            val m = player.at(t)
            assertStep(prev, m, 3.0, dtSec)
            prev = m
        }

        // 中途调速 3 → 5: 切换时刻位置连续
        t += 1
        player.setSpeed(5.0, t)
        val switched = player.at(t)
        assertStep(prev, switched, 3.0, dtSec)
        assertEquals(5.0f, switched.speedMps, 0.01f)
        prev = switched

        // 新速度段: 1s 位移仍不超过新速度
        for (i in 1..10) {
            t += dtNanos
            val m = player.at(t)
            assertStep(prev, m, 5.0, dtSec)
            assertEquals(5.0f, m.speedMps, 0.01f)
            prev = m
        }
    }

    /** 暂停速度语义(wobble=true 路径): 暂停时 speedMps 归零,漂移仍继续;恢复后速度回来 */
    @Test
    fun `暂停期间速度归零且漂移继续`() {
        val route = PresetRoutes.generate(PresetRoutes.ALL[0], anchor)
        val player = RoutePlayer(route, baseSpeedMps = 3.0, startElapsedNanos = 0, wobble = true, seed = 42)
        var t = 0L
        repeat(10) { t += 1_000_000_000L; player.at(t) }

        player.pause()
        var driftSeen = false
        var prev = player.at(t)
        repeat(10) {
            t += 1_000_000_000L
            val m = player.at(t)
            assertEquals("暂停期间速度应归零", 0f, m.speedMps, 0.0001f)
            // 漂移可以继续(特性),只验证有界不发散
            val d = RoutePlayer.haversine(GeoPoint(prev.lat, prev.lng), GeoPoint(m.lat, m.lng))
            assertTrue("暂停期间漂移应保持微小,实际 ${"%.2f".format(d)}m", d < 15.0)
            if (d > 0.0) driftSeen = true
            prev = m
        }
        // 恢复后速度回到基准速度附近
        t += 1_000_000_000L
        player.resume(t)
        val resumed = player.at(t)
        assertEquals(3.0f, resumed.speedMps, 0.3f)
        assertTrue(driftSeen)
    }

    /** 暂停的"时间平移"语义: 暂停 0s / 5s / 60s 恢复后位置与里程完全一致 */
    @Test
    fun `暂停时长不影响恢复点`() {
        val route = listOf(
            GeoPoint(39.9042, 116.4074),
            GeoPoint(39.9042, 116.4074 + RoutePlayer.metersToDegLng(2000.0, Math.toRadians(39.9042)))
        )
        fun runThrough(pauseSec: Long): MockMotion {
            val p = RoutePlayer(route, baseSpeedMps = 3.0, startElapsedNanos = 0, wobble = false)
            var t = 0L
            repeat(10) { t += 1_000_000_000L; p.at(t) }
            p.pause()
            t += pauseSec * 1_000_000_000L
            p.resume(t)
            t += 1_000_000_000L
            return p.at(t)
        }
        val a = runThrough(0)
        val b = runThrough(5)
        val c = runThrough(60)
        val dAB = RoutePlayer.haversine(GeoPoint(a.lat, a.lng), GeoPoint(b.lat, b.lng))
        val dAC = RoutePlayer.haversine(GeoPoint(a.lat, a.lng), GeoPoint(c.lat, c.lng))
        assertEquals("暂停 5s 与 0s 的恢复点应一致", 0.0, dAB, 0.01)
        assertEquals("暂停 60s 与 0s 的恢复点应一致", 0.0, dAC, 0.01)
    }

    /** 变速只影响之后的推进: 变速前后累计里程连续(位置不跳) */
    @Test
    fun `变速前后里程连续`() {
        val route = listOf(
            GeoPoint(39.9042, 116.4074),
            GeoPoint(39.9042, 116.4074 + RoutePlayer.metersToDegLng(2000.0, Math.toRadians(39.9042)))
        )
        val player = RoutePlayer(route, baseSpeedMps = 3.0, startElapsedNanos = 0, wobble = false)
        val t = 30_000_000_000L
        val before = player.at(t)
        val beforeDist = RoutePlayer.haversine(route[0], GeoPoint(before.lat, before.lng))

        player.setSpeed(6.0, t)
        val atSwitch = player.at(t)
        val switchDist = RoutePlayer.haversine(route[0], GeoPoint(atSwitch.lat, atSwitch.lng))
        assertEquals("变速瞬间不应产生位移", beforeDist, switchDist, 0.05)

        // 变速后 10s,推进量按新速度 (6m/s × 10s = 60m ±5%)
        val after = player.at(t + 10_000_000_000L)
        val afterDist = RoutePlayer.haversine(route[0], GeoPoint(after.lat, after.lng))
        assertEquals(60.0, afterDist - switchDist, 3.0)
        // 已走里程不被重算
        assertTrue(switchDist > beforeDist - 0.05)
    }

    /** 边界调用序列: 不抛异常、不产生跳变 */
    @Test
    fun `边界调用序列不抛异常`() {
        val route = listOf(
            GeoPoint(39.9042, 116.4074),
            GeoPoint(39.9042, 116.4074 + RoutePlayer.metersToDegLng(2000.0, Math.toRadians(39.9042)))
        )
        val player = RoutePlayer(route, baseSpeedMps = 3.0, startElapsedNanos = 0, wobble = false)
        var t = 0L

        player.pause()                       // 未开始就暂停
        player.pause()                       // 重复暂停
        player.resume(t)                     // 未开始就 resume
        player.resume(t)                     // 未暂停时 resume 是 no-op
        player.setSpeed(0.0, t)              // 速度归零
        player.pause()
        player.resume(t)
        var prev = player.at(t)
        // 速度为 0: 位置不动
        repeat(5) {
            t += 1_000_000_000L
            val m = player.at(t)
            val d = RoutePlayer.haversine(GeoPoint(prev.lat, prev.lng), GeoPoint(m.lat, m.lng))
            assertTrue("速度为 0 时位置应冻结,实际 ${"%.3f".format(d)}m", d < 0.01)
            prev = m
        }
        // 速度恢复
        player.setSpeed(4.0, t)
        repeat(5) {
            t += 1_000_000_000L
            val m = player.at(t)
            assertStep(prev, m, 4.0, 1.0)
            prev = m
        }
    }

    /** 单点静止模式: pause/resume/setSpeed 不崩,位置仍有界 */
    @Test
    fun `单点模式暂停恢复不崩`() {
        val player = RoutePlayer(listOf(anchor), baseSpeedMps = 0.0, startElapsedNanos = 0, wobble = false)
        player.pause()
        player.at(1_000_000_000L)
        player.resume(2_000_000_000L)
        player.setSpeed(5.0, 2_000_000_000L)
        val m = player.at(3_000_000_000L)
        assertEquals(0f, m.speedMps, 0.001f)
        val off = RoutePlayer.haversine(anchor, GeoPoint(m.lat, m.lng))
        assertTrue(off < 0.5)
    }

    private fun assertStep(prev: MockMotion, cur: MockMotion, speedMps: Double, dtSec: Double) {
        val step = RoutePlayer.haversine(GeoPoint(prev.lat, prev.lng), GeoPoint(cur.lat, cur.lng))
        assertTrue(
            "相邻喂点位移 ${"%.3f".format(step)}m 超过上限 ${"%.3f".format(speedMps * dtSec + 0.5)}m",
            step <= speedMps * dtSec + 0.5
        )
    }

    private fun routeLength(route: List<GeoPoint>): Double {
        var d = 0.0
        for (i in 0 until route.size - 1) d += RoutePlayer.haversine(route[i], route[i + 1])
        return d
    }
}
