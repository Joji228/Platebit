package app.morsel

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.morsel.ai.AiService
import app.morsel.ai.AnalysisResult
import app.morsel.data.MorselRepository
import app.morsel.data.PhotoStore
import app.morsel.data.SettingsRepository
import app.morsel.data.db.MorselDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class MorselApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** A scan waiting to be reviewed on the results screen. */
data class MealDraft(val photos: List<File>, val note: String?, val result: AnalysisResult?)

/** Hand-wired dependencies; small enough that a DI framework isn't worth the build time. */
class AppContainer(context: Context) {
    private val database = Room.databaseBuilder(context, MorselDatabase::class.java, "morsel.db").build()
    val settings = SettingsRepository(context)
    val repository = MorselRepository(database)
    val photos = PhotoStore(context)
    val ai = AiService(settings)
    var draft: MealDraft? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Housekeeping on launch: fix old model settings, drop stale captures, enforce the photo window.
        scope.launch {
            runCatching { settings.migrate() }
            photos.clearCaptures()
            runCatching { repository.prunePhotos(photos.mealPhotos()) }
        }
    }
}
