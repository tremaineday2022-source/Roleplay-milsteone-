package com.roleplay.ai

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.roleplay.ai.data.db.AppDatabase
import com.roleplay.ai.data.db.LorebookEntity
import com.roleplay.ai.data.db.variantList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Builds a database exactly as version 1.0.0 of the app created it (from the exported v1
 * schema), fills it with chats, and opens it with the current code. Room re-validates every
 * table after the migration, so this fails if MIGRATION_1_2 and the entities ever disagree.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var db: AppDatabase? = null

    @After
    fun tearDown() {
        db?.close()
        context.deleteDatabase(AppDatabase.NAME)
    }

    private fun createVersion1Database() {
        val schema = Json.parseToJsonElement(
            File("schemas/com.roleplay.ai.data.db.AppDatabase/1.json").readText()
        ).jsonObject["database"]!!.jsonObject
        val path = context.getDatabasePath(AppDatabase.NAME).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { v1 ->
            schema["entities"]!!.jsonArray.forEach { entity ->
                val table = entity.jsonObject["tableName"]!!.jsonPrimitive.content
                val sql = entity.jsonObject["createSql"]!!.jsonPrimitive.content
                v1.execSQL(sql.replace("\${TABLE_NAME}", table))
            }
            schema["setupQueries"]!!.jsonArray.forEach { v1.execSQL(it.jsonPrimitive.content) }

            v1.execSQL(
                "INSERT INTO characters (id, name, tagline, avatarColor, persona, description, exampleDialogue, greeting, isFavorite, createdAt) " +
                    "VALUES (1, 'Mira', 'A thief', 4284960932, 'Sly.', 'Docks.', '', 'Hello.', 1, 1000)"
            )
            v1.execSQL(
                "INSERT INTO sessions (id, characterId, title, summary, summarizedUpTo, createdAt, updatedAt) " +
                    "VALUES (7, 1, 'Mira', 'They met.', 2, 1000, 2000)"
            )
            v1.execSQL("INSERT INTO messages (id, sessionId, role, content, createdAt) VALUES (1, 7, 'assistant', 'Hello.', 1000)")
            v1.execSQL("INSERT INTO messages (id, sessionId, role, content, createdAt) VALUES (2, 7, 'user', 'Hi Mira', 1001)")
            v1.execSQL("INSERT INTO memories (id, sessionId, content, enabled, createdAt) VALUES (1, 7, 'Mira owes Bron money.', 1, 1002)")
            v1.version = 1
        }
    }

    private fun openCurrent(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
            .also { db = it }

    @Test
    fun version1DataSurvivesTheUpgrade() = runBlocking {
        createVersion1Database()
        val current = openCurrent()

        // Opening runs the migration and Room's schema validation.
        assertEquals(2, current.openHelper.writableDatabase.version)

        val character = current.characterDao().getById(1)!!
        assertEquals("Mira", character.name)
        assertEquals("Sly.", character.persona)
        assertTrue(character.isFavorite)

        val session = current.sessionDao().getById(7)!!
        assertEquals("Mira", session.title)
        assertEquals("They met.", session.summary)
        assertEquals(2, session.summarizedUpTo)
        assertEquals("chat", session.mode)
        assertEquals("", session.document)
        assertEquals("", session.characterIds)

        val messages = current.messageDao().getForSession(7)
        assertEquals(listOf("Hello.", "Hi Mira"), messages.map { it.content })
        assertEquals(listOf("assistant", "user"), messages.map { it.role })
        messages.forEach {
            assertEquals("[]", it.variants)
            assertEquals(0, it.variantIndex)
            assertEquals("[]", it.images)
            assertEquals(listOf(it.content), it.variantList())
        }

        val memories = current.memoryDao().getForSession(7)
        assertEquals(listOf("Mira owes Bron money."), memories.map { it.content })

        // The new table exists and works.
        assertTrue(current.lorebookDao().getAll().isEmpty())
        current.lorebookDao().upsert(LorebookEntity(name = "City", keywords = "silverhold", content = "A city."))
        assertEquals("City", current.lorebookDao().observeAll().first().single().name)
    }

    @Test
    fun migratedSchemaMatchesAFreshInstall() {
        createVersion1Database()
        val migrated = tableDefinitions(openCurrent())
        db?.close()
        context.deleteDatabase(AppDatabase.NAME)
        val fresh = tableDefinitions(openCurrent())

        assertEquals(fresh.keys, migrated.keys)
        assertEquals(setOf("characters", "sessions", "messages", "memories", "lorebook"), fresh.keys)
        fresh.forEach { (table, columns) -> assertEquals("columns of $table", columns, migrated[table]) }
    }

    /** table -> set of "name type notnull pk" for every column. */
    private fun tableDefinitions(database: AppDatabase): Map<String, Set<String>> {
        val sqlite = database.openHelper.writableDatabase
        val tables = mutableListOf<String>()
        sqlite.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
                "AND name NOT LIKE 'android_%' AND name != 'room_master_table'"
        ).use { cursor -> while (cursor.moveToNext()) tables += cursor.getString(0) }
        return tables.associateWith { table ->
            val columns = mutableSetOf<String>()
            sqlite.query("PRAGMA table_info(`$table`)").use { cursor ->
                while (cursor.moveToNext()) {
                    columns += "${cursor.getString(1)} ${cursor.getString(2)} notnull=${cursor.getInt(3)} pk=${cursor.getInt(5)}"
                }
            }
            columns
        }
    }
}
