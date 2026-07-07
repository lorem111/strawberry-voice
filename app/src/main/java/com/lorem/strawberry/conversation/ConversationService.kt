package com.lorem.strawberry.conversation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.lorem.strawberry.MainActivity
import com.lorem.strawberry.R
import com.lorem.strawberry.core.AppLogger
import com.lorem.strawberry.core.ForegroundController
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Foreground service that anchors the audio pipeline while a conversation is active,
 * so listening/speaking survives the activity being backgrounded (car mode, long sessions).
 * The conversation logic itself lives in the ConversationOrchestrator singleton; this
 * service only keeps the process foregrounded.
 */
class ConversationService : Service() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Active conversation",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while Strawberry is listening or speaking"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Strawberry")
            .setContentText("Conversation active")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "conversation"
        private const val NOTIFICATION_ID = 1
    }
}

@Singleton
class ConversationServiceController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: AppLogger
) : ForegroundController {

    private var active = false

    override fun setActive(active: Boolean) {
        if (this.active == active) return
        this.active = active

        val intent = Intent(context, ConversationService::class.java)
        try {
            if (active) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.stopService(intent)
            }
        } catch (e: Exception) {
            // E.g. FGS start restrictions when backgrounded — log, don't crash the loop
            logger.w(TAG, "Foreground service toggle failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "ConversationService"
    }
}
