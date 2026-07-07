package com.lorem.strawberry.core

import kotlinx.coroutines.flow.StateFlow

/**
 * Playback state shared by all TTS engines.
 */
sealed interface TtsState {
    data object Idle : TtsState

    /** [latencyMs] is time-to-first-audio, when the engine can measure it. */
    data class Speaking(val latencyMs: Long? = null) : TtsState

    data class Error(val message: String) : TtsState
}

/**
 * A speech synthesizer. Implementations: CartesiaTTS (streaming), GoogleCloudTTS (Chirp),
 * LocalTTS (on-device). New engines only need to implement this and be wired in EngineRegistry.
 */
interface TtsEngine {
    /** Stable id matching [com.lorem.strawberry.settings.TtsEngineId]. */
    val id: String

    val state: StateFlow<TtsState>

    /** Engine-specific voice id; null means the engine default. */
    var voice: String?

    /** When true, play with USAGE_VOICE_COMMUNICATION so audio routes through Bluetooth SCO. */
    var useVoiceCommunication: Boolean

    fun speak(text: String)

    fun stop()

    /** Release all resources. The engine must not be used afterwards. */
    fun destroy()
}
