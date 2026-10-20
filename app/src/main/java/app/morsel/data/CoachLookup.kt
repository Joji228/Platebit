package app.morsel.data

import java.util.Locale

/**
 * The coach's product lookups. Before answering about a branded product it can reply with only
 *
 *     <lookup>lacta milk chocolate</lookup>
 *
 * The app searches Open Food Facts for each line and sends the results back to it (they're never
 * shown in the chat); then it answers using the label values.
 */
object CoachLookup {
    const val MAX_QUERIES = 3
    const val RESULTS_PER_QUERY = 5

    /** How many lookup rounds one question may use before the coach has to answer. */
    const val MAX_ROUNDS = 2

    private val block = Regex("""<lookup>(.*?)(?:</lookup>|$)""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    /** The searches the coach asked for, or empty when it answered straight away. */
    fun queries(reply: String): List<String> =
        block.findAll(reply)
            .flatMap { it.groupValues[1].split('\n', ';') }
            .map { it.trim().trim('"', '\'', '-', '•', ' ').take(60) }
            .filter { it.length >= 2 }
            .distinct()
            .take(MAX_QUERIES)
            .toList()

    /** The reply without any lookup block, for the rare answer that mixes both. */
    fun strip(reply: String): String = block.replace(reply, "").trim()

    /** What goes back to the coach: each search with its products and label values per 100 g. */
    fun results(found: List<Pair<String, List<FoodFact>?>>): String = buildString {
        append("[Open Food Facts results, not shown to the user. Values per 100 g; products sold in Greece first.]\n")
        found.forEach { (query, facts) ->
            append("\"").append(query).append("\": ")
            when {
                facts == null -> append("the search didn't work (no connection?). Use your own knowledge.\n")
                facts.isEmpty() -> append("nothing found. Use your own knowledge.\n")
                else -> {
                    append('\n')
                    facts.forEach { f ->
                        append("- code ").append(f.code.ifBlank { "none" }).append(" | ").append(f.label.replace('\n', ' '))
                        append(" | ").append(n(f.kcalPer100)).append(" kcal, P ").append(n(f.proteinPer100, 1))
                        append(", C ").append(n(f.carbsPer100, 1)).append(", F ").append(n(f.fatPer100, 1))
                        f.servingGrams?.let { append(" | serving ").append(n(it)).append(" g") }
                        if (f.local) append(" | sold in Greece")
                        append('\n')
                    }
                }
            }
        }
        append("Now answer the user's message. If you log one of these products, use its values and add its \"off_code\".")
    }

    private fun n(value: Double, decimals: Int = 0) =
        if (decimals == 0) Math.round(value).toString() else String.format(Locale.US, "%.${decimals}f", value)
}
