package com.lorem.strawberry.speech

import android.content.Context
import android.content.Intent
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

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "onReadyForSpeech")
            retryCount = 0 // Reset retry count on success
            _state.value = SpeechState.Listening
            _partialResults.value = ""
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
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition not available")
            _state.value = SpeechState.Error("Speech recognition not available")
            return
        }
        startListeningInternal()
    }

    private fun startListeningInternal() {
        Log.d(TAG, "startListeningInternal() called, retryCount: $retryCount")
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
        speechRecognizer?.stopListening()
    }

    fun resetState() {
        _state.value = SpeechState.Idle
        _partialResults.value = ""
    }

    fun destroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
    }
}
