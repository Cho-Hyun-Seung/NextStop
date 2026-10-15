package kr.nextstop.app.data

import kr.nextstop.app.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class TransitException(val safeMessage: String, val fatal: Boolean = false) : Exception(safeMessage)
data class TagoPage(val items: List<JSONObject>, val total: Int)

object ApiParsers {
    fun tago(raw: String): TagoPage {
        if (raw.trimStart().startsWith("<")) {
            val code = Regex("<(?:returnReasonCode|resultCode)>([^<]+)</").find(raw)?.groupValues?.get(1).orEmpty()
            throw codeError(code)
        }
        val response = JSONObject(raw).optJSONObject("response") ?: throw TransitException("교통 API 응답 형식을 확인할 수 없습니다.")
        val header = response.optJSONObject("header") ?: throw TransitException("교통 API 결과 코드가 없습니다.")
        val code = header.optString("resultCode")
        if (code !in listOf("00", "0", "000")) throw codeError(code)
        val body = response.optJSONObject("body") ?: return TagoPage(emptyList(), 0)
        val item = body.optJSONObject("items")?.opt("item")
        val items = when (item) {
            is JSONArray -> (0 until item.length()).map { item.getJSONObject(it) }
            is JSONObject -> listOf(item)
            else -> emptyList()
        }
        return TagoPage(items, body.optInt("totalCount", items.size))
    }

    private fun codeError(code: String): TransitException = when (code) {
        "20", "30", "31", "32", "21" -> TransitException("개인 API 키와 해당 서비스의 활용 승인을 확인해 주세요.", true)
        "22" -> TransitException("API 일일 호출 한도를 초과했습니다. 추적을 중단합니다.", true)
        else -> TransitException("교통 API가 요청을 처리하지 못했습니다. 코드 ${code.filter { it.isDigit() }.take(4).ifEmpty { "확인 불가" }}")
    }

    fun buses(raw: String, route: TransitRoute, now: Long): List<Vehicle> = tago(raw).items.mapNotNull { item ->
        val id = item.optString("vehicleno")
        val order = item.optString("nodeord").toIntOrNull()
        val stopId = item.optString("nodeid")
        val stop = route.stops.firstOrNull { it.id == stopId && it.order == order }
        if (id.isBlank() || stop == null) return@mapNotNull null
        val directional = if (stop.direction.isEmpty()) route.stops else route.stops.filter { it.direction == stop.direction }
        Vehicle(id, id, stop.direction, "${directional.first().name} → ${directional.last().name}", stopId, order, stop.name, Motion.NEAR, now, false)
    }.distinctBy { it.id }

    fun subway(raw: String, now: Long, includeStale: Boolean = false, lineId: String? = null): List<Vehicle> {
        val root = JSONObject(raw)
        val error = root.optJSONObject("errorMessage")
        val code = error?.optString("code").orEmpty()
        if (code.isNotEmpty() && code != "INFO-000") {
            if (code == "INFO-200") return emptyList()
            throw TransitException(if (code == "ERROR-300" || code == "ERROR-301" || code == "ERROR-310") "서울 실시간 지하철 API 키와 활용 승인을 확인해 주세요." else "서울 API가 요청을 처리하지 못했습니다.", code.startsWith("ERROR-3"))
        }
        val items = root.optJSONArray("realtimePositionList") ?: throw TransitException("서울 실시간 열차 위치 응답을 확인할 수 없습니다.")
        return (0 until items.length()).mapNotNull { index ->
            val item = items.getJSONObject(index)
            val train = item.optString("trainNo")
            val stopId = item.optString("statnId")
            val suppliedLineId = item.optString("subwayId")
            if (lineId != null && suppliedLineId != lineId) return@mapNotNull null
            val line = SubwayNetwork.line(suppliedLineId) ?: return@mapNotNull null
            if (train.isBlank() || stopId !in line.stations) return@mapNotNull null
            val timestamp = runCatching { LocalDateTime.parse(item.optString("recptnDt"), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).atZone(ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli() }.getOrNull() ?: return@mapNotNull null
            if (!includeStale && now - timestamp !in 0..TrackingEngine.STALE_MS) return@mapNotNull null
            val direction = item.optString("updnLine")
            if (direction !in listOf("0", "1")) return@mapNotNull null
            val motion = when (item.optString("trainSttus")) { "0" -> Motion.APPROACHING; "1" -> Motion.ARRIVED; "2" -> Motion.DEPARTED; "3" -> Motion.PREVIOUS_DEPARTED; else -> return@mapNotNull null }
            val destination = item.optString("statnTnm")
            val destinationId = item.optString("statnTid")
            val serviceType = item.optString("directAt").ifBlank { "0" }
            if (serviceType !in listOf("0", "1", "7")) return@mapNotNull null
            val serviceDate = item.optString("lastRecptnDt").ifEmpty { item.optString("recptnDt").take(10).replace("-", "") }
            if (!Regex("\\d{8}").matches(serviceDate)) return@mapNotNull null
            val vehicle = Vehicle("${line.id}:$serviceDate:$train", "열차 $train${if (serviceType == "0") "" else if (serviceType == "7") " · 특급" else " · 급행"}", direction,
                line.directionLabel(direction, stopId, destinationId.ifBlank { line.stations.entries.firstOrNull { it.value == destination }?.key.orEmpty() }, destination),
                stopId, null, item.optString("statnNm").ifBlank { line.stations.getValue(stopId) }, motion, timestamp, true, destinationId, destination, serviceType)
            if (!includeStale && !line.canStart(vehicle)) return@mapNotNull null
            vehicle
        }.distinctBy { it.id }
    }
}

