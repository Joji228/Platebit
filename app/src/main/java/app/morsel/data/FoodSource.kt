package app.morsel.data

/**
 * Where a meal's numbers came from, as stored in its source line ("DeepSeek · deepseek-flash",
 * "Open Food Facts · barcode", "Added by hand"...). The meal screen marks Open Food Facts with a
 * blue tick and AI with the green sparkle, both when a meal mixes them.
 */
object FoodSource {
    const val FOOD_FACTS = "Open Food Facts"
    const val BY_HAND = "Added by hand"
    const val COACH = "AI coach"

    data class Marks(val foodFacts: Boolean, val ai: Boolean)

    /** [anyItemFromFoodFacts] covers a scan the AI read where some products were then matched. */
    fun marks(source: String?, anyItemFromFoodFacts: Boolean = false): Marks {
        val s = source.orEmpty().trim()
        val foodFacts = anyItemFromFoodFacts || s.contains(FOOD_FACTS, ignoreCase = true)
        val ai = s.isNotEmpty() && !s.startsWith(FOOD_FACTS, ignoreCase = true) && !s.equals(BY_HAND, ignoreCase = true)
        return Marks(foodFacts, ai)
    }

    /**
     * The source line to save: an AI meal with some label values from Open Food Facts says so,
     * because once saved the individual foods no longer carry that.
     */
    fun withFoodFacts(source: String?, anyItemFromFoodFacts: Boolean): String? =
        if (!anyItemFromFoodFacts || source == null || source.contains(FOOD_FACTS, ignoreCase = true)) source else "$source + $FOOD_FACTS"
}
