package app.morsel.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.morsel.R

/** Nutrient colors stay the same in both themes so charts read consistently. */
object Nutrient {
    val Calories = Color(0xFFC5F25A)
    val Protein = Color(0xFFFF7A59)
    val Carbs = Color(0xFFFFC542)
    val Fat = Color(0xFFB48CFF)
    val Water = Color(0xFF4DA3FF)
}

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC5F25A),
    onPrimary = Color(0xFF0A0A0A),
    primaryContainer = Color(0xFF2A3313),
    onPrimaryContainer = Color(0xFFC5F25A),
    secondary = Color(0xFF8E8E93),
    onSecondary = Color(0xFF0A0A0A),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF5F5F7),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFF5F5F7),
    surfaceVariant = Color(0xFF1C1C1E),
    onSurfaceVariant = Color(0xFF8E8E93),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0E0E10),
    surfaceContainer = Color(0xFF141416),
    surfaceContainerHigh = Color(0xFF1C1C1E),
    surfaceContainerHighest = Color(0xFF26262A),
    outline = Color(0xFF3A3A3C),
    outlineVariant = Color(0xFF232326),
    error = Color(0xFFFF6B5E),
    onError = Color(0xFF0A0A0A),
    inverseSurface = Color(0xFFF5F5F7),
    inverseOnSurface = Color(0xFF111111),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF4C7A00),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE3F7B8),
    onPrimaryContainer = Color(0xFF2E4A00),
    secondary = Color(0xFF6C6C70),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF2F2F7),
    onBackground = Color(0xFF111111),
    surface = Color(0xFFF2F2F7),
    onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFE5E5EA),
    onSurfaceVariant = Color(0xFF6C6C70),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F7FA),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFEDEDF2),
    surfaceContainerHighest = Color(0xFFE3E3E8),
    outline = Color(0xFFC7C7CC),
    outlineVariant = Color(0xFFE5E5EA),
    error = Color(0xFFD7372B),
    onError = Color(0xFFFFFFFF),
)

private fun inter(weight: Int) = Font(
    R.font.inter,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Inter = FontFamily(inter(400), inter(500), inter(600), inter(700))

/** Tabular figures keep numbers from jumping around while they animate. */
val Numeric = TextStyle(fontFeatureSettings = "tnum")

private val MorselTypography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = Inter, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.02).em),
        displayMedium = displayMedium.copy(fontFamily = Inter, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.02).em),
        displaySmall = displaySmall.copy(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, letterSpacing = (-0.02).em),
        headlineLarge = headlineLarge.copy(fontFamily = Inter, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.015).em),
        headlineMedium = headlineMedium.copy(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, letterSpacing = (-0.015).em),
        headlineSmall = headlineSmall.copy(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, letterSpacing = (-0.01).em),
        titleLarge = titleLarge.copy(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
        titleMedium = titleMedium.copy(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
        titleSmall = titleSmall.copy(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 14.sp),
        // Inter is drawn with tight spacing already; Material's default tracking makes it look loose.
        bodyLarge = bodyLarge.copy(fontFamily = Inter, fontSize = 16.sp, letterSpacing = 0.sp),
        bodyMedium = bodyMedium.copy(fontFamily = Inter, fontSize = 14.sp, letterSpacing = 0.sp),
        bodySmall = bodySmall.copy(fontFamily = Inter, fontSize = 12.sp, letterSpacing = 0.sp),
        labelLarge = labelLarge.copy(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, letterSpacing = 0.sp),
        labelMedium = labelMedium.copy(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.sp),
        labelSmall = labelSmall.copy(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.sp),
    )
}

@Composable
fun MorselTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = MorselTypography,
        content = content,
    )
}
