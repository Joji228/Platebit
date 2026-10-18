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
    CUSTOM(
        "Custom",
        "Any OpenAI-compatible service",
        "custom",
        ApiKind.OPENAI_COMPATIBLE,
        null,
        "Works with OpenRouter, Groq, Mistral, xAI, a local server and more. Enter its address, key and a vision-capable model.",
    ),
    ;

    val needsBaseUrl get() = this == CUSTOM

    /** The company, when it adds something to the name (it doesn't for "DeepSeek · DeepSeek"). */
    val byline: String? get() = company.takeIf { it != title }
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
) {
    fun keyFor(provider: Provider) = keys[provider].orEmpty()

    fun modelFor(provider: Provider) = models[provider].orEmpty()

    fun baseUrlFor(provider: Provider) = provider.baseUrl ?: customBaseUrl.trim().trimEnd('/')

    /** Has everything needed to send a request. */
    fun isReady(provider: Provider) = keyFor(provider).isNotBlank() && (!provider.needsBaseUrl || customBaseUrl.isNotBlank())

    /** The provider tried when [primary] fails; null when fallback is off or nothing else is set up. */
    val fallbackTarget: Provider?
        get() = if (!fallback) {
            null
        } else {
            fallbackProvider?.takeIf { it != primary && isReady(it) } ?: Provider.entries.firstOrNull { it != primary && isReady(it) }
        }
}
