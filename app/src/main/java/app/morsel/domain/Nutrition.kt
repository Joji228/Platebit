package app.morsel.domain

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

@Serializable
enum class Sex { MALE, FEMALE }

@Serializable
enum class Activity(val factor: Double, val waterBonusMl: Int, val title: String, val subtitle: String) {
    SEDENTARY(1.2, 0, "Sedentary", "Desk job, little or no exercise"),
    LIGHT(1.375, 250, "Lightly active", "1–3 workouts a week"),
    MODERATE(1.55, 500, "Moderately active", "3–5 workouts a week"),
    VERY(1.725, 750, "Very active", "6–7 workouts a week"),
    ATHLETE(1.9, 1000, "Athlete", "Hard daily training or a physical job"),
}

@Serializable
data class UserProfile(
    val sex: Sex = Sex.MALE,
    val age: Int = 30,
    val heightCm: Double = 180.0,
    val startWeightKg: Double = 85.0,
    val goalWeightKg: Double = 78.0,
    val activity: Activity = Activity.MODERATE,
    val weeklyRateKg: Double = 0.25,
    val startDate: String = LocalDate.now().toString(),
    val customKcal: Int? = null,
    /** Null means the water goal is calculated from sex, weight, height and activity. */
    val customWaterMl: Int? = null,
    /** Adjust the calorie target from logged meals and weight trend (see [AdaptiveTdee]). */
    val smartTarget: Boolean = true,
)

enum class MealType(val title: String) {
    BREAKFAST("Breakfast"),
    LUNCH("Lunch"),
    DINNER("Dinner"),
    SNACK("Snack");

    companion object {
        fun forTime(time: LocalTime = LocalTime.now()): MealType = when {
            time < LocalTime.of(10, 30) -> BREAKFAST
            time < LocalTime.of(15, 0) -> LUNCH
            time < LocalTime.of(17, 30) -> SNACK
            time < LocalTime.of(21, 30) -> DINNER
            else -> SNACK
        }

        fun parse(value: String?): MealType = entries.firstOrNull { it.name == value } ?: SNACK
    }
}

data class Macros(
    val kcal: Double = 0.0,
    val protein: Double = 0.0,
    val carbs: Double = 0.0,
    val fat: Double = 0.0,
) {
    operator fun plus(other: Macros) =
        Macros(kcal + other.kcal, protein + other.protein, carbs + other.carbs, fat + other.fat)

    operator fun times(factor: Double) =
        Macros(kcal * factor, protein * factor, carbs * factor, fat * factor)
}

data class Targets(
    val kcal: Int,
    val protein: Int,
    val carbs: Int,
    val fat: Int,
    val bmr: Int,
    val tdee: Int,
    /** Negative for a deficit, positive for a surplus. */
    val dailyDelta: Int,
    val weeksToGoal: Double?,
    val waterMl: Int,
    val bmi: Double,
)

object Nutrition {
    private const val KCAL_PER_KG = 7700.0

    /** Mifflin-St Jeor resting energy expenditure. */
    fun bmr(profile: UserProfile, weightKg: Double): Double {
        val base = 10 * weightKg + 6.25 * profile.heightCm - 5 * profile.age
        return base + if (profile.sex == Sex.MALE) 5 else -161
    }

    fun bmi(weightKg: Double, heightCm: Double): Double = weightKg / (heightCm / 100).pow(2)

    fun bmiCategory(bmi: Double): String = when {
        bmi < 18.5 -> "Underweight"
        bmi < 25 -> "Healthy"
        bmi < 30 -> "Overweight"
        else -> "Obese"
    }

    /**
     * Daily drinking-water goal: 33 ml/kg for men, 31 ml/kg for women, plus extra for activity.
     * Weight above a BMI of 30 is mostly fat, which holds little water, so it isn't counted.
     * Lands near the EFSA/US guidance (about 2.5–3.7 L for men, 2.0–2.7 L for women).
     */
    fun waterMl(profile: UserProfile, weightKg: Double): Int {
        profile.customWaterMl?.let { return it }
        val countedKg = min(weightKg, 30 * (profile.heightCm / 100).pow(2))
        val perKg = if (profile.sex == Sex.MALE) 33 else 31
        val ml = countedKg * perKg + profile.activity.waterBonusMl
        return ((ml / 250).roundToInt() * 250).coerceIn(1500, 5000)
    }

    private fun direction(profile: UserProfile, weightKg: Double): Int {
        val diff = profile.goalWeightKg - weightKg
        return when {
            abs(diff) < 0.3 -> 0
            diff < 0 -> -1
            else -> 1
        }
    }

    /**
     * Daily burn. [scale] is measured burn ÷ formula burn from [AdaptiveTdee]; applying it as a
     * ratio keeps the correction valid as weight (and so BMR) changes.
     */
    fun tdee(profile: UserProfile, weightKg: Double, scale: Double = 1.0) = bmr(profile, weightKg) * profile.activity.factor * scale

    /** The daily calorie target the app shows at a given body weight. */
    private fun kcalTarget(profile: UserProfile, weightKg: Double, scale: Double): Int {
        profile.customKcal?.let { return (it / 10.0).roundToInt() * 10 }
        val tdee = tdee(profile, weightKg, scale)
        val direction = direction(profile, weightKg)
        // Cap loss at ~1% of body weight per week and gain at 0.5 kg per week.
        val maxRate = if (direction < 0) weightKg * 0.01 else 0.5
        val rate = min(profile.weeklyRateKg, maxRate)
        val floor = if (profile.sex == Sex.MALE) 1500.0 else 1200.0
        val planned = tdee + direction * rate * KCAL_PER_KG / 7
        return (max(planned, min(floor, tdee)) / 10).roundToInt() * 10
    }

    /**
     * Days to goal, simulated one day at a time. As weight drops, BMR drops too, so a fixed
     * calorie target slowly stops working; a plain "difference ÷ deficit" ignores that and is
     * too optimistic, especially with a custom target or when the minimum-calorie floor applies.
     */
    private fun weeksToGoal(profile: UserProfile, startKg: Double, scale: Double): Double? {
        val direction = direction(profile, startKg)
        if (direction == 0) return null
        var weight = startKg
        for (day in 0 until 5 * 365) {
            // Within 0.3 kg of the goal counts as there; the target switches to maintenance then.
            if (direction(profile, weight) != direction) return day / 7.0
            val delta = kcalTarget(profile, weight, scale) - tdee(profile, weight, scale)
            if (delta == 0.0 || (delta < 0) != (direction < 0)) return null
            weight += delta / KCAL_PER_KG
        }
        return null
    }

    fun targets(profile: UserProfile, currentWeightKg: Double, tdeeScale: Double = 1.0): Targets {
        val bmr = bmr(profile, currentWeightKg)
        val tdee = tdee(profile, currentWeightKg, tdeeScale)
        val kcal = kcalTarget(profile, currentWeightKg, tdeeScale)

        val proteinBase = if (direction(profile, currentWeightKg) < 0) profile.goalWeightKg else currentWeightKg
        val protein = 1.8 * proteinBase
        val fat = kcal * 0.27 / 9
        val carbs = max(0.0, (kcal - protein * 4 - fat * 9) / 4)

        return Targets(
            kcal = kcal,
            protein = protein.roundToInt(),
            carbs = carbs.roundToInt(),
            fat = fat.roundToInt(),
            bmr = bmr.roundToInt(),
            tdee = tdee.roundToInt(),
            dailyDelta = (kcal - tdee).roundToInt(),
            weeksToGoal = weeksToGoal(profile, currentWeightKg, tdeeScale),
            waterMl = waterMl(profile, currentWeightKg),
            bmi = bmi(currentWeightKg, profile.heightCm),
        )
    }
}
