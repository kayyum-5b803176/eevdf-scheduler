package com.eevdf.scheduler.viewmodel

import android.app.Application
import android.content.Context
import android.os.CountDownTimer
import androidx.lifecycle.*
import com.eevdf.scheduler.db.TaskDatabase
import com.eevdf.scheduler.db.TaskRepository
import com.eevdf.scheduler.model.Task
import com.eevdf.scheduler.model.NoticePhase
import com.eevdf.scheduler.model.RunSession
import com.eevdf.scheduler.model.TimerStartEvent
import com.eevdf.scheduler.model.TimerState
import com.eevdf.scheduler.model.TimerCardAction
import com.eevdf.scheduler.model.IntButtonState
import com.eevdf.scheduler.model.NextButtonState
import com.eevdf.scheduler.model.timerState
import com.eevdf.scheduler.model.withTimerState
import com.eevdf.scheduler.scheduler.EEVDFScheduler
import com.eevdf.scheduler.scheduler.TimerEngine
import com.eevdf.scheduler.model.TaskDisplayItem
import com.eevdf.scheduler.scheduler.SchedulerStats
import com.eevdf.scheduler.ui.AlarmForegroundService
import com.eevdf.scheduler.ui.AlarmScheduler
import com.eevdf.scheduler.ui.AlarmState
import com.eevdf.scheduler.ui.SoundManager
import kotlinx.coroutines.launch

class TaskViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("eevdf_prefs", Context.MODE_PRIVATE)
    private val KEY_GROUPS         = "groups_enabled"
    private val KEY_GLOBAL_ROTATE  = "global_rotate_enabled"
    private val KEY_ALLOW_EDIT     = "allow_edit_enabled"
    private val KEY_AUTO_SCROLL    = "auto_scroll_enabled"
    private val KEY_AUTO_MODE      = "auto_mode"
    private val KEY_GLOBAL_ROTATE_BEFORE_AUTO = "global_rotate_before_auto"

    private val repository: TaskRepository
    val allTasks: LiveData<List<Task>>
    val activeTasks: LiveData<List<Task>>
    val completedTasks: LiveData<List<Task>>

    /** Groups available for parent selection in AddTaskActivity. */
    val activeGroups: LiveData<List<Task>>

    private val _currentTask = MutableLiveData<Task?>(null)
    val currentTask: LiveData<Task?> = _currentTask

    // ── Interrupt task (INT-A / INT-B) ────────────────────────────────────────

    /**
     * Which interrupt slot the INT button currently points to.
     * "A" by default; hold the INT button to toggle to "B" and back.
     */
    private val _activeInterruptSlot = MutableLiveData<String>("A")
    val activeInterruptSlot: LiveData<String> = _activeInterruptSlot

    /** Toggle the active slot between "A" and "B". */
    fun toggleInterruptSlot() {
        _activeInterruptSlot.value = if (_activeInterruptSlot.value == "A") "B" else "A"
    }

    /** The task currently flagged as INT-A interrupt destination. */
    private val _interruptTask = MutableLiveData<Task?>(null)
    val interruptTask: LiveData<Task?> = _interruptTask   // kept for compat (= INT-A)

    /** The task currently flagged as INT-B interrupt destination. */
    private val _interruptTaskB = MutableLiveData<Task?>(null)
    val interruptTaskB: LiveData<Task?> = _interruptTaskB

    /** Saved card to return to after visiting the INT-A interrupt task. */
    private var savedTaskBeforeInterrupt: Task? = null

    /** Saved card to return to after visiting the INT-B interrupt task. */
    private var savedTaskBeforeInterruptB: Task? = null

    // Named observer references kept so onCleared() can remove them.
    // Anonymous observeForever lambdas cannot be unregistered, which would allow
    // duplicate observers to accumulate across ViewModel recreation.
    private lateinit var tickObserver:           Observer<Long>
    private lateinit var expiredObserver:        Observer<Task>
    private lateinit var expiredSessionObserver: Observer<RunSession>

    private val _timerSeconds = MutableLiveData<Long>()
    val timerSeconds: LiveData<Long> = _timerSeconds

    private val _timerRunning = MutableLiveData<Boolean>(false)
    val timerRunning: LiveData<Boolean> = _timerRunning

    private val _scheduleOrder = MutableLiveData<List<Task>>(emptyList())
    val scheduleOrder: LiveData<List<Task>> = _scheduleOrder

    private val _stats = MutableLiveData<SchedulerStats>()
    val stats: LiveData<SchedulerStats> = _stats

    private val _toastMessage = MutableLiveData<String?>(null)
    val toastMessage: LiveData<String?> = _toastMessage

    private val _alarmTaskName = MutableLiveData<String?>(null)
    val alarmTaskName: LiveData<String?> = _alarmTaskName

    private val _alarmElapsedSeconds = MutableLiveData<Long>(0L)
    val alarmElapsedSeconds: LiveData<Long> = _alarmElapsedSeconds

    // ── Groups mode ───────────────────────────────────────────────────────────

    private val _groupsEnabled = MutableLiveData<Boolean>(prefs.getBoolean(KEY_GROUPS, false))
    val groupsEnabled: LiveData<Boolean> = _groupsEnabled

    fun toggleGroupsEnabled() {
        val next = !(_groupsEnabled.value ?: false)
        prefs.edit().putBoolean(KEY_GROUPS, next).apply()
        _groupsEnabled.value = next
    }

    // ── Global rotate mode ────────────────────────────────────────────────────

    private val _globalRotateEnabled = MutableLiveData<Boolean>(prefs.getBoolean(KEY_GLOBAL_ROTATE, false))
    val globalRotateEnabled: LiveData<Boolean> = _globalRotateEnabled

    fun toggleGlobalRotate() {
        val next = !(_globalRotateEnabled.value ?: false)
        prefs.edit().putBoolean(KEY_GLOBAL_ROTATE, next).apply()
        _globalRotateEnabled.value = next
    }

    // ── Allow edit mode ───────────────────────────────────────────────────────

    private val _allowEditEnabled = MutableLiveData<Boolean>(prefs.getBoolean(KEY_ALLOW_EDIT, false))
    val allowEditEnabled: LiveData<Boolean> = _allowEditEnabled

    fun toggleAllowEdit() {
        val next = !(_allowEditEnabled.value ?: false)
        prefs.edit().putBoolean(KEY_ALLOW_EDIT, next).apply()
        _allowEditEnabled.value = next
    }

    // ── Auto scroll mode ─────────────────────────────────────────────────────

    private val _autoScrollEnabled = MutableLiveData<Boolean>(prefs.getBoolean(KEY_AUTO_SCROLL, false))
    val autoScrollEnabled: LiveData<Boolean> = _autoScrollEnabled

    fun toggleAutoScroll() {
        val next = !(_autoScrollEnabled.value ?: false)
        prefs.edit().putBoolean(KEY_AUTO_SCROLL, next).apply()
        _autoScrollEnabled.value = next
    }

    // ── Auto mode ─────────────────────────────────────────────────────────────
    //  Hold on the "Next" button cycles between two states:
    //   • OFF ("Next")  – normal sibling rotation, Global Rotate follows its own toggle.
    //   • ON  ("Auto")  – Global Rotate is forced off; when the countdown timer expires
    //                     the scheduler automatically advances to the next EEVDF task and
    //                     restarts the timer, hands-free.

    private val _autoMode = MutableLiveData<Boolean>(prefs.getBoolean(KEY_AUTO_MODE, false))
    val autoMode: LiveData<Boolean> = _autoMode

    /** Mirrors MainActivity's active tab position so onTimerFinished can auto-advance correctly. */
    var activeTab: Int = 0

    /** Set to true by onTimerFinished when auto mode queues the next task; consumed by MainActivity. */
    private var pendingAutoStart = false

    /**
     * Holds the reset-state task while the expire card is visible.
     * Set in onTimerFinished when the timer card closes and the expire card opens.
     * Consumed in stopAlarmSound() to reopen the timer card with the default timer.
     * Null at all other times.
     */
    private var taskToRestoreAfterExpire: Task? = null

    /**
     * Global Rotate state saved on entering Auto mode so it can be restored on exit.
     * Persisted to prefs so the value survives an app kill while Auto mode is active.
     * Without persistence: global_rotate=false is saved when Auto turns on; on relaunch
     * savedGlobalRotateBeforeAuto defaults to false; toggling Auto off restores false
     * instead of the original true — silently losing the user's Global Rotate setting.
     */
    private var savedGlobalRotateBeforeAuto: Boolean =
        prefs.getBoolean(KEY_GLOBAL_ROTATE_BEFORE_AUTO, false)

    /**
     * Toggles Auto mode on/off (bound to long-press of the "Next" button).
     *  ON  → saves and forces Global Rotate OFF; button label → "Auto".
     *  OFF → restores Global Rotate to its previous state; button label → "Next".
     */
    fun toggleAutoMode() {
        val next = !(_autoMode.value ?: false)
        if (next) {
            savedGlobalRotateBeforeAuto = _globalRotateEnabled.value ?: false
            // Persist the pre-auto value so it survives an app kill.
            prefs.edit()
                .putBoolean(KEY_GLOBAL_ROTATE_BEFORE_AUTO, savedGlobalRotateBeforeAuto)
                .putBoolean(KEY_GLOBAL_ROTATE, false)
                .apply()
            _globalRotateEnabled.value = false
            _toastMessage.value = "Auto mode ON — Global Rotate disabled"
        } else {
            // Restore and clear the saved value — no longer needed once Auto is off.
            prefs.edit()
                .putBoolean(KEY_GLOBAL_ROTATE, savedGlobalRotateBeforeAuto)
                .remove(KEY_GLOBAL_ROTATE_BEFORE_AUTO)
                .apply()
            _globalRotateEnabled.value = savedGlobalRotateBeforeAuto
            savedGlobalRotateBeforeAuto = false
            _toastMessage.value = "Auto mode OFF — Global Rotate restored"
        }
        prefs.edit().putBoolean(KEY_AUTO_MODE, next).apply()
        _autoMode.value = next
    }

    /**
     * Called from MainActivity's currentTask observer to consume the one-shot
     * auto-start flag set by onTimerFinished when auto mode is active.
     */
    fun consumePendingAutoStart(): Boolean {
        val v = pendingAutoStart
        pendingAutoStart = false
        return v
    }

    /** Direct DB lookup used by AddTaskActivity to reliably load a task for editing. */
    suspend fun getTaskById(id: String): Task? = repository.getTaskById(id)

    // Initialized after init{} so activeTasks/_scheduleOrder are already assigned
    lateinit var flatActiveTasks:   MediatorLiveData<List<TaskDisplayItem>>
    lateinit var flatScheduleOrder: MediatorLiveData<List<TaskDisplayItem>>

    // ── Per-tab independent expand state ─────────────────────────────────────

    // Prefix constants for SharedPreferences expand persistence
    private val QUEUE_EXPAND_PREFIX    = "qexpand_"
    private val SCHEDULE_EXPAND_PREFIX = "sexpand_"

    /**
     * Queue tab expand state — loaded from prefs on init, saved on every toggle.
     * Key format in prefs: "qexpand_{taskId}" = Boolean
     */
    private val queueExpandState: MutableMap<String, Boolean> = run {
        val map = mutableMapOf<String, Boolean>()
        prefs.all.forEach { (key, value) ->
            if (key.startsWith(QUEUE_EXPAND_PREFIX) && value is Boolean)
                map[key.removePrefix(QUEUE_EXPAND_PREFIX)] = value
        }
        map
    }

    /**
     * Schedule tab expand state — loaded from prefs on init, saved on every toggle.
     * Key format in prefs: "sexpand_{taskId}" = Boolean
     */
    private val scheduleExpandState: MutableMap<String, Boolean> = run {
        val map = mutableMapOf<String, Boolean>()
        prefs.all.forEach { (key, value) ->
            if (key.startsWith(SCHEDULE_EXPAND_PREFIX) && value is Boolean)
                map[key.removePrefix(SCHEDULE_EXPAND_PREFIX)] = value
        }
        map
    }

    private val _queueExpandTrigger    = MutableLiveData(0)
    private val _scheduleExpandTrigger = MutableLiveData(0)

    fun toggleQueueGroupExpanded(group: Task) {
        val next = !(queueExpandState[group.id] ?: true)
        queueExpandState[group.id] = next
        prefs.edit().putBoolean(QUEUE_EXPAND_PREFIX + group.id, next).apply()
        _queueExpandTrigger.value = (_queueExpandTrigger.value ?: 0) + 1
    }

    fun toggleScheduleGroupExpanded(group: Task) {
        val next = !(scheduleExpandState[group.id] ?: true)
        scheduleExpandState[group.id] = next
        prefs.edit().putBoolean(SCHEDULE_EXPAND_PREFIX + group.id, next).apply()
        _scheduleExpandTrigger.value = (_scheduleExpandTrigger.value ?: 0) + 1
    }

    /**
     * Recursively collect IDs of every descendant group of [parentId].
     * Walks the full [allTasks] list — depth is unbounded.
     */
    private fun collectDescendantGroupIds(parentId: String, allTasks: List<Task>): List<String> {
        val result = mutableListOf<String>()
        val directChildren = allTasks.filter { it.parentId == parentId && it.isGroup }
        for (child in directChildren) {
            result += child.id
            result += collectDescendantGroupIds(child.id, allTasks)
        }
        return result
    }

    /**
     * Long-press on Queue tab group chevron:
     * Toggles the tapped group AND every descendant group to the same new state.
     * Ex: parent closed → parent + all child groups + grandchild groups all close.
     */
    fun deepToggleQueueGroupExpanded(group: Task) {
        val next     = !(queueExpandState[group.id] ?: true)
        val allTasks = activeTasks.value ?: emptyList()
        val targets  = listOf(group.id) + collectDescendantGroupIds(group.id, allTasks)
        val editor   = prefs.edit()
        for (id in targets) {
            queueExpandState[id] = next
            editor.putBoolean(QUEUE_EXPAND_PREFIX + id, next)
        }
        editor.apply()
        _queueExpandTrigger.value = (_queueExpandTrigger.value ?: 0) + 1
    }

    /**
     * Long-press on Schedule tab group chevron:
     * Toggles the tapped group AND every descendant group to the same new state.
     */
    fun deepToggleScheduleGroupExpanded(group: Task) {
        val next     = !(scheduleExpandState[group.id] ?: true)
        val allTasks = activeTasks.value ?: emptyList()
        val targets  = listOf(group.id) + collectDescendantGroupIds(group.id, allTasks)
        val editor   = prefs.edit()
        for (id in targets) {
            scheduleExpandState[id] = next
            editor.putBoolean(SCHEDULE_EXPAND_PREFIX + id, next)
        }
        editor.apply()
        _scheduleExpandTrigger.value = (_scheduleExpandTrigger.value ?: 0) + 1
    }

    /**
     * Hold Schedule Next while no timer card is active — Queue tab visible.
     * Rule (mirrors single-group hold): if ANY root group is closed → open all.
     *                                   if ALL root groups are open  → close all.
     * Applies to every root group AND all their descendants so the visual result
     * matches what the user expects ("everything opens" / "everything closes").
     */
    fun toggleAllQueueGroupsExpanded() {
        val allTasks    = activeTasks.value ?: emptyList()
        val rootGroups  = allTasks.filter { it.isGroup && it.parentId == null }
        if (rootGroups.isEmpty()) return
        val anyCollapsed = rootGroups.any { !(queueExpandState[it.id] ?: true) }
        val next   = anyCollapsed   // open all if any closed; close all if all open
        val editor = prefs.edit()
        for (group in rootGroups) {
            val targets = listOf(group.id) + collectDescendantGroupIds(group.id, allTasks)
            for (id in targets) {
                queueExpandState[id] = next
                editor.putBoolean(QUEUE_EXPAND_PREFIX + id, next)
            }
        }
        editor.apply()
        _queueExpandTrigger.value = (_queueExpandTrigger.value ?: 0) + 1
    }

    /**
     * Hold Schedule Next while no timer card is active — Schedule tab visible.
     * Same open/close rule as [toggleAllQueueGroupsExpanded].
     */
    fun toggleAllScheduleGroupsExpanded() {
        val allTasks    = activeTasks.value ?: emptyList()
        val rootGroups  = allTasks.filter { it.isGroup && it.parentId == null }
        if (rootGroups.isEmpty()) return
        val anyCollapsed = rootGroups.any { !(scheduleExpandState[it.id] ?: true) }
        val next   = anyCollapsed
        val editor = prefs.edit()
        for (group in rootGroups) {
            val targets = listOf(group.id) + collectDescendantGroupIds(group.id, allTasks)
            for (id in targets) {
                scheduleExpandState[id] = next
                editor.putBoolean(SCHEDULE_EXPAND_PREFIX + id, next)
            }
        }
        editor.apply()
        _scheduleExpandTrigger.value = (_scheduleExpandTrigger.value ?: 0) + 1
    }

    // ── Interrupt task operations ─────────────────────────────────────────────

    /** Sets [task] as the INT-A interrupt task; clears previous INT-A assignment. */
    fun assignInterruptTask(task: Task) = viewModelScope.launch {
        repository.setInterruptTask(task, slot = "A")
        _interruptTask.postValue(task.copy(isInterrupt = true, interruptSlot = "A"))
        refreshSchedule()
    }

    /** Sets [task] as the INT-B interrupt task; clears previous INT-B assignment. */
    fun assignInterruptTaskB(task: Task) = viewModelScope.launch {
        repository.setInterruptTask(task, slot = "B")
        _interruptTaskB.postValue(task.copy(isInterrupt = true, interruptSlot = "B"))
        refreshSchedule()
    }

    /** Clears the INT-A interrupt assignment. */
    fun clearInterruptTask() = viewModelScope.launch {
        repository.clearInterruptTask(slot = "A")
        _interruptTask.postValue(null)
        refreshSchedule()
    }

    /** Clears the INT-B interrupt assignment. */
    fun clearInterruptTaskB() = viewModelScope.launch {
        repository.clearInterruptTask(slot = "B")
        _interruptTaskB.postValue(null)
        refreshSchedule()
    }

    /**
     * INT button tap logic — delegates to whichever slot is currently active.
     */
    fun jumpToInterrupt() {
        if (_activeInterruptSlot.value == "B") jumpToInterruptB() else jumpToInterruptA()
    }

    /**
     * INT-A slot jump:
     * - Not on INT-A task → save current card, jump to INT-A.
     * - On INT-A task → jump back to saved card.
     */
    fun jumpToInterruptA() = jumpToInterruptSlot(
        interruptLiveData   = _interruptTask,
        savedSlotField      = { savedTaskBeforeInterrupt },
        setSavedSlot        = { savedTaskBeforeInterrupt = it },
        slotLabel           = "A"
    )

    /**
     * INT-B button tap logic (mirrors INT-A).
     */
    fun jumpToInterruptB() = jumpToInterruptSlot(
        interruptLiveData   = _interruptTaskB,
        savedSlotField      = { savedTaskBeforeInterruptB },
        setSavedSlot        = { savedTaskBeforeInterruptB = it },
        slotLabel           = "B"
    )

    private fun jumpToInterruptSlot(
        interruptLiveData: MutableLiveData<Task?>,
        savedSlotField:    () -> Task?,
        setSavedSlot:      (Task?) -> Unit,
        slotLabel:         String
    ) {
        val interrupt = interruptLiveData.value
            ?: activeTasks.value?.firstOrNull { it.isInterrupt && it.interruptSlot == slotLabel && !it.isCompleted }
            ?: run {
                _toastMessage.value = "No INT-$slotLabel task assigned"
                return
            }
        if (interruptLiveData.value == null) interruptLiveData.value = interrupt
        val current = _currentTask.value
        if (current?.id == interrupt.id) {
            val back = savedSlotField()
            setSavedSlot(null)
            if (back != null) {
                pauseTimer()
                _currentTask.value?.let { paused -> interruptLiveData.value = paused }
                _currentTask.value = back
                _timerSeconds.value = back.remainingSeconds
                _toastMessage.value = "Returned to \"${back.name}\""
            } else {
                // BUG FIX: When the user jumped to the interrupt slot while no task
                // card was open (e.g. after timer expiry), savedTaskBeforeInterrupt
                // was saved as null.  The original code only posted a toast and left
                // _currentTask unchanged, so subsequent INT taps kept seeing
                // current?.id == interrupt.id → kept trying to "return" with a null
                // back → infinite stuck loop.
                //
                // Fix: pause + close the timer card so _currentTask becomes null.
                // The next INT tap will then enter the JUMP branch instead of RETURN,
                // breaking the loop.  Also restore interruptLiveData so the interrupt
                // task reference is preserved for the next jump.
                pauseTimer()
                interruptLiveData.value = interrupt   // preserve interrupt reference
                _currentTask.value = null             // close card, exits stuck loop
                _toastMessage.value = "No saved task to return to"
            }
        } else {
            setSavedSlot(current)
            pauseTimer()
            val freshInterrupt = activeTasks.value
                ?.firstOrNull { it.isInterrupt && it.interruptSlot == slotLabel && !it.isCompleted }
                ?: interrupt
            interruptLiveData.value = freshInterrupt
            _currentTask.value = freshInterrupt
            _timerSeconds.value = freshInterrupt.remainingSeconds
            _toastMessage.value = "Jumped to INT-$slotLabel: \"${freshInterrupt.name}\""
        }
    }

    // ── Auto Switch — Call Detection ──────────────────────────────────────────

    /**
     * True while a call is in progress. Separate from [savedTaskBeforeCall] because
     * savedTaskBeforeCall can legitimately be null (no task card was open before the call).
     */
    private var callInProgress: Boolean = false

    /** The task that was showing before the call started. Null means no card was open. */
    private var savedTaskBeforeCall: Task? = null

    /**
     * Whether the timer was actively running when the call arrived.
     * Used to restore the exact paused/running state when the call ends.
     */
    private var wasTimerRunningBeforeCall: Boolean = false

    /**
     * Called by MainActivity when [CallEvents] posts CALL_STARTED.
     * Pauses the current task (if any) and switches to the call-assigned task.
     * [callTaskId] comes from [AutoSwitchPrefs.getCallTaskId].
     */
    fun handleCallStarted(callTaskId: String) {
        if (callInProgress) return   // already in a call, ignore nested events

        val callTask = activeTasks.value
            ?.firstOrNull { it.id == callTaskId && !it.isCompleted }
            ?: run {
                _toastMessage.value = "Call task not found — check Auto Switch settings"
                return
            }

        // Snapshot state before we touch anything
        callInProgress           = true
        savedTaskBeforeCall      = _currentTask.value
        wasTimerRunningBeforeCall = _timerRunning.value == true

        pauseTimer()
        _currentTask.value  = callTask
        _timerSeconds.value = callTask.remainingSeconds
        startTimer()
        _toastMessage.value = "Call started → \"${callTask.name}\""
    }

    /**
     * Called by MainActivity when [CallEvents] posts CALL_ENDED.
     * Pauses the call task, then restores the previous task in its original state:
     *  - If no task was open before → close the timer card (set current to null).
     *  - If a task was paused before → return to it but do NOT start the timer.
     *  - If a task was running before → return to it and resume the timer.
     */
    fun handleCallEnded() {
        if (!callInProgress) return
        callInProgress = false

        val returnTo    = savedTaskBeforeCall   // may be null — that's valid
        val wasRunning  = wasTimerRunningBeforeCall

        savedTaskBeforeCall       = null
        wasTimerRunningBeforeCall = false

        pauseTimer()

        if (returnTo == null) {
            // No task card was open before the call → close the card
            _currentTask.value  = null
            _toastMessage.value = "Call ended"
            return
        }

        // Restore the previous task card
        _currentTask.value  = returnTo
        _timerSeconds.value = returnTo.remainingSeconds

        if (wasRunning) {
            startTimer()
            _toastMessage.value = "Call ended → resumed \"${returnTo.name}\""
        } else {
            // Was paused before — leave it paused
            _toastMessage.value = "Call ended → \"${returnTo.name}\" (paused)"
        }
    }


    fun getQueueExpanded(taskId: String): Boolean = queueExpandState[taskId] ?: true

    /** Exposed for adapter rotation icon — returns Schedule expand state for [taskId]. */
    fun getScheduleExpanded(taskId: String): Boolean = scheduleExpandState[taskId] ?: true

    // ── Tab persistence ───────────────────────────────────────────────────────

    private val KEY_LAST_TAB = "last_tab"

    fun saveTab(tab: Int) { prefs.edit().putInt(KEY_LAST_TAB, tab).apply() }

    fun getSavedTab(): Int = prefs.getInt(KEY_LAST_TAB, 0)

    // ── Number extraction helper for Queue sort ───────────────────────────────

    private val numberRegex = Regex("""(\d+(?:\.\d+)?)""")

    private fun extractNumber(name: String): Double =
        numberRegex.find(name)?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: Double.MAX_VALUE

    /**
     * Queue tab: static sort by first number in task name (number pattern).
     * "icon 1"→1.0, "task 1.1"→1.1, "10 work"→10.0, "clean 4.4"→4.4.
     * Tasks with no number sort after numbered ones.
     * Uses queueExpandState — independent of Schedule tab.
     */
    private fun buildQueueList(tasks: List<Task>, groupsEnabled: Boolean): List<TaskDisplayItem> {
        val shares = EEVDFScheduler.computeShares(tasks, groupsEnabled)
        if (!groupsEnabled) {
            return tasks
                .filter { !it.isGroup }
                .sortedWith(compareBy({ extractNumber(it.name) }, { it.name }))
                .map { TaskDisplayItem(it, 0, cpuShare = shares[it.id] ?: 0.0,
                    effectiveQuotaExceeded = it.isQuotaExceeded,
                    effectiveQuotaWarning  = it.isQuotaWarning) }
        }
        val result = mutableListOf<TaskDisplayItem>()
        fun addLevel(parentId: String?, depth: Int, parentQuotaExceeded: Boolean, parentQuotaWarning: Boolean) {
            val children = tasks
                .filter { it.parentId == parentId }
                .sortedWith(compareBy({ extractNumber(it.name) }, { it.name }))
            for (task in children) {
                val dc = tasks.filter { it.parentId == task.id }
                val quotaExceeded = parentQuotaExceeded || task.isQuotaExceeded
                val quotaWarning  = !quotaExceeded && (parentQuotaWarning || task.isQuotaWarning)
                result.add(TaskDisplayItem(task, depth, dc.size, dc.sumOf { it.totalRunTime },
                    shares[task.id] ?: 0.0, quotaExceeded, quotaWarning))
                if (task.isGroup && (queueExpandState[task.id] ?: true))
                    addLevel(task.id, depth + 1, quotaExceeded, quotaWarning)
            }
        }
        addLevel(null, 0, false, false)
        return result
    }

    /**
     * Schedule tab: live EEVDF sort (virtualDeadline ascending).
     * Uses scheduleExpandState — independent of Queue tab.
     * Sources from activeTasks so reflects DB changes (vruntime updates, new tasks) instantly.
     */
    private fun buildScheduleList(tasks: List<Task>, groupsEnabled: Boolean): List<TaskDisplayItem> {
        val shares = EEVDFScheduler.computeShares(tasks, groupsEnabled)
        if (!groupsEnabled) {
            return tasks
                .filter { !it.isGroup }
                .sortedBy { it.virtualDeadline }
                .map { TaskDisplayItem(it, 0, cpuShare = shares[it.id] ?: 0.0,
                    effectiveQuotaExceeded = it.isQuotaExceeded,
                    effectiveQuotaWarning  = it.isQuotaWarning) }
        }
        val result = mutableListOf<TaskDisplayItem>()
        fun addLevel(parentId: String?, depth: Int, parentQuotaExceeded: Boolean, parentQuotaWarning: Boolean) {
            val children = tasks
                .filter { it.parentId == parentId }
                .sortedBy { it.virtualDeadline }
            for (task in children) {
                val dc = tasks.filter { it.parentId == task.id }
                val quotaExceeded = parentQuotaExceeded || task.isQuotaExceeded
                val quotaWarning  = !quotaExceeded && (parentQuotaWarning || task.isQuotaWarning)
                result.add(TaskDisplayItem(task, depth, dc.size, dc.sumOf { it.totalRunTime },
                    shares[task.id] ?: 0.0, quotaExceeded, quotaWarning))
                if (task.isGroup && (scheduleExpandState[task.id] ?: true))
                    addLevel(task.id, depth + 1, quotaExceeded, quotaWarning)
            }
        }
        addLevel(null, 0, false, false)
        return result
    }

    // ── Timer state ───────────────────────────────────────────────────────────

    // CountDownTimer still used for delay, wait, and overrun phases only.
    // The main execute countdown is owned entirely by TimerEngine.
    private var overrunTimer: CountDownTimer? = null
    private var delayTimer:   CountDownTimer? = null
    private var waitTimer:    CountDownTimer? = null

    private val _delayRunning = MutableLiveData<Boolean>(false)
    val delayRunning: LiveData<Boolean> = _delayRunning
    private val _delaySecondsRemaining = MutableLiveData<Long>(0L)
    val delaySecondsRemaining: LiveData<Long> = _delaySecondsRemaining

    private val _waitRunning = MutableLiveData<Boolean>(false)
    val waitRunning: LiveData<Boolean> = _waitRunning
    private val _waitSecondsRemaining = MutableLiveData<Long>(0L)
    val waitSecondsRemaining: LiveData<Long> = _waitSecondsRemaining

    /**
     * Current phase of the active NOTIFICATION-type task's state machine.
     * UI observes this to determine which button label to show:
     *   Idle    → "Start"
     *   Delay   → "Cancel"
     *   Execute → "Pause"
     *   Wait    → "Cancel"
     *   Expired → (none — alarm banner shown)
     */
    private val _noticePhase = MutableLiveData<NoticePhase>(NoticePhase.Idle)
    val noticePhase: LiveData<NoticePhase> = _noticePhase

    // ── Derived button-state LiveData — single sources of truth ──────────────
    //
    // Each MediatorLiveData combines ALL inputs that affect a button into ONE
    // settled value.  The UI observer reads this value to set label + color.
    // The click handler dispatches from this value — never from .value of the
    // raw LiveData — eliminating the race window between two LiveData dispatches.

    /**
     * Start/Pause/Cancel button — derived from noticePhase + timerRunning + currentTask.
     *
     * Derivation order:
     *  1. No task selected           → Unavailable  (no card, button hidden/disabled)
     *  2. Notice Delay phase active  → Cancel
     *  3. Notice Wait  phase active  → Cancel
     *  4. Notice Execute phase       → Pause   (same as normal running)
     *  5. Notice Expired             → Unavailable  (alarm banner owns the action)
     *  6. Timer running              → Pause
     *  7. Everything else            → Start
     *
     * Never reads timerRunning and noticePhase separately at different points in time.
     */
    val timerCardAction: MediatorLiveData<TimerCardAction> =
        MediatorLiveData<TimerCardAction>().apply {
            fun derive() {
                val phase   = _noticePhase.value  ?: NoticePhase.Idle
                val running = _timerRunning.value ?: false
                val task    = _currentTask.value
                value = when {
                    task == null                  -> TimerCardAction.Unavailable
                    phase is NoticePhase.Delay    -> TimerCardAction.Cancel
                    phase is NoticePhase.Wait     -> TimerCardAction.Cancel
                    phase is NoticePhase.Execute  -> TimerCardAction.Pause
                    phase is NoticePhase.Expired  -> TimerCardAction.Unavailable
                    running                       -> TimerCardAction.Pause
                    else                          -> TimerCardAction.Start
                }
            }
            addSource(_noticePhase)  { derive() }
            addSource(_timerRunning) { derive() }
            addSource(_currentTask)  { derive() }
        }

    /**
     * INT button label + colour — derived from activeInterruptSlot + interruptTask + interruptTaskB.
     * Replaces the three separate applyIntButtonState observers in MainActivity.
     */
    val intButtonState: MediatorLiveData<IntButtonState> =
        MediatorLiveData<IntButtonState>().apply {
            fun derive() {
                val slot    = _activeInterruptSlot.value ?: "A"
                val taskA   = _interruptTask.value
                val taskB   = _interruptTaskB.value
                val hasTask = if (slot == "A") taskA != null else taskB != null
                value = IntButtonState(slot = slot, hasTask = hasTask)
            }
            addSource(_activeInterruptSlot) { derive() }
            addSource(_interruptTask)       { derive() }
            addSource(_interruptTaskB)      { derive() }
        }

    /**
     * Next / Auto button label — derived from autoMode.
     * Exists as a named type so future states can be added without touching the click handler.
     */
    val nextButtonState: MediatorLiveData<NextButtonState> =
        MediatorLiveData<NextButtonState>().apply {
            fun derive() {
                value = if (_autoMode.value == true) NextButtonState.Auto else NextButtonState.Next
            }
            addSource(_autoMode) { derive() }
        }

    /** Elapsed seconds accumulated across delay + execute + wait phases this session. */
    private var delayElapsedSeconds:      Long = 0L
    private var waitElapsedSeconds:       Long = 0L
    /**
     * Running total of every second consumed by the notice state machine in the
     * current session (delay + all execute cycles + all wait cycles).
     * Applied to vruntime in a single shot on expire or pause — never per-cycle.
     */
    private var noticeSessionSeconds:     Long = 0L
    /**
     * Real wall-clock epoch ms when the current notice cycle started (before the
     * delay phase).  Used as RunSession.NoticeSession.startEpochMs so the RunLog
     * always has the correct start time regardless of how many phase transitions
     * occurred within the cycle.
     */
    private var noticeSessionStartEpochMs: Long = 0L

    /** Tracks completed repeat cycles for the current Notice task session. */
    private var currentRepeatIteration = 0

    /**
     * Isolated timer engine — owns the CountDownTimer, in-memory TimerState, and
     * all epoch arithmetic for the active execute countdown.
     *
     * The ViewModel wires its LiveData outputs in init{} and calls its API
     * from startActualTimer / pauseTimer / resetTimer. Nothing else in the
     * ViewModel touches accumulatedMs or startTimeEpoch directly.
     */
    private val timerEngine = TimerEngine()

    init {
        val db = TaskDatabase.getDatabase(application)
        repository = TaskRepository(db.taskDao(), application)
        allTasks       = repository.allTasks
        activeTasks    = repository.activeTasks
        completedTasks = repository.completedTasks
        activeGroups   = repository.activeGroups

        // ── Wire TimerEngine outputs ──────────────────────────────────────────

        // Wire TimerEngine outputs via named observers so onCleared() can remove them.
        tickObserver = Observer { remainingSecs: Long ->
            _timerSeconds.postValue(remainingSecs)
            _currentTask.value?.let { t ->
                _currentTask.postValue(t.copy(remainingSeconds = remainingSecs))
            }
        }
        // expiredSession arrives from the engine in the same postValue batch as expiredTask.
        // Store it so expiredObserver can pass it to onTimerFinished.
        var pendingExpiredSession: RunSession? = null
        expiredSessionObserver = Observer { session: RunSession ->
            pendingExpiredSession = session
        }
        expiredObserver = Observer { expired: Task ->
            // Pair this task event with the session the engine emitted at the same time.
            // pendingExpiredSession is null only on the remaining<=0 path (vruntime already
            // applied by pauseTimer) — passing null to onTimerFinished signals that.
            val session = pendingExpiredSession
            pendingExpiredSession = null
            _timerRunning.postValue(false)
            _currentTask.value = expired
            onTimerFinished(session = session)
        }
        timerEngine.tickSeconds.observeForever(tickObserver)
        timerEngine.expiredSession.observeForever(expiredSessionObserver)
        timerEngine.expiredTask.observeForever(expiredObserver)

        // ── Startup / app-kill recovery ───────────────────────────────────────

        viewModelScope.launch {
            _interruptTask.postValue(repository.getInterruptTask())
            _interruptTaskB.postValue(repository.getInterruptTaskB())

            // ── Step 1: check if alarm is already ringing ─────────────────────
            // AlarmState persists across process death. If state is Ringing it means
            // the alarm fired and the service is playing sound, but the user killed
            // the app before tapping Stop. The expire card must be restored so the
            // user has a way to dismiss it. Without this check _alarmTaskName stays
            // null and the card never appears even though the alarm is still ringing.
            val alarmState = AlarmScheduler.currentState(getApplication())
            if (alarmState is AlarmState.Ringing) {
                // firedEpoch is the exact wall-clock ms when the alarm first fired,
                // persisted to disk by AlarmScheduler.onAlarmFired(). Computing
                // elapsed from it gives the correct total overrun across all reopens:
                //   kill app 30s after fire, reopen 1 min later → shows 1:30, not 0:00
                val elapsedSinceExpiry = ((System.currentTimeMillis() - alarmState.firedEpoch) / 1000L)
                    .coerceAtLeast(0L)
                _alarmTaskName.postValue(alarmState.taskName)
                _alarmElapsedSeconds.postValue(elapsedSinceExpiry)
                startInAppOverrunCounter(alarmState.taskName, elapsedSinceExpiry)
                return@launch
            }

            // ── Step 2: check if a task was mid-run when app was killed ───────
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
                    // Timer expired while the app was dead.
                    // Build a Recovered session using the real DB-stored start epoch so
                    // wallClockSeconds = remaining slice at kill time, not timeSliceSeconds.
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

        flatActiveTasks = MediatorLiveData<List<TaskDisplayItem>>().apply {
            fun rebuild() {
                val tasks   = activeTasks.value ?: emptyList()
                val enabled = _groupsEnabled.value ?: false
                value = buildQueueList(tasks, enabled)
            }
            addSource(activeTasks)         { rebuild() }
            addSource(_groupsEnabled)      { rebuild() }
            addSource(_queueExpandTrigger) { rebuild() }
        }

        flatScheduleOrder = MediatorLiveData<List<TaskDisplayItem>>().apply {
            fun rebuild() {
                val tasks   = activeTasks.value ?: emptyList()
                val enabled = _groupsEnabled.value ?: false
                value = buildScheduleList(tasks, enabled)
            }
            addSource(activeTasks)            { rebuild() }
            addSource(_groupsEnabled)         { rebuild() }
            addSource(_scheduleExpandTrigger) { rebuild() }
        }
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────

    /**
     * After any task mutation (add / update / delete / complete) the float-pool
     * changes for every sibling.  This re-derives [Task.internalWeight] for all
     * pinned tasks and batch-persists only the ones that actually changed, so the
     * cards immediately reflect the new effective weight without the user having
     * to re-open each task editor.
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
        _toastMessage.postValue("Task \"${task.name}\" added to scheduler")
    }

    fun updateTask(task: Task) = viewModelScope.launch {
        repository.update(task)
        syncPinnedWeights()
        refreshSchedule()
    }

    fun deleteTask(task: Task) = viewModelScope.launch {
        if (task.id == _currentTask.value?.id) {
            pauseTimer()
            _currentTask.postValue(null)
        }
        repository.delete(task)
        syncPinnedWeights()
        refreshSchedule()
        _toastMessage.postValue("Task \"${task.name}\" deleted")
    }

    /** Moves a completed task back to the active queue, restoring its timer slice. */
    fun revertTask(task: Task) = viewModelScope.launch {
        val reverted = task.copy(isCompleted = false).withTimerState(TimerState.reset())
        repository.update(reverted)
        syncPinnedWeights()
    }

    fun markCompleted(task: Task) = viewModelScope.launch {
        if (task.id == _currentTask.value?.id) stopTimer(completed = true)
        else repository.markCompleted(task)
        syncPinnedWeights()
        refreshSchedule()
    }

    fun clearCompleted() = viewModelScope.launch { repository.clearCompleted() }

    fun clearToast() { _toastMessage.value = null }

    // ── Group expand / collapse ───────────────────────────────────────────────

    fun toggleGroupExpanded(group: Task) = viewModelScope.launch {
        val updated = group.copy(isGroupExpanded = !group.isGroupExpanded)
        repository.update(updated)
        // flatActiveTasks rebuilds automatically via activeTasks LiveData
    }

    // ── Scheduler ────────────────────────────────────────────────────────────

    /**
     * Rotates to the next task.
     *  - Global Rotate ON : cycles through the first leaf of each root-level entry
     *                       (first leaf of group-a → first leaf of group-b → root tasks → wrap)
     *  - Global Rotate OFF: cycles through siblings (same parentId) in UI order.
     */
    /**
     * [onQueueTab] = true  → uses Queue task list (number-sorted)
     * [onQueueTab] = false → uses Schedule task list (VDL-sorted, default)
     */
    fun nextSibling(onQueueTab: Boolean = false) {
        pauseTimer()
        // BUG FIX: When no task card is open (_currentTask is null — e.g. after
        // timer expiry or pauseAndDismiss), rotateSiblings/rotateGlobal both
        // compute parentId = null and indexOf = -1, which makes every press
        // silently jump to the first root task or show "No other siblings" and
        // get stuck.  Fall back to jumpToFirst so the behaviour is predictable
        // and consistent with what the user actually wants: "give me something".
        if (_currentTask.value == null) {
            jumpToFirst(onQueueTab)
            return
        }
        if (_globalRotateEnabled.value == true) {
            rotateGlobal(onQueueTab)
        } else {
            rotateSiblings(onQueueTab)
        }
    }

    /** Sibling rotation: next leaf with same parentId.
     *  Queue tab → number sort; Schedule tab → VDL sort. */
    private fun rotateSiblings(onQueueTab: Boolean) {
        val current  = _currentTask.value
        val allTasks = (if (onQueueTab) flatActiveTasks else flatScheduleOrder)
            .value?.map { it.task } ?: return

        // Check parent group type — NOTIFICATION always jumps to lowest VDL, never rotates.
        val parentId   = current?.parentId
        val parentType = allTasks.find { it.id == parentId }?.taskType

        val base = allTasks
            .filter { !it.isGroup && !it.isCompleted && !it.isInterrupt && it.parentId == parentId }
        val siblings = if (onQueueTab)
            base.sortedWith(compareBy({ extractNumber(it.name) }, { it.name }))
        else
            base.sortedBy { it.virtualDeadline }

        if (siblings.size <= 1) {
            _toastMessage.value = "No other siblings to rotate"
            return
        }

        val next = if (parentType == "NOTIFICATION") {
            // NOTIFICATION parent: always the sibling with the lowest virtual deadline.
            // No rotation — same target every tap, consistent with auto mode behaviour.
            base.sortedBy { it.virtualDeadline }.first()
        } else {
            val idx = siblings.indexOfFirst { it.id == current?.id }
            siblings[(idx + 1) % siblings.size]
        }

        _currentTask.value  = next
        _timerSeconds.value = next.remainingSeconds
        _toastMessage.value = "Next: \"${next.name}\""
        viewModelScope.launch { refreshSchedule() }
    }

    /**
     * Global rotation: one representative leaf per root-level entry, in UI order.
     * For a group, the representative is its first leaf (depth-first, virtualDeadline order).
     * For a root leaf task, it represents itself.
     * Example: group-a(group-aa(task-aa1), task-a1), group-b(task-b1,task-b2), task-a
     *          → task-aa1 → task-b1 → task-a → (wrap)
     */
    /** Global rotation.
     *  Queue tab → number-sorted root entries.
     *  Schedule tab → VDL-sorted root entries (original behaviour). */
    private fun rotateGlobal(onQueueTab: Boolean) {
        val current  = _currentTask.value
        val allTasks = (if (onQueueTab) flatActiveTasks else flatScheduleOrder)
            .value?.map { it.task } ?: return
        // Root-level entries — skip interrupt-flagged tasks/groups
        val base = allTasks
            .filter { it.parentId == null && !it.isCompleted && !it.isInterrupt }
        val rootEntries = if (onQueueTab)
            base.sortedWith(compareBy({ extractNumber(it.name) }, { it.name }))
        else
            base.sortedBy { it.virtualDeadline }
        // Map each root entry to its representative first leaf;
        // skip empties and skip if the first leaf itself is the interrupt task
        val representatives = rootEntries.mapNotNull { root ->
            val leaf = if (!root.isGroup) root else firstLeafOf(allTasks, root.id)
            if (leaf == null || leaf.isInterrupt) null else Pair(root.id, leaf)
        }
        if (representatives.isEmpty()) return
        // Find which root slot the current task lives under
        val currentRootId = current?.let { rootAncestorOf(allTasks, it)?.id }
        val currentIdx    = representatives.indexOfFirst { it.first == currentRootId }
        val nextIdx       = (currentIdx + 1) % representatives.size
        val next          = representatives[nextIdx].second
        _currentTask.value  = next
        _timerSeconds.value = next.remainingSeconds
        _toastMessage.value = "Next: \"${next.name}\" (${nextIdx + 1}/${representatives.size})"
        viewModelScope.launch { refreshSchedule() }
    }

    /** Returns the first non-group, non-completed leaf under [parentId] in virtualDeadline order. */
    private fun firstLeafOf(tasks: List<Task>, parentId: String?): Task? {
        val children = tasks
            .filter { it.parentId == parentId && !it.isCompleted && !it.isInterrupt }
            .sortedBy { it.virtualDeadline }
        for (child in children) {
            if (!child.isGroup) return child
            val leaf = firstLeafOf(tasks, child.id)
            if (leaf != null) return leaf
        }
        return null
    }

    /** Traces up parentId chain to return the root-level ancestor (parentId == null). */
    private fun rootAncestorOf(tasks: List<Task>, task: Task): Task? {
        if (task.parentId == null) return task
        val parent = tasks.find { it.id == task.parentId } ?: return task
        return rootAncestorOf(tasks, parent)
    }

    /**
     * Selects the next task to run when Auto mode is active, using the parent
     * group's taskType to choose the appropriate sibling-navigation strategy.
     *
     * | Parent taskType | Strategy                                             |
     * |-----------------|------------------------------------------------------|
     * | DEFAULT         | Next sibling by VDL order, looping back to first     |
     * | NOTIFICATION    | VDL-first sibling (lowest virtual deadline)          |
     * | ALERT / CUSTOM  | null → caller falls back to global selectNextTask()  |
     * | no parent group | null → caller falls back to global selectNextTask()  |
     *
     * @param task      The task whose slice just expired.
     * @param allTasks  Snapshot of active tasks (activeTasks.value).
     * @return The chosen sibling, or null if no parent-aware rule applies.
     */
    private fun selectAutoNextTask(task: Task, allTasks: List<Task>): Task? {
        val parentId = task.parentId ?: return null   // root task — no parent type to inherit
        val parent   = allTasks.find { it.id == parentId } ?: return null

        // All non-group, non-completed, non-interrupt siblings inside the same parent,
        // sorted by virtualDeadline (scheduler order) for consistent selection.
        val siblings = allTasks
            .filter { !it.isGroup && !it.isCompleted && !it.isInterrupt && it.parentId == parentId }
            .sortedBy { it.virtualDeadline }

        if (siblings.isEmpty()) return null

        return when (parent.taskType) {
            "DEFAULT" -> {
                // Rotate: next sibling in VDL order, wrapping back to the first.
                // indexOfFirst returns -1 if task is not found (e.g. already removed);
                // (-1 + 1) % size = 0 safely falls back to the first sibling.
                val idx = siblings.indexOfFirst { it.id == task.id }
                siblings[(idx + 1) % siblings.size]
            }
            "NOTIFICATION" -> {
                // Always jump to the sibling with the lowest virtual deadline.
                siblings.first()
            }
            else -> null   // ALERT, CUSTOM — not yet defined; fall back to global scheduler
        }
    }

    fun scheduleNext() = viewModelScope.launch {
        pauseTimer()
        val next = repository.selectNextTask()
        if (next != null) {
            _currentTask.postValue(next)
            _timerSeconds.postValue(next.remainingSeconds)
            _toastMessage.postValue("Now: \"${next.name}\" (Priority ${next.priority})")
        } else {
            _currentTask.postValue(null)
            _toastMessage.postValue("No active tasks to schedule")
        }
        refreshSchedule()
    }

    fun refreshSchedule() = viewModelScope.launch {
        val order = repository.getScheduleOrder()
        _scheduleOrder.postValue(order)
        val allActive = order + (completedTasks.value ?: emptyList())
        _stats.postValue(EEVDFScheduler.getStats(allActive))
    }

    // ── Timer ─────────────────────────────────────────────────────────────────

    fun startTimer() {
        if (_timerRunning.value == true || _delayRunning.value == true || _waitRunning.value == true) return

        val task      = _currentTask.value ?: return
        val remaining = _timerSeconds.value ?: task.remainingSeconds

        if (remaining <= 0) {
            // Slice already expired — the user paused at or after 0:00 before the
            // engine's CountDownTimer.onFinish() could fire. stopTicker() cancelled
            // the CountDownTimer so expiredTask never emitted. Trigger expiry now.
            //
            // Compute when the slice actually hit zero so the overrun counter starts
            // from the correct elapsed time (not always 0):
            //   expiryEpoch = now − overrunMs
            //   overrunMs   = accumulatedMs − sliceMs  (engine is Paused after pauseTimer)
            val sliceMs = task.timeSliceSeconds * 1000L
            when (val state = timerEngine.currentState()) {
                is TimerState.Paused  -> (state.accumulatedMs - sliceMs).coerceAtLeast(0L)
                is TimerState.Expired -> 0L
                else                  -> 0L
            }
            timerEngine.clear()   // engine already stopped; make Idle explicit
            // session = null → vruntime already applied by pauseTimer() above.
            // Passing a fake session here would double-count the elapsed time.
            onTimerFinished(task, session = null)
            return
        }

        val delaySecs = if (task.taskType == "NOTIFICATION") task.notificationDelaySeconds else 0L

        if (delaySecs > 0) {
            currentRepeatIteration    = 0   // fresh session — reset repeat counter
            noticeSessionSeconds      = 0L
            noticeSessionStartEpochMs = System.currentTimeMillis()
            startDelayPhase(task, remaining, delaySecs)
        } else {
            currentRepeatIteration    = 0
            noticeSessionSeconds      = 0L
            noticeSessionStartEpochMs = System.currentTimeMillis()
            startActualTimer(task, remaining)
        }
    }

    // ── Delay phase (step 2) ──────────────────────────────────────────────────

    // ── Notice task state machine ─────────────────────────────────────────────
    //
    // Phase transitions all go through _noticePhase so the UI only needs to
    // observe one LiveData to know which button to show.
    //
    // Idle    → "Start"   → startTimer() → Delay or Execute
    // Delay   → "Cancel"  → cancelNotice() → Idle
    // Execute → "Pause"   → pauseTimer() → Idle (slice preserved)
    // Wait    → "Cancel"  → cancelNotice() → Idle
    // Expired → (alarm banner, no timer button)

    private fun startDelayPhase(task: Task, remaining: Long, delaySecs: Long) {
        delayElapsedSeconds          = 0L
        waitElapsedSeconds           = 0L
        _delayRunning.value          = true
        _delaySecondsRemaining.value = delaySecs
        _noticePhase.value           = NoticePhase.Delay(delaySecs)
        val delayStart = System.currentTimeMillis()
        delayTimer?.cancel()
        delayTimer = object : CountDownTimer(delaySecs * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val secs = (millisUntilFinished / 1000L).coerceAtLeast(0L)
                _delaySecondsRemaining.postValue(secs)
                _noticePhase.postValue(NoticePhase.Delay(secs))
            }
            override fun onFinish() {
                delayElapsedSeconds   = ((System.currentTimeMillis() - delayStart) / 1000L).coerceAtLeast(0L)
                noticeSessionSeconds += delayElapsedSeconds
                _delayRunning.postValue(false)
                _delaySecondsRemaining.postValue(0L)
                delayTimer = null
                startExecutePhase(task, remaining, currentRepeatIteration)
            }
        }.start()
        AlarmForegroundService.delayStart(getApplication(), task.name, delaySecs)
    }

    private fun startExecutePhase(task: Task, secs: Long, iteration: Int) {
        _noticePhase.value = NoticePhase.Execute(iteration)
        SoundManager.playExecuteSound(getApplication(), prefs)
        startActualTimer(task, secs)
    }

    private fun startWaitPhase(task: Task, waitSecs: Long) {
        AlarmForegroundService.cancelScheduledAlarm(getApplication())
        _waitRunning.value          = true
        _waitSecondsRemaining.value = waitSecs
        _noticePhase.value          = NoticePhase.Wait(waitSecs, currentRepeatIteration)
        val waitStart = System.currentTimeMillis()
        SoundManager.playWaitSound(getApplication(), prefs)
        AlarmForegroundService.delayStart(getApplication(), task.name, waitSecs)
        waitTimer?.cancel()
        waitTimer = object : CountDownTimer(waitSecs * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val secs = (millisUntilFinished / 1000L).coerceAtLeast(0L)
                _waitSecondsRemaining.postValue(secs)
                _noticePhase.postValue(NoticePhase.Wait(secs, currentRepeatIteration))
            }
            override fun onFinish() {
                waitElapsedSeconds    = ((System.currentTimeMillis() - waitStart) / 1000L).coerceAtLeast(0L)
                noticeSessionSeconds += waitElapsedSeconds
                _waitRunning.postValue(false)
                _waitSecondsRemaining.postValue(0L)
                waitTimer = null
                val maxRepeat = task.notificationRepeatCount
                if (currentRepeatIteration < maxRepeat) {
                    currentRepeatIteration++
                    startExecutePhase(task, task.timeSliceSeconds, currentRepeatIteration)
                } else {
                    triggerAlarmExpire(task)
                }
            }
        }.start()
    }

    /**
     * Public entry point for the "Cancel" button shown during Delay or Wait phases,
     * and for the "Pause" button shown during Execute phase.
     *
     * Delay   → cancel: abort delay, return to Idle, "Start" shown
     * Wait    → cancel: abort wait, return to Idle, "Start" shown
     * Execute → pause:  handled by pauseTimer() which is already public
     *
     * Calling this when not in a Notice phase is a no-op.
     */
    fun cancelNotice() {
        when (_noticePhase.value) {
            is NoticePhase.Delay   -> cancelDelayPhase()
            is NoticePhase.Wait    -> cancelWaitPhase()
            is NoticePhase.Execute -> pauseTimer()   // execute cancel = pause
            else                   -> Unit
        }
    }

    private fun cancelDelayPhase() {
        val elapsed = _delaySecondsRemaining.value
            ?.let { (delaySecs() - it).coerceAtLeast(0L) } ?: 0L
        delayElapsedSeconds          = elapsed
        delayTimer?.cancel(); delayTimer = null
        _delayRunning.value          = false
        _delaySecondsRemaining.value = 0L
        currentRepeatIteration       = 0
        noticeSessionSeconds         = 0L
        _noticePhase.value           = NoticePhase.Idle
        if (elapsed > 0) {
            val nowMs = System.currentTimeMillis()
            applyVruntimeUpdate(RunSession.NoticeSession(
                taskId         = _currentTask.value?.id ?: "",
                startEpochMs   = noticeSessionStartEpochMs,
                endEpochMs     = nowMs,
                totalPhaseSecs = elapsed
            ))
        }
        AlarmForegroundService.timerPause(getApplication())
    }

    private fun delaySecs(): Long =
        if (_currentTask.value?.taskType == "NOTIFICATION")
            _currentTask.value?.notificationDelaySeconds ?: 0L else 0L

    private fun cancelWaitPhase() {
        val secs     = _waitSecondsRemaining.value ?: 0L
        val task     = _currentTask.value
        val waitSecs = task?.notificationRestSeconds ?: 0L
        waitElapsedSeconds          = (waitSecs - secs).coerceAtLeast(0L)
        waitTimer?.cancel(); waitTimer = null
        _waitRunning.value          = false
        _waitSecondsRemaining.value = 0L
        currentRepeatIteration      = 0
        _noticePhase.value          = NoticePhase.Idle
        val totalConsumed = noticeSessionSeconds + waitElapsedSeconds
        noticeSessionSeconds = 0L
        if (totalConsumed > 0) {
            val nowMs = System.currentTimeMillis()
            applyVruntimeUpdate(RunSession.NoticeSession(
                taskId         = task?.id ?: "",
                startEpochMs   = noticeSessionStartEpochMs,
                endEpochMs     = nowMs,
                totalPhaseSecs = totalConsumed
            ))
        }
        // Restore execute slice so Start restarts from the full slice
        val sliceSecs = task?.timeSliceSeconds ?: 0L
        _timerSeconds.value = sliceSecs
        _currentTask.value?.let { t ->
            _currentTask.value = t.copy(remainingSeconds = sliceSecs)
        }
        AlarmForegroundService.timerPause(getApplication())
    }

    private fun triggerAlarmExpire(task: Task) {
        val ctx = getApplication<Application>()
        _noticePhase.value = NoticePhase.Expired

        // Capture session total before clearing.
        val sessionSecs = noticeSessionSeconds
        noticeSessionSeconds = 0L

        // Run vruntime update and slice reset in ONE sequential coroutine.
        //
        // Root cause of 0:00 stuck bug:
        //   updateVruntimeAfterRun internally calls dao.update(task) to persist
        //   vruntime fields onto the task row. If reset ran in a separate coroutine
        //   and landed first, this dao.update would overwrite it and put
        //   remainingSeconds back to 0.
        //
        // Fix: single coroutine, reset runs AFTER updateVruntimeAfterRun so the
        // final write is always the correct reset (remainingSeconds = sliceSeconds).
        viewModelScope.launch {
            if (sessionSecs > 0) {
                val nowMs = System.currentTimeMillis()
                repository.updateVruntimeAfterRun(task, RunSession.NoticeSession(
                    taskId         = task.id,
                    startEpochMs   = noticeSessionStartEpochMs,
                    endEpochMs     = nowMs,
                    totalPhaseSecs = sessionSecs
                ))
            }
            repository.update(task.withTimerState(TimerState.reset()))
            refreshSchedule()
        }

        AlarmForegroundService.timerExpire(ctx, task.name, task.taskType)
        _alarmTaskName.postValue(task.name)
        _alarmElapsedSeconds.postValue(0L)
        startInAppOverrunCounter(task.name)
        _currentTask.postValue(null)
    }

    // ── Actual task timer ─────────────────────────────────────────────────────

    /**
     * Builds a Running state, persists it to DB, then hands off to [timerEngine].
     * Single entry point for starting an execute countdown.
     */
    private fun startActualTimer(task: Task, remaining: Long) {
        // Build the start event from the task's exact sealed state.
        //
        // OLD (buggy):
        //   val accumulated = (task.timeSliceSeconds - remaining) * 1000L
        //   remaining comes from _timerSeconds.value (integer seconds, truncated).
        //   Start→Pause in < 1s: remaining shows 9 even if only 50ms elapsed.
        //   accumulated = (10-9)*1000 = 1000ms — charges 1000ms for a 50ms tap.
        //   Each spam tap loses 1 display-second regardless of real time passing.
        //
        // NEW (fixed):
        //   TimerStartEvent.from reads accumulatedMs directly from the engine's
        //   Paused state (exact ms), never from a seconds-rounded display value.
        //   Start→Pause in 50ms → Paused(50ms) → next Start reads 50ms → correct.
        val nowMs   = System.currentTimeMillis()
        val event   = TimerStartEvent.from(task.timerState, nowMs)
        val running = event.toRunning
        val updated = task.withTimerState(running)

        _timerRunning.value = true
        // MUST update _currentTask with the Running state so the tick observer's
        //   _currentTask.postValue(t.copy(remainingSeconds = X))
        // carries the correct startTimeEpoch into each copy.
        // Without this, `t` in the tick observer still has startTimeEpoch=0 (the
        // pre-start task), so liveElapsedMs = accumulatedMs (fixed), and progressPercent
        // never moves while the timer is running — it only jumps to the correct value
        // on pause when _currentTask is explicitly updated with the paused task.
        _currentTask.value = updated
        viewModelScope.launch { repository.update(updated) }
        AlarmForegroundService.timerStart(getApplication(), task.name, remaining, task.taskType)
        timerEngine.start(updated)
    }

    fun pauseTimer() {
        if (_delayRunning.value == true) { cancelDelayPhase(); return }
        if (_waitRunning.value == true)  { cancelWaitPhase();  return }

        stopAlarmSound()

        val nowMs = System.currentTimeMillis()

        // Use RunSession from the engine — startEpochMs is the real wall-clock
        // epoch when Start was last tapped.  wallClockSeconds = (nowMs - startEpoch) / 1000,
        // so we never accidentally charge timeSliceSeconds for a resumed partial session.
        val result  = timerEngine.pause(nowMs)
        val session = result?.second   // RunSession.Paused; null if engine was already idle
        _timerRunning.value = false

        val task = _currentTask.value
        if (result != null) {
            val paused = result.first
            _currentTask.value  = paused
            _timerSeconds.value = paused.remainingSeconds
            viewModelScope.launch { repository.update(paused) }
            // BUG FIX (root cause of Next stuck / schedule-next random-jump):
            //
            // timerEngine.pause() returns the engine's own activeTask copy and also
            // keeps it stored as activeTask.  Every future pauseTimer() call —
            // including the one at the top of nextSibling(), jumpToInterruptSlot(),
            // and pauseAndDismiss() — calls timerEngine.pause() again.  Because
            // activeTask is never cleared, pause() returns the STALE previous task
            // and the line above silently overwrites _currentTask with it.
            //
            // Fix: clear the engine right after the state is saved so activeTask
            // becomes null.  startTimer() rebuilds the engine from _currentTask
            // directly, so clearing here does not break resume.
            timerEngine.clear()
        }

        if (task != null && task.taskType == "NOTIFICATION") {
            val executeSeconds    = session?.wallClockSeconds ?: 0L
            val totalConsumed     = noticeSessionSeconds + executeSeconds
            noticeSessionSeconds  = 0L
            _noticePhase.value    = NoticePhase.Idle
            if (totalConsumed > 0) {
                applyVruntimeUpdate(RunSession.NoticeSession(
                    taskId         = task.id,
                    startEpochMs   = noticeSessionStartEpochMs,
                    endEpochMs     = nowMs,
                    totalPhaseSecs = totalConsumed
                ))
            }
        } else if (session != null && session.wallClockSeconds > 0) {
            // For non-Notice tasks delayElapsedSeconds/waitElapsedSeconds are always 0,
            // so session.wallClockSeconds is the complete elapsed time this run.
            applyVruntimeUpdate(session)
        }
        delayElapsedSeconds = 0L
        waitElapsedSeconds  = 0L
        AlarmForegroundService.timerPause(getApplication())
    }


    fun resetTimer() {
        pauseTimer()
        timerEngine.clear()
        currentRepeatIteration = 0
        noticeSessionSeconds   = 0L
        _noticePhase.value     = NoticePhase.Idle
        val task = _currentTask.value ?: return
        val reset = task.withTimerState(TimerState.reset())
        _timerSeconds.value = reset.remainingSeconds
        viewModelScope.launch {
            repository.update(reset)
            _currentTask.postValue(reset)
        }
    }

    /** Reset the timer slice of any task back to its default [timeSliceSeconds]. */
    fun resetSlice(task: Task) {
        if (task.id == _currentTask.value?.id) {
            resetTimer()
            return
        }
        viewModelScope.launch {
            repository.update(task.withTimerState(TimerState.reset()))
        }
    }

    fun skipTask() {
        stopAlarmSound()
        pauseTimer()
        val task = _currentTask.value ?: return
        _toastMessage.value = "Skipped \"${task.name}\""
        _currentTask.value = null
        scheduleNext()
    }

    /**
     * Called when the countdown reaches zero.
     *
     * [taskOverride] is supplied by the app-killed recovery path in init{} where
     * _currentTask hasn't been set yet (postValue is asynchronous — it hasn't resolved
     * by the time onTimerFinished() is called on the same thread).  Every other call
     * site leaves [taskOverride] null and relies on _currentTask.value as before.
     */
    private fun onTimerFinished(
        taskOverride: Task? = null,
        session: RunSession? = null   // null = vruntime already applied by caller (remaining<=0 path)
    ) {
        val task = taskOverride ?: _currentTask.value ?: return
        val ctx  = getApplication<Application>()

        // How long ago did the slice actually expire?
        // session.endEpochMs is the real wall-clock moment the engine fired onFinish(),
        // so elapsedSinceExpiry reflects actual overrun time in both live and recovered paths.
        val expiryEpochMs      = session?.endEpochMs ?: System.currentTimeMillis()
        val elapsedSinceExpiry = ((System.currentTimeMillis() - expiryEpochMs) / 1000L)
            .coerceAtLeast(0L)

        // Clear the engine immediately — synchronously, before any coroutine or suspend call.
        // CRITICAL: setCurrentTask() calls pauseTimer() which calls timerEngine.pause().
        // If the engine still holds TimerState.Expired when that happens, pause() computes
        // Paused(sliceMs) → withTimerState sets remainingSeconds=0 → _timerSeconds stuck at 0:00.
        // Clearing here ensures the engine is in Idle state before the user can interact.
        timerEngine.clear()

        viewModelScope.launch {
            // session == null means the remaining<=0 branch in startTimer() already
            // called applyVruntimeUpdate() via pauseTimer().  Passing null here prevents
            // double-counting.
            //
            // When session is non-null, session.wallClockSeconds = actual remaining slice
            // = (endEpoch - startEpoch) / 1000.  This is the fix for the core bug:
            //   task-A 10s slice, paused at 7s → resumed → expires after 3s.
            //   OLD: credits task.timeSliceSeconds = 10s. Total: 7+10 = 17s. WRONG.
            //   NEW: credits session.wallClockSeconds = 3s.  Total: 7+3 = 10s. CORRECT.
            if (session != null) {
                if (task.taskType != "NOTIFICATION") {
                    repository.updateVruntimeAfterRun(task, session)
                } else {
                    noticeSessionSeconds += session.wallClockSeconds
                }
            }
            repository.update(task.withTimerState(TimerState.reset()))
            _toastMessage.postValue("Time slice done for \"${task.name}\"")
            refreshSchedule()

            if (_autoMode.value == true) {
                // Select the next task using the parent group's taskType strategy.
                // selectAutoNextTask() returns null when there is no parent group or
                // the type is not yet handled (ALERT, CUSTOM) — in those cases fall
                // back to the global EEVDF scheduler (selectNextTask).
                val allTasks = activeTasks.value ?: emptyList()
                val next = selectAutoNextTask(task, allTasks) ?: repository.selectNextTask()
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
                val waitSecs  = task.notificationRestSeconds
                val maxRepeat = task.notificationRepeatCount
                when {
                    waitSecs > 0 -> startWaitPhase(task, waitSecs)
                    currentRepeatIteration < maxRepeat -> {
                        currentRepeatIteration++
                        startExecutePhase(task, task.timeSliceSeconds, currentRepeatIteration)
                    }
                    else -> triggerAlarmExpire(task)
                }
            } else {
                AlarmForegroundService.timerExpire(ctx, task.name, task.taskType)
                _alarmTaskName.postValue(task.name)
                _alarmElapsedSeconds.postValue(elapsedSinceExpiry)
                startInAppOverrunCounter(task.name, elapsedSinceExpiry)
                // Proper one-at-a-time card transition:
                //   1. Close timer card  (_currentTask = null)
                //   2. Open expire card  (_alarmTaskName already posted above)
                // When user taps Stop, stopAlarmSound() closes the expire card and
                // reopens the timer card with the reset (default) state — never both visible.
                taskToRestoreAfterExpire = task.withTimerState(TimerState.reset())
                _currentTask.postValue(null)
            }
        }
    }

    private fun startInAppOverrunCounter(taskName: String, initialElapsedSeconds: Long = 0L) {
        overrunTimer?.cancel()
        overrunTimer = object : CountDownTimer(3600_000L, 1000L) {
            // Start from the real elapsed time since expiry, not always from 0.
            // When the app was killed and the timer expired while dead, this ensures
            // the card shows the correct elapsed time from the moment it actually
            // expired — not from the moment the user opened the app.
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
        AlarmForegroundService.stopAlarm(getApplication())
        // Reopen the timer card with the reset (default) state after the expire card closes.
        // taskToRestoreAfterExpire is null when stopAlarmSound is called for reasons other
        // than a slice expiry (e.g. alarm from a previous session on app relaunch), so the
        // null check prevents accidentally reopening a card the user never had open.
        taskToRestoreAfterExpire?.let { resetTask ->
            _currentTask.postValue(resetTask)
            _timerSeconds.postValue(resetTask.timeSliceSeconds)
            taskToRestoreAfterExpire = null
        }
    }

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

    private fun applyVruntimeUpdate(session: RunSession) {
        val task = _currentTask.value ?: return
        viewModelScope.launch {
            repository.updateVruntimeAfterRun(task, session)
            refreshSchedule()
        }
    }

    fun setCurrentTask(task: Task) {
        pauseTimer()
        _currentTask.value = task
        _timerSeconds.value = task.remainingSeconds
    }

    /**
     * Tap behaviour for the "Next" button.
     * Jumps to the first visible leaf task at the top of the current tab list
     * (depth-first, list order — e.g. group-a → group-aa → task-aa1).
     * Skips groups, completed tasks, and the interrupt task.
     * [onQueueTab] true → Queue list order; false → Schedule (VDL) list order.
     */
    fun jumpToFirst(onQueueTab: Boolean) {
        val list = if (onQueueTab) flatActiveTasks.value else flatScheduleOrder.value
        val first = list
            ?.firstOrNull { !it.task.isGroup && !it.task.isCompleted && !it.task.isInterrupt }
            ?.task
            ?: run { _toastMessage.value = "No tasks available"; return }
        pauseTimer()
        _currentTask.value = first
        _timerSeconds.value = first.remainingSeconds
        _toastMessage.value = "Jumped to \"${first.name}\""
    }

    /**
     * Hold behaviour for the "Next" button.
     * Saves the current timer state (identical to a manual pause) then
     * dismisses the timer card so the user sees the plain task list.
     */
    fun pauseAndDismiss() {
        pauseTimer()
        _currentTask.value = null
        _toastMessage.value = "Timer paused — task saved"
    }

    override fun onCleared() {
        super.onCleared()
        // Stop in-process timers and engine only.
        // Must NOT call stopAlarmSound() here — that calls AlarmScheduler.cancel()
        // which removes the AlarmManager entry.  If the ViewModel is cleared while
        // the timer is still running (app killed, config change), the alarm must
        // survive to fire at expiry.  AlarmManager lives in the system process and
        // is unaffected by ViewModel death — but calling cancel() here kills it.
        // Remove named observers to prevent accumulation across ViewModel recreation.
        timerEngine.tickSeconds.removeObserver(tickObserver)
        timerEngine.expiredSession.removeObserver(expiredSessionObserver)
        timerEngine.expiredTask.removeObserver(expiredObserver)
        timerEngine.clear()
        overrunTimer?.cancel()
        delayTimer?.cancel()
        waitTimer?.cancel()
        _noticePhase.value = NoticePhase.Idle
    }

    // ── DB export / import ────────────────────────────────────────────────────

    /**
     * Stops the timer, clears the current task, then flushes and closes the
     * Room connection so SettingsActivity can safely copy/replace the .db file.
     * Room re-initialises automatically the next time any DAO is accessed.
     */
    fun prepareForDbExport() {
        pauseTimer()
        _currentTask.postValue(null)
        TaskDatabase.checkpointAndClose(getApplication())
    }
}