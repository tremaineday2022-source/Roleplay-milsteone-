package com.roleplay.ai

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.text.TextLayoutResult
import com.roleplay.ai.data.db.CharacterEntity
import com.roleplay.ai.data.db.MessageEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The 1.3.0 features, used from the screen in the real app. No AI replies are needed. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NewFeaturesUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val app: RoleplayApp get() = rule.activity.application as RoleplayApp

    private fun clickText(text: String) = rule.onNodeWithText(text, useUnmergedTree = true).performClick()
    private fun waitForText(text: String, substring: Boolean = false) =
        rule.waitUntilAtLeastOneExists(hasText(text, substring = substring), 15_000)
    private fun sessions() = runBlocking { app.repository.db.sessionDao().getAll() }

    /** The font size, in sp, that a piece of text is actually drawn with. */
    private fun fontSizeOf(text: String): Float {
        val results = mutableListOf<TextLayoutResult>()
        // Unmerged: a bubble holds several pieces of text (the speaker's name, then the message).
        rule.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.first().layoutInput.style.fontSize.value
    }

    @Test
    fun branchingPinningRenamingSharingAndTheOtherNewControlsWork() {
        val chatId = runBlocking {
            app.settingsStore.update { it.copy(apiKey = "sk-test") }
            val riko = app.repository.getCharacter(
                app.repository.saveCharacter(CharacterEntity(name = "Riko", greeting = "Hello from Orth!"))
            )!!
            app.repository.createSession(riko).also { id ->
                app.repository.insertMessage(MessageEntity(sessionId = id, role = "user", content = "Shall we go down?"))
                app.repository.insertMessage(MessageEntity(sessionId = id, role = "assistant", content = "Right away."))
                app.repository.createWritingSession()
            }
        }

        // ---- Chat list: preview, then pin from the row menu ----
        waitForText("Right away.")
        rule.onNodeWithContentDescription("More options for Riko").performClick()
        clickText("Pin to top")
        rule.waitUntilAtLeastOneExists(hasContentDescription("Pinned"), 15_000)
        assertTrue(sessions().single { it.id == chatId }.pinned)
        assertEquals(chatId, runBlocking { app.repository.observeSessions().first() }.first().id)

        // ---- Rename from the row menu ----
        rule.onNodeWithContentDescription("More options for Riko").performClick()
        clickText("Rename")
        rule.onNode(hasSetTextAction()).performTextClearance()
        rule.onNode(hasSetTextAction()).performTextInput("Descent with Riko")
        clickText("Save")
        waitForText("Descent with Riko")

        // ---- Share as text ----
        rule.onNodeWithContentDescription("More options for Descent with Riko").performClick()
        clickText("Share as text")
        rule.waitUntil(15_000) { shadowOf(app).peekNextStartedActivity() != null }
        val chooser = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val shared = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!.getStringExtra(Intent.EXTRA_TEXT)!!
        assertTrue(shared, shared.startsWith("Descent with Riko\n"))
        assertTrue(shared, "Riko:\nHello from Orth!\n\nYou:\nShall we go down?\n\nRiko:\nRight away." in shared)

        // ---- Open the chat: the renamed title sits under the character's name ----
        clickText("Descent with Riko")
        waitForText("Hello from Orth!")
        rule.onNodeWithText("Descent with Riko").assertIsDisplayed()

        // ---- Copy a message ----
        rule.onNodeWithText("Shall we go down?").performTouchInput { longClick() }
        clickText("Copy text")
        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        rule.waitUntil(15_000) { clipboard.primaryClip?.getItemAt(0)?.text?.toString() == "Shall we go down?" }

        // ---- Text size from Settings changes the message text, not the labels ----
        val normal = fontSizeOf("Hello from Orth!")
        runBlocking { app.settingsStore.update { it.copy(textScale = 1.5f) } }
        assertEquals(14f, normal)
        rule.waitUntil(15_000) { fontSizeOf("Hello from Orth!") == normal * 1.5f }
        runBlocking { app.settingsStore.update { it.copy(textScale = 1f) } }
        rule.waitUntil(15_000) { fontSizeOf("Hello from Orth!") == normal }

        // ---- Branch from the user's message ----
        rule.onNodeWithText("Shall we go down?").performTouchInput { longClick() }
        clickText("Branch from here")
        waitForText("Descent with Riko (branch)")
        rule.waitUntil(15_000) { sessions().size == 3 }
        val branch = sessions().single { it.title == "Descent with Riko (branch)" }
        assertEquals(
            listOf("Hello from Orth!", "Shall we go down?"),
            runBlocking { app.repository.getMessages(branch.id) }.map { it.content }
        )
        assertEquals(3, runBlocking { app.repository.getMessages(chatId) }.size)
        // The later reply is not in the branch; the last message offers to generate one.
        assertTrue(rule.onAllNodes(hasText("Right away.")).fetchSemanticsNodes().isEmpty())

        // ---- Lorebook opened from the branch: an entry for this chat only ----
        rule.onNodeWithContentDescription("Lorebook").performClick()
        waitForText("This chat (0)")
        clickText("Add entry")
        rule.onNodeWithText("Name").performTextInput("Camp")
        rule.onNodeWithText("Trigger keywords, separated by commas").performTextInput("camp")
        rule.onNodeWithText("Content added to the prompt").performTextInput("A hollow under the roots.")
        clickText("Save")
        waitForText("This chat (1)")
        rule.onNodeWithText("All chats (0)").assertIsDisplayed()
        assertEquals(branch.id, runBlocking { app.repository.db.lorebookDao().getAll() }.single().sessionId)
        // Deleting an entry asks first.
        rule.onNodeWithContentDescription("Delete entry").performClick()
        waitForText("Delete this entry?")
        clickText("Cancel")
        assertEquals(1, runBlocking { app.repository.db.lorebookDao().getAll() }.size)
        rule.onNodeWithContentDescription("Back").performClick()

        // ---- Back goes to the list (the branch replaced the chat it came from) ----
        rule.onNodeWithContentDescription("Back").performClick()
        waitForText("Chats")
        rule.onNodeWithText("Descent with Riko (branch)").assertIsDisplayed()

        // ---- Deleting a chat asks first ----
        rule.onNodeWithContentDescription("More options for Descent with Riko (branch)").performClick()
        clickText("Delete")
        waitForText("Delete this chat?")
        clickText("Cancel")
        assertEquals(3, sessions().size)
        rule.onNodeWithContentDescription("More options for Descent with Riko (branch)").performClick()
        clickText("Delete")
        rule.onNodeWithText("Delete", useUnmergedTree = true).performClick()
        rule.waitUntil(15_000) { sessions().size == 2 }
        assertTrue(runBlocking { app.repository.db.lorebookDao().getAll() }.isEmpty())

        // ---- Character editor: a model for this character ----
        clickText("Characters")
        clickText("Riko")
        waitForText("Edit character")
        rule.onNodeWithText("Model name").performScrollTo().performTextInput("special/model")
        rule.onNodeWithContentDescription("Save").performClick()
        rule.waitUntil(15_000) { runBlocking { app.repository.db.characterDao().getAll() }.single().model == "special/model" }
        // Deleting a character asks first.
        rule.onNodeWithContentDescription("Delete").performClick()
        waitForText("Delete Riko?")
        clickText("Cancel")
        assertEquals(1, runBlocking { app.repository.db.characterDao().getAll() }.size)

        // ---- Settings: the new controls, and the warning for an unencrypted address ----
        clickText("Settings")
        rule.onNodeWithText("Text size").performScrollTo().assertIsDisplayed()
        assertTrue(rule.onAllNodes(hasText("This address is not encrypted", substring = true)).fetchSemanticsNodes().isEmpty())
        val baseUrl = rule.onNode(hasText("Base URL") and hasSetTextAction())
        baseUrl.performScrollTo()
        baseUrl.performTextClearance()
        baseUrl.performTextInput("http://example.com/v1")
        waitForText("This address is not encrypted", substring = true)
        baseUrl.performTextClearance()
        baseUrl.performTextInput("http://192.168.1.20:1234/v1")
        rule.waitUntil(15_000) {
            rule.onAllNodes(hasText("This address is not encrypted", substring = true)).fetchSemanticsNodes().isEmpty()
        }
    }
}
