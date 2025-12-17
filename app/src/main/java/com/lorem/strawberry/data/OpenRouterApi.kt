package com.lorem.strawberry.data

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ChatMessage(
    val role: String,
    val content: String
)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>
)

@Serializable
data class ChatChoice(
    val message: ChatMessage,
    @SerialName("finish_reason")
    val finishReason: String? = null
)

@Serializable
data class UsageInfo(
    @SerialName("prompt_tokens")
    val promptTokens: Int = 0,
    @SerialName("completion_tokens")
    val completionTokens: Int = 0,
    @SerialName("total_tokens")
    val totalTokens: Int = 0
)

@Serializable
data class ChatResponse(
    val id: String? = null,
    val choices: List<ChatChoice>,
    val usage: UsageInfo? = null,
    val model: String? = null
)

class OpenRouterApi(private val apiKey: String) {

    private val client = HttpClient(Android) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
        install(Logging) {
            level = LogLevel.BODY
        }
    }

    suspend fun chat(
        messages: List<ChatMessage>,
        model: String = "google/gemini-3-flash-preview",
        systemPrompt: String? = null
    ): Result<String> {
        val startTime = System.currentTimeMillis()

        return try {
            val allMessages = if (systemPrompt != null) {
                listOf(ChatMessage(role = "system", content = systemPrompt)) + messages
            } else {
                messages
            }

            val response: ChatResponse = client.post("https://openrouter.ai/api/v1/chat/completions") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $apiKey")
                header("HTTP-Referer", "https://github.com/lorem111/strawberry-voice")
                header("X-Title", "Strawberry Voice Assistant")
                setBody(ChatRequest(model = model, messages = allMessages))
            }.body()

            val durationMs = System.currentTimeMillis() - startTime

            // Log usage
            response.usage?.let { usage ->
                UsageLogger.logLlmUsage(
                    model = response.model ?: model,
                    inputTokens = usage.promptTokens,
                    outputTokens = usage.completionTokens,
                    durationMs = durationMs
                )
            }

            val reply = response.choices.firstOrNull()?.message?.content
                ?: "No response received"
            Result.success(reply)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun close() {
        client.close()
    }
}
