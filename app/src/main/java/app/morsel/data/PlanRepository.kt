package app.morsel.data

import app.morsel.data.db.WeightEntity
import app.morsel.domain.AdaptiveEstimate
import app.morsel.domain.AdaptiveTdee
import app.morsel.domain.DayIntake
import app.morsel.domain.Nutrition
import app.morsel.domain.Targets
import app.morsel.domain.TrendPoint
import app.morsel.domain.UserProfile
import app.morsel.domain.WeighIn
import app.morsel.domain.WeightTrend
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.LocalDate

/** Everything the targets depend on, computed in one place so every screen agrees. */
data class PlanState(
    val profile: UserProfile? = null,
    val weighIns: List<WeightEntity> = emptyList(),
    val trend: List<TrendPoint> = emptyList(),
    /** Smoothed trend weight, or the starting weight before any weigh-in. */
    val currentWeight: Double? = null,
    /** Null when smart target is off. */
    val estimate: AdaptiveEstimate? = null,
    val tdeeScale: Double = 1.0,
    val targets: Targets? = null,
)

class PlanRepository(private val settings: SettingsRepository, private val repository: MorselRepository) {

    val plan: Flow<PlanState> = LocalDate.now().let { today ->
        combine(
            settings.profile,
            repository.weights(),
            repository.dailyKcal(today.minusDays(AdaptiveTdee.WINDOW_DAYS + 7L), today),
        ) { profile, weights, daily ->
            val weighIns = weights.map { WeighIn(LocalDate.parse(it.date), it.kg) }
            val trend = WeightTrend.compute(weighIns)
            val current = trend.lastOrNull()?.trend ?: profile?.startWeightKg
            if (profile == null || current == null) return@combine PlanState(profile, weights, trend, current)

            val formula = Nutrition.tdee(profile, current)
            val estimate = if (profile.smartTarget) {
                AdaptiveTdee.estimate(weighIns, daily.map { DayIntake(LocalDate.parse(it.date), it.kcal) }, formula, today)
            } else {
                null
            }
            val scale = estimate?.let { AdaptiveTdee.blend(formula, it) / formula } ?: 1.0
            PlanState(profile, weights, trend, current, estimate, scale, Nutrition.targets(profile, current, scale))
        }
    }
}
