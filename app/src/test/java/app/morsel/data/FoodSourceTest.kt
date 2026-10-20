package app.morsel.data

import org.junit.Assert.assertEquals
import org.junit.Test

class FoodSourceTest {
    @Test
    fun marksTellDatabaseFromAi() {
        assertEquals(FoodSource.Marks(foodFacts = true, ai = false), FoodSource.marks("Open Food Facts · barcode"))
        assertEquals(FoodSource.Marks(foodFacts = true, ai = false), FoodSource.marks("Open Food Facts"))
        assertEquals(FoodSource.Marks(foodFacts = false, ai = true), FoodSource.marks("DeepSeek · deepseek-flash"))
        // An AI scan where some products were matched on Open Food Facts shows both
        assertEquals(FoodSource.Marks(foodFacts = true, ai = true), FoodSource.marks("DeepSeek · deepseek-flash", anyItemFromFoodFacts = true))
        assertEquals(FoodSource.Marks(foodFacts = true, ai = true), FoodSource.marks("DeepSeek · deepseek-flash + Open Food Facts"))
        assertEquals(FoodSource.Marks(foodFacts = true, ai = true), FoodSource.marks("AI coach + Open Food Facts"))
        assertEquals(FoodSource.Marks(foodFacts = false, ai = false), FoodSource.marks("Added by hand"))
    }

    @Test
    fun savedSourceRemembersDatabaseValues() {
        assertEquals("DeepSeek · deepseek-flash + Open Food Facts", FoodSource.withFoodFacts("DeepSeek · deepseek-flash", true))
        assertEquals("DeepSeek · deepseek-flash", FoodSource.withFoodFacts("DeepSeek · deepseek-flash", false))
        assertEquals("Open Food Facts · barcode", FoodSource.withFoodFacts("Open Food Facts · barcode", true))
        assertEquals(null, FoodSource.withFoodFacts(null, true))
    }
}
