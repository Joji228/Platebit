package app.morsel.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.morsel.ui.theme.Numeric
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow

/** One point on a [TrendChart]: a weigh-in's raw value and the smoothed trend after it. */
data class ChartPoint(val date: LocalDate, val raw: Double, val trend: Double)

/**
 * A metric over time with value labels and dates: the smoothed trend as a line with a soft fill,
 * raw readings as dots, and an optional dashed [target]. Touch or drag across it to read any
 * weigh-in; the readout above the chart shows the latest one otherwise.
 */
@Composable
fun TrendChart(
    points: List<ChartPoint>,
    unit: String,
    decimals: Int,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
    target: Double? = null,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    var selected by remember(points) { mutableStateOf<Int?>(null) }
    val grid = MaterialTheme.colorScheme.outlineVariant
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val dotColor = muted.copy(alpha = 0.55f)
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.merge(Numeric).copy(color = muted)
    val dayFormat = remember { DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()) }
    val fullFormat = remember { DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault()) }
    fun Double.withUnit() = format(decimals) + if (unit.isEmpty()) "" else " $unit"

    Column(modifier) {
        val shown = selected?.let { points.getOrNull(it) } ?: points.lastOrNull()
        Row {
            Text(
                shown?.let { "${it.date.format(fullFormat)} · ${it.raw.withUnit()}" } ?: "No readings in this range",
                style = MaterialTheme.typography.labelMedium.merge(Numeric),
                color = if (selected != null) MaterialTheme.colorScheme.onSurface else muted,
                modifier = Modifier.weight(1f),
            )
            shown?.takeIf { points.size > 1 }?.let {
                Text("trend ${it.trend.withUnit()}", style = MaterialTheme.typography.labelMedium.merge(Numeric), color = muted)
            }
        }
        Spacer(Modifier.height(10.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .pointerInput(points) {
                    detectTapGestures(onPress = { selected = nearest(points, it.x / (size.width - GUTTER.toPx())) })
                }
                // Sideways only, so scrolling the page up and down over the chart still works.
                .pointerInput(points) {
                    detectHorizontalDragGestures(
                        onDragStart = { selected = nearest(points, it.x / (size.width - GUTTER.toPx())) },
                        onHorizontalDrag = { change, _ -> selected = nearest(points, change.position.x / (size.width - GUTTER.toPx())) },
                    )
                },
        ) {
            if (points.isEmpty()) return@Canvas
            // Zoomed to your own readings so small changes show; the target is drawn only when it falls inside.
            val values = points.flatMap { listOf(it.raw, it.trend) }
            val pad = maxOf(if (decimals == 0) 1.0 else 0.4, (values.max() - values.min()) * 0.12)
            val min = values.min() - pad
            val max = values.max() + pad
            val t0 = points.first().date.toEpochDay().toDouble()
            val t1 = points.last().date.toEpochDay().toDouble()
            val span = (t1 - t0).coerceAtLeast(0.01)
            val bottom = size.height - 18.dp.toPx() // room for the date labels
            val plot = size.width - GUTTER.toPx() // the value labels get their own strip on the right
            fun x(d: LocalDate) = if (points.size == 1) plot / 2 else ((d.toEpochDay() - t0) / span * plot).toFloat()
            fun y(v: Double) = (bottom * (1 - (v - min) / (max - min))).toFloat()

            // Three value guides with their labels on the right. A narrow range gets one more
            // decimal, or whole-number metrics would label the guides 10, 12, 13.
            val labelDecimals = if (max - min < 6 * 10.0.pow(-decimals)) decimals + 1 else decimals
            for (i in 0..2) {
                val v = min + (max - min) * (0.15 + 0.35 * i)
                val yy = y(v)
                drawLine(grid, Offset(0f, yy), Offset(plot, yy), strokeWidth = 1.dp.toPx())
                val text = measurer.measure(v.format(labelDecimals), labelStyle)
                drawText(text, topLeft = Offset(size.width - text.size.width, yy - text.size.height / 2))
            }
            target?.takeIf { it in min..max }?.let { goal ->
                drawLine(
                    muted.copy(alpha = 0.7f),
                    Offset(0f, y(goal)),
                    Offset(plot, y(goal)),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())),
                )
            }

            val line = Path()
            points.forEachIndexed { i, p -> if (i == 0) line.moveTo(x(p.date), y(p.trend)) else line.lineTo(x(p.date), y(p.trend)) }
            if (points.size > 1) {
                val fill = Path().apply {
                    addPath(line)
                    lineTo(x(points.last().date), bottom)
                    lineTo(x(points.first().date), bottom)
                    close()
                }
                drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.26f), color.copy(alpha = 0f)), endY = bottom))
            }
            points.forEach { drawCircle(dotColor, radius = 2.5.dp.toPx(), center = Offset(x(it.date), y(it.raw))) }
            if (points.size > 1) {
                drawPath(line, color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }

            val focus = selected?.let { points.getOrNull(it) } ?: points.last()
            if (selected != null) {
                drawLine(muted.copy(alpha = 0.6f), Offset(x(focus.date), 0f), Offset(x(focus.date), bottom), strokeWidth = 1.dp.toPx())
            }
            drawCircle(color, radius = 4.5.dp.toPx(), center = Offset(x(focus.date), y(focus.trend)))
            drawCircle(color, radius = 3.dp.toPx(), center = Offset(x(focus.date), y(focus.raw)))

            // Dates along the bottom: first and last, plus the middle when there's room
            val first = measurer.measure(points.first().date.format(dayFormat), labelStyle)
            drawText(first, topLeft = Offset(0f, size.height - first.size.height))
            if (points.size > 1) {
                val last = measurer.measure(points.last().date.format(dayFormat), labelStyle)
                drawText(last, topLeft = Offset(plot - last.size.width, size.height - last.size.height))
                if (span >= 14) {
                    val mid = LocalDate.ofEpochDay(((t0 + t1) / 2).toLong())
                    val m = measurer.measure(mid.format(dayFormat), labelStyle)
                    drawText(m, topLeft = Offset(plot / 2 - m.size.width / 2, size.height - m.size.height))
                }
            }
        }
    }
}

private val GUTTER = 38.dp

/** The weigh-in closest to a horizontal position (0 = left edge, 1 = right edge). */
private fun nearest(points: List<ChartPoint>, fraction: Float): Int? {
    if (points.isEmpty()) return null
    val t0 = points.first().date.toEpochDay().toDouble()
    val span = (points.last().date.toEpochDay() - t0).coerceAtLeast(0.01)
    val t = t0 + fraction.coerceIn(0f, 1f) * span
    return points.indices.minBy { abs(points[it].date.toEpochDay() - t) }
}
