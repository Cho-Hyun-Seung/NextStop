package kr.nextstop.app.data

import kr.nextstop.app.domain.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ApiParsersTest {
    private val now = LocalDateTime.of(2026, 10, 7, 10, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli()
    private fun tago(item: Any?, code: String = "00") = JSONObject().put("response", JSONObject().put("header", JSONObject().put("resultCode", code)).put("body", JSONObject().put("totalCount", 1).put("items", if (item == null) "" else JSONObject().put("item", item)))).toString()
    private fun train(overrides: Map<String, Any> = emptyMap()) = JSONObject(mapOf("trainNo" to "0014", "statnId" to "1002000222", "statnNm" to "강남", "subwayId" to "1002", "trainSttus" to "1", "updnLine" to "0", "statnTnm" to "성수", "recptnDt" to "2026-10-07 10:00:00", "lastRecptnDt" to "20261007") + overrides)
    private fun subway(item: JSONObject) = JSONObject().put("errorMessage", JSONObject().put("code", "INFO-000")).put("realtimePositionList", JSONArray().put(item)).toString()

    @Test fun `TAGO 단일 객체와 배열과 빈 응답을 처리한다`() {
        val item = JSONObject().put("nodeid", "a")
        assertEquals(1, ApiParsers.tago(tago(item)).items.size)
        assertEquals(1, ApiParsers.tago(tago(JSONArray().put(item))).items.size)
        assertTrue(ApiParsers.tago(tago(null)).items.isEmpty())
    }
    @Test fun `인증 오류와 호출 한도는 재시도하지 않는다`() {
        assertTrue(assertThrows(TransitException::class.java) { ApiParsers.tago(tago(null, "30")) }.fatal)
        assertTrue(assertThrows(TransitException::class.java) { ApiParsers.tago(tago(null, "22")) }.fatal)
        assertTrue(assertThrows(TransitException::class.java) { ApiParsers.tago("<OpenAPI_ServiceResponse><returnReasonCode>30</returnReasonCode></OpenAPI_ServiceResponse>") }.fatal)
    }
    @Test fun `버스 차량번호와 반복 정류장 순번을 유지한다`() {
        val route = TransitRoute("r", "10", "대전", "25", Provider.TAGO, listOf(Stop("a", "첫 방문", 1), Stop("a", "두 번째 방문", 5)))
        val raw = tago(JSONObject().put("vehicleno", "대전99가1234").put("nodeid", "a").put("nodeord", 5))
        val bus = ApiParsers.buses(raw, route, now).single()
        assertEquals("두 번째 방문", bus.stopName)
        assertEquals(Motion.NEAR, bus.motion)
        assertFalse(bus.sourceTimestamp)
    }
    @Test fun `열차번호 앞자리 0과 데이터 기준 시각을 보존한다`() {
        val result = ApiParsers.subway(subway(train()), now).single()
        assertEquals("1002:20261007:0014", result.id)
        assertEquals(Motion.ARRIVED, result.motion)
        assertTrue(result.sourceTimestamp)
    }
    @Test fun `지연되거나 시각이 없는 열차를 후보로 사용하지 않는다`() {
        assertTrue(ApiParsers.subway(subway(train(mapOf("recptnDt" to "2026-10-07 09:58:00"))), now).isEmpty())
        assertTrue(ApiParsers.subway(subway(train(mapOf("recptnDt" to ""))), now).isEmpty())
    }
    @Test fun `추적 중 오래된 열차 응답은 소실 대신 지연으로 판단한다`() {
        val old = ApiParsers.subway(subway(train(mapOf("recptnDt" to "2026-10-07 09:58:00"))), now, includeStale = true).single()
        val fresh = ApiParsers.subway(subway(train()), now).single()
        val route = SubwayNetwork.forVehicle(SubwayNetwork.line("1002")!!.route, fresh)
        val ride = TrackingEngine.begin(route, fresh, 8, AlertMode.ALIGHT, now)
        // Ignore older responses; a repeated observation that ages out becomes stale.
        assertEquals(Freshness.STALE, TrackingEngine.update(ride.copy(vehicle = old), old, now).freshness)
    }
    @Test fun `방향에 맞지 않는 지선과 잘못된 노선 역코드 및 알 수 없는 도착 상태를 제외한다`() {
        assertTrue(ApiParsers.subway(subway(train(mapOf("statnTnm" to "까치산"))), now).isEmpty())
        assertTrue(ApiParsers.subway(subway(train(mapOf("subwayId" to "1009"))), now).isEmpty())
        assertTrue(ApiParsers.subway(subway(train(mapOf("trainSttus" to "9"))), now).isEmpty())
    }
    @Test fun `내선과 외선 정차 순서를 구분한다`() {
        val inner = ApiParsers.subway(subway(train()), now).single()
        val outer = inner.copy(direction = "1")
        val first = SubwayNetwork.forVehicle(SubwayNetwork.line("1002")!!.route, inner)
        val reverse = SubwayNetwork.forVehicle(SubwayNetwork.line("1002")!!.route, outer)
        assertEquals("강남", first.stops[3].name)
        assertEquals("교대", first.stops[4].name)
        assertEquals("역삼", reverse.stops[4].name)
    }
    @Test fun `다른 호선의 역코드와 노선별로 겹치는 열차번호를 구분한다`() {
        val row = train(mapOf("subwayId" to "1003", "statnId" to "1003000320", "statnNm" to "구파발", "statnTid" to "1003000352", "statnTnm" to "오금", "updnLine" to "1"))
        val result = ApiParsers.subway(subway(row), now, lineId = "1003").single()
        assertEquals("1003:20261007:0014", result.id)
        assertEquals("하행 · 오금 방면", result.directionLabel)
        assertTrue(ApiParsers.subway(subway(row), now, lineId = "1002").isEmpty())
    }
    @Test fun `미지원 급행은 정차 경로를 추정하지 않고 전역 출발은 도착으로 표시하지 않는다`() {
        assertTrue(ApiParsers.subway(subway(train(mapOf("directAt" to "1"))), now).isEmpty())
        val result = ApiParsers.subway(subway(train(mapOf("trainSttus" to "3"))), now).single()
        assertEquals(Motion.PREVIOUS_DEPARTED, result.motion)
        val route = SubwayNetwork.forVehicle(SubwayNetwork.line("1002")!!.route, result)
        val session = TrackingEngine.begin(route, result, route.stops.indexOfFirst { it.id == result.stopId }, AlertMode.ALIGHT, now)
        assertEquals("강남", session.next!!.name)
        assertEquals(1, session.remainingStops)
        assertTrue(session.location.contains("전역 출발"))
        assertNull(session.window()[3])
        assertEquals("강남", session.window()[4]!!.name)
    }
    @Test fun `9호선 급행은 통과역에서 수신해도 후보로 제공하고 다른 특급은 제외한다`() {
        val line = SubwayNetwork.line("1009")!!
        fun id(name: String) = line.stations.entries.single { it.value == name }.key
        // The public realtimePosition sample reports 사평/등촌 locations with directAt=1.
        val row = train(mapOf("subwayId" to "1009", "statnId" to id("사평"), "statnNm" to "사평", "statnTid" to id("중앙보훈병원"), "statnTnm" to "중앙보훈병원", "updnLine" to "1", "trainSttus" to "2", "directAt" to "1"))
        val express = ApiParsers.subway(subway(row), now, lineId = "1009").single()
        assertEquals("1", express.serviceType)
        assertTrue(express.label.contains("급행"))
        val route = SubwayNetwork.forVehicle(line.route, express)
        val ride = TrackingEngine.begin(route, express, null, AlertMode.ALL, now)
        assertEquals("신논현", ride.next!!.name)
        assertEquals("사평 통과 구간", ride.location)
        assertTrue(ApiParsers.subway(subway(row.put("directAt", "7")), now, lineId = "1009").isEmpty())
    }
    @Test fun `개인키 인코딩을 한 번만 적용하고 plus 문자를 보존한다`() {
        assertEquals("a+b/c=", TransitClient.normalizeTagoKey("a%2Bb%2Fc%3D"))
        assertEquals("a+b/c=", TransitClient.normalizeTagoKey("a+b/c="))
    }
    @Test fun `사용자 동의 없이 HTTP로 키를 전송하지 않는다`() {
        var captured = ""
        val client = TransitClient({ "test_key" }, { false }, Transport { captured = it; subway(train()) })
        client.vehicles(SubwayNetwork.line("1002")!!.route, now)
        assertTrue(captured.startsWith("https://swopenapi.seoul.go.kr/"))
        val consented = TransitClient({ "test_key" }, { true }, Transport { captured = it; subway(train()) })
        consented.vehicles(SubwayNetwork.line("1002")!!.route, now)
        assertTrue(captured.startsWith("http://swopenapi.seoul.go.kr/"))
    }
    @Test fun `API 키가 없으면 네트워크 요청을 하지 않는다`() {
        var requested = false
        val client = TransitClient({ null }, transport = Transport { requested = true; "" })
        assertThrows(TransitException::class.java) { client.cities() }
        assertFalse(requested)
    }
    @Test fun `TAGO 페이지를 모두 가져온다`() {
        val requests = mutableListOf<String>()
        val client = TransitClient({ "a+b=" }, transport = Transport { url ->
            requests += url
            val page = if (url.contains("pageNo=1")) 1 else 2
            JSONObject().put("response", JSONObject().put("header", JSONObject().put("resultCode", "00")).put("body", JSONObject().put("totalCount", 2).put("items", JSONObject().put("item", JSONObject().put("citycode", page).put("cityname", "도시$page"))))).toString()
        })
        assertEquals(2, client.cities().size)
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.contains("serviceKey=a%2Bb%3D") })
    }
    @Test fun `네트워크 수신이 끝난 시점으로 조회 시각을 기록한다`() {
        var receipt = 100L
        val route = TransitRoute("r", "10", "대전", "25", Provider.TAGO, listOf(Stop("a", "기점", 1), Stop("b", "종점", 2)))
        val client = TransitClient({ "test-key" }, transport = Transport {
            receipt = 200L
            tago(JSONObject().put("vehicleno", "대전99가1234").put("nodeid", "a").put("nodeord", 1))
        }, clock = { receipt })
        assertEquals(200L, client.vehicles(route).single().observedAt)
    }
}
