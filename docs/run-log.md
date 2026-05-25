# RunLog — Three-Tier Analytics System

**Files:**
- `model/RunLogEntry.kt` — Tier 1 entity
- `model/RunDailySummary.kt` — Tier 2 entity
- `model/RunMonthlySummary.kt` — Tier 3 entity
- `db/RunLogDao.kt` — all SQL queries
- `db/RunLogRepository.kt` — compaction pipeline

---

## System Overview

The RunLog is a three-tier log designed to answer analytics questions spanning from "what did I do this session" to "how much did I work on this task over the past year" — all within a predictable storage budget.

```
run_log       ← recordRun()
   TTL: 30 days, cap 100K rows
   (after 30 days or cap hit)
 
run_daily     ← compactLogToDaily()
   TTL: 365 days, cap 500K rows
   (after 365 days)
 
run_monthly   ← compactDailyToMonthly()
    No TTL, kept forever
```

Compaction is **lazy** — triggered on `recordRun()` but throttled to at most once every 24 hours.

---

## Tier 1: run_log (RunLogEntry)

One row per completed timer session.

### Schema

| Column | Type | Index | Description |
|--------|------|-------|-------------|
| `id` | INTEGER PK autoincrement | — | |
| `taskId` | TEXT NOT NULL |  | Which task ran |
| `startEpoch` | INTEGER NOT NULL |  | Epoch ms when session started |
| `durationSecs` | INTEGER NOT NULL | — | Session length in whole seconds |
| `prevTaskId` | TEXT |  | Previous task (null if gap > 5 min) |
| `weekDay` | INTEGER NOT NULL | — | 1=Sun … 7=Sat |

### Retention rules

- **TTL:** 30 days. Older rows are compacted to `run_daily` and deleted.
- **Hard cap:** 100 000 rows. Oldest rows deleted when exceeded (enforced on every `recordRun`).

### Context-switch detection

`prevTaskId` is set to the previous task's ID only if:
```
gap = currentStartEpoch − (prevStartEpoch + prevDurationSecs × 1000)
prevTaskId = prevTask.taskId if gap ≤ 300_000 ms (5 min) else null
```

Gaps larger than 5 minutes indicate the user stepped away from the app. These are not counted as task switches.

---

## Tier 2: run_daily (RunDailySummary)

Daily aggregate per task. Produced by compacting Tier 1.

### Schema

| Column | Type | Description |
|--------|------|-------------|
| `taskId` | TEXT NOT NULL | Composite PK |
| `dayEpoch` | INTEGER NOT NULL | UTC midnight epoch ms of the day |
| `totalSecs` | INTEGER | Sum of session durations |
| `runCount` | INTEGER | Number of sessions |
| `switchInCount` | INTEGER | Times switched TO this task from another |
| `weekDay` | INTEGER | 1=Sun … 7=Sat |

### Retention rules

- **TTL:** 365 days. Older rows are compacted to `run_monthly` and deleted.
- **Soft cap:** 500 000 rows.

### Day boundaries

Days use **UTC midnight** (`startOfDayEpoch`). Consistent with the server-less architecture — no timezone ambiguity when the device moves regions.

---

## Tier 3: run_monthly (RunMonthlySummary)

Monthly aggregate. **Kept forever.**

### Schema

| Column | Type | Description |
|--------|------|-------------|
| `taskId` | TEXT NOT NULL | Composite PK |
| `monthEpoch` | INTEGER NOT NULL | UTC midnight of the 1st of the month |
| `totalSecs` | INTEGER | Total seconds for the month |
| `runCount` | INTEGER | Total sessions for the month |

### Storage proof

100 yr × 12 mo × 200 avg active tasks × 77 bytes = **~18 MB**. At 1 000 active tasks/month: ~92 MB. Well within the 256 MB budget.

---

## recordRun(taskId, startEpoch, durationSecs)

The only public entry point. Called by `TaskRepository.updateVruntimeAfterRun()` for every non-group leaf task.

```
1. getLatestEntry()                    ← find the previous task for switch detection
2. resolvePrevTask(prev, startEpoch)   ← null if gap > 5 min
3. weekDayOf(startEpoch)               ← compute day of week
4. insertLog(RunLogEntry(...))         ← write the row
5. enforce hard cap (100K)
6. maybeCompact()                      ← throttled compaction
```

---

## Compaction Pipeline

### maybeCompact()

Guard: `now − lastCompactEpoch < 24h → return`.

Otherwise runs:
1. `compactLogToDaily()`
2. `compactDailyToMonthly()`

Updates `PREF_LAST_COMPACT` timestamp on success.

---

### compactLogToDaily()

```
cutoff = now − 30 days (in ms)

entries = getEntriesOlderThan(cutoff)
if empty: return

Group by (taskId, startOfDayEpoch(entry.startEpoch)):
  for each group:
    acc.totalSecs     += entry.durationSecs
    acc.runCount      += 1
    if prevTaskId != null && prevTaskId != taskId:
      acc.switchInCount += 1

for each group:
  existing = getDailyForTask(taskId).find { it.dayEpoch == dayEpoch }
  merged = RunDailySummary(
    totalSecs    = (existing?.totalSecs ?: 0) + acc.totalSecs,
    runCount     = (existing?.runCount  ?: 0) + acc.runCount,
    switchInCount= (existing?.switchInCount ?: 0) + acc.switchInCount,
    weekDay      = acc.weekDay
  )
  upsertDaily(merged)

deleteEntriesOlderThan(cutoff)
enforce 500K daily cap
```

**Merge strategy:** if a `run_daily` row already exists for `(taskId, dayEpoch)`, the new counts are added to the existing counts. This handles the case where partial compaction has already run for the same day.

---

### compactDailyToMonthly()

```
cutoff = now − 365 days (in ms)

rows = getDailyOlderThan(cutoff)
if empty: return

Group by (taskId, startOfMonthEpoch(row.dayEpoch)):
  for each group:
    acc.totalSecs += row.totalSecs
    acc.runCount  += row.runCount

for each group:
  existing = getMonthlyForTask(taskId).find { it.monthEpoch == monthEpoch }
  upsertMonthly(RunMonthlySummary(
    totalSecs = (existing?.totalSecs ?: 0) + acc.totalSecs,
    runCount  = (existing?.runCount  ?: 0) + acc.runCount
  ))

deleteDailyOlderThan(cutoff)
```

---

## Analytics Queries Available

### Per-task history

| Query | Table | Use |
|-------|-------|-----|
| `getEntriesForTask(taskId, fromEpoch)` | run_log | Recent session detail |
| `getDailyForTask(taskId)` | run_daily | Day-by-day history |
| `getMonthlyForTask(taskId)` | run_monthly | Month-by-month history |

### Range queries

| Query | Table | Use |
|-------|-------|-----|
| `getEntriesInRange(from, to)` | run_log | Cross-task switch analysis for a window |
| `getDailyInRange(fromEpoch)` | run_daily | All tasks' daily totals from a start date |
| `getMonthlyInRange(fromEpoch)` | run_monthly | Monthly totals from a start date |

### Aggregates

| Query | Table | Use |
|-------|-------|-----|
| `getWeekdayTotalsForTask(taskId)` | run_daily | Per-task weekday pattern (Sun–Sat totals) |
| `getGlobalWeekdayTotals()` | run_daily | Global weekday pattern across all tasks |

---

## Adding New Analytics (how-to)

To add a new metric (e.g., "time of day pattern"):

1. If it can be derived from existing columns at query time (e.g., bucket `startEpoch` by hour) → add a new `@Query` method to `RunLogDao` with a projection.
2. If it needs to be stored for compaction efficiency → add the column to `RunDailySummary`, increment the DB version, write a migration, and update `compactLogToDaily()` to populate it.
3. Never add columns to `RunMonthlySummary` without verifying the storage budget.

---

## Related files

- `db/TaskRepository.kt` — calls `recordRun()` after every timer session
- `ui/StatsActivity.kt` — displays analytics using these queries
- `docs/database.md` — schema and migration details for run_log tables
- `docs/repository.md` — `updateVruntimeAfterRun` where `recordRun` is called
