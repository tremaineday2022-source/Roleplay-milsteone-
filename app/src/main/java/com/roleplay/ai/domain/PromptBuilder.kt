package com.roleplay.ai.domain

import com.roleplay.ai.data.db.CharacterEntity
import com.roleplay.ai.data.db.MemoryEntity

object PromptBuilder {

    fun build(
        character: CharacterEntity,
        globalInstructions: String,
        memories: List<MemoryEntity>,
        summary: String
    ): String = buildString {
        appendLine("You are \"${character.name}\" in an immersive roleplay. Stay fully in character at all times.")
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
        if (globalInstructions.isNotBlank()) {
            appendLine()
            appendLine("## Custom instructions")
            appendLine(globalInstructions.trim())
        }
        val activeMemories = memories.filter { it.enabled && it.content.isNotBlank() }
        if (activeMemories.isNotEmpty()) {
            appendLine()
            appendLine("## Long-term memory")
            appendLine("The following facts are established and must remain consistent:")
            activeMemories.forEach { appendLine("- ${it.content.trim()}") }
        }
        if (summary.isNotBlank()) {
            appendLine()
            appendLine("## Summary of earlier events")
            appendLine(summary.trim())
        }
        appendLine()
        appendLine("Write engaging, descriptive replies that move the story forward. Never speak or act for the user unless asked.")
    }.trim()
}
