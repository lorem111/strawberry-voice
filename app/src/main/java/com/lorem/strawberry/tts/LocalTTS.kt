package com.lorem.strawberry.tts

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import com.lorem.strawberry.core.AppLogger
import com.lorem.strawberry.core.TtsEngine
import com.lorem.strawberry.core.TtsState
import com.lorem.strawberry.settings.TtsEngineId

class LocalTTS(
    context: Context,
    private val logger: AppLogger
) : TtsEngine {

    private var tts: TextToSpeech? = null
    private var isInitialized = false

    private val _state = MutableStateFlow<TtsState>(TtsState.Idle)
    override val state: StateFlow<TtsState> = _state.asStateFlow()

    override val id: String = TtsEngineId.LOCAL

    // Android's built-in TTS uses the device voice; no per-app voice selection
    override var voice: String? = null

    private var _useVoiceCommunication: Boolean = false
    override var useVoiceCommunication: Boolean
        get() = _useVoiceCommunication
        set(value) {
            _useVoiceCommunication = value
            updateAudioAttributes()
        }

    companion object {
        private const val TAG = "LocalTTS"
    }

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isInitialized = true
                tts?.language = Locale.US
                tts?.setSpeechRate(1.0f)
                updateAudioAttributes()

                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _state.value = TtsState.Speaking()
                    }

                    override fun onDone(utteranceId: String?) {
                        _state.value = TtsState.Idle
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        _state.value = TtsState.Error("TTS playback error")
                    }
                })

                logger.d(TAG, "TTS initialized successfully")
            } else {
                _state.value = TtsState.Error("Failed to initialize TTS")
                logger.e(TAG, "TTS initialization failed with status: $status")
            }
        }
    }

    private fun updateAudioAttributes() {
        if (!isInitialized) return

        val audioUsage = if (_useVoiceCommunication) {
            AudioAttributes.USAGE_VOICE_COMMUNICATION
        } else {
            AudioAttributes.USAGE_MEDIA
        }

        tts?.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(audioUsage)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        logger.d(TAG, "Audio attributes updated: useVoiceCommunication=$_useVoiceCommunication")
    }

    override fun speak(text: String) {
        if (!isInitialized) {
            _state.value = TtsState.Error("TTS not initialized")
            return
        }

        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_utterance")
    }

    override fun stop() {
        tts?.stop()
        _state.value = TtsState.Idle
    }

    override fun destroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }
}
