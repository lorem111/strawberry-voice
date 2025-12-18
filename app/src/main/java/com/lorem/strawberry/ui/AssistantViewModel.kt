package com.lorem.strawberry.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lorem.strawberry.audio.BluetoothScoManager
import com.lorem.strawberry.data.AppSettings
import com.lorem.strawberry.data.ChatMessage
import com.lorem.strawberry.data.GeminiApi
import com.lorem.strawberry.data.OpenRouterApi
import com.lorem.strawberry.data.TtsEngine
import com.lorem.strawberry.speech.CartesiaTTS
import com.lorem.strawberry.speech.GoogleCloudTTS
import com.lorem.strawberry.speech.LocalTTS
import com.lorem.strawberry.speech.SpeechRecognizerManager
import com.lorem.strawberry.speech.SpeechState
import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class Message(
    val content: String,
    val isUser: Boolean,
    val isLoading: Boolean = false
)

data class AssistantUiState(
    val messages: List<Message> = emptyList(),
    val isListening: Boolean = false,
    val isSpeaking: Boolean = false,
    val partialSpeech: String = "",
    val error: String? = null,
    val ttsLatencyMs: Long? = null
)

class AssistantViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application
    private val speechManager = SpeechRecognizerManager(application)
    private val bluetoothScoManager = BluetoothScoManager(application)

    private var currentSettings = AppSettings()
    private var openRouterApi: OpenRouterApi? = null
    private var geminiApi: GeminiApi? = null
    private var chirpTts: GoogleCloudTTS? = null
    private var cartesiaTts: CartesiaTTS? = null
    private var localTts: LocalTTS? = null

    private val conversationHistory = mutableListOf<ChatMessage>()
    private val systemPrompt: String = loadSystemPrompt()

    // Track observer jobs to cancel them when TTS is recreated
    private var chirpObserverJob: Job? = null
    private var cartesiaObserverJob: Job? = null
    private var cartesiaLatencyObserverJob: Job? = null
    private var localObserverJob: Job? = null

    companion object {
        private const val TAG = "AssistantViewModel"
    }

    private fun loadSystemPrompt(): String {
        return try {
            context.assets.open("system_prompt.txt").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "You are a helpful voice assistant. Keep your responses concise and conversational."
        }
    }

    private val _uiState = MutableStateFlow(AssistantUiState())
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    init {
        observeSpeechState()
        observePartialResults()
    }

    fun updateSettings(settings: AppSettings) {
        val isFirstLoad = openRouterApi == null && chirpTts == null && cartesiaTts == null && localTts == null

        // Update OpenRouter API if key changed or first load
        if (isFirstLoad || settings.openRouterApiKey != currentSettings.openRouterApiKey) {
            openRouterApi?.close()
            openRouterApi = if (settings.openRouterApiKey.isNotBlank()) {
                OpenRouterApi(settings.openRouterApiKey)
            } else null
        }

        // Update Chirp TTS and Gemini API if Google Cloud key changed or first load
        if (isFirstLoad || settings.googleCloudApiKey != currentSettings.googleCloudApiKey) {
            chirpObserverJob?.cancel()
            chirpTts?.destroy()
            chirpTts = if (settings.googleCloudApiKey.isNotBlank()) {
                GoogleCloudTTS(context, settings.googleCloudApiKey).also { tts ->
                    observeChirpTtsState(tts)
                }
            } else null

            // Initialize Gemini API with same Google Cloud key
            geminiApi?.close()
            geminiApi = if (settings.googleCloudApiKey.isNotBlank()) {
                GeminiApi(settings.googleCloudApiKey)
            } else null
        }

        // Update Cartesia TTS if API key changed or first load
        if (isFirstLoad || settings.cartesiaApiKey != currentSettings.cartesiaApiKey) {
            cartesiaObserverJob?.cancel()
            cartesiaLatencyObserverJob?.cancel()
            cartesiaTts?.destroy()
            cartesiaTts = if (settings.cartesiaApiKey.isNotBlank()) {
                CartesiaTTS(settings.cartesiaApiKey).also { tts ->
                    observeCartesiaTtsState(tts)
                }
            } else null
        }

        // Initialize Local TTS if needed
        if (isFirstLoad && localTts == null) {
            localObserverJob?.cancel()
            localTts = LocalTTS(context).also { tts ->
                observeLocalTtsState(tts)
            }
        }

        // Update continuous listening mode
        speechManager.continuousListening = settings.continuousListening

        // Update car mode (Bluetooth SCO)
        if (settings.carMode != currentSettings.carMode || isFirstLoad) {
            if (settings.carMode) {
                Log.d(TAG, "Enabling car mode (Bluetooth SCO)")
                bluetoothScoManager.start()
            } else {
                Log.d(TAG, "Disabling car mode (Bluetooth SCO)")
                bluetoothScoManager.stop()
            }
        }

        currentSettings = settings
    }

    private fun observeSpeechState() {
        viewModelScope.launch {
            speechManager.state.collect { state ->
                when (state) {
                    is SpeechState.Idle -> {
                        _uiState.value = _uiState.value.copy(
                            isListening = false,
                            partialSpeech = ""
                        )
                    }
                    is SpeechState.Listening -> {
                        _uiState.value = _uiState.value.copy(
                            isListening = true,
                            error = null
                        )
                    }
                    is SpeechState.Result -> {
                        _uiState.value = _uiState.value.copy(
                            isListening = false,
                            partialSpeech = ""
                        )
                        // In car mode, keep mic active to maintain Bluetooth SCO connection
                        // while LLM is processing (will be stopped when TTS starts)
                        if (currentSettings.carMode) {
                            speechManager.startListeningSilent()
                        }
                        handleUserQuery(state.text)
                    }
                    is SpeechState.Error -> {
                        _uiState.value = _uiState.value.copy(
                            isListening = false,
                            error = state.message,
                            partialSpeech = ""
                        )
                    }
                }
            }
        }
    }

    private fun observePartialResults() {
        viewModelScope.launch {
            speechManager.partialResults.collect { partial ->
                _uiState.value = _uiState.value.copy(partialSpeech = partial)
            }
        }
    }

    private fun observeChirpTtsState(tts: GoogleCloudTTS) {
        chirpObserverJob = viewModelScope.launch {
            var wasSpeaking = false
            tts.isSpeaking.collect { speaking ->
                Log.d(TAG, "Chirp TTS state: speaking=$speaking, wasSpeaking=$wasSpeaking, engine=${currentSettings.ttsEngine}")
                if (currentSettings.ttsEngine == TtsEngine.CHIRP) {
                    _uiState.value = _uiState.value.copy(isSpeaking = speaking)
                    // Auto-start listening when TTS finishes (silently to avoid beep)
                    if (wasSpeaking && !speaking) {
                        Log.d(TAG, "Chirp TTS finished, triggering silent auto-listen")
                        viewModelScope.launch {
                            kotlinx.coroutines.delay(300)
                            startListeningSilent()
                        }
                    }
                    wasSpeaking = speaking
                }
            }
        }
    }

    private fun observeCartesiaTtsState(tts: CartesiaTTS) {
        cartesiaObserverJob = viewModelScope.launch {
            var wasSpeaking = false
            tts.isSpeaking.collect { speaking ->
                Log.d(TAG, "Cartesia TTS state: speaking=$speaking, wasSpeaking=$wasSpeaking, engine=${currentSettings.ttsEngine}")
                if (currentSettings.ttsEngine == TtsEngine.CARTESIA) {
                    _uiState.value = _uiState.value.copy(
                        isSpeaking = speaking,
                        ttsLatencyMs = if (!speaking) null else _uiState.value.ttsLatencyMs
                    )
                    // Auto-start listening when TTS finishes (silently to avoid beep)
                    if (wasSpeaking && !speaking) {
                        Log.d(TAG, "Cartesia TTS finished, triggering silent auto-listen")
                        viewModelScope.launch {
                            kotlinx.coroutines.delay(300)
                            startListeningSilent()
                        }
                    }
                    wasSpeaking = speaking
                }
            }
        }
        cartesiaLatencyObserverJob = viewModelScope.launch {
            tts.latencyMs.collect { latency ->
                if (currentSettings.ttsEngine == TtsEngine.CARTESIA) {
                    _uiState.value = _uiState.value.copy(ttsLatencyMs = latency)
                }
            }
        }
    }

    private fun observeLocalTtsState(tts: LocalTTS) {
        localObserverJob = viewModelScope.launch {
            var wasSpeaking = false
            tts.isSpeaking.collect { speaking ->
                Log.d(TAG, "Local TTS state: speaking=$speaking, wasSpeaking=$wasSpeaking, engine=${currentSettings.ttsEngine}")
                if (currentSettings.ttsEngine == TtsEngine.LOCAL) {
                    _uiState.value = _uiState.value.copy(isSpeaking = speaking)
                    // Auto-start listening when TTS finishes (silently to avoid beep)
                    if (wasSpeaking && !speaking) {
                        Log.d(TAG, "Local TTS finished, triggering silent auto-listen")
                        viewModelScope.launch {
                            kotlinx.coroutines.delay(300)
                            startListeningSilent()
                        }
                    }
                    wasSpeaking = speaking
                }
            }
        }
    }

    fun startListening() {
        Log.d(TAG, "startListening() called, current isListening=${_uiState.value.isListening}")
        stopSpeaking()
        speechManager.resetState()
        speechManager.startListening()
    }

    /**
     * Start listening without the beep sound - used for auto-restart after TTS.
     */
    private fun startListeningSilent() {
        Log.d(TAG, "startListeningSilent() called")
        stopSpeaking()
        speechManager.resetState()
        speechManager.startListeningSilent()
    }

    fun stopListening() {
        speechManager.stopListening()
    }

    private fun handleUserQuery(query: String) {
        val useGeminiSearch = currentSettings.geminiSearch

        // Check if we have the right API configured
        if (useGeminiSearch) {
            if (geminiApi == null) {
                _uiState.value = _uiState.value.copy(
                    error = "Google Cloud API key not configured. Please sign out and sign in again."
                )
                return
            }
        } else {
            if (openRouterApi == null) {
                _uiState.value = _uiState.value.copy(
                    error = "Please set your OpenRouter API key in Settings"
                )
                return
            }
        }

        // Add user message to UI
        val userMessage = Message(content = query, isUser = true)
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages + userMessage + Message(
                content = "",
                isUser = false,
                isLoading = true
            )
        )

        // Add to conversation history
        conversationHistory.add(ChatMessage(role = "user", content = query))

        // Send to LLM
        viewModelScope.launch {
            if (useGeminiSearch) {
                // Use Gemini API with Google Search grounding
                val result = geminiApi!!.chat(
                    messages = conversationHistory,
                    systemPrompt = systemPrompt,
                    enableSearch = true
                )

                result.onSuccess { geminiResult ->
                    conversationHistory.add(ChatMessage(role = "assistant", content = geminiResult.text))

                    // Remove loading message and add actual response
                    val currentMessages = _uiState.value.messages.dropLast(1)
                    _uiState.value = _uiState.value.copy(
                        messages = currentMessages + Message(content = geminiResult.text, isUser = false)
                    )

                    // Log sources if available
                    if (geminiResult.sources.isNotEmpty()) {
                        Log.d(TAG, "Gemini sources: ${geminiResult.sources.map { it.title }}")
                    }

                    // Speak the response with selected TTS engine
                    speakResponse(geminiResult.text)
                }.onFailure { error ->
                    // Remove loading message
                    val currentMessages = _uiState.value.messages.dropLast(1)
                    _uiState.value = _uiState.value.copy(
                        messages = currentMessages,
                        error = "Failed to get response: ${error.message}"
                    )
                }
            } else {
                // Use OpenRouter API
                val result = openRouterApi!!.chat(
                    conversationHistory,
                    model = currentSettings.llmModel,
                    systemPrompt = systemPrompt
                )

                result.onSuccess { response ->
                    conversationHistory.add(ChatMessage(role = "assistant", content = response))

                    // Remove loading message and add actual response
                    val currentMessages = _uiState.value.messages.dropLast(1)
                    _uiState.value = _uiState.value.copy(
                        messages = currentMessages + Message(content = response, isUser = false)
                    )

                    // Speak the response with selected TTS engine
                    speakResponse(response)
                }.onFailure { error ->
                    // Remove loading message
                    val currentMessages = _uiState.value.messages.dropLast(1)
                    _uiState.value = _uiState.value.copy(
                        messages = currentMessages,
                        error = "Failed to get response: ${error.message}"
                    )
                }
            }
        }
    }

    private fun speakResponse(text: String) {
        // Stop listening before speaking (important for car mode where mic stays active)
        speechManager.stopListening()

        when (currentSettings.ttsEngine) {
            TtsEngine.CARTESIA -> {
                cartesiaTts?.speak(text, voiceId = currentSettings.cartesiaVoice)
            }
            TtsEngine.CHIRP -> {
                chirpTts?.speak(
                    text = text,
                    voiceName = currentSettings.ttsVoice
                )
            }
            TtsEngine.LOCAL -> {
                localTts?.speak(text)
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun stopSpeaking() {
        chirpTts?.stop()
        cartesiaTts?.stop()
        localTts?.stop()
    }

    fun clearConversation() {
        stopSpeaking()
        conversationHistory.clear()
        _uiState.value = AssistantUiState()
    }

    override fun onCleared() {
        super.onCleared()
        speechManager.destroy()
        chirpTts?.destroy()
        cartesiaTts?.destroy()
        localTts?.destroy()
        openRouterApi?.close()
        bluetoothScoManager.destroy()
    }
}
