package app.morsel.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.morsel.AppContainer
import app.morsel.ai.ApiBalance
import app.morsel.ai.ApiBalances
import app.morsel.data.AiSettings
import app.morsel.data.Backup
import app.morsel.data.Provider
import app.morsel.data.StorageInfo
import app.morsel.data.formatBytes
import app.morsel.data.ThemeMode
import app.morsel.data.ThinkingLevel
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
    val scaleEnabled: Boolean = false,
)

/** The credit-left card at the top of the AI engine screen. */
sealed interface BalanceState {
    /** The engine used for scans can't report its balance (or has no key yet). */
    data object Unavailable : BalanceState
    data object Loading : BalanceState
    data class Ok(val balance: ApiBalance, val at: Long) : BalanceState
    data class Failed(val message: String) : BalanceState
}

/** A short reason for the balance card; the full API error belongs in the key check, not here. */
internal fun balanceProblem(service: String, message: String?): String = when {
    message == null -> "Couldn't reach $service. Tap refresh to try again."
    "401" in message || "403" in message -> "$service rejected this key, so the balance can't be shown."
    "402" in message -> "$service says the account has no credit left."
    else -> message.substringBefore(" (request_id").take(140)
}

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
        app.settings.scaleEnabled,
    ) { plan, ai, theme, accent, scale ->
        SettingsState(
            profile = plan.profile, targets = plan.targets, estimate = plan.estimate, ai = ai, theme = theme, accent = accent,
            scaleEnabled = scale,
        )
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

    val balance = MutableStateFlow<BalanceState>(BalanceState.Unavailable)

    /** Asks the engine used for scans how much credit is left (DeepSeek and OpenRouter can say). */
    fun refreshBalance() = viewModelScope.launch {
        val ai = app.settings.ai.first()
        val provider = ai.primary
        if (!ApiBalances.supported(provider, ai) || !ai.isReady(provider)) {
            balance.value = BalanceState.Unavailable
            return@launch
        }
        balance.value = BalanceState.Loading
        balance.value = try {
            app.ai.balance(provider)?.let { BalanceState.Ok(it, System.currentTimeMillis()) } ?: BalanceState.Unavailable
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BalanceState.Failed(balanceProblem(provider.title, e.message))
        }
    }

    val keyCheck = MutableStateFlow<KeyCheck>(KeyCheck.Idle)
    val message = MutableStateFlow<String?>(null)

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { app.settings.setTheme(mode) }

    fun setAccent(accent: AccentColor) = viewModelScope.launch { app.settings.setAccent(accent) }

    fun setScaleEnabled(enabled: Boolean) = viewModelScope.launch { app.settings.setScaleEnabled(enabled) }

    val useFoodFacts = app.settings.useFoodFacts

    fun setUseFoodFacts(enabled: Boolean) = viewModelScope.launch { app.settings.setUseFoodFacts(enabled) }

    val coachEnabled = app.settings.coachEnabled

    /** Turning the coach off also deletes its conversation. */
    fun setCoachEnabled(enabled: Boolean) = viewModelScope.launch {
        app.settings.setCoachEnabled(enabled)
        if (!enabled) app.coach.clear()
    }

    fun setPrimary(provider: Provider) = viewModelScope.launch { app.settings.setPrimary(provider) }

    fun setDeepseekThinking(level: ThinkingLevel) = viewModelScope.launch { app.settings.setDeepseekThinking(level) }

    /** Adds the next engine that isn't in the list yet (up to four). Keys are left alone. */
    fun addEngine() = viewModelScope.launch {
        val ai = state.value.ai
        val next = ai.addableEngines.firstOrNull() ?: return@launch
        app.settings.setEngines(ai.activeEngines + next)
    }

    /** Picking another service in an engine's dropdown. The old service's saved key stays stored. */
    fun changeEngine(old: Provider, new: Provider) = viewModelScope.launch {
        val ai = state.value.ai
        if (new == old || new in ai.activeEngines) return@launch
        // Primary first, so the list never briefly shows the old engine again.
        if (ai.primary == old) app.settings.setPrimary(new)
        app.settings.setEngines(ai.activeEngines.map { if (it == old) new else it })
    }

    /** Takes an engine off the list and deletes its saved key from this phone. */
    fun removeEngine(provider: Provider) = viewModelScope.launch {
        val ai = state.value.ai
        val remaining = ai.activeEngines - provider
        if (remaining.isEmpty()) return@launch
        if (ai.primary == provider) app.settings.setPrimary(remaining.first())
        app.settings.setEngines(remaining)
        app.settings.setKey(provider, "")
    }
    fun setModel(provider: Provider, model: String) = viewModelScope.launch { app.settings.setModel(provider, model) }

    fun setFallback(enabled: Boolean) = viewModelScope.launch { app.settings.setFallback(enabled) }

    fun setFallbackProvider(provider: Provider) = viewModelScope.launch { app.settings.setFallbackProvider(provider) }

    /** Erases meals, water, weights, photos and the profile; optionally the API keys too. */
    fun resetAll(keepKeys: Boolean, onDone: () -> Unit) {
        viewModelScope.launch {
            app.repository.restore(Backup())
            app.photos.deleteAll()
            app.coach.clear()
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
                val backup = app.repository.export(app.settings.profile.first(), app.settings.backup())
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
                backup.settings?.let { app.settings.restore(it) }
                backup
            }.onSuccess { message.value = "Imported ${it.meals.size} meals" }
                .onFailure { message.value = "Import failed: ${it.message}" }
        }
    }
}
