package app.morsel.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.morsel.data.FoodFact
import app.morsel.domain.MealType
import app.morsel.ui.components.NumberStepper
import app.morsel.ui.components.PillSelector
import app.morsel.ui.dashboard.QuickAddDialog
import app.morsel.ui.theme.MorselTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The pieces people tap most, driven like a person would: no network, no database. */
@RunWith(AndroidJUnit4::class)
class ComponentsUiTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun pillSelectorSelectsWhatIsTapped() {
        var selected by mutableStateOf(MealType.BREAKFAST)
        rule.setContent { MorselTheme(dark = true) { PillSelector(MealType.entries, selected, { it.title }, { selected = it }) } }
        rule.onNodeWithText("Dinner").performClick()
        rule.runOnIdle { assertEquals(MealType.DINNER, selected) }
    }

    @Test
    fun numberStepperMovesByItsStep() {
        var weight by mutableDoubleStateOf(80.0)
        rule.setContent {
            MorselTheme(dark = true) { NumberStepper("Goal weight", weight, { weight = it }, 35.0..250.0, 0.5, "kg", decimals = 1) }
        }
        rule.onNodeWithContentDescription("Increase").performClick()
        rule.runOnIdle { assertEquals(80.5, weight, 0.0001) }
        rule.onNodeWithContentDescription("Decrease").performClick()
        rule.onNodeWithContentDescription("Decrease").performClick()
        rule.runOnIdle { assertEquals(79.5, weight, 0.0001) }
    }

    @Test
    fun quickAddSearchesPicksAndLogs() {
        val kitKat = FoodFact("1", "Kit Kat", "Nestle", 518.0, 6.0, 62.0, 26.0, servingGrams = 41.5, snack = true)
        var logged: Triple<String, Double, MealType>? = null
        rule.setContent {
            MorselTheme(dark = true) {
                QuickAddDialog(
                    search = { listOf(kitKat) },
                    onDismiss = {},
                    onLog = { _, name, grams, type -> logged = Triple(name, grams, type) },
                )
            }
        }
        rule.onNode(hasSetTextAction()).performTextInput("kit kat")
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Nestle Kit Kat").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Nestle Kit Kat").performClick()
        rule.onNodeWithText("Log it").performClick()
        rule.runOnIdle {
            val (name, grams, type) = logged!!
            assertEquals("Nestle Kit Kat", name)
            assertEquals(42.0, grams, 0.0) // the 41.5 g serving, shown and logged rounded
            assertEquals(MealType.SNACK, type) // a chocolate bar is a snack whatever the time
        }
    }

    @Test
    fun addFoodByHandWithoutTheDatabase() {
        var logged: List<Any>? = null
        rule.setContent {
            MorselTheme(dark = true) {
                QuickAddDialog(
                    search = null, // Open Food Facts off: it opens straight on typing it in
                    title = "Add food · Yesterday",
                    onDismiss = {},
                    onLog = { _, _, _, _ -> },
                    onLogManual = { name, kcal, protein, carbs, fat, type -> logged = listOf(name, kcal, protein, carbs, fat, type) },
                )
            }
        }
        rule.onNodeWithText("Add food · Yesterday").assertExists()
        // Nothing to log until there's a name and calories
        rule.onNodeWithText("Log it").performClick()
        rule.runOnIdle { assertEquals(null, logged) }
        rule.onNode(hasSetTextAction() and hasText("Food")).performTextInput("Homemade lasagna")
        rule.onNode(hasSetTextAction() and hasText("Calories (kcal)")).performTextInput("650")
        rule.onNode(hasSetTextAction() and hasText("Protein g")).performTextInput("32")
        rule.onNodeWithText("Dinner").performClick()
        rule.onNodeWithText("Log it").performClick()
        rule.runOnIdle { assertEquals(listOf("Homemade lasagna", 650.0, 32.0, 0.0, 0.0, MealType.DINNER), logged) }
    }
}