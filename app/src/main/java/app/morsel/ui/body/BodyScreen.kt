package app.morsel.ui.body

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import java.time.ZoneId
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.Stable
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.foundation.verticalScroll
import android.widget.Toast
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MonitorWeight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.morsel.domain.Band
import app.morsel.domain.BodyReport
import app.morsel.domain.Metric
import app.morsel.domain.Reading
import app.morsel.domain.Tone
import app.morsel.ui.components.ChartPoint
import app.morsel.ui.components.MorselCard
import app.morsel.ui.components.Pill
import app.morsel.ui.components.PillSelector
import app.morsel.ui.components.ProgressLine
import app.morsel.ui.components.RoundIconButton
import app.morsel.ui.components.SecondaryButton
import app.morsel.ui.components.TrendChart
import app.morsel.ui.components.format
import app.morsel.ui.theme.Numeric
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private enum class Span(val label: String, val days: Long?) { M1("1M", 30), M3("3M", 90), Y1("1Y", 365), ALL("All", null) }

/**
 * What the composition list shows: the everyday numbers, each with the related detail that opens
 * under it. Skeletal muscle and lean mass stay in the background; they feed the body score.
 */
private val COMPOSITION_ROWS = listOf(
    Metric.FAT to listOf(Metric.FAT_MASS),
    Metric.MUSCLE to listOf(Metric.PROTEIN),
    Metric.WATER to emptyList(),
    Metric.BONE to emptyList(),
    Metric.VISCERAL to emptyList(),
    Metric.BMR to emptyList(),
    Metric.BODY_AGE to emptyList(),
)

/** Metrics worth a full-size chart; the rest get a small one when their row is opened. */
/** Rows that lead with kilograms: bone shows its mass instead of a share, muscle shows both. */
private val SHOWN_AS = mapOf(Metric.BONE to Metric.BONE_MASS, Metric.MUSCLE to Metric.MUSCLE_MASS)
private val ALSO_SHOW_SHARE = setOf(Metric.MUSCLE)

private val CHART_METRICS = listOf(Metric.WEIGHT, Metric.BMI, Metric.FAT, Metric.MUSCLE, Metric.WATER, Metric.VISCERAL)

@Composable
fun BodyScreen(viewModel: BodyViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The whole page, not just what fits on screen, is recorded here so it can be saved as one tall image.
    val page = rememberGraphicsLayer()
    val pageBackground = MaterialTheme.colorScheme.background
    var saving by remember { mutableStateOf(false) }

    // Which measurement is shown: -1 means the newest. Swiping right goes back in time.
    val scans = state.scans
    var selected by rememberSaveable { mutableIntStateOf(-1) }
    val index = if (selected !in scans.indices) scans.lastIndex else selected
    val swipe = remember { MeasurementSwipe(scope) }
    swipe.canGoOlder = index > 0
    swipe.canGoNewer = index in 0 until scans.lastIndex
    swipe.onChange = { dir -> val target = index + dir; selected = if (target >= scans.lastIndex) -1 else target }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack)
            Spacer(Modifier.width(14.dp))
            Text("Body", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            if (state.latest != null) {
                RoundIconButton(
                    Icons.Rounded.Screenshot,
                    "Save a screenshot of the whole page to the gallery",
                    {
                        if (!saving) {
                            saving = true
                            scope.launch {
                                val saved = runCatching { savePageToGallery(context, page.toImageBitmap().asAndroidBitmap()) }.isSuccess
                                Toast.makeText(context, if (saved) "Saved the full page to your gallery" else "Couldn't save the image", Toast.LENGTH_SHORT).show()
                                saving = false
                            }
                        }
                    },
                )
            }
        }
        if (state.loading) return@Column

        // A plain scrolling column (the page is short), so the inner column is laid out at full
        // height and can be recorded whole for the gallery image.
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            Column(
                Modifier
                    .drawWithContent {
                        page.record {
                            drawRect(pageBackground)
                            this@drawWithContent.drawContent()
                        }
                        drawLayer(page)
                    }
                    .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val latest = state.latest
                if (latest == null) {
                    EmptyCard("No weigh-ins yet", "Step on your smart scale with Platebit open, or log your weight, to see your trends here.")
                    return@Column
                }
                val entry = scans.getOrNull(index)
                // The newest position shows the newest weigh-in of any kind; older ones show that measurement.
                val weighIn = if (entry == null || index == scans.lastIndex) latest else entry.report
                if (entry != null) {
                    MeasurementBar(entry, index, scans.size, onOlder = { swipe.go(-1) }, onNewer = { swipe.go(1) })
                }
                // Everything about one measurement swipes together; the trends below stay put.
                Column(
                    Modifier
                        .onSizeChanged { swipe.width = it.width.toFloat().coerceAtLeast(1f) }
                        .draggable(
                            orientation = Orientation.Horizontal,
                            enabled = scans.size > 1,
                            state = rememberDraggableState { swipe.drag(it) },
                            onDragStopped = { swipe.release(it) },
                        )
                        .graphicsLayer {
                            translationX = swipe.offset
                            alpha = 1f - 0.5f * (abs(swipe.offset) / swipe.width).coerceAtMost(1f)
                        },
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    entry?.let { ScoreCard(it.report) }
                    WeightBmiCard(weighIn)
                    if (entry == null) {
                        EmptyCard(
                            "Body composition",
                            "Turn on the smart scale in Settings, then step on it barefoot with Platebit open to see body fat, muscle, water and more.",
                        )
                    } else {
                        CompositionCard(entry.report, scans.getOrNull(index - 1)?.report, state.series)
                        Text(
                            "Estimated from your weight, the scale's impedance and your profile. Good for trends, not a medical measurement.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
                ChartCard(state, latest)
            }
        }
    }
}

/**
 * Sideways switching between measurements, like the dashboard's day swipe: the cards follow the
 * finger, then slide out while the neighbouring measurement slides in from the other side.
 * Swiping right goes to the older one, left to the newer one; past either end it just springs back.
 */
@Stable
private class MeasurementSwipe(private val scope: CoroutineScope) {
    var offset by mutableFloatStateOf(0f)
    var width by mutableFloatStateOf(1f)
    var canGoOlder = false
    var canGoNewer = false
    var onChange: (Int) -> Unit = {}
    private var busy = false

    fun drag(delta: Float) {
        if (busy) return
        val next = offset + delta
        // Nothing further that way: pulling feels heavy.
        offset = if ((next > 0 && !canGoOlder) || (next < 0 && !canGoNewer)) offset + delta * 0.3f else next
    }

    fun release(velocity: Float) {
        if (busy) return
        val far = width * 0.25f
        when {
            (offset > far || velocity > 1500f) && canGoOlder -> go(-1)
            (offset < -far || velocity < -1500f) && canGoNewer -> go(1)
            else -> settle()
        }
    }

    /** [direction] is -1 for the older measurement and 1 for the newer one. */
    fun go(direction: Int) {
        if (busy) return
        if ((direction < 0 && !canGoOlder) || (direction > 0 && !canGoNewer)) return settle()
        busy = true
        scope.launch {
            val exit = if (direction < 0) width else -width
            animate(offset, exit, animationSpec = tween(150, easing = FastOutLinearInEasing)) { v, _ -> offset = v }
            onChange(direction)
            animate(-exit, 0f, animationSpec = tween(230, easing = FastOutSlowInEasing)) { v, _ -> offset = v }
            busy = false
        }
    }

    private fun settle() {
        busy = true
        scope.launch {
            animate(offset, 0f, animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)) { v, _ -> offset = v }
            busy = false
        }
    }
}

/** Which measurement is on screen, with arrows to step through them. */
@Composable
private fun MeasurementBar(entry: ScanEntry, index: Int, count: Int, onOlder: () -> Unit, onNewer: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onOlder, enabled = index > 0) { Icon(Icons.Rounded.ChevronLeft, "Older measurement") }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            val time = Instant.ofEpochMilli(entry.timestamp).atZone(ZoneId.systemDefault())
            Text(
                time.format(DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", Locale.getDefault())),
                style = MaterialTheme.typography.titleSmall.merge(Numeric),
            )
            Text(
                if (index == count - 1) "Latest measurement" else "Measurement ${index + 1} of $count",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onNewer, enabled = index < count - 1) { Icon(Icons.Rounded.ChevronRight, "Newer measurement") }
    }
}

// ---- Weight and BMI ------------------------------------------------------------------------------

/** The weight of one weigh-in with its BMI, range bar and healthy weight, in one card. */
@Composable
private fun WeightBmiCard(report: BodyReport) {
    val reading = report.readings.first { it.metric == Metric.BMI }
    MorselCard(padding = 16.dp) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("Weight", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(report.weightKg.format(1), style = MaterialTheme.typography.headlineMedium.merge(Numeric))
                    Text(" kg", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("BMI", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    reading.segment?.let {
                        Pill(it.label, it.tone.color())
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(report.bmi.format(1), style = MaterialTheme.typography.headlineMedium.merge(Numeric))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        BandBar(reading.band!!, reading.value, decimals = 1)
        Spacer(Modifier.height(6.dp))
        Text(
            "Healthy for your height: ${report.healthyWeight.start.format(1)}–${report.healthyWeight.endInclusive.format(1)} kg · BMI 22 at ${report.idealWeightKg.format(1)} kg",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Where the weight is heading: trend, recent changes and progress to the goal (shown with the trends). */
@Composable
private fun WeightProgress(state: BodyState, latest: BodyReport) {
    val profile = state.profile ?: return
    val trend = state.trendWeight ?: latest.weightKg
    val losing = profile.goalWeightKg < profile.startWeightKg
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Trend ${trend.format(1)} kg", style = MaterialTheme.typography.titleSmall.merge(Numeric), modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            state.change7?.let { ChangePill("7d", it, losing) }
            state.change30?.let { ChangePill("30d", it, losing) }
        }
    }
    Spacer(Modifier.height(10.dp))
    val total = profile.goalWeightKg - profile.startWeightKg
    val done = trend - profile.startWeightKg
    val progress = if (abs(total) < 0.05) 1f else (done / total).toFloat().coerceIn(0f, 1f)
    ProgressLine(progress, MaterialTheme.colorScheme.primary, height = 6.dp)
    Spacer(Modifier.height(6.dp))
    Row {
        Text("Start ${profile.startWeightKg.format(1)}", style = MaterialTheme.typography.labelSmall.merge(Numeric), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        val left = profile.goalWeightKg - trend
        Text(
            if ((losing && left >= -0.05) || (!losing && left <= 0.05)) "Goal reached" else "${abs(left).format(1)} kg to go",
            style = MaterialTheme.typography.labelSmall.merge(Numeric),
        )
        Spacer(Modifier.weight(1f))
        Text("Goal ${profile.goalWeightKg.format(1)}", style = MaterialTheme.typography.labelSmall.merge(Numeric), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChangePill(label: String, change: Double, losing: Boolean) {
    val good = if (losing) change <= 0 else change >= 0
    val sign = if (change > 0.05) "+" else if (change < -0.05) "−" else ""
    Pill(
        "$label $sign${abs(change).format(1)} kg",
        if (abs(change) < 0.05) MaterialTheme.colorScheme.onSurfaceVariant else if (good) ToneColors.good else ToneColors.high,
    )
}
// ---- Trends --------------------------------------------------------------------------------------

@Composable
private fun ChartCard(state: BodyState, latest: BodyReport) {
    val available = CHART_METRICS.filter { !state.series[it].isNullOrEmpty() }
    var picked by rememberSaveable { mutableStateOf(Metric.WEIGHT) }
    var span by rememberSaveable { mutableStateOf(Span.M3) }
    val metric = if (picked in available) picked else Metric.WEIGHT
    val all = state.series[metric].orEmpty()
    val from = span.days?.let { state.today.minusDays(it) }
    val points = if (from == null) all else all.filter { !it.date.isBefore(from) }

    MorselCard {
        Text("Trends", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))
        WeightProgress(state, latest)
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            available.forEach { m -> Chip(m.title, m == metric) { picked = m } }
        }
        Spacer(Modifier.height(16.dp))
        TrendChart(
            points = points,
            unit = metric.unit,
            decimals = metric.decimals,
            target = if (metric == Metric.WEIGHT) state.profile?.goalWeightKg else null,
        )
        Spacer(Modifier.height(14.dp))
        PillSelector(Span.entries, span, { it.label }, { span = it })
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
    }
}

// ---- Score and body type -------------------------------------------------------------------------

/** A compact summary: the score, and in plain words what it says about fat and muscle. */
@Composable
private fun ScoreCard(scan: BodyReport) {
    val score = scan.score ?: return
    val type = scan.type ?: return
    MorselCard(padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScoreRing(score)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Body score", style = MaterialTheme.typography.titleSmall)
                Text(type.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                type.tip?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun ScoreRing(score: Int) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val color = when {
        score >= 80 -> ToneColors.good
        score >= 60 -> MaterialTheme.colorScheme.primary
        else -> ToneColors.high
    }
    Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 5.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 135f, 270f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(color, 135f, 270f * score / 100f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Text(score.toString(), style = MaterialTheme.typography.titleMedium.merge(Numeric))
    }
}

// ---- Composition list ----------------------------------------------------------------------------

@Composable
private fun CompositionCard(scan: BodyReport, previous: BodyReport?, series: Map<Metric, List<ChartPoint>>) {
    val rows = COMPOSITION_ROWS.mapNotNull { (main, extra) ->
        val reading = scan.readings.firstOrNull { it.metric == main } ?: return@mapNotNull null
        reading to extra.mapNotNull { m -> scan.readings.firstOrNull { it.metric == m } }
    }
    var open by rememberSaveable { mutableStateOf<Metric?>(null) }
    MorselCard(padding = 0.dp) {
        Text("Body composition", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp))
        rows.forEachIndexed { index, (reading, details) ->
            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 18.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            val shown = SHOWN_AS[reading.metric]?.let { m -> scan.readings.firstOrNull { it.metric == m } } ?: reading
            MetricRow(
                reading = reading,
                shown = shown,
                share = reading.takeIf { it.metric in ALSO_SHOW_SHARE },
                details = details,
                change = previous?.value(shown.metric)?.let { shown.value - it },
                expanded = open == reading.metric,
                points = series[reading.metric].orEmpty(),
                onToggle = { open = if (open == reading.metric) null else reading.metric },
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun MetricRow(
    reading: Reading,
    details: List<Reading>,
    change: Double?,
    expanded: Boolean,
    points: List<ChartPoint>,
    onToggle: () -> Unit,
    /** The number in the row; another reading of the same thing in other units (bone and muscle in kg). */
    shown: Reading = reading,
    /** Also shown small under the number, e.g. muscle as a share of weight. */
    share: Reading? = null,
) {
    val metric = reading.metric
    fun Double.withUnit(m: Metric = shown.metric) = format(m.decimals) + if (m.unit.isEmpty()) "" else " ${m.unit}"
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .animateContentSize()
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MetricBadge(metric, 30.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(metric.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val step = if (metric.decimals == 0) 0.5 else 0.05
                val sub = listOfNotNull(
                    share?.let { it.value.withUnit(it.metric) },
                    change?.takeIf { abs(it) >= step }?.let { (if (it > 0) "+" else "−") + abs(it).withUnit() + if (share == null) " vs last" else "" },
                ).joinToString(" · ")
                if (sub.isNotEmpty()) {
                    Text(sub, style = MaterialTheme.typography.labelSmall.merge(Numeric), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            Spacer(Modifier.width(8.dp))
            // Status and value side by side keep every row one line tall.
            reading.segment?.let {
                Pill(it.label, it.tone.color())
                Spacer(Modifier.width(8.dp))
            }
            Text(shown.value.withUnit(), style = MaterialTheme.typography.titleMedium.merge(Numeric), maxLines = 1)
            Spacer(Modifier.width(6.dp))
            Icon(
                if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                if (expanded) "Hide details" else "Show details",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        if (expanded) {
            Spacer(Modifier.height(10.dp))
            Text(metric.about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            details.forEach { detail ->
                Spacer(Modifier.height(10.dp))
                DetailRow(detail)
            }
            reading.band?.let {
                Spacer(Modifier.height(12.dp))
                BandBar(it, reading.value, metric.decimals)
            }
            if (points.size > 1) {
                Spacer(Modifier.height(14.dp))
                TrendChart(points, metric.unit, metric.decimals, height = 120.dp)
            }
        }
    }
}

/** A value shown inside an opened row, like fat in kilograms under body fat. */
@Composable
private fun DetailRow(reading: Reading) {
    val metric = reading.metric
    Row(verticalAlignment = Alignment.CenterVertically) {
        MetricBadge(metric, 26.dp)
        Spacer(Modifier.width(10.dp))
        Text(metric.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            reading.value.format(metric.decimals) + if (metric.unit.isEmpty()) "" else " ${metric.unit}",
            style = MaterialTheme.typography.bodyMedium.merge(Numeric),
        )
        reading.segment?.let {
            Spacer(Modifier.width(8.dp))
            Pill(it.label, it.tone.color())
        }
    }
}

// ---- Shared pieces -------------------------------------------------------------------------------

/** The metric's icon on a softly tinted rounded square. */
@Composable
private fun MetricBadge(metric: Metric, size: androidx.compose.ui.unit.Dp = 34.dp) {
    val tint = MetricIcons.tint(metric)
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(tint.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(MetricIcons.of(metric), null, Modifier.size(size * 0.56f), tint = tint)
    }
}

/**
 * A healthy-range bar: one coloured stretch per range, a marker at the value, and the cut-off
 * numbers under the gaps. The range's name is in the pill beside the value, so it isn't repeated.
 */
@Composable
private fun BandBar(band: Band, value: Double, decimals: Int) {
    val colors = band.segments.map { it.tone.color() }
    val marker = MaterialTheme.colorScheme.onSurface
    val ring = MaterialTheme.colorScheme.surfaceContainer
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.merge(Numeric).copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(34.dp),
    ) {
        val track = 14.dp.toPx()
        val barHeight = 6.dp.toPx()
        val top = (track - barHeight) / 2
        val gap = 3.dp.toPx()
        var start = 0f
        band.segments.forEachIndexed { i, segment ->
            val end = (band.position(segment.upTo) * size.width).toFloat()
            val width = (end - start - if (i < band.segments.lastIndex) gap else 0f).coerceAtLeast(1f)
            drawRoundRect(colors[i].copy(alpha = 0.85f), Offset(start, top), Size(width, barHeight), CornerRadius(barHeight / 2))
            if (i < band.segments.lastIndex) {
                val text = measurer.measure(segment.upTo.format(decimals), labelStyle)
                val x = (end - gap / 2 - text.size.width / 2).coerceIn(0f, size.width - text.size.width)
                drawText(text, topLeft = Offset(x, size.height - text.size.height))
            }
            start = end
        }
        val x = (band.position(value) * size.width).toFloat().coerceIn(track / 2, size.width - track / 2)
        drawCircle(ring, radius = track / 2, center = Offset(x, track / 2))
        drawCircle(marker, radius = track / 2 - 2.5.dp.toPx(), center = Offset(x, track / 2))
    }
}

@Composable
private fun EmptyCard(title: String, text: String) {
    MorselCard {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private object ToneColors {
    val low = Color(0xFFFFC542)
    val good = Color(0xFF4CD48A)
    val high = Color(0xFFFF9F43)
    val veryHigh = Color(0xFFFF6B5E)
}

private fun Tone.color() = when (this) {
    Tone.LOW -> ToneColors.low
    Tone.GOOD -> ToneColors.good
    Tone.HIGH -> ToneColors.high
    Tone.VERY_HIGH -> ToneColors.veryHigh
}
