package app.morsel.ui.components

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * The two feelings the app uses, in one place: a light [tick] for choosing and moving things, and
 * a firmer [click] for things that happen (saved, accepted, switched on).
 *
 * Both are mapped onto the plainest vibrations a phone has: CONTEXT_CLICK is the system's TICK
 * and LONG_PRESS its heavy click. The constants used before (TEXT_HANDLE_MOVE, a "texture tick",
 * and the newer GESTURE_* and CONFIRM) are optional extras that many vibration motors don't have:
 * on the test emulator the system logs them as "ignored_unsupported", i.e. silent. That's why the
 * coach pull could play nothing.
 */
@Stable
class Haptics(private val view: View) {
    fun tick() {
        if (HapticsSetting.enabled) view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    fun click() {
        if (HapticsSetting.enabled) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    /** A switch: firm when it goes on, light when it goes off, so the two feel different. */
    fun toggle(on: Boolean) = if (on) click() else tick()
}

/**
 * Settings → Haptic feedback, mirrored here so every [Haptics] (and any gesture code holding one
 * for a long time) sees the current choice without being rebuilt. AppContainer keeps it in sync.
 */
object HapticsSetting {
    @Volatile
    var enabled: Boolean = true
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}
