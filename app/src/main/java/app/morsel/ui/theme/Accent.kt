package app.morsel.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * The accent colors offered in Settings. Each has a bright shade for the dark theme and a deeper
 * one for the light theme, so text and icons stay readable on either background.
 */
enum class AccentColor(val title: String, val dark: Color, val light: Color) {
    LIME("Lime", Color(0xFFC5F25A), Color(0xFF4C7A00)),
    GREEN("Green", Color(0xFF4ADE80), Color(0xFF15803D)),
    TEAL("Teal", Color(0xFF2DD4BF), Color(0xFF0F766E)),
    SKY("Sky", Color(0xFF38BDF8), Color(0xFF0369A1)),
    BLUE("Blue", Color(0xFF6C8CFF), Color(0xFF2F54D6)),
    PURPLE("Purple", Color(0xFFA78BFA), Color(0xFF6D4AD8)),
    PINK("Pink", Color(0xFFF472B6), Color(0xFFBE185D)),
    RED("Red", Color(0xFFFF5A6E), Color(0xFFC81E3A)),
    ORANGE("Orange", Color(0xFFFF9F43), Color(0xFFC2570C)),
    YELLOW("Yellow", Color(0xFFFACC15), Color(0xFFA16207)),
    ;

    /** Fill behind selected pills and the active tab: a quiet tint of the accent. */
    val darkContainer: Color get() = if (this == LIME) Color(0xFF2A3313) else lerp(Color.Black, dark, 0.2f)
    val lightContainer: Color get() = if (this == LIME) Color(0xFFE3F7B8) else lerp(Color.White, light, 0.16f)
    val onLightContainer: Color get() = if (this == LIME) Color(0xFF2E4A00) else lerp(light, Color.Black, 0.45f)

    companion object {
        val Default = LIME

        fun fromName(name: String?) = entries.firstOrNull { it.name == name } ?: Default
    }
}
