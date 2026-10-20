package app.morsel.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.SideEffect
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextField
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.morsel.data.FoodFact
import app.morsel.domain.MealType
import app.morsel.ui.components.PillSelector
import app.morsel.ui.components.RoundIconButton
import app.morsel.ui.components.format
import app.morsel.ui.theme.Numeric
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Logging a packaged food in a few taps, for users who turned on Open Food Facts: search as you
 * type, pick the product, adjust the grams, tick. It's saved as its own meal for today.
 */
@Composable
fun QuickAddDialog(
    search: suspend (String) -> List<FoodFact>,
    onDismiss: () -> Unit,
    onLog: (fact: FoodFact, name: String, grams: Double, type: MealType) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<FoodFact>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<FoodFact?>(null) }
    var name by remember { mutableStateOf("") }
    var grams by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(MealType.forTime()) }
    val focus = remember { FocusRequester() }
    var closing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2 || picked != null) {
            if (picked == null) results = emptyList()
            problem = null
            return@LaunchedEffect
        }
        delay(450) // typing pause; a new letter cancels this and starts over
        searching = true
        runCatching { search(q) }
            .onSuccess { results = it; problem = if (it.isEmpty()) "No products found" else null }
            .onFailure { if (it is CancellationException) throw it else problem = "Couldn't reach Open Food Facts" }
        searching = false
    }

    // Slides down from the top edge, quick and eased, and back up when closed. The system's own
    // dialog animation and dim are switched off so only this motion is seen.
    val shown = remember { MutableTransitionState(false) }.apply { targetState = !closing }
    LaunchedEffect(shown.currentState, shown.isIdle) { if (closing && shown.isIdle && !shown.currentState) onDismiss() }
    Dialog(
        onDismissRequest = { closing = true },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        (LocalView.current.parent as? DialogWindowProvider)?.window?.let { window ->
            SideEffect {
                window.setWindowAnimations(0)
                window.setDimAmount(0f)
            }
        }
        Box(Modifier.fillMaxSize()) {
            AnimatedVisibility(shown, enter = fadeIn(tween(160)), exit = fadeOut(tween(140))) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { closing = true },
                )
            }
            AnimatedVisibility(
                shown,
                enter = slideInVertically(tween(220, easing = FastOutSlowInEasing)) { -it } + fadeIn(tween(120)),
                exit = slideOutVertically(tween(180, easing = FastOutLinearInEasing)) { -it } + fadeOut(tween(140, delayMillis = 40)),
            ) {
        Surface(
            shape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth()
                .imePadding(),
        ) {
            Column(
                Modifier
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Quick add", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    RoundIconButton(Icons.Rounded.Close, "Close", { closing = true })
                }
                TextField(
                    query,
                    {
                        query = it
                        picked = null
                    },
                    placeholder = { Text("Search a food, e.g. Kit Kat") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    trailingIcon = {
                        when {
                            searching -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            query.isNotEmpty() -> IconButton(onClick = { query = ""; picked = null }) { Icon(Icons.Rounded.Close, "Clear", Modifier.size(18.dp)) }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus),
                )

                val chosen = picked
                if (chosen == null) {
                    if (results.isNotEmpty()) {
                        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                            results.forEachIndexed { index, fact ->
                                if (index > 0) HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            picked = fact
                                            name = fact.label
                                            grams = (fact.servingGrams ?: 100.0).format()
                                        }
                                        .padding(vertical = 10.dp, horizontal = 4.dp),
                                ) {
                                    Text(fact.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "${fact.kcalPer100.format()} kcal per 100 g" + (fact.servingGrams?.let { " · serving ${it.format()} g" } ?: ""),
                                        style = MaterialTheme.typography.labelSmall.merge(Numeric),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    val g = grams.replace(',', '.').toDoubleOrNull() ?: 0.0
                    val factor = g / 100.0
                    OutlinedTextField(name, { name = it }, label = { Text("Meal name") }, singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        grams,
                        { grams = it },
                        label = { Text("Grams") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${(chosen.kcalPer100 * factor).format()} kcal · P ${(chosen.proteinPer100 * factor).format()} g · " +
                            "C ${(chosen.carbsPer100 * factor).format()} g · F ${(chosen.fatPer100 * factor).format()} g",
                        style = MaterialTheme.typography.titleSmall.merge(Numeric),
                    )
                    PillSelector(MealType.entries, type, { it.title }, { type = it })
                    Row {
                        Spacer(Modifier.weight(1f))
                        Surface(
                            onClick = {
                                if (g > 0) {
                                    onLog(chosen, name.ifBlank { chosen.label }, g, type)
                                    closing = true
                                }
                            },
                            enabled = g > 0,
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ) {
                            Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Check, null, Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Log it", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
        }
            }
        }
    }
}
