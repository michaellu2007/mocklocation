package com.learning.mockrun

import android.Manifest
import android.animation.Animator
import android.animation.ObjectAnimator
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AnimationDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
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
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * 四 Tab 骨架(对齐参考实现):定位 / 路线 / 路线库 / 信息与设置。
 * 底层地图共享;底部常驻开跑条(速度+启停)所有 Tab 可见。
 *
 * 坐标系边界(关键):内部状态(锚点/路线/注入)一律 WGS-84,
 * 高德显示是 GCJ-02,进出地图各转换一次。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var mapView: TextureMapView
    private lateinit var statusText: TextView
    private lateinit var speedLabel: TextView
    private lateinit var authStatusText: TextView
    private lateinit var panels: List<android.view.View>
    private lateinit var rbAmap: RadioButton
    private lateinit var rbBaidu: RadioButton
    private lateinit var mapCrosshair: View

    private var currentTab = TAB_LOCATION
    private var mapResumed = false
    private var aMap: AMap? = null
    private var beagleMarker: Marker? = null
    private var routePolyline: Polyline? = null
    private var beagleBitmap: Bitmap? = null

    private var anchor: GeoPoint? = null
    private var customRoute: RouteStore.SavedRoute? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private var feedCount = 0
    private var pendingStart = false

    // 启动加载遮罩(二帧比格奔跑)
    private lateinit var splashRoot: View
    private lateinit var splashBeagle: ImageView
    private var splashBounce: ObjectAnimator? = null
    private var splashActive = false
    private val splashTimeoutRunnable = Runnable { hideSplash() }

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

    private val routeResultLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val data = result.data ?: return@registerForActivityResult
                val pts = data.getDoubleArrayExtra(PickerContract.EXTRA_ROUTE_PTS) ?: return@registerForActivityResult
                val name = data.getStringExtra(PickerContract.EXTRA_ROUTE_NAME) ?: getString(R.string.notif_default_name)
                val loop = data.getBooleanExtra(PickerContract.EXTRA_ROUTE_LOOP, true)
                val geo = pts.toList().chunked(2).map { GeoPoint(it[0], it[1]) }
                customRoute = RouteStore.SavedRoute(name, loop, geo)
                updateRoutePreview()
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
        mapCrosshair = findViewById(R.id.map_crosshair)
        statusText = findViewById(R.id.status_text)
        speedLabel = findViewById(R.id.speed_label)
        rbAmap = findViewById(R.id.rb_amap)
        rbBaidu = findViewById(R.id.rb_baidu)
        authStatusText = findViewById(R.id.set_auth_status)
        panels = listOf(
            findViewById(R.id.panel_location),
            findViewById(R.id.panel_route),
            findViewById(R.id.panel_library),
            findViewById(R.id.panel_settings),
        )
        mapView.onCreate(savedInstanceState)

        beagleBitmap = vectorToBitmap(R.drawable.ic_beagle, 128)

        setupMap()
        setupTabs()
        setupControls()
        setupSettings()

        splashRoot = findViewById(R.id.splash_root)
        splashBeagle = findViewById(R.id.splash_beagle)
        startSplash()
        if (!prefs().getBoolean("eula_accepted", false)) showEulaDialog()
        maybeShowKeyNag()

        anchor?.let { setAnchor(it, animateCamera = false) }
    }

    /** 首次启动用户协议:不可跳过,不同意即退出 */
    private fun showEulaDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.eula_title)
            .setMessage(R.string.eula_text)
            .setCancelable(false)
            .setPositiveButton(R.string.eula_agree) { d, _ ->
                prefs().edit().putBoolean("eula_accepted", true).apply()
                d.dismiss()
            }
            .setNegativeButton(R.string.eula_decline) { _, _ ->
                finish()
            }
            .show()
    }

    private fun setupMap() {
        val map = mapView.map
        aMap = map
        map.uiSettings.isZoomControlsEnabled = false
        map.uiSettings.isCompassEnabled = false
        map.uiSettings.isMyLocationButtonEnabled = false
        // 地图就绪(瓦片加载完成)即淡出启动页;key 无效等异常由 4s 超时兜底
        map.setOnMapLoadedListener { hideSplash() }
        // 定位Tab:拖到哪就模拟到哪——相机停稳后,锚点自动取屏幕中心圆点处
        map.setOnCameraChangeListener(object : AMap.OnCameraChangeListener {
            override fun onCameraChange(pos: com.amap.api.maps.model.CameraPosition?) {}
            override fun onCameraChangeFinish(pos: com.amap.api.maps.model.CameraPosition?) {
                if (currentTab == TAB_LOCATION) updateAnchorFromCenter()
            }
        })
        val a = anchor
        map.moveCamera(
            CameraUpdateFactory.newLatLngZoom(
                LatLng(a?.lat ?: MockLocationService.DEFAULT_LAT, a?.lng ?: MockLocationService.DEFAULT_LON),
                16f
            )
        )
    }

    private fun setupTabs() {
        val nav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        nav.setOnItemSelectedListener { item ->
            currentTab = when (item.itemId) {
                R.id.nav_route -> TAB_ROUTE
                R.id.nav_library -> TAB_LIBRARY
                R.id.nav_settings -> TAB_SETTINGS
                else -> TAB_LOCATION
            }
            showTab(currentTab)
            true
        }
        // 返回键:非定位 Tab 时回到定位页(参考实现同款行为),定位页才退出
        onBackPressedDispatcher.addCallback(
            this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (currentTab != TAB_LOCATION) {
                        nav.selectedItemId = R.id.nav_location
                    } else {
                        finish()
                    }
                }
            }
        )
        showTab(TAB_LOCATION)
    }

    private fun showTab(tab: Int) {
        currentTab = tab
        val mapVisible = isMapTab()
        // 库/设置是独立整页:地图、提示、开跑条全部隐藏,只留底部导航
        mapView.visibility = if (mapVisible) View.VISIBLE else View.GONE
        mapCrosshair.visibility = if (tab == TAB_LOCATION) View.VISIBLE else View.GONE
        findViewById<View>(R.id.hint_top).visibility = if (mapVisible) View.VISIBLE else View.GONE
        findViewById<View>(R.id.running_bar).visibility = if (mapVisible) View.VISIBLE else View.GONE
        panels.forEachIndexed { i, v -> v.visibility = if (i == tab) View.VISIBLE else View.GONE }
        if (tab == TAB_SETTINGS) refreshAuthStatus()
        setMapResumed(mapVisible)
    }

    private fun isMapTab() = currentTab == TAB_LOCATION || currentTab == TAB_ROUTE

    /** 地图不可见时挂起渲染,省电省内存;恢复可见时唤醒 */
    private fun setMapResumed(resume: Boolean) {
        if (mapResumed == resume) return
        mapResumed = resume
        if (resume) mapView.onResume() else mapView.onPause()
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

        findViewById<Button>(R.id.btn_draw).setOnClickListener {
            routeResultLauncher.launch(Intent(this, RouteEditorActivity::class.java))
        }
        findViewById<Button>(R.id.btn_my).setOnClickListener {
            routeResultLauncher.launch(Intent(this, SavedRoutesActivity::class.java))
        }
        findViewById<Button>(R.id.lib_manage).setOnClickListener {
            routeResultLauncher.launch(Intent(this, SavedRoutesActivity::class.java))
        }
        findViewById<Button>(R.id.lib_draw).setOnClickListener {
            routeResultLauncher.launch(Intent(this, RouteEditorActivity::class.java))
        }

        findViewById<Button>(R.id.loc_pick).setOnClickListener { openPicker() }
        findViewById<Button>(R.id.btn_fav_add).setOnClickListener { addFavorite() }
        findViewById<Button>(R.id.btn_fav_list).setOnClickListener { showFavoritesDialog() }
        findViewById<Button>(R.id.btn_search).setOnClickListener {
            val kw = findViewById<EditText>(R.id.search_input).text.toString()
            if (kw.isBlank()) {
                Toast.makeText(this, R.string.search_empty_kw, Toast.LENGTH_SHORT).show()
            } else {
                doPoiSearch(kw) { item ->
                    val wgs = CoordinateConverter.gcj02ToWgs84(item.latLonPoint.latitude, item.latLonPoint.longitude)
                    setAnchor(GeoPoint(wgs.lat, wgs.lng), animateCamera = true)
                    Toast.makeText(this, getString(R.string.search_picked, item.title), Toast.LENGTH_SHORT).show()
                }
            }
        }
        findViewById<Button>(R.id.btn_open_dev).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
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

        findViewById<Button>(R.id.btn_start).setOnClickListener { startMock() }
        findViewById<Button>(R.id.btn_stop).setOnClickListener {
            MockLocationService.stop(this)
            stopTicker()
            refreshIdleStatus()
            Toast.makeText(this, R.string.toast_stopped, Toast.LENGTH_SHORT).show()
        }
        refreshIdleStatus()
        refreshVersion()
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

    /** 屏幕中心圆点处 = 新锚点(仅定位Tab使用) */
    private fun updateAnchorFromCenter() {
        val target = aMap?.cameraPosition?.target ?: return
        val wgs = CoordinateConverter.gcj02ToWgs84(target.latitude, target.longitude)
        val p = GeoPoint(wgs.lat, wgs.lng)
        if (anchor == null || RoutePlayer.haversine(anchor!!, p) > 0.5) {
            anchor = p
            saveAnchor(p)
            val gcj = CoordinateConverter.wgs84ToGcj02(p.lat, p.lng)
            val latLng = LatLng(gcj.lat, gcj.lng)
            if (beagleMarker == null) setupBeagleMarker(latLng) else beagleMarker?.position = latLng
        }
    }

    private fun updateRoutePreview() {
        routePolyline?.remove()
        routePolyline = null
        if (currentTab != TAB_ROUTE) return
        val r = customRoute ?: return
        drawRouteOnMap(r.points)
    }

    private fun drawRouteOnMap(route: List<GeoPoint>) {
        val gcjPts = route.map {
            val g = CoordinateConverter.wgs84ToGcj02(it.lat, it.lng)
            LatLng(g.lat, g.lng)
        }
        routePolyline = aMap?.addPolyline(
            PolylineOptions()
                .addAll(gcjPts)
                .color(0xFF00C853.toInt())
                .width(14f)
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

    /** 收藏当前位置:优先锚点,否则取图中心。同名覆盖 */
    private fun addFavorite() {
        val point = anchor
            ?: aMap?.cameraPosition?.target?.let {
                val wgs = CoordinateConverter.gcj02ToWgs84(it.latitude, it.longitude)
                GeoPoint(wgs.lat, wgs.lng)
            }
        if (point == null) {
            Toast.makeText(this, R.string.toast_pick_first, Toast.LENGTH_SHORT).show()
            return
        }
        val name = "收藏 " + java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date())
        FavoriteStore.add(this, FavoriteStore.Favorite(name, point.lat, point.lng, System.currentTimeMillis()))
        Toast.makeText(this, getString(R.string.fav_saved, name), Toast.LENGTH_SHORT).show()
    }

    /** 我的收藏:列表 → 定位过去 / 删除 */
    private fun showFavoritesDialog() {
        val favorites = FavoriteStore.list(this)
        if (favorites.isEmpty()) {
            Toast.makeText(this, R.string.fav_none, Toast.LENGTH_SHORT).show()
            return
        }
        val titles = favorites.map {
            getString(R.string.fav_item_fmt, it.name, "%.5f".format(it.lat), "%.5f".format(it.lng))
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.fav_title)
            .setItems(titles.toTypedArray()) { _, which ->
                val fav = favorites[which]
                val actions = arrayOf(getString(R.string.fav_act_goto), getString(R.string.act_delete))
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(fav.name)
                    .setItems(actions) { _, w ->
                        when (w) {
                            0 -> {
                                setAnchor(GeoPoint(fav.lat, fav.lng), animateCamera = true)
                                Toast.makeText(this, getString(R.string.search_picked, fav.name), Toast.LENGTH_SHORT).show()
                            }
                            1 -> {
                                FavoriteStore.remove(this, fav.name)
                                Toast.makeText(this, getString(R.string.fav_deleted, fav.name), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .show()
            }
            .show()
    }

    /** POI 搜索:关键字 → 结果列表 → 选中即设为锚点 */
    private fun doPoiSearch(keyword: String, onPick: (com.amap.api.services.core.PoiItem) -> Unit) {
        PoiSearchHelper.ensurePrivacy(this)
        PoiSearchHelper.search(this, keyword) { pois ->
            runOnUiThread {
                if (pois.isEmpty()) {
                    Toast.makeText(this, R.string.search_no_result, Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val titles = pois.map { "${it.title} · ${it.snippet}" }
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(getString(R.string.search_result_title, pois.size))
                    .setItems(titles.toTypedArray()) { _, which -> onPick(pois[which]) }
                    .show()
            }
        }
    }

    /** 第四 Tab:软件配置(地图类型/坐标系/波动)与版本更新,全部本地持久化 */
    private fun setupSettings() {
        val prefs = prefs()
        val rbNormal = findViewById<RadioButton>(R.id.rb_map_normal)
        val rbSat = findViewById<RadioButton>(R.id.rb_map_sat)
        val rbWgs = findViewById<RadioButton>(R.id.rb_coord_wgs)
        val rbGcj = findViewById<RadioButton>(R.id.rb_coord_gcj)
        val rdBd = findViewById<RadioButton>(R.id.rb_coord_bd)
        val wobbleSwitch = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.wobble_switch)

        when (prefs.getString("map_type", "normal")) {
            "satellite" -> rbSat.isChecked = true
            else -> rbNormal.isChecked = true
        }
        applyMapType()
        findViewById<RadioGroup>(R.id.maptype_group).setOnCheckedChangeListener { _, id ->
            prefs.edit().putString("map_type", if (id == R.id.rb_map_sat) "satellite" else "normal").apply()
            applyMapType()
        }

        when (prefs.getString("coord_sys", "WGS84")) {
            "GCJ02" -> rbGcj.isChecked = true
            "BD09" -> rdBd.isChecked = true
            else -> rbWgs.isChecked = true
        }
        findViewById<RadioGroup>(R.id.coord_group).setOnCheckedChangeListener { _, id ->
            val sys = when (id) {
                R.id.rb_coord_gcj -> "GCJ02"
                R.id.rb_coord_bd -> "BD09"
                else -> "WGS84"
            }
            prefs.edit().putString("coord_sys", sys).apply()
            refreshManualLabel()
        }

        wobbleSwitch.isChecked = prefs.getBoolean("wobble", true)
        wobbleSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("wobble", checked).apply()
        }

        findViewById<Button>(R.id.btn_check_update).setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.settings_update))
                .setMessage(getString(R.string.update_latest, currentVersion()))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        findViewById<Button>(R.id.btn_apply_coord).setOnClickListener { applyManualCoord() }
        refreshManualLabel()
        findViewById<Button>(R.id.btn_export_diag).setOnClickListener { exportDiagnostics() }
        setupAmapKeySection()
        setupSettingsTabs()
    }

    /** 设置页三段子页:地图设置 / 更新 / 打赏(待开发) */
    private fun setupSettingsTabs() {
        val pages = listOf(
            findViewById<View>(R.id.settings_page_map),
            findViewById<View>(R.id.settings_page_update),
            findViewById<View>(R.id.settings_page_donate),
        )
        findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.settings_tabs)
            .addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (!isChecked) return@addOnButtonCheckedListener
                val idx = when (checkedId) {
                    R.id.btn_stab_update -> 1
                    R.id.btn_stab_donate -> 2
                    else -> 0
                }
                pages.forEachIndexed { i, v -> v.visibility = if (i == idx) View.VISIBLE else View.GONE }
            }
        pages.forEachIndexed { i, v -> v.visibility = if (i == 0) View.VISIBLE else View.GONE }
    }

    private fun exportDiagnostics() {
        val sb = StringBuilder(CrashReporter.buildText(this))
        val crashes = CrashReporter.latestCrashes(this)
        if (crashes.isNotEmpty()) {
            sb.appendLine().appendLine("== 历史崩溃记录 ==")
            crashes.forEach { f ->
                sb.appendLine("--- ${f.name} ---")
                sb.appendLine(runCatching { f.readText() }.getOrDefault("(读取失败)"))
            }
        }
        val safeName = "MockRun诊断-" + java.text.SimpleDateFormat("MMdd-HHmm", java.util.Locale.getDefault())
            .format(java.util.Date()) + ".txt"
        val dir = java.io.File(cacheDir, "share").apply { mkdirs() }
        val file = java.io.File(dir, safeName)
        file.writeText(sb.toString())

        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newRawUri(safeName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.diag_shared)))
    }

    /** 高德个人 Key 区块:显示包名+本机SHA1,保存用户Key,公共额度状态 */
    private fun setupAmapKeySection() {
        val prefs = prefs()
        findViewById<TextView>(R.id.sha1_value).text = apkSha1()
        findViewById<TextView>(R.id.key_status).text =
            if (hasUserAmapKey()) getString(R.string.key_status_personal)
            else getString(R.string.key_status_public, prefs.getInt("launch_count", 0))
        findViewById<EditText>(R.id.user_amap_key_input).setText(prefs.getString("user_amap_key", ""))

        findViewById<Button>(R.id.btn_copy_sha1).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("SHA1", apkSha1()))
            Toast.makeText(this, R.string.sha1_copied, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btn_save_key).setOnClickListener {
            val key = findViewById<EditText>(R.id.user_amap_key_input).text.toString().trim()
            prefs.edit().putString("user_amap_key", key).apply()
            runCatching {
                MapsInitializer.setApiKey(key)
                com.amap.api.services.core.ServiceSettings.getInstance().setApiKey(key)
            }
            refreshKeyStatus()
            Toast.makeText(this, R.string.key_saved, Toast.LENGTH_LONG).show()
        }
    }

    private fun hasUserAmapKey() = !prefs().getString("user_amap_key", "").isNullOrBlank()

    private fun refreshKeyStatus() {
        findViewById<TextView>(R.id.key_status).text =
            if (hasUserAmapKey()) getString(R.string.key_status_personal)
            else getString(R.string.key_status_public, prefs().getInt("launch_count", 0))
    }

    /** 本机 APK 签名的 SHA1(用户去高德申请 Key 时要填的就是它) */
    private fun apkSha1(): String = runCatching {
        @Suppress("DEPRECATION")
        val sig = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures!!.first()
        java.security.MessageDigest.getInstance("SHA1").digest(sig.toByteArray())
            .joinToString(":") { "%02X".format(it) }
    }.getOrDefault("获取失败")

    /** 公共 Key 公告:启动超过 10 次仍未配置个人 Key 时,每次启动温和提醒 */
    private fun maybeShowKeyNag() {
        val prefs = prefs()
        if (hasUserAmapKey()) return
        val count = prefs.getInt("launch_count", 0)
        if (count <= 10) return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.nag_title)
            .setMessage(getString(R.string.nag_msg, count))
            .setPositiveButton(R.string.btn_open_dev) { _, _ ->
                currentTab = TAB_SETTINGS
                findViewById<BottomNavigationView>(R.id.bottom_nav).selectedItemId = R.id.nav_settings
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun prefs() = getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun applyMapType() {
        val sat = prefs().getString("map_type", "normal") == "satellite"
        aMap?.mapType = if (sat) AMap.MAP_TYPE_SATELLITE else AMap.MAP_TYPE_NORMAL
    }

    /** 手动输入坐标:按用户选择的口径解析,统一转 WGS-84 */
    private fun applyManualCoord() {
        val lat = findViewById<EditText>(R.id.input_man_lat).text.toString().toDoubleOrNull()
        val lng = findViewById<EditText>(R.id.input_man_lng).text.toString().toDoubleOrNull()
        if (lat == null || lng == null || lat !in -90.0..90.0 || lng !in -180.0..180.0) {
            Toast.makeText(this, R.string.manual_coord_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        val wgs: GeoPoint = when (prefs().getString("coord_sys", "WGS84")) {
            "GCJ02" -> CoordinateConverter.gcj02ToWgs84(lat, lng).let { GeoPoint(it.lat, it.lng) }
            "BD09" -> CoordinateConverter.bd09ToWgs84(lat, lng).let { GeoPoint(it.lat, it.lng) }
            else -> GeoPoint(lat, lng)
        }
        setAnchor(GeoPoint(wgs.lat, wgs.lng), animateCamera = true)
        Toast.makeText(this, R.string.manual_coord_applied, Toast.LENGTH_SHORT).show()
    }

    private fun refreshManualLabel() {
        val sys = when (prefs().getString("coord_sys", "WGS84")) {
            "GCJ02" -> "GCJ-02(高德)"
            "BD09" -> "BD-09(百度)"
            else -> "WGS-84"
        }
        findViewById<TextView>(R.id.manual_coord_label).text = getString(R.string.manual_coord_fmt, sys)
    }

    private fun currentVersion(): String = runCatching {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull() ?: "?"

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
        // 定位 Tab = 屏幕中心圆点处驻留;路线 Tab = 自定义路线回放
        val name: String
        val route: List<GeoPoint>
        if (currentTab == TAB_LOCATION) {
            updateAnchorFromCenter()
            val a = anchor ?: run {
                Toast.makeText(this, R.string.toast_pick_first, Toast.LENGTH_SHORT).show()
                return
            }
            name = getString(R.string.notif_default_name)
            route = listOf(a)
        } else {
            val r = customRoute
            if (r == null) {
                Toast.makeText(this, R.string.toast_no_custom_route, Toast.LENGTH_SHORT).show()
                return
            }
            name = r.name
            route = r.points
        }

        val pts = DoubleArray(route.size * 2)
        route.forEachIndexed { i, p ->
            pts[i * 2] = p.lat
            pts[i * 2 + 1] = p.lng
        }

        feedCount = 0
        MockLocationService.start(this, name, speedMps(), pts, wobble = prefs().getBoolean("wobble", true))
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

    private fun refreshAuthStatus() {
        val ok = isMockLocationAllowed()
        authStatusText.text = getString(
            R.string.settings_auth_state,
            if (ok) "已授权" else "未授权"
        )
    }

    private fun refreshVersion() {
        findViewById<TextView>(R.id.set_version).text = getString(R.string.settings_version, currentVersion())
    }

    private fun stopTicker() {
        uiHandler.removeCallbacks(ticker)
    }

    override fun onResume() {
        super.onResume()
        refreshAuthStatus()
        if (isMapTab()) setMapResumed(true)
        if (MockLocationService.currentMotion != null) {
            stopTicker()
            uiHandler.post(ticker)
        }
    }

    override fun onPause() {
        if (isMapTab()) setMapResumed(false)
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

    /** 二帧奔跑动画:帧循环(原图↔镜像) + 纵向颠簸 */
    private fun startSplash() {
        splashActive = true
        splashBeagle.setBackgroundResource(R.drawable.anim_beagle_run)
        (splashBeagle.background as? AnimationDrawable)?.start()
        splashBounce = ObjectAnimator.ofFloat(splashBeagle, View.TRANSLATION_Y, 0f, -30f).apply {
            duration = 240
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        uiHandler.postDelayed(splashTimeoutRunnable, SPLASH_MAX_MS)
    }

    private fun hideSplash() {
        if (!splashActive) return
        splashActive = false
        uiHandler.removeCallbacks(splashTimeoutRunnable)
        splashBounce?.cancel()
        splashBounce = null
        (splashBeagle.background as? AnimationDrawable)?.stop()
        splashRoot.animate()
            .alpha(0f)
            .setDuration(350)
            .withEndAction { splashRoot.visibility = View.GONE }
            .start()
    }

    companion object {
        private const val TICK_MS = 1000L
        private const val SPLASH_MAX_MS = 4000L
        private const val TAB_LOCATION = 0
        private const val TAB_ROUTE = 1
        private const val TAB_LIBRARY = 2
        private const val TAB_SETTINGS = 3
    }
}
