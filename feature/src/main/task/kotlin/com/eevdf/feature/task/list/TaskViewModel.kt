package com.eevdf.feature.task.list

import android.app.Application
import android.content.SharedPreferences
import android.os.CountDownTimer
import androidx.lifecycle.*
import com.eevdf.data.task.TaskDatabase
import com.eevdf.data.task.TaskRepository
import com.eevdf.data.task.Task
import com.eevdf.data.task.TaskLoadFactor
import com.eevdf.feature.shared.AppPreferences
import com.eevdf.feature.task.notice.NoticePhase
import com.eevdf.data.runlog.RunSession
import com.eevdf.feature.task.timer.TimerStartEvent
import com.eevdf.data.task.timer.TaskTimerState
import com.eevdf.feature.task.timer.TimerCardAction
import com.eevdf.feature.task.timer.NextButtonState
import com.eevdf.data.task.timer.timerState
import com.eevdf.data.task.timer.withTimerState
import com.eevdf.data.task.TaskDisplayItem
import com.eevdf.data.scheduler.SchedulerStats
import com.eevdf.feature.task.timer.TimerEngine
import kotlinx.coroutines.launch
import com.eevdf.data.sync.MultiUserSyncManager
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.eevdf.feature.task.timer.InterruptDelegate
import com.eevdf.feature.task.notice.NoticeStateMachine
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import com.eevdf.feature.shared.signals.BubbleEventBus
import com.eevdf.contract.control.AlarmController
import com.eevdf.contract.control.OverlayController

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
 *   • [ListTogglesDelegate]    — settings toggles + tab persistence
 *   • [GroupExpandDelegate] — per-tab group expand / collapse state
 *   • [InterruptDelegate]   — INT-A / INT-B slot logic
 *   • [CallSwitchDelegate]  — phone-call auto-switch
 *   • [NoticeStateMachine]  — NOTIFICATION task phase state machine
 *   • [SchedulerDelegate]   — rotation, auto-next, schedule-next
 *   • [ListBuilderDelegate] — flat Queue / Schedule list construction
 *   • [SortHelper]          — shared number-extraction sort utility
 *   • [TaskCrudDelegate]    — add / update / delete / revert / complete a task
 *   • [AlarmOverrunDelegate] — overrun counter + restart-after-expire
 *   • [BubbleTapDelegate]   — hover-bubble tap during a call
 *   • [StartupRecoveryDelegate] — app-kill / startup recovery (called from init{})
 *
 * ── Adding a feature to a domain ─────────────────────────────────────────────
 *
 *  Edit only the relevant delegate file.  Expose the new public method here
 *  as a one-liner facade if the UI needs to call it.
 */
@HiltViewModel
class TaskViewModel @Inject constructor(
    application: Application,
    @AppPreferences internal val prefs: SharedPreferences,
    internal val repository: TaskRepository,
    /**
     * Alarm + overlay are reached through contracts, not through the alarm and
     * autoswitch feature classes. `internal` so the delegates in this package
     * (notice state machine, call-switch) can use them without re-injecting.
     */
    internal val alarms: AlarmController,
    internal val overlay: OverlayController,
) : AndroidViewModel(application) {

    // ── Shared preferences (internal so delegates can access prefs directly) ──
    //
    // Injected from PlatformModule (@AppPreferences → "eevdf_prefs"); previously
    // built inline via application.getSharedPreferences(...).

    /** Convenience accessor for delegates that need an Application context. */
    internal val app: Application = application

    // ── Repository + DB-backed LiveData ───────────────────────────────────────
    // [repository] is constructor-injected above; the DB-backed LiveData streams
    // are bound in init{} once the superclass + injected fields are ready.
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

    internal val settings    = ListTogglesDelegate(prefs)
    internal val groupExpand = GroupExpandDelegate(prefs, this)
    internal val lastRun     = QueueLastRunDelegate(prefs)
    internal val interrupt   = InterruptDelegate(this)
    internal val callSwitch  = CallSwitchDelegate(this)
    internal val notice      = NoticeStateMachine(this)
    internal val scheduler   = SchedulerDelegate(this)
    internal val listBuilder = ListBuilderDelegate(this)
    internal val crud        = TaskCrudDelegate(this)
    internal val alarmOverrun = AlarmOverrunDelegate(this)
    internal val bubbleTap   = BubbleTapDelegate(this)
    internal val startupRecovery = StartupRecoveryDelegate(this)

    // ── Flat task lists (built by listBuilder) ────────────────────────────────

    var flatActiveTasks:   MediatorLiveData<List<TaskDisplayItem>> = MediatorLiveData()
    var flatScheduleOrder: MediatorLiveData<List<TaskDisplayItem>> = MediatorLiveData()

    // ── Derived button-state LiveData ─────────────────────────────────────────
    //
    // Each MediatorLiveData combines ALL inputs that affect a button into ONE
    // settled value — no race window between separate LiveData reads.

    /**
     * THE single source of truth for the whole (merged) timer card.
     *
     * After merging cardAlarmBanner into cardTimer there is exactly one card and
     * exactly one state object describing it. The derivation combines every input
     * that can affect the card into one atomic value:
     *
     *   _alarmTaskName  → alarm ringing?      (was a separate, un-wired LiveData)
     *   notice.noticePhase → notice phase
     *   _timerRunning   → countdown running?
     *   _currentTask    → is anything selected?
     *
     * Derivation priority (highest first):
     *   1. alarm ringing  → Expired(name, elapsed)   [red banner + Stop]
     *   2. no task         → Hidden                   [card removed from layout]
     *   3. notice Delay/Wait → Cancel
     *   4. notice Execute  → Pause
     *   5. notice Expired (transient, pre-alarm) → Unavailable
     *   6. running         → Pause
     *   7. otherwise       → Start
     *
     * Bug 2 fix: _alarmTaskName / _alarmElapsedSeconds are now addSource()'d, so
     * the alarm can never be visible while this value simultaneously reports an
     * actionable Start/Pause. The alarm branch sits ABOVE the task==null branch
     * because during expiry _currentTask is momentarily nulled while the alarm is
     * up — without this ordering the card would flash Hidden between the two.
     */
    val timerCardAction: MediatorLiveData<TimerCardAction> =
        MediatorLiveData<TimerCardAction>().apply {
            fun derive() {
                val alarmName = _alarmTaskName.value
                val phase     = notice.noticePhase.value ?: NoticePhase.Idle
                val running   = _timerRunning.value       ?: false
                val task      = _currentTask.value
                value = when {
                    alarmName != null            -> TimerCardAction.Expired(
                                                        taskName       = alarmName,
                                                        elapsedSeconds = _alarmElapsedSeconds.value ?: 0L
                                                    )
                    task == null                 -> TimerCardAction.Hidden
                    phase is NoticePhase.Delay   -> TimerCardAction.Cancel
                    phase is NoticePhase.Wait    -> TimerCardAction.Cancel
                    phase is NoticePhase.Execute -> TimerCardAction.Pause
                    phase is NoticePhase.Expired -> TimerCardAction.Unavailable
                    running                      -> TimerCardAction.Pause
                    else                         -> TimerCardAction.Start
                }
            }
            addSource(notice.noticePhase)     { derive() }
            addSource(_timerRunning)          { derive() }
            addSource(_currentTask)           { derive() }
            // Bug 2 fix: alarm state is now part of the same atomic derivation.
            addSource(_alarmTaskName)         { derive() }
            addSource(_alarmElapsedSeconds)   { derive() }
        }

    /** INT button state — slot label + whether a task is assigned. */
    val intButtonState: MediatorLiveData<com.eevdf.feature.task.timer.IntButtonState>
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
        // Session is now captured synchronously by the engine and read here via
        // consumeExpiredSession(), so crediting no longer depends on the delivery
        // order of expiredSession vs expiredTask (the old null-session race).
        expiredSessionObserver = Observer { /* no-op: session read from engine */ }
        expiredObserver = Observer { expired: Task ->
            val session = timerEngine.consumeExpiredSession()
            _timerRunning.postValue(false)
            _currentTask.value = expired
            onTimerFinished(session = session)
        }
        timerEngine.tickSeconds.observeForever(tickObserver)
        timerEngine.expiredSession.observeForever(expiredSessionObserver)
        timerEngine.expiredTask.observeForever(expiredObserver)

        // ── Startup / app-kill recovery ────────────────────────────────────────
        // See StartupRecoveryDelegate for the actual 3-step decision logic.
        viewModelScope.launch { startupRecovery.recover() }

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
    // CRUD facade
    // =========================================================================

    fun addTask(task: Task)                      = crud.addTask(task)
    fun updateTask(task: Task)                    = crud.updateTask(task)
    fun deleteTask(task: Task)                    = crud.deleteTask(task)
    fun revertTask(task: Task)                    = crud.revertTask(task)
    fun markCompleted(task: Task)                 = crud.markCompleted(task)
    fun clearCompleted()                          = crud.clearCompleted()
    fun clearToast()                               = crud.clearToast()
    fun toggleGroupExpanded(group: Task)          = crud.toggleGroupExpanded(group)
    suspend fun getTaskById(id: String): Task?    = crud.getTaskById(id)
    suspend fun getLoadFactor(taskId: String): TaskLoadFactor? = crud.getLoadFactor(taskId)
    fun saveLoadFactor(entry: TaskLoadFactor)     = crud.saveLoadFactor(entry)

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
        val isInitialResume = task.resumeType == "INITIAL" &&
            task.timerState is TaskTimerState.Paused &&
            !notice.hasPendingWait()

        val effectiveTask      = if (isInitialResume) task.withTimerState(TaskTimerState.reset()) else task
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
     * directly and by [NoticeStateMachine.startExecutePhase].
     */
    /**
     * @param remaining  Execute-slice seconds remaining — drives the engine countdown and
     *                   the notification chronometer.
     * @param alarmSecs  Total seconds until the AlarmManager should fire.  For NOTIFICATION
     *                   tasks [NoticeStateMachine.startExecutePhase] passes the pre-computed
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
        // Record which task ran inside each ancestor group so the Queue tab's
        // global-rotate Next can return to the most recently used task per group.
        lastRun.update(task, activeTasks.value ?: emptyList())
        viewModelScope.launch {
            repository.update(updated)
            triggerSyncExport()          // notify other users: timer started
        }
        alarms.timerStart(task.name, remaining, task.taskType, alarmSecs)
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
        alarms.timerPause()
        triggerSyncExport()               // notify other users: timer paused
    }

    /**
     * Hold-to-close action (Start/Pause long-press on the timer card).
     *
     * Pauses the running task — crediting the partial session's run time and
     * persisting the Paused state, so progress is NOT lost — then DESELECTS it by
     * clearing [_currentTask]. The currentTask observer in MainActivity then closes
     * the timer card and clears the running highlight in the adapters.
     *
     * This is distinct from the manual hide (isCardManuallyHidden), which keeps the
     * task selected and only hides the card. Here the task is fully deselected; the
     * task remains Paused (not reset), so reselecting it later resumes where it left
     * off.
     */
    fun pauseAndDeselect() {
        pauseTimer()
        _currentTask.value = null
        clearPersistedSelection()
    }

    fun resetTimer() {
        pauseTimer()
        timerEngine.clear()
        notice.resetState()
        val task  = _currentTask.value ?: return
        val reset = task.withTimerState(TaskTimerState.reset())
        _timerSeconds.value = reset.remainingSeconds
        viewModelScope.launch {
            repository.update(reset)
            _currentTask.postValue(reset)
        }
    }

    /** Resets the timer slice of any task back to its default [timeSliceSeconds]. */
    fun resetSlice(task: Task) {
        if (task.id == _currentTask.value?.id) { resetTimer(); return }
        viewModelScope.launch { repository.update(task.withTimerState(TaskTimerState.reset())) }
    }

    fun skipTask() {
        stopAlarmSound()
        pauseTimer()
        val task = _currentTask.value ?: return
        _toastMessage.value = "Skipped \"${task.name}\""
        _currentTask.value  = null
        clearPersistedSelection()
        scheduler.scheduleNext()
    }

    fun setCurrentTask(task: Task) {
        pauseTimer()
        // Bug 1 fix — stale NoticePhase.Expired locking the button:
        //
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

        // If an expiry alarm is up when the user selects a (possibly different)
        // task, clear it synchronously so timerCardAction does not derive Expired
        // for the freshly-selected task on the next frame.
        if (_alarmTaskName.value != null) {
            taskToRestoreAfterExpire = null
            stopAlarmSound()
        }

        _currentTask.value  = task
        _timerSeconds.value = task.remainingSeconds

        // Selecting a task is an explicit "open this card" gesture: clear any
        // prior manual-hide and persist the selection so it survives reboot.
        setCardManuallyHidden(false)
        settings.saveSelectedTaskId(task.id)
    }

    /**
     * Persists the manual card-hidden flag so a hand-closed card stays closed
     * across app reopen / reboot. Called by MainActivity's key1-hold handler and
     * by [setCurrentTask] (which always reopens the card).
     */
    fun setCardManuallyHidden(hidden: Boolean) = settings.saveCardManuallyHidden(hidden)

    /** Restored on startup by MainActivity to decide whether to show the card. */
    fun getCardManuallyHidden(): Boolean = settings.getSavedCardManuallyHidden()

    /**
     * Re-seats the persisted last-selected task onto the card on startup, without
     * the side effects of [setCurrentTask] (no notice reset, no re-persist). Reads
     * the live row from the DB so paused/reset state is reflected. No-op if no id
     * is stored or the task no longer exists (e.g. it was deleted/completed).
     */
    fun restorePersistedSelection() {
        // Don't clobber a task already seated by the mid-run / alarm recovery paths.
        if (_currentTask.value != null || _alarmTaskName.value != null) return
        val savedId = settings.getSavedSelectedTaskId() ?: return
        viewModelScope.launch {
            val task = repository.getTaskById(savedId)
            if (task == null || task.isCompleted) {
                settings.saveSelectedTaskId(null)
                return@launch
            }
            _currentTask.postValue(task)
            _timerSeconds.postValue(task.remainingSeconds)
        }
    }

    /**
     * Clears the timer card's persisted selection. Call from GENUINE deselection
     * paths (delete, skip, complete, hold-to-deselect) — NOT from the expiry path,
     * where requirement #3 mandates the card stay seated on the just-expired task.
     */
    internal fun clearPersistedSelection() = settings.saveSelectedTaskId(null)

    fun cancelNotice() = notice.cancelNotice()

    internal fun stopTimer(completed: Boolean) {
        stopAlarmSound()
        timerEngine.clear()
        _timerRunning.value = false
        if (completed) {
            val task = _currentTask.value ?: return
            viewModelScope.launch {
                repository.markCompleted(task)
                _currentTask.postValue(null)
                clearPersistedSelection()
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
    internal fun onTimerFinished(
        taskOverride: Task?       = null,
        session:      RunSession? = null
    ) {
        val task = taskOverride ?: _currentTask.value ?: return

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
            repository.update(task.withTimerState(TaskTimerState.reset()))
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
                    settings.saveSelectedTaskId(next.id)   // card follows the auto task
                    _toastMessage.postValue("Auto → \"${next.name}\"")
                } else {
                    _currentTask.postValue(null)
                    clearPersistedSelection()
                    _toastMessage.postValue("Auto: no more active tasks")
                }
            } else if (task.taskType == "NOTIFICATION") {
                notice.handleExpiredNotificationTask(task)
            } else {
                alarms.timerExpire(task.name, task.taskType)
                _alarmTaskName.postValue(task.name)
                _alarmElapsedSeconds.postValue(elapsedSinceExpiry)
                startInAppOverrunCounter(task.name, elapsedSinceExpiry)
                taskToRestoreAfterExpire = task.withTimerState(TaskTimerState.reset())
                // Requirement #3: do NOT clear the persisted selection on expiry.
                // The merged card stays seated on the just-expired task (showing the
                // Expired/alarm state); keep its id stored so a reboot mid-alarm
                // reopens the card on the same task.
                settings.saveSelectedTaskId(task.id)
                _currentTask.postValue(null)
            }
        }
    }

    // =========================================================================
    // Alarm / overrun counter
    // =========================================================================

    // =========================================================================
    // Alarm / overrun counter facade
    // =========================================================================

    internal fun startInAppOverrunCounter(_taskName: String, initialElapsedSeconds: Long = 0L) =
        alarmOverrun.startInAppOverrunCounter(_taskName, initialElapsedSeconds)

    fun stopAlarmSound() = alarmOverrun.stopAlarmSound()

    fun isAlarmActive(): Boolean = alarmOverrun.isAlarmActive()

    fun restartAfterExpire(fallbackName: String? = null) = alarmOverrun.restartAfterExpire(fallbackName)

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

    /** Distinct category strings from the DB — drives autocomplete in Add/Edit task. */
    val distinctCategories:  LiveData<List<String>> get() = repository.distinctCategories
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

    /**
     * Called from [BubbleEventBus.onBubbleTap] when the user taps the hover
     * bubble during a call. See [BubbleTapDelegate.handleBubbleTap] for the
     * full case breakdown.
     */
    fun handleBubbleTap() = bubbleTap.handleBubbleTap()

    /** @deprecated Use [handleBubbleTap] — kept to avoid compile errors during migration. */
    @Deprecated("Replaced by handleBubbleTap", ReplaceWith("handleBubbleTap()"))
    fun toggleCallTaskTimer() = handleBubbleTap()

    /**
     * Overflow-menu hold: collapses all groups when any leaf is visible, expands
     * all when all groups are already collapsed.  Groups that are ancestors of
     * any interrupt task are excluded so the interrupt slot is never disrupted.
     *
     * @param onQueueTab       true = Queue tab, false = Schedule tab
     * @param hasVisibleLeaves true when the flat list has at least one visible
     *                         non-group, non-interrupt, non-completed leaf task
     */
    fun toggleAllGroupsGlobal(onQueueTab: Boolean, hasVisibleLeaves: Boolean) {
        val excludeIds = collectInterruptAncestorIds()
        if (onQueueTab) groupExpand.toggleAllQueueGroupsGlobal(hasVisibleLeaves, excludeIds)
        else            groupExpand.toggleAllScheduleGroupsGlobal(hasVisibleLeaves, excludeIds)
    }

    /**
     * Walks up the parent chain of every interrupt task and collects all
     * ancestor group IDs.  These groups are excluded from the global toggle so
     * the interrupt task's visibility is never accidentally changed.
     */
    internal fun collectInterruptAncestorIds(): Set<String> {
        val allTasks = activeTasks.value ?: return emptySet()
        val result   = mutableSetOf<String>()
        allTasks.filter { it.isInterrupt }.forEach { interruptTask ->
            var parentId: String? = interruptTask.parentId
            while (parentId != null) {
                result.add(parentId)
                parentId = allTasks.find { it.id == parentId }?.parentId
            }
        }
        return result
    }

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

    /**
     * Reconciles ViewModel in-memory state with the DB after [CallSwitchService]
     * may have written changes while MainActivity was dead or backgrounded.
     *
     * Called from MainActivity.onResume() so the UI always reflects the true
     * DB state after the user opens (or returns to) the app.
     *
     * What it does:
     *   1. Reads the currently running task from DB.
     *   2. If it differs from what _currentTask holds, updates the LiveData so
     *      the timer card shows the correct task and time.
     *   3. Syncs [BubbleEventBus] volatile fields so the bubble dot colour is
     *      correct immediately — no waiting for the next POLL_MS tick.
     *
     * This is intentionally lightweight — it does NOT restart the CountDownTimer
     * engine (that is already handled by the tick-observer in init{}).  It only
     * corrects the *displayed* task identity and remaining seconds so the user
     * sees the right card when they open the app mid-call or after a call.
     */
    fun syncFromDb() {
        viewModelScope.launch {
            val runningTask = repository.getRunningTask() ?: return@launch

            val currentId = _currentTask.value?.id
            if (runningTask.id != currentId) {
                // CallSwitchService switched tasks while the Activity was dead.
                // Update LiveData on the main thread so the timer card refreshes.
                _currentTask.postValue(runningTask)
                _timerSeconds.postValue(runningTask.remainingSeconds)
                _timerRunning.postValue(runningTask.isRunning)
            }
        }
    }

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
     * Prepares the database for a **non-destructive export copy**.
     *
     * Flushes the WAL into the main `.db` file (TRUNCATE checkpoint) so the file
     * copied by the export path is self-contained, but keeps Room OPEN. This is
     * deliberate under Hilt: the Room instance is `@Singleton` and cached inside
     * the Hilt graph, so closing it here would leave Hilt handing out a closed
     * handle (the old `getDatabase()` auto-reinit no longer covers the cached
     * reference). Export never replaces the file, so there is no need to close.
     */
    fun prepareForDbExport() {
        pauseTimer()
        _currentTask.postValue(null)
        TaskDatabase.checkpointWal(app)
    }

    /**
     * Prepares the database for a **destructive import** that overwrites the
     * `.db` file on disk. Checkpoints and CLOSES Room so no file locks are held
     * during the swap. Safe to close the cached Hilt handle here only because
     * every import path restarts the process (`killProcess`) immediately after,
     * rebuilding the Hilt graph — the closed instance is never reused.
     */
    fun prepareForDbImport() {
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