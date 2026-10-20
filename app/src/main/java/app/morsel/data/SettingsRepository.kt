package app.morsel.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.morsel.domain.UserProfile
import app.morsel.ui.theme.AccentColor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.dataStore by preferencesDataStore("morsel_settings")

/**
 * The settings a backup carries, so a restored phone looks and works like the old one.
 * API keys and the custom service address never leave the phone.
 */
@Serializable
data class SettingsBackup(
    val theme: String? = null,
    val accent: String? = null,
    val scaleEnabled: Boolean? = null,
    val primary: String? = null,
    val fallback: Boolean? = null,
    val fallbackProvider: String? = null,
    val engines: String? = null,
    val models: Map<String, String> = emptyMap(),
)

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.dataStore
    private val cipher = KeyCipher()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val profileKey = stringPreferencesKey("profile")
    private val themeKey = stringPreferencesKey("theme")
    private val accentKey = stringPreferencesKey("accent")
    private val scaleKey = booleanPreferencesKey("scale_enabled")
    private val primaryKey = stringPreferencesKey("ai_primary")
    private val fallbackKey = booleanPreferencesKey("ai_fallback")
    private val fallbackProviderKey = stringPreferencesKey("ai_fallback_provider")
    private val customUrlKey = stringPreferencesKey("custom_base_url")
    private val enginesKey = stringPreferencesKey("ai_engines")
    private val thinkingKey = stringPreferencesKey("deepseek_thinking")
    private val foodFactsKey = booleanPreferencesKey("use_food_facts")
    private val coachKey = booleanPreferencesKey("coach_enabled")
    private val coachTutorialKey = booleanPreferencesKey("coach_tutorial_done")
    private val modelsMigratedKey = booleanPreferencesKey("models_auto_v2")
    private val fallbackOffKey = booleanPreferencesKey("fallback_off_v3")

    private fun keyPref(provider: Provider) = stringPreferencesKey("${provider.prefix}_key")

    private fun modelPref(provider: Provider) = stringPreferencesKey("${provider.prefix}_model")

    val profile: Flow<UserProfile?> = store.data.map { prefs ->
        prefs[profileKey]?.let { runCatching { json.decodeFromString<UserProfile>(it) }.getOrNull() }
    }

    val theme: Flow<ThemeMode> = store.data.map { prefs ->
        ThemeMode.entries.firstOrNull { it.name == prefs[themeKey] } ?: ThemeMode.DARK
    }

    val accent: Flow<AccentColor> = store.data.map { prefs -> AccentColor.fromName(prefs[accentKey]) }

    /** The Xiaomi scale integration is opt-in; off until the user turns it on in Settings. */
    val scaleEnabled: Flow<Boolean> = store.data.map { prefs -> prefs[scaleKey] ?: false }

    /** Open Food Facts lookups: on unless the user turns them off (since 1.1.7). */
    val useFoodFacts: Flow<Boolean> = store.data.map { prefs -> prefs[foodFactsKey] ?: true }

    /**
     * The AI coach (beta) sends a summary of the user's data to their AI engine, so it stays off
     * until they turn it on. Deliberately not carried in backups: a restored phone asks again.
     */
    val coachEnabled: Flow<Boolean> = store.data.map { prefs -> prefs[coachKey] ?: false }.distinctUntilChanged()

    /** False until the user has pulled the coach down once after turning it on (the hint shows until then). */
    val coachTutorialDone: Flow<Boolean> = store.data.map { prefs -> prefs[coachTutorialKey] ?: false }.distinctUntilChanged()

    val ai: Flow<AiSettings> = store.data.map { prefs ->
        AiSettings(
            primary = Provider.entries.firstOrNull { it.name == prefs[primaryKey] } ?: Provider.DEEPSEEK,
            keys = Provider.entries.associateWith { cipher.decrypt(prefs[keyPref(it)]) },
            models = Provider.entries.associateWith { prefs[modelPref(it)].orEmpty() },
            customBaseUrl = prefs[customUrlKey].orEmpty(),
            fallback = prefs[fallbackKey] ?: false,
            fallbackProvider = Provider.entries.firstOrNull { it.name == prefs[fallbackProviderKey] },
            deepseekThinking = ThinkingLevel.entries.firstOrNull { it.name == prefs[thinkingKey] } ?: ThinkingLevel.HIGH,
            engines = prefs[enginesKey].orEmpty().split(',').mapNotNull { name -> Provider.entries.firstOrNull { it.name == name } },
        )
    }

    private fun backupOf(prefs: Preferences) = SettingsBackup(
        theme = prefs[themeKey],
        accent = prefs[accentKey],
        scaleEnabled = prefs[scaleKey],
        primary = prefs[primaryKey],
        fallback = prefs[fallbackKey],
        fallbackProvider = prefs[fallbackProviderKey],
        engines = prefs[enginesKey],
        models = Provider.entries.mapNotNull { p -> prefs[modelPref(p)]?.takeIf { it.isNotBlank() }?.let { p.name to it } }.toMap(),
    )

    suspend fun backup(): SettingsBackup = backupOf(store.data.first())


    /** Puts restored settings in place. Missing values (older backups) leave the current ones alone. */
    suspend fun restore(backup: SettingsBackup) {
        store.edit { prefs ->
            backup.theme?.let { prefs[themeKey] = it }
            backup.accent?.let { prefs[accentKey] = it }
            backup.scaleEnabled?.let { prefs[scaleKey] = it }
            backup.primary?.let { prefs[primaryKey] = it }
            backup.fallback?.let { prefs[fallbackKey] = it }
            backup.fallbackProvider?.let { prefs[fallbackProviderKey] = it }
            backup.engines?.let { prefs[enginesKey] = it }
            backup.models.forEach { (name, model) ->
                Provider.entries.firstOrNull { it.name == name }?.let { prefs[modelPref(it)] = model }
            }
        }
    }

    /**
     * One-time cleanups of choices made by older versions:
     * - Version 1.0 saved its automatic model pick, locking DeepSeek onto the legacy
     *   "deepseek-chat" alias. Clear those so "Automatic" really is automatic.
     * - Fallback used to be on by default. It is now off, so reset whatever was stored once.
     */
    suspend fun migrate() {
        store.edit { prefs ->
            if (prefs[modelsMigratedKey] != true) {
                if (prefs[modelPref(Provider.DEEPSEEK)] in setOf("deepseek-chat", "deepseek-reasoner")) prefs.remove(modelPref(Provider.DEEPSEEK))
                prefs.remove(modelPref(Provider.GEMINI))
                prefs[modelsMigratedKey] = true
            }
            if (prefs[fallbackOffKey] != true) {
                prefs.remove(fallbackKey)
                prefs[fallbackOffKey] = true
            }
        }
    }

    suspend fun setFallback(enabled: Boolean) {
        store.edit { it[fallbackKey] = enabled }
    }

    suspend fun setFallbackProvider(provider: Provider) {
        store.edit { it[fallbackProviderKey] = provider.name }
    }

    suspend fun setCustomBaseUrl(url: String) {
        store.edit { it[customUrlKey] = url.trim() }
    }

    /** Wipes every setting. API keys (and the custom address that goes with them) survive when [keepKeys] is true. */
    suspend fun clear(keepKeys: Boolean) {
        store.edit { prefs ->
            val keys = Provider.entries.mapNotNull { p -> prefs[keyPref(p)]?.let { p to it } }
            val customUrl = prefs[customUrlKey]
            prefs.clear()
            prefs[modelsMigratedKey] = true
            prefs[fallbackOffKey] = true
            if (keepKeys) {
                keys.forEach { (p, key) -> prefs[keyPref(p)] = key }
                customUrl?.let { prefs[customUrlKey] = it }
            }
        }
    }

    suspend fun saveProfile(profile: UserProfile) {
        store.edit { it[profileKey] = json.encodeToString(UserProfile.serializer(), profile) }
    }

    suspend fun setTheme(mode: ThemeMode) {
        store.edit { it[themeKey] = mode.name }
    }

    suspend fun setAccent(accent: AccentColor) {
        store.edit { it[accentKey] = accent.name }
    }

    suspend fun setScaleEnabled(enabled: Boolean) {
        store.edit { it[scaleKey] = enabled }
    }

    /** Only the list of engines shown; saved keys are never touched here. */
    suspend fun setEngines(engines: List<Provider>) {
        store.edit { it[enginesKey] = engines.distinct().joinToString(",") { p -> p.name } }
    }

    suspend fun setDeepseekThinking(level: ThinkingLevel) {
        store.edit { it[thinkingKey] = level.name }
    }

    suspend fun setUseFoodFacts(enabled: Boolean) {
        store.edit { it[foodFactsKey] = enabled }
    }

    /** Turning the coach on also brings back its one-time "pull down" hint. */
    suspend fun setCoachEnabled(enabled: Boolean) {
        store.edit {
            it[coachKey] = enabled
            if (enabled) it[coachTutorialKey] = false
        }
    }

    suspend fun setCoachTutorialDone() {
        store.edit { it[coachTutorialKey] = true }
    }

    suspend fun setPrimary(provider: Provider) {
        store.edit { it[primaryKey] = provider.name }
    }

    suspend fun setKey(provider: Provider, key: String) {
        val encrypted = cipher.encrypt(key.trim())
        store.edit { it[keyPref(provider)] = encrypted }
    }

    suspend fun setModel(provider: Provider, model: String) {
        store.edit { it[modelPref(provider)] = model.trim() }
    }
}

/** Encrypts API keys with an AES key that never leaves the Android Keystore. */
private class KeyCipher {
    private val alias = "morsel_api_keys"

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    /** Decrypted keys by ciphertext; settings re-emit often, and unlocking the Keystore each time is slow. */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun decrypt(encoded: String?): String {
        if (encoded.isNullOrEmpty()) return ""
        return cache.getOrPut(encoded) { decryptUncached(encoded) }
    }

    private fun decryptUncached(encoded: String): String {
        return runCatching {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
            String(cipher.doFinal(bytes, 12, bytes.size - 12))
        }.getOrDefault("")
    }
}
