package com.learning.mockrun

import android.content.Context
import android.util.AttributeSet
import android.widget.ListView

/**
 * 有高度上限的 ListView(搜索结果列表用)。
 *
 * 为什么需要它:原生 ListView 配 layout_height="wrap_content" 时,父级给的是
 * AT_MOST(可用空间),ListView 会一路量到「内容全高或可用空间」——结果条数一多
 * 就把整张卡撑到屏幕一大截,盖住地图和底部面板;而且此时它自身高度 == 内容高度,
 * 它自己反而滚不动,表现就是"结果列表不能滚动"。
 *
 * 这里把可用高度再收一刀(默认 240dp,XML 可用 android:maxHeight 覆盖),
 * 用 AT_MOST 重新量:量出来 = min(内容高, 上限)。超出的部分就在列表内部滚动,
 * 卡片本身不再变高、不会盖住别的东西,也不会把整页拖成可滚动的
 * (整页压根没有 ScrollView,不存在"把整个页面都滚动"的情况)。
 */
class MaxHeightListView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ListView(context, attrs) {

    private val maxHeightPx: Int = run {
        val ta = context.obtainStyledAttributes(attrs, intArrayOf(android.R.attr.maxHeight))
        val v = ta.getDimensionPixelSize(
            0,
            (DEFAULT_MAX_HEIGHT_DP * context.resources.displayMetrics.density).toInt(),
        )
        ta.recycle()
        v
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        val size = MeasureSpec.getSize(heightMeasureSpec)
        // 父级没给上限(UNSPECIFIED)时,直接用我们自己的上限兜底
        val limit = if (mode == MeasureSpec.UNSPECIFIED) maxHeightPx else minOf(size, maxHeightPx)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST))
    }

    private companion object {
        const val DEFAULT_MAX_HEIGHT_DP = 240f
    }
}
