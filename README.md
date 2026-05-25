# EEVDF Task Scheduler — Android App

A full-featured Android task manager powered by the **EEVDF (Earliest Eligible Virtual Deadline First)** scheduling algorithm — the same algorithm used in the **Linux kernel** (since v6.6) for CPU process scheduling. Adapted here for real-life productivity and time management.

---

##  Project Structure

```
eevdf-scheduler-debug/
 app/
    build.gradle
    src/main/
        AndroidManifest.xml
        java/com/eevdf/scheduler/
            EEVDFApp.kt                        ← Application class
            backup/
               BackupManager.kt               ← JSON export/import
            db/
               TaskDao.kt                     ← Room DAO (tasks table)
               TaskDatabase.kt                ← Room DB, schema v14, migrations 1→14
               TaskRepository.kt              ← cgroup-aware data layer
               RunLogDao.kt                   ← DAO (run_log / run_daily / run_monthly)
               RunLogRepository.kt            ← 3-tier compacting analytics log
            model/
               Task.kt                        ← Task entity + quota/weight computed properties
               TimerState.kt                  ← Sealed timer state machine
               TaskTimerExt.kt                ← timerState read / withTimerState write
               NoticePhase.kt                 ← Sealed NOTIFICATION task state machine
               TaskDisplayItem.kt             ← Flat-list wrapper for RecyclerView
               RunLogEntry.kt                 ← Per-run analytics row (Tier 1)
               RunDailySummary.kt             ← Daily aggregate row (Tier 2)
               RunMonthlySummary.kt           ← Monthly aggregate row (Tier 3)
            scheduler/
               EEVDFScheduler.kt              ← Core EEVDF algorithm (pure Kotlin, zero Android)
               TimerEngine.kt                 ← CountDownTimer wrapper / LiveData emitter
            viewmodel/
               TaskViewModel.kt               ← MVVM brain: timer, EEVDF, interrupt, auto-mode
            adapter/
               TaskAdapter.kt                 ← RecyclerView adapter with diff + quota tint
            ui/
                MainActivity.kt                ← Main screen (Queue / Schedule / Done tabs)
                AddTaskActivity.kt             ← Add / Edit task form
                StatsActivity.kt               ← Analytics dashboard
                ProfileSettingsActivity.kt     ← Per-task profile / share settings
                SettingsActivity.kt            ← Global settings
                AutoSwitchActivity.kt          ← Call-detection auto-switch config
                SoundVibrationActivity.kt      ← Sound + vibration picker
                AlarmActivity.kt               ← Full-screen alarm UI (shown over lock screen)
                AlarmForegroundService.kt      ← Foreground service (sound, WakeLock, notification)
                AlarmScheduler.kt              ← Sole owner of AlarmManager calls
                AlarmState.kt                  ← Sealed alarm state (persisted to SharedPrefs)
                AlarmStopReceiver.kt           ← Notification "Stop" action handler
                TimerAlarmReceiver.kt          ← BroadcastReceiver for Doze-immune alarm fires
                CallEvents.kt                  ← Call state event definitions
                CallStateReceiver.kt           ← Phone-call BroadcastReceiver
                AutoSwitchPrefs.kt             ← SharedPreferences helpers for call switch
                NotificationHelper.kt          ← Notification channel registration
                SoundManager.kt                ← MediaPlayer / SoundPool playback
                VibrationManager.kt            ← Vibration pattern management
 module/
    doze_immune_alarm/                         ← Standalone reusable alarm module
        DozeImmuneTimer.kt
        TimerAlarmReceiver.kt
        README.md
        integration_guide.md
 build.gradle
 settings.gradle
 gradle.properties
```

>  **Full function-level docs:** [`docs/`](docs/) folder — one file per module.

---

##  Setup Instructions

### Requirements

- **Android Studio Hedgehog** (2023.1.1) or later
- **JDK 17**
- **Android SDK 34** (compileSdk), min SDK 26 (Android 8.0+)

### Steps

1. **Open in Android Studio**
   ```
   File → Open → select the eevdf-scheduler-debug/ folder
   ```

2. **Sync Gradle**
   - Android Studio will prompt to sync — click "Sync Now"
   - First sync downloads dependencies (~2–3 min)

3. **Run**
   - Connect an Android device (USB debug on) or launch an emulator
   - Click  Run

---

##  EEVDF Algorithm — How It Works

### Core Concepts

| Term | Meaning |
|------|---------|
| **Weight** | Effective task priority. `weight = internalWeight ?: priority.toDouble()` |
| **Virtual Runtime (vruntime)** | Weighted time consumed: `vruntime += secondsRan / weight` |
| **Average VRT** | Weighted mean vruntime across all active tasks |
| **Lag** | `lag = (avgVRT − vruntime) × weight`. Positive → task is owed time |
| **Eligible Time** | Virtual time when a task can be scheduled (`lag ≥ 0`) |
| **Virtual Deadline** | `eligibleTime + timeSliceSeconds / weight` |

### Scheduling Decision

```
1. Compute avgVruntime  (weighted average across all active tasks)
2. For each task: lag = (avgVRT − task.vruntime) × task.weight
3. Eligible tasks = those with lag ≥ 0
4. Among eligible tasks → pick SMALLEST virtualDeadline
5. If no eligible tasks → pick SMALLEST vruntime (fallback — prevents starvation)
```

### cgroup-Aware Hierarchical Scheduling

When **Groups mode** is on, scheduling is applied level by level — exactly like Linux cgroups:

```
Root EEVDF selects winning entry (group or leaf)
   If winner is a group → recurse into its children
        Apply EEVDF within that group
             Return the first winning leaf task
```

Empty groups are skipped via a visited-set to prevent infinite recursion.

### Real-Life Analogy

Three tasks sharing a time budget:
- **Task A** — Priority 8, 30-min slice → vruntime grows slowest (÷8)
- **Task B** — Priority 5, 20-min slice → moderate growth
- **Task C** — Priority 2, 60-min slice → fastest growth (÷2)

High-priority tasks are selected more often, but low-priority tasks always run — no starvation.

### Fairness Score

Uses **Jain's Fairness Index** on the vruntime distribution:
```
J = (Σ vrt_i)² / (n × Σ vrt_i²)   →   0.0 (unfair) to 1.0 (perfectly fair)
```

---

##  App Features

### Core Task Management
- Add tasks with name, description, priority (1–10), time slice (h/m/s), category, color
- Edit / delete / mark complete with undo (revert)
- Room database with schema v14 and a full migration chain (1 → 14)

### EEVDF Scheduler
- **"Schedule Next"** auto-selects the highest-priority eligible task (cgroup-aware)
- **Schedule tab** shows full EEVDF ordering with rank badges (#1, #2 …)
- Live vruntime and virtual deadline per task
- Fairness score + stats bar (active tasks, total weight, avg VRT)
- vruntime propagates up ancestor groups after every run

### Groups (cgroup Hierarchy)
- Tasks can be nested under group containers
- Per-tab independent expand/collapse (Queue vs. Schedule tabs)
- Group rows show child count + total runtime
- Toggle between flat mode and groups mode

### CPU Share Pinning
- Assign a fixed % share to any task (overrides EEVDF weight)
- Un-pinned tasks share the remaining % by weight
- Hierarchical: children share 100 % of their group's budget independently
- `internalWeight` auto-synced so pinned tasks integrate correctly with the float pool

### CPU Quota (Bandwidth Control)
- Per-task quota limits total runtime per period (mirrors Linux `cpu.cfs_quota_us`)
- Configurable period length (default 86 400 s = 1 day)
- Continuous linear decay: budget replenishes smoothly over the period
- Warning at 80 % consumed; throttle at 100 %
- Quota throttle propagates down the hierarchy — a child is blocked if its parent's budget is exhausted

### Built-in Timer
- Epoch-anchored countdown: `remaining = sliceMs − (accumulatedMs + (now − startTimeEpoch))`
- Immune to CountDownTimer drift — display values re-derived from epoch on every tick
-  Start / ⏸ Pause / ⏭ Skip / ↺ Reset
- vruntime updated on pause (partial credit) and skip
- Survives process death: `accumulatedMs` + `startTimeEpoch` written to DB on every pause

### NOTIFICATION Task Type (Notice tasks)
Multi-phase state machine — **Delay → Execute → Wait → (repeat) → Expire**:
- Pre-delay 0–300 s before countdown starts
- Rest period 0–300 s between cycles
- Repeat count: 0 = run once, N = run N+1 total cycles
- Button labels update per phase (Start / Cancel / Pause)

### ALARM Task Type
- Alarm sound + vibration on expiry
- Full-screen `AlarmActivity` shown over lock screen via `FLAG_SHOW_WHEN_LOCKED`
- Doze-immune: `AlarmManager.setAlarmClock()` fires even in deep sleep
- `AlarmState` (Idle / Scheduled / Ringing) persisted synchronously for crash recovery

### Interrupt Tasks (INT-A / INT-B)
- Two independent interrupt slots — A and B
- Tap INT → jump to INT-A destination; long-press INT → toggle active slot
- Saves current task before jumping; return restores it
- Used by call-detection integration (call → jump to call task automatically)

### Auto Mode
- Long-press "Next" → hands-free: timer expiry auto-advances to next EEVDF task and restarts
- Disables Global Rotate while active; restores on exit

### Global Rotate / Sibling Rotate
- **Global Rotate**: Next cycles all top-level active tasks round-robin
- **Sibling Rotate**: Next cycles siblings at the same group level

### Doze-Immune Alarm System
- `AlarmScheduler` is the **sole** `AlarmManager` owner — nothing else touches it
- `AlarmForegroundService` handles WakeLock, sound, notification
- Ghost-alarm guard: `TimerAlarmReceiver` checks `AlarmState` before waking the app

### Call Detection (Auto-Switch)
- Detects incoming/outgoing calls via `TelephonyManager`
- Auto-switches to a user-designated call task when call starts
- Returns to the previous task when call ends
- Requires READ_PHONE_STATE; configured in AutoSwitch settings

### Analytics (RunLog — Three Tiers)

| Tier | Table | TTL | Cap |
|------|-------|-----|-----|
| 1 | `run_log` | 30 days | 100 000 rows |
| 2 | `run_daily` | 365 days | 500 000 rows |
| 3 | `run_monthly` | Forever | ~18 MB |

Compaction runs lazily, throttled to once every 24 hours. Stats dashboard shows per-task totals, weekday patterns, context-switch counts.

### Backup / Restore
- Export all tasks (active + completed) to a JSON file
- Import replaces the database atomically (delete-all → re-insert)
- Backup v1 schema includes groupsEnabled, globalRotate, allowEdit settings
- Running state always exported as paused

---

##  Key Classes Quick Reference

### `EEVDFScheduler` (pure Kotlin object)
```kotlin
recalculate(tasks)                         // Recompute lag, eligibleTime, virtualDeadline
selectNext(tasks)                          // Pick next task by EEVDF policy
getScheduleOrder(tasks)                    // Full ranked list
updateVruntime(task, secondsRan)           // Credit elapsed time + runCount++
computeShares(tasks, groupsEnabled)        // CPU share % per task (flat or hierarchical)
syncPinnedWeights(tasks)                   // Re-derive internalWeight for pinned tasks
calcPinnedWeight(targetShare, ...)         // Back-calculate weight for a target % share
otherPinnedTotal(tasks, excludeId)         // Sum of other pinned shares (validation helper)
getStats(tasks)                            // SchedulerStats: fairness, counts, avgVRT
```

### `TimerState` (sealed)
```
Idle resume() Running pause() Paused
                       expire() Expired
any reset() Idle
```
All math (elapsedMs, remainingMs, remainingSecs, progress, isExpired) is pure and side-effect-free.

### `TimerEngine`
```kotlin
start(task)         // Attach CountDownTimer; idempotent on same task
pause()             // Stop ticker, return updated Task (caller persists)
reset()             // Return to Idle, return updated Task
restoreFromDb(task) // Re-attach after process death; posts expiredTask if already elapsed
clear()             // Hard stop, no return value
```

### `TaskViewModel` (selection)
```kotlin
addTask(task)              scheduleNext()         startTimer()
pauseTimer()               skipTask()             resetTimer()
resetSlice(task)           markCompleted(task)    deleteTask(task)
revertTask(task)           jumpToInterrupt()      toggleAutoMode()
toggleGroupsEnabled()      toggleGlobalRotate()   toggleAllowEdit()
toggleAutoScroll()         assignInterruptTask()  assignInterruptTaskB()
handleCallStarted()        handleCallEnded()
```

### `TaskRepository`
```kotlin
updateVruntimeAfterRun(task, secs)  // Leaf + ancestor propagation + quota accounting + RunLog
selectNextTask()                     // Hierarchical cgroup-aware EEVDF selection
setInterruptTask(task, slot)         // Atomically set INT-A or INT-B
getAllTasksForBackup()               // Full snapshot for JSON export
restoreFromBackup(tasks)            // Atomic replace
```

---

##  Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| Material Components | 1.11.0 | UI components |
| Room | 2.6.1 | SQLite ORM + migrations |
| Lifecycle ViewModel | 2.7.0 | MVVM + LiveData |
| Coroutines | 1.7.3 | Async DB / IO |
| RecyclerView | 1.3.2 | Task list |
| CardView | 1.0.0 | Task cards |

---

##  Database Schema (v14)

| Table | Purpose |
|-------|---------|
| `tasks` | All tasks and group containers (scheduler state, quota, timer state) |
| `run_log` | Per-run session detail — Tier 1 analytics |
| `run_daily` | Daily aggregates — Tier 2 analytics |
| `run_monthly` | Monthly aggregates — Tier 3 analytics |

Migration chain: `1→2→3→4→5→6→7→8→9→10→11→12→13→14`

See [`docs/database.md`](docs/database.md) for each migration's rationale and SQL.

---

##  Documentation Index

| File | Contents |
|------|---------|
| [`docs/architecture.md`](docs/architecture.md) | System map, all data flows, threading model, process-death recovery, invariants |
| [`docs/scheduler.md`](docs/scheduler.md) | `EEVDFScheduler` — every function, algorithm details, share pinning |
| [`docs/timer.md`](docs/timer.md) | `TimerState`, `TimerEngine`, `TaskTimerExt` — timer state machine |
| [`docs/models.md`](docs/models.md) | `Task`, `NoticePhase`, `TaskDisplayItem`, all computed properties |
| [`docs/database.md`](docs/database.md) | `TaskDatabase` migrations 1→14, `TaskDao`, `RunLogDao`, schema history |
| [`docs/repository.md`](docs/repository.md) | `TaskRepository`, `RunLogRepository` — data layer contracts |
| [`docs/viewmodel.md`](docs/viewmodel.md) | `TaskViewModel` — all public functions, LiveData, feature flags |
| [`docs/alarm-system.md`](docs/alarm-system.md) | `AlarmState`, `AlarmScheduler`, `AlarmForegroundService`, ghost-alarm guard |
| [`docs/run-log.md`](docs/run-log.md) | Three-tier RunLog system, compaction pipeline, analytics queries |
| [`docs/features.md`](docs/features.md) | Feature guide: groups, quota, interrupt, auto-mode, notice type, backup, call-detection |
| [`docs/ui-activities.md`](docs/ui-activities.md) | `MainActivity`, `TaskAdapter`, `AddTaskActivity`, `StatsActivity`, `BackupManager` |
| [`docs/module-doze-alarm.md`](docs/module-doze-alarm.md) | Standalone `doze_immune_alarm` module — integration guide and API |

---

##  Possible Extensions

- **Recurring tasks** — reset vruntime and re-queue on completion
- **Deadline-aware scheduling** — add absolute deadlines for hard constraints
- **Pomodoro mode** — NOTIFICATION task preset (25/5 work-break)
- **Statistics charts** — render RunLog data with MPAndroidChart
- **Home screen widget** — live countdown widget
- **Google Calendar sync** — import events as tasks
- **Export analytics** — CSV export of run_log / run_daily tables

---

##  References

- [Linux Kernel EEVDF patch (Peter Zijlstra, 2023)](https://lwn.net/Articles/925371/)
- [Original EEVDF paper — Stoica, Abdel-Wahab, Jeffay (1995)](https://citeseerx.ist.psu.edu/doc/10.1.1.43.5347)
- [Jain's Fairness Index](https://en.wikipedia.org/wiki/Fairness_measure)
- [Linux CFS Bandwidth Control](https://www.kernel.org/doc/html/latest/scheduler/sched-bwc.html)
- [Android AlarmManager Doze behaviour](https://developer.android.com/training/scheduling/alarms)
