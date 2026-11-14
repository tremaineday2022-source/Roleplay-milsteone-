package com.roleplay.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.roleplay.ai.data.db.CharacterEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the real chat screen with a chat background set and checks the picture is visible
 * in the empty space between and below the messages.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-night-xxhdpi")
class BackgroundScreenshotTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val app: RoleplayApp get() = rule.activity.application as RoleplayApp

    @Test
    fun theChatBackgroundIsVisibleInsideAChat() {
        // A photo-like picture: a pale blue sky fading into grey-green cliffs, similar in tone to a
        // typical landscape. Stored the way the Settings picker stores it.
        val name = "background-test.jpg"
        val picture = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
        Canvas(picture).drawPaint(android.graphics.Paint().apply {
            shader = android.graphics.LinearGradient(
                0f, 0f, 0f, 1920f,
                Color.rgb(150, 190, 225), Color.rgb(95, 110, 95),
                android.graphics.Shader.TileMode.CLAMP
            )
        })
        app.repository.images.file(name).outputStream().use { picture.compress(Bitmap.CompressFormat.JPEG, 90, it) }

        runBlocking {
            app.settingsStore.update { it.copy(chatBackground = name) }
            val id = app.repository.saveCharacter(CharacterEntity(name = "Riko", greeting = "Hello!"))
            app.repository.createSession(app.repository.getCharacter(id)!!)
        }

        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Riko")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Riko").performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Hello!")).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        Thread.sleep(500)
        rule.waitForIdle()

        // Draw the whole window into a bitmap (captureToImage needs a real GPU surface).
        val view = rule.activity.window.decorView
        val shot = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(Canvas(shot)) }
        System.getProperty("screenshot.dir")?.let { dir ->
            File(dir).mkdirs()
            File(dir, "chat-background-night.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }

        // Sample empty space below the greeting. In dark mode the plain chat background is
        // nearly black (lightness ~7%); the picture must stay clearly visible through the scrim.
        val x = shot.width / 2
        val y = (shot.height * 0.4).toInt()
        val pixel = shot.getPixel(x, y)
        val lightness = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3
        val blueTint = Color.blue(pixel) - Color.red(pixel)
        assertTrue(
            "picture too dark or missing at ($x,$y): #${Integer.toHexString(pixel)} (lightness $lightness)",
            lightness >= 80 && blueTint >= 15
        )

        // Bubbles let the picture through a little, so the screen does not look like a plain card wall.
        val bubble = shot.getPixel((shot.width * 0.2).toInt(), (shot.height * 0.12).toInt())
        assertTrue(
            "bubble should be slightly translucent: #${Integer.toHexString(bubble)}",
            Color.blue(bubble) > Color.red(bubble)
        )
    }
}
