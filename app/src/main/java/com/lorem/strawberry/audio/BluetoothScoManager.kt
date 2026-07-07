package com.lorem.strawberry.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread
import com.lorem.strawberry.core.AppLogger
import com.lorem.strawberry.core.ScoController

/**
 * Manages Bluetooth SCO (Synchronous Connection-Oriented) audio connection.
 * When enabled, routes audio through the Bluetooth hands-free profile (HFP),
 * making the app appear as a phone call to car Bluetooth systems.
 */
@Singleton
class BluetoothScoManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: AppLogger
) : ScoController {

    companion object {
        private const val TAG = "BluetoothScoManager"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val _scoState = MutableStateFlow(ScoState.DISCONNECTED)
    val scoState: StateFlow<ScoState> = _scoState.asStateFlow()

    private var scoReceiver: BroadcastReceiver? = null

    // Silent audio to keep SCO alive when mic is off
    private var silentAudioThread: Thread? = null
    @Volatile private var playingSilence = false

    enum class ScoState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    /**
     * Start Bluetooth SCO connection (car mode).
     * This makes the app appear as a phone call to the car's Bluetooth system.
     */
    override fun start() {
        if (_isActive.value) {
            logger.d(TAG, "SCO already active")
            return
        }

        logger.i(TAG, "=== Starting Bluetooth SCO (Car Mode) ===")
        logger.i(TAG, "SCO available off call: ${audioManager.isBluetoothScoAvailableOffCall}")
        logger.i(TAG, "Bluetooth SCO currently on: ${audioManager.isBluetoothScoOn}")
        logger.i(TAG, "Current audio mode: ${audioManager.mode}")

        // Register receiver to monitor SCO state
        registerScoReceiver()

        try {
            // Set audio mode to communication (voice call mode)
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            logger.i(TAG, "Set audio mode to MODE_IN_COMMUNICATION")

            // Start Bluetooth SCO
            @Suppress("DEPRECATION")
            audioManager.startBluetoothSco()
            logger.i(TAG, "Called startBluetoothSco()")

            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = true
            logger.i(TAG, "Set isBluetoothScoOn = true")

            _scoState.value = ScoState.CONNECTING
            _isActive.value = true

            logger.i(TAG, "Bluetooth SCO start requested, waiting for connection...")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to start Bluetooth SCO", e)
            _scoState.value = ScoState.ERROR
            cleanup()
        }
    }

    /**
     * Stop Bluetooth SCO connection and return to normal audio mode.
     */
    override fun stop() {
        if (!_isActive.value) {
            logger.d(TAG, "SCO not active, nothing to stop")
            return
        }

        logger.d(TAG, "Stopping Bluetooth SCO...")
        cleanup()
    }

    private fun cleanup() {
        try {
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = false
            @Suppress("DEPRECATION")
            audioManager.stopBluetoothSco()
            audioManager.mode = AudioManager.MODE_NORMAL

            unregisterScoReceiver()

            _isActive.value = false
            _scoState.value = ScoState.DISCONNECTED

            logger.d(TAG, "Bluetooth SCO stopped and cleaned up")
        } catch (e: Exception) {
            logger.e(TAG, "Error during SCO cleanup", e)
        }
    }

    private fun registerScoReceiver() {
        if (scoReceiver != null) return

        scoReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val state = intent?.getIntExtra(
                    AudioManager.EXTRA_SCO_AUDIO_STATE,
                    AudioManager.SCO_AUDIO_STATE_DISCONNECTED
                )

                logger.i(TAG, "=== SCO state broadcast received: $state ===")

                when (state) {
                    AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                        logger.d(TAG, "SCO connected - car mode active")
                        _scoState.value = ScoState.CONNECTED
                    }
                    AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                        logger.d(TAG, "SCO disconnected")
                        if (_isActive.value) {
                            _scoState.value = ScoState.DISCONNECTED
                        }
                    }
                    AudioManager.SCO_AUDIO_STATE_CONNECTING -> {
                        logger.d(TAG, "SCO connecting...")
                        _scoState.value = ScoState.CONNECTING
                    }
                    AudioManager.SCO_AUDIO_STATE_ERROR -> {
                        logger.e(TAG, "SCO error")
                        _scoState.value = ScoState.ERROR
                    }
                }
            }
        }

        val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(scoReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(scoReceiver, filter)
        }
    }

    private fun unregisterScoReceiver() {
        scoReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: Exception) {
                logger.w(TAG, "Error unregistering SCO receiver: ${e.message}")
            }
            scoReceiver = null
        }
    }

    /**
     * Check if Bluetooth SCO is available on this device.
     */
    fun isBluetoothScoAvailable(): Boolean {
        return audioManager.isBluetoothScoAvailableOffCall
    }

    /**
     * Start playing silent audio to keep SCO connection alive.
     * Use this when mic is off but you want to maintain the Bluetooth connection.
     */
    override fun startKeepalive() {
        if (playingSilence || !_isActive.value) return

        logger.d(TAG, "Starting silent audio to keep SCO alive")
        playingSilence = true

        silentAudioThread = thread(name = "SilentAudioThread") {
            // Track is local to the thread so a quick stop/start can't race two
            // threads onto the same instance
            var track: AudioTrack? = null
            try {
                val sampleRate = 8000 // Low sample rate for SCO
                val bufferSize = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                track.play()

                // Buffer of silence (zeros)
                val silentBuffer = ByteArray(bufferSize)

                while (playingSilence && track.state == AudioTrack.STATE_INITIALIZED) {
                    track.write(silentBuffer, 0, silentBuffer.size)
                }

                logger.d(TAG, "Silent audio stopped")
            } catch (e: Exception) {
                logger.e(TAG, "Error playing silent audio", e)
            } finally {
                try {
                    track?.stop()
                } catch (_: Exception) {
                }
                track?.release()
            }
        }
    }

    /**
     * Stop playing silent audio.
     */
    override fun stopKeepalive() {
        if (!playingSilence) return

        logger.d(TAG, "Stopping silent audio")
        playingSilence = false
        silentAudioThread?.join(500)
        silentAudioThread = null
    }

    override fun destroy() {
        stopKeepalive()
        stop()
    }
}
