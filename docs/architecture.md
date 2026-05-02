# Architecture Overview

A complete map of how components interact — useful when adding a new feature or debugging an unexpected behaviour.

---

## Layer Map

```

  UI LAYER                                                            
                                                                      
  MainActivity           AddTaskActivity       StatsActivity          
  AlarmActivity          AutoSwitchActivity    SettingsActivity       
  ProfileSettingsActivity SoundVibrationActivity                      
                                                                      
  TaskAdapter  (RecyclerView, DiffUtil, partial-rebind quota tick)    

                                observe LiveData
                                call viewModel.fn()

  VIEWMODEL LAYER                                                     
                                                                      
  TaskViewModel                                                       
   Feature flags (groupsEnabled, globalRotate, allowEdit, auto…)    
   Timer orchestration  (startTimer / pauseTimer / skipTask / …)    
   EEVDF scheduling     (scheduleNext / nextSibling / rotateGlobal) 
   Interrupt management (jumpToInterrupt / handleCallStarted / …)   
   Flat-list building   (buildQueueList / buildScheduleList)        
   LiveData outputs     (currentTask / timerSeconds / scheduleOrder)

                                                       
                                                       
      
  SCHEDULER          TIMER ENGINE             ALARM LAYER         
                                                                  
 EEVDFScheduler     TimerEngine              AlarmScheduler       
 (pure Kotlin)      (CountDownTimer +        (AlarmManager owner) 
                     epoch anchoring)                             
 recalculate()      start / pause /          AlarmForeground-     
 selectNext()       reset / restore          Service              
 computeShares()                             (WakeLock, sound,    
 getStats()         tickSeconds LiveData      notification)       
    expiredTask LiveData                          
                        AlarmState (sealed,  
                                                 disk-persisted)     

  REPOSITORY LAYER                                                  
                                                                    
  TaskRepository                    RunLogRepository               
   insert / update / delete        recordRun()                 
   updateVruntimeAfterRun          compactLogToDaily()         
      (cgroup vruntime + quota)     compactDailyToMonthly()     
   selectNextTask() (cgroup-aware)                                
   restoreFromBackup / backup                                     

                     

  DATABASE LAYER                                                    
                                                                    
  TaskDatabase (Room, v14)                                          
   TaskDao        → tasks table                                   
   RunLogDao      → run_log / run_daily / run_monthly tables      

```

---

## Data Flows

### 1. Add a new task

```
AddTaskActivity.saveTask()
   viewModel.addTask(task)
         repository.insert(task)
               EEVDFScheduler.recalculate(all)   [sets lag, eligibleTime, vDeadline]
               dao.insert(task) + dao.update(each)
         viewModel.syncPinnedWeights()
               EEVDFScheduler.syncPinnedWeights(all)
               repository.updateBatch(changed)
   MainActivity observes activeTasks → adapter.submitList() → render
```

---

### 2. Start timer

```
MainActivity: btnStart.click()
   viewModel.startTimer()
         task.withTimerState(TimerState.resume(…))  [epoch columns set]
         repository.update(task)                     [write to DB]
         engine.start(task)                          [attach CountDownTimer]
             every 1s: engine posts tickSeconds
                       viewModel observes → _timerSeconds.value
                                 MainActivity updates countdown label
         AlarmForegroundService.timerStart(ctx, …)
               AlarmScheduler.schedule(ctx, …)       [setAlarmClock]
               starts ForegroundService with ACTION_TIMER_START
```

---

### 3. Timer expires (screen on)

```
TimerEngine.CountDownTimer.onFinish()
   engine posts expiredTask LiveData
         viewModel observes expiredTask → onTimerFinished(task)
               applyVruntimeUpdate(sliceSeconds)
                   repository.updateVruntimeAfterRun(task, secs)
                         RunLogRepository.recordRun(taskId, …)
                         EEVDFScheduler.updateVruntime(task, secs)
                         propagate vruntime up ancestor chain
                         EEVDFScheduler.recalculate(allActive)
               repository.update(task)
               AlarmForegroundService.timerExpire(ctx, …)  [show expired notification]
               if autoMode: viewModel.scheduleNext() → viewModel.startTimer()
```

---

### 4. Timer expires (screen off / Doze)

```
[App in background, screen off > 30s]

AlarmManager fires TimerAlarmReceiver (Doze-immune)
   AlarmScheduler.onAlarmFired(ctx)
         check AlarmState: if NOT Scheduled → ghost alarm, drop
         write AlarmState = Ringing(…)
               AlarmForegroundService.timerExpire(ctx, …)
                     acquire WakeLock
                     SoundManager.playAlarm()
                     post high-importance notification with setFullScreenIntent
                           AlarmActivity shown over lock screen

[User opens app]
   ViewModel.init()
         TimerEngine.restoreFromDb(runningTask)
               remaining > 0: re-attach CountDownTimer
               remaining = 0: post expiredTask immediately
                     onTimerFinished(task)  [vruntime update etc.]
```

---

### 5. Schedule Next (EEVDF)

```
MainActivity: btnScheduleNext.click()
   viewModel.scheduleNext()
         repository.selectNextTask()
               selectNextCgroup(allActive, parentId = null)
                     EEVDFScheduler.recalculate(rootLevel)
                     EEVDFScheduler.selectNext(rootLevel) → winner
                     if winner.isGroup → recurse into children
                           if no eligible children → try next sibling
         viewModel.setCurrentTask(winner)
               _currentTask.value = winner
                     MainActivity observes → update timer card
```

---

### 6. RunLog compaction

```
repository.updateVruntimeAfterRun(task, secs)
   runLogRepository.recordRun(taskId, startEpoch, secs)
         dao.insertLog(RunLogEntry)
         enforce 100K cap (deleteOldest)
         maybeCompact()    [throttled: once per 24h]
               compactLogToDaily()
                   entries older than 30d → group by (taskId, dayEpoch)
                   dao.upsertDaily(RunDailySummary) for each group
                   dao.deleteEntriesOlderThan(cutoff)
               compactDailyToMonthly()
                     rows older than 365d → group by (taskId, monthEpoch)
                     dao.upsertMonthly(RunMonthlySummary) for each group
                     dao.deleteDailyOlderThan(cutoff)
```

---

## SharedPreferences Files

| File name | Owner | Contents |
|-----------|-------|---------|
| `eevdf_prefs` | `TaskViewModel` | Feature flags: groupsEnabled, globalRotate, allowEdit, autoScroll, autoMode, lastTab, per-group expand states |
| `eevdf_alarm_state_v2` | `AlarmState` | Alarm state (Idle/Scheduled/Ringing), task name, trigger epoch |
| `auto_switch_prefs` | `AutoSwitchPrefs` | Call detection enable, call task ID/name, last phone state |
| `eevdf_run_log_prefs` | `RunLogRepository` | `last_compact_epoch` throttle timestamp |

---

## Threading Model

| Layer | Thread |
|-------|--------|
| Room DAO calls | Must be on `Dispatchers.IO` (enforced by Room) |
| Repository methods | Suspending functions; callers use `viewModelScope.launch(IO)` |
| ViewModel updates to LiveData | Posted on `Dispatchers.Main` via `postValue()` |
| TimerEngine CountDownTimer | Ticks on main thread |
| AlarmScheduler | Called on main thread (AlarmManager calls are instant) |
| AlarmForegroundService | Runs in its own thread; `onStartCommand` on main |
| BroadcastReceivers | `onReceive` on main thread; must complete quickly |

---

## Process Death Recovery

The app can be killed at any moment. All recoverable state must be persisted before the operation that would be lost.

| State | Persistence | Recovery point |
|-------|-------------|---------------|
| Timer running | `accumulatedMs + startTimeEpoch` in `tasks` DB | `ViewModel.init()` → `TimerEngine.restoreFromDb()` |
| Alarm scheduled | `AlarmState` in SharedPrefs (`commit()`) | `TimerAlarmReceiver.onAlarmFired()` |
| Current task | `isRunning = 1` in `tasks` DB | `ViewModel.init()` queries `getRunningTask()` |
| Feature toggles | SharedPreferences | `ViewModel.init()` reads prefs |
| Interrupt tasks | `isInterrupt + interruptSlot` in `tasks` DB | `ViewModel.init()` reads `getInterruptTask()` / `getInterruptTaskB()` |
| Group expand state | SharedPreferences (per group id) | `buildQueueList` / `buildScheduleList` |

The **write-before-act** rule: any durable state change writes to disk *before* the operation it's protecting. Example: `AlarmState.write(Scheduled)` is called before `AlarmManager.setAlarmClock()`. If the process dies between the two calls, the state on disk is correct and the recovery path will re-schedule.

---

## Adding a New Feature — End-to-End Checklist

```
1. SCHEMA (if new data needed)
    Add field to Task.kt with default value
    Increment TaskDatabase.version
    Write MIGRATION_N_M with ALTER TABLE
    Register migration in addMigrations(...)
    Update docs/database.md

2. PERSISTENCE (if new queries needed)
    Add @Query to TaskDao.kt
    Add wrapper method to TaskRepository.kt (suspend + Dispatchers.IO)

3. SCHEDULER (if affects task ordering)
    Modify EEVDFScheduler.kt (pure Kotlin, write unit tests)
    Call recalculate() or syncPinnedWeights() after mutation
    Update docs/scheduler.md

4. VIEWMODEL (always)
    Add MutableLiveData<T> + public LiveData<T> getter
    Add public fun for each user action (toggle, set, clear)
    Persist toggle to SharedPreferences if it survives process death
    Update docs/viewmodel.md

5. UI (always)
    Observe the new LiveData in MainActivity.setupObservers()
    Wire button/gesture to the new ViewModel function
    Update TaskDisplayItem if the list needs new per-item data
    Update TaskAdapter.onBindViewHolder if new views are needed
    Update docs/ui-activities.md

6. BACKUP (if new field should be exported)
    Add put(...) in BackupManager.taskToJson
    Add optXxx(...) in BackupManager.taskFromJson with safe default
    Document what version the field was added

7. DOCS
    Update docs/features.md with the feature description
    Update README.md feature table
    Update this file (architecture.md) if a new component is added
```

---

## Invariants — Never Break These

| Invariant | Why |
|-----------|-----|
| `AlarmScheduler` is the only class that calls `AlarmManager` | Prevents ghost alarms and cancellation races |
| `AlarmState.write()` uses `commit()` not `apply()` | State on disk must be authoritative before function returns |
| `withTimerState()` is the only legal way to write timer columns | Prevents `copy(isRunning=false)` forgetting to clear `startTimeEpoch` |
| `repository.updateVruntimeAfterRun()` always follows a run | vruntime integrity and RunLog completeness |
| `recalculate()` called after every vruntime mutation | Lag / eligibleTime / virtualDeadline must always be consistent |
| `syncPinnedWeights()` called after every task list mutation | `internalWeight` must always reflect current share allocation |
| `AlarmForegroundService.onDestroy()` does NOT cancel the alarm | Cancelling on destroy removes the alarm on process death |
| No DAO calls outside `Dispatchers.IO` | Room enforces this at runtime but it must also be a code discipline |
