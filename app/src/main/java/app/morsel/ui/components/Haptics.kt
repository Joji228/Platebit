package app.morsel.ui.components

import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
 * They are played straight on the phone's vibrator, as the system's own TICK and HEAVY_CLICK
 * effects, rather than through View.performHapticFeedback. That call obeys the system "touch
 * feedback" switch (Samsung: Settings → Sounds and vibration → Vibration intensity → Touch
 * feedback): with it off, every request is dropped as "ignored_for_settings" and the app can't
 * vibrate at all. Settings → Haptic feedback in the app is the one switch that matters here; the
 * vibration is tagged as media, so the phone's media vibration intensity still scales it.
 *
 * (The optional constants used earlier, TEXT_HANDLE_MOVE and the GESTURE_* / CONFIRM ones, are
 * missing on many vibration motors and were silent there. Plain TICK and HEAVY_CLICK always play,
 * with a fallback waveform if the motor lacks them.)
 */
@Stable
class Haptics(private val view: View) {
    private val vibrator: Vibrator? = runCatching {
        view.context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    }.getOrNull()?.takeIf { it.hasVibrator() }

    fun tick() = play(VibrationEffect.EFFECT_TICK, HapticFeedbackConstants.CONTEXT_CLICK)

    fun click() = play(VibrationEffect.EFFECT_HEAVY_CLICK, HapticFeedbackConstants.LONG_PRESS)

    /** A switch: firm when it goes on, light when it goes off, so the two feel different. */
    fun toggle(on: Boolean) = if (on) click() else tick()

    private fun play(effect: Int, fallbackConstant: Int) {
        if (!HapticsSetting.enabled) return
        val v = vibrator
        if (v == null) {
            view.performHapticFeedback(fallbackConstant)
            return
        }
        runCatching {
            val e = VibrationEffect.createPredefined(effect)
            if (Build.VERSION.SDK_INT >= 33) {
                v.vibrate(e, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(
                    e,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
            }
        }.onFailure { view.performHapticFeedback(fallbackConstant) }
    }
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
