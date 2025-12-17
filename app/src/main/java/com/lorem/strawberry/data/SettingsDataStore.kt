package com.lorem.strawberry.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

// TTS Engine options
object TtsEngine {
    const val CARTESIA = "cartesia"  // Cartesia Sonic (streaming, low latency)
    const val CHIRP = "chirp"  // Google Cloud Chirp 3 HD (non-streaming)
    const val LOCAL = "local"  // Android's built-in TTS
}

data class AppSettings(
    val openRouterApiKey: String = "",
    val googleCloudApiKey: String = "",
    val cartesiaApiKey: String = "",
    // DO NOT MODIFY: Default model must match first entry in SettingsScreen.availableLlmModels
    val llmModel: String = "google/gemini-3-flash-preview",
    val ttsEngine: String = TtsEngine.CARTESIA,
    val ttsVoice: String = "Kore",
    val cartesiaVoice: String = "6f84f4b8-58a2-430c-8c79-688dad597532" // Brooke - Big Sister
)

class SettingsDataStore(private val context: Context) {

    private object Keys {
        val OPENROUTER_API_KEY = stringPreferencesKey("openrouter_api_key")
        val GOOGLE_CLOUD_API_KEY = stringPreferencesKey("google_cloud_api_key")
        val CARTESIA_API_KEY = stringPreferencesKey("cartesia_api_key")
        val LLM_MODEL = stringPreferencesKey("llm_model")
        val TTS_ENGINE = stringPreferencesKey("tts_engine")
        val TTS_VOICE = stringPreferencesKey("tts_voice")
        val CARTESIA_VOICE = stringPreferencesKey("cartesia_voice")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            openRouterApiKey = prefs[Keys.OPENROUTER_API_KEY] ?: "",
            googleCloudApiKey = prefs[Keys.GOOGLE_CLOUD_API_KEY] ?: "",
            cartesiaApiKey = prefs[Keys.CARTESIA_API_KEY] ?: "",
            llmModel = prefs[Keys.LLM_MODEL] ?: "google/gemini-3-flash-preview",
            ttsEngine = prefs[Keys.TTS_ENGINE] ?: TtsEngine.CARTESIA,
            ttsVoice = prefs[Keys.TTS_VOICE] ?: "Kore",
            cartesiaVoice = prefs[Keys.CARTESIA_VOICE] ?: "6f84f4b8-58a2-430c-8c79-688dad597532"
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

    suspend fun updateCartesiaApiKey(key: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.CARTESIA_API_KEY] = key
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
}
