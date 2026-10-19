package app.morsel.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * The five accent colors offered in Settings. A saved color that is no longer offered falls back to Lime. Each has a bright shade for the dark theme and a deeper
 * one for the light theme, so text and icons stay readable on either background.
 */
enum class AccentColor(val title: String, val dark: Color, val light: Color) {
    LIME("Lime", Color(0xFFC5F25A), Color(0xFF4C7A00)),
    BLUE("Blue", Color(0xFF6C8CFF), Color(0xFF2F54D6)),
    PURPLE("Purple", Color(0xFFA78BFA), Color(0xFF6D4AD8)),
    PINK("Pink", Color(0xFFF472B6), Color(0xFFBE185D)),
    RED("Red", Color(0xFFFF5A6E), Color(0xFFC81E3A)),
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
