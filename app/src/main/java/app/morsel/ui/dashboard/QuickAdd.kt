package app.morsel.ui.dashboard

import androidx.compose.foundation.clickable
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

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .imePadding(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Quick add", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    RoundIconButton(Icons.Rounded.Close, "Close", onDismiss)
                }
                OutlinedTextField(
                    query,
                    {
                        query = it
                        picked = null
                    },
                    placeholder = { Text("Search a food, e.g. Kit Kat") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = { if (searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus),
                )

                val chosen = picked
                if (chosen == null) {
                    if (results.isNotEmpty()) {
                        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                            results.forEach { fact ->
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            picked = fact
                                            name = fact.label
                                            grams = (fact.servingGrams ?: 100.0).format()
                                        }
                                        .padding(vertical = 8.dp, horizontal = 4.dp),
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
                            onClick = { if (g > 0) onLog(chosen, name.ifBlank { chosen.label }, g, type) },
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
