package com.lorem.strawberry.di

import android.content.Context
import androidx.room.Room
import com.lorem.strawberry.audio.BluetoothScoManager
import com.lorem.strawberry.audio.SpeechRecognizerManager
import com.lorem.strawberry.chat.ChatDao
import com.lorem.strawberry.chat.ChatDatabase
import com.lorem.strawberry.chat.ImageStore
import com.lorem.strawberry.chat.RoomChatStore
import com.lorem.strawberry.conversation.ConversationOrchestrator
import com.lorem.strawberry.conversation.ConversationServiceController
import com.lorem.strawberry.core.AndroidAppLogger
import com.lorem.strawberry.core.AppLogger
import com.lorem.strawberry.core.ChatStore
import com.lorem.strawberry.core.ForegroundController
import com.lorem.strawberry.core.ImageEncoder
import com.lorem.strawberry.core.ScoController
import com.lorem.strawberry.core.SpeechInput
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {

    @Binds
    abstract fun bindAppLogger(impl: AndroidAppLogger): AppLogger

    @Binds
    abstract fun bindSpeechInput(impl: SpeechRecognizerManager): SpeechInput

    @Binds
    abstract fun bindScoController(impl: BluetoothScoManager): ScoController

    @Binds
    abstract fun bindForegroundController(impl: ConversationServiceController): ForegroundController

    @Binds
    abstract fun bindChatStore(impl: RoomChatStore): ChatStore

    @Binds
    abstract fun bindImageEncoder(impl: ImageStore): ImageEncoder
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /** App-lifetime scope on the main thread: orchestrator + registry state stays single-threaded. */
    @Provides
    @Singleton
    fun provideAppScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Provides
    @Singleton
    fun provideChatDatabase(@ApplicationContext context: Context): ChatDatabase =
        Room.databaseBuilder(context, ChatDatabase::class.java, "chats.db").build()

    @Provides
    fun provideChatDao(db: ChatDatabase): ChatDao = db.chatDao()

    @Provides
    @Singleton
    fun provideOrchestrator(
        scope: CoroutineScope,
        speech: SpeechInput,
        sco: ScoController,
        chatStore: ChatStore,
        registry: EngineRegistry,
        foreground: ForegroundController,
        logger: AppLogger,
        @ApplicationContext context: Context
    ): ConversationOrchestrator = ConversationOrchestrator(
        scope = scope,
        speech = speech,
        sco = sco,
        chatStore = chatStore,
        activeTts = registry.activeTts,
        activeLlm = registry.activeLlm,
        settings = registry.settings,
        foreground = foreground,
        logger = logger,
        systemPrompt = { loadSystemPrompt(context) }
    )

    private fun loadSystemPrompt(context: Context): String = try {
        context.assets.open("system_prompt.txt").bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        "You are a helpful voice assistant. Keep your responses concise and conversational."
    }
}
