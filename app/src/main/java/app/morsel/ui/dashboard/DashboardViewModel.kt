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
import app.morsel.data.FoodFact
import app.morsel.domain.BodyCompositionCalc
import app.morsel.domain.Macros
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
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
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardState())

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

    fun logWeight(kg: Double) {
        viewModelScope.launch { app.repository.logWeight(kg) }
    }

    /** Off by default; the Weigh in button and the Body screen only appear once it's turned on in Settings. */
    val scaleEnabled = app.settings.scaleEnabled

    val scale get() = app.scale

    /** Live frames from the smart scale for as long as the weigh-in dialog is open. */
    fun scaleReadings() = app.scale.readings()

    fun setManualWeighInOpen(open: Boolean) {
        app.manualWeighInOpen.value = open
    }

    /** Saves a smart-scale weigh-in; body composition is worked out here from the profile. */
    fun logScaleWeighIn(kg: Double, impedance: Int?) {
        val profile = state.value.profile
        val composition = profile?.let { BodyCompositionCalc.compute(kg, impedance, it) }
        viewModelScope.launch { app.repository.logScaleWeighIn(kg, impedance, composition) }
    }

    /** Quick add is offered only to users who turned on Open Food Facts. */
    val foodFactsEnabled = app.settings.useFoodFacts

    suspend fun searchFoods(query: String): List<FoodFact> = app.foodFacts.search(query)

    /** Logs one Open Food Facts product as its own meal, for today like every new meal. */
    fun quickLog(fact: FoodFact, name: String, grams: Double, type: MealType) {
        viewModelScope.launch {
            val today = app.clock.today.value
            app.repository.saveMeal(
                MealEntity(
                    date = today.toString(),
                    timestamp = System.currentTimeMillis(),
                    mealType = type.name,
                    name = name,
                    photoPath = null,
                    source = "Open Food Facts",
                    note = null,
                ),
                listOf(
                    FoodItemEntity(
                        mealId = 0,
                        name = fact.label,
                        grams = grams,
                        kcalPer100 = fact.kcalPer100,
                        proteinPer100 = fact.proteinPer100,
                        carbsPer100 = fact.carbsPer100,
                        fatPer100 = fact.fatPer100,
                    ),
                ),
            )
            app.viewedDay.value = today
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
