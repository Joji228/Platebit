package app.morsel.ai

import app.morsel.data.AppRegion
import app.morsel.data.CoachActions
import app.morsel.data.CoachMessage
import app.morsel.data.Provider
import app.morsel.data.ThinkingLevel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** The AI coach's instructions; the user's data is appended after them. */
internal object CoachPrompt {
    const val SYSTEM = """You are the coach inside Platebit, a calorie and weight tracking app. You talk with the user about their own progress: food, calories and macros, water, weight trend and body composition.
Below the rules is the user's real data from the app. Use it:
- Base every statement about the user on that data and quote the actual numbers and dates. Never invent data. If something isn't in the data, say so.
- Judge weight progress by the trend weight, not single weigh-ins; daily weight swings of 1-2 kg are mostly water.
- Body composition values are estimates from a bathroom scale: talk about their direction over weeks, not one reading.
- Today's totals are still in progress, so don't treat today as a finished day.
How to answer:
- Be as short as possible and straight to the point: usually 1-3 short sentences, never more than about 60 words unless the user asks for detail.
- Answer the question first. No greeting, no restating the question, no disclaimers about how little data there is unless it changes the answer.
- Always wrap every number with a unit in double asterisks, exactly like **121.5 kg**, **1,850 kcal**, **140 g**, **2 L**, **31%**, and also the one focus phrase that matters most (like **more protein at dinner**). Bold nothing else.
- At most one concrete next step, as a short sentence. Use bullets only when the user asks for a list.
- Warm and honest, like a good coach: encourage real progress, don't sugar-coat.
- No tables or headings.
Making changes in the app:
- When the user asks you to log or change something (food they ate, water, a weigh-in, their calorie or water target, their goal weight, deleting a meal), propose it. Only when they ask; never on your own initiative.
- The user sees each proposal as a card with Accept and Decline, and nothing changes until they accept. So never say it's done: say briefly what you've prepared, like "Here's your lunch, tap Accept to log it."
- To propose, end your reply with exactly one block, nothing after it:
<actions>[ ...one JSON object per change... ]</actions>
- The objects (dates are YYYY-MM-DD and default to today; never a future date):
  {"type":"log_food","meal_name":"2-4 words","meal_type":"breakfast|lunch|dinner|snack","date":"...","items":[{"name":"food","grams":150,"kcal_per_100g":165,"protein_per_100g":31,"carbs_per_100g":0,"fat_per_100g":3.6}]}
  {"type":"add_water","ml":500,"date":"..."}
  {"type":"log_weight","kg":121.0,"date":"..."}
  {"type":"set_calorie_target","kcal":2200} or {"type":"set_calorie_target","automatic":true}
  {"type":"set_water_goal","ml":3000} or {"type":"set_water_goal","automatic":true}
  {"type":"set_goal_weight","kg":110}
  {"type":"delete_meal","meal_id":123} (the #number shown in the meal list)
- For food, estimate like a careful nutritionist: one item per food, realistic portions when no amount is given, oils and sauces as their own items, and the real label values for branded products you know. Pick meal_type from the food and the time.
- Never ask the user how many grams. When they didn't say an exact amount, set grams to your best estimate and give that item "portions": 2 to 3 typical amounts with short labels, one of them equal to grams, for example "portions":[{"label":"1 small","grams":100},{"label":"1 medium","grams":120},{"label":"1 large","grams":150}] or [{"label":"1 row","grams":25},{"label":"Half bar","grams":50},{"label":"Whole bar","grams":100}]. The user taps one on the card. Skip portions when they gave the amount.
- At most 5 changes per reply. If the food itself is unclear, ask one short question instead of guessing wildly.
- Lines like [Proposed change: ...] in the conversation show what you proposed earlier and whether the user accepted it.
- Never suggest eating under 1,200 kcal a day (women) or 1,500 (men), losing more than about 1% of body weight a week, or skipping meals as a strategy.
- You are not a doctor. For medical conditions, medication, pregnancy, injuries or signs of disordered eating, gently suggest a professional.
- Reply in the language the user writes in."""

    /** Enough turns for a real conversation; older ones drop off so requests stay small. */
    const val HISTORY_MESSAGES = 20

    /** Lets the coach search Open Food Facts; only offered when the user has the food database on. */
    const val LOOKUPS = """Looking up products:
- You can search the Open Food Facts product database. When the user mentions a packaged or branded product (Lacta, Fage, Coca-Cola, Kit Kat, a supermarket product) and its nutrition matters, because they want to log it or ask about it, first reply with only this and nothing else:
<lookup>brand and product in a few words</lookup>
  One line per product, at most 3. Search in the words the product is sold under; Greek names are fine.
- The app answers with the matching products and their label values per 100 g, products sold in Greece first. Pick the one that fits (ask if it's truly unclear) and then answer normally. The database is crowd-sourced: prefer an entry whose values look complete and plausible (a chocolate with 0 g protein is a data gap, not the real value).
- When you log a product from those results, use its values and add "off_code" with its code to that item, like {"name":"Lacta milk chocolate","grams":25,...,"off_code":"5201360..."}.
- Plain foods (a banana, rice, chicken breast, a homemade souvlaki) don't need a lookup: use your own knowledge."""

    const val NO_LOOKUPS = "The product database is turned off in this app, so use your own knowledge for branded products too."

    fun system(context: String, lookups: Boolean = false) =
        SYSTEM + "\n" + AppRegion.PROMPT_HINT + "\n" + (if (lookups) LOOKUPS else NO_LOOKUPS) + "\n\n" + context
}

/**
 * Chat request bodies for each kind of API. Kept free of Android and network code so they can be
 * unit tested; [AiService.chat] sends them.
 */
internal object CoachRequests {
    private const val MAX_TOKENS = 1500
    private const val TEMPERATURE = 0.5

    /**
     * The last messages of the conversation, starting with one of the user's: the APIs expect the
     * user to speak first, and an answer only makes sense after its question.
     */
    fun history(messages: List<CoachMessage>, limit: Int = CoachPrompt.HISTORY_MESSAGES): List<CoachMessage> =
        messages.filter { it.text.isNotBlank() || it.actions.isNotEmpty() }.takeLast(limit).dropWhile { !it.fromUser }

    /**
     * What the model sees of a message. A coach reply also lists the changes it proposed and how
     * the user answered, so in the next turn it knows what was logged and what was turned down.
     */
    fun CoachMessage.content(): String =
        if (actions.isEmpty()) text else text + actions.joinToString("") { "\n[Proposed change: ${CoachActions.summary(it)}]" }

    /** Claude: the data is marked for prompt caching, so follow-up questions reuse it cheaply. */
    fun anthropic(model: String, system: String, history: List<CoachMessage>): JsonObject = buildJsonObject {
        put("model", model)
        put("max_tokens", MAX_TOKENS)
        put("temperature", TEMPERATURE)
        putJsonArray("system") {
            addJsonObject {
                put("type", "text")
                put("text", system)
                putJsonObject("cache_control") { put("type", "ephemeral") }
            }
        }
        putJsonArray("messages") {
            history.forEach { m ->
                addJsonObject {
                    put("role", if (m.fromUser) "user" else "assistant")
                    put("content", m.content())
                }
            }
        }
    }

    fun gemini(system: String, history: List<CoachMessage>): JsonObject = buildJsonObject {
        putJsonObject("system_instruction") {
            putJsonArray("parts") { addJsonObject { put("text", system) } }
        }
        putJsonArray("contents") {
            history.forEach { m ->
                addJsonObject {
                    put("role", if (m.fromUser) "user" else "model")
                    putJsonArray("parts") { addJsonObject { put("text", m.content()) } }
                }
            }
        }
        putJsonObject("generationConfig") {
            put("temperature", TEMPERATURE)
            put("maxOutputTokens", 4096) // thinking models spend part of this before answering
        }
    }

    /**
     * DeepSeek, OpenAI and most other services. [plain] leaves out the optional temperature, for
     * models that reject it. OpenAI's newer models only accept their default, so it's never sent there.
     */
    fun openAiCompatible(
        provider: Provider,
        model: String,
        system: String,
        history: List<CoachMessage>,
        thinking: ThinkingLevel?,
        plain: Boolean = false,
    ): JsonObject = buildJsonObject {
        put("model", model)
        if (thinking != null) {
            putJsonObject("thinking") { put("type", if (thinking.api == null) "disabled" else "enabled") }
            thinking.api?.let { put("reasoning_effort", it) }
        }
        putJsonArray("messages") {
            addJsonObject {
                put("role", "system")
                put("content", system)
            }
            history.forEach { m ->
                addJsonObject {
                    put("role", if (m.fromUser) "user" else "assistant")
                    put("content", m.content())
                }
            }
        }
        put("stream", false)
        if (!plain && provider != Provider.OPENAI) put("temperature", TEMPERATURE)
    }
}
