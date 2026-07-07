package com.lorem.strawberry.conversation

/**
 * The voice-loop state machine: Idle → Listening → Thinking → Speaking → Listening → …
 *
 * All transitions go through ConversationOrchestrator.setState, which is where
 * cross-cutting rules live (SCO keepalive, foreground service). Errors are not a state:
 * they surface via ConversationOrchestrator.lastError and drop the loop back to Idle.
 */
sealed interface ConversationState {
    data object Idle : ConversationState
    data object Listening : ConversationState
    data object Thinking : ConversationState
    data object Speaking : ConversationState
}

data class Message(
    val content: String,
    val isUser: Boolean,
    val isLoading: Boolean = false,
    val imagePath: String? = null
)
