package com.lorem.strawberry.core

import kotlinx.coroutines.flow.Flow

data class ChatSummary(
    val id: Long,
    val title: String,
    val updatedAt: Long
)

data class StoredMessage(
    val id: Long,
    val role: String,          // "user" or "assistant"
    val text: String,
    val imagePath: String? = null
)

/**
 * Persistence for chat threads. Implemented by RoomChatStore; faked in tests.
 */
interface ChatStore {
    /** All chats, newest activity first. */
    val chats: Flow<List<ChatSummary>>

    fun messages(chatId: Long): Flow<List<StoredMessage>>

    suspend fun createChat(title: String): Long

    suspend fun appendMessage(chatId: Long, role: String, text: String, imagePath: String? = null)

    suspend fun getHistory(chatId: Long): List<StoredMessage>

    suspend fun deleteChat(chatId: Long)
}
