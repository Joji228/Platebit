package app.morsel.data

import app.morsel.data.db.FoodItemEntity
import app.morsel.data.db.MealEntity
import app.morsel.data.db.WaterEntity
import app.morsel.data.db.WeightEntity
import app.morsel.domain.BodyAnalysis
import app.morsel.domain.Nutrition
import app.morsel.domain.Sex
import app.morsel.domain.TrendPoint
import app.morsel.domain.UserProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale

class CoachContextTest {
    private val zone = ZoneOffset.UTC
    private val now = LocalDateTime.of(2026, 10, 20, 15, 30)
    private val today = now.toLocalDate()
    private val profile = UserProfile(sex = Sex.MALE, age = 34, heightCm = 180.0, startWeightKg = 85.0, goalWeightKg = 78.0, startDate = "2026-01-05")

    private fun at(date: LocalDate, hour: Int) = date.atTime(hour, 0).toInstant(zone).toEpochMilli()

    private fun plan(trend: List<TrendPoint> = emptyList()) = PlanState(
        profile = profile,
        trend = trend,
        currentWeight = 83.8,
        targets = Nutrition.targets(profile, 83.8),
    )

    private fun meal(id: Long, date: LocalDate, name: String, hour: Int = 12) =
        MealEntity(id = id, date = date.toString(), timestamp = at(date, hour), mealType = "LUNCH", name = name)

    private fun item(mealId: Long, name: String, grams: Double, kcal: Double, p: Double = 10.0) =
        FoodItemEntity(mealId = mealId, name = name, grams = grams, kcalPer100 = kcal, proteinPer100 = p, carbsPer100 = 20.0, fatPer100 = 5.0)

    @Test
    fun includesProfileTargetsDailyTotalsAndMeals() {
        val meals = listOf(meal(1, today, "Chicken rice"), meal(2, today.minusDays(1), "Greek yogurt bowl", 8))
        val items = listOf(item(1, "Chicken", 150.0, 165.0, 31.0), item(1, "Rice", 200.0, 130.0, 2.7), item(2, "Yogurt", 200.0, 97.0))
        val water = listOf(WaterEntity(date = today.toString(), timestamp = at(today, 9), ml = 500), WaterEntity(date = today.toString(), timestamp = at(today, 11), ml = 500))
        val text = CoachContext.build(now, plan(), meals, items, water, emptyList(), zone)

        assertTrue(text.contains("Male, 34 years, 180 cm"))
        assertTrue(text.contains("Goal 78.0 kg"))
        assertTrue(text.contains("Trend weight now: 83.8 kg"))
        assertTrue(text.contains("Calories ${Nutrition.targets(profile, 83.8).kcal} (calculated from the profile)"))
        // 150 g chicken (248 kcal) + 200 g rice (260 kcal) = 508 kcal, water 1000 ml, one meal
        assertTrue(text, text.contains("$today,508,"))
        assertTrue(text, text.lines().any { it.startsWith("$today,508,") && it.endsWith(",1000,1") })
        assertTrue(text.contains("$today 12:00 | #1 | lunch | Chicken rice | 508"))
        assertTrue(text.contains("Chicken 150 g, Rice 200 g"))
        // Oldest first
        assertTrue(text.indexOf("${today.minusDays(1)},") < text.indexOf("$today,"))
    }

    @Test
    fun weighInsCarryTheSameCompositionAsTheBodyScreen() {
        val day = today.minusDays(2)
        val scale = WeightEntity(date = day.toString(), timestamp = at(day, 7), kg = 84.0, impedance = 500)
        val manual = WeightEntity(date = today.toString(), timestamp = at(today, 7), kg = 83.5)
        val text = CoachContext.build(now, plan(listOf(TrendPoint(day, 84.0, 84.1))), emptyList(), emptyList(), emptyList(), listOf(manual, scale), zone)

        val report = BodyAnalysis.report(84.0, 500, profile)
        val c = report.composition!!
        val fat = String.format(Locale.US, "%.1f", c.fatPct)
        val muscle = String.format(Locale.US, "%.1f", c.muscleKg)
        val line = text.lines().first { it.startsWith("$day 07:00,84.0,84.1,") }
        assertTrue(line, line.contains(",$fat,") && line.contains(",$muscle,"))
        assertTrue(line.endsWith(",${report.score},${report.bodyAge}"))
        // The manual one has no composition and comes after (oldest first)
        val manualLine = text.lines().first { it.startsWith("$today 07:00,83.5,") }
        assertTrue(manualLine.endsWith(",,,,,,,,,"))
        assertTrue(text.indexOf(line) < text.indexOf(manualLine))
    }

    @Test
    fun oldMealsBeyondTheWindowOnlyCountInDailyTotals() {
        val old = today.minusDays(200)
        val text = CoachContext.build(now, plan(), listOf(meal(1, old, "Old pizza")), listOf(item(1, "Pizza", 300.0, 270.0)), emptyList(), emptyList(), zone)
        assertTrue(text.contains("$old,810,"))
        assertFalse(text.contains("Old pizza"))
    }

    @Test
    fun foodsOnlyForTheLastTwoWeeks() {
        val recent = today.minusDays(3)
        val older = today.minusDays(30)
        val text = CoachContext.build(
            now, plan(),
            listOf(meal(1, recent, "Recent bowl"), meal(2, older, "Older bowl")),
            listOf(item(1, "Oats", 80.0, 380.0), item(2, "Quinoa", 80.0, 370.0)),
            emptyList(), emptyList(), zone,
        )
        assertTrue(text.contains("Recent bowl") && text.contains("Oats 80 g"))
        assertTrue(text.contains("Older bowl"))
        assertFalse(text.contains("Quinoa"))
    }

    @Test
    fun shrinksTheMealWindowToFitTheLimit() {
        val meals = (0 until 90).map { meal(it + 1L, today.minusDays(it.toLong()), "Meal number $it with a fairly long descriptive name") }
        val items = meals.map { item(it.id, "Something tasty", 250.0, 200.0) }
        val full = CoachContext.build(now, plan(), meals, items, emptyList(), emptyList(), zone)
        val limit = full.length - 2_000
        val small = CoachContext.build(now, plan(), meals, items, emptyList(), emptyList(), zone, maxChars = limit)
        assertTrue(small.length <= limit)
        assertTrue(small.contains("Meals, last 60 days"))
        // Every day still has its totals
        assertTrue(small.contains("${today.minusDays(89)},500,"))
    }

    @Test
    fun namesCantBreakTheLayout() {
        val text = CoachContext.build(now, plan(), listOf(meal(1, today, "Two\nlines | pipe")), listOf(item(1, "X", 100.0, 100.0)), emptyList(), emptyList(), zone)
        assertTrue(text.contains("| Two lines / pipe |"))
    }

    @Test
    fun emptyAppSaysSo() {
        val text = CoachContext.build(now, PlanState(), emptyList(), emptyList(), emptyList(), emptyList(), zone)
        assertTrue(text.contains("No profile set up."))
        assertTrue(text.contains("Nothing logged yet."))
        assertTrue(text.contains("None yet."))
    }
}
