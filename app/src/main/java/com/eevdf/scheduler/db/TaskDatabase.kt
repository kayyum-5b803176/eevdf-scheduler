package com.eevdf.scheduler.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.eevdf.scheduler.model.Task

@Database(entities = [Task::class], version = 8, exportSchema = false)
abstract class TaskDatabase : RoomDatabase() {

    abstract fun taskDao(): TaskDao

    companion object {
        @Volatile
        private var INSTANCE: TaskDatabase? = null

        /** version 1 → 2: add cgroup hierarchy columns */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE tasks ADD COLUMN parentId TEXT")
                database.execSQL("ALTER TABLE tasks ADD COLUMN isGroup INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE tasks ADD COLUMN isGroupExpanded INTEGER NOT NULL DEFAULT 1")
            }
        }

        private const val DB_NAME = "eevdf_task_database"

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE tasks ADD COLUMN isInterrupt INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** version 3 → 4: add wall-clock deadline for accurate timer across kills / sleep */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE tasks ADD COLUMN timerDeadlineEpoch INTEGER NOT NULL DEFAULT 0")
                // Clear any stale isRunning flags left by a previous crash so the
                // resume logic only fires for tasks that genuinely had an active deadline.
                database.execSQL("UPDATE tasks SET isRunning = 0 WHERE timerDeadlineEpoch = 0")
            }
        }

        /** version 4 → 5: add task type + notification delay */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE tasks ADD COLUMN taskType TEXT NOT NULL DEFAULT 'DEFAULT'")
                database.execSQL("ALTER TABLE tasks ADD COLUMN notificationDelaySeconds INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** version 5 → 6: Notice type rest duration + repeat count */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE tasks ADD COLUMN notificationRestSeconds INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE tasks ADD COLUMN notificationRepeatCount INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** version 6 → 7: pinned CPU share per task (null = auto-float) */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // NULL default = auto-float (not pinned)
                database.execSQL("ALTER TABLE tasks ADD COLUMN pinnedShare INTEGER")
            }
        }

        /** version 7 → 8: auto-calculated internal weight derived from pinnedShare (null = use priority) */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // REAL = SQLite float; NULL = fall back to priority-based weight
                database.execSQL("ALTER TABLE tasks ADD COLUMN internalWeight REAL")
            }
        }

        fun getDatabase(context: Context): TaskDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    TaskDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                    .build()
                INSTANCE = instance
                instance
            }
        }
        /** Returns the on-disk path of the main SQLite database file. */
        fun getDatabaseFile(context: Context): File =
            context.getDatabasePath(DB_NAME)

        /**
         * Flushes the WAL into the main database file so the exported .db is
         * self-contained, then closes and nulls the singleton so Room does not
         * hold any file locks during the copy.
         */
        fun checkpointAndClose(context: Context) {
            synchronized(this) {
                try {
                    INSTANCE?.let { db ->
                        db.openHelper.writableDatabase
                            .execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
                    }
                } catch (_: Exception) { /* ignore if already closed */ }
                INSTANCE?.close()
                INSTANCE = null
            }
        }
    }
}