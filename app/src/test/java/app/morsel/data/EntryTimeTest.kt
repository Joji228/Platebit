package app.morsel.data

import app.morsel.domain.MealType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

class EntryTimeTest {
    private val today = LocalDate.of(2026, 10, 20)

    @Test
    fun todayUsesTheCurrentTime() {
        assertEquals(123L, EntryTime.stamp(today, LocalTime.NOON, today, now = 123L, zone = ZoneOffset.UTC))
    }

    @Test
    fun anotherDayGetsASensibleTimeOnThatDay() {
        val yesterday = today.minusDays(1)
        val lunch = EntryTime.stamp(yesterday, EntryTime.meal(MealType.LUNCH), today, now = 123L, zone = ZoneOffset.UTC)
        assertEquals(yesterday.atTime(13, 0).toInstant(ZoneOffset.UTC).toEpochMilli(), lunch)
        val weighIn = EntryTime.stamp(yesterday, EntryTime.WEIGH_IN, today, now = 123L, zone = ZoneOffset.UTC)
        // Before breakfast, so a past day's list reads in the order it happened
        assertEquals(true, weighIn < EntryTime.stamp(yesterday, EntryTime.meal(MealType.BREAKFAST), today, zone = ZoneOffset.UTC))
    }
}
