package com.roleplay.ai

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import com.roleplay.ai.data.db.CharacterEntity
import com.roleplay.ai.data.db.variantList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Launches the real app (MainActivity, navigation, screens, view models, database) and taps
 * through the new features the way a person would, against a local fake API server.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val server = MockWebServer()
    private val chatReplies = ConcurrentLinkedQueue<String>()
    private val systems = ConcurrentLinkedQueue<String>()

    private val app: RoleplayApp get() = rule.activity.application as RoleplayApp

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                val first = body["messages"]!!.jsonArray.first().jsonObject["content"]!!.jsonPrimitive.content
                systems += first
                val text = when {
                    "character cards" in first ->
                        "```json\n{\"name\":\"Captain Vex\",\"tagline\":\"A pirate with a debt\",\"persona\":\"Loud and loyal.\"," +
                            "\"description\":\"The port of Brine.\",\"exampleDialogue\":\"Vex: Hoist it!\",\"greeting\":\"Ahoy.\"}\n```"
                    "co-writer" in first -> "and the lighthouse answered."
                    "long-term memory of a roleplay" in first -> "[\"The user is the new deckhand.\"]"
                    else -> chatReplies.poll() ?: "Default reply."
                }
                val quoted = JsonPrimitive(text).toString()
                return if (body["stream"]!!.jsonPrimitive.content == "true") {
                    MockResponse().setHeader("Content-Type", "text/event-stream")
                        .setBody("data: {\"choices\":[{\"delta\":{\"content\":$quoted}}]}\n\ndata: [DONE]\n\n")
                } else {
                    MockResponse().setBody("{\"choices\":[{\"message\":{\"content\":$quoted}}]}")
                }
            }
        }
        server.start()
        runBlocking {
            app.settingsStore.update { it.copy(baseUrl = server.url("/v1").toString(), apiKey = "test-key", model = "m") }
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** Clicks a label, including labels that sit inside buttons. */
    private fun clickText(text: String) = rule.onNodeWithText(text, useUnmergedTree = true).performClick()

    private fun waitForText(text: String, substring: Boolean = false) =
        rule.waitUntilAtLeastOneExists(hasText(text, substring = substring), 15_000)

    @Test
    fun theNewFeaturesWorkFromTheScreen() {
        // -- Generate with AI in the character editor --
        clickText("Characters")
        clickText("New character")
        clickText("Generate with AI")
        rule.onNodeWithText("Short description").performTextInput("a pirate captain")
        clickText("Generate")
        waitForText("Captain Vex")
        rule.onNodeWithText("Loud and loyal.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Ahoy.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("Save").performClick()
        waitForText("A pirate with a debt")

        // -- Group chat with two characters --
        runBlocking { app.repository.saveCharacter(CharacterEntity(name = "Bron", persona = "A gruff smith.", greeting = "Hmph.")) }
        clickText("Chats")
        rule.onNodeWithContentDescription("New chat").performClick()
        waitForText("Start something new")
        clickText("Bron")
        clickText("Captain Vex")
        clickText("Start group chat (2)")
        waitForText("Group chat · 2 characters")
        // Both greetings, each under its bold name prefix (shown without the asterisks).
        waitForText("Bron: Hmph.", substring = true)
        rule.onNodeWithText("Captain Vex: Ahoy.", substring = true).assertIsDisplayed()

        // -- Send, then regenerate twice --
        chatReplies += "**Bron:** First answer"
        rule.onNode(hasSetTextAction()).performTextInput("Hello both")
        rule.onNodeWithContentDescription("Send").performClick()
        waitForText("Bron: First answer")
        assertTrue(systems.any { "You play ALL of these characters: \"Bron\", \"Captain Vex\"" in it })

        chatReplies += "**Bron:** Second answer"
        rule.onNodeWithContentDescription("Regenerate").performClick()
        waitForText("Bron: Second answer")
        waitForText("2/2")
        chatReplies += "**Bron:** Third answer"
        rule.onNodeWithContentDescription("Regenerate").performClick()
        waitForText("Bron: Third answer")
        waitForText("3/3")

        // -- Swipe between the variants --
        rule.onNodeWithText("Bron: Third answer").performTouchInput { swipeRight() }
        waitForText("Bron: Second answer")
        waitForText("2/3")
        rule.onNodeWithText("Bron: Second answer").performTouchInput { swipeLeft() }
        waitForText("Bron: Third answer")
        waitForText("3/3")
        rule.onNodeWithText("Bron: Third answer").performTouchInput { swipeLeft() }
        waitForText("Bron: First answer")
        waitForText("1/3")
        rule.onNodeWithContentDescription("Previous reply").performClick()
        waitForText("3/3")

        // -- Continue the last reply --
        chatReplies += "that keeps going."
        rule.onNodeWithContentDescription("Continue").performClick()
        waitForText("Bron: Third answer that keeps going.")
        val sessionId = runBlocking { app.repository.db.sessionDao().getAll().single().id }
        val stored = runBlocking { app.repository.getMessages(sessionId) }
        assertEquals(3, stored.size)
        assertEquals(
            listOf("**Bron:** First answer", "**Bron:** Second answer", "**Bron:** Third answer that keeps going."),
            stored.last().variantList()
        )

        // -- Auto-memory (on by default) shows up in Memories --
        rule.waitUntil(15_000) {
            runBlocking { app.repository.db.memoryDao().getForSession(sessionId) }.any { it.content == "The user is the new deckhand." }
        }
        rule.onNodeWithContentDescription("Memories").performClick()
        waitForText("The user is the new deckhand.")
        rule.onNodeWithContentDescription("Back").performClick()

        // -- Lorebook, opened from the chat --
        rule.onNodeWithContentDescription("Lorebook").performClick()
        clickText("Add entry")
        rule.onNodeWithText("Name").performTextInput("Brine")
        rule.onNodeWithText("Trigger keywords, separated by commas").performTextInput("brine, lighthouse")
        rule.onNodeWithText("Content added to the prompt").performTextInput("Brine is a fog-bound port town.")
        clickText("Save")
        waitForText("Keywords: brine, lighthouse")
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithContentDescription("Back").performClick()

        // -- Writing mode --
        rule.onNodeWithContentDescription("New chat").performClick()
        clickText("New writing document")
        waitForText("Untitled document")
        rule.onNode(hasSetTextAction()).performTextInput("The fog rolled over the lighthouse")
        clickText("Continue")
        waitForText("The fog rolled over the lighthouse and the lighthouse answered.")
        val writingSystem = systems.last { "co-writer" in it }
        assertTrue("the lorebook entry should be triggered by the document", "Brine is a fog-bound port town." in writingSystem)
        rule.waitUntil(15_000) {
            runBlocking { app.repository.db.sessionDao().getAll() }
                .any { it.document == "The fog rolled over the lighthouse and the lighthouse answered." }
        }
        rule.onNodeWithContentDescription("Back").performClick()
        waitForText("Document · ", substring = true)
        rule.onNodeWithText("Group · ", substring = true).assertIsDisplayed()

        // -- Settings shows the new controls --
        clickText("Settings")
        rule.onNodeWithText("Choose image").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Manage lorebook").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Auto-save new facts as memories").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Export").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Import").performScrollTo().assertIsDisplayed()
    }
}
