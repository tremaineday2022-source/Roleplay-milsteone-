package com.roleplay.ai.domain

import com.roleplay.ai.data.db.CharacterEntity
import com.roleplay.ai.data.db.MessageEntity
import com.roleplay.ai.data.db.SessionEntity
import com.roleplay.ai.data.db.imageList
import com.roleplay.ai.data.db.isWriting
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A chat or document turned into plain text, with a file name to save it under. */
data class ExportedText(val title: String, val fileName: String, val text: String)

/** Turns a chat or a writing document into readable plain text. */
object ChatExport {

    fun export(
        session: SessionEntity,
        characters: List<CharacterEntity>,
        messages: List<MessageEntity>,
        exportedAt: Long = System.currentTimeMillis()
    ): ExportedText {
        val title = session.title.trim().ifEmpty { if (session.isWriting) "Untitled document" else "Chat" }
        val text = if (session.isWriting) document(title, session.document) else chat(title, characters, messages, exportedAt)
        return ExportedText(title, fileName(title), text)
    }

    private fun document(title: String, body: String): String = "$title\n\n${body.trim()}\n"

    private fun chat(
        title: String,
        characters: List<CharacterEntity>,
        messages: List<MessageEntity>,
        exportedAt: Long
    ): String = buildString {
        val group = characters.size > 1
        val speaker = characters.singleOrNull()?.name ?: "AI"
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(exportedAt))
        appendLine(title)
        appendLine("Roleplay AI chat · exported $date · ${messages.size} messages")
        messages.forEach { message ->
            appendLine()
            val photos = message.imageList().size
            when {
                message.role == "user" -> appendLine("You:")
                // Group replies already carry a "Name:" prefix for each character.
                group -> Unit
                else -> appendLine("$speaker:")
            }
            repeat(photos) { appendLine("[photo]") }
            val content = if (group && message.role != "user") message.content.replace("**", "") else message.content
            if (content.isNotBlank()) appendLine(content.trim())
        }
    }

    /** A safe file name made from the title, e.g. "Riko, Reg.txt". */
    fun fileName(title: String): String {
        val cleaned = title.map { if (it.isLetterOrDigit() || it in " ,.-_()'") it else ' ' }.joinToString("")
            .split(' ').filter { it.isNotEmpty() }.joinToString(" ").trim('.', ' ').take(60).trim()
        return cleaned.ifEmpty { "chat" } + ".txt"
    }
}
