package com.lorem.strawberry.assistant

import android.service.voice.VoiceInteractionService
import android.util.Log

class AssistantService : VoiceInteractionService() {

    companion object {
        private const val TAG = "AssistantService"
    }

    override fun onReady() {
        super.onReady()
        Log.d(TAG, "Voice assistant service is ready")
    }

    override fun onShutdown() {
        super.onShutdown()
        Log.d(TAG, "Voice assistant service shutting down")
    }
}
