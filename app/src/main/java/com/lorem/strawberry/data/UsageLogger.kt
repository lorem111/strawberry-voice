package com.lorem.strawberry.data

import android.util.Log
import com.lorem.strawberry.BuildConfig
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val TAG = "UsageLogger"

@Serializable
data class UsageLogRequest(
    val email: String,
    val type: String,           // "llm" or "tts"
    val model: String,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val durationMs: Long = 0,
    val cost: Double = 0.0
)

object UsageLogger {
    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            })
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private var userEmail: String? = null

    fun setUserEmail(email: String?) {
        userEmail = email
    }

    fun logLlmUsage(
        model: String,
        inputTokens: Int,
        outputTokens: Int,
        durationMs: Long,
        cost: Double = 0.0
    ) {
        val email = userEmail ?: return

        scope.launch {
            try {
                val request = UsageLogRequest(
                    email = email,
                    type = "llm",
                    model = model,
                    inputTokens = inputTokens,
                    outputTokens = outputTokens,
                    durationMs = durationMs,
                    cost = cost
                )

                client.post("${BuildConfig.AUTH_SERVER_URL}/api/log-usage") {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }

                Log.d(TAG, "Logged LLM usage: $model, ${inputTokens}in/${outputTokens}out tokens")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to log LLM usage: ${e.message}")
            }
        }
    }

    fun logTtsUsage(
        engine: String,
        durationMs: Long,
        textLength: Int
    ) {
        val email = userEmail ?: return

        scope.launch {
            try {
                val request = UsageLogRequest(
                    email = email,
                    type = "tts",
                    model = engine,
                    inputTokens = textLength,
                    durationMs = durationMs
                )

                client.post("${BuildConfig.AUTH_SERVER_URL}/api/log-usage") {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }

                Log.d(TAG, "Logged TTS usage: $engine, ${textLength} chars")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to log TTS usage: ${e.message}")
            }
        }
    }
}
