package app.morsel.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CoachActionsTest {
    private val today = LocalDate.of(2026, 10, 20)

    @Test
    fun takesTheBlockOutOfTheTextAndReadsAFood() {
        val raw = """Here's your lunch, tap **Accept** to log it.
<actions>[{"type":"log_food","meal_name":"Chicken salad","meal_type":"lunch","items":[
 {"name":"Grilled chicken","grams":150,"kcal_per_100g":165,"protein_per_100g":31,"carbs_per_100g":0,"fat_per_100g":3.6},
 {"name":"Olive oil","grams":10,"kcal_per_100g":884,"protein_per_100g":0,"carbs_per_100g":0,"fat_per_100g":100}]}]</actions>"""
        val p = CoachActions.parse(raw, today)
        assertEquals("Here's your lunch, tap **Accept** to log it.", p.text)
        val a = p.actions.single()
        assertEquals(ActionType.LOG_FOOD, a.type)
        assertEquals("Chicken salad", a.name)
        assertEquals("lunch", a.mealType)
        assertEquals(today.toString(), a.date)
        assertEquals(2, a.foods.size)
        assertEquals(247.5 + 88.4, a.macros.kcal, 0.01)
        assertEquals(ActionStatus.PENDING, a.status)
        assertEquals("Log lunch: Chicken salad", CoachActions.title(a))
        assertEquals("336 kcal · P 47 g · C 0 g · F 15 g\nGrilled chicken 150 g, Olive oil 10 g", CoachActions.detail(a, today))
    }

    @Test
    fun readsEveryTypeAndClampsValues() {
        val raw = """Done-ish <actions>[
            {"type":"add_water","ml":500},
            {"type":"log_weight","kg":121.04,"date":"2026-10-19"},
            {"type":"set_calorie_target","kcal":200},
            {"type":"set_calorie_target","automatic":true},
            {"type":"set_water_goal","ml":3000}
        ]</actions>"""
        val a = CoachActions.parse(raw, today).actions
        assertEquals(listOf(ActionType.ADD_WATER, ActionType.LOG_WEIGHT, ActionType.SET_CALORIES, ActionType.SET_CALORIES, ActionType.SET_WATER_GOAL), a.map { it.type })
        assertEquals(500, a[0].ml)
        assertEquals(121.0, a[1].kg!!, 0.0001)
        assertEquals("2026-10-19", a[1].date)
        assertEquals("yesterday", CoachActions.detail(a[1], today))
        assertEquals(800, a[2].kcal) // clamped up to the safe minimum
        assertNull(a[3].kcal) // back to automatic
        assertEquals("Set the water goal to 3.0 L", CoachActions.title(a[4]))
    }

    @Test
    fun dropsNonsenseAndFutureDates() {
        val raw = """<actions>[
            {"type":"launch_rocket"},
            {"type":"add_water","ml":90000},
            {"type":"log_weight","kg":5},
            {"type":"log_food","items":[]},
            {"type":"add_water","ml":250,"date":"2030-01-01"},
            {"type":"log_food","meal_name":"x","items":[{"name":"Butter","grams":20,"kcal_per_100g":5000,"protein_per_100g":300}]},
            {"type":"delete_meal","meal_id":42},
            {"type":"set_goal_weight","kg":75}
        ]</actions>"""
        val a = CoachActions.parse(raw, today).actions
        assertEquals(4, a.size)
        assertEquals(today.toString(), a[0].date) // a future date becomes today
        assertEquals(900.0, a[1].foods.single().kcalPer100, 0.0)
        assertEquals(100.0, a[1].foods.single().proteinPer100, 0.0)
        assertEquals(42L, a[2].mealId)
        assertEquals(75.0, a[3].kg!!, 0.0)
    }

    @Test
    fun noBlockMeansNoActionsAndBrokenJsonIsSafe() {
        assertTrue(CoachActions.parse("Your trend is **83.9 kg**.", today).actions.isEmpty())
        val broken = CoachActions.parse("Sure. <actions>[{\"type\":\"add_water\", ml: </actions>", today)
        assertEquals("Sure.", broken.text)
        assertTrue(broken.actions.isEmpty())
        // An unclosed block (the answer was cut off) still leaves the text clean.
        assertEquals("Okay", CoachActions.parse("Okay <actions>[{\"type\":\"add_water\",\"ml\":300}", today).text)
    }

    @Test
    fun acceptsAFencedJsonBlockAndCapsTheCount() {
        val fenced = "Here you go\n```json\n{\"actions\":[{\"type\":\"add_water\",\"ml\":300}]}\n```"
        val p = CoachActions.parse(fenced, today)
        assertEquals("Here you go", p.text)
        assertEquals(300, p.actions.single().ml)
        val many = "<actions>[" + (1..9).joinToString(",") { "{\"type\":\"add_water\",\"ml\":100}" } + "]</actions>"
        assertEquals(CoachActions.MAX_ACTIONS, CoachActions.parse(many, today).actions.size)
    }

    @Test
    fun readsPortionChoices() {
        val raw = """<actions>[{"type":"log_food","meal_name":"Banana","items":[
          {"name":"Banana","grams":120,"kcal_per_100g":89,"portions":[
            {"label":"1 small","grams":100},{"label":"1 medium","grams":120},{"label":"1 large","grams":150},
            {"label":"duplicate","grams":120},{"label":"","grams":200},{"label":"far too many","grams":250},{"grams":-5}]},
          {"name":"Coffee","grams":200,"kcal_per_100g":2}]}]</actions>"""
        val foods = CoachActions.parse(raw, today).actions.single().foods
        assertEquals(
            listOf(Portion("1 small", 100.0), Portion("1 medium", 120.0), Portion("1 large", 150.0), Portion("200 g", 200.0)),
            foods[0].portions,
        )
        assertEquals(120.0, foods[0].grams, 0.0)
        assertTrue(foods[1].portions.isEmpty())
    }

    @Test
    fun summaryTellsTheCoachWhatHappened() {
        val a = CoachAction(ActionType.ADD_WATER, date = today.toString(), ml = 500)
        assertEquals("Add 500 ml of water: waiting for the user", CoachActions.summary(a))
        assertEquals("Add 500 ml of water: accepted, done", CoachActions.summary(a.copy(status = ActionStatus.ACCEPTED)))
        assertEquals("Add 500 ml of water: declined by the user", CoachActions.summary(a.copy(status = ActionStatus.DECLINED)))
    }
}
