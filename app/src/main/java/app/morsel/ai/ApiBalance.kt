package app.morsel.ai

import app.morsel.data.AiSettings
import app.morsel.data.Provider
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

/** Credit left on an API account, in the provider's own currency. */
data class ApiBalance(
    val amounts: List<Amount>,
    /** A short extra line, like "Topped up $5.00 · free $0.00". */
    val detail: String? = null,
) {
    data class Amount(val value: Double, val currency: String) {
        val text: String get() = format(value, currency)
    }

    companion object {
        fun format(value: Double, currency: String): String {
            val symbol = when (currency.uppercase()) {
                "USD" -> "$"
                "CNY" -> "¥"
                "EUR" -> "€"
                else -> ""
            }
            val number = String.format(Locale.US, "%.2f", value)
            return if (symbol.isNotEmpty()) "$symbol$number" else "$number ${currency.uppercase()}"
        }
    }
}

/**
 * Which engines can report their balance, and reading their answers. Only DeepSeek and OpenRouter
 * offer this to an API key; OpenAI, Anthropic and Google don't, so they show no balance.
 */
object ApiBalances {
    const val DEEPSEEK_URL = "https://api.deepseek.com/user/balance"

    fun supported(provider: Provider, ai: AiSettings): Boolean = when (provider) {
        Provider.DEEPSEEK -> true
        Provider.CUSTOM -> isOpenRouter(ai.baseUrlFor(provider))
        else -> false
    }

    fun isOpenRouter(baseUrl: String) = baseUrl.contains("openrouter.ai", ignoreCase = true)

    /** {"is_available":true,"balance_infos":[{"currency":"USD","total_balance":"4.82","granted_balance":"0.00","topped_up_balance":"4.82"}]} */
    fun parseDeepSeek(root: JsonObject): ApiBalance? {
        val infos = root["balance_infos"]?.let { runCatching { it.jsonArray }.getOrNull() } ?: return null
        val parsed = infos.mapNotNull { el ->
            val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            val currency = o.str("currency") ?: return@mapNotNull null
            val total = o.num("total_balance") ?: return@mapNotNull null
            Triple(ApiBalance.Amount(total, currency), o.num("topped_up_balance"), o.num("granted_balance"))
        }
        if (parsed.isEmpty()) return null
        val (_, topped, granted) = parsed.first()
        val currency = parsed.first().first.currency
        val detail = if (topped != null && granted != null) {
            "Topped up ${ApiBalance.format(topped, currency)} · free credit ${ApiBalance.format(granted, currency)}"
        } else {
            null
        }
        return ApiBalance(parsed.map { it.first }, detail)
    }

    /** {"data":{"total_credits":10.0,"total_usage":2.5}}: what's left is credits minus usage, in dollars. */
    fun parseOpenRouter(root: JsonObject): ApiBalance? {
        val data = root["data"]?.let { runCatching { it.jsonObject }.getOrNull() } ?: return null
        val credits = data.num("total_credits") ?: return null
        val usage = data.num("total_usage") ?: 0.0
        return ApiBalance(
            listOf(ApiBalance.Amount(credits - usage, "USD")),
            "Bought ${ApiBalance.format(credits, "USD")} · used ${ApiBalance.format(usage, "USD")}",
        )
    }

    private fun JsonObject.str(key: String) = this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

    private fun JsonObject.num(key: String) = str(key)?.toDoubleOrNull()
}
