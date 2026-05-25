# Repository Layer

**Files:**
- `db/TaskRepository.kt` — task CRUD + cgroup-aware scheduling + quota accounting
- `db/RunLogRepository.kt` — three-tier compacting analytics log

---

## TaskRepository

**Constructor:** `TaskRepository(dao: TaskDao, context: Context)`

The single source of truth for all data mutations. The ViewModel never calls the DAO directly — it always goes through the repository.

### LiveData streams

| Property | Type | Source query |
|----------|------|-------------|
| `allTasks` | `LiveData<List<Task>>` | All tasks ordered by `virtualDeadline ASC, priority DESC` |
| `activeTasks` | `LiveData<List<Task>>` | Non-completed, ordered by `virtualDeadline ASC` |
| `completedTasks` | `LiveData<List<Task>>` | Completed, ordered by `createdAt DESC` |
| `activeGroups` | `LiveData<List<Task>>` | Active group tasks, ordered by name |

---

### `insert(task)`

Inserts a task and recalculates EEVDF for all active tasks:

1. Load all current active tasks.
2. Add the new task to the in-memory list.
3. `EEVDFScheduler.recalculate(existing)` — sets lag, eligibleTime, virtualDeadline on all.
4. `dao.insert(task)`.
5. `dao.update(it)` for every task in the list (to persist the recalculated fields).

---

### `update(task)`

Simple passthrough: `dao.update(task)`. EEVDF fields must already be correct — no recalculation.

### `updateBatch(tasks)`

Batch-updates a list of tasks in a single transaction. Used after `EEVDFScheduler.syncPinnedWeights()` to persist only the tasks whose `internalWeight` changed.

---

### `delete(task)`

Recursive delete:
1. Call `deleteDescendants(task.id)` — depth-first traversal deletes all children and their children.
2. `dao.delete(task)`.

**`deleteDescendants(parentId)`** (private):
- `dao.getChildrenOf(parentId)` — get direct children.
- For each group child, recurse.
- `dao.delete(child)` for every child.

---

### `markCompleted(task)`

```kotlin
dao.update(task.copy(isCompleted = true, isRunning = false, remainingSeconds = 0))
```

---

### `updateVruntimeAfterRun(task, secondsRan)` 

The most important write path. Called after every timer session.

**Step 1 — Record analytics:**
```kotlin
if (secondsRan > 0 && !task.isGroup) {
    runLog.recordRun(task.id, startEpoch, secondsRan)
}
```

**Step 2 — Update leaf task:**
```kotlin
EEVDFScheduler.updateVruntime(task, secondsRan)  // vruntime++, runCount++, totalRunTime++
applyQuotaAccounting(task, secondsRan)
dao.update(task)
```

**Step 3 — Propagate up ancestor chain (cgroup credit):**
```kotlin
var parentId = task.parentId
while (parentId != null) {
    val parent = dao.getTaskById(parentId) ?: break
    parent.totalRunTime += secondsRan
    parent.vruntime     += secondsRan / parent.weight
    applyQuotaAccounting(parent, secondsRan)
    dao.update(parent)
    parentId = parent.parentId
}
```
This mirrors Linux's cgroup hierarchy: every time a leaf task runs, its CPU time is credited all the way up to the root group.

**Step 4 — Global recalculate:**
```kotlin
val allActive = dao.getActiveTasksSync()
EEVDFScheduler.recalculate(allActive)
allActive.forEach { dao.update(it) }
```

---

### `applyQuotaAccounting(task, secondsRan)` (private)

Rolls the quota period forward if needed, then credits `secondsRan`.

**Period management:**
- If `quotaPeriodStartEpoch == 0` (first run ever): open a fresh period at `now`.
- Snapshots `currentQuotaUsed` (decayed value at this instant) to eliminate floating-point drift.
- Resets `quotaPeriodStartEpoch = now`.
- Sets `quotaUsedSeconds = decayedNow + secondsRan`.

This means every run starts a new decay window from the correct baseline.

---

### `selectNextTask(): Task?` (cgroup-aware)

Hierarchical EEVDF selection — mirrors Linux's hierarchical scheduling:

```kotlin
selectNextCgroup(allActive, parentId = null)
```

**`selectNextCgroup(all, parentId, visited)`** (private, recursive):
1. Filter tasks at this level: `parentId == parentId`, non-completed, non-running, not already visited.
2. `EEVDFScheduler.recalculate(level)` — ensure fields are current.
3. `EEVDFScheduler.selectNext(level)` — get the EEVDF winner.
4. If winner is a **group**: add to `visited`, recurse into `selectNextCgroup(all, winner.id, visited)`.
5. If that recursion returns null (empty group): fall back with `selectNextCgroup(all, parentId, visited)` — try the next sibling at the same level.
6. If winner is a **leaf task**: return it directly.

The `visited` set prevents infinite recursion when a group has no eligible children.

---

### `getScheduleOrder(): List<Task>`

Loads all active tasks and delegates to `EEVDFScheduler.getScheduleOrder(activeTasks)`.

---

### Interrupt task management

| Method | Description |
|--------|-------------|
| `getRunningTask()` | Task with `isRunning=1 AND startTimeEpoch>0` |
| `getInterruptTask()` | INT-A destination |
| `getInterruptTaskB()` | INT-B destination |
| `setInterruptTask(task, slot)` | Atomically clear slot, then mark task as that slot's interrupt |
| `clearInterruptTask(slot)` | Clear interrupt for one slot |
| `clearAllInterruptTasks()` | Clear both slots |

---

### Backup / Restore

| Method | Description |
|--------|-------------|
| `getAllTasksForBackup()` | All tasks (active + completed) for JSON export |
| `restoreFromBackup(tasks)` | `deleteAllTasks()` then re-insert each task atomically in one IO coroutine |

---

## RunLogRepository

**Constructor:** `RunLogRepository(context: Context)`

Manages the three-tier compacting log. All compaction is triggered lazily on `recordRun` but throttled to once every 24 hours.

### `recordRun(taskId, startEpoch, durationSecs)`

1. `dao.getLatestEntry()` — get the most recent log row.
2. `resolvePrevTask(prevEntry, startEpoch)` — determine if previous run counts as a context switch.
3. `weekDayOf(startEpoch)` — compute day-of-week.
4. `dao.insertLog(RunLogEntry(...))` — write the row.
5. Enforce 100K hard cap: `if count > MAX_ROWS: deleteOldest(count - MAX_ROWS)`.
6. `maybeCompact()` — run compaction if 24 hours have elapsed.

---

### `maybeCompact()` (private)

Throttle guard: reads `PREF_LAST_COMPACT` from SharedPreferences. If `now - last < 24h`, returns immediately. Otherwise runs the full pipeline and updates the timestamp.

---

### `compactLogToDaily()` (private)

Compacts `run_log` entries older than 30 days into `run_daily`.

1. `dao.getEntriesOlderThan(cutoff)` — load old rows.
2. Group by `(taskId, startOfDayEpoch)`.
3. For each group: merge into `RunDailySummary` (add to existing row if present).
4. `dao.upsertDaily(merged)` for each group.
5. `dao.deleteEntriesOlderThan(cutoff)`.
6. Enforce 500K daily cap.

**Context-switch count:** Entry counts as a switch-in if `prevTaskId != null && prevTaskId != taskId`.

---

### `compactDailyToMonthly()` (private)

Compacts `run_daily` entries older than 365 days into `run_monthly`.

1. `dao.getDailyOlderThan(cutoff)`.
2. Group by `(taskId, startOfMonthEpoch)`.
3. Merge into `RunMonthlySummary`.
4. `dao.upsertMonthly(merged)`.
5. `dao.deleteDailyOlderThan(cutoff)`.

---

### Helper functions

#### `resolvePrevTask(prev, currentStartEpoch): String?`

Returns `prev.taskId` only if the gap between the previous session's end and `currentStartEpoch` is within `SWITCH_GAP_THRESHOLD_MS` (5 minutes). Larger gaps indicate the user stepped away entirely and are not counted as context switches.

```
prevEndEpoch = prev.startEpoch + prev.durationSecs × 1000
gap          = currentStartEpoch − prevEndEpoch
return prev.taskId if gap in [0, 300_000] else null
```

#### `startOfDayEpoch(epochMs): Long`

UTC midnight of the day containing `epochMs`. Uses `Calendar.getInstance(UTC)`.

#### `startOfMonthEpoch(epochMs): Long`

UTC midnight of the 1st of the month containing `epochMs`.

#### `weekDayOf(epochMs): Int`

`Calendar.DAY_OF_WEEK` value (1=Sun … 7=Sat).

---

### Storage budget proof

| Tier | Max rows | Bytes/row | Max size |
|------|----------|-----------|---------|
| `run_log` | 100 000 | ~120 | ~12 MB |
| `run_daily` | 500 000 | ~80 | ~40 MB |
| `run_monthly` | ~240 000 (100 yr) | ~77 | ~18 MB |
| **Total** | | | **~70 MB** |

Well under 256 MB even at 10 000 active tasks over 100 years.

---

## Related files

- `db/TaskDao.kt` — SQL queries
- `db/RunLogDao.kt` — RunLog SQL queries
- `scheduler/EEVDFScheduler.kt` — algorithm called by repository
- `viewmodel/TaskViewModel.kt` — calls repository methods
- `docs/scheduler.md` — EEVDF algorithm detail
- `docs/run-log.md` — RunLog system detail
