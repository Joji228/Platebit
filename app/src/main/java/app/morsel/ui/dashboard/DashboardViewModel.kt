package app.morsel.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.morsel.AppContainer
import app.morsel.data.PlanState
import app.morsel.data.db.MealWithItems
import app.morsel.data.db.macros
import app.morsel.domain.Macros
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
    private val date = MutableStateFlow(LocalDate.now())

    // The date travels inside each emission, so the day and its meals always change together;
    // otherwise a swipe could briefly show the new date over the old day's meals.
    val state: StateFlow<DashboardState> = date.flatMapLatest { day ->
        combine(app.plans.plan, app.repository.mealsFor(day), app.repository.waterFor(day)) { plan, meals, water ->
            DashboardState(
                loading = false,
                date = day,
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
        if (date.value < LocalDate.now()) date.value = date.value.plusDays(1)
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

    fun deleteWeight(id: Long) {
        viewModelScope.launch { app.repository.deleteWeight(id) }
    }
}
