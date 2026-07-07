package com.lorem.strawberry.conversation

import com.lorem.strawberry.core.AppLogger
import com.lorem.strawberry.core.ChatStore
import com.lorem.strawberry.core.ChatSummary
import com.lorem.strawberry.core.ChatTurn
import com.lorem.strawberry.core.ForegroundController
import com.lorem.strawberry.core.LlmClient
import com.lorem.strawberry.core.ScoController
import com.lorem.strawberry.core.SpeechInput
import com.lorem.strawberry.core.SpeechState
import com.lorem.strawberry.core.TtsEngine
import com.lorem.strawberry.core.TtsState
import com.lorem.strawberry.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Owns the conversation loop (listen/type → think → speak → listen) and chat threads.
 * Pure Kotlin apart from the injected interfaces, so the whole loop is unit-testable
 * with fakes.
 *
 * Cross-cutting rules live in [setState]:
 * - entering Thinking in car mode starts the SCO keepalive
 * - leaving Thinking for ANY reason (success, LLM failure, clear) stops it
 * - the foreground service runs whenever the loop is active or car mode is on
 *
 * Voice replies stream sentence-by-sentence: as LLM deltas arrive, complete sentences
 * are queued into a speak session that plays them sequentially; the session owns the
 * auto-relisten when it drains. Typed replies are not spoken. With barge-in enabled,
 * the mic stays hot while speaking and user speech cancels the session.
 */
class ConversationOrchestrator(
    private val scope: CoroutineScope,
    private val speech: SpeechInput,
    private val sco: ScoController,
    private val chatStore: ChatStore,
    private val activeTts: StateFlow<TtsEngine?>,
    private val activeLlm: StateFlow<LlmClient?>,
    val settings: StateFlow<AppSettings>,
    private val foreground: ForegroundController,
    private val logger: AppLogger,
    private val systemPrompt: () -> String,
    private val relistenDelayMs: Long = 300
) {

    companion object {
        private const val TAG = "ConversationOrchestrator"
        private const val MAX_TITLE_LENGTH = 40

        // Cap context sent to the LLM; no summarization yet, just the most recent turns
        private const val MAX_HISTORY_TURNS = 100

        // A sentence ends with terminal punctuation followed by whitespace
        private val SENTENCE_END = Regex("[.!?]\\s")
    }

    private val _state = MutableStateFlow<ConversationState>(ConversationState.Idle)
    val state: StateFlow<ConversationState> = _state.asStateFlow()

    private val _activeChatId = MutableStateFlow<Long?>(null)
    val activeChatId: StateFlow<Long?> = _activeChatId.asStateFlow()

    val chats: Flow<List<ChatSummary>> = chatStore.chats

    /** Partial LLM response text while it streams in; null when not streaming. */
    private val _streamingText = MutableStateFlow<String?>(null)

    /**
     * Messages of the active thread. While the LLM responds, a transient bubble shows
     * the streamed text (or loading dots before the first delta); it's replaced by the
     * persisted message when the reply completes.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val messages: StateFlow<List<Message>> = combine(
        _activeChatId.flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else chatStore.messages(id)
        },
        _state,
        _streamingText
    ) { stored, state, streaming ->
        val base = stored.map {
            Message(content = it.text, isUser = it.role == "user", imagePath = it.imagePath)
        }
        when {
            !streaming.isNullOrEmpty() -> base + Message(content = streaming, isUser = false)
            state is ConversationState.Thinking -> base + Message(content = "", isUser = false, isLoading = true)
            else -> base
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _ttsLatencyMs = MutableStateFlow<Long?>(null)
    val ttsLatencyMs: StateFlow<Long?> = _ttsLatencyMs.asStateFlow()

    val partialSpeech: StateFlow<String> = speech.partialResults

    private val conversationHistory = mutableListOf<ChatTurn>()
    private var appliedSettings: AppSettings? = null

    /** Sequential sentence player for the current voice reply. */
    private inner class SpeakSession(val tts: TtsEngine) {
        val sentences = Channel<String>(Channel.UNLIMITED)
        var job: Job? = null
    }

    private var speakSession: SpeakSession? = null

    init {
        scope.launch {
            speech.state.collect { handleSpeechState(it) }
        }
        scope.launch {
            @OptIn(ExperimentalCoroutinesApi::class)
            activeTts.flatMapLatest { it?.state ?: flowOf(TtsState.Idle) }
                .collect { handleTtsState(it) }
        }
        scope.launch {
            settings.collect { applySettings(it) }
        }
        scope.launch {
            speech.partialResults.collect { partial ->
                if (partial.isNotBlank() &&
                    _state.value is ConversationState.Speaking &&
                    settings.value.bargeIn
                ) {
                    logger.d(TAG, "Barge-in: user speech during TTS, cancelling playback")
                    cancelSpeakSession()
                    setState(ConversationState.Listening)
                }
            }
        }
    }

    // ---- Public intents -----------------------------------------------------------------

    fun startListening() {
        logger.d(TAG, "startListening() called, state=${_state.value}")
        stopSpeaking()
        speech.resetState()
        speech.startListening()
    }

    fun stopListening() {
        speech.stopListening()
    }

    fun stopSpeaking() {
        cancelSpeakSession()
        if (_state.value is ConversationState.Speaking) {
            setState(ConversationState.Idle)
        }
    }

    /** Send a typed message (optionally with an image). The reply is not spoken. */
    fun sendTextMessage(text: String, imagePath: String? = null) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && imagePath == null) return
        stopSpeaking()
        speech.stopListening()
        submitQuery(trimmed, imagePath, speakResponse = false)
    }

    /** Start a fresh thread; it's created lazily on the first message. */
    fun newChat() {
        stopSpeaking()
        speech.stopListening()
        _activeChatId.value = null
        conversationHistory.clear()
        _lastError.value = null
        setState(ConversationState.Idle)
    }

    fun selectChat(chatId: Long) {
        if (_activeChatId.value == chatId) return
        stopSpeaking()
        speech.stopListening()
        _lastError.value = null
        setState(ConversationState.Idle)
        scope.launch {
            conversationHistory.clear()
            conversationHistory += chatStore.getHistory(chatId).map {
                ChatTurn(role = it.role, text = it.text, imagePath = it.imagePath)
            }
            trimHistory()
            _activeChatId.value = chatId
        }
    }

    fun deleteChat(chatId: Long) {
        scope.launch {
            chatStore.deleteChat(chatId)
            if (_activeChatId.value == chatId) {
                newChat()
            }
        }
    }

    fun clearError() {
        _lastError.value = null
    }

    // ---- State machine ------------------------------------------------------------------

    private fun setState(new: ConversationState) {
        val old = _state.value
        if (old == new) return

        // Leaving Thinking for any reason ends the SCO keepalive — this is the single
        // place that guarantees silent audio can't leak on error paths
        if (old is ConversationState.Thinking) {
            sco.stopKeepalive()
        }
        if (new is ConversationState.Thinking && settings.value.carMode) {
            sco.startKeepalive()
        }

        _state.value = new
        foreground.setActive(new !is ConversationState.Idle || settings.value.carMode)
    }

    private fun handleSpeechState(speechState: SpeechState) {
        when (speechState) {
            is SpeechState.Idle -> {
                if (_state.value is ConversationState.Listening) {
                    setState(ConversationState.Idle)
                }
            }
            is SpeechState.Listening -> {
                // With barge-in the mic is hot during TTS; stay in Speaking
                if (_state.value is ConversationState.Speaking) return
                _lastError.value = null
                setState(ConversationState.Listening)
            }
            is SpeechState.Result -> {
                if (_state.value is ConversationState.Speaking) {
                    cancelSpeakSession()
                }
                submitQuery(speechState.text, imagePath = null, speakResponse = true)
            }
            is SpeechState.Error -> {
                _lastError.value = speechState.message
                if (_state.value is ConversationState.Listening) {
                    setState(ConversationState.Idle)
                }
            }
        }
    }

    private fun handleTtsState(ttsState: TtsState) {
        when (ttsState) {
            is TtsState.Speaking -> {
                _ttsLatencyMs.value = ttsState.latencyMs
            }
            is TtsState.Error -> {
                _ttsLatencyMs.value = null
                _lastError.value = ttsState.message
            }
            is TtsState.Idle -> {
                _ttsLatencyMs.value = null
                // Relisten is owned by the speak session, not raw TTS transitions —
                // between queued sentences the engine goes Idle without the reply being done
            }
        }
    }

    private fun startListeningSilent() {
        stopSpeaking()
        speech.resetState()
        speech.startListeningSilent()
    }

    // ---- Speak session (sentence-pipelined TTS) -------------------------------------------

    /** Returns false when no TTS engine is available. */
    private fun ensureSpeakSession(): Boolean {
        if (speakSession != null) return true
        val tts = activeTts.value
        if (tts == null) {
            logger.w(TAG, "No TTS engine available, skipping speech")
            return false
        }

        val session = SpeakSession(tts)
        speakSession = session
        setState(ConversationState.Speaking)

        if (settings.value.bargeIn) {
            // Keep the mic hot so the user can interrupt; echo risk is why this is opt-in
            speech.resetState()
            speech.startListeningSilent()
        } else {
            speech.stopListening()
        }

        session.job = scope.launch {
            for (sentence in session.sentences) {
                session.tts.speak(sentence)
                // Wait for this utterance to start, then finish (or fail)
                val started = session.tts.state.first { it is TtsState.Speaking || it is TtsState.Error }
                if (started is TtsState.Error) break
                val finished = session.tts.state.first { it !is TtsState.Speaking }
                if (finished is TtsState.Error) break
            }
            onSpeakSessionComplete(session)
        }
        return true
    }

    private fun enqueueSentence(sentence: String) {
        if (sentence.isBlank()) return
        if (!ensureSpeakSession()) return
        speakSession?.sentences?.trySend(sentence)
    }

    /** No more sentences will be added; the session ends once the queue drains. */
    private fun finishSpeakSession() {
        val session = speakSession
        if (session == null) {
            // Nothing was speakable (empty reply or no TTS engine)
            if (_state.value is ConversationState.Thinking) {
                setState(ConversationState.Idle)
            }
            return
        }
        session.sentences.close()
    }

    private fun cancelSpeakSession() {
        speakSession?.let {
            it.job?.cancel()
            it.sentences.close()
            it.tts.stop()
        }
        speakSession = null
    }

    private fun onSpeakSessionComplete(session: SpeakSession) {
        if (speakSession !== session) return // superseded by cancel/new session
        speakSession = null
        if (_state.value !is ConversationState.Speaking) return

        setState(ConversationState.Idle)
        scope.launch {
            delay(relistenDelayMs)
            if (speech.state.value is SpeechState.Listening) {
                // Barge-in kept the mic on; just reflect it in the loop state
                setState(ConversationState.Listening)
            } else {
                // Resume listening silently (no beep) for the next turn
                logger.d(TAG, "Reply finished, triggering silent auto-listen")
                startListeningSilent()
            }
        }
    }

    /** Removes and returns the complete sentences at the start of [buffer]. */
    private fun drainCompleteSentences(buffer: StringBuilder): List<String> {
        val sentences = mutableListOf<String>()
        while (true) {
            val match = SENTENCE_END.find(buffer) ?: break
            val end = match.range.last + 1
            val sentence = buffer.substring(0, end).trim()
            buffer.delete(0, end)
            if (sentence.isNotEmpty()) sentences += sentence
        }
        return sentences
    }

    // ---- Query handling -----------------------------------------------------------------

    private fun submitQuery(text: String, imagePath: String?, speakResponse: Boolean) {
        logger.d(TAG, "User query (speak=$speakResponse, image=${imagePath != null}): $text")

        cancelSpeakSession()
        setState(ConversationState.Thinking)
        _streamingText.value = null

        scope.launch {
            val chatId = ensureActiveChat(text)
            chatStore.appendMessage(chatId, role = "user", text = text, imagePath = imagePath)
            conversationHistory.add(ChatTurn(role = "user", text = text, imagePath = imagePath))
            trimHistory()

            val llm = activeLlm.value
            if (llm == null) {
                val message = if (settings.value.geminiSearch) {
                    "Google Cloud API key not configured. Please sign out and sign in again."
                } else {
                    "Please set your OpenRouter API key in Settings"
                }
                failThinking(message, speakResponse)
                return@launch
            }

            val streamed = StringBuilder()
            val pendingSentence = StringBuilder()

            val result = llm.chat(conversationHistory.toList(), systemPrompt()) { delta ->
                streamed.append(delta)
                _streamingText.value = streamed.toString()
                if (speakResponse) {
                    pendingSentence.append(delta)
                    drainCompleteSentences(pendingSentence).forEach { enqueueSentence(it) }
                }
            }

            result.fold(
                onSuccess = { reply ->
                    conversationHistory.add(ChatTurn(role = "assistant", text = reply.text))
                    trimHistory()
                    chatStore.appendMessage(chatId, role = "assistant", text = reply.text)
                    if (reply.sources.isNotEmpty()) {
                        logger.d(TAG, "Reply sources: ${reply.sources.map { it.title }}")
                    }
                    if (speakResponse) {
                        if (streamed.isEmpty()) {
                            // Non-streaming backend: split the whole reply into sentences
                            val buffer = StringBuilder(reply.text)
                            drainCompleteSentences(buffer).forEach { enqueueSentence(it) }
                            enqueueSentence(buffer.toString().trim())
                        } else {
                            enqueueSentence(pendingSentence.toString().trim())
                        }
                        finishSpeakSession()
                    } else {
                        setState(ConversationState.Idle)
                    }
                    _streamingText.value = null
                },
                onFailure = { error ->
                    logger.e(TAG, "LLM request failed", error)
                    _streamingText.value = null
                    failThinking("Failed to get response: ${error.message}", speakResponse)
                }
            )
        }
    }

    private suspend fun ensureActiveChat(firstText: String): Long {
        _activeChatId.value?.let { return it }
        val title = firstText.trim().take(MAX_TITLE_LENGTH).ifBlank { "New chat" }
        val id = chatStore.createChat(title)
        _activeChatId.value = id
        return id
    }

    private fun trimHistory() {
        while (conversationHistory.size > MAX_HISTORY_TURNS) {
            conversationHistory.removeAt(0)
        }
    }

    private fun failThinking(message: String, wasVoiceQuery: Boolean) {
        _lastError.value = message
        setState(ConversationState.Idle)

        // In car mode the user can't tap the mic — resume listening so the
        // conversation survives a failed request (voice queries only)
        if (wasVoiceQuery && settings.value.carMode) {
            scope.launch {
                delay(relistenDelayMs)
                startListeningSilent()
            }
        }
    }

    // ---- Settings -----------------------------------------------------------------------

    private fun applySettings(new: AppSettings) {
        val old = appliedSettings
        appliedSettings = new

        speech.continuousListening = new.continuousListening

        if (old?.carMode != new.carMode) {
            if (new.carMode) {
                logger.d(TAG, "Enabling car mode (Bluetooth SCO)")
                sco.start()
            } else if (old != null) {
                logger.d(TAG, "Disabling car mode (Bluetooth SCO)")
                sco.stop()
            }
            foreground.setActive(_state.value !is ConversationState.Idle || new.carMode)
        }
    }
}
