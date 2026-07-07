package com.lorem.strawberry.conversation

import com.lorem.strawberry.core.LlmClient
import com.lorem.strawberry.core.LlmReply
import com.lorem.strawberry.core.TtsEngine
import com.lorem.strawberry.settings.AppSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Orchestrator collectors run on runTest's backgroundScope, so tests drive them with
 * runCurrent()/advanceTimeBy() — advanceUntilIdle() intentionally skips background work.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationOrchestratorTest {

    private val speech = FakeSpeechInput()
    private val sco = FakeSco()
    private val tts = FakeTts()
    private val foreground = FakeForeground()
    private val chatStore = FakeChatStore()
    private val activeTts = MutableStateFlow<TtsEngine?>(tts)
    private val activeLlm = MutableStateFlow<LlmClient?>(null)
    private val settings = MutableStateFlow(AppSettings())

    private fun TestScope.createOrchestrator(): ConversationOrchestrator {
        val orchestrator = ConversationOrchestrator(
            scope = backgroundScope,
            speech = speech,
            sco = sco,
            chatStore = chatStore,
            activeTts = activeTts,
            activeLlm = activeLlm,
            settings = settings,
            foreground = foreground,
            logger = NoopLogger(),
            systemPrompt = { "test prompt" }
        )
        runCurrent() // let the collectors start
        return orchestrator
    }

    @Test
    fun `voice utterance flows through llm to tts, then auto-relistens`() = runTest {
        activeLlm.value = FakeLlm(Result.success(LlmReply("hi there")))
        val orchestrator = createOrchestrator()

        speech.emitResult("hello")
        runCurrent()

        assertEquals(listOf("hi there"), tts.spoken)
        assertEquals(ConversationState.Speaking, orchestrator.state.value)
        assertEquals(2, orchestrator.messages.value.size)
        assertEquals("hello", orchestrator.messages.value[0].content)
        assertEquals("hi there", orchestrator.messages.value[1].content)
        assertFalse(orchestrator.messages.value.any { it.isLoading })

        tts.finishSpeaking()
        runCurrent()
        advanceTimeBy(301)
        runCurrent()

        assertEquals(1, speech.silentStartCalls)
        assertEquals(ConversationState.Listening, orchestrator.state.value)
    }

    @Test
    fun `typed message gets reply but is not spoken`() = runTest {
        activeLlm.value = FakeLlm(Result.success(LlmReply("typed reply")))
        val orchestrator = createOrchestrator()

        orchestrator.sendTextMessage("hello from keyboard")
        runCurrent()

        assertTrue(tts.spoken.isEmpty())
        assertEquals(ConversationState.Idle, orchestrator.state.value)
        assertEquals(2, orchestrator.messages.value.size)
        assertEquals("typed reply", orchestrator.messages.value[1].content)
        // No auto-relisten for typed messages
        assertEquals(0, speech.silentStartCalls)
    }

    @Test
    fun `typed message with image carries the image into history and store`() = runTest {
        val llm = FakeLlm(Result.success(LlmReply("nice photo")))
        activeLlm.value = llm
        val orchestrator = createOrchestrator()

        orchestrator.sendTextMessage("what is this?", imagePath = "/data/img.jpg")
        runCurrent()

        assertEquals("/data/img.jpg", llm.lastHistory.first().imagePath)
        assertEquals("/data/img.jpg", orchestrator.messages.value[0].imagePath)
    }

    @Test
    fun `first message creates a chat titled from the text`() = runTest {
        activeLlm.value = FakeLlm(Result.success(LlmReply("reply")))
        val orchestrator = createOrchestrator()

        assertNull(orchestrator.activeChatId.value)
        orchestrator.sendTextMessage("plan my weekend trip to the mountains please and thanks")
        runCurrent()

        assertNotNull(orchestrator.activeChatId.value)
        val chat = chatStore.chats.first().single()
        assertEquals(40, chat.title.length)
        assertTrue("plan my weekend trip to the mountains please".startsWith(chat.title.take(20)))
    }

    @Test
    fun `switching chats loads that thread's history for the llm`() = runTest {
        val llm = FakeLlm(Result.success(LlmReply("first reply")))
        activeLlm.value = llm
        val orchestrator = createOrchestrator()

        orchestrator.sendTextMessage("first chat message")
        runCurrent()
        val firstChatId = orchestrator.activeChatId.value!!

        orchestrator.newChat()
        runCurrent()
        orchestrator.sendTextMessage("second chat message")
        runCurrent()
        assertEquals(1, llm.lastHistory.size) // fresh thread: only the new user turn

        orchestrator.selectChat(firstChatId)
        runCurrent()
        assertEquals(firstChatId, orchestrator.activeChatId.value)
        assertEquals(2, orchestrator.messages.value.size)

        orchestrator.sendTextMessage("follow-up in first chat")
        runCurrent()
        // History: first message, first reply, follow-up
        assertEquals(3, llm.lastHistory.size)
        assertEquals("first chat message", llm.lastHistory[0].text)
    }

    @Test
    fun `deleting the active chat resets to a fresh thread`() = runTest {
        activeLlm.value = FakeLlm(Result.success(LlmReply("reply")))
        val orchestrator = createOrchestrator()

        orchestrator.sendTextMessage("hello")
        runCurrent()
        val chatId = orchestrator.activeChatId.value!!

        orchestrator.deleteChat(chatId)
        runCurrent()

        assertNull(orchestrator.activeChatId.value)
        assertTrue(orchestrator.messages.value.isEmpty())
        assertTrue(chatStore.chats.first().isEmpty())
    }

    @Test
    fun `llm failure surfaces error, shows no loading bubble, returns to idle`() = runTest {
        activeLlm.value = FakeLlm(Result.failure(Exception("boom")))
        val orchestrator = createOrchestrator()

        speech.emitResult("hello")
        runCurrent()

        assertEquals(ConversationState.Idle, orchestrator.state.value)
        assertNotNull(orchestrator.lastError.value)
        assertTrue(orchestrator.lastError.value!!.contains("boom"))
        // User message persisted, loading bubble gone
        assertEquals(1, orchestrator.messages.value.size)
        assertTrue(orchestrator.messages.value[0].isUser)
        assertFalse(orchestrator.messages.value.any { it.isLoading })
        assertTrue(tts.spoken.isEmpty())
    }

    @Test
    fun `car mode keepalive starts on thinking and stops on llm failure`() = runTest {
        settings.value = AppSettings(carMode = true)
        activeLlm.value = FakeLlm(Result.failure(Exception("network down")))
        createOrchestrator()

        speech.emitResult("hello")
        runCurrent()

        // The keepalive must not leak when the LLM fails (the original car-mode bug)
        assertEquals(1, sco.keepaliveStartCount)
        assertFalse(sco.keepaliveActive)
        assertEquals(1, sco.keepaliveStopCount)

        // Car mode resumes listening so the hands-free conversation survives the error
        advanceTimeBy(301)
        runCurrent()
        assertEquals(1, speech.silentStartCalls)
    }

    @Test
    fun `car mode keepalive stops when speaking starts`() = runTest {
        settings.value = AppSettings(carMode = true)
        activeLlm.value = FakeLlm(Result.success(LlmReply("answer")))
        val orchestrator = createOrchestrator()

        speech.emitResult("question")
        runCurrent()

        assertFalse(sco.keepaliveActive)
        assertEquals(1, sco.keepaliveStartCount)
        assertEquals(1, sco.keepaliveStopCount)
        assertEquals(ConversationState.Speaking, orchestrator.state.value)
    }

    @Test
    fun `missing llm produces friendly configuration error`() = runTest {
        activeLlm.value = null
        val orchestrator = createOrchestrator()

        speech.emitResult("hello")
        runCurrent()

        assertEquals(ConversationState.Idle, orchestrator.state.value)
        assertTrue(orchestrator.lastError.value!!.contains("OpenRouter"))
    }

    @Test
    fun `missing tts still records the reply and returns to idle`() = runTest {
        activeTts.value = null
        activeLlm.value = FakeLlm(Result.success(LlmReply("text only")))
        val orchestrator = createOrchestrator()

        speech.emitResult("hello")
        runCurrent()

        assertEquals(ConversationState.Idle, orchestrator.state.value)
        assertEquals("text only", orchestrator.messages.value[1].content)
    }

    @Test
    fun `newChat starts a fresh thread and history`() = runTest {
        activeLlm.value = FakeLlm(Result.success(LlmReply("reply")))
        val orchestrator = createOrchestrator()

        speech.emitResult("hello")
        runCurrent()
        orchestrator.newChat()
        runCurrent()

        assertTrue(orchestrator.messages.value.isEmpty())
        assertNull(orchestrator.lastError.value)
        assertEquals(ConversationState.Idle, orchestrator.state.value)

        // Next utterance starts a fresh history (just the new user message)
        val llm = FakeLlm(Result.success(LlmReply("fresh")))
        activeLlm.value = llm
        speech.emitResult("new conversation")
        runCurrent()
        assertEquals(1, llm.lastHistory.count { it.role == "user" })
    }

    @Test
    fun `streamed reply speaks sentence by sentence as deltas arrive`() = runTest {
        activeLlm.value = FakeLlm(
            Result.success(LlmReply("Hello there. How are you today")),
            deltas = listOf("Hello ", "there. ", "How are ", "you today")
        )
        val orchestrator = createOrchestrator()

        speech.emitResult("hi")
        runCurrent()

        // First complete sentence is already playing while the rest streams/queues
        assertEquals(listOf("Hello there.", "How are you today"), tts.spoken)
        assertEquals(ConversationState.Speaking, orchestrator.state.value)

        tts.finishSpeaking() // first sentence done
        runCurrent()
        assertEquals(ConversationState.Speaking, orchestrator.state.value)

        tts.finishSpeaking() // second sentence done -> session drains
        runCurrent()
        advanceTimeBy(301)
        runCurrent()
        assertEquals(ConversationState.Listening, orchestrator.state.value)
    }

    @Test
    fun `streamed text appears as a transient bubble while thinking`() = runTest {
        // Reply with no sentence terminator: stays in Thinking while streaming
        activeLlm.value = FakeLlm(
            Result.success(LlmReply("partial answer")),
            deltas = listOf("partial ", "answer")
        )
        val orchestrator = createOrchestrator()

        orchestrator.sendTextMessage("question")
        runCurrent()

        // Stream completed: persisted message visible, no transient bubble left
        assertEquals(2, orchestrator.messages.value.size)
        assertEquals("partial answer", orchestrator.messages.value[1].content)
        assertFalse(orchestrator.messages.value.any { it.isLoading })
    }

    @Test
    fun `barge-in cancels playback and returns to listening`() = runTest {
        settings.value = AppSettings(bargeIn = true)
        activeLlm.value = FakeLlm(Result.success(LlmReply("a very long reply.")))
        val orchestrator = createOrchestrator()

        speech.emitResult("hi")
        runCurrent()
        assertEquals(ConversationState.Speaking, orchestrator.state.value)
        // Barge-in keeps the mic hot during TTS
        assertEquals(1, speech.silentStartCalls)

        // User starts talking over the assistant
        speech.partialResults.value = "wait actually"
        runCurrent()

        assertEquals(ConversationState.Listening, orchestrator.state.value)
        // A new utterance then flows through normally
        activeLlm.value = FakeLlm(Result.success(LlmReply("interrupted reply.")))
        speech.emitResult("wait actually tell me a joke")
        runCurrent()
        assertEquals(ConversationState.Speaking, orchestrator.state.value)
        assertTrue(tts.spoken.contains("interrupted reply."))
    }

    @Test
    fun `history sent to llm is capped at 100 turns`() = runTest {
        val llm = FakeLlm(Result.success(LlmReply("ok")))
        activeLlm.value = llm
        val orchestrator = createOrchestrator()

        repeat(60) { i ->
            orchestrator.sendTextMessage("message $i")
            runCurrent()
        }

        // 60 exchanges = 120 turns; cap keeps the most recent 100 (99 prior + the new one)
        assertEquals(100, llm.lastHistory.size)
        assertEquals("message 59", llm.lastHistory.last().text)
    }

    @Test
    fun `car mode setting toggles sco connection`() = runTest {
        createOrchestrator()

        settings.value = AppSettings(carMode = true)
        runCurrent()
        assertTrue(sco.scoStarted)

        settings.value = AppSettings(carMode = false)
        runCurrent()
        assertFalse(sco.scoStarted)
    }

    @Test
    fun `foreground service tracks conversation activity`() = runTest {
        activeLlm.value = FakeLlm(Result.success(LlmReply("hi")))
        val orchestrator = createOrchestrator()

        orchestrator.startListening()
        runCurrent()
        assertTrue(foreground.serviceRunning)

        speech.emitResult("hello")
        runCurrent()
        assertTrue(foreground.serviceRunning) // speaking

        tts.finishSpeaking()
        runCurrent()
        orchestrator.stopListening()
        speech.resetState()
        runCurrent()
        assertFalse(foreground.serviceRunning)
    }
}
