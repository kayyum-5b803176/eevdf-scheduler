# Database — Schema & Migrations

**File:** `db/TaskDatabase.kt`  
**Type:** Room `RoomDatabase`, abstract singleton  
**Current version:** 14  
**DB file name:** `eevdf_task_database`

---

## Tables

| Table | Entity class | Purpose |
|-------|-------------|---------|
| `tasks` | `Task` | All tasks and group containers |
| `run_log` | `RunLogEntry` | Per-run session detail (Tier 1 analytics) |
| `run_daily` | `RunDailySummary` | Daily aggregates (Tier 2 analytics) |
| `run_monthly` | `RunMonthlySummary` | Monthly aggregates (Tier 3 analytics) |

---

## Migration History

### v1 → v2: cgroup hierarchy columns
Adds the three columns needed for group nesting:
```sql
ALTER TABLE tasks ADD COLUMN parentId TEXT;
ALTER TABLE tasks ADD COLUMN isGroup INTEGER NOT NULL DEFAULT 0;
ALTER TABLE tasks ADD COLUMN isGroupExpanded INTEGER NOT NULL DEFAULT 1;
```

### v2 → v3: interrupt flag
```sql
ALTER TABLE tasks ADD COLUMN isInterrupt INTEGER NOT NULL DEFAULT 0;
```

### v3 → v4: wall-clock deadline (now superseded by v8)
Added `timerDeadlineEpoch` for accurate timer across kills / sleep. Also clears stale `isRunning` flags left by a previous crash.

> `timerDeadlineEpoch` was later replaced by the two-field model in v8. SQLite cannot DROP columns, so the column remains in the schema as a permanently ignored legacy field (always 0 for new rows).

### v4 → v5: task type + notification delay
```sql
ALTER TABLE tasks ADD COLUMN taskType TEXT NOT NULL DEFAULT 'DEFAULT';
ALTER TABLE tasks ADD COLUMN notificationDelaySeconds INTEGER NOT NULL DEFAULT 0;
```

### v5 → v6: NOTIFICATION rest + repeat
```sql
ALTER TABLE tasks ADD COLUMN notificationRestSeconds INTEGER NOT NULL DEFAULT 0;
ALTER TABLE tasks ADD COLUMN notificationRepeatCount INTEGER NOT NULL DEFAULT 0;
```

### v6 → v7: pinned CPU share
```sql
ALTER TABLE tasks ADD COLUMN pinnedShare INTEGER;  -- NULL = auto-float
```

### v7 → v8: internal weight for pinned tasks
```sql
ALTER TABLE tasks ADD COLUMN internalWeight REAL;  -- NULL = use priority
```

### v8 → v9: two-field epoch timer model 
Most complex migration. Replaces the single `timerDeadlineEpoch` column with two columns that together represent the full timer state without ambiguity.

New columns:
```sql
ALTER TABLE tasks ADD COLUMN accumulatedMs  INTEGER NOT NULL DEFAULT 0;
ALTER TABLE tasks ADD COLUMN startTimeEpoch INTEGER NOT NULL DEFAULT 0;
```

Back-fill logic:
```sql
-- All rows: compute elapsed from old remaining
UPDATE tasks SET accumulatedMs = (timeSliceSeconds - remainingSeconds) * 1000;

-- Running rows with deadline still in future: restore startTimeEpoch
UPDATE tasks
   SET startTimeEpoch = timerDeadlineEpoch - remainingSeconds * 1000
 WHERE isRunning = 1
   AND timerDeadlineEpoch > (strftime('%s','now') * 1000);

-- Running rows whose deadline already passed: expire them cleanly
UPDATE tasks
   SET isRunning = 0, remainingSeconds = 0, accumulatedMs = timeSliceSeconds * 1000
 WHERE isRunning = 1 AND startTimeEpoch = 0;
```

The `liveElapsedMs` formula after this migration:
```
liveElapsedMs = accumulatedMs + (now − startTimeEpoch)   // while Running
liveElapsedMs = accumulatedMs                             // while Paused
```

### v9 → v10: CPU bandwidth quota
```sql
ALTER TABLE tasks ADD COLUMN quotaSeconds          INTEGER NOT NULL DEFAULT 0;
ALTER TABLE tasks ADD COLUMN quotaPeriodSeconds    INTEGER NOT NULL DEFAULT 86400;
ALTER TABLE tasks ADD COLUMN quotaPeriodStartEpoch INTEGER NOT NULL DEFAULT 0;
ALTER TABLE tasks ADD COLUMN quotaUsedSeconds      INTEGER NOT NULL DEFAULT 0;
```

### v10 → v11: schema repair
A debug build stamped version 10 onto a database missing some quota columns. This migration uses `PRAGMA table_info` to check which columns exist and adds only the missing ones — SQLite lacks `ADD COLUMN IF NOT EXISTS`.

### v11 → v12: RunLog tiered analytics tables
Creates the three analytics tables (`run_log`, `run_daily`, `run_monthly`) with initial indices. Note: this migration had incorrect index names (`idx_*` instead of `index_*`) and incorrect DEFAULT clauses — corrected in v13.

### v12 → v13: analytics schema repair
Drops and recreates all three analytics tables with the exact schema Room generates (correct `index_*` naming, no DEFAULT on `weekDay`/`switchInCount`). Safe because analytics tables are at most 30 days old.

### v13 → v14: dual interrupt slots (INT-A / INT-B)
```sql
ALTER TABLE tasks ADD COLUMN interruptSlot TEXT NOT NULL DEFAULT 'A';
```
Existing interrupt tasks default to slot "A".

---

## TaskDao

**File:** `db/TaskDao.kt`

### Queries

| Method | Return | Description |
|--------|--------|-------------|
| `getAllTasks()` | `LiveData<List<Task>>` | All tasks ordered by `virtualDeadline ASC, priority DESC` |
| `getActiveTasks()` | `LiveData<List<Task>>` | Non-completed, ordered by `virtualDeadline ASC` |
| `getCompletedTasks()` | `LiveData<List<Task>>` | Completed, ordered by `createdAt DESC` |
| `getTaskById(id)` | `Task?` | Single task lookup by ID |
| `insert(task)` | — | `REPLACE` conflict strategy |
| `update(task)` | — | Update single task |
| `updateAll(tasks)` | — | Batch update (single transaction) — used for weight sync |
| `delete(task)` | — | Delete single task |
| `clearCompleted()` | — | Delete all completed tasks |
| `stopAllRunning()` | — | `UPDATE tasks SET isRunning = 0` |
| `getActiveTasksSync()` | `List<Task>` | Non-completed (suspend, for coroutine use) |
| `getChildrenOf(parentId)` | `List<Task>` | Direct children of a group |
| `getActiveGroups()` | `LiveData<List<Task>>` | All active group tasks |
| `getInterruptTask()` | `Task?` | INT-A interrupt destination |
| `getInterruptTaskB()` | `Task?` | INT-B interrupt destination |
| `getAnyInterruptTask()` | `Task?` | First interrupt task in any slot |
| `getRunningTask()` | `Task?` | Task with `isRunning=1 AND startTimeEpoch>0` |
| `clearAllInterrupts()` | — | Set `isInterrupt=0` on all tasks |
| `clearInterruptsForSlot(slot)` | — | Clear interrupt flag for a specific slot |
| `getAllTasksForBackup()` | `List<Task>` | All tasks (active + completed), ordered by `createdAt` |
| `getAllTasksForStats()` | `List<Task>` | All tasks, ordered by `totalRunTime DESC` |
| `deleteAllTasks()` | — | Truncate tasks table (used by backup restore) |

### Notes
- `getRunningTask()` uses `startTimeEpoch > 0` as the canonical "timer is active" guard, not `isRunning` alone. A crash could leave `isRunning=1` with `startTimeEpoch=0`; the epoch guard prevents phantom resumes.
- `updateAll` runs in a single Room transaction, safe for batch weight syncs.

---

## RunLogDao

**File:** `db/RunLogDao.kt`

### run_log queries

| Method | Description |
|--------|-------------|
| `insertLog(entry)` | Insert one run log row |
| `getLatestEntry()` | Most recent row (used to detect previous task on switch) |
| `getEntriesOlderThan(cutoffEpoch)` | For 30-day compaction |
| `deleteEntriesOlderThan(cutoffEpoch)` | Delete after compaction |
| `count()` | Total row count (for 100K cap) |
| `deleteOldest(n)` | Delete oldest N rows to enforce cap |
| `getEntriesForTask(taskId, fromEpoch)` | Per-task session history |
| `getEntriesInRange(fromEpoch, toEpoch)` | Cross-task range query |

### run_daily queries

| Method | Description |
|--------|-------------|
| `upsertDaily(summary)` | Insert or replace daily aggregate |
| `getDailyForTask(taskId)` | All daily rows for one task |
| `getDailyInRange(fromEpoch)` | Daily rows from a start date |
| `getDailyOlderThan(cutoffEpoch)` | For 365-day compaction |
| `deleteDailyOlderThan(cutoffEpoch)` | Delete after compaction |
| `countDaily()` | Total row count (for 500K cap) |
| `deleteOldestDaily(n)` | Delete oldest N rows |
| `getWeekdayTotalsForTask(taskId)` | Weekday pattern for one task |
| `getGlobalWeekdayTotals()` | Weekday pattern across all tasks |

### run_monthly queries

| Method | Description |
|--------|-------------|
| `upsertMonthly(summary)` | Insert or replace monthly aggregate |
| `getMonthlyForTask(taskId)` | Monthly history for one task |
| `getMonthlyInRange(fromEpoch)` | Monthly rows from a start date |

### Helper data class

`WeekdayTotal(weekDay, totalSecs, runCount)` — used by `@Query` projections for day-of-week aggregates.

---

## TaskDatabase — Lifecycle Helpers

### `getDatabase(context): TaskDatabase`

Thread-safe singleton via `synchronized`. Registers all 13 migrations.

### `getDatabaseFile(context): File`

Returns the on-disk path of the main `.db` file. Used by `BackupManager` for export.

### `checkpointAndClose(context)`

1. Executes `PRAGMA wal_checkpoint(TRUNCATE)` — flushes the WAL into the main db file so the exported `.db` is self-contained.
2. Closes the Room instance and nulls the singleton.

**Must be called before copying the database file for export.** Otherwise the WAL file is not merged and the copy may be incomplete.

---

## Adding a new column (how-to)

1. Add the field to `Task.kt` with a sensible default.
2. Increment `version` in `@Database(version = N)`.
3. Add a `MIGRATION_N_M` object with the `ALTER TABLE` statement.
4. Register it in `getDatabase()` → `addMigrations(...)`.
5. If the column may already exist in some DB versions (like v10→v11), use `PRAGMA table_info` to check first.
