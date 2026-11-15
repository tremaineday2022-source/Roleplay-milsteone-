package com.roleplay.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.hasSetTextAction
import com.roleplay.ai.data.db.CharacterEntity
import com.roleplay.ai.data.db.LorebookEntity
import com.roleplay.ai.data.db.MemoryEntity
import com.roleplay.ai.data.db.MessageEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Walks through every screen and the main menus and dialogs of the real app and saves a
 * picture of each to build/screenshots/<theme>/, for reviewing the look of the app by eye. It
 * also fails if any of those screens cannot be opened.
 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
abstract class ScreenshotGallery(private val theme: String) {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val server = MockWebServer()
    private val replies = ConcurrentLinkedQueue<String>()
    private val app: RoleplayApp get() = rule.activity.application as RoleplayApp

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                val text = JsonPrimitive(replies.poll() ?: "[]").toString()
                return if (body["stream"]!!.jsonPrimitive.content == "true") {
                    MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                        "data: {\"choices\":[{\"delta\":{\"content\":$text}}]}\n\n" +
                            "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":1234,\"completion_tokens\":56}}\n\n" +
                            "data: [DONE]\n\n"
                    )
                } else {
                    MockResponse().setBody("{\"choices\":[{\"message\":{\"content\":$text}}]}")
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun clickText(text: String) = rule.onNodeWithText(text, useUnmergedTree = true).performClick()

    /** Taps a button by its description once it is on screen (some only appear when a reply has finished saving). */
    private fun clickWhenShown(description: String) {
        rule.waitUntilAtLeastOneExists(hasContentDescription(description), 15_000)
        rule.onNodeWithContentDescription(description).performClick()
    }
    private fun waitForText(text: String, substring: Boolean = false) =
        rule.waitUntilAtLeastOneExists(hasText(text, substring = substring), 15_000)

    private fun windowViews(): Pair<List<View>, List<WindowManager.LayoutParams>> {
        val global = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
        fun <T> field(fieldName: String): T {
            val field = global.javaClass.getDeclaredField(fieldName).apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            return field.get(global) as T
        }
        return field<List<View>>("mViews").toList() to field<List<WindowManager.LayoutParams>>("mParams").toList()
    }

    /**
     * Pictures a dialog that holds a text box, then closes it with the Back key.
     *
     * Under this test renderer any text box inside a dialog keeps the screen from ever counting
     * as "idle" (a bare text box in an empty dialog does it too, and the same box outside a
     * dialog does not), so the usual wait-then-tap cannot be used while one is open.
     */
    private fun shotOfTextDialog(name: String) {
        rule.mainClock.autoAdvance = false
        try {
            rule.mainClock.advanceTimeBy(600)
            shot(name, waitForIdle = false)
            rule.runOnUiThread {
                val top = windowViews().first.last()
                top.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
                top.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
            }
            rule.mainClock.advanceTimeBy(600)
        } finally {
            rule.mainClock.autoAdvance = true
        }
        rule.waitForIdle()
    }

    /** Draws every window (the screen plus any open menu or dialog) into one picture. */
    private fun shot(name: String, waitForIdle: Boolean = true) {
        if (waitForIdle) {
            // Pictures load in the background; give them a moment so they are in the shot.
            rule.waitForIdle()
            Thread.sleep(250)
            rule.waitForIdle()
        }
        val main = rule.activity.window.decorView
        val picture = Bitmap.createBitmap(main.width, main.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(picture)
        rule.runOnUiThread {
            val (views, params) = windowViews()
            views.forEachIndexed { index, view ->
                if (view.width == 0 || view.height == 0 || !view.isShown) return@forEachIndexed
                val layout = params[index]
                var x = 0f
                var y = 0f
                if (view !== main) {
                    if (layout.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0) {
                        canvas.drawColor(Color.argb((layout.dimAmount * 255).toInt(), 0, 0, 0))
                    }
                    x = when (layout.gravity and Gravity.HORIZONTAL_GRAVITY_MASK) {
                        Gravity.LEFT -> layout.x.toFloat()
                        Gravity.RIGHT -> (main.width - view.width - layout.x).toFloat()
                        else -> (main.width - view.width) / 2f + layout.x
                    }
                    y = when (layout.gravity and Gravity.VERTICAL_GRAVITY_MASK) {
                        Gravity.TOP -> layout.y.toFloat()
                        Gravity.BOTTOM -> (main.height - view.height - layout.y).toFloat()
                        else -> (main.height - view.height) / 2f + layout.y
                    }
                }
                canvas.save()
                canvas.translate(x, y)
                view.draw(canvas)
                canvas.restore()
            }
        }
        val dir = File(System.getProperty("screenshot.dir") ?: "build/screenshots", theme).apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun gradientPicture(name: String, top: Int, bottom: Int, width: Int, height: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawPaint(Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, height.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        })
        app.repository.images.file(name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    }

    private fun seed(): Long = runBlocking {
        val repo = app.repository
        gradientPicture("riko.jpg", Color.rgb(250, 205, 90), Color.rgb(220, 90, 60), 256, 256)
        gradientPicture("abyss.jpg", Color.rgb(150, 190, 225), Color.rgb(70, 95, 80), 1080, 1920)
        app.settingsStore.update { it.copy(baseUrl = server.url("/v1").toString(), apiKey = "sk-test-key", model = "openrouter/auto", autoMemory = false) }

        val riko = repo.getCharacter(
            repo.saveCharacter(
                CharacterEntity(
                    name = "Riko", tagline = "Red Whistle from Orth, daughter of Lyza",
                    persona = "Curious, stubborn and endlessly cheerful. Loves relics and cooking.",
                    description = "Orth, the town on the rim of the Abyss.",
                    greeting = "*Riko looks up from her notebook, goggles pushed into her hair.* You're finally here! Are you ready to go down?",
                    avatarImage = "riko.jpg"
                )
            )
        )!!
        val reg = repo.getCharacter(
            repo.saveCharacter(
                CharacterEntity(
                    name = "Reg", tagline = "A robot boy with no memories", persona = "Earnest and easily flustered.",
                    greeting = "W-what is it?", model = "anthropic/claude-sonnet", temperature = 1.2f, avatarColor = 0xFF00639B
                )
            )
        )!!
        repo.saveCharacter(CharacterEntity(name = "Nanachi", tagline = "A Hollow living in the fourth layer", avatarColor = 0xFF815600))

        val group = repo.createSession(listOf(riko, reg))
        repo.insertMessage(MessageEntity(sessionId = group, role = "user", content = "Which way now?"))
        repo.insertMessage(
            MessageEntity(sessionId = group, role = "assistant", content = "**Riko:** Down, of course! *She points past the ledge.*\n\n**Reg:** I'll go first. Hold on to my arm.")
        )

        val document = repo.createWritingSession()
        repo.updateSessionTitle(document, "The Long Descent")
        repo.saveDocument(
            document,
            "The wind that rose from the pit smelled of rain and old stone.\n\n" +
                "Riko tightened the strap of her pack and looked back once at the lights of Orth, " +
                "already small above them. Nobody had come to see them off. That was how she had wanted it.\n\n" +
                "\"Ready?\" Reg asked."
        )

        val solo = repo.createSession(riko)
        repo.setPinned(solo, true)
        repo.updateNote(solo, "We are on the 2nd layer, the Forest of Temptation. Riko's left arm is hurt.")
        repo.saveMemory(MemoryEntity(sessionId = solo, content = "The user is a Blue Whistle called Ash."))
        repo.saveMemory(MemoryEntity(sessionId = solo, content = "Riko promised to cook once they reach the Seeker Camp."))
        repo.saveLoreEntry(LorebookEntity(name = "Curse of the Abyss", keywords = "curse, ascend, climb", content = "Ascending causes strain that worsens with each layer."))
        repo.saveLoreEntry(LorebookEntity(name = "Whistles", keywords = "whistle, delver", content = "Red, Blue, Moon, Black and White whistles mark a delver's rank."))
        repo.saveLoreEntry(LorebookEntity(name = "Our camp", keywords = "camp", content = "A hollow under the roots of an inverted tree.", sessionId = solo))
        solo
    }

    fun tour() {
        seed()
        waitForText("The Long Descent")
        shot("01-chats")

        rule.onNodeWithContentDescription("More options for Riko").performClick()
        shot("02-chats-row-menu")
        clickText("Delete")
        shot("03-delete-confirm")
        clickText("Cancel")

        rule.onNodeWithContentDescription("New chat").performClick()
        waitForText("Start something new")
        shot("04-new-chat-dialog")
        clickText("Cancel")

        // A one-on-one chat: send, regenerate (for the 2/2 arrows), token line, note strip.
        clickText("Riko")
        waitForText("You're finally here!", substring = true)
        replies += "*She grins and hops to her feet.* Then let's not waste the daylight! Stay close, okay?"
        rule.onNode(hasSetTextAction()).performTextInput("Ready when you are.")
        rule.onNodeWithContentDescription("Send").performClick()
        waitForText("Then let's not waste the daylight", substring = true)
        replies += "*Riko snaps her notebook shut.* Great! First stop is the Seeker Camp. I marked a shortcut."
        clickWhenShown("Regenerate")
        waitForText("2/2")
        waitForText("tokens sent", substring = true)
        shot("05-chat")

        rule.onNodeWithText("First stop is the Seeker Camp", substring = true).performTouchInput { longClick() }
        waitForText("Branch from here")
        shot("06-chat-message-menu")
        clickText("Save as memory")
        clickText("Cancel")

        rule.onNodeWithContentDescription("More options").performClick()
        shot("07-chat-top-menu")
        clickText("Memories")
        waitForText("The user is a Blue Whistle called Ash.")
        shot("08-memories")
        rule.onNodeWithContentDescription("Back").performClick()

        rule.onNodeWithContentDescription("Chat note").performClick()
        shotOfTextDialog("09-note-dialog")

        rule.onNodeWithContentDescription("Lorebook").performClick()
        waitForText("This chat (1)")
        shot("10-lorebook-this-chat")
        clickText("All chats (2)")
        waitForText("Curse of the Abyss")
        shot("11-lorebook-all")
        clickText("Curse of the Abyss")
        shotOfTextDialog("12-lore-edit-dialog")
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithContentDescription("Back").performClick()

        clickText("Riko, Reg")
        waitForText("Group chat · 2 characters")
        shot("13-group-chat")
        rule.onNodeWithContentDescription("Back").performClick()

        clickText("The Long Descent")
        waitForText("Continue sends the whole document", substring = true)
        shot("14-writing")
        rule.onNodeWithContentDescription("Back").performClick()

        clickText("Characters")
        waitForText("A robot boy with no memories")
        shot("15-characters")
        clickText("Reg")
        waitForText("Edit character")
        shot("16-character-editor")
        rule.onNodeWithText("Custom creativity (temperature)").performScrollTo()
        rule.onNodeWithText("Temperature").performScrollTo()
        shot("17-character-editor-model")
        rule.onNodeWithContentDescription("Back").performClick()

        clickText("Settings")
        waitForText("API connection")
        shot("18-settings")
        rule.onNodeWithText("Choose image").performScrollTo()
        shot("19-settings-appearance")
        rule.onNodeWithText("Manage lorebook").performScrollTo()
        shot("20-settings-middle")
        rule.onNodeWithText("Export").performScrollTo()
        shot("21-settings-bottom")

        // Large text and a background picture.
        runBlocking { app.settingsStore.update { it.copy(textScale = 1.4f, chatBackground = "abyss.jpg") } }
        clickText("Chats")
        clickText("Riko")
        waitForText("First stop is the Seeker Camp", substring = true)
        Thread.sleep(400)
        shot("22-chat-background-large-text")
        rule.onNodeWithContentDescription("Back").performClick()

        // Before an API key is set.
        runBlocking { app.settingsStore.update { it.copy(baseUrl = "https://openrouter.ai/api/v1", apiKey = "") } }
        waitForText("Connect your AI first")
        shot("23-chats-setup-banner")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-notnight-xhdpi")
class ScreenshotGalleryLightTest : ScreenshotGallery("light") {
    @Test
    fun everyScreenOpens() = tour()
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-night-xhdpi")
class ScreenshotGalleryDarkTest : ScreenshotGallery("dark") {
    @Test
    fun everyScreenOpens() = tour()
}
