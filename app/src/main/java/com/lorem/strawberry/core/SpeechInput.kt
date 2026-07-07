package com.lorem.strawberry.core

import kotlinx.coroutines.flow.StateFlow

sealed class SpeechState {
    data object Idle : SpeechState()
    data object Listening : SpeechState()
    data class Result(val text: String) : SpeechState()
    data class Error(val message: String) : SpeechState()
}

/**
 * Speech-to-text input source. Implemented by SpeechRecognizerManager; faked in tests.
 */
interface SpeechInput {
    val state: StateFlow<SpeechState>
    val partialResults: StateFlow<String>

    /** Auto-restart listening after silence/no-match. */
    var continuousListening: Boolean

    fun startListening()

    /** Start listening without the system beep (auto-restarts after TTS). */
    fun startListeningSilent()

    fun stopListening()

    fun resetState()

    fun destroy()
}
