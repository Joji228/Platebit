package app.morsel.data

import app.morsel.data.db.FoodItemEntity
import app.morsel.data.db.MealEntity
import app.morsel.data.db.WaterEntity
import app.morsel.data.db.WeightEntity
import app.morsel.data.db.macros
import app.morsel.domain.BodyAnalysis
import app.morsel.domain.Macros
import app.morsel.domain.MealType
import app.morsel.domain.Sex
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The user's data, written out compactly for the AI coach. Built on the phone for each question.
 *
 * Every logged day is in it as one line of totals, and every weigh-in with its body composition
 * (worked out by the same code as the Body screen, so the numbers agree). Individual meals go
 * back [MEAL_DAYS] days and the foods inside them [FOOD_DAYS] days; with a lot of history those
 * windows shrink until the text fits [maxChars], so a question never gets out of hand.
 */
object CoachContext {
    const val MEAL_DAYS = 90
    const val FOOD_DAYS = 14

    /** About 50k tokens: plenty for years of daily totals plus months of meals. */
    const val MAX_CHARS = 200_000

    private val WINDOWS = listOf(MEAL_DAYS to FOOD_DAYS, 60 to 10, 30 to 7, 14 to 3, 7 to 0, 0 to 0)

    fun build(
        now: LocalDateTime,
        plan: PlanState,
        meals: List<MealEntity>,
        items: List<FoodItemEntity>,
        water: List<WaterEntity>,
        weights: List<WeightEntity>,
        zone: ZoneId = ZoneId.systemDefault(),
        maxChars: Int = MAX_CHARS,
    ): String {
        val itemsByMeal = items.groupBy { it.mealId }
        val logged = meals.map { meal -> LoggedMeal(meal, itemsByMeal[meal.id].orEmpty()) }
        val fixed = buildString {
            header(now)
            profile(plan)
            daily(logged, water, now.toLocalDate())
            weighIns(plan, weights, zone)
        }
        // Meals are the part that grows fastest; take the widest window that still fits.
        for ((mealDays, foodDays) in WINDOWS) {
            val text = fixed + buildString { meals(logged, now.toLocalDate(), mealDays, foodDays, zone) }
            if (text.length <= maxChars || mealDays == 0) return text
        }
        return fixed
    }

    private class LoggedMeal(val meal: MealEntity, val items: List<FoodItemEntity>) {
        val date: LocalDate = LocalDate.parse(meal.date)
        val macros: Macros = items.fold(Macros()) { acc, item -> acc + item.macros() }
    }

    private fun StringBuilder.header(now: LocalDateTime) {
        append("# The user's Platebit data\n")
        append("Now: ").append(now.format(DateTimeFormatter.ofPattern("EEEE yyyy-MM-dd HH:mm", Locale.ENGLISH)))
        append(". Today's numbers are still in progress.\n")
        append("Units: kcal; protein (P), carbs (C) and fat (F) in grams; water in ml; weight in kg.\n")
    }

    private fun StringBuilder.profile(plan: PlanState) {
        val p = plan.profile
        append("\n## Profile\n")
        if (p == null) {
            append("No profile set up.\n")
            return
        }
        append(if (p.sex == Sex.MALE) "Male" else "Female").append(", ").append(p.age).append(" years, ")
        append(n(p.heightCm)).append(" cm, ").append(p.activity.title.lowercase()).append(" (").append(p.activity.subtitle.lowercase()).append(").\n")
        append("Started ").append(p.startDate).append(" at ").append(n(p.startWeightKg, 1)).append(" kg. Goal ")
        append(n(p.goalWeightKg, 1)).append(" kg at ").append(n(p.weeklyRateKg, 2)).append(" kg a week.\n")
        plan.currentWeight?.let { append("Trend weight now: ").append(n(it, 1)).append(" kg (smoothed, the number to judge progress by).\n") }
        plan.targets?.let { t ->
            val source = when {
                p.customKcal != null -> "custom target set by the user"
                plan.estimate?.active == true -> "smart target, tuned to their measured burn"
                else -> "calculated from the profile"
            }
            append("\n## Daily targets now\n")
            append("Calories ").append(t.kcal).append(" (").append(source).append("), P ").append(t.protein)
            append(", C ").append(t.carbs).append(", F ").append(t.fat).append(", water ").append(t.waterMl).append(".\n")
            append("BMR ").append(t.bmr).append(", maintenance (TDEE) ").append(t.tdee).append(", planned daily ")
            append(if (t.dailyDelta < 0) "deficit ${-t.dailyDelta}" else "surplus ${t.dailyDelta}").append(". BMI ").append(n(t.bmi, 1))
            t.weeksToGoal?.let { append(". About ").append(n(it)).append(" weeks to goal at this pace") }
            append(".\n")
        }
        plan.estimate?.let { e ->
            append("Smart target: ")
            if (e.active) {
                append("measured burn about ").append(e.measuredTdee).append(" kcal a day from logged meals and weight trend (")
                append((e.confidence * 100).roundToInt()).append("% trusted).\n")
            } else {
                append("still learning (").append(e.daysLogged).append(" of ").append(e.daysNeeded).append(" days of meals logged).\n")
            }
        }
    }

    private fun StringBuilder.daily(meals: List<LoggedMeal>, water: List<WaterEntity>, today: LocalDate) {
        val byDay = meals.groupBy { it.date }
        val waterByDay = water.groupBy { LocalDate.parse(it.date) }.mapValues { (_, list) -> list.sumOf { it.ml } }
        val days = (byDay.keys + waterByDay.keys).filter { !it.isAfter(today) }.sorted()
        append("\n## Daily totals, every logged day (oldest first)\n")
        if (days.isEmpty()) {
            append("Nothing logged yet.\n")
            return
        }
        append("date,kcal,P,C,F,water,meals\n")
        days.forEach { day ->
            val dayMeals = byDay[day].orEmpty()
            val m = dayMeals.fold(Macros()) { acc, meal -> acc + meal.macros }
            append(day).append(',').append(n(m.kcal)).append(',').append(n(m.protein)).append(',').append(n(m.carbs))
            append(',').append(n(m.fat)).append(',').append(waterByDay[day] ?: 0).append(',').append(dayMeals.size).append('\n')
        }
    }

    private fun StringBuilder.weighIns(plan: PlanState, weights: List<WeightEntity>, zone: ZoneId) {
        append("\n## Weigh-ins, every one (oldest first)\n")
        if (weights.isEmpty()) {
            append("None yet.\n")
            return
        }
        val trendByDate = plan.trend.associate { it.date to it.trend }
        val profile = plan.profile
        append("Body composition comes from a bathroom smart scale: estimates, so trends matter more than one reading. ")
        append("Blank means a manual weigh-in without it.\n")
        append("date time,kg,trend,fat%,fat kg,muscle kg,water%,bone kg,visceral,BMR,body score,body age\n")
        weights.sortedBy { it.timestamp }.forEach { w ->
            append(w.date).append(' ').append(time(w.timestamp, zone)).append(',').append(n(w.kg, 1)).append(',')
            trendByDate[LocalDate.parse(w.date)]?.let { append(n(it, 1)) }
            val report = profile?.let { BodyAnalysis.report(w.kg, w.impedance, it) }
            val c = report?.composition
            if (c != null) {
                append(',').append(n(c.fatPct, 1)).append(',').append(n(c.fatKg(w.kg), 1)).append(',').append(n(c.muscleKg, 1))
                append(',').append(n(c.waterPct, 1)).append(',').append(n(c.boneKg, 1)).append(',').append(report.visceral ?: "")
                append(',').append(n(c.bmr)).append(',').append(report.score ?: "").append(',').append(report.bodyAge ?: "")
            } else {
                append(",,,,,,,,,")
            }
            append('\n')
        }
    }

    private fun StringBuilder.meals(meals: List<LoggedMeal>, today: LocalDate, mealDays: Int, foodDays: Int, zone: ZoneId) {
        if (mealDays == 0) {
            append("\n## Meals\nIndividual meals left out to keep this short; ask the user what they ate if it matters.\n")
            return
        }
        val recent = meals.filter { !it.date.isBefore(today.minusDays(mealDays - 1L)) && !it.date.isAfter(today) }
            .sortedWith(compareBy({ it.date }, { it.meal.timestamp }))
        append("\n## Meals, last ").append(mealDays).append(" days (oldest first")
        if (foodDays > 0) append("; foods and grams for the last ").append(foodDays).append(" days")
        append(")\n")
        if (recent.isEmpty()) {
            append("None.\n")
            return
        }
        append("date time | meal | name | kcal P C F\n")
        val foodsFrom = today.minusDays(foodDays - 1L)
        recent.forEach { m ->
            val macros = m.macros
            append(m.meal.date).append(' ').append(time(m.meal.timestamp, zone)).append(" | ")
            append(MealType.parse(m.meal.mealType).title.lowercase()).append(" | ").append(m.meal.name.oneLine()).append(" | ")
            append(n(macros.kcal)).append(' ').append(n(macros.protein)).append(' ').append(n(macros.carbs)).append(' ').append(n(macros.fat))
            if (foodDays > 0 && !m.date.isBefore(foodsFrom) && m.items.isNotEmpty()) {
                append(" | ").append(m.items.joinToString(", ") { "${it.name.oneLine()} ${n(it.grams)} g" })
            }
            append('\n')
        }
    }

    private fun time(timestamp: Long, zone: ZoneId) =
        Instant.ofEpochMilli(timestamp).atZone(zone).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))

    /** Names come from the AI or the user; keep each record on its own line. */
    private fun String.oneLine() = replace('\n', ' ').replace('|', '/').trim()

    /** Numbers always with a dot and no grouping, whatever the phone's language. */
    private fun n(value: Double, decimals: Int = 0): String =
        if (decimals == 0) value.roundToInt().toString() else String.format(Locale.US, "%.${decimals}f", value)
}
