package app.morsel.ui.dashboard

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.morsel.ble.MiScaleReading
import app.morsel.ble.MiScaleScanner
import app.morsel.data.db.WeightEntity
import app.morsel.domain.BodyComposition
import app.morsel.domain.BodyCompositionCalc
import app.morsel.domain.UserProfile
import app.morsel.ui.components.ProgressLine
import app.morsel.ui.components.format
import app.morsel.ui.theme.Numeric
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlin.math.abs

/** What the dialog knows about the weigh-in so far. */
private data class Weighing(
    /** The number on the scale right now, still settling. */
    val live: Double? = null,
    /** Set once the scale reports a stable weight. */
    val settled: Double? = null,
    val impedance: Int? = null,
)

private fun Weighing.after(r: MiScaleReading): Weighing = when {
    !r.stable -> Weighing(live = r.weightKg) // someone stepped on again: start over
    // The scale reports the stable weight first and the impedance a moment later in a second frame.
    r.impedance == null && settled != null && abs(settled - r.weightKg) < 0.3 -> this
    else -> Weighing(live = r.weightKg, settled = r.weightKg, impedance = r.impedance)
}

/** Where the weigh-in is, in the order the scale goes through it. */
private enum class Phase { WAITING, WEIGHING, MEASURING, DONE, NO_IMPEDANCE }

private val Weighing.phase
    get() = when {
        live == null -> Phase.WAITING
        settled == null -> Phase.WEIGHING
        impedance == null -> Phase.MEASURING
        else -> Phase.DONE
    }

/**
 * The scale sends its body composition reading a few seconds after the weight settles. Past this
 * it almost certainly isn't coming (shoes or socks on, dry feet, or stepped off too soon).
 */
private const val IMPEDANCE_WAIT_MS = 12_000

private const val AUTO_SAVE_DELAY_MS = 900L

/**
 * Listens to the Mi Body Composition Scale 2 and lets the user save the weigh-in once it settles.
 * Only reads the scale's Bluetooth broadcast; nothing is paired.
 */
@Composable
fun ScaleDialog(
    profile: UserProfile?,
    scale: MiScaleScanner,
    readings: () -> Flow<MiScaleReading>,
    onDismiss: () -> Unit,
    onSave: (kg: Double, impedance: Int?) -> Unit,
) {
    var permitted by remember { mutableStateOf(scale.permissionGranted) }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permitted = it }
    var weighing by remember { mutableStateOf(Weighing()) }
    var problem by remember { mutableStateOf<String?>(null) }

    val ready = scale.hasBluetooth && permitted && scale.bluetoothOn
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        problem = null
        readings()
            .catch { problem = it.message ?: "Couldn't use Bluetooth" }
            .collect { weighing = weighing.after(it) }
    }

    // Stops waiting for body composition once it's clearly not coming. Keyed on the settled
    // weight, so stepping on again restarts the wait.
    var gaveUp by remember { mutableStateOf(false) }
    LaunchedEffect(weighing.settled, weighing.impedance) {
        gaveUp = false
        if (weighing.settled != null && weighing.impedance == null) {
            delay(IMPEDANCE_WAIT_MS.toLong())
            gaveUp = true
        }
    }
    val phase = if (weighing.phase == Phase.MEASURING && gaveUp) Phase.NO_IMPEDANCE else weighing.phase

    val composition = weighing.settled?.let { kg ->
        profile?.let { BodyCompositionCalc.compute(kg, weighing.impedance, it) }
    }

    // Once the body composition is in, save it without a tap. The short pause lets the finished
    // bar and numbers register before the dialog closes.
    val latestSave by rememberUpdatedState(onSave)
    LaunchedEffect(phase, weighing.settled, weighing.impedance) {
        val kg = weighing.settled
        if (phase == Phase.DONE && kg != null) {
            delay(AUTO_SAVE_DELAY_MS)
            latestSave(kg, weighing.impedance)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text("Smart scale") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                when {
                    !scale.hasBluetooth -> Note("This phone has no Bluetooth LE, so it can't read the scale.")
                    !permitted -> Note("Allow Bluetooth so Platebit can hear your scale. It only listens to the scale's broadcast and never needs your location.")
                    !scale.bluetoothOn -> Note("Turn Bluetooth on, then reopen this.")
                    problem != null -> Note(problem!!)
                    else -> Reading(weighing, phase, composition)
                }
            }
        },
        confirmButton = {
            when {
                // A complete reading saves itself; the button is only for a weight with no composition.
                ready && problem == null && phase == Phase.NO_IMPEDANCE -> TextButton(
                    onClick = { weighing.settled?.let { onSave(it, weighing.impedance) } },
                ) { Text("Save weight only") }
                scale.hasBluetooth && !permitted -> TextButton(onClick = { askPermission.launch(Manifest.permission.BLUETOOTH_SCAN) }) { Text("Allow") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Reading(weighing: Weighing, phase: Phase, composition: BodyComposition?) {
    val shown = weighing.settled ?: weighing.live
    Text(
        if (shown == null) "— kg" else "${shown.format(2)} kg",
        style = MaterialTheme.typography.headlineMedium.merge(Numeric),
        color = if (weighing.settled != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(14.dp))
    MeasureProgress(phase)
    Spacer(Modifier.height(10.dp))
    Text(
        when (phase) {
            Phase.WAITING -> "Step on the scale barefoot, with both feet on the metal pads."
            Phase.WEIGHING -> "Hold still while the weight settles…"
            Phase.MEASURING -> "Weight locked. Measuring body composition, stay still for a few seconds…"
            Phase.DONE -> "Done. Saving your weigh-in…"
            Phase.NO_IMPEDANCE -> "No body composition this time. It needs bare, dry feet on the pads. You can still save the weight."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val kg = weighing.settled
    if (phase == Phase.DONE && composition != null && kg != null) {
        Spacer(Modifier.height(12.dp))
        CompositionFacts(composition, kg)
    }
}

/**
 * Two steps, weight then body composition. The scale doesn't say how far along it is, so the bar
 * creeps forward while it works and jumps to full when the reading arrives.
 */
@Composable
private fun MeasureProgress(phase: Phase) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(phase) {
        when (phase) {
            Phase.WAITING -> progress.snapTo(0f)
            Phase.WEIGHING -> progress.animateTo(0.4f, tween(3_000, easing = LinearOutSlowInEasing))
            Phase.MEASURING -> {
                progress.animateTo(0.5f, tween(250))
                progress.animateTo(0.95f, tween(IMPEDANCE_WAIT_MS - 2_000, easing = LinearOutSlowInEasing))
            }
            Phase.DONE -> progress.animateTo(1f, tween(300))
            Phase.NO_IMPEDANCE -> progress.animateTo(0.5f, tween(300))
        }
    }
    val color = when (phase) {
        Phase.DONE -> DoneGreen
        Phase.NO_IMPEDANCE -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.primary
    }
    ProgressLine(progress.value, color, height = 8.dp)
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth()) {
        Step("Weight", done = phase >= Phase.MEASURING, Modifier.weight(1f))
        Step("Body composition", done = phase == Phase.DONE, Modifier.weight(1f), alignEnd = true)
    }
}

private val DoneGreen = Color(0xFF4CD48A)

@Composable
private fun Step(label: String, done: Boolean, modifier: Modifier, alignEnd: Boolean = false) {
    Row(modifier, horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
        if (done) {
            Icon(Icons.Rounded.CheckCircle, null, Modifier.size(16.dp), tint = DoneGreen)
            Spacer(Modifier.width(4.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (done) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The everyday body composition numbers, the same ones the Body screen lists first. */
@Composable
fun CompositionFacts(c: BodyComposition, weightKg: Double) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Fact("Body fat", "${c.fatPct.format(1)} %")
        Fact("Muscle", "${c.musclePct(weightKg).format(1)} %")
        Fact("Water", "${c.waterPct.format(1)} %")
        Fact("Bone", "${c.bonePct(weightKg).format(1)} %")
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium.merge(Numeric))
    }
}

/**
 * A short one-line summary of a weigh-in's composition, or null for a manual entry. Worked out
 * again from the stored impedance so it always agrees with the Body screen.
 */
fun WeightEntity.compositionLine(profile: UserProfile?): String? {
    val c = profile?.let { BodyCompositionCalc.compute(kg, impedance, it) } ?: return null
    return "Fat ${c.fatPct.format(1)} % · Muscle ${c.musclePct(kg).format(1)} % · Water ${c.waterPct.format(1)} %"
}
