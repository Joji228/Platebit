package app.morsel.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.math.abs
import kotlin.random.Random

class AdaptiveTest {
    private val today = LocalDate.of(2026, 10, 18)

    /** A person who really burns [trueTdee], eats [eats] a day and weighs in daily with noise. */
    private fun simulate(trueTdee: Double, eats: Double, days: Int = 28, startKg: Double = 90.0, noiseKg: Double = 0.5, seed: Int = 1): Pair<List<WeighIn>, List<DayIntake>> {
        val random = Random(seed)
        var kg = startKg
        val weighIns = mutableListOf<WeighIn>()
        val intakes = mutableListOf<DayIntake>()
        for (i in days downTo 1) {
            val date = today.minusDays(i.toLong())
            weighIns += WeighIn(date, kg + (random.nextDouble() * 2 - 1) * noiseKg)
            intakes += DayIntake(date, eats)
            kg += (eats - trueTdee) / 7700.0
        }
        return weighIns to intakes
    }

    @Test
    fun trendIgnoresDailyNoise() {
        // Alternating 80 / 81 kg: the trend should settle near 80.5 and barely move.
        val weighIns = (0 until 30).map { WeighIn(today.minusDays(30L - it), if (it % 2 == 0) 80.0 else 81.0) }
        val trend = WeightTrend.compute(weighIns)
        assertTrue(trend.takeLast(10).all { abs(it.trend - 80.5) < 0.15 })
    }

    @Test
    fun trendDecaysAcrossMissedDays() {
        val trend = WeightTrend.compute(listOf(WeighIn(today.minusDays(10), 80.0), WeighIn(today, 70.0)))
        // 10 days of 0.9 decay: 80 × 0.9^10 + 70 × (1 − 0.9^10) ≈ 73.5
        assertEquals(73.5, trend.last().trend, 0.05)
    }

    @Test
    fun latestSameDayWeighInWins() {
        // e.g. a default 85 kg from setup, corrected to the real 99.5 kg the same day.
        val trend = WeightTrend.compute(listOf(WeighIn(today, 85.0), WeighIn(today, 99.5)))
        assertEquals(1, trend.size)
        assertEquals(99.5, trend.single().trend, 0.001)
    }

    @Test
    fun recoversTrueBurnFromNoisyData() {
        val (weighIns, intakes) = simulate(trueTdee = 2600.0, eats = 2100.0)
        val estimate = AdaptiveTdee.estimate(weighIns, intakes, formulaTdee = 2900.0, today = today)
        assertTrue(estimate.active)
        assertEquals(1.0, estimate.confidence, 0.001)
        assertEquals(2600.0, estimate.measuredTdee!!.toDouble(), 120.0)
        assertEquals(2600.0, AdaptiveTdee.blend(2900.0, estimate), 120.0)
    }

    @Test
    fun waitsForEnoughWeighIns() {
        val estimate = AdaptiveTdee.estimate(listOf(WeighIn(today.minusDays(3), 90.0)), emptyList(), 2500.0, today)
        assertFalse(estimate.active)
        assertNotNull(estimate.waitingFor)
        assertEquals(2500.0, AdaptiveTdee.blend(2500.0, estimate), 0.001)
    }

    @Test
    fun ignoresPartialDaysAndWaitsForLogs() {
        val (weighIns, intakes) = simulate(trueTdee = 2600.0, eats = 2100.0)
        // Only every third day fully logged; the rest look like forgotten logs.
        val sparse = intakes.mapIndexed { i, day -> if (i % 3 == 0) day else day.copy(kcal = 300.0) }
        val estimate = AdaptiveTdee.estimate(weighIns, sparse, 2600.0, today)
        assertFalse(estimate.active)
        assertTrue(estimate.daysLogged < estimate.daysNeeded)
    }

    @Test
    fun blendsPartiallyWithFewerLoggedDays() {
        val (weighIns, intakes) = simulate(trueTdee = 2300.0, eats = 1800.0)
        // Two of every three days logged: 18 of 27, enough to start (needs 60%), not full trust (21).
        val partial = intakes.mapIndexed { i, day -> if (i % 3 != 2) day else day.copy(kcal = 0.0) }
        val estimate = AdaptiveTdee.estimate(weighIns, partial, 2700.0, today)
        assertTrue(estimate.active)
        assertTrue(estimate.confidence in 0.75..0.95)
        val blended = AdaptiveTdee.blend(2700.0, estimate)
        assertTrue(blended < 2700.0 && blended > estimate.measuredTdee!!)
    }

    @Test
    fun clampsImplausibleResults() {
        // Logs say 1,000 kcal a day but weight goes up: missing logs, not a 600 kcal metabolism.
        val weighIns = (28 downTo 1).map { WeighIn(today.minusDays(it.toLong()), 80.0 + (28 - it) * 0.05) }
        val intakes = (28 downTo 1).map { DayIntake(today.minusDays(it.toLong()), 1000.0) }
        val estimate = AdaptiveTdee.estimate(weighIns, intakes, 2400.0, today)
        assertEquals(1800, estimate.measuredTdee)
    }

    @Test
    fun scaleFlowsIntoTargets() {
        val profile = UserProfile(heightCm = 180.0, goalWeightKg = 80.0, weeklyRateKg = 0.5)
        val base = Nutrition.targets(profile, 90.0)
        val adjusted = Nutrition.targets(profile, 90.0, tdeeScale = 0.9)
        assertEquals((base.tdee * 0.9).toDouble(), adjusted.tdee.toDouble(), 2.0)
        assertTrue(adjusted.kcal < base.kcal)
        assertNull(Nutrition.targets(profile.copy(goalWeightKg = 90.0), 90.0, 0.9).weeksToGoal)
    }
}
