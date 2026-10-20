package app.morsel.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CoachLookupTest {
    @Test
    fun readsTheSearchesTheCoachAskedFor() {
        assertEquals(listOf("lacta milk chocolate"), CoachLookup.queries("<lookup>lacta milk chocolate</lookup>"))
        assertEquals(
            listOf("Fage Total 2%", "Coca-Cola Zero", "Λακτα αμύγδαλο"),
            CoachLookup.queries("<lookup>\n- Fage Total 2%\n\"Coca-Cola Zero\"; Λακτα αμύγδαλο\nKit Kat\n</lookup>"),
        )
        assertTrue(CoachLookup.queries("Your trend is **83.9 kg**.").isEmpty())
        assertTrue(CoachLookup.queries("<lookup> </lookup>").isEmpty())
        // Cut off mid-block still works
        assertEquals(listOf("oreo"), CoachLookup.queries("<lookup>oreo"))
        assertEquals("Sure.", CoachLookup.strip("Sure. <lookup>oreo</lookup>"))
    }

    @Test
    fun resultsGiveTheCoachCodesAndLabelValues() {
        val lacta = FoodFact("5201360", "Lacta", "Lacta", 526.0, 6.9, 57.0, 30.2, servingGrams = 25.0, local = true)
        val text = CoachLookup.results(listOf("lacta" to listOf(lacta), "unknown thing" to emptyList(), "offline" to null))
        assertTrue(text, text.contains("- code 5201360 | Lacta | 526 kcal, P 6.9, C 57.0, F 30.2 | serving 25 g | sold in Greece"))
        assertTrue(text.contains("\"unknown thing\": nothing found"))
        assertTrue(text.contains("\"offline\": the search didn't work"))
        assertTrue(text.contains("off_code"))
    }

    @Test
    fun loggedProductsGetTheExactLabelValues() {
        val raw = """Here's your Lacta, tap Accept.
<actions>[{"type":"log_food","meal_name":"Lacta","meal_type":"snack","items":[
 {"name":"Lacta milk chocolate","grams":25,"kcal_per_100g":500,"protein_per_100g":6,"carbs_per_100g":60,"fat_per_100g":28,"off_code":"5201360"},
 {"name":"Coffee","grams":200,"kcal_per_100g":2,"off_code":"made-up"}]}]</actions>"""
        val action = CoachActions.parse(raw, LocalDate.of(2026, 10, 20)).actions.single()
        assertEquals("5201360", action.foods[0].offCode)
        val lacta = FoodFact("5201360", "Lacta", "Lacta", 526.0, 6.9, 57.0, 30.2)
        val checked = CoachActions.withLabelValues(action, mapOf(lacta.code to lacta))
        // The looked-up product's real values replace what the model typed
        assertEquals(526.0, checked.foods[0].kcalPer100, 0.0)
        assertEquals(30.2, checked.foods[0].fatPer100, 0.0)
        assertEquals("5201360", checked.foods[0].offCode)
        // A code that wasn't in this answer's lookups loses its tag and keeps its own values
        assertEquals(null, checked.foods[1].offCode)
        assertEquals(2.0, checked.foods[1].kcalPer100, 0.0)
    }
}
