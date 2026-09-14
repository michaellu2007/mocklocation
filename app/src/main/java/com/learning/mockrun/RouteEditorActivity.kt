package com.learning.mockrun

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.TextureMapView
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.PolylineOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 自定义路线绘制:点按地图依次加途经点(内部 WGS-84,显示 GCJ-02),
 * 保存并使用 = 存入「我的路线」+ 返回主界面立即开跑。
 */
class RouteEditorActivity : AppCompatActivity() {

    private lateinit var mapView: TextureMapView
    private lateinit var infoText: TextView
    private lateinit var loopCheck: CheckBox
    private val pts = ArrayList<GeoPoint>()
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
        map.setOnMapClickListener { latLng ->
            val wgs = CoordinateConverter.gcj02ToWgs84(latLng.latitude, latLng.longitude)
            addPoint(GeoPoint(wgs.lat, wgs.lng))
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

        var len = 0.0
        for (i in 0 until show.size - 1) len += RoutePlayer.haversine(show[i], show[i + 1])
        infoText.text = getString(R.string.editor_pts_fmt, pts.size, len)
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
