package app.morsel.ui.theme

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AccentColorTest {
    @Test
    fun offersTenDistinctColors() {
        assertEquals(10, AccentColor.entries.size)
        assertEquals(10, AccentColor.entries.map { it.dark }.toSet().size)
        assertEquals(10, AccentColor.entries.map { it.light }.toSet().size)
    }

    @Test
    fun limeIsTheDefaultAndKeepsTheOriginalLook() {
        assertEquals(AccentColor.LIME, AccentColor.Default)
        assertEquals(0xFFC5F25A.toInt(), AccentColor.LIME.dark.toArgb())
    }

    @Test
    fun unknownOrMissingNamesFallBackToTheDefault() {
        assertEquals(AccentColor.Default, AccentColor.fromName(null))
        assertEquals(AccentColor.Default, AccentColor.fromName("MAGENTA"))
        assertEquals(AccentColor.ORANGE, AccentColor.fromName("ORANGE"))
    }

    @Test
    fun containersDifferFromTheAccent() {
        AccentColor.entries.forEach {
            assertNotEquals(it.dark, it.darkContainer)
            assertNotEquals(it.light, it.lightContainer)
        }
    }
}