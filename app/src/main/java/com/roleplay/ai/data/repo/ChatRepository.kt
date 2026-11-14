package com.roleplay.ai.data.repo

import android.net.Uri
import com.roleplay.ai.data.api.ApiConfig
import com.roleplay.ai.data.api.ChatMessage
import com.roleplay.ai.data.api.OpenAiClient
import com.roleplay.ai.data.api.OpenAiException
import com.roleplay.ai.data.api.Usage
import com.roleplay.ai.data.db.AppDatabase
import com.roleplay.ai.data.db.CharacterEntity
import com.roleplay.ai.data.db.JsonList
import com.roleplay.ai.data.db.LorebookEntity
import com.roleplay.ai.data.db.MemoryEntity
import com.roleplay.ai.data.db.MessageEntity
import com.roleplay.ai.data.db.SessionEntity
import com.roleplay.ai.data.db.imageList
import com.roleplay.ai.data.db.isWriting
import com.roleplay.ai.data.db.memberIds
import com.roleplay.ai.data.db.withContent
import com.roleplay.ai.data.db.withNewVariant
import com.roleplay.ai.data.db.withVariantOffset
import com.roleplay.ai.data.files.ImageStore
import com.roleplay.ai.data.prefs.AppSettings
import com.roleplay.ai.domain.DocumentWindow
import com.roleplay.ai.domain.JsonExtract
import com.roleplay.ai.domain.LoreMatcher
import com.roleplay.ai.domain.PromptBuilder
import com.roleplay.ai.domain.TextJoin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ReplyKind { NEW, REGENERATE, CONTINUE }

data class CharacterDraft(
    val name: String,
    val tagline: String,
    val persona: String,
    val description: String,
    val exampleDialogue: String,
    val greeting: String
)

class ChatRepository(
    internal val db: AppDatabase,
    val images: ImageStore,
    private val api: OpenAiClient = OpenAiClient()
) {
    private val characterDao = db.characterDao()
    private val sessionDao = db.sessionDao()
    private val messageDao = db.messageDao()
    private val memoryDao = db.memoryDao()
    private val lorebookDao = db.lorebookDao()

    /** Outlives screens, so summaries, memory extraction and autosaves finish after you leave. */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun observeCharacters(): Flow<List<CharacterEntity>> = characterDao.observeAll()
    fun observeSessions(): Flow<List<SessionEntity>> = sessionDao.observeAll()
    fun observeSession(sessionId: Long): Flow<SessionEntity?> = sessionDao.observeById(sessionId)
    fun observeMessages(sessionId: Long): Flow<List<MessageEntity>> = messageDao.observeForSession(sessionId)
    fun observeMemories(sessionId: Long): Flow<List<MemoryEntity>> = memoryDao.observeForSession(sessionId)
    fun observeLorebook(): Flow<List<LorebookEntity>> = lorebookDao.observeAll()

    suspend fun getCharacter(id: Long): CharacterEntity? = characterDao.getById(id)
    suspend fun getSession(id: Long): SessionEntity? = sessionDao.getById(id)
    suspend fun getMessages(sessionId: Long): List<MessageEntity> = messageDao.getForSession(sessionId)

    suspend fun charactersFor(session: SessionEntity): List<CharacterEntity> =
        session.memberIds().mapNotNull { characterDao.getById(it) }

    suspend fun saveCharacter(character: CharacterEntity): Long = characterDao.upsert(character)

    suspend fun deleteCharacter(character: CharacterEntity) {
        characterDao.delete(character)
        if (character.avatarImage.isNotBlank()) withContext(Dispatchers.IO) { images.delete(listOf(character.avatarImage)) }
    }

    // ---- Sessions ----

    suspend fun createSession(character: CharacterEntity): Long = createSession(listOf(character))

    /** One character starts a normal chat; two or more start a group chat. */
    suspend fun createSession(characters: List<CharacterEntity>): Long {
        require(characters.isNotEmpty()) { "A chat needs at least one character" }
        val group = characters.size > 1
        val sessionId = sessionDao.upsert(
            SessionEntity(
                characterId = characters.first().id,
                title = characters.joinToString(", ") { it.name },
                characterIds = if (group) characters.joinToString(",") { it.id.toString() } else ""
            )
        )
        val greeting = if (group) {
            characters.filter { it.greeting.isNotBlank() }
                .joinToString("\n\n") { "**${it.name}:** ${it.greeting.trim()}" }
        } else {
            characters.first().greeting
        }
        if (greeting.isNotBlank()) {
            messageDao.insert(MessageEntity(sessionId = sessionId, role = "assistant", content = greeting))
        }
        return sessionId
    }

    suspend fun createWritingSession(): Long = sessionDao.upsert(
        SessionEntity(characterId = 0, title = "Untitled document", mode = SessionEntity.MODE_WRITING)
    )

    suspend fun deleteSession(sessionId: Long) {
        val names = messageDao.getForSession(sessionId).flatMap { it.imageList() }
        messageDao.deleteForSession(sessionId)
        memoryDao.deleteForSession(sessionId)
        sessionDao.deleteById(sessionId)
        withContext(Dispatchers.IO) { images.delete(names) }
    }

    suspend fun touchSession(sessionId: Long) = sessionDao.touch(sessionId)

    suspend fun updateSessionTitle(sessionId: Long, title: String) =
        sessionDao.updateTitle(sessionId, title)

    suspend fun updateNote(sessionId: Long, note: String) = sessionDao.updateNote(sessionId, note.trim())

    suspend fun saveDocument(sessionId: Long, document: String) =
        sessionDao.updateDocument(sessionId, document)

    /** Saves a document even if the screen that owned it is already gone. */
    fun saveDocumentInBackground(sessionId: Long, document: String): Job =
        background.launch { saveDocument(sessionId, document) }

    // ---- Messages ----

    suspend fun insertMessage(message: MessageEntity): Long = messageDao.insert(message)
    suspend fun updateMessage(message: MessageEntity) = messageDao.update(message)

    suspend fun deleteMessage(message: MessageEntity) {
        messageDao.deleteById(message.id)
        withContext(Dispatchers.IO) { images.delete(message.imageList()) }
    }

    suspend fun editMessage(message: MessageEntity, newContent: String) =
        messageDao.update(message.withContent(newContent))

    /** Shows the previous (-1) or next (+1) stored variant of a regenerated reply. */
    suspend fun swipeVariant(messageId: Long, delta: Int) {
        val message = messageDao.getById(messageId) ?: return
        val swiped = message.withVariantOffset(delta)
        if (swiped != message) messageDao.update(swiped)
    }

    suspend fun importImages(uris: List<Uri>): List<String> = uris.mapNotNull { images.importImage(it) }

    /** Removes stored images that no message, character or setting refers to. */
    suspend fun sweepOrphanImages(keep: Set<String>) = withContext(Dispatchers.IO) {
        val referenced = messageDao.getAllImageLists().flatMap { JsonList.decode(it) }.toSet() +
            characterDao.getAllAvatarImages()
        images.sweep(referenced + keep.filter { it.isNotBlank() })
    }

    // ---- Memories & lorebook ----

    suspend fun saveMemory(memory: MemoryEntity): Long = memoryDao.upsert(memory)
    suspend fun deleteMemory(memory: MemoryEntity) = memoryDao.delete(memory)

    suspend fun saveLoreEntry(entry: LorebookEntity): Long = lorebookDao.upsert(entry)
    suspend fun deleteLoreEntry(entry: LorebookEntity) = lorebookDao.delete(entry)

    // ---- Requests ----

    fun toApiConfig(settings: AppSettings, streaming: Boolean = settings.streaming): ApiConfig = ApiConfig(
        baseUrl = settings.baseUrl,
        apiKey = settings.apiKey,
        model = settings.model,
        temperature = settings.temperature.toDouble(),
        maxTokens = settings.maxTokens,
        stream = streaming
    )

    /** The API-key requirement: a key is needed unless the endpoint is a local server. */
    fun keyError(settings: AppSettings): String? =
        if (settings.apiKey.isBlank() && !settings.baseUrl.contains("localhost") &&
            !settings.baseUrl.contains("127.0.0.1")
        ) "No API key configured. Add one in Settings." else null

    /**
     * The full request for a chat reply: system prompt (characters, instructions, matching
     * lorebook entries, memories, summary) followed by the recent [history], with images.
     */
    suspend fun chatRequest(
        session: SessionEntity,
        history: List<MessageEntity>,
        settings: AppSettings,
        continueLast: Boolean = false
    ): List<ChatMessage> {
        val recent = history.takeLast(settings.contextMessages)
        val lore = LoreMatcher.match(lorebookDao.getEnabled(), recent.joinToString("\n") { it.content })
        val system = PromptBuilder.chat(
            characters = charactersFor(session),
            globalInstructions = settings.globalInstructions,
            memories = memoryDao.getForSession(session.id),
            lore = lore,
            summary = session.summary
        )
        val turns = withContext(Dispatchers.IO) {
            recent.map { message ->
                ChatMessage(message.role, message.content, message.imageList().mapNotNull { images.dataUrl(it) })
            }
        }
        val conversation = turns.toMutableList()
        if (continueLast) conversation += ChatMessage("user", PromptBuilder.CONTINUE_INSTRUCTION)
        if (session.note.isNotBlank()) {
            // Just before the latest user turn, where models pay most attention.
            val at = conversation.indexOfLast { it.role == "user" }.takeIf { it >= 0 } ?: conversation.size
            conversation.add(at, ChatMessage("system", PromptBuilder.note(session.note)))
        }
        return listOf(ChatMessage("system", system)) + conversation
    }

    /**
     * Writing mode: the document is sent as the user turn — whole, or only its latest words when
     * a limit is set. Lorebook entries are matched against the whole document either way.
     */
    suspend fun writingRequest(sessionId: Long, document: String, settings: AppSettings): List<ChatMessage> {
        val system = PromptBuilder.writing(
            globalInstructions = settings.globalInstructions,
            memories = memoryDao.getForSession(sessionId),
            lore = LoreMatcher.match(lorebookDao.getEnabled(), document),
            note = sessionDao.getById(sessionId)?.note.orEmpty()
        )
        return listOf(
            ChatMessage("system", system),
            ChatMessage("user", DocumentWindow.tail(document, settings.writingContextWords))
        )
    }

    fun stream(config: ApiConfig, messages: List<ChatMessage>, onUsage: ((Usage) -> Unit)? = null) =
        api.streamChat(config, messages, onUsage)

    /** Stores generated text: a new message, a new variant of [targetId], or appended to it. */
    suspend fun commitReply(sessionId: Long, kind: ReplyKind, targetId: Long?, text: String) {
        val target = targetId?.let { messageDao.getById(it) }
        when {
            kind == ReplyKind.REGENERATE && target != null ->
                messageDao.update(target.withNewVariant(text.trim()))
            kind == ReplyKind.CONTINUE && target != null ->
                messageDao.update(target.withContent(TextJoin.join(target.content, text.trimEnd())))
            else ->
                messageDao.insert(MessageEntity(sessionId = sessionId, role = "assistant", content = text.trim()))
        }
        sessionDao.touch(sessionId)
    }

    /** Runs the follow-up calls for a finished reply without blocking the chat. */
    fun afterReply(sessionId: Long, settings: AppSettings, extractMemories: Boolean): Job = background.launch {
        if (extractMemories) runCatching { extractMemories(sessionId, settings) }
        runCatching { sessionDao.getById(sessionId)?.let { maybeSummarize(it, settings) } }
    }

    /** Sends one tiny request. Returns normally if the endpoint, key and model all work. */
    suspend fun testConnection(settings: AppSettings) {
        keyError(settings)?.let { throw OpenAiException(it) }
        api.complete(
            toApiConfig(settings, streaming = false).copy(temperature = 0.0, maxTokens = 16),
            listOf(ChatMessage("user", "Reply with the single word OK."))
        )
    }

    // ---- Auto-memory ----

    /**
     * Asks the model for up to three new durable facts from the latest exchange and saves the
     * ones that are not already remembered. Returns how many were added.
     */
    suspend fun extractMemories(sessionId: Long, settings: AppSettings): Int {
        if (!settings.autoMemory || keyError(settings) != null) return 0
        val exchange = messageDao.getForSession(sessionId).takeLast(2)
        if (exchange.none { it.role == "assistant" }) return 0
        val known = memoryDao.getForSession(sessionId).map { it.content }
        val prompt = buildString {
            appendLine("Already remembered (do not repeat these, even reworded):")
            if (known.isEmpty()) appendLine("(nothing yet)") else known.forEach { appendLine("- $it") }
            appendLine()
            appendLine("Latest exchange:")
            exchange.forEach { appendLine("${it.role}: ${it.content}") }
        }
        val raw = api.complete(
            toApiConfig(settings, streaming = false).copy(temperature = 0.2, maxTokens = 400),
            listOf(ChatMessage("system", MEMORY_SYSTEM_PROMPT), ChatMessage("user", prompt))
        )
        val accepted = mutableListOf<String>()
        for (fact in JsonExtract.stringArray(raw)) {
            if (accepted.size == MAX_NEW_MEMORIES) break
            if (!TextJoin.isDuplicate(fact, known + accepted)) accepted += fact
        }
        accepted.forEach { memoryDao.upsert(MemoryEntity(sessionId = sessionId, content = it)) }
        return accepted.size
    }

    // ---- Character generation ----

    suspend fun generateCharacter(idea: String, settings: AppSettings): CharacterDraft {
        keyError(settings)?.let { throw OpenAiException(it) }
        val raw = api.complete(
            toApiConfig(settings, streaming = false).copy(maxTokens = maxOf(settings.maxTokens, 1500)),
            listOf(ChatMessage("system", CHARACTER_SYSTEM_PROMPT), ChatMessage("user", idea.trim()))
        )
        val obj = JsonExtract.obj(raw)
            ?: throw OpenAiException("The model did not return a valid character card. Try again.")
        val draft = CharacterDraft(
            name = JsonExtract.string(obj, "name"),
            tagline = JsonExtract.string(obj, "tagline"),
            persona = JsonExtract.string(obj, "persona"),
            description = JsonExtract.string(obj, "description"),
            exampleDialogue = JsonExtract.string(obj, "exampleDialogue"),
            greeting = JsonExtract.string(obj, "greeting")
        )
        if (draft.name.isBlank() && draft.persona.isBlank()) {
            throw OpenAiException("The model did not return a valid character card. Try again.")
        }
        return draft
    }

    // ---- Rolling summary ----

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
        if (!settings.autoSummary || session.isWriting || keyError(settings) != null) return
        val total = messageDao.countForSession(session.id)
        if (total >= session.summarizedUpTo + settings.summaryThreshold) {
            val messages = messageDao.getForSession(session.id)
            summarize(session, messages, settings)
        }
    }

    companion object {
        const val MAX_NEW_MEMORIES = 3

        private const val MEMORY_SYSTEM_PROMPT =
            "You maintain the long-term memory of a roleplay. From the latest exchange, extract at " +
                "most 3 NEW durable facts worth remembering for the rest of the story: names, " +
                "relationships, backstory, promises, lasting changes to characters or the world. " +
                "Skip moment-to-moment actions, anything already remembered, and anything uncertain. " +
                "Each fact is one short, self-contained sentence. Reply with ONLY a JSON array of " +
                "strings, with no code fences and no commentary. If there is nothing new, reply []."

        private const val CHARACTER_SYSTEM_PROMPT =
            "You write character cards for a roleplay chat app. Expand the user's short idea into a " +
                "full card. Reply with ONLY one JSON object, with no code fences and no commentary, " +
                "containing exactly these string keys:\n" +
                "\"name\": the character's name.\n" +
                "\"tagline\": one short line summarizing them.\n" +
                "\"persona\": who they are, personality, appearance, how they speak and behave (1-2 paragraphs).\n" +
                "\"description\": the world, setting, and opening situation (1 paragraph).\n" +
                "\"exampleDialogue\": 2-4 short example lines in the character's voice.\n" +
                "\"greeting\": the character's first message that opens the roleplay.\n" +
                "Every value must be a plain string; use \\n for line breaks inside a value."
    }
}
