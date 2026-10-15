package kr.nextstop.app.tracking

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import kr.nextstop.app.data.*
import kr.nextstop.app.domain.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class RideTrackingService : Service() {
    private lateinit var store: SessionStore
    private lateinit var notifications: RideNotifications
    private lateinit var client: TransitClient
    private val main = Handler(Looper.getMainLooper())
    private var executor: ScheduledExecutorService? = null
    private var generation = 0
    private var failures = 0
    private var startedAt = 0L
    override fun onCreate() {
        super.onCreate()
        store = SessionStore(this)
        notifications = RideNotifications(this)
        client = TransitClient(PersonalKeyStore(this)::read, { store.allowSeoulHttp })
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { store.clear(); finish(); return START_NOT_STICKY }
        // Never restart monitoring after process death without an explicit user action.
        if (intent?.action != START) { finish(); return START_NOT_STICKY }
        val session = store.read() ?: run { finish(); return START_NOT_STICKY }
        generation++
        executor?.shutdownNow()
        failures = 0
        startedAt = session.startedAt
        val notification = notifications.status(session)
        if (Build.VERSION.SDK_INT >= 29) startForeground(RideNotifications.STATUS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(RideNotifications.STATUS_ID, notification)
        running = true
        executor = Executors.newSingleThreadScheduledExecutor()
        schedule(0, generation)
        return START_NOT_STICKY
    }
    private fun schedule(seconds: Long, token: Int) {
        executor?.schedule({ poll(token) }, seconds, TimeUnit.SECONDS)
    }
    private fun poll(token: Int) {
        if (System.currentTimeMillis() - startedAt >= TrackingEngine.MAX_RIDE_MS) { main.post { if (token == generation) stopWithMessage("4시간 추적을 마쳤습니다. 차량을 다시 선택해 주세요.") }; return }
        val before = store.read() ?: run { main.post { if (token == generation) finish() }; return }
        if (System.currentTimeMillis() - before.startedAt >= TrackingEngine.MAX_RIDE_MS) { main.post { if (token == generation) stopWithMessage("4시간 추적을 마쳤습니다. 차량을 다시 선택해 주세요.") }; return }
        val lock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NextStop:vehicle-fetch")
        var observation: Vehicle? = null
        var error: TransitException? = null
        try {
            lock.acquire(30_000)
            observation = client.vehicles(before.route, includeStale = true).firstOrNull { it.id == before.vehicle.id }
        } catch (safe: TransitException) { error = safe }
        catch (_: Exception) { error = TransitException("교통 정보를 갱신할 수 없습니다. 연결과 API 키를 확인해 주세요.") }
        finally { if (lock.isHeld) lock.release() }
        main.post {
            if (token != generation || !running) return@post
            if (store.read()?.id != before.id) return@post
            if (error?.fatal == true) { stopWithMessage(error.safeMessage); return@post }
            val now = System.currentTimeMillis()
            val updated = store.mutate { current ->
                if (current == null || current.id != before.id) current
                else if (error != null) current.copy(freshness = Freshness.STALE, message = "연결 지연 · 마지막 확인 위치입니다.")
                else TrackingEngine.update(current, observation, now)
            } ?: run { finish(); return@post }
            if (updated.id != before.id) return@post
            if (updated.freshness == Freshness.STOPPED) { stopWithMessage(updated.message); return@post }
            notifications.publish(updated)
            updated.events.filter { it.id !in before.seen }.forEach { notifications.alert(it) }
            failures = if (error == null) 0 else (failures + 1).coerceAtMost(3)
            schedule((store.interval.toLong() * (1L shl failures)).coerceAtMost(120), token)
        }
    }
    private fun stopWithMessage(message: String) {
        store.mutate { it?.copy(freshness = Freshness.STOPPED, message = message) }
        finish()
        notifications.stopped(message)
    }
    private fun finish() {
        generation++
        running = false
        executor?.shutdownNow(); executor = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onTimeout(startId: Int, fgsType: Int) { stopWithMessage("Android의 백그라운드 실행 시간을 모두 사용했습니다. 앱에서 차량을 다시 선택해 주세요.") }
    override fun onDestroy() { generation++; running = false; executor?.shutdownNow(); main.removeCallbacksAndMessages(null); super.onDestroy() }
    override fun onBind(intent: Intent?) = null
    companion object {
        const val START = "kr.nextstop.START_RIDE"
        const val STOP = "kr.nextstop.STOP_RIDE"
        @Volatile var running = false
            private set
    }
}
