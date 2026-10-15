package kr.nextstop.app.data

import kr.nextstop.app.domain.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

fun interface Transport { fun get(url: String): String }

class DirectTransport : Transport {
    override fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 12_000
            connection.readTimeout = 12_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            if (code == 401 || code == 403) throw TransitException("개인 API 키와 서비스 활용 승인을 확인해 주세요.", true)
            if (code == 429) throw TransitException("API 호출 한도를 확인해 주세요. 추적을 중단합니다.", true)
            if (code !in 200..299) throw TransitException("교통 API 연결에 실패했습니다. 잠시 후 다시 시도해 주세요.")
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val result = StringBuilder()
                val buffer = CharArray(4096)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    result.append(buffer, 0, count)
                    if (result.length > 4_000_000) throw TransitException("교통 API 응답이 너무 큽니다.")
                }
                result.toString()
            }
        } catch (safe: TransitException) { throw safe }
        catch (_: Exception) { throw TransitException("네트워크 연결을 확인해 주세요. 서울 API 연결이 실패하면 API 키 화면에서 연결 방식을 확인해 주세요.") }
        finally { connection.disconnect() }
    }
}

class TransitClient(private val keys: (Provider) -> String?, private val allowSeoulHttp: () -> Boolean = { false }, private val transport: Transport = DirectTransport(), private val clock: () -> Long = System::currentTimeMillis) {
    private fun key(provider: Provider): String = keys(provider)?.takeIf { it.isNotBlank() } ?: throw TransitException("API 키 화면에서 개인 키를 먼저 등록해 주세요.", true)
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    private fun tago(service: String, operation: String, params: Map<String, String> = emptyMap()): List<org.json.JSONObject> {
        val entered = key(Provider.TAGO)
        val decoded = normalizeTagoKey(entered)
        val items = mutableListOf<org.json.JSONObject>()
        var page = 1
        while (true) {
            val parameters = linkedMapOf("serviceKey" to decoded, "_type" to "json", "numOfRows" to "1000", "pageNo" to page.toString()) + params
            val url = "https://apis.data.go.kr/1613000/$service/$operation?" + parameters.entries.joinToString("&") { "${it.key}=${encode(it.value)}" }
            val result = ApiParsers.tago(transport.get(url))
            items += result.items
            if (items.size >= result.total || result.items.isEmpty()) return items
            if (++page > 10) throw TransitException("노선 응답이 너무 많습니다. 검색 범위를 줄여 주세요.")
        }
    }
    fun cities(): List<City> = tago("BusRouteInfoInqireService", "getCtyCodeList").map { City(it.optString("citycode"), it.optString("cityname")) }.filter { it.code.isNotBlank() && it.name.isNotBlank() }.sortedBy { it.name }
    fun verifyTago(): List<City> {
        val routeCities = cities()
        tago("BusLcInfoInqireService", "getCtyCodeList")
        return routeCities
    }
    fun routes(city: City, query: String): List<TransitRoute> = tago("BusRouteInfoInqireService", "getRouteNoList", mapOf("cityCode" to city.code, "routeNo" to query)).map { TransitRoute(it.optString("routeid"), it.optString("routeno"), city.name, city.code, Provider.TAGO) }.filter { it.id.isNotBlank() && it.name.isNotBlank() }.distinctBy { it.id }
    fun stops(route: TransitRoute): TransitRoute {
        val stops = tago("BusRouteInfoInqireService", "getRouteAcctoThrghSttnList", mapOf("cityCode" to route.cityCode, "routeId" to route.id)).mapNotNull {
            val order = it.optString("nodeord").toIntOrNull() ?: return@mapNotNull null
            val id = it.optString("nodeid")
            val name = it.optString("nodenm")
            if (id.isBlank() || name.isBlank()) null else Stop(id, name, order, it.optString("updowncd"))
        }.sortedBy { it.order }
        if (stops.size < 2 || stops.map { it.order }.distinct().size != stops.size) throw TransitException("정차 순서를 확인할 수 없는 노선입니다.")
        return route.copy(stops = stops)
    }
    fun vehicles(route: TransitRoute, now: Long? = null, includeStale: Boolean = false): List<Vehicle> {
        if (route.provider == Provider.SEOUL) {
            val protocol = if (allowSeoulHttp()) "http" else "https"
            val line = SubwayNetwork.line(route.id) ?: throw TransitException("지원 노선 목록에서 지하철을 다시 선택해 주세요.")
            val url = "$protocol://swopenapi.seoul.go.kr/api/subway/${encode(key(Provider.SEOUL))}/json/realtimePosition/0/1000/${encode(line.name)}" 
            val response = transport.get(url)
            return ApiParsers.subway(response, now ?: clock(), includeStale, line.id)
        }
        val items = tago("BusLcInfoInqireService", "getRouteAcctoBusLcList", mapOf("cityCode" to route.cityCode, "routeId" to route.id))
        val root = org.json.JSONObject().put("response", org.json.JSONObject().put("header", org.json.JSONObject().put("resultCode", "00")).put("body", org.json.JSONObject().put("items", org.json.JSONObject().put("item", org.json.JSONArray(items)))))
        return ApiParsers.buses(root.toString(), route, now ?: clock())
    }
    companion object {
        fun normalizeTagoKey(value: String): String = if (Regex("%[0-9a-fA-F]{2}").containsMatchIn(value)) URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") else value
    }
}
