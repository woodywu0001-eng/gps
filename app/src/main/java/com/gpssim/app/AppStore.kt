package com.gpssim.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 儲存常用座標、常用路徑與使用者偏好設定（SharedPreferences + JSON）。
 */
class AppStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("gps_sim_store", Context.MODE_PRIVATE)

    // ---------- 常用座標 ----------

    fun favorites(): List<Favorite> {
        val arr = JSONArray(prefs.getString(KEY_FAVORITES, "[]"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Favorite(o.getLong("id"), o.getString("name"), o.getDouble("lat"), o.getDouble("lng"))
        }
    }

    fun addFavorite(name: String, point: LatLng): Favorite {
        val fav = Favorite(System.currentTimeMillis(), name, point.lat, point.lng)
        saveFavorites(favorites() + fav)
        return fav
    }

    fun renameFavorite(id: Long, name: String) =
        saveFavorites(favorites().map { if (it.id == id) it.copy(name = name) else it })

    fun deleteFavorite(id: Long) = saveFavorites(favorites().filterNot { it.id == id })

    private fun saveFavorites(list: List<Favorite>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("lat", it.lat).put("lng", it.lng))
        }
        prefs.edit().putString(KEY_FAVORITES, arr.toString()).apply()
    }

    // ---------- 常用路徑 ----------

    fun routes(): List<SavedRoute> {
        val arr = JSONArray(prefs.getString(KEY_ROUTES, "[]"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SavedRoute(o.getLong("id"), o.getString("name"), routeConfigFromJson(o.getJSONObject("config")))
        }
    }

    fun addRoute(name: String, config: RouteConfig): SavedRoute {
        val route = SavedRoute(System.currentTimeMillis(), name, config)
        saveRoutes(routes() + route)
        return route
    }

    fun deleteRoute(id: Long) = saveRoutes(routes().filterNot { it.id == id })

    private fun saveRoutes(list: List<SavedRoute>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("config", routeConfigToJson(it.config)))
        }
        prefs.edit().putString(KEY_ROUTES, arr.toString()).apply()
    }

    // ---------- 偏好設定 ----------

    var speedKmh: Double
        get() = prefs.getFloat(KEY_SPEED, 5f).toDouble()
        set(value) = prefs.edit().putFloat(KEY_SPEED, value.toFloat()).apply()

    var loopMode: LoopMode
        get() = runCatching { LoopMode.valueOf(prefs.getString(KEY_LOOP, null)!!) }.getOrDefault(LoopMode.ONCE)
        set(value) = prefs.edit().putString(KEY_LOOP, value.name).apply()

    var mapCenter: LatLng?
        get() = if (prefs.contains(KEY_CENTER_LAT)) {
            LatLng(
                java.lang.Double.longBitsToDouble(prefs.getLong(KEY_CENTER_LAT, 0)),
                java.lang.Double.longBitsToDouble(prefs.getLong(KEY_CENTER_LNG, 0)),
            )
        } else null
        set(value) {
            if (value == null) return
            prefs.edit()
                .putLong(KEY_CENTER_LAT, java.lang.Double.doubleToRawLongBits(value.lat))
                .putLong(KEY_CENTER_LNG, java.lang.Double.doubleToRawLongBits(value.lng))
                .apply()
        }

    var mapZoom: Double
        get() = prefs.getFloat(KEY_ZOOM, 16f).toDouble()
        set(value) = prefs.edit().putFloat(KEY_ZOOM, value.toFloat()).apply()

    companion object {
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_ROUTES = "routes"
        private const val KEY_SPEED = "speed_kmh"
        private const val KEY_LOOP = "loop_mode"
        private const val KEY_CENTER_LAT = "center_lat"
        private const val KEY_CENTER_LNG = "center_lng"
        private const val KEY_ZOOM = "zoom"

        fun routeConfigToJson(config: RouteConfig): JSONObject {
            val pts = JSONArray()
            config.points.forEach { pts.put(JSONArray().put(it.lat).put(it.lng)) }
            return JSONObject()
                .put("points", pts)
                .put("speedKmh", config.speedKmh)
                .put("loopMode", config.loopMode.name)
        }

        fun routeConfigFromJson(o: JSONObject): RouteConfig {
            val pts = o.getJSONArray("points")
            val points = (0 until pts.length()).map { i ->
                val p = pts.getJSONArray(i)
                LatLng(p.getDouble(0), p.getDouble(1))
            }
            val loop = runCatching { LoopMode.valueOf(o.getString("loopMode")) }.getOrDefault(LoopMode.ONCE)
            return RouteConfig(points, o.getDouble("speedKmh"), loop)
        }
    }
}

/**
 * 記錄目前的模擬工作，服務被系統回收後重新啟動時可接續模擬。
 */
class SessionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("gps_sim_session", Context.MODE_PRIVATE)

    sealed class Session {
        data class Fixed(val point: LatLng) : Session()
        data class Route(val config: RouteConfig, val travelled: Double) : Session()
    }

    fun saveFixed(point: LatLng) {
        prefs.edit().clear()
            .putString("type", "fixed")
            .putString("lat", point.lat.toString())
            .putString("lng", point.lng.toString())
            .apply()
    }

    fun saveRoute(config: RouteConfig) {
        prefs.edit().clear()
            .putString("type", "route")
            .putString("config", AppStore.routeConfigToJson(config).toString())
            .putString("travelled", "0")
            .apply()
    }

    fun saveProgress(travelled: Double) {
        prefs.edit().putString("travelled", travelled.toString()).apply()
    }

    fun saveSpeed(speedKmh: Double) {
        val session = load() as? Session.Route ?: return
        prefs.edit()
            .putString("config", AppStore.routeConfigToJson(session.config.copy(speedKmh = speedKmh)).toString())
            .apply()
    }

    fun load(): Session? = runCatching {
        when (prefs.getString("type", null)) {
            "fixed" -> Session.Fixed(LatLng(prefs.getString("lat", null)!!.toDouble(), prefs.getString("lng", null)!!.toDouble()))
            "route" -> Session.Route(
                AppStore.routeConfigFromJson(JSONObject(prefs.getString("config", null)!!)),
                prefs.getString("travelled", "0")!!.toDouble(),
            )
            else -> null
        }
    }.getOrNull()

    fun clear() = prefs.edit().clear().apply()
}
