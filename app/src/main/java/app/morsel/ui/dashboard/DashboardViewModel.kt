package app.morsel.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.morsel.AppContainer
import app.morsel.data.db.MealWithItems
import app.morsel.data.db.WeightEntity
import app.morsel.data.db.macros
import app.morsel.domain.Macros
import app.morsel.domain.Nutrition
import app.morsel.domain.Targets
import app.morsel.domain.UserProfile
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
    val profile: UserProfile? = null,
    val targets: Targets? = null,
    val eaten: Macros = Macros(),
    val waterMl: Int = 0,
    val meals: List<MealWithItems> = emptyList(),
    val weights: List<WeightEntity> = emptyList(),
    val currentWeight: Double? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModel(private val app: AppContainer) : ViewModel() {
    private val date = MutableStateFlow(LocalDate.now())

    val state: StateFlow<DashboardState> = combine(
        date,
        app.settings.profile,
        app.repository.weights(),
        date.flatMapLatest { app.repository.mealsFor(it) },
        date.flatMapLatest { app.repository.waterFor(it) },
    ) { day, profile, weights, meals, water ->
        val current = weights.lastOrNull()?.kg ?: profile?.startWeightKg
        DashboardState(
            loading = false,
            date = day,
            profile = profile,
            targets = if (profile != null && current != null) Nutrition.targets(profile, current) else null,
            eaten = meals.fold(Macros()) { acc, meal -> acc + meal.macros() },
            waterMl = water,
            meals = meals,
            weights = weights,
            currentWeight = current,
        )
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
}
