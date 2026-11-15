package com.roleplay.ai.data.backup

import androidx.room.withTransaction
import com.roleplay.ai.data.db.AppDatabase
import com.roleplay.ai.data.db.CharacterEntity
import com.roleplay.ai.data.db.JsonList
import com.roleplay.ai.data.db.LorebookEntity
import com.roleplay.ai.data.db.MemoryEntity
import com.roleplay.ai.data.db.MessageEntity
import com.roleplay.ai.data.db.SessionEntity
import com.roleplay.ai.data.db.imageList
import com.roleplay.ai.data.files.ImageStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Everything a backup holds. Settings and the API key are deliberately not part of it. */
@Serializable
data class BackupFile(
    // No defaults on these two: a file without them is not a backup and must be rejected,
    // otherwise any JSON object would import as "empty" and Replace would wipe everything.
    val app: String,
    val formatVersion: Int,
    val exportedAt: Long = 0,
    val characters: List<CharacterEntity> = emptyList(),
    val sessions: List<SessionEntity> = emptyList(),
    val messages: List<MessageEntity> = emptyList(),
    val memories: List<MemoryEntity> = emptyList(),
    val lorebook: List<LorebookEntity> = emptyList()
) {
    companion object {
        const val APP_ID = "roleplay-ai"
        const val FORMAT_VERSION = 1
    }
}

data class ImportSummary(
    val characters: Int,
    val sessions: Int,
    val messages: Int,
    val memories: Int,
    val lorebook: Int
)

class BackupException(message: String) : Exception(message)

class BackupManager(private val db: AppDatabase, private val images: ImageStore) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    suspend fun export(): String = db.withTransaction {
        json.encodeToString(
            BackupFile.serializer(),
            BackupFile(
                app = BackupFile.APP_ID,
                formatVersion = BackupFile.FORMAT_VERSION,
                exportedAt = System.currentTimeMillis(),
                characters = db.characterDao().getAll(),
                sessions = db.sessionDao().getAll(),
                messages = db.messageDao().getAll(),
                memories = db.memoryDao().getAll(),
                lorebook = db.lorebookDao().getAll()
            )
        )
    }

    /**
     * Restores a backup. With [replace] the current data is removed first; otherwise the
     * backup is added next to what is already there. Rows always get fresh ids, and the
     * links between them (session -> characters, message/memory -> session) are remapped.
     */
    suspend fun import(text: String, replace: Boolean): ImportSummary {
        val backup = runCatching { json.decodeFromString(BackupFile.serializer(), text) }
            .getOrElse { throw BackupException("This file is not a readable Roleplay AI backup.") }
        if (backup.app != BackupFile.APP_ID) throw BackupException("This file is not a Roleplay AI backup.")
        if (backup.formatVersion > BackupFile.FORMAT_VERSION) {
            throw BackupException("This backup was made by a newer version of the app.")
        }

        return db.withTransaction {
            if (replace) {
                db.messageDao().deleteAll()
                db.memoryDao().deleteAll()
                db.sessionDao().deleteAll()
                db.characterDao().deleteAll()
                db.lorebookDao().deleteAll()
            }

            val characterIds = HashMap<Long, Long>()
            backup.characters.sortedBy { it.id }.forEach { character ->
                // Pictures are not part of the backup; keep one only if the file is still here.
                val picture = character.avatarImage.takeIf { images.exists(it) }.orEmpty()
                characterIds[character.id] = db.characterDao().upsert(character.copy(id = 0, avatarImage = picture))
            }

            val sessionIds = HashMap<Long, Long>()
            backup.sessions.sortedBy { it.id }.forEach { session ->
                val members = session.characterIds.split(',')
                    .mapNotNull { it.trim().toLongOrNull() }
                    .mapNotNull { characterIds[it] }
                sessionIds[session.id] = db.sessionDao().upsert(
                    session.copy(
                        id = 0,
                        characterId = characterIds[session.characterId] ?: 0,
                        characterIds = members.joinToString(",")
                    )
                )
            }

            var messages = 0
            backup.messages.sortedBy { it.id }.forEach { message ->
                val sessionId = sessionIds[message.sessionId] ?: return@forEach
                // Image files are not part of the backup; keep a reference only if the file is still here.
                val kept = message.imageList().filter { images.exists(it) }
                db.messageDao().insert(message.copy(id = 0, sessionId = sessionId, images = JsonList.encode(kept)))
                messages++
            }

            var memories = 0
            backup.memories.sortedBy { it.id }.forEach { memory ->
                val sessionId = if (memory.sessionId == 0L) 0L else sessionIds[memory.sessionId] ?: return@forEach
                db.memoryDao().upsert(memory.copy(id = 0, sessionId = sessionId))
                memories++
            }

            var lore = 0
            backup.lorebook.sortedBy { it.id }.forEach { entry ->
                val sessionId = if (entry.sessionId == 0L) 0L else sessionIds[entry.sessionId] ?: return@forEach
                db.lorebookDao().upsert(entry.copy(id = 0, sessionId = sessionId))
                lore++
            }

            ImportSummary(characterIds.size, sessionIds.size, messages, memories, lore)
        }
    }
}
