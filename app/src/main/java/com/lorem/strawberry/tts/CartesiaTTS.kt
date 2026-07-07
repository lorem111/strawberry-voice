package com.lorem.strawberry.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.nio.ByteBuffer
import java.nio.ByteOrder
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
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

@Serializable
data class CartesiaRequest(
    val model_id: String,
    val transcript: String,
    val voice: CartesiaVoice,
    val output_format: CartesiaOutputFormat,
    val language: String = "en"
)

@Serializable
data class CartesiaVoice(
    val mode: String = "id",
    val id: String
)

@Serializable
data class CartesiaOutputFormat(
    val container: String,
    val encoding: String,
    val sample_rate: Int
)

class CartesiaTTS(
    private val tokenProvider: suspend (invalidToken: String?) -> String?,
    private val logger: AppLogger
) : TtsEngine {

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    private var audioTrack: AudioTrack? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<TtsState>(TtsState.Idle)
    override val state: StateFlow<TtsState> = _state.asStateFlow()

    override val id: String = TtsEngineId.CARTESIA
    override var voice: String? = CartesiaVoices.DEFAULT
    override var useVoiceCommunication: Boolean = false

    companion object {
        private const val TAG = "CartesiaTTS"
        private const val SAMPLE_RATE = 24000
        private const val API_URL = "https://api.cartesia.ai/tts/bytes"
        private const val API_VERSION = "2026-03-01"
        private const val MODEL_ID = "sonic-3"  // Latest model
    }

    private enum class Attempt { STREAMED, UNAUTHORIZED, FAILED }

    override fun speak(text: String) {
        val voiceId = voice ?: CartesiaVoices.DEFAULT
        scope.launch {
            try {
                _state.value = TtsState.Speaking()

                logger.d(TAG, "Starting Cartesia streaming TTS...")
                val startTime = System.currentTimeMillis()

                streamAudio(text, voiceId, startTime)

                // Log TTS usage
                val durationMs = System.currentTimeMillis() - startTime
                UsageLogger.logTtsUsage(
                    engine = "cartesia",
                    durationMs = durationMs,
                    textLength = text.length
                )

            } catch (e: Exception) {
                logger.e(TAG, "Cartesia TTS error", e)
                _state.value = TtsState.Error("TTS Error: ${e.message}")
            }
        }
    }

    private suspend fun streamAudio(text: String, voiceId: String, startTime: Long) {
        // Initialize AudioTrack for streaming playback
        initAudioTrack()
        audioTrack?.play()

        var token = tokenProvider(null)
        if (token == null) {
            logger.e(TAG, "No Cartesia token available (not signed in or over quota)")
            releaseTrack()
            _state.value = TtsState.Error("TTS unavailable — check your usage limit")
            return
        }

        // Attempt once; on a 401 the token is stale/expired, so refresh (to a
        // different token) and retry exactly once. A 401 arrives before any audio
        // bytes, so no partial playback happens on the discarded attempt.
        var outcome = streamOnce(text, voiceId, startTime, token)
        if (outcome == Attempt.UNAUTHORIZED) {
            logger.d(TAG, "Cartesia 401 — refreshing token and retrying once")
            token = tokenProvider(token)
            outcome = if (token == null) Attempt.FAILED
                      else streamOnce(text, voiceId, startTime, token)
        }

        if (outcome != Attempt.STREAMED) {
            // Ensure teardown for every non-success path (incl. a second 401, which
            // streamOnce intentionally leaves un-torn-down for the retry). releaseTrack
            // is idempotent; only set an error if one wasn't set already.
            releaseTrack()
            if (_state.value !is TtsState.Error) {
                _state.value = TtsState.Error("TTS unavailable — check your usage limit")
            }
            return
        }

        // Wait for AudioTrack buffer to drain (audio plays while streaming, so just wait for buffer)
        // Buffer is typically ~100-200ms, add small margin
        val bufferDrainMs = 500L
        kotlinx.coroutines.delay(bufferDrainMs)

        cleanup()
    }

    /** One TTS request+stream attempt. Returns whether it streamed, hit a 401, or otherwise failed. */
    private suspend fun streamOnce(text: String, voiceId: String, startTime: Long, token: String): Attempt {
        var firstChunkReceived = false
        var totalBytesWritten = 0
        var result = Attempt.STREAMED

        // Use preparePost for true streaming - doesn't wait for full response
        client.preparePost(API_URL) {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $token")
            header("Cartesia-Version", API_VERSION)
            setBody(
                CartesiaRequest(
                    model_id = MODEL_ID,
                    transcript = text,
                    voice = CartesiaVoice(mode = "id", id = voiceId),
                    output_format = CartesiaOutputFormat(
                        container = "raw",
                        encoding = "pcm_f32le",
                        sample_rate = SAMPLE_RATE
                    )
                )
            )
        }.execute { response ->
            if (response.status.value != 200) {
                val errorBody = response.bodyAsText()
                logger.e(TAG, "Cartesia API error: ${response.status} - $errorBody")
                if (response.status.value == 401) {
                    // Let the caller refresh + retry; don't tear down yet.
                    result = Attempt.UNAUTHORIZED
                } else {
                    releaseTrack()
                    _state.value = TtsState.Error("Cartesia error: ${response.status}")
                    result = Attempt.FAILED
                }
                return@execute
            }

            // Stream the audio bytes as they arrive
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(8192)
            var carryOver = ByteArray(0) // Hold incomplete float bytes

            try {
                while (!channel.isClosedForRead) {
                    val bytesRead = channel.readAvailable(buffer)
                    if (bytesRead > 0) {
                        if (!firstChunkReceived) {
                            firstChunkReceived = true
                            val latency = System.currentTimeMillis() - startTime
                            _state.value = TtsState.Speaking(latencyMs = latency)
                            logger.d(TAG, "First audio chunk received in ${latency}ms")
                        }

                        // Combine carryover bytes with new data
                        val combined = if (carryOver.isNotEmpty()) {
                            carryOver + buffer.copyOf(bytesRead)
                        } else {
                            buffer.copyOf(bytesRead)
                        }

                        // Only process complete 4-byte floats
                        val usableBytes = (combined.size / 4) * 4
                        val remainder = combined.size % 4

                        if (usableBytes > 0) {
                            val floatCount = usableBytes / 4
                            val floatBuffer = FloatArray(floatCount)
                            ByteBuffer.wrap(combined, 0, usableBytes)
                                .order(ByteOrder.LITTLE_ENDIAN)
                                .asFloatBuffer()
                                .get(floatBuffer)

                            audioTrack?.write(floatBuffer, 0, floatCount, AudioTrack.WRITE_BLOCKING)
                            totalBytesWritten += usableBytes
                        }

                        // Save remainder for next iteration
                        carryOver = if (remainder > 0) {
                            combined.copyOfRange(usableBytes, combined.size)
                        } else {
                            ByteArray(0)
                        }
                    }
                }
            } catch (e: Exception) {
                logger.d(TAG, "Stream ended: ${e.message}")
            }

            logger.d(TAG, "Streaming complete. Total bytes: $totalBytesWritten")
        }

        return result
    }

    private fun initAudioTrack() {
        val bufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )

        // Use VOICE_COMMUNICATION for Bluetooth SCO routing in car mode
        val audioUsage = if (useVoiceCommunication) {
            AudioAttributes.USAGE_VOICE_COMMUNICATION
        } else {
            AudioAttributes.USAGE_MEDIA
        }

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(audioUsage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun releaseTrack() {
        try {
            audioTrack?.stop()
        } catch (_: Exception) {
        } finally {
            audioTrack?.release()
            audioTrack = null
        }
    }

    private fun cleanup() {
        releaseTrack()
        _state.value = TtsState.Idle
    }

    override fun stop() {
        cleanup()
    }

    override fun destroy() {
        stop()
        scope.cancel()
        client.close()
    }
}
