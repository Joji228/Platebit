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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
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
import androidx.compose.runtime.mutableStateOf
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
import app.morsel.ui.components.Pill
import app.morsel.ui.components.RoundIconButton

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
    CoachContent(
        messages = messages,
        thinking = thinking,
        error = error,
        loaded = loaded,
        onSend = viewModel::send,
        onRetry = viewModel::retry,
        onNewChat = viewModel::newChat,
        onBack = onBack,
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
            RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack)
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
                        if (message.fromUser) UserBubble(message.text) else CoachText(message.text)
                    }
                    if (thinking) {
                        item { Thinking() }
                    } else if (error != null) {
                        item { ErrorRow(error, onRetry) }
                    }
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

/** The coach writes plain text on the page, no bubble; long-press selects it for copying. */
@Composable
private fun CoachText(text: String) {
    val strong = MaterialTheme.colorScheme.onSurface
    SelectionContainer {
        Text(
            coachMarkup(text, strong),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 24.dp),
        )
    }
}

@Composable
private fun Thinking() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        Text("Thinking…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ErrorRow(message: String, onRetry: () -> Unit) {
    Column {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("Retry") }
    }
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
    val parts = line.split("**")
    // An odd count means every ** has a partner; otherwise show the text as it is.
    if (parts.size % 2 == 0) {
        append(line)
        return
    }
    parts.forEachIndexed { i, part ->
        if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = strong)) { append(part) } else append(part)
    }
}
