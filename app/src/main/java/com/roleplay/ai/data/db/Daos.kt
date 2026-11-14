package com.roleplay.ai.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CharacterDao {
    @Query("SELECT * FROM characters ORDER BY isFavorite DESC, name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<CharacterEntity>>

    @Query("SELECT * FROM characters ORDER BY id ASC")
    suspend fun getAll(): List<CharacterEntity>

    @Query("SELECT * FROM characters WHERE id = :id")
    suspend fun getById(id: Long): CharacterEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(character: CharacterEntity): Long

    @Delete
    suspend fun delete(character: CharacterEntity)

    @Query("DELETE FROM characters")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM characters")
    suspend fun count(): Int
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    fun observeById(id: Long): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions ORDER BY id ASC")
    suspend fun getAll(): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getById(id: Long): SessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: SessionEntity): Long

    @Update
    suspend fun update(session: SessionEntity)

    @Query("UPDATE sessions SET summary = :summary, summarizedUpTo = :summarizedUpTo, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateSummary(id: Long, summary: String, summarizedUpTo: Int, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE sessions SET document = :document, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateDocument(id: Long, document: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE sessions SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String)

    @Query("UPDATE sessions SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: Long, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM sessions")
    suspend fun deleteAll()
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt ASC, id ASC")
    fun observeForSession(sessionId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt ASC, id ASC")
    suspend fun getForSession(sessionId: Long): List<MessageEntity>

    @Query("SELECT * FROM messages ORDER BY id ASC")
    suspend fun getAll(): List<MessageEntity>

    @Query("SELECT images FROM messages WHERE images != '[]'")
    suspend fun getAllImageLists(): List<String>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getById(id: Long): MessageEntity?

    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Update
    suspend fun update(message: MessageEntity)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: Long)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM messages WHERE sessionId = :sessionId")
    suspend fun countForSession(sessionId: Long): Int
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories WHERE sessionId = :sessionId OR sessionId = 0 ORDER BY createdAt DESC")
    fun observeForSession(sessionId: Long): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE sessionId = :sessionId OR sessionId = 0 ORDER BY createdAt DESC")
    suspend fun getForSession(sessionId: Long): List<MemoryEntity>

    @Query("SELECT * FROM memories ORDER BY id ASC")
    suspend fun getAll(): List<MemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(memory: MemoryEntity): Long

    @Update
    suspend fun update(memory: MemoryEntity)

    @Delete
    suspend fun delete(memory: MemoryEntity)

    @Query("DELETE FROM memories WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: Long)

    @Query("DELETE FROM memories")
    suspend fun deleteAll()
}

@Dao
interface LorebookDao {
    @Query("SELECT * FROM lorebook ORDER BY name COLLATE NOCASE ASC, id ASC")
    fun observeAll(): Flow<List<LorebookEntity>>

    @Query("SELECT * FROM lorebook ORDER BY id ASC")
    suspend fun getAll(): List<LorebookEntity>

    @Query("SELECT * FROM lorebook WHERE enabled = 1 ORDER BY id ASC")
    suspend fun getEnabled(): List<LorebookEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: LorebookEntity): Long

    @Delete
    suspend fun delete(entry: LorebookEntity)

    @Query("DELETE FROM lorebook")
    suspend fun deleteAll()
}
