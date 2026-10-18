package app.morsel.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiSettingsTest {
    private val keys = mapOf(Provider.DEEPSEEK to "ds-key", Provider.GEMINI to "gm-key", Provider.OPENAI to "oa-key")

    @Test
    fun backupIsOffByDefault() {
        val ai = AiSettings(keys = keys)
        assertFalse(ai.fallback)
        assertNull(ai.fallbackTarget)
    }

    @Test
    fun backupPicksAnotherReadyEngine() {
        val ai = AiSettings(primary = Provider.DEEPSEEK, keys = keys, fallback = true)
        // Order of the enum: Gemini comes before OpenAI.
        assertEquals(Provider.GEMINI, ai.fallbackTarget)
        // An explicit choice wins when it is ready.
        assertEquals(Provider.OPENAI, ai.copy(fallbackProvider = Provider.OPENAI).fallbackTarget)
    }

    @Test
    fun backupNeverRepeatsThePrimaryOrUsesAnEngineWithoutKey() {
        val ai = AiSettings(primary = Provider.DEEPSEEK, keys = mapOf(Provider.DEEPSEEK to "ds-key"), fallback = true)
        assertNull(ai.fallbackTarget)
        val pointsAtPrimary = AiSettings(primary = Provider.GEMINI, keys = keys, fallback = true, fallbackProvider = Provider.GEMINI)
        assertEquals(Provider.DEEPSEEK, pointsAtPrimary.fallbackTarget)
        val noKey = AiSettings(primary = Provider.DEEPSEEK, keys = keys, fallback = true, fallbackProvider = Provider.ANTHROPIC)
        assertEquals(Provider.GEMINI, noKey.fallbackTarget)
    }

    @Test
    fun customNeedsAnAddressAsWellAsAKey() {
        val noUrl = AiSettings(keys = mapOf(Provider.CUSTOM to "k"))
        assertFalse(noUrl.isReady(Provider.CUSTOM))
        val ready = noUrl.copy(customBaseUrl = " https://openrouter.ai/api/v1/ ")
        assertTrue(ready.isReady(Provider.CUSTOM))
        assertEquals("https://openrouter.ai/api/v1", ready.baseUrlFor(Provider.CUSTOM))
        assertEquals("https://api.openai.com/v1", ready.baseUrlFor(Provider.OPENAI))
    }

    @Test
    fun olderSavedKeysKeepTheirStorageNames() {
        // DeepSeek and Gemini keys saved by earlier versions live under these names.
        assertEquals("deepseek", Provider.DEEPSEEK.prefix)
        assertEquals("gemini", Provider.GEMINI.prefix)
    }
}
