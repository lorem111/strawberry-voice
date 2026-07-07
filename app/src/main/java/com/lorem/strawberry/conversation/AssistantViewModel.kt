package com.lorem.strawberry.conversation

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lorem.strawberry.chat.ImageStore
import com.lorem.strawberry.core.ChatSummary
import com.lorem.strawberry.network.ConnectivityObserver
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AssistantUiState(
    val messages: List<Message> = emptyList(),
    val isListening: Boolean = false,
    val isSpeaking: Boolean = false,
    val isThinking: Boolean = false,
    val partialSpeech: String = "",
    val error: String? = null,
    val ttsLatencyMs: Long? = null
)

/**
 * Thin UI adapter: maps the orchestrator's state to AssistantUiState and forwards intents.
 * All conversation logic lives in [ConversationOrchestrator].
 */
@HiltViewModel
class AssistantViewModel @Inject constructor(
    private val orchestrator: ConversationOrchestrator,
    private val imageStore: ImageStore,
    connectivityObserver: ConnectivityObserver
) : ViewModel() {

    val isOnline: StateFlow<Boolean> = connectivityObserver.isOnline

    val uiState: StateFlow<AssistantUiState> = combine(
        orchestrator.state,
        orchestrator.messages,
        orchestrator.partialSpeech,
        orchestrator.lastError,
        orchestrator.ttsLatencyMs
    ) { state, messages, partial, error, latency ->
        AssistantUiState(
            messages = messages,
            isListening = state is ConversationState.Listening,
            isSpeaking = state is ConversationState.Speaking,
            isThinking = state is ConversationState.Thinking,
            partialSpeech = partial,
            error = error,
            ttsLatencyMs = latency
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AssistantUiState())

    val chats: StateFlow<List<ChatSummary>> = orchestrator.chats
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeChatId: StateFlow<Long?> = orchestrator.activeChatId

    /** Local path of an image attached to the next message, if any. */
    private val _pendingImagePath = MutableStateFlow<String?>(null)
    val pendingImagePath: StateFlow<String?> = _pendingImagePath.asStateFlow()

    fun startListening() = orchestrator.startListening()

    fun stopListening() = orchestrator.stopListening()

    fun stopSpeaking() = orchestrator.stopSpeaking()

    fun clearError() = orchestrator.clearError()

    fun sendMessage(text: String) {
        orchestrator.sendTextMessage(text, _pendingImagePath.value)
        _pendingImagePath.value = null
    }

    fun attachImage(uri: Uri) {
        viewModelScope.launch {
            _pendingImagePath.value = imageStore.importImage(uri)
        }
    }

    fun removePendingImage() {
        _pendingImagePath.value = null
    }

    fun newChat() = orchestrator.newChat()

    fun selectChat(chatId: Long) = orchestrator.selectChat(chatId)

    fun deleteChat(chatId: Long) = orchestrator.deleteChat(chatId)
}
