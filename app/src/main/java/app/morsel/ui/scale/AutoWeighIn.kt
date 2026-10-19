package app.morsel.ui.scale

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MonitorWeight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.morsel.AppContainer
import app.morsel.ble.WeighInDetector
import app.morsel.domain.BodyCompositionCalc
import app.morsel.ui.theme.Numeric
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

/** What the banner at the top of the screen is showing. */
private sealed interface Banner {
    data class Weighing(val kg: Double, val settled: Boolean) : Banner
    data class Saved(val id: Long, val kg: Double, val fatPct: Double?) : Banner
}

/**
 * Hands-free weigh-ins: while Platebit is open on screen and the smart scale is turned on in
 * Settings, it listens for the scale and saves each finished weigh-in by itself. It never listens
 * in the background: scanning stops as soon as the app leaves the screen.
 *
 * A weigh-in more than 5 kg from your latest one might be someone else on your scale, so that one
 * is checked with you before it's saved.
 */
@Composable
fun AutoWeighIn(container: AppContainer, onOpenBody: () -> Unit) {
    val enabled by container.settings.scaleEnabled.collectAsStateWithLifecycle(initialValue = false)
    val manualOpen by container.manualWeighInOpen.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()

    var banner by remember { mutableStateOf<Banner?>(null) }
    var askAbout by remember { mutableStateOf<WeighInDetector.Event.Done?>(null) }
    var lastFrameAt by remember { mutableStateOf(0L) }

    fun save(done: WeighInDetector.Event.Done) = scope.launch {
        val profile = container.plans.plan.first().profile
        val composition = profile?.let { BodyCompositionCalc.compute(done.kg, done.impedance, it) }
        val id = container.repository.logScaleWeighIn(done.kg, done.impedance, composition)
        banner = Banner.Saved(id, done.kg, composition?.fatPct)
    }

    suspend fun finished(done: WeighInDetector.Event.Done) {
        val plan = container.plans.plan.first()
        val reference = plan.weighIns.lastOrNull()?.kg ?: plan.currentWeight
        if (WeighInDetector.looksLikeYou(done.kg, reference)) save(done) else {
            banner = null
            askAbout = done
        }
    }

    LaunchedEffect(enabled, manualOpen) {
        if (!enabled || manualOpen) {
            if (banner is Banner.Weighing) banner = null
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
                    // The scale went quiet without finishing (stepped off mid-weighing): drop the live number.
                    if (banner is Banner.Weighing && now - lastFrameAt > STALE_MS) banner = null
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
                                is WeighInDetector.Event.Weighing -> {
                                    lastFrameAt = now
                                    if (banner !is Banner.Saved) banner = Banner.Weighing(event.kg, event.settled)
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

    // A saved weigh-in stays on screen long enough to read or undo, then slides away.
    LaunchedEffect(banner) {
        if (banner is Banner.Saved) {
            delay(SAVED_VISIBLE_MS)
            banner = null
        }
    }

    Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 12.dp, vertical = 8.dp)) {
        AnimatedVisibility(
            visible = banner != null,
            enter = slideInVertically(tween(220, easing = FastOutSlowInEasing)) { -it } + fadeIn(tween(160)),
            exit = slideOutVertically(tween(200, easing = FastOutSlowInEasing)) { -it } + fadeOut(tween(140)),
        ) {
            // Keeps showing the last banner while it slides out.
            var shown by remember { mutableStateOf(banner) }
            if (banner != null) shown = banner
            shown?.let { current ->
                ScaleBanner(
                    current,
                    onUndo = { id ->
                        scope.launch { container.repository.deleteWeight(id) }
                        banner = null
                    },
                    onView = {
                        banner = null
                        onOpenBody()
                    },
                )
            }
        }
    }

    askAbout?.let { done ->
        AlertDialog(
            onDismissRequest = { askAbout = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            icon = { Icon(Icons.Rounded.MonitorWeight, null) },
            title = { Text("Is this you?") },
            text = {
                Text(
                    "The scale read ${kg(done.kg)}, more than ${WeighInDetector.SAME_PERSON_KG.toInt()} kg away from your last " +
                        "weigh-in. If someone else stepped on, it won't be saved.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    save(done)
                    askAbout = null
                }) { Text("Yes, save it") }
            },
            dismissButton = { TextButton(onClick = { askAbout = null }) { Text("Not me") } },
        )
    }
}

@Composable
private fun ScaleBanner(banner: Banner, onUndo: (Long) -> Unit, onView: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.MonitorWeight, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                when (banner) {
                    is Banner.Weighing -> {
                        Text(kg(banner.kg), style = MaterialTheme.typography.titleMedium.merge(Numeric))
                        Text(
                            if (banner.settled) "Measuring body composition… stay on the scale" else "Weighing…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is Banner.Saved -> {
                        Text("Saved ${kg(banner.kg)}", style = MaterialTheme.typography.titleMedium.merge(Numeric))
                        Text(
                            banner.fatPct?.let { String.format(Locale.getDefault(), "Body fat %.1f %%", it) } ?: "Weight only, no body composition this time",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (banner is Banner.Saved) {
                TextButton(onClick = { onUndo(banner.id) }) { Text("Undo") }
                TextButton(onClick = onView) { Text("View") }
            }
        }
    }
}

private fun kg(value: Double) = String.format(Locale.getDefault(), "%.1f kg", value)

private const val STALE_MS = 6_000L
private const val RETRY_MS = 5_000L
private const val SAVED_VISIBLE_MS = 7_000L
