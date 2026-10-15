package kr.nextstop.app

import android.app.NotificationManager
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.pm.ServiceInfo
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import kr.nextstop.app.data.*
import kr.nextstop.app.domain.*
import kr.nextstop.app.tracking.RideNotifications
import kr.nextstop.app.tracking.RideTrackingService
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidUiTest {
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun button(activity: MainActivity, label: String) = views(activity.window.decorView).filterIsInstance<Button>().first { it.text.toString() == label }
    private fun back(activity: MainActivity) = views(activity.window.decorView).first { it.contentDescription == "뒤로" }.performClick()
    private fun texts(activity: MainActivity) = views(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
    private fun insideScroll(view: View): Boolean = generateSequence(view.parent) { it.parent }.any { it is ScrollView }
    private fun session(): Session {
        val now = System.currentTimeMillis()
        val stops = (0..9).map { Stop("s$it", "정류장$it", it) }
        val vehicle = Vehicle("v", "버스1234", "0", "종점 방면", "s4", 4, "정류장4", Motion.NEAR, now, false)
        return TrackingEngine.begin(TransitRoute("r", "10", "대전", "25", Provider.TAGO, stops), vehicle, 7, AlertMode.ALIGHT, now)
    }
    private fun layout(activity: MainActivity, width: Int, height: Int): View {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        content.layout(0, 0, width, height)
        return content
    }
    private fun capture(view: View, name: String) {
        val directory = System.getenv("NEXTSTOP_ANDROID_CAPTURE_DIR")?.let { java.io.File(it) } ?: return
        directory.mkdirs()
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        java.io.File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun finishRequest(activity: MainActivity) {
        ReflectionHelpers.getField<ExecutorService>(activity, "worker").submit {}.get(5, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun tago(items: List<JSONObject>) = JSONObject().put("response", JSONObject().put("header", JSONObject().put("resultCode", "00")).put("body", JSONObject().put("totalCount", items.size).put("items", JSONObject().put("item", JSONArray(items))))).toString()
    @After fun cleanup() {
        val app = RuntimeEnvironment.getApplication()
        SessionStore(app).clear()
        app.getSharedPreferences("personal_api_keys", Context.MODE_PRIVATE).edit().clear().commit()
        RuntimeEnvironment.setFontScale(1f)
        ReflectionHelpers.setStaticField(RideTrackingService::class.java, "running", false)
    }
    @Test fun `키 없이 실행하면 개인키 등록으로 안내하고 네트워크를 호출하지 않는다`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val texts = views(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(texts.contains("개인 API 키를 등록해 주세요"))
        assertTrue(texts.contains("API 키 등록하기"))
        controller.pause().stop().destroy()
    }
    @Test @Config(qualifiers = "w360dp-h640dp-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `지도 아이콘으로 교통수단을 바꾸고 선택 상태와 등록 대상을 유지한다`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        fun marker(tag: String) = views(activity.window.decorView).filterIsInstance<Button>().single { it.tag == tag }
        assertTrue(marker("map-bus").isSelected)
        assertFalse(marker("map-subway").isSelected)
        val content = layout(activity, 360, 592)
        capture(content, "home-map-bus-360")
        for (tag in listOf("map-bus", "map-subway")) {
            val marker = marker(tag)
            assertTrue(marker.width >= 48 && marker.height >= 48)
            assertTrue(marker.compoundDrawables[1] != null)
            assertTrue(marker.isFocusable)
        }
        marker("map-subway").performClick()
        assertTrue(marker("map-subway").isSelected)
        assertFalse(marker("map-bus").isSelected)
        assertTrue(texts(activity).contains("서울 실시간 지하철 API"))
        capture(layout(activity, 360, 592), "home-map-subway-360")
        button(activity, "API 키 등록하기").performClick()
        assertTrue(texts(activity).contains("서울 실시간 지하철 API"))
        back(activity); back(activity)
        assertTrue(marker("map-subway").isSelected)
        marker("map-bus").performClick()
        assertTrue(marker("map-bus").isSelected)
        assertTrue(texts(activity).contains("TAGO 버스 노선·위치 API"))
        RuntimeEnvironment.setFontScale(1.6f)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
        capture(layout(activity, 320, 520), "home-map-320-large-font")
        for (tag in listOf("map-bus", "map-subway")) {
            val pin = marker(tag)
            assertTrue(pin.left >= 0 && pin.right <= (pin.parent as View).width)
            assertTrue(pin.top >= 0 && pin.bottom <= (pin.parent as View).height)
        }
        controller.pause().stop().destroy()
    }
    @Test @Config(qualifiers = "w360dp-h640dp-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `노선도 차량 아이콘과 하차 지점을 직접 선택하고 이전 정류장은 선택할 수 없다`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val ride = session()
        ReflectionHelpers.setField(activity, "route", ride.route.copy(stops = ride.route.stops.map { it.copy(direction = "0") }))
        ReflectionHelpers.setField(activity, "vehicles", listOf(ride.vehicle, ride.vehicle.copy(id = "other", label = "버스5678", stopId = "s6", stopOrder = 6, stopName = "정류장6")))
        ReflectionHelpers.setField(activity, "page", "candidates")
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
        capture(layout(activity, 360, 592), "vehicle-map-360")
        val vehicle = views(activity.window.decorView).filterIsInstance<Button>().single { it.tag == "vehicle-v" }
        assertNotNull(vehicle.compoundDrawables[0])
        vehicle.performClick()
        assertEquals("v", ReflectionHelpers.getField<Vehicle>(activity, "selected").id)
        assertTrue(views(activity.window.decorView).none { it.tag == "alight-4" || it.tag == "alight-3" })
        views(activity.window.decorView).filterIsInstance<Button>().single { it.tag == "alight-6" }.performClick()
        assertEquals(6, ReflectionHelpers.getField<Int>(activity, "alight"))
        assertTrue(views(activity.window.decorView).filterIsInstance<Button>().single { it.tag == "alight-6" }.isSelected)
        assertFalse(insideScroll(button(activity, "이동 추적 시작하기")))
        capture(layout(activity, 360, 592), "alight-map-360")
        back(activity)
        views(activity.window.decorView).filterIsInstance<Button>().single { it.tag == "vehicle-other" }.performClick()
        assertEquals("other", ReflectionHelpers.getField<Vehicle>(activity, "selected").id)
        assertTrue(views(activity.window.decorView).none { it.tag == "alight-6" })
        controller.pause().stop().destroy()
    }
    @Test fun `주변 노선은 시작과 끝에서 존재하는 지점만 연결한다`() {
        val store = SessionStore(RuntimeEnvironment.getApplication())
        val base = session()
        store.write(base)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        for (index in listOf(0, 9)) {
            store.write(base.copy(index = index, vehicle = base.vehicle.copy(stopId = "s$index", stopOrder = index, stopName = "정류장$index")))
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
            val description = views(activity.window.decorView).single { it.contentDescription?.startsWith("주변 노선:") == true }.contentDescription.toString()
            assertFalse(description.contains("경로 없음"))
            assertTrue(description.contains("정류장$index"))
        }
        controller.pause().stop().destroy()
    }
    @Test fun `키 화면은 캡처를 막고 다른 화면에서는 해제한다`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        button(activity, "설정").performClick()
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        assertTrue(views(activity.window.decorView).filterIsInstance<EditText>().isEmpty())
        button(activity, "버스 API · 미등록  ›").performClick()
        val input = views(activity.window.decorView).filterIsInstance<EditText>().single()
        assertFalse(input.isSaveEnabled)
        assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, input.importantForAutofill)
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        back(activity); back(activity)
        assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
        assertTrue(texts(activity).contains("개인 API 키를 등록해 주세요"))
        controller.pause().stop().destroy()
    }
    @Test @Config(qualifiers = "w360dp-h640dp-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `이동 핵심 정보와 하차 완료가 작은 화면에 들어가고 기록은 상세에서 연다`() {
        val ride = session().copy(events = (1..20).map { RideEvent("e$it", "알림 $it", "기록 본문", System.currentTimeMillis()) })
        SessionStore(RuntimeEnvironment.getApplication()).write(ride)
        ReflectionHelpers.setStaticField(RideTrackingService::class.java, "running", true)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val content = layout(activity, 360, 592)
        capture(content, "ride-360")
        val scroll = views(activity.window.decorView).filterIsInstance<ScrollView>().single()
        assertTrue("핵심 화면 높이 ${scroll.getChildAt(0).height}, 가용 높이 ${scroll.height}", scroll.getChildAt(0).height <= scroll.height)
        assertFalse(insideScroll(button(activity, "하차 완료")))
        assertTrue(texts(activity).contains("다음 정류장5"))
        assertTrue(texts(activity).any { it.contains("정류장7 · 3개 지점 남음") })
        assertFalse(texts(activity).contains("알림 20"))
        val summary = views(activity.window.decorView).first { it.contentDescription?.startsWith("주변 노선:") == true }
        assertTrue(summary.contentDescription.contains("정류장3 → 정류장4 → 정류장5"))
        button(activity, "이동 상세").performClick()
        assertTrue(texts(activity).contains("알림 20"))
        button(activity, "전체 노선").performClick()
        assertTrue(texts(activity).any { it.contains("정류장9") })
        button(activity, "이동 화면으로").performClick()
        button(activity, "하차 완료").performClick()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(SessionStore(activity).read())
        button(activity, "하차 완료").performClick()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(SessionStore(activity).read())
        assertTrue(texts(activity).contains("버스"))
        controller.pause().stop().destroy()
    }
    @Test @Config(qualifiers = "w320dp-h568dp-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `큰 글자에서도 하단 버튼은 화면 안에 있고 본문은 스크롤할 수 있다`() {
        val app = RuntimeEnvironment.getApplication()
        RuntimeEnvironment.setFontScale(1.6f)
        val ride = session()
        val stops = ride.route.stops.map { it.copy(name = "아주 긴 이름의 정류장 ${it.order}") }
        SessionStore(app).write(ride.copy(route = ride.route.copy(stops = stops)))
        ReflectionHelpers.setStaticField(RideTrackingService::class.java, "running", true)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val content = layout(activity, 320, 520)
        capture(content, "ride-320-large-font")
        val complete = button(activity, "하차 완료")
        val bounds = android.graphics.Rect()
        complete.getDrawingRect(bounds)
        (content as ViewGroup).offsetDescendantRectToMyCoords(complete, bounds)
        assertTrue(bounds.bottom <= content.height)
        assertTrue(complete.height >= 48)
        assertFalse(insideScroll(complete))
        val scroll = views(content).filterIsInstance<ScrollView>().single()
        assertTrue(scroll.height > 0)
        assertTrue(scroll.getChildAt(0).height > scroll.height)
        controller.pause().stop().destroy()
    }
    @Test fun `선택창에서 알림과 하차 지점을 바꿔도 최신 이동과 기록을 보존한다`() {
        val store = SessionStore(RuntimeEnvironment.getApplication())
        store.write(session())
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        assertTrue(texts(activity).any { it.contains("추적 중단") })
        assertFalse(insideScroll(button(activity, "추적 재개하기")))
        button(activity, "알림: 하차할 때만").performClick()
        val alert = ShadowAlertDialog.getLatestAlertDialog()
        val latest = store.read()!!.copy(index = 5, vehicle = store.read()!!.vehicle.copy(stopId = "s5", stopOrder = 5, stopName = "정류장5"), events = listOf(RideEvent("event", "보존할 알림", "본문", System.currentTimeMillis())))
        store.write(latest)
        alert.listView.performItemClick(alert.listView.getChildAt(1), 1, 1)
        assertEquals(AlertMode.ALL, store.read()!!.mode)
        assertEquals(5, store.read()!!.index)
        assertEquals("보존할 알림", store.read()!!.events.single().title)
        views(activity.window.decorView).first { it.contentDescription?.toString()?.endsWith("하차 지점 변경") == true }.performClick()
        val destination = ShadowAlertDialog.getLatestAlertDialog()
        destination.listView.performItemClick(destination.listView.getChildAt(0), 0, 0)
        assertNull(store.read()!!.alightIndex)
        assertEquals(AlertMode.ALL, store.read()!!.mode)
        controller.pause().stop().destroy()
    }
    @Test fun `검색과 차량 선택을 분리하고 조건과 시작 버튼은 목록 밖에 둔다`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val keys = PersonalKeyStore(activity) { aes }.apply { save(Provider.TAGO, "test-key") }
        ReflectionHelpers.setField(activity, "keys", keys)
        val client = TransitClient(keys::read, transport = Transport { url ->
            when {
                url.contains("getCtyCodeList") -> tago(listOf(JSONObject().put("citycode", "25").put("cityname", "대전")))
                url.contains("getRouteNoList") -> tago((1..30).map { JSONObject().put("routeid", "r$it").put("routeno", "10-$it") })
                url.contains("getRouteAcctoThrghSttnList") -> tago((0..9).map { JSONObject().put("nodeid", "s$it").put("nodenm", "정류장$it").put("nodeord", it) })
                url.contains("getRouteAcctoBusLcList") -> tago((1..25).map { JSONObject().put("vehicleno", "차량$it").put("nodeid", "s4").put("nodeord", 4) })
                else -> error("예상하지 못한 API 요청")
            }
        })
        ReflectionHelpers.setField(activity, "client", client)
        button(activity, "버스").performClick()
        button(activity, "제공 도시 불러오기").performClick(); finishRequest(activity)
        val input = views(activity.window.decorView).filterIsInstance<EditText>().single()
        input.setText("10")
        button(activity, "노선 검색").performClick(); finishRequest(activity)
        assertFalse(insideScroll(views(activity.window.decorView).filterIsInstance<EditText>().single()))
        assertFalse(insideScroll(button(activity, "노선 검색")))
        button(activity, "대전 · 10-1  →").performClick(); finishRequest(activity)
        assertTrue(views(activity.window.decorView).filterIsInstance<EditText>().isEmpty())
        assertFalse(texts(activity).contains("하차·알림 설정"))
        views(activity.window.decorView).filterIsInstance<Button>().first { it.text.startsWith("차량1 ·") }.performClick()
        assertTrue(texts(activity).contains("하차·알림 설정"))
        assertFalse(texts(activity).any { it.startsWith("차량2 ·") })
        assertFalse(insideScroll(button(activity, "이동 추적 시작하기")))
        back(activity); back(activity)
        assertEquals("10", views(activity.window.decorView).filterIsInstance<EditText>().single().text.toString())
        assertTrue(texts(activity).contains("대전 · 10-1  →"))
        controller.pause().stop().destroy()
    }
    @Test fun `지하철 19개 노선 중 3호선을 선택해 방향과 하차 설정을 연다`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val keys = PersonalKeyStore(activity) { aes }.apply { save(Provider.SEOUL, "testkey") }
        ReflectionHelpers.setField(activity, "keys", keys)
        var requested = ""
        val client = TransitClient(keys::read, transport = Transport { url ->
            requested = url
            val local = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Seoul"))
            val train = JSONObject().put("subwayId", "1003").put("statnId", "1003000320").put("statnNm", "구파발")
                .put("trainNo", "0003").put("updnLine", "1").put("trainSttus", "1").put("statnTid", "1003000352").put("statnTnm", "오금").put("directAt", "0")
                .put("recptnDt", local.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .put("lastRecptnDt", local.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")))
            JSONObject().put("errorMessage", JSONObject().put("code", "INFO-000")).put("realtimePositionList", JSONArray().put(train)).toString()
        })
        ReflectionHelpers.setField(activity, "client", client)
        button(activity, "지하철").performClick()
        val lines = views(activity.window.decorView).filterIsInstance<android.widget.Spinner>().single()
        assertEquals(19, lines.adapter.count)
        lines.setSelection((0 until lines.adapter.count).first { lines.adapter.getItem(it) == "3호선" })
        shadowOf(Looper.getMainLooper()).idle()
        button(activity, "운행 열차 조회").performClick(); finishRequest(activity)
        assertTrue(requested.endsWith(java.net.URLEncoder.encode("3호선", "UTF-8")))
        assertEquals("하행", views(activity.window.decorView).filterIsInstance<android.widget.Spinner>().single().adapter.getItem(1))
        val candidate = views(activity.window.decorView).filterIsInstance<Button>().first { it.text.startsWith("열차 0003") }
        assertTrue(candidate.text.contains("하행 · 오금 방면"))
        candidate.performClick()
        assertTrue(texts(activity).contains("하차·알림 설정"))
        assertTrue(texts(activity).any { it.startsWith("3호선 · 하행") })
        assertFalse(insideScroll(button(activity, "이동 추적 시작하기")))
        controller.pause().stop().destroy()
    }
    @Test fun `확장 알림과 큰 글자 요약을 생성한다`() {
        val context = RuntimeEnvironment.getApplication()
        val now = System.currentTimeMillis()
        val stops = (0..9).map { Stop("s$it", "정류장$it", it) }
        val vehicle = Vehicle("v", "버스1234", "0", "종점 방면", "s4", 4, "정류장4", Motion.NEAR, now, false)
        val session = TrackingEngine.begin(TransitRoute("r", "10", "대전", "25", Provider.TAGO, stops), vehicle, 7, AlertMode.ALL, now)
        val notifications = RideNotifications(context)
        val normal = notifications.status(session)
        assertNotNull(normal.bigContentView)
        assertEquals(1, normal.actions.size)
        val config = android.content.res.Configuration(context.resources.configuration).apply { fontScale = 1.5f }
        val large = RideNotifications(context.createConfigurationContext(config)).status(session)
        assertTrue(large.extras.getCharSequence("android.bigText").toString().contains("정류장7"))
        assertEquals(2, context.getSystemService(NotificationManager::class.java).notificationChannels.size)
    }
    @Test fun `급행 차량의 하차 선택과 이동 화면 및 알림은 정차역만 안내한다`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val line = SubwayNetwork.line("1009")!!
        fun id(name: String) = line.stations.entries.single { it.value == name }.key
        val now = System.currentTimeMillis()
        val train = Vehicle("1009:20261015:9001", "열차 9001 · 급행", "1", "하행 · 중앙보훈병원 방면", id("김포공항"), null, "김포공항", Motion.DEPARTED, now, true, id("중앙보훈병원"), "중앙보훈병원", "1")
        ReflectionHelpers.setField(activity, "route", line.route)
        ReflectionHelpers.setField(activity, "vehicles", listOf(train))
        ReflectionHelpers.setField(activity, "page", "candidates")
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
        views(activity.window.decorView).filterIsInstance<Button>().first { it.text.startsWith("열차 9001 · 급행") }.performClick()
        assertTrue(texts(activity).any { it.startsWith("급행 정차역만") })
        views(activity.window.decorView).filterIsInstance<Button>().first { it.text.startsWith("하차:") }.performClick()
        val boarding = ShadowAlertDialog.getLatestAlertDialog()
        val options = (0 until boarding.listView.adapter.count).map { boarding.listView.adapter.getItem(it).toString() }
        assertEquals("마곡나루", options[1])
        assertTrue(options.contains("가양"))
        assertFalse(options.contains("공항시장"))
        assertFalse(options.contains("양천향교"))
        boarding.dismiss()
        val route = line.forVehicle(train)!!
        val ride = TrackingEngine.begin(route, train, route.stops.indexOfFirst { it.name == "가양" }, AlertMode.ALIGHT, now)
        SessionStore(activity).write(ride)
        ReflectionHelpers.setField(activity, "page", "ride")
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
        assertTrue(texts(activity).any { it.startsWith("9호선 급행 ·") })
        assertTrue(texts(activity).contains("다음 마곡나루"))
        assertTrue(texts(activity).any { it.contains("가양 · 2개 지점 남음") })
        views(activity.window.decorView).first { it.contentDescription?.toString()?.endsWith("하차 지점 변경") == true }.performClick()
        val change = ShadowAlertDialog.getLatestAlertDialog()
        assertFalse((0 until change.listView.adapter.count).any { change.listView.adapter.getItem(it) == "공항시장" })
        change.dismiss()
        val notification = RideNotifications(activity).status(ride)
        assertTrue(notification.extras.getCharSequence("android.title").toString().startsWith("9호선 급행"))
        assertTrue(notification.extras.getCharSequence("android.subText").toString().contains("다음 마곡나루"))
        button(activity, "이동 상세").performClick()
        button(activity, "전체 노선").performClick()
        assertTrue(texts(activity).any { it.contains("공항시장") && it.contains("통과") })
        controller.pause().stop().destroy()
    }
    @Test fun `추적 서비스는 외부 공개 없이 dataSync로 선언한다`() {
        val context = RuntimeEnvironment.getApplication()
        val info = context.packageManager.getServiceInfo(ComponentName(context, RideTrackingService::class.java), 0)
        assertFalse(info.exported)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, info.foregroundServiceType)
    }
}
