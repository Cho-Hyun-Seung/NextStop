package kr.nextstop.app.data

import kr.nextstop.app.domain.*
import org.json.JSONArray
import org.json.JSONObject

/** Seoul Open Data Plaza station IDs; connections are same-line, never transfers. */
object SubwayNetwork {
    private data class ExpressPattern(val lineId: String, val serviceType: String, val stops: List<String>)
    private val expressPatterns: List<ExpressPattern> by lazy {
        val stream = requireNotNull(SubwayNetwork::class.java.getResourceAsStream("/seoul-subway-express.json"))
        val items = stream.bufferedReader().use { JSONObject(it.readText()).getJSONArray("patterns") }
        (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            ExpressPattern(item.getString("lineId"), item.getString("serviceType"), item.getJSONArray("stops").let { stops -> (0 until stops.length()).map { stops.getJSONArray(it).getString(0) } })
        }
    }
    class Line(val id: String, val name: String, val stations: Map<String, String>, val segments: List<List<String>>, val ring: List<String>) {
        val route get() = TransitRoute(id, name, "수도권", "", Provider.SEOUL)
        private val graphs = listOf(buildGraph("0"), buildGraph("1"))
        private fun buildGraph(direction: String): Map<String, List<String>> {
            val graph = stations.keys.associateWith { mutableListOf<String>() }
            fun add(from: String, to: String) { graph.getValue(from).add(to) }
            segments.forEach { segment -> segment.zipWithNext().forEach { (a, b) -> if (direction == "1") add(a, b) else add(b, a) } }
            if (ring.isNotEmpty()) (ring + ring.first()).zipWithNext().forEach { (a, b) ->
                if (id == "1002" && direction == "1") add(b, a) else add(a, b)
            }
            return graph
        }
        fun destination(vehicle: Vehicle): String? {
            if (vehicle.destinationId.isNotBlank()) return vehicle.destinationId.takeIf { it in stations }
            val matches = stations.filterValues { normalize(it) == normalize(vehicle.destinationName) }.keys
            return matches.singleOrNull()
        }
        private fun path(graph: Map<String, List<String>>, start: String, end: String): List<String>? {
            val queue = ArrayDeque<String>(); queue.add(start)
            val previous = mutableMapOf<String, String?>(start to null)
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                if (node == end) {
                    val result = mutableListOf<String>(); var current: String? = node
                    while (current != null) { result.add(current); current = previous[current] }
                    return result.reversed()
                }
                graph[node].orEmpty().forEach { if (it !in previous) { previous[it] = node; queue.add(it) } }
            }
            return null
        }
        fun forVehicle(vehicle: Vehicle): TransitRoute? {
            if (vehicle.direction !in listOf("0", "1") || vehicle.stopId !in stations) return null
            val terminal = destination(vehicle) ?: return null
            // Keep physical stations for position matching; only verified calls can be selected or counted.
            val express = if (vehicle.serviceType == "0") null else {
                expressPatterns.singleOrNull { it.lineId == id && it.serviceType == vehicle.serviceType } ?: return null
            }
            val calls = express?.stops?.toSet()
            val corridor = express?.let { path(graphs[1], it.stops.first(), it.stops.last())?.toSet() ?: return null }
            if (calls != null && (terminal !in calls || vehicle.stopId !in corridor.orEmpty())) return null
            val graph = graphs[vehicle.direction.toInt()]
            val future = if (vehicle.stopId == terminal && terminal in ring && (id == "1002" || vehicle.direction == "0")) {
                val cycle = if (id == "1002" && vehicle.direction == "1") ring.reversed() else ring
                val start = cycle.indexOf(terminal)
                (0..cycle.size).map { cycle[(start + it) % cycle.size] }
            } else path(graph, vehicle.stopId, terminal) ?: return null
            if (corridor != null && future.any { it !in corridor }) return null
            val previous = mutableListOf<String>(); var cursor = vehicle.stopId
            while (previous.count { calls == null || it in calls } < 3) {
                val incoming = graph.filterValues { cursor in it }.keys
                val node = incoming.singleOrNull() ?: break
                if (node == vehicle.stopId || node in previous || (corridor != null && node !in corridor)) break
                previous.add(node); cursor = node
            }
            val stopIds = previous.reversed() + future
            return route.copy(stops = stopIds.mapIndexed { index, station -> Stop(station, if (station == vehicle.stopId && vehicle.stopName.isNotBlank()) vehicle.stopName else stations.getValue(station), index, isScheduledStop = calls == null || station in calls) })
        }
        fun canStart(vehicle: Vehicle): Boolean {
            val prepared = forVehicle(vehicle) ?: return false
            val index = prepared.stops.indexOfFirst { it.id == vehicle.stopId }
            return index >= 0 && prepared.alightOptions(index, vehicle.motion in listOf(Motion.APPROACHING, Motion.PREVIOUS_DEPARTED)).isNotEmpty()
        }
        fun directionLabel(direction: String, stopId: String, terminal: String, destination: String): String {
            val circular = id == "1002" && stopId in ring && terminal in ring
            val label = if (circular) { if (direction == "0") "내선" else "외선" } else { if (direction == "0") "상행" else "하행" }
            return "$label · $destination 방면"
        }
    }
    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
    val lines: List<Line> by lazy {
        val stream = requireNotNull(SubwayNetwork::class.java.getResourceAsStream("/seoul-subway.json"))
        val root = stream.bufferedReader().use { JSONObject(it.readText()) }
        val items = root.getJSONArray("lines")
        (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            val stationRows = item.getJSONArray("stations")
            val stations = (0 until stationRows.length()).associate { stationRows.getJSONArray(it).let { row -> row.getString(0) to row.getString(1) } }
            val segments = item.getJSONArray("segments").let { paths -> (0 until paths.length()).map { paths.getJSONArray(it).strings() } }
            Line(item.getString("id"), item.getString("name"), stations, segments, item.getJSONArray("ring").strings())
        }
    }
    fun line(id: String) = lines.firstOrNull { it.id == id }
    fun forVehicle(route: TransitRoute, vehicle: Vehicle) = line(route.id)?.forVehicle(vehicle)
        ?: throw IllegalArgumentException("열차의 방향·행선지에 맞는 정차 경로를 확인할 수 없습니다. 차량을 다시 선택해 주세요.")
    private fun normalize(value: String) = value.substringBefore('(').replace(Regex("\\s"), "").removeSuffix("역")
}
