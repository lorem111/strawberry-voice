package com.example.voiceassistant.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.voiceassistant.data.AppSettings
import com.example.voiceassistant.data.ChatMessage
import com.example.voiceassistant.data.OpenRouterApi
import com.example.voiceassistant.data.TtsEngine
import com.example.voiceassistant.speech.CartesiaTTS
import com.example.voiceassistant.speech.GoogleCloudTTS
import com.example.voiceassistant.speech.LocalTTS
import com.example.voiceassistant.speech.SpeechRecognizerManager
import com.example.voiceassistant.speech.SpeechState
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

    private var currentSettings = AppSettings()
    private var openRouterApi: OpenRouterApi? = null
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

        // Update Chirp TTS if Google Cloud key changed or first load
        if (isFirstLoad || settings.googleCloudApiKey != currentSettings.googleCloudApiKey) {
            chirpObserverJob?.cancel()
            chirpTts?.destroy()
            chirpTts = if (settings.googleCloudApiKey.isNotBlank()) {
                GoogleCloudTTS(context, settings.googleCloudApiKey).also { tts ->
                    observeChirpTtsState(tts)
                }
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
                    // Auto-start listening when TTS finishes
                    if (wasSpeaking && !speaking) {
                        Log.d(TAG, "Chirp TTS finished, triggering auto-listen")
                        viewModelScope.launch {
                            kotlinx.coroutines.delay(300)
                            startListening()
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
                    // Auto-start listening when TTS finishes
                    if (wasSpeaking && !speaking) {
                        Log.d(TAG, "Cartesia TTS finished, triggering auto-listen")
                        viewModelScope.launch {
                            kotlinx.coroutines.delay(300)
                            startListening()
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
                    // Auto-start listening when TTS finishes
                    if (wasSpeaking && !speaking) {
                        Log.d(TAG, "Local TTS finished, triggering auto-listen")
                        viewModelScope.launch {
                            kotlinx.coroutines.delay(300)
                            startListening()
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

    fun stopListening() {
        speechManager.stopListening()
    }

    private fun handleUserQuery(query: String) {
        val api = openRouterApi
        if (api == null) {
            _uiState.value = _uiState.value.copy(
                error = "Please set your OpenRouter API key in Settings"
            )
            return
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
            val result = api.chat(conversationHistory, model = currentSettings.llmModel, systemPrompt = systemPrompt)

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

    private fun speakResponse(text: String) {
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
    }
}
