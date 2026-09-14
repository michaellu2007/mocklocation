package com.learning.mockrun

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var latInput: EditText
    private lateinit var lonInput: EditText
    private lateinit var rbAmap: RadioButton
    private lateinit var rbBaidu: RadioButton
    private var pendingStart = false

    private val pickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val data = result.data ?: return@registerForActivityResult
                val lat = data.getDoubleExtra(PickerContract.EXTRA_LAT, Double.NaN)
                val lng = data.getDoubleExtra(PickerContract.EXTRA_LNG, Double.NaN)
                if (!lat.isNaN() && !lng.isNaN()) {
                    latInput.setText("%.6f".format(lat))
                    lonInput.setText("%.6f".format(lng))
                    refreshStatus()
                }
            }
        }

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val fineGranted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
            val coarseGranted = grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (fineGranted && coarseGranted) {
                requestNotificationPermissionIfNeeded()
                if (pendingStart) {
                    pendingStart = false
                    startMock()
                }
            } else {
                Toast.makeText(this, R.string.toast_need_perm, Toast.LENGTH_LONG).show()
            }
        }

    /** API 33+ 才需要;拿不到也不阻塞启动,只是常驻通知不显示。 */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        latInput = findViewById(R.id.input_lat)
        lonInput = findViewById(R.id.input_lon)
        rbAmap = findViewById(R.id.rb_amap)
        rbBaidu = findViewById(R.id.rb_baidu)

        // 地图引擎选择:持久化 + 切换即生效
        when (MapEngine.load(this)) {
            MapEngine.AMAP -> rbAmap.isChecked = true
            MapEngine.BAIDU -> rbBaidu.isChecked = true
        }
        findViewById<RadioGroup>(R.id.engine_group)
            .setOnCheckedChangeListener { _, checkedId ->
                val engine = if (checkedId == R.id.rb_baidu) MapEngine.BAIDU else MapEngine.AMAP
                MapEngine.save(this, engine)
                Toast.makeText(
                    this,
                    getString(R.string.toast_engine_switched, engine.displayName),
                    Toast.LENGTH_SHORT
                ).show()
            }

        findViewById<Button>(R.id.btn_pick).setOnClickListener { openPicker() }
        findViewById<Button>(R.id.btn_open_dev).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        }
        findViewById<Button>(R.id.btn_start).setOnClickListener { startMock() }
        findViewById<Button>(R.id.btn_stop).setOnClickListener {
            MockLocationService.stop(this)
            Toast.makeText(this, R.string.toast_stopped, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    /** 按当前选中的引擎打开选点页;未接入的引擎给出提示而不是崩。 */
    private fun openPicker() {
        val engine = MapEngine.load(this)
        if (!engine.available) {
            Toast.makeText(
                this,
                getString(R.string.toast_engine_pending, engine.displayName),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        pickerLauncher.launch(Intent(this, AMapPickerActivity::class.java))
    }

    private fun refreshStatus() {
        val granted = if (isMockLocationAllowed()) "已授权" else "未授权"
        statusText.text = getString(R.string.phase1_status, packageName, granted)
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
        val lat = latInput.text.toString().toDoubleOrNull()
        val lon = lonInput.text.toString().toDoubleOrNull()
        if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            Toast.makeText(this, R.string.toast_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        requestNotificationPermissionIfNeeded()
        MockLocationService.start(this, lat, lon)
        Toast.makeText(
            this,
            getString(R.string.toast_started, "%.5f".format(lat), "%.5f".format(lon)),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun hasLocationPermission(): Boolean {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * 检查本应用是否被允许模拟位置。
     * 不用 Settings.Secure.ALLOW_MOCK_LOCATION:那是全机全局开关且 API 23 起废弃,
     * AppOps 按 uid/包名判断,是权威口径。
     * API 29 起 checkOpNoThrow 废弃,改用 unsafeCheckOpNoThrow。
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
}
