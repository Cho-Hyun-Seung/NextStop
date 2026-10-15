package kr.nextstop.app.tracking

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.widget.RemoteViews
import kr.nextstop.app.MainActivity
import kr.nextstop.app.R
import kr.nextstop.app.domain.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RideNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    init {
        manager.createNotificationChannel(NotificationChannel(STATUS, "이동 상황", NotificationManager.IMPORTANCE_LOW).apply { description = "추적 중인 차량의 진행 상황을 소리 없이 갱신합니다."; setSound(null, null) })
        manager.createNotificationChannel(NotificationChannel(ALERTS, "도착·하차 안내", NotificationManager.IMPORTANCE_HIGH).apply { description = "확인된 역 도착과 버스 위치 기반 진행·하차 안내입니다." })
    }
    private fun open(): PendingIntent = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("screen", "ride"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun status(session: Session): Notification {
        val label = when (session.freshness) { Freshness.FRESH -> session.location; Freshness.STALE -> "갱신 지연 · ${session.location}"; Freshness.LOST -> "차량 정보 없음 · 마지막 ${session.location}"; Freshness.STOPPED -> "추적 중단 · ${session.location}" }
        val observed = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date(session.vehicle.observedAt))
        val origin = if (session.vehicle.sourceTimestamp) "데이터 기준" else "조회 시각"
        val next = "다음 ${session.next?.name ?: "운행 경로 끝"} · $origin $observed"
        val title = session.title
        val stop = PendingIntent.getService(context, 1, Intent(context, RideTrackingService::class.java).setAction(RideTrackingService.STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(context, STATUS).setSmallIcon(R.drawable.ic_nextstop)
            .setContentTitle(title).setContentText(label).setSubText(next)
            .setContentIntent(open()).setOnlyAlertOnce(true).setOngoing(true)
            .setCategory(Notification.CATEGORY_PROGRESS).setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(null, "하차 완료", stop).build())
        val routeText = session.window().joinToString(" → ") { it?.name ?: "이동 중" }
        if (context.resources.configuration.fontScale >= 1.3f) {
            builder.setStyle(Notification.BigTextStyle().bigText("$label\n$next\n$routeText\n${session.message}"))
        } else {
            val views = RemoteViews(context.packageName, R.layout.notification_ride)
            views.setTextViewText(R.id.notification_route, title)
            views.setTextViewText(R.id.notification_location, label)
            views.setTextViewText(R.id.notification_next, next)
            views.setImageViewBitmap(R.id.notification_line, routeBitmap(session))
            views.setContentDescription(R.id.notification_line, "이전·현재·다음 지점: $routeText")
            builder.setStyle(Notification.DecoratedCustomViewStyle()).setCustomBigContentView(views)
        }
        return builder.build()
    }
    fun publish(session: Session) { manager.notify(STATUS_ID, status(session)) }
    fun alert(event: RideEvent) {
        val notification = Notification.Builder(context, ALERTS).setSmallIcon(R.drawable.ic_nextstop).setContentTitle(event.title).setContentText(event.body)
            .setStyle(Notification.BigTextStyle().bigText(event.body)).setContentIntent(open()).setAutoCancel(true).setGroup("nextstop.arrivals").build()
        manager.notify(event.id, 201, notification)
        manager.activeNotifications.filter { it.id == 201 }.sortedByDescending { it.postTime }.drop(3).forEach { manager.cancel(it.tag, it.id) }
    }
    fun stopped(message: String) {
        manager.notify(202, Notification.Builder(context, STATUS).setSmallIcon(R.drawable.ic_nextstop).setContentTitle("NextStop 추적 중단").setContentText(message).setStyle(Notification.BigTextStyle().bigText(message)).setContentIntent(open()).setAutoCancel(true).build())
    }
    private fun routeBitmap(session: Session): Bitmap {
        val bitmap = Bitmap.createBitmap(980, 190, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(255, 248, 231))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.rgb(123, 135, 150); paint.strokeWidth = 5f
        canvas.drawLine(65f, 60f, 915f, 60f, paint)
        session.window().forEachIndexed { index, stop ->
            val x = 65f + index * (850f / 6)
            paint.color = when { index == 3 -> Color.rgb(36, 87, 197); index < 3 -> Color.rgb(83, 98, 122); else -> Color.rgb(255, 248, 231) }
            paint.style = Paint.Style.FILL; canvas.drawCircle(x, 60f, if (index == 3) 23f else 13f, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f; paint.color = Color.rgb(36, 87, 197); canvas.drawCircle(x, 60f, if (index == 3) 23f else 13f, paint)
            paint.style = Paint.Style.FILL
            val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = if (index == 3) Color.rgb(23, 63, 138) else Color.rgb(83, 98, 122); textSize = if (index == 3) 27f else 24f; typeface = if (index == 3) Typeface.DEFAULT_BOLD else Typeface.DEFAULT }
            val layout = StaticLayout.Builder.obtain(stop?.name ?: if (index == 3) "이동 중" else "", 0, (stop?.name ?: if (index == 3) "이동 중" else "").length, text, 128).setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).build()
            canvas.save(); canvas.translate(x - 64f, 94f); layout.draw(canvas); canvas.restore()
        }
        return bitmap
    }
    companion object { const val STATUS_ID = 100; const val STATUS = "ride_status"; const val ALERTS = "ride_alerts" }
}
