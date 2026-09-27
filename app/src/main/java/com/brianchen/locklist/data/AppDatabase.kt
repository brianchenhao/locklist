package com.brianchen.locklist.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Task::class], version = 7, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN recurring INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN status TEXT NOT NULL DEFAULT 'more'")
                db.execSQL("ALTER TABLE tasks ADD COLUMN notes TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE tasks ADD COLUMN imagePaths TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE tasks SET status = 'done' WHERE done = 1")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN area TEXT NOT NULL DEFAULT 'personal'")
            }
        }

        /**
         * Replaces the old phone-clock watermark with per-row flags. Anything changed after the
         * last completed sync (or everything, if the phone never synced) is marked dirty, so it
         * is compared with the server instead of being dropped. Of those, rows that existed at
         * the last sync were on the server then (the old sync pushed every change, and its
         * re-pull stamps pushed rows later than the watermark): they count as confirmed, so a
         * web delete made since still wins. Only rows created after it count as never pushed.
         */
        private fun migration5to6(lastSync: Long) = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN dirty INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tasks ADD COLUMN syncedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tasks ADD COLUMN remoteCompletedAt TEXT")
                db.execSQL("ALTER TABLE tasks ADD COLUMN remoteUpdatedAt TEXT")
                if (lastSync <= 0L) {
                    db.execSQL("UPDATE tasks SET dirty = 1, syncedAt = 0")
                } else {
                    db.execSQL(
                        "UPDATE tasks SET dirty = 1, " +
                            "syncedAt = CASE WHEN createdAt <= ? THEN ? ELSE 0 END WHERE updatedAt > ?",
                        arrayOf<Any>(lastSync, lastSync, lastSync)
                    )
                    db.execSQL(
                        "UPDATE tasks SET syncedAt = MAX(updatedAt, 1) WHERE updatedAt <= ?",
                        arrayOf<Any>(lastSync)
                    )
                }
            }
        }

        /**
         * Adds the merge base and the last unconfirmed push. Both start empty: the next pull
         * fills the base of every clean row, and a row without one falls back to "newer wins".
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN remoteBase TEXT")
                db.execSQL("ALTER TABLE tasks ADD COLUMN pendingSent TEXT")
            }
        }

        fun create(context: Context): AppDatabase {
            // Read before building: the migration needs the old watermark, and nothing
            // writes this key any more.
            val lastSync = context.applicationContext
                .getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_SYNC, 0L)
            return Room.databaseBuilder(context, AppDatabase::class.java, "locklist.db")
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    migration5to6(lastSync),
                    MIGRATION_6_7
                )
                .build()
        }

        private const val SYNC_PREFS = "locklist_sync"
        private const val KEY_LAST_SYNC = "lastSync"
    }
}
