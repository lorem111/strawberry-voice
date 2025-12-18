package com.lorem.strawberry.speech

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "SpeechRecognizerManager"

sealed class SpeechState {
    data object Idle : SpeechState()
    data object Listening : SpeechState()
    data class Result(val text: String) : SpeechState()
    data class Error(val message: String) : SpeechState()
}

class SpeechRecognizerManager(private val context: Context) {

    private var speechRecognizer: SpeechRecognizer? = null
    private var retryCount = 0
    private val maxRetries = 3

    private val _state = MutableStateFlow<SpeechState>(SpeechState.Idle)
    val state: StateFlow<SpeechState> = _state.asStateFlow()

    private val _partialResults = MutableStateFlow("")
    val partialResults: StateFlow<String> = _partialResults.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // Continuous listening mode - auto-restart after silence/no match
    var continuousListening: Boolean = false

    // Flag to prevent auto-restart when manually stopped or when processing a result
    private var shouldAutoRestart: Boolean = true

    // Track if we muted audio (to restore it)
    private var didMuteAudio: Boolean = false

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "onReadyForSpeech")
            retryCount = 0 // Reset retry count on success
            _state.value = SpeechState.Listening
            _partialResults.value = ""

            // Unmute after beep would have played (if we muted)
            if (didMuteAudio) {
                mainHandler.postDelayed({
                    unmuteBeep()
                }, 100)
            }
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "onBeginningOfSpeech")
        }

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            Log.d(TAG, "onEndOfSpeech")
        }

        override fun onError(error: Int) {
            Log.d(TAG, "onError: $error, retryCount: $retryCount")

            // Error 11 (ERROR_CANNOT_CHECK_SUPPORT) often resolves itself
            // Wait a bit to see if onReadyForSpeech comes
            if (error == 11) {
                Log.d(TAG, "Error 11, waiting to see if recognizer recovers...")
                // Schedule a retry check - if we're not in Listening state after 200ms, retry
                mainHandler.postDelayed({
                    if (_state.value != SpeechState.Listening && retryCount < maxRetries) {
                        retryCount++
                        Log.d(TAG, "Error 11 did not recover, retrying ($retryCount/$maxRetries)")
                        startListeningInternal()
                    } else if (_state.value != SpeechState.Listening) {
                        Log.d(TAG, "Error 11 did not recover after max retries, going to idle")
                        _state.value = SpeechState.Idle
                    }
                }, 200)
                return
            }

            // Other recoverable errors - go to idle without showing error
            val isRecoverable = error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                    error == SpeechRecognizer.ERROR_CLIENT

            if (isRecoverable) {
                Log.d(TAG, "Recoverable error, going to idle")
                _state.value = SpeechState.Idle

                // In continuous mode, auto-restart after silence/no match (silently)
                if (continuousListening && shouldAutoRestart) {
                    Log.d(TAG, "Continuous mode: auto-restarting silently after recoverable error")
                    mainHandler.postDelayed({
                        if (continuousListening && shouldAutoRestart) {
                            startListeningSilent()
                        }
                    }, 300)
                }
                return
            }

            val message = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                SpeechRecognizer.ERROR_NETWORK -> "Network error"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                SpeechRecognizer.ERROR_SERVER -> "Server error"
                else -> "Speech error ($error)"
            }
            _state.value = SpeechState.Error(message)
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull() ?: ""
            Log.d(TAG, "onResults: $text")
            _state.value = if (text.isNotEmpty()) {
                SpeechState.Result(text)
            } else {
                SpeechState.Error("No speech detected")
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            _partialResults.value = matches?.firstOrNull() ?: ""
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    fun startListening() {
        Log.d(TAG, "startListening() called")
        retryCount = 0
        shouldAutoRestart = true  // Re-enable auto-restart when starting
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition not available")
            _state.value = SpeechState.Error("Speech recognition not available")
            return
        }
        startListeningInternal(silent = false)
    }

    /**
     * Start listening silently (mute the beep) - used for continuous mode restarts.
     */
    fun startListeningSilent() {
        Log.d(TAG, "startListeningSilent() called")
        retryCount = 0
        shouldAutoRestart = true
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition not available")
            _state.value = SpeechState.Error("Speech recognition not available")
            return
        }
        startListeningInternal(silent = true)
    }

    private fun startListeningInternal(silent: Boolean = false) {
        Log.d(TAG, "startListeningInternal() called, retryCount: $retryCount, silent: $silent")

        // Mute beep if silent mode requested
        if (silent) {
            muteBeep()
        }

        mainHandler.post {
            try {
                Log.d(TAG, "Destroying old recognizer")
                speechRecognizer?.destroy()
                speechRecognizer = null

                // Small delay to let the old recognizer fully release
                mainHandler.postDelayed({
                    try {
                        Log.d(TAG, "Creating new SpeechRecognizer")
                        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)

                        if (speechRecognizer == null) {
                            Log.e(TAG, "Failed to create SpeechRecognizer")
                            _state.value = SpeechState.Error("Failed to create speech recognizer")
                            return@postDelayed
                        }

                        speechRecognizer?.setRecognitionListener(recognitionListener)

                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                        }

                        Log.d(TAG, "Calling speechRecognizer.startListening()")
                        speechRecognizer?.startListening(intent)
                        Log.d(TAG, "speechRecognizer.startListening() called successfully")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error creating/starting speech recognition", e)
                        _state.value = SpeechState.Error("Failed to start: ${e.message}")
                    }
                }, 100) // 100ms delay after destroy
            } catch (e: Exception) {
                Log.e(TAG, "Error destroying speech recognition", e)
            }
        }
    }

    fun stopListening() {
        shouldAutoRestart = false
        speechRecognizer?.stopListening()
    }

    /**
     * Temporarily pause auto-restart (e.g., when TTS is speaking).
     * Call resumeAutoRestart() when ready to listen again.
     */
    fun pauseAutoRestart() {
        shouldAutoRestart = false
    }

    /**
     * Resume auto-restart after pausing (e.g., when TTS finishes).
     */
    fun resumeAutoRestart() {
        shouldAutoRestart = true
    }

    fun resetState() {
        _state.value = SpeechState.Idle
        _partialResults.value = ""
    }

    fun destroy() {
        shouldAutoRestart = false
        unmuteBeep() // Ensure we don't leave audio muted
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    /**
     * Mute the beep sound that plays when speech recognition starts.
     * Only used in continuous mode to avoid repeated beeps.
     */
    @Suppress("DEPRECATION")
    private fun muteBeep() {
        try {
            // Use adjustStreamVolume with ADJUST_MUTE for newer APIs
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_MUTE,
                    0
                )
            } else {
                audioManager.setStreamMute(AudioManager.STREAM_MUSIC, true)
            }
            didMuteAudio = true
            Log.d(TAG, "Muted beep sound")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mute beep", e)
        }
    }

    /**
     * Unmute the audio after speech recognition has started.
     */
    @Suppress("DEPRECATION")
    private fun unmuteBeep() {
        if (!didMuteAudio) return
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_UNMUTE,
                    0
                )
            } else {
                audioManager.setStreamMute(AudioManager.STREAM_MUSIC, false)
            }
            didMuteAudio = false
            Log.d(TAG, "Unmuted beep sound")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unmute beep", e)
        }
    }
}
