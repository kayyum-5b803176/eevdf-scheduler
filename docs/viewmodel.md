# TaskViewModel — Complete Reference

**File:** `viewmodel/TaskViewModel.kt`  
**Type:** `AndroidViewModel` — lives for the duration of the Activity's lifecycle scope.

The ViewModel is the MVVM brain: it holds all UI state, orchestrates the timer engine, drives EEVDF scheduling decisions, and coordinates between the repository and the UI layer.

---

## Architecture Overview

```
MainActivity / AddTaskActivity
          observe LiveData            call fun ...()
                                    
    TaskViewModel
                                                
                                                
  TaskRepository        TimerEngine         AlarmForegroundService
  (DB + EEVDF)          (countdown)         (notification / alarm)
```

---

## LiveData Outputs

| LiveData | Type | Description |
|----------|------|-------------|
| `allTasks` | `List<Task>` | All tasks, all states |
| `activeTasks` | `List<Task>` | Non-completed tasks |
| `completedTasks` | `List<Task>` | Completed tasks |
| `activeGroups` | `List<Task>` | Group containers for parent picker |
| `currentTask` | `Task?` | The task currently selected/running |
| `timerSeconds` | `Long` | Remaining seconds for countdown display |
| `timerRunning` | `Boolean` | Whether timer is actively counting |
| `scheduleOrder` | `List<Task>` | EEVDF-ranked task list |
| `stats` | `SchedulerStats` | Fairness, counts, avgVRT |
| `toastMessage` | `String?` | One-shot toast (clear after consuming) |
| `alarmTaskName` | `String?` | Task name for overrun alarm banner |
| `alarmElapsedSeconds` | `Long` | Seconds overrun since alarm fired |
| `flatActiveTasks` | `List<TaskDisplayItem>` | Flattened tree for Queue tab RecyclerView |
| `flatScheduleOrder` | `List<TaskDisplayItem>` | Flattened schedule for Schedule tab |
| `groupsEnabled` | `Boolean` | Groups mode toggle state |
| `globalRotateEnabled` | `Boolean` | Global Rotate toggle state |
| `allowEditEnabled` | `Boolean` | Allow-edit gate toggle state |
| `autoScrollEnabled` | `Boolean` | Auto-scroll toggle state |
| `autoMode` | `Boolean` | Hands-free auto-advance toggle state |
| `interruptTask` | `Task?` | INT-A destination task |
| `interruptTaskB` | `Task?` | INT-B destination task |
| `activeInterruptSlot` | `String` | Currently active interrupt slot ("A" or "B") |

---

## Feature Toggles

All feature toggles persist to `SharedPreferences("eevdf_prefs")`.

### `toggleGroupsEnabled()`
Flips `groupsEnabled`. Triggers rebuild of both flat lists.

### `toggleGlobalRotate()`
Flips `globalRotateEnabled`. Controls whether "Next" cycles all top-level tasks or only siblings.

### `toggleAllowEdit()`
Flips `allowEditEnabled`. When false, the adapter blocks edit gestures.

### `toggleAutoScroll()`
Flips `autoScrollEnabled`. When true, the list scrolls to the current task automatically.

### `toggleAutoMode()`
Flips `autoMode`.
- **ON:** saves current `globalRotateEnabled` state, forces it OFF, shows toast.
- **OFF:** restores the saved `globalRotateEnabled`, shows toast.

When auto mode is on and the timer expires, `onTimerFinished` automatically calls `scheduleNext()` and restarts the timer without user interaction.

### `toggleInterruptSlot()`
Flips `activeInterruptSlot` between `"A"` and `"B"`.

---

## Task CRUD

### `addTask(task)`
1. `repository.insert(task)` — inserts and recalculates EEVDF for all tasks.
2. `syncPinnedWeights()` — re-derive `internalWeight` for any pinned tasks.

### `updateTask(task)`
1. `repository.update(task)`.
2. `syncPinnedWeights()`.

### `deleteTask(task)`
1. If `currentTask == task`: stops timer, clears current task.
2. `repository.delete(task)` — recursive delete including children.
3. `syncPinnedWeights()`.

### `revertTask(task)`
Restores a task to its initial state (full slice, zero vruntime, zero runCount):
```kotlin
repository.update(task.copy(
    vruntime = 0.0, eligibleTime = 0.0, virtualDeadline = 0.0, lag = 0.0,
    remainingSeconds = task.timeSliceSeconds, isRunning = false,
    accumulatedMs = 0L, startTimeEpoch = 0L,
    totalRunTime = 0L, runCount = 0
))
```

### `markCompleted(task)`
1. Calls `repository.markCompleted(task)`.
2. If this was the `currentTask`, stops timer and clears current.

### `clearCompleted()`
Deletes all completed tasks via `repository.clearCompleted()`.

---

## Scheduling

### `scheduleNext()`
1. `repository.selectNextTask()` — hierarchical cgroup EEVDF selection.
2. `setCurrentTask(winner)` — update `_currentTask`.
3. `refreshSchedule()` — update `_scheduleOrder` and `_stats`.

### `refreshSchedule()`
1. `repository.getScheduleOrder()` — recompute EEVDF order.
2. Posts to `_scheduleOrder`.
3. `EEVDFScheduler.getStats(activeTasks)` → posts to `_stats`.

### `setCurrentTask(task)`
Sets `_currentTask`. If a timer is running for a different task, pauses it first.

### `jumpToFirst(onQueueTab)`
Selects the first task in the flat list (Queue tab) or schedule order (Schedule tab).

---

## Timer

### `startTimer()`
Branches on `currentTask.taskType`:

**DEFAULT:**
1. Mark task `isRunning = true` via `withTimerState(TimerState.resume(...))`.
2. `repository.update(task)`.
3. `engine.start(task)` — attach CountDownTimer.
4. `AlarmForegroundService.timerStart(...)` — schedule Doze-immune alarm + foreground notification.

**NOTIFICATION:**
1. If delay > 0: `startDelayPhase(task, remaining, delaySecs)`.
2. Else: `startExecutePhase(task, remaining, iteration = 0)`.

**ALARM:**
Same as DEFAULT — the difference is in `onTimerFinished` where an alarm is rung.

### `pauseTimer()`
1. `engine.pause()` → returns updated Task with paused state.
2. If task is NOTIFICATION type: `cancelDelayPhase()` or pause the execute phase.
3. `applyVruntimeUpdate(ranSeconds)` — credit partial time.
4. `repository.update(pausedTask)`.
5. `AlarmForegroundService.timerPause(ctx)` — cancel alarm + remove notification.
6. `_timerRunning.value = false`.

### `skipTask()`
1. Stop timer.
2. `applyVruntimeUpdate(ranSeconds)` — credit partial time.
3. `scheduleNext()` — advance to next EEVDF task.

### `resetTimer()`
1. Stop CountDownTimer.
2. `engine.reset()` → Task with `TimerState.Idle`.
3. `repository.update(resetTask)`.
4. `AlarmForegroundService.timerPause(ctx)`.
5. `_timerSeconds.value = task.timeSliceSeconds`.

### `resetSlice(task)`
Resets a specific task's timer to full without changing `currentTask`. Used for manual reset of a non-active task.

### `pauseAndDismiss()`
Pauses the timer and sets `currentTask = null`. Used when the user dismisses the current task without completing it.

---

## NOTIFICATION Type Phases

### `startDelayPhase(task, remaining, delaySecs)`
1. `AlarmForegroundService` with `ACTION_DELAY_START` — shows a delay-phase notification.
2. Creates a separate `CountDownTimer` for the delay countdown.
3. Posts `NoticePhase.Delay(remainingSecs)` on each tick.
4. On finish: calls `startExecutePhase(task, remaining, 0)`.

### `startExecutePhase(task, secs, iteration)`
Launches the real timer for this cycle via `startActualTimer(task, secs)`.

### `startWaitPhase(task, waitSecs)`
1. Creates a separate CountDownTimer for the rest period.
2. Posts `NoticePhase.Wait(remainingSecs, iteration)` on each tick.
3. On finish:
   - If `iteration < notificationRepeatCount` → `startExecutePhase(task, fullSlice, iteration + 1)`.
   - Else → `triggerAlarmExpire(task)`.

### `cancelNotice()`
Cancels whichever phase is currently active (delay or wait). Returns to `Idle` phase.

### `delaySecs(): Long`
Returns `currentTask?.notificationDelaySeconds ?: 0`.

---

## `onTimerFinished(task, expired)` (private)

Called by `TimerEngine.expiredTask` observer.

1. Mark task `isRunning = false`, `remainingSeconds = 0`.
2. `applyVruntimeUpdate(task.timeSliceSeconds)` — credit full slice.
3. `repository.update(task)`.
4. Dispatch on `taskType`:
   - **DEFAULT:** `AlarmForegroundService.timerExpire(...)`.
   - **NOTIFICATION:** start the wait phase (or expire if final cycle).
   - **ALARM:** `triggerAlarmExpire(task)`.
5. If `autoMode == true`: call `scheduleNext()` and `startTimer()`, set `pendingAutoStart = true`.

### `applyVruntimeUpdate(ranSeconds)` (private)

```kotlin
repository.updateVruntimeAfterRun(currentTask!!, ranSeconds)
```

Delegates the full cgroup propagation + quota accounting to the repository.

---

## Sibling / Global Rotation

### `nextSibling(onQueueTab)`

Decides rotation mode:
- If `autoMode`: no-op (auto mode handles advancement automatically).
- If `globalRotateEnabled`: `rotateGlobal(onQueueTab)`.
- Else: `rotateSiblings(onQueueTab)`.

### `rotateSiblings(onQueueTab)` (private)

1. Find the current task in the flat list.
2. Find all siblings (same `parentId`).
3. Select the next sibling in list order.
4. `setCurrentTask(nextSibling)`.

### `rotateGlobal(onQueueTab)` (private)

1. Collect all root-level tasks (or all flat tasks, depending on mode).
2. Advance to the next task by natural sort order.
3. `setCurrentTask(next)`.

### `firstLeafOf(tasks, parentId)` (private)

Recursively finds the first non-group task under a given parent. Used to land on a leaf when entering a group via rotation.

### `rootAncestorOf(tasks, task)` (private)

Walks `parentId` up to find the root-level ancestor. Used for global rotation to compare tasks at the same level.

---

## Interrupt Tasks

### `assignInterruptTask(task)`
Sets the INT-A destination: `repository.setInterruptTask(task, "A")`.

### `assignInterruptTaskB(task)`
Sets the INT-B destination: `repository.setInterruptTask(task, "B")`.

### `clearInterruptTask()` / `clearInterruptTaskB()`
Clears the respective slot.

### `jumpToInterrupt()`
Delegates to `jumpToInterruptSlot("A")`.

### `jumpToInterruptA()` / `jumpToInterruptB()`
Named versions for explicit slot targeting.

### `jumpToInterruptSlot(slot, interruptTask)` (private)

1. Save `currentTask` to `savedTaskBeforeInterrupt[A|B]`.
2. Pause timer if running.
3. `setCurrentTask(interruptTask)`.

When the interrupt task's timer finishes, `onTimerFinished` checks for a saved task and restores it.

---

## Call Detection

### `handleCallStarted(callTaskId)`

1. Look up the call task by ID.
2. Save `currentTask` as the task to return to after the call.
3. Pause current timer if running.
4. `setCurrentTask(callTask)`.

### `handleCallEnded()`

1. Restore the saved task.
2. `setCurrentTask(savedTask)`.

---

## Group Expand / Collapse

### `toggleQueueGroupExpanded(group)` / `toggleScheduleGroupExpanded(group)`

Toggle the expand state for the Queue or Schedule tab independently. State persisted to `SharedPreferences` under `"qexpand_{id}"` / `"sexpand_{id}"`.

### `getQueueExpanded(taskId)` / `getScheduleExpanded(taskId)`

Returns the expand state for a group in the respective tab. Defaults to `true` (expanded).

---

## Flat List Building

### `buildQueueList(tasks, groupsEnabled): List<TaskDisplayItem>`

Builds the flat list for the Queue tab, respecting expand/collapse state and injecting `cpuShare`, `effectiveQuotaExceeded`, `effectiveQuotaWarning` for each item.

Internally calls `addLevel(parentId, depth, parentQuotaExceeded, parentQuotaWarning)` recursively to walk the tree.

### `buildScheduleList(tasks, groupsEnabled): List<TaskDisplayItem>`

Same as `buildQueueList` but uses `scheduleOrder` (EEVDF-ranked) instead of `allTasks` for the flat list.

---

## Misc

### `clearToast()`
Clears the one-shot `_toastMessage` LiveData. Call after displaying the toast.

### `consumePendingAutoStart(): Boolean`
Reads and clears the one-shot `pendingAutoStart` flag set by auto mode. Call from `MainActivity.currentTask` observer.

### `saveTab(tab)` / `getSavedTab()`
Persist the last-active tab index so it survives configuration changes.

### `prepareForDbExport()`
Calls `TaskDatabase.checkpointAndClose(ctx)` — flushes WAL and closes the DB before a backup file copy.

### `syncPinnedWeights()` (private, suspend)
Calls `EEVDFScheduler.syncPinnedWeights(allActive)` then `repository.updateBatch(changed)` to persist only the tasks whose `internalWeight` changed.

### `stopAlarmSound()`
Sends `ACTION_STOP` to `AlarmForegroundService` and calls `stopOverrunCounter()`.

---

## SharedPreferences Keys

| Key | Purpose |
|-----|---------|
| `groups_enabled` | Groups mode |
| `global_rotate_enabled` | Global Rotate mode |
| `allow_edit_enabled` | Allow-edit gate |
| `auto_scroll_enabled` | Auto-scroll |
| `auto_mode` | Hands-free auto-advance |
| `last_tab` | Last active tab index |
| `qexpand_{id}` | Per-group Queue tab expand state |
| `sexpand_{id}` | Per-group Schedule tab expand state |

---

## Related files

- `scheduler/EEVDFScheduler.kt` — scheduling algorithm
- `scheduler/TimerEngine.kt` — CountDownTimer wrapper
- `db/TaskRepository.kt` — data persistence
- `ui/AlarmForegroundService.kt` — notification and alarm sound
- `ui/AlarmScheduler.kt` — AlarmManager owner
- `model/TimerState.kt` — timer state machine
- `model/NoticePhase.kt` — NOTIFICATION task phases
