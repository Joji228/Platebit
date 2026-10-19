package app.morsel

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.morsel.ai.AiService
import app.morsel.ai.AnalysisResult
import app.morsel.ble.MiScaleScanner
import app.morsel.data.DayClock
import app.morsel.data.MorselRepository
import app.morsel.data.PhotoStore
import app.morsel.data.PlanRepository
import app.morsel.data.SettingsRepository
import app.morsel.data.StorageInfo
import app.morsel.data.db.MorselDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate

class MorselApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** A scan waiting to be reviewed on the results screen; [date] is the real current day it was taken on. */
data class MealDraft(val photos: List<File>, val note: String?, val result: AnalysisResult?, val date: LocalDate)

/** Hand-wired dependencies; small enough that a DI framework isn't worth the build time. */
class AppContainer(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val database = Room.databaseBuilder(context, MorselDatabase::class.java, "morsel.db")
        .addMigrations(*MorselDatabase.MIGRATIONS)
        .build()
    val clock = DayClock(scope)
    val settings = SettingsRepository(context)
    val repository = MorselRepository(database)
    val plans = PlanRepository(settings, repository, clock)
    val photos = PhotoStore(context)
    val storage = StorageInfo(context)
    val ai = AiService(settings)
    val scale = MiScaleScanner(context)

    /** True while the manual Weigh in dialog is open, so the automatic listener steps aside. */
    val manualWeighInOpen = MutableStateFlow(false)
    var draft: MealDraft? = null

    /** The day the dashboard is showing. Water taps go to it; scanned meals always go to today. */
    val viewedDay = MutableStateFlow(clock.today.value)

    init {
        // Viewing today at midnight? Follow along to the new today.
        scope.launch {
            var previous = clock.today.value
            clock.today.collect { now ->
                if (now != previous) {
                    if (viewedDay.value == previous) viewedDay.value = now
                    previous = now
                }
            }
        }
    }

    init {
        // Housekeeping on launch: fix old model settings, drop stale captures, enforce the photo window.
        scope.launch {
            runCatching { settings.migrate() }
            photos.clearCaptures()
            runCatching { repository.prunePhotos(photos.mealPhotos()) }
        }
    }
}
