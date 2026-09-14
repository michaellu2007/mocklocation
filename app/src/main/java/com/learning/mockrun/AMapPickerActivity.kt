package com.learning.mockrun

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions

/**
 * 高德地图选点。
 *
 * - 高德地图数据是 GCJ-02:标记放在 GCJ-02 坐标上,返回前才转 WGS-84
 * - key 为空或校验失败时地图是空白网格,但点击回调照常工作
 * - 8.1.0 起 SDK 要求隐私合规调用,不做地图初始化直接失败
 */
class AMapPickerActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var coordText: TextView
    private var aMap: AMap? = null
    private var marker: Marker? = null
    private var picked: LatLng? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        MapsInitializer.updatePrivacyShow(this, true, true)
        MapsInitializer.updatePrivacyAgree(this, true)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_amap_picker)

        mapView = findViewById(R.id.amap_view)
        coordText = findViewById(R.id.picked_coord)
        mapView.onCreate(savedInstanceState)

        val map = mapView.map
        aMap = map
        map.moveCamera(
            CameraUpdateFactory.newLatLngZoom(
                LatLng(MockLocationService.DEFAULT_LAT, MockLocationService.DEFAULT_LON),
                15f
            )
        )
        map.setOnMapClickListener { latLng -> placeMarker(latLng) }

        findViewById<Button>(R.id.btn_confirm_pick).setOnClickListener {
            val pos = picked
            if (pos == null) {
                Toast.makeText(this, R.string.toast_pick_first, Toast.LENGTH_SHORT).show()
            } else {
                val wgs = CoordinateConverter.gcj02ToWgs84(pos.latitude, pos.longitude)
                setResult(
                    RESULT_OK,
                    Intent()
                        .putExtra(PickerContract.EXTRA_LAT, wgs.lat)
                        .putExtra(PickerContract.EXTRA_LNG, wgs.lng)
                )
                finish()
            }
        }
    }

    private fun placeMarker(latLng: LatLng) {
        picked = latLng
        marker?.remove()
        marker = aMap?.addMarker(MarkerOptions().position(latLng).title(getString(R.string.amap_marker_title)))
        coordText.text = getString(
            R.string.picked_coord_fmt,
            "%.6f".format(latLng.latitude),
            "%.6f".format(latLng.longitude)
        )
    }

    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { mapView.onPause(); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState)
    }
    override fun onDestroy() { mapView.onDestroy(); super.onDestroy() }
}
