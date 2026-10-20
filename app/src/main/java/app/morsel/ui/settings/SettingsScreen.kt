package app.morsel.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import app.morsel.data.PHOTO_DAYS
import app.morsel.data.formatBytes
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.morsel.data.Provider
import app.morsel.data.ThemeMode
import androidx.compose.material.icons.rounded.Search
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.Manifest
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.MonitorWeight
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.Check
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import app.morsel.ui.theme.AccentColor
import app.morsel.domain.Sex
import app.morsel.ui.components.GroupedList
import app.morsel.ui.components.ListRow
import app.morsel.ui.components.NumberStepper
import app.morsel.ui.components.PillSelector
import app.morsel.ui.components.RoundIconButton
import app.morsel.ui.components.format
import app.morsel.ui.theme.Nutrient
import java.time.LocalDate
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onEditProfile: () -> Unit,
    onAiEngine: () -> Unit,
    onReset: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var targetDialog by remember { mutableStateOf(false) }
    var waterDialog by remember { mutableStateOf(false) }
    var resetDialog by remember { mutableStateOf(false) }
    var scaleConfirm by remember { mutableStateOf(false) }
    var coachConfirm by remember { mutableStateOf(false) }
    // Hands-free weigh-ins need the Bluetooth scan permission; asked right when the scale is turned on.
    val askBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val scaleContext = LocalContext.current
    var photoDialog by remember { mutableStateOf(false) }
    val photoStorage by viewModel.photoStorage.collectAsStateWithLifecycle()
    val storage by viewModel.storage.collectAsStateWithLifecycle()

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { viewModel.export(context, it) }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.import(context, it) }
    }

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
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
        }

        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            item { GroupLabel("Profile") }
            item {
                val p = state.profile
                GroupedList(
                    rows = listOf(
                        {
                            ListRow(
                                "Profile and goal",
                                subtitle = p?.let {
                                    "${if (it.sex == Sex.MALE) "Male" else "Female"} · ${it.age} y · ${it.heightCm.format()} cm · goal ${it.goalWeightKg.format(1)} kg"
                                },
                                icon = Icons.Rounded.Person,
                                onClick = onEditProfile,
                            )
                        },
                        {
                            ListRow(
                                "Daily calories",
                                subtitle = when {
                                    p?.customKcal != null -> "Custom target"
                                    state.estimate?.active == true -> "Smart: tuned to your real results"
                                    else -> "Calculated from your profile"
                                },
                                value = state.targets?.let { "${it.kcal.format()} kcal" },
                                icon = Icons.Rounded.LocalFireDepartment,
                                iconTint = Nutrient.Protein,
                                onClick = { targetDialog = true },
                            )
                        },
                        {
                            ListRow(
                                "Water goal",
                                subtitle = if (p?.customWaterMl != null) "Custom target" else "Calculated from your weight and activity",
                                value = state.targets?.let { "${(it.waterMl / 1000.0).format(2).trimEnd('0').trimEnd('.', ',')} L" },
                                icon = Icons.Rounded.WaterDrop,
                                iconTint = Nutrient.Water,
                                onClick = { waterDialog = true },
                            )
                        },
                        {
                            val estimate = state.estimate
                            SwitchRow(
                                title = "Smart target",
                                subtitle = when {
                                    p?.smartTarget == false -> "Off. Your calories use the standard formula only"
                                    estimate == null -> "Tunes your daily calories to how your body really responds"
                                    estimate.active -> "Your real burn is about ${estimate.measuredTdee!!.format()} kcal a day, " +
                                        "measured from your meals and weigh-ins (${(estimate.confidence * 100).roundToInt()}% trusted)"
                                    else -> "Learning your real burn. ${estimate.waitingFor} " +
                                        "(${estimate.daysLogged} of ${estimate.daysNeeded} days of meals logged)"
                                },
                                checked = p?.smartTarget != false,
                                onChange = { on -> viewModel.updateProfile { it.copy(smartTarget = on) } },
                                icon = Icons.Rounded.Psychology,
                                iconTint = Nutrient.Fat,
                            )
                        },
                    ),
                )
            }

            item { GroupLabel("AI") }
            item {
                val ai = state.ai
                GroupedList(
                    rows = listOf(
                        {
                            ListRow(
                                "AI engine",
                                subtitle = if (ai.isReady(ai.primary)) {
                                    val model = ai.modelFor(ai.primary).ifBlank { if (ai.primary == Provider.CUSTOM) "no model chosen" else "automatic model" }
                                    "${ai.primary.title} · $model" + (ai.fallbackTarget?.let { " · backup: ${it.title}" } ?: "")
                                } else {
                                    "Add an API key to start scanning meals"
                                },
                                icon = Icons.Rounded.AutoAwesome,
                                onClick = onAiEngine,
                            )
                        },
                    ),
                )
            }

            item { GroupLabel("Appearance") }
            item {
                GroupedList(
                    rows = listOf(
                        {
                            PillSelector(
                                ThemeMode.entries,
                                state.theme,
                                { it.title },
                                viewModel::setTheme,
                                Modifier.padding(14.dp),
                            )
                        },
                        {
                            AccentPicker(state.accent, viewModel::setAccent, Modifier.padding(14.dp))
                        },
                    ),
                )
            }

            item { GroupLabel("Data") }
            item {
                GroupedList(
                    rows = listOf(
                        {
                            ListRow(
                                "Export backup",
                                subtitle = "Save all your data to a file",
                                icon = Icons.Rounded.Upload,
                                onClick = { exportLauncher.launch("platebit-backup-${LocalDate.now()}.json") },
                            )
                        },
                        {
                            ListRow(
                                "Import backup",
                                subtitle = "Replaces the data on this phone",
                                icon = Icons.Rounded.Download,
                                onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                            )
                        },
                        {
                            ListRow(
                                "Meal photos",
                                subtitle = (photoStorage?.second?.let { if (it == 1) "1 photo · " else "$it photos · " } ?: "") +
                                    "deleted after $PHOTO_DAYS days, meals stay logged. Tap to delete now",
                                value = photoStorage?.first,
                                icon = Icons.Rounded.PhotoLibrary,
                                iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                                onClick = { photoDialog = true },
                            )
                        },
                        {
                            val usage = storage
                            ListRow(
                                "Storage used",
                                subtitle = usage?.let {
                                    "App ${formatBytes(it.appBytes)} · your data ${formatBytes(it.dataBytes)} (meals, photos, settings)"
                                },
                                value = usage?.let { formatBytes(it.totalBytes) },
                                icon = Icons.Rounded.Storage,
                                iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    ),
                )
            }

            item { GroupLabel("Smart scale") }
            item {
                GroupedList(
                    rows = listOf(
                        {
                            SwitchRow(
                                title = "Xiaomi smart scale",
                                subtitle = if (state.scaleEnabled) {
                                    "On. Open Platebit and step on the scale; weigh-ins save by themselves"
                                } else {
                                    "For the Mi Body Composition Scale 2 (XMTZC05HM). Adds weigh-ins over Bluetooth and a Body screen"
                                },
                                checked = state.scaleEnabled,
                                // Turning it on asks first; turning it off doesn't need to.
                                onChange = { on -> if (on) scaleConfirm = true else viewModel.setScaleEnabled(false) },
                                icon = Icons.Rounded.MonitorWeight,
                            )
                        },
                    ),
                )
            }

            item { GroupLabel("Food database") }
            item {
                val foodFactsOn by viewModel.useFoodFacts.collectAsStateWithLifecycle(initialValue = false)
                GroupedList(
                    rows = listOf(
                        {
                            SwitchRow(
                                title = "Use Open Food Facts",
                                subtitle = if (foodFactsOn) {
                                    "On. Packaged foods in scans are checked against their real labels, and Add item can search products. Adds a couple of seconds to scans"
                                } else {
                                    "Free, open product database. Checks packaged foods in scans and lets you search products when adding food. Adds a couple of seconds; only food names are sent, never photos"
                                },
                                checked = foodFactsOn,
                                onChange = viewModel::setUseFoodFacts,
                                icon = Icons.Rounded.Search,
                            )
                        },
                    ),
                )
            }

            item { GroupLabel("AI coach · Beta") }
            item {
                val coachOn by viewModel.coachEnabled.collectAsStateWithLifecycle(initialValue = false)
                GroupedList(
                    rows = listOf(
                        {
                            SwitchRow(
                                title = "AI coach",
                                subtitle = if (coachOn) {
                                    "On. Tap the sparkle on Today to ask how you're doing"
                                } else {
                                    "A chat that sees your meals, water, weigh-ins and body composition and explains how you're doing. Uses your AI engine"
                                },
                                checked = coachOn,
                                // Turning it on asks first, since it sends your data to the AI engine; off needs no question.
                                onChange = { on -> if (on) coachConfirm = true else viewModel.setCoachEnabled(false) },
                                icon = Icons.Rounded.Forum,
                            )
                        },
                    ),
                )
            }

            item { GroupLabel("Danger zone") }
            item {
                GroupedList(
                    rows = listOf(
                        {
                            ListRow(
                                "Erase everything and start over",
                                subtitle = "Deletes meals, water, weight, photos and your profile",
                                icon = Icons.Rounded.DeleteForever,
                                iconTint = MaterialTheme.colorScheme.error,
                                onClick = { resetDialog = true },
                            )
                        },
                    ),
                )
            }

            item {
                Text(
                    "Platebit ${context.packageManager.getPackageInfo(context.packageName, 0).versionName} · " +
                        "Your data stays on this phone. Photos are sent only to the AI provider you choose.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp),
                )
            }
        }
    }

    if (targetDialog) {
        val profile = state.profile
        var kcal by remember { mutableDoubleStateOf((state.targets?.kcal ?: 2000).toDouble()) }
        AlertDialog(
            onDismissRequest = { targetDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Daily calories") },
            text = { NumberStepper("Target", kcal, { kcal = it }, 800.0..6000.0, 50.0, "kcal") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateProfile { it.copy(customKcal = kcal.toInt()) }
                    targetDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                if (profile?.customKcal != null) {
                    TextButton(onClick = {
                        viewModel.updateProfile { it.copy(customKcal = null) }
                        targetDialog = false
                    }) { Text("Use automatic") }
                } else {
                    TextButton(onClick = { targetDialog = false }) { Text("Cancel") }
                }
            },
        )
    }
    if (waterDialog) {
        var ml by remember { mutableDoubleStateOf((state.targets?.waterMl ?: 2500).toDouble()) }
        AlertDialog(
            onDismissRequest = { waterDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Water goal") },
            text = { NumberStepper("Per day", ml, { ml = it }, 500.0..6000.0, 250.0, "ml") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateProfile { it.copy(customWaterMl = ml.toInt()) }
                    waterDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                if (state.profile?.customWaterMl != null) {
                    TextButton(onClick = {
                        viewModel.updateProfile { it.copy(customWaterMl = null) }
                        waterDialog = false
                    }) { Text("Use automatic") }
                } else {
                    TextButton(onClick = { waterDialog = false }) { Text("Cancel") }
                }
            },
        )
    }
    if (photoDialog) {
        val count = photoStorage?.second ?: 0
        AlertDialog(
            onDismissRequest = { photoDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text(if (count == 0) "No meal photos" else "Delete meal photos?") },
            text = {
                Text(
                    if (count == 0) {
                        "There are no meal photos saved right now."
                    } else {
                        "Removes ${if (count == 1) "1 photo" else "all $count photos"} (${photoStorage?.first}). " +
                            "Every meal stays logged with its calories and macros. This can't be undone."
                    },
                )
            },
            confirmButton = {
                if (count == 0) {
                    TextButton(onClick = { photoDialog = false }) { Text("OK") }
                } else {
                    TextButton(onClick = {
                        photoDialog = false
                        viewModel.deleteAllPhotos()
                    }) { Text("Delete photos", color = MaterialTheme.colorScheme.error) }
                }
            },
            dismissButton = {
                if (count > 0) TextButton(onClick = { photoDialog = false }) { Text("Cancel") }
            },
        )
    }
    if (scaleConfirm) {
        AlertDialog(
            onDismissRequest = { scaleConfirm = false },
            title = { Text("Turn on the smart scale?") },
            text = {
                Text(
                    "This works only with the Xiaomi Mi Body Composition Scale 2, model XMTZC05HM: the square glass scale " +
                        "with metal pads on top. Other Xiaomi scales won't be recognized.\n\n" +
                        "While Platebit is open on your screen, it listens for the scale and saves each weigh-in by itself, " +
                        "with no button to press. It never listens in the background. There's nothing to pair, it never needs " +
                        "your location, and the Zepp Life app keeps working as before. A weigh-in more than 5 kg from your " +
                        "last one is checked with you first, in case someone else stepped on.\n\n" +
                        "You'll also get a Body screen with body fat, muscle, water and more. " +
                        "These are estimates for following trends, not medical measurements.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setScaleEnabled(true)
                    scaleConfirm = false
                    if (ContextCompat.checkSelfPermission(scaleContext, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                        askBluetooth.launch(Manifest.permission.BLUETOOTH_SCAN)
                    }
                }) { Text("Turn on") }
            },
            dismissButton = { TextButton(onClick = { scaleConfirm = false }) { Text("Cancel") } },
        )
    }
    if (coachConfirm) {
        AlertDialog(
            onDismissRequest = { coachConfirm = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Turn on the AI coach?") },
            text = {
                Text(
                    "The coach is a chat that answers questions about your progress from your own data.\n\n" +
                        "Each question sends a summary of your data to your AI engine (${state.ai.primary.title}): your profile " +
                        "(sex, age, height and goals), daily totals, recent meals, water, weigh-ins and body composition. " +
                        "Photos and API keys are never sent.\n\n" +
                        "It's a beta. Answers are general guidance, not medical advice.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setCoachEnabled(true)
                    coachConfirm = false
                }) { Text("Turn on") }
            },
            dismissButton = { TextButton(onClick = { coachConfirm = false }) { Text("Cancel") } },
        )
    }
    if (resetDialog) {
        ResetDialog(
            onDismiss = { resetDialog = false },
            onConfirm = { keepKeys ->
                resetDialog = false
                viewModel.resetAll(keepKeys, onReset)
            },
        )
    }
    message?.let {
        AlertDialog(
            onDismissRequest = { viewModel.message.value = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            text = { Text(it) },
            confirmButton = { TextButton(onClick = { viewModel.message.value = null }) { Text("OK") } },
        )
    }
}

@Composable
internal fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Same icon tile as ListRow, so switch rows line up with the rows around them.
        if (icon != null) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(iconTint.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, Modifier.size(18.dp), tint = iconTint)
            }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

/** Needs two taps on the red button, so a stray tap can't wipe months of data. */
@Composable
private fun ResetDialog(onDismiss: () -> Unit, onConfirm: (keepKeys: Boolean) -> Unit) {
    var removeKeys by remember { mutableStateOf(false) }
    var armed by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        icon = { Icon(Icons.Rounded.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("Erase everything?") },
        text = {
            Column {
                Text(
                    "All meals, water, weight history, photos and your profile will be deleted from this phone. " +
                        "This can't be undone. Export a backup first if you might want it back.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { removeKeys = !removeKeys },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = removeKeys, onCheckedChange = { removeKeys = it })
                    Text("Also remove my API keys", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (armed) onConfirm(!removeKeys) else armed = true }) {
                Text(if (armed) "Tap again to erase" else "Erase", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun GroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp, top = 14.dp, bottom = 2.dp),
    )
}

/** The accent colors in one row; the chosen one carries a check mark and a ring. */
@Composable
private fun AccentPicker(selected: AccentColor, onSelect: (AccentColor) -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Accent color", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(selected.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AccentColor.entries.chunked(5).forEach { rowColors ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                rowColors.forEach { accent ->
                    val isSelected = accent == selected
                    val color = if (dark) accent.dark else accent.light
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .then(if (isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                            .padding(5.dp)
                            .clip(CircleShape)
                            .background(color)
                            .semantics { contentDescription = accent.title }
                            .selectable(
                                selected = isSelected,
                                role = Role.RadioButton,
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onSelect(accent)
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isSelected) {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = ", selected",
                                tint = if (dark) Color(0xFF0A0A0A) else Color.White,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
