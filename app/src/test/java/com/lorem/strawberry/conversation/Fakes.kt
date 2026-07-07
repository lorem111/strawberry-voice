package com.lorem.strawberry.conversation

import com.lorem.strawberry.core.AppLogger
import com.lorem.strawberry.core.ChatStore
import com.lorem.strawberry.core.ChatSummary
import com.lorem.strawberry.core.ChatTurn
import com.lorem.strawberry.core.ForegroundController
import com.lorem.strawberry.core.LlmClient
import com.lorem.strawberry.core.LlmReply
import com.lorem.strawberry.core.ScoController
import com.lorem.strawberry.core.SpeechInput
import com.lorem.strawberry.core.SpeechState
import com.lorem.strawberry.core.StoredMessage
import com.lorem.strawberry.core.TtsEngine
import com.lorem.strawberry.core.TtsState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeSpeechInput : SpeechInput {
    val stateFlow = MutableStateFlow<SpeechState>(SpeechState.Idle)
    override val state: StateFlow<SpeechState> = stateFlow
    override val partialResults = MutableStateFlow("")
    override var continuousListening: Boolean = false

    var startCalls = 0
    var silentStartCalls = 0
    var stopCalls = 0

    override fun startListening() {
        startCalls++
        stateFlow.value = SpeechState.Listening
    }

    override fun startListeningSilent() {
        silentStartCalls++
        stateFlow.value = SpeechState.Listening
    }

    override fun stopListening() {
        stopCalls++
    }

    override fun resetState() {
        stateFlow.value = SpeechState.Idle
    }

    override fun destroy() {}

    fun emitResult(text: String) {
        stateFlow.value = SpeechState.Result(text)
    }
}

class FakeSco : ScoController {
    var scoStarted = false
    var keepaliveActive = false
    var keepaliveStartCount = 0
    var keepaliveStopCount = 0

    override fun start() {
        scoStarted = true
    }

    override fun stop() {
        scoStarted = false
    }

    override fun startKeepalive() {
        keepaliveActive = true
        keepaliveStartCount++
    }

    override fun stopKeepalive() {
        if (keepaliveActive) keepaliveStopCount++
        keepaliveActive = false
    }

    override fun destroy() {}
}

class FakeTts : TtsEngine {
    val stateFlow = MutableStateFlow<TtsState>(TtsState.Idle)
    override val state: StateFlow<TtsState> = stateFlow
    override val id: String = "fake"
    override var voice: String? = null
    override var useVoiceCommunication: Boolean = false

    val spoken = mutableListOf<String>()

    override fun speak(text: String) {
        spoken += text
        stateFlow.value = TtsState.Speaking()
    }

    override fun stop() {
        stateFlow.value = TtsState.Idle
    }

    override fun destroy() {}

    fun finishSpeaking() {
        stateFlow.value = TtsState.Idle
    }
}

class FakeLlm(
    var reply: Result<LlmReply>,
    /** When set, these are fed to onDelta before returning, simulating streaming. */
    var deltas: List<String>? = null
) : LlmClient {
    var calls = 0
    var lastHistory: List<ChatTurn> = emptyList()

    override suspend fun chat(
        history: List<ChatTurn>,
        systemPrompt: String?,
        onDelta: ((String) -> Unit)?
    ): Result<LlmReply> {
        calls++
        lastHistory = history
        if (onDelta != null) {
            deltas?.forEach { onDelta(it) }
        }
        return reply
    }

    override fun close() {}
}

class FakeChatStore : ChatStore {
    private var nextChatId = 1L
    private var nextMessageId = 1L
    private val chatsFlow = MutableStateFlow<List<ChatSummary>>(emptyList())
    private val messageFlows = mutableMapOf<Long, MutableStateFlow<List<StoredMessage>>>()

    override val chats: Flow<List<ChatSummary>> = chatsFlow

    private fun flowFor(chatId: Long) =
        messageFlows.getOrPut(chatId) { MutableStateFlow(emptyList()) }

    override fun messages(chatId: Long): Flow<List<StoredMessage>> = flowFor(chatId)

    override suspend fun createChat(title: String): Long {
        val id = nextChatId++
        chatsFlow.value = listOf(ChatSummary(id, title, 0)) + chatsFlow.value
        return id
    }

    override suspend fun appendMessage(chatId: Long, role: String, text: String, imagePath: String?) {
        flowFor(chatId).value += StoredMessage(nextMessageId++, role, text, imagePath)
    }

    override suspend fun getHistory(chatId: Long): List<StoredMessage> = flowFor(chatId).value

    override suspend fun deleteChat(chatId: Long) {
        chatsFlow.value = chatsFlow.value.filterNot { it.id == chatId }
        messageFlows.remove(chatId)
    }
}

class FakeForeground : ForegroundController {
    var serviceRunning = false

    override fun setActive(active: Boolean) {
        serviceRunning = active
    }
}

class NoopLogger : AppLogger {
    override fun d(tag: String, message: String) {}
    override fun i(tag: String, message: String) {}
    override fun w(tag: String, message: String) {}
    override fun e(tag: String, message: String, throwable: Throwable?) {}
}
