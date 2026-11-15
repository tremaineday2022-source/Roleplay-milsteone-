package com.roleplay.ai

import com.roleplay.ai.data.api.ChatMessage
import com.roleplay.ai.data.api.ChatRequest
import com.roleplay.ai.data.api.toJsonElement
import com.roleplay.ai.data.db.CharacterEntity
import com.roleplay.ai.data.db.LorebookEntity
import com.roleplay.ai.data.db.MemoryEntity
import com.roleplay.ai.data.db.MessageEntity
import com.roleplay.ai.data.db.SessionEntity
import com.roleplay.ai.data.db.isGroup
import com.roleplay.ai.data.db.memberIds
import com.roleplay.ai.data.db.variantList
import com.roleplay.ai.data.db.withContent
import com.roleplay.ai.data.db.withNewVariant
import com.roleplay.ai.data.db.withVariantOffset
import com.roleplay.ai.domain.JsonExtract
import com.roleplay.ai.domain.LoreMatcher
import com.roleplay.ai.domain.PromptBuilder
import com.roleplay.ai.domain.TextJoin
import com.roleplay.ai.ui.settings.isUnencryptedRemote
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextToolsTest {

    // ---- Variants (message swiping) ----

    @Test
    fun regeneratingKeepsOldRepliesAsVariants() {
        val first = MessageEntity(id = 1, sessionId = 1, role = "assistant", content = "one")
        assertEquals(listOf("one"), first.variantList())

        val second = first.withNewVariant("two")
        val third = second.withNewVariant("three")
        assertEquals(listOf("one", "two", "three"), third.variantList())
        assertEquals(2, third.variantIndex)
        assertEquals("three", third.content)
    }

    @Test
    fun swipingCyclesAndWrapsAround() {
        val message = MessageEntity(id = 1, sessionId = 1, role = "assistant", content = "one")
            .withNewVariant("two").withNewVariant("three")

        val back = message.withVariantOffset(-1)
        assertEquals("two", back.content)
        assertEquals(1, back.variantIndex)

        val wrappedForward = message.withVariantOffset(1)
        assertEquals("one", wrappedForward.content)
        assertEquals(0, wrappedForward.variantIndex)

        val wrappedBack = wrappedForward.withVariantOffset(-1)
        assertEquals("three", wrappedBack.content)
        assertEquals(listOf("one", "two", "three"), wrappedBack.variantList())
    }

    @Test
    fun swipingASingleReplyDoesNothing() {
        val message = MessageEntity(id = 1, sessionId = 1, role = "assistant", content = "only")
        assertEquals(message, message.withVariantOffset(1))
    }

    @Test
    fun editingChangesOnlyTheSelectedVariant() {
        val message = MessageEntity(id = 1, sessionId = 1, role = "assistant", content = "one")
            .withNewVariant("two").withVariantOffset(-1)
        val edited = message.withContent("ONE")
        assertEquals(listOf("ONE", "two"), edited.variantList())
        assertEquals("ONE", edited.content)

        val plain = MessageEntity(id = 2, sessionId = 1, role = "user", content = "hi").withContent("hello")
        assertEquals("hello", plain.content)
        assertEquals("[]", plain.variants)
    }

    // ---- Sessions ----

    @Test
    fun groupMembership() {
        val solo = SessionEntity(characterId = 4, title = "a")
        assertEquals(listOf(4L), solo.memberIds())
        assertFalse(solo.isGroup)

        val group = SessionEntity(characterId = 4, title = "a", characterIds = "4,9,2")
        assertEquals(listOf(4L, 9L, 2L), group.memberIds())
        assertTrue(group.isGroup)

        assertTrue(SessionEntity(characterId = 0, title = "doc", mode = "writing").memberIds().isEmpty())
    }

    // ---- Lorebook ----

    private fun lore(keywords: String, content: String = "lore", enabled: Boolean = true) =
        LorebookEntity(name = "n", keywords = keywords, content = content, enabled = enabled)

    @Test
    fun loreMatchesCaseInsensitivelyOnAnyKeyword() {
        val dragon = lore("Dragon, wyrm")
        val city = lore("Silverhold")
        val off = lore("dragon", enabled = false)
        val matched = LoreMatcher.match(listOf(dragon, city, off), "The old WYRM circled the tower.")
        assertEquals(listOf(dragon), matched)
        assertTrue(LoreMatcher.match(listOf(dragon, city), "Nothing relevant here.").isEmpty())
        assertTrue(LoreMatcher.match(listOf(lore(" , ")), "anything").isEmpty())
    }

    // ---- JSON extraction ----

    @Test
    fun stripsCodeFencesAroundJsonObject() {
        val raw = "Sure! Here is the card:\n```json\n{\"name\": \"Mira\", \"tagline\": \"A thief\"}\n```\nEnjoy."
        val obj = JsonExtract.obj(raw)!!
        assertEquals("Mira", JsonExtract.string(obj, "name"))
        assertEquals("A thief", JsonExtract.string(obj, "tagline"))
        assertEquals("", JsonExtract.string(obj, "missing"))
    }

    @Test
    fun readsBareObjectAndObjectInsideProseAndThinkTags() {
        assertEquals("A", JsonExtract.string(JsonExtract.obj("{\"name\":\"A\"}")!!, "name"))
        assertEquals("B", JsonExtract.string(JsonExtract.obj("Here you go: {\"name\":\"B\"} Hope it helps")!!, "name"))
        assertEquals(
            "C",
            JsonExtract.string(JsonExtract.obj("<think>maybe {\"name\":\"wrong\"}</think>{\"name\":\"C\"}")!!, "name")
        )
        assertNull(JsonExtract.obj("I cannot do that."))
    }

    @Test
    fun readsStringArrays() {
        assertEquals(listOf("a", "b"), JsonExtract.stringArray("```\n[\"a\", \"b\"]\n```"))
        assertEquals(listOf("x"), JsonExtract.stringArray("{\"facts\": [\"x\", 3, \"  \"]}"))
        assertTrue(JsonExtract.stringArray("[]").isEmpty())
        assertTrue(JsonExtract.stringArray("Nothing new.").isEmpty())
    }

    // ---- Text joins and duplicates ----

    @Test
    fun continuationJoinsWithoutDoublingOrDroppingSpaces() {
        assertEquals("She smiled and waved.", TextJoin.join("She smiled", "and waved."))
        assertEquals("She smiled and waved.", TextJoin.join("She smiled ", "and waved."))
        assertEquals("She smiled, then left.", TextJoin.join("She smiled", ", then left."))
        assertEquals("End.\n\nNext", TextJoin.join("End.", "\n\nNext"))
        assertEquals("abc", TextJoin.join("", "abc"))
    }

    @Test
    fun duplicateMemoriesAreDetected() {
        val known = listOf("Kira is allergic to cats.")
        assertTrue(TextJoin.isDuplicate("kira is allergic to cats", known))
        assertTrue(TextJoin.isDuplicate("Kira is allergic to cats, badly", known))
        assertFalse(TextJoin.isDuplicate("Kira owns a bakery in Silverhold.", known))
        assertTrue(TextJoin.isDuplicate("  ", known))
    }

    // ---- Settings helpers ----

    @Test
    fun unencryptedWarningIsOnlyForRemoteHttpAddresses() {
        assertTrue(isUnencryptedRemote("http://api.example.com/v1"))
        assertTrue(isUnencryptedRemote("  HTTP://203.0.113.5:8080/v1"))
        assertTrue(isUnencryptedRemote("http://172.32.0.1/v1"))
        assertFalse(isUnencryptedRemote("https://api.example.com/v1"))
        assertFalse(isUnencryptedRemote("http://localhost:11434/v1"))
        assertFalse(isUnencryptedRemote("http://127.0.0.1:1234/v1"))
        assertFalse(isUnencryptedRemote("http://192.168.1.20:1234/v1"))
        assertFalse(isUnencryptedRemote("http://10.0.0.5/v1"))
        assertFalse(isUnencryptedRemote("http://172.16.4.4:8000/v1"))
        assertFalse(isUnencryptedRemote("http://my-pc.local:1234/v1"))
        assertFalse(isUnencryptedRemote(""))
    }

    // ---- Prompts ----

    private val mira = CharacterEntity(id = 1, name = "Mira", persona = "A sly thief.")
    private val bron = CharacterEntity(id = 2, name = "Bron", persona = "A gruff smith.")

    @Test
    fun soloPromptKeepsPersonaInstructionsMemoriesAndLore() {
        val prompt = PromptBuilder.chat(
            characters = listOf(mira),
            globalInstructions = "Write in present tense.",
            memories = listOf(
                MemoryEntity(sessionId = 1, content = "Mira owes Bron money."),
                MemoryEntity(sessionId = 1, content = "disabled fact", enabled = false)
            ),
            lore = listOf(lore("x", content = "Silverhold is a mountain city.")),
            summary = "They met at the docks."
        )
        assertTrue(prompt.startsWith("You are \"Mira\""))
        assertTrue("A sly thief." in prompt)
        assertTrue("Write in present tense." in prompt)
        assertTrue("Mira owes Bron money." in prompt)
        assertFalse("disabled fact" in prompt)
        assertTrue("Silverhold is a mountain city." in prompt)
        assertTrue("They met at the docks." in prompt)
        assertFalse("Group format" in prompt)
    }

    @Test
    fun groupPromptListsEveryCharacterAndTheNamePrefixRule() {
        val prompt = PromptBuilder.chat(listOf(mira, bron), "", emptyList(), emptyList(), "")
        assertTrue("\"Mira\", \"Bron\"" in prompt)
        assertTrue("**Mira:**" in prompt)
        assertTrue("# Character: Mira" in prompt)
        assertTrue("# Character: Bron" in prompt)
        assertTrue("A gruff smith." in prompt)
    }

    @Test
    fun writingPromptCarriesInstructionsMemoriesAndLore() {
        val prompt = PromptBuilder.writing(
            "Keep it grim.",
            listOf(MemoryEntity(sessionId = 0, content = "The king is dead.")),
            listOf(lore("x", content = "Magic is outlawed."))
        )
        assertTrue("Keep it grim." in prompt)
        assertTrue("The king is dead." in prompt)
        assertTrue("Magic is outlawed." in prompt)
    }

    // ---- Request body ----

    @Test
    fun imagesAreSentAsImageUrlContentParts() {
        val body = ChatRequest(
            model = "m",
            messages = listOf(
                ChatMessage("system", "sys"),
                ChatMessage("user", "look", listOf("data:image/jpeg;base64,AAAA"))
            )
        ).toJsonElement().jsonObject
        val messages = body["messages"]!!.jsonArray
        assertEquals("sys", messages[0].jsonObject["content"]!!.jsonPrimitive.content)

        val parts = messages[1].jsonObject["content"]!!.jsonArray
        assertEquals("text", parts[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("look", parts[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("image_url", parts[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(
            "data:image/jpeg;base64,AAAA",
            parts[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
        )
    }
}
