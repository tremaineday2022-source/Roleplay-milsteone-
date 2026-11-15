package com.roleplay.ai

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import com.roleplay.ai.data.db.CharacterEntity
import com.roleplay.ai.data.db.MessageEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Reading earlier messages while new text arrives, in the real chat screen. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatScrollUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val app: RoleplayApp get() = rule.activity.application as RoleplayApp

    private fun clickText(text: String) = rule.onNodeWithText(text, useUnmergedTree = true).performClick()
    private fun waitForText(text: String, substring: Boolean = false) =
        rule.waitUntilAtLeastOneExists(hasText(text, substring = substring), 15_000)

    @Test
    fun scrollingUpIsNotUndoneByNewTextAndTheJumpButtonReturnsToTheEnd() {
        val chatId = runBlocking {
            app.settingsStore.update { it.copy(apiKey = "sk-test") }
            val riko = app.repository.getCharacter(app.repository.saveCharacter(CharacterEntity(name = "Riko")))!!
            app.repository.createSession(riko).also { id ->
                repeat(40) { n ->
                    app.repository.insertMessage(
                        MessageEntity(sessionId = id, role = if (n % 2 == 0) "user" else "assistant", content = "Message number $n")
                    )
                }
            }
        }
        waitForText("Message number 39")
        clickText("Riko")
        // Opening a chat shows the latest message.
        waitForText("Message number 39")
        assertTrue(rule.onAllNodes(hasContentDescription("Jump to latest message")).fetchSemanticsNodes().isEmpty())

        // Scroll up to read earlier messages.
        repeat(2) {
            rule.onRoot().performTouchInput { swipeDown(startY = height * 0.3f, endY = height * 0.8f) }
        }
        rule.waitUntilAtLeastOneExists(hasContentDescription("Jump to latest message"), 15_000)
        assertTrue(rule.onAllNodes(hasText("Message number 39")).fetchSemanticsNodes().isEmpty())

        // A new message arriving (as while a reply streams in) does not pull the view down.
        runBlocking { app.repository.insertMessage(MessageEntity(sessionId = chatId, role = "assistant", content = "A brand new reply")) }
        rule.waitUntil(15_000) { runBlocking { app.repository.getMessages(chatId) }.size == 41 }
        rule.waitForIdle()
        assertTrue(rule.onAllNodes(hasText("A brand new reply")).fetchSemanticsNodes().isEmpty())
        rule.onNodeWithContentDescription("Jump to latest message").assertIsDisplayed()

        // The button goes to the end, and from there new text is followed again.
        rule.onNodeWithContentDescription("Jump to latest message").performClick()
        waitForText("A brand new reply")
        rule.waitUntil(15_000) { rule.onAllNodes(hasContentDescription("Jump to latest message")).fetchSemanticsNodes().isEmpty() }
        runBlocking { app.repository.insertMessage(MessageEntity(sessionId = chatId, role = "user", content = "And one more")) }
        waitForText("And one more")
        rule.onNodeWithText("And one more").assertIsDisplayed()
    }
}
