package com.lorem.strawberry.chat

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["chatId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("chatId")]
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: Long,
    val role: String,           // "user" or "assistant"
    val content: String,
    val imagePath: String? = null,
    val createdAt: Long
)

@Dao
interface ChatDao {
    @Query("SELECT * FROM chats ORDER BY updatedAt DESC")
    fun chats(): Flow<List<ChatEntity>>

    @Insert
    suspend fun insertChat(chat: ChatEntity): Long

    @Query("UPDATE chats SET updatedAt = :updatedAt WHERE id = :chatId")
    suspend fun touchChat(chatId: Long, updatedAt: Long)

    @Query("DELETE FROM chats WHERE id = :chatId")
    suspend fun deleteChat(chatId: Long)

    // Both queries cap at the 100 most recent messages (no summarization of older ones yet)
    @Query(
        "SELECT * FROM (SELECT * FROM messages WHERE chatId = :chatId ORDER BY id DESC LIMIT 100) " +
            "ORDER BY id ASC"
    )
    fun messages(chatId: Long): Flow<List<ChatMessageEntity>>

    @Query(
        "SELECT * FROM (SELECT * FROM messages WHERE chatId = :chatId ORDER BY id DESC LIMIT 100) " +
            "ORDER BY id ASC"
    )
    suspend fun messagesOnce(chatId: Long): List<ChatMessageEntity>

    @Insert
    suspend fun insertMessage(message: ChatMessageEntity): Long
}

@Database(
    entities = [ChatEntity::class, ChatMessageEntity::class],
    version = 1,
    exportSchema = false
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
}
