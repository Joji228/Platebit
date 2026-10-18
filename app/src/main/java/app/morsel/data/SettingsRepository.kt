package app.morsel.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.morsel.domain.UserProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.dataStore by preferencesDataStore("morsel_settings")

enum class Provider(val title: String) { DEEPSEEK("DeepSeek"), GEMINI("Gemini") }

enum class ThemeMode(val title: String) { DARK("Dark"), LIGHT("Light"), SYSTEM("System") }

data class AiSettings(
    val primary: Provider = Provider.DEEPSEEK,
    val deepseekKey: String = "",
    val geminiKey: String = "",
    /** Blank means "pick automatically from the provider's model list". */
    val deepseekModel: String = "",
    val geminiModel: String = "",
    /** When false, only [primary] is ever used. */
    val fallback: Boolean = true,
) {
    fun keyFor(provider: Provider) = when (provider) {
        Provider.DEEPSEEK -> deepseekKey
        Provider.GEMINI -> geminiKey
    }

    fun modelFor(provider: Provider) = when (provider) {
        Provider.DEEPSEEK -> deepseekModel
        Provider.GEMINI -> geminiModel
    }
}

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.dataStore
    private val cipher = KeyCipher()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val profileKey = stringPreferencesKey("profile")
    private val themeKey = stringPreferencesKey("theme")
    private val primaryKey = stringPreferencesKey("ai_primary")
    private val deepseekKey = stringPreferencesKey("deepseek_key")
    private val geminiKey = stringPreferencesKey("gemini_key")
    private val deepseekModel = stringPreferencesKey("deepseek_model")
    private val geminiModel = stringPreferencesKey("gemini_model")
    private val fallbackKey = booleanPreferencesKey("ai_fallback")
    private val modelsMigratedKey = booleanPreferencesKey("models_auto_v2")

    val profile: Flow<UserProfile?> = store.data.map { prefs ->
        prefs[profileKey]?.let { runCatching { json.decodeFromString<UserProfile>(it) }.getOrNull() }
    }

    val theme: Flow<ThemeMode> = store.data.map { prefs ->
        ThemeMode.entries.firstOrNull { it.name == prefs[themeKey] } ?: ThemeMode.DARK
    }

    val ai: Flow<AiSettings> = store.data.map { prefs ->
        AiSettings(
            primary = Provider.entries.firstOrNull { it.name == prefs[primaryKey] } ?: Provider.DEEPSEEK,
            deepseekKey = cipher.decrypt(prefs[deepseekKey]),
            geminiKey = cipher.decrypt(prefs[geminiKey]),
            deepseekModel = prefs[deepseekModel].orEmpty(),
            geminiModel = prefs[geminiModel].orEmpty(),
            fallback = prefs[fallbackKey] ?: true,
        )
    }

    /**
     * Version 1.0 saved its automatic model pick, which locked DeepSeek onto the legacy
     * "deepseek-chat" alias. Clear those once so "Automatic" really is automatic.
     */
    suspend fun migrate() {
        store.edit { prefs ->
            if (prefs[modelsMigratedKey] == true) return@edit
            if (prefs[deepseekModel] in setOf("deepseek-chat", "deepseek-reasoner")) prefs.remove(deepseekModel)
            prefs.remove(geminiModel)
            prefs[modelsMigratedKey] = true
        }
    }

    suspend fun setFallback(enabled: Boolean) {
        store.edit { it[fallbackKey] = enabled }
    }

    /** Wipes every setting. API keys survive when [keepKeys] is true. */
    suspend fun clear(keepKeys: Boolean) {
        store.edit { prefs ->
            val ds = prefs[deepseekKey]
            val gm = prefs[geminiKey]
            prefs.clear()
            prefs[modelsMigratedKey] = true
            if (keepKeys) {
                ds?.let { prefs[deepseekKey] = it }
                gm?.let { prefs[geminiKey] = it }
            }
        }
    }

    suspend fun saveProfile(profile: UserProfile) {
        store.edit { it[profileKey] = json.encodeToString(UserProfile.serializer(), profile) }
    }

    suspend fun setTheme(mode: ThemeMode) {
        store.edit { it[themeKey] = mode.name }
    }

    suspend fun setPrimary(provider: Provider) {
        store.edit { it[primaryKey] = provider.name }
    }

    suspend fun setKey(provider: Provider, key: String) {
        val encrypted = cipher.encrypt(key.trim())
        store.edit {
            when (provider) {
                Provider.DEEPSEEK -> it[deepseekKey] = encrypted
                Provider.GEMINI -> it[geminiKey] = encrypted
            }
        }
    }

    suspend fun setModel(provider: Provider, model: String) {
        store.edit {
            when (provider) {
                Provider.DEEPSEEK -> it[deepseekModel] = model
                Provider.GEMINI -> it[geminiModel] = model
            }
        }
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

    fun decrypt(encoded: String?): String {
        if (encoded.isNullOrEmpty()) return ""
        return runCatching {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
            String(cipher.doFinal(bytes, 12, bytes.size - 12))
        }.getOrDefault("")
    }
}
