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
import app.morsel.data.ActionStatus
import app.morsel.data.ActionType
import app.morsel.data.CoachAction
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
    fun proposedChangesWaitForAcceptOrDecline() {
        val water = CoachAction(ActionType.ADD_WATER, date = java.time.LocalDate.now().toString(), ml = 500)
        val weight = CoachAction(ActionType.LOG_WEIGHT, date = java.time.LocalDate.now().toString(), kg = 121.0, status = ActionStatus.ACCEPTED, result = "Logged 121.0 kg")
        val chat = listOf(
            CoachMessage(true, "Log my weight and a bottle of water", at = 1L),
            CoachMessage(false, "Ready, tap Accept.", at = 2L, actions = listOf(weight, water)),
        )
        val accepted = mutableListOf<Pair<Long, Int>>()
        val declined = mutableListOf<Pair<Long, Int>>()
        rule.setContent {
            MorselTheme(dark = true) {
                CoachContent(
                    chat, thinking = false, error = null, loaded = true, onSend = {}, onRetry = {}, onNewChat = {}, onBack = {},
                    onAccept = { at, i -> accepted += at to i }, onDecline = { at, i -> declined += at to i },
                )
            }
        }
        // The answered one shows as a line in the chat; the open one as a card with both buttons.
        rule.onNodeWithText("Logged 121.0 kg").assertIsDisplayed()
        rule.onNodeWithText("Add 500 ml of water").assertIsDisplayed()
        rule.onNodeWithText("Accept").performClick()
        rule.onNodeWithText("Decline").performClick()
        rule.runOnIdle {
            assertEquals(listOf(2L to 1), accepted)
            assertEquals(listOf(2L to 1), declined)
        }
    }

    @Test
    fun markupMakesBoldAndBullets() {
        val text = coachMarkup("## Summary\nYou're **on track**.\n- Protein: 120 g\n* Water: 2 L", Color.White)
        assertEquals("Summary\nYou're on track.\n•  Protein: 120 g\n•  Water: 2 L", text.text)
        // The heading, "on track", and the numbers with units, which are bold even unmarked
        val bold = text.spanStyles.map { text.text.substring(it.start, it.end) }
        assertEquals(listOf("Summary", "on track", "120 g", "2 L"), bold)
        // __text__ counts as bold too, and plain numbers without a unit stay plain
        val other = coachMarkup("Eat __more protein__ on 3 days, about 1,850 kcal or 31%", Color.White)
        assertEquals(listOf("more protein", "1,850 kcal", "31%"), other.spanStyles.map { other.text.substring(it.start, it.end) })
        // An unpaired ** is left alone
        assertEquals("5 ** 2", coachMarkup("5 ** 2", Color.White).text)
    }
}
