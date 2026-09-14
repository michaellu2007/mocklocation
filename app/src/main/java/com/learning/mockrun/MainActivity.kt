package com.learning.mockrun

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.TextureMapView
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.PolylineOptions

/**
 * 全屏地图 + 底部比格控制卡。
 *
 * 坐标系边界(关键):
 * - 内部状态(锚点/路线/注入)一律 WGS-84
 * - 高德地图显示用 GCJ-02,进出地图各做一次转换
 * - 比格标记位置由服务的 currentMotion 驱动(1s tick),并按航向转身
 */
class MainActivity : AppCompatActivity() {

    private lateinit var mapView: TextureMapView
    private lateinit var statusText: TextView
    private lateinit var speedLabel: TextView
    private lateinit var presetLenText: TextView
    private lateinit var presetSpinner: Spinner
    private lateinit var rbModeRoute: RadioButton
    private lateinit var rbModePoint: RadioButton
    private lateinit var rbModeCustom: RadioButton
    private lateinit var presetRow: android.view.View
    private lateinit var customRow: android.view.View
    private lateinit var rbAmap: RadioButton
    private lateinit var rbBaidu: RadioButton

    private var aMap: AMap? = null
    private var beagleMarker: Marker? = null
    private var routePolyline: Polyline? = null
    private var beagleBitmap: Bitmap? = null

    private var anchor: GeoPoint? = null
    private var customRoute: RouteStore.SavedRoute? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private var feedCount = 0
    private var pendingStart = false

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val ok = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true &&
                grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (ok) {
                requestNotificationPermissionIfNeeded()
                if (pendingStart) {
                    pendingStart = false
                    startMock()
                }
            } else {
                Toast.makeText(this, R.string.toast_need_perm, Toast.LENGTH_LONG).show()
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val pickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val data = result.data ?: return@registerForActivityResult
                val lat = data.getDoubleExtra(PickerContract.EXTRA_LAT, Double.NaN)
                val lng = data.getDoubleExtra(PickerContract.EXTRA_LNG, Double.NaN)
                if (!lat.isNaN() && !lng.isNaN()) {
                    setAnchor(GeoPoint(lat, lng), animateCamera = true)
                }
            }
        }

    /** 绘制路线 / 我的路线 共用:拿到整条路线(WGS-84)后装填为自定义路线 */
    private val routeResultLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val data = result.data ?: return@registerForActivityResult
                val pts = data.getDoubleArrayExtra(PickerContract.EXTRA_ROUTE_PTS) ?: return@registerForActivityResult
                val name = data.getStringExtra(PickerContract.EXTRA_ROUTE_NAME) ?: getString(R.string.notif_default_name)
                val loop = data.getBooleanExtra(PickerContract.EXTRA_ROUTE_LOOP, true)
                val geo = pts.toList().chunked(2).map { GeoPoint(it[0], it[1]) }
                customRoute = RouteStore.SavedRoute(name, loop, geo)
                rbModeCustom.isChecked = true
                updateRoutePreview()
                // 相机看一眼路线
                anchor = customRoute?.points?.firstOrNull() ?: anchor
                anchor?.let {
                    val g = CoordinateConverter.wgs84ToGcj02(it.lat, it.lng)
                    aMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(g.lat, g.lng), 16f))
                }
            }
        }

    private val ticker = object : Runnable {
        override fun run() {
            val motion = MockLocationService.currentMotion
            if (motion == null) {
                uiHandler.postDelayed(this, TICK_MS)
                return
            }
            feedCount++
            val gcj = CoordinateConverter.wgs84ToGcj02(motion.lat, motion.lng)
            beagleMarker?.let { mk ->
                mk.position = LatLng(gcj.lat, gcj.lng)
                mk.rotateAngle = motion.bearingDeg
            }
            statusText.text = getString(
                R.string.status_running,
                MockLocationService.activeName ?: "",
                motion.speedMps,
                feedCount
            )
            uiHandler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        MapsInitializer.updatePrivacyShow(this, true, true)
        MapsInitializer.updatePrivacyAgree(this, true)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        anchor = loadAnchor()

        mapView = findViewById(R.id.map_view)
        statusText = findViewById(R.id.status_text)
        speedLabel = findViewById(R.id.speed_label)
        presetLenText = findViewById(R.id.preset_len)
        presetSpinner = findViewById(R.id.preset_spinner)
        presetRow = findViewById(R.id.preset_row)
        rbModeRoute = findViewById(R.id.rb_mode_route)
        rbModePoint = findViewById(R.id.rb_mode_point)
        rbModeCustom = findViewById(R.id.rb_mode_custom)
        customRow = findViewById(R.id.custom_row)
        rbAmap = findViewById(R.id.rb_amap)
        rbBaidu = findViewById(R.id.rb_baidu)
        mapView.onCreate(savedInstanceState)

        beagleBitmap = vectorToBitmap(R.drawable.ic_beagle, 128)

        setupMap()
        setupControls()

        anchor?.let { setAnchor(it, animateCamera = false) }
    }

    private fun setupMap() {
        val map = mapView.map
        aMap = map
        map.uiSettings.isZoomControlsEnabled = false
        map.uiSettings.isCompassEnabled = false
        map.uiSettings.isMyLocationButtonEnabled = false
        map.setOnMapLongClickListener { latLng ->
            val wgs = CoordinateConverter.gcj02ToWgs84(latLng.latitude, latLng.longitude)
            setAnchor(GeoPoint(wgs.lat, wgs.lng), animateCamera = false)
        }
        val a = anchor
        map.moveCamera(
            CameraUpdateFactory.newLatLngZoom(
                LatLng(a?.lat ?: MockLocationService.DEFAULT_LAT, a?.lng ?: MockLocationService.DEFAULT_LON),
                16f
            )
        )
    }

    private fun setupControls() {
        when (MapEngine.load(this)) {
            MapEngine.AMAP -> rbAmap.isChecked = true
            MapEngine.BAIDU -> rbBaidu.isChecked = true
        }
        findViewById<RadioGroup>(R.id.engine_group).setOnCheckedChangeListener { _, checkedId ->
            val engine = if (checkedId == R.id.rb_baidu) MapEngine.BAIDU else MapEngine.AMAP
            MapEngine.save(this, engine)
            Toast.makeText(this, getString(R.string.toast_engine_switched, engine.displayName), Toast.LENGTH_SHORT).show()
        }

        presetSpinner.adapter = android.widget.ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            PresetRoutes.ALL.map { it.label }
        )
        presetSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                updateRoutePreview()
            }

            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        findViewById<RadioGroup>(R.id.mode_group).setOnCheckedChangeListener { _, _ ->
            presetRow.visibility = if (rbModeRoute.isChecked) android.view.View.VISIBLE else android.view.View.GONE
            customRow.visibility = if (rbModeCustom.isChecked) android.view.View.VISIBLE else android.view.View.GONE
            updateRoutePreview()
        }

        findViewById<Button>(R.id.btn_draw).setOnClickListener {
            routeResultLauncher.launch(Intent(this, RouteEditorActivity::class.java))
        }
        findViewById<Button>(R.id.btn_my).setOnClickListener {
            routeResultLauncher.launch(Intent(this, SavedRoutesActivity::class.java))
        }

        val seek = findViewById<SeekBar>(R.id.speed_seek)
        seek.progress = 30 // 3.0 m/s
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                updateSpeedLabel()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        updateSpeedLabel()

        findViewById<Button>(R.id.btn_pick).setOnClickListener { openPicker() }
        statusText.setOnClickListener {
            // 状态栏兼做授权入口:未授权时点这里直达开发者选项
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        }
        findViewById<Button>(R.id.btn_start).setOnClickListener { startMock() }
        findViewById<Button>(R.id.btn_stop).setOnClickListener {
            MockLocationService.stop(this)
            stopTicker()
            refreshIdleStatus()
            Toast.makeText(this, R.string.toast_stopped, Toast.LENGTH_SHORT).show()
        }
        refreshIdleStatus()
    }

    private fun setupBeagleMarker(gcj: LatLng) {
        val bmp = beagleBitmap ?: return
        beagleMarker = aMap?.addMarker(
            MarkerOptions()
                .position(gcj)
                .icon(BitmapDescriptorFactory.fromBitmap(bmp))
                .anchor(0.5f, 0.5f)
                .setFlat(true)
        )
    }

    private fun setAnchor(wgs: GeoPoint, animateCamera: Boolean) {
        anchor = wgs
        saveAnchor(wgs)
        val gcj = CoordinateConverter.wgs84ToGcj02(wgs.lat, wgs.lng)
        val latLng = LatLng(gcj.lat, gcj.lng)
        if (beagleMarker == null) setupBeagleMarker(latLng) else beagleMarker?.position = latLng
        if (animateCamera) aMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 16f))
        updateRoutePreview()
    }

    private fun updateRoutePreview() {
        routePolyline?.remove()
        routePolyline = null
        if (rbModeCustom.isChecked) {
            val r = customRoute ?: return
            drawRouteOnMap(r.points)
            return
        }
        val a = anchor ?: return
        if (!rbModeRoute.isChecked) return
        val preset = PresetRoutes.ALL.getOrNull(presetSpinner.selectedItemPosition) ?: return
        presetLenText.text = "~${preset.lengthM}m"
        drawRouteOnMap(PresetRoutes.generate(preset, a))
    }

    private fun drawRouteOnMap(route: List<GeoPoint>) {
        val gcjPts = route.map {
            val g = CoordinateConverter.wgs84ToGcj02(it.lat, it.lng)
            LatLng(g.lat, g.lng)
        }
        routePolyline = aMap?.addPolyline(
            PolylineOptions()
                .addAll(gcjPts)
                .color(0xCC8B5E3C.toInt())
                .width(10f)
        )
    }

    private fun openPicker() {
        val engine = MapEngine.load(this)
        if (!engine.available) {
            Toast.makeText(this, getString(R.string.toast_engine_pending, engine.displayName), Toast.LENGTH_SHORT).show()
            return
        }
        pickerLauncher.launch(Intent(this, AMapPickerActivity::class.java))
    }

    private fun startMock() {
        if (!isMockLocationAllowed()) {
            Toast.makeText(this, R.string.toast_need_auth, Toast.LENGTH_LONG).show()
            return
        }
        if (!hasLocationPermission()) {
            pendingStart = true
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            return
        }
        doStart()
    }

    private fun doStart() {
        val customMode = rbModeCustom.isChecked
        val routeMode = rbModeRoute.isChecked

        // 自定义模式的坐标来自路线本身,不依赖锚点
        var a: GeoPoint? = anchor
        if (!customMode && a == null) {
            a = aMap?.cameraPosition?.target?.let {
                val wgs = CoordinateConverter.gcj02ToWgs84(it.latitude, it.longitude)
                GeoPoint(wgs.lat, wgs.lng).also { p -> setAnchor(p, animateCamera = false) }
            }
        }
        if (!customMode && a == null) {
            Toast.makeText(this, R.string.toast_pick_first, Toast.LENGTH_SHORT).show()
            return
        }
        val anchorPoint = a

        val preset = PresetRoutes.ALL.getOrNull(presetSpinner.selectedItemPosition) ?: PresetRoutes.ALL[0]

        val name: String
        val route: List<GeoPoint>
        when {
            customMode -> {
                val r = customRoute
                if (r == null) {
                    Toast.makeText(this, R.string.toast_no_custom_route, Toast.LENGTH_SHORT).show()
                    return
                }
                name = r.name
                route = r.points
            }
            routeMode -> {
                name = preset.label
                route = PresetRoutes.generate(preset, anchorPoint!!)
            }
            else -> {
                name = getString(R.string.notif_default_name)
                route = listOf(anchorPoint!!)
            }
        }
        val pts = DoubleArray(route.size * 2)
        route.forEachIndexed { i, p ->
            pts[i * 2] = p.lat
            pts[i * 2 + 1] = p.lng
        }

        feedCount = 0
        MockLocationService.start(this, name, speedMps(), pts)
        Toast.makeText(this, R.string.toast_started, Toast.LENGTH_SHORT).show()
        stopTicker()
        uiHandler.post(ticker)
    }

    private fun speedMps(): Double {
        val seek = findViewById<SeekBar>(R.id.speed_seek)
        return (seek.progress + 1) / 10.0 // 0.1 ~ 6.0 m/s
    }

    private fun updateSpeedLabel() {
        val s = speedMps()
        val paceSecPerKm = 1000.0 / s
        val label = "'%02d\"".format((paceSecPerKm % 60).toInt())
        speedLabel.text = getString(R.string.speed_fmt, s, "${(paceSecPerKm / 60).toInt()}$label")
    }

    private fun refreshIdleStatus() {
        statusText.text = getString(R.string.status_idle, MapEngine.load(this).displayName)
    }

    private fun stopTicker() {
        uiHandler.removeCallbacks(ticker)
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        if (MockLocationService.currentMotion != null) {
            stopTicker()
            uiHandler.post(ticker)
        }
    }

    override fun onPause() {
        mapView.onPause()
        stopTicker()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        mapView.onDestroy()
        super.onDestroy()
    }

    // ---- 常规工具 ----

    private fun hasLocationPermission(): Boolean {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun loadAnchor(): GeoPoint? {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val lat = prefs.getString("anchor_lat", null)?.toDoubleOrNull() ?: return null
        val lng = prefs.getString("anchor_lng", null)?.toDoubleOrNull() ?: return null
        return GeoPoint(lat, lng)
    }

    private fun saveAnchor(a: GeoPoint) {
        getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putString("anchor_lat", a.lat.toString())
            .putString("anchor_lng", a.lng.toString())
            .apply()
    }

    /**
     * 检查本应用是否被允许模拟位置(AppOps 按 uid/包名,权威口径;
     * Settings.Secure.ALLOW_MOCK_LOCATION 是全机全局开关且已废弃)。
     */
    private fun isMockLocationAllowed(): Boolean {
        return try {
            val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), packageName)
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), packageName)
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    private fun vectorToBitmap(resId: Int, sizePx: Int): Bitmap {
        val d = ContextCompat.getDrawable(this, resId)!!
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        d.setBounds(0, 0, sizePx, sizePx)
        d.draw(canvas)
        return bmp
    }

    companion object {
        private const val TICK_MS = 1000L
    }
}
