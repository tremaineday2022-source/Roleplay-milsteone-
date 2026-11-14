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

    /** Creates the empty tables exactly as the given schema version did, and returns the handle. */
    private fun createSchema(version: Int): SQLiteDatabase {
        val schema = Json.parseToJsonElement(
            File("schemas/com.roleplay.ai.data.db.AppDatabase/$version.json").readText()
        ).jsonObject["database"]!!.jsonObject
        val path = context.getDatabasePath(AppDatabase.NAME).apply { parentFile?.mkdirs() }
        val db = SQLiteDatabase.openOrCreateDatabase(path, null)
        schema["entities"]!!.jsonArray.forEach { entity ->
            val table = entity.jsonObject["tableName"]!!.jsonPrimitive.content
            val sql = entity.jsonObject["createSql"]!!.jsonPrimitive.content
            db.execSQL(sql.replace("\${TABLE_NAME}", table))
        }
        schema["setupQueries"]!!.jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
        db.version = version
        return db
    }

    /** A version-2 database (app 1.1.x) holding a group chat, a regenerated reply and a lorebook entry. */
    private fun createVersion2Database() {
        createSchema(2).use { v2 ->
            v2.execSQL(
                "INSERT INTO characters (id, name, tagline, avatarColor, persona, description, exampleDialogue, greeting, isFavorite, createdAt) " +
                    "VALUES (1, 'Riko', 'Red Whistle', 4284960932, 'Curious.', 'Orth.', '', 'Hi!', 0, 1000)"
            )
            v2.execSQL(
                "INSERT INTO characters (id, name, tagline, avatarColor, persona, description, exampleDialogue, greeting, isFavorite, createdAt) " +
                    "VALUES (2, 'Reg', 'Robot boy', 4284960932, 'Loyal.', '', '', 'Huh?', 0, 1001)"
            )
            v2.execSQL(
                "INSERT INTO sessions (id, characterId, title, summary, summarizedUpTo, createdAt, updatedAt, mode, document, characterIds) " +
                    "VALUES (3, 1, 'Riko, Reg', 'They left Orth.', 0, 1000, 2000, 'chat', '', '1,2')"
            )
            v2.execSQL(
                "INSERT INTO sessions (id, characterId, title, summary, summarizedUpTo, createdAt, updatedAt, mode, document, characterIds) " +
                    "VALUES (4, 0, 'Story', '', 0, 1000, 2000, 'writing', 'Chapter one.', '')"
            )
            v2.execSQL(
                "INSERT INTO messages (id, sessionId, role, content, createdAt, variants, variantIndex, images) " +
                    "VALUES (1, 3, 'assistant', 'Take two', 1000, '[\"Take one\",\"Take two\"]', 1, '[\"a.jpg\"]')"
            )
            v2.execSQL("INSERT INTO memories (id, sessionId, content, enabled, createdAt) VALUES (1, 3, 'Reg has an arm cannon.', 1, 1002)")
            v2.execSQL("INSERT INTO lorebook (id, name, keywords, content, enabled, createdAt) VALUES (1, 'Orth', 'orth', 'A town on the rim.', 1, 1003)")
        }
    }

    private fun createVersion1Database() {
        createSchema(1).use { v1 ->
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
        }
    }

    private fun openCurrent(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
            .also { db = it }

    @Test
    fun version1DataSurvivesTheUpgrade() = runBlocking {
        createVersion1Database()
        val current = openCurrent()

        // Opening runs both migrations (1 -> 2 -> 3) and Room's schema validation.
        assertEquals(3, current.openHelper.writableDatabase.version)
        assertEquals("", current.characterDao().getById(1)!!.avatarImage)
        assertEquals("", current.sessionDao().getById(7)!!.note)

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
    fun version2DataSurvivesTheUpgradeTo3() = runBlocking {
        createVersion2Database()
        val current = openCurrent()
        assertEquals(3, current.openHelper.writableDatabase.version)

        assertEquals(listOf("Riko", "Reg"), current.characterDao().getAll().map { it.name })
        current.characterDao().getAll().forEach { assertEquals("", it.avatarImage) }

        val group = current.sessionDao().getById(3)!!
        assertEquals("1,2", group.characterIds)
        assertEquals("They left Orth.", group.summary)
        assertEquals("", group.note)
        val document = current.sessionDao().getById(4)!!
        assertEquals("writing", document.mode)
        assertEquals("Chapter one.", document.document)

        val reply = current.messageDao().getById(1)!!
        assertEquals(listOf("Take one", "Take two"), reply.variantList())
        assertEquals(1, reply.variantIndex)
        assertEquals("[\"a.jpg\"]", reply.images)

        assertEquals("Reg has an arm cannon.", current.memoryDao().getForSession(3).single().content)
        assertEquals("A town on the rim.", current.lorebookDao().getAll().single().content)

        // The new columns work.
        current.sessionDao().updateNote(3, "We are on the 2nd layer.")
        assertEquals("We are on the 2nd layer.", current.sessionDao().getById(3)!!.note)
        current.characterDao().upsert(current.characterDao().getById(1)!!.copy(avatarImage = "riko.jpg"))
        assertEquals(listOf("riko.jpg"), current.characterDao().getAllAvatarImages())
    }

    @Test
    fun version2SchemaMigratesToTheSameTablesAsAFreshInstall() {
        createSchema(2).close()
        val migrated = tableDefinitions(openCurrent())
        db?.close()
        context.deleteDatabase(AppDatabase.NAME)
        val fresh = tableDefinitions(openCurrent())
        assertEquals(fresh, migrated)
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
