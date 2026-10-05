package com.gpssim.app

data class LatLng(val lat: Double, val lng: Double) {
    fun format(): String = String.format(java.util.Locale.US, "%.6f, %.6f", lat, lng)
}

enum class LoopMode {
    /** 走到終點後停在終點 */
    ONCE,

    /** 走到終點後回到起點，不斷循環 */
    LOOP,

    /** 起點 → 終點 → 起點，來回往返 */
    ROUND_TRIP,
}

data class Favorite(
    val id: Long,
    val name: String,
    val lat: Double,
    val lng: Double,
) {
    val point get() = LatLng(lat, lng)
}

data class RouteConfig(
    val points: List<LatLng>,
    val speedKmh: Double,
    val loopMode: LoopMode,
)

data class SavedRoute(
    val id: Long,
    val name: String,
    val config: RouteConfig,
)
