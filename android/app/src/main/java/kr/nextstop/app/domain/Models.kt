package kr.nextstop.app.domain

enum class Provider { TAGO, SEOUL }
enum class Motion { NEAR, APPROACHING, ARRIVED, DEPARTED, PREVIOUS_DEPARTED }
enum class AlertMode { ALL, ALIGHT, SILENT }
enum class Freshness { FRESH, STALE, LOST, STOPPED }
data class City(val code: String, val name: String)
data class Stop(val id: String, val name: String, val order: Int, val direction: String = "", val isScheduledStop: Boolean = true)
data class TransitRoute(val id: String, val name: String, val city: String, val cityCode: String, val provider: Provider, val stops: List<Stop> = emptyList()) {
    fun alightOptions(index: Int, approaching: Boolean) = stops.indices.filter { stops[it].isScheduledStop && (it > index || (it == index && approaching)) }
}
data class Vehicle(val id: String, val label: String, val direction: String, val directionLabel: String, val stopId: String, val stopOrder: Int?, val stopName: String, val motion: Motion, val observedAt: Long, val sourceTimestamp: Boolean, val destinationId: String = "", val destinationName: String = "", val serviceType: String = "0")
data class RideEvent(val id: String, val title: String, val body: String, val at: Long)
data class Session(
    val id: String,
    val route: TransitRoute,
    val vehicle: Vehicle,
    val index: Int,
    val alightIndex: Int?,
    val mode: AlertMode,
    val startedAt: Long,
    val freshness: Freshness = Freshness.FRESH,
    val message: String = "",
    val seen: Set<String> = emptySet(),
    val events: List<RideEvent> = emptyList(),
) {
    val stop get() = route.stops[index]
    val title get() = "${route.name}${when (vehicle.serviceType) { "1" -> " 급행"; "7" -> " 특급"; else -> "" }} · ${vehicle.directionLabel}"
    val approaching get() = vehicle.motion in listOf(Motion.APPROACHING, Motion.PREVIOUS_DEPARTED)
    val remainingStops get() = alightIndex?.let { target ->
        if (target < index) -route.stops.subList(target + 1, index + 1).count { it.isScheduledStop }.coerceAtLeast(1)
        else route.stops.subList(index + 1, target + 1).count { it.isScheduledStop } + if (approaching && stop.isScheduledStop) 1 else 0
    }
    val next get() = route.stops.drop(index + if (approaching) 0 else 1).firstOrNull { it.isScheduledStop }
    val location get() = if (!stop.isScheduledStop) "${stop.name} 통과 구간" else when (vehicle.motion) {
        Motion.DEPARTED -> "${stop.name} → ${next?.name ?: "운행 경로 끝"}"
        Motion.APPROACHING -> "${stop.name} 진입 중"
        Motion.NEAR -> "${stop.name} 부근"
        Motion.ARRIVED -> stop.name
        Motion.PREVIOUS_DEPARTED -> "${stop.name} 방향 · 전역 출발"
    }
    fun window(): List<Stop?> {
        val departed = vehicle.motion == Motion.DEPARTED
        val between = departed || vehicle.motion == Motion.PREVIOUS_DEPARTED || !stop.isScheduledStop
        val past = route.stops.take(index + if (departed) 1 else 0).filter { it.isScheduledStop }.takeLast(3)
        val future = route.stops.drop(index + if (vehicle.motion == Motion.PREVIOUS_DEPARTED) 0 else 1).filter { it.isScheduledStop }.take(3)
        return List<Stop?>(3 - past.size) { null } + past + listOf(if (between) null else stop) + future + List<Stop?>(3 - future.size) { null }
    }
}

object TrackingEngine {
    const val STALE_MS = 90_000L
    const val MAX_RIDE_MS = 4 * 60 * 60 * 1000L

    fun begin(route: TransitRoute, vehicle: Vehicle, alight: Int?, mode: AlertMode, now: Long): Session {
        require(now - vehicle.observedAt in 0..STALE_MS) { "차량 정보가 오래되었습니다. 다시 조회해 주세요." }
        val index = route.stops.indexOfFirst { it.id == vehicle.stopId && (vehicle.stopOrder == null || it.order == vehicle.stopOrder) }
        val approaching = vehicle.motion in listOf(Motion.APPROACHING, Motion.PREVIOUS_DEPARTED)
        require(index >= 0 && route.alightOptions(index, approaching).isNotEmpty()) { "선택한 차량의 정차 경로를 확인할 수 없습니다." }
        require(alight == null || alight in route.alightOptions(index, approaching)) { "하차 지점은 앞으로 정차하는 역·정류장에서 선택해 주세요." }
        require(mode != AlertMode.ALIGHT || alight != null) { "하차 지점을 선택해 주세요." }
        val id = java.util.UUID.randomUUID().toString()
        val arrival = if (vehicle.motion == Motion.ARRIVED) setOf("$id:$index:arrival") else emptySet()
        return Session(id, route, vehicle, index, alight, mode, now, seen = arrival)
    }

    fun update(session: Session, observation: Vehicle?, now: Long): Session {
        if (session.freshness == Freshness.STOPPED) return session
        if (now - session.startedAt >= MAX_RIDE_MS) return session.copy(freshness = Freshness.STOPPED, message = "4시간 추적을 마쳤습니다. 차량을 다시 선택해 주세요.")
        if (observation == null) return session.copy(freshness = Freshness.LOST, message = "선택한 차량 정보를 찾을 수 없습니다. 다른 차량으로 자동 변경하지 않습니다.")
        if (observation.id != session.vehicle.id || observation.direction != session.vehicle.direction) return session.copy(freshness = Freshness.LOST, message = "차량의 운행 방향이 바뀌었습니다. 차량을 다시 선택해 주세요.")
        val destinationChanged = if (session.vehicle.destinationId.isNotBlank()) observation.destinationId != session.vehicle.destinationId
            else session.vehicle.destinationName.isNotBlank() && observation.destinationName != session.vehicle.destinationName
        if (observation.serviceType != session.vehicle.serviceType || destinationChanged) return session.copy(freshness = Freshness.LOST, message = "열차의 행선지·정차 방식이 바뀌었습니다. 차량을 다시 선택해 주세요.")
        if (observation.observedAt < session.vehicle.observedAt) return session
        if (now - observation.observedAt !in 0..STALE_MS) return session.copy(freshness = Freshness.STALE, message = "마지막 확인 위치입니다. 최신 정보를 기다리고 있습니다.")
        val index = session.route.stops.withIndex().firstOrNull { (index, stop) -> index >= session.index && stop.id == observation.stopId && (observation.stopOrder == null || stop.order == observation.stopOrder) }?.index
            ?: return session.copy(freshness = Freshness.LOST, message = "선택한 정차 경로를 벗어났습니다. 차량을 다시 선택해 주세요.")
        var next = session.copy(vehicle = observation, index = index, freshness = Freshness.FRESH, message = "")
        if (session.mode == AlertMode.SILENT) return next
        val candidates = mutableListOf<RideEvent>()
        if (next.remainingStops == 1 && (observation.motion in listOf(Motion.DEPARTED, Motion.NEAR) || next.approaching || !next.stop.isScheduledStop)) {
            candidates += RideEvent("${session.id}:${session.alightIndex}:before", "다음은 하차 지점이에요", "${session.route.stops[session.alightIndex!!].name}에서 내릴 준비를 해 주세요.", now)
        }
        val alight = index == session.alightIndex
        if (next.stop.isScheduledStop && observation.motion == Motion.ARRIVED && (index != session.index || session.vehicle.motion != Motion.ARRIVED) && (session.mode == AlertMode.ALL || alight)) {
            candidates += RideEvent("${session.id}:$index:arrival", if (alight) "하차역에 도착했어요" else "${next.stop.name} 도착", "${next.stop.name} · 다음 ${next.next?.name ?: "운행 경로 끝"}", now)
        }
        if (next.stop.isScheduledStop && observation.motion == Motion.NEAR && index > session.index && (session.mode == AlertMode.ALL || alight)) {
            candidates += RideEvent("${session.id}:$index:near", if (alight) "하차 정류장 부근이에요" else "${next.stop.name} 부근으로 이동", "버스 위치 정보 기준의 진행 안내입니다. 실제 정류장 도착 확인은 아닙니다.", now)
        }
        for (event in candidates) if (event.id !in next.seen) next = next.copy(seen = next.seen + event.id, events = (next.events + event).takeLast(20))
        return next
    }
}
