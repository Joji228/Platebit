package app.morsel.ui.scale

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MonitorWeight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.morsel.AppContainer
import app.morsel.ble.WeighInDetector
import app.morsel.domain.BodyCompositionCalc
import app.morsel.ui.components.ProgressLine
import app.morsel.ui.components.RoundIconButton
import app.morsel.ui.components.rememberHaptics
import app.morsel.ui.theme.Numeric
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/** Where the weigh-in on the scale is. */
private enum class Stage { WEIGHING, MEASURING, DONE }

/**
 * Hands-free weigh-ins: while Platebit is open on screen and the smart scale is turned on in
 * Settings, it listens for the scale. As soon as someone steps on, a full-screen dial follows the
 * weight; once body composition is measured the weigh-in is saved and the Body screen opens.
 * It never listens in the background: scanning stops as soon as the app leaves the screen.
 *
 * A weigh-in more than 5 kg from your latest one might be someone else on your scale, so that one
 * is checked with you before it's saved.
 */
@Composable
fun AutoWeighIn(container: AppContainer, onOpenBody: () -> Unit) {
    val enabled by container.settings.scaleEnabled.collectAsStateWithLifecycle(initialValue = false)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()

    var kg by remember { mutableStateOf<Double?>(null) }
    var stage by remember { mutableStateOf(Stage.WEIGHING) }
    var dismissed by remember { mutableStateOf(false) }
    var lastFrameAt by remember { mutableLongStateOf(0L) }
    var askAbout by remember { mutableStateOf<WeighInDetector.Event.Done?>(null) }

    fun close() {
        kg = null
        stage = Stage.WEIGHING
    }

    fun save(done: WeighInDetector.Event.Done) = scope.launch {
        val profile = container.plans.plan.first().profile
        val composition = profile?.let { BodyCompositionCalc.compute(done.kg, done.impedance, it) }
        container.repository.logScaleWeighIn(done.kg, done.impedance, composition)
        // The weigh-in is in: a firm click, even when you're standing on the scale looking elsewhere.
        haptics.click()
        // Let the finished dial and full bar register, then go straight to the analysis.
        delay(700)
        val show = !dismissed
        close()
        dismissed = false
        if (show) onOpenBody()
    }

    suspend fun finished(done: WeighInDetector.Event.Done) {
        kg = done.kg
        stage = Stage.DONE
        val plan = container.plans.plan.first()
        val reference = plan.weighIns.lastOrNull()?.kg ?: plan.currentWeight
        if (WeighInDetector.looksLikeYou(done.kg, reference)) save(done) else askAbout = done
    }

    LaunchedEffect(enabled) {
        if (!enabled) {
            if (stage != Stage.DONE) close()
            return@LaunchedEffect
        }
        // Runs only while the app is on screen; leaving it cancels the scan right away.
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val detector = WeighInDetector()
            launch {
                while (true) {
                    delay(1_000)
                    val now = System.currentTimeMillis()
                    detector.onTick(now)?.let { finished(it as WeighInDetector.Event.Done) }
                    // The scale went quiet before finishing (stepped off mid-weighing): put the screen away.
                    if (kg != null && stage != Stage.DONE && now - lastFrameAt > STALE_MS) {
                        close()
                        dismissed = false
                    }
                }
            }
            while (true) {
                // No permission or Bluetooth off just means nothing is heard; try again in a bit,
                // so turning Bluetooth on while the app is open starts working without a restart.
                if (container.scale.permissionGranted && container.scale.bluetoothOn) {
                    container.scale.readings()
                        .catch { }
                        .collect { reading ->
                            val now = System.currentTimeMillis()
                            when (val event = detector.onReading(reading, now)) {
                                is WeighInDetector.Event.Weighing -> if (stage != Stage.DONE) {
                                    lastFrameAt = now
                                    kg = event.kg
                                    stage = if (event.settled) Stage.MEASURING else Stage.WEIGHING
                                }
                                is WeighInDetector.Event.Done -> finished(event)
                                null -> Unit
                            }
                        }
                }
                delay(RETRY_MS)
            }
        }
    }

    AnimatedVisibility(
        visible = kg != null && !dismissed,
        enter = fadeIn(tween(200)) + scaleIn(tween(260, easing = FastOutSlowInEasing), initialScale = 0.96f),
        exit = fadeOut(tween(200)),
    ) {
        // Keeps the last weight while fading out.
        var shownKg by remember { mutableStateOf(kg ?: 0.0) }
        kg?.let { shownKg = it }
        BackHandler { dismissed = true }
        ScaleScreen(kg = shownKg, stage = stage, onClose = { dismissed = true })
    }

    askAbout?.let { done ->
        AlertDialog(
            onDismissRequest = {},
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            icon = { Icon(Icons.Rounded.MonitorWeight, null) },
            title = { Text("Is this you?") },
            text = {
                Text(
                    "The scale read ${formatKg(done.kg)}, more than ${WeighInDetector.SAME_PERSON_KG.toInt()} kg away from your last " +
                        "weigh-in. If someone else stepped on, it won't be saved.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    askAbout = null
                    save(done)
                }) { Text("Yes, save it") }
            },
            dismissButton = {
                TextButton(onClick = {
                    askAbout = null
                    close()
                    dismissed = false
                }) { Text("Not me") }
            },
        )
    }
}

@Composable
private fun ScaleScreen(kg: Double, stage: Stage, onClose: () -> Unit) {
    // The number and the needle glide to each new reading instead of jumping.
    val shown by animateFloatAsState(kg.toFloat(), spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessLow), label = "kg")

    // The bar: a little while weighing, then it fills steadily while body composition is measured
    // (it usually takes a few seconds), and snaps full when it's done.
    val progress = remember { Animatable(0f) }
    LaunchedEffect(stage) {
        when (stage) {
            Stage.WEIGHING -> progress.animateTo(0.12f, tween(400))
            Stage.MEASURING -> progress.animateTo(0.92f, tween(7_000, easing = LinearEasing))
            Stage.DONE -> progress.animateTo(1f, tween(300, easing = FastOutSlowInEasing))
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Swallow touches so nothing behind the screen reacts.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        RoundIconButton(Icons.Rounded.Close, "Hide", onClose, Modifier.align(Alignment.TopEnd).padding(16.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Smart scale", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            Box(contentAlignment = Alignment.Center) {
                Dial(shown, Modifier.fillMaxWidth().aspectRatio(1f))
                // In the open bottom of the dial, clear of the needle.
                Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
                    Text(
                        String.format(Locale.getDefault(), "%.1f", shown),
                        style = MaterialTheme.typography.displayLarge.merge(Numeric).copy(fontSize = 64.sp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("kg", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            ProgressLine(progress.value, MaterialTheme.colorScheme.primary, Modifier.fillMaxWidth(), height = 8.dp)
            Spacer(Modifier.height(14.dp))
            Text(
                when (stage) {
                    Stage.WEIGHING -> "Weighing…"
                    Stage.MEASURING -> "Measuring body composition. Stay still, barefoot"
                    Stage.DONE -> "Done"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A plain analog dial: a 240° arc of fine ticks from 0 to 150 kg, a longer tick every 10 kg, the
 * part up to the reading drawn in the accent color, and a slim needle with a round hub.
 */
@Composable
private fun Dial(kg: Float, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val faint = MaterialTheme.colorScheme.outline
    val needleColor = MaterialTheme.colorScheme.onSurface
    Canvas(modifier) {
        val radius = size.minDimension / 2f * 0.92f
        val center = Offset(size.width / 2f, size.height / 2f)
        val track = 10.dp.toPx()
        val arcSize = Size(radius * 2 - track, radius * 2 - track)
        val arcTopLeft = Offset(center.x - arcSize.width / 2, center.y - arcSize.height / 2)
        val fraction = (kg / DIAL_MAX).coerceIn(0f, 1f)

        drawArc(faint.copy(alpha = 0.35f), START_DEG, SWEEP_DEG, false, arcTopLeft, arcSize, style = Stroke(track, cap = StrokeCap.Round))
        drawArc(accent, START_DEG, SWEEP_DEG * fraction, false, arcTopLeft, arcSize, style = Stroke(track, cap = StrokeCap.Round))

        // Ticks inside the arc, every 2 kg.
        val tickOuter = radius - track * 1.6f
        for (step in 0..(DIAL_MAX.toInt() / 2)) {
            val value = step * 2f
            val major = value % 10f == 0f
            val angle = Math.toRadians((START_DEG + SWEEP_DEG * value / DIAL_MAX).toDouble())
            val inner = tickOuter - (if (major) 14.dp else 6.dp).toPx()
            drawLine(
                color = if (value <= kg) accent.copy(alpha = 0.9f) else faint.copy(alpha = if (major) 0.8f else 0.4f),
                start = Offset(center.x + inner * cos(angle).toFloat(), center.y + inner * sin(angle).toFloat()),
                end = Offset(center.x + tickOuter * cos(angle).toFloat(), center.y + tickOuter * sin(angle).toFloat()),
                strokeWidth = (if (major) 2.dp else 1.dp).toPx(),
                cap = StrokeCap.Round,
            )
        }

        val needleAngle = Math.toRadians((START_DEG + SWEEP_DEG * fraction).toDouble())
        val needleLength = tickOuter - 22.dp.toPx()
        drawLine(
            needleColor,
            start = center,
            end = Offset(center.x + needleLength * cos(needleAngle).toFloat(), center.y + needleLength * sin(needleAngle).toFloat()),
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawCircle(needleColor, radius = 7.dp.toPx(), center = center)
        drawCircle(accent, radius = 3.dp.toPx(), center = center)
    }
}

private fun formatKg(value: Double) = String.format(Locale.getDefault(), "%.1f kg", value)

private const val DIAL_MAX = 150f
private const val START_DEG = 150f
private const val SWEEP_DEG = 240f
private const val STALE_MS = 6_000L
private const val RETRY_MS = 5_000L
