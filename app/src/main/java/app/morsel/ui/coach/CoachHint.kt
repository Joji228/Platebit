package app.morsel.ui.coach

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Shown once after the coach is turned on, until the user pulls it down for the first time: a
 * finger drags a ghost bubble down, holds while its ring fills, and the bubble pops, on a loop.
 * Nothing here takes touches except Skip, so the real gesture works right through it.
 */
@Composable
fun CoachHint(onSkip: () -> Unit, modifier: Modifier = Modifier) {
    val travel = remember { Animatable(0f) }
    val ring = remember { Animatable(0f) }
    val pop = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(400)
        while (true) {
            travel.snapTo(0f)
            ring.snapTo(0f)
            pop.snapTo(0f)
            travel.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
            ring.animateTo(1f, tween(CoachPull.HOLD_MS, easing = LinearEasing))
            launch { pop.animateTo(1f, tween(CoachPull.BURST_MS)) }
            delay(1_100)
        }
    }

    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .fillMaxSize()
            // Darkens the top a little so the demo reads over any screen.
            .background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.72f), 0.55f to Color.Transparent))
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(190.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                val y = 12.dp + 110.dp * travel.value
                // The ghost bubble, a step ahead of the finger.
                Box(
                    Modifier
                        .offset(y = y)
                        .size(52.dp)
                        .graphicsLayer {
                            val grow = if (pop.value > 0f) 1f + 0.5f * pop.value else 0.55f + 0.45f * travel.value
                            scaleX = grow
                            scaleY = grow
                            alpha = if (pop.value > 0f) (1f - pop.value * 1.6f).coerceIn(0f, 1f) else travel.value.coerceAtLeast(0.25f)
                        }
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (ring.value > 0f) {
                        CircularProgressIndicator(
                            progress = { ring.value },
                            modifier = Modifier.size(52.dp),
                            strokeWidth = 3.dp,
                            color = primary,
                            trackColor = primary.copy(alpha = 0.18f),
                        )
                    }
                    Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(24.dp), tint = primary)
                }
                Icon(
                    Icons.Rounded.TouchApp,
                    null,
                    Modifier
                        .offset(x = 18.dp, y = y + 34.dp)
                        .size(40.dp)
                        .graphicsLayer { alpha = if (pop.value > 0f) 1f - pop.value else 1f },
                    tint = Color.White,
                )
            }
            Column(
                Modifier
                    .padding(horizontal = 32.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Pull down and hold", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "Drag down anywhere on the screen, hold for a moment, and your coach pops in. Try it now.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(2.dp))
                TextButton(onClick = onSkip) { Text("Skip") }
            }
        }
    }
}
