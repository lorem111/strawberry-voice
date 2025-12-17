package com.lorem.strawberry.assistant

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log
import com.lorem.strawberry.MainActivity

class AssistantSessionService : VoiceInteractionSessionService() {

    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return AssistantSession(this)
    }
}

class AssistantSession(context: AssistantSessionService) : VoiceInteractionSession(context) {

    companion object {
        private const val TAG = "AssistantSession"
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.d(TAG, "Assistant session shown")

        // Launch the main assistant activity
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("FROM_ASSISTANT", true)
            putExtra("AUTO_START_LISTENING", true)
        }
        context.startActivity(intent)

        // Hide the voice interaction UI since we're using our own activity
        hide()
    }

    override fun onHide() {
        super.onHide()
        Log.d(TAG, "Assistant session hidden")
    }
}
