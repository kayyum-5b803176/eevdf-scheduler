package com.eevdf.data.task
import com.eevdf.data.runlog.RunLogDao

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.eevdf.data.runlog.RunDailySummary
import com.eevdf.data.runlog.RunLogEntry
import com.eevdf.data.runlog.RunMonthlySummary
import com.eevdf.data.task.Task

@Database(
    entities = [
        Task::class,
        RunLogEntry::class,
        RunDailySummary::class,
        RunMonthlySummary::class,
        InterruptReturnEntry::class,
        TaskLoadFactor::class,          // ← new side table
    ],
    version  = 25,                      // ← bumped from 24
    exportSchema = true
)
abstract class TaskDatabase : RoomDatabase() {

    abstract fun taskDao(): TaskDao
    abstract fun runLogDao(): RunLogDao
    abstract fun interruptReturnDao(): InterruptReturnDao
    abstract fun taskLoadFactorDao(): TaskLoadFactorDao   // ← new

    companion object {
        @Volatile
        private var INSTANCE: TaskDatabase? = null

        /** version 1 → 2: add cgroup hierarchy columns */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN parentId TEXT")
                db.execSQL("ALTER TABLE tasks ADD COLUMN isGroup INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tasks ADD COLUMN isGroupExpanded INTEGER NOT NULL DEFAULT 1")
            }
        }

        private const val DB_NAME = "eevdf_task_database"

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN isInterrupt INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** version 3 → 4: add wall-clock deadline for accurate timer across kills / sleep */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN timerDeadlineEpoch INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE tasks SET isRunning = 0 WHERE timerDeadlineEpoch = 0")
            }
        }

        /** version 4 → 5: add task type + notification delay */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN taskType TEXT NOT NULL DEFAULT 'DEFAULT'")
                db.execSQL("ALTER TABLE tasks ADD COLUMN notificationDelaySeconds INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** version 5 → 6: Notice type rest duration + repeat count */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN notificationRestSeconds INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tasks ADD COLUMN notificationRepeatCount INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** version 6 → 7: pinned CPU share per task (null = auto-float) */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN pinnedShare INTEGER")
            }
        }

        /** version 7 → 8: auto-calculated internal weight derived from pinnedShare (null = use priority) */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN internalWeight REAL")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN accumulatedMs  INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tasks ADD COLUMN startTimeEpoch INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE tasks SET accumulatedMs = (timeSliceSeconds - remainingSeconds) * 1000")
                db.execSQL("""
                    UPDATE tasks
                       SET startTimeEpoch = timerDeadlineEpoch - remainingSeconds * 1000
                     WHERE isRunning = 1
                       AND timerDeadlineEpoch > (strftime('%s','now') * 1000)
                """.trimIndent())
                db.execSQL("""
                    UPDATE tasks
                       SET isRunning        = 0,
                           remainingSeconds = 0,
                           accumulatedMs    = timeSliceSeconds * 1000
                     WHERE isRunning = 1
                       AND startTimeEpoch = 0
                """.trimIndent())
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN quotaSeconds         INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tasks ADD COLUMN quotaPeriodSeconds   INTEGER NOT NULL DEFAULT 86400")
                db.execSQL("ALTER TABLE tasks ADD COLUMN quotaPeriodStartEpoch INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tasks ADD COLUMN quotaUsedSeconds     INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val existing = mutableSetOf<String>()
                val cursor = db.query("PRAGMA table_info(tasks)")
                cursor.use {
                    val nameIdx = it.getColumnIndex("name")
                    while (it.moveToNext()) existing.add(it.getString(nameIdx))
                }
                if ("quotaSeconds" !in existing)
                    db.execSQL("ALTER TABLE tasks ADD COLUMN quotaSeconds          INTEGER NOT NULL DEFAULT 0")
                if ("quotaPeriodSeconds" !in existing)
                    db.execSQL("ALTER TABLE tasks ADD COLUMN quotaPeriodSeconds    INTEGER NOT NULL DEFAULT 86400")
                if ("quotaPeriodStartEpoch" !in existing)
                    db.execSQL("ALTER TABLE tasks ADD COLUMN quotaPeriodStartEpoch INTEGER NOT NULL DEFAULT 0")
                if ("quotaUsedSeconds" !in existing)
                    db.execSQL("ALTER TABLE tasks ADD COLUMN quotaUsedSeconds      INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS run_log (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        taskId      TEXT    NOT NULL,
                        startEpoch  INTEGER NOT NULL,
                        durationSecs INTEGER NOT NULL,
                        prevTaskId  TEXT,
                        weekDay     INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_run_log_taskId     ON run_log(taskId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_run_log_startEpoch ON run_log(startEpoch)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_run_log_prevTaskId ON run_log(prevTaskId)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS run_daily (
                        taskId       TEXT    NOT NULL,
                        dayEpoch     INTEGER NOT NULL,
                        totalSecs    INTEGER NOT NULL,
                        runCount     INTEGER NOT NULL,
                        switchInCount INTEGER NOT NULL DEFAULT 0,
                        weekDay      INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(taskId, dayEpoch)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_run_daily_dayEpoch ON run_daily(dayEpoch)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_run_daily_taskId   ON run_daily(taskId)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS run_monthly (
                        taskId      TEXT    NOT NULL,
                        monthEpoch  INTEGER NOT NULL,
                        totalSecs   INTEGER NOT NULL,
                        runCount    INTEGER NOT NULL,
                        PRIMARY KEY(taskId, monthEpoch)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_run_monthly_monthEpoch ON run_monthly(monthEpoch)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_run_monthly_taskId     ON run_monthly(taskId)")
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS run_log")
                db.execSQL("DROP TABLE IF EXISTS run_daily")
                db.execSQL("DROP TABLE IF EXISTS run_monthly")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `run_log` (
                        `id`           INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `taskId`       TEXT    NOT NULL,
                        `startEpoch`   INTEGER NOT NULL,
                        `durationSecs` INTEGER NOT NULL,
                        `prevTaskId`   TEXT,
                        `weekDay`      INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_run_log_taskId`     ON `run_log`(`taskId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_run_log_startEpoch` ON `run_log`(`startEpoch`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_run_log_prevTaskId` ON `run_log`(`prevTaskId`)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `run_daily` (
                        `taskId`        TEXT    NOT NULL,
                        `dayEpoch`      INTEGER NOT NULL,
                        `totalSecs`     INTEGER NOT NULL,
                        `runCount`      INTEGER NOT NULL,
                        `switchInCount` INTEGER NOT NULL,
                        `weekDay`       INTEGER NOT NULL,
                        PRIMARY KEY(`taskId`, `dayEpoch`)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_run_daily_dayEpoch` ON `run_daily`(`dayEpoch`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_run_daily_taskId`   ON `run_daily`(`taskId`)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `run_monthly` (
                        `taskId`     TEXT    NOT NULL,
                        `monthEpoch` INTEGER NOT NULL,
                        `totalSecs`  INTEGER NOT NULL,
                        `runCount`   INTEGER NOT NULL,
                        PRIMARY KEY(`taskId`, `monthEpoch`)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_run_monthly_monthEpoch` ON `run_monthly`(`monthEpoch`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_run_monthly_taskId`     ON `run_monthly`(`taskId`)")
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN interruptSlot TEXT NOT NULL DEFAULT 'A'")
            }
        }

        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS tasks_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL DEFAULT '',
                        timeSliceSeconds INTEGER NOT NULL DEFAULT 1500,
                        remainingSeconds INTEGER NOT NULL DEFAULT 1500,
                        vruntime REAL NOT NULL DEFAULT 0.0,
                        totalRunTime INTEGER NOT NULL DEFAULT 0,
                        priority INTEGER NOT NULL DEFAULT 4,
                        isCompleted INTEGER NOT NULL DEFAULT 0,
                        isRunning INTEGER NOT NULL DEFAULT 0,
                        accumulatedMs INTEGER NOT NULL DEFAULT 0,
                        startTimeEpoch INTEGER NOT NULL DEFAULT 0,
                        runCount INTEGER NOT NULL DEFAULT 0,
                        virtualDeadline REAL NOT NULL DEFAULT 0.0,
                        isGroup INTEGER NOT NULL DEFAULT 0,
                        parentId TEXT,
                        isGroupExpanded INTEGER NOT NULL DEFAULT 1,
                        taskType TEXT NOT NULL DEFAULT 'DEFAULT',
                        color INTEGER NOT NULL DEFAULT 0,
                        eligibleTime REAL NOT NULL DEFAULT 0.0,
                        lag REAL NOT NULL DEFAULT 0.0,
                        notificationDelaySeconds INTEGER NOT NULL DEFAULT 0,
                        notificationRestSeconds INTEGER NOT NULL DEFAULT 0,
                        notificationRepeatCount INTEGER NOT NULL DEFAULT 0,
                        pinnedShare REAL,
                        internalWeight REAL,
                        quotaSeconds INTEGER NOT NULL DEFAULT 0,
                        quotaPeriodSeconds INTEGER NOT NULL DEFAULT 86400,
                        quotaPeriodStartEpoch INTEGER NOT NULL DEFAULT 0,
                        quotaUsedSeconds INTEGER NOT NULL DEFAULT 0,
                        isInterrupt INTEGER NOT NULL DEFAULT 0,
                        interruptSlot TEXT NOT NULL DEFAULT 'A',
                        category TEXT NOT NULL DEFAULT 'General',
                        createdAt INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO tasks_new SELECT
                        id, name, description, timeSliceSeconds, remainingSeconds,
                        vruntime, totalRunTime, priority, isCompleted, isRunning,
                        accumulatedMs, startTimeEpoch, runCount, virtualDeadline,
                        isGroup, parentId, isGroupExpanded, taskType,
                        color, eligibleTime, lag,
                        notificationDelaySeconds, notificationRestSeconds, notificationRepeatCount,
                        CAST(pinnedShare AS REAL), internalWeight,
                        quotaSeconds, quotaPeriodSeconds, quotaPeriodStartEpoch, quotaUsedSeconds,
                        isInterrupt, interruptSlot, category, createdAt
                    FROM tasks
                """.trimIndent())
                db.execSQL("DROP TABLE tasks")
                db.execSQL("ALTER TABLE tasks_new RENAME TO tasks")
            }
        }

        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN schedulerClass TEXT NOT NULL DEFAULT 'fair_sched_class'")
                db.execSQL("ALTER TABLE tasks ADD COLUMN dlRuntimeSeconds INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tasks ADD COLUMN dlDeadlineSeconds INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tasks ADD COLUMN dlPeriodSeconds INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN dlPeriodStartEpoch INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tasks ADD COLUMN dlRuntimeUsedSeconds INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN rtPriority INTEGER NOT NULL DEFAULT 50")
                db.execSQL("ALTER TABLE tasks ADD COLUMN rtPolicy TEXT NOT NULL DEFAULT 'RR'")
                db.execSQL("ALTER TABLE tasks ADD COLUMN rtActiveDays INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tasks ADD COLUMN rtActivationHour INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tasks ADD COLUMN rtActivationMinute INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tasks ADD COLUMN rtActivationSecond INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tasks ADD COLUMN rtSliceTimeoutSeconds INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN notificationResumeType TEXT NOT NULL DEFAULT 'MIDDLE'")
            }
        }

        /** version 19 → 20: load factor + load average columns */
        private val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN loadFactor REAL NOT NULL DEFAULT 1.0")
                db.execSQL("ALTER TABLE tasks ADD COLUMN loadAverage REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE tasks ADD COLUMN loadLastUpdateEpoch INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** version 20 → 21: per-tab, per-slot interrupt return-to table */
        private val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS interrupt_return (" +
                        "cellKey TEXT NOT NULL PRIMARY KEY, " +
                        "tab TEXT NOT NULL, " +
                        "slot TEXT NOT NULL, " +
                        "taskId TEXT NOT NULL)"
                )
            }
        }

        /** version 21 → 22: load factor inheritance flag */
        private val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN loadFactorInherited INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** version 22 → 23: time slice inheritance flag */
        private val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN timeSliceInherited INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * version 23 → 24 — Human-effort load factor side table.
         *
         * Adds [task_load_factor] to hold the three NASA-TLX-derived slider values
         * (Cognitive, Physical, Emotional) and an enabled flag per task.
         *
         * No columns are added to [tasks]: the computed loadFactor REAL already
         * exists (added in v19) and continues to carry the result for the EWMA.
         * The slider inputs live entirely in the new side table so the tasks schema
         * stays lean and the feature can be toggled independently per task.
         *
         * Existing tasks get no row here (missing row = disabled / midpoint default),
         * which is the correct backward-compatible behaviour: they continue to use
         * loadFactor = 1.0 (v19 default) until the user opens the edit form and
         * explicitly enables the new sliders.
         */
        private val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `task_load_factor` (
                        `taskId`    TEXT    NOT NULL PRIMARY KEY,
                        `cognitive` INTEGER NOT NULL DEFAULT 4,
                        `physical`  INTEGER NOT NULL DEFAULT 4,
                        `emotional` INTEGER NOT NULL DEFAULT 4,
                        `enabled`   INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        /**
         * version 24 → 25 — Per-dimension EWMA state in the tasks table.
         *
         * Three NASA-TLX-derived dimensions each carry their own EWMA with a
         * physiologically-calibrated decay constant (see LoadAverage.kt):
         *   Cognitive  τ =  6 h — recovers within a working day
         *   Physical   τ = 18 h — recovers with overnight sleep
         *   Emotional  τ = 60 h — lingers across 2–3 days
         *
         * All six columns default to 0.  Existing tasks rebuild per-dimension
         * history from their next run; the combined loadAverage column continues
         * to serve as the single stats-bar output during the rebuild window.
         */
        private val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN loadAvgCognitive       REAL    NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE tasks ADD COLUMN loadLastUpdateCognitive INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tasks ADD COLUMN loadAvgPhysical        REAL    NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE tasks ADD COLUMN loadLastUpdatePhysical  INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tasks ADD COLUMN loadAvgEmotional       REAL    NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE tasks ADD COLUMN loadLastUpdateEmotional INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getDatabase(context: Context): TaskDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    TaskDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                        MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                        MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13,
                        MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17,
                        MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21,
                        MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25,
                    )
                    .build()
                INSTANCE = instance
                instance
            }
        }

        fun getDatabaseFile(context: Context): File = context.getDatabasePath(DB_NAME)

        @Suppress("UNUSED_PARAMETER")
        fun checkpointWal(context: Context) {
            synchronized(this) {
                try {
                    INSTANCE?.openHelper?.writableDatabase
                        ?.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
                } catch (_: Exception) { /* ignore */ }
            }
        }

        @Suppress("UNUSED_PARAMETER")
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
