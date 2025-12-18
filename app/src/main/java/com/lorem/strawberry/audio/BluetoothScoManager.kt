package com.lorem.strawberry.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages Bluetooth SCO (Synchronous Connection-Oriented) audio connection.
 * When enabled, routes audio through the Bluetooth hands-free profile (HFP),
 * making the app appear as a phone call to car Bluetooth systems.
 */
class BluetoothScoManager(private val context: Context) {

    companion object {
        private const val TAG = "BluetoothScoManager"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val _scoState = MutableStateFlow(ScoState.DISCONNECTED)
    val scoState: StateFlow<ScoState> = _scoState.asStateFlow()

    private var scoReceiver: BroadcastReceiver? = null

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
    fun start() {
        if (_isActive.value) {
            Log.d(TAG, "SCO already active")
            return
        }

        Log.i(TAG, "=== Starting Bluetooth SCO (Car Mode) ===")
        Log.i(TAG, "SCO available off call: ${audioManager.isBluetoothScoAvailableOffCall}")
        Log.i(TAG, "Bluetooth SCO currently on: ${audioManager.isBluetoothScoOn}")
        Log.i(TAG, "Current audio mode: ${audioManager.mode}")

        // Register receiver to monitor SCO state
        registerScoReceiver()

        try {
            // Set audio mode to communication (voice call mode)
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            Log.i(TAG, "Set audio mode to MODE_IN_COMMUNICATION")

            // Start Bluetooth SCO
            @Suppress("DEPRECATION")
            audioManager.startBluetoothSco()
            Log.i(TAG, "Called startBluetoothSco()")

            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = true
            Log.i(TAG, "Set isBluetoothScoOn = true")

            _scoState.value = ScoState.CONNECTING
            _isActive.value = true

            Log.i(TAG, "Bluetooth SCO start requested, waiting for connection...")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Bluetooth SCO", e)
            _scoState.value = ScoState.ERROR
            cleanup()
        }
    }

    /**
     * Stop Bluetooth SCO connection and return to normal audio mode.
     */
    fun stop() {
        if (!_isActive.value) {
            Log.d(TAG, "SCO not active, nothing to stop")
            return
        }

        Log.d(TAG, "Stopping Bluetooth SCO...")
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

            Log.d(TAG, "Bluetooth SCO stopped and cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error during SCO cleanup", e)
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

                Log.i(TAG, "=== SCO state broadcast received: $state ===")

                when (state) {
                    AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                        Log.d(TAG, "SCO connected - car mode active")
                        _scoState.value = ScoState.CONNECTED
                    }
                    AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                        Log.d(TAG, "SCO disconnected")
                        if (_isActive.value) {
                            _scoState.value = ScoState.DISCONNECTED
                        }
                    }
                    AudioManager.SCO_AUDIO_STATE_CONNECTING -> {
                        Log.d(TAG, "SCO connecting...")
                        _scoState.value = ScoState.CONNECTING
                    }
                    AudioManager.SCO_AUDIO_STATE_ERROR -> {
                        Log.e(TAG, "SCO error")
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
                Log.w(TAG, "Error unregistering SCO receiver", e)
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

    fun destroy() {
        stop()
    }
}
