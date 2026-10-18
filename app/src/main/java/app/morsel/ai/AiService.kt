package app.morsel.ai

import android.util.Base64
import app.morsel.data.AiSettings
import app.morsel.data.Provider
import app.morsel.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

@Serializable
data class AiItem(
    val name: String = "",
    val grams: Double = 0.0,
    @SerialName("kcal_per_100g") val kcalPer100: Double = 0.0,
    @SerialName("protein_per_100g") val proteinPer100: Double = 0.0,
    @SerialName("carbs_per_100g") val carbsPer100: Double = 0.0,
    @SerialName("fat_per_100g") val fatPer100: Double = 0.0,
)

@Serializable
data class AiMeal(
    @SerialName("meal_name") val mealName: String = "",
    val items: List<AiItem> = emptyList(),
    val confidence: String = "medium",
    val notes: String = "",
)

/** [fallbackReason] explains why the primary provider wasn't used, when it wasn't. */
data class AnalysisResult(val meal: AiMeal, val provider: Provider, val model: String, val fallbackReason: String? = null)

class AiException(message: String) : Exception(message)

private object Prompts {
    const val SYSTEM = """You are a precise nutrition estimation assistant inside a calorie tracking app.
You receive one or more photos and/or a short description. Photos can show a plated meal, a nutrition facts label, food packaging, or raw ingredients of the same meal.
Identify every distinct food item that is eaten, estimate its edible weight in grams, and give its nutrition per 100 g.
Rules:
- If a nutrition label is visible, use its exact values (converted to per 100 g) for that food.
- Use plate, cutlery and hands for scale. Estimate realistic portions, not generic servings.
- Include visible or mentioned cooking oil, butter, sauces and dressings as separate items.
- Follow the user's description when it adds or corrects information.
- Keep names short and in English.
Respond ONLY with JSON in exactly this shape:
{"meal_name": "2-4 word name", "items": [{"name": "food", "grams": 150, "kcal_per_100g": 165, "protein_per_100g": 31, "carbs_per_100g": 0, "fat_per_100g": 3.6}], "confidence": "low|medium|high", "notes": "one short sentence about assumptions"}
If there is no food, return {"meal_name": "", "items": [], "confidence": "low", "notes": "No food found"}."""

    fun user(note: String?, imageCount: Int): String = buildString {
        append(
            when (imageCount) {
                0 -> "Estimate the nutrition of the meal described below."
                1 -> "Estimate the nutrition of the food in this photo."
                else -> "These $imageCount photos show the same meal. Estimate its nutrition."
            },
        )
        if (!note.isNullOrBlank()) append("\nUser's description: ").append(note.trim())
        append("\nReturn JSON only.")
    }
}

class AiService(private val settings: SettingsRepository) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(150, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    private val jsonMedia = "application/json".toMediaType()

    /**
     * Tries the primary provider first. If it fails and fallback is on, tries the other one
     * (when it has a key) and reports why the primary was skipped.
     */
    suspend fun analyze(images: List<ByteArray>, note: String?): AnalysisResult {
        val ai = settings.ai.first()
        val order = if (ai.fallback) listOf(ai.primary) + Provider.entries.filter { it != ai.primary } else listOf(ai.primary)
        val usable = order.filter { ai.keyFor(it).isNotBlank() }
        if (usable.isEmpty()) {
            throw AiException(
                if (ai.fallback) "Add a DeepSeek or Gemini API key in Settings first."
                else "Add a ${ai.primary.title} API key in Settings first, or turn on fallback.",
            )
        }

        val errors = mutableListOf<String>()
        for (provider in usable) {
            try {
                val model = resolveModel(provider, ai)
                val raw = complete(provider, ai.keyFor(provider), model, images, Prompts.user(note, images.size))
                return AnalysisResult(parse(raw), provider, model, errors.joinToString("\n").ifBlank { null })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors += "${provider.title}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        throw AiException(errors.joinToString("\n"))
    }

    /** Lists models the key can use. Doubles as a key check. */
    suspend fun listModels(provider: Provider, key: String): List<String> = withContext(Dispatchers.IO) {
        when (provider) {
            Provider.DEEPSEEK -> {
                val root = get("https://api.deepseek.com/models", mapOf("Authorization" to "Bearer $key"), provider)
                root["data"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }.sorted()
            }
            Provider.GEMINI -> {
                val root = get(
                    "https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000",
                    mapOf("x-goog-api-key" to key),
                    provider,
                )
                root["models"]?.jsonArray.orEmpty().mapNotNull { element ->
                    val model = element.jsonObject
                    val methods = model["supportedGenerationMethods"]?.jsonArray.orEmpty()
                        .mapNotNull { it.jsonPrimitive.contentOrNull }
                    val name = model["name"]?.jsonPrimitive?.contentOrNull?.removePrefix("models/")
                    name?.takeIf { "generateContent" in methods && it.startsWith("gemini") }
                }.sorted()
            }
        }
    }

    /** Best default when the user hasn't picked a model: newest vision-capable fast model. */
    fun pickDefault(provider: Provider, models: List<String>): String? = when (provider) {
        // V4.1 Flash has native vision and is served as "deepseek-flash"; the legacy
        // "deepseek-chat" alias is only a last resort.
        Provider.DEEPSEEK ->
            models.firstOrNull { it == "deepseek-flash" }
                ?: models.filter { "flash" in it }.maxOrNull()
                ?: models.filter { "vision" in it || "vl" in it }.maxOrNull()
                ?: models.firstOrNull { it == "deepseek-chat" }
                ?: models.maxOrNull()
        Provider.GEMINI ->
            models.firstOrNull { it == "gemini-flash-latest" }
                ?: models.filter { m ->
                    "flash" in m && listOf("lite", "image", "tts", "live", "audio", "exp", "preview", "thinking").none { it in m }
                }.maxOrNull()
                ?: models.firstOrNull { "flash" in it }
    }

    /** Automatic picks are cached for this session only, so newer models are picked up later. */
    private val autoModels = mutableMapOf<Provider, String>()

    private suspend fun resolveModel(provider: Provider, ai: AiSettings): String {
        ai.modelFor(provider).takeIf { it.isNotBlank() }?.let { return it }
        autoModels[provider]?.let { return it }
        val picked = pickDefault(provider, listModels(provider, ai.keyFor(provider)))
            ?: throw AiException("No usable model found for this key")
        autoModels[provider] = picked
        return picked
    }

    private suspend fun complete(
        provider: Provider,
        key: String,
        model: String,
        images: List<ByteArray>,
        userText: String,
    ): String = withContext(Dispatchers.IO) {
        val encoded = images.map { Base64.encodeToString(it, Base64.NO_WRAP) }
        when (provider) {
            Provider.GEMINI -> gemini(key, model, encoded, userText)
            Provider.DEEPSEEK -> try {
                deepseek(key, model, encoded, userText, jsonMode = true)
            } catch (e: HttpException) {
                // Some models reject response_format; retry once without it.
                if (e.code == 400 || e.code == 422) deepseek(key, model, encoded, userText, jsonMode = false) else throw e
            }
        }
    }

    private fun gemini(key: String, model: String, images: List<String>, userText: String): String {
        val body = buildJsonObject {
            putJsonObject("system_instruction") {
                putJsonArray("parts") { addJsonObject { put("text", Prompts.SYSTEM) } }
            }
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        images.forEach { data ->
                            addJsonObject {
                                putJsonObject("inline_data") {
                                    put("mime_type", "image/jpeg")
                                    put("data", data)
                                }
                            }
                        }
                        addJsonObject { put("text", userText) }
                    }
                }
            }
            putJsonObject("generationConfig") {
                put("temperature", 0.2)
                put("responseMimeType", "application/json")
            }
        }
        val root = post(
            "https://generativelanguage.googleapis.com/v1beta/models/${model.removePrefix("models/")}:generateContent",
            mapOf("x-goog-api-key" to key),
            body,
            Provider.GEMINI,
        )
        val parts = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("content")?.jsonObject?.get("parts")?.jsonArray
            ?: throw AiException("Gemini returned no answer")
        return parts.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
    }

    private fun deepseek(key: String, model: String, images: List<String>, userText: String, jsonMode: Boolean): String {
        val body = buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", Prompts.SYSTEM)
                }
                addJsonObject {
                    put("role", "user")
                    if (images.isEmpty()) {
                        put("content", userText)
                    } else {
                        putJsonArray("content") {
                            images.forEach { data ->
                                addJsonObject {
                                    put("type", "image_url")
                                    putJsonObject("image_url") { put("url", "data:image/jpeg;base64,$data") }
                                }
                            }
                            addJsonObject {
                                put("type", "text")
                                put("text", userText)
                            }
                        }
                    }
                }
            }
            put("temperature", 0.2)
            put("stream", false)
            if (jsonMode) putJsonObject("response_format") { put("type", "json_object") }
        }
        val root = post(
            "https://api.deepseek.com/chat/completions",
            mapOf("Authorization" to "Bearer $key"),
            body,
            Provider.DEEPSEEK,
        )
        return root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            ?: throw AiException("DeepSeek returned no answer")
    }

    private fun parse(raw: String): AiMeal {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) throw AiException("The answer wasn't readable")
        val meal = json.decodeFromString<AiMeal>(raw.substring(start, end + 1))
        return meal.copy(items = meal.items.filter { it.name.isNotBlank() && it.grams > 0 })
    }

    private class HttpException(val code: Int, message: String) : Exception(message)

    private fun post(url: String, headers: Map<String, String>, body: JsonObject, provider: Provider): JsonObject {
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }
            .post(body.toString().toRequestBody(jsonMedia))
            .build()
        return execute(request, provider)
    }

    private fun get(url: String, headers: Map<String, String>, provider: Provider): JsonObject {
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.get().build()
        return execute(request, provider)
    }

    private fun execute(request: Request, provider: Provider): JsonObject =
        http.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw HttpException(response.code, describeError(provider, response.code, text))
            json.parseToJsonElement(text).jsonObject
        }

    private fun describeError(provider: Provider, code: Int, body: String): String {
        val detail = runCatching {
            json.parseToJsonElement(body).jsonObject["error"]?.let { error ->
                (error as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
                    ?: error.jsonPrimitive.contentOrNull
            }
        }.getOrNull()
        val hint = when (code) {
            401, 403 -> "The API key was rejected"
            402 -> "The account has no balance"
            404 -> "Model not found"
            429 -> "Rate limit reached, try again in a minute"
            in 500..599 -> "${provider.title} is having problems"
            else -> "Request failed"
        }
        return "$hint ($code)" + (detail?.let { ": ${it.take(160)}" } ?: "")
    }
}
