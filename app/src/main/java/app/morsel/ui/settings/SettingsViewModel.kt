package app.morsel.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.morsel.AppContainer
import app.morsel.data.AiSettings
import app.morsel.data.Backup
import app.morsel.data.Provider
import app.morsel.data.StorageInfo
import app.morsel.data.formatBytes
import app.morsel.data.ThemeMode
import app.morsel.domain.AdaptiveEstimate
import app.morsel.domain.Targets
import app.morsel.domain.UserProfile
import app.morsel.ui.theme.AccentColor
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
    val estimate: AdaptiveEstimate? = null,
    val ai: AiSettings = AiSettings(),
    val theme: ThemeMode = ThemeMode.DARK,
    val accent: AccentColor = AccentColor.Default,
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
        app.plans.plan,
        app.settings.ai,
        app.settings.theme,
        app.settings.accent,
    ) { plan, ai, theme, accent ->
        SettingsState(profile = plan.profile, targets = plan.targets, estimate = plan.estimate, ai = ai, theme = theme, accent = accent)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsState())

    /** Size and count of stored meal photos, e.g. "3.2 MB" and 41. */
    val photoStorage = MutableStateFlow<Pair<String, Int>?>(null)

    /** Everything the app takes on the phone. */
    val storage = MutableStateFlow<StorageInfo.Usage?>(null)

    init {
        refreshStorage()
    }

    private fun refreshStorage() {
        viewModelScope.launch(Dispatchers.IO) {
            val files = app.photos.mealPhotos()
            photoStorage.value = formatBytes(files.sumOf { it.length() }) to files.size
            storage.value = app.storage.usage()
        }
    }

    /** Removes every meal photo but keeps all meals and their numbers. */
    fun deleteAllPhotos() {
        viewModelScope.launch(Dispatchers.IO) {
            app.repository.deleteAllPhotos(app.photos.mealPhotos())
            refreshStorage()
            message.value = "Meal photos deleted. Your meals are still logged."
        }
    }

    val keyCheck = MutableStateFlow<KeyCheck>(KeyCheck.Idle)
    val message = MutableStateFlow<String?>(null)

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { app.settings.setTheme(mode) }

    fun setAccent(accent: AccentColor) = viewModelScope.launch { app.settings.setAccent(accent) }

    fun setPrimary(provider: Provider) = viewModelScope.launch { app.settings.setPrimary(provider) }

    fun setModel(provider: Provider, model: String) = viewModelScope.launch { app.settings.setModel(provider, model) }

    fun setFallback(enabled: Boolean) = viewModelScope.launch { app.settings.setFallback(enabled) }

    fun setFallbackProvider(provider: Provider) = viewModelScope.launch { app.settings.setFallbackProvider(provider) }

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

    /**
     * Saves the key (and, for a custom service, its address), then checks them by listing the
     * models they can use.
     */
    fun saveKey(provider: Provider, key: String, baseUrl: String = "") {
        viewModelScope.launch {
            if (provider.needsBaseUrl) app.settings.setCustomBaseUrl(baseUrl)
            app.settings.setKey(provider, key)
            if (key.isBlank()) {
                keyCheck.value = KeyCheck.Idle
                return@launch
            }
            check(provider, key.trim(), if (provider.needsBaseUrl) baseUrl.trim().trimEnd('/') else provider.baseUrl.orEmpty())
        }
    }

    fun loadModels(provider: Provider) {
        viewModelScope.launch {
            val ai = app.settings.ai.first()
            when {
                ai.keyFor(provider).isBlank() -> keyCheck.value = KeyCheck.Failed("Add a key first")
                !ai.isReady(provider) -> keyCheck.value = KeyCheck.Failed("Add the service address first")
                else -> check(provider, ai.keyFor(provider), ai.baseUrlFor(provider))
            }
        }
    }

    private suspend fun check(provider: Provider, key: String, baseUrl: String) {
        keyCheck.value = KeyCheck.Checking
        keyCheck.value = try {
            val models = app.ai.listModels(provider, key, baseUrl)
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
