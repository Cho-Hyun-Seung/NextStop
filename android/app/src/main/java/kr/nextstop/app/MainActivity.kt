package kr.nextstop.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.text.TextUtils
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import kr.nextstop.app.data.*
import kr.nextstop.app.domain.*
import kr.nextstop.app.tracking.RideTrackingService
import kr.nextstop.app.ui.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var header: LinearLayout
    private lateinit var controls: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var footer: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var notice: TextView
    private lateinit var store: SessionStore
    private lateinit var keys: PersonalKeyStore
    private lateinit var client: TransitClient
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var page = "home"
    private var subway = false
    private var subwayLineId = "1002"
    private var cities = emptyList<City>()
    private var city: City? = null
    private var query = ""
    private var results = emptyList<TransitRoute>()
    private var route: TransitRoute? = null
    private var vehicles = emptyList<Vehicle>()
    private var direction: String? = null
    private var selected: Vehicle? = null
    private var alight: Int? = null
    private var mode = AlertMode.ALIGHT
    private var busy = false
    private var error = ""
    private var lastRide = ""
    private var afterPermission: (() -> Unit)? = null
    private var requestVersion = 0
    private var resumed = false
    private var settingsReturnPage = "home"
    private var editingProvider = Provider.TAGO
    private data class SearchDraft(val cities: List<City>, val city: City?, val results: List<TransitRoute>, val route: TransitRoute?, val vehicles: List<Vehicle>, val direction: String?, val selected: Vehicle?, val alight: Int?, val mode: AlertMode)
    private val tick = object : Runnable {
        override fun run() {
            if (page == "ride") {
                val session = store.read()
                val signature = session?.let { SessionCodec.encode(it) }.orEmpty() + RideTrackingService.running
                if (signature != lastRide) render()
            }
            if (resumed) main.postDelayed(this, 1000)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SessionStore(this); keys = PersonalKeyStore(this)
        client = TransitClient(keys::read, { store.allowSeoulHttp })
        page = savedInstanceState?.getString("page") ?: intent.getStringExtra("screen") ?: if (store.read() != null) "ride" else "home"
        subway = savedInstanceState?.getBoolean("subway") ?: false
        subwayLineId = savedInstanceState?.getString("subwayLineId") ?: "1002"
        query = savedInstanceState?.getString("query").orEmpty()
        settingsReturnPage = savedInstanceState?.getString("settingsReturnPage") ?: if (store.read() != null) "ride" else "home"
        editingProvider = savedInstanceState?.getString("editingProvider")?.let { runCatching { Provider.valueOf(it) }.getOrNull() } ?: Provider.TAGO
        (lastNonConfigurationInstance as? SearchDraft)?.let {
            cities = it.cities; city = it.city; results = it.results; route = it.route; vehicles = it.vehicles
            direction = it.direction; selected = it.selected; alight = it.alight; mode = it.mode
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(IVORY) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) { val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime()); view.setPadding(bars.left, bars.top, bars.right, bars.bottom) }
            else view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(56); setPadding(dp(12), dp(4), dp(12), dp(4)) }
        root.addView(header)
        notice = text("", 13, SECONDARY).apply { setPadding(dp(20), dp(6), dp(20), dp(6)); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        root.addView(notice)
        controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), dp(4)) }
        root.addView(controls)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), dp(8)) }
        scroll = ScrollView(this).apply { isFillViewport = true; addView(body); clipToPadding = false }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        footer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(12)); setBackgroundColor(SURFACE) }
        root.addView(footer)
        setContentView(root)
        if (Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { goBack() }
        root.requestApplyInsets()
        render()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("page", page); outState.putString("query", query); outState.putBoolean("subway", subway)
        outState.putString("subwayLineId", subwayLineId)
        outState.putString("settingsReturnPage", settingsReturnPage); outState.putString("editingProvider", editingProvider.name)
        super.onSaveInstanceState(outState)
    }
    override fun onRetainNonConfigurationInstance(): Any = SearchDraft(cities, city, results, route, vehicles, direction, selected, alight, mode)
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); navigate(intent.getStringExtra("screen") ?: if (store.read() != null) "ride" else "home") }
    override fun onResume() { super.onResume(); resumed = true; render(); main.post(tick) }
    override fun onPause() { resumed = false; main.removeCallbacks(tick); super.onPause() }
    override fun onDestroy() { requestVersion++; worker.shutdownNow(); main.removeCallbacksAndMessages(null); super.onDestroy() }
    @Deprecated("Framework back compatibility")
    // API 33+ uses the OnBackInvokedCallback registered in onCreate; this serves API 26–32.
    @SuppressLint("GestureBackNavigation")
    override fun onBackPressed() = goBack()
    private fun goBack() {
        when (page) {
            "home", "ride" -> finish()
            "keys" -> navigate(settingsReturnPage.takeIf { it !in listOf("keys", "key") } ?: "home")
            "key" -> navigate("keys")
            "boarding" -> navigate("candidates")
            "candidates" -> navigate("home")
            else -> navigate("ride")
        }
    }
    private fun navigate(destination: String) {
        if (destination == "keys" && page !in listOf("keys", "key")) settingsReturnPage = page
        hideKeyboard()
        requestVersion++; busy = false; error = ""; page = destination
        render(); scroll.scrollTo(0, 0)
    }
    private fun render() {
        if (isDestroyed) return
        if ((page == "candidates" && route == null) || (page == "boarding" && (route == null || selected == null))) page = "home"
        if (page in listOf("keys", "key")) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        header.removeAllViews(); controls.removeAllViews(); body.removeAllViews(); footer.removeAllViews()
        renderHeader()
        when (page) {
            "ride" -> renderRide()
            "details" -> renderRideDetails()
            "route" -> renderFullRoute()
            "keys" -> renderKeys()
            "key" -> renderKeyEditor()
            "candidates" -> route?.let(::renderCandidates) ?: renderHome()
            "boarding" -> renderBoarding()
            else -> renderHome()
        }
        notice.text = error.ifEmpty { if (busy) "교통 정보를 조회하고 있어요…" else "" }
        notice.setTextColor(if (error.isNotEmpty()) DANGER else SECONDARY)
        notice.visibility = if (notice.text.isEmpty()) View.GONE else View.VISIBLE
        controls.visibility = if (controls.childCount == 0) View.GONE else View.VISIBLE
        footer.visibility = if (footer.childCount == 0) View.GONE else View.VISIBLE
    }
    private fun renderHeader() {
        val title = when (page) { "keys" -> "설정"; "key" -> "API 키"; "candidates" -> "차량 선택"; "boarding" -> "하차·알림 설정"; "details" -> "이동 상세"; "route" -> "전체 노선"; else -> "nextstop." }
        if (page !in listOf("home", "ride")) header.addView(button("‹", false) { goBack() }.apply { contentDescription = "뒤로"; background = shape(IVORY) }, LinearLayout.LayoutParams(dp(48), -2))
        header.addView(text(title, if (page in listOf("home", "ride")) 23 else 20, BLUE, true), LinearLayout.LayoutParams(0, -2, 1f))
        if (page !in listOf("keys", "key")) header.addView(button("설정", false) { navigate("keys") }.apply { background = shape(IVORY) }, LinearLayout.LayoutParams(-2, -2))
    }
    private fun primaryAction(label: String, action: () -> Unit) {
        footer.addView(button(label, true, action).apply { isEnabled = !busy; alpha = if (busy) .55f else 1f; layoutParams = LinearLayout.LayoutParams(-1, -2) })
    }
    private fun renderHome() {
        store.read()?.let { controls.addView(button("진행 중인 이동 확인 →", false) { navigate("ride") }) }
        body.addView(TransportMapView(this, subway) { if (!busy) changeTransport(it) })
        val provider = if (subway) Provider.SEOUL else Provider.TAGO
        if (!keys.has(provider)) {
            val box = card()
            box.addView(text("개인 API 키를 등록해 주세요", 16, TEXT, true).apply {
                setCompoundDrawablesWithIntrinsicBounds(TransitIcon(TransitSymbol.LOCK, BLUE, dp(24)), null, null, null); compoundDrawablePadding = dp(10)
            })
            box.addView(text(if (subway) "서울 실시간 지하철 API" else "TAGO 버스 노선·위치 API", 13, SECONDARY).apply { setPadding(dp(34), dp(4), 0, 0) })
            body.addView(box)
            primaryAction("API 키 등록하기") { openKey(provider) }; return
        }
        if (subway) {
            val lines = SubwayNetwork.lines
            controls.addView(spinner(lines.map { it.name }, lines.indexOfFirst { it.id == subwayLineId }.coerceAtLeast(0)) {
                if (subwayLineId != lines[it].id) { subwayLineId = lines[it].id; route = null; selected = null; vehicles = emptyList(); render() }
            }.apply { contentDescription = "지하철 노선 선택" })
            val lineGrid = GridLayout(this).apply { columnCount = if (resources.configuration.screenWidthDp < 350 || resources.configuration.fontScale >= 1.3f) 2 else 3; useDefaultMargins = true }
            lines.forEach { line ->
                lineGrid.addView(button(line.name, line.id == subwayLineId) {
                    if (subwayLineId != line.id) { subwayLineId = line.id; route = null; selected = null; vehicles = emptyList(); render() }
                }.apply {
                    textSize = 13f; setPadding(dp(8), dp(10), dp(8), dp(10)); isSelected = line.id == subwayLineId
                    contentDescription = "${line.name} 노선 선택${if (isSelected) ", 선택됨" else ""}"
                    setCompoundDrawablesWithIntrinsicBounds(TransitIcon(TransitSymbol.SUBWAY, if (isSelected) Color.WHITE else BLUE, dp(20)), null, null, null); compoundDrawablePadding = dp(4)
                }, GridLayout.LayoutParams().apply { width = 0; height = -2; columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f) })
            }
            body.addView(lineGrid)
            body.addView(text("${lines.size}개 노선 · 일반 / 9호선 급행", 12, SECONDARY).apply { setPadding(0, dp(8), 0, 0) })
            primaryAction("운행 열차 조회") { loadRoute(lines.first { it.id == subwayLineId }.route) }
        } else {
            if (cities.isEmpty()) {
                body.addView(text("지역 선택 → 노선 검색 → 차량 선택", 14, SECONDARY).apply { setPadding(0, dp(8), 0, dp(8)) })
                primaryAction("제공 도시 불러오기") { fetch({ client.cities() }) { cities = it; city = it.firstOrNull(); if (it.isEmpty()) error = "제공 도시가 없습니다. API 연결을 확인해 주세요." } }
            }
            else {
                controls.addView(spinner(cities.map { it.name }, cities.indexOf(city).coerceAtLeast(0)) {
                    if (city?.code != cities[it].code) { city = cities[it]; route = null; vehicles = emptyList(); selected = null; results = emptyList(); render() }
                })
                val input = EditText(this).apply {
                    hint = "버스 노선 번호"; setText(query); setTextColor(TEXT); textSize = 16f; setSingleLine(true); inputType = InputType.TYPE_CLASS_TEXT
                    minHeight = dp(48); imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { query = s.toString() }
                        override fun afterTextChanged(s: Editable?) = Unit
                    })
                    setOnEditorActionListener { _, action, _ -> if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { searchRoutes(); true } else false }
                }
                controls.addView(input)
                primaryAction("노선 검색", ::searchRoutes)
                results.take(50).forEach { result -> body.addView(button("${result.city} · ${result.name}  →", false) { loadRoute(result) }.apply {
                    gravity = Gravity.CENTER_VERTICAL or Gravity.START
                    setCompoundDrawablesWithIntrinsicBounds(TransitIcon(TransitSymbol.BUS, BLUE, dp(28)), null, null, null); compoundDrawablePadding = dp(12)
                }) }
                if (results.size > 50) body.addView(text("검색 결과가 많아 50개를 표시합니다. 노선 번호를 더 구체적으로 입력해 주세요.", 12, SECONDARY))
            }
            if (results.isEmpty()) body.addView(text("TAGO 제공 도시 · 위치 제공 노선", 12, SECONDARY).apply { setPadding(0, dp(8), 0, 0) })
        }
    }
    private fun changeTransport(value: Boolean) {
        requestVersion++; busy = false
        subway = value; route = null; selected = null; vehicles = emptyList(); error = ""; render()
    }
    private fun searchRoutes() {
        if (busy) return
        query = query.trim()
        if (query.isEmpty()) { showError("노선 번호를 입력해 주세요."); return }
        val chosen = city ?: return
        hideKeyboard(); route = null; selected = null; vehicles = emptyList()
        fetch({ client.routes(chosen, query) }) { results = it; if (it.isEmpty()) error = "일치하는 노선이 없어요. 다른 번호로 검색해 주세요." }
    }
    private fun loadRoute(value: TransitRoute, keepDirection: Boolean = false) {
        selected = null
        if (!keepDirection) direction = null
        fetch({ val loaded = if (value.provider == Provider.TAGO) client.stops(value) else value; loaded to client.vehicles(loaded) }) {
            route = it.first; vehicles = it.second; page = "candidates"; scroll.scrollTo(0, 0)
            if (vehicles.none { it.direction == direction }) direction = null
            if (vehicles.isEmpty()) error = if (value.provider == Provider.SEOUL) "현재 정차 경로를 확인할 수 있는 열차가 없습니다. 정차 정보가 없는 열차·구간은 제외되며, 잠시 후 다시 조회해 주세요." else "현재 추적 가능한 차량이 없습니다. 잠시 후 다시 조회해 주세요."
        }
    }
    private fun renderCandidates(selectedRoute: TransitRoute) {
        controls.addView(text("${selectedRoute.city} · ${selectedRoute.name}", 19, TEXT, true))
        val directions = vehicles.map { it.direction }.distinct()
        val labels = listOf("모든 방향") + directions.map { value ->
            if (selectedRoute.provider == Provider.SEOUL) {
                if (selectedRoute.id == "1002") { if (value == "0") "상행·내선" else "하행·외선" }
                else if (value == "0") "상행" else "하행"
            } else vehicles.first { it.direction == value }.directionLabel
        }
        controls.addView(spinner(labels, directions.indexOf(direction) + 1) { index ->
            val next = directions.getOrNull(index - 1)
            if (next != direction) { direction = next; selected = null; render() }
        })
        controls.addView(text("노선도에서 내가 탄 차량 아이콘을 선택하세요", 12, SECONDARY))
        val candidates = vehicles.filter { direction == null || it.direction == direction }.map { it to preparedRoute(selectedRoute, it) }
        val groups = candidates.groupBy { (vehicle, prepared) -> vehicle.direction to prepared.stops.map { Triple(it.id, it.order, it.isScheduledStop) } }
        groups.values.forEach { group ->
            val prepared = group.first().second
            body.addView(text(group.first().first.directionLabel, 14, TEXT, true).apply { setPadding(0, dp(12), 0, 0) })
            val atStops = group.mapNotNull { (vehicle, _) ->
                val index = prepared.stops.indexOfFirst { it.id == vehicle.stopId && (vehicle.stopOrder == null || it.order == vehicle.stopOrder) }
                if (index < 0) null else index to vehicle
            }.groupBy({ it.first }, { it.second })
            val visible = atStops.keys.flatMap { listOf(it - 1, it, it + 1) }.filter { it in prepared.stops.indices }.distinct().sorted()
            val map = RouteMapView(this, selectedRoute.provider)
            visible.forEachIndexed { position, index ->
                if (position > 0 && index > visible[position - 1] + 1) map.addGap()
                val stop = prepared.stops[index]
                val column = map.addStop(stop, if (!stop.isScheduledStop) "통과" else "", first = position == 0, last = position == visible.lastIndex)
                atStops[index].orEmpty().forEach { vehicle ->
                    val status = if (stop.isScheduledStop) motionLabel(vehicle.motion) else "통과 구간"
                    column.addView(button("${vehicle.label} · ${vehicle.directionLabel}\n${vehicle.stopName} · $status\n${observedLabel(vehicle)}", false) { selectVehicle(prepared, vehicle) }.apply {
                        tag = "vehicle-${vehicle.id}"; gravity = Gravity.CENTER_VERTICAL or Gravity.START; textSize = 13f
                        setCompoundDrawablesWithIntrinsicBounds(TransitIcon(if (selectedRoute.provider == Provider.SEOUL) TransitSymbol.SUBWAY else TransitSymbol.BUS, BLUE, dp(28)), null, null, null)
                        compoundDrawablePadding = dp(10)
                        contentDescription = "${vehicle.label}, ${vehicle.directionLabel}, ${vehicle.stopName}, $status, ${observedLabel(vehicle)}, 탑승 차량 선택"
                    })
                }
            }
            body.addView(map)
        }
        primaryAction("차량 다시 조회") { loadRoute(selectedRoute, true) }
    }
    private fun selectVehicle(prepared: TransitRoute, vehicle: Vehicle) {
        selected = vehicle
        val current = prepared.stops.indexOfFirst { it.id == vehicle.stopId && (vehicle.stopOrder == null || it.order == vehicle.stopOrder) }
        val options = prepared.alightOptions(current, vehicle.motion in listOf(Motion.APPROACHING, Motion.PREVIOUS_DEPARTED))
        alight = options.filter { it > current }.take(4).lastOrNull() ?: options.firstOrNull()
        navigate("boarding")
    }
    private fun renderBoarding() {
        val selectedRoute = route ?: run { renderHome(); return }
        selected?.let { vehicle ->
            val prepared = preparedRoute(selectedRoute, vehicle)
            val current = prepared.stops.indexOfFirst { it.id == vehicle.stopId && (vehicle.stopOrder == null || it.order == vehicle.stopOrder) }
            val summary = card()
            summary.addView(text("${selectedRoute.name} · ${vehicle.directionLabel}", 18, TEXT, true))
            val position = if (prepared.stops[current].isScheduledStop) motionLabel(vehicle.motion) else "통과 구간"
            summary.addView(text("${vehicle.label} · ${vehicle.stopName} · $position", 13, SECONDARY))
            summary.addView(text(observedLabel(vehicle), 11, SECONDARY))
            body.addView(summary)
            body.addView(text("내릴 지점을 노선도에서 선택하세요", 14, TEXT, true).apply { setPadding(0, dp(12), 0, 0) })
            val options = prepared.alightOptions(current, vehicle.motion in listOf(Motion.APPROACHING, Motion.PREVIOUS_DEPARTED))
            val visible = prepared.stops.indices.filter { it >= current && (prepared.stops[it].isScheduledStop || it == current) }
            val map = RouteMapView(this, selectedRoute.provider)
            visible.forEachIndexed { positionIndex, index ->
                val label = listOfNotNull(if (index == current) "현재" else null, if (index == alight) "하차" else null, if (!prepared.stops[index].isScheduledStop) "통과" else null).joinToString(" · ")
                map.addStop(prepared.stops[index], label,
                    current = index == current, destination = index == alight, passed = index < current, first = positionIndex == 0, last = positionIndex == visible.lastIndex,
                    onSelect = if (index in options) ({ alight = index; render() }) else null)
            }
            body.addView(button("하차: ${alight?.let { prepared.stops[it].name } ?: "선택하지 않음"}  ›", false) {
                val options = listOf<Int?>(null) + prepared.alightOptions(current, vehicle.motion in listOf(Motion.APPROACHING, Motion.PREVIOUS_DEPARTED))
                choose("내릴 정류장·역", options.map { it?.let { index -> prepared.stops[index].name } ?: "선택하지 않음" }, options.indexOf(alight)) { index ->
                    alight = options[index]
                    if (alight == null && mode == AlertMode.ALIGHT) mode = AlertMode.SILENT
                    render()
                }
            })
            body.addView(map)
            if (vehicle.serviceType != "0") body.addView(text("급행 정차역만 하차역으로 선택할 수 있어요. 남은 역 수와 알림도 정차역 기준이에요.", 12, SECONDARY))
            footer.addView(button("알림: ${modeLabel(mode)}  ›", false) {
                chooseMode(mode) { next -> if (next == AlertMode.ALIGHT && alight == null) showError("하차 지점을 먼저 선택해 주세요.") else { mode = next; render() } }
            })
            if (selectedRoute.provider == Provider.TAGO) body.addView(text("버스는 위치 기준의 진행·하차 안내를 제공합니다. 실제 정류장 도착 확인은 아니에요.", 12, SECONDARY).apply { setPadding(0, dp(12), 0, 0) })
            primaryAction("이동 추적 시작하기", ::startRide)
        }
    }
    private fun preparedRoute(route: TransitRoute, vehicle: Vehicle): TransitRoute = if (route.provider == Provider.SEOUL) SubwayNetwork.forVehicle(route, vehicle) else route.copy(stops = if (vehicle.direction.isBlank()) route.stops else route.stops.filter { it.direction == vehicle.direction })
    private fun startRide() {
        val selectedRoute = route ?: return
        val vehicle = selected ?: return
        val session = try { TrackingEngine.begin(preparedRoute(selectedRoute, vehicle), vehicle, alight, mode, System.currentTimeMillis()) }
            catch (exception: IllegalArgumentException) { showError(exception.message ?: "차량을 다시 선택해 주세요."); return }
        withNotificationPermission {
            val launch = {
                if (System.currentTimeMillis() - vehicle.observedAt > TrackingEngine.STALE_MS) showError("차량 정보가 오래되었습니다. 다시 조회해 주세요.")
                else { store.write(session); launchService(); navigate("ride") }
            }
            if (RideTrackingService.running) AlertDialog.Builder(this).setTitle("현재 이동을 종료할까요?").setMessage("새 차량을 선택하면 진행 중인 추적을 종료합니다.").setNegativeButton("취소", null).setPositiveButton("새 이동 시작") { _, _ -> launch() }.show()
            else launch()
        }
    }
    private fun launchService() {
        try { startForegroundService(Intent(this, RideTrackingService::class.java).setAction(RideTrackingService.START)) }
        catch (_: Exception) { store.mutate { it?.copy(freshness = Freshness.STOPPED, message = "Android가 추적 서비스 시작을 허용하지 않았습니다. 앱에서 다시 시작해 주세요.") } }
    }
    private fun withNotificationPermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            afterPermission = action
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        } else if (!getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
            AlertDialog.Builder(this).setTitle("이동 알림을 허용해 주세요").setMessage("앱 밖에서 이동 상황을 확인하려면 알림을 켜야 합니다.").setNegativeButton("취소", null).setPositiveButton("알림 설정") { _, _ -> startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) }.show()
        } else action()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10) {
            val action = afterPermission; afterPermission = null
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) action?.invoke() else showError("알림 권한이 거부되어 추적을 시작하지 않았습니다. Android 설정에서 NextStop 알림을 허용해 주세요.")
        }
    }
    private fun renderRide() {
        val session = store.read()
        lastRide = session?.let { SessionCodec.encode(it) }.orEmpty() + RideTrackingService.running
        if (session == null) {
            body.addView(text("진행 중인 이동이 없어요.", 20, TEXT, true))
            primaryAction("노선 찾기") { navigate("home") }; return
        }
        val status = if (!RideTrackingService.running) "추적 중단 · 마지막 확인 위치" else when (session.freshness) {
            Freshness.FRESH -> if (session.stop.isScheduledStop) motionLabel(session.vehicle.motion) else "통과 구간"
            Freshness.STALE -> "갱신 지연 · 마지막 확인 위치"
            Freshness.LOST -> "차량 정보 없음 · 마지막 확인 위치"
            Freshness.STOPPED -> "추적 중단 · 마지막 확인 위치"
        }
        val primary = card(BLUE)
        primary.addView(text(session.title, 16, Color.WHITE, true))
        primary.addView(text(status, 12, Color.WHITE).apply { setPadding(0, dp(6), 0, 0) })
        primary.addView(text(session.location, 26, Color.WHITE, true).apply { setPadding(0, dp(4), 0, dp(4)) })
        primary.addView(text("다음 ${session.next?.name ?: "운행 경로 끝"}", 15, Color.WHITE))
        primary.addView(text(observedLabel(session.vehicle), 11, Color.WHITE).apply { setPadding(0, dp(8), 0, 0) })
        body.addView(primary)
        if (session.message.isNotEmpty()) body.addView(text(session.message, 13, DANGER))
        body.addView(routeSummary(session))
        val destination = session.alightIndex?.let { session.route.stops[it].name } ?: "하차 지점 선택"
        val destinationRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val progressTotal = session.alightIndex?.let { session.route.stops.take(it + 1).count { stop -> stop.isScheduledStop } } ?: 0
        destinationRow.addView(JourneyProgressView(this, session.remainingStops, progressTotal), LinearLayout.LayoutParams(dp(62), dp(62)).apply { marginEnd = dp(10) })
        destinationRow.addView(button("$destination · ${remainingLabel(session)}  ›", false) { changeAlight(session) }.apply {
            contentDescription = "$destination, ${remainingLabel(session)}, 하차 지점 변경"
            setCompoundDrawablesWithIntrinsicBounds(TransitIcon(TransitSymbol.FLAG, BLUE, dp(22)), null, null, null); compoundDrawablePadding = dp(8)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(destinationRow)
        val actions = LinearLayout(this)
        actions.addView(button("알림: ${modeLabel(session.mode)}", false) {
            chooseMode(session.mode) { next ->
                val current = store.read()
                if (current?.id == session.id) {
                    if (next == AlertMode.ALIGHT && current.alightIndex == null) showError("하차 지점을 먼저 선택해 주세요.")
                    else { store.mutate { if (it?.id == session.id) it.copy(mode = next) else it }; render() }
                }
            }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8); topMargin = dp(8) })
        actions.addView(button("이동 상세", false) { navigate("details") }, LinearLayout.LayoutParams(0, -2, 1f).apply { topMargin = dp(8) })
        body.addView(actions)
        if (!RideTrackingService.running && session.freshness != Freshness.STOPPED) {
            val recovery = LinearLayout(this)
            recovery.addView(button("추적 재개하기") { withNotificationPermission { launchService(); render() } }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
            recovery.addView(button("하차 완료", false) { endRide(session.id) }, LinearLayout.LayoutParams(0, -2, 1f))
            footer.addView(recovery)
        } else primaryAction("하차 완료") { endRide(session.id) }
    }
    private fun routeSummary(session: Session): View {
        val compact = resources.configuration.screenWidthDp < 400 || resources.configuration.fontScale >= 1.3f
        val nodes = session.window().withIndex().filter { (!compact || it.index in 2..4) && (it.value != null || it.index == 3) }
        val row = LinearLayout(this).apply {
            setPadding(dp(4), dp(10), dp(4), dp(4))
            contentDescription = "주변 노선: " + nodes.joinToString(" → ") { it.value?.name ?: if (it.index == 3) "이동 중" else "경로 없음" }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        nodes.forEach { (index, stop) ->
            val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
            column.addView(text(if (index == 3) "현재" else if (index == 4) "다음" else "", 11, SECONDARY).apply { gravity = Gravity.CENTER })
            column.addView(View(this).apply {
                background = SummaryNode(this@MainActivity, index, index == nodes.first().index, index == nodes.last().index, session.route.provider)
            }, LinearLayout.LayoutParams(-1, dp(32)))
            column.addView(text(stop?.name ?: if (index == 3) "이동 중" else "—", 12, if (index == 3) BLUE else SECONDARY, index == 3).apply {
                gravity = Gravity.CENTER; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            })
            row.addView(column, LinearLayout.LayoutParams(0, -2, 1f))
        }
        return row
    }
    private fun remainingLabel(session: Session): String {
        val remaining = session.remainingStops
        return when {
            remaining == null -> "미선택"
            remaining < 0 -> "하차 지점을 지났어요"
            remaining == 0 -> if (session.route.provider == Provider.TAGO) "하차 정류장 부근" else if (session.vehicle.motion == Motion.ARRIVED) "하차역 도착" else "하차역 진입·출발 확인"
            else -> "${remaining}개 지점 남음"
        }
    }
    private fun changeAlight(session: Session) {
        val options = listOf<Int?>(null) + session.route.stops.indices.filter { session.route.stops[it].isScheduledStop && (it > session.index || (it == session.index && session.approaching) || it == session.alightIndex) }
        choose("내릴 정류장·역", options.map { it?.let { index -> session.route.stops[index].name } ?: "선택하지 않음" }, options.indexOf(session.alightIndex)) { index ->
            val target = options[index]
            val current = store.read()
            if (current?.id != session.id) { render(); return@choose }
            if (target != null && (target < current.index || (target == current.index && !current.approaching)) && target != current.alightIndex) { showError("차량이 이동했습니다. 하차 지점을 다시 선택해 주세요."); return@choose }
            store.mutate { if (it?.id == session.id) it.copy(alightIndex = target, mode = if (target == null && it.mode == AlertMode.ALIGHT) AlertMode.SILENT else it.mode) else it }
            render()
        }
    }
    private fun endRide(sessionId: String) {
        AlertDialog.Builder(this).setTitle("이동을 마칠까요?").setMessage("차량 조회와 지속 알림을 종료합니다.")
            .setNegativeButton("계속 이동", null).setPositiveButton("하차 완료") { _, _ ->
                if (store.read()?.id == sessionId) {
                    store.clear(); stopService(Intent(this, RideTrackingService::class.java)); navigate("home")
                } else render()
            }.show()
    }
    private fun renderRideDetails() {
        val session = store.read() ?: run { renderRide(); return }
        body.addView(text(session.title, 18, TEXT, true))
        body.addView(text("${session.vehicle.label} · ${observedLabel(session.vehicle)}", 12, SECONDARY))
        body.addView(button("전체 노선", false) { navigate("route") })
        body.addView(button("차량 변경", false) { navigate("home") })
        body.addView(text("도착·진행 알림 기록", 18, TEXT, true).apply { setPadding(0, dp(16), 0, dp(8)) })
        if (session.events.isEmpty()) body.addView(text("선택한 알림 조건에 해당하면 여기에 기록합니다.", 13, SECONDARY))
        session.events.asReversed().forEach { event ->
            val item = card()
            item.addView(text(event.title, 16, TEXT, true)); item.addView(text(event.body, 13, SECONDARY)); item.addView(text(clock(event.at), 11, SECONDARY))
            body.addView(item)
        }
        body.addView(text("버스는 위치 정보로 진행을 안내합니다. 연결 지연·차량 소실 시 마지막 확인 위치를 표시하며, 앱 강제 종료·Android 실행 제한 시 추적이 멈출 수 있어요.", 12, SECONDARY).apply { setPadding(0, dp(16), 0, 0) })
        primaryAction("이동 화면으로") { navigate("ride") }
    }
    private fun renderFullRoute() {
        val session = store.read() ?: run { renderRide(); return }
        controls.addView(text("${session.route.name} · ${session.vehicle.directionLabel}", 16, TEXT, true))
        val map = RouteMapView(this, session.route.provider)
        session.route.stops.forEachIndexed { index, stop ->
            val label = listOfNotNull(if (!stop.isScheduledStop) "통과" else null, if (index == session.index) { if (session.vehicle.motion == Motion.DEPARTED && stop.isScheduledStop) "출발 지점" else "현재" } else null, if (index == session.alightIndex) "하차" else null).joinToString(" · ")
            map.addStop(stop, label, current = index == session.index, destination = index == session.alightIndex, passed = index < session.index, first = index == 0, last = index == session.route.stops.lastIndex)
        }
        body.addView(map)
        primaryAction("이동 화면으로") { navigate("ride") }
    }
    private fun renderKeys() {
        listOf(Provider.TAGO to "버스 API", Provider.SEOUL to "지하철 API").forEach { (provider, title) ->
            body.addView(button("$title · ${if (keys.has(provider)) "등록됨" else "미등록"}  ›", false) { openKey(provider) })
        }
        body.addView(button("조회 간격 · ${store.interval}초  ›", false) {
            choose("조회 간격", listOf("20초 · 시간당 약 180회", "30초 · 시간당 약 120회", "60초 · 시간당 약 60회"), listOf(20, 30, 60).indexOf(store.interval)) {
                store.interval = listOf(20, 30, 60)[it]; render()
            }
        })
        body.addView(button("Android 알림 설정  ›", false) { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) })
        body.addView(text("개인 키는 이 기기에 암호화해 저장합니다.", 13, SECONDARY).apply { setPadding(0, dp(12), 0, 0) })
        body.addView(text("활성 이동 1개 · 최대 4시간 추적. 연결 오류 시 조회 간격을 늘립니다. API별 호출 한도를 확인해 주세요.", 12, SECONDARY).apply { setPadding(0, dp(8), 0, 0) })
    }
    private fun openKey(provider: Provider) {
        if (page !in listOf("keys", "key")) settingsReturnPage = page
        editingProvider = provider; navigate("key")
    }
    private fun renderKeyEditor() {
        val provider = editingProvider
        val registered = keys.has(provider)
        val title = if (provider == Provider.TAGO) "TAGO 버스 API" else "서울 실시간 지하철 API"
        val description = if (provider == Provider.TAGO) "버스노선정보·버스위치정보에 각각 활용 신청하세요. Encoding·Decoding 키 모두 입력할 수 있어요." else "실시간 지하철 위치정보용 개인 인증키를 입력하세요."
        val link = if (provider == Provider.TAGO) "https://www.data.go.kr/data/15098529/openapi.do" else "https://data.seoul.go.kr/dataList/OA-12601/A/1/datasetView.do"
        body.addView(text(title, 20, TEXT, true))
        body.addView(text(if (registered) "개인 키 등록됨" else "개인 키 미등록", 13, BLUE))
        body.addView(text(description, 13, SECONDARY).apply { setPadding(0, dp(8), 0, dp(8)) })
        val input = EditText(this).apply {
            hint = if (registered) "변경할 키 입력" else "개인 API 키 입력"
            textSize = 14f; setTextColor(TEXT); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance(); setSingleLine(true); minHeight = dp(48)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS; isSaveEnabled = false
        }
        body.addView(input)
        primaryAction("이 기기에 저장") {
            try {
                keys.save(provider, input.text.toString()); input.text.clear()
                Toast.makeText(this, "개인 키를 암호화해 저장했어요.", Toast.LENGTH_SHORT).show(); navigate("keys")
            } catch (_: Exception) { input.text.clear(); showError("API 키 형식과 기기 저장 상태를 확인해 주세요.") }
        }
        if (registered) body.addView(button("연결 확인", false) {
            fetch({ if (provider == Provider.TAGO) client.verifyTago().size else client.vehicles(SubwayNetwork.line("1002")!!.route).size }) { Toast.makeText(this, "연결 확인 완료 · ${it}개 응답", Toast.LENGTH_LONG).show() }
        })
        body.addView(button("키 발급 페이지", false) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) })
        if (provider == Provider.TAGO) body.addView(button("버스 위치 서비스 활용 신청", false) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.data.go.kr/data/15098533/openapi.do"))) })
        if (provider == Provider.SEOUL) body.addView(CheckBox(this).apply {
            setText(R.string.seoul_http_consent); textSize = 13f; setTextColor(SECONDARY); minHeight = dp(48); isChecked = store.allowSeoulHttp
            setOnCheckedChangeListener { _, checked -> store.allowSeoulHttp = checked }
        })
        if (registered) body.addView(button("저장한 키 삭제", false) {
            AlertDialog.Builder(this).setTitle("개인 키를 삭제할까요?").setMessage("이 키를 사용하는 추적은 종료됩니다.").setNegativeButton("취소", null).setPositiveButton("삭제") { _, _ ->
                if (store.read()?.route?.provider == provider) { store.clear(); stopService(Intent(this, RideTrackingService::class.java)) }
                keys.delete(provider); route = null; vehicles = emptyList(); selected = null
                if (provider == Provider.TAGO) { cities = emptyList(); results = emptyList() }
                settingsReturnPage = if (store.read() != null) "ride" else "home"
                navigate("keys")
            }.show()
        })
    }
    private fun choose(title: String, labels: List<String>, checked: Int, selected: (Int) -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, index ->
            dialog.dismiss(); selected(index)
        }.setNegativeButton("취소", null).show()
    }
    private fun chooseMode(current: AlertMode, changed: (AlertMode) -> Unit) {
        val values = listOf(AlertMode.ALIGHT, AlertMode.ALL, AlertMode.SILENT)
        choose("알림 방식", values.map(::modeLabel), values.indexOf(current)) { changed(values[it]) }
    }
    private fun modeLabel(value: AlertMode) = when (value) { AlertMode.ALIGHT -> "하차할 때만"; AlertMode.ALL -> "모든 지점"; AlertMode.SILENT -> "무음 갱신" }
    private fun observedLabel(vehicle: Vehicle) = "${if (vehicle.sourceTimestamp) "데이터 기준" else "조회 시각"} ${clock(vehicle.observedAt)}"
    private fun hideKeyboard() {
        currentFocus?.let { getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0); it.clearFocus() }
    }
    private fun <T> fetch(operation: () -> T, complete: (T) -> Unit) {
        if (busy) return
        val version = ++requestVersion
        busy = true; error = ""; render()
        worker.execute {
            val result = runCatching(operation)
            main.post {
                if (isDestroyed || version != requestVersion) return@post
                busy = false
                result.onSuccess(complete).onFailure { error = (it as? TransitException)?.safeMessage ?: "교통 정보를 처리하지 못했습니다. API 키와 응답을 확인해 주세요." }
                render()
            }
        }
    }
    private fun showError(message: String) { error = message; render() }
    private fun spinner(items: List<String>, selected: Int = 0, changed: (Int) -> Unit): Spinner = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
        setSelection(selected.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { if (position in items.indices) changed(position) }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        layoutParams = LinearLayout.LayoutParams(-1, dp(52))
    }
    private fun card(color: Int = SURFACE) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = shape(color, if (color == BLUE) BLUE else BORDER); setPadding(dp(16), dp(14), dp(16), dp(14)); layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(4) } }
    private fun button(label: String, primary: Boolean = true, clicked: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 14f; minHeight = dp(48); minimumWidth = dp(48); setPadding(dp(14), dp(10), dp(14), dp(10)); setTextColor(if (primary) Color.WHITE else BLUE)
        background = shape(if (primary) BLUE else SURFACE, if (primary) BLUE else BORDER)
        val symbol = when {
            label.startsWith("알림:") -> if (label.contains("무음")) TransitSymbol.SILENT else TransitSymbol.BELL
            label.startsWith("하차:") -> TransitSymbol.FLAG
            label == "하차 완료" -> TransitSymbol.CHECK
            label.startsWith("버스 API") -> TransitSymbol.BUS
            label.startsWith("지하철 API") -> TransitSymbol.SUBWAY
            else -> null
        }
        symbol?.let { setCompoundDrawablesWithIntrinsicBounds(TransitIcon(it, if (primary) Color.WHITE else BLUE, dp(22)), null, null, null); compoundDrawablePadding = dp(8) }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(2) }
        setOnClickListener { if (!busy) clicked() }
    }
    private fun text(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply { text = value; textSize = size.toFloat(); setTextColor(color); setLineSpacing(dp(4).toFloat(), 1f); if (bold) setTypeface(typeface, Typeface.BOLD) }
    private fun shape(color: Int, border: Int = color) = GradientDrawable().apply { setColor(color); cornerRadius = dp(14).toFloat(); setStroke(dp(1), border) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun clock(value: Long) = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date(value))
    private fun motionLabel(motion: Motion) = when (motion) { Motion.NEAR -> "위치 기준"; Motion.APPROACHING -> "역 진입"; Motion.ARRIVED -> "역 도착"; Motion.DEPARTED -> "역 출발"; Motion.PREVIOUS_DEPARTED -> "전역 출발" }
    companion object { private val BLUE = Color.rgb(36, 87, 197); private val IVORY = Color.rgb(255, 248, 231); private val SURFACE = Color.rgb(255, 252, 245); private val TEXT = Color.rgb(23, 43, 77); private val SECONDARY = Color.rgb(83, 98, 122); private val BORDER = Color.rgb(216, 212, 199); private val DANGER = Color.rgb(180, 35, 24) }
}
