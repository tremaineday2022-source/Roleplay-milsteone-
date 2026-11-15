package com.roleplay.ai

import android.content.Context
import android.content.Intent
import androidx.compose.ui.text.input.TextFieldValue
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.roleplay.ai.data.backup.BackupException
import com.roleplay.ai.data.backup.BackupManager
import com.roleplay.ai.data.db.AppDatabase
import com.roleplay.ai.data.db.CharacterEntity
import com.roleplay.ai.data.db.JsonList
import com.roleplay.ai.data.db.LorebookEntity
import com.roleplay.ai.data.db.MemoryEntity
import com.roleplay.ai.data.db.MessageEntity
import com.roleplay.ai.data.db.imageList
import com.roleplay.ai.data.db.isGroup
import com.roleplay.ai.data.db.isWriting
import com.roleplay.ai.data.db.memberIds
import com.roleplay.ai.data.db.variantList
import com.roleplay.ai.data.files.ImageStore
import com.roleplay.ai.data.prefs.AppSettings
import com.roleplay.ai.data.prefs.SettingsStore
import com.roleplay.ai.data.repo.ChatRepository
import com.roleplay.ai.domain.ChatExport
import com.roleplay.ai.domain.DocumentWindow
import com.roleplay.ai.domain.ExportedText
import com.roleplay.ai.domain.PromptBuilder
import com.roleplay.ai.domain.TokenEstimate
import com.roleplay.ai.ui.chat.TokenUsage
import com.roleplay.ai.ui.chatlist.ChatListViewModel
import com.roleplay.ai.ui.common.buildShareIntent
import com.roleplay.ai.ui.AppViewModel
import com.roleplay.ai.ui.ConnectionState
import com.roleplay.ai.ui.chat.ChatViewModel
import com.roleplay.ai.ui.writing.WritingViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/** One request the app sent to the fake API. */
private class Sent(val auth: String?, val body: JsonObject) {
    val messages: List<Pair<String, String>> = body["messages"]!!.jsonArray.map {
        val content = it.jsonObject["content"]
        it.jsonObject["role"]!!.jsonPrimitive.content to
            if (content is JsonPrimitive) content.content else content.toString()
    }
    val system: String get() = messages.first().second
    val stream: Boolean get() = body["stream"]!!.jsonPrimitive.content == "true"
    val wantsUsage: Boolean get() = body["stream_options"] != null
}

/**
 * Drives the real view models, repository, database and HTTP client against a local fake of an
 * OpenAI-compatible server. Only the screen layer (Compose) is not exercised here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AppFlowTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var server: MockWebServer
    private lateinit var db: AppDatabase
    private lateinit var images: ImageStore
    private lateinit var repo: ChatRepository
    private lateinit var store: SettingsStore
    private lateinit var settings: AppSettings

    private val sent = Collections.synchronizedList(mutableListOf<Sent>())
    private val chatReplies = ConcurrentLinkedQueue<String>()
    @Volatile private var memoryReply = "[]"
    @Volatile private var characterReply = "{}"
    @Volatile private var writingReply = " and the story went on."
    @Volatile private var slowChat = false
    @Volatile private var failWith: Int? = null
    @Volatile private var rejectStreamOptions = false

    private val mira = CharacterEntity(name = "Mira", tagline = "A thief", persona = "A sly thief.", greeting = "Well, hello.")
    private val bron = CharacterEntity(name = "Bron", persona = "A gruff smith.", greeting = "Hmph.")

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                val call = Sent(request.getHeader("Authorization"), body)
                sent += call
                if (rejectStreamOptions && call.wantsUsage) {
                    return MockResponse().setResponseCode(400)
                        .setBody("{\"error\":{\"message\":\"Unrecognized request argument supplied: stream_options\"}}")
                }
                failWith?.let { code ->
                    return MockResponse().setResponseCode(code)
                        .setBody("{\"error\":{\"message\":\"No auth credentials found\"}}")
                }
                val text = when {
                    "long-term memory of a roleplay" in call.system -> memoryReply
                    "character cards" in call.system -> characterReply
                    "precise summarizer" in call.system -> "A summary."
                    "co-writer" in call.system -> writingReply
                    else -> chatReplies.poll() ?: "Default reply."
                }
                return if (call.stream) sse(text, slow = slowChat, usage = call.wantsUsage) else plain(text)
            }
        }
        server.start()

        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        images = ImageStore(context)
        repo = ChatRepository(db, images)
        store = SettingsStore(context)
        configure { AppSettings(baseUrl = server.url("/v1").toString(), apiKey = "test-key", model = "test-model", autoMemory = false) }
    }

    @After
    fun tearDown() {
        // Settings writes started by a view model must finish while the test's main dispatcher
        // is still installed; a write left pending would block the next test's set-up.
        runBlocking { store.update { it } }
        db.close()
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun configure(change: (AppSettings) -> AppSettings) = runBlocking {
        store.update(change)
        settings = store.settings.first()
    }

    private fun quoted(text: String) = JsonPrimitive(text).toString()

    private fun sse(text: String, slow: Boolean, usage: Boolean = false): MockResponse {
        val pieces = text.chunked(maxOf(1, text.length / 3))
        val body = pieces.joinToString("") { "data: {\"choices\":[{\"delta\":{\"content\":${quoted(it)}}}]}\n\n" } +
            (if (usage) "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":1234,\"completion_tokens\":56,\"total_tokens\":1290}}\n\n" else "") +
            "data: [DONE]\n\n"
        return MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body).apply {
            if (slow) throttleBody(48, 400, TimeUnit.MILLISECONDS)
        }
    }

    private fun plain(text: String) = MockResponse().setHeader("Content-Type", "application/json")
        .setBody(
            "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":${quoted(text)}}}]," +
                "\"usage\":{\"prompt_tokens\":321,\"completion_tokens\":9}}"
        )

    private fun eventually(what: String, condition: suspend () -> Boolean) = runBlocking {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return@runBlocking
            Thread.sleep(25)
        }
        fail("Timed out waiting for: $what")
    }

    private fun chatSession(vararg characters: CharacterEntity): Long = runBlocking {
        val saved = characters.map { it.copy(id = repo.saveCharacter(it)) }
        repo.createSession(saved)
    }

    private fun chatViewModel(sessionId: Long) = ChatViewModel(repo, store, sessionId)

    private fun messages(sessionId: Long): List<MessageEntity> = runBlocking { repo.getMessages(sessionId) }

    /** Sends a user message and waits for the reply to be stored. */
    private fun ChatViewModel.sendAndAwait(sessionId: Long, text: String, reply: String) {
        // Count messages too: a reply can have the same text as something already in the chat.
        val before = messages(sessionId).size
        chatReplies += reply
        send(text)
        eventually("reply '$reply'") {
            val now = messages(sessionId)
            now.size >= before + 2 && now.last().content == reply && generation.value == null
        }
    }

    // ---- 1. Message swiping ----

    @Test
    fun regeneratingKeepsOldRepliesAndSwipingCyclesThroughThem() {
        val sessionId = chatSession(mira)
        val vm = chatViewModel(sessionId)
        vm.sendAndAwait(sessionId, "Hi", "Reply one")
        assertEquals("Bearer test-key", sent.last().auth)
        assertEquals(listOf("system", "assistant", "user"), sent.last().messages.map { it.first })

        chatReplies += "Reply two"
        vm.regenerate()
        eventually("second variant") { messages(sessionId).last().content == "Reply two" && vm.generation.value == null }
        // The reply being replaced is not sent back to the model.
        assertEquals(listOf("system", "assistant", "user"), sent.last().messages.map { it.first })
        assertFalse(sent.last().messages.any { "Reply one" in it.second })

        chatReplies += "Reply three"
        vm.regenerate()
        eventually("third variant") { messages(sessionId).last().content == "Reply three" && vm.generation.value == null }

        val all = messages(sessionId)
        assertEquals("regenerating must not add or remove messages", 3, all.size)
        val reply = all.last()
        assertEquals(listOf("Reply one", "Reply two", "Reply three"), reply.variantList())
        assertEquals(2, reply.variantIndex)

        vm.swipe(reply, -1)
        eventually("swipe back") { messages(sessionId).last().let { it.content == "Reply two" && it.variantIndex == 1 } }
        vm.swipe(messages(sessionId).last(), 1)
        eventually("swipe forward") { messages(sessionId).last().content == "Reply three" }
        vm.swipe(messages(sessionId).last(), 1)
        eventually("wrap around") { messages(sessionId).last().let { it.content == "Reply one" && it.variantIndex == 0 } }
        assertEquals(listOf("Reply one", "Reply two", "Reply three"), messages(sessionId).last().variantList())

        // The next turn continues from whichever variant is showing.
        vm.sendAndAwait(sessionId, "Go on", "Next")
        assertTrue(sent.last().messages.any { it.first == "assistant" && it.second == "Reply one" })
        assertFalse(sent.last().messages.any { it.second == "Reply three" })
    }

    // ---- 8. Continue ----

    @Test
    fun continueExtendsTheLastReplyInPlace() {
        val sessionId = chatSession(mira)
        val vm = chatViewModel(sessionId)
        vm.sendAndAwait(sessionId, "Open it", "The door creaked")

        chatReplies += "open, slowly."
        vm.continueReply()
        eventually("continued reply") {
            messages(sessionId).last().content == "The door creaked open, slowly." && vm.generation.value == null
        }

        assertEquals(3, messages(sessionId).size)
        val request = sent.last().messages
        assertEquals("user" to PromptBuilder.CONTINUE_INSTRUCTION, request.last())
        assertEquals("assistant" to "The door creaked", request[request.size - 2])

        // Continuing a regenerated reply only changes the variant on screen.
        chatReplies += "Second take"
        vm.regenerate()
        eventually("variant") { messages(sessionId).last().content == "Second take" && vm.generation.value == null }
        chatReplies += "continues."
        vm.continueReply()
        eventually("continued variant") {
            messages(sessionId).last().content == "Second take continues." && vm.generation.value == null
        }
        assertEquals(
            listOf("The door creaked open, slowly.", "Second take continues."),
            messages(sessionId).last().variantList()
        )
    }

    @Test
    fun stoppingKeepsThePartialReply() {
        val sessionId = chatSession(mira)
        val vm = chatViewModel(sessionId)
        val full = "This is a deliberately long reply that arrives slowly so it can be interrupted part of the way through."
        chatReplies += full
        slowChat = true
        vm.send("Tell me a story")
        eventually("first streamed text") { !vm.generation.value?.text.isNullOrEmpty() }
        vm.stopGeneration()
        eventually("stopped") { vm.generation.value == null && messages(sessionId).last().role == "assistant" && messages(sessionId).size == 3 }

        val partial = messages(sessionId).last().content
        assertTrue("partial '$partial' should be the start of the reply", full.startsWith(partial))
        assertTrue(partial.length < full.length)
    }

    // ---- 9. API-key requirement ----

    @Test
    fun aMissingApiKeyStillBlocksEveryRequest() {
        val sessionId = chatSession(mira)
        configure { it.copy(baseUrl = "https://api.example.com/v1", apiKey = "") }
        val vm = chatViewModel(sessionId)
        vm.send("Hi")
        eventually("key error") { vm.error.value == "No API key configured. Add one in Settings." }
        eventually("idle") { vm.generation.value == null }
        assertTrue(sent.isEmpty())
        // The user's message is kept so it can be retried once a key is set.
        assertEquals("Hi", messages(sessionId).last().content)

        val writingId = runBlocking { repo.createWritingSession() }
        val writing = WritingViewModel(repo, store, writingId)
        eventually("loaded") { writing.loaded }
        writing.onDocumentChange(TextFieldValue("Once upon a time"))
        writing.continueWriting()
        eventually("writing key error") { writing.error.value == "No API key configured. Add one in Settings." }
        assertEquals("Once upon a time", writing.document.text)

        val failure = runCatching { runBlocking { repo.generateCharacter("a pirate", settings) } }.exceptionOrNull()
        assertEquals("No API key configured. Add one in Settings.", failure?.message)
        assertTrue(sent.isEmpty())
    }

    // ---- 1.3.0: branching, per-chat lore, per-character model, pins, export ----

    @Test
    fun branchingCopiesTheChatUpToAMessageAndLeavesTheOriginalAlone() {
        val sessionId = chatSession(mira)
        images.file("photo.jpg").writeBytes(byteArrayOf(1, 2, 3))
        val vm = chatViewModel(sessionId)
        vm.sendAndAwait(sessionId, "First question", "Answer one")
        chatReplies += "Answer one, second take"
        vm.regenerate()
        eventually("variant") { messages(sessionId).last().content == "Answer one, second take" && vm.generation.value == null }
        runBlocking {
            repo.insertMessage(
                MessageEntity(sessionId = sessionId, role = "user", content = "Look", images = JsonList.encode(listOf("photo.jpg")))
            )
            repo.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = "A later reply"))
            repo.updateNote(sessionId, "Layer 2.")
            repo.setPinned(sessionId, true)
            repo.saveMemory(MemoryEntity(sessionId = sessionId, content = "Chat fact."))
            repo.saveMemory(MemoryEntity(sessionId = 0, content = "Global fact."))
            repo.saveLoreEntry(LorebookEntity(name = "Own", keywords = "own", content = "Chat lore.", sessionId = sessionId))
            repo.saveLoreEntry(LorebookEntity(name = "Shared", keywords = "shared", content = "Shared lore."))
        }
        val original = messages(sessionId)
        assertEquals(5, original.size)

        // Branch from the photo message: everything up to it comes along, the later reply does not.
        val branchId = runBlocking { repo.branchSession(sessionId, original[3].id)!! }
        val branch = runBlocking { repo.getSession(branchId)!! }
        assertNotEquals(sessionId, branchId)
        assertEquals("Mira (branch)", branch.title)
        assertEquals("Layer 2.", branch.note)
        assertFalse(branch.pinned)
        assertEquals(listOf("Mira"), runBlocking { repo.charactersFor(branch) }.map { it.name })

        val copied = messages(branchId)
        assertEquals(original.take(4).map { it.content }, copied.map { it.content })
        assertEquals(original.take(4).map { it.role }, copied.map { it.role })
        assertEquals(listOf("Answer one", "Answer one, second take"), copied[2].variantList())
        assertEquals(1, copied[2].variantIndex)

        // The photo was duplicated, not shared.
        val copiedPhoto = copied[3].imageList().single()
        assertNotEquals("photo.jpg", copiedPhoto)
        assertTrue(images.exists(copiedPhoto))
        assertEquals(listOf<Byte>(1, 2, 3), images.file(copiedPhoto).readBytes().toList())

        // Its own memories and lore came along; shared ones are not duplicated.
        runBlocking {
            assertEquals(listOf("Chat fact."), db.memoryDao().getOwnedBy(branchId).map { it.content })
            assertEquals(1, db.memoryDao().getAll().count { it.content == "Global fact." })
            assertEquals(listOf("Chat lore."), db.lorebookDao().getOwnedBy(branchId).map { it.content })
            assertEquals(1, db.lorebookDao().getAll().count { it.content == "Shared lore." })
        }

        // The original is untouched.
        assertEquals(original, messages(sessionId))
        assertTrue(runBlocking { repo.getSession(sessionId)!! }.pinned)

        // The branch continues on its own path, from its own history.
        val branchVm = chatViewModel(branchId)
        branchVm.sendAndAwait(branchId, "Another way", "A different path")
        assertFalse(sent.last().messages.any { it.second == "A later reply" })
        assertEquals(5, messages(sessionId).size)

        // Deleting the branch removes its copy of the photo, not the original's.
        runBlocking { repo.deleteSession(branchId) }
        assertFalse(images.exists(copiedPhoto))
        assertTrue(images.exists("photo.jpg"))
        assertEquals(1, runBlocking { db.lorebookDao().getAll() }.count { it.content == "Chat lore." })

        // Branching a branch keeps a single suffix; an unknown message is refused.
        val second = runBlocking { repo.branchSession(sessionId, original[0].id)!! }
        val third = runBlocking { repo.branchSession(second, messages(second)[0].id)!! }
        assertEquals("Mira (branch)", runBlocking { repo.getSession(third)!! }.title)
        assertNull(runBlocking { repo.branchSession(sessionId, 999_999) })
    }

    @Test
    fun branchingBeforeTheSummarizedPartDropsTheSummary() {
        val sessionId = chatSession(mira)
        runBlocking {
            repeat(6) { repo.insertMessage(MessageEntity(sessionId = sessionId, role = if (it % 2 == 0) "user" else "assistant", content = "m$it")) }
            db.sessionDao().updateSummary(sessionId, "Covers the first five messages.", 5)
        }
        val all = messages(sessionId)
        val early = runBlocking { repo.getSession(repo.branchSession(sessionId, all[2].id)!!)!! }
        assertEquals("", early.summary)
        assertEquals(0, early.summarizedUpTo)
        val late = runBlocking { repo.getSession(repo.branchSession(sessionId, all[5].id)!!)!! }
        assertEquals("Covers the first five messages.", late.summary)
        assertEquals(5, late.summarizedUpTo)
    }

    @Test
    fun chatLoreAppliesOnlyInItsOwnChatOnTopOfSharedLore() {
        val first = chatSession(mira)
        val second = chatSession(bron)
        val document = runBlocking { repo.createWritingSession() }
        runBlocking {
            repo.saveLoreEntry(LorebookEntity(name = "Shared", keywords = "abyss", content = "The Abyss is a vast pit."))
            repo.saveLoreEntry(LorebookEntity(name = "Own", keywords = "abyss", content = "In this story the pit is sealed.", sessionId = first))
            repo.saveLoreEntry(LorebookEntity(name = "Doc", keywords = "abyss", content = "Told as a diary.", sessionId = document))
        }

        chatViewModel(first).sendAndAwait(first, "Tell me about the Abyss", "It is deep.")
        assertTrue("The Abyss is a vast pit." in sent.last().system)
        assertTrue("In this story the pit is sealed." in sent.last().system)
        assertFalse("Told as a diary." in sent.last().system)

        chatViewModel(second).sendAndAwait(second, "Tell me about the Abyss", "Hmph.")
        assertTrue("The Abyss is a vast pit." in sent.last().system)
        assertFalse("In this story the pit is sealed." in sent.last().system)

        val writing = runBlocking { repo.writingRequest(document, "Into the abyss we went.", settings) }[0].content
        assertTrue("The Abyss is a vast pit." in writing)
        assertTrue("Told as a diary." in writing)
        assertFalse("In this story the pit is sealed." in writing)

        // What each screen lists.
        runBlocking {
            assertEquals(setOf("Shared", "Own"), repo.observeLorebook(first).first().map { it.name }.toSet())
            assertEquals(setOf("Shared"), repo.observeLorebook().first().map { it.name }.toSet())
        }

        // A chat's lore goes with it; shared lore stays.
        runBlocking { repo.deleteSession(first) }
        assertEquals(setOf("Shared", "Doc"), runBlocking { db.lorebookDao().getAll() }.map { it.name }.toSet())
    }

    @Test
    fun backupKeepsChatLoreAttachedToItsChat() {
        val sessionId = chatSession(mira)
        runBlocking {
            repo.saveLoreEntry(LorebookEntity(name = "Own", keywords = "k", content = "Chat lore.", sessionId = sessionId))
            repo.saveLoreEntry(LorebookEntity(name = "Shared", keywords = "k", content = "Shared lore."))
            repo.setPinned(sessionId, true)
        }
        val backups = BackupManager(db, images)
        val json = runBlocking { backups.export() }
        val summary = runBlocking { backups.import(json, replace = true) }
        assertEquals(2, summary.lorebook)
        runBlocking {
            val restored = db.sessionDao().getAll().single()
            assertNotEquals(sessionId, restored.id)
            assertTrue(restored.pinned)
            assertEquals(restored.id, db.lorebookDao().getAll().single { it.name == "Own" }.sessionId)
            assertEquals(0L, db.lorebookDao().getAll().single { it.name == "Shared" }.sessionId)
        }
    }

    @Test
    fun aCharactersOwnModelIsUsedOneOnOneButNotInGroupsOrBackgroundCalls() {
        configure { it.copy(autoMemory = true, model = "default-model", temperature = 0.9f) }
        val nanachi = CharacterEntity(name = "Nanachi", persona = "Gentle.", model = " special/model ", temperature = 1.4f)
        val plain = CharacterEntity(name = "Reg", persona = "Loyal.")

        val solo = chatSession(nanachi)
        val vm = chatViewModel(solo)
        vm.sendAndAwait(solo, "Hi", "Naa.")
        val reply = sent.first { "long-term memory of a roleplay" !in it.system }
        assertEquals("special/model", reply.body["model"]!!.jsonPrimitive.content)
        assertEquals(1.4, reply.body["temperature"]!!.jsonPrimitive.content.toDouble(), 0.001)
        eventually("token line names the model") { vm.tokens.value?.model == "special/model" }

        // The background memory call keeps the app default.
        eventually("memory call") { sent.any { "long-term memory of a roleplay" in it.system } }
        val extraction = sent.first { "long-term memory of a roleplay" in it.system }
        assertEquals("default-model", extraction.body["model"]!!.jsonPrimitive.content)

        // A character with nothing set uses the defaults, and the token line does not name a model.
        configure { it.copy(autoMemory = false) }
        val other = chatSession(plain)
        val otherVm = chatViewModel(other)
        otherVm.sendAndAwait(other, "Hi", "Hello.")
        assertEquals("default-model", sent.last().body["model"]!!.jsonPrimitive.content)
        assertEquals(0.9, sent.last().body["temperature"]!!.jsonPrimitive.content.toDouble(), 0.001)
        assertNull(otherVm.tokens.value?.model)

        // Group chats always use the app default, whatever the members are set to.
        val group = chatSession(nanachi, plain)
        chatViewModel(group).sendAndAwait(group, "Hi both", "**Nanachi:** Naa.")
        assertEquals("default-model", sent.last().body["model"]!!.jsonPrimitive.content)
        assertEquals(0.9, sent.last().body["temperature"]!!.jsonPrimitive.content.toDouble(), 0.001)
    }

    @Test
    fun pinnedChatsAreListedFirstThenNewestFirst() {
        val a = chatSession(mira)
        val b = chatSession(bron)
        val c = runBlocking { repo.createWritingSession() }
        runBlocking {
            db.sessionDao().touch(a, 1_000)
            db.sessionDao().touch(b, 2_000)
            db.sessionDao().touch(c, 3_000)
        }
        assertEquals(listOf(c, b, a), runBlocking { repo.observeSessions().first() }.map { it.id })
        runBlocking { repo.setPinned(a, true) }
        assertEquals(listOf(a, c, b), runBlocking { repo.observeSessions().first() }.map { it.id })
        runBlocking { repo.setPinned(a, false) }
        assertEquals(listOf(c, b, a), runBlocking { repo.observeSessions().first() }.map { it.id })
    }

    @Test
    fun chatListShowsAPreviewOfTheLatestMessageAndDocumentText() {
        val chat = chatSession(mira)
        val document = runBlocking { repo.createWritingSession().also { repo.saveDocument(it, "Chapter one.\n\nThe  end of the page.") } }
        runBlocking { repo.insertMessage(MessageEntity(sessionId = chat, role = "user", content = "**Hello**\n*waves* there")) }
        val vm = ChatListViewModel(repo, store)
        val collecting = kotlinx.coroutines.GlobalScope.launch(Dispatchers.Unconfined) { vm.items.collect { } }
        try {
            eventually("previews") { vm.items.value.size == 2 && vm.items.value.all { it.preview.isNotEmpty() } }
            assertEquals("Hello waves there", vm.items.value.single { it.session.id == chat }.preview)
            assertEquals("Chapter one. The end of the page.", vm.items.value.single { it.session.id == document }.preview)
        } finally {
            collecting.cancel()
        }
        val long = "word ".repeat(100).trim()
        assertTrue(ChatListViewModel.previewOf(long).endsWith("…"))
        assertTrue(ChatListViewModel.previewOf(long, fromEnd = true).startsWith("…"))
        assertEquals("", ChatListViewModel.previewOf("  \n "))
    }

    @Test
    fun exportTurnsChatsAndDocumentsIntoReadableText() {
        val solo = chatSession(mira)
        images.file("p.jpg").writeBytes(byteArrayOf(1))
        runBlocking {
            repo.insertMessage(MessageEntity(sessionId = solo, role = "user", content = "Hi *waves*", images = JsonList.encode(listOf("p.jpg"))))
            repo.insertMessage(MessageEntity(sessionId = solo, role = "assistant", content = "Well met."))
        }
        val session = runBlocking { repo.getSession(solo)!! }
        val text = ChatExport.export(session, listOf(mira), messages(solo), exportedAt = 0L).text
        val lines = text.lines()
        assertEquals("Mira", lines[0])
        assertTrue(lines[1].startsWith("Roleplay AI chat · exported 1970-01-01"))
        assertTrue(lines[1].endsWith("3 messages"))
        assertTrue("\nMira:\nWell, hello.\n\nYou:\n[photo]\nHi *waves*\n\nMira:\nWell met.\n" in text)

        val group = chatSession(mira, bron)
        runBlocking { repo.insertMessage(MessageEntity(sessionId = group, role = "user", content = "Hello both")) }
        val groupExport = runBlocking { repo.exportText(group)!! }
        assertEquals("Mira, Bron", groupExport.title)
        assertEquals("Mira, Bron.txt", groupExport.fileName)
        assertTrue("\nMira: Well, hello.\n\nBron: Hmph.\n\nYou:\nHello both\n" in groupExport.text)
        assertFalse("**" in groupExport.text)

        val document = runBlocking {
            repo.createWritingSession().also {
                repo.saveDocument(it, "Once upon a time.\n\nThe end.")
                repo.updateSessionTitle(it, "My story: part 1/2?")
            }
        }
        val docExport = runBlocking { repo.exportText(document)!! }
        assertEquals("My story: part 1/2?\n\nOnce upon a time.\n\nThe end.\n", docExport.text)
        assertEquals("My story part 1 2.txt", docExport.fileName)
        assertEquals("chat.txt", ChatExport.fileName("///"))
        assertNull(runBlocking { repo.exportText(987_654) })
    }

    @Test
    fun sharingSendsShortChatsAsTextAndVeryLongOnesAsAFile() {
        val short = buildShareIntent(context, ExportedText("Mira", "Mira.txt", "Mira:\nHello."))
        assertEquals(Intent.ACTION_CHOOSER, short.action)
        val shortSend = short.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, shortSend.action)
        assertEquals("text/plain", shortSend.type)
        assertEquals("Mira:\nHello.", shortSend.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals("Mira", shortSend.getStringExtra(Intent.EXTRA_SUBJECT))

        val body = "A very long story. ".repeat(5_000)
        val long = buildShareIntent(context, ExportedText("Epic", "Epic.txt", body))
        val longSend = long.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertNull(longSend.getStringExtra(Intent.EXTRA_TEXT))
        val uri = longSend.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("content", uri.scheme)
        assertTrue(uri.toString().endsWith("/exports/Epic.txt"))
        assertTrue(longSend.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(body, java.io.File(context.cacheDir, "exports/Epic.txt").readText())
    }

    // ---- 1.2.0: writing limit, notes, pictures, token counts ----

    @Test
    fun writingLimitSendsOnlyTheLatestWordsFromABreak() {
        val paragraphs = (1..40).joinToString("\n\n") { n -> "Paragraph $n " + "word ".repeat(49).trim() + "." }
        configure { it.copy(writingContextWords = 300) }
        val sessionId = runBlocking { repo.createWritingSession().also { repo.saveDocument(it, paragraphs) } }
        val vm = WritingViewModel(repo, store, sessionId)
        eventually("loaded") { vm.loaded }
        writingReply = " The end."
        vm.continueWriting()
        eventually("continued") { !vm.generating.value && vm.document.text.endsWith("The end.") }

        val sentDocument = sent.last().messages[1].second
        assertTrue(sentDocument.startsWith(DocumentWindow.OMITTED_MARKER + "Paragraph "))
        assertTrue(sentDocument.endsWith("Paragraph 40 " + "word ".repeat(49).trim() + "."))
        val sentWords = DocumentWindow.wordCount(sentDocument.removePrefix(DocumentWindow.OMITTED_MARKER))
        assertTrue("sent $sentWords words", sentWords in 250..300)
        assertFalse("Paragraph 1 " in sentDocument)
        // The document itself keeps every word.
        assertTrue(vm.document.text.startsWith("Paragraph 1 "))

        // 0 means the whole document again.
        configure { it.copy(writingContextWords = 0) }
        assertEquals(paragraphs, runBlocking { repo.writingRequest(sessionId, paragraphs, settings) }[1].content)
        // A limit above the length sends everything unchanged.
        configure { it.copy(writingContextWords = 100_000) }
        assertEquals(paragraphs, runBlocking { repo.writingRequest(sessionId, paragraphs, settings) }[1].content)
    }

    @Test
    fun chatNoteIsSentJustBeforeTheLatestUserMessage() {
        val sessionId = chatSession(mira)
        runBlocking { repo.updateNote(sessionId, "  We are on the 2nd layer. Riko is injured.  ") }
        assertEquals("We are on the 2nd layer. Riko is injured.", runBlocking { repo.getSession(sessionId)!!.note })

        val vm = chatViewModel(sessionId)
        vm.sendAndAwait(sessionId, "What now?", "We rest.")
        val roles = sent.last().messages.map { it.first }
        assertEquals(listOf("system", "assistant", "system", "user"), roles)
        assertEquals(PromptBuilder.note("We are on the 2nd layer. Riko is injured."), sent.last().messages[2].second)

        // Continue: the note still sits right before the final user turn.
        chatReplies += "more."
        vm.continueReply()
        eventually("continued") { messages(sessionId).last().content.endsWith("more.") && vm.generation.value == null }
        val continued = sent.last().messages
        assertEquals("system" to PromptBuilder.note("We are on the 2nd layer. Riko is injured."), continued[continued.size - 2])
        assertEquals("user" to PromptBuilder.CONTINUE_INSTRUCTION, continued.last())

        // Cleared notes are not sent.
        vm.saveNote("   ")
        eventually("cleared") { repo.getSession(sessionId)!!.note.isEmpty() }
        vm.sendAndAwait(sessionId, "And then?", "Then we climb.")
        assertTrue(sent.last().messages.none { "Author's note" in it.second })
    }

    @Test
    fun writingNoteGoesIntoTheSystemPrompt() {
        val sessionId = runBlocking { repo.createWritingSession().also { repo.updateNote(it, "Keep it in Riko's voice.") } }
        val request = runBlocking { repo.writingRequest(sessionId, "Once upon a time", settings) }
        assertTrue("## Author's note\nKeep it in Riko's voice." in request[0].content)
    }

    @Test
    fun characterPicturesSurviveTheSweepAndAreDeletedWithTheCharacter() {
        images.file("riko.jpg").writeBytes(byteArrayOf(1))
        images.file("background.jpg").writeBytes(byteArrayOf(2))
        images.file("unsaved-pick.jpg").writeBytes(byteArrayOf(3))
        val riko = runBlocking {
            repo.getCharacter(repo.saveCharacter(CharacterEntity(name = "Riko", avatarImage = "riko.jpg")))!!
        }
        assertEquals(listOf("riko.jpg"), runBlocking { db.characterDao().getAllAvatarImages() })

        runBlocking { repo.sweepOrphanImages(keep = setOf("background.jpg", "")) }
        assertTrue(images.exists("riko.jpg"))
        assertTrue(images.exists("background.jpg"))
        assertFalse(images.exists("unsaved-pick.jpg"))

        runBlocking { repo.deleteCharacter(riko) }
        assertFalse(images.exists("riko.jpg"))
        assertTrue(images.exists("background.jpg"))
    }

    @Test
    fun backupKeepsNotesAndPicturesThatAreStillOnTheDevice() {
        images.file("riko.jpg").writeBytes(byteArrayOf(1))
        val sessionId = runBlocking {
            val riko = repo.getCharacter(repo.saveCharacter(CharacterEntity(name = "Riko", avatarImage = "riko.jpg")))!!
            repo.saveCharacter(CharacterEntity(name = "Reg", avatarImage = "deleted.jpg"))
            repo.createSession(riko).also { repo.updateNote(it, "Layer 2.") }
        }
        val backups = BackupManager(db, images)
        val json = runBlocking { backups.export() }
        runBlocking { backups.import(json, replace = true) }
        val characters = runBlocking { db.characterDao().getAll() }
        assertEquals("riko.jpg", characters.single { it.name == "Riko" }.avatarImage)
        assertEquals("", characters.single { it.name == "Reg" }.avatarImage)
        assertEquals("Layer 2.", runBlocking { db.sessionDao().getAll() }.single().note)
        assertNotEquals(sessionId, runBlocking { db.sessionDao().getAll() }.single().id)
    }

    @Test
    fun tokenCountsUseTheProvidersNumbersWhenGivenAndAnEstimateOtherwise() {
        val sessionId = chatSession(mira)
        val vm = chatViewModel(sessionId)
        vm.sendAndAwait(sessionId, "Hi", "Hello.")
        assertTrue("streamed requests ask for usage", sent.last().wantsUsage)
        eventually("exact usage") { vm.tokens.value == TokenUsage(1234, 56, exact = true) }

        // Non-streamed replies carry usage in the body.
        configure { it.copy(streaming = false) }
        vm.sendAndAwait(sessionId, "Again", "Hello again.")
        assertFalse(sent.last().wantsUsage)
        eventually("usage from body") { vm.tokens.value == TokenUsage(321, 9, exact = true) }
    }

    @Test
    fun serversThatRejectStreamOptionsStillWorkAndShowAnEstimate() {
        rejectStreamOptions = true
        val sessionId = chatSession(mira)
        val vm = chatViewModel(sessionId)
        vm.sendAndAwait(sessionId, "Hi", "Hello.")
        // First attempt with stream_options was refused, the retry without it succeeded.
        val attempts = sent.toList()
        assertEquals(2, attempts.size)
        assertTrue(attempts[0].wantsUsage)
        assertFalse(attempts[1].wantsUsage)
        val usage = vm.tokens.value!!
        assertFalse(usage.exact)
        assertNull(usage.received)
        assertEquals(TokenEstimate.request(listOf(
            com.roleplay.ai.data.api.ChatMessage("system", attempts[1].system),
            com.roleplay.ai.data.api.ChatMessage("assistant", "Well, hello."),
            com.roleplay.ai.data.api.ChatMessage("user", "Hi")
        )), usage.sent)

        // The refusal is remembered: the next request goes straight through without it.
        vm.sendAndAwait(sessionId, "Again", "Hi again.")
        assertEquals(3, sent.size)
        assertFalse(sent.last().wantsUsage)
    }

    @Test
    fun tokenEstimateIsRoughlyFourCharactersPerToken() {
        assertEquals(0, TokenEstimate.text(""))
        assertEquals(25, TokenEstimate.text("a".repeat(100)))
        val withImage = com.roleplay.ai.data.api.ChatMessage("user", "abcd", listOf("data:x"))
        assertEquals(1 + 4 + TokenEstimate.PER_IMAGE + 3, TokenEstimate.request(listOf(withImage)))
    }

    // ---- Settings screen ----

    @Test
    fun typingInSettingsShowsEveryLetterImmediatelyAndSavesTheWholeText() {
        val vm = AppViewModel(store, repo)
        eventually("settings loaded") { vm.loaded }

        // Type one letter at a time, as a keyboard does, without waiting for the save to finish.
        val phrase = "write a roleplay"
        for (length in 1..phrase.length) {
            val typed = phrase.take(length)
            vm.update { it.copy(globalInstructions = typed) }
            assertEquals("the field must show exactly what was typed", typed, vm.draft.globalInstructions)
        }
        eventually("saved in full") { store.settings.first().globalInstructions == phrase }

        // The same for the API key and model, which the store also trims when saving.
        val key = "sk-or-v1-0123456789abcdef"
        for (length in 1..key.length) vm.update { it.copy(apiKey = key.take(length)) }
        vm.update { it.copy(model = "openrouter/auto ") }
        assertEquals(key, vm.draft.apiKey)
        assertEquals("openrouter/auto ", vm.draft.model)
        eventually("key and model saved") {
            store.settings.first().let { it.apiKey == key && it.model == "openrouter/auto" }
        }
        // Nothing typed was lost or reordered on the way to disk.
        assertEquals(phrase, runBlocking { store.settings.first() }.globalInstructions)
    }

    @Test
    fun testConnectionReportsSuccessAndExplainsARejectedKey() {
        val vm = AppViewModel(store, repo)
        eventually("settings loaded") { vm.loaded }
        assertEquals(ConnectionState.Idle, vm.connection)

        vm.testConnection()
        eventually("connected") { vm.connection == ConnectionState.Connected("test-model") }
        assertEquals("Bearer test-key", sent.last().auth)
        assertFalse(sent.last().stream)

        failWith = 401
        vm.testConnection()
        eventually("failed") { vm.connection is ConnectionState.Failed }
        val message = (vm.connection as ConnectionState.Failed).message
        assertTrue(message, message.startsWith("The API key was rejected."))
        assertTrue(message, "No auth credentials found" in message)

        // Editing a setting clears the old result.
        vm.update { it.copy(apiKey = "another-key") }
        assertEquals(ConnectionState.Idle, vm.connection)

        // A blank key for a remote server is reported without sending anything.
        val before = sent.size
        vm.update { it.copy(baseUrl = "https://openrouter.ai/api/v1", apiKey = "") }
        vm.testConnection()
        eventually("key missing") { vm.connection == ConnectionState.Failed("No API key configured. Add one in Settings.") }
        assertEquals(before, sent.size)
    }

    // ---- 3. Group chats ----

    @Test
    fun groupChatSendsEveryCharacterAndTheNamePrefixRule() {
        val sessionId = chatSession(mira, bron)
        val session = runBlocking { repo.getSession(sessionId)!! }
        assertTrue(session.isGroup)
        assertEquals("Mira, Bron", session.title)
        assertEquals(2, session.memberIds().size)
        assertEquals("**Mira:** Well, hello.\n\n**Bron:** Hmph.", messages(sessionId).single().content)

        val vm = chatViewModel(sessionId)
        eventually("characters loaded") { vm.characters.value.size == 2 }
        vm.sendAndAwait(sessionId, "Hello both", "**Mira:** Hi.\n\n**Bron:** What.")

        val system = sent.last().system
        assertTrue("You play ALL of these characters: \"Mira\", \"Bron\"" in system)
        assertTrue("**Mira:**" in system)
        assertTrue("A sly thief." in system)
        assertTrue("A gruff smith." in system)
    }

    // ---- 6. Lorebook ----

    @Test
    fun lorebookEntriesAreInjectedOnlyWhenAKeywordAppears() {
        runBlocking {
            repo.saveLoreEntry(LorebookEntity(name = "Silverhold", keywords = "silverhold, the hold", content = "Silverhold is a mountain city ruled by smiths."))
            repo.saveLoreEntry(LorebookEntity(name = "Dragons", keywords = "dragon", content = "Dragons were hunted to extinction."))
            repo.saveLoreEntry(LorebookEntity(name = "Off", keywords = "silverhold", content = "Disabled entry text.", enabled = false))
        }
        val sessionId = chatSession(mira)
        val vm = chatViewModel(sessionId)

        vm.sendAndAwait(sessionId, "What is the weather like?", "Mild.")
        assertFalse("World info" in sent.last().system)

        vm.sendAndAwait(sessionId, "Take me to SILVERHOLD.", "This way.")
        val system = sent.last().system
        assertTrue("Silverhold is a mountain city ruled by smiths." in system)
        assertFalse("Dragons were hunted to extinction." in system)
        assertFalse("Disabled entry text." in system)
    }

    // ---- 4. Auto-memory ----

    @Test
    fun autoMemorySavesUpToThreeNewFactsAndSkipsDuplicates() {
        configure { it.copy(autoMemory = true) }
        val sessionId = chatSession(mira)
        runBlocking { repo.saveMemory(MemoryEntity(sessionId = sessionId, content = "Mira is afraid of deep water.")) }
        memoryReply = "```json\n[\"Mira is afraid of deep water\", \"Mira owes Bron fifty gold.\", " +
            "\"Bron forged the user's sword.\", \"The user is called Ash.\", \"A fifth fact that is over the limit.\"]\n```"

        val vm = chatViewModel(sessionId)
        vm.sendAndAwait(sessionId, "I'm Ash. Bron made my sword.", "And I owe Bron fifty gold.")
        eventually("memories saved") { db.memoryDao().getForSession(sessionId).size == 4 }
        Thread.sleep(300)

        val saved = runBlocking { db.memoryDao().getForSession(sessionId) }.map { it.content }.toSet()
        assertEquals(
            setOf(
                "Mira is afraid of deep water.",
                "Mira owes Bron fifty gold.",
                "Bron forged the user's sword.",
                "The user is called Ash."
            ),
            saved
        )
        val extraction = sent.last { "long-term memory of a roleplay" in it.system }
        assertFalse(extraction.stream)
        assertTrue("Mira is afraid of deep water." in extraction.messages.last().second)
        assertTrue("And I owe Bron fifty gold." in extraction.messages.last().second)

        // Facts that are already saved add nothing, even when reworded.
        memoryReply = "[\"mira owes bron fifty gold\", \"The user is called Ash!\"]"
        assertEquals(0, runBlocking { repo.extractMemories(sessionId, settings) })
        assertEquals(4, runBlocking { db.memoryDao().getForSession(sessionId) }.size)
    }

    @Test
    fun autoMemoryCanBeSwitchedOff() {
        val sessionId = chatSession(mira)
        memoryReply = "[\"Something new.\"]"
        val vm = chatViewModel(sessionId)
        vm.sendAndAwait(sessionId, "Hi", "Hello.")
        Thread.sleep(400)
        assertTrue(sent.none { "long-term memory of a roleplay" in it.system })
        assertTrue(runBlocking { db.memoryDao().getForSession(sessionId) }.isEmpty())
    }

    // ---- 2. Writing mode ----

    @Test
    fun writingModeSendsTheWholeDocumentAndAppendsTheContinuation() {
        configure { it.copy(globalInstructions = "Write in a grim tone.") }
        val sessionId = runBlocking {
            repo.saveLoreEntry(LorebookEntity(name = "Silverhold", keywords = "Silverhold", content = "Silverhold is a mountain city."))
            repo.createWritingSession().also { repo.saveMemory(MemoryEntity(sessionId = it, content = "The king is dead.")) }
        }
        assertTrue(runBlocking { repo.getSession(sessionId)!! }.isWriting)

        val vm = WritingViewModel(repo, store, sessionId)
        eventually("loaded") { vm.loaded }
        // ~120k characters: far beyond the chat context window, to prove nothing is cut.
        val document = "The road to silverhold was long. " + "It rained for another day. ".repeat(4500) + "At last the gates appeared"
        vm.onDocumentChange(TextFieldValue(document))
        writingReply = "through the mist, black and tall."
        vm.continueWriting()

        val expected = "$document through the mist, black and tall."
        eventually("continuation appended and saved") {
            !vm.generating.value && repo.getSession(sessionId)!!.document == expected
        }
        assertEquals(expected, vm.document.text)

        val request = sent.last { "co-writer" in it.system }
        assertEquals(2, request.messages.size)
        assertEquals("user" to document, request.messages[1])
        assertTrue("Write in a grim tone." in request.system)
        assertTrue("The king is dead." in request.system)
        assertTrue("Silverhold is a mountain city." in request.system)
        // Documents are not chats: no summary or memory-extraction calls.
        assertEquals(1, sent.size)
    }

    @Test
    fun aDocumentEditedJustBeforeLeavingIsStillSaved() {
        val sessionId = runBlocking { repo.createWritingSession() }
        val vm = WritingViewModel(repo, store, sessionId)
        eventually("loaded") { vm.loaded }
        vm.onDocumentChange(TextFieldValue("Typed a moment before closing."))
        vm.rename("  My story ")

        // What the framework does when the screen is closed.
        val onCleared = WritingViewModel::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }
        onCleared.invoke(vm)

        eventually("saved after close") {
            repo.getSession(sessionId)!!.let { it.document == "Typed a moment before closing." && it.title == "My story" }
        }
    }

    // ---- 8. Generate with AI ----

    @Test
    fun characterGeneratorReadsFencedJson() {
        characterReply = "Here is your character!\n```json\n{\n  \"name\": \"Captain Vex\",\n  \"tagline\": \"A pirate with a debt\",\n" +
            "  \"persona\": \"Loud and loyal.\",\n  \"description\": \"The port of Brine.\",\n" +
            "  \"exampleDialogue\": \"Vex: Hoist it!\",\n  \"greeting\": \"Ahoy.\"\n}\n```"
        val draft = runBlocking { repo.generateCharacter("a pirate captain", settings) }
        assertEquals("Captain Vex", draft.name)
        assertEquals("A pirate with a debt", draft.tagline)
        assertEquals("Loud and loyal.", draft.persona)
        assertEquals("The port of Brine.", draft.description)
        assertEquals("Vex: Hoist it!", draft.exampleDialogue)
        assertEquals("Ahoy.", draft.greeting)
        assertEquals("user" to "a pirate captain", sent.last().messages.last())

        characterReply = "Sorry, I can't help with that."
        val failure = runCatching { runBlocking { repo.generateCharacter("x", settings) } }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().startsWith("The model did not return a valid character card"))
    }

    // ---- 5. Images ----

    @Test
    fun attachedImagesAreSentToTheModelAndCleanedUpWithTheirMessage() {
        val sessionId = chatSession(mira)
        images.file("photo.jpg").writeBytes(byteArrayOf(1, 2, 3))
        images.file("background.jpg").writeBytes(byteArrayOf(4))
        images.file("never-sent.jpg").writeBytes(byteArrayOf(5))
        val messageId = runBlocking {
            repo.insertMessage(
                MessageEntity(sessionId = sessionId, role = "user", content = "Look at this", images = JsonList.encode(listOf("photo.jpg")))
            )
        }

        val vm = chatViewModel(sessionId)
        chatReplies += "Nice photo."
        vm.generateReply()
        eventually("reply") { messages(sessionId).last().content == "Nice photo." && vm.generation.value == null }

        val userTurn = sent.last().body["messages"]!!.jsonArray.last().jsonObject
        val parts = userTurn["content"] as JsonArray
        assertEquals("Look at this", parts[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("image_url", parts[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("data:image/jpeg;base64,AQID", parts[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)

        runBlocking { repo.sweepOrphanImages(keep = setOf("background.jpg")) }
        assertTrue(images.exists("photo.jpg"))
        assertTrue(images.exists("background.jpg"))
        assertFalse(images.exists("never-sent.jpg"))

        runBlocking { repo.deleteMessage(db.messageDao().getById(messageId)!!) }
        assertFalse(images.exists("photo.jpg"))
    }

    // ---- 7. Backup / restore ----

    @Test
    fun backupRoundTripRestoresEverythingExceptSettings() {
        val groupId = chatSession(mira, bron)
        val vm = chatViewModel(groupId)
        vm.sendAndAwait(groupId, "Hello", "First")
        chatReplies += "Second"
        vm.regenerate()
        eventually("variant") { messages(groupId).last().content == "Second" && vm.generation.value == null }
        val writingId = runBlocking {
            repo.saveMemory(MemoryEntity(sessionId = groupId, content = "Session fact."))
            repo.saveMemory(MemoryEntity(sessionId = 0, content = "Global fact."))
            repo.saveLoreEntry(LorebookEntity(name = "City", keywords = "silverhold", content = "A city."))
            repo.createWritingSession().also { repo.saveDocument(it, "Chapter one.") }
        }

        val backups = BackupManager(db, images)
        val json = runBlocking { backups.export() }
        assertFalse("the API key must never be exported", "test-key" in json)
        assertFalse("apiKey" in json)
        assertFalse("baseUrl" in json)

        // Adding to existing data: everything is duplicated with fresh, correctly linked ids.
        val added = runBlocking { backups.import(json, replace = false) }
        assertEquals(listOf(2, 2, 3, 2, 1), listOf(added.characters, added.sessions, added.messages, added.memories, added.lorebook))
        runBlocking {
            assertEquals(4, db.characterDao().getAll().size)
            assertEquals(4, db.sessionDao().getAll().size)
            assertEquals(6, db.messageDao().getAll().size)
            assertEquals(4, db.memoryDao().getAll().size)
            assertEquals(2, db.lorebookDao().getAll().size)

            val copy = db.sessionDao().getAll().last { it.isGroup }
            assertNotEquals(groupId, copy.id)
            val originalMembers = db.sessionDao().getById(groupId)!!.memberIds()
            assertTrue(copy.memberIds().none { it in originalMembers })
            assertEquals(listOf("Mira", "Bron"), repo.charactersFor(copy).map { it.name })
            assertEquals(copy.memberIds().first(), copy.characterId)
            assertEquals(messages(groupId).map { it.content }, messages(copy.id).map { it.content })
            assertEquals(listOf("First", "Second"), messages(copy.id).last().variantList())
            assertEquals(setOf("Session fact.", "Global fact."), db.memoryDao().getForSession(copy.id).map { it.content }.toSet())
        }

        // Replacing: exactly what the backup holds, nothing else.
        runBlocking { repo.saveCharacter(CharacterEntity(name = "Made after the backup")) }
        val replaced = runBlocking { backups.import(json, replace = true) }
        assertEquals(2, replaced.sessions)
        runBlocking {
            assertEquals(listOf("Mira", "Bron"), db.characterDao().getAll().map { it.name })
            assertEquals(2, db.sessionDao().getAll().size)
            assertEquals(3, db.messageDao().getAll().size)
            assertEquals(2, db.memoryDao().getAll().size)
            assertEquals(1, db.lorebookDao().getAll().size)
            val document = db.sessionDao().getAll().single { it.isWriting }
            assertEquals("Chapter one.", document.document)
            assertNotEquals(writingId, document.id)
        }
    }

    @Test
    fun importRejectsFilesThatAreNotBackupsAndLeavesDataAlone() {
        val sessionId = chatSession(mira)
        val backups = BackupManager(db, images)
        for (bad in listOf("not json at all", "{\"hello\": 1}", "{\"app\": \"something-else\", \"formatVersion\": 1}",
            "{\"app\": \"roleplay-ai\", \"formatVersion\": 99}", "{\"characters\": []}", "[]")) {
            val failure = runCatching { runBlocking { backups.import(bad, replace = true) } }.exceptionOrNull()
            assertTrue("'$bad' should be rejected, got $failure", failure is BackupException)
        }
        assertEquals(1, messages(sessionId).size)
        assertEquals(1, runBlocking { db.characterDao().getAll() }.size)
        assertNull(runBlocking { db.sessionDao().getById(sessionId + 100) })
    }
}
