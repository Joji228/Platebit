package app.morsel.ui.body

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import app.morsel.domain.Metric

/**
 * Small line icons for the body metrics, drawn here as SVG-style paths on a 24-unit grid with a
 * 2-unit rounded stroke, so they match each other and tint like any other icon.
 */
object MetricIcons {
    private fun icon(name: String, vararg paths: String): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        paths.forEach { d ->
            addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()

    private const val CIRCLE_8 = "M12 12m-8 0a8 8 0 1 0 16 0a8 8 0 1 0 -16 0"
    private const val DROP = "M12 3c3 4 6 7.5 6 11a6 6 0 0 1 -12 0c0 -3.5 3 -7 6 -11z"
    private const val HEAD = "M12 6m-2.5 0a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0"

    /** A bathroom scale with its dial. */
    val Weight = icon("Weight", "M6 4h12a2 2 0 0 1 2 2v12a2 2 0 0 1 -2 2h-12a2 2 0 0 1 -2 -2v-12a2 2 0 0 1 2 -2z", "M8.5 11a3.5 3.5 0 0 1 7 0", "M12 11l1.8 -2")

    /** A ruler, for height against weight. */
    val Bmi = icon("Bmi", "M4 16l12 -12l4 4l-12 12z", "M8 12l1.5 1.5", "M11 9l1.5 1.5", "M14 6l1.5 1.5")

    /** A drop of fat. */
    val Fat = icon("Fat", DROP)

    /** A drop with a weight line through it: fat in kilograms. */
    val FatMass = icon("FatMass", DROP, "M9 15h6")

    /** A dumbbell. */
    val Muscle = icon("Muscle", "M6.5 7v10", "M17.5 7v10", "M3.5 10v4", "M20.5 10v4", "M6.5 12h11")

    /** A figure mid-stride: the muscles that move you. */
    val Skeletal = icon("Skeletal", "M13 5m-2 0a2 2 0 1 0 4 0a2 2 0 1 0 -4 0", "M12 9l-2 5l3 2l-1 4", "M10 14l-3 5", "M12 9l3 3h3", "M12 9l-3 1l-2 3")

    /** A drop with a ripple. */
    val Water = icon("Water", DROP, "M9 14.5c1 0.8 2 0.8 3 0s2 -0.8 3 0")

    /** A hexagon molecule. */
    val Protein = icon("Protein", "M12 3l7.8 4.5v9l-7.8 4.5l-7.8 -4.5v-9z", "M12 12m-2.5 0a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0")

    /** A bone. */
    val Bone = icon(
        "Bone",
        "M9.5 11.5l3 3", "M11.5 9.5l3 3",
        "M7.5 9.5m-2 0a2 2 0 1 0 4 0a2 2 0 1 0 -4 0", "M9.5 7.5m-2 0a2 2 0 1 0 4 0a2 2 0 1 0 -4 0",
        "M14.5 16.5m-2 0a2 2 0 1 0 4 0a2 2 0 1 0 -4 0", "M16.5 14.5m-2 0a2 2 0 1 0 4 0a2 2 0 1 0 -4 0",
    )

    /** A target over the belly: fat around the organs. */
    val Visceral = icon("Visceral", CIRCLE_8, "M12 12m-3.5 0a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0 -7 0", "M12 12h0.01")

    /** A person's outline: everything that isn't fat. */
    val Lean = icon("Lean", HEAD, "M5.5 20c0 -4 2.9 -7 6.5 -7s6.5 3 6.5 7")

    /** A flame: calories burned at rest. */
    val Bmr = icon("Bmr", "M12 3c1 3.5 5.5 5.5 5.5 10.5a5.5 5.5 0 0 1 -11 0c0 -2.2 1 -3.8 2.2 -5c0 2 1 3.2 2.3 3.2c0 -3 -1.2 -5.2 1 -8.7z")

    /** A clock. */
    val BodyAge = icon("BodyAge", CIRCLE_8, "M12 8v4l3 2")

    fun of(metric: Metric): ImageVector = when (metric) {
        Metric.WEIGHT -> Weight
        Metric.BMI -> Bmi
        Metric.FAT -> Fat
        Metric.FAT_MASS -> FatMass
        Metric.MUSCLE, Metric.MUSCLE_MASS -> Muscle
        Metric.SKELETAL -> Skeletal
        Metric.WATER -> Water
        Metric.PROTEIN -> Protein
        Metric.BONE, Metric.BONE_MASS -> Bone
        Metric.VISCERAL -> Visceral
        Metric.LEAN -> Lean
        Metric.BMR -> Bmr
        Metric.BODY_AGE -> BodyAge
    }

    /** Each metric keeps its own colour, so the list is quick to scan. */
    fun tint(metric: Metric): Color = when (metric) {
        Metric.WEIGHT, Metric.BMI -> Color(0xFF6C8CFF)
        Metric.FAT, Metric.FAT_MASS -> Color(0xFFFFB44D)
        Metric.MUSCLE, Metric.MUSCLE_MASS, Metric.SKELETAL -> Color(0xFFFF7A59)
        Metric.WATER -> Color(0xFF4DA3FF)
        Metric.PROTEIN -> Color(0xFFB48CFF)
        Metric.BONE, Metric.BONE_MASS -> Color(0xFFD8D2C4)
        Metric.VISCERAL -> Color(0xFFFF6B8B)
        Metric.LEAN -> Color(0xFF4CD48A)
        Metric.BMR -> Color(0xFFFF9F43)
        Metric.BODY_AGE -> Color(0xFF5AC8C8)
    }
}
