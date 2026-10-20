package app.morsel.ai

import app.morsel.data.ActionStatus
import app.morsel.data.ActionType
import app.morsel.data.CoachAction
import app.morsel.data.CoachMessage
import app.morsel.data.Provider
import app.morsel.data.ThinkingLevel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachRequestsTest {
    private val chat = listOf(
        CoachMessage(true, "How am I doing?"),
        CoachMessage(false, "Great: down 1.2 kg."),
        CoachMessage(true, "And protein?"),
    )
    private val system = CoachPrompt.system("# The user's Platebit data")

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull

    @Test
    fun historyStartsWithTheUserAndKeepsTheLatest() {
        val long = (1..30).map { CoachMessage(it % 2 == 1, "m$it") }
        val h = CoachRequests.history(long, limit = 20)
        assertTrue(h.first().fromUser)
        assertEquals("m30", h.last().text)
        assertTrue(h.size <= 20)
        // A coach message left at the front is dropped, so the user speaks first
        assertEquals(listOf("q"), CoachRequests.history(listOf(CoachMessage(false, "hi"), CoachMessage(true, "q"))).map { it.text })
    }

    @Test
    fun proposalsAndTheirAnswersGoBackToTheModel() {
        val withActions = listOf(
            CoachMessage(true, "Log 500 ml of water"),
            CoachMessage(false, "Ready, tap Accept.", actions = listOf(CoachAction(ActionType.ADD_WATER, ml = 500, status = ActionStatus.ACCEPTED))),
            CoachMessage(true, "Thanks"),
        )
        val body = CoachRequests.openAiCompatible(Provider.DEEPSEEK, "m", system, withActions, null)
        val coach = body["messages"]!!.jsonArray[2].jsonObject.str("content")
        assertEquals("Ready, tap Accept.\n[Proposed change: Add 500 ml of water: accepted, done]", coach)
        assertTrue(CoachPrompt.SYSTEM.contains("<actions>"))
    }

    @Test
    fun theCoachKnowsItsInGreeceAndWhetherItCanLookUp() {
        val on = CoachPrompt.system("DATA", lookups = true)
        assertTrue(on.contains("lives in Greece"))
        assertTrue(on.contains("<lookup>"))
        assertTrue(on.endsWith("DATA"))
        val off = CoachPrompt.system("DATA", lookups = false)
        assertTrue(off.contains("lives in Greece"))
        assertFalse(off.contains("<lookup>"))
        assertTrue(off.contains("product database is turned off"))
    }

    @Test
    fun systemPromptHasTheRulesThenTheData() {
        assertTrue(system.startsWith(CoachPrompt.SYSTEM))
        assertTrue(system.endsWith("# The user's Platebit data"))
    }

    @Test
    fun claudeGetsCachedSystemAndAlternatingRoles() {
        val body = CoachRequests.anthropic("claude-haiku", system, chat)
        val sys = body["system"]!!.jsonArray.first().jsonObject
        assertEquals(system, sys.str("text"))
        assertEquals("ephemeral", sys["cache_control"]!!.jsonObject.str("type"))
        val roles = body["messages"]!!.jsonArray.map { it.jsonObject.str("role") }
        assertEquals(listOf("user", "assistant", "user"), roles)
        assertEquals("And protein?", body["messages"]!!.jsonArray.last().jsonObject.str("content"))
    }

    @Test
    fun geminiUsesModelRoleAndSystemInstruction() {
        val body = CoachRequests.gemini(system, chat)
        val sys = body["system_instruction"]!!.jsonObject["parts"]!!.jsonArray.first().jsonObject
        assertEquals(system, sys.str("text"))
        val roles = body["contents"]!!.jsonArray.map { it.jsonObject.str("role") }
        assertEquals(listOf("user", "model", "user"), roles)
        // Plain text answers, not the meal JSON
        assertNull(body["generationConfig"]!!.jsonObject["responseMimeType"])
    }

    @Test
    fun openAiStyleHasSystemFirstAndNoJsonMode() {
        val body = CoachRequests.openAiCompatible(Provider.DEEPSEEK, "deepseek-flash", system, chat, ThinkingLevel.HIGH)
        val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("system", "user", "assistant", "user"), messages.map { it.str("role") })
        assertEquals(system, messages.first().str("content"))
        assertNull(body["response_format"])
        assertEquals("enabled", body["thinking"]!!.jsonObject.str("type"))
        assertTrue(body.containsKey("temperature"))
    }

    @Test
    fun openAiNeverGetsATemperatureAndPlainDropsIt() {
        assertFalse(CoachRequests.openAiCompatible(Provider.OPENAI, "gpt-x-mini", system, chat, null).containsKey("temperature"))
        assertFalse(CoachRequests.openAiCompatible(Provider.CUSTOM, "m", system, chat, null, plain = true).containsKey("temperature"))
        assertNull(CoachRequests.openAiCompatible(Provider.CUSTOM, "m", system, chat, null)["thinking"])
    }
}
