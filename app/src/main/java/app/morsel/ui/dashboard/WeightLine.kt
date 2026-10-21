package app.morsel.ui.dashboard

import app.morsel.domain.TrendPoint
import java.time.LocalDate

/** What the Weight card's chart draws. X values are epoch days (fractions allowed). */
internal data class WeightLine(
    /** The line: the starting weight when it belongs on the chart, then each day's weigh-in. */
    val points: List<Pair<Double, Double>>,
    /** One dot per weigh-in. They sit on the line. */
    val weighIns: List<Pair<Double, Double>>,
    /** Older weigh-ins were left off, so the chart reads "last 60 days". */
    val windowed: Boolean = false,
) {
    /** At least one weigh-in is on the chart. */
    val hasWeighIns get() = weighIns.isNotEmpty()

    /** Enough points to draw a line rather than a lone dot. */
    val isLine get() = points.size >= 2
}

/**
 * The line goes through the weigh-ins themselves (one per day, the day's last), so every dot is on
 * it. It starts at the starting weight, so the very first weigh-in already shows progress
 * (100 → 99.5 draws a line, not a lone dot). When that weigh-in is on the start day itself, the
 * start sits half a day earlier so the two points don't stack. Weigh-ins older than the start date
 * tell the story on their own, so no start point is added then.
 *
 * Only the last [windowDays] days are drawn: connecting a year of daily readings turns into a
 * scribble. The Body screen's Trends card has the longer ranges.
 */
internal fun weightLine(
    allDays: List<TrendPoint>,
    startDate: LocalDate?,
    startKg: Double,
    today: LocalDate,
    windowDays: Int = WINDOW_DAYS,
): WeightLine {
    val last = allDays.lastOrNull()?.date
    val days = if (last == null) allDays else allDays.filter { !it.date.isBefore(last.minusDays(windowDays - 1L)) }
    val windowed = days.size < allDays.size
    val firstDay = days.firstOrNull()?.date
    val startX = when {
        windowed -> null // the start is older than anything on the chart; the Start number says it
        firstDay == null -> (startDate ?: today).toEpochDay().toDouble()
        startDate == null || startDate.isAfter(firstDay) -> null
        startDate == firstDay -> firstDay.toEpochDay() - 0.5
        else -> startDate.toEpochDay().toDouble()
    }
    val weighIns = days.map { it.date.toEpochDay().toDouble() to it.raw }
    return WeightLine(
        points = buildList {
            startX?.let { add(it to startKg) }
            addAll(weighIns)
        },
        weighIns = weighIns,
        windowed = windowed,
    )
}

internal const val WINDOW_DAYS = 60
