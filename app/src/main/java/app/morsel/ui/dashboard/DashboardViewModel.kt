package app.morsel.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.morsel.AppContainer
import app.morsel.data.PlanState
import app.morsel.data.db.MealWithItems
import app.morsel.data.db.macros
import app.morsel.domain.MealType
import app.morsel.data.db.FoodItemEntity
import app.morsel.data.db.MealEntity
import app.morsel.data.EntryTime
import app.morsel.data.FoodSource
import app.morsel.data.FoodFact
import app.morsel.domain.Macros
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class DashboardState(
    val loading: Boolean = true,
    val date: LocalDate = LocalDate.now(),
    /** The real current day; changes at midnight. */
    val today: LocalDate = LocalDate.now(),
    val plan: PlanState = PlanState(),
    val eaten: Macros = Macros(),
    val waterMl: Int = 0,
    val meals: List<MealWithItems> = emptyList(),
) {
    val profile get() = plan.profile
    val targets get() = plan.targets
    val currentWeight get() = plan.currentWeight
}

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModel(private val app: AppContainer) : ViewModel() {
    private val date = app.viewedDay

    // The date travels inside each emission, so the day and its meals always change together;
    // otherwise a swipe could briefly show the new date over the old day's meals.
    val state: StateFlow<DashboardState> = date.flatMapLatest { day ->
        combine(
            app.plans.plan,
            app.repository.mealsFor(day),
            app.repository.waterFor(day),
            app.clock.today,
        ) { plan, meals, water, today ->
            DashboardState(
                loading = false,
                date = day,
                today = today,
                plan = plan,
                eaten = meals.fold(Macros()) { acc, meal -> acc + meal.macros() },
                waterMl = water,
                meals = meals,
            )
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardState())

    fun previousDay() {
        date.value = date.value.minusDays(1)
    }

    fun nextDay() {
        if (date.value < app.clock.today.value) date.value = date.value.plusDays(1)
    }

    fun addWater(ml: Int) {
        viewModelScope.launch { app.repository.addWater(date.value, ml) }
    }

    fun undoWater() {
        viewModelScope.launch { app.repository.undoWater(date.value) }
    }

    /** A typed-in weigh-in for [date], the day being viewed. The smart scale only ever logs today. */
    fun logWeight(kg: Double, date: LocalDate) {
        viewModelScope.launch {
            val today = app.clock.today.value
            if (date == today) app.repository.logWeight(kg) else app.repository.logWeight(kg, date, EntryTime.stamp(date, EntryTime.WEIGH_IN, today))
        }
    }

    /** Off by default; the Body screen and composition line only appear once it's turned on in Settings. */
    val scaleEnabled = app.settings.scaleEnabled

    /** Quick add is offered only to users who turned on Open Food Facts. */
    val foodFactsEnabled = app.settings.useFoodFacts

    suspend fun searchFoods(query: String): List<FoodFact> = app.foodFacts.search(query)

    /** Logs one Open Food Facts product as its own meal, on [date] (the day being viewed). */
    fun quickLog(fact: FoodFact, name: String, grams: Double, type: MealType, date: LocalDate) {
        logMeal(
            date, type, name, "Open Food Facts",
            FoodItemEntity(
                mealId = 0,
                name = fact.label,
                grams = grams,
                kcalPer100 = fact.kcalPer100,
                proteinPer100 = fact.proteinPer100,
                carbsPer100 = fact.carbsPer100,
                fatPer100 = fact.fatPer100,
            ),
        )
    }

    /**
     * A food typed in by hand: its total calories and macros. Stored as a 100 g item so the
     * per-100 g values are the totals and editing it later works like any other meal.
     */
    fun manualLog(name: String, kcal: Double, protein: Double, carbs: Double, fat: Double, type: MealType, date: LocalDate) {
        logMeal(
            date, type, name, FoodSource.BY_HAND,
            FoodItemEntity(mealId = 0, name = name, grams = 100.0, kcalPer100 = kcal, proteinPer100 = protein, carbsPer100 = carbs, fatPer100 = fat),
        )
    }

    private fun logMeal(date: LocalDate, type: MealType, name: String, source: String, item: FoodItemEntity) {
        viewModelScope.launch {
            app.repository.saveMeal(
                MealEntity(
                    date = date.toString(),
                    timestamp = EntryTime.stamp(date, EntryTime.meal(type), today = app.clock.today.value),
                    mealType = type.name,
                    name = name,
                    photoPath = null,
                    source = source,
                    note = null,
                ),
                listOf(item),
            )
        }
    }

    /** Removes a meal and its photo. */
    fun deleteMeal(id: Long) {
        viewModelScope.launch { app.repository.deleteMeal(id) }
    }

    fun deleteWeight(id: Long) {
        viewModelScope.launch { app.repository.deleteWeight(id) }
    }
}
