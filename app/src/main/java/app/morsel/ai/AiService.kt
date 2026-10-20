package app.morsel.ai

import android.util.Base64
import app.morsel.data.AiSettings
import app.morsel.data.ApiKind
import app.morsel.data.Provider
import app.morsel.data.ThinkingLevel
import app.morsel.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import java.util.Locale
import java.time.format.DateTimeFormatter
import java.time.LocalDateTime
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
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
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
    /** Brand and product for packaged foods ("Oreo Original"), blank for anything else. */
    val product: String = "",
    /** Set by Platebit when the numbers came from Open Food Facts: the product it matched. */
    val foodFacts: String? = null,
)

@Serializable
data class AiMeal(
    @SerialName("meal_name") val mealName: String = "",
    /** breakfast, lunch, dinner or snack, as the model judged it; blank if it didn't say. */
    @SerialName("meal_type") val mealType: String = "",
    val items: List<AiItem> = emptyList(),
    val confidence: String = "medium",
    val notes: String = "",
)

/** [fallbackReason] explains why the primary provider wasn't used, when it wasn't. */
data class AnalysisResult(
    val meal: AiMeal,
    val provider: Provider,
    val model: String,
    val fallbackReason: String? = null,
    /** Shown instead of "provider · model" when no AI was used (a barcode read from Open Food Facts). */
    val sourceLabel: String? = null,
)

class AiException(message: String) : Exception(message)

private const val ANTHROPIC_VERSION = "2023-06-01"

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
- For a packaged or branded product you can identify (from the packaging, a logo or the description), set product to its brand and product name, e.g. "Oreo Original" or "Coca-Cola Zero"; leave product empty for home-made or unbranded food.
- Choose meal_type from what the food is, using the local time given as a hint: typical breakfast foods (cereal, eggs and toast, yogurt, pastries, coffee with something small) are breakfast; a single small item or light bite (a chocolate bar, fruit, crisps, a protein bar, a drink with a biscuit) is a snack at any time; a full or heavy plated meal is lunch around midday and dinner in the evening.
Respond ONLY with JSON in exactly this shape:
{"meal_name": "2-4 word name", "meal_type": "breakfast|lunch|dinner|snack", "items": [{"name": "food", "grams": 150, "kcal_per_100g": 165, "protein_per_100g": 31, "carbs_per_100g": 0, "fat_per_100g": 3.6, "product": ""}], "confidence": "low|medium|high", "notes": "one short sentence about assumptions"}
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
        append("\nLocal time: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEEE HH:mm", Locale.ENGLISH)))
        append("\nReturn JSON only.")
    }
}

class AiService(private val settings: SettingsRepository, client: OkHttpClient) {
    private val http = client.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(150, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    private val jsonMedia = "application/json".toMediaType()

    /**
     * Tries the primary provider. Only when fallback is on, and a second provider is set up,
     * does a failure move on to that one; the result then says why the first was skipped.
     */
    suspend fun analyze(images: List<ByteArray>, note: String?): AnalysisResult {
        val ai = settings.ai.first()
        val errors = mutableListOf<String>()
        val order = listOfNotNull(ai.primary, ai.fallbackTarget)
        if (!ai.isReady(ai.primary)) {
            val missing = if (ai.primary.needsBaseUrl && ai.keyFor(ai.primary).isNotBlank()) "address" else "API key"
            errors += "${ai.primary.title}: no $missing set. Add it in Settings → AI engine."
        }
        val usable = order.filter { ai.isReady(it) }
        if (usable.isEmpty()) throw AiException(errors.joinToString("\n"))

        for (provider in usable) {
            try {
                val model = resolveModel(provider, ai)
                val raw = complete(provider, ai, model, images, Prompts.user(note, images.size))
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
    suspend fun listModels(provider: Provider, key: String, baseUrl: String): List<String> = withContext(Dispatchers.IO) {
        if (provider.needsBaseUrl) UrlPolicy.problem(baseUrl)?.let { throw AiException(it) }
        when (provider.kind) {
            ApiKind.GEMINI -> {
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
            ApiKind.ANTHROPIC -> {
                val root = get(
                    "https://api.anthropic.com/v1/models?limit=1000",
                    mapOf("x-api-key" to key, "anthropic-version" to ANTHROPIC_VERSION),
                    provider,
                )
                root["data"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }.sorted()
            }
            ApiKind.OPENAI_COMPATIBLE -> {
                val root = get("$baseUrl/models", mapOf("Authorization" to "Bearer $key"), provider)
                val ids = root["data"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
                // OpenAI lists embeddings, speech and image models too; keep the ones that chat.
                (if (provider == Provider.OPENAI) ids.filter(::isOpenAiChatModel) else ids).sorted()
            }
        }
    }

    private fun isOpenAiChatModel(id: String): Boolean {
        val chat = id.startsWith("gpt-") || id.startsWith("chatgpt") || Regex("^o\\d").containsMatchIn(id)
        val other = listOf("audio", "realtime", "transcribe", "tts", "image", "embedding", "moderation", "whisper", "search", "instruct", "codex", "dall")
        return chat && other.none { it in id }
    }

    /** Best default when the user hasn't picked a model: a recent, fast, vision-capable one. */
    fun pickDefault(provider: Provider, models: List<String>): String? {
        // Dated snapshots such as gpt-x-2025-08-07 are skipped in favour of the moving alias.
        val aliases = models.filterNot { Regex("\\d{4}-?\\d{2}-?\\d{2}$").containsMatchIn(it) }
        return when (provider) {
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
            Provider.OPENAI ->
                aliases.filter { "mini" in it && it.startsWith("gpt-") }.maxOrNull()
                    ?: aliases.filter { it.startsWith("gpt-") }.maxOrNull()
                    ?: models.maxOrNull()
            Provider.ANTHROPIC ->
                aliases.filter { "haiku" in it }.maxOrNull()
                    ?: aliases.filter { "sonnet" in it }.maxOrNull()
                    ?: models.maxOrNull()
            // Unknown service: the user has to name the model themselves.
            Provider.CUSTOM -> null
        }
    }

    /** Automatic picks are cached for this session only, so newer models are picked up later. */
    private val autoModels = mutableMapOf<Provider, String>()

    private suspend fun resolveModel(provider: Provider, ai: AiSettings): String {
        ai.modelFor(provider).takeIf { it.isNotBlank() }?.let { return it }
        autoModels[provider]?.let { return it }
        if (provider == Provider.CUSTOM) throw AiException("Choose a model for Custom in Settings → AI engine.")
        val picked = pickDefault(provider, listModels(provider, ai.keyFor(provider), ai.baseUrlFor(provider)))
            ?: throw AiException("No usable model found for this key")
        autoModels[provider] = picked
        return picked
    }

    private suspend fun complete(
        provider: Provider,
        ai: AiSettings,
        model: String,
        images: List<ByteArray>,
        userText: String,
    ): String = withContext(Dispatchers.IO) {
        val key = ai.keyFor(provider)
        if (provider.needsBaseUrl) UrlPolicy.problem(ai.baseUrlFor(provider))?.let { throw AiException(it) }
        val encoded = images.map { Base64.encodeToString(it, Base64.NO_WRAP) }
        when (provider.kind) {
            ApiKind.GEMINI -> gemini(key, model, encoded, userText)
            ApiKind.ANTHROPIC -> anthropic(provider, key, model, encoded, userText)
            ApiKind.OPENAI_COMPATIBLE -> try {
                openAiCompatible(provider, ai.baseUrlFor(provider), key, model, encoded, userText, strict = true, thinking = ai.thinkingFor(provider))
            } catch (e: HttpException) {
                // Some models reject response_format or a custom temperature; retry once without them.
                if (e.code == 400 || e.code == 422) {
                    openAiCompatible(provider, ai.baseUrlFor(provider), key, model, encoded, userText, strict = false, thinking = ai.thinkingFor(provider))
                } else {
                    throw e
                }
            }
        }
    }

    /** Claude's Messages API: images are base64 blocks, and the answer is a list of text blocks. */
    private suspend fun anthropic(provider: Provider, key: String, model: String, images: List<String>, userText: String): String {
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", 2048)
            put("temperature", 0.2)
            put("system", Prompts.SYSTEM)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        images.forEach { data ->
                            addJsonObject {
                                put("type", "image")
                                putJsonObject("source") {
                                    put("type", "base64")
                                    put("media_type", "image/jpeg")
                                    put("data", data)
                                }
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
        val root = post(
            "https://api.anthropic.com/v1/messages",
            mapOf("x-api-key" to key, "anthropic-version" to ANTHROPIC_VERSION),
            body,
            provider,
        )
        val text = root["content"]?.jsonArray.orEmpty()
            .joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
        return text.ifBlank { throw AiException("${provider.title} returned no answer") }
    }

    private suspend fun gemini(key: String, model: String, images: List<String>, userText: String): String {
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

    /**
     * The chat-completions format that DeepSeek, OpenAI and most other services share.
     * [strict] adds JSON mode and a low temperature; OpenAI's newer models reject a custom
     * temperature, so it is only sent to the others.
     */
    private suspend fun openAiCompatible(
        provider: Provider,
        baseUrl: String,
        key: String,
        model: String,
        images: List<String>,
        userText: String,
        strict: Boolean,
        /** DeepSeek only: turns its thinking off or sets how hard it thinks. */
        thinking: ThinkingLevel? = null,
    ): String {
        val body = buildJsonObject {
            put("model", model)
            if (thinking != null) {
                putJsonObject("thinking") { put("type", if (thinking.api == null) "disabled" else "enabled") }
                thinking.api?.let { put("reasoning_effort", it) }
            }
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
            put("stream", false)
            if (strict) {
                if (provider != Provider.OPENAI) put("temperature", 0.2)
                putJsonObject("response_format") { put("type", "json_object") }
            }
        }
        val root = post(
            "$baseUrl/chat/completions",
            mapOf("Authorization" to "Bearer $key"),
            body,
            provider,
        )
        return root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            ?: throw AiException("${provider.title} returned no answer")
    }

    private fun parse(raw: String): AiMeal = AiResponses.parseMeal(raw)

    private class HttpException(val code: Int, message: String) : Exception(message)

    private suspend fun post(url: String, headers: Map<String, String>, body: JsonObject, provider: Provider): JsonObject {
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }
            .post(body.toString().toRequestBody(jsonMedia))
            .build()
        return execute(request, provider)
    }

    private suspend fun get(url: String, headers: Map<String, String>, provider: Provider): JsonObject {
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.get().build()
        return execute(request, provider)
    }

    /**
     * Runs the request so that cancelling the scan really aborts it: the connection is closed
     * straight away instead of the request finishing (and being billed) in the background.
     */
    private suspend fun execute(request: Request, provider: Provider): JsonObject {
        val call = http.newCall(request)
        val text = suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val body = runCatching { it.body.string() }
                        body.onFailure { error -> cont.resumeWithException(error) }
                        body.onSuccess { b ->
                            if (it.isSuccessful) cont.resume(b) else cont.resumeWithException(HttpException(it.code, describeError(provider, it.code, b)))
                        }
                    }
                }
            })
        }
        return json.parseToJsonElement(text).jsonObject
    }

    private fun describeError(provider: Provider, code: Int, body: String) = AiResponses.describeError(provider, code, body)
}

/** Turning raw API responses into meals and readable errors. Separate so it can be unit tested. */
internal object AiResponses {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /** Finds the JSON object in a model's answer (models sometimes wrap it in prose or ``` fences). */
    fun parseMeal(raw: String): AiMeal {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) throw AiException("The answer wasn't readable")
        val meal = runCatching { json.decodeFromString<AiMeal>(raw.substring(start, end + 1)) }
            .getOrElse { throw AiException("The answer wasn't readable") }
        // A misread number can't log something absurd: keep every value inside what food can be.
        return meal.copy(
            items = meal.items.filter { it.name.isNotBlank() && it.grams > 0 }.map {
                it.copy(
                    grams = it.grams.coerceIn(1.0, 5000.0),
                    kcalPer100 = it.kcalPer100.coerceIn(0.0, 900.0),
                    proteinPer100 = it.proteinPer100.coerceIn(0.0, 100.0),
                    carbsPer100 = it.carbsPer100.coerceIn(0.0, 100.0),
                    fatPer100 = it.fatPer100.coerceIn(0.0, 100.0),
                )
            },
        )
    }

    fun describeError(provider: Provider, code: Int, body: String): String {
        val detail = runCatching {
            json.parseToJsonElement(body).jsonObject["error"]?.let { error ->
                (error as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
                    ?: error.jsonPrimitive.contentOrNull
            }
        }.getOrNull()
        // Gemini answers a bad key with 400 rather than 401.
        val badKey = code == 400 && listOf("API_KEY_INVALID", "API key not valid", "invalid api key").any { body.contains(it, ignoreCase = true) }
        val hint = when {
            code == 401 || code == 403 || badKey -> "The API key was rejected"
            code == 402 -> "The account has no balance"
            code == 404 -> "Model not found"
            code == 429 -> "Rate limit reached, try again in a minute"
            code in 500..599 -> "${provider.title} is having problems"
            else -> "Request failed"
        }
        return "$hint ($code)" + (detail?.let { ": ${it.take(160)}" } ?: "")
    }
}
