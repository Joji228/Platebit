package app.morsel.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.pow
import kotlin.math.roundToInt

data class WeighIn(val date: LocalDate, val kg: Double)

data class DayIntake(val date: LocalDate, val kcal: Double)

/** One point per day with a weigh-in: the raw reading and the smoothed trend after it. */
data class TrendPoint(val date: LocalDate, val raw: Double, val trend: Double)

/**
 * Smoothed body-weight trend. Day-to-day weight swings by 1–2 kg with water and food in the gut,
 * which hides real fat loss. An exponential moving average (the "Hacker's Diet" method) keeps
 * 90% of yesterday's trend and adds 10% of today's reading, so noise fades and the real
 * direction shows. Missed days are handled by applying the daily decay once per elapsed day.
 */
object WeightTrend {
    private const val DAILY_KEEP = 0.9

    /** [weighIns] must be in the order they were logged. */
    fun compute(weighIns: List<WeighIn>): List<TrendPoint> {
        // A second weigh-in on the same day usually corrects the first, so the latest one counts.
        val daily = weighIns.groupBy { it.date }.map { (date, list) -> date to list.last().kg }.sortedBy { it.first }
        var trend: Double? = null
        var last: LocalDate? = null
        return daily.map { (date, kg) ->
            val previous = trend
            trend = if (previous == null) {
                kg
            } else {
                val days = ChronoUnit.DAYS.between(last, date).coerceAtLeast(1)
                val keep = DAILY_KEEP.pow(days.toDouble())
                previous * keep + kg * (1 - keep)
            }
            last = date
            TrendPoint(date, kg, trend!!)
        }
    }

    /** Trend as of [date]: the last trend value on or before it. */
    fun at(points: List<TrendPoint>, date: LocalDate): Double? = points.lastOrNull { !it.date.isAfter(date) }?.trend
}

/** What the logged data says about your real daily burn, and how much to trust it. */
data class AdaptiveEstimate(
    /** Burn implied by your intake and weight trend, before blending; null while learning. */
    val measuredTdee: Int?,
    /** 0 = formula only, 1 = data only. */
    val confidence: Double,
    val daysLogged: Int,
    val daysNeeded: Int,
    /** Why there's no estimate yet, shown to the user. */
    val waitingFor: String?,
) {
    val active get() = measuredTdee != null
}

/**
 * Adaptive daily burn (TDEE) from energy balance: what you ate minus what your body stored.
 *
 *   burn = average daily intake − (trend change in kg × 7,700 kcal) ÷ days
 *
 * The formula estimate (Mifflin-St Jeor × activity) can be off by ±10–15% for a given person;
 * this measures you instead. It looks at the last 4 weeks, takes the weight change from a
 * straight-line fit through the weigh-ins (so one salty dinner doesn't swing it, and unlike the
 * smoothed trend it doesn't lag behind a fresh start), skips days with no or partial logging,
 * and blends in gradually: 10 logged days give it about half weight, 21 or more full weight.
 */
object AdaptiveTdee {
    const val WINDOW_DAYS = 28
    const val MIN_SPAN_DAYS = 14
    const val MIN_LOGGED_DAYS = 10
    private const val FULL_CONFIDENCE_DAYS = 21
    private const val PARTIAL_DAY_KCAL = 800.0
    private const val KCAL_PER_KG = 7700.0

    fun estimate(weighIns: List<WeighIn>, intakes: List<DayIntake>, formulaTdee: Double, today: LocalDate): AdaptiveEstimate {
        val windowStart = today.minusDays(WINDOW_DAYS.toLong())
        val yesterday = today.minusDays(1)
        val trend = WeightTrend.compute(weighIns.filter { !it.date.isAfter(yesterday) })
        val inWindow = trend.filter { !it.date.isBefore(windowStart) }

        fun waiting(logged: Int, reason: String) = AdaptiveEstimate(null, 0.0, logged, MIN_LOGGED_DAYS, reason)

        if (inWindow.size < 3) return waiting(0, "Log your weight at least 3 times over 2 weeks")
        val first = inWindow.first()
        val last = inWindow.last()
        val span = ChronoUnit.DAYS.between(first.date, last.date).toInt()
        if (span < MIN_SPAN_DAYS) return waiting(0, "Needs 2 weeks between your first and latest weigh-in")

        // Today is still being logged, and a day under 800 kcal is almost always a partial log.
        val logged = intakes.filter { !it.date.isBefore(first.date) && it.date.isBefore(last.date) && it.kcal >= PARTIAL_DAY_KCAL }
        val needed = maxOf(MIN_LOGGED_DAYS, (span * 0.6).roundToInt())
        if (logged.size < needed) return waiting(logged.size, "Log your meals on more days").copy(daysNeeded = needed)

        val averageIntake = logged.map { it.kcal }.average()
        val stored = slopeKgPerDay(inWindow) * KCAL_PER_KG
        // A wildly different number means missing logs or a bad weigh-in, not a real metabolism.
        val measured = (averageIntake - stored).coerceIn(formulaTdee * 0.75, formulaTdee * 1.35)
        val confidence = (logged.size.toDouble() / FULL_CONFIDENCE_DAYS).coerceAtMost(1.0)
        return AdaptiveEstimate((measured / 10).roundToInt() * 10, confidence, logged.size, needed, null)
    }

    /** Least-squares slope of the daily readings, in kg per day. */
    private fun slopeKgPerDay(points: List<TrendPoint>): Double {
        val origin = points.first().date
        val xs = points.map { ChronoUnit.DAYS.between(origin, it.date).toDouble() }
        val ys = points.map { it.raw }
        val mx = xs.average()
        val my = ys.average()
        val variance = xs.sumOf { (it - mx) * (it - mx) }
        return if (variance == 0.0) 0.0 else xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / variance
    }

    /** Formula burn moved toward the measured one by how much we trust the data. */
    fun blend(formulaTdee: Double, estimate: AdaptiveEstimate): Double =
        estimate.measuredTdee?.let { formulaTdee + estimate.confidence * (it - formulaTdee) } ?: formulaTdee
}
