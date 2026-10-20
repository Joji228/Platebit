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

    @Test
    fun readsABarcodeLookup() {
        val body = """{"code":"3017620422003","product":{"brands":"Nutella, Ferrero","nutriments":{"energy-kcal_100g":539,"proteins_100g":6.3,"carbohydrates_100g":57.5,"fat_100g":30.9},"product_name":"Nutella","serving_quantity":15},"status":1}"""
        val fact = FoodFactsParser.parseProduct(body, "3017620422003")!!
        assertEquals("Nutella", fact.name)
        assertEquals("Nutella", fact.brand)
        assertEquals("Nutella", fact.label)
        assertEquals(539.0, fact.kcalPer100, 0.0)
        assertEquals(15.0, fact.servingGrams!!, 0.0)
        assertNull(FoodFactsParser.parseProduct("""{"status":0,"status_verbose":"product not found"}""", "1"))
    }

    @Test
    fun snackCategoriesAreRecognized() {
        val body = """{"hits":[
          {"code":"1","product_name":"Kit Kat","brands":["Nestle"],"categories_tags":["en:snacks","en:sweet-snacks","en:chocolates"],"nutriments":{"energy-kcal_100g":518}},
          {"code":"2","product_name":"Penne","brands":["Barilla"],"categories_tags":["en:pastas","en:dry-pastas"],"nutriments":{"energy-kcal_100g":359}},
          {"code":"3","product_name":"Water","nutriments":{"energy-kcal_100g":1}}
        ]}"""
        val facts = FoodFactsParser.parse(body)
        assertEquals(listOf(true, false, false), facts.map { it.snack })
    }

    @Test
    fun uncategorizedProductsFollowTheirBrand() {
        val body = """{"hits":[
          {"code":"1","product_name":"Kit Kat Chunky","brands":["Kit Kat"],"categories_tags":["en:snacks","en:chocolates"],"nutriments":{"energy-kcal_100g":518}},
          {"code":"2","product_name":"Kit Kat","brands":["Kit Kat"],"nutriments":{"energy-kcal_100g":500}},
          {"code":"3","product_name":"Penne","brands":["Barilla"],"nutriments":{"energy-kcal_100g":359}}
        ]}"""
        assertEquals(listOf(true, true, false), FoodFactsParser.parse(body).map { it.snack })
    }

    @Test
    fun productsSoldInGreeceComeFirst() {
        val body = """{"hits":[
          {"code":"1","product_name":"Lacta intense 70%","brands":["Lacta"],"countries_tags":["en:switzerland"],"nutriments":{"energy-kcal_100g":548}},
          {"code":"2","product_name":"Lacta","brands":["Lacta"],"countries_tags":["en:greece"],"nutriments":{"energy-kcal_100g":526}},
          {"code":"3","product_name":"Lacta Banoffee","brands":["Lacta"],"countries_tags":["en:serbia"],"nutriments":{"energy-kcal_100g":531}},
          {"code":"4","product_name":"lacta Αμύγδαλο","brands":["lacta"],"countries_tags":["en:greece","en:cyprus"],"nutriments":{"energy-kcal_100g":536}}
        ]}"""
        val facts = FoodFactsParser.parse(body)
        assertEquals(listOf(false, true, false, true), facts.map { it.local })
        // Greek ones first, each group keeping Open Food Facts' own order
        assertEquals(listOf("2", "4", "1", "3"), FoodFactsParser.localFirst(facts).map { it.code })
    }
}
