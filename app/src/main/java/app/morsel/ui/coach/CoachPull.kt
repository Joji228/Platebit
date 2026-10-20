package app.morsel.ui.coach

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pull down and hold to call the coach, like pull-to-refresh: drag down while the page is already
 * at its top (or has nothing to scroll), keep holding past the line for a moment, and the bubble
 * pops. Letting go early springs it back and nothing happens.
 */
@Stable
class CoachPull internal constructor(
    private val scope: CoroutineScope,
    private val threshold: Float,
    private val max: Float,
) {
    /** How far the bubble has come down, in pixels. */
    var pull by mutableFloatStateOf(0f)
        private set

    /** Fills from 0 to 1 while held past the line; at 1 the bubble pops. */
    val hold = Animatable(0f)

    /** Runs 0 to 1 once the bubble pops (the burst), then everything resets. */
    val burst = Animatable(0f)

    var popped by mutableStateOf(false)
        private set

    internal var enabled = false
    internal var onArm: () -> Unit = {}
    internal var onPop: () -> Unit = {}

    val progress get() = (pull / threshold).coerceIn(0f, 1f)
    private val armed get() = pull >= threshold

    private var holdJob: Job? = null
    private var settleJob: Job? = null

    /** A finger movement; positive is downwards. */
    internal fun drag(dy: Float) {
        if (!enabled || popped) return
        settleJob?.cancel()
        val wasArmed = armed
        pull = (pull + dy * RESISTANCE).coerceIn(0f, max)
        if (armed && !wasArmed) {
            onArm()
            holdJob = scope.launch {
                hold.snapTo(0f)
                hold.animateTo(1f, tween(HOLD_MS, easing = LinearEasing))
                pop()
            }
        } else if (!armed && wasArmed) {
            holdJob?.cancel()
            scope.launch { hold.snapTo(0f) }
        }
    }

    internal fun release() {
        if (popped) return
        holdJob?.cancel()
        scope.launch { hold.snapTo(0f) }
        if (pull == 0f) return
        settleJob = scope.launch {
            animate(pull, 0f, animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)) { v, _ -> pull = v }
        }
    }

    private suspend fun pop() {
        popped = true
        onPop()
        burst.snapTo(0f)
        burst.animateTo(1f, tween(BURST_MS, easing = FastOutSlowInEasing))
        pull = 0f
        hold.snapTo(0f)
        burst.snapTo(0f)
        popped = false
    }

    /** The page is at its top and the finger keeps pulling down: that leftover becomes the pull. */
    internal val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            // Pushing back up while the bubble is out takes it back in before the page scrolls.
            if (pull > 0f && available.y < 0f && source == NestedScrollSource.UserInput) {
                val before = pull
                drag(available.y)
                return Offset(0f, (pull - before) / RESISTANCE)
            }
            return Offset.Zero
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (!enabled || source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
            drag(available.y)
            return Offset(0f, available.y)
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            val pulling = pull > 0f
            release()
            return if (pulling) available else Velocity.Zero
        }
    }

    internal companion object {
        const val RESISTANCE = 0.55f
        const val HOLD_MS = 380
        const val BURST_MS = 320
    }
}

@Composable
fun rememberCoachPull(): CoachPull {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    return remember(density) { with(density) { CoachPull(scope, threshold = 84.dp.toPx(), max = 130.dp.toPx()) } }
}

/**
 * Listens for the pull on everything inside. Scrolling pages hand over what they can't scroll
 * (nested scroll); pages with nothing to scroll, like the camera, are read straight from the finger.
 * A gesture any page uses itself (a sideways swipe, a scroll, a pinch) is left alone.
 */
fun Modifier.coachPull(state: CoachPull, enabled: Boolean, onArm: () -> Unit, onPop: () -> Unit): Modifier {
    state.enabled = enabled
    state.onArm = onArm
    state.onPop = onPop
    if (!enabled) return this
    return nestedScroll(state.connection).pointerInput(state) {
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
            var total = Offset.Zero
            var active = false
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed || event.changes.count { it.pressed } > 1) break
                    val delta = change.positionChangeIgnoreConsumed()
                    if (!active) {
                        // Something on the page took this gesture (a scroll, a swipe): not ours.
                        if (change.isConsumed) break
                        total += delta
                        when {
                            total.y > slop && total.y > abs(total.x) * 1.5f -> {
                                active = true
                                state.drag(total.y - slop)
                                change.consume()
                            }
                            abs(total.x) > slop || total.y < -slop -> break
                        }
                    } else {
                        state.drag(delta.y)
                        change.consume()
                    }
                }
            } finally {
                if (active) state.release()
            }
        }
    }
}

/** The light tick when the pull crosses the line. */
fun View.coachArmTick() {
    performHapticFeedback(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.CLOCK_TICK)
}

/** The firmer tap when the bubble pops. */
fun View.coachPopTap() {
    performHapticFeedback(HapticFeedbackConstants.CONFIRM)
}

/**
 * The bubble that follows the finger down from the top: it grows as it comes, a ring fills while
 * it's held past the line, then it bursts into a few dots.
 */
@Composable
fun CoachPullBubble(state: CoachPull, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val top = WindowInsets.statusBars.getTop(density)
    val size = 52.dp
    val sizePx = with(density) { size.toPx() }
    val burst = state.burst.value
    if (state.pull <= 0f && !state.popped) return
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .offset { IntOffset(0, (top + state.pull - sizePx * 0.4f).roundToInt()) }
            .size(size * 2.2f),
        contentAlignment = Alignment.Center,
    ) {
        if (state.popped) {
            // The burst: eight dots flying out and fading.
            Canvas(Modifier.size(size * 2.2f)) {
                val radius = sizePx * (0.45f + 0.65f * burst)
                val dot = (1f - burst) * 5.dp.toPx() + 1f
                repeat(8) { i ->
                    val angle = Math.toRadians(i * 45.0 + 22.5)
                    drawCircle(
                        primary.copy(alpha = (1f - burst).coerceIn(0f, 1f)),
                        radius = dot,
                        center = Offset(center.x + radius * cos(angle).toFloat(), center.y + radius * sin(angle).toFloat()),
                    )
                }
            }
        }
        Box(
            Modifier
                .size(size)
                .graphicsLayer {
                    val grow = if (state.popped) 1f + 0.5f * burst else 0.55f + 0.45f * state.progress
                    scaleX = grow
                    scaleY = grow
                    alpha = if (state.popped) (1f - burst * 1.6f).coerceIn(0f, 1f) else state.progress.coerceAtLeast(0.25f)
                }
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (state.hold.value > 0f && !state.popped) {
                CircularProgressIndicator(
                    progress = { state.hold.value },
                    modifier = Modifier.size(size),
                    strokeWidth = 3.dp,
                    color = primary,
                    trackColor = primary.copy(alpha = 0.18f),
                )
            }
            Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(24.dp), tint = primary)
        }
    }
}
