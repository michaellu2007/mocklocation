package com.learning.mockrun

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.TextureMapView
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.PolylineOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 自定义路线绘制:点按地图依次加途经点(内部 WGS-84,显示 GCJ-02)。
 * 逐点体验对齐参考实现:每个途经点立一个序号标记,起点绿"起"、终点红"终",
 * 另有「+ 图中心」按钮(平移对准后把图中心作为下一个点,适合精标)。
 * 保存并使用 = 存入「我的路线」+ 返回主界面立即开跑。
 */
class RouteEditorActivity : AppCompatActivity() {

    private lateinit var mapView: TextureMapView
    private lateinit var infoText: TextView
    private lateinit var loopCheck: CheckBox
    private val pts = ArrayList<GeoPoint>()
    private val markers = ArrayList<Marker>()
    private var polyline: Polyline? = null
    private var aMap: AMap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        MapsInitializer.updatePrivacyShow(this, true, true)
        MapsInitializer.updatePrivacyAgree(this, true)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_route_editor)

        mapView = findViewById(R.id.editor_map)
        infoText = findViewById(R.id.editor_info)
        loopCheck = findViewById(R.id.editor_loop)
        mapView.onCreate(savedInstanceState)

        val map = mapView.map
        aMap = map
        map.uiSettings.isZoomControlsEnabled = false
        map.uiSettings.isCompassEnabled = false
        map.moveCamera(
            CameraUpdateFactory.newLatLngZoom(
                LatLng(MockLocationService.DEFAULT_LAT, MockLocationService.DEFAULT_LON),
                16f
            )
        )
        // 点图加点(精细) + 准星坐标读数随平移刷新
        map.setOnMapClickListener { latLng ->
            val wgs = CoordinateConverter.gcj02ToWgs84(latLng.latitude, latLng.longitude)
            addPoint(GeoPoint(wgs.lat, wgs.lng))
        }
        map.setOnCameraChangeListener(object : AMap.OnCameraChangeListener {
            override fun onCameraChange(pos: com.amap.api.maps.model.CameraPosition?) {
                updateCrosshairReadout(pos)
            }

            override fun onCameraChangeFinish(pos: com.amap.api.maps.model.CameraPosition?) {
                updateCrosshairReadout(pos)
            }
        })

        // 大号加点按钮:收下屏幕中心(准星处)的坐标 —— 描点画路线的主交互
        findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(
            R.id.fab_add_point
        ).setOnClickListener {
            val target = aMap?.cameraPosition?.target
            if (target != null) {
                val wgs = CoordinateConverter.gcj02ToWgs84(target.latitude, target.longitude)
                addPoint(GeoPoint(wgs.lat, wgs.lng))
            }
        }

        findViewById<Button>(R.id.btn_undo).setOnClickListener {
            if (pts.isNotEmpty()) {
                pts.removeAt(pts.size - 1)
                updatePreview()
            }
        }
        findViewById<Button>(R.id.btn_clear).setOnClickListener {
            pts.clear()
            updatePreview()
        }
        findViewById<Button>(R.id.editor_search_btn).setOnClickListener {
            val kw = findViewById<android.widget.EditText>(R.id.editor_search_input).text.toString()
            if (kw.isBlank()) {
                Toast.makeText(this, R.string.search_empty_kw, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            PoiSearchHelper.ensurePrivacy(this)
            PoiSearchHelper.search(this, kw) { pois ->
                runOnUiThread {
                    if (pois.isEmpty()) {
                        Toast.makeText(this, R.string.search_no_result, Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
                    }
                    val titles = pois.map { "${it.title} · ${it.snippet}" }
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle(getString(R.string.search_result_title, pois.size))
                        .setItems(titles.toTypedArray()) { _, which ->
                            val ll = pois[which].latLonPoint
                            // PoiItem 是 GCJ-02,相机需要 GCJ-02 坐标,直接飞过去
                            aMap?.animateCamera(
                                CameraUpdateFactory.newLatLngZoom(LatLng(ll.latitude, ll.longitude), 17f)
                            )
                        }
                        .show()
                }
            }
        }
        findViewById<Button>(R.id.btn_save_use).setOnClickListener { saveAndUse() }
        updatePreview()
    }

    private fun addPoint(p: GeoPoint) {
        pts.add(p)
        updatePreview()
    }

    private fun updatePreview() {
        polyline?.remove()
        polyline = null
        markers.forEach { it.remove() }
        markers.clear()
        if (pts.isEmpty()) {
            infoText.text = getString(R.string.editor_hint)
            return
        }
        val show = if (loopCheck.isChecked && pts.size >= 2) pts + pts.first() else pts
        val gcjPts = show.map {
            val g = CoordinateConverter.wgs84ToGcj02(it.lat, it.lng)
            LatLng(g.lat, g.lng)
        }
        polyline = aMap?.addPolyline(PolylineOptions().addAll(gcjPts).color(0xCC2E7D32.toInt()).width(10f))

        // 逐点标记: 起点绿"起",终点红"终"(仅多点时),其余棕色序号
        pts.forEachIndexed { i, p ->
            val g = CoordinateConverter.wgs84ToGcj02(p.lat, p.lng)
            val isLast = i == pts.lastIndex && pts.size > 1
            val (label, color) = when {
                i == 0 -> "起" to 0xFF2E7D32.toInt()
                isLast -> "终" to 0xFFD32F2F.toInt()
                else -> "${i + 1}" to 0xFF8B5E3C.toInt()
            }
            val mk = aMap?.addMarker(
                com.amap.api.maps.model.MarkerOptions()
                    .position(LatLng(g.lat, g.lng))
                    .icon(BitmapDescriptorFactory.fromBitmap(badgeBitmap(label, color)))
                    .anchor(0.5f, 0.5f)
            )
            if (mk != null) markers.add(mk)
        }

        var len = 0.0
        for (i in 0 until show.size - 1) len += RoutePlayer.haversine(show[i], show[i + 1])
        infoText.text = getString(R.string.editor_pts_fmt, pts.size, len)
    }

    /** 准星读数:地图中心点的 WGS-84 坐标,随平移/缩放实时刷新 */
    private fun updateCrosshairReadout(pos: com.amap.api.maps.model.CameraPosition?) {
        val t = pos?.target ?: return
        val wgs = CoordinateConverter.gcj02ToWgs84(t.latitude, t.longitude)
        findViewById<TextView>(R.id.crosshair_coord).text =
            getString(R.string.crosshair_coord_fmt, wgs.lat, wgs.lng)
    }

    /** 序号圆点徽章:彩底白字,比格主题配色 */
    private fun badgeBitmap(text: String, bgColor: Int): Bitmap {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = bgColor
            style = Paint.Style.FILL
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 8f
        }
        c.drawCircle(size / 2f, size / 2f, size / 2f - 8f, fill)
        c.drawCircle(size / 2f, size / 2f, size / 2f - 8f, stroke)
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = if (text.length > 1) 40f else 48f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val baseline = size / 2f - (tp.ascent() + tp.descent()) / 2f
        c.drawText(text, size / 2f, baseline, tp)
        return bmp
    }

    private fun saveAndUse() {
        if (pts.size < 2) {
            Toast.makeText(this, R.string.editor_need_pts, Toast.LENGTH_SHORT).show()
            return
        }
        val loop = loopCheck.isChecked
        val name = "路线 " + SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
        RouteStore.add(this, RouteStore.SavedRoute(name, loop, ArrayList(pts)))

        val arr = DoubleArray(pts.size * 2)
        pts.forEachIndexed { i, p ->
            arr[i * 2] = p.lat
            arr[i * 2 + 1] = p.lng
        }
        setResult(
            RESULT_OK,
            Intent()
                .putExtra(PickerContract.EXTRA_ROUTE_PTS, arr)
                .putExtra(PickerContract.EXTRA_ROUTE_NAME, name)
                .putExtra(PickerContract.EXTRA_ROUTE_LOOP, loop)
        )
        Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { mapView.onPause(); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState)
    }
    override fun onDestroy() { mapView.onDestroy(); super.onDestroy() }
}
