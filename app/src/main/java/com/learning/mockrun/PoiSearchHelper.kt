package com.learning.mockrun

import android.content.Context
import com.amap.api.services.core.AMapException
import com.amap.api.services.core.PoiItem
import com.amap.api.services.core.ServiceSettings
import com.amap.api.services.poisearch.PoiResult
import com.amap.api.services.poisearch.PoiSearch

/**
 * 高德 POI 搜索的薄封装:输入关键字 → 回调结果列表(_gcj-02 坐标在 PoiItem 里_)。
 * 调用前先 ensurePrivacy(搜索 SDK 的合规接口与地图 SDK 分开)。
 */
object PoiSearchHelper {

    fun ensurePrivacy(context: Context) {
        ServiceSettings.updatePrivacyShow(context, true, true)
        ServiceSettings.updatePrivacyAgree(context, true)
    }

    fun search(context: Context, keyword: String, onResult: (List<PoiItem>) -> Unit) {
        val query = PoiSearch.Query(keyword.trim(), "", "")
        val search = PoiSearch(context, query)
        search.setOnPoiSearchListener(object : PoiSearch.OnPoiSearchListener {
            override fun onPoiSearched(result: PoiResult?, rCode: Int) {
                val pois = if (rCode == AMapException.CODE_AMAP_SUCCESS) result?.pois.orEmpty() else emptyList()
                onResult(pois)
            }

            override fun onPoiItemSearched(item: PoiItem?, rCode: Int) {}
        })
        search.searchPOIAsyn()
    }
}
