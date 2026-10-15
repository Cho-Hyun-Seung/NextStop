package kr.nextstop.app.domain

import org.junit.Assert.*
import org.junit.Test
import kr.nextstop.app.data.SubwayNetwork

class TrackingEngineTest {
    private val now = 1_700_000_000_000L
    private val stops = (0..9).map { Stop("s$it", "지점$it", it, "0") }
    private val route = TransitRoute("r", "10", "대전", "25", Provider.TAGO, stops)
    private fun vehicle(index: Int = 2, motion: Motion = Motion.NEAR, at: Long = now) = Vehicle("bus1", "대전99가1234", "0", "기점 → 종점", "s$index", index, "지점$index", motion, at, false)
    private fun ride(mode: AlertMode = AlertMode.ALL) = TrackingEngine.begin(route, vehicle(), 5, mode, now)
    private fun expressRide(target: String = "가양"): Session {
        val line = SubwayNetwork.line("1009")!!
        fun id(name: String) = line.stations.entries.single { it.value == name }.key
        val train = Vehicle("1009:20270115:001", "열차 001 · 급행", "1", "중앙보훈병원 방면", id("김포공항"), null, "김포공항", Motion.DEPARTED, now, true, id("중앙보훈병원"), "중앙보훈병원", "1")
        val path = line.forVehicle(train)!!
        return TrackingEngine.begin(path, train, path.stops.indexOfFirst { it.name == target }, AlertMode.ALL, now)
    }
    private fun expressObservation(ride: Session, name: String, motion: Motion, at: Long) = ride.vehicle.copy(stopId = ride.route.stops.first { it.name == name }.id, stopName = name, motion = motion, observedAt = at)

    @Test fun `오래된 후보로 시작하지 않는다`() {
        assertThrows(IllegalArgumentException::class.java) { TrackingEngine.begin(route, vehicle(at = now - 91_000), 5, AlertMode.ALL, now) }
    }
    @Test fun `앞으로 갈 수 없는 차량과 하차 지점을 거절한다`() {
        assertThrows(IllegalArgumentException::class.java) { TrackingEngine.begin(route, vehicle(9), null, AlertMode.ALL, now) }
        assertThrows(IllegalArgumentException::class.java) { TrackingEngine.begin(route, vehicle(), 1, AlertMode.ALL, now) }
        assertThrows(IllegalArgumentException::class.java) { TrackingEngine.begin(route, vehicle(), null, AlertMode.ALIGHT, now) }
    }
    @Test fun `버스 진행 안내를 실제 도착으로 표시하지 않는다`() {
        val updated = TrackingEngine.update(ride(), vehicle(3, at = now + 10), now + 10)
        assertEquals(3, updated.index)
        assertEquals(1, updated.events.size)
        assertTrue(updated.events.single().title.contains("부근"))
        assertTrue(updated.events.single().body.contains("실제 정류장 도착 확인은 아닙니다"))
    }
    @Test fun `건너뛴 지점의 알림을 만들어내지 않는다`() {
        val updated = TrackingEngine.update(ride(), vehicle(6, at = now + 10), now + 10)
        assertEquals(1, updated.events.size)
        assertTrue(updated.events.single().title.contains("지점6"))
    }
    @Test fun `하차 모드는 직전과 해당 지점에서만 안내한다`() {
        val other = TrackingEngine.update(ride(AlertMode.ALIGHT), vehicle(3, at = now + 10), now + 10)
        assertTrue(other.events.isEmpty())
        val before = TrackingEngine.update(other, vehicle(4, at = now + 20), now + 20)
        assertEquals("다음은 하차 지점이에요", before.events.single().title)
        val arrived = TrackingEngine.update(before, vehicle(5, at = now + 30), now + 30)
        assertEquals("하차 정류장 부근이에요", arrived.events.last().title)
    }
    @Test fun `무음은 상태만 갱신한다`() {
        val updated = TrackingEngine.update(ride(AlertMode.SILENT), vehicle(5, at = now + 10), now + 10)
        assertEquals(5, updated.index)
        assertTrue(updated.events.isEmpty())
    }
    @Test fun `역 도착 상태 변화만 실제 도착 안내를 발생시킨다`() {
        val base = TrackingEngine.begin(route, vehicle(motion = Motion.APPROACHING), 5, AlertMode.ALL, now)
        val arrived = TrackingEngine.update(base, vehicle(motion = Motion.ARRIVED, at = now + 10), now + 10)
        assertEquals("지점2 도착", arrived.events.single().title)
        val again = TrackingEngine.update(arrived, vehicle(motion = Motion.ARRIVED, at = now + 20), now + 20)
        assertEquals(1, again.events.size)
    }
    @Test fun `현재 역에 탑승했다고 즉시 도착 알림을 중복 발송하지 않는다`() {
        val base = TrackingEngine.begin(route, vehicle(motion = Motion.ARRIVED), 5, AlertMode.ALL, now)
        assertTrue(TrackingEngine.update(base, vehicle(motion = Motion.ARRIVED, at = now + 10), now + 10).events.isEmpty())
    }
    @Test fun `무음에서 모드를 바꿔도 지난 역 도착을 새로 알리지 않는다`() {
        val base = TrackingEngine.update(ride(AlertMode.SILENT), vehicle(motion = Motion.ARRIVED, at = now + 10), now + 10)
        val updated = TrackingEngine.update(base.copy(mode = AlertMode.ALL), vehicle(motion = Motion.ARRIVED, at = now + 20), now + 20)
        assertTrue(updated.events.isEmpty())
    }
    @Test fun `차량 소실과 방향 변경 시 기존 위치를 유지한다`() {
        val lost = TrackingEngine.update(ride(), null, now + 10)
        assertEquals(Freshness.LOST, lost.freshness)
        assertEquals(2, lost.index)
        val direction = TrackingEngine.update(ride(), vehicle(3).copy(direction = "1"), now)
        assertEquals(Freshness.LOST, direction.freshness)
        assertTrue(direction.events.isEmpty())
    }
    @Test fun `오래되거나 역순인 관측으로 진행하지 않는다`() {
        assertEquals(Freshness.STALE, TrackingEngine.update(ride(), vehicle(5), now + 100_000).freshness)
        assertEquals(2, TrackingEngine.update(ride(), vehicle(5, at = now - 1), now).index)
    }
    @Test fun `같은 정류장 ID의 반복 방문은 순번으로 구분한다`() {
        val repeated = route.copy(stops = stops.map { if (it.order == 6) it.copy(id = "s2") else it })
        val base = TrackingEngine.begin(repeated, vehicle(), 6, AlertMode.ALL, now)
        val updated = TrackingEngine.update(base, vehicle(6, at = now + 10).copy(stopId = "s2"), now + 10)
        assertEquals(6, updated.index)
    }
    @Test fun `이동 중 노선도는 지난 세 개와 다음 세 개를 표시한다`() {
        val base = ride().copy(index = 4, vehicle = vehicle(4, Motion.DEPARTED))
        assertEquals(listOf("지점2", "지점3", "지점4", null, "지점5", "지점6", "지점7"), base.window().map { it?.name })
    }
    @Test fun `종점 이후 가상의 다음 지점을 만들지 않는다`() {
        val updated = TrackingEngine.update(ride(), vehicle(9, at = now + 10), now + 10)
        assertNull(updated.next)
        assertEquals(listOf(null, null, null), updated.window().takeLast(3))
    }
    @Test fun `4시간 이후 중단한다`() {
        assertEquals(Freshness.STOPPED, TrackingEngine.update(ride(), vehicle(3), now + TrackingEngine.MAX_RIDE_MS).freshness)
    }
    @Test fun `진입 예정 하차역은 선택할 수 있고 실제 도착시에만 도착 안내한다`() {
        val train = vehicle(9, Motion.PREVIOUS_DEPARTED)
        val base = TrackingEngine.begin(route, train, 9, AlertMode.ALIGHT, now)
        assertEquals(1, base.remainingStops)
        val before = TrackingEngine.update(base, train.copy(observedAt = now + 1), now + 1)
        assertEquals(listOf("다음은 하차 지점이에요"), before.events.map { it.title })
        val arrived = TrackingEngine.update(before, train.copy(motion = Motion.ARRIVED, observedAt = now + 2), now + 2)
        assertEquals(0, arrived.remainingStops)
        assertEquals("하차역에 도착했어요", arrived.events.last().title)
    }
    @Test fun `열차가 단축 운행하거나 급행으로 바뀌면 기존 경로로 안내하지 않는다`() {
        val train = vehicle().copy(destinationId = "end", destinationName = "종착역")
        val base = TrackingEngine.begin(route, train, 5, AlertMode.ALIGHT, now)
        val changed = TrackingEngine.update(base, train.copy(destinationId = "short", observedAt = now + 1), now + 1)
        assertEquals(Freshness.LOST, changed.freshness)
        assertEquals(base.index, changed.index)
        assertTrue(changed.events.isEmpty())
        assertEquals(Freshness.LOST, TrackingEngine.update(base, train.copy(serviceType = "1", observedAt = now + 1), now + 1).freshness)
        assertEquals(Freshness.FRESH, TrackingEngine.update(base, train.copy(destinationName = "종착역(표기 변경)", observedAt = now + 1), now + 1).freshness)
    }
    @Test fun `급행의 다음 역과 남은 역 수는 실제 정차역 기준이다`() {
        val ride = expressRide()
        assertEquals("마곡나루", ride.next!!.name)
        assertEquals(2, ride.remainingStops)
        assertEquals(listOf("마곡나루", "가양", "염창"), ride.window().takeLast(3).map { it?.name })
        assertThrows(IllegalArgumentException::class.java) { TrackingEngine.begin(ride.route, ride.vehicle, ride.route.stops.indexOfFirst { it.name == "공항시장" }, AlertMode.ALL, now) }
        val passing = TrackingEngine.update(ride, expressObservation(ride, "공항시장", Motion.ARRIVED, now + 1), now + 1)
        assertEquals(Freshness.FRESH, passing.freshness)
        assertEquals("공항시장 통과 구간", passing.location)
        assertEquals("마곡나루", passing.next!!.name)
        assertEquals(2, passing.remainingStops)
        assertTrue(passing.events.isEmpty())
        assertFalse(passing.window().filterNotNull().any { !it.isScheduledStop })
    }
    @Test fun `급행은 통과역에서 도착 알림 없이 정차역 직전과 도착만 안내한다`() {
        val ride = expressRide()
        val before = TrackingEngine.update(ride, expressObservation(ride, "마곡나루", Motion.DEPARTED, now + 1), now + 1)
        assertEquals(listOf("다음은 하차 지점이에요"), before.events.map { it.title })
        assertEquals(1, before.remainingStops)
        assertEquals("가양", before.next!!.name)
        val passing = TrackingEngine.update(before, expressObservation(ride, "양천향교", Motion.ARRIVED, now + 2), now + 2)
        assertEquals(Freshness.FRESH, passing.freshness)
        assertEquals(before.events, passing.events)
        val arrived = TrackingEngine.update(passing, expressObservation(ride, "가양", Motion.ARRIVED, now + 3), now + 3)
        assertEquals("하차역에 도착했어요", arrived.events.last().title)
        assertEquals(0, arrived.remainingStops)
        assertEquals(2, arrived.events.size)
        val past = TrackingEngine.update(arrived, expressObservation(ride, "증미", Motion.DEPARTED, now + 4), now + 4)
        assertTrue(past.remainingStops!! < 0)
    }
    @Test fun `급행 관측이 통과 구간으로 건너뛰어도 다음 정차역 하차를 한 번만 안내한다`() {
        val ride = expressRide()
        val passing = TrackingEngine.update(ride, expressObservation(ride, "양천향교", Motion.PREVIOUS_DEPARTED, now + 1), now + 1)
        assertEquals("가양", passing.next!!.name)
        assertEquals(1, passing.remainingStops)
        assertEquals(listOf("다음은 하차 지점이에요"), passing.events.map { it.title })
        assertEquals("가양", passing.window()[4]!!.name)
        val again = TrackingEngine.update(passing, expressObservation(ride, "양천향교", Motion.ARRIVED, now + 2), now + 2)
        assertEquals(passing.events, again.events)
    }
}
