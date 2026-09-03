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
import com.eevdf.feature.task.timer.TimerCardAction
import com.eevdf.feature.task.timer.NextButtonState
import com.eevdf.data.task.TaskDisplayItem
import com.eevdf.data.scheduler.SchedulerStats
import com.eevdf.feature.task.timer.TimerEngine
import kotlinx.coroutines.launch
import com.eevdf.data.sync.MultiUserSyncManager
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
 *   • [TimerLifecycleDelegate] — start / pause / reset / skip / select / expiry
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
    // pendingAutoStart removed: it existed only to support Auto mode's old
    // automatic-advance-on-timer-completion behavior, which no longer exists.

    /**
     * Holds the reset-state task while the expire card is visible.
     * Consumed in [stopAlarmSound] to reopen the timer card with the default timer.
     */
    internal var taskToRestoreAfterExpire: Task? = null

    // ── Links (symlinks/hardlinks) ────────────────────────────────────────────

    /** Every symlink in the app. Spliced into the tree by [ListBuilderDelegate]. */
    val allTaskLinks: LiveData<List<com.eevdf.data.task.TaskLink>> = repository.allTaskLinks

    /** Every hardlink placement in the app. Spliced into the tree by [ListBuilderDelegate]. */
    val allTaskMemberships: LiveData<List<com.eevdf.data.task.TaskMembership>> = repository.allTaskMemberships

    /**
     * Non-null while the currently selected/running task ([_currentTask]) was
     * seated via a hardlink placement rather than its real, primary parent.
     * Read by [TimerLifecycleDelegate] so a completed/paused session's runtime
     * is credited to that ONE placement (see [TaskRepository.creditMembershipRun])
     * instead of the task's own primary fields. Cleared on every ordinary
     * [setCurrentTask] so a ordinary tap never inherits a stale membership
     * context from a previous selection.
     */
    internal var activeRunMembershipId: String? = null

    /** Creates a symlink: [hostGroupId] will show [targetTaskId] as a live pointer. */
    fun createSymlink(targetTaskId: String, hostGroupId: String) =
        viewModelScope.launch { repository.createSymlink(targetTaskId, hostGroupId) }

    fun deleteSymlink(linkId: String) =
        viewModelScope.launch { repository.deleteSymlink(linkId) }

    /** Creates a hardlink: an extra real placement of [taskId] inside [hostGroupId]. */
    fun createHardlink(taskId: String, hostGroupId: String) =
        viewModelScope.launch { repository.createHardlink(taskId, hostGroupId) }

    fun deleteHardlink(membershipId: String) =
        viewModelScope.launch { repository.deleteHardlink(membershipId) }

    /**
     * Selects [task] as if the user tapped it inside a specific hardlink
     * placement ([membershipId]) rather than at its real, primary location.
     * Everything about the running task is identical — same timer, same
     * config — only where the resulting runtime gets credited differs.
     */
    fun setCurrentTaskAsMembership(task: Task, membershipId: String) {
        timerLifecycle.setCurrentTask(task)
        activeRunMembershipId = membershipId
    }

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
    // ── Drill-down navigation (Links feature: symlink-aware "up") ───────────
    //
    // In-memory only, one DrillState per tab. NEVER wired into flatActiveTasks
    // or flatScheduleOrder themselves — see DrillState's doc comment for why.
    // The style toggle and drill functions live here (not ListBuilderDelegate)
    // because they're navigation/selection state, matching where every other
    // "which task/group am I on" concern (currentTask, groupExpand) already lives.
    //
    // MUST be declared before the init block below — listBuilder.setup() runs
    // from it and immediately does addSource(vm.queueDrillState)/
    // addSource(vm.scheduleDrillState). Kotlin initializes properties in
    // textual declaration order, so if these were declared after init{},
    // they'd still be null (uninitialized) at the moment setup() reads them —
    // MediatorLiveData.addSource throws NullPointerException("source cannot
    // be null") in that case, which is exactly the crash this fixes.
    internal val _queueDrillState    = MutableLiveData(DrillState())
    internal val _scheduleDrillState = MutableLiveData(DrillState())
    val queueDrillState:    LiveData<DrillState> = _queueDrillState
    val scheduleDrillState: LiveData<DrillState> = _scheduleDrillState

    internal val listBuilder = ListBuilderDelegate(this)
    internal val crud        = TaskCrudDelegate(this)
    internal val alarmOverrun = AlarmOverrunDelegate(this)
    internal val bubbleTap   = BubbleTapDelegate(this)
    internal val startupRecovery = StartupRecoveryDelegate(this)
    internal val timerLifecycle = TimerLifecycleDelegate(this)

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
                value = if (settings.nextButtonShowsAuto.value == true) NextButtonState.Auto
                        else NextButtonState.Next
            }
            addSource(settings.nextButtonShowsAuto) { derive() }
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
    // Timer lifecycle facade
    // =========================================================================

    fun startTimer() = timerLifecycle.startTimer()

    /** Called by the ViewModel directly and by [NoticeStateMachine.startExecutePhase]. */
    internal fun startActualTimer(task: Task, remaining: Long, alarmSecs: Long = remaining) =
        timerLifecycle.startActualTimer(task, remaining, alarmSecs)

    fun pauseTimer() = timerLifecycle.pauseTimer()

    /**
     * Hold-to-close action (Start/Pause long-press on the timer card). See
     * [TimerLifecycleDelegate.pauseAndDeselect] for the full behavior.
     */
    fun pauseAndDeselect() = timerLifecycle.pauseAndDeselect()

    fun resetTimer() = timerLifecycle.resetTimer()

    /** Resets the timer slice of any task back to its default timeSliceSeconds. */
    fun resetSlice(task: Task) = timerLifecycle.resetSlice(task)

    fun skipTask() = timerLifecycle.skipTask()

    fun setCurrentTask(task: Task) = timerLifecycle.setCurrentTask(task)

    /**
     * Persists the manual card-hidden flag so a hand-closed card stays closed
     * across app reopen / reboot. Called by MainActivity's key1-hold handler and
     * by [setCurrentTask] (which always reopens the card).
     */
    fun setCardManuallyHidden(hidden: Boolean) = timerLifecycle.setCardManuallyHidden(hidden)

    /** Restored on startup by MainActivity to decide whether to show the card. */
    fun getCardManuallyHidden(): Boolean = timerLifecycle.getCardManuallyHidden()

    /**
     * Re-seats the persisted last-selected task onto the card on startup. See
     * [TimerLifecycleDelegate.restorePersistedSelection] for the full behavior.
     */
    fun restorePersistedSelection() = timerLifecycle.restorePersistedSelection()

    /**
     * Clears the timer card's persisted selection. Call from GENUINE deselection
     * paths (delete, skip, complete, hold-to-deselect) — NOT from the expiry path,
     * where requirement #3 mandates the card stay seated on the just-expired task.
     */
    internal fun clearPersistedSelection() = settings.saveSelectedTaskId(null)

    fun cancelNotice() = timerLifecycle.cancelNotice()

    internal fun stopTimer(completed: Boolean) = timerLifecycle.stopTimer(completed)

    /**
     * Called when the countdown reaches zero. See
     * [TimerLifecycleDelegate.onTimerFinished] for the full expiry/auto-mode/
     * notification-task branching.
     */
    internal fun onTimerFinished(
        taskOverride: Task?       = null,
        session:      RunSession? = null
    ) = timerLifecycle.onTimerFinished(taskOverride, session)

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

    internal fun applyVruntimeUpdate(session: RunSession) = timerLifecycle.applyVruntimeUpdate(session)

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
    val queueListStyle:      LiveData<TaskListStyle> get() = settings.queueListStyle
    val scheduleListStyle:   LiveData<TaskListStyle> get() = settings.scheduleListStyle

    /** Distinct category strings from the DB — drives autocomplete in Add/Edit task. */
    val distinctCategories:  LiveData<List<String>> get() = repository.distinctCategories
    val globalRotateEnabled: LiveData<Boolean> get() = settings.globalRotateEnabled
    val allowEditEnabled:    LiveData<Boolean> get() = settings.allowEditEnabled
    val autoScrollEnabled:   LiveData<Boolean> get() = settings.autoScrollEnabled

    // ── Toggle methods ────────────────────────────────────────────────────────
    fun toggleGroupsEnabled()  = settings.toggleGroupsEnabled()

    // ── Drill-down navigation (Links feature: symlink-aware "up") toggles ───
    // (state itself — _queueDrillState/_scheduleDrillState/queueDrillState/
    // scheduleDrillState — is declared earlier, before listBuilder, since
    // listBuilder.setup() needs it already initialized; see that declaration's
    // comment for why.)

    fun toggleQueueListStyle() {
        settings.toggleQueueListStyle()
        if (settings.queueListStyle.value == TaskListStyle.FLAT_OUTLINE) _queueDrillState.value = DrillState()
    }
    fun toggleScheduleListStyle() {
        settings.toggleScheduleListStyle()
        if (settings.scheduleListStyle.value == TaskListStyle.FLAT_OUTLINE) _scheduleDrillState.value = DrillState()
    }

    /** Pushes a new drill frame — [arrivedVia] SYMLINK when following a symlink into [groupId].
     *  [groupId] null represents root (a symlink to a root-level leaf task).
     *  [highlightTaskId] marks one row in the new frame for a jump-highlight
     *  (a symlink-to-leaf-task jump highlighting that task among its real
     *  siblings) — leave null for a symlink-to-group jump or a real drill-in. */
    fun drillInto(onQueueTab: Boolean, groupId: String?, arrivedVia: ArrivedVia, highlightTaskId: String? = null) {
        val live = if (onQueueTab) _queueDrillState else _scheduleDrillState
        val current = live.value ?: DrillState()
        live.value = current.copy(stack = current.stack + DrillFrame(groupId, arrivedVia, highlightTaskId))
    }

    /** Pops one frame. For a SYMLINK frame this returns to the symlink's HOST group, not the real parent. */
    fun drillBack(onQueueTab: Boolean): Boolean {
        val live = if (onQueueTab) _queueDrillState else _scheduleDrillState
        val current = live.value ?: DrillState()
        if (current.stack.isEmpty()) return false
        live.value = current.copy(stack = current.stack.dropLast(1))
        return true
    }
    fun toggleGlobalRotate()   = settings.toggleGlobalRotate()
    fun toggleAllowEdit()      = settings.toggleAllowEdit()
    fun toggleAutoScroll()     = settings.toggleAutoScroll()

    /** Hold gesture on the Next/Auto button — flips which label is armed. */
    fun toggleNextButtonMode() = settings.toggleNextButtonMode()

    /**
     * "Auto" tap. See [SchedulerDelegate.triggerAutoJump] for the actual
     * group-locked selection — this is a one-shot manual jump, never
     * triggered automatically by anything.
     */
    fun triggerAutoJump(onQueueTab: Boolean = false) = scheduler.triggerAutoJump(onQueueTab)

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