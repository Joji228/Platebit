package app.morsel.ui.dashboard

import app.morsel.domain.TrendPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class WeightLineTest {
    private val today = LocalDate.of(2026, 10, 21)
    private val start = LocalDate.of(2026, 10, 18)

    // Trend values differ from the readings on purpose: the line must use the readings.
    private fun day(date: LocalDate, raw: Double) = TrendPoint(date, raw, raw + 0.6)
    private fun x(date: LocalDate) = date.toEpochDay().toDouble()

    @Test
    fun theLineGoesThroughTheWeighInsNotTheSmoothedTrend() {
        val days = listOf(day(start.plusDays(1), 84.5), day(start.plusDays(3), 83.9))
        val line = weightLine(days, start, 85.0, today)
        // The start weight first, then exactly the readings
        assertEquals(listOf(x(start) to 85.0, x(start.plusDays(1)) to 84.5, x(start.plusDays(3)) to 83.9), line.points)
        assertEquals(listOf(x(start.plusDays(1)) to 84.5, x(start.plusDays(3)) to 83.9), line.weighIns)
        // Every dot is a point of the line
        assertTrue(line.points.containsAll(line.weighIns))
        assertTrue(line.isLine && line.hasWeighIns)
    }

    @Test
    fun aWeighInOnTheStartDayStartsHalfADayLater() {
        val line = weightLine(listOf(day(start, 84.6)), start, 85.0, today)
        assertEquals(listOf((x(start) - 0.5) to 85.0, x(start) to 84.6), line.points)
    }

    @Test
    fun weighInsBeforeTheStartDateGetNoStartPoint() {
        val before = start.minusDays(8)
        val one = weightLine(listOf(day(before, 84.2)), start, 85.0, today)
        assertEquals(listOf(x(before) to 84.2), one.points)
        // A single weigh-in is a dot and says so, instead of claiming nothing is logged
        assertTrue(one.hasWeighIns)
        assertFalse(one.isLine)
        val two = weightLine(listOf(day(before, 84.2), day(before.plusDays(2), 84.0)), start, 85.0, today)
        assertTrue(two.isLine)
    }

    @Test
    fun aLongHistoryShowsOnlyTheLastSixtyDays() {
        val first = today.minusDays(299)
        val days = (0L until 300L).map { day(first.plusDays(it), 90.0 - it * 0.02) }
        val line = weightLine(days, first, 92.0, today)
        assertEquals(WINDOW_DAYS, line.weighIns.size)
        assertEquals(x(today.minusDays(59)), line.weighIns.first().first, 0.0)
        assertEquals(x(today), line.weighIns.last().first, 0.0)
        // The start (300 days ago) isn't on a chart of the last 60 days
        assertEquals(line.weighIns, line.points)
        assertTrue(line.windowed)
        // Exactly sixty days of data is not cut, and keeps its start point
        val sixty = weightLine(days.takeLast(WINDOW_DAYS), today.minusDays(70), 92.0, today)
        assertFalse(sixty.windowed)
        assertEquals(WINDOW_DAYS + 1, sixty.points.size)
    }

    @Test
    fun noWeighInsIsJustTheStartWeight() {
        val line = weightLine(emptyList(), start, 85.0, today)
        assertEquals(listOf(x(start) to 85.0), line.points)
        assertFalse(line.hasWeighIns)
        assertFalse(line.isLine)
        // Without a readable start date it falls back to today
        assertEquals(listOf(x(today) to 85.0), weightLine(emptyList(), null, 85.0, today).points)
    }
}
