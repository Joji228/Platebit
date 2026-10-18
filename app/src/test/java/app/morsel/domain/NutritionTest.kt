package app.morsel.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NutritionTest {
    private val man = UserProfile(
        sex = Sex.MALE,
        age = 30,
        heightCm = 180.0,
        startWeightKg = 90.0,
        goalWeightKg = 80.0,
        activity = Activity.MODERATE,
        weeklyRateKg = 0.5,
    )

    @Test
    fun bmrMatchesMifflinStJeor() {
        // 10*90 + 6.25*180 - 5*30 + 5 = 1880
        assertEquals(1880.0, Nutrition.bmr(man, 90.0), 0.001)
        assertEquals(1714.0, Nutrition.bmr(man.copy(sex = Sex.FEMALE), 90.0), 0.001)
    }

    @Test
    fun deficitFollowsWeeklyRate() {
        val t = Nutrition.targets(man, 90.0)
        // TDEE 1880 * 1.55 = 2914; 0.5 kg/week = 550 kcal/day deficit -> 2364 -> rounded 2360
        assertEquals(2914, t.tdee)
        assertEquals(2360, t.kcal)
        assertEquals(144, t.protein) // 1.8 g per kg of goal weight
        assertTrue(t.weeksToGoal!! in 19.0..21.0)
    }

    @Test
    fun neverGoesBelowFloor() {
        val tiny = man.copy(sex = Sex.FEMALE, heightCm = 150.0, age = 60, activity = Activity.SEDENTARY, weeklyRateKg = 1.0)
        val t = Nutrition.targets(tiny, 55.0)
        assertTrue(t.kcal >= 1150)
    }

    @Test
    fun maintenanceHasNoGoalDate() {
        val t = Nutrition.targets(man.copy(goalWeightKg = 90.0), 90.0)
        assertEquals(2910, t.kcal)
        assertNull(t.weeksToGoal)
    }

    @Test
    fun mealTypeFollowsTheClock() {
        assertEquals(MealType.SNACK, MealType.forTime(java.time.LocalTime.of(0, 30)))
        assertEquals(MealType.BREAKFAST, MealType.forTime(java.time.LocalTime.of(8, 0)))
        assertEquals(MealType.LUNCH, MealType.forTime(java.time.LocalTime.of(13, 0)))
        assertEquals(MealType.DINNER, MealType.forTime(java.time.LocalTime.of(20, 0)))
        assertEquals(MealType.SNACK, MealType.forTime(java.time.LocalTime.of(23, 0)))
    }

    @Test
    fun customTargetWins() {
        assertEquals(2000, Nutrition.targets(man.copy(customKcal = 2000), 90.0).kcal)
    }

    @Test
    fun fixedTargetTakesLongerThanNaiveEstimate() {
        // 2,400 kcal is a 514 kcal deficit at 90 kg, but the deficit shrinks as weight (and BMR) drops.
        val naiveWeeks = 10 * 7700.0 / (514 * 7)
        val weeks = Nutrition.targets(man.copy(customKcal = 2400), 90.0).weeksToGoal!!
        assertTrue(weeks > naiveWeeks + 1)
    }

    @Test
    fun waterScalesWithSexWeightAndActivity() {
        // 85 kg × 33 ml + 500 ml (moderate) = 3,305 → 3,250 ml
        assertEquals(3250, Nutrition.waterMl(man, 85.0))
        // 65 kg × 31 ml + 0 (sedentary) = 2,015 → 2,000 ml
        assertEquals(2000, Nutrition.waterMl(man.copy(sex = Sex.FEMALE, activity = Activity.SEDENTARY), 65.0))
        // Weight above BMI 30 isn't counted: 180 cm caps at 97.2 kg
        assertEquals(Nutrition.waterMl(man, 97.2), Nutrition.waterMl(man, 130.0))
        assertEquals(4000, Nutrition.waterMl(man.copy(customWaterMl = 4000), 85.0))
    }

    @Test
    fun bmi() {
        assertEquals(27.8, Nutrition.bmi(90.0, 180.0), 0.05)
        assertEquals("Overweight", Nutrition.bmiCategory(27.8))
    }
}
