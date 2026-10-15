package kr.nextstop.app.data

import kr.nextstop.app.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SessionCodecTest {
    private val now = 1_700_000_000_000L
    private val route = TransitRoute("r", "10", "대전", "25", Provider.TAGO, (0..9).map { Stop("s$it", "정류장$it", it) })
    private val vehicle = Vehicle("v", "대전99가1234", "", "종점 방면", "s2", 2, "정류장2", Motion.NEAR, now, false)
    private val ride = TrackingEngine.begin(route, vehicle, 5, AlertMode.ALIGHT, now)

    @Test fun `현재 이동과 알림 중복 기록을 복원한다`() {
        val updated = TrackingEngine.update(ride, vehicle.copy(stopId = "s4", stopOrder = 4, observedAt = now + 10), now + 10)
        assertEquals(updated, SessionCodec.decode(SessionCodec.encode(updated), now + 10))
    }
    @Test fun `손상된 상태와 잘못된 지점을 거절한다`() {
        assertNull(SessionCodec.decode("{broken", now))
        assertNull(SessionCodec.decode(JSONObject(SessionCodec.encode(ride)).put("index", 100).toString(), now))
        assertNull(SessionCodec.decode(JSONObject(SessionCodec.encode(ride)).put("alight", 100).toString(), now))
    }
    @Test fun `4시간이 지난 이동은 복원하지 않는다`() {
        assertNull(SessionCodec.decode(SessionCodec.encode(ride), now + TrackingEngine.MAX_RIDE_MS))
    }
    @Test fun `오래된 관측을 최신 위치로 표시하지 않는다`() {
        assertEquals(Freshness.STALE, SessionCodec.decode(SessionCodec.encode(ride), now + 100_000)?.freshness)
    }
    @Test fun `이동 저장 상태에는 개인 API 키를 포함하지 않는다`() {
        val encoded = SessionCodec.encode(ride)
        assertFalse(encoded.contains("serviceKey"))
        assertFalse(encoded.contains("apiKey"))
    }
    @Test fun `열차 행선지와 정차 방식 정보를 저장 복원한다`() {
        val train = ride.copy(vehicle = vehicle.copy(destinationId = "s9", destinationName = "정류장9", serviceType = "1"))
        assertEquals(train, SessionCodec.decode(SessionCodec.encode(train), now))
    }
    @Test fun `기존 2호선 이동의 차량 ID를 노선 포함 형식으로 복원한다`() {
        val legacy = ride.copy(route = route.copy(id = "1002", provider = Provider.SEOUL), vehicle = vehicle.copy(id = "20231114:0014"))
        val json = JSONObject(SessionCodec.encode(legacy))
        json.getJSONObject("vehicle").apply { remove("destinationId"); remove("destinationName"); remove("serviceType") }
        val restored = SessionCodec.decode(json.toString(), now)!!
        assertEquals("1002:20231114:0014", restored.vehicle.id)
        assertEquals("0", restored.vehicle.serviceType)
        assertEquals(legacy.route, restored.route)
    }
    @Test fun `급행 통과역 정보를 저장하고 통과역 하차 설정은 복원하지 않는다`() {
        val express = ride.copy(route = route.copy(stops = route.stops.map { it.copy(isScheduledStop = it.order != 4) }), vehicle = vehicle.copy(serviceType = "1"))
        val restored = SessionCodec.decode(SessionCodec.encode(express), now)!!
        assertEquals(express, restored)
        assertEquals(2, restored.remainingStops)
        assertFalse(restored.route.stops[4].isScheduledStop)
        assertNull(SessionCodec.decode(JSONObject(SessionCodec.encode(express)).put("alight", 4).toString(), now))
    }
    @Test fun `이전 버전의 정차 여부가 없는 저장값은 모두 정차로 복원한다`() {
        val json = JSONObject(SessionCodec.encode(ride))
        val stops = json.getJSONObject("route").getJSONArray("stops")
        (0 until stops.length()).forEach { stops.getJSONObject(it).remove("isScheduledStop") }
        assertEquals(ride, SessionCodec.decode(json.toString(), now))
    }
}
