package com.learning.mockrun

/**
 * 地图选点页 / 路线编辑页 / 我的路线 → MainActivity 的返回约定:
 * 单点用 EXTRA_LAT/EXTRA_LNG(WGS-84);整条路线用 EXTRA_ROUTE_PTS(WGS-84,
 * lat,lng 交错)+ EXTRA_ROUTE_NAME + EXTRA_ROUTE_LOOP。
 */
object PickerContract {
    const val EXTRA_LAT = "picked_lat"
    const val EXTRA_LNG = "picked_lng"
    const val EXTRA_ROUTE_PTS = "route_pts"
    const val EXTRA_ROUTE_NAME = "route_name"
    const val EXTRA_ROUTE_LOOP = "route_loop"
}
