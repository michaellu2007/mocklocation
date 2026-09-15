package com.learning.mockrun

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RouteStoreTest {

    private val route = RouteStore.SavedRoute(
        "路线 09-15 14:24",
        true,
        listOf(GeoPoint(31.3172, 121.3887), GeoPoint(31.3170, 121.3889), GeoPoint(31.3168, 121.3890))
    )

    /** 回归测试:encodeAll 曾把对象编码成字符串塞进数组,导致 list() 永远读回空 */
    @Test
    fun `encodeAll 产出对象数组且能完整读回`() {
        val text = RouteStore.encodeAll(listOf(route))
        val arr = JSONArray(text)
        // 关键断言:数组元素必须是 JSONObject 而不是字符串
        assertEquals(
            "数组元素应为对象",
            org.json.JSONObject::class.java,
            arr.get(0)::class.java
        )
        val back = RouteStore.parseRoute(arr.getJSONObject(0).toString())
        assertNotNull(back)
        assertEquals(route.name, back!!.name)
        assertEquals(route.loop, back.loop)
        assertEquals(route.points.size, back.points.size)
        assertEquals(route.points[0].lat, back.points[0].lat, 1e-9)
    }

    /** 单条 encode → parseRoute 往返 */
    @Test
    fun `单条往返一致`() {
        val back = RouteStore.parseRoute(RouteStore.encode(route))
        assertNotNull(back)
        assertEquals(route.name, back!!.name)
        assertEquals(route.points.size, back.points.size)
    }

    /** 损坏容错:非法文本返回 null 而不是抛异常 */
    @Test
    fun `损坏输入返回null`() {
        assertNull(RouteStore.parseRoute("不是JSON"))
        assertNull(RouteStore.parseRoute("{\"name\":\"只有名字\"}"))
        assertNull(RouteStore.parseRoute(""))
    }
}
