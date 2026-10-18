package app.morsel.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.morsel.AppContainer
import app.morsel.data.AiSettings
import app.morsel.data.Backup
import app.morsel.data.Provider
import app.morsel.data.ThemeMode
import app.morsel.domain.Nutrition
import app.morsel.domain.Targets
import app.morsel.domain.UserProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

data class SettingsState(
    val profile: UserProfile? = null,
    val targets: Targets? = null,
    val ai: AiSettings = AiSettings(),
    val theme: ThemeMode = ThemeMode.DARK,
)

sealed interface KeyCheck {
    data object Idle : KeyCheck
    data object Checking : KeyCheck
    /** [automatic] is what "Automatic" resolves to with this key. */
    data class Ok(val models: List<String>, val automatic: String?) : KeyCheck
    data class Failed(val message: String) : KeyCheck
}

class SettingsViewModel(private val app: AppContainer) : ViewModel() {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    val state: StateFlow<SettingsState> = combine(
        app.settings.profile,
        app.settings.ai,
        app.settings.theme,
        app.repository.weights(),
    ) { profile, ai, theme, weights ->
        val current = weights.lastOrNull()?.kg ?: profile?.startWeightKg
        SettingsState(
            profile = profile,
            targets = if (profile != null && current != null) Nutrition.targets(profile, current) else null,
            ai = ai,
            theme = theme,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsState())

    /** Size and count of stored meal photos, e.g. "3.2 MB" and 41; computed once when Settings opens. */
    val photoStorage = MutableStateFlow<Pair<String, Int>?>(null)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val files = app.photos.mealPhotos()
            val bytes = files.sumOf { it.length() }
            val size = if (bytes < 1024 * 1024) "${bytes / 1024} KB"
            else String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
            photoStorage.value = size to files.size
        }
    }

    val keyCheck = MutableStateFlow<KeyCheck>(KeyCheck.Idle)
    val message = MutableStateFlow<String?>(null)

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { app.settings.setTheme(mode) }

    fun setPrimary(provider: Provider) = viewModelScope.launch { app.settings.setPrimary(provider) }

    fun setModel(provider: Provider, model: String) = viewModelScope.launch { app.settings.setModel(provider, model) }

    fun setFallback(enabled: Boolean) = viewModelScope.launch { app.settings.setFallback(enabled) }

    /** Erases meals, water, weights, photos and the profile; optionally the API keys too. */
    fun resetAll(keepKeys: Boolean, onDone: () -> Unit) {
        viewModelScope.launch {
            app.repository.restore(Backup())
            app.photos.deleteAll()
            app.settings.clear(keepKeys)
            app.draft = null
            onDone()
        }
    }

    fun updateProfile(transform: (UserProfile) -> UserProfile) = viewModelScope.launch {
        app.settings.profile.first()?.let { app.settings.saveProfile(transform(it)) }
    }

    /** Saves the key, then checks it by listing the models it can use. */
    fun saveKey(provider: Provider, key: String) {
        viewModelScope.launch {
            app.settings.setKey(provider, key)
            if (key.isBlank()) {
                keyCheck.value = KeyCheck.Idle
                return@launch
            }
            check(provider, key.trim())
        }
    }

    fun loadModels(provider: Provider) {
        viewModelScope.launch {
            val key = app.settings.ai.first().keyFor(provider)
            if (key.isBlank()) keyCheck.value = KeyCheck.Failed("Add a key first") else check(provider, key)
        }
    }

    private suspend fun check(provider: Provider, key: String) {
        keyCheck.value = KeyCheck.Checking
        keyCheck.value = try {
            val models = app.ai.listModels(provider, key)
            KeyCheck.Ok(models, app.ai.pickDefault(provider, models))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            KeyCheck.Failed(e.message ?: "Couldn't reach ${provider.title}")
        }
    }

    fun resetKeyCheck() {
        keyCheck.value = KeyCheck.Idle
    }

    fun export(context: Context, uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val backup = app.repository.export(app.settings.profile.first())
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(json.encodeToString(Backup.serializer(), backup).toByteArray())
                    } ?: error("Couldn't open the file")
                }
                backup
            }.onSuccess { message.value = "Exported ${it.meals.size} meals" }
                .onFailure { message.value = "Export failed: ${it.message}" }
        }
    }

    fun import(context: Context, uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: error("Couldn't open the file")
                }
                val backup = json.decodeFromString(Backup.serializer(), text)
                app.repository.restore(backup)
                backup.profile?.let { app.settings.saveProfile(it) }
                backup
            }.onSuccess { message.value = "Imported ${it.meals.size} meals" }
                .onFailure { message.value = "Import failed: ${it.message}" }
        }
    }
}
