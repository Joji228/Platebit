package app.morsel.ai

import app.morsel.data.AiSettings
import app.morsel.data.Provider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiBalancesTest {
    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun readsDeepSeekBalance() {
        val b = ApiBalances.parseDeepSeek(
            json("""{"is_available":true,"balance_infos":[{"currency":"USD","total_balance":"4.82","granted_balance":"0.50","topped_up_balance":"4.32"}]}"""),
        )!!
        assertEquals("$4.82", b.amounts.single().text)
        assertEquals("Topped up $4.32 · free credit $0.50", b.detail)
        // An account with yuan and dollars shows both
        val two = ApiBalances.parseDeepSeek(
            json("""{"balance_infos":[{"currency":"CNY","total_balance":"110.00"},{"currency":"USD","total_balance":"1.20"}]}"""),
        )!!
        assertEquals(listOf("¥110.00", "$1.20"), two.amounts.map { it.text })
        assertNull(two.detail)
        assertNull(ApiBalances.parseDeepSeek(json("""{"error":"nope"}""")))
    }

    @Test
    fun readsOpenRouterCreditsMinusUsage() {
        val b = ApiBalances.parseOpenRouter(json("""{"data":{"total_credits":10,"total_usage":2.34}}"""))!!
        assertEquals("$7.66", b.amounts.single().text)
        assertEquals("Bought $10.00 · used $2.34", b.detail)
        assertNull(ApiBalances.parseOpenRouter(json("""{"data":{}}""")))
    }

    @Test
    fun balanceErrorsAreShort() {
        assertEquals(
            "DeepSeek rejected this key, so the balance can't be shown.",
            app.morsel.ui.settings.balanceProblem("DeepSeek", "The API key was rejected (401): Authentication Fails, Your api key: ****0000 is invalid (request_id: 4c78)"),
        )
        assertEquals("DeepSeek says the account has no credit left.", app.morsel.ui.settings.balanceProblem("DeepSeek", "Insufficient Balance (402)"))
        assertEquals("Timeout", app.morsel.ui.settings.balanceProblem("DeepSeek", "Timeout (request_id: x)"))
    }

    @Test
    fun onlyServicesThatReportItAreChecked() {
        val ai = AiSettings(customBaseUrl = "https://openrouter.ai/api/v1")
        assertTrue(ApiBalances.supported(Provider.DEEPSEEK, ai))
        assertTrue(ApiBalances.supported(Provider.CUSTOM, ai))
        assertFalse(ApiBalances.supported(Provider.CUSTOM, AiSettings(customBaseUrl = "http://192.168.1.10:11434/v1")))
        assertFalse(ApiBalances.supported(Provider.OPENAI, ai))
        assertFalse(ApiBalances.supported(Provider.ANTHROPIC, ai))
        assertFalse(ApiBalances.supported(Provider.GEMINI, ai))
        assertEquals("12.50 GBP", ApiBalance.format(12.5, "gbp"))
    }
}
