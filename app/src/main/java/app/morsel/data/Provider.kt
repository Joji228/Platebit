package app.morsel.data

/** How a provider's HTTP API is spoken. Most services copy OpenAI's format. */
enum class ApiKind { OPENAI_COMPATIBLE, GEMINI, ANTHROPIC }

/**
 * An AI service the app can send meal photos to.
 *
 * [prefix] names the stored settings. DeepSeek and Gemini keep the names they had before more
 * providers existed, so keys saved by older versions still load.
 */
enum class Provider(
    val title: String,
    val company: String,
    val prefix: String,
    val kind: ApiKind,
    /** Fixed endpoint root; null for [CUSTOM], where the user supplies it. */
    val baseUrl: String?,
    val keyHelp: String,
) {
    DEEPSEEK("DeepSeek", "DeepSeek", "deepseek", ApiKind.OPENAI_COMPATIBLE, "https://api.deepseek.com", "Create a key at platform.deepseek.com"),
    GEMINI("Gemini", "Google", "gemini", ApiKind.GEMINI, null, "Create a free key at aistudio.google.com"),
    OPENAI("OpenAI", "OpenAI", "openai", ApiKind.OPENAI_COMPATIBLE, "https://api.openai.com/v1", "Create a key at platform.openai.com"),
    ANTHROPIC("Claude", "Anthropic", "anthropic", ApiKind.ANTHROPIC, null, "Create a key at console.anthropic.com"),
    OPENROUTER(
        "OpenRouter",
        "Hundreds of models, some free",
        "openrouter",
        ApiKind.OPENAI_COMPATIBLE,
        "https://openrouter.ai/api/v1",
        "Create a key at openrouter.ai/keys. Models ending in :free cost nothing, with daily limits.",
    ),
    CUSTOM(
        "Custom",
        "Any OpenAI-compatible service",
        "custom",
        ApiKind.OPENAI_COMPATIBLE,
        null,
        "Works with Groq, Mistral, xAI, a local server and more. Enter its address, key and a vision-capable model.",
    ),
    ;

    val needsBaseUrl get() = this == CUSTOM

    /** The company, when it adds something to the name (it doesn't for "DeepSeek · DeepSeek"). */
    val byline: String? get() = company.takeIf { it != title }
}

/**
 * How long DeepSeek reasons before answering. Its API thinks at High unless told otherwise;
 * [api] is the reasoning_effort sent, null for thinking turned off.
 */
enum class ThinkingLevel(val title: String, val api: String?, val about: String) {
    OFF("Off", null, "Answers right away. Fastest and cheapest"),
    LOW("Low", "low", "A short think first. Quick, a bit more careful"),
    HIGH("High", "high", "DeepSeek's default. Slower, more careful"),
    MAX("Max", "max", "Thinks the longest. Slowest and most expensive"),
}

enum class ThemeMode(val title: String) { DARK("Dark"), LIGHT("Light"), SYSTEM("System") }

data class AiSettings(
    val primary: Provider = Provider.DEEPSEEK,
    val keys: Map<Provider, String> = emptyMap(),
    /** Blank means "pick automatically from the provider's model list". */
    val models: Map<Provider, String> = emptyMap(),
    /** Address for [Provider.CUSTOM], e.g. https://openrouter.ai/api/v1 */
    val customBaseUrl: String = "",
    /** Off by default: when off, only [primary] is ever used, and a failure is simply a failure. */
    val fallback: Boolean = false,
    val fallbackProvider: Provider? = null,
    /**
     * The engines the user has added on the AI engine screen, in order. Empty until the list is
     * first edited; [activeEngines] then falls back to the engines that already have a key.
     */
    val engines: List<Provider> = emptyList(),
    /** DeepSeek's thinking; High is what its API does when nothing is sent, so it stays the default. */
    val deepseekThinking: ThinkingLevel = ThinkingLevel.HIGH,
) {
    /** What the AI engine screen shows: always includes [primary], never more than [MAX_ENGINES]. */
    val activeEngines: List<Provider>
        get() {
            val chosen = engines.ifEmpty { Provider.entries.filter { keyFor(it).isNotBlank() } }
            return (if (primary in chosen) chosen else listOf(primary) + chosen).distinct().take(MAX_ENGINES)
        }

    /** Engines that can still be added, in the order the dropdown lists them. */
    val addableEngines: List<Provider> get() = if (activeEngines.size >= MAX_ENGINES) emptyList() else Provider.entries - activeEngines.toSet()

    fun keyFor(provider: Provider) = keys[provider].orEmpty()

    fun modelFor(provider: Provider) = models[provider].orEmpty()

    /** The thinking setting sent with a request; only DeepSeek has one. */
    fun thinkingFor(provider: Provider) = deepseekThinking.takeIf { provider == Provider.DEEPSEEK }

    fun baseUrlFor(provider: Provider) = provider.baseUrl ?: customBaseUrl.trim().trimEnd('/')

    /** Has everything needed to send a request. */
    fun isReady(provider: Provider) = keyFor(provider).isNotBlank() && (!provider.needsBaseUrl || customBaseUrl.isNotBlank())

    /** The provider tried when [primary] fails; null when fallback is off or nothing else is set up. */
    val fallbackTarget: Provider?
        get() = if (!fallback) {
            null
        } else {
            fallbackProvider?.takeIf { it != primary && it in activeEngines && isReady(it) }
                ?: activeEngines.firstOrNull { it != primary && isReady(it) }
        }

    companion object {
        const val MAX_ENGINES = 4
    }
}
