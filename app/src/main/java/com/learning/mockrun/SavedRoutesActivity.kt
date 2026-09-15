package com.learning.mockrun

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File

/**
 * 我的路线:点按条目 → 使用/导出/删除;「导入」读取导出的 JSON 文件。
 * 使用 = 返回主界面并装填该路线。
 */
class SavedRoutesActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private var routes: List<RouteStore.SavedRoute> = emptyList()
    private var pendingExport: RouteStore.SavedRoute? = null

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val route = pendingExport
            if (uri != null && route != null) {
                runCatching {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(RouteStore.encode(route).toByteArray(Charsets.UTF_8))
                    }
                }.onSuccess {
                    Toast.makeText(this, getString(R.string.routes_exported, route.name), Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(this, getString(R.string.import_failed, it.message), Toast.LENGTH_LONG).show()
                }
            }
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                runCatching {
                    contentResolver.openInputStream(uri)?.use { ins ->
                        ins.readBytes().toString(Charsets.UTF_8)
                    }
                }.getOrNull()?.let { text ->
                    val route = RouteStore.parseRoute(text)
                    if (route == null) {
                        Toast.makeText(this, getString(R.string.import_failed, "格式不识别"), Toast.LENGTH_LONG).show()
                    } else {
                        RouteStore.add(this, route)
                        Toast.makeText(this, getString(R.string.routes_imported, route.name), Toast.LENGTH_SHORT).show()
                        refresh()
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_saved_routes)

        listView = findViewById(R.id.routes_list)
        findViewById<Button>(R.id.btn_import).setOnClickListener {
            importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
        }
        listView.setOnItemClickListener { _, _, pos, _ -> showActions(routes[pos]) }
        refresh()
    }

    private fun refresh() {
        routes = RouteStore.list(this)
        if (routes.isEmpty()) {
            listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, listOf(getString(R.string.routes_empty)))
            listView.isEnabled = false
            return
        }
        listView.isEnabled = true
        listView.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            routes.map { r ->
                val len = r.points.zipWithNext { a, b -> RoutePlayer.haversine(a, b) }.sum() +
                    (if (r.loop) RoutePlayer.haversine(r.points.first(), r.points.last()) else 0.0)
                val tag = if (r.loop) "环线" else "往返"
                getString(R.string.routes_count_fmt, r.name, r.points.size, len, tag)
            }
        )
    }

    private fun showActions(route: RouteStore.SavedRoute) {
        val actions = arrayOf(
            getString(R.string.act_use),
            getString(R.string.act_rename),
            getString(R.string.act_share),
            getString(R.string.act_export),
            getString(R.string.act_delete)
        )
        AlertDialog.Builder(this)
            .setTitle(route.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> useRoute(route)
                    1 -> showRenameDialog(route)
                    2 -> shareRoute(route)
                    3 -> exportRoute(route)
                    4 -> confirmDelete(route)
                }
            }
            .show()
    }

    private fun showRenameDialog(route: RouteStore.SavedRoute) {
        val input = android.widget.EditText(this).apply {
            setText(route.name)
            setSelection(route.name.length)
        }
        val container = android.widget.FrameLayout(this).apply {
            setPadding(60, 20, 60, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.routes_rename_title)
            .setView(container)
            .setPositiveButton(R.string.act_rename) { _, _ ->
                val newName = input.text.toString().trim()
                when {
                    newName.isEmpty() ->
                        Toast.makeText(this, R.string.routes_rename_empty, Toast.LENGTH_SHORT).show()
                    RouteStore.rename(this, route.name, newName) -> {
                        // 若改名的是"上次使用的路线",同步持久化里的名字
                        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
                        prefs.getString("last_route", null)?.let { s ->
                            RouteStore.parseRoute(s)?.takeIf { it.name == route.name }?.let { r ->
                                prefs.edit()
                                    .putString("last_route", RouteStore.encode(r.copy(name = newName)))
                                    .apply()
                            }
                        }
                        Toast.makeText(this, getString(R.string.routes_renamed, newName), Toast.LENGTH_SHORT).show()
                        refresh()
                    }
                    else ->
                        Toast.makeText(this, getString(R.string.routes_rename_dup, newName), Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * 一键分享路线文件(收集入口):写进 cache/share 后走系统分享面板,
     * 用户直接选微信/QQ 发给作者即可,不用先导出再翻文件。
     */
    private fun shareRoute(route: RouteStore.SavedRoute) {
        val safeName = route.name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val dir = File(cacheDir, "share").apply { mkdirs() }
        val file = File(dir, safeName + ".json")
        file.writeText(RouteStore.encode(route))

        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, route.name)
            clipData = ClipData.newRawUri(route.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.routes_share_chooser)))
    }

    private fun useRoute(route: RouteStore.SavedRoute) {
        val arr = DoubleArray(route.points.size * 2)
        route.points.forEachIndexed { i, p ->
            arr[i * 2] = p.lat
            arr[i * 2 + 1] = p.lng
        }
        setResult(
            RESULT_OK,
            Intent()
                .putExtra(PickerContract.EXTRA_ROUTE_PTS, arr)
                .putExtra(PickerContract.EXTRA_ROUTE_NAME, route.name)
                .putExtra(PickerContract.EXTRA_ROUTE_LOOP, route.loop)
        )
        finish()
    }

    private fun exportRoute(route: RouteStore.SavedRoute) {
        pendingExport = route
        @Suppress("DEPRECATION")
        exportLauncher.launch(route.name + ".json")
    }

    private fun confirmDelete(route: RouteStore.SavedRoute) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.routes_delete_confirm, route.name))
            .setPositiveButton(R.string.act_delete) { _, _ ->
                RouteStore.remove(this, route.name)
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
