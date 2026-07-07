package com.lorem.strawberry.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.lorem.strawberry.core.AppLogger
import com.lorem.strawberry.core.TtsEngine
import com.lorem.strawberry.core.TtsState
import com.lorem.strawberry.settings.TtsEngineId
import com.lorem.strawberry.telemetry.UsageLogger

private val json = Json { ignoreUnknownKeys = true }

@Serializable
data class TtsRequest(
    val input: TtsInput,
    val voice: TtsVoice,
    val audioConfig: TtsAudioConfig
)

@Serializable
data class TtsInput(
    val text: String
)

@Serializable
data class TtsVoice(
    val languageCode: String,
    val name: String
)

@Serializable
data class TtsAudioConfig(
    val audioEncoding: String,
    val speakingRate: Double = 1.0,
    val pitch: Double = 0.0,
    val sampleRateHertz: Int? = null
)

@Serializable
data class TtsResponse(
    val audioContent: String
)

class GoogleCloudTTS(
    private val apiKey: String,
    private val logger: AppLogger
) : TtsEngine {

    private val client = HttpClient(Android) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    private var audioTrack: AudioTrack? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<TtsState>(TtsState.Idle)
    override val state: StateFlow<TtsState> = _state.asStateFlow()

    override val id: String = TtsEngineId.CHIRP
    override var voice: String? = "Kore"
    override var useVoiceCommunication: Boolean = false

    companion object {
        private const val TAG = "GoogleCloudTTS"
        private const val SAMPLE_RATE = 24000 // Chirp 3 HD supports 24kHz
        private const val LANGUAGE_CODE = "en-US"
    }

    override fun speak(text: String) {
        scope.launch {
            try {
                _state.value = TtsState.Speaking()

                val voiceFullName = "$LANGUAGE_CODE-Chirp3-HD-${voice ?: "Kore"}"
                logger.d(TAG, "Using voice: $voiceFullName")

                val startTime = System.currentTimeMillis()
                synthesizeAndPlay(text, voiceFullName, LANGUAGE_CODE)

                UsageLogger.logTtsUsage(
                    engine = "chirp",
                    durationMs = System.currentTimeMillis() - startTime,
                    textLength = text.length
                )
            } catch (e: Exception) {
                logger.e(TAG, "TTS Error", e)
                _state.value = TtsState.Error("TTS Exception: ${e.message}")
            }
        }
    }

    private suspend fun synthesizeAndPlay(text: String, voiceName: String, languageCode: String) {
        val httpResponse = client.post("https://texttospeech.googleapis.com/v1beta1/text:synthesize") {
            contentType(ContentType.Application.Json)
            // API key in header, not query param, so it never lands in logs
            header("X-Goog-Api-Key", apiKey)
            setBody(
                TtsRequest(
                    input = TtsInput(text = text),
                    voice = TtsVoice(
                        languageCode = languageCode,
                        name = voiceName
                    ),
                    audioConfig = TtsAudioConfig(
                        audioEncoding = "LINEAR16",
                        speakingRate = 1.0,
                        sampleRateHertz = SAMPLE_RATE
                    )
                )
            )
        }

        val responseText = httpResponse.body<String>()
        logger.d(TAG, "Response status: ${httpResponse.status}")

        if (httpResponse.status.value != 200) {
            logger.e(TAG, "API Error: $responseText")
            _state.value = TtsState.Error("TTS Error: ${httpResponse.status}")
            return
        }

        val response = json.decodeFromString<TtsResponse>(responseText)
        val audioBytes = Base64.decode(response.audioContent, Base64.DEFAULT)
        logger.d(TAG, "Audio bytes received: ${audioBytes.size}")

        playPcmAudio(audioBytes)
    }

    private fun playPcmAudio(audioBytes: ByteArray) {
        try {
            // LINEAR16 includes a 44-byte WAV header - skip it for raw PCM playback
            val wavHeaderSize = 44
            val pcmData = if (audioBytes.size > wavHeaderSize) {
                audioBytes.copyOfRange(wavHeaderSize, audioBytes.size)
            } else {
                audioBytes
            }

            logger.d(TAG, "PCM data size after header strip: ${pcmData.size}")

            val bufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            // Use VOICE_COMMUNICATION for Bluetooth SCO routing in car mode
            val audioUsage = if (useVoiceCommunication) {
                AudioAttributes.USAGE_VOICE_COMMUNICATION
            } else {
                AudioAttributes.USAGE_MEDIA
            }

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(audioUsage)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(bufferSize, pcmData.size))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            audioTrack = track

            try {
                track.write(pcmData, 0, pcmData.size)
                val sampleCount = pcmData.size / 2  // 16-bit = 2 bytes per sample
                track.setNotificationMarkerPosition(sampleCount)
                track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(t: AudioTrack?) {
                        _state.value = TtsState.Idle
                        t?.release()
                        audioTrack = null
                    }

                    override fun onPeriodicNotification(t: AudioTrack?) {}
                })
                track.play()
            } catch (e: Exception) {
                track.release()
                audioTrack = null
                throw e
            }
        } catch (e: Exception) {
            logger.e(TAG, "Playback error", e)
            _state.value = TtsState.Error("Playback error: ${e.message}")
        }
    }

    override fun stop() {
        try {
            audioTrack?.stop()
        } catch (_: Exception) {
        } finally {
            audioTrack?.release()
            audioTrack = null
        }
        _state.value = TtsState.Idle
    }

    override fun destroy() {
        stop()
        scope.cancel()
        client.close()
    }
}
