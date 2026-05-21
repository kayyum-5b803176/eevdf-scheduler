package com.eevdf.scheduler.viewmodel.task

import android.app.Application
import android.content.Context
import android.os.CountDownTimer
import androidx.lifecycle.*
import com.eevdf.scheduler.db.TaskDatabase
import com.eevdf.scheduler.db.TaskRepository
import com.eevdf.scheduler.model.task.Task
import com.eevdf.scheduler.model.notice.NoticePhase
import com.eevdf.scheduler.model.runlog.RunSession
import com.eevdf.scheduler.model.timer.TimerStartEvent
import com.eevdf.scheduler.model.timer.TimerState
import com.eevdf.scheduler.model.timer.TimerCardAction
import com.eevdf.scheduler.model.timer.NextButtonState
import com.eevdf.scheduler.model.timer.timerState
import com.eevdf.scheduler.model.timer.withTimerState
import com.eevdf.scheduler.model.task.TaskDisplayItem
import com.eevdf.scheduler.scheduler.EEVDFScheduler
import com.eevdf.scheduler.scheduler.SchedulerStats
import com.eevdf.scheduler.scheduler.TimerEngine
import com.eevdf.scheduler.ui.alarm.AlarmForegroundService
import com.eevdf.scheduler.ui.alarm.AlarmScheduler
import com.eevdf.scheduler.ui.alarm.AlarmState
import kotlinx.coroutines.launch
import com.eevdf.scheduler.sync.MultiUserSyncManager
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.eevdf.scheduler.viewmodel.settings.TaskSettingsDelegate
import com.eevdf.scheduler.viewmodel.timer.TaskInterruptDelegate
import com.eevdf.scheduler.viewmodel.autoswitch.TaskCallSwitchDelegate
import com.eevdf.scheduler.viewmodel.notice.TaskNoticeStateMachine
import com.eevdf.scheduler.viewmodel.scheduler.TaskSchedulerDelegate

/**
 * Root coordinator ViewModel.
 *
 * ── Responsibility split ──────────────────────────────────────────────────────
 *
 *  This class owns:
 *   • Shared mutable state (LiveData) that crosses domain boundaries.
 *   • The TimerEngine integration (tick / expire observers).
 *   • Core timer lifecycle: startTimer / pauseTimer / resetTimer / onTimerFinished.
 *   • CRUD operations and pinned-weight sync.
 *   • Overrun counter + alarm dismissal.
 *   • App-kill / startup recovery (init{}).
 *   • Public API facade — thin delegation to domain delegates.
 *
 *  Each domain lives in its own file:
 *   • [TaskSettingsDelegate]    — settings toggles + tab persistence
 *   • [TaskGroupExpandDelegate] — per-tab group expand / collapse state
 *   • [TaskInterruptDelegate]   — INT-A / INT-B slot logic
 *   • [TaskCallSwitchDelegate]  — phone-call auto-switch
 *   • [TaskNoticeStateMachine]  — NOTIFICATION task phase state machine
 *   • [TaskSchedulerDelegate]   — rotation, auto-next, schedule-next
 *   • [TaskListBuilderDelegate] — flat Queue / Schedule list construction
 *   • [TaskSortHelper]          — shared number-extraction sort utility
 *
 * ── Adding a feature to a domain ─────────────────────────────────────────────
 *
 *  Edit only the relevant delegate file.  Expose the new public method here
 *  as a one-liner facade if the UI needs to call it.
 */
class TaskViewModel(application: Application) : AndroidViewModel(application) {

    // ── Shared preferences (internal so delegates can access prefs directly) ──

    internal val prefs = application.getSharedPreferences("eevdf_prefs", Context.MODE_PRIVATE)

    /** Convenience accessor for delegates that need an Application context. */
    internal val app: Application = application

    // ── Repository + DB-backed LiveData ───────────────────────────────────────

    internal val repository: TaskRepository
    val allTasks:       LiveData<List<Task>>
    val activeTasks:    LiveData<List<Task>>
    val completedTasks: LiveData<List<Task>>

    /** Groups available for parent selection in AddTaskActivity. */
    val activeGroups: LiveData<List<Task>>

    // ── Shared mutable state (internal so delegates can post to them) ─────────

    internal val _currentTask          = MutableLiveData<Task?>(null)
    val           currentTask: LiveData<Task?> = _currentTask

    internal val _timerSeconds         = MutableLiveData<Long>()
    val           timerSeconds: LiveData<Long> = _timerSeconds

    internal val _timerRunning         = MutableLiveData<Boolean>(false)
    val           timerRunning: LiveData<Boolean> = _timerRunning

    internal val _scheduleOrder        = MutableLiveData<List<Task>>(emptyList())
    val           scheduleOrder: LiveData<List<Task>> = _scheduleOrder

    internal val _stats                = MutableLiveData<SchedulerStats>()
    val           stats: LiveData<SchedulerStats> = _stats

    internal val _toastMessage         = MutableLiveData<String?>(null)
    val           toastMessage: LiveData<String?> = _toastMessage

    internal val _alarmTaskName        = MutableLiveData<String?>(null)
    val           alarmTaskName: LiveData<String?> = _alarmTaskName

    internal val _alarmElapsedSeconds  = MutableLiveData<Long>(0L)
    val           alarmElapsedSeconds: LiveData<Long> = _alarmElapsedSeconds

    // ── Auto mode state ───────────────────────────────────────────────────────

    /** Mirrors MainActivity's active tab so onTimerFinished can auto-advance correctly. */
    var activeTab: Int = 0

    /** Set by onTimerFinished when auto mode queues the next task; consumed by MainActivity. */
    internal var pendingAutoStart = false

    /**
     * Holds the reset-state task while the expire card is visible.
     * Consumed in [stopAlarmSound] to reopen the timer card with the default timer.
     */
    internal var taskToRestoreAfterExpire: Task? = null

    // ── Timer engine ──────────────────────────────────────────────────────────

    internal val timerEngine = TimerEngine()

    // Named observer references — removed in onCleared() to prevent accumulation.
    private var tickObserver:           Observer<Long> = Observer {}
    private var expiredObserver:        Observer<Task> = Observer {}
    private var expiredSessionObserver: Observer<RunSession> = Observer {}

    // ── Overrun counter ───────────────────────────────────────────────────────

    internal var overrunTimer: CountDownTimer? = null

    // ── Domain delegates ──────────────────────────────────────────────────────

    internal val settings    = TaskSettingsDelegate(prefs)
    internal val groupExpand = TaskGroupExpandDelegate(prefs, this)
    internal val interrupt   = TaskInterruptDelegate(this)
    internal val callSwitch  = TaskCallSwitchDelegate(this)
    internal val notice      = TaskNoticeStateMachine(this)
    internal val scheduler   = TaskSchedulerDelegate(this)
    internal val listBuilder = TaskListBuilderDelegate(this)

    // ── Flat task lists (built by listBuilder) ────────────────────────────────

    var flatActiveTasks:   MediatorLiveData<List<TaskDisplayItem>> = MediatorLiveData()
    var flatScheduleOrder: MediatorLiveData<List<TaskDisplayItem>> = MediatorLiveData()

    // ── Derived button-state LiveData ─────────────────────────────────────────
    //
    // Each MediatorLiveData combines ALL inputs that affect a button into ONE
    // settled value — no race window between separate LiveData reads.

    /**
     * Start / Pause / Cancel button state.
     * Derivation priority: task==null → notice phase → timerRunning.
     */
    val timerCardAction: MediatorLiveData<TimerCardAction> =
        MediatorLiveData<TimerCardAction>().apply {
            fun derive() {
                val phase   = notice.noticePhase.value ?: NoticePhase.Idle
                val running = _timerRunning.value       ?: false
                val task    = _currentTask.value
                value = when {
                    task == null                 -> TimerCardAction.Unavailable
                    phase is NoticePhase.Delay   -> TimerCardAction.Cancel
                    phase is NoticePhase.Wait    -> TimerCardAction.Cancel
                    phase is NoticePhase.Execute -> TimerCardAction.Pause
                    phase is NoticePhase.Expired -> TimerCardAction.Unavailable
                    running                      -> TimerCardAction.Pause
                    else                         -> TimerCardAction.Start
                }
            }
            // Sources wired in init{} after notice is ready
            addSource(notice.noticePhase) { derive() }
            addSource(_timerRunning)      { derive() }
            addSource(_currentTask)       { derive() }
        }

    /** INT button state — slot label + whether a task is assigned. */
    val intButtonState: MediatorLiveData<com.eevdf.scheduler.model.timer.IntButtonState>
        get() = interrupt.intButtonState

    /** Next / Auto button label. */
    val nextButtonState: MediatorLiveData<NextButtonState> =
        MediatorLiveData<NextButtonState>().apply {
            fun derive() {
                value = if (settings.autoMode.value == true) NextButtonState.Auto
                        else NextButtonState.Next
            }
            addSource(settings.autoMode) { derive() }
        }

    // ── init ──────────────────────────────────────────────────────────────────

    init {
        val db = TaskDatabase.getDatabase(application)
        repository     = TaskRepository(db.taskDao(), application)
        allTasks       = repository.allTasks
        activeTasks    = repository.activeTasks
        completedTasks = repository.completedTasks
        activeGroups   = repository.activeGroups

        // ── Wire TimerEngine outputs via named observers ───────────────────────
        tickObserver = Observer { remainingSecs: Long ->
            _timerSeconds.postValue(remainingSecs)
            _currentTask.value?.let { t ->
                _currentTask.postValue(t.copy(remainingSeconds = remainingSecs))
            }
        }
        var pendingExpiredSession: RunSession? = null
        expiredSessionObserver = Observer { session: RunSession ->
            pendingExpiredSession = session
        }
        expiredObserver = Observer { expired: Task ->
            val session = pendingExpiredSession
            pendingExpiredSession = null
            _timerRunning.postValue(false)
            _currentTask.value = expired
            onTimerFinished(session = session)
        }
        timerEngine.tickSeconds.observeForever(tickObserver)
        timerEngine.expiredSession.observeForever(expiredSessionObserver)
        timerEngine.expiredTask.observeForever(expiredObserver)

        // ── Startup / app-kill recovery ────────────────────────────────────────
        viewModelScope.launch {
            interrupt.postInterruptTask(repository.getInterruptTask())
            interrupt.postInterruptTaskB(repository.getInterruptTaskB())

            // Step 1: check if alarm is already ringing (app killed mid-alarm)
            val alarmState = AlarmScheduler.currentState(application)
            if (alarmState is AlarmState.Ringing) {
                val elapsedSinceExpiry =
                    ((System.currentTimeMillis() - alarmState.firedEpoch) / 1000L)
                        .coerceAtLeast(0L)
                _alarmTaskName.postValue(alarmState.taskName)
                _alarmElapsedSeconds.postValue(elapsedSinceExpiry)
                startInAppOverrunCounter(alarmState.taskName, elapsedSinceExpiry)
                return@launch
            }

            // Step 2: check if a task was mid-run when app was killed
            val running = repository.getRunningTask()
            if (running != null) {
                val nowMs       = System.currentTimeMillis()
                val secondsLeft = TimerState.remainingSecs(
                    running.timerState, running.timeSliceSeconds, nowMs
                )
                if (secondsLeft > 0L) {
                    val corrected = running.copy(remainingSeconds = secondsLeft)
                    repository.update(corrected)
                    _currentTask.postValue(corrected)
                    _timerSeconds.postValue(secondsLeft)
                    _timerRunning.postValue(true)
                    timerEngine.restoreFromDb(corrected)
                } else {
                    val state         = running.timerState as TimerState.Running
                    val expiryEpochMs = state.startTimeEpoch +
                        running.timeSliceSeconds * 1000L - state.accumulatedMs
                    val session = RunSession.Recovered(
                        taskId       = running.id,
                        startEpochMs = state.startTimeEpoch,
                        endEpochMs   = expiryEpochMs
                    )
                    onTimerFinished(running, session = session)
                }
            }
        }

        // ── Build flat lists (must come after repository + delegates are ready) ─
        listBuilder.setup()
        flatActiveTasks   = listBuilder.flatActiveTasks
        flatScheduleOrder = listBuilder.flatScheduleOrder

        // ── Multi-user sync ───────────────────────────────────────────────────
        MultiUserSyncManager.init(application)

        // When a remote sync import completes, the local DB file has been
        // replaced. Signal MainActivity to restart the app so Room opens the
        // new file with a clean singleton (same path as manual DB import).
        MultiUserSyncManager.importEvent.observeForever { _ ->
            _restartNeeded.postValue(Unit)
        }
    }

    // =========================================================================
    // CRUD
    // =========================================================================

    /**
     * After any task mutation the float-pool changes for every sibling.
     * Re-derives internalWeight for all pinned tasks and batch-persists only
     * the ones that actually changed.
     */
    private suspend fun syncPinnedWeights() {
        val tasks   = repository.getActiveTasksSync()
        val changed = EEVDFScheduler.syncPinnedWeights(tasks)
        if (changed.isNotEmpty()) repository.updateBatch(changed)
    }

    fun addTask(task: Task) = viewModelScope.launch {
        repository.insert(task)
        syncPinnedWeights()
        refreshSchedule()
        triggerSyncExport()
        _toastMessage.postValue("Task \"${task.name}\" added to scheduler")
    }

    fun updateTask(task: Task) = viewModelScope.launch {
        repository.update(task)
        syncPinnedWeights()
        refreshSchedule()
        triggerSyncExport()
    }

    fun deleteTask(task: Task) = viewModelScope.launch {
        if (task.id == _currentTask.value?.id) {
            pauseTimer()
            _currentTask.postValue(null)
        }
        repository.delete(task)
        syncPinnedWeights()
        refreshSchedule()
        triggerSyncExport()
        _toastMessage.postValue("Task \"${task.name}\" deleted")
    }

    /** Moves a completed task back to the active queue, restoring its timer slice. */
    fun revertTask(task: Task) = viewModelScope.launch {
        val reverted = task.copy(isCompleted = false).withTimerState(TimerState.reset())
        repository.update(reverted)
        syncPinnedWeights()
    }

    fun markCompleted(task: Task) = viewModelScope.launch {
        triggerSyncExport()               // notify other users: task completed
        if (task.id == _currentTask.value?.id) stopTimer(completed = true)
        else repository.markCompleted(task)
        syncPinnedWeights()
        refreshSchedule()
    }

    fun clearCompleted() = viewModelScope.launch { repository.clearCompleted() }

    fun clearToast() { _toastMessage.value = null }

    fun toggleGroupExpanded(group: Task) = viewModelScope.launch {
        val updated = group.copy(isGroupExpanded = !group.isGroupExpanded)
        repository.update(updated)
    }

    /** Direct DB lookup used by AddTaskActivity to reliably load a task for editing. */
    suspend fun getTaskById(id: String): Task? = repository.getTaskById(id)

    // =========================================================================
    // Timer lifecycle
    // =========================================================================

    fun startTimer() {
        if (_timerRunning.value == true ||
            notice.isDelayRunning()      ||
            notice.isWaitRunning()) return

        val task      = _currentTask.value ?: return
        val remaining = _timerSeconds.value ?: task.remainingSeconds

        if (remaining <= 0) {
            // Slice already expired — engine's onFinish() never fired (user paused
            // at 0:00 before CountDownTimer could call back).
            timerEngine.clear()
            onTimerFinished(task, session = null)
            return
        }

        val delaySecs = if (task.taskType == "NOTIFICATION") task.notificationDelaySeconds else 0L

        notice.initSession(task)

        // Resume-type INITIAL: always restart execute from the full slice (0 elapsed).
        //
        // Two things must be corrected, not just the countdown seconds:
        //   1. effectiveRemaining — pass timeSliceSeconds so the engine countdown
        //      ticks down from the full duration.
        //   2. effectiveTask — reset timerState to Idle (accumulatedMs = 0) so
        //      TimerStartEvent.from() does NOT carry the accumulated 15 s from the
        //      previous Paused state into the new Running state.  Without this,
        //      the engine starts with accumulatedMs=15000 and the progress bar /
        //      remaining display begins at (30s − 15s) = 15 s even when we passed
        //      secs=30.  Resetting to Idle gives accumulatedMs=0 → full 30 s.
        //
        // Only applies to true resumes (Paused state); fresh starts and
        // pending-wait paths are unaffected.
        val isInitialResume = task.taskType == "NOTIFICATION" &&
            task.notificationResumeType == "INITIAL" &&
            task.timerState is TimerState.Paused &&
            !notice.hasPendingWait()

        val effectiveTask      = if (isInitialResume) task.withTimerState(TimerState.reset()) else task
        val effectiveRemaining = if (isInitialResume) task.timeSliceSeconds else remaining

        if (delaySecs > 0) {
            notice.startDelayPhase(effectiveTask, effectiveRemaining, delaySecs)
        } else if (task.taskType == "NOTIFICATION") {
            // Route through resolveAfterDelay so a pending wait-cancel is handled
            // (timestamp resume or skip to next execute) even when there is no delay.
            notice.resolveAfterDelay(effectiveTask, effectiveRemaining)
        } else {
            startActualTimer(effectiveTask, effectiveRemaining)
        }
    }

    /**
     * Builds a Running state, persists it to DB, then hands off to [timerEngine].
     * Single entry point for starting an execute countdown — called by the ViewModel
     * directly and by [TaskNoticeStateMachine.startExecutePhase].
     */
    /**
     * @param remaining  Execute-slice seconds remaining — drives the engine countdown and
     *                   the notification chronometer.
     * @param alarmSecs  Total seconds until the AlarmManager should fire.  For NOTIFICATION
     *                   tasks [TaskNoticeStateMachine.startExecutePhase] passes the pre-computed
     *                   sum of all remaining (execute + wait) cycles so the alarm is set ONCE
     *                   and never cancelled mid-cycle.  Defaults to [remaining] for all other
     *                   task types (alarm fires when the single execute slice expires).
     */
    internal fun startActualTimer(task: Task, remaining: Long, alarmSecs: Long = remaining) {
        val nowMs   = System.currentTimeMillis()
        val event   = TimerStartEvent.from(task.timerState, nowMs)
        val running = event.toRunning
        val updated = task.withTimerState(running)

        _timerRunning.value = true
        // Update _currentTask with the Running state so tick observer copies carry
        // the correct startTimeEpoch (needed for live progressPercent calculation).
        _currentTask.value = updated
        viewModelScope.launch {
            repository.update(updated)
            triggerSyncExport()          // notify other users: timer started
        }
        AlarmForegroundService.timerStart(app, task.name, remaining, task.taskType, alarmSecs)
        timerEngine.start(updated)
    }

    fun pauseTimer() {
        // Notice-phase cancellations take priority
        if (notice.isDelayRunning()) { notice.cancelDelayPhase(); return }
        if (notice.isWaitRunning())  { notice.cancelWaitPhase();  return }

        stopAlarmSound()

        val nowMs   = System.currentTimeMillis()
        val result  = timerEngine.pause(nowMs)
        val session = result?.second   // RunSession.Paused; null if engine was idle
        _timerRunning.value = false

        val task = _currentTask.value
        if (result != null) {
            val paused = result.first
            _currentTask.value  = paused
            _timerSeconds.value = paused.remainingSeconds
            viewModelScope.launch { repository.update(paused) }
            // Clear the engine so stale activeTask can't overwrite _currentTask on
            // the next pauseTimer() call (fixes Next-stuck / random-jump bug).
            timerEngine.clear()
        }

        if (task != null && task.taskType == "NOTIFICATION") {
            notice.handlePause(task.id, session?.wallClockSeconds ?: 0L, nowMs)
        } else if (session != null && session.wallClockSeconds > 0) {
            applyVruntimeUpdate(session)
        }
        AlarmForegroundService.timerPause(app)
        triggerSyncExport()               // notify other users: timer paused
    }

    fun resetTimer() {
        pauseTimer()
        timerEngine.clear()
        notice.resetState()
        val task  = _currentTask.value ?: return
        val reset = task.withTimerState(TimerState.reset())
        _timerSeconds.value = reset.remainingSeconds
        viewModelScope.launch {
            repository.update(reset)
            _currentTask.postValue(reset)
        }
    }

    /** Resets the timer slice of any task back to its default [timeSliceSeconds]. */
    fun resetSlice(task: Task) {
        if (task.id == _currentTask.value?.id) { resetTimer(); return }
        viewModelScope.launch { repository.update(task.withTimerState(TimerState.reset())) }
    }

    fun skipTask() {
        stopAlarmSound()
        pauseTimer()
        val task = _currentTask.value ?: return
        _toastMessage.value = "Skipped \"${task.name}\""
        _currentTask.value  = null
        scheduler.scheduleNext()
    }

    fun setCurrentTask(task: Task) {
        pauseTimer()
        // After a NOTIFICATION task expires, triggerAlarmExpire() sets
        // _noticePhase = Expired (sync) then nulls _currentTask via postValue
        // (async).  By the time the user taps the task row, _currentTask is
        // already null, so pauseTimer()'s `task != null` guard skips handlePause()
        // and the Expired phase is never cleared.  On the first re-select the
        // derive() therefore sees:  task != null  +  phase == Expired
        // -> TimerCardAction.Unavailable ("-") instead of Start.
        //
        // Fix: always reset notice state here, after pauseTimer() has already
        // handled any truly-running delay/wait/execute phase.  resetState() is
        // idempotent: if pauseTimer() already transitioned the phase to Idle
        // (normal cancel/pause paths) this is a harmless no-op.
        notice.resetState()
        _currentTask.value  = task
        _timerSeconds.value = task.remainingSeconds
    }

    fun cancelNotice() = notice.cancelNotice()

    private fun stopTimer(completed: Boolean) {
        stopAlarmSound()
        timerEngine.clear()
        _timerRunning.value = false
        if (completed) {
            val task = _currentTask.value ?: return
            viewModelScope.launch {
                repository.markCompleted(task)
                _currentTask.postValue(null)
                _toastMessage.postValue("\"${task.name}\" completed!")
                refreshSchedule()
            }
        }
    }

    /**
     * Called when the countdown reaches zero.
     *
     * [taskOverride] is supplied by the app-killed recovery path in init{} where
     * _currentTask hasn't been set yet (postValue is asynchronous).
     * [session] == null means vruntime was already applied by the caller.
     */
    private fun onTimerFinished(
        taskOverride: Task?       = null,
        session:      RunSession? = null
    ) {
        val task = taskOverride ?: _currentTask.value ?: return
        val ctx  = app

        val expiryEpochMs      = session?.endEpochMs ?: System.currentTimeMillis()
        val elapsedSinceExpiry = ((System.currentTimeMillis() - expiryEpochMs) / 1000L)
            .coerceAtLeast(0L)

        // Clear engine synchronously — before any suspend call — so that a user
        // interaction arriving before the coroutine runs sees Idle state and avoids
        // the Paused(sliceMs) → remainingSeconds=0 stuck-at-0:00 bug.
        timerEngine.clear()

        viewModelScope.launch {
            // NOTIFICATION tasks: do NOT cancel the alarm here.
            //
            // The alarm is now set for the FULL remaining cycle duration in
            // startExecutePhase (execute + all future wait/execute pairs), so it
            // cannot collide with CountDownTimer.onFinish() at execute boundaries —
            // the alarm fires at e.g. 30 s while execute ends at 10 s.
            //
            // The only remaining collision point is the FINAL wait phase where
            // CountDownTimer and AlarmManager both fire at the same epoch.
            // That race is handled in triggerAlarmExpire(), which cancels the
            // AlarmManager entry synchronously before starting the in-app expire
            // path, so onAlarmFired() finds AlarmState==Idle and returns false.
            if (session != null) {
                if (task.taskType != "NOTIFICATION") {
                    repository.updateVruntimeAfterRun(task, session)
                } else {
                    notice.accumulateSessionSeconds(session.wallClockSeconds)
                }
            }
            repository.update(task.withTimerState(TimerState.reset()))
            _toastMessage.postValue("Time slice done for \"${task.name}\"")
            refreshSchedule()

            if (settings.autoMode.value == true) {
                val allTasks = activeTasks.value ?: emptyList()
                val next = scheduler.selectAutoNextTask(task, allTasks)
                    ?: repository.selectNextTask()
                if (next != null) {
                    pendingAutoStart = true
                    _currentTask.postValue(next)
                    _timerSeconds.postValue(next.remainingSeconds)
                    _toastMessage.postValue("Auto → \"${next.name}\"")
                } else {
                    _currentTask.postValue(null)
                    _toastMessage.postValue("Auto: no more active tasks")
                }
            } else if (task.taskType == "NOTIFICATION") {
                notice.handleExpiredNotificationTask(task)
            } else {
                AlarmForegroundService.timerExpire(ctx, task.name, task.taskType)
                _alarmTaskName.postValue(task.name)
                _alarmElapsedSeconds.postValue(elapsedSinceExpiry)
                startInAppOverrunCounter(task.name, elapsedSinceExpiry)
                taskToRestoreAfterExpire = task.withTimerState(TimerState.reset())
                _currentTask.postValue(null)
            }
        }
    }

    // =========================================================================
    // Alarm / overrun counter
    // =========================================================================

    internal fun startInAppOverrunCounter(_taskName: String, initialElapsedSeconds: Long = 0L) {
        overrunTimer?.cancel()
        overrunTimer = object : CountDownTimer(3600_000L, 1000L) {
            var elapsed = initialElapsedSeconds
            override fun onTick(millisUntilFinished: Long) {
                elapsed++
                _alarmElapsedSeconds.postValue(elapsed)
            }
            override fun onFinish() { stopAlarmSound() }
        }.start()
    }

    private fun stopOverrunCounter() {
        overrunTimer?.cancel()
        overrunTimer = null
    }

    fun stopAlarmSound() {
        stopOverrunCounter()
        _alarmTaskName.postValue(null)
        _alarmElapsedSeconds.postValue(0L)
        AlarmForegroundService.stopAlarm(app)
        taskToRestoreAfterExpire?.let { resetTask ->
            _currentTask.postValue(resetTask)
            _timerSeconds.postValue(resetTask.timeSliceSeconds)
            taskToRestoreAfterExpire = null
        }
    }

    // =========================================================================
    // Vruntime helper
    // =========================================================================

    internal fun applyVruntimeUpdate(session: RunSession) {
        val task = _currentTask.value ?: return
        viewModelScope.launch {
            repository.updateVruntimeAfterRun(task, session)
            refreshSchedule()
        }
    }

    // =========================================================================
    // Scheduler facade
    // =========================================================================

    fun refreshSchedule()                           = scheduler.refreshSchedule()
    fun scheduleNext()                              = scheduler.scheduleNext()
    fun nextSibling(onQueueTab: Boolean = false)    = scheduler.nextSibling(onQueueTab)
    fun jumpToFirst(onQueueTab: Boolean)            = scheduler.jumpToFirst(onQueueTab)
    fun pauseAndDismiss()                           = scheduler.pauseAndDismiss()

    // =========================================================================
    // Settings facade
    // =========================================================================

    // ── LiveData passthrough ──────────────────────────────────────────────────
    val groupsEnabled:       LiveData<Boolean> get() = settings.groupsEnabled
    val globalRotateEnabled: LiveData<Boolean> get() = settings.globalRotateEnabled
    val allowEditEnabled:    LiveData<Boolean> get() = settings.allowEditEnabled
    val autoScrollEnabled:   LiveData<Boolean> get() = settings.autoScrollEnabled
    val autoMode:            LiveData<Boolean> get() = settings.autoMode

    // ── Toggle methods ────────────────────────────────────────────────────────
    fun toggleGroupsEnabled()  = settings.toggleGroupsEnabled()
    fun toggleGlobalRotate()   = settings.toggleGlobalRotate()
    fun toggleAllowEdit()      = settings.toggleAllowEdit()
    fun toggleAutoScroll()     = settings.toggleAutoScroll()

    fun toggleAutoMode() {
        _toastMessage.value = settings.toggleAutoMode()
    }

    fun consumePendingAutoStart(): Boolean {
        val v = pendingAutoStart
        pendingAutoStart = false
        return v
    }

    fun saveTab(tab: Int)   = settings.saveTab(tab)
    fun getSavedTab(): Int  = settings.getSavedTab()

    // =========================================================================
    // Group expand facade
    // =========================================================================

    fun getQueueExpanded(taskId: String):    Boolean = groupExpand.getQueueExpanded(taskId)
    fun getScheduleExpanded(taskId: String): Boolean = groupExpand.getScheduleExpanded(taskId)

    fun toggleQueueGroupExpanded(group: Task)    = groupExpand.toggleQueueGroupExpanded(group)
    fun toggleScheduleGroupExpanded(group: Task) = groupExpand.toggleScheduleGroupExpanded(group)
    fun deepToggleQueueGroupExpanded(group: Task)    = groupExpand.deepToggleQueueGroupExpanded(group)
    fun deepToggleScheduleGroupExpanded(group: Task) = groupExpand.deepToggleScheduleGroupExpanded(group)
    fun toggleAllQueueGroupsExpanded()    = groupExpand.toggleAllQueueGroupsExpanded()
    fun toggleAllScheduleGroupsExpanded() = groupExpand.toggleAllScheduleGroupsExpanded()

    // =========================================================================
    // Interrupt facade
    // =========================================================================

    val activeInterruptSlot: LiveData<String>  get() = interrupt.activeInterruptSlot
    val interruptTask:       LiveData<Task?>   get() = interrupt.interruptTask
    val interruptTaskB:      LiveData<Task?>   get() = interrupt.interruptTaskB

    fun toggleInterruptSlot()              = interrupt.toggleInterruptSlot()
    fun assignInterruptTask(task: Task)    = interrupt.assignInterruptTask(task)
    fun assignInterruptTaskB(task: Task)   = interrupt.assignInterruptTaskB(task)
    fun clearInterruptTask()               = interrupt.clearInterruptTask()
    fun clearInterruptTaskB()              = interrupt.clearInterruptTaskB()
    fun jumpToInterrupt()                  = interrupt.jumpToInterrupt()
    fun jumpToInterruptA()                 = interrupt.jumpToInterruptA()
    fun jumpToInterruptB()                 = interrupt.jumpToInterruptB()

    // =========================================================================
    // Call switch facade
    // =========================================================================

    fun handleCallStarted(callTaskId: String) = callSwitch.handleCallStarted(callTaskId)
    fun handleCallEnded()                     = callSwitch.handleCallEnded()

    // =========================================================================
    // Notice-phase LiveData passthrough
    // =========================================================================

    val noticePhase:             LiveData<NoticePhase> get() = notice.noticePhase
    val delayRunning:            LiveData<Boolean>     get() = notice.delayRunning
    val delaySecondsRemaining:   LiveData<Long>        get() = notice.delaySecondsRemaining
    val waitRunning:             LiveData<Boolean>     get() = notice.waitRunning
    val waitSecondsRemaining:    LiveData<Long>        get() = notice.waitSecondsRemaining

    // =========================================================================
    // Lifecycle
    // =========================================================================

    override fun onCleared() {
        super.onCleared()
        // Remove named observers to prevent accumulation across ViewModel recreation.
        // Do NOT call stopAlarmSound() here — that would cancel the AlarmManager entry,
        // which must survive ViewModel death so the alarm fires at expiry.
        timerEngine.tickSeconds.removeObserver(tickObserver)
        timerEngine.expiredSession.removeObserver(expiredSessionObserver)
        timerEngine.expiredTask.removeObserver(expiredObserver)
        timerEngine.clear()
        overrunTimer?.cancel()
        notice.cancelTimers()
        listBuilder.stop()
    }

    // =========================================================================
    // DB export
    // =========================================================================

    /**
     * Stops the timer, clears the current task, then flushes and closes the
     * Room connection so SettingsActivity can safely copy / replace the .db file.
     * Room re-initialises automatically the next time any DAO is accessed.
     */
    fun prepareForDbExport() {
        pauseTimer()
        _currentTask.postValue(null)
        TaskDatabase.checkpointAndClose(app)
    }

    // =========================================================================
    // MULTI-USER SYNC
    // =========================================================================

    /** Exposes sync state LiveData for the toolbar dot observer in MainActivity. */
    val syncState = MultiUserSyncManager.syncState

    /**
     * Fires with Unit when a remote sync import has replaced the local DB file.
     * MainActivity observes this and restarts the app so Room opens cleanly.
     */
    private val _restartNeeded = MutableLiveData<Unit>()
    val restartNeeded: LiveData<Unit> = _restartNeeded

    /** Called from MainActivity.onResume to restart polling if it was stopped. */
    fun onSyncResume() = MultiUserSyncManager.onResume()

    /**
     * Triggers a debounced export after any local DB write.
     * Also used for the Sync Now toolbar tap.
     */
    fun triggerSyncExport() = MultiUserSyncManager.scheduleExport()
}