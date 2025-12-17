package com.lorem.strawberry.speech

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.lorem.strawberry.data.UsageLogger

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

// Cartesia voice options (id to display name) - Featured voices from Cartesia
val availableCartesiaVoices = listOf(
    // Featured voices
    "6f84f4b8-58a2-430c-8c79-688dad597532" to "Brooke - Big Sister",
    "f786b574-daa5-4673-aa0c-cbe3e8534c02" to "Katie - Friendly Fixer",
    "9626c31c-bec5-4cca-baa8-f8ba9e84c8bc" to "Jacqueline - Reassuring Agent",
    "f9836c6e-a0bd-460e-9d3c-f7299fa60f94" to "Caroline - Southern Guide",
    "e8e5fffb-252c-436d-b842-8879b84445b6" to "Cathy - Coworker",
    "79f8b5fb-2cc8-479a-80df-29f7a7cf1a3e" to "Theo - Modern Narrator",
    "86e30c1d-714b-4074-a1f2-1cb6b552fb49" to "Carson - Curious Conversationalist",
    "87286a8d-7ea7-4235-a41a-dd9fa6630feb" to "Henry - Plainspoken Guy",
    "1242fb95-7ddd-44ac-8a05-9e8a22a6137d" to "Cindy - Receptionist",
    "6ccbfb76-1fc6-48f7-b71d-91ac6298247b" to "Tessa - Kind Companion",
    "228fca29-3a0a-435c-8728-5cb483251068" to "Kiefer - Assured Tone",
    "5ee9feff-1265-424a-9d7f-8e4d431a12c7" to "Ronald - Thinker",
    "829ccd10-f8b3-43cd-b8a0-4aeaa81f3b30" to "Linda - Conversational Guide",
    "5cad89c9-d88a-4832-89fb-55f2f16d13d3" to "Brandon - Confident Guy",
    "ec1e269e-9ca0-402f-8a18-58e0e022355a" to "Ariana - Kind Friend",
    "66c6b81c-ddb7-4892-bdd5-19b5a7be38e7" to "Dorothy - Easy Charm",
    "a7b8d8fa-f6e5-4908-900e-0c11d1d82519" to "Joanie - Vibrant Speaker",
    "999df508-4de5-40a7-8bd3-8c12f678c284" to "Layla - Casual Friend",
    "26403c37-80c1-4a1a-8692-540551ca2ae5" to "Marian - Poised Narrator",
    "41468051-3a85-4b68-92ad-64add250d369" to "Cory - Relaxed Voice",
    "c961b81c-a935-4c17-bfb3-ba2239de8c2f" to "Kyle - Approachable Friend",
    "694f9389-aac1-45b6-b726-9d9369183238" to "Sarah - Mindful Woman",
    "248be419-c632-4f23-adf1-5324ed7dbf1d" to "Elizabeth - Manager",
    "a167e0f3-df7e-4d52-a9c3-f949145efdab" to "Blake - Helpful Agent",
    "5c5ad5e7-1020-476b-8b91-fdcbe9cc313c" to "Daniela - Relaxed Woman",
    "bf0a246a-8642-498a-9950-80c35e9276b5" to "Sophie - Teacher",
    "57dcab65-68ac-45a6-8480-6c4c52ec1cd1" to "Kira - Trusted Confidant",
    "78ab82d5-25be-4f7d-82b3-7ad64e5b85b2" to "Savannah - Magnolia Belle",
    "faf0731e-dfb9-4cfc-8119-259a79b27e12" to "Riya - College Roommate",
    "03496517-369a-4db1-8236-3d3ae459ddf7" to "Calypso - ASMR Lady",
    "b7d50908-b17c-442d-ad8d-810c63997ed9" to "Sierra - California Girl",
    "32b3f3c5-7171-46aa-abe7-b598964aa793" to "Daisy - Reading Girl",
    "00a77add-48d5-4ef6-8157-71e5437b282d" to "Callie - Encourager",
    "4af7c703-f2a9-45dd-a7fd-724cf7efc371" to "Lila - Meditation Guide",
    "156fb8d2-335b-4950-9cb3-a2d33befec77" to "Sunny - Pep Talker",
    "b9de4a89-2257-424b-94c2-db18ba68c81a" to "Viktoria - Phone Conversationalist",
    "8d8ce8c9-44a4-46c4-b10f-9a927b99a853" to "Connie - Candid Conversationalist",
    "c2ac25f9-ecc4-4f56-9095-651354df60c0" to "Renee - Commander",
    "5c42302c-194b-4d0c-ba1a-8cb485c84ab9" to "Mary - Nurse",
    "28ca2041-5dda-42df-8123-f58ea9c3da00" to "Palak - Presenter",
    "146485fd-8736-41c7-88a8-7cdd0da34d84" to "Tim - Pal",
    "3b554273-4299-48b9-9aaf-eefd438e3941" to "Simi - Support Specialist",
    "71a7ad14-091c-4e8e-a314-022ece01c121" to "Charlotte - Heiress",
    "565510e8-6b45-45de-8758-13588fbaec73" to "Ray - Conversationalist",
    "cefcb124-080b-4655-b31f-932f3ee743de" to "Elena - Narrator",
    "e3827ec5-697a-4b7c-9704-1a23041bbc51" to "Dottie - Sweet Gal",
    "98a34ef2-2140-4c28-9c71-663dc4dd7022" to "Clyde - Calm Narrator",
    "8f091740-3df1-4795-8bd9-dc62d88e5131" to "Aurora - Fairy Princess",
    "1463a4e1-56a1-4b41-b257-728d56e93605" to "Hugo - Teatime Friend",
    "ed81fd13-2016-4a49-8fe3-c0d2761695fc" to "Zack - Sportsman",
    "34575e71-908f-4ab6-ab54-b08c95d6597d" to "Joey - Neighborhood Guy",
    "00967b2f-88a6-4a31-8153-110a92134b9f" to "Asher - Podcaster",
    "5abd2130-146a-41b1-bcdb-974ea8e19f56" to "Jo - Go to Gal",
    "91b4cf29-5166-44eb-8054-30d40ecc8081" to "Tina - Customer Ally",
    "729651dc-c6c3-4ee5-97fa-350da1f88600" to "Jake - Sidekick",
    "f6ff7c0c-e396-40a9-a70b-f7607edb6937" to "Emma - Customer Care Line",
    "79a125e8-cd45-4c13-8a67-188112f4dd22" to "British Lady",
)

object CartesiaVoices {
    const val DEFAULT = "6f84f4b8-58a2-430c-8c79-688dad597532" // Brooke - Big Sister
}

class CartesiaTTS(private val apiKey: String) {

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    private var audioTrack: AudioTrack? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _latencyMs = MutableStateFlow<Long?>(null)
    val latencyMs: StateFlow<Long?> = _latencyMs.asStateFlow()

    companion object {
        private const val TAG = "CartesiaTTS"
        private const val SAMPLE_RATE = 24000
        private const val API_URL = "https://api.cartesia.ai/tts/bytes"
        private const val API_VERSION = "2024-06-10"
        private const val MODEL_ID = "sonic-3"  // Latest model
    }

    fun speak(text: String, voiceId: String = CartesiaVoices.DEFAULT) {
        scope.launch {
            try {
                _isSpeaking.value = true
                _lastError.value = null
                _latencyMs.value = null

                Log.d(TAG, "Starting Cartesia streaming TTS...")
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
                Log.e(TAG, "Cartesia TTS error", e)
                _lastError.value = "TTS Error: ${e.message}"
                _isSpeaking.value = false
            }
        }
    }

    private suspend fun streamAudio(text: String, voiceId: String, startTime: Long) {
        // Initialize AudioTrack for streaming playback
        initAudioTrack()
        audioTrack?.play()

        var firstChunkReceived = false
        var totalBytesWritten = 0

        // Use preparePost for true streaming - doesn't wait for full response
        client.preparePost(API_URL) {
            contentType(ContentType.Application.Json)
            header("X-API-Key", apiKey)
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
                Log.e(TAG, "Cartesia API error: ${response.status} - $errorBody")
                _lastError.value = "Cartesia error: ${response.status}"
                cleanup()
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
                            _latencyMs.value = latency
                            Log.d(TAG, "First audio chunk received in ${latency}ms")
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
                Log.d(TAG, "Stream ended: ${e.message}")
            }
        }

        Log.d(TAG, "Streaming complete. Total bytes: $totalBytesWritten")

        // Wait for AudioTrack buffer to drain (audio plays while streaming, so just wait for buffer)
        // Buffer is typically ~100-200ms, add small margin
        val bufferDrainMs = 500L
        kotlinx.coroutines.delay(bufferDrainMs)

        cleanup()
    }

    private fun initAudioTrack() {
        val bufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
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
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun cleanup() {
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        _isSpeaking.value = false
    }

    fun stop() {
        cleanup()
    }

    fun destroy() {
        stop()
        client.close()
    }
}
