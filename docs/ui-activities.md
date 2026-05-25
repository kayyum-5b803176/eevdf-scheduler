# UI Layer — Activities, Adapter & Backup

**Package:** `com.eevdf.scheduler.ui`

---

## MainActivity

**File:** `ui/MainActivity.kt`

The root screen. Hosts the three-tab layout (Queue / Schedule / Done) and the persistent timer card at the bottom.

### Views

| View | ID | Purpose |
|------|----|---------|
| `TabLayout` | `tabLayout` | Queue / Schedule / Done tabs |
| `RecyclerView` | `rvTasks` (queue), `rvSchedule` (schedule), `rvDone` (done) | One list per tab |
| `CardView` (timer card) | `timerCard` | Persistent bottom section showing current task |
| `TextView` | `tvCurrentTask` | Current task name |
| `TextView` | `tvTimerCountdown` | Countdown display (HH:MM:SS or MM:SS) |
| `ProgressBar` | `progressTimer` | Time slice consumption bar |
| `Button` | `btnStart`, `btnPause`, `btnSkip`, `btnReset` | Timer controls |
| `Button` | `btnScheduleNext` | "Schedule Next" (EEVDF pick) |
| `Button` | `btnInterrupt` | Jump to INT-A; long-press toggles to INT-B |
| `TextView` | `tvAlarmBanner` | Overrun alarm banner (hidden normally) |
| `TextView` | `tvStats` | Fairness score / stats line |

### Lifecycle hooks

#### `onCreate`
1. `setupToolbar()` — inflates and hooks the overflow menu long-press.
2. `setupViews()` — sets up tab layout, RecyclerViews, button click handlers.
3. `setupAdapters()` — creates three `TaskAdapter` instances (queue, schedule, done).
4. `setupObservers()` — wires all LiveData → UI.
5. `setupAlarmBanner()` — registers `alarmStopReceiver` for `ACTION_STOP_ALARM` broadcast.
6. `handler.post(quotaTickRunnable)` — starts the 1-second quota progress tick.

#### `onResume`
- Re-registers the call state receiver (`CallStateReceiver`).
- Re-starts the `quotaTickRunnable` if not running.

#### `onPause`
- Unregisters `CallStateReceiver`.
- Stops `quotaTickRunnable`.

#### `onDestroy`
- Unregisters `alarmStopReceiver`.
- Stops `quotaTickRunnable`.

---

### `setupObservers()`

The heaviest method — 17+ LiveData subscriptions:

| Observable | Action |
|-----------|--------|
| `flatActiveTasks` | Submit to queue adapter + call `updateEmptyView()` |
| `flatScheduleOrder` | Submit to schedule adapter |
| `completedTasks` | Submit to done adapter |
| `currentTask` | Update timer card (name, controls visibility), `consumePendingAutoStart()` → auto `startTimer()` |
| `timerSeconds` | Update countdown label + progress bar |
| `timerRunning` | Toggle Start/Pause button visibility |
| `stats` | Render stats line (fairness, count, avgVRT) |
| `groupsEnabled` | Rebuild adapters with/without rank badges |
| `globalRotateEnabled` | Update toolbar menu checkbox state |
| `allowEditEnabled` | Propagate to adapter `allowEdit` flag |
| `autoScrollEnabled` | Toggle auto-scroll behaviour |
| `autoMode` | Toggle auto-advance state indicator |
| `toastMessage` | Show Android Toast, then call `viewModel.clearToast()` |
| `alarmTaskName` | Show/hide alarm banner with task name |
| `alarmElapsedSeconds` | Update overrun counter display |
| `interruptTask` / `interruptTaskB` | Call `applyIntButtonState()` to label INT button |
| `activeInterruptSlot` | Update INT button slot indicator badge |

---

### Button wiring

| Button | Click action | Long-press action |
|--------|-------------|------------------|
| `btnStart` | `viewModel.startTimer()` | — |
| `btnPause` | `viewModel.pauseTimer()` | — |
| `btnSkip` | `viewModel.skipTask()` | — |
| `btnReset` | `viewModel.resetTimer()` | — |
| `btnScheduleNext` | `viewModel.scheduleNext()` | `viewModel.toggleAutoMode()` |
| `btnInterrupt` | `viewModel.jumpToInterrupt()` | `viewModel.toggleInterruptSlot()` |
| Next (queue/schedule) | `viewModel.nextSibling(onQueueTab)` | — |

---

### `makeAdapter(showRank, scheduleTab)`

Factory that creates a `TaskAdapter` pre-configured for its tab:
- Queue tab: `showRank = false`, `scheduleTab = false`
- Schedule tab: `showRank = true`, `scheduleTab = true`
- Done tab: `showRank = false`, read-only (no swipe)

---

### `tickQuotaOnVisibleItems()`

Runs every 1 second via `handler.post(quotaTickRunnable)`. For each visible `TaskViewHolder` in the RecyclerView, dispatches a partial `PAYLOAD_QUOTA_TICK` bind call — updates only the quota progress bar without a full rebind. This keeps quota bars animating smoothly without triggering DiffUtil on every tick.

---

### `scrollToTask(taskId)`

Finds the position of `taskId` in the current flat list and calls `recyclerView.smoothScrollToPosition(pos)`. Called whenever `currentTask` changes and `autoScrollEnabled = true`.

---

### `hookOverflowLongPress(toolbar)`

Long-pressing the ⋮ overflow button opens the Options menu (same as a tap). Implemented by walking the view tree looking for the internal `OverflowMenuButton` content description. Fragile but necessary because the Android `Toolbar` API has no public long-press hook.

---

### `applyIntButtonState(slot, taskA, taskB)`

Updates the INT button label to show which slot is active and the name of each destination:
```
[A] Call Task ↕ [B] Deep Work
```
When no task is assigned to a slot, shows "—".

---

## TaskAdapter

**File:** `adapter/TaskAdapter.kt`  
**Type:** `ListAdapter<TaskDisplayItem, TaskAdapter.TaskViewHolder>` with `DiffCallback`

### Constructor parameters

| Param | Type | Purpose |
|-------|------|---------|
| `onItemClick` | `(Task) → Unit` | Called when a row is tapped |
| `onItemLongClick` | `(Task) → Unit` | Called on long-press (edit gate applies) |
| `onDeleteClick` | `(Task) → Unit` | Called by swipe-to-delete |
| `onGroupToggle` | `(Task) → Unit` | Called when a group's expand/collapse chevron is tapped |
| `allowEdit` | `Boolean` | Gate: disables swipe/long-press when false |
| `showRank` | `Boolean` | Show #1, #2 … badges on the Schedule tab |
| `scheduleTab` | `Boolean` | Minor layout tweaks for Schedule tab vs Queue tab |
| `viewModel` | `TaskViewModel` | Used for expand-state queries |

---

### `setRunningTask(id: String?)`

Sets the highlighted row (blue accent + side bar). Should be called from the `currentTask` observer in `MainActivity`:
```kotlin
adapter.setRunningTask(viewModel.currentTask.value?.id)
adapter.notifyItemChanged(pos)   // minimal redraw
```

---

### `onBindViewHolder(holder, position, payloads)`

Supports partial rebind via `PAYLOAD_QUOTA_TICK`:

| Payload | Action |
|---------|--------|
| `PAYLOAD_QUOTA_TICK` | Call `bindQuotaOnly(holder, item)` — only quota bar + tint |
| empty | Full bind of all views |

Full bind covers:
- Name, description, priority chip, category chip, color strip
- Elapsed time / remaining time display
- `totalRunTime` and `runCount` display
- Quota bar (width, color tint) + quota label
- CPU share % label
- Depth indent (for nested tasks)
- Group chevron icon ( collapsed /  expanded)
- Rank badge (#N) for Schedule tab
- Running highlight (blue accent + side bar)
- vruntime + virtualDeadline in debug overlay (if debug build)

---

### `bindQuotaOnly(holder, item)`

Called on `PAYLOAD_QUOTA_TICK`. Updates only:
- Quota progress bar width percentage
- Quota bar tint (normal / warning / exceeded)
- Remaining quota label text
- Card background tint based on `effectiveQuotaExceeded`

This is a **performance-critical path** — it fires every second for each visible card. No view lookups — all views are cached in the `ViewHolder`.

---

### `DiffCallback`

```kotlin
areItemsTheSame   = old.task.id == new.task.id
areContentsTheSame = old == new  // data class structural equality
```

Full structural equality comparison on `TaskDisplayItem`. Because `TaskDisplayItem` is a data class wrapping `Task`, any field change on the task triggers a rebind.

---

### `formatTRT(totalSec): String`

Human-readable total run time: `"5h 23m"` / `"45m"` / `"30s"`.

### `formatQuota(totalSec): String`

Human-readable quota: `"2h 0m"` / `"30m"` / `"45s"`.

### `setQuotaBarTopMargin(holder, bothBarsVisible)`

Adjusts the top margin of the quota bar so it doesn't overlap the runtime row when both bars are visible simultaneously.

---

## AddTaskActivity

**File:** `ui/AddTaskActivity.kt`

Form screen for creating and editing tasks.

### Intent extras (input)

| Extra | Type | Presence | Meaning |
|-------|------|----------|---------|
| `EXTRA_TASK_ID` | `String` | Edit mode only | ID of task to edit |
| `EXTRA_PARENT_ID` | `String?` | Optional | Pre-select a parent group |

### UI sections

| Section | Fields |
|---------|--------|
| Basic | Name, Description |
| Time Slice | Hours / Minutes / Seconds pickers |
| Priority | Slider 1–10 + colour chip preview |
| Category | Chip group (Work / Study / Health / Personal / Project / Meeting / General) |
| Group | "Make this a Group" switch + Parent Group spinner |
| Interrupt | "Interrupt Task" switch + Slot spinner (A / B) |
| CPU Share Pinning | "Pin CPU Share" switch + percentage input |
| Task Type | Spinner (DEFAULT / NOTIFICATION / ALARM) + conditional fields |
| NOTIFICATION fields | Delay seconds, Rest seconds, Repeat count |
| Quota | Enable switch + Quota amount + Period amount |

---

### `setupPinnedShare()`

Live validation as the user types:
1. `validatePinnedShare(newValue)` — checks `otherPinnedTotal + newValue ≤ 100`.
2. `applySliderLock(pinnedValue)` — when a pin is set, the priority slider is hidden (weight is driven by the pin, not priority).
3. `recalcWeightFromPinned()` — calls `calcInternalWeight(targetShare)` to compute the preview internal weight.

### `calcInternalWeight(targetShare): Double`

Mirrors `EEVDFScheduler.calcPinnedWeight` for live preview:
```kotlin
val (floatPool, otherFloatWeight) = computeFloatPool(siblings, excludeId = editingTaskId)
val denom = floatPool - targetShare
if (denom <= 0) return MAX_INTERNAL_WEIGHT
return (targetShare * otherFloatWeight) / denom
```

### `setupTaskTypeSection()`

Shows/hides the NOTIFICATION-specific fields (delay, rest, repeat) based on the type spinner. NOTIFICATION and ALARM types share the alarm-triggering infrastructure but differ in how the ViewModel handles their phases.

### `saveTask()`

The main validation + persistence path:

1. Validate required fields (name not blank, time slice > 0).
2. Validate pinned share total ≤ 100.
3. Build the `Task` object from form fields.
4. If editing: `viewModel.updateTask(task)`.
5. If new: `viewModel.addTask(task)`.
6. `finish()` — returns to `MainActivity`.

### `parseDelayInput(raw): Long`

Parses the delay/rest/repeat input fields, supporting:
- Plain number: interpreted as seconds.
- `"Xs"` → seconds, `"Xm"` → minutes, `"Xh"` → hours.

### `parseQuotaInput(raw): Long`

Parses quota and period inputs using the same suffix convention as `parseDelayInput`.

---

## StatsActivity

**File:** `ui/StatsActivity.kt`

Analytics dashboard built as a programmatically-constructed scrollable `LinearLayout` (no XML adapter).

### `loadStats()`

Coroutine launched on `viewModelScope.Main`. Loads from:
- `taskRepository.getAllTasksForStats()` — tasks sorted by `totalRunTime DESC`
- `runLogRepository.getEntriesInRange(fromEpoch)` — Tier-1 session entries
- `runLogRepository.getDailyInRange(fromEpoch)` — Tier-2 daily summaries
- `runLogDao.getGlobalWeekdayTotals()` — weekday heatmap data

`fromEpoch` is derived from `parseRangeInput(etRange.text)` — user can type a number of days to look back (default 30).

---

### Render functions

#### `renderStats(tasks, entries, daily, monthly, nowMs)`

Orchestrates all sections. Each section adds views to the scrollable `LinearLayout`.

#### `renderDistribution(leafTasks, totalRT)`

Horizontal bar chart of time distribution across all tasks. Each bar width = `task.totalRunTime / totalRT × maxWidth`. Color-coded by priority.

```
Work Task A     65%
Deep Work                   25%
Email                            10%
```

#### `renderFrequency(ranTasks)`

Run count per task, sorted descending. Shows task name + total sessions + average session length.

#### `renderUntouched(leafTasks, nowMs)`

Tasks with `totalRunTime == 0` or not run in the last N days. "Neglected tasks" alert section.

#### `renderQuotaViolators(tasks)`

Tasks where `isQuotaExceeded = true`. Shows task name + quota amount + current usage %.

#### `renderSwitching(entries, taskById)`

Context-switch analysis from Tier-1 `run_log` entries:
- Counts how many times each `(from → to)` task pair occurred.
- Renders the top 5 most frequent context switches.
- High switch count between two tasks suggests they should be merged or scheduled together.

#### `renderWindowActivity(entries, rangeMs)`

Time-of-day distribution: divides the range into 6-hour buckets (midnight–6am, 6am–noon, noon–6pm, 6pm–midnight) and shows activity per bucket.

#### `renderPeakDay(dailyAll, taskById)`

From Tier-2 `run_daily`, finds the single day with the highest total runtime and shows a breakdown of tasks run that day.

#### `renderWeekdayHeatmap(tots)`

7-column heatmap (Sun–Sat) with bar height proportional to total seconds worked on that day of week. Uses `RunLogDao.WeekdayTotal` projection.

```
Sun  Mon  Tue  Wed  Thu  Fri  Sat
                  —
```

#### `renderStreaks(entries, nowMs)`

Finds consecutive days of activity. A day counts as active if at least one session was logged. Shows:
- Current streak (days in a row up to today).
- Best streak (longest historical streak).

`startOfDay(ms)` helper rounds down to UTC midnight for streak comparisons.

---

### View factory helpers

These produce consistent Material-styled views programmatically:

| Helper | Output |
|--------|--------|
| `makeBarRow(label, sublabel, frac, color)` | One bar row for charts |
| `makeStatRow(primary, value, secondaryLabel, secondaryValue)` | Two-column stat row |
| `makeLabel(text)` | Section header label |
| `makeEmpty(msg)` | Empty-state placeholder text |
| `divider()` | 1dp horizontal divider |

### `formatDur(secs): String`

`"5h 23m 12s"` — used throughout the stats display.

### `priorityColor(p): Int`

Maps priority 1–10 to a colour resource:
- 9–10: Red
- 7–8: Orange
- 5–6: Blue
- 3–4: Green
- 1–2: Grey

---

## BackupManager

**File:** `backup/BackupManager.kt`  
**Type:** Kotlin `object` (singleton)

### `toJson(tasks, groupsEnabled, globalRotateEnabled, allowEditEnabled): String`

Serialises the full task list + settings to a pretty-printed JSON string (2-space indent).

**Fields always written as fixed values:**
- `isRunning = false` — running state is never exported.
- `backupVersion = 1` — for future forward-compat branching.

**Fields preserved in backup:**
All EEVDF fields (`vruntime`, `eligibleTime`, `virtualDeadline`, `lag`), `totalRunTime`, `runCount`, `remainingSeconds`.

**Fields NOT in current backup v1** (would need a v2):
- `accumulatedMs`, `startTimeEpoch` (timer epoch columns — running state only)
- `quotaUsedSeconds`, `quotaPeriodStartEpoch` (quota runtime state)
- `pinnedShare`, `internalWeight` (share pinning)
- `interruptSlot` (interrupt configuration)
- RunLog analytics rows

### `fromJson(json): BackupData`

Deserialises using `opt*` methods throughout — unknown keys are silently ignored. This means:
- Old backups (v1) restore correctly into a newer app.
- New backups restore correctly into an older app (unknown fields default gracefully).

`BackupData` data class:
```kotlin
data class BackupData(
    val tasks: List<Task>,
    val groupsEnabled: Boolean,
    val globalRotateEnabled: Boolean,
    val allowEditEnabled: Boolean,
    val exportedAt: Long,
    val backupVersion: Int
)
```

### `taskFromJson(j): Task`

Key resilience: `remainingSeconds` falls back to `timeSliceSeconds` if missing (backwards compat with pre-v5 backups):
```kotlin
remainingSeconds = j.optLong("remainingSeconds", j.optLong("timeSliceSeconds", 0L))
```

### Extending the backup schema

To add new fields (e.g., `pinnedShare`):
1. Increment `BACKUP_VERSION` to `2`.
2. Add `put("pinnedShare", t.pinnedShare)` in `taskToJson` (handle null with `JSONObject.NULL`).
3. Add `pinnedShare = if (j.isNull("pinnedShare")) null else j.optInt("pinnedShare")` in `taskFromJson`.
4. Document what version `2` adds and what defaults v1 backups receive.

---

## ProfileSettingsActivity

**File:** `ui/ProfileSettingsActivity.kt`

Per-task extended configuration. Launched from a task's options menu or long-press.

### Settings available

| Setting | Type | Description |
|---------|------|-------------|
| Pinned share | Integer 0–100 or null | Override EEVDF weight with fixed % |
| Quota | Long seconds | Max runtime per period |
| Quota period | Long seconds | Period length |
| Task type | Spinner | DEFAULT / NOTIFICATION / ALARM |
| Delay / Rest / Repeat | Long / Long / Int | NOTIFICATION phase config |
| Interrupt assignment | Switch + Slot picker | Assign this task as INT-A or INT-B |
| Sound profile | Picker | Per-task alarm sound |
| Vibration profile | Picker | Per-task vibration pattern |

---

## SettingsActivity

**File:** `ui/SettingsActivity.kt`

Global app settings. All toggles persist to `SharedPreferences("eevdf_prefs")`.

| Setting | Key | Default |
|---------|-----|---------|
| Groups mode | `groups_enabled` | false |
| Global Rotate | `global_rotate_enabled` | false |
| Allow Edit | `allow_edit_enabled` | false |
| Auto Scroll | `auto_scroll_enabled` | false |
| Backup export | — | Triggers file picker |
| Backup import | — | Triggers file picker |

---

## AutoSwitchActivity

**File:** `ui/AutoSwitchActivity.kt`

Call-detection configuration.

| Control | Purpose |
|---------|---------|
| Enable switch | `AutoSwitchPrefs.setEnabled(enabled)` |
| Task picker | Loads active leaf tasks via `taskRepository.activeTasks`, writes selected task ID to `AutoSwitchPrefs` |
| Permission prompt | Shows `READ_PHONE_STATE` rationale if not granted |

---

## SoundVibrationActivity

**File:** `ui/SoundVibrationActivity.kt`

Per-task sound + vibration picker. Previews sounds via `SoundManager`. Writes selection to task profile SharedPreferences (keyed by task ID).

---

## AlarmActivity

**File:** `ui/AlarmActivity.kt`

Full-screen alarm shown over lock screen on timer expiry.

### Window flags
```kotlin
setShowWhenLocked(true)
setTurnScreenOn(true)
window.addFlags(FLAG_KEEP_SCREEN_ON)
```

### Controls
- Task name label
- "Elapsed since expiry" counter (from `viewModel.alarmElapsedSeconds`)
- "Stop" button → `viewModel.stopAlarmSound()`

Launched via the `setFullScreenIntent` of the alarm notification.
