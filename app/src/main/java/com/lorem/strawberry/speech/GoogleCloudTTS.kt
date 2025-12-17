package com.lorem.strawberry.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import android.util.Log
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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

// Chirp 3 HD voices
val availableTtsVoices = listOf(
    // Female voices
    "Kore" to "Kore (Female)",
    "Aoede" to "Aoede (Female)",
    "Leda" to "Leda (Female)",
    "Zephyr" to "Zephyr (Female)",
    "Achernar" to "Achernar (Female)",
    "Autonoe" to "Autonoe (Female)",
    "Callirrhoe" to "Callirrhoe (Female)",
    "Despina" to "Despina (Female)",
    "Erinome" to "Erinome (Female)",
    "Gacrux" to "Gacrux (Female)",
    "Laomedeia" to "Laomedeia (Female)",
    "Pulcherrima" to "Pulcherrima (Female)",
    "Sulafat" to "Sulafat (Female)",
    "Vindemiatrix" to "Vindemiatrix (Female)",
    // Male voices
    "Charon" to "Charon (Male)",
    "Fenrir" to "Fenrir (Male)",
    "Puck" to "Puck (Male)",
    "Orus" to "Orus (Male)",
    "Achird" to "Achird (Male)",
    "Algenib" to "Algenib (Male)",
    "Algieba" to "Algieba (Male)",
    "Alnilam" to "Alnilam (Male)",
    "Enceladus" to "Enceladus (Male)",
    "Iapetus" to "Iapetus (Male)",
    "Rasalgethi" to "Rasalgethi (Male)",
    "Sadachbia" to "Sadachbia (Male)",
    "Sadaltager" to "Sadaltager (Male)",
    "Schedar" to "Schedar (Male)",
    "Umbriel" to "Umbriel (Male)",
    "Zubenelgenubi" to "Zubenelgenubi (Male)",
)

class GoogleCloudTTS(
    private val context: Context,
    private val apiKey: String
) {
    private val client = HttpClient(Android) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    private var audioTrack: AudioTrack? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    companion object {
        private const val SAMPLE_RATE = 24000 // Chirp 3 HD supports 24kHz
    }

    fun speak(
        text: String,
        voiceName: String = "Kore",
        languageCode: String = "en-US",
        useStreaming: Boolean = false // Streaming requires gRPC, not available via REST
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                _isSpeaking.value = true
                _lastError.value = null

                val voiceFullName = "$languageCode-Chirp3-HD-$voiceName"
                Log.d("GoogleCloudTTS", "Using voice: $voiceFullName")

                synthesizeAndPlay(text, voiceFullName, languageCode)

            } catch (e: Exception) {
                Log.e("GoogleCloudTTS", "TTS Error", e)
                _lastError.value = "TTS Exception: ${e.message}"
                e.printStackTrace()
                _isSpeaking.value = false
            }
        }
    }

    private suspend fun synthesizeAndPlay(text: String, voiceName: String, languageCode: String) {
        val httpResponse = client.post("https://texttospeech.googleapis.com/v1beta1/text:synthesize") {
            contentType(ContentType.Application.Json)
            parameter("key", apiKey)
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
        Log.d("GoogleCloudTTS", "Response status: ${httpResponse.status}")

        if (httpResponse.status.value != 200) {
            Log.e("GoogleCloudTTS", "API Error: $responseText")
            _lastError.value = "TTS Error: $responseText"
            _isSpeaking.value = false
            return
        }

        val response = json.decodeFromString<TtsResponse>(responseText)
        val audioBytes = Base64.decode(response.audioContent, Base64.DEFAULT)
        Log.d("GoogleCloudTTS", "Audio bytes received: ${audioBytes.size}")

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

            Log.d("GoogleCloudTTS", "PCM data size after header strip: ${pcmData.size}")

            val bufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
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

            audioTrack?.apply {
                write(pcmData, 0, pcmData.size)
                val sampleCount = pcmData.size / 2  // 16-bit = 2 bytes per sample
                setNotificationMarkerPosition(sampleCount)
                setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(track: AudioTrack?) {
                        _isSpeaking.value = false
                        track?.release()
                        audioTrack = null
                    }
                    override fun onPeriodicNotification(track: AudioTrack?) {}
                })
                play()
            }
        } catch (e: Exception) {
            Log.e("GoogleCloudTTS", "Playback error", e)
            _isSpeaking.value = false
        }
    }

    fun stop() {
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        _isSpeaking.value = false
    }

    fun destroy() {
        stop()
        client.close()
    }
}
