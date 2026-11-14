package com.roleplay.ai.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

@Serializable
@Entity(tableName = "characters")
data class CharacterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val tagline: String = "",
    val avatarColor: Long = 0xFF6750A4,
    val persona: String = "",
    val description: String = "",
    val exampleDialogue: String = "",
    val greeting: String = "",
    val isFavorite: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    /** File name of the profile picture in the app image folder; blank for the letter avatar. */
    val avatarImage: String = ""
)

@Serializable
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** First (or only) character. 0 for writing documents. */
    val characterId: Long,
    val title: String,
    val summary: String = "",
    val summarizedUpTo: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** [MODE_CHAT] or [MODE_WRITING]. */
    val mode: String = MODE_CHAT,
    /** The freeform text of a writing-mode session. */
    val document: String = "",
    /** Comma-separated character ids of a group chat; empty for one-on-one chats. */
    val characterIds: String = "",
    /** Author's note for this chat or document, kept close to the latest messages. */
    val note: String = ""
) {
    companion object {
        const val MODE_CHAT = "chat"
        const val MODE_WRITING = "writing"
    }
}

@Serializable
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val role: String,
    /** Always the currently selected variant. */
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** JSON array of every reply variant; "[]" when the message was never regenerated. */
    val variants: String = "[]",
    val variantIndex: Int = 0,
    /** JSON array of image file names inside the app's image folder. */
    val images: String = "[]"
)

@Serializable
@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val content: String,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
@Entity(tableName = "lorebook")
data class LorebookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Comma-separated trigger keywords. */
    val keywords: String,
    val content: String,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

object JsonList {
    private val serializer = ListSerializer(String.serializer())

    fun encode(list: List<String>): String = Json.encodeToString(serializer, list)

    fun decode(raw: String): List<String> =
        if (raw.isBlank()) emptyList()
        else runCatching { Json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
}

val SessionEntity.isWriting: Boolean get() = mode == SessionEntity.MODE_WRITING

fun SessionEntity.memberIds(): List<Long> {
    val ids = characterIds.split(',').mapNotNull { it.trim().toLongOrNull() }
    return ids.ifEmpty { if (characterId > 0) listOf(characterId) else emptyList() }
}

val SessionEntity.isGroup: Boolean get() = memberIds().size > 1

fun MessageEntity.variantList(): List<String> = JsonList.decode(variants).ifEmpty { listOf(content) }

fun MessageEntity.imageList(): List<String> = JsonList.decode(images)

/** Adds [text] as a new variant and selects it. Earlier variants are kept. */
fun MessageEntity.withNewVariant(text: String): MessageEntity {
    val all = variantList() + text
    return copy(content = text, variants = JsonList.encode(all), variantIndex = all.lastIndex)
}

/** Selects the variant [delta] steps away, wrapping around at either end. */
fun MessageEntity.withVariantOffset(delta: Int): MessageEntity {
    val all = variantList()
    if (all.size < 2) return this
    val current = variantIndex.coerceIn(0, all.lastIndex)
    val next = ((current + delta) % all.size + all.size) % all.size
    return copy(content = all[next], variantIndex = next)
}

/** Replaces the text of the selected variant (used by edit and continue). */
fun MessageEntity.withContent(text: String): MessageEntity {
    val stored = JsonList.decode(variants)
    if (stored.isEmpty()) return copy(content = text)
    val index = variantIndex.coerceIn(0, stored.lastIndex)
    val all = stored.toMutableList().also { it[index] = text }
    return copy(content = text, variants = JsonList.encode(all), variantIndex = index)
}
