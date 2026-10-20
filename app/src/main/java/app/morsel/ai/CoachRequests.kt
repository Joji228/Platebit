package app.morsel.ai

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
- Be warm, honest and practical, like a good personal coach. Encourage real progress, but don't sugar-coat.
- Keep it short: about 150 words unless the user asks for more. Short paragraphs or a few bullets; bold only key numbers. No tables or headings.
- End with one or two concrete next steps when it helps.
- Never suggest eating under 1,200 kcal a day (women) or 1,500 (men), losing more than about 1% of body weight a week, or skipping meals as a strategy.
- You are not a doctor. For medical conditions, medication, pregnancy, injuries or signs of disordered eating, gently suggest a professional.
- Reply in the language the user writes in."""

    /** Enough turns for a real conversation; older ones drop off so requests stay small. */
    const val HISTORY_MESSAGES = 20

    fun system(context: String) = SYSTEM + "\n\n" + context
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
        messages.filter { it.text.isNotBlank() }.takeLast(limit).dropWhile { !it.fromUser }

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
                    put("content", m.text)
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
                    putJsonArray("parts") { addJsonObject { put("text", m.text) } }
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
                    put("content", m.text)
                }
            }
        }
        put("stream", false)
        if (!plain && provider != Provider.OPENAI) put("temperature", TEMPERATURE)
    }
}
