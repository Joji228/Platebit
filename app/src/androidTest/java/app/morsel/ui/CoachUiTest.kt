package app.morsel.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.morsel.data.CoachMessage
import app.morsel.ui.coach.CoachContent
import app.morsel.ui.coach.CoachSuggestions
import app.morsel.ui.coach.coachMarkup
import app.morsel.ui.theme.MorselTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The coach chat without an AI engine: what's on screen for each state, and what taps send. */
@RunWith(AndroidJUnit4::class)
class CoachUiTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun suggestionsAndTypingSendQuestions() {
        val sent = mutableListOf<String>()
        rule.setContent {
            MorselTheme(dark = true) {
                CoachContent(emptyList(), thinking = false, error = null, loaded = true, onSend = { sent += it }, onRetry = {}, onNewChat = {}, onBack = {})
            }
        }
        rule.onNodeWithText("Ask about your progress").assertIsDisplayed()
        rule.onNodeWithText(CoachSuggestions.first()).performClick()
        // Send does nothing while the field is empty
        rule.onNodeWithContentDescription("Send").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("Is my protein OK?")
        rule.onNodeWithContentDescription("Send").performClick()
        rule.runOnIdle { assertEquals(listOf(CoachSuggestions.first(), "Is my protein OK?"), sent) }
    }

    @Test
    fun showsTheConversationThinkingAndErrors() {
        var thinking by mutableStateOf(true)
        var error by mutableStateOf<String?>(null)
        var retried = false
        var cleared = false
        val chat = listOf(CoachMessage(true, "How am I doing?"), CoachMessage(false, "You're down **1.2 kg** this month."))
        rule.setContent {
            MorselTheme(dark = true) {
                CoachContent(chat, thinking, error, loaded = true, onSend = {}, onRetry = { retried = true }, onNewChat = { cleared = true }, onBack = {})
            }
        }
        rule.onNodeWithText("How am I doing?").assertIsDisplayed()
        rule.onNodeWithText("You're down 1.2 kg this month.").assertIsDisplayed()
        rule.onNodeWithText("Thinking…").assertIsDisplayed()

        thinking = false
        error = "DeepSeek: no API key set. Add it in Settings → AI engine."
        rule.onNodeWithText("Retry").performClick()
        rule.runOnIdle { assertTrue(retried) }

        // New chat asks first
        rule.onNodeWithContentDescription("New chat").performClick()
        rule.onNodeWithText("Start a new chat?").assertIsDisplayed()
        rule.onNodeWithText("New chat").performClick()
        rule.runOnIdle { assertTrue(cleared) }
    }

    @Test
    fun markupMakesBoldAndBullets() {
        val text = coachMarkup("## Summary\nYou're **on track**.\n- Protein: 120 g\n* Water: 2 L", Color.White)
        assertEquals("Summary\nYou're on track.\n•  Protein: 120 g\n•  Water: 2 L", text.text)
        assertEquals(2, text.spanStyles.size) // the heading and "on track"
        assertEquals("on track", text.text.substring(text.spanStyles.last().start, text.spanStyles.last().end))
        // An unpaired ** is left alone
        assertEquals("5 ** 2", coachMarkup("5 ** 2", Color.White).text)
    }
}
