package kr.nextstop.app.data

import android.content.Context
import kr.nextstop.app.domain.*
import org.json.JSONArray
import org.json.JSONObject

object SessionCodec {
    fun encode(session: Session): String = JSONObject().apply {
        put("version", 1); put("id", session.id); put("index", session.index); put("alight", session.alightIndex ?: JSONObject.NULL)
        put("mode", session.mode.name); put("started", session.startedAt); put("freshness", session.freshness.name); put("message", session.message)
        put("route", JSONObject().apply {
            put("id", session.route.id); put("name", session.route.name); put("city", session.route.city); put("cityCode", session.route.cityCode); put("provider", session.route.provider.name)
            put("stops", JSONArray(session.route.stops.map { JSONObject().put("id", it.id).put("name", it.name).put("order", it.order).put("direction", it.direction).put("isScheduledStop", it.isScheduledStop) }))
        })
        put("vehicle", JSONObject().apply {
            val vehicle = session.vehicle
            put("id", vehicle.id); put("label", vehicle.label); put("direction", vehicle.direction); put("directionLabel", vehicle.directionLabel)
            put("stopId", vehicle.stopId); put("stopOrder", vehicle.stopOrder ?: JSONObject.NULL); put("stopName", vehicle.stopName)
            put("motion", vehicle.motion.name); put("observed", vehicle.observedAt); put("sourceTimestamp", vehicle.sourceTimestamp)
            put("destinationId", vehicle.destinationId); put("destinationName", vehicle.destinationName); put("serviceType", vehicle.serviceType)
        })
        put("seen", JSONArray(session.seen.toList()))
        put("events", JSONArray(session.events.map { JSONObject().put("id", it.id).put("title", it.title).put("body", it.body).put("at", it.at) }))
    }.toString()

    fun decode(raw: String?, now: Long): Session? = runCatching {
        if (raw == null) return null
        val root = JSONObject(raw)
        require(root.getInt("version") == 1)
        val data = root.getJSONObject("route")
        val stops = data.getJSONArray("stops").let { array -> (0 until array.length()).map { i -> array.getJSONObject(i).let { Stop(it.getString("id"), it.getString("name"), it.getInt("order"), it.optString("direction"), it.optBoolean("isScheduledStop", true)) } } }
        require(stops.size in 2..1000 && stops.map { it.order }.distinct().size == stops.size)
        val route = TransitRoute(data.getString("id"), data.getString("name"), data.getString("city"), data.getString("cityCode"), Provider.valueOf(data.getString("provider")), stops)
        val item = root.getJSONObject("vehicle")
        val savedVehicleId = item.getString("id")
        val vehicleId = if (route.provider == Provider.SEOUL && Regex("\\d{8}:.+").matches(savedVehicleId)) "${route.id}:$savedVehicleId" else savedVehicleId
        val vehicle = Vehicle(vehicleId, item.getString("label"), item.getString("direction"), item.getString("directionLabel"), item.getString("stopId"), if (item.isNull("stopOrder")) null else item.getInt("stopOrder"), item.getString("stopName"), Motion.valueOf(item.getString("motion")), item.getLong("observed"), item.getBoolean("sourceTimestamp"), item.optString("destinationId"), item.optString("destinationName"), item.optString("serviceType", "0"))
        val index = root.getInt("index")
        val alight = if (root.isNull("alight")) null else root.getInt("alight")
        val started = root.getLong("started")
        require(index in stops.indices && (alight == null || (alight in stops.indices && stops[alight].isScheduledStop)) && now - started in 0 until TrackingEngine.MAX_RIDE_MS && vehicle.observedAt <= now + 5000)
        require(stops[index].id == vehicle.stopId && (vehicle.stopOrder == null || vehicle.stopOrder == stops[index].order))
        val mode = AlertMode.valueOf(root.getString("mode"))
        require(mode != AlertMode.ALIGHT || alight != null)
        val events = root.getJSONArray("events").let { array -> (0 until array.length()).map { i -> array.getJSONObject(i).let { RideEvent(it.getString("id"), it.getString("title"), it.getString("body"), it.getLong("at")) } }.takeLast(20) }
        val seen = root.getJSONArray("seen").let { array -> (0 until array.length()).map { array.getString(it) }.toSet() }
        val saved = Freshness.valueOf(root.getString("freshness"))
        val freshness = if (saved == Freshness.FRESH && now - vehicle.observedAt > TrackingEngine.STALE_MS) Freshness.STALE else saved
        Session(root.getString("id"), route, vehicle, index, alight, mode, started, freshness, root.optString("message"), seen, events)
    }.getOrNull()
}

class SessionStore(context: Context) {
    private val preferences = context.getSharedPreferences("ride_state", Context.MODE_PRIVATE)
    fun read(now: Long = System.currentTimeMillis()): Session? = SessionCodec.decode(preferences.getString("session", null), now)
    fun write(session: Session) = synchronized(lock) { check(preferences.edit().putString("session", SessionCodec.encode(session)).commit()) }
    fun clear() = synchronized(lock) { check(preferences.edit().remove("session").commit()); Unit }
    fun mutate(transform: (Session?) -> Session?): Session? = synchronized(lock) {
        val next = transform(read())
        if (next == null) clear() else write(next)
        next
    }
    var interval: Int
        get() = preferences.getInt("interval", 30).takeIf { it in listOf(20, 30, 60) } ?: 30
        set(value) { require(value in listOf(20, 30, 60)); preferences.edit().putInt("interval", value).apply() }
    var allowSeoulHttp: Boolean
        get() = preferences.getBoolean("seoul_http", false)
        set(value) { preferences.edit().putBoolean("seoul_http", value).apply() }
    companion object { private val lock = Any() }
}
