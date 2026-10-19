package app.morsel.ui.result

import org.junit.Assert.assertEquals
import org.junit.Test

class GramsStepTest {
    @Test
    fun smallAmountsMoveInFives() {
        assertEquals(5.0, GramsStep.forGrams(0.0), 0.0)
        assertEquals(5.0, GramsStep.forGrams(49.9), 0.0)
    }

    @Test
    fun largerAmountsMoveInTens() {
        assertEquals(10.0, GramsStep.forGrams(50.0), 0.0)
        assertEquals(10.0, GramsStep.forGrams(180.0), 0.0)
        assertEquals(10.0, GramsStep.forGrams(5000.0), 0.0)
    }
}
