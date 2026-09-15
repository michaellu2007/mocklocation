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
import android.net.Uri
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
import android.widget.ListView
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
import com.amap.api.maps.model.BitmapDescriptor
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
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
    private lateinit var mapCrosshair: ImageView

    private var currentTab = TAB_LOCATION
    private var mapResumed = false
    private var aMap: AMap? = null
    private var routePolyline: Polyline? = null
    private var runMarker: Marker? = null
    private var markerRunning = false

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
                prefs().edit().putString("last_route", RouteStore.encode(customRoute!!)).apply()
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
            // 显示走干净通道(零噪声,参考实现同款分离);注入走 currentMotion(带波动)
            val motion = MockLocationService.currentClean ?: MockLocationService.currentMotion
            if (motion == null) {
                uiHandler.postDelayed(this, TICK_MS)
                return
            }
            feedCount++
            applyRunVisual(true)
            val gcj = CoordinateConverter.wgs84ToGcj02(motion.lat, motion.lng)
            val pos = LatLng(gcj.lat, gcj.lng)
            drawRunMarker(pos)
            // 路线页镜头跟着模拟点跑(用户要的);定位页不跟,拖图挪点的手势不能被抢
            if (currentTab == TAB_ROUTE) {
                aMap?.moveCamera(CameraUpdateFactory.changeLatLng(pos))
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
        // 自动恢复上次使用的自定义路线(重启不丢)
        prefs().getString("last_route", null)?.let { s ->
            RouteStore.parseRoute(s)?.let { customRoute = it }
        }

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
        // 定位Tab:拖到哪就模拟到哪——相机停稳后,锚点自动取屏幕中心大头针处
        map.setOnCameraChangeListener(object : AMap.OnCameraChangeListener {
            override fun onCameraChange(pos: com.amap.api.maps.model.CameraPosition?) {}
            override fun onCameraChangeFinish(pos: com.amap.api.maps.model.CameraPosition?) {
                if (currentTab == TAB_LOCATION) {
                    if (markerRunning) applyLocationAtCenter(announce = false)  // 运行中拖动 = 实时把模拟点挪过来
                    else updateAnchorFromCenter()
                }
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
        // 库/设置是独立整页:地图、提示、搜索卡、开跑条全部隐藏,只留底部导航
        mapView.visibility = if (mapVisible) View.VISIBLE else View.GONE
        mapCrosshair.visibility = if (tab == TAB_LOCATION) View.VISIBLE else View.GONE
        findViewById<View>(R.id.hint_top).visibility = if (mapVisible) View.VISIBLE else View.GONE
        findViewById<View>(R.id.search_card).visibility = if (tab == TAB_LOCATION) View.VISIBLE else View.GONE
        // 开跑条(速度/启停)只在路线 Tab 需要;定位 Tab 用「应用定位」
        findViewById<View>(R.id.running_bar).visibility = if (tab == TAB_ROUTE) View.VISIBLE else View.GONE
        // 进入路线页时重画当前路线(重启后自动恢复的那条也会显示)
        if (tab == TAB_ROUTE) updateRoutePreview()
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
        val cbFinish = findViewById<android.widget.CheckBox>(R.id.cb_finish_pause)
        cbFinish.isChecked = prefs().getBoolean("finish_pause", false)
        cbFinish.setOnCheckedChangeListener { _, c ->
            prefs().edit().putBoolean("finish_pause", c).apply()
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

        findViewById<Button>(R.id.btn_apply_location).setOnClickListener {
            if (markerRunning) {
                MockLocationService.stop(this)
                stopTicker()
                applyRunVisual(false)
                refreshIdleStatus()
                Toast.makeText(this, R.string.toast_stopped, Toast.LENGTH_SHORT).show()
            } else {
                applyLocationAtCenter(announce = true)
                Toast.makeText(this, R.string.applied_done, Toast.LENGTH_SHORT).show()
                uiHandler.post(ticker)
            }
        }
        findViewById<Button>(R.id.btn_fav_add).setOnClickListener { addFavorite() }
        findViewById<Button>(R.id.btn_fav_list).setOnClickListener { showFavoritesDialog() }
        val searchInput = findViewById<EditText>(R.id.search_input)
        fun runSearch() {
            val kw = searchInput.text.toString()
            if (kw.isBlank()) {
                Toast.makeText(this, R.string.search_empty_kw, Toast.LENGTH_SHORT).show()
                return
            }
            doPoiSearch(kw) { item ->
                val wgs = CoordinateConverter.gcj02ToWgs84(item.latLonPoint.latitude, item.latLonPoint.longitude)
                setAnchor(GeoPoint(wgs.lat, wgs.lng), animateCamera = true)
                Toast.makeText(this, getString(R.string.search_picked, item.title), Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.btn_search).setOnClickListener { runSearch() }
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                runSearch()
                true
            } else {
                false
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
            applyRunVisual(false)
            refreshIdleStatus()
            Toast.makeText(this, R.string.toast_stopped, Toast.LENGTH_SHORT).show()
        }
        refreshIdleStatus()
        refreshVersion()
    }

    private fun setAnchor(wgs: GeoPoint, animateCamera: Boolean) {
        anchor = wgs
        saveAnchor(wgs)
        if (animateCamera) {
            val gcj = CoordinateConverter.wgs84ToGcj02(wgs.lat, wgs.lng)
            aMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(gcj.lat, gcj.lng), 16f))
        }
        updateRoutePreview()
    }

    /** 屏幕中心圆点处 = 新锚点(仅定位Tab使用) */
    /** 运行态视觉:应用定位按钮变红显示「结束定位」;停止恢复「应用定位」 */
    private var applyBtnTint: android.content.res.ColorStateList? = null

    private fun applyRunVisual(running: Boolean) {
        if (markerRunning == running) return
        markerRunning = running
        if (!running) {
            runMarker?.remove()
            runMarker = null
        }
        val btn = findViewById<Button>(R.id.btn_apply_location)
        if (applyBtnTint == null) applyBtnTint = btn.backgroundTintList
        if (running) {
            btn.text = getString(R.string.btn_stop_location)
            btn.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFE53935.toInt())
        } else {
            btn.text = getString(R.string.btn_apply_location)
            // 不能置 null:清空 tint 后 Material 按钮不会回到主题色,会黑掉;还原缓存的主题默认 tint
            btn.backgroundTintList = applyBtnTint
        }
    }

    private fun updateAnchorFromCenter() {
        val target = aMap?.cameraPosition?.target ?: return
        val wgs = CoordinateConverter.gcj02ToWgs84(target.latitude, target.longitude)
        val p = GeoPoint(wgs.lat, wgs.lng)
        if (anchor == null || RoutePlayer.haversine(anchor!!, p) > 0.5) {
            anchor = p
            saveAnchor(p)
        }
    }

    private fun updateRoutePreview() {
        routePolyline?.remove()
        routePolyline = null
        if (currentTab != TAB_ROUTE) return
        val r = customRoute ?: return
        drawRouteOnMap(r.points)
    }

    /** 地图上的模拟点小圆点:运行时每秒挪到当前注入位置(首次调用时创建) */
    private fun drawRunMarker(pos: LatLng) {
        val m = runMarker ?: aMap?.addMarker(
            MarkerOptions()
                .position(pos)
                .icon(BitmapDescriptorFactory.fromBitmap(vectorToBitmap(R.drawable.ic_dot, 24)))
                .anchor(0.5f, 0.5f)
        )?.also { runMarker = it }
        m?.position = pos
    }

    /** 把整条路线框进屏幕;镜头之后保持不动(回放中每秒跟点会抵消位移,看起来像原地画圈) */
    private fun fitRouteOnScreen(route: List<GeoPoint>) {
        val b = LatLngBounds.Builder()
        route.forEach {
            val g = CoordinateConverter.wgs84ToGcj02(it.lat, it.lng)
            b.include(LatLng(g.lat, g.lng))
        }
        runCatching { aMap?.moveCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 100)) }
    }

    private fun drawRouteOnMap(route: List<GeoPoint>) {
        val gcjPts = route.map {
            val g = CoordinateConverter.wgs84ToGcj02(it.lat, it.lng)
            LatLng(g.lat, g.lng)
        }
        // 导航风格:绿底白箭头纹理沿线平铺(随行进方向旋转);纹理加载失败时退回纯色,绝不隐形
        val opts = PolylineOptions().addAll(gcjPts).width(26f)
        val tex = runCatching {
            BitmapDescriptorFactory.fromAsset("route_arrow_texture.png")
        }.getOrNull()
        if (tex != null) {
            opts.setCustomTexture(tex).setUseTexture(true)
        } else {
            opts.color(0xFF00C853.toInt())
        }
        routePolyline = aMap?.addPolyline(opts)
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
    /** POI 搜索:结果内联展开在搜索卡下方(学参考项目),点击即选 */
    private fun doPoiSearch(keyword: String, onPick: (com.amap.api.services.core.PoiItem) -> Unit) {
        PoiSearchHelper.ensurePrivacy(this)
        PoiSearchHelper.search(this, keyword) { pois ->
            runOnUiThread {
                val lv = findViewById<ListView>(R.id.search_results)
                if (pois.isEmpty()) {
                    lv.visibility = View.GONE
                    Toast.makeText(this, R.string.search_no_result, Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                lv.adapter = android.widget.ArrayAdapter(
                    this,
                    android.R.layout.simple_list_item_1,
                    pois.take(8).map { "${it.title} · ${it.snippet}" }
                )
                lv.setOnItemClickListener { _, _, pos, _ ->
                    lv.visibility = View.GONE
                    hideSearchKeyboard()
                    onPick(pois[pos])
                }
                lv.visibility = View.VISIBLE
            }
        }
    }

    private fun hideSearchKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(findViewById<EditText>(R.id.search_input).windowToken, 0)
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

        val stepJitterSwitch = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.step_jitter_switch)
        stepJitterSwitch.isChecked = prefs.getBoolean("step_jitter", true)
        stepJitterSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("step_jitter", checked).apply()
        }

        findViewById<Button>(R.id.btn_check_update).setOnClickListener {
            val version = currentVersion()
            val repo = BuildConfig.GITHUB_REPO
            if (repo.isBlank()) {
                // 作者尚未配置发布仓库:保持纯本地文案
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(getString(R.string.settings_update))
                    .setMessage(getString(R.string.update_latest, version))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return@setOnClickListener
            }
            Toast.makeText(this, R.string.update_checking, Toast.LENGTH_SHORT).show()
            UpdateChecker.check(repo, version) { result ->
                runOnUiThread {
                    when {
                        !result.ok ->
                            androidx.appcompat.app.AlertDialog.Builder(this)
                                .setTitle(R.string.update_failed_title)
                                .setMessage(getString(R.string.update_failed, result.error ?: ""))
                                .setPositiveButton(android.R.string.ok, null)
                                .show()
                        result.hasUpdate -> {
                            val notes = result.notes.take(400)
                            androidx.appcompat.app.AlertDialog.Builder(this)
                                .setTitle(getString(R.string.update_available_title, result.latestVersion))
                                .setMessage(
                                    if (notes.isBlank()) getString(R.string.update_goto_page)
                                    else notes + "\n\n" + getString(R.string.update_goto_page)
                                )
                                .setPositiveButton(R.string.btn_goto_download) { _, _ ->
                                    runCatching {
                                        startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(result.apkUrl.ifBlank { result.releasesUrl }))
                                        )
                                    }
                                }
                                .setNegativeButton(android.R.string.cancel, null)
                                .show()
                        }
                        else ->
                            androidx.appcompat.app.AlertDialog.Builder(this)
                                .setTitle(getString(R.string.settings_update))
                                .setMessage(getString(R.string.update_none, version))
                                .setPositiveButton(android.R.string.ok, null)
                                .show()
                    }
                }
            }
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

    /** 高德个人 Key 区块:显示包名+本机SHA1(均可复制),保存用户Key,公共额度状态 */
    private fun setupAmapKeySection() {
        val prefs = prefs()
        findViewById<TextView>(R.id.sha1_value).text = apkSha1()
        findViewById<TextView>(R.id.key_status).text =
            if (hasUserAmapKey()) getString(R.string.key_status_personal)
            else getString(R.string.key_status_public, prefs.getInt("launch_count", 0))
        findViewById<EditText>(R.id.user_amap_key_input).setText(prefs.getString("user_amap_key", ""))

        fun copyToClipboard(label: String, value: String) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, value))
            Toast.makeText(this, getString(R.string.sha1_copied), Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btn_copy_sha1).setOnClickListener {
            copyToClipboard("SHA1", apkSha1())
        }
        findViewById<Button>(R.id.btn_copy_pkg).setOnClickListener {
            copyToClipboard("package", packageName)
        }
        findViewById<Button>(R.id.btn_open_amap).setOnClickListener {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://lbs.amap.com")))
            }
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

    /** 公共 Key 额度门禁: 仅当构建期 KEY_ENFORCE=true(分发版)时生效;开发构建零限制 */
    private fun maybeShowKeyNag() {
        if (!BuildConfig.KEY_ENFORCE || hasUserAmapKey()) return
        val count = prefs().getInt("launch_count", 0)
        if (count <= 10) return
        if (quotaBlocked()) {
            showKeyQuotaDialog()
        } else {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.nag_title)
                .setMessage(getString(R.string.nag_msg, count))
                .setPositiveButton(R.string.btn_open_dev) { _, _ -> goConfigTab() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /** 门禁条件: 构建期开关打开 + 未配置个人 Key + 启动超 10 次 */
    private fun quotaBlocked(): Boolean =
        BuildConfig.KEY_ENFORCE && !hasUserAmapKey() && prefs().getInt("launch_count", 0) > 10

    private fun showKeyQuotaDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.key_quota_title)
            .setMessage(getString(R.string.key_quota_msg))
            .setCancelable(false)
            .setPositiveButton(R.string.quota_goto_config) { d, _ ->
                d.dismiss()
                goConfigTab()
            }
            .show()
    }

    private fun goConfigTab() {
        currentTab = TAB_SETTINGS
        findViewById<BottomNavigationView>(R.id.bottom_nav).selectedItemId = R.id.nav_settings
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

    /** 应用定位:把屏幕中心大头针处设为模拟点并(重)启动注入;运行中拖动地图时静默挪点 */
    private fun applyLocationAtCenter(announce: Boolean) {
        val target = aMap?.cameraPosition?.target ?: return
        val wgs = CoordinateConverter.gcj02ToWgs84(target.latitude, target.longitude)
        val a = GeoPoint(wgs.lat, wgs.lng)
        anchor = a
        saveAnchor(a)

        // 单点改 2m 微动环:让融合定位(高德等)持续看到 GPS 位移,否则蓝点会被真位置拉回
        val loop = tinyLoopAround(a)
        val pts = DoubleArray(loop.size * 2).also { arr ->
            loop.forEachIndexed { i, p ->
                arr[i * 2] = p.lat
                arr[i * 2 + 1] = p.lng
            }
        }
        feedCount = 0
        MockLocationService.start(
            this,
            getString(R.string.notif_default_name),
            speedForPointMode(),
            pts,
            wobble = prefs().getBoolean("wobble", true),
            stepJitter = prefs().getBoolean("step_jitter", true),
        )
        if (announce) {
            stopTicker()
            uiHandler.post(ticker)
        }
    }

    /** 原地微动环用慢速(蓝点停在目标附近微微画圈) */
    private fun speedForPointMode(): Double = speedMps().coerceIn(0.1, 0.4)

    private fun tinyLoopAround(p: GeoPoint): List<GeoPoint> {
        val r = 2.0
        val n = 12
        val latRad = Math.toRadians(p.lat)
        val out = ArrayList<GeoPoint>(n + 1)
        for (i in 0..n) {
            val a = 2 * Math.PI * i / n
            out.add(
                GeoPoint(
                    p.lat + RoutePlayer.metersToDegLat(r * Math.sin(a)),
                    p.lng + RoutePlayer.metersToDegLng(r * Math.cos(a), latRad)
                )
            )
        }
        return out
    }

    /** 路线 Tab 回放:跑当前装填的自定义路线 */
    private fun doStart() {
        // release 版公共额度门禁:未配置个人 Key 时拦截(专业口径见弹窗文案)
        if (quotaBlocked()) {
            showKeyQuotaDialog()
            return
        }
        val r = customRoute
        if (r == null) {
            Toast.makeText(this, R.string.toast_no_custom_route, Toast.LENGTH_SHORT).show()
            return
        }
        val pts = DoubleArray(r.points.size * 2)
        r.points.forEachIndexed { i, p ->
            pts[i * 2] = p.lat
            pts[i * 2 + 1] = p.lng
        }
        feedCount = 0
        drawRouteOnMap(r.points)   // 开跑瞬间把路线画上屏,不依赖切换页面的时机
        fitRouteOnScreen(r.points) // 整条线框进屏幕(参考实现同款);回放中镜头不动,看小点沿线跑
        MockLocationService.start(
            this,
            r.name,
            speedMps(),
            pts,
            wobble = prefs().getBoolean("wobble", true),
            loopClosed = r.loop,
            finishPause = prefs().getBoolean("finish_pause", false),
            stepJitter = prefs().getBoolean("step_jitter", true),
        )
        Toast.makeText(this, R.string.toast_started, Toast.LENGTH_SHORT).show()
        stopTicker()
        uiHandler.post(ticker)
    }

    private fun speedMps(): Double {
        val seek = findViewById<SeekBar>(R.id.speed_seek)
        return (seek.progress + 1) / 10.0 // 0.1 ~ 10.0 m/s
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
            applyRunVisual(true)
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
        private const val TICK_MS = 100L // 对齐参考实现的 10Hz 回放节奏
        private const val SPLASH_MAX_MS = 4000L
        private const val TAB_LOCATION = 0
        private const val TAB_ROUTE = 1
        private const val TAB_LIBRARY = 2
        private const val TAB_SETTINGS = 3
    }
}
