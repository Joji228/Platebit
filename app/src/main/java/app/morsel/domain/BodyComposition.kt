package app.morsel.domain

import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Body composition estimated from a bio-impedance reading. A scale measures only weight and one
 * impedance number; everything here is an estimate, so the trend is more trustworthy than any
 * single value, and the numbers won't match another app's exactly.
 */
data class BodyComposition(
    val fatPct: Double,
    val waterPct: Double,
    /** Muscle mass the way bathroom scales report it: everything lean except bone. */
    val muscleKg: Double,
    val boneKg: Double,
    /** Everything that isn't fat. */
    val leanKg: Double,
    val proteinPct: Double,
    /** Resting calories: Cunningham (from lean mass) averaged with Mifflin-St Jeor (from size and age). */
    val bmr: Double,
    /** Skeletal (movement) muscle only, a stricter and much smaller number than [muscleKg]. */
    val skeletalMuscleKg: Double,
) {
    fun fatKg(weightKg: Double) = weightKg * fatPct / 100

    fun musclePct(weightKg: Double) = 100 * muscleKg / weightKg

    fun bonePct(weightKg: Double) = 100 * boneKg / weightKg
}

/**
 * Platebit's own estimate, built from published equations rather than any vendor's formula.
 *
 * Foot-to-foot impedance is noisy (how hydrated you are, how damp your feet are), so instead of
 * trusting one equation each value blends an impedance-based estimate with one from height,
 * weight, age and sex alone, then checks the parts against each other:
 *
 * - Lean mass: 75 % from impedance, the mean of Sun et al. 2003 (NHANES, hand-to-foot) and a
 *   foot-to-foot equation fitted against DXA (Taiwan 2015, Nutr J 14:65), the electrode layout
 *   a bathroom scale actually uses; 25 % from CUN-BAE (Gomez-Ambrosi 2012, BMI, age, sex).
 * - Resting calories: Cunningham 1980 from lean mass, averaged with Mifflin-St Jeor 1990 so an
 *   off impedance reading can only move it half as much.
 * - Water: mean of Sun et al. 2003 (impedance), Watson 1980 (anthropometric) and the 73.2 %
 *   hydration of lean tissue, kept within the 65-78 % of lean mass that's physiologically possible.
 * - Bone mineral: about 5.2 % of lean mass, falling slowly after 40 (faster for women).
 * - Protein: lean mass minus water minus all mineral (bone plus about 20 % more in soft tissue).
 * - Skeletal muscle: Janssen et al. 2000.
 */
object BodyCompositionCalc {
    private const val IMPEDANCE_SHARE = 0.75
    private const val LEAN_HYDRATION = 0.732
    private const val SOFT_TISSUE_MINERAL = 1.2

    /** Null when the inputs can't give a sensible answer. */
    fun compute(weightKg: Double, impedance: Int?, profile: UserProfile): BodyComposition? {
        if (impedance == null || impedance <= 0 || weightKg <= 0 || profile.heightCm <= 0) return null
        val male = profile.sex == Sex.MALE
        val age = profile.age
        val r = impedance.toDouble()
        val h = profile.heightCm
        val index = h * h / r // height squared over resistance: the part of the signal that tracks lean tissue

        val leanSun = if (male) {
            -10.678 + 0.262 * weightKg + 0.652 * index + 0.015 * r
        } else {
            -9.529 + 0.168 * weightKg + 0.696 * index + 0.016 * r
        }
        val leanFootToFoot = 13.055 + 0.204 * weightKg + 0.394 * index - 0.136 * age + if (male) 8.125 else 0.0
        val leanFromImpedance = (leanSun + leanFootToFoot) / 2
        val leanFromBuild = weightKg * (1 - cunBaeFatPct(Nutrition.bmi(weightKg, h), age, male).coerceIn(3.0, 60.0) / 100)
        val lean = (IMPEDANCE_SHARE * leanFromImpedance + (1 - IMPEDANCE_SHARE) * leanFromBuild)
            .coerceIn(weightKg * 0.40, weightKg * 0.97)

        val waterFromImpedance = if (male) 1.203 + 0.449 * index + 0.176 * weightKg else 3.747 + 0.450 * index + 0.113 * weightKg
        val waterFromBuild = if (male) {
            2.447 - 0.09156 * age + 0.1074 * h + 0.3362 * weightKg
        } else {
            -2.097 + 0.1069 * h + 0.2466 * weightKg
        }
        val water = ((waterFromImpedance + waterFromBuild + LEAN_HYDRATION * lean) / 3).coerceIn(lean * 0.65, lean * 0.78)

        val bone = lean * 0.052 * boneAgeFactor(male, age)
        val protein = max(0.0, lean - water - bone * SOFT_TISSUE_MINERAL)
        val skeletal = (0.401 * index + (if (male) 3.825 else 0.0) - 0.071 * age + 5.102)
            .coerceIn(weightKg * 0.10, (lean - bone) * 0.75)

        return BodyComposition(
            fatPct = (100 * (weightKg - lean) / weightKg).coerceIn(3.0, 60.0),
            waterPct = 100 * water / weightKg,
            muscleKg = lean - bone,
            boneKg = bone,
            leanKg = lean,
            proteinPct = 100 * protein / weightKg,
            bmr = (500 + 22 * lean + Nutrition.bmr(profile, weightKg)) / 2,
            skeletalMuscleKg = skeletal,
        )
    }

    /** CUN-BAE body fat % from BMI, age and sex (Gomez-Ambrosi et al., Diabetes Care 2012). */
    fun cunBaeFatPct(bmi: Double, age: Int, male: Boolean): Double {
        val s = if (male) 0.0 else 1.0
        val a = age.toDouble()
        val b2 = bmi * bmi
        return -44.988 + 0.503 * a + 10.689 * s + 3.172 * bmi - 0.026 * b2 + 0.181 * bmi * s -
            0.02 * bmi * a - 0.005 * b2 * s + 0.00021 * b2 * a
    }

    private fun boneAgeFactor(male: Boolean, age: Int): Double {
        val yearsPast = (age - if (male) 40 else 45).coerceAtLeast(0)
        return (1 - yearsPast * if (male) 0.003 else 0.006).coerceAtLeast(0.75)
    }
}

/** How a value sits against its healthy range. */
enum class Tone { LOW, GOOD, HIGH, VERY_HIGH }

/** One coloured stretch of a range bar, running up to [upTo]. */
data class Segment(val label: String, val upTo: Double, val tone: Tone)

/** A value's healthy-range bar from [min] to the last segment's end. */
data class Band(val min: Double, val segments: List<Segment>) {
    val max get() = segments.last().upTo

    fun segmentOf(value: Double): Segment = segments.firstOrNull { value < it.upTo } ?: segments.last()

    /** Where the value sits along the bar, 0..1. */
    fun position(value: Double) = ((value - min) / (max - min)).coerceIn(0.0, 1.0)

    /** The same ranges in kilograms turned into % of [weightKg]. */
    fun perWeight(weightKg: Double) = Band(100 * min / weightKg, segments.map { it.copy(upTo = 100 * it.upTo / weightKg) })
}

enum class Metric(val title: String, val unit: String, val decimals: Int, val about: String) {
    WEIGHT("Weight", "kg", 1, "Your weigh-in. Day-to-day swings of 1-2 kg are mostly water and food, so watch the trend line."),
    BMI("BMI", "", 1, "Weight relative to height. Quick to work out, but it can't tell muscle from fat."),
    FAT("Body fat", "%", 1, "How much of your weight is fat. The healthy range rises a little with age."),
    FAT_MASS("Fat mass", "kg", 1, "Your body fat in kilograms."),
    MUSCLE("Muscle", "%", 1, "How much of your weight is muscle, counting the water held inside it, the way bathroom scales measure it. Strength training and enough protein raise it."),
    MUSCLE_MASS("Muscle mass", "kg", 1, "Your muscle in kilograms."),
    SKELETAL("Skeletal muscle", "kg", 1, "Only the muscles that move you. Used in the background for the body score."),
    WATER("Water", "%", 1, "How much of your weight is water. It follows muscle, so more muscle usually means more water."),
    PROTEIN("Protein", "%", 1, "The protein your muscles and organs are built from, as a share of your weight."),
    BONE("Bone", "%", 1, "How much of your weight is bone mineral. It changes very slowly, so short-term moves are just estimate noise."),
    BONE_MASS("Bone mass", "kg", 1, "Your bone mineral in kilograms."),
    VISCERAL("Visceral fat", "", 0, "Fat around the organs in your belly, the kind most linked to health risk. Our own index: 1-9 is normal."),
    LEAN("Lean mass", "kg", 1, "Everything that isn't fat: muscle, bone, organs and water."),
    BMR("BMR", "kcal", 0, "What your body burns a day at complete rest, before any movement or exercise."),
    BODY_AGE("Body age", "yrs", 0, "How your fat and muscle compare with typical people your age. Lower than your age is better."),
}

/**
 * Where you sit on body fat (0 low, 1 healthy, 2 high for your age and sex) against muscle
 * (0 less than typical, 1 typical, 2 more), described in plain words rather than a label.
 */
data class BodyType(val fat: Int, val muscle: Int) {
    val summary get() = "${FAT_TEXT[fat]}, ${MUSCLE_TEXT[muscle]}."

    /** What would raise the score most, or null when both are where they should be. */
    val tip: String?
        get() = when {
            fat == 2 && muscle == 0 -> "Losing fat while building muscle would help most."
            fat == 2 -> "Losing some fat would help most; keep your muscle while you do."
            muscle == 0 -> "Building some muscle would help most."
            fat == 0 -> "Your body fat is lower than the healthy range; avoid losing more."
            else -> null
        }

    companion object {
        private val FAT_TEXT = listOf("Body fat below the healthy range", "Body fat in the healthy range", "Body fat above the healthy range")
        private val MUSCLE_TEXT = listOf("less muscle than typical", "typical muscle", "more muscle than typical")

        fun of(fat: Int, muscle: Int) = BodyType(fat.coerceIn(0, 2), muscle.coerceIn(0, 2))
    }
}

data class Reading(val metric: Metric, val value: Double, val band: Band?) {
    val segment get() = band?.segmentOf(value)
}

/** Everything the Body screen shows for one weigh-in. */
data class BodyReport(
    val weightKg: Double,
    val bmi: Double,
    val healthyWeight: ClosedFloatingPointRange<Double>,
    val idealWeightKg: Double,
    /** Null for weigh-ins without impedance (manual entries, or shoes on). */
    val composition: BodyComposition?,
    val visceral: Int?,
    val bodyAge: Int?,
    val score: Int?,
    val type: BodyType?,
    val readings: List<Reading>,
) {
    fun value(metric: Metric): Double? = readings.firstOrNull { it.metric == metric }?.value
}

object BodyAnalysis {
    private const val IDEAL_BMI = 22.0

    fun report(weightKg: Double, impedance: Int?, profile: UserProfile): BodyReport {
        val male = profile.sex == Sex.MALE
        val age = profile.age
        val m2 = (profile.heightCm / 100).pow(2)
        val bmi = weightKg / m2
        val c = BodyCompositionCalc.compute(weightKg, impedance, profile)

        val readings = mutableListOf(
            Reading(Metric.WEIGHT, weightKg, weightBand(m2)),
            Reading(Metric.BMI, bmi, BMI_BAND),
        )
        var visceral: Int? = null
        var bodyAge: Int? = null
        var score: Int? = null
        var type: BodyType? = null
        if (c != null) {
            val fatBand = fatBand(male, age)
            val muscleRef = skeletalReference(male, age)
            val skeletalPct = 100 * c.skeletalMuscleKg / weightKg
            visceral = visceralLevel(c.fatKg(weightKg), male, age)
            bodyAge = bodyAge(c.fatPct, skeletalPct, male, age)
            type = BodyType.of(fatClass(c.fatPct, fatBand), muscleClass(skeletalPct, muscleRef))
            score = score(bmi, c, fatBand, visceral, skeletalPct, muscleRef, male)
            readings += listOf(
                Reading(Metric.FAT, c.fatPct, fatBand),
                Reading(Metric.FAT_MASS, c.fatKg(weightKg), null),
                Reading(Metric.MUSCLE, c.musclePct(weightKg), muscleMassBand(weightKg, muscleRef, c.muscleKg / c.skeletalMuscleKg).perWeight(weightKg)),
                Reading(Metric.MUSCLE_MASS, c.muscleKg, null),
                Reading(Metric.SKELETAL, c.skeletalMuscleKg, skeletalBand(weightKg, muscleRef)),
                Reading(Metric.WATER, c.waterPct, waterBand(male)),
                Reading(Metric.PROTEIN, c.proteinPct, PROTEIN_BAND),
                Reading(Metric.BONE, c.bonePct(weightKg), boneBand(male, weightKg).perWeight(weightKg)),
                Reading(Metric.BONE_MASS, c.boneKg, null),
                Reading(Metric.VISCERAL, visceral.toDouble(), VISCERAL_BAND),
                Reading(Metric.LEAN, c.leanKg, null),
                Reading(Metric.BMR, c.bmr, bmrBand(Nutrition.bmr(profile, weightKg))),
                Reading(Metric.BODY_AGE, bodyAge.toDouble(), bodyAgeBand(age)),
            )
        }
        return BodyReport(
            weightKg = weightKg,
            bmi = bmi,
            healthyWeight = 18.5 * m2..24.9 * m2,
            idealWeightKg = IDEAL_BMI * m2,
            composition = c,
            visceral = visceral,
            bodyAge = bodyAge,
            score = score,
            type = type,
            readings = readings,
        )
    }

    // ---- Our own indices -------------------------------------------------------------------

    /**
     * Visceral fat level, 1-59 like bathroom scales use. There's no published way to get it from
     * weight and impedance alone, so this is our own estimate: the share of body fat stored
     * around the organs grows with age and is about twice as high in men (DXA studies put it at
     * roughly 10-20 % of fat in men and 5-10 % in women), and each level is 0.2 kg of it.
     * Calibrated so a lean 30-year-old lands around 5 and obesity in middle age goes past 15.
     */
    fun visceralLevel(fatKg: Double, male: Boolean, age: Int): Int {
        val years = (age - 20).coerceAtLeast(0)
        val share = if (male) 0.08 + 0.0018 * years else 0.04 + 0.0014 * years
        return (fatKg * share.coerceAtMost(0.22) / 0.2).roundToInt().coerceIn(1, 59)
    }

    /**
     * Body age: your age, moved half a year per point of body fat away from the middle of the
     * healthy range for your age, and 0.4 years per point of skeletal muscle above or below
     * typical. Kept within 15 years of your real age.
     */
    fun bodyAge(fatPct: Double, skeletalPct: Double, male: Boolean, age: Int): Int {
        val healthy = fatBand(male, age).segments.first { it.tone == Tone.GOOD }
        val healthyLow = fatBand(male, age).segments.first().upTo
        val mid = (healthyLow + healthy.upTo) / 2
        val shift = 0.5 * (fatPct - mid) - 0.4 * (skeletalPct - skeletalReference(male, age))
        return (age + shift).roundToInt().coerceIn(max(18, age - 15), age + 15)
    }

    /** 0-100: starts at 100 and loses points for each value outside its healthy range. */
    fun score(bmi: Double, c: BodyComposition, fat: Band, visceral: Int, skeletalPct: Double, muscleRef: Double, male: Boolean): Int {
        val fatLow = fat.segments.first().upTo
        val fatHigh = fat.segments.first { it.tone == Tone.GOOD }.upTo
        val waterLow = if (male) 50.0 else 45.0
        var penalty = 0.0
        penalty += when {
            bmi < 18.5 -> (18.5 - bmi) * 4
            bmi >= 25 -> (bmi - 25) * 4
            else -> 0.0
        }.coerceAtMost(25.0)
        penalty += when {
            c.fatPct < fatLow -> (fatLow - c.fatPct) * 2
            c.fatPct > fatHigh -> (c.fatPct - fatHigh) * 3.5
            else -> 0.0
        }.coerceAtMost(40.0)
        penalty += ((visceral - 9) * 2.0).coerceIn(0.0, 20.0)
        penalty += ((muscleRef - 3 - skeletalPct) * 1.5).coerceIn(0.0, 15.0)
        penalty += (waterLow - c.waterPct).coerceIn(0.0, 10.0)
        return (100 - penalty).roundToInt().coerceIn(30, 100)
    }

    /** Typical skeletal muscle as % of weight for a healthy adult (Janssen 2000), falling with age. */
    fun skeletalReference(male: Boolean, age: Int): Double {
        val years = (age - 25).coerceAtLeast(0)
        return if (male) 40.0 - 0.1 * years else 31.0 - 0.08 * years
    }

    private fun fatClass(fatPct: Double, band: Band) = when (band.segmentOf(fatPct).tone) {
        Tone.LOW -> 0
        Tone.GOOD -> 1
        else -> 2
    }

    private fun muscleClass(skeletalPct: Double, ref: Double) = when {
        skeletalPct < ref - 3 -> 0
        skeletalPct > ref + 3 -> 2
        else -> 1
    }

    // ---- Healthy ranges ----------------------------------------------------------------------

    private val BMI_BAND = Band(
        14.0,
        listOf(
            Segment("Underweight", 18.5, Tone.LOW),
            Segment("Normal", 25.0, Tone.GOOD),
            Segment("Overweight", 30.0, Tone.HIGH),
            Segment("Obese", 40.0, Tone.VERY_HIGH),
        ),
    )

    private fun weightBand(m2: Double) = Band(
        14.0 * m2,
        listOf(
            Segment("Under", 18.5 * m2, Tone.LOW),
            Segment("Healthy", 25.0 * m2, Tone.GOOD),
            Segment("Over", 30.0 * m2, Tone.HIGH),
            Segment("Obese", 40.0 * m2, Tone.VERY_HIGH),
        ),
    )

    /** Gallagher et al. 2000, healthy body fat by sex and age. */
    fun fatBand(male: Boolean, age: Int): Band {
        val cuts = when {
            male && age < 40 -> listOf(8.0, 21.0, 26.0)
            male && age < 60 -> listOf(11.0, 23.0, 29.0)
            male -> listOf(13.0, 25.0, 31.0)
            age < 40 -> listOf(21.0, 33.0, 39.0)
            age < 60 -> listOf(23.0, 35.0, 41.0)
            else -> listOf(25.0, 38.0, 43.0)
        }
        return Band(
            0.0,
            listOf(
                Segment("Low", cuts[0], Tone.LOW),
                Segment("Healthy", cuts[1], Tone.GOOD),
                Segment("High", cuts[2], Tone.HIGH),
                Segment("Very high", 55.0, Tone.VERY_HIGH),
            ),
        )
    }

    private fun waterBand(male: Boolean): Band {
        val (low, high) = if (male) 50.0 to 65.0 else 45.0 to 60.0
        return Band(
            30.0,
            listOf(Segment("Low", low, Tone.LOW), Segment("Healthy", high, Tone.GOOD), Segment("High", 75.0, Tone.HIGH)),
        )
    }

    private fun skeletalBand(weightKg: Double, ref: Double) = Band(
        weightKg * (ref - 15) / 100,
        listOf(
            Segment("Low", weightKg * (ref - 3) / 100, Tone.LOW),
            Segment("Normal", weightKg * (ref + 3) / 100, Tone.GOOD),
            Segment("High", weightKg * (ref + 12) / 100, Tone.GOOD),
        ),
    )

    /**
     * Muscle mass and skeletal muscle rise and fall together, so muscle mass uses the skeletal
     * muscle range scaled by your own ratio between the two; the two rows always agree.
     */
    private fun muscleMassBand(weightKg: Double, ref: Double, ratio: Double): Band {
        val k = ratio * weightKg / 100
        return Band(
            k * (ref - 15),
            listOf(Segment("Low", k * (ref - 3), Tone.LOW), Segment("Normal", k * (ref + 3), Tone.GOOD), Segment("High", k * (ref + 12), Tone.GOOD)),
        )
    }

    private val PROTEIN_BAND = Band(
        10.0,
        listOf(Segment("Low", 13.0, Tone.LOW), Segment("Healthy", 20.0, Tone.GOOD), Segment("High", 25.0, Tone.GOOD)),
    )

    /** Typical bone mineral for the body weight; within 10 % either way counts as normal. */
    private fun boneBand(male: Boolean, weightKg: Double): Band {
        val ref = if (male) {
            when {
                weightKg < 60 -> 2.5
                weightKg < 75 -> 2.9
                else -> 3.2
            }
        } else {
            when {
                weightKg < 45 -> 1.8
                weightKg < 60 -> 2.2
                else -> 2.5
            }
        }
        return Band(
            ref * 0.6,
            listOf(Segment("Low", ref * 0.9, Tone.LOW), Segment("Normal", ref * 1.1, Tone.GOOD), Segment("High", ref * 1.4, Tone.GOOD)),
        )
    }

    private val VISCERAL_BAND = Band(
        1.0,
        listOf(Segment("Normal", 10.0, Tone.GOOD), Segment("High", 15.0, Tone.HIGH), Segment("Very high", 30.0, Tone.VERY_HIGH)),
    )

    /** Compared with the formula estimate for someone of your size and age (Mifflin-St Jeor). */
    private fun bmrBand(typical: Double) = Band(
        typical * 0.7,
        listOf(Segment("Below typical", typical * 0.9, Tone.LOW), Segment("Typical", typical * 1.3, Tone.GOOD)),
    )

    private fun bodyAgeBand(age: Int) = Band(
        (age - 15).toDouble(),
        listOf(Segment("Younger", age + 0.5, Tone.GOOD), Segment("Older", (age + 15).toDouble(), Tone.HIGH)),
    )
}
