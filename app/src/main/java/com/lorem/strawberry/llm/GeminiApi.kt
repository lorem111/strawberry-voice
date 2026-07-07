package com.lorem.strawberry.llm

import com.lorem.strawberry.BuildConfig
import com.lorem.strawberry.core.AppLogger
import com.lorem.strawberry.core.ChatTurn
import com.lorem.strawberry.core.ImageEncoder
import com.lorem.strawberry.core.LlmClient
import com.lorem.strawberry.core.LlmReply
import com.lorem.strawberry.core.LlmSource
import com.lorem.strawberry.telemetry.UsageLogger
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// ============================================================================
// DO NOT MODIFY THE MODEL ID - Must use gemini-3-flash-preview for grounding
// ============================================================================
private const val GEMINI_MODEL = "gemini-3-flash-preview"
private const val GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent"
private const val TAG = "GeminiApi"

// Request models
@Serializable
data class GeminiRequest(
    val contents: List<GeminiContent>,
    val tools: List<GeminiTool>? = null
)

@Serializable
data class GeminiContent(
    val role: String,
    val parts: List<GeminiPart>
)

@Serializable
data class GeminiPart(
    val text: String? = null,
    val inlineData: GeminiInlineData? = null
)

@Serializable
data class GeminiInlineData(
    val mimeType: String,
    val data: String
)

@Serializable
data class GeminiTool(
    val googleSearch: GeminiGoogleSearch? = null
)

@Serializable
class GeminiGoogleSearch  // Empty object for {"googleSearch": {}}

// Response models
@Serializable
data class GeminiResponse(
    val candidates: List<GeminiCandidate>? = null,
    val usageMetadata: GeminiUsageMetadata? = null,
    val error: GeminiError? = null
)

@Serializable
data class GeminiCandidate(
    val content: GeminiContent? = null,
    val finishReason: String? = null,
    val groundingMetadata: GeminiGroundingMetadata? = null
)

@Serializable
data class GeminiGroundingMetadata(
    val webSearchQueries: List<String>? = null,
    val groundingChunks: List<GeminiGroundingChunk>? = null,
    val groundingSupports: List<GeminiGroundingSupport>? = null
)

@Serializable
data class GeminiGroundingChunk(
    val web: GeminiWebSource? = null
)

@Serializable
data class GeminiWebSource(
    val uri: String? = null,
    val title: String? = null
)

@Serializable
data class GeminiGroundingSupport(
    val segment: GeminiSegment? = null,
    val groundingChunkIndices: List<Int>? = null
)

@Serializable
data class GeminiSegment(
    val startIndex: Int? = null,
    val endIndex: Int? = null,
    val text: String? = null
)

@Serializable
data class GeminiUsageMetadata(
    val promptTokenCount: Int? = null,
    val candidatesTokenCount: Int? = null,
    val totalTokenCount: Int? = null,
    val thoughtsTokenCount: Int? = null
)

@Serializable
data class GeminiError(
    val code: Int? = null,
    val message: String? = null,
    val status: String? = null
)

/**
 * Gemini API client with Google Search grounding support.
 * Uses gemini-3-flash-preview model for real-time web search capabilities.
 */
class GeminiApi(
    private val apiKey: String,
    private val logger: AppLogger,
    private val imageEncoder: ImageEncoder,
    private val enableSearch: Boolean = true
) : LlmClient {

    private val client = HttpClient(Android) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
        install(Logging) {
            // BODY would leak request details to Logcat in release builds
            level = if (BuildConfig.DEBUG) LogLevel.INFO else LogLevel.NONE
        }
    }

    // onDelta unused: grounded generateContent doesn't stream
    override suspend fun chat(
        history: List<ChatTurn>,
        systemPrompt: String?,
        onDelta: ((String) -> Unit)?
    ): Result<LlmReply> {
        val startTime = System.currentTimeMillis()

        return try {
            // Convert messages to Gemini format
            val geminiContents = mutableListOf<GeminiContent>()

            // Add system prompt as first user message if provided
            if (!systemPrompt.isNullOrBlank()) {
                geminiContents.add(
                    GeminiContent(
                        role = "user",
                        parts = listOf(GeminiPart("System instruction: $systemPrompt"))
                    )
                )
                geminiContents.add(
                    GeminiContent(
                        role = "model",
                        parts = listOf(GeminiPart("Understood. I'll follow these instructions."))
                    )
                )
            }

            // Convert chat turns (OpenAI-style roles -> Gemini format)
            for (msg in history) {
                val role = when (msg.role) {
                    "user" -> "user"
                    "assistant" -> "model"
                    "system" -> continue // Already handled above
                    else -> "user"
                }
                val parts = buildList {
                    add(GeminiPart(text = msg.text))
                    msg.imagePath?.let { path ->
                        imageEncoder.encode(path)?.let { encoded ->
                            add(GeminiPart(inlineData = GeminiInlineData(encoded.mimeType, encoded.base64)))
                        }
                    }
                }
                geminiContents.add(GeminiContent(role = role, parts = parts))
            }

            val request = GeminiRequest(
                contents = geminiContents,
                tools = if (enableSearch) listOf(GeminiTool(googleSearch = GeminiGoogleSearch())) else null
            )

            val response: GeminiResponse = client.post(GEMINI_API_URL) {
                contentType(ContentType.Application.Json)
                // API key in header, not query param, so it never lands in logs
                header("X-Goog-Api-Key", apiKey)
                setBody(request)
            }.body()

            val durationMs = System.currentTimeMillis() - startTime

            // Check for API error
            if (response.error != null) {
                logger.e(TAG, "Gemini API error: ${response.error.message}")
                return Result.failure(Exception(response.error.message ?: "Unknown Gemini error"))
            }

            // Extract response text
            val candidate = response.candidates?.firstOrNull()
            val responseText = candidate?.content?.parts?.firstNotNullOfOrNull { it.text }
                ?: return Result.failure(Exception("No response from Gemini"))

            // Extract grounding sources if available
            val sources = candidate.groundingMetadata?.groundingChunks?.mapNotNull { chunk ->
                chunk.web?.let { web ->
                    LlmSource(
                        title = web.title ?: "Unknown",
                        uri = web.uri ?: ""
                    )
                }
            } ?: emptyList()

            // Log usage
            response.usageMetadata?.let { usage ->
                logger.d(TAG, "Gemini usage: ${usage.promptTokenCount} prompt, ${usage.candidatesTokenCount} response, ${usage.thoughtsTokenCount} thinking")
                UsageLogger.logLlmUsage(
                    model = "gemini-3-flash-preview-search",
                    inputTokens = usage.promptTokenCount ?: 0,
                    outputTokens = (usage.candidatesTokenCount ?: 0) + (usage.thoughtsTokenCount ?: 0),
                    durationMs = durationMs
                )
            }

            Result.success(LlmReply(text = responseText, sources = sources))
        } catch (e: Exception) {
            logger.e(TAG, "Gemini API request failed", e)
            Result.failure(e)
        }
    }

    override fun close() {
        client.close()
    }
}
