package app.morsel.ble

import kotlin.math.abs

/**
 * Turns the scale's stream of broadcast frames into finished weigh-ins, without anyone pressing a
 * button. The scale reports the weight while it settles, then a stable weight, then (barefoot) a
 * second stable frame with the impedance. It keeps repeating that last frame for a while after
 * the person steps off, so a finished weigh-in is reported once, not once per frame.
 *
 * Time is passed in so the rules can be tested without waiting.
 */
class WeighInDetector(
    /** How long to wait after the weight settles for the impedance before saving weight only. */
    private val impedanceWaitMs: Long = 12_000,
    /** Repeats of a finished weigh-in within this window are ignored. */
    private val repeatGuardMs: Long = 90_000,
) {
    sealed interface Event {
        /** The number on the scale right now; [settled] once it stopped changing. */
        data class Weighing(val kg: Double, val settled: Boolean) : Event

        /** A finished weigh-in. [impedance] is null when it was weighed without it (shoes, socks). */
        data class Done(val kg: Double, val impedance: Int?) : Event
    }

    private var settledKg: Double? = null
    private var settledAt = 0L
    private var lastDoneKg: Double? = null
    private var lastDoneAt = Long.MIN_VALUE / 2

    fun onReading(reading: MiScaleReading, now: Long): Event? {
        val doneKg = lastDoneKg
        if (doneKg != null && now - lastDoneAt < repeatGuardMs && abs(reading.weightKg - doneKg) < REPEAT_KG) return null
        return when {
            !reading.stable -> {
                settledKg = null
                Event.Weighing(reading.weightKg, settled = false)
            }
            reading.impedance != null -> finish(reading.weightKg, reading.impedance, now)
            else -> {
                val settled = settledKg
                // The impedance-less stable frame repeats until the impedance arrives; keep the first one's time.
                if (settled == null || abs(settled - reading.weightKg) >= SAME_KG) {
                    settledKg = reading.weightKg
                    settledAt = now
                }
                Event.Weighing(reading.weightKg, settled = true)
            }
        }
    }

    /** Call regularly (about once a second): saves the weight alone once the impedance clearly isn't coming. */
    fun onTick(now: Long): Event? {
        val settled = settledKg ?: return null
        return if (now - settledAt >= impedanceWaitMs) finish(settled, null, now) else null
    }

    private fun finish(kg: Double, impedance: Int?, now: Long): Event.Done {
        settledKg = null
        lastDoneKg = kg
        lastDoneAt = now
        return Event.Done(kg, impedance)
    }

    companion object {
        /** Two stable frames this close are the same weigh-in. */
        private const val SAME_KG = 0.3

        /** A new weigh-in within the repeat window has to differ by at least this much to count (someone else). */
        private const val REPEAT_KG = 1.0

        /** Auto-saved only when this close to your latest weigh-in; anything further is checked with you first. */
        const val SAME_PERSON_KG = 5.0

        /** Whether a finished weigh-in is close enough to [referenceKg] to be you without asking. */
        fun looksLikeYou(kg: Double, referenceKg: Double?) = referenceKg == null || abs(kg - referenceKg) <= SAME_PERSON_KG
    }
}
