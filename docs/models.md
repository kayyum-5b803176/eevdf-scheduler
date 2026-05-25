# Data Models

**Package:** `com.eevdf.scheduler.model`

---

## Task

**File:** `model/Task.kt`  
**Room entity:** `@Entity(tableName = "tasks")`

The central data model. Every feature of the app maps onto fields in this class.

### Fields by category

#### Identity
| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `id` | `String` | `UUID.randomUUID()` | Primary key |
| `name` | `String` | — | Display name |
| `description` | `String` | `""` | Optional notes |
| `category` | `String` | `"General"` | Work / Study / Health / Personal / Project / Meeting / General |
| `color` | `Int` | `0` | User-assigned tint color |
| `createdAt` | `Long` | `System.currentTimeMillis()` | Epoch ms of creation |

#### cgroup Hierarchy
| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `parentId` | `String?` | `null` | ID of parent group, or null for root |
| `isGroup` | `Boolean` | `false` | True when this task is a group container |
| `isGroupExpanded` | `Boolean` | `true` | Expand/collapse toggle (per-tab state managed in ViewModel) |

#### EEVDF Scheduler State
| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `priority` | `Int` | — | User-set priority 1–10 |
| `internalWeight` | `Double?` | `null` | Auto-calculated override; null = use `priority` |
| `vruntime` | `Double` | `0.0` | Weighted virtual time consumed |
| `eligibleTime` | `Double` | `0.0` | Virtual time when task becomes schedulable |
| `virtualDeadline` | `Double` | `0.0` | `eligibleTime + timeSliceSeconds / weight` |
| `lag` | `Double` | `0.0` | `(avgVRT − vruntime) × weight`. Positive = owed time |
| `totalRunTime` | `Long` | `0` | Total seconds run across all sessions |
| `runCount` | `Int` | `0` | Number of completed timer sessions |

#### Timer State (raw DB columns — use `TaskTimerExt.kt` to read/write)
| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `timeSliceSeconds` | `Long` | — | Full slice duration |
| `remainingSeconds` | `Long` | `timeSliceSeconds` | Cached display value; updated by `withTimerState()` |
| `isRunning` | `Boolean` | `false` | True while CountDownTimer is active |
| `isCompleted` | `Boolean` | `false` | True when task is marked done |
| `accumulatedMs` | `Long` | `0` | ms consumed before the current session |
| `startTimeEpoch` | `Long` | `0` | Epoch ms when timer was last resumed (0 = paused) |

>  **Never read `accumulatedMs` / `startTimeEpoch` directly.** Use `task.timerState` from `TaskTimerExt.kt`.
>  **Never write these columns via `task.copy()`.** Use `task.withTimerState(newState)`.

#### Task Type & NOTIFICATION Parameters
| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `taskType` | `String` | `"DEFAULT"` | `DEFAULT` / `NOTIFICATION` / `ALARM` / `CUSTOM` |
| `notificationDelaySeconds` | `Long` | `0` | Pre-delay before countdown (0–300 s) |
| `notificationRestSeconds` | `Long` | `0` | Rest period between cycles (0–300 s) |
| `notificationRepeatCount` | `Int` | `0` | Extra cycles after the first (0 = run once) |

#### Interrupt
| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `isInterrupt` | `Boolean` | `false` | True when assigned as an interrupt destination |
| `interruptSlot` | `String` | `"A"` | Which slot: `"A"` or `"B"` |

#### CPU Share Pinning
| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `pinnedShare` | `Int?` | `null` | Fixed % share (0–100); null = auto-float via EEVDF weight |

#### Quota (CPU Bandwidth Control)
| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `quotaSeconds` | `Long` | `0` | Max runtime per period; 0 = unlimited |
| `quotaPeriodSeconds` | `Long` | `86400` | Period length (default 1 day) |
| `quotaPeriodStartEpoch` | `Long` | `0` | Epoch ms when current period started; 0 = not started |
| `quotaUsedSeconds` | `Long` | `0` | Seconds consumed in current period (before decay) |

---

### Computed Properties

#### `weight: Double`
```kotlin
val weight: Double get() = internalWeight ?: priority.toDouble()
```
Effective EEVDF weight. `internalWeight` is auto-set when a `pinnedShare` is assigned.

#### `isQuotaEnabled: Boolean`
`quotaSeconds > 0`

#### `currentQuotaUsed: Long`
Live decayed quota usage. Quota replenishes continuously at `quotaSeconds / quotaPeriodSeconds` seconds per second. The calculation:
```
rate        = quotaSeconds / quotaPeriodSeconds   (seconds replenished per second)
elapsed     = (now − quotaPeriodStartEpoch) / 1000
replenished = elapsed × rate
result      = max(0, quotaUsedSeconds − replenished)
```
This produces a smooth reverse-clock effect: over-budget tasks gradually return to budget without requiring a hard period boundary.

#### `isQuotaExceeded: Boolean`
`isQuotaEnabled && currentQuotaUsed >= quotaSeconds`

#### `isQuotaWarning: Boolean`
`isQuotaEnabled && currentQuotaUsed >= quotaSeconds × 0.8 && !isQuotaExceeded`

#### `quotaRemainingSeconds: Long`
- `-1` when quota is not enabled
- `0` when exceeded
- `> 0` when budget remains

#### `quotaOverflowSeconds: Long`
Positive seconds beyond quota; `0` when within budget.

#### `quotaProgressPercent: Int`
0–100 for the quota progress bar:
- Normal: fills as budget is consumed.
- Exceeded: shows overflow draining back toward 0.

#### `timeSliceDisplay: String`
Human-readable slice: `"2h 30m"` / `"45m 0s"` / `"30s"`.

#### `remainingDisplay: String`
Countdown string: `"1:23:45"` or `"23:45"`.

#### `liveElapsedMs: Long`
```kotlin
if (startTimeEpoch > 0L) accumulatedMs + (now − startTimeEpoch)
else accumulatedMs
```
Live elapsed ms, correct whether running or paused.

#### `progressPercent: Int`
`(liveElapsedMs × 100 / sliceMs).coerceIn(0, 100)`

---

## TimerState

**File:** `model/TimerState.kt`  
Full documentation in [`docs/timer.md`](timer.md).

Sealed class with four states: `Idle`, `Paused`, `Running`, `Expired`.

---

## NoticePhase

**File:** `model/NoticePhase.kt`

Sealed class for NOTIFICATION task type state machine.

### States

| State | Button shown | Description |
|-------|-------------|-------------|
| `Idle` | Start | Not started / after cancel |
| `Delay(remainingSecs)` | Cancel | Pre-delay countdown running |
| `Execute(iteration)` | Pause | Actual task timer running |
| `Wait(remainingSecs, iteration)` | Cancel | Rest period between cycles |
| `Expired` | (none) | All cycles done — alarm banner takes over |

### State Machine

```
Idle start() Delay onDelayFinish() Execute onExecuteFinish() Wait
                                                                          
               cancel()                      pause()                    cancel()
                                                                          
                           Idle          onWaitFinish() if repeat
                                                             onWaitFinish() if done Expired
```

### Fields

#### `Delay`
- `remainingSecs: Long` — countdown value for the delay phase UI label

#### `Execute`
- `iteration: Int` — 0-based cycle index (0 = first run)

#### `Wait`
- `remainingSecs: Long` — countdown value for rest phase label
- `iteration: Int` — which cycle just finished

---

## TaskDisplayItem

**File:** `model/TaskDisplayItem.kt`

Wrapper around `Task` for flat-list RecyclerView rendering. The ViewModel flattens the task tree into this structure, respecting expand/collapse state.

### Fields

| Field | Type | Description |
|-------|------|-------------|
| `task` | `Task` | The underlying task |
| `depth` | `Int` | Nesting depth (0 = root, 1 = child of root group, …) |
| `childCount` | `Int` | Number of direct children (groups only) |
| `childTotalRuntime` | `Long` | Sum of direct children's `totalRunTime` (groups only) |
| `cpuShare` | `Double` | Real-time CPU share % from `EEVDFScheduler.computeShares()` |
| `effectiveQuotaExceeded` | `Boolean` | True if this task OR any ancestor has quota exceeded |
| `effectiveQuotaWarning` | `Boolean` | True if this task OR any ancestor is in the warning zone |

**effectiveQuota*** mirrors Linux cgroup bandwidth throttling: a child cannot run if its parent's budget is exhausted.

---

## RunLogEntry

**File:** `model/RunLogEntry.kt`  
**Room entity:** `@Entity(tableName = "run_log")`  
**Purpose:** Tier-1 analytics — one row per completed timer session.

### Fields

| Field | Type | Description |
|-------|------|-------------|
| `id` | `Long` | Auto-increment PK |
| `taskId` | `String` | Task that ran |
| `startEpoch` | `Long` | Epoch ms when session started |
| `durationSecs` | `Long` | Session length in whole seconds |
| `prevTaskId` | `String?` | Previous task (for context-switch overhead); null if gap > 5 min |
| `weekDay` | `Int` | Day of week at start (1=Sun … 7=Sat) |

### Constants

| Constant | Value | Description |
|----------|-------|-------------|
| `SWITCH_GAP_THRESHOLD_MS` | 300 000 ms (5 min) | Gaps larger than this are not counted as switches |
| `MAX_ROWS` | 100 000 | Hard cap; oldest rows deleted when exceeded |
| `TTL_DAYS` | 30 | Rows older than this are compacted to `run_daily` |

---

## RunDailySummary

**File:** `model/RunDailySummary.kt`  
**Room entity:** `@Entity(tableName = "run_daily", primaryKeys = ["taskId", "dayEpoch"])`  
**Purpose:** Tier-2 analytics — daily aggregate per task.

### Fields

| Field | Type | Description |
|-------|------|-------------|
| `taskId` | `String` | Composite PK (with `dayEpoch`) |
| `dayEpoch` | `Long` | Epoch ms of UTC midnight starting this day |
| `totalSecs` | `Long` | Sum of session durations that day |
| `runCount` | `Int` | Number of sessions that day |
| `switchInCount` | `Int` | Times switched TO this task from a different task |
| `weekDay` | `Int` | Day of week (1=Sun … 7=Sat) |

### Constants

| Constant | Value | Description |
|----------|-------|-------------|
| `MAX_ROWS` | 500 000 | Soft cap |
| `TTL_DAYS` | 365 | Rows older than this are compacted to `run_monthly` |

---

## RunMonthlySummary

**File:** `model/RunMonthlySummary.kt`  
**Room entity:** `@Entity(tableName = "run_monthly", primaryKeys = ["taskId", "monthEpoch"])`  
**Purpose:** Tier-3 analytics — monthly aggregate. Kept forever.

### Fields

| Field | Type | Description |
|-------|------|-------------|
| `taskId` | `String` | Composite PK (with `monthEpoch`) |
| `monthEpoch` | `Long` | Epoch ms of the UTC midnight of the 1st of this month |
| `totalSecs` | `Long` | Total seconds run in this month |
| `runCount` | `Int` | Total sessions in this month |

No TTL. Storage budget proof: 100 yr × 12 mo × 200 active tasks × ~77 bytes ≈ 18 MB.

---

## Related files

- `db/TaskDatabase.kt` — Room schema and migrations
- `db/TaskRepository.kt` — business logic acting on Task
- `model/TaskTimerExt.kt` — read/write gateway for timer columns
- `model/TimerState.kt` — sealed timer state
