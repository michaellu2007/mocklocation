package com.learning.mockrun

/**
 * 地图选点页 → MainActivity 的返回约定:
 * 坐标一律 WGS-84(各引擎在自己页面内完成转换),MainActivity 直接填输入框。
 */
object PickerContract {
    const val EXTRA_LAT = "picked_lat"
    const val EXTRA_LNG = "picked_lng"
}
