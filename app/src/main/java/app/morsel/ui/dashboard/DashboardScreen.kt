package app.morsel.ui.dashboard

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import app.morsel.ui.components.repeatOnHold
import app.morsel.ui.components.RepeatIconButton
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.foundation.layout.heightIn
import app.morsel.data.db.WeightEntity
import java.time.Instant
import java.time.ZoneId
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.morsel.data.db.MealWithItems
import app.morsel.data.db.macros
import app.morsel.domain.MealType
import app.morsel.ui.components.MacroStat
import app.morsel.ui.components.MorselCard
import app.morsel.ui.components.NumberStepper
import app.morsel.ui.components.Pill
import app.morsel.ui.components.PrimaryButton
import app.morsel.ui.components.ProgressLine
import app.morsel.ui.components.RoundIconButton
import app.morsel.ui.components.SecondaryButton
import app.morsel.ui.components.SectionTitle
import app.morsel.ui.components.WeightChart
import app.morsel.ui.components.format
import app.morsel.ui.result.PhotoViewer
import app.morsel.ui.theme.Numeric
import app.morsel.ui.theme.Nutrient
import coil3.compose.AsyncImage
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * Sideways day switching. The page follows the finger; on release it either slides away and the
 * neighbouring day slides in from the other side, or springs back. Swiping right goes to the
 * earlier day, left to the later one, which stops at today with a rubber-band tug.
 */
@Stable
private class DaySwipe(
    private val scope: CoroutineScope,
    private val listState: LazyListState,
    private val earlier: () -> Unit,
    private val later: () -> Unit,
) {
    var offset by mutableFloatStateOf(0f)
    var width by mutableFloatStateOf(1f)
    var canGoNext = false
    private var busy = false

    fun drag(delta: Float) {
        if (busy) return
        val next = offset + delta
        // Past today there is nothing to show, so pulling further feels heavy.
        offset = if (next < 0f && !canGoNext) offset + delta * 0.3f else next
    }

    fun release(velocity: Float) {
        if (busy) return
        val far = width * 0.25f
        when {
            offset > far || velocity > FLING -> go(-1)
            (offset < -far || velocity < -FLING) && canGoNext -> go(1)
            else -> settle()
        }
    }

    /** [direction] is -1 for the earlier day and 1 for the later one. */
    fun go(direction: Int) {
        if (busy) return
        if (direction > 0 && !canGoNext) return settle()
        busy = true
        scope.launch {
            val exit = if (direction < 0) width else -width
            animate(offset, exit, animationSpec = tween(150, easing = FastOutLinearInEasing)) { v, _ -> offset = v }
            if (direction < 0) earlier() else later()
            listState.scrollToItem(0)
            animate(-exit, 0f, animationSpec = tween(230, easing = FastOutSlowInEasing)) { v, _ -> offset = v }
            busy = false
        }
    }

    private fun settle() {
        busy = true
        scope.launch {
            animate(offset, 0f, animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)) { v, _ -> offset = v }
            busy = false
        }
    }

    private companion object {
        const val FLING = 1500f
    }
}

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onScan: () -> Unit,
    onOpenMeal: (Long) -> Unit,
    onSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showWeightDialog by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val swipe = remember { DaySwipe(scope, listState, viewModel::previousDay, viewModel::nextDay) }
    swipe.canGoNext = state.date < state.today

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { swipe.width = it.width.toFloat().coerceAtLeast(1f) }
            // Swipe sideways anywhere on the page to move between days. Vertical scrolling is
            // untouched, and the system back gesture still owns the very edges of the screen.
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { swipe.drag(it) },
                onDragStopped = { swipe.release(it) },
            ),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .graphicsLayer {
                    translationX = swipe.offset
                    alpha = 1f - 0.5f * (abs(swipe.offset) / swipe.width).coerceAtMost(1f)
                },
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Header(
                    date = state.date,
                    today = state.today,
                    onPrev = { swipe.go(-1) },
                    onNext = { swipe.go(1) },
                    onSettings = onSettings,
                )
            }
            // Until the first real numbers arrive, show nothing rather than placeholder targets.
            if (state.loading) return@LazyColumn
            item { CaloriesCard(state) }
            item {
                WaterCard(
                    ml = state.waterMl,
                    goalMl = state.targets?.waterMl ?: 2500,
                    onAdd = { viewModel.addWater(500) },
                    onUndo = viewModel::undoWater,
                )
            }
            item { WeightCard(state, onLog = { showWeightDialog = true }, onHistory = { showHistory = true }) }
            item {
                Spacer(Modifier.height(4.dp))
                SectionTitle("Meals", trailing = "${state.eaten.kcal.format()} kcal")
            }
            if (state.meals.isEmpty() && !state.loading) {
                item { EmptyMeals(isToday = state.date == state.today, onScan = onScan) }
            }
            val groups = state.meals.groupBy { MealType.parse(it.meal.mealType) }
            MealType.entries.forEach { type ->
                val meals = groups[type] ?: return@forEach
                item(key = "header_$type") {
                    Text(
                        "${type.title} · ${meals.sumOf { it.macros().kcal }.format()} kcal",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                    )
                }
                items(meals, key = { it.meal.id }) { meal ->
                    MealRow(meal, onClick = { onOpenMeal(meal.meal.id) }, onDelete = { viewModel.deleteMeal(meal.meal.id) })
                }
            }
        }

        val haptics = LocalHapticFeedback.current
        Surface(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onScan()
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 20.dp)
                .size(64.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shadowElevation = 6.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.CameraAlt, "Snap a meal", Modifier.size(28.dp))
            }
        }
    }

    if (showHistory) {
        WeightHistoryDialog(
            weighIns = state.plan.weighIns,
            onDelete = viewModel::deleteWeight,
            onDismiss = { showHistory = false },
        )
    }
    if (showWeightDialog) {
        WeightDialog(
            initial = state.currentWeight ?: 80.0,
            onDismiss = { showWeightDialog = false },
            onSave = {
                viewModel.logWeight(it)
                showWeightDialog = false
            },
        )
    }
}

@Composable
private fun Header(date: LocalDate, today: LocalDate, onPrev: () -> Unit, onNext: () -> Unit, onSettings: () -> Unit) {
    val title = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault()))
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault())),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onPrev, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.ChevronLeft, "Previous day", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onNext, enabled = date < today, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Rounded.ChevronRight,
                        "Next day",
                        tint = if (date < today) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
        RoundIconButton(Icons.Rounded.Settings, "Settings", onSettings, size = 44.dp)
    }
}

@Composable
private fun CaloriesCard(state: DashboardState) {
    val goal = state.targets?.kcal ?: 2000
    val eaten = state.eaten.kcal
    val left = goal - eaten
    val over = left < 0
    MorselCard {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Calories", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.plan.estimate?.active == true && state.profile?.customKcal == null) {
                        Spacer(Modifier.width(8.dp))
                        Pill("Smart", MaterialTheme.colorScheme.primary)
                    }
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(eaten.format(), style = MaterialTheme.typography.displaySmall.merge(Numeric))
                    Text(
                        " / ${goal.format()} kcal",
                        style = MaterialTheme.typography.bodyMedium.merge(Numeric),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    abs(left).format(),
                    style = MaterialTheme.typography.titleLarge.merge(Numeric),
                    color = if (over) Nutrient.Protein else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    if (over) "over" else "left",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        ProgressLine((eaten / goal).toFloat(), if (over) Nutrient.Protein else MaterialTheme.colorScheme.primary, height = 10.dp)
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            MacroStat("Protein", state.eaten.protein, state.targets?.protein ?: 0, Nutrient.Protein, Modifier.weight(1f))
            MacroStat("Carbs", state.eaten.carbs, state.targets?.carbs ?: 0, Nutrient.Carbs, Modifier.weight(1f))
            MacroStat("Fat", state.eaten.fat, state.targets?.fat ?: 0, Nutrient.Fat, Modifier.weight(1f))
        }
    }
}

@Composable
private fun WaterCard(ml: Int, goalMl: Int, onAdd: () -> Unit, onUndo: () -> Unit) {
    MorselCard(padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Nutrient.Water.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.WaterDrop, null, tint = Nutrient.Water, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        if (ml < 1000) "$ml ml" else (ml / 1000.0).format(2).trimEnd('0').trimEnd('.', ',') + " L",
                        style = MaterialTheme.typography.titleMedium.merge(Numeric),
                    )
                    Text(
                        " / ${(goalMl / 1000.0).format(1)} L",
                        style = MaterialTheme.typography.labelMedium.merge(Numeric),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(6.dp))
                ProgressLine(ml.toFloat() / goalMl.coerceAtLeast(1), Nutrient.Water, height = 5.dp)
            }
            Spacer(Modifier.width(12.dp))
            // Same press-and-hold behavior as every other +/− in the app, just slower: one step
            // here is half a litre, so a faster repeat would overshoot in a heartbeat.
            RepeatIconButton(
                Icons.Rounded.Remove, "Undo last water", size = 36.dp,
                firstDelayMs = 500, startIntervalMs = 480, minIntervalMs = 320, onStep = onUndo,
            )
            Spacer(Modifier.width(8.dp))
            var addPressed by remember { mutableStateOf(false) }
            val addStep by rememberUpdatedState(onAdd)
            Row(
                Modifier
                    .height(36.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Nutrient.Water.copy(alpha = if (addPressed) 0.30f else 0.18f))
                    .semantics(mergeDescendants = true) {
                        contentDescription = "Add 500 ml of water"
                        role = Role.Button
                        onClick { addStep(); true }
                    }
                    .repeatOnHold(
                        firstDelayMs = 500, startIntervalMs = 480, minIntervalMs = 320,
                        onPressedChange = { addPressed = it }, onStep = onAdd,
                    )
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Add, null, Modifier.size(16.dp), tint = Nutrient.Water)
                Text("500 ml", style = MaterialTheme.typography.labelLarge, color = Nutrient.Water)
            }
        }
    }
}

@Composable
private fun WeightCard(state: DashboardState, onLog: () -> Unit, onHistory: () -> Unit) {
    val profile = state.profile ?: return
    val current = state.currentWeight ?: profile.startWeightKg
    val change = current - profile.startWeightKg
    val towardsGoal = (profile.goalWeightKg - profile.startWeightKg) * change >= 0
    val trend = state.plan.trend
    // The line starts at the starting weight, so the very first weigh-in already shows progress
    // (100 → 99.5 draws a line, not a lone dot). When that weigh-in is on the start day itself,
    // the start sits half a day earlier so the two points don't stack. Weigh-ins older than the
    // start date tell the story on their own, so no start point is added then.
    val startDate = runCatching { LocalDate.parse(profile.startDate) }.getOrNull()
    val firstDay = trend.firstOrNull()?.date
    val startX = when {
        firstDay == null -> (startDate ?: LocalDate.now()).toEpochDay().toDouble()
        startDate == null || startDate.isAfter(firstDay) -> null
        startDate == firstDay -> firstDay.toEpochDay() - 0.5
        else -> startDate.toEpochDay().toDouble()
    }
    val trendLine = buildList {
        startX?.let { add(it to profile.startWeightKg) }
        trend.forEach { add(it.date.toEpochDay().toDouble() to it.trend) }
    }
    val readings = trend.map { it.date.toEpochDay().toDouble() to it.raw }
    MorselCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Weight", style = MaterialTheme.typography.titleMedium)
            if (abs(change) >= 0.05) {
                Spacer(Modifier.width(10.dp))
                Pill(
                    (if (change > 0) "+" else "−") + abs(change).format(1) + " kg",
                    if (towardsGoal) MaterialTheme.colorScheme.primary else Nutrient.Protein,
                )
            }
            Spacer(Modifier.weight(1f))
            if (state.plan.weighIns.isNotEmpty()) {
                RoundIconButton(Icons.Rounded.History, "Weigh-in history", onHistory, size = 40.dp)
                Spacer(Modifier.width(8.dp))
            }
            // Kept in the header so the floating camera button never covers it.
            SecondaryButton("Log", onLog, icon = Icons.Rounded.Add)
        }
        Spacer(Modifier.height(14.dp))
        Box(contentAlignment = Alignment.Center) {
            WeightChart(
                trend = trendLine,
                readings = readings,
                goal = profile.goalWeightKg,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp),
            )
            if (trendLine.size < 2) {
                Text(
                    "Log your weight to see your progress",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp),
                )
            }
        }
        if (trend.isNotEmpty() && trendLine.size >= 2) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Line is your smoothed trend · dots are weigh-ins",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            WeightStat("Start", profile.startWeightKg, Modifier.weight(1f))
            WeightStat("Trend", current, Modifier.weight(1f), highlight = true)
            WeightStat("Goal", profile.goalWeightKg, Modifier.weight(1f))
        }
    }
}

@Composable
private fun WeightHistoryDialog(weighIns: List<WeightEntity>, onDelete: (Long) -> Unit, onDismiss: () -> Unit) {
    val formatter = remember { DateTimeFormatter.ofPattern("EEE, MMM d · HH:mm", Locale.getDefault()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text("Weigh-ins") },
        text = {
            Column {
                Text(
                    "Delete any reading that's wrong. Your trend and calorie target update right away.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(weighIns.asReversed(), key = { it.id }) { entry ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("${entry.kg.format(1)} kg", style = MaterialTheme.typography.titleSmall.merge(Numeric))
                                Text(
                                    Instant.ofEpochMilli(entry.timestamp).atZone(ZoneId.systemDefault()).format(formatter),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { onDelete(entry.id) }) {
                                Icon(Icons.Rounded.DeleteOutline, "Delete reading", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun WeightStat(label: String, kg: Double, modifier: Modifier, highlight: Boolean = false) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            kg.format(1),
            style = MaterialTheme.typography.titleMedium.merge(Numeric),
            color = if (highlight) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A logged meal. Tap to edit it; press and hold for a small menu with Delete. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MealRow(meal: MealWithItems, onClick: () -> Unit, onDelete: () -> Unit) {
    val macros = meal.macros()
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        MorselCard(onClick = onClick, onLongClick = { menuOpen = true }, padding = 12.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    val photo = meal.meal.photoPath
                    if (photo != null) {
                        var viewing by remember { mutableStateOf(false) }
                        val haptics = LocalHapticFeedback.current
                        AsyncImage(
                            model = File(photo),
                            contentDescription = "Meal photo",
                            contentScale = ContentScale.Crop,
                            // Holding the thumbnail opens the same menu as holding the rest of the row.
                            modifier = Modifier
                                .fillMaxSize()
                                .combinedClickable(
                                    onClick = { viewing = true },
                                    onLongClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        menuOpen = true
                                    },
                                ),
                        )
                        if (viewing) PhotoViewer(File(photo), onDismiss = { viewing = false })
                    } else {
                        Icon(Icons.Rounded.Restaurant, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        meal.meal.name.ifBlank { "Meal" },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MacroTag("P", macros.protein, Nutrient.Protein)
                        MacroTag("C", macros.carbs, Nutrient.Carbs)
                        MacroTag("F", macros.fat, Nutrient.Fat)
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(macros.kcal.format(), style = MaterialTheme.typography.titleMedium.merge(Numeric))
                    Text("kcal", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        // The menu opens from the card's lower right corner, under the finger's usual resting place.
        Box(Modifier.align(Alignment.BottomEnd).padding(end = 12.dp)) {
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                shape = RoundedCornerShape(16.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                DropdownMenuItem(
                    text = { Text("Delete meal", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
}

@Composable
private fun MacroTag(letter: String, grams: Double, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            "$letter ${grams.format()}g",
            style = MaterialTheme.typography.labelMedium.merge(Numeric),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyMeals(isToday: Boolean, onScan: () -> Unit) {
    MorselCard {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Restaurant, null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                if (isToday) "Log your first meal" else "Nothing logged this day",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Snap a photo of your plate and Platebit estimates the rest.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (isToday) {
                Spacer(Modifier.height(16.dp))
                PrimaryButton("Open camera", onScan)
            }
        }
    }
}

@Composable
private fun WeightDialog(initial: Double, onDismiss: () -> Unit, onSave: (Double) -> Unit) {
    var kg by remember { mutableDoubleStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text("Log weight") },
        text = {
            NumberStepper(
                label = "Today",
                value = kg,
                onValueChange = { kg = it },
                range = 30.0..250.0,
                step = 0.1,
                unit = "kg",
                decimals = 1,
            )
        },
        confirmButton = { TextButton(onClick = { onSave(kg) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
