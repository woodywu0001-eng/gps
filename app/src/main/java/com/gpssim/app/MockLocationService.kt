package com.gpssim.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class SimMode { FIXED, ROUTE }

data class SimState(
    val running: Boolean = false,
    val mode: SimMode? = null,
    val paused: Boolean = false,
    val position: LatLng? = null,
    val bearing: Float = 0f,
    val speedKmh: Double = 0.0,
    val travelled: Double = 0.0,
    val pathLength: Double = 0.0,
    val loopMode: LoopMode = LoopMode.ONCE,
    val finished: Boolean = false,
    val error: SimError? = null,
)

enum class SimError { NOT_MOCK_APP, NO_PERMISSION, OTHER }

/**
 * 前景服務：持續將模擬座標推送給系統，App 退到背景或螢幕關閉時仍維持模擬。
 */
class MockLocationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tickJob: Job? = null
    private var provider: MockLocationProvider? = null
    private var walker: RouteWalker? = null
    private var paused = false
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var session: SessionStore

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        session = SessionStore(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!startInForeground()) return START_NOT_STICKY

        when (intent?.action) {
            ACTION_START_FIXED -> {
                val point = LatLng(intent.getDoubleExtra(EXTRA_LAT, 0.0), intent.getDoubleExtra(EXTRA_LNG, 0.0))
                session.saveFixed(point)
                runFixed(point)
            }

            ACTION_START_ROUTE -> {
                val lats = intent.getDoubleArrayExtra(EXTRA_LATS) ?: DoubleArray(0)
                val lngs = intent.getDoubleArrayExtra(EXTRA_LNGS) ?: DoubleArray(0)
                val config = RouteConfig(
                    points = lats.indices.map { LatLng(lats[it], lngs[it]) },
                    speedKmh = intent.getDoubleExtra(EXTRA_SPEED, 5.0),
                    loopMode = runCatching { LoopMode.valueOf(intent.getStringExtra(EXTRA_LOOP)!!) }
                        .getOrDefault(LoopMode.ONCE),
                )
                if (config.points.size < 2) {
                    fail(SimError.OTHER)
                } else {
                    session.saveRoute(config)
                    runRoute(config, 0.0)
                }
            }

            ACTION_PAUSE -> setPaused(true)
            ACTION_RESUME -> setPaused(false)
            ACTION_SET_SPEED -> setSpeed(intent.getDoubleExtra(EXTRA_SPEED, 5.0))

            ACTION_STOP -> {
                stopSimulation(clearSession = true)
                return START_NOT_STICKY
            }

            else -> {
                // 服務被系統回收後重新啟動（START_STICKY），接續上次的模擬
                if (tickJob == null) {
                    when (val s = session.load()) {
                        is SessionStore.Session.Fixed -> runFixed(s.point)
                        is SessionStore.Session.Route -> runRoute(s.config, s.travelled)
                        null -> stopSimulation(clearSession = true)
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        tickJob?.cancel()
        provider?.disable()
        provider = null
        releaseWakeLock()
        if (_state.value.running) _state.update { it.copy(running = false, paused = false) }
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------------

    private fun runFixed(point: LatLng) {
        if (!ensureProvider()) return
        tickJob?.cancel()
        walker = null
        paused = false
        acquireWakeLock()
        _state.value = SimState(running = true, mode = SimMode.FIXED, position = point)
        updateNotification()

        tickJob = scope.launch {
            var tick = 0
            while (isActive) {
                if (!pushSafely(point, 0f, 0f)) break
                if (++tick % 10 == 0) updateNotification()
                delay(FIXED_INTERVAL_MS)
            }
        }
    }

    private fun runRoute(config: RouteConfig, startDistance: Double) {
        if (!ensureProvider()) return
        tickJob?.cancel()
        val w = RouteWalker(config.points, config.speedKmh / 3.6, config.loopMode, startDistance)
        walker = w
        paused = false
        acquireWakeLock()
        _state.value = SimState(
            running = true,
            mode = SimMode.ROUTE,
            position = w.current().point,
            speedKmh = config.speedKmh,
            pathLength = w.pathLength,
            loopMode = config.loopMode,
        )
        updateNotification()

        tickJob = scope.launch {
            var last = SystemClock.elapsedRealtime()
            var tick = 0
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                val dt = (now - last) / 1000.0
                last = now

                val pos = w.advance(if (paused) 0.0 else dt)
                val moving = !paused && !pos.finished && w.speedMps > 0
                if (!pushSafely(pos.point, if (moving) w.speedMps.toFloat() else 0f, pos.bearing)) break

                _state.update {
                    it.copy(
                        position = pos.point,
                        bearing = pos.bearing,
                        speedKmh = w.speedMps * 3.6,
                        travelled = w.travelled,
                        finished = pos.finished,
                        paused = paused,
                    )
                }
                tick++
                if (tick % 4 == 0) updateNotification()
                if (tick % 20 == 0) session.saveProgress(w.travelled)
                delay(ROUTE_INTERVAL_MS)
            }
        }
    }

    private fun setPaused(value: Boolean) {
        if (walker == null) return
        paused = value
        _state.update { it.copy(paused = value) }
        walker?.let { session.saveProgress(it.travelled) }
        updateNotification()
    }

    private fun setSpeed(kmh: Double) {
        val w = walker ?: return
        w.speedMps = kmh / 3.6
        session.saveSpeed(kmh)
        _state.update { it.copy(speedKmh = kmh) }
        updateNotification()
    }

    private fun ensureProvider(): Boolean {
        if (provider != null) return true
        return try {
            provider = MockLocationProvider(this).also { it.enable() }
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "未被選為模擬位置應用程式", e)
            fail(SimError.NOT_MOCK_APP)
            false
        } catch (e: Exception) {
            Log.e(TAG, "啟用模擬定位失敗", e)
            fail(SimError.OTHER)
            false
        }
    }

    private fun pushSafely(point: LatLng, speedMps: Float, bearing: Float): Boolean = try {
        provider?.push(point, speedMps, bearing)
        true
    } catch (e: SecurityException) {
        Log.w(TAG, "模擬位置權限被取消", e)
        fail(SimError.NOT_MOCK_APP)
        false
    } catch (e: Exception) {
        Log.w(TAG, "推送位置失敗", e)
        true
    }

    private fun fail(error: SimError) {
        stopSimulation(clearSession = true)
        _state.value = SimState(error = error)
    }

    private fun stopSimulation(clearSession: Boolean) {
        tickJob?.cancel()
        tickJob = null
        walker = null
        paused = false
        provider?.disable()
        provider = null
        releaseWakeLock()
        if (clearSession) session.clear()
        _state.value = SimState()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------------------------------------------------------------------------------------------

    private fun startInForeground(): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        true
    } catch (e: Exception) {
        // 例如 Android 14 以上缺少定位權限，或由背景啟動受限
        Log.e(TAG, "無法啟動前景服務", e)
        stopSimulation(clearSession = false)
        _state.value = SimState(error = SimError.NO_PERMISSION)
        false
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GpsSimulator:mock").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        nm.createNotificationChannel(channel)
    }

    private fun updateNotification() {
        if (!_state.value.running) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.notify(NOTIFICATION_ID, buildNotification()) }
    }

    private fun buildNotification(): Notification {
        val s = _state.value
        val title = when (s.mode) {
            SimMode.FIXED -> getString(R.string.notif_title_fixed)
            SimMode.ROUTE -> getString(
                if (s.paused) R.string.notif_title_route_paused
                else if (s.finished) R.string.notif_title_route_finished
                else R.string.notif_title_route,
            )
            null -> getString(R.string.notif_title_preparing)
        }
        val text = buildString {
            s.position?.let { append(it.format()) }
            if (s.mode == SimMode.ROUTE) {
                append(String.format(java.util.Locale.US, " · %.1f km/h", s.speedKmh))
                append(" · ").append(GeoUtils.formatDistance(s.travelled))
            }
        }

        val immutable = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            immutable,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MockLocationService::class.java).setAction(ACTION_STOP), immutable,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (s.mode == SimMode.ROUTE) {
            val toggle = PendingIntent.getService(
                this, 2,
                Intent(this, MockLocationService::class.java).setAction(if (s.paused) ACTION_RESUME else ACTION_PAUSE),
                immutable,
            )
            builder.addAction(0, getString(if (s.paused) R.string.action_resume else R.string.action_pause), toggle)
        }
        builder.addAction(0, getString(R.string.action_stop), stop)
        return builder.build()
    }

    companion object {
        private const val TAG = "MockLocationService"
        private const val CHANNEL_ID = "mock_location"
        private const val NOTIFICATION_ID = 1001
        private const val FIXED_INTERVAL_MS = 1000L
        private const val ROUTE_INTERVAL_MS = 500L
        private const val WAKE_LOCK_TIMEOUT_MS = 24 * 60 * 60 * 1000L

        private const val ACTION_START_FIXED = "com.gpssim.app.START_FIXED"
        private const val ACTION_START_ROUTE = "com.gpssim.app.START_ROUTE"
        private const val ACTION_PAUSE = "com.gpssim.app.PAUSE"
        private const val ACTION_RESUME = "com.gpssim.app.RESUME"
        private const val ACTION_SET_SPEED = "com.gpssim.app.SET_SPEED"
        private const val ACTION_STOP = "com.gpssim.app.STOP"

        private const val EXTRA_LAT = "lat"
        private const val EXTRA_LNG = "lng"
        private const val EXTRA_LATS = "lats"
        private const val EXTRA_LNGS = "lngs"
        private const val EXTRA_SPEED = "speed_kmh"
        private const val EXTRA_LOOP = "loop_mode"

        private val _state = MutableStateFlow(SimState())
        val state: StateFlow<SimState> = _state.asStateFlow()

        fun clearError() = _state.update { it.copy(error = null) }

        fun startFixed(context: Context, point: LatLng) = start(
            context,
            intent(context, ACTION_START_FIXED)
                .putExtra(EXTRA_LAT, point.lat)
                .putExtra(EXTRA_LNG, point.lng),
        )

        fun startRoute(context: Context, config: RouteConfig) = start(
            context,
            intent(context, ACTION_START_ROUTE)
                .putExtra(EXTRA_LATS, config.points.map { it.lat }.toDoubleArray())
                .putExtra(EXTRA_LNGS, config.points.map { it.lng }.toDoubleArray())
                .putExtra(EXTRA_SPEED, config.speedKmh)
                .putExtra(EXTRA_LOOP, config.loopMode.name),
        )

        fun pause(context: Context) = sendIfRunning(context, intent(context, ACTION_PAUSE))
        fun resume(context: Context) = sendIfRunning(context, intent(context, ACTION_RESUME))
        fun stop(context: Context) = sendIfRunning(context, intent(context, ACTION_STOP))
        fun setSpeed(context: Context, kmh: Double) =
            sendIfRunning(context, intent(context, ACTION_SET_SPEED).putExtra(EXTRA_SPEED, kmh))

        private fun intent(context: Context, action: String) =
            Intent(context, MockLocationService::class.java).setAction(action)

        private fun start(context: Context, intent: Intent) {
            ContextCompat.startForegroundService(context, intent)
        }

        private fun sendIfRunning(context: Context, intent: Intent) {
            if (_state.value.running) start(context, intent)
        }
    }
}
