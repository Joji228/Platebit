package app.morsel.ui.body

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.morsel.AppContainer
import app.morsel.data.db.WeightEntity
import app.morsel.domain.BodyAnalysis
import app.morsel.domain.BodyReport
import app.morsel.domain.Metric
import app.morsel.domain.UserProfile
import app.morsel.domain.WeighIn
import app.morsel.domain.WeightTrend
import app.morsel.ui.components.ChartPoint
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import java.time.LocalDate
import kotlin.math.pow

/** One body-composition measurement from the scale. */
data class ScanEntry(val date: LocalDate, val timestamp: Long, val report: BodyReport)

data class BodyState(
    val loading: Boolean = true,
    val profile: UserProfile? = null,
    val today: LocalDate = LocalDate.now(),
    /** The newest weigh-in of any kind; drives weight and BMI. */
    val latest: BodyReport? = null,
    /** The newest weigh-in that came with impedance, and the one before it for the changes. */
    val latestScan: BodyReport? = null,
    val previousScan: BodyReport? = null,
    val latestScanDate: LocalDate? = null,
    /** Every measurement with body composition, oldest first, for swiping back through them. */
    val scans: List<ScanEntry> = emptyList(),
    val trendWeight: Double? = null,
    val change7: Double? = null,
    val change30: Double? = null,
    /** Each metric over time, oldest first, with its own smoothed trend. */
    val series: Map<Metric, List<ChartPoint>> = emptyMap(),
)

class BodyViewModel(private val app: AppContainer) : ViewModel() {

    val state: StateFlow<BodyState> = combine(app.plans.plan, app.clock.today) { plan, today ->
        val profile = plan.profile ?: return@combine BodyState(loading = false, today = today)
        build(profile, plan.weighIns, today)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BodyState()) // every weigh-in is analysed: keep it off the main thread

    private fun build(profile: UserProfile, weighIns: List<WeightEntity>, today: LocalDate): BodyState {
        if (weighIns.isEmpty()) return BodyState(loading = false, profile = profile, today = today)
        // Everything is worked out again from the stored weight and impedance, so improvements to
        // the formulas (and birthdays) apply to old weigh-ins too.
        val reports = weighIns.map { LocalDate.parse(it.date) to BodyAnalysis.report(it.kg, it.impedance, profile) }
        val scans = reports.filter { it.second.composition != null }

        val weightTrend = WeightTrend.compute(weighIns.map { WeighIn(LocalDate.parse(it.date), it.kg) })
        val m2 = (profile.heightCm / 100).pow(2)
        val series = buildMap {
            put(Metric.WEIGHT, weightTrend.map { ChartPoint(it.date, it.raw, it.trend) })
            put(Metric.BMI, weightTrend.map { ChartPoint(it.date, it.raw / m2, it.trend / m2) })
            if (scans.isNotEmpty()) {
                Metric.entries.filter { it != Metric.WEIGHT && it != Metric.BMI }.forEach { metric ->
                    val values = scans.mapNotNull { (date, r) -> r.value(metric)?.let { WeighIn(date, it) } }
                    put(metric, WeightTrend.compute(values).map { ChartPoint(it.date, it.raw, it.trend) })
                }
            }
        }
        val trendNow = weightTrend.last().trend
        fun changeSince(days: Long) = WeightTrend.at(weightTrend, today.minusDays(days))
            ?.takeIf { weightTrend.first().date <= today.minusDays(days) }
            ?.let { trendNow - it }

        return BodyState(
            loading = false,
            profile = profile,
            today = today,
            latest = reports.last().second,
            latestScan = scans.lastOrNull()?.second,
            previousScan = scans.getOrNull(scans.size - 2)?.second,
            latestScanDate = scans.lastOrNull()?.first,
            scans = weighIns.zip(reports).filter { it.second.second.composition != null }.map { (w, r) -> ScanEntry(r.first, w.timestamp, r.second) },
            trendWeight = trendNow,
            change7 = changeSince(7),
            change30 = changeSince(30),
            series = series,
        )
    }
}
