package com.gpssim.app

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlin.random.Random

/**
 * 將模擬座標注入系統的 GPS / Network 定位提供者，以及 Google Fused Location。
 * App 必須在「開發者選項 → 選取模擬位置應用程式」中被選取。
 */
class MockLocationProvider(context: Context) {

    private val appContext = context.applicationContext
    private val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val activeProviders = mutableListOf<String>()
    private val fused: FusedLocationProviderClient? =
        runCatching { LocationServices.getFusedLocationProviderClient(appContext) }.getOrNull()
    private var fusedMockEnabled = false

    /** @throws SecurityException 尚未被選為模擬位置應用程式 */
    @SuppressLint("MissingPermission")
    fun enable() {
        for (name in PROVIDERS) {
            runCatching { lm.removeTestProvider(name) }
            try {
                @Suppress("DEPRECATION")
                lm.addTestProvider(
                    name,
                    false, // requiresNetwork
                    name == LocationManager.GPS_PROVIDER, // requiresSatellite
                    false, // requiresCell
                    false, // hasMonetaryCost
                    true, // supportsAltitude
                    true, // supportsSpeed
                    true, // supportsBearing
                    Criteria.POWER_LOW,
                    Criteria.ACCURACY_FINE,
                )
                lm.setTestProviderEnabled(name, true)
                activeProviders += name
            } catch (e: SecurityException) {
                disable()
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "無法建立模擬提供者 $name", e)
            }
        }
        check(activeProviders.isNotEmpty()) { "無法建立任何模擬定位提供者" }

        fused?.let { client ->
            runCatching {
                client.setMockMode(true)
                    .addOnSuccessListener { fusedMockEnabled = true }
                    .addOnFailureListener { Log.w(TAG, "Fused 模擬模式啟用失敗", it) }
            }.onFailure { Log.w(TAG, "Fused 模擬模式啟用失敗", it) }
        }
    }

    /** @throws SecurityException 使用中途被取消模擬位置應用程式權限 */
    @SuppressLint("MissingPermission")
    fun push(point: LatLng, speedMps: Float, bearing: Float) {
        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtimeNanos()
        val accuracy = 3f + Random.nextFloat() * 2f
        for (name in activeProviders) {
            lm.setTestProviderLocation(name, buildLocation(name, point, speedMps, bearing, accuracy, now, elapsed))
        }
        if (fusedMockEnabled) {
            runCatching {
                fused?.setMockLocation(buildLocation("fused", point, speedMps, bearing, accuracy, now, elapsed))
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun disable() {
        for (name in activeProviders) {
            runCatching { lm.setTestProviderEnabled(name, false) }
            runCatching { lm.removeTestProvider(name) }
        }
        activeProviders.clear()
        if (fusedMockEnabled) {
            runCatching { fused?.setMockMode(false) }
            fusedMockEnabled = false
        }
    }

    private fun buildLocation(
        provider: String,
        point: LatLng,
        speedMps: Float,
        bearing: Float,
        accuracy: Float,
        time: Long,
        elapsedNanos: Long,
    ) = Location(provider).apply {
        latitude = point.lat
        longitude = point.lng
        altitude = 30.0
        this.accuracy = accuracy
        speed = speedMps
        this.bearing = bearing
        this.time = time
        elapsedRealtimeNanos = elapsedNanos
        verticalAccuracyMeters = 5f
        speedAccuracyMetersPerSecond = 0.5f
        bearingAccuracyDegrees = 10f
        extras = Bundle().apply { putInt("satellites", 12) }
    }

    companion object {
        private const val TAG = "MockLocationProvider"
        private val PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

        /** 是否已在開發者選項中被選為模擬位置應用程式 */
        fun isMockAppSelected(context: Context): Boolean = try {
            val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName)
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            true // 無法判斷時交由實際注入時的例外處理
        }
    }
}
