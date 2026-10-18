package app.morsel.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * The current calendar day, updated when midnight passes. Checks twice a minute rather than
 * sleeping until midnight, because timers pause while the phone sleeps; [refresh] is also
 * called whenever the app comes back to the screen, so the date is right the moment you look.
 */
class DayClock(scope: CoroutineScope) {
    private val _today = MutableStateFlow(LocalDate.now())
    val today: StateFlow<LocalDate> = _today

    init {
        scope.launch {
            while (isActive) {
                delay(30_000)
                refresh()
            }
        }
    }

    fun refresh() {
        _today.value = LocalDate.now()
    }
}
