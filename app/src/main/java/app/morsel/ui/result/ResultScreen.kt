package app.morsel.ui.result

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.morsel.domain.MealType
import app.morsel.ui.components.MorselCard
import app.morsel.ui.components.PillSelector
import app.morsel.ui.components.PrimaryButton
import app.morsel.ui.components.RoundIconButton
import app.morsel.ui.components.SectionTitle
import app.morsel.ui.components.format
import app.morsel.ui.theme.Numeric
import app.morsel.ui.theme.Nutrient
import coil3.compose.AsyncImage
import java.io.File
import kotlin.math.roundToInt

private val multipliers = listOf(0.5, 1.0, 1.5, 2.0)

@Composable
fun ResultScreen(viewModel: ResultViewModel, onClose: () -> Unit, onSaved: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editingGrams by remember { mutableStateOf<EditableItem?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Any?>(null) }

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
            RoundIconButton(Icons.Rounded.Close, "Close", {
                viewModel.discardDraft()
                onClose()
            })
            Spacer(Modifier.weight(1f))
            if (state.isEdit) {
                RoundIconButton(Icons.Rounded.DeleteOutline, "Delete meal", { confirmDelete = true })
            }
        }

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val photos: List<Any> = state.photos.ifEmpty { listOfNotNull(state.savedPhotoPath?.let { File(it) }) }
            if (photos.isNotEmpty()) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(photos) { photo ->
                            AsyncImage(
                                model = photo,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .height(160.dp)
                                    .then(if (photos.size == 1) Modifier.fillParentMaxWidth() else Modifier.width(200.dp))
                                    .clip(RoundedCornerShape(22.dp))
                                    .clickable { viewing = photo },
                            )
                        }
                    }
                }
            }

            item {
                Column(Modifier.padding(horizontal = 4.dp)) {
                    BasicTextField(
                        value = state.name,
                        onValueChange = viewModel::setName,
                        textStyle = MaterialTheme.typography.headlineMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { inner ->
                            if (state.name.isEmpty()) {
                                Text("Name this meal", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            inner()
                        },
                    )
                    state.source?.let { source ->
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                source + (state.confidence?.let { " · $it confidence" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    state.fallbackReason?.let { reason ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Your first choice failed, so the other AI was used.\n$reason",
                            style = MaterialTheme.typography.bodySmall,
                            color = Nutrient.Carbs,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(Nutrient.Carbs.copy(alpha = 0.12f))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            item { TotalsCard(state) }

            item {
                SectionTitle("Meal")
                Spacer(Modifier.height(8.dp))
                PillSelector(MealType.entries, state.mealType, { it.title }, viewModel::setMealType)
            }

            item {
                SectionTitle("Portion")
                Spacer(Modifier.height(8.dp))
                PillSelector(
                    multipliers,
                    state.multiplier,
                    { if (it == 0.5) "½×" else "${it.format(if (it % 1.0 == 0.0) 0 else 1)}×" },
                    viewModel::setMultiplier,
                )
            }

            item {
                Spacer(Modifier.height(4.dp))
                SectionTitle("Items", trailing = "${state.items.size}")
            }
            if (state.loaded && state.items.isEmpty()) {
                item {
                    Text(
                        state.notes ?: "No items yet. Add what you ate below.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
            items(state.items, key = { it.key }) { item ->
                ItemCard(
                    item = item,
                    multiplier = state.multiplier,
                    onRename = { viewModel.rename(item.key, it) },
                    onGrams = { viewModel.setGrams(item.key, it) },
                    onEditGrams = { editingGrams = item },
                    onRemove = { viewModel.remove(item.key) },
                )
            }
            item {
                TextButton(onClick = { adding = true }) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add item")
                }
            }
            if (state.items.isNotEmpty() && state.notes != null) {
                item {
                    Text(
                        state.notes.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }

        PrimaryButton(
            text = if (state.isEdit) "Save changes" else "Log meal",
            onClick = { viewModel.save(onSaved) },
            enabled = state.items.isNotEmpty() && !state.saving,
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(16.dp),
        )
    }

    editingGrams?.let { item ->
        GramsDialog(
            initial = item.grams * state.multiplier,
            onDismiss = { editingGrams = null },
            onSave = {
                viewModel.setGrams(item.key, it / state.multiplier)
                editingGrams = null
            },
        )
    }
    if (adding) {
        AddItemDialog(
            onDismiss = { adding = false },
            onAdd = { name, grams, kcal, p, c, f ->
                viewModel.add(name, grams, kcal, p, c, f)
                adding = false
            },
        )
    }
    viewing?.let { photo -> PhotoViewer(photo, onDismiss = { viewing = null }) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Delete this meal?") },
            text = { Text("It will be removed from your log.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete(onSaved)
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** Full-screen photo on black; tap anywhere or swipe back to close. */
@Composable
fun PhotoViewer(photo: Any, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = photo,
                contentDescription = "Meal photo",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth(),
            )
            RoundIconButton(
                Icons.Rounded.Close,
                "Close",
                onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(16.dp),
            )
        }
    }
}

@Composable
private fun TotalsCard(state: ResultState) {
    val total = state.total
    MorselCard {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(total.kcal.format(), style = MaterialTheme.typography.displaySmall.merge(Numeric))
            Text(
                " kcal",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 5.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MacroChip("Protein", total.protein, Nutrient.Protein, Modifier.weight(1f))
            MacroChip("Carbs", total.carbs, Nutrient.Carbs, Modifier.weight(1f))
            MacroChip("Fat", total.fat, Nutrient.Fat, Modifier.weight(1f))
        }
    }
}

@Composable
private fun MacroChip(label: String, grams: Double, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .padding(vertical = 2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
        Text("${grams.format()} g", style = MaterialTheme.typography.titleMedium.merge(Numeric))
    }
}

@Composable
private fun ItemCard(
    item: EditableItem,
    multiplier: Double,
    onRename: (String) -> Unit,
    onGrams: (Double) -> Unit,
    onEditGrams: () -> Unit,
    onRemove: () -> Unit,
) {
    val macros = item.macros(multiplier)
    val step = if (item.grams < 50) 5.0 else 10.0
    MorselCard(padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = item.name,
                onValueChange = onRename,
                textStyle = MaterialTheme.typography.titleSmall.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${macros.kcal.format()} kcal",
                style = MaterialTheme.typography.titleSmall.merge(Numeric),
            )
            IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Rounded.Close, "Remove item", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(Icons.Rounded.Remove, "Less", { onGrams((item.grams - step).coerceAtLeast(0.0)) }, size = 36.dp)
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onEditGrams)
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    (item.grams * multiplier).format() + " g",
                    style = MaterialTheme.typography.titleLarge.merge(Numeric),
                )
            }
            RoundIconButton(Icons.Rounded.Add, "More", { onGrams(item.grams + step) }, size = 36.dp)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "P ${macros.protein.format()}g · C ${macros.carbs.format()}g · F ${macros.fat.format()}g · ${item.kcalPer100.format()} kcal/100g",
            style = MaterialTheme.typography.labelMedium.merge(Numeric),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GramsDialog(initial: Double, onDismiss: () -> Unit, onSave: (Double) -> Unit) {
    var text by remember { mutableStateOf(initial.roundToInt().toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text("Weight in grams") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                suffix = { Text("g") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                shape = RoundedCornerShape(14.dp),
            )
        },
        confirmButton = {
            TextButton(onClick = { text.toNumber()?.let(onSave) ?: onDismiss() }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AddItemDialog(
    onDismiss: () -> Unit,
    onAdd: (String, Double, Double, Double, Double, Double) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var grams by remember { mutableStateOf("100") }
    var kcal by remember { mutableStateOf("") }
    var protein by remember { mutableStateOf("") }
    var carbs by remember { mutableStateOf("") }
    var fat by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text("Add item") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                NumberField("Grams eaten", grams) { grams = it }
                Text("Per 100 g", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("kcal", kcal, Modifier.weight(1f)) { kcal = it }
                    NumberField("Protein g", protein, Modifier.weight(1f)) { protein = it }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Carbs g", carbs, Modifier.weight(1f)) { carbs = it }
                    NumberField("Fat g", fat, Modifier.weight(1f)) { fat = it }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onAdd(
                        name.ifBlank { "Food" },
                        grams.toNumber() ?: 100.0,
                        kcal.toNumber() ?: 0.0,
                        protein.toNumber() ?: 0.0,
                        carbs.toNumber() ?: 0.0,
                        fat.toNumber() ?: 0.0,
                    )
                },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun NumberField(label: String, value: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, maxLines = 1) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier,
    )
}

private fun String.toNumber(): Double? = replace(',', '.').filter { it.isDigit() || it == '.' }.toDoubleOrNull()
