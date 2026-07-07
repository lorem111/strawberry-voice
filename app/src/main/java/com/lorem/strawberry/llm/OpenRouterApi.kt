package com.lorem.strawberry.llm

import com.lorem.strawberry.BuildConfig
import com.lorem.strawberry.core.AppLogger
import com.lorem.strawberry.core.ChatTurn
import com.lorem.strawberry.core.ImageEncoder
import com.lorem.strawberry.core.LlmClient
import com.lorem.strawberry.core.LlmReply
import com.lorem.strawberry.telemetry.UsageLogger
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

// content is either a plain string or an array of typed parts (text / image_url)
@Serializable
data class OrRequestMessage(
    val role: String,
    val content: JsonElement
)

@Serializable
data class StreamOptions(
    val include: Boolean = true
)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<OrRequestMessage>,
    val stream: Boolean = false,
    // Asks OpenRouter to attach token usage to the final stream chunk
    val usage: StreamOptions? = null
)

// Streaming (SSE) chunk models
@Serializable
data class StreamDelta(
    val content: String? = null
)

@Serializable
data class StreamChoice(
    val delta: StreamDelta? = null,
    @SerialName("finish_reason")
    val finishReason: String? = null
)

@Serializable
data class StreamChunk(
    val choices: List<StreamChoice> = emptyList(),
    val usage: UsageInfo? = null,
    val model: String? = null
)

@Serializable
data class OrResponseMessage(
    val role: String,
    val content: String
)

@Serializable
data class ChatChoice(
    val message: OrResponseMessage,
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

class OpenRouterApi(
    private val apiKey: String,
    private val logger: AppLogger,
    private val imageEncoder: ImageEncoder,
    var model: String = "google/gemini-3-flash-preview"
) : LlmClient {

    private val client = HttpClient(Android) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
        install(Logging) {
            // BODY would leak the Authorization header to Logcat in release builds
            level = if (BuildConfig.DEBUG) LogLevel.INFO else LogLevel.NONE
        }
    }

    companion object {
        private const val TAG = "OpenRouterApi"
        private const val API_URL = "https://openrouter.ai/api/v1/chat/completions"
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun chat(
        history: List<ChatTurn>,
        systemPrompt: String?,
        onDelta: ((String) -> Unit)?
    ): Result<LlmReply> {
        val messages = buildList {
            if (systemPrompt != null) {
                add(OrRequestMessage(role = "system", content = JsonPrimitive(systemPrompt)))
            }
            history.forEach { add(it.toRequestMessage()) }
        }
        return if (onDelta != null) {
            chatStreaming(messages, onDelta)
        } else {
            chatBlocking(messages)
        }
    }

    private suspend fun chatBlocking(messages: List<OrRequestMessage>): Result<LlmReply> {
        val startTime = System.currentTimeMillis()

        return try {
            val response: ChatResponse = client.post(API_URL) {
                applyHeaders()
                setBody(ChatRequest(model = model, messages = messages))
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
            if (reply == null) {
                logger.e(TAG, "OpenRouter returned no choices (id=${response.id}, model=${response.model})")
                Result.failure(Exception("No response received"))
            } else {
                Result.success(LlmReply(text = reply))
            }
        } catch (e: Exception) {
            logger.e(TAG, "OpenRouter request failed", e)
            Result.failure(e)
        }
    }

    private suspend fun chatStreaming(
        messages: List<OrRequestMessage>,
        onDelta: (String) -> Unit
    ): Result<LlmReply> {
        val startTime = System.currentTimeMillis()
        val fullText = StringBuilder()
        var usage: UsageInfo? = null
        var usedModel: String? = null

        return try {
            client.preparePost(API_URL) {
                applyHeaders()
                setBody(ChatRequest(model = model, messages = messages, stream = true, usage = StreamOptions()))
            }.execute { response ->
                if (response.status.value != 200) {
                    val errorBody = response.bodyAsText()
                    logger.e(TAG, "OpenRouter stream error: ${response.status} - ${errorBody.take(500)}")
                    throw Exception("OpenRouter error: ${response.status}")
                }

                val channel = response.bodyAsChannel()
                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: break
                    if (!line.startsWith("data: ")) continue
                    val payload = line.removePrefix("data: ").trim()
                    if (payload == "[DONE]") break

                    val chunk = try {
                        json.decodeFromString<StreamChunk>(payload)
                    } catch (e: Exception) {
                        logger.w(TAG, "Unparseable stream chunk: ${payload.take(200)}")
                        continue
                    }
                    chunk.usage?.let { usage = it }
                    chunk.model?.let { usedModel = it }
                    chunk.choices.firstOrNull()?.delta?.content?.let { delta ->
                        if (delta.isNotEmpty()) {
                            fullText.append(delta)
                            onDelta(delta)
                        }
                    }
                }
            }

            usage?.let {
                UsageLogger.logLlmUsage(
                    model = usedModel ?: model,
                    inputTokens = it.promptTokens,
                    outputTokens = it.completionTokens,
                    durationMs = System.currentTimeMillis() - startTime
                )
            }

            if (fullText.isEmpty()) {
                logger.e(TAG, "OpenRouter stream produced no content")
                Result.failure(Exception("No response received"))
            } else {
                Result.success(LlmReply(text = fullText.toString()))
            }
        } catch (e: Exception) {
            logger.e(TAG, "OpenRouter streaming request failed", e)
            // Surface partial text if we got any before the failure
            if (fullText.isNotEmpty()) {
                Result.success(LlmReply(text = fullText.toString()))
            } else {
                Result.failure(e)
            }
        }
    }

    private fun HttpRequestBuilder.applyHeaders() {
        contentType(ContentType.Application.Json)
        header("Authorization", "Bearer $apiKey")
        header("HTTP-Referer", "https://github.com/lorem111/strawberry-voice")
        header("X-Title", "Strawberry Voice Assistant")
    }

    private fun ChatTurn.toRequestMessage(): OrRequestMessage {
        val encoded = imagePath?.let { imageEncoder.encode(it) }
        val content: JsonElement = if (encoded == null) {
            JsonPrimitive(text)
        } else {
            buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", text)
                })
                add(buildJsonObject {
                    put("type", "image_url")
                    putJsonObject("image_url") {
                        put("url", "data:${encoded.mimeType};base64,${encoded.base64}")
                    }
                })
            }
        }
        return OrRequestMessage(role = role, content = content)
    }

    override fun close() {
        client.close()
    }
}
