package com.lorem.strawberry.data

import android.util.Log
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
    val text: String
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
class GeminiApi(private val apiKey: String) {

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

    /**
     * Send a chat request to Gemini with optional Google Search grounding.
     *
     * @param messages Conversation history in OpenRouter/OpenAI format
     * @param systemPrompt System instruction for the model
     * @param enableSearch Whether to enable Google Search grounding
     * @return Result containing the response text and optional sources
     */
    suspend fun chat(
        messages: List<ChatMessage>,
        systemPrompt: String? = null,
        enableSearch: Boolean = true
    ): Result<GeminiChatResult> {
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

            // Convert chat messages (OpenAI format -> Gemini format)
            for (msg in messages) {
                val role = when (msg.role) {
                    "user" -> "user"
                    "assistant" -> "model"
                    "system" -> continue // Already handled above
                    else -> "user"
                }
                geminiContents.add(
                    GeminiContent(
                        role = role,
                        parts = listOf(GeminiPart(msg.content))
                    )
                )
            }

            val request = GeminiRequest(
                contents = geminiContents,
                tools = if (enableSearch) listOf(GeminiTool(googleSearch = GeminiGoogleSearch())) else null
            )

            val response: GeminiResponse = client.post("$GEMINI_API_URL?key=$apiKey") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body()

            val durationMs = System.currentTimeMillis() - startTime

            // Check for API error
            if (response.error != null) {
                Log.e(TAG, "Gemini API error: ${response.error.message}")
                return Result.failure(Exception(response.error.message ?: "Unknown Gemini error"))
            }

            // Extract response text
            val candidate = response.candidates?.firstOrNull()
            val responseText = candidate?.content?.parts?.firstOrNull()?.text
                ?: return Result.failure(Exception("No response from Gemini"))

            // Extract grounding sources if available
            val sources = candidate.groundingMetadata?.groundingChunks?.mapNotNull { chunk ->
                chunk.web?.let { web ->
                    GeminiSource(
                        title = web.title ?: "Unknown",
                        uri = web.uri ?: ""
                    )
                }
            } ?: emptyList()

            val searchQueries = candidate.groundingMetadata?.webSearchQueries ?: emptyList()

            // Log usage
            response.usageMetadata?.let { usage ->
                Log.d(TAG, "Gemini usage: ${usage.promptTokenCount} prompt, ${usage.candidatesTokenCount} response, ${usage.thoughtsTokenCount} thinking")
                UsageLogger.logLlmUsage(
                    model = "gemini-3-flash-preview-search",
                    inputTokens = usage.promptTokenCount ?: 0,
                    outputTokens = (usage.candidatesTokenCount ?: 0) + (usage.thoughtsTokenCount ?: 0),
                    durationMs = durationMs
                )
            }

            Result.success(
                GeminiChatResult(
                    text = responseText,
                    sources = sources,
                    searchQueries = searchQueries
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Gemini API request failed", e)
            Result.failure(e)
        }
    }

    fun close() {
        client.close()
    }
}

/**
 * Result from a Gemini chat request, including optional grounding sources.
 */
data class GeminiChatResult(
    val text: String,
    val sources: List<GeminiSource> = emptyList(),
    val searchQueries: List<String> = emptyList()
)

data class GeminiSource(
    val title: String,
    val uri: String
)
