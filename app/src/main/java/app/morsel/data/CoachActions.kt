package app.morsel.data

import app.morsel.domain.Macros
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.LocalDate
import kotlin.math.roundToInt

/** Whether the user has answered a proposed change yet. */
@Serializable
enum class ActionStatus { PENDING, ACCEPTED, DECLINED, FAILED }

/** Something the coach offers to change in the app. Nothing happens until the user taps Accept. */
@Serializable
enum class ActionType { LOG_FOOD, DELETE_MEAL, ADD_WATER, LOG_WEIGHT, SET_CALORIES, SET_WATER_GOAL, SET_GOAL_WEIGHT }

@Serializable
data class ActionFood(
    val name: String,
    val grams: Double,
    val kcalPer100: Double,
    val proteinPer100: Double,
    val carbsPer100: Double,
    val fatPer100: Double,
) {
    val macros get() = Macros(kcalPer100, proteinPer100, carbsPer100, fatPer100) * (grams / 100.0)
}

/**
 * One proposed change. Only the fields its [type] needs are set; null [kcal] or [ml] on a target
 * change means "go back to automatic".
 */
@Serializable
data class CoachAction(
    val type: ActionType,
    val date: String? = null,
    val name: String? = null,
    val mealType: String? = null,
    val foods: List<ActionFood> = emptyList(),
    val mealId: Long? = null,
    val ml: Int? = null,
    val kg: Double? = null,
    val kcal: Int? = null,
    val status: ActionStatus = ActionStatus.PENDING,
    /** What happened, once answered: "Logged lunch: Chicken salad", or why it couldn't be done. */
    val result: String? = null,
) {
    val macros get() = foods.fold(Macros()) { acc, food -> acc + food.macros }
}

/**
 * Reads the changes the coach proposes. The coach ends its reply with one block:
 *
 *     <actions>[{"type": "log_food", ...}]</actions>
 *
 * The block is taken out of the text the user reads. Everything in it is checked here: unknown
 * types and impossible values are dropped or clamped, dates can't be in the future or more than a
 * year back, so a confused model can't propose anything strange.
 */
object CoachActions {
    const val MAX_ACTIONS = 5

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val block = Regex("""<actions>(.*?)(?:</actions>|$)""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val fence = Regex("""```(?:json)?\s*(.*?)```""", RegexOption.DOT_MATCHES_ALL)

    data class Parsed(val text: String, val actions: List<CoachAction>)

    fun parse(raw: String, today: LocalDate): Parsed {
        val match = block.find(raw) ?: fence.findAll(raw).firstOrNull { "\"type\"" in it.groupValues[1] }
        if (match == null) return Parsed(raw.trim(), emptyList())
        val text = raw.removeRange(match.range).trim()
        val payload = match.groupValues[1].trim()
        val element = runCatching { json.parseToJsonElement(payload) }.getOrNull()
        val list = when (element) {
            is JsonArray -> element
            is JsonObject -> element["actions"]?.let { runCatching { it.jsonArray }.getOrNull() } ?: JsonArray(listOf(element))
            else -> JsonArray(emptyList())
        }
        val actions = list.mapNotNull { runCatching { read(it.jsonObject, today) }.getOrNull() }.take(MAX_ACTIONS)
        return Parsed(text, actions)
    }

    private fun read(o: JsonObject, today: LocalDate): CoachAction? {
        val type = when (o.str("type")?.lowercase()?.replace('-', '_')) {
            "log_food", "log_meal", "add_food", "add_meal" -> ActionType.LOG_FOOD
            "delete_meal", "remove_meal" -> ActionType.DELETE_MEAL
            "add_water", "log_water" -> ActionType.ADD_WATER
            "log_weight", "add_weight" -> ActionType.LOG_WEIGHT
            "set_calorie_target", "set_calories" -> ActionType.SET_CALORIES
            "set_water_goal" -> ActionType.SET_WATER_GOAL
            "set_goal_weight" -> ActionType.SET_GOAL_WEIGHT
            else -> return null
        }
        val date = date(o.str("date"), today)
        return when (type) {
            ActionType.LOG_FOOD -> {
                val foods = (o["items"] ?: o["foods"])?.let { runCatching { it.jsonArray }.getOrNull() }.orEmpty()
                    .mapNotNull { runCatching { food(it.jsonObject) }.getOrNull() }
                    .take(20)
                if (foods.isEmpty()) return null
                val name = (o.str("meal_name") ?: o.str("name"))?.oneLine()?.take(60)?.ifBlank { null } ?: foods.first().name
                CoachAction(type, date = date.toString(), name = name, mealType = o.str("meal_type")?.lowercase(), foods = foods)
            }
            ActionType.DELETE_MEAL -> {
                val id = (o.num("meal_id") ?: o.num("id"))?.toLong() ?: return null
                CoachAction(type, mealId = id)
            }
            ActionType.ADD_WATER -> {
                val ml = o.num("ml")?.roundToInt()?.takeIf { it in 50..5_000 } ?: return null
                CoachAction(type, date = date.toString(), ml = ml)
            }
            ActionType.LOG_WEIGHT -> {
                val kg = o.num("kg")?.takeIf { it in 20.0..400.0 } ?: return null
                CoachAction(type, date = date.toString(), kg = kg.round(1))
            }
            ActionType.SET_CALORIES -> {
                if (o.bool("automatic")) return CoachAction(type)
                val kcal = o.num("kcal")?.roundToInt()?.coerceIn(800, 6_000) ?: return null
                CoachAction(type, kcal = kcal)
            }
            ActionType.SET_WATER_GOAL -> {
                if (o.bool("automatic")) return CoachAction(type)
                val ml = o.num("ml")?.roundToInt()?.coerceIn(500, 6_000) ?: return null
                CoachAction(type, ml = ml)
            }
            ActionType.SET_GOAL_WEIGHT -> {
                val kg = o.num("kg")?.takeIf { it in 35.0..250.0 } ?: return null
                CoachAction(type, kg = kg.round(1))
            }
        }
    }

    private fun food(o: JsonObject): ActionFood? {
        val name = o.str("name")?.oneLine()?.take(60)?.ifBlank { null } ?: return null
        val grams = o.num("grams")?.takeIf { it > 0 } ?: return null
        // Same limits as a photo scan: nothing has more than 900 kcal or 100 g of a macro per 100 g.
        return ActionFood(
            name = name,
            grams = grams.coerceIn(1.0, 5_000.0),
            kcalPer100 = (o.num("kcal_per_100g") ?: return null).coerceIn(0.0, 900.0),
            proteinPer100 = (o.num("protein_per_100g") ?: 0.0).coerceIn(0.0, 100.0),
            carbsPer100 = (o.num("carbs_per_100g") ?: 0.0).coerceIn(0.0, 100.0),
            fatPer100 = (o.num("fat_per_100g") ?: 0.0).coerceIn(0.0, 100.0),
        )
    }

    /** Today when missing or unreadable; never in the future, never more than a year back. */
    private fun date(value: String?, today: LocalDate): LocalDate {
        val parsed = value?.let { runCatching { LocalDate.parse(it.trim()) }.getOrNull() } ?: return today
        return if (parsed.isAfter(today) || parsed.isBefore(today.minusYears(1))) today else parsed
    }

    private fun JsonObject.str(key: String): String? = this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

    private fun JsonObject.num(key: String): Double? = this[key]?.let { el ->
        runCatching { el.jsonPrimitive.doubleOrNull ?: el.jsonPrimitive.longOrNull?.toDouble() ?: el.jsonPrimitive.content.replace(',', '.').toDoubleOrNull() }.getOrNull()
    }?.takeIf { it.isFinite() }

    private fun JsonObject.bool(key: String) = str(key)?.equals("true", ignoreCase = true) == true

    /** The card's headline, e.g. "Log lunch: Chicken salad" or "Add 500 ml of water". */
    fun title(a: CoachAction): String = when (a.type) {
        ActionType.LOG_FOOD -> "Log ${a.mealType?.lowercase()?.takeIf { it in MEALS } ?: "food"}: ${a.name}"
        ActionType.DELETE_MEAL -> "Delete ${a.name ?: "meal"}"
        ActionType.ADD_WATER -> "Add ${a.ml} ml of water"
        ActionType.LOG_WEIGHT -> "Log a weigh-in of ${num(a.kg ?: 0.0, 1)} kg"
        ActionType.SET_CALORIES -> if (a.kcal == null) "Daily calories: back to automatic" else "Set daily calories to ${a.kcal} kcal"
        ActionType.SET_WATER_GOAL -> if (a.ml == null) "Water goal: back to automatic" else "Set the water goal to ${num(a.ml / 1000.0, 1)} L"
        ActionType.SET_GOAL_WEIGHT -> "Set the goal weight to ${num(a.kg ?: 0.0, 1)} kg"
    }

    /** The smaller line under it: totals and foods for a meal, and the day when it isn't today. */
    fun detail(a: CoachAction, today: LocalDate): String? {
        val day = a.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let { d ->
            when (d) {
                today -> null
                today.minusDays(1) -> "yesterday"
                else -> d.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.ENGLISH))
            }
        }
        return when (a.type) {
            ActionType.LOG_FOOD -> {
                val m = a.macros
                listOfNotNull(
                    "${num(m.kcal)} kcal · P ${num(m.protein)} g · C ${num(m.carbs)} g · F ${num(m.fat)} g" + (day?.let { " · $it" } ?: ""),
                    a.foods.joinToString(", ") { "${it.name} ${num(it.grams)} g" },
                ).joinToString("\n")
            }
            ActionType.DELETE_MEAL -> listOfNotNull(day ?: "today", a.kcal?.let { "$it kcal" }).joinToString(" · ")
            else -> day
        }
    }

    /** How a proposal reads back to the coach in later turns, so it knows what was accepted. */
    fun summary(a: CoachAction): String = "${title(a)}: " + when (a.status) {
        ActionStatus.PENDING -> "waiting for the user"
        ActionStatus.ACCEPTED -> "accepted, done"
        ActionStatus.DECLINED -> "declined by the user"
        ActionStatus.FAILED -> "couldn't be done (${a.result})"
    }

    private val MEALS = setOf("breakfast", "lunch", "dinner", "snack")

    private fun num(value: Double, decimals: Int = 0) =
        if (decimals == 0) value.roundToInt().toString() else String.format(java.util.Locale.US, "%.${decimals}f", value)

    private fun String.oneLine() = replace('\n', ' ').trim()

    private fun Double.round(decimals: Int): Double {
        val f = Math.pow(10.0, decimals.toDouble())
        return (this * f).roundToInt() / f
    }
}
