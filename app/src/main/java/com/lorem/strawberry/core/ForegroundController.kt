package com.lorem.strawberry.core

/**
 * Keeps the process alive (foreground service) while a conversation is active.
 * Implemented by ConversationServiceController; no-op fake in tests.
 */
interface ForegroundController {
    fun setActive(active: Boolean)
}
