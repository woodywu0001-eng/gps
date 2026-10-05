package com.gpssim.app

/**
 * 依速度沿路徑移動，計算每個時間點的位置與方向。
 */
class RouteWalker(
    points: List<LatLng>,
    speedMps: Double,
    loopMode: LoopMode,
    startDistance: Double = 0.0,
) {
    data class Position(val point: LatLng, val bearing: Float, val finished: Boolean)

    private val path: List<LatLng> = when (loopMode) {
        LoopMode.ONCE -> points
        LoopMode.LOOP -> if (points.first() == points.last()) points else points + points.first()
        LoopMode.ROUND_TRIP -> points + points.dropLast(1).reversed()
    }

    private val repeat = loopMode != LoopMode.ONCE

    /** cumulative[i] = 由 path[0] 到 path[i] 的距離 */
    private val cumulative = DoubleArray(path.size).also { arr ->
        for (i in 1 until path.size) arr[i] = arr[i - 1] + GeoUtils.distance(path[i - 1], path[i])
    }

    /** 單趟（一圈）總長度（公尺） */
    val pathLength: Double = cumulative.last()

    var speedMps: Double = speedMps.coerceAtLeast(0.0)
        set(value) {
            field = value.coerceAtLeast(0.0)
        }

    /** 累計移動距離（公尺，循環模式下會超過 pathLength） */
    var travelled: Double = startDistance.coerceAtLeast(0.0)
        private set

    private var lastBearing = 0f

    init {
        require(points.size >= 2) { "路徑至少需要兩個點" }
        if (!repeat && travelled > pathLength) travelled = pathLength
    }

    val finished: Boolean get() = !repeat && travelled >= pathLength

    /** 前進 dtSeconds 秒後的位置 */
    fun advance(dtSeconds: Double): Position {
        if (dtSeconds > 0) travelled += speedMps * dtSeconds
        if (pathLength <= 0.0) return Position(path.first(), lastBearing, !repeat)

        val d: Double
        var done = false
        if (repeat) {
            d = travelled % pathLength
        } else {
            if (travelled >= pathLength) {
                travelled = pathLength
                done = true
            }
            d = travelled
        }
        return positionAt(d, done)
    }

    fun current(): Position = advance(0.0)

    private fun positionAt(d: Double, done: Boolean): Position {
        var i = 0
        while (i < path.size - 2 && cumulative[i + 1] <= d) i++
        val segLen = cumulative[i + 1] - cumulative[i]
        val fraction = if (segLen > 0) ((d - cumulative[i]) / segLen).coerceIn(0.0, 1.0) else 1.0
        if (segLen > 0) lastBearing = GeoUtils.bearing(path[i], path[i + 1])
        return Position(GeoUtils.interpolate(path[i], path[i + 1], fraction), lastBearing, done)
    }
}
