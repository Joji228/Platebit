package app.morsel.data

import app.morsel.domain.MealType
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * When an entry logged for some day is stamped. Today's entries get the current time; another
 * day's get a sensible time on that day (breakfast in the morning, a weigh-in before it), so the
 * day's list and the weight trend stay in order.
 */
object EntryTime {
    val WEIGH_IN: LocalTime = LocalTime.of(7, 30)

    fun meal(type: MealType): LocalTime = when (type) {
        MealType.BREAKFAST -> LocalTime.of(8, 0)
        MealType.LUNCH -> LocalTime.of(13, 0)
        MealType.DINNER -> LocalTime.of(19, 30)
        MealType.SNACK -> LocalTime.of(16, 0)
    }

    fun stamp(
        date: LocalDate,
        time: LocalTime,
        today: LocalDate = LocalDate.now(),
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long = if (date == today) now else date.atTime(time).atZone(zone).toInstant().toEpochMilli()
}
