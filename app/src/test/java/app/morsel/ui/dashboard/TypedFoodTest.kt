package app.morsel.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TypedFoodTest {
    @Test
    fun caloriesAreNeededMacrosAreOptional() {
        assertEquals(TypedFood(450.0, 0.0, 0.0, 0.0), typedFood("450", "", "", ""))
        assertEquals(TypedFood(612.5, 30.0, 55.5, 20.0), typedFood(" 612,5 ", "30", "55.5", "20"))
        assertNull(typedFood("", "30", "", ""))
        assertNull(typedFood("0", "", "", ""))
        assertNull(typedFood("abc", "", "", ""))
        assertNull(typedFood("50000", "", "", ""))
        // A typo in a macro counts as nothing rather than blocking the log
        assertEquals(TypedFood(300.0, 0.0, 0.0, 0.0), typedFood("300", "x", "-5", ""))
    }
}
