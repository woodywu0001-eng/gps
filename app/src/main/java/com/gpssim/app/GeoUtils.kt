package com.gpssim.app

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object GeoUtils {
    private const val EARTH_RADIUS_M = 6_371_008.8

    /** 兩點間的大圓距離（公尺） */
    fun distance(a: LatLng, b: LatLng): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLat = lat2 - lat1
        val dLng = Math.toRadians(b.lng - a.lng)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
    }

    /** 由 a 指向 b 的方位角（0~360 度，正北為 0） */
    fun bearing(a: LatLng, b: LatLng): Float {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val y = sin(dLng) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
        val deg = Math.toDegrees(atan2(y, x))
        return ((deg + 360.0) % 360.0).toFloat()
    }

    /** 線性內插（路徑點間距短，誤差可忽略） */
    fun interpolate(a: LatLng, b: LatLng, fraction: Double): LatLng =
        LatLng(a.lat + (b.lat - a.lat) * fraction, a.lng + (b.lng - a.lng) * fraction)

    fun pathLength(points: List<LatLng>): Double =
        points.zipWithNext().sumOf { (p, q) -> distance(p, q) }

    fun formatDistance(meters: Double): String =
        if (meters < 1000) String.format(java.util.Locale.US, "%.0f m", meters)
        else String.format(java.util.Locale.US, "%.2f km", meters / 1000)

    fun formatDuration(seconds: Double): String {
        val total = seconds.toLong()
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "${h} 小時 ${m} 分" else if (m > 0) "${m} 分 ${s} 秒" else "${s} 秒"
    }
}
