package app.morsel.ui.coach

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.morsel.AppContainer
import app.morsel.data.ActionStatus
import app.morsel.data.ActionType
import app.morsel.data.CoachAction
import app.morsel.data.CoachActions
import app.morsel.data.CoachContext
import app.morsel.data.CoachMessage
import app.morsel.data.CoachLookup
import app.morsel.data.FoodFact
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import app.morsel.data.EntryTime
import app.morsel.data.db.FoodItemEntity
import app.morsel.data.db.MealEntity
import app.morsel.data.db.macros
import app.morsel.domain.MealType
import app.morsel.domain.UserProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale
import kotlin.math.roundToInt

class CoachViewModel(private val app: AppContainer) : ViewModel() {
    val messages = MutableStateFlow<List<CoachMessage>>(emptyList())

    /** True while the coach is working on an answer. */
    val thinking = MutableStateFlow(false)

    /** What the coach is doing beyond thinking, like "Checking Open Food Facts…"; null otherwise. */
    val activity = MutableStateFlow<String?>(null)

    /** Why the last question got no answer; shown in the chat with a Retry button. */
    val error = MutableStateFlow<String?>(null)

    /** When the newest answer arrived, while it's still being typed out on screen; null otherwise. */
    val typing = MutableStateFlow<Long?>(null)

    /** False until the saved conversation has been read, so the empty state doesn't flash first. */
    val loaded = MutableStateFlow(false)

    private var job: Job? = null

    init {
        // Turned off (or everything erased) while this chat is alive: its file is gone, so forget it here too.
        viewModelScope.launch {
            app.settings.coachEnabled.collect { on -> if (!on) newChat() }
        }
        viewModelScope.launch {
            messages.value = app.coach.load()
            loaded.value = true
            // Left mid-question last time (the app was closed before the answer came): ask again.
            if (messages.value.lastOrNull()?.fromUser == true) ask()
        }
    }

    fun send(text: String) {
        val question = text.trim()
        if (question.isEmpty() || thinking.value || !loaded.value) return
        // Writing again instead of answering a card counts as declining it.
        messages.value = messages.value.map { m ->
            if (m.actions.none { it.status == ActionStatus.PENDING }) m
            else m.copy(actions = m.actions.map { if (it.status == ActionStatus.PENDING) it.copy(status = ActionStatus.DECLINED) else it })
        } + CoachMessage(fromUser = true, text = question)
        viewModelScope.launch { app.coach.save(messages.value) }
        ask()
    }

    fun retry() {
        if (!thinking.value && messages.value.lastOrNull()?.fromUser == true) ask()
    }

    fun typed() {
        typing.value = null
    }

    fun newChat() {
        job?.cancel()
        typing.value = null
        thinking.value = false
        error.value = null
        messages.value = emptyList()
        viewModelScope.launch { app.coach.clear() }
    }

    /** The user tapped Accept on a proposed change: only now is anything in the app changed. */
    fun accept(messageAt: Long, index: Int) {
        val action = actionAt(messageAt, index) ?: return
        if (action.status != ActionStatus.PENDING) return
        // Marked straight away, so a double tap can't run it twice.
        update(messageAt, index, action.copy(status = ActionStatus.ACCEPTED))
        viewModelScope.launch {
            val done = try {
                action.copy(status = ActionStatus.ACCEPTED, result = perform(action))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                action.copy(status = ActionStatus.FAILED, result = e.message ?: "something went wrong")
            }
            update(messageAt, index, done)
            app.coach.save(messages.value)
        }
    }

    /** Picking another amount for one food on a card that's still waiting; the totals follow. */
    fun setGrams(messageAt: Long, index: Int, foodIndex: Int, grams: Double) {
        val action = actionAt(messageAt, index) ?: return
        if (action.status != ActionStatus.PENDING || foodIndex !in action.foods.indices) return
        val amount = grams.coerceIn(1.0, 5_000.0)
        update(messageAt, index, action.copy(foods = action.foods.mapIndexed { i, f -> if (i == foodIndex) f.copy(grams = amount) else f }))
        viewModelScope.launch { app.coach.save(messages.value) }
    }

    fun decline(messageAt: Long, index: Int) {
        val action = actionAt(messageAt, index) ?: return
        if (action.status != ActionStatus.PENDING) return
        update(messageAt, index, action.copy(status = ActionStatus.DECLINED))
        viewModelScope.launch { app.coach.save(messages.value) }
    }

    private fun actionAt(messageAt: Long, index: Int) = messages.value.firstOrNull { it.at == messageAt }?.actions?.getOrNull(index)

    private fun update(messageAt: Long, index: Int, action: CoachAction) {
        messages.value = messages.value.map { m ->
            if (m.at != messageAt) m else m.copy(actions = m.actions.mapIndexed { i, a -> if (i == index) action else a })
        }
    }

    private fun ask() {
        thinking.value = true
        error.value = null
        job = viewModelScope.launch {
            try {
                val context = withContext(Dispatchers.Default) {
                    val data = app.repository.export(null, null)
                    CoachContext.build(LocalDateTime.now(), app.plans.plan.first(), data.meals, data.items, data.water, data.weights)
                }
                // With the food database on, the coach may first ask to look products up; the
                // results go back to it (never into the chat) and it answers from the label values.
                val lookups = app.settings.useFoodFacts.first()
                val found = mutableMapOf<String, FoodFact>()
                var history = messages.value
                var round = 0
                var reply = app.ai.chat(context, history, lookups)
                while (lookups && round < CoachLookup.MAX_ROUNDS) {
                    val queries = CoachLookup.queries(reply.text)
                    if (queries.isEmpty()) break
                    activity.value = "Checking Open Food Facts…"
                    val results = coroutineScope {
                        queries.map { q ->
                            async { q to withTimeoutOrNull(8_000) { runCatching { app.foodFacts.search(q, CoachLookup.RESULTS_PER_QUERY) }.getOrNull() } }
                        }.awaitAll()
                    }
                    results.forEach { (_, facts) -> facts?.forEach { if (it.code.isNotBlank()) found[it.code] = it } }
                    history = history + CoachMessage(fromUser = false, text = reply.text) + CoachMessage(fromUser = true, text = CoachLookup.results(results))
                    activity.value = null
                    round++
                    reply = app.ai.chat(context, history, lookups)
                }
                val parsed = CoachActions.parse(CoachLookup.strip(reply.text), LocalDate.now())
                val actions = parsed.actions.mapNotNull { prepare(it) }.map { CoachActions.withLabelValues(it, found) }
                val text = parsed.text.ifBlank {
                    if (actions.isNotEmpty()) "Here's what I've prepared:" else "I couldn't prepare that. Could you say it another way?"
                }
                val answer = CoachMessage(fromUser = false, text = text, actions = actions)
                typing.value = answer.at
                messages.value = messages.value + answer
                app.coach.save(messages.value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = e.message ?: "Couldn't reach the AI engine"
            } finally {
                thinking.value = false
                activity.value = null
            }
        }
    }

    /** Fills in what the card shows for a meal to delete, and drops proposals for meals that don't exist. */
    private suspend fun prepare(action: CoachAction): CoachAction? {
        if (action.type != ActionType.DELETE_MEAL) return action
        val meal = app.repository.meal(action.mealId ?: return null) ?: return null
        return action.copy(name = meal.meal.name, date = meal.meal.date, kcal = meal.macros().kcal.roundToInt())
    }

    /** Makes the change and returns the line shown under the coach's message. Throws if it can't. */
    private suspend fun perform(a: CoachAction): String {
        val today = LocalDate.now()
        val date = a.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: today
        fun stamp(time: LocalTime) = EntryTime.stamp(date, time, today)
        return when (a.type) {
            ActionType.LOG_FOOD -> {
                val type = MealType.fromAi(a.mealType) ?: MealType.forTime()
                val time = EntryTime.meal(type)
                val name = a.name ?: a.foods.first().name
                app.repository.saveMeal(
                    MealEntity(date = date.toString(), timestamp = stamp(time), mealType = type.name, name = name, source = "AI coach"),
                    a.foods.map { FoodItemEntity(mealId = 0, name = it.name, grams = it.grams, kcalPer100 = it.kcalPer100, proteinPer100 = it.proteinPer100, carbsPer100 = it.carbsPer100, fatPer100 = it.fatPer100) },
                )
                "Logged ${type.title.lowercase()}: $name · ${a.macros.kcal.roundToInt()} kcal"
            }
            ActionType.DELETE_MEAL -> {
                val meal = app.repository.meal(a.mealId ?: error("no meal")) ?: error("that meal is already gone")
                app.repository.deleteMeal(meal.meal.id)
                "Deleted ${meal.meal.name}"
            }
            ActionType.ADD_WATER -> {
                val ml = a.ml ?: error("no amount")
                if (date == today) app.repository.addWater(date, ml) else app.repository.addWater(date, ml, stamp(LocalTime.NOON))
                "Added $ml ml of water"
            }
            ActionType.LOG_WEIGHT -> {
                val kg = a.kg ?: error("no weight")
                if (date == today) app.repository.logWeight(kg) else app.repository.logWeight(kg, date, stamp(EntryTime.WEIGH_IN))
                "Logged ${String.format(Locale.US, "%.1f", kg)} kg"
            }
            ActionType.SET_CALORIES -> {
                updateProfile { it.copy(customKcal = a.kcal) }
                if (a.kcal == null) "Daily calories are automatic again" else "Daily calories set to ${a.kcal} kcal"
            }
            ActionType.SET_WATER_GOAL -> {
                updateProfile { it.copy(customWaterMl = a.ml) }
                if (a.ml == null) "Water goal is automatic again" else "Water goal set to ${String.format(Locale.US, "%.1f", a.ml / 1000.0)} L"
            }
            ActionType.SET_GOAL_WEIGHT -> {
                val kg = a.kg ?: error("no weight")
                updateProfile { it.copy(goalWeightKg = kg) }
                "Goal weight set to ${String.format(Locale.US, "%.1f", kg)} kg"
            }
        }
    }

    private suspend fun updateProfile(change: (UserProfile) -> UserProfile) {
        val profile = app.settings.profile.first() ?: error("no profile set up yet")
        app.settings.saveProfile(change(profile))
    }
}
