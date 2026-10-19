package app.morsel.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.morsel.data.AiSettings
import app.morsel.data.Provider
import app.morsel.ui.components.GroupedList
import app.morsel.ui.components.ListRow
import app.morsel.ui.components.RoundIconButton
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import app.morsel.ui.components.SecondaryButton

/**
 * Where the user picks which AI reads their meal photos and enters their own API key for it.
 * Several services can be set up side by side; one is used for scans, and a second can be
 * allowed as a backup (off by default).
 */
@Composable
fun AiSettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ai = state.ai

    var keyDialog by remember { mutableStateOf<Provider?>(null) }
    var modelDialog by remember { mutableStateOf<Provider?>(null) }
    var backupPicker by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Provider?>(null) }

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
            Text("AI engine", style = MaterialTheme.typography.headlineSmall)
        }

        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            item {
                Text(
                    "Your photos go only to the engine you pick, using your own API key. " +
                        "Keys are stored encrypted on this phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }

            item { GroupLabel("Your engines") }
            item {
                Text(
                    "Pick a service in each engine's menu. The ticked engine reads your photos.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            items(ai.activeEngines, key = { it.name }) { provider ->
                GroupedList(
                    rows = listOf(
                        {
                            EngineHeader(
                                provider = provider,
                                ai = ai,
                                onUse = { viewModel.setPrimary(provider) },
                                onChange = { viewModel.changeEngine(provider, it) },
                                onRemove = if (ai.activeEngines.size > 1) ({ removing = provider }) else null,
                            )
                        },
                        { KeyRow(provider, ai, onClick = { keyDialog = provider }) },
                        { ModelRow(provider, ai, onClick = { modelDialog = provider }) },
                    ),
                )
            }
            if (!ai.isReady(ai.primary)) {
                item {
                    Text(
                        "${ai.primary.title} has no API key yet. Add one to start scanning.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
            item {
                if (ai.addableEngines.isNotEmpty()) {
                    SecondaryButton(
                        "Add engine",
                        viewModel::addEngine,
                        Modifier.fillMaxWidth(),
                        icon = Icons.Rounded.Add,
                        accent = true,
                    )
                } else {
                    Text(
                        "You can set up to ${AiSettings.MAX_ENGINES} engines.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
            item { GroupLabel("Backup engine") }
            item {
                GroupedList(
                    rows = buildList {
                        add {
                            SwitchRow(
                                title = "Use a backup",
                                subtitle = when {
                                    !ai.fallback -> "Off. Only ${ai.primary.title} is used. If it fails, you'll see why."
                                    ai.fallbackTarget == null -> "On, but no other engine has a key yet"
                                    else -> "If ${ai.primary.title} fails, ${ai.fallbackTarget!!.title} tries the same photo"
                                },
                                checked = ai.fallback,
                                onChange = viewModel::setFallback,
                            )
                        }
                        if (ai.fallback) {
                            add {
                                ListRow(
                                    "Backup engine",
                                    value = ai.fallbackTarget?.title ?: "None",
                                    onClick = { backupPicker = true },
                                )
                            }
                        }
                    },
                )
            }

        }
    }

    keyDialog?.let { provider ->
        KeyDialog(
            provider = provider,
            initialKey = ai.keyFor(provider),
            initialUrl = ai.customBaseUrl,
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
            selected = ai.modelFor(provider),
            viewModel = viewModel,
            onDismiss = {
                modelDialog = null
                viewModel.resetKeyCheck()
            },
        )
    }
    if (backupPicker) {
        BackupPicker(
            ai = ai,
            onPick = {
                viewModel.setFallbackProvider(it)
                backupPicker = false
            },
            onDismiss = { backupPicker = false },
        )
    }
    removing?.let { provider ->
        AlertDialog(
            onDismissRequest = { removing = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Remove ${provider.title}?") },
            text = {
                Text(
                    "Its saved API key is deleted from this phone." +
                        if (provider == ai.primary) " Scans will use ${(ai.activeEngines - provider).first().title} instead." else "",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeEngine(provider)
                    removing = null
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

/** Top row of an engine card: tick to use it for scans, its service menu, and Remove. */
@Composable
private fun EngineHeader(
    provider: Provider,
    ai: AiSettings,
    onUse: () -> Unit,
    onChange: (Provider) -> Unit,
    onRemove: (() -> Unit)?,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = provider == ai.primary, onClick = onUse)
        Box(Modifier.weight(1f)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { menuOpen = true }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(provider.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (provider == ai.primary) "Used for scans" else (provider.byline ?: "Tap the circle to use for scans"),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (provider == ai.primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.Rounded.ExpandMore, "Choose service", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                shape = RoundedCornerShape(16.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Provider.entries.forEach { option ->
                    val taken = option != provider && option in ai.activeEngines
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(option.title, style = MaterialTheme.typography.bodyLarge)
                                (if (taken) "Already added" else option.byline)?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        },
                        trailingIcon = if (option == provider) ({ Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary) }) else null,
                        enabled = !taken,
                        onClick = {
                            menuOpen = false
                            onChange(option)
                        },
                    )
                }
            }
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove) {
                Icon(Icons.Rounded.DeleteOutline, "Remove ${provider.title}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun KeyRow(provider: Provider, ai: AiSettings, onClick: () -> Unit) {
    val key = ai.keyFor(provider)
    val missingUrl = provider.needsBaseUrl && ai.customBaseUrl.isBlank()
    ListRow(
        "API key",
        subtitle = when {
            key.isBlank() -> "Not set"
            missingUrl -> "Saved · ••••${key.takeLast(4)} · address missing"
            provider.needsBaseUrl -> "Saved · ••••${key.takeLast(4)} · ${ai.customBaseUrl}"
            else -> "Saved · ••••${key.takeLast(4)}"
        },
        icon = if (provider.needsBaseUrl) Icons.Rounded.Link else Icons.Rounded.Key,
        iconTint = if (key.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
        onClick = onClick,
    )
}

@Composable
private fun ModelRow(provider: Provider, ai: AiSettings, onClick: () -> Unit) {
    ListRow(
        "Model",
        subtitle = ai.modelFor(provider).ifBlank { if (provider == Provider.CUSTOM) "Not chosen" else "Automatic" },
        icon = if (provider == Provider.DEEPSEEK) Icons.Rounded.Memory else Icons.Rounded.AutoAwesome,
        iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
        onClick = onClick,
    )
}

@Composable
private fun KeyDialog(
    provider: Provider,
    initialKey: String,
    initialUrl: String,
    viewModel: SettingsViewModel,
    onDismiss: () -> Unit,
) {
    var key by remember { mutableStateOf(initialKey) }
    var url by remember { mutableStateOf(initialUrl) }
    var visible by remember { mutableStateOf(false) }
    val check by viewModel.keyCheck.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text("${provider.title} API key") },
        text = {
            Column {
                Text(provider.keyHelp, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                if (provider.needsBaseUrl) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        singleLine = true,
                        label = { Text("Service address") },
                        placeholder = { Text("https://openrouter.ai/api/v1") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                }
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
            TextButton(onClick = { viewModel.saveKey(provider, key, url) }) { Text(if (key.isBlank()) "Remove" else "Save and test") }
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
    var typed by remember { mutableStateOf(selected) }
    LaunchedEffect(provider) { viewModel.loadModels(provider) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text("${provider.title} model") },
        text = {
            Column {
                Text(
                    when (provider) {
                        Provider.DEEPSEEK -> "Pick a model that accepts images. Automatic prefers deepseek-flash (V4.1 Flash)."
                        Provider.GEMINI -> "Flash models are fast and on the free tier."
                        Provider.OPENAI -> "Automatic picks a recent mini model: fast, cheap and able to read photos."
                        Provider.ANTHROPIC -> "Automatic picks a Haiku model: fast, cheap and able to read photos."
                        Provider.CUSTOM -> "Name a model that can read images, exactly as the service spells it."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                val ok = check as? KeyCheck.Ok
                val models = ok?.models.orEmpty()
                LazyColumn(Modifier.heightIn(max = 260.dp)) {
                    if (provider != Provider.CUSTOM) {
                        item {
                            val auto = ok?.automatic?.let { "Automatic ($it)" } ?: "Automatic"
                            ModelOption(auto, selected.isBlank()) {
                                viewModel.setModel(provider, "")
                                onDismiss()
                            }
                        }
                    }
                    items(models) { model ->
                        ModelOption(model, model == selected) {
                            viewModel.setModel(provider, model)
                            onDismiss()
                        }
                    }
                    if (check !is KeyCheck.Ok) {
                        item {
                            Spacer(Modifier.height(8.dp))
                            CheckStatus(check)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    singleLine = true,
                    label = { Text("Or type a model name") },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    viewModel.setModel(provider, typed)
                    onDismiss()
                },
                enabled = typed.isNotBlank() && typed != selected,
            ) { Text("Use this name") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
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

/** Lists the other added engines; ones without a key can't be chosen yet. */
@Composable
private fun BackupPicker(ai: AiSettings, onPick: (Provider) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text("Backup engine") },
        text = {
            Column {
                ai.activeEngines.filter { it != ai.primary }.forEach { provider ->
                    val ready = ai.isReady(provider)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = ready) { onPick(provider) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = provider == ai.fallbackTarget, onClick = null, enabled = ready)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                provider.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (ready) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (!ready) {
                                Text("Add a key first", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
