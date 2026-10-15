package kr.nextstop.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import kr.nextstop.app.domain.Provider
import kr.nextstop.app.domain.Stop

internal object TransitPalette {
    val blue = Color.rgb(36, 87, 197)
    val green = Color.rgb(37, 116, 77)
    val ink = Color.rgb(23, 43, 77)
    val muted = Color.rgb(83, 98, 122)
    val surface = Color.rgb(255, 252, 245)
    val border = Color.rgb(216, 212, 199)
    val soft = Color.rgb(231, 238, 252)
}

internal fun Context.dip(value: Int) = (value * resources.displayMetrics.density).toInt()
internal fun Context.rounded(fill: Int, border: Int = fill, radius: Int = 16) = GradientDrawable().apply {
    setColor(fill); cornerRadius = dip(radius).toFloat(); setStroke(dip(1), border)
}

internal enum class TransitSymbol { BUS, SUBWAY, FLAG, BELL, SILENT, LOCK, CHECK, ARROW }

/** Small vector icons also used by native, focusable map markers. */
internal class TransitIcon(private val symbol: TransitSymbol, private val tint: Int, private val size: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Drawable compatibility") override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun draw(canvas: Canvas) {
        canvas.save(); canvas.translate(bounds.left.toFloat(), bounds.top.toFloat()); canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        paint.color = tint; paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.7f; paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND; path.rewind()
        fun line(x: Float, y: Float, x2: Float, y2: Float) = canvas.drawLine(x, y, x2, y2, paint)
        when (symbol) {
            TransitSymbol.BUS, TransitSymbol.SUBWAY -> {
                canvas.drawRoundRect(5f, 3f, 19f, 19f, 3f, 3f, paint)
                canvas.drawRoundRect(8f, 6f, 16f, 12f, 1f, 1f, paint)
                canvas.drawCircle(8.5f, 15.5f, .8f, paint); canvas.drawCircle(15.5f, 15.5f, .8f, paint)
                if (symbol == TransitSymbol.BUS) { line(7f, 19f, 7f, 21f); line(17f, 19f, 17f, 21f); line(2f, 7f, 2f, 11f); line(22f, 7f, 22f, 11f) }
                else { line(8f, 19f, 6f, 22f); line(16f, 19f, 18f, 22f); line(7f, 21f, 17f, 21f); line(12f, 6f, 12f, 12f) }
            }
            TransitSymbol.FLAG -> { line(6f, 22f, 6f, 3f); canvas.drawPath(path.apply { moveTo(6f, 3f); lineTo(19f, 3f); lineTo(16f, 8f); lineTo(19f, 13f); lineTo(6f, 13f) }, paint) }
            TransitSymbol.BELL, TransitSymbol.SILENT -> {
                canvas.drawPath(path.apply { moveTo(5f, 17f); lineTo(7f, 14f); lineTo(7f, 9f); cubicTo(7f, 2f, 17f, 2f, 17f, 9f); lineTo(17f, 14f); lineTo(19f, 17f); close() }, paint)
                line(10f, 21f, 14f, 21f)
                if (symbol == TransitSymbol.SILENT) line(3f, 3f, 21f, 21f)
            }
            TransitSymbol.LOCK -> { canvas.drawRoundRect(5f, 10f, 19f, 21f, 2f, 2f, paint); canvas.drawArc(8f, 2f, 16f, 17f, 180f, 180f, false, paint); line(12f, 14f, 12f, 17f) }
            TransitSymbol.CHECK -> { line(5f, 12f, 10f, 17f); line(10f, 17f, 20f, 6f) }
            TransitSymbol.ARROW -> { line(4f, 12f, 20f, 12f); line(14f, 6f, 20f, 12f); line(14f, 18f, 20f, 12f) }
        }
        canvas.restore()
    }
}

/** A selection illustration, deliberately independent of GPS or live vehicle positions. */
@SuppressLint("ViewConstructor") // Composed by MainActivity with selection state and a callback, never inflated from XML.
internal class TransportMapView(context: Context, subway: Boolean, onSelect: (Boolean) -> Unit) : FrameLayout(context) {
    private val bus: Button
    private val train: Button
    init {
        background = context.rounded(Color.rgb(242, 240, 229), TransitPalette.border, 22)
        clipToOutline = true
        addView(CityMapArtwork(context), LayoutParams(-1, -1))
        addView(TextView(context).apply {
            text = "지도에서 교통수단 선택"; textSize = 15f; setTextColor(TransitPalette.ink); setTypeface(typeface, Typeface.BOLD)
        }, LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { leftMargin = context.dip(16); topMargin = context.dip(14) })
        fun marker(label: String, isSubway: Boolean): Button = Button(context).apply {
            text = label; textSize = 15f; isAllCaps = false; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (isSubway) TransitPalette.green else TransitPalette.blue)
            setPadding(context.dip(8), context.dip(10), context.dip(8), context.dip(8))
            setCompoundDrawablesWithIntrinsicBounds(null, TransitIcon(if (isSubway) TransitSymbol.SUBWAY else TransitSymbol.BUS, if (isSubway) TransitPalette.green else TransitPalette.blue, context.dip(36)), null, null)
            compoundDrawablePadding = context.dip(6)
            isSelected = subway == isSubway
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_selected), LayerDrawable(arrayOf(
                    context.rounded(if (isSubway) Color.rgb(229, 243, 232) else TransitPalette.soft, if (isSubway) TransitPalette.green else TransitPalette.blue, 18),
                    SelectionBadge(context, if (isSubway) TransitPalette.green else TransitPalette.blue)
                )))
                addState(intArrayOf(), context.rounded(TransitPalette.surface, TransitPalette.border, 18))
            }
            elevation = context.dip(4).toFloat()
            tag = if (isSubway) "map-subway" else "map-bus"
            contentDescription = "$label 선택${if (isSelected) ", 선택됨" else ""}"
            setOnClickListener { onSelect(isSubway) }
        }
        bus = marker("버스", false); train = marker("지하철", true)
        addView(bus, LayoutParams(context.dip(92), context.dip(100)))
        addView(train, LayoutParams(context.dip(92), context.dip(100)))
        addView(TextView(context).apply { text = "선택용 개념도 · 실제 위치 아님"; textSize = 10f; setTextColor(TransitPalette.muted) }, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { leftMargin = context.dip(14); bottomMargin = context.dip(10) })
        layoutParams = LinearLayout.LayoutParams(-1, context.dip(if (resources.configuration.fontScale >= 1.3f) 256 else 224)).apply { topMargin = context.dip(8); bottomMargin = context.dip(12) }
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fun place(view: View, x: Float, y: Float) {
            view.layoutParams = (view.layoutParams as LayoutParams).apply { leftMargin = (w * x - width / 2).toInt(); topMargin = (h * y - height / 2).toInt() }
        }
        place(bus, .28f, .5f); place(train, .72f, .59f)
    }
}

private class SelectionBadge(private val context: Context, private val tint: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icon = TransitIcon(TransitSymbol.CHECK, Color.WHITE, context.dip(12))
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Drawable compatibility") override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun draw(canvas: Canvas) {
        val x = bounds.right - context.dip(15); val y = bounds.top + context.dip(15)
        paint.color = tint; canvas.drawCircle(x.toFloat(), y.toFloat(), context.dip(8).toFloat(), paint)
        icon.apply { setBounds(x - context.dip(6), y - context.dip(6), x + context.dip(6), y + context.dip(6)); draw(canvas) }
    }
}

private class CityMapArtwork(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val river = Path().apply { moveTo(-10f, 125f); cubicTo(90f, 115f, 105f, 186f, 210f, 187f); cubicTo(260f, 190f, 310f, 151f, 370f, 164f) }
    private val busLine = Path().apply { moveTo(-10f, 70f); lineTo(100f, 70f); quadTo(145f, 70f, 145f, 108f); lineTo(145f, 142f); quadTo(145f, 159f, 164f, 159f); lineTo(225f, 159f) }
    private val subwayLine = Path().apply { moveTo(184f, 32f); lineTo(184f, 90f); quadTo(184f, 111f, 208f, 111f); lineTo(305f, 111f); quadTo(326f, 111f, 326f, 133f); lineTo(326f, 225f) }
    private val stations = listOf(44f to 70f, 145f to 128f, 184f to 58f, 222f to 111f, 305f to 111f, 326f to 209f)
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(canvas: Canvas) {
        canvas.save(); canvas.scale(width / 360f, height / 224f)
        paint.color = Color.rgb(224, 232, 210); paint.style = Paint.Style.FILL
        canvas.drawRoundRect(241f, 36f, 333f, 88f, 15f, 15f, paint); canvas.drawRoundRect(12f, 143f, 90f, 192f, 16f, 16f, paint)
        paint.color = Color.rgb(230, 226, 213)
        for (x in 24..340 step 47) for (y in 49..210 step 42) canvas.drawRoundRect(x.toFloat(), y.toFloat(), x + 29f, y + 25f, 4f, 4f, paint)
        paint.color = Color.rgb(255, 252, 245); paint.style = Paint.Style.STROKE; paint.strokeWidth = 10f
        for (x in 12..360 step 47) canvas.drawLine(x.toFloat(), 35f, x - 25f, 224f, paint)
        for (y in 40..224 step 42) canvas.drawLine(0f, y.toFloat(), 360f, y + 10f, paint)
        paint.color = Color.rgb(191, 215, 228); paint.strokeWidth = 28f
        canvas.drawPath(river, paint)
        paint.color = Color.rgb(255, 252, 245); paint.strokeWidth = 10f
        canvas.drawLine(178f, 131f, 160f, 219f, paint); canvas.drawLine(310f, 125f, 316f, 197f, paint)
        paint.color = TransitPalette.blue; paint.strokeWidth = 4f; paint.strokeCap = Paint.Cap.ROUND
        canvas.drawPath(busLine, paint)
        paint.color = TransitPalette.green
        canvas.drawPath(subwayLine, paint)
        stations.forEachIndexed { index, (x, y) ->
            paint.style = Paint.Style.FILL; paint.color = TransitPalette.surface; canvas.drawCircle(x, y, 4.5f, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f; paint.color = if (index < 2) TransitPalette.blue else TransitPalette.green; canvas.drawCircle(x, y, 4.5f, paint)
        }
        canvas.restore()
    }
}

/** A connected route diagram. Nodes are provider stop order, never geographic coordinates. */
@SuppressLint("ViewConstructor") // Provider and stop nodes are supplied programmatically.
internal class RouteMapView(context: Context, private val provider: Provider) : LinearLayout(context) {
    init {
        orientation = VERTICAL; setPadding(context.dip(8), context.dip(12), context.dip(12), context.dip(12))
        background = context.rounded(TransitPalette.surface, TransitPalette.border, 18)
        layoutParams = LayoutParams(-1, -2).apply { topMargin = context.dip(10); bottomMargin = context.dip(10) }
        addView(TextView(context).apply { text = "노선도 · 실제 거리와 다름"; textSize = 11f; setTextColor(TransitPalette.muted); setPadding(context.dip(12), 0, 0, context.dip(10)) })
    }
    fun addStop(stop: Stop, label: String = "", current: Boolean = false, destination: Boolean = false, passed: Boolean = false, first: Boolean = false, last: Boolean = false, onSelect: (() -> Unit)? = null): LinearLayout {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; minimumHeight = context.dip(54) }
        row.addView(View(context).apply {
            background = RouteRail(context, if (provider == Provider.SEOUL) TransitPalette.green else TransitPalette.blue, current, destination, passed, first, last, stop.isScheduledStop)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(context.dip(40), -1))
        val content = LinearLayout(context).apply { orientation = VERTICAL; setPadding(0, context.dip(3), 0, context.dip(6)) }
        val title = if (onSelect != null) Button(context).apply {
            isAllCaps = false; gravity = Gravity.CENTER_VERTICAL or Gravity.START; minimumHeight = context.dip(48); minHeight = context.dip(48)
            setPadding(context.dip(10), context.dip(8), context.dip(10), context.dip(8))
            background = context.rounded(if (destination) TransitPalette.soft else TransitPalette.surface, if (destination) TransitPalette.blue else TransitPalette.border, 12)
            isSelected = destination; contentDescription = "${stop.name} 하차 지점${if (destination) ", 선택됨" else " 선택"}"
            tag = "alight-${stop.order}"
            setOnClickListener { onSelect() }
        } else TextView(context).apply { gravity = Gravity.CENTER_VERTICAL; minHeight = context.dip(42); setPadding(context.dip(4), 0, 0, 0) }
        title.apply {
            text = listOf(stop.name, label).filter { it.isNotBlank() }.joinToString("  ·  "); textSize = 14f
            setTextColor(if (current || destination) TransitPalette.blue else if (passed || !stop.isScheduledStop) TransitPalette.muted else TransitPalette.ink)
            if (current || destination) setTypeface(typeface, Typeface.BOLD)
        }
        content.addView(title, LayoutParams(-1, -2)); row.addView(content, LayoutParams(0, -2, 1f)); addView(row)
        return content
    }
    fun addGap() {
        addView(TextView(context).apply { text = "┊"; textSize = 18f; setTextColor(TransitPalette.muted); gravity = Gravity.CENTER; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }, LayoutParams(context.dip(40), context.dip(28)))
    }
}

private class RouteRail(private val context: Context, private val color: Int, private val current: Boolean, private val destination: Boolean, private val passed: Boolean, private val first: Boolean, private val last: Boolean, private val scheduled: Boolean) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icon = TransitIcon(if (destination) TransitSymbol.FLAG else if (color == TransitPalette.green) TransitSymbol.SUBWAY else TransitSymbol.BUS, color, context.dip(22))
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Drawable compatibility") override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun draw(canvas: Canvas) {
        val x = bounds.exactCenterX(); val y = bounds.top + context.dip(26).toFloat()
        paint.color = if (passed) TransitPalette.border else color; paint.strokeWidth = context.dip(3).toFloat(); paint.style = Paint.Style.STROKE
        canvas.drawLine(x, if (first) y else bounds.top.toFloat(), x, if (last) y else bounds.bottom.toFloat(), paint)
        if (current || destination) {
            paint.style = Paint.Style.FILL; paint.color = TransitPalette.soft; canvas.drawCircle(x, y, context.dip(17).toFloat(), paint)
            icon.apply { setBounds((x - context.dip(11)).toInt(), (y - context.dip(11)).toInt(), (x + context.dip(11)).toInt(), (y + context.dip(11)).toInt()); draw(canvas) }
        } else {
            paint.style = Paint.Style.FILL; paint.color = TransitPalette.surface; canvas.drawCircle(x, y, context.dip(if (scheduled) 6 else 3).toFloat(), paint)
            paint.style = Paint.Style.STROKE; paint.color = if (passed || !scheduled) TransitPalette.border else color; paint.strokeWidth = context.dip(2).toFloat(); canvas.drawCircle(x, y, context.dip(if (scheduled) 6 else 3).toFloat(), paint)
        }
    }
}

@SuppressLint("ViewConstructor") // Ride values are supplied programmatically; this view is not an XML component.
internal class JourneyProgressView(context: Context, private val remaining: Int?, private val total: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = remaining?.coerceAtLeast(0)?.toString() ?: "–"
    private val numberTypeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(canvas: Canvas) {
        val stroke = context.dip(6).toFloat(); val radius = (minOf(width, height) - stroke * 2) / 2f
        val x = width / 2f; val y = height / 2f
        paint.style = Paint.Style.STROKE; paint.strokeWidth = stroke; paint.color = TransitPalette.border
        canvas.drawCircle(x, y, radius, paint)
        paint.color = TransitPalette.blue; paint.strokeCap = Paint.Cap.ROUND
        val progress = if (remaining == null || total <= 0) 0f else (1f - remaining.coerceAtLeast(0).toFloat() / total).coerceIn(0f, 1f)
        canvas.drawArc(x - radius, y - radius, x + radius, y + radius, -90f, progress * 360f, false, paint)
        paint.style = Paint.Style.FILL; paint.color = TransitPalette.blue; paint.typeface = numberTypeface; paint.textAlign = Paint.Align.CENTER; paint.textSize = context.dip(25).toFloat()
        canvas.drawText(label, x, y + context.dip(9), paint)
    }
}

/** Connected horizontal nodes with a vehicle symbol at the current route position. */
internal class SummaryNode(private val context: Context, private val index: Int, private val first: Boolean, private val last: Boolean, private val provider: Provider) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icon = TransitIcon(if (provider == Provider.SEOUL) TransitSymbol.SUBWAY else TransitSymbol.BUS, TransitPalette.blue, context.dip(22))
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Drawable compatibility") override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun draw(canvas: Canvas) {
        val x = bounds.exactCenterX(); val y = bounds.exactCenterY()
        paint.style = Paint.Style.STROKE; paint.strokeWidth = context.dip(3).toFloat()
        paint.color = if (index <= 3) TransitPalette.blue else TransitPalette.border
        canvas.drawLine(if (first) x else bounds.left.toFloat(), y, if (last) x else bounds.right.toFloat(), y, paint)
        if (index == 3) {
            paint.style = Paint.Style.FILL; paint.color = TransitPalette.soft; canvas.drawCircle(x, y, context.dip(16).toFloat(), paint)
            icon.apply {
                setBounds((x - context.dip(11)).toInt(), (y - context.dip(11)).toInt(), (x + context.dip(11)).toInt(), (y + context.dip(11)).toInt()); draw(canvas)
            }
        } else {
            paint.style = Paint.Style.FILL; paint.color = TransitPalette.surface; canvas.drawCircle(x, y, context.dip(5).toFloat(), paint)
            paint.style = Paint.Style.STROKE; paint.color = if (index < 3) TransitPalette.blue else TransitPalette.muted; paint.strokeWidth = context.dip(2).toFloat(); canvas.drawCircle(x, y, context.dip(5).toFloat(), paint)
        }
    }
}
