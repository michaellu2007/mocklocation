package com.learning.mockrun

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import kotlin.math.max

/**
 * 毛玻璃面板(参考 LocationSpoofer ui/liquid 配方的 View 版):
 * 自绘底层 = 抓 TextureView 地图当前帧 → 缩小重放大平滑模糊 → 蒙白 58% → 顶部高光渐变,
 * 子控件照常叠画在上面。在 XML 里当普通 LinearLayout 用(orientation/padding 等 attr 照常生效)。
 * ponytail: Paint.setRenderEffect 本 SDK 平台没有;View 级 setRenderEffect(API 31+)会连
 * 子控件一起糊,故用缩放模糊替代——反正蒙白 58% 之后细节不可见。
 */
class GlassPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private var capture: (() -> Bitmap?)? = null
    private var frame: Bitmap? = null
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val overlayPaint = Paint()
    private val clipPath = Path()

    /** 绑定取帧源:从 TextureMapView 内部递归找到 TextureView */
    fun bind(mapRoot: View?) {
        capture = findTextureView(mapRoot)?.let { tv ->
            { tv.getBitmap(CAPTURE_W, CAPTURE_H) }
        }
    }

    /** 地图动了/面板重新可见时调用:抓新帧并重画 */
    fun refresh() {
        val bmp = capture?.invoke() ?: return
        // 先缩到 1/4,重放大即平滑模糊(等效磨砂,省掉 RenderEffect)
        frame = Bitmap.createScaledBitmap(
            bmp,
            (bmp.width / BLUR_SHRINK).coerceAtLeast(1),
            (bmp.height / BLUR_SHRINK).coerceAtLeast(1),
            true,
        )
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val density = resources.displayMetrics.density
        val r = TOP_RADIUS_DP * density

        canvas.save()
        clipPath.reset()
        clipPath.addRoundRect(
            RectF(0f, 0f, w, h),
            floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f),
            Path.Direction.CW,
        )
        canvas.clipPath(clipPath)

        frame?.let { bmp ->
            val s = max(w / bmp.width, h / bmp.height)
            canvas.translate((w - bmp.width * s) / 2f, (h - bmp.height * s) / 2f)
            canvas.scale(s, s)
            canvas.drawBitmap(bmp, 0f, 0f, bitmapPaint)
        }

        // 表面蒙白(原版配方 Color.White.copy(alpha=0.58f))
        overlayPaint.color = SURFACE_TINT
        canvas.drawRect(0f, 0f, w, h, overlayPaint)

        // 顶部高光(玻璃上边缘反光)
        overlayPaint.shader = LinearGradient(
            0f, 0f, 0f, HIGHLIGHT_H_DP * density,
            0x66FFFFFF, 0x00FFFFFF,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, HIGHLIGHT_H_DP * density, overlayPaint)
        overlayPaint.shader = null

        canvas.restore()
    }

    private fun findTextureView(v: View?): TextureView? = when {
        v is TextureView -> v
        v is ViewGroup -> {
            for (i in 0 until v.childCount) {
                findTextureView(v.getChildAt(i))?.let { return it }
            }
            null
        }
        else -> null
    }

    private companion object {
        // 小尺寸抓帧:模糊后细节无关紧要,小图抓得快、模糊也快
        const val CAPTURE_W = 240
        const val CAPTURE_H = 520
        const val BLUR_SHRINK = 4
        const val TOP_RADIUS_DP = 24f
        const val HIGHLIGHT_H_DP = 40f
        const val SURFACE_TINT = 0x94FFFFFF.toInt() // 白 58%(超出 Int 范围,需显式 toInt)
    }
}
