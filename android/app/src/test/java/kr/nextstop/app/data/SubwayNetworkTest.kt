package kr.nextstop.app.data

import kr.nextstop.app.domain.*
import org.junit.Assert.*
import org.junit.Test

class SubwayNetworkTest {
    private val now = 1_800_000_000_000L
    private fun vehicle(lineId: String, from: String, to: String, direction: String = "1", motion: Motion = Motion.ARRIVED): Vehicle {
        val line = SubwayNetwork.line(lineId)!!
        fun id(name: String) = line.stations.entries.single { it.value == name }.key
        return Vehicle("$lineId:20270115:001", "열차 001", direction, "$to 방면", id(from), null, from, motion, now, true, id(to), to)
    }
    private fun onward(lineId: String, from: String, to: String, direction: String = "1"): List<String> {
        val train = vehicle(lineId, from, to, direction)
        val route = SubwayNetwork.line(lineId)!!.forVehicle(train)!!
        return route.stops.dropWhile { it.id != train.stopId }.map { it.name }
    }
    @Test fun `서울시 공식 역정보의 19개 노선과 모든 역을 연결한다`() {
        assertEquals(19, SubwayNetwork.lines.size)
        assertTrue(SubwayNetwork.lines.map { it.id }.containsAll((1001..1009).map(Int::toString) + listOf("1063", "1065", "1067", "1075", "1077", "1092", "1094")))
        assertEquals(696, SubwayNetwork.lines.sumOf { it.stations.size })
        SubwayNetwork.lines.forEach { line ->
            val covered = (line.segments.flatten() + line.ring).toSet()
            assertEquals(line.name, line.stations.keys, covered)
            line.segments.forEach { assertTrue(it.size >= 2); assertEquals(it.size, it.distinct().size) }
        }
    }
    @Test fun `3호선 상행과 하행을 행선지까지 안내한다`() {
        assertEquals(listOf("구파발", "연신내", "불광"), onward("1003", "구파발", "오금").take(3))
        assertEquals(listOf("구파발", "지축", "삼송"), onward("1003", "구파발", "대화", "0").take(3))
        assertEquals("대화", onward("1003", "구파발", "대화", "0").last())
    }
    @Test fun `1호선 구로 분기와 광명 서동탄 지선을 구분한다`() {
        assertEquals("구일", onward("1001", "구로", "인천")[1])
        assertEquals("가산디지털단지", onward("1001", "구로", "신창")[1])
        assertEquals(listOf("금천구청", "광명"), onward("1001", "금천구청", "광명"))
        assertEquals("병점", onward("1001", "서동탄", "연천", "0")[1])
        assertFalse(onward("1001", "구로", "인천").contains("가산디지털단지"))
    }
    @Test fun `2호선 두 지선을 본선과 연결하지 않고 각각 안내한다`() {
        assertEquals(listOf("성수", "용답", "신답", "용두", "신설동"), onward("1002", "성수", "신설동"))
        assertEquals(listOf("신도림", "도림천", "양천구청", "신정네거리", "까치산"), onward("1002", "신도림", "까치산"))
        assertEquals(listOf("신설동", "용두", "신답", "용답", "성수"), onward("1002", "신설동", "성수", "0"))
    }
    @Test fun `2호선 내선 외선과 순환 종착을 처리한다`() {
        assertEquals("교대", onward("1002", "강남", "성수", "0")[1])
        assertEquals("역삼", onward("1002", "강남", "성수", "1")[1])
        val loop = onward("1002", "성수", "성수", "0")
        assertEquals(44, loop.size); assertEquals("성수", loop.first()); assertEquals("성수", loop.last())
    }
    @Test fun `5호선 강동에서 하남과 마천 경로를 구분한다`() {
        assertEquals("길동", onward("1005", "강동", "하남검단산")[1])
        assertEquals("둔촌동", onward("1005", "강동", "마천")[1])
        assertFalse(onward("1005", "방화", "마천").contains("하남검단산"))
    }
    @Test fun `6호선 응암 순환은 역촌 불광 독바위 연신내 구산 순서로만 돈다`() {
        assertEquals(listOf("역촌", "불광", "독바위", "연신내", "구산", "응암순환(상선)"), onward("1006", "역촌", "응암순환(상선)", "0"))
        assertEquals("새절(신사)", onward("1006", "응암순환(상선)", "신내")[1])
    }
    @Test fun `경의중앙선 효창공원앞과 서울역 지선을 연결한다`() {
        assertEquals(listOf("용산", "효창공원앞", "공덕"), onward("1063", "용산", "임진강", "0").take(3))
        assertEquals(listOf("가좌", "신촌(경의중앙선)", "서울"), onward("1063", "가좌", "서울"))
        assertEquals("홍대입구", onward("1063", "가좌", "지평")[1])
    }
    @Test fun `공항철도는 추가 개통 역을 실제 순서에 배치한다`() {
        assertEquals("마곡나루", onward("1065", "디지털미디어시티", "인천공항2터미널")[1])
        assertEquals(listOf("검암", "청라국제도시", "영종", "운서"), onward("1065", "검암", "인천공항2터미널").take(4))
    }
    @Test fun `GTX A의 분리된 운행 구간을 임의로 잇지 않는다`() {
        val line = SubwayNetwork.line("1032")!!
        assertNull(line.forVehicle(vehicle("1032", "서울", "수서")))
        assertEquals(listOf("수서", "성남", "구성", "동탄"), onward("1032", "수서", "동탄"))
    }
    @Test fun `행선지가 다른 방향이거나 모르는 역과 정차 정보가 없는 급행은 경로를 추정하지 않는다`() {
        val line = SubwayNetwork.line("1003")!!
        assertNull(line.forVehicle(vehicle("1003", "구파발", "오금", "0")))
        assertNull(line.forVehicle(vehicle("1003", "구파발", "오금").copy(stopId = "unknown")))
        assertNull(line.forVehicle(vehicle("1003", "구파발", "오금").copy(destinationId = "unknown")))
        assertNull(line.forVehicle(vehicle("1003", "구파발", "오금").copy(serviceType = "1")))
    }
    @Test fun `합류역에서 지나온 지선은 관측 없이 단정하지 않는다`() {
        val train = vehicle("1001", "구로", "연천", "0")
        val route = SubwayNetwork.line("1001")!!.forVehicle(train)!!
        assertEquals("구로", route.stops.first().name)
        assertEquals("신도림", route.stops[1].name)
    }
    @Test fun `9호선 급행은 공식 16개 정차역을 양방향으로 안내한다`() {
        val line = SubwayNetwork.line("1009")!!
        val forward = vehicle("1009", "김포공항", "중앙보훈병원").copy(serviceType = "1")
        val stops = line.forVehicle(forward)!!.stops
        val expected = listOf("김포공항", "마곡나루", "가양", "염창", "당산", "여의도", "노량진", "동작", "고속터미널", "신논현", "선정릉", "봉은사", "종합운동장", "석촌", "올림픽공원", "중앙보훈병원")
        assertEquals(expected, stops.filter { it.isScheduledStop }.map { it.name })
        assertFalse(stops.first { it.name == "공항시장" }.isScheduledStop)
        assertFalse(stops.any { it.name == "개화" })
        val backward = vehicle("1009", "중앙보훈병원", "김포공항", "0").copy(serviceType = "1")
        assertEquals(expected.reversed(), line.forVehicle(backward)!!.stops.filter { it.isScheduledStop }.map { it.name })
        assertTrue(line.canStart(forward)); assertTrue(line.canStart(backward))
    }
    @Test fun `급행 통과역 위치도 추적하되 하차 목록에는 정차역만 넣는다`() {
        val line = SubwayNetwork.line("1009")!!
        val train = vehicle("1009", "사평", "중앙보훈병원", motion = Motion.DEPARTED).copy(serviceType = "1")
        val route = line.forVehicle(train)!!
        val index = route.stops.indexOfFirst { it.id == train.stopId }
        assertFalse(route.stops[index].isScheduledStop)
        assertEquals(listOf("노량진", "동작", "고속터미널"), route.stops.take(index).filter { it.isScheduledStop }.map { it.name })
        assertEquals("신논현", route.stops[route.alightOptions(index, false).first()].name)
        assertTrue(line.canStart(train))
        assertNull(line.forVehicle(train.copy(serviceType = "7")))
        assertNull(line.forVehicle(vehicle("1009", "김포공항", "개화", "0").copy(serviceType = "1")))
        assertNull(line.forVehicle(vehicle("1009", "개화", "중앙보훈병원").copy(serviceType = "1")))
    }
    @Test fun `일반 열차는 급행이 통과하는 역에도 정차한다`() {
        val train = vehicle("1009", "김포공항", "중앙보훈병원")
        val route = SubwayNetwork.line("1009")!!.forVehicle(train)!!
        assertTrue(route.stops.all { it.isScheduledStop })
        assertEquals("공항시장", route.stops[route.stops.indexOfFirst { it.id == train.stopId } + 1].name)
    }
}
