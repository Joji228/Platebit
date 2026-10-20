package app.morsel.data

/**
 * Where the app is used. For now Platebit is made for Greece: the AI coach and scans assume Greek
 * foods, dishes and brands, and Open Food Facts results from Greece come first. A language and
 * country choice at setup can replace this later; everything reads it from here.
 */
object AppRegion {
    const val COUNTRY = "Greece"

    /** Open Food Facts' tag for products sold in [COUNTRY]. */
    const val FOOD_FACTS_COUNTRY = "en:greece"

    /** One line the AI prompts share. */
    const val PROMPT_HINT =
        "The user lives in $COUNTRY: expect Greek foods, dishes, brands and portions unless they say otherwise " +
            "(for example Lacta is a Greek chocolate, Fage and Delta are Greek dairy brands, a souvlaki pita is a common meal)."
}
