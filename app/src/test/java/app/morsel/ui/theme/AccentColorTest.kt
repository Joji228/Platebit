package app.morsel.ui.theme

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AccentColorTest {
    @Test
    fun offersFiveDistinctColors() {
        assertEquals(5, AccentColor.entries.size)
        assertEquals(5, AccentColor.entries.map { it.dark }.toSet().size)
        assertEquals(5, AccentColor.entries.map { it.light }.toSet().size)
    }

    @Test
    fun limeIsTheDefaultAndKeepsTheOriginalLook() {
        assertEquals(AccentColor.LIME, AccentColor.Default)
        assertEquals(0xFFC5F25A.toInt(), AccentColor.LIME.dark.toArgb())
    }

    @Test
    fun unknownOrMissingNamesFallBackToTheDefault() {
        assertEquals(AccentColor.Default, AccentColor.fromName(null))
        assertEquals(AccentColor.Default, AccentColor.fromName("ORANGE"))
        assertEquals(AccentColor.PINK, AccentColor.fromName("PINK"))
    }

    @Test
    fun containersDifferFromTheAccent() {
        AccentColor.entries.forEach {
            assertNotEquals(it.dark, it.darkContainer)
            assertNotEquals(it.light, it.lightContainer)
        }
    }
}