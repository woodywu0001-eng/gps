package com.gpssim.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.gpssim.app.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private enum class UiMode { FIXED, ROUTE }

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: AppStore

    private var uiMode = UiMode.FIXED
    private var target: LatLng? = null
    private val waypoints = mutableListOf<LatLng>()
    private var speedKmh = 5.0
    private var loopMode = LoopMode.ONCE
    private var simState = SimState()

    private lateinit var targetMarker: Marker
    private lateinit var simMarker: Marker
    private lateinit var routeLine: Polyline
    private val waypointFolder = FolderOverlay()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.ACCESS_FINE_LOCATION] == false) {
                toast(R.string.msg_need_location_permission)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        Configuration.getInstance().apply {
            load(applicationContext, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(osmdroidBasePath, "tiles")
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = AppStore(this)
        speedKmh = store.speedKmh
        loopMode = store.loopMode

        ViewCompat.setOnApplyWindowInsetsListener(binding.overlay) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        setupMap()
        setupControls()
        requestBasicPermissions()
        observeSimulation()
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
    }

    override fun onPause() {
        val center = binding.map.mapCenter
        store.mapCenter = LatLng(center.latitude, center.longitude)
        store.mapZoom = binding.map.zoomLevelDouble
        binding.map.onPause()
        super.onPause()
    }

    // ---------------------------------------------------------------------------------------------
    // 地圖

    private fun setupMap() {
        val map = binding.map
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.controller.setZoom(store.mapZoom)
        val center = store.mapCenter ?: LatLng(25.033964, 121.564468) // 預設：台北 101
        map.controller.setCenter(center.toGeo())

        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                onMapTap(p.toLatLng())
                return true
            }

            override fun longPressHelper(p: GeoPoint): Boolean {
                onMapLongPress(p.toLatLng())
                return true
            }
        }))

        routeLine = Polyline(map).apply {
            outlinePaint.color = ContextCompat.getColor(this@MainActivity, R.color.route)
            outlinePaint.strokeWidth = 10f
            // 不攔截點擊，讓路徑附近仍可新增路徑點
            setOnClickListener { _, _, _ -> false }
        }
        map.overlays.add(routeLine)
        map.overlays.add(waypointFolder)

        targetMarker = Marker(map).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            isEnabled = false
            setOnMarkerClickListener { _, _ -> true }
        }
        map.overlays.add(targetMarker)

        simMarker = Marker(map).apply {
            icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_sim_dot)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            isEnabled = false
            setOnMarkerClickListener { _, _ -> true }
        }
        map.overlays.add(simMarker)
    }

    private fun onMapTap(p: LatLng) {
        when (uiMode) {
            UiMode.FIXED -> setTarget(p)
            UiMode.ROUTE -> addWaypoint(p)
        }
    }

    private fun onMapLongPress(p: LatLng) {
        val options = arrayOf("設為目標點", "加入路徑點", "加入常用座標", "立即瞬移到此")
        MaterialAlertDialogBuilder(this)
            .setTitle(p.format())
            .setItems(options) { _, which ->
                when (which) {
                    0 -> { setUiMode(UiMode.FIXED); setTarget(p) }
                    1 -> { setUiMode(UiMode.ROUTE); addWaypoint(p) }
                    2 -> promptSaveFavorite(p)
                    3 -> teleport(p)
                }
            }
            .show()
    }

    private fun setTarget(p: LatLng) {
        target = p
        targetMarker.position = p.toGeo()
        targetMarker.isEnabled = uiMode == UiMode.FIXED
        binding.tvTarget.text = "目標：${p.format()}"
        binding.map.invalidate()
        renderButtons()
    }

    private fun addWaypoint(p: LatLng) {
        waypoints += p
        refreshRoute()
    }

    private fun refreshRoute() {
        val map = binding.map
        routeLine.setPoints(waypoints.map { it.toGeo() })
        waypointFolder.items.toList().forEach { waypointFolder.remove(it) }
        waypoints.forEachIndexed { index, p ->
            val marker = Marker(map).apply {
                position = p.toGeo()
                icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_waypoint)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setOnMarkerClickListener { _, _ ->
                    confirmRemoveWaypoint(index)
                    true
                }
            }
            waypointFolder.add(marker)
        }

        binding.tvRouteInfo.text = if (waypoints.isEmpty()) {
            getString(R.string.hint_tap_route)
        } else {
            val length = GeoUtils.pathLength(waypoints)
            val eta = if (speedKmh > 0) length / (speedKmh / 3.6) else 0.0
            "路徑點 ${waypoints.size} 個 · 單程 ${GeoUtils.formatDistance(length)} · 約 ${GeoUtils.formatDuration(eta)}"
        }
        map.invalidate()
    }

    private fun confirmRemoveWaypoint(index: Int) {
        if (index !in waypoints.indices) return
        MaterialAlertDialogBuilder(this)
            .setTitle("路徑點 ${index + 1}")
            .setMessage(waypoints[index].format())
            .setPositiveButton(R.string.delete) { _, _ ->
                if (index in waypoints.indices) {
                    waypoints.removeAt(index)
                    refreshRoute()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun centerOn(p: LatLng) {
        binding.map.controller.animateTo(p.toGeo())
    }

    // ---------------------------------------------------------------------------------------------
    // 控制項

    private fun setupControls() {
        binding.modeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) setUiMode(if (checkedId == R.id.btnModeRoute) UiMode.ROUTE else UiMode.FIXED)
        }

        binding.btnInputCoord.setOnClickListener { showInputCoordinateDialog() }
        binding.btnSaveFavorite.setOnClickListener {
            val p = target ?: simState.position
            if (p == null) toast(R.string.msg_pick_target_first) else promptSaveFavorite(p)
        }
        binding.btnFavorites.setOnClickListener { showFavoritesDialog() }
        binding.btnRouteFavorites.setOnClickListener { showFavoritesDialog() }

        binding.btnUndo.setOnClickListener {
            if (waypoints.isNotEmpty()) {
                waypoints.removeAt(waypoints.lastIndex)
                refreshRoute()
            }
        }
        binding.btnClearRoute.setOnClickListener {
            if (waypoints.isEmpty()) return@setOnClickListener
            MaterialAlertDialogBuilder(this)
                .setMessage("確定清除所有路徑點？")
                .setPositiveButton(R.string.clear) { _, _ ->
                    waypoints.clear()
                    refreshRoute()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        binding.btnSaveRoute.setOnClickListener { promptSaveRoute() }
        binding.btnLoadRoute.setOnClickListener { showRoutesDialog() }

        binding.sliderSpeed.value = sliderValue(speedKmh)
        binding.sliderSpeed.setLabelFormatter { String.format(Locale.US, "%.1f km/h", it) }
        binding.sliderSpeed.addOnChangeListener { _, value, fromUser ->
            if (fromUser) setSpeed(value.toDouble())
        }
        binding.tvSpeed.setOnClickListener { showSpeedInputDialog() }
        binding.chipWalk.setOnClickListener { setSpeed(5.0) }
        binding.chipBrisk.setOnClickListener { setSpeed(7.0) }
        binding.chipRun.setOnClickListener { setSpeed(10.0) }
        binding.chipBike.setOnClickListener { setSpeed(18.0) }
        binding.chipCar.setOnClickListener { setSpeed(50.0) }
        updateSpeedLabel()

        binding.loopToggle.check(
            when (loopMode) {
                LoopMode.ONCE -> R.id.btnLoopOnce
                LoopMode.LOOP -> R.id.btnLoopLoop
                LoopMode.ROUND_TRIP -> R.id.btnLoopRoundTrip
            },
        )
        binding.loopToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            loopMode = when (checkedId) {
                R.id.btnLoopLoop -> LoopMode.LOOP
                R.id.btnLoopRoundTrip -> LoopMode.ROUND_TRIP
                else -> LoopMode.ONCE
            }
            store.loopMode = loopMode
        }

        binding.btnStart.setOnClickListener { startSimulation() }
        binding.btnPause.setOnClickListener {
            if (simState.paused) MockLocationService.resume(this) else MockLocationService.pause(this)
        }
        binding.btnStop.setOnClickListener { MockLocationService.stop(this) }
        binding.btnLocate.setOnClickListener {
            val p = simState.position ?: target
            if (p == null) toast(R.string.msg_no_position) else centerOn(p)
        }
        binding.btnMore.setOnClickListener { showMoreMenu(it) }

        renderButtons()
    }

    private fun setUiMode(mode: UiMode) {
        uiMode = mode
        val checkedId = if (mode == UiMode.ROUTE) R.id.btnModeRoute else R.id.btnModeFixed
        if (binding.modeToggle.checkedButtonId != checkedId) binding.modeToggle.check(checkedId)
        binding.panelFixed.visibility = if (mode == UiMode.FIXED) View.VISIBLE else View.GONE
        binding.panelRoute.visibility = if (mode == UiMode.ROUTE) View.VISIBLE else View.GONE
        targetMarker.isEnabled = mode == UiMode.FIXED && target != null
        routeLine.isEnabled = mode == UiMode.ROUTE
        waypointFolder.isEnabled = mode == UiMode.ROUTE
        binding.map.invalidate()
        renderButtons()
    }

    private fun setSpeed(kmh: Double) {
        speedKmh = kmh.coerceIn(MIN_SPEED, MAX_SPEED)
        store.speedKmh = speedKmh
        binding.sliderSpeed.value = sliderValue(speedKmh)
        updateSpeedLabel()
        refreshRoute()
        if (simState.running && simState.mode == SimMode.ROUTE) {
            MockLocationService.setSpeed(this, speedKmh)
        }
    }

    private fun sliderValue(kmh: Double): Float =
        ((kmh * 2).roundToInt() / 2.0).coerceIn(MIN_SPEED, MAX_SPEED).toFloat()

    private fun updateSpeedLabel() {
        binding.tvSpeed.text = String.format(Locale.US, "%.1f km/h", speedKmh)
    }

    // ---------------------------------------------------------------------------------------------
    // 模擬控制

    private fun startSimulation() {
        if (!hasLocationPermission()) {
            requestBasicPermissions()
            toast(R.string.msg_need_location_permission)
            return
        }
        if (!MockLocationProvider.isMockAppSelected(this)) {
            showMockSetupDialog()
            return
        }
        when (uiMode) {
            UiMode.FIXED -> {
                val p = target ?: return toast(R.string.msg_pick_target_first)
                MockLocationService.startFixed(this, p)
            }

            UiMode.ROUTE -> {
                if (waypoints.size < 2) return toast(R.string.msg_need_two_points)
                MockLocationService.startRoute(this, RouteConfig(waypoints.toList(), speedKmh, loopMode))
            }
        }
    }

    private fun teleport(p: LatLng) {
        setUiMode(UiMode.FIXED)
        setTarget(p)
        centerOn(p)
        startSimulation()
    }

    private fun observeSimulation() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                MockLocationService.state.collect { render(it) }
            }
        }
    }

    private fun render(s: SimState) {
        simState = s

        binding.tvStatus.text = when {
            !s.running -> getString(R.string.status_idle)
            s.mode == SimMode.FIXED -> "定點模擬中：${s.position?.format().orEmpty()}"
            else -> buildString {
                append(String.format(Locale.US, "步行 %.1f km/h · ", s.speedKmh))
                append(GeoUtils.formatDistance(s.travelled))
                if (s.loopMode == LoopMode.ONCE) append(" / ").append(GeoUtils.formatDistance(s.pathLength))
                when {
                    s.finished -> append("（已抵達終點）")
                    s.paused -> append("（已暫停）")
                }
            }
        }

        val pos = s.position
        if (s.running && pos != null) {
            simMarker.position = pos.toGeo()
            simMarker.isEnabled = true
        } else {
            simMarker.isEnabled = false
        }
        binding.map.invalidate()
        renderButtons()

        s.error?.let {
            MockLocationService.clearError()
            when (it) {
                SimError.NOT_MOCK_APP -> showMockSetupDialog()
                SimError.NO_PERMISSION -> showMessage(R.string.dlg_permission_title, R.string.dlg_permission_message)
                SimError.OTHER -> showMessage(R.string.app_name, R.string.dlg_error_message)
            }
        }
    }

    private fun renderButtons() {
        if (!::binding.isInitialized) return
        val s = simState
        binding.btnStart.setText(
            when {
                s.running && uiMode == UiMode.FIXED && s.mode == SimMode.FIXED -> R.string.move_to_target
                s.running && uiMode == UiMode.ROUTE && s.mode == SimMode.ROUTE -> R.string.restart_route
                else -> R.string.start
            },
        )
        binding.btnPause.isEnabled = s.running && s.mode == SimMode.ROUTE && !s.finished
        binding.btnPause.setText(if (s.paused) R.string.action_resume else R.string.action_pause)
        binding.btnStop.isEnabled = s.running
    }

    // ---------------------------------------------------------------------------------------------
    // 常用座標

    private fun promptSaveFavorite(p: LatLng) {
        val default = "地點 ${store.favorites().size + 1}"
        textInputDialog("加入常用座標", p.format(), default) { name ->
            store.addFavorite(name.ifBlank { default }, p)
            toast(R.string.msg_saved)
        }
    }

    private fun showFavoritesDialog() {
        val favorites = store.favorites()
        if (favorites.isEmpty()) return toast(R.string.msg_no_favorites)
        val items = favorites.map { "${it.name}\n${it.point.format()}" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.favorites)
            .setItems(items) { _, which -> showFavoriteActions(favorites[which]) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showFavoriteActions(fav: Favorite) {
        val options = arrayOf("立即瞬移到此", "設為目標點", "加入路徑點", "重新命名", "刪除")
        MaterialAlertDialogBuilder(this)
            .setTitle(fav.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> teleport(fav.point)
                    1 -> {
                        setUiMode(UiMode.FIXED)
                        setTarget(fav.point)
                        centerOn(fav.point)
                    }
                    2 -> {
                        setUiMode(UiMode.ROUTE)
                        addWaypoint(fav.point)
                        centerOn(fav.point)
                    }
                    3 -> textInputDialog("重新命名", null, fav.name) { name ->
                        if (name.isNotBlank()) store.renameFavorite(fav.id, name)
                    }
                    4 -> {
                        store.deleteFavorite(fav.id)
                        toast(R.string.msg_deleted)
                    }
                }
            }
            .show()
    }

    private fun showInputCoordinateDialog() {
        textInputDialog("輸入座標", "格式：緯度, 經度（例如 25.033964, 121.564468）", "") { text ->
            val p = parseLatLng(text) ?: return@textInputDialog toast(R.string.msg_invalid_coord)
            when (uiMode) {
                UiMode.FIXED -> setTarget(p)
                UiMode.ROUTE -> addWaypoint(p)
            }
            centerOn(p)
        }
    }

    private fun parseLatLng(text: String): LatLng? {
        val parts = text.trim().split(Regex("[,\\s，]+")).filter { it.isNotEmpty() }
        if (parts.size != 2) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lng = parts[1].toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        return LatLng(lat, lng)
    }

    // ---------------------------------------------------------------------------------------------
    // 常用路徑

    private fun promptSaveRoute() {
        if (waypoints.size < 2) return toast(R.string.msg_need_two_points)
        val default = "路徑 ${store.routes().size + 1}"
        textInputDialog("儲存路徑", null, default) { name ->
            store.addRoute(name.ifBlank { default }, RouteConfig(waypoints.toList(), speedKmh, loopMode))
            toast(R.string.msg_saved)
        }
    }

    private fun showRoutesDialog() {
        val routes = store.routes()
        if (routes.isEmpty()) return toast(R.string.msg_no_routes)
        val items = routes.map {
            val length = GeoUtils.formatDistance(GeoUtils.pathLength(it.config.points))
            "${it.name}\n${it.config.points.size} 點 · $length · ${String.format(Locale.US, "%.1f", it.config.speedKmh)} km/h"
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("已儲存的路徑")
            .setItems(items) { _, which ->
                val route = routes[which]
                MaterialAlertDialogBuilder(this)
                    .setTitle(route.name)
                    .setItems(arrayOf("載入", "載入並開始模擬", "刪除")) { _, action ->
                        when (action) {
                            0 -> loadRoute(route)
                            1 -> { loadRoute(route); startSimulation() }
                            2 -> {
                                store.deleteRoute(route.id)
                                toast(R.string.msg_deleted)
                            }
                        }
                    }
                    .show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun loadRoute(route: SavedRoute) {
        setUiMode(UiMode.ROUTE)
        waypoints.clear()
        waypoints.addAll(route.config.points)
        loopMode = route.config.loopMode
        store.loopMode = loopMode
        binding.loopToggle.check(
            when (loopMode) {
                LoopMode.ONCE -> R.id.btnLoopOnce
                LoopMode.LOOP -> R.id.btnLoopLoop
                LoopMode.ROUND_TRIP -> R.id.btnLoopRoundTrip
            },
        )
        setSpeed(route.config.speedKmh)
        refreshRoute()
        binding.map.post {
            val box = BoundingBox.fromGeoPoints(waypoints.map { it.toGeo() })
            binding.map.zoomToBoundingBox(box.increaseByScale(1.3f), true)
        }
    }

    private fun showSpeedInputDialog() {
        textInputDialog(
            "設定速度（km/h）",
            "範圍 ${MIN_SPEED} ~ ${MAX_SPEED.toInt()} km/h",
            String.format(Locale.US, "%.1f", speedKmh),
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL,
        ) { text ->
            text.trim().toDoubleOrNull()?.let { setSpeed(it) }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 設定與權限

    private fun showMoreMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menuInflater.inflate(R.menu.menu_more, menu)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.menu_dev_settings -> openDeveloperSettings()
                    R.id.menu_battery -> requestIgnoreBatteryOptimization()
                    R.id.menu_help -> showMessage(R.string.dlg_help_title, R.string.dlg_help_message)
                }
                true
            }
        }.show()
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestBasicPermissions() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms += Manifest.permission.POST_NOTIFICATIONS
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    private fun showMockSetupDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dlg_mock_title)
            .setMessage(R.string.dlg_mock_message)
            .setPositiveButton(R.string.dlg_open_dev_settings) { _, _ -> openDeveloperSettings() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openDeveloperSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "請先在「關於手機」連點版本號碼 7 次啟用開發者選項", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimization() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return toast(R.string.msg_battery_ok)
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
            )
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 小工具

    private fun textInputDialog(
        title: String,
        message: String?,
        initial: String,
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        onOk: (String) -> Unit,
    ) {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val edit = EditText(this).apply {
            setText(initial)
            setSelectAllOnFocus(true)
            this.inputType = inputType
            isSingleLine = true
        }
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(edit)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .apply { if (message != null) setMessage(message) }
            .setView(container)
            .setPositiveButton(R.string.ok) { _, _ -> onOk(edit.text.toString()) }
            .setNegativeButton(R.string.cancel, null)
            .show()
        edit.requestFocus()
    }

    private fun showMessage(titleRes: Int, messageRes: Int) {
        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setMessage(messageRes)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()

    private fun LatLng.toGeo() = GeoPoint(lat, lng)
    private fun GeoPoint.toLatLng() = LatLng(latitude, longitude)

    companion object {
        private const val MIN_SPEED = 0.5
        private const val MAX_SPEED = 120.0
    }
}
