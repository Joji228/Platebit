package app.morsel.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FoodFactsParserTest {
    // Trimmed from a real Search-a-licious answer.
    private val body = """
        {"hits":[
          {"code":"2000000046692","brands":["Oreo"],"nutriments":{"carbohydrates_100g":69.5,"energy-kj_100g":1955,"fat_100g":18,"proteins_100g":5.1},"product_name":"oreo"},
          {"code":"9300650450271","brands":["Oreo"],"nutriments":{"carbohydrates_100g":70.2,"energy-kcal_100g":483.9,"fat_100g":20,"proteins_100g":4.2},"product_name":"Oreo strawberry"},
          {"code":"1","brands":["X"],"nutriments":{},"product_name":"No nutrition"},
          {"code":"2","nutriments":{"energy-kcal_100g":100},"product_name":""}
        ]}
    """.trimIndent()

    @Test
    fun keepsProductsWithNutritionAndConvertsKilojoules() {
        val facts = FoodFactsParser.parse(body)
        assertEquals(2, facts.size)
        assertEquals(467.3, facts[0].kcalPer100, 0.1) // 1955 kJ
        assertEquals(483.9, facts[1].kcalPer100, 0.01)
        assertEquals("Oreo", facts[0].brand)
        assertEquals("Oreo strawberry", facts[1].label)
    }

    @Test
    fun matchesOnlyWhenEveryWordFits() {
        val facts = FoodFactsParser.parse(body)
        assertEquals("Oreo strawberry", FoodFactsParser.bestMatch("Oreo Strawberry", facts)?.name)
        assertEquals("oreo", FoodFactsParser.bestMatch("Oreo", facts)?.name)
        assertNull(FoodFactsParser.bestMatch("Milka chocolate", facts))
    }

    @Test
    fun badAnswersGiveNoProducts() {
        assertEquals(emptyList<FoodFact>(), FoodFactsParser.parse("not json"))
        assertEquals(emptyList<FoodFact>(), FoodFactsParser.parse("""{"error":"x"}"""))
    }
}
