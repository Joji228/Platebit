package app.morsel.ui.coach

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.morsel.data.CoachMessage
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.rounded.Close
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import app.morsel.ui.theme.Numeric
import app.morsel.ui.theme.Nutrient
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.MonitorWeight
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.WaterDrop
import app.morsel.data.ActionStatus
import app.morsel.data.ActionType
import app.morsel.data.CoachAction
import app.morsel.data.CoachActions
import java.time.LocalDate
import app.morsel.ui.components.Pill
import app.morsel.ui.components.RoundIconButton

private const val TYPE_CHARS_PER_SECOND = 140L

/** Questions offered on an empty chat; tapping one asks it. */
val CoachSuggestions = listOf(
    "How am I doing this week?",
    "Am I eating enough protein?",
    "Explain my last weigh-in",
)

@Composable
fun CoachScreen(viewModel: CoachViewModel, onBack: () -> Unit) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val thinking by viewModel.thinking.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val loaded by viewModel.loaded.collectAsStateWithLifecycle()
    val typing by viewModel.typing.collectAsStateWithLifecycle()
    val activity by viewModel.activity.collectAsStateWithLifecycle()
    CoachContent(
        messages = messages,
        thinking = thinking,
        error = error,
        loaded = loaded,
        onSend = viewModel::send,
        onRetry = viewModel::retry,
        onNewChat = viewModel::newChat,
        onBack = onBack,
        typingAt = typing,
        activity = activity,
        onTyped = viewModel::typed,
        onAccept = viewModel::accept,
        onDecline = viewModel::decline,
    )
}

/** The whole chat, driven only by its arguments (so it can be tested without an AI engine). */
@Composable
fun CoachContent(
    messages: List<CoachMessage>,
    thinking: Boolean,
    error: String?,
    loaded: Boolean,
    onSend: (String) -> Unit,
    onRetry: () -> Unit,
    onNewChat: () -> Unit,
    onBack: () -> Unit,
    /** The answer (by its time) to type out letter by letter; older ones show in full. */
    typingAt: Long? = null,
    /** Shown instead of "Thinking…" while the coach does something else, like a product lookup. */
    activity: String? = null,
    onTyped: () -> Unit = {},
    /** Accept or Decline on a proposed change: the message it belongs to (by time) and its position. */
    onAccept: (Long, Int) -> Unit = { _, _ -> },
    onDecline: (Long, Int) -> Unit = { _, _ -> },
) {
    var input by rememberSaveable { mutableStateOf("") }
    var confirmNew by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val rows = messages.size + (if (thinking || error != null) 1 else 0)
    // Each new message scrolls to its start, so a long answer is read from the top.
    LaunchedEffect(rows) {
        if (rows > 0) listState.animateScrollToItem(rows - 1)
    }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .imePadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The chat slides down from the top, so it closes upwards.
            RoundIconButton(Icons.Rounded.KeyboardArrowUp, "Close", onBack)
            Spacer(Modifier.width(14.dp))
            Text("Coach", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(10.dp))
            Pill("Beta", MaterialTheme.colorScheme.primary)
            Spacer(Modifier.weight(1f))
            if (messages.isNotEmpty()) {
                RoundIconButton(Icons.Rounded.AddComment, "New chat", { confirmNew = true })
            }
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (loaded && messages.isEmpty() && !thinking && error == null) {
                EmptyCoach(onPick = onSend)
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(messages) { message ->
                        if (message.fromUser) {
                            UserBubble(message.text)
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                CoachText(message.text, typing = message.at == typingAt, onTyped = onTyped)
                                message.actions.filter { it.status != ActionStatus.PENDING }.forEach { ActionOutcome(it) }
                            }
                        }
                    }
                    if (thinking) {
                        item { Thinking(activity ?: "Thinking…") }
                    } else if (error != null) {
                        item { ErrorRow(error, onRetry) }
                    }
                }
            }
        }

        // The newest answer's proposals wait above the text box until each is accepted or declined.
        // They appear once the answer has finished typing out.
        val last = messages.lastOrNull()?.takeIf { !it.fromUser && it.at != typingAt }
        val pending = last?.actions?.withIndex()?.filter { it.value.status == ActionStatus.PENDING }.orEmpty()
        AnimatedVisibility(
            visible = pending.isNotEmpty() && !thinking,
            enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut(),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                pending.forEach { (index, action) ->
                    ActionCard(
                        action,
                        onAccept = { last?.let { onAccept(it.at, index) } },
                        onDecline = { last?.let { onDecline(it.at, index) } },
                    )
                }
            }
        }

        InputBar(
            value = input,
            onChange = { input = it },
            canSend = input.isNotBlank() && !thinking && loaded,
            onSend = {
                onSend(input)
                input = ""
            },
        )
    }

    if (confirmNew) {
        AlertDialog(
            onDismissRequest = { confirmNew = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Start a new chat?") },
            text = { Text("This conversation will be deleted. Your data in the app stays as it is.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmNew = false
                    onNewChat()
                }) { Text("New chat") }
            },
            dismissButton = { TextButton(onClick = { confirmNew = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EmptyCoach(onPick: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(56.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(16.dp))
        Text("Ask about your progress", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Your coach sees your meals, water, weigh-ins and body composition.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CoachSuggestions.forEach { question ->
                Surface(
                    onClick = { onPick(question) },
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(
                        question,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
            modifier = Modifier
                .padding(start = 48.dp)
                .widthIn(max = 320.dp),
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

/**
 * The coach writes plain text on the page, no bubble; long-press selects it for copying. A new
 * answer types itself out, quickly enough that even a long one takes under two seconds.
 */
@Composable
private fun CoachText(text: String, typing: Boolean = false, onTyped: () -> Unit = {}) {
    val strong = MaterialTheme.colorScheme.onSurface
    val full = remember(text, strong) { coachMarkup(text, strong) }
    var shown by remember(text) { mutableIntStateOf(if (typing) 0 else full.length) }
    if (typing) {
        LaunchedEffect(text) {
            val duration = (full.length * 1000L / TYPE_CHARS_PER_SECOND).coerceIn(400L, 1_800L)
            val start = withFrameMillis { it }
            while (shown < full.length) {
                val elapsed = withFrameMillis { it } - start
                shown = (full.length * elapsed / duration).toInt().coerceAtMost(full.length)
            }
            onTyped()
        }
    }
    SelectionContainer {
        Text(
            if (shown >= full.length) full else full.subSequence(0, shown),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 24.dp),
        )
    }
}

@Composable
private fun Thinking(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ErrorRow(message: String, onRetry: () -> Unit) {
    Column {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("Retry") }
    }
}

/** A change the coach offers to make, waiting for Accept or Decline. */
@Composable
private fun ActionCard(action: CoachAction, onAccept: () -> Unit, onDecline: () -> Unit) {
    val today = remember { LocalDate.now() }
    val deleting = action.type == ActionType.DELETE_MEAL
    // The accent colour, except for deleting, which is red so it can't be mistaken for logging.
    val tint = if (deleting) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val day = remember(action) {
        action.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.takeIf { it != today }?.let {
            if (it == today.minusDays(1)) "Yesterday" else it.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
        }
    }
    val view = LocalView.current
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, tint.copy(alpha = 0.35f)),
        shadowElevation = 6.dp,
    ) {
        Column(
            Modifier
                .background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.12f), Color.Transparent)))
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(42.dp)
                        .background(tint.copy(alpha = 0.18f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(action.type.icon, null, Modifier.size(22.dp), tint = tint)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "COACH SUGGESTS",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold),
                        color = tint,
                    )
                    Text(CoachActions.title(action), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                day?.let {
                    Spacer(Modifier.width(8.dp))
                    Pill(it, MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            when (action.type) {
                ActionType.LOG_FOOD -> {
                    val m = action.macros
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(m.kcal.roundToInt().toString(), style = MaterialTheme.typography.headlineSmall.merge(Numeric))
                        Text(" kcal", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        MacroChip("P", m.protein, Nutrient.Protein)
                        Spacer(Modifier.width(6.dp))
                        MacroChip("C", m.carbs, Nutrient.Carbs)
                        Spacer(Modifier.width(6.dp))
                        MacroChip("F", m.fat, Nutrient.Fat)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        action.foods.joinToString(" · ") { "${it.name} ${it.grams.roundToInt()} g" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Label values checked against the product database, not estimated.
                    if (action.foods.any { it.offCode != null }) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Verified, null, Modifier.size(14.dp), tint = Nutrient.Water)
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (action.foods.all { it.offCode != null }) "Label values from Open Food Facts"
                                else "Branded items use Open Food Facts label values",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                ActionType.DELETE_MEAL -> {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        listOfNotNull(day ?: "Today", action.kcal?.let { "$it kcal" }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> Unit
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CardButton(
                    "Decline", Icons.Rounded.Close,
                    container = MaterialTheme.colorScheme.surfaceContainerHighest,
                    content = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    onClick = onDecline,
                )
                CardButton(
                    if (deleting) "Delete" else "Accept", Icons.Rounded.Check,
                    container = tint,
                    content = if (deleting) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        view.coachPopTap()
                        onAccept()
                    },
                )
            }
        }
    }
}

/** P / C / F amount on a meal card, with the same colours as everywhere else in the app. */
@Composable
private fun MacroChip(letter: String, grams: Double, color: Color) {
    Row(
        Modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(letter, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(4.dp))
        Text("${grams.roundToInt()} g", style = MaterialTheme.typography.labelMedium.merge(Numeric))
    }
}

/** A big, easy-to-hit button for the cards: Decline on the left, Accept on the right. */
@Composable
private fun CardButton(label: String, icon: ImageVector, container: Color, content: Color, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = container,
        contentColor = content,
        modifier = modifier.height(50.dp),
    ) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold))
        }
    }
}

/** Under the coach's message once a proposal is answered: what was done, or that it was declined. */
@Composable
private fun ActionOutcome(action: CoachAction) {
    val (icon, color, text) = when (action.status) {
        ActionStatus.ACCEPTED -> Triple(Icons.Rounded.CheckCircle, MaterialTheme.colorScheme.primary, action.result ?: CoachActions.title(action))
        ActionStatus.FAILED -> Triple(Icons.Rounded.ErrorOutline, MaterialTheme.colorScheme.error, "Couldn't do it: ${action.result}")
        else -> Triple(Icons.Rounded.Block, MaterialTheme.colorScheme.onSurfaceVariant, "Declined: ${CoachActions.title(action)}")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(16.dp), tint = color)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val ActionType.icon
    get() = when (this) {
        ActionType.LOG_FOOD -> Icons.Rounded.Restaurant
        ActionType.DELETE_MEAL -> Icons.Rounded.DeleteOutline
        ActionType.ADD_WATER, ActionType.SET_WATER_GOAL -> Icons.Rounded.WaterDrop
        ActionType.LOG_WEIGHT -> Icons.Rounded.MonitorWeight
        ActionType.SET_CALORIES -> Icons.Rounded.LocalFireDepartment
        ActionType.SET_GOAL_WEIGHT -> Icons.Rounded.Flag
    }

@Composable
private fun InputBar(value: String, onChange: (String) -> Unit, canSend: Boolean, onSend: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        TextField(
            value,
            onChange,
            placeholder = { Text("Ask your coach") },
            maxLines = 5,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            shape = RoundedCornerShape(24.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        RoundIconButton(
            Icons.Rounded.ArrowUpward,
            "Send",
            { if (canSend) onSend() },
            modifier = Modifier.padding(bottom = 4.dp),
            size = 48.dp,
            container = if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
            content = if (canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The little formatting models use in chat: **bold**, bullet lines ("- " or "* ") and "#" headings
 * (shown as bold lines). Anything else is left as written.
 */
internal fun coachMarkup(raw: String, strong: Color): AnnotatedString = buildAnnotatedString {
    val lines = raw.trim().lines()
    lines.forEachIndexed { index, original ->
        var line = original.trimEnd()
        val heading = line.trimStart().startsWith("#")
        line = when {
            heading -> line.trimStart().trimStart('#').trim()
            Regex("^\\s*[-*•]\\s+").containsMatchIn(line) -> line.replace(Regex("^(\\s*)[-*•]\\s+"), "$1•  ")
            else -> line
        }
        if (heading) {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = strong)) { appendBold(line.replace("**", ""), strong) }
        } else {
            appendBold(line, strong)
        }
        if (index < lines.lastIndex) append('\n')
    }
}

private fun AnnotatedString.Builder.appendBold(line: String, strong: Color) {
    // Models sometimes bold with __text__ instead of **text**.
    val text = line.replace(Regex("""__(\S(?:.*?\S)?)__"""), "**$1**")
    val parts = text.split("**")
    // An odd count means every ** has a partner; otherwise treat the asterisks as plain text.
    if (parts.size % 2 == 0) {
        appendNumbers(text, strong)
        return
    }
    parts.forEachIndexed { i, part ->
        if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = strong)) { append(part) } else appendNumbers(part, strong)
    }
}

/**
 * Numbers with a unit (121.5 kg, 1,800 kcal, 140 g, 2 L, 25 %) are bold even when the model forgot
 * to mark them, so the key figures always stand out.
 */
private fun AnnotatedString.Builder.appendNumbers(text: String, strong: Color) {
    var last = 0
    KEY_NUMBER.findAll(text).forEach { match ->
        append(text.substring(last, match.range.first))
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = strong)) { append(match.value) }
        last = match.range.last + 1
    }
    append(text.substring(last))
}

private val KEY_NUMBER = Regex(
    """(?<![\w.,])[-+−~]?\d+(?:[.,]\d+)*\s?(?:%|(?:kcal|cal|kg|lbs?|ml|mg|g|l)(?![\p{L}\d]))""",
    RegexOption.IGNORE_CASE,
)
