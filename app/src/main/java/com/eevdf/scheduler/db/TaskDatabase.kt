package com.eevdf.scheduler.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.eevdf.scheduler.model.Task

@Database(entities = [Task::class], version = 11, exportSchema = false)
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

        /**
         * version 8 → 9 — replace timerDeadlineEpoch with the two-field epoch model.
         *
         * New model:
         *   accumulatedMs  = total ms consumed across all sessions before the current one
         *   startTimeEpoch = System.currentTimeMillis() when Start was last pressed (0 = paused)
         *   liveElapsedMs  = accumulatedMs + (now − startTimeEpoch)   // while Running
         *   remainingMs    = timeSliceSeconds * 1000 − liveElapsedMs
         *
         * Back-fill maths for existing rows:
         *
         *   Paused (timerDeadlineEpoch = 0, isRunning = 0):
         *     accumulatedMs  = (timeSliceSeconds − remainingSeconds) * 1000
         *     startTimeEpoch = 0
         *
         *   Running (timerDeadlineEpoch > 0, isRunning = 1, deadline still in future):
         *     accumulatedMs  = (timeSliceSeconds − remainingSeconds) * 1000
         *     startTimeEpoch = timerDeadlineEpoch − remainingSeconds * 1000
         *
         *   Running but deadline already passed (deadline ≤ now):
         *     Treat as expired → remainingSeconds=0, accumulatedMs=sliceMs, isRunning=0
         *
         * Note: SQLite cannot DROP columns, so timerDeadlineEpoch remains in the schema
         * as a permanently ignored legacy column. It will be 0 for all new rows.
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 1. Add the two new columns
                database.execSQL(
                    "ALTER TABLE tasks ADD COLUMN accumulatedMs  INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE tasks ADD COLUMN startTimeEpoch INTEGER NOT NULL DEFAULT 0"
                )

                // 2. Back-fill accumulatedMs for every row
                database.execSQL(
                    "UPDATE tasks SET accumulatedMs = (timeSliceSeconds - remainingSeconds) * 1000"
                )

                // 3. Back-fill startTimeEpoch for tasks whose deadline is still in the future
                database.execSQL("""
                    UPDATE tasks
                       SET startTimeEpoch = timerDeadlineEpoch - remainingSeconds * 1000
                     WHERE isRunning = 1
                       AND timerDeadlineEpoch > (strftime('%s','now') * 1000)
                """.trimIndent())

                // 4. Tasks running but deadline already passed → expire them cleanly
                database.execSQL("""
                    UPDATE tasks
                       SET isRunning        = 0,
                           remainingSeconds = 0,
                           accumulatedMs    = timeSliceSeconds * 1000
                     WHERE isRunning = 1
                       AND startTimeEpoch = 0
                """.trimIndent())
            }
        }

        /**
         * version 9 → 10 — CPU bandwidth quota (mirrors Linux cgroup cpu.cfs_quota_us).
         *
         *   quotaSeconds       — max runtime per period; 0 = unlimited.
         *   quotaPeriodSeconds — rolling window length; default 86400 (1 day).
         *   quotaPeriodStartEpoch — epoch ms when current accounting period started; 0 = not started.
         *   quotaUsedSeconds   — seconds consumed in the current period.
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE tasks ADD COLUMN quotaSeconds         INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE tasks ADD COLUMN quotaPeriodSeconds   INTEGER NOT NULL DEFAULT 86400")
                database.execSQL("ALTER TABLE tasks ADD COLUMN quotaPeriodStartEpoch INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE tasks ADD COLUMN quotaUsedSeconds     INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * version 10 → 11 — schema repair / idempotent quota columns.
         *
         * A previous debug build may have stamped version 10 onto a database that
         * is missing some or all of the four quota columns (hash mismatch crash).
         * This migration adds each column only when it is absent by inspecting
         * PRAGMA table_info — SQLite has no "ALTER TABLE … ADD COLUMN IF NOT EXISTS".
         */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                val existing = mutableSetOf<String>()
                val cursor = database.query("PRAGMA table_info(tasks)")
                cursor.use {
                    val nameIdx = it.getColumnIndex("name")
                    while (it.moveToNext()) existing.add(it.getString(nameIdx))
                }

                if ("quotaSeconds" !in existing)
                    database.execSQL("ALTER TABLE tasks ADD COLUMN quotaSeconds          INTEGER NOT NULL DEFAULT 0")
                if ("quotaPeriodSeconds" !in existing)
                    database.execSQL("ALTER TABLE tasks ADD COLUMN quotaPeriodSeconds    INTEGER NOT NULL DEFAULT 86400")
                if ("quotaPeriodStartEpoch" !in existing)
                    database.execSQL("ALTER TABLE tasks ADD COLUMN quotaPeriodStartEpoch INTEGER NOT NULL DEFAULT 0")
                if ("quotaUsedSeconds" !in existing)
                    database.execSQL("ALTER TABLE tasks ADD COLUMN quotaUsedSeconds      INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getDatabase(context: Context): TaskDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    TaskDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
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