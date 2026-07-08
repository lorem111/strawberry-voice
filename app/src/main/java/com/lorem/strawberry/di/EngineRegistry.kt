package com.lorem.strawberry.di

import android.content.Context
import com.lorem.strawberry.auth.CartesiaTokenProvider
import com.lorem.strawberry.auth.SecureStorage
import com.lorem.strawberry.core.AppLogger
import com.lorem.strawberry.core.ImageEncoder
import com.lorem.strawberry.core.LlmClient
import com.lorem.strawberry.core.TtsEngine
import com.lorem.strawberry.llm.GeminiApi
import com.lorem.strawberry.llm.OpenRouterApi
import com.lorem.strawberry.settings.AppSettings
import com.lorem.strawberry.settings.SettingsDataStore
import com.lorem.strawberry.settings.TtsEngineId
import com.lorem.strawberry.tts.CartesiaTTS
import com.lorem.strawberry.tts.LocalTTS
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single place engines are constructed and configured. Watches settings (DataStore)
 * and server-provisioned API keys (SecureStorage), rebuilds engines when their key
 * changes, and exposes the currently selected TTS/LLM as flows.
 *
 * Adding a new TTS or LLM = implement the interface, wire it here.
 */
@Singleton
class EngineRegistry @Inject constructor(
    @ApplicationContext private val context: Context,
    settingsDataStore: SettingsDataStore,
    private val secureStorage: SecureStorage,
    private val cartesiaTokenProvider: CartesiaTokenProvider,
    private val logger: AppLogger,
    private val imageEncoder: ImageEncoder,
    appScope: CoroutineScope
) {

    companion object {
        private const val TAG = "EngineRegistry"
    }

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _activeTts = MutableStateFlow<TtsEngine?>(null)
    val activeTts: StateFlow<TtsEngine?> = _activeTts.asStateFlow()

    private val _activeLlm = MutableStateFlow<LlmClient?>(null)
    val activeLlm: StateFlow<LlmClient?> = _activeLlm.asStateFlow()

    private var cartesia: CartesiaTTS? = null
    private var local: LocalTTS? = null
    private var openRouter: OpenRouterApi? = null
    private var gemini: GeminiApi? = null

    private var applied: AppSettings? = null

    init {
        appScope.launch {
            combine(settingsDataStore.settings, secureStorage.changes) { stored, _ ->
                // Server-provisioned keys (from sign-in) take precedence
                stored.copy(
                    openRouterApiKey = secureStorage.openRouterApiKey ?: "",
                    sessionToken = secureStorage.sessionToken ?: "",
                    googleCloudApiKey = secureStorage.googleCloudApiKey ?: ""
                )
            }
                .distinctUntilChanged()
                .collect { apply(it) }
        }
    }

    private fun apply(settings: AppSettings) {
        val old = applied

        if (old == null || settings.openRouterApiKey != old.openRouterApiKey) {
            openRouter?.close()
            openRouter = if (settings.openRouterApiKey.isNotBlank()) {
                logger.d(TAG, "Creating OpenRouter client")
                OpenRouterApi(settings.openRouterApiKey, logger, imageEncoder)
            } else null
        }
        openRouter?.model = settings.llmModel

        if (old == null || settings.googleCloudApiKey != old.googleCloudApiKey) {
            gemini?.close()
            gemini = if (settings.googleCloudApiKey.isNotBlank()) {
                logger.d(TAG, "Creating Gemini client")
                GeminiApi(settings.googleCloudApiKey, logger, imageEncoder)
            } else null
        }

        // Cartesia availability is gated on being signed in (having a session token);
        // the engine pulls short-lived tokens from CartesiaTokenProvider per request.
        if (old == null || settings.sessionToken.isNotBlank() != old.sessionToken.isNotBlank()) {
            cartesia?.destroy()
            cartesia = if (settings.sessionToken.isNotBlank()) {
                logger.d(TAG, "Creating Cartesia TTS")
                CartesiaTTS(cartesiaTokenProvider::getToken, logger)
            } else null
        }

        if (local == null) {
            local = LocalTTS(context, logger)
        }

        cartesia?.voice = settings.cartesiaVoice
        listOfNotNull(cartesia, local).forEach {
            it.useVoiceCommunication = settings.carMode
        }

        _activeTts.value = when (settings.ttsEngine) {
            TtsEngineId.CARTESIA -> cartesia
            else -> local
        }
        _activeLlm.value = if (settings.geminiSearch) gemini else openRouter

        applied = settings
        _settings.value = settings
    }
}
