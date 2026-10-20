package app.morsel.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Wallet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.morsel.ai.ApiBalances
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SwapHoriz
import app.morsel.ai.OpenRouterModels
import app.morsel.data.AiSettings
import app.morsel.data.Provider
import app.morsel.data.ThinkingLevel
import app.morsel.ui.components.Pill
import app.morsel.ui.components.RoundIconButton
import app.morsel.ui.theme.Numeric
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Where the user picks which AI reads their meal photos and enters their own API key for it.
 * The credit left on the scan engine's account sits on top (for services that report it);
 * below, one card per engine. Every choice opens a sheet that slides up from the bottom.
 */
@Composable
fun AiSettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val balance by viewModel.balance.collectAsStateWithLifecycle()
    val ai = state.ai

    var serviceSheet by remember { mutableStateOf<Provider?>(null) }
    var keySheet by remember { mutableStateOf<Provider?>(null) }
    var modelSheet by remember { mutableStateOf<Provider?>(null) }
    var thinkingSheet by remember { mutableStateOf(false) }
    var backupSheet by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Provider?>(null) }

    // A new scan engine, key or address: check its balance again.
    LaunchedEffect(ai.primary, ai.keyFor(ai.primary), ai.customBaseUrl) { viewModel.refreshBalance() }

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
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            item { BalanceCard(ai.primary, ai, balance, onRefresh = viewModel::refreshBalance) }

            item {
                Text(
                    "Your photos go only to the engine you pick, using your own API key. Keys are stored encrypted on this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }

            item { GroupLabel("Your engines") }
            if (ai.activeEngines.size > 1) {
                item {
                    Text(
                        "Tap an engine to use it for scans and the coach.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }
            }
            items(ai.activeEngines, key = { it.name }) { provider ->
                EngineCard(
                    provider = provider,
                    ai = ai,
                    onUse = { viewModel.setPrimary(provider) },
                    onService = { serviceSheet = provider },
                    onKey = { keySheet = provider },
                    onModel = { modelSheet = provider },
                    onThinking = { thinkingSheet = true },
                    onRemove = if (ai.activeEngines.size > 1) ({ removing = provider }) else null,
                )
            }
            item {
                if (ai.addableEngines.isNotEmpty()) {
                    Surface(
                        onClick = viewModel::addEngine,
                        shape = RoundedCornerShape(18.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(vertical = 14.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Add, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("Add engine", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                } else {
                    Text(
                        "You can set up to ${AiSettings.MAX_ENGINES} engines.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }
            }

            item { GroupLabel("Backup engine") }
            item {
                Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column {
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
                        if (ai.fallback) {
                            SettingRow(
                                icon = Icons.Rounded.AutoAwesome,
                                title = "Backup engine",
                                value = ai.fallbackTarget?.title ?: "None",
                                onClick = { backupSheet = true },
                            )
                        }
                    }
                }
            }
        }
    }

    serviceSheet?.let { provider ->
        ServiceSheet(provider, ai, onPick = { viewModel.changeEngine(provider, it) }, onDismiss = { serviceSheet = null })
    }
    keySheet?.let { provider ->
        KeySheet(
            provider = provider,
            initialKey = ai.keyFor(provider),
            initialUrl = ai.customBaseUrl,
            viewModel = viewModel,
            onDismiss = {
                keySheet = null
                viewModel.resetKeyCheck()
            },
        )
    }
    modelSheet?.let { provider ->
        ModelSheet(
            provider = provider,
            selected = ai.modelFor(provider),
            viewModel = viewModel,
            onDismiss = {
                modelSheet = null
                viewModel.resetKeyCheck()
            },
        )
    }
    if (thinkingSheet) {
        Sheet(
            title = "DeepSeek thinking",
            subtitle = "How long DeepSeek reasons before answering. Off or Low is usually plenty for reading a meal and makes scans faster.",
            onDismiss = { thinkingSheet = false },
        ) { close ->
            ThinkingLevel.entries.forEach { level ->
                Option(level.title, level.about, selected = level == ai.deepseekThinking) {
                    viewModel.setDeepseekThinking(level)
                    close()
                }
            }
        }
    }
    if (backupSheet) {
        Sheet(title = "Backup engine", subtitle = "Tries the same photo if your scan engine fails.", onDismiss = { backupSheet = false }) { close ->
            ai.activeEngines.filter { it != ai.primary }.forEach { provider ->
                val ready = ai.isReady(provider)
                Option(provider.title, if (ready) provider.byline else "Add a key first", selected = provider == ai.fallbackTarget, enabled = ready) {
                    viewModel.setFallbackProvider(provider)
                    close()
                }
            }
        }
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

/**
 * Credit left on the scan engine's account, with a refresh button. DeepSeek and OpenRouter report
 * it; for others the card just says the service doesn't share it.
 */
@Composable
private fun BalanceCard(provider: Provider, ai: AiSettings, state: BalanceState, onRefresh: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val canCheck = ApiBalances.supported(provider, ai)
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, primary.copy(alpha = if (canCheck) 0.3f else 0.12f)),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(primary.copy(alpha = if (canCheck) 0.12f else 0.04f), Color.Transparent)))
                .padding(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(36.dp)
                        .background(primary.copy(alpha = 0.16f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Wallet, null, Modifier.size(20.dp), tint = primary)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "CREDIT LEFT",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold),
                        color = primary,
                    )
                    Text("${provider.title} account", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (canCheck && ai.isReady(provider)) RefreshButton(spinning = state is BalanceState.Loading, onClick = onRefresh)
            }
            Spacer(Modifier.height(12.dp))
            AnimatedContent(targetState = state, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) }, label = "balance") { s ->
                Column {
                    when {
                        !canCheck -> Text(
                            "${provider.title} doesn't share your balance with apps. Check it on its website. DeepSeek and OpenRouter keys show it here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        !ai.isReady(provider) -> Text(
                            "Add your ${provider.title} key below to see what's left.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        s is BalanceState.Ok -> {
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(s.balance.amounts.first().text, style = MaterialTheme.typography.displaySmall.merge(Numeric))
                                s.balance.amounts.drop(1).forEach {
                                    Spacer(Modifier.width(10.dp))
                                    Text(it.text, style = MaterialTheme.typography.titleMedium.merge(Numeric), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                listOfNotNull(s.balance.detail, "updated ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(s.at))}").joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        s is BalanceState.Failed -> Text(s.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        else -> Text("Checking…", style = MaterialTheme.typography.displaySmall.merge(Numeric), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun RefreshButton(spinning: Boolean, onClick: () -> Unit) {
    val turn = rememberInfiniteTransition(label = "refresh")
    val angle by turn.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "angle")
    Surface(
        onClick = { if (!spinning) onClick() },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.size(40.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Rounded.Refresh,
                "Refresh balance",
                Modifier
                    .size(20.dp)
                    .graphicsLayer { rotationZ = if (spinning) angle else 0f },
            )
        }
    }
}

/**
 * One engine. Tapping anywhere on the card makes it the one Platebit uses (scans and the coach);
 * its rows open their own sheets, and the name opens the service list.
 */
@Composable
private fun EngineCard(
    provider: Provider,
    ai: AiSettings,
    onUse: () -> Unit,
    onService: () -> Unit,
    onKey: () -> Unit,
    onModel: () -> Unit,
    onThinking: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    val used = provider == ai.primary
    val primary = MaterialTheme.colorScheme.primary
    Surface(
        onClick = onUse,
        shape = RoundedCornerShape(22.dp),
        color = if (used) primary.copy(alpha = 0.07f).compositeOver(MaterialTheme.colorScheme.surfaceContainer) else MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(if (used) 1.5.dp else 1.dp, if (used) primary.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // A letter badge for the service, accent-coloured when it reads the photos.
                Box(
                    Modifier
                        .size(40.dp)
                        .background(if (used) primary else MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(13.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        provider.title.take(1),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (used) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    // Plain text: the whole card picks the engine, and the Service row below switches it.
                    // (A small tappable name here grabbed taps meant for the card.)
                    Text(provider.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (used) provider.byline ?: "Reads your photos and runs the coach" else "Tap to use this engine",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (used) {
                    Row(
                        Modifier
                            .background(primary, RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Check, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(4.dp))
                        Text("In use", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
                if (onRemove != null) {
                    IconButton(onClick = onRemove) {
                        Icon(Icons.Rounded.DeleteOutline, "Remove ${provider.title}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            SettingRow(icon = Icons.Rounded.SwapHoriz, title = "Service", value = provider.title, onClick = onService)
            val key = ai.keyFor(provider)
            val missingUrl = provider.needsBaseUrl && ai.customBaseUrl.isBlank()
            SettingRow(
                icon = if (provider.needsBaseUrl) Icons.Rounded.Link else Icons.Rounded.Key,
                title = "API key",
                value = when {
                    key.isBlank() -> null
                    provider.needsBaseUrl && !missingUrl -> "••••${key.takeLast(4)} · ${ai.customBaseUrl.removePrefix("https://").removePrefix("http://")}"
                    else -> "••••${key.takeLast(4)}"
                },
                status = when {
                    key.isBlank() -> Status("Not set", MaterialTheme.colorScheme.error)
                    missingUrl -> Status("Address missing", MaterialTheme.colorScheme.error)
                    else -> Status("Saved", primary)
                },
                onClick = onKey,
            )
            SettingRow(
                icon = if (provider == Provider.DEEPSEEK) Icons.Rounded.Memory else Icons.Rounded.AutoAwesome,
                title = "Model",
                value = ai.modelFor(provider).ifBlank { if (provider == Provider.CUSTOM) "Not chosen" else "Automatic" },
                onClick = onModel,
            )
            if (provider == Provider.DEEPSEEK) {
                SettingRow(icon = Icons.Rounded.Psychology, title = "Thinking", value = ai.deepseekThinking.title, onClick = onThinking)
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

private data class Status(val text: String, val color: Color)

/** A row in an engine card: icon, name, its current value and a chevron. */
@Composable
private fun SettingRow(icon: ImageVector, title: String, value: String?, onClick: () -> Unit, status: Status? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            value?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        status?.let {
            Spacer(Modifier.width(8.dp))
            Pill(it.text, it.color)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * A sheet that slides up from the bottom with a drag handle, a title and a short explanation.
 * Choosing something closes it with the same slide; [content] gets a `close` to call.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Sheet(
    title: String,
    subtitle: String? = null,
    onDismiss: () -> Unit,
    /**
     * Whether the whole sheet scrolls. A sheet with its own scrolling list (the model picker) turns
     * this off: a list inside a scrolling sheet inside a draggable sheet made the three fight over
     * every upward swipe, so the list bounced up and down.
     */
    scrollable: Boolean = true,
    content: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val close: () -> Unit = { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            subtitle?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(16.dp))
            content(close)
        }
    }
}

/** A choice in a sheet: rounded, the picked one tinted with the accent colour and ticked. */
@Composable
private fun Option(title: String, subtitle: String? = null, selected: Boolean, enabled: Boolean = true, badge: String? = null, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) primary.copy(alpha = 0.14f) else Color.Transparent,
        border = BorderStroke(1.dp, if (selected) primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            badge?.let {
                Spacer(Modifier.width(8.dp))
                Pill(it, primary)
            }
            if (selected) {
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Rounded.CheckCircle, null, tint = primary)
            }
        }
    }
}

@Composable
private fun ServiceSheet(provider: Provider, ai: AiSettings, onPick: (Provider) -> Unit, onDismiss: () -> Unit) {
    Sheet(title = "Choose a service", subtitle = "Each service needs its own API key. Keys you saved before stay on this phone.", onDismiss = onDismiss) { close ->
        Provider.entries.forEach { option ->
            val taken = option != provider && option in ai.activeEngines
            Option(option.title, if (taken) "Already added" else option.byline, selected = option == provider, enabled = !taken) {
                if (option != provider) onPick(option)
                close()
            }
        }
    }
}

@Composable
private fun KeySheet(provider: Provider, initialKey: String, initialUrl: String, viewModel: SettingsViewModel, onDismiss: () -> Unit) {
    var key by remember { mutableStateOf(initialKey) }
    var url by remember { mutableStateOf(initialUrl) }
    var visible by remember { mutableStateOf(false) }
    val check by viewModel.keyCheck.collectAsStateWithLifecycle()
    Sheet(title = "${provider.title} API key", subtitle = provider.keyHelp, onDismiss = onDismiss) { close ->
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
            Spacer(Modifier.height(10.dp))
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
        Spacer(Modifier.height(12.dp))
        CheckStatus(check)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SheetButton("Done", filled = false, modifier = Modifier.weight(1f), onClick = close)
            SheetButton(if (key.isBlank()) "Remove key" else "Save and test", filled = true, modifier = Modifier.weight(1f)) {
                viewModel.saveKey(provider, key, url)
            }
        }
    }
}

@Composable
private fun SheetButton(label: String, filled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (filled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        modifier = modifier.height(50.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold))
        }
    }
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
        is KeyCheck.Ok -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text("Key works · ${check.models.size} models available", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        is KeyCheck.Failed -> Text(check.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun ModelSheet(provider: Provider, selected: String, viewModel: SettingsViewModel, onDismiss: () -> Unit) {
    val check by viewModel.keyCheck.collectAsStateWithLifecycle()
    var typed by remember { mutableStateOf(selected) }
    LaunchedEffect(provider) { viewModel.loadModels(provider) }
    Sheet(
        title = "${provider.title} model",
        subtitle = when (provider) {
            Provider.DEEPSEEK -> "Pick a model that accepts images. Automatic prefers deepseek-flash (V4.1 Flash)."
            Provider.GEMINI -> "Flash models are fast and on the free tier."
            Provider.OPENAI -> "Automatic picks a recent mini model: fast, cheap and able to read photos."
            Provider.ANTHROPIC -> "Automatic picks a Haiku model: fast, cheap and able to read photos."
            Provider.OPENROUTER -> "Only models that can read photos are listed. Free ones cost nothing but have daily limits and can be busy."
            Provider.CUSTOM -> "Name a model that can read images, exactly as the service spells it."
        },
        onDismiss = onDismiss,
        scrollable = false,
    ) { close ->
        val ok = check as? KeyCheck.Ok
        val models = ok?.models.orEmpty()
        val canFilterFree = provider == Provider.OPENROUTER
        var query by remember { mutableStateOf("") }
        var freeOnly by remember { mutableStateOf(false) }
        // Long lists (OpenRouter has hundreds) get a search box, and OpenRouter a Free filter.
        if (models.size > 12 || canFilterFree) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Search ${models.size} models") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear search") }
                },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            if (canFilterFree) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChipButton("All", selected = !freeOnly) { freeOnly = false }
                    FilterChipButton("Free only · ${models.count(OpenRouterModels::isFree)}", selected = freeOnly) { freeOnly = true }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        val words = query.trim().lowercase().split(' ').filter { it.isNotEmpty() }
        val shown = models.filter { m ->
            (!freeOnly || OpenRouterModels.isFree(m)) && words.all { it in m.lowercase() }
        }
        // The only thing that scrolls in this sheet; it gives up room when the keyboard is open.
        LazyColumn(
            Modifier
                .weight(1f, fill = false)
                .heightIn(max = 420.dp),
        ) {
            if (provider != Provider.CUSTOM && words.isEmpty() && !freeOnly) {
                item {
                    Option(ok?.automatic?.let { "Automatic ($it)" } ?: "Automatic", "Picks a good model for you", selected = selected.isBlank()) {
                        viewModel.setModel(provider, "")
                        close()
                    }
                }
            }
            items(shown, key = { it }) { model ->
                Option(model, selected = model == selected, badge = if (canFilterFree && OpenRouterModels.isFree(model)) "Free" else null) {
                    viewModel.setModel(provider, model)
                    close()
                }
            }
            if (ok != null && shown.isEmpty()) {
                item {
                    Text(
                        if (freeOnly) "No free models match." else "No models match.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }
        }
        if (check !is KeyCheck.Ok) {
            Spacer(Modifier.height(8.dp))
            CheckStatus(check)
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            singleLine = true,
            label = { Text("Or type a model name") },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        if (typed.isNotBlank() && typed != selected) {
            Spacer(Modifier.height(12.dp))
            SheetButton("Use this name", filled = true, modifier = Modifier.fillMaxWidth()) {
                viewModel.setModel(provider, typed)
                close()
            }
        }
    }
}

/** A small pill toggle, filled with the accent colour when on. */
@Composable
private fun FilterChipButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
    }
}
