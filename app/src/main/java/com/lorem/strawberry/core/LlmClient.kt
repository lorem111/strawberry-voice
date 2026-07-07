package com.lorem.strawberry.core

data class LlmSource(
    val title: String,
    val uri: String
)

data class LlmReply(
    val text: String,
    val sources: List<LlmSource> = emptyList()
)

/**
 * A chat-completion backend. Implementations: OpenRouterApi (streaming), GeminiApi
 * (search grounding, non-streaming). Turns may carry images; clients encode them for
 * their own wire format.
 */
interface LlmClient {
    /**
     * [onDelta] receives partial response text as it streams in, when the backend
     * supports streaming; non-streaming backends never call it. The full reply is
     * always returned at the end either way.
     */
    suspend fun chat(
        history: List<ChatTurn>,
        systemPrompt: String?,
        onDelta: ((String) -> Unit)? = null
    ): Result<LlmReply>

    fun close()
}
