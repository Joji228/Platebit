package app.morsel.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyCompositionTest {
    private val man = UserProfile(sex = Sex.MALE, age = 30, heightCm = 180.0)
    private val woman = UserProfile(sex = Sex.FEMALE, age = 30, heightCm = 165.0)

    @Test
    fun typicalManLandsInHealthyRanges() {
        val c = BodyCompositionCalc.compute(85.0, 500, man)!!
        assertEquals(26.0, c.fatPct, 2.0)
        assertTrue(c.waterPct in 50.0..60.0)
        // Muscle mass the way scales report it (lean minus bone), and the stricter skeletal muscle
        assertTrue(c.muscleKg in 55.0..65.0)
        assertTrue(c.skeletalMuscleKg in 28.0..40.0)
        assertTrue(c.boneKg in 2.8..3.6)
        assertTrue(c.proteinPct in 12.0..20.0)
        assertEquals(85.0 - c.fatKg(85.0), c.leanKg, 0.001)
        assertEquals(c.leanKg - c.boneKg, c.muscleKg, 0.001)
    }

    @Test
    fun typicalWomanHasMoreFatThanAManOfTheSameBuild() {
        val w = BodyCompositionCalc.compute(65.0, 550, woman)!!
        assertTrue(w.fatPct in 25.0..37.0)
        assertTrue(w.waterPct in 45.0..55.0)
        assertTrue(w.boneKg in 1.8..2.8)
        val m = BodyCompositionCalc.compute(65.0, 550, man.copy(heightCm = 165.0))!!
        assertTrue(w.fatPct > m.fatPct)
    }

    @Test
    fun lowerImpedanceMeansMoreLeanMass() {
        val low = BodyCompositionCalc.compute(85.0, 400, man)!!
        val high = BodyCompositionCalc.compute(85.0, 650, man)!!
        // At the same weight, a lower resistance means more conductive (watery, lean) tissue.
        assertTrue(low.leanKg > high.leanKg)
        assertTrue(low.fatPct < high.fatPct)
    }

    @Test
    fun waterStaysPhysiological() {
        listOf(1, 50, 400, 700, 2999).forEach { z ->
            val c = BodyCompositionCalc.compute(70.0, z, man)!!
            val waterKg = c.waterPct * 70.0 / 100
            assertTrue(waterKg in c.leanKg * 0.65 - 1e-9..c.leanKg * 0.78 + 1e-9)
            assertTrue(c.fatPct in 3.0..60.0)
            assertTrue(c.skeletalMuscleKg < c.muscleKg)
            assertTrue(c.proteinPct >= 0)
        }
    }

    @Test
    fun boneThinsWithAgeFasterForWomen() {
        val young = BodyCompositionCalc.compute(65.0, 550, woman)!!
        val older = BodyCompositionCalc.compute(65.0, 550, woman.copy(age = 70))!!
        assertTrue(older.boneKg < young.boneKg)
    }

    @Test
    fun noImpedanceMeansNoComposition() {
        assertNull(BodyCompositionCalc.compute(80.0, null, man))
        assertNull(BodyCompositionCalc.compute(80.0, 0, man))
        val r = BodyAnalysis.report(80.0, null, man)
        assertNull(r.composition)
        assertNull(r.score)
        assertEquals(listOf(Metric.WEIGHT, Metric.BMI), r.readings.map { it.metric })
    }

    @Test
    fun cunBaeMatchesThePublishedEquation() {
        // Worked by hand from Gomez-Ambrosi 2012: man, 30, BMI 26.23 -> about 24.0 % fat
        assertEquals(24.0, BodyCompositionCalc.cunBaeFatPct(26.23, 30, male = true), 0.1)
        // A woman of the same BMI and age carries clearly more fat
        assertTrue(BodyCompositionCalc.cunBaeFatPct(26.23, 30, male = false) > 33.0)
    }

    @Test
    fun muscleAndBoneAlsoComeAsShares() {
        val c = BodyCompositionCalc.compute(85.0, 500, man)!!
        assertEquals(100 * c.muscleKg / 85.0, c.musclePct(85.0), 1e-9)
        val r = BodyAnalysis.report(85.0, 500, man)
        assertEquals(c.boneKg, r.value(Metric.BONE_MASS)!!, 1e-9)
        assertEquals(c.bonePct(85.0), r.value(Metric.BONE)!!, 1e-9)
    }

    @Test
    fun bmrComesFromLeanMass() {
        val c = BodyCompositionCalc.compute(85.0, 500, man)!!
        assertEquals((500 + 22 * c.leanKg + Nutrition.bmr(man, 85.0)) / 2, c.bmr, 0.001)
    }
}

class BodyAnalysisTest {
    private val man = UserProfile(sex = Sex.MALE, age = 30, heightCm = 180.0)

    @Test
    fun bmiAndHealthyWeight() {
        val r = BodyAnalysis.report(81.0, null, man)
        assertEquals(25.0, r.bmi, 0.01)
        assertEquals(59.94, r.healthyWeight.start, 0.01)
        assertEquals(80.68, r.healthyWeight.endInclusive, 0.01)
        assertEquals(71.28, r.idealWeightKg, 0.01)
        assertEquals("Overweight", r.readings.first { it.metric == Metric.BMI }.segment!!.label)
    }

    @Test
    fun visceralLevelIsCalibrated() {
        // Lean 30-year-old man, 11 kg of fat: around 5
        assertTrue(BodyAnalysis.visceralLevel(11.0, male = true, age = 30) in 4..6)
        // Obese 45-year-old man, 38 kg of fat: very high
        assertTrue(BodyAnalysis.visceralLevel(38.0, male = true, age = 45) >= 15)
        // Women store less around the organs at the same fat mass
        assertTrue(BodyAnalysis.visceralLevel(20.0, male = false, age = 30) < BodyAnalysis.visceralLevel(20.0, male = true, age = 30))
        assertEquals(1, BodyAnalysis.visceralLevel(0.0, male = true, age = 30))
    }

    @Test
    fun fatBandsFollowGallagher() {
        val young = BodyAnalysis.fatBand(male = true, age = 30)
        assertEquals("Healthy", young.segmentOf(15.0).label)
        assertEquals("High", young.segmentOf(22.0).label)
        assertEquals("Very high", young.segmentOf(30.0).label)
        assertEquals("Healthy", BodyAnalysis.fatBand(male = false, age = 30).segmentOf(30.0).label)
        assertEquals("Healthy", BodyAnalysis.fatBand(male = true, age = 65).segmentOf(24.0).label)
    }

    @Test
    fun bodyAgeTracksFatAndMuscle() {
        val ref = BodyAnalysis.skeletalReference(male = true, age = 30)
        assertTrue(BodyAnalysis.bodyAge(12.0, ref + 4, male = true, age = 30) < 30)
        assertTrue(BodyAnalysis.bodyAge(30.0, ref - 4, male = true, age = 30) > 30)
        assertEquals(45, BodyAnalysis.bodyAge(60.0, ref - 20, male = true, age = 30)) // capped at +15
    }

    @Test
    fun fullReportForAScaleWeighIn() {
        val r = BodyAnalysis.report(85.0, 500, man)
        assertTrue(r.composition != null)
        assertTrue(r.score!! in 30..100)
        assertTrue(r.visceral!! in 1..59)
        assertTrue(r.bodyAge!! in 15..45)
        assertEquals(15, r.readings.size)
        // 26 % fat is "very high" for a 30-year-old man, and the body type says so
        assertEquals(2, r.type!!.fat)
    }

    @Test
    fun leanerMeansBetterScore() {
        val lean = BodyAnalysis.report(75.0, 430, man).score!!
        val heavy = BodyAnalysis.report(105.0, 520, man).score!!
        assertTrue(lean > heavy)
    }

    @Test
    fun bodyTypeIsDescribedInPlainWords() {
        assertEquals("Body fat above the healthy range, typical muscle.", BodyType.of(2, 1).summary)
        assertEquals("Body fat in the healthy range, more muscle than typical.", BodyType.of(1, 2).summary)
        assertNull(BodyType.of(1, 1).tip)
        assertTrue(BodyType.of(2, 0).tip!!.contains("fat"))
    }
}
