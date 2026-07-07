package com.lorem.strawberry.chat

import com.lorem.strawberry.core.ChatStore
import com.lorem.strawberry.core.ChatSummary
import com.lorem.strawberry.core.StoredMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomChatStore @Inject constructor(
    private val dao: ChatDao
) : ChatStore {

    override val chats: Flow<List<ChatSummary>> = dao.chats().map { entities ->
        entities.map { ChatSummary(id = it.id, title = it.title, updatedAt = it.updatedAt) }
    }

    override fun messages(chatId: Long): Flow<List<StoredMessage>> =
        dao.messages(chatId).map { entities -> entities.map { it.toStored() } }

    override suspend fun createChat(title: String): Long {
        val now = System.currentTimeMillis()
        return dao.insertChat(ChatEntity(title = title, createdAt = now, updatedAt = now))
    }

    override suspend fun appendMessage(chatId: Long, role: String, text: String, imagePath: String?) {
        val now = System.currentTimeMillis()
        dao.insertMessage(
            ChatMessageEntity(
                chatId = chatId,
                role = role,
                content = text,
                imagePath = imagePath,
                createdAt = now
            )
        )
        dao.touchChat(chatId, now)
    }

    override suspend fun getHistory(chatId: Long): List<StoredMessage> =
        dao.messagesOnce(chatId).map { it.toStored() }

    override suspend fun deleteChat(chatId: Long) {
        dao.deleteChat(chatId) // messages cascade
    }

    private fun ChatMessageEntity.toStored() = StoredMessage(
        id = id,
        role = role,
        text = content,
        imagePath = imagePath
    )
}
