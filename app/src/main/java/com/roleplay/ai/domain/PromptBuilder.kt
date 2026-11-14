package com.roleplay.ai.domain

import com.roleplay.ai.data.db.CharacterEntity
import com.roleplay.ai.data.db.LorebookEntity
import com.roleplay.ai.data.db.MemoryEntity

object PromptBuilder {

    /** Sent as a trailing user turn when the user asks to continue the last reply. */
    const val CONTINUE_INSTRUCTION =
        "[Continue your previous message from exactly where it stopped. Do not repeat or " +
            "summarize anything already written, do not start over, and do not add commentary. " +
            "Output only the continuation.]"

    /** Wraps a per-chat note so the model treats it as standing direction, not dialogue. */
    fun note(note: String): String =
        "[Author's note for this scene. Keep to it, but do not mention or quote it: ${note.trim()}]"

    /** System prompt for a chat with one character, or a group chat with several. */
    fun chat(
        characters: List<CharacterEntity>,
        globalInstructions: String,
        memories: List<MemoryEntity>,
        lore: List<LorebookEntity>,
        summary: String
    ): String = buildString {
        when {
            characters.isEmpty() ->
                appendLine("You are the narrator of an immersive roleplay. Stay in the story at all times.")
            characters.size == 1 -> {
                val character = characters.first()
                appendLine("You are \"${character.name}\" in an immersive roleplay. Stay fully in character at all times.")
                appendCard(character, heading = false)
            }
            else -> {
                val names = characters.joinToString(", ") { "\"${it.name}\"" }
                appendLine("This is an immersive group roleplay. You play ALL of these characters: $names. Stay fully in character for each of them at all times.")
                appendLine()
                appendLine("## Group format")
                appendLine("- Start each character's part on its own paragraph, prefixed with their name in bold and a colon, exactly like: **${characters.first().name}:** ...")
                appendLine("- Only use the names listed above as prefixes. Never write a part for the user.")
                appendLine("- Not everyone has to speak every turn: let whoever would naturally react respond, and let the characters talk to each other as well as to the user.")
                appendLine("- Keep each character's voice, knowledge, and personality distinct.")
                characters.forEach { appendCard(it, heading = true) }
            }
        }
        appendShared(globalInstructions, memories, lore)
        if (summary.isNotBlank()) {
            appendLine()
            appendLine("## Summary of earlier events")
            appendLine(summary.trim())
        }
        appendLine()
        appendLine("Write engaging, descriptive replies that move the story forward. Never speak or act for the user unless asked.")
    }.trim()

    /** System prompt for writing mode, where the whole document is sent as the user turn. */
    fun writing(
        globalInstructions: String,
        memories: List<MemoryEntity>,
        lore: List<LorebookEntity>,
        note: String = ""
    ): String = buildString {
        appendLine("You are a co-writer. The user's message is the complete text of a document they are writing.")
        appendLine("Continue it seamlessly from exactly where it ends, matching its voice, tense, point of view, and formatting.")
        appendLine("Do not repeat or rewrite existing text, do not summarize, and do not add titles, notes, or commentary. Output only the new text that comes next.")
        appendLine("If the document starts with \"[Earlier text omitted]\", only its end was sent; carry on from that end.")
        appendShared(globalInstructions, memories, lore)
        if (note.isNotBlank()) {
            appendLine()
            appendLine("## Author's note")
            appendLine(note.trim())
        }
    }.trim()

    private fun StringBuilder.appendCard(character: CharacterEntity, heading: Boolean) {
        if (heading) {
            appendLine()
            appendLine("# Character: ${character.name}")
        }
        if (character.tagline.isNotBlank()) appendLine("Tagline: ${character.tagline}")
        if (character.persona.isNotBlank()) {
            appendLine()
            appendLine("## Persona")
            appendLine(character.persona.trim())
        }
        if (character.description.isNotBlank()) {
            appendLine()
            appendLine("## Description & Scenario")
            appendLine(character.description.trim())
        }
        if (character.exampleDialogue.isNotBlank()) {
            appendLine()
            appendLine("## Example dialogue (style reference, never quote verbatim)")
            appendLine(character.exampleDialogue.trim())
        }
    }

    private fun StringBuilder.appendShared(
        globalInstructions: String,
        memories: List<MemoryEntity>,
        lore: List<LorebookEntity>
    ) {
        if (globalInstructions.isNotBlank()) {
            appendLine()
            appendLine("## Custom instructions")
            appendLine(globalInstructions.trim())
        }
        if (lore.isNotEmpty()) {
            appendLine()
            appendLine("## World info")
            appendLine("Established lore relevant to the current scene. Treat it as canon:")
            lore.forEach { entry ->
                val title = entry.name.trim()
                if (title.isNotEmpty()) appendLine("### $title")
                appendLine(entry.content.trim())
            }
        }
        val activeMemories = memories.filter { it.enabled && it.content.isNotBlank() }
        if (activeMemories.isNotEmpty()) {
            appendLine()
            appendLine("## Long-term memory")
            appendLine("The following facts are established and must remain consistent:")
            activeMemories.forEach { appendLine("- ${it.content.trim()}") }
        }
    }
}
