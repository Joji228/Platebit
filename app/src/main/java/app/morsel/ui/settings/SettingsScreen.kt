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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountCircle
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
import app.morsel.domain.Sex
import app.morsel.ui.components.GroupedList
import app.morsel.ui.components.ListRow
import app.morsel.ui.components.NumberStepper
import app.morsel.ui.components.PillSelector
import app.morsel.ui.components.RoundIconButton
import app.morsel.ui.components.format
import app.morsel.ui.theme.Nutrient
import java.time.LocalDate

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit, onEditProfile: () -> Unit, onReset: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var keyDialog by remember { mutableStateOf<Provider?>(null) }
    var modelDialog by remember { mutableStateOf<Provider?>(null) }
    var targetDialog by remember { mutableStateOf(false) }
    var waterDialog by remember { mutableStateOf(false) }
    var resetDialog by remember { mutableStateOf(false) }
    val photoStorage by viewModel.photoStorage.collectAsStateWithLifecycle()

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
                                subtitle = if (p?.customKcal != null) "Custom target" else "Calculated from your profile",
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
                    ),
                )
            }

            item { GroupLabel("AI") }
            item {
                GroupedList(
                    rows = listOf(
                        {
                            Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                                Text("Use first", style = MaterialTheme.typography.bodyLarge)
                                Spacer(Modifier.height(10.dp))
                                PillSelector(Provider.entries, state.ai.primary, { it.title }, viewModel::setPrimary)
                            }
                        },
                        {
                            SwitchRow(
                                title = "Fall back to ${Provider.entries.first { it != state.ai.primary }.title}",
                                subtitle = if (state.ai.fallback) {
                                    "Used only if ${state.ai.primary.title} fails"
                                } else {
                                    "Off: only ${state.ai.primary.title} is ever used"
                                },
                                checked = state.ai.fallback,
                                onChange = viewModel::setFallback,
                            )
                        },
                        { KeyRow(Provider.DEEPSEEK, state.ai.deepseekKey, onClick = { keyDialog = Provider.DEEPSEEK }) },
                        { ModelRow(Provider.DEEPSEEK, state.ai.deepseekModel, onClick = { modelDialog = Provider.DEEPSEEK }) },
                        { KeyRow(Provider.GEMINI, state.ai.geminiKey, onClick = { keyDialog = Provider.GEMINI }) },
                        { ModelRow(Provider.GEMINI, state.ai.geminiModel, onClick = { modelDialog = Provider.GEMINI }) },
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
                                    "deleted after $PHOTO_DAYS days, meals stay logged",
                                value = photoStorage?.first,
                                icon = Icons.Rounded.PhotoLibrary,
                                iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    ),
                )
            }

            item { GroupLabel("Account") }
            item {
                GroupedList(
                    rows = listOf(
                        {
                            ListRow(
                                "Sign in with Google",
                                subtitle = "Optional cloud backup is coming in a later version",
                                icon = Icons.Rounded.AccountCircle,
                                iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                                enabled = false,
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

    keyDialog?.let { provider ->
        KeyDialog(
            provider = provider,
            initial = state.ai.keyFor(provider),
            viewModel = viewModel,
            onDismiss = {
                keyDialog = null
                viewModel.resetKeyCheck()
            },
        )
    }
    modelDialog?.let { provider ->
        ModelDialog(
            provider = provider,
            selected = state.ai.modelFor(provider),
            viewModel = viewModel,
            onDismiss = {
                modelDialog = null
                viewModel.resetKeyCheck()
            },
        )
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
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
private fun GroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp, top = 14.dp, bottom = 2.dp),
    )
}

@Composable
private fun KeyRow(provider: Provider, key: String, onClick: () -> Unit) {
    ListRow(
        "${provider.title} API key",
        subtitle = if (key.isBlank()) "Not set" else "Saved · ••••${key.takeLast(4)}",
        icon = Icons.Rounded.Key,
        iconTint = if (key.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
        onClick = onClick,
    )
}

@Composable
private fun ModelRow(provider: Provider, model: String, onClick: () -> Unit) {
    ListRow(
        "${provider.title} model",
        subtitle = model.ifBlank { "Automatic" },
        icon = if (provider == Provider.DEEPSEEK) Icons.Rounded.Memory else Icons.Rounded.AutoAwesome,
        iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
        onClick = onClick,
    )
}

@Composable
private fun KeyDialog(provider: Provider, initial: String, viewModel: SettingsViewModel, onDismiss: () -> Unit) {
    var key by remember { mutableStateOf(initial) }
    var visible by remember { mutableStateOf(false) }
    val check by viewModel.keyCheck.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text("${provider.title} API key") },
        text = {
            Column {
                Text(
                    if (provider == Provider.DEEPSEEK) "Create one at platform.deepseek.com" else "Create a free one at aistudio.google.com",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    singleLine = true,
                    placeholder = { Text("Paste your key") },
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (visible) "Hide key" else "Show key")
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                CheckStatus(check)
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.saveKey(provider, key) }) { Text(if (key.isBlank()) "Remove" else "Save and test") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun CheckStatus(check: KeyCheck) {
    when (check) {
        KeyCheck.Idle -> Unit
        KeyCheck.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Checking…", style = MaterialTheme.typography.bodyMedium)
        }
        is KeyCheck.Ok -> Text(
            "Key works · ${check.models.size} models available",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        is KeyCheck.Failed -> Text(check.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun ModelDialog(provider: Provider, selected: String, viewModel: SettingsViewModel, onDismiss: () -> Unit) {
    val check by viewModel.keyCheck.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(provider) { viewModel.loadModels(provider) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text("${provider.title} model") },
        text = {
            Column {
                Text(
                    if (provider == Provider.DEEPSEEK) "Pick a model that accepts images. Automatic prefers deepseek-flash (V4.1 Flash)." else "Flash models are fast and on the free tier.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                val ok = check as? KeyCheck.Ok
                val models = ok?.models.orEmpty()
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    item {
                        val auto = ok?.automatic?.let { "Automatic ($it)" } ?: "Automatic"
                        ModelOption(auto, selected.isBlank()) { viewModel.setModel(provider, ""); onDismiss() }
                    }
                    items(models) { model ->
                        ModelOption(model, model == selected) { viewModel.setModel(provider, model); onDismiss() }
                    }
                    if (check !is KeyCheck.Ok) item { Spacer(Modifier.height(8.dp)); CheckStatus(check) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun ModelOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
