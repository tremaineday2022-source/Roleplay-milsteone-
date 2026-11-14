package com.roleplay.ai.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        CharacterEntity::class,
        SessionEntity::class,
        MessageEntity::class,
        MemoryEntity::class,
        LorebookEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun characterDao(): CharacterDao
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun memoryDao(): MemoryDao
    abstract fun lorebookDao(): LorebookDao

    companion object {
        const val NAME = "roleplay_ai.db"

        /**
         * v1 -> v2: reply variants and image attachments on messages, writing mode and
         * group chats on sessions, and the new lorebook table. Existing rows are kept;
         * never replace this with a destructive migration.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `variants` TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `variantIndex` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `images` TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE `sessions` ADD COLUMN `mode` TEXT NOT NULL DEFAULT 'chat'")
                db.execSQL("ALTER TABLE `sessions` ADD COLUMN `document` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `sessions` ADD COLUMN `characterIds` TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `lorebook` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`keywords` TEXT NOT NULL, " +
                        "`content` TEXT NOT NULL, " +
                        "`enabled` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
            }
        }

        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    NAME
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
