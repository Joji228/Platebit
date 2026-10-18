package app.morsel.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.morsel.ui.theme.Numeric
import java.util.Locale
import kotlin.math.roundToInt

val CardShape = RoundedCornerShape(22.dp)

fun Double.format(decimals: Int = 0): String =
    if (decimals == 0) String.format(Locale.getDefault(), "%,d", roundToInt())
    else String.format(Locale.getDefault(), "%,.${decimals}f", this)

fun Int.format(): String = String.format(Locale.getDefault(), "%,d", this)

@Composable
fun MorselCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = CardShape,
    ) {
        Column(Modifier.padding(padding), content = content)
    }
}

@Composable
fun ProgressLine(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 6.dp,
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = "progress",
    )
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height),
    ) {
        val radius = CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(track, cornerRadius = radius)
        if (animated > 0f) {
            drawRoundRect(
                color,
                size = Size((size.width * animated).coerceAtLeast(size.height), size.height),
                cornerRadius = radius,
            )
        }
    }
}

@Composable
fun MacroStat(label: String, value: Double, goal: Int, color: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        ProgressLine(if (goal > 0) (value / goal).toFloat() else 0f, color, height = 5.dp)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value.format(), style = MaterialTheme.typography.titleSmall.merge(Numeric))
            Text(
                " / ${goal}g",
                style = MaterialTheme.typography.labelMedium.merge(Numeric),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.labelLarge.merge(Numeric), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val haptics = LocalHapticFeedback.current
    Surface(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        },
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(18.dp),
        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: Color = MaterialTheme.colorScheme.onSurface,
) {
    val haptics = LocalHapticFeedback.current
    Surface(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        },
        modifier = modifier.size(size),
        shape = CircleShape,
        color = container,
        contentColor = content,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription, Modifier.size(size * 0.5f))
        }
    }
}

/** A row of rounded pills; the selected one is filled with the accent color. */
@Composable
fun <T> PillSelector(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            val isSelected = option == selected
            Surface(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSelect(option)
                },
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp),
                shape = RoundedCornerShape(14.dp),
                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    val style = MaterialTheme.typography.labelLarge
                    // Four pills on a 360 dp screen leave little room for words like "Breakfast".
                    Text(label(option), style = if (options.size >= 4) style.copy(fontSize = 13.sp) else style, maxLines = 1)
                }
            }
        }
    }
}

/** Big number with −/+ buttons and a slider for coarse moves. */
@Composable
fun NumberStepper(
    label: String,
    value: Double,
    onValueChange: (Double) -> Unit,
    range: ClosedFloatingPointRange<Double>,
    step: Double,
    unit: String,
    modifier: Modifier = Modifier,
    decimals: Int = 0,
) {
    fun snap(v: Double) = ((v / step).roundToInt() * step).coerceIn(range.start, range.endInclusive)
    var editing by remember { mutableStateOf(false) }
    MorselCard(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            RepeatIconButton(Icons.Rounded.Remove, "Decrease") { onValueChange(snap(value - step)) }
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.Bottom,
            ) {
                val numberStyle = MaterialTheme.typography.displaySmall.merge(Numeric)
                if (editing) {
                    NumberInput(
                        initial = value,
                        decimals = decimals,
                        style = numberStyle,
                        // Live updates, so tapping Save while the keyboard is still up keeps what was typed.
                        onTyped = { onValueChange(it.coerceIn(range.start, range.endInclusive)) },
                        onFinish = {
                            onValueChange(snap(value))
                            editing = false
                        },
                    )
                } else {
                    Text(
                        value.format(decimals),
                        style = numberStyle,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { editing = true }
                            .padding(horizontal = 6.dp),
                    )
                }
                Text(
                    " $unit",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 5.dp),
                )
            }
            RepeatIconButton(Icons.Rounded.Add, "Increase") { onValueChange(snap(value + step)) }
        }
        Spacer(Modifier.height(8.dp))
        ThinSlider(
            value = value.toFloat(),
            onValueChange = { onValueChange(snap(it.toDouble())) },
            valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
        )
    }
}

/** Inline keyboard entry for [NumberStepper]. */
@Composable
private fun NumberInput(initial: Double, decimals: Int, style: TextStyle, onTyped: (Double) -> Unit, onFinish: () -> Unit) {
    val start = if (decimals == 0) initial.roundToInt().toString() else String.format(Locale.US, "%.${decimals}f", initial)
    var text by remember { mutableStateOf(TextFieldValue(start, selection = TextRange(0, start.length))) }
    val focus = remember { FocusRequester() }
    var hadFocus by remember { mutableStateOf(false) }
    val finish by rememberUpdatedState(onFinish)
    fun commit() = finish()

    BasicTextField(
        value = text,
        onValueChange = { new ->
            if (new.text.length <= 7) {
                text = new
                new.text.replace(',', '.').trim().toDoubleOrNull()?.let(onTyped)
            }
        },
        textStyle = style.copy(color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimals == 0) KeyboardType.Number else KeyboardType.Decimal,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier = Modifier
            .width(IntrinsicSize.Min)
            .widthIn(min = 48.dp)
            .focusRequester(focus)
            .onFocusChanged { state ->
                if (state.isFocused) hadFocus = true else if (hadFocus) commit()
            },
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** Round button that steps once on tap and keeps stepping, faster and faster, while held. */
@Composable
fun RepeatIconButton(
    icon: ImageVector,
    contentDescription: String,
    size: Dp = 40.dp,
    onStep: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val step by rememberUpdatedState(onStep)
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(
                if (pressed) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerHigh,
            )
            .semantics(mergeDescendants = true) {
                this.contentDescription = contentDescription
                role = Role.Button
                onClick { step(); true }
            }
            .pointerInput(Unit) {
                coroutineScope {
                    awaitEachGesture {
                        awaitFirstDown()
                        pressed = true
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        step()
                        val repeater = launch {
                            delay(400)
                            var interval = 110L
                            while (isActive) {
                                step()
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                delay(interval)
                                interval = (interval * 0.88).toLong().coerceAtLeast(35L)
                            }
                        }
                        waitForUpOrCancellation()
                        repeater.cancel()
                        pressed = false
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(size * 0.5f), tint = MaterialTheme.colorScheme.onSurface)
    }
}

/** iOS-style slider: thin rounded track and a white round knob. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThinSlider(value: Float, onValueChange: (Float) -> Unit, valueRange: ClosedFloatingPointRange<Float>) {
    val colors = SliderDefaults.colors(
        thumbColor = Color.White,
        activeTrackColor = MaterialTheme.colorScheme.primary,
        inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    )
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        colors = colors,
        thumb = {
            Box(
                Modifier
                    .size(24.dp)
                    .shadow(3.dp, CircleShape)
                    .background(Color.White, CircleShape),
            )
        },
        track = { state ->
            SliderDefaults.Track(
                sliderState = state,
                modifier = Modifier.height(6.dp),
                colors = colors,
                drawStopIndicator = null,
                thumbTrackGapSize = 0.dp,
            )
        },
    )
}

/** Grouped settings-style list: rounded container, hairline dividers between rows. */
@Composable
fun GroupedList(modifier: Modifier = Modifier, rows: List<@Composable () -> Unit>) {
    Surface(modifier.fillMaxWidth(), shape = CardShape, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            rows.forEachIndexed { index, row ->
                row()
                if (index < rows.lastIndex) {
                    HorizontalDivider(Modifier.padding(start = 18.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (value != null) {
            Spacer(Modifier.width(12.dp))
            Text(value, style = MaterialTheme.typography.bodyMedium.merge(Numeric), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Weight over time with a dashed goal line and a soft fill under the curve. */
@Composable
fun WeightChart(
    points: List<Pair<Long, Double>>,
    goal: Double,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val goalColor = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier) {
        if (points.isEmpty()) return@Canvas
        val values = points.map { it.second } + goal
        val min = values.min() - 1.0
        val max = values.max() + 1.0
        val t0 = points.first().first
        val t1 = points.last().first
        val span = (t1 - t0).coerceAtLeast(1L).toFloat()
        fun x(t: Long) = if (points.size == 1) size.width / 2 else (t - t0) / span * size.width
        fun y(v: Double) = (size.height * (1 - (v - min) / (max - min))).toFloat()

        drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
        drawLine(
            goalColor.copy(alpha = 0.6f),
            Offset(0f, y(goal)),
            Offset(size.width, y(goal)),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())),
        )

        val line = Path()
        points.forEachIndexed { index, (t, v) ->
            if (index == 0) line.moveTo(x(t), y(v)) else line.lineTo(x(t), y(v))
        }
        if (points.size > 1) {
            val fill = Path().apply {
                addPath(line)
                lineTo(x(t1), size.height)
                lineTo(x(t0), size.height)
                close()
            }
            drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0f))))
            drawPath(line, color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        val last = points.last()
        drawCircle(color, radius = 4.5.dp.toPx(), center = Offset(x(last.first), y(last.second)))
    }
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        color = color,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold).merge(Numeric),
    )
}
