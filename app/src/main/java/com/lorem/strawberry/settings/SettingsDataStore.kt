package com.lorem.strawberry.settings

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Versioned settings schema. Bump [CURRENT_SETTINGS_VERSION] and add a step to
 * [SettingsMigration.migrate] whenever a key is renamed or its values change shape —
 * users then upgrade in place instead of silently losing settings.
 */
const val CURRENT_SETTINGS_VERSION = 1
private val SETTINGS_VERSION = intPreferencesKey("settings_version")

object SettingsMigration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        (currentData[SETTINGS_VERSION] ?: 0) < CURRENT_SETTINGS_VERSION

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()
        var version = prefs[SETTINGS_VERSION] ?: 0
        while (version < CURRENT_SETTINGS_VERSION) {
            when (version) {
                // v0 -> v1: stamp the schema version; keys already match the v1 layout.
                // Future steps transform keys here, e.g.:
                // 1 -> prefs[NEW_KEY] = prefs[OLD_KEY] ...; prefs.remove(OLD_KEY)
                0 -> Unit
            }
            version++
        }
        prefs[SETTINGS_VERSION] = version
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    produceMigrations = { listOf(SettingsMigration) }
)

@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private object Keys {
        val OPENROUTER_API_KEY = stringPreferencesKey("openrouter_api_key")
        val GOOGLE_CLOUD_API_KEY = stringPreferencesKey("google_cloud_api_key")
        val LLM_MODEL = stringPreferencesKey("llm_model")
        val TTS_ENGINE = stringPreferencesKey("tts_engine")
        val TTS_VOICE = stringPreferencesKey("tts_voice")
        val CARTESIA_VOICE = stringPreferencesKey("cartesia_voice")
        val CONTINUOUS_LISTENING = booleanPreferencesKey("continuous_listening")
        val CAR_MODE = booleanPreferencesKey("car_mode")
        val GEMINI_SEARCH = booleanPreferencesKey("gemini_search")
        val BARGE_IN = booleanPreferencesKey("barge_in")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            openRouterApiKey = prefs[Keys.OPENROUTER_API_KEY] ?: "",
            googleCloudApiKey = prefs[Keys.GOOGLE_CLOUD_API_KEY] ?: "",
            llmModel = prefs[Keys.LLM_MODEL] ?: "google/gemini-3-flash-preview",
            ttsEngine = prefs[Keys.TTS_ENGINE] ?: TtsEngineId.CARTESIA,
            ttsVoice = prefs[Keys.TTS_VOICE] ?: "Kore",
            cartesiaVoice = prefs[Keys.CARTESIA_VOICE] ?: "6f84f4b8-58a2-430c-8c79-688dad597532",
            continuousListening = prefs[Keys.CONTINUOUS_LISTENING] ?: false,
            carMode = prefs[Keys.CAR_MODE] ?: false,
            geminiSearch = prefs[Keys.GEMINI_SEARCH] ?: false,
            bargeIn = prefs[Keys.BARGE_IN] ?: false
        )
    }

    suspend fun updateOpenRouterApiKey(key: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.OPENROUTER_API_KEY] = key
        }
    }

    suspend fun updateGoogleCloudApiKey(key: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.GOOGLE_CLOUD_API_KEY] = key
        }
    }

    suspend fun updateLlmModel(model: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.LLM_MODEL] = model
        }
    }

    suspend fun updateTtsEngine(engine: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.TTS_ENGINE] = engine
        }
    }

    suspend fun updateTtsVoice(voice: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.TTS_VOICE] = voice
        }
    }

    suspend fun updateCartesiaVoice(voice: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.CARTESIA_VOICE] = voice
        }
    }

    suspend fun updateContinuousListening(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.CONTINUOUS_LISTENING] = enabled
        }
    }

    suspend fun updateCarMode(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.CAR_MODE] = enabled
        }
    }

    suspend fun updateGeminiSearch(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.GEMINI_SEARCH] = enabled
        }
    }

    suspend fun updateBargeIn(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.BARGE_IN] = enabled
        }
    }
}
