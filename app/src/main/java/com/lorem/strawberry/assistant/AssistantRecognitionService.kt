package com.lorem.strawberry.assistant

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Stub RecognitionService required for VoiceInteractionService registration.
 * We use Android's built-in speech recognition, but Samsung requires this to be declared.
 */
class AssistantRecognitionService : RecognitionService() {

    companion object {
        private const val TAG = "AssistantRecognition"
    }

    override fun onStartListening(intent: Intent?, callback: Callback?) {
        Log.d(TAG, "onStartListening - delegating to system")
        // We don't actually use this - we use Android's SpeechRecognizer directly
        callback?.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onStopListening(callback: Callback?) {
        Log.d(TAG, "onStopListening")
    }

    override fun onCancel(callback: Callback?) {
        Log.d(TAG, "onCancel")
    }
}
