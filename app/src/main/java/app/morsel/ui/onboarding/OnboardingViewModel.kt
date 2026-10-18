package app.morsel.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.morsel.AppContainer
import app.morsel.domain.Nutrition
import app.morsel.domain.Targets
import app.morsel.domain.UserProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

enum class Step { WELCOME, BASICS, WEIGHT, ACTIVITY, PACE, PLAN }

data class OnboardingState(
    val loaded: Boolean = false,
    val edit: Boolean = false,
    val step: Step = Step.WELCOME,
    val profile: UserProfile = UserProfile(),
    val currentWeight: Double = UserProfile().startWeightKg,
    /** Measured-burn correction from the smart target, so the plan preview matches the dashboard. */
    val tdeeScale: Double = 1.0,
) {
    val targets: Targets get() = Nutrition.targets(profile, currentWeight, if (profile.smartTarget) tdeeScale else 1.0)
    val maintaining: Boolean get() = abs(profile.goalWeightKg - currentWeight) < 0.3
    val steps: List<Step>
        get() = Step.entries.filter { (it != Step.WELCOME || !edit) && (it != Step.PACE || !maintaining) }
}

class OnboardingViewModel(private val app: AppContainer, edit: Boolean) : ViewModel() {
    private val _state = MutableStateFlow(OnboardingState(edit = edit, step = if (edit) Step.BASICS else Step.WELCOME))
    val state: StateFlow<OnboardingState> = _state
    private var savedWeight: Double? = null

    init {
        viewModelScope.launch {
            val existing = app.settings.profile.first()
            val plan = app.plans.plan.first()
            val latest = app.repository.weights().first().lastOrNull()?.kg
            savedWeight = latest ?: existing?.startWeightKg
            _state.update {
                it.copy(
                    loaded = true,
                    profile = existing ?: it.profile,
                    currentWeight = savedWeight ?: it.currentWeight,
                    tdeeScale = plan.tdeeScale,
                )
            }
        }
    }

    fun update(transform: (UserProfile) -> UserProfile) = _state.update { it.copy(profile = transform(it.profile)) }

    fun setCurrentWeight(kg: Double) = _state.update { it.copy(currentWeight = kg) }

    fun next() = move(1)

    /** Returns false when already on the first step, so the caller can leave the screen. */
    fun back(): Boolean {
        val s = _state.value
        if (s.steps.indexOf(s.step) <= 0) return false
        move(-1)
        return true
    }

    private fun move(delta: Int) = _state.update { s ->
        val steps = s.steps
        val index = (steps.indexOf(s.step) + delta).coerceIn(0, steps.lastIndex)
        s.copy(step = steps[index])
    }

    fun finish(onDone: () -> Unit) {
        val s = _state.value
        viewModelScope.launch {
            val profile = if (s.edit) s.profile else s.profile.copy(startWeightKg = s.currentWeight)
            app.settings.saveProfile(profile)
            if (savedWeight == null || abs((savedWeight ?: 0.0) - s.currentWeight) >= 0.05) {
                app.repository.logWeight(s.currentWeight)
            }
            onDone()
        }
    }
}
