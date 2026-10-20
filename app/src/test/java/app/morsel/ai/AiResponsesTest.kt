package app.morsel.ai

import app.morsel.data.Provider
import app.morsel.domain.MealType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiResponsesTest {

    @Test
    fun parsesJsonWrappedInProseAndFences() {
        val raw = """
            Sure! Here is the estimate:
            ```json
            {"meal_name": "Chicken rice", "items": [{"name": "Chicken", "grams": 150, "kcal_per_100g": 165,
              "protein_per_100g": 31, "carbs_per_100g": 0, "fat_per_100g": 3.6}], "confidence": "high", "notes": "ok"}
            ```
        """.trimIndent()
        val meal = AiResponses.parseMeal(raw)
        assertEquals("Chicken rice", meal.mealName)
        assertEquals(150.0, meal.items.single().grams, 0.001)
        assertEquals(165.0, meal.items.single().kcalPer100, 0.001)
    }

    @Test
    fun dropsEmptyOrWeightlessItemsAndToleratesMissingFields() {
        val meal = AiResponses.parseMeal(
            """{"items": [{"name": "", "grams": 50}, {"name": "Rice", "grams": 0}, {"name": "Egg", "grams": 50}]}""",
        )
        assertEquals(listOf("Egg"), meal.items.map { it.name })
        assertEquals("medium", meal.confidence)
    }

    @Test(expected = AiException::class)
    fun unreadableAnswerIsAFriendlyError() {
        AiResponses.parseMeal("I can't see any food, sorry.")
    }

    @Test(expected = AiException::class)
    fun brokenJsonIsAFriendlyError() {
        AiResponses.parseMeal("""{"items": [ {"name": "x", "grams": }""")
    }

    @Test
    fun geminiBadKeyIsReportedAsRejectedKey() {
        val body = """{"error": {"code": 400, "message": "API key not valid. Please pass a valid API key.", "status": "INVALID_ARGUMENT"}}"""
        val text = AiResponses.describeError(Provider.GEMINI, 400, body)
        assertTrue(text, text.startsWith("The API key was rejected (400)"))
        assertTrue(AiResponses.describeError(Provider.OPENAI, 400, """{"error": {"message": "bad image"}}""").startsWith("Request failed (400)"))
        assertTrue(AiResponses.describeError(Provider.DEEPSEEK, 402, "{}").startsWith("The account has no balance"))
    }

    @Test
    fun urlPolicy() {
        assertNull(UrlPolicy.problem("https://openrouter.ai/api/v1"))
        assertNull(UrlPolicy.problem("http://192.168.1.10:11434/v1"))
        assertNull(UrlPolicy.problem("http://10.0.2.2:1234/v1"))
        assertNull(UrlPolicy.problem("http://localhost:8080/v1"))
        assertNull(UrlPolicy.problem("http://my-pc.local:1234/v1"))
        // Unencrypted to the internet, or to a public IP, is refused.
        assertTrue(UrlPolicy.problem("http://api.example.com/v1")!!.contains("https"))
        assertTrue(UrlPolicy.problem("http://8.8.8.8/v1")!!.contains("https"))
        assertTrue(UrlPolicy.problem("http://172.32.0.1/v1")!!.contains("https"))
        assertTrue(UrlPolicy.problem("ftp://example.com") != null)
        assertTrue(UrlPolicy.problem("not a url") != null)
    }

    @Test
    fun readsTheSuggestedMealType() {
        val meal = AiResponses.parseMeal("""{"meal_name": "Chocolate bar", "meal_type": "Snack", "items": [], "confidence": "high", "notes": ""}""")
        assertEquals("Snack", meal.mealType)
        assertEquals(MealType.SNACK, MealType.fromAi(meal.mealType))
        // Older answers without the field, or an unknown word, fall back to the time of day.
        assertNull(MealType.fromAi(AiResponses.parseMeal("""{"meal_name": "Soup", "items": []}""").mealType))
        assertNull(MealType.fromAi("brunch"))
        assertEquals(MealType.DINNER, MealType.fromAi(" dinner "))
    }
}
