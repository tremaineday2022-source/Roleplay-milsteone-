package com.roleplay.ai.data.repo

import com.roleplay.ai.data.api.ApiConfig
import com.roleplay.ai.data.api.ChatMessage
import com.roleplay.ai.data.api.OpenAiClient
import com.roleplay.ai.data.db.AppDatabase
import com.roleplay.ai.data.db.CharacterEntity
import com.roleplay.ai.data.db.MemoryEntity
import com.roleplay.ai.data.db.MessageEntity
import com.roleplay.ai.data.db.SessionEntity
import com.roleplay.ai.data.prefs.AppSettings
import com.roleplay.ai.domain.PromptBuilder
import kotlinx.coroutines.flow.Flow

class ChatRepository(
    private val db: AppDatabase,
    private val api: OpenAiClient = OpenAiClient()
) {
    private val characterDao = db.characterDao()
    private val sessionDao = db.sessionDao()
    private val messageDao = db.messageDao()
    private val memoryDao = db.memoryDao()

    fun observeCharacters(): Flow<List<CharacterEntity>> = characterDao.observeAll()
    fun observeSessions(): Flow<List<SessionEntity>> = sessionDao.observeAll()
    fun observeMessages(sessionId: Long): Flow<List<MessageEntity>> = messageDao.observeForSession(sessionId)
    fun observeMemories(sessionId: Long): Flow<List<MemoryEntity>> = memoryDao.observeForSession(sessionId)

    suspend fun getCharacter(id: Long): CharacterEntity? = characterDao.getById(id)
    suspend fun getSession(id: Long): SessionEntity? = sessionDao.getById(id)

    suspend fun saveCharacter(character: CharacterEntity): Long = characterDao.upsert(character)
    suspend fun deleteCharacter(character: CharacterEntity) = characterDao.delete(character)

    suspend fun createSession(character: CharacterEntity): Long {
        val sessionId = sessionDao.upsert(
            SessionEntity(characterId = character.id, title = character.name)
        )
        if (character.greeting.isNotBlank()) {
            messageDao.insert(
                MessageEntity(sessionId = sessionId, role = "assistant", content = character.greeting)
            )
        }
        return sessionId
    }

    suspend fun deleteSession(sessionId: Long) {
        messageDao.deleteForSession(sessionId)
        sessionDao.deleteById(sessionId)
    }

    suspend fun touchSession(sessionId: Long) = sessionDao.touch(sessionId)

    suspend fun updateSessionTitle(sessionId: Long, title: String) {
        sessionDao.getById(sessionId)?.let { sessionDao.update(it.copy(title = title)) }
    }

    suspend fun insertMessage(message: MessageEntity): Long = messageDao.insert(message)
    suspend fun updateMessage(message: MessageEntity) = messageDao.update(message)
    suspend fun deleteMessage(message: MessageEntity) = messageDao.deleteById(message.id)

    suspend fun saveMemory(memory: MemoryEntity): Long = memoryDao.upsert(memory)
    suspend fun deleteMemory(memory: MemoryEntity) = memoryDao.delete(memory)

    fun toApiConfig(settings: AppSettings, streaming: Boolean = settings.streaming): ApiConfig = ApiConfig(
        baseUrl = settings.baseUrl,
        apiKey = settings.apiKey,
        model = settings.model,
        temperature = settings.temperature.toDouble(),
        maxTokens = settings.maxTokens,
        stream = streaming
    )

    fun buildPrompt(
        character: CharacterEntity,
        session: SessionEntity,
        memories: List<MemoryEntity>,
        settings: AppSettings
    ): String = PromptBuilder.build(character, settings.globalInstructions, memories, session.summary)

    fun historyMessages(
        messages: List<MessageEntity>,
        systemPrompt: String,
        settings: AppSettings
    ): List<ChatMessage> {
        val recent = messages.takeLast(settings.contextMessages)
        return listOf(ChatMessage("system", systemPrompt)) +
            recent.map { ChatMessage(it.role, it.content) }
    }

    fun stream(config: ApiConfig, messages: List<ChatMessage>) = api.streamChat(config, messages)

    suspend fun summarize(session: SessionEntity, messages: List<MessageEntity>, settings: AppSettings): String? {
        val pending = messages.drop(session.summarizedUpTo).dropLast(settings.contextMessages / 2)
        if (pending.isEmpty()) return null
        val transcript = pending.joinToString("\n") { "${it.role}: ${it.content}" }
        val prompt = "Summarize the following roleplay conversation so far. " +
            "Keep character names, relationships, important events, promises, and ongoing plot threads. " +
            "Be concise but complete. Existing summary:\n${session.summary.ifBlank { "(none)" }}\n\nTranscript:\n$transcript"
        val summary = api.complete(
            toApiConfig(settings, streaming = false),
            listOf(
                ChatMessage("system", "You are a precise summarizer. Reply with the updated summary only."),
                ChatMessage("user", prompt)
            )
        ).trim()
        if (summary.isBlank()) return null
        sessionDao.updateSummary(session.id, summary, messages.size - settings.contextMessages / 2)
        return summary
    }

    suspend fun maybeSummarize(session: SessionEntity, settings: AppSettings) {
        if (!settings.autoSummary) return
        val total = messageDao.countForSession(session.id)
        if (total >= session.summarizedUpTo + settings.summaryThreshold) {
            val messages = messageDao.getForSession(session.id)
            summarize(session, messages, settings)
        }
    }
}
