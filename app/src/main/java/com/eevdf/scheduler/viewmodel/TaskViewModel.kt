package com.eevdf.scheduler.viewmodel

import android.app.Application
import android.content.Context
import android.os.CountDownTimer
import androidx.lifecycle.*
import com.eevdf.scheduler.db.TaskDatabase
import com.eevdf.scheduler.db.TaskRepository
import com.eevdf.scheduler.model.Task
import com.eevdf.scheduler.model.TaskDisplayItem
import com.eevdf.scheduler.scheduler.EEVDFScheduler
import com.eevdf.scheduler.scheduler.SchedulerStats
import com.eevdf.scheduler.ui.AlarmForegroundService
import kotlinx.coroutines.launch

class TaskViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("eevdf_prefs", Context.MODE_PRIVATE)
    private val KEY_GROUPS         = "groups_enabled"
    private val KEY_GLOBAL_ROTATE  = "global_rotate_enabled"
    private val KEY_ALLOW_EDIT     = "allow_edit_enabled"
    private val KEY_AUTO_SCROLL    = "auto_scroll_enabled"
    private val KEY_AUTO_MODE      = "auto_mode"

    private val repository: TaskRepository
    val allTasks: LiveData<List<Task>>
    val activeTasks: LiveData<List<Task>>
    val completedTasks: LiveData<List<Task>>

    /** Groups available for parent selection in AddTaskActivity. */
    val activeGroups: LiveData<List<Task>>

    private val _currentTask = MutableLiveData<Task?>(null)
    val currentTask: LiveData<Task?> = _currentTask

    // ── Interrupt task ────────────────────────────────────────────────────────

    /** The task currently flagged as the interrupt destination. */
    private val _interruptTask = MutableLiveData<Task?>(null)
    val interruptTask: LiveData<Task?> = _interruptTask

    /** Saved card to return to after visiting the interrupt task. */
    private var savedTaskBeforeInterrupt: Task? = null

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

    /** Global Rotate state saved on entering Auto mode so it can be restored on exit. */
    private var savedGlobalRotateBeforeAuto: Boolean = false

    /**
     * Toggles Auto mode on/off (bound to long-press of the "Next" button).
     *  ON  → saves and forces Global Rotate OFF; button label → "Auto".
     *  OFF → restores Global Rotate to its previous state; button label → "Next".
     */
    fun toggleAutoMode() {
        val next = !(_autoMode.value ?: false)
        if (next) {
            savedGlobalRotateBeforeAuto = _globalRotateEnabled.value ?: false
            prefs.edit().putBoolean(KEY_GLOBAL_ROTATE, false).apply()
            _globalRotateEnabled.value = false
            _toastMessage.value = "Auto mode ON — Global Rotate disabled"
        } else {
            prefs.edit().putBoolean(KEY_GLOBAL_ROTATE, savedGlobalRotateBeforeAuto).apply()
            _globalRotateEnabled.value = savedGlobalRotateBeforeAuto
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

    // ── Interrupt task operations ─────────────────────────────────────────────

    /** Sets [task] as the single interrupt task; clears any previous assignment. */
    fun assignInterruptTask(task: Task) = viewModelScope.launch {
        repository.setInterruptTask(task)
        _interruptTask.postValue(task.copy(isInterrupt = true))
        refreshSchedule()
    }

    /** Clears the interrupt assignment from all tasks. */
    fun clearInterruptTask() = viewModelScope.launch {
        repository.clearInterruptTask()
        _interruptTask.postValue(null)
        refreshSchedule()
    }

    /**
     * Int button logic:
     * - If not currently on the interrupt task → save current card, jump to interrupt.
     * - If currently on the interrupt task → jump back to saved card.
     */
    fun jumpToInterrupt() {
        val interrupt = _interruptTask.value
            ?: activeTasks.value?.firstOrNull { it.isInterrupt && !it.isCompleted }
            ?: run {
                _toastMessage.value = "No interrupt task assigned"
                return
            }
        if (_interruptTask.value == null) _interruptTask.value = interrupt
        val current = _currentTask.value
        if (current?.id == interrupt.id) {
            val back = savedTaskBeforeInterrupt
            savedTaskBeforeInterrupt = null
            if (back != null) {
                pauseTimer()
                // Sync _interruptTask with the just-paused state so it has the correct
                // remainingSeconds when the user jumps back to it later.
                _currentTask.value?.let { paused -> _interruptTask.value = paused }
                _currentTask.value = back
                _timerSeconds.value = back.remainingSeconds
                _toastMessage.value = "Returned to \"${back.name}\""
            } else {
                _toastMessage.value = "No saved task to return to"
            }
        } else {
            savedTaskBeforeInterrupt = current
            pauseTimer()
            // Prefer fresh interrupt data from activeTasks LiveData (Room keeps this
            // up-to-date from the DB, so remainingSeconds reflects the last persisted
            // value rather than the potentially stale in-memory _interruptTask copy).
            val freshInterrupt = activeTasks.value?.firstOrNull { it.isInterrupt && !it.isCompleted }
                ?: interrupt
            _interruptTask.value = freshInterrupt
            _currentTask.value = freshInterrupt
            _timerSeconds.value = freshInterrupt.remainingSeconds
            _toastMessage.value = "Jumped to interrupt: \"${freshInterrupt.name}\""
        }
    }

    /** Exposed for adapter rotation icon — returns Queue expand state for [taskId]. */
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
        if (!groupsEnabled) {
            return tasks
                .filter { !it.isGroup }
                .sortedWith(compareBy({ extractNumber(it.name) }, { it.name }))
                .map { TaskDisplayItem(it, 0) }
        }
        val result = mutableListOf<TaskDisplayItem>()
        fun addLevel(parentId: String?, depth: Int) {
            val children = tasks
                .filter { it.parentId == parentId }
                .sortedWith(compareBy({ extractNumber(it.name) }, { it.name }))
            for (task in children) {
                val dc = tasks.filter { it.parentId == task.id }
                result.add(TaskDisplayItem(task, depth, dc.size, dc.sumOf { it.totalRunTime }))
                if (task.isGroup && (queueExpandState[task.id] ?: true)) addLevel(task.id, depth + 1)
            }
        }
        addLevel(null, 0)
        return result
    }

    /**
     * Schedule tab: live EEVDF sort (virtualDeadline ascending).
     * Uses scheduleExpandState — independent of Queue tab.
     * Sources from activeTasks so reflects DB changes (vruntime updates, new tasks) instantly.
     */
    private fun buildScheduleList(tasks: List<Task>, groupsEnabled: Boolean): List<TaskDisplayItem> {
        if (!groupsEnabled) {
            return tasks
                .filter { !it.isGroup }
                .sortedBy { it.virtualDeadline }
                .map { TaskDisplayItem(it, 0) }
        }
        val result = mutableListOf<TaskDisplayItem>()
        fun addLevel(parentId: String?, depth: Int) {
            val children = tasks
                .filter { it.parentId == parentId }
                .sortedBy { it.virtualDeadline }
            for (task in children) {
                val dc = tasks.filter { it.parentId == task.id }
                result.add(TaskDisplayItem(task, depth, dc.size, dc.sumOf { it.totalRunTime }))
                if (task.isGroup && (scheduleExpandState[task.id] ?: true)) addLevel(task.id, depth + 1)
            }
        }
        addLevel(null, 0)
        return result
    }

    // ── Timer state ───────────────────────────────────────────────────────────

    private var countDownTimer: CountDownTimer? = null
    private var overrunTimer: CountDownTimer? = null

    /** Epoch ms when the current countdown expires — in-memory mirror of Task.timerDeadlineEpoch.
     *  0 means no timer is running. Written to DB the instant Start is pressed. */
    private var timerDeadlineEpoch: Long = 0L

    /** Remaining seconds at the moment the current session's Start was pressed.
     *  Used to compute elapsed = sessionStartRemaining - secondsLeftNow on pause. */
    private var sessionStartRemaining: Long = 0L

    init {
        val db = TaskDatabase.getDatabase(application)
        repository = TaskRepository(db.taskDao())
        allTasks       = repository.allTasks
        activeTasks    = repository.activeTasks
        completedTasks = repository.completedTasks
        activeGroups   = repository.activeGroups

        // Queue: stable name-number sort, own expand state, never re-sorts by vruntime
        // Load persisted interrupt task
        viewModelScope.launch {
            _interruptTask.postValue(repository.getInterruptTask())

            // Resume any task that was actively running when the app was killed or
            // the device went to sleep.  The wall-clock deadline tells us exactly
            // how many seconds remain without relying on any in-process state.
            val running = repository.getRunningTask()
            if (running != null) {
                val nowMs        = System.currentTimeMillis()
                val secondsLeft  = ((running.timerDeadlineEpoch - nowMs) / 1000L).coerceAtLeast(0L)
                if (secondsLeft > 0L) {
                    // Correct the in-DB remaining so the card shows the right value
                    val corrected = running.copy(remainingSeconds = secondsLeft)
                    repository.update(corrected)
                    _currentTask.postValue(corrected)
                    _timerSeconds.postValue(secondsLeft)
                    // Restore in-memory deadline so startTimer() / pauseTimer() work
                    timerDeadlineEpoch    = running.timerDeadlineEpoch
                    sessionStartRemaining = secondsLeft
                    // Re-attach the UI ticker (does not restart the AlarmManager alarm —
                    // AlarmForegroundService.timerStart already set that before the kill)
                    _timerRunning.postValue(true)
                    attachTickerOnly(secondsLeft)
                } else {
                    // Deadline already passed while the app was killed/dead.
                    // Do NOT postValue here — that would briefly show the timer card
                    // at 0:00 before onTimerFinished() clears it.  Instead, pass the
                    // task directly so onTimerFinished() can show the alarm banner
                    // without depending on the async _currentTask update.
                    onTimerFinished(running)
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

        // Schedule: live EEVDF sort directly from activeTasks — updates instantly
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

    fun addTask(task: Task) = viewModelScope.launch {
        repository.insert(task)
        refreshSchedule()
        _toastMessage.postValue("Task \"${task.name}\" added to scheduler")
    }

    fun updateTask(task: Task) = viewModelScope.launch {
        repository.update(task)
        refreshSchedule()
    }

    fun deleteTask(task: Task) = viewModelScope.launch {
        if (task.id == _currentTask.value?.id) {
            pauseTimer()
            _currentTask.postValue(null)
        }
        repository.delete(task)
        refreshSchedule()
        _toastMessage.postValue("Task \"${task.name}\" deleted")
    }

    /** Moves a completed task back to the active queue, restoring its timer slice. */
    fun revertTask(task: Task) = viewModelScope.launch {
        val reverted = task.copy(
            isCompleted      = false,
            isRunning        = false,
            remainingSeconds = task.timeSliceSeconds
        )
        repository.update(reverted)
    }

    fun markCompleted(task: Task) = viewModelScope.launch {
        if (task.id == _currentTask.value?.id) stopTimer(completed = true)
        else repository.markCompleted(task)
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
        val base = allTasks
            .filter { !it.isGroup && !it.isCompleted && !it.isInterrupt && it.parentId == current?.parentId }
        val siblings = if (onQueueTab)
            base.sortedWith(compareBy({ extractNumber(it.name) }, { it.name }))
        else
            base.sortedBy { it.virtualDeadline }
        if (siblings.size <= 1) {
            // Only one task in this sibling group — stay on the current task
            // rather than handing off to scheduleNext() which would close the card.
            _toastMessage.value = "No other siblings to rotate"
            return
        }
        val idx  = siblings.indexOfFirst { it.id == current?.id }
        val next = siblings[(idx + 1) % siblings.size]
        _currentTask.value  = next
        _timerSeconds.value = next.remainingSeconds
        _toastMessage.value = "Next: \"${next.name}\" (${(idx + 2) % siblings.size + 1}/${siblings.size})"
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
        // ── Rapid-tap guard ───────────────────────────────────────────────────
        // If the timer is already running, ignore the press entirely.
        // Fixes "60 taps = 60 seconds lost": each re-enter used to create a new
        // CountDownTimer starting from stale LiveData, shedding a second per tap.
        if (_timerRunning.value == true) return

        val task      = _currentTask.value ?: return
        val remaining = _timerSeconds.value ?: task.remainingSeconds
        if (remaining <= 0) return

        // ── Wall-clock anchor ─────────────────────────────────────────────────
        // Store the absolute expiry epoch rather than a duration.  Every tick,
        // pause, and resume derives elapsed/remaining from this anchor, so the
        // timer is immune to CountDownTimer drift, process death, and device sleep.
        val deadlineEpoch     = System.currentTimeMillis() + remaining * 1000L
        timerDeadlineEpoch    = deadlineEpoch
        sessionStartRemaining = remaining
        _timerRunning.value   = true

        // Persist the deadline immediately — if the process dies the instant after
        // this line the resume path in init{} will recover the correct time.
        viewModelScope.launch {
            repository.update(
                task.copy(remainingSeconds = remaining, isRunning = true,
                    timerDeadlineEpoch = deadlineEpoch)
            )
        }

        AlarmForegroundService.timerStart(
            getApplication(), task.name, remaining,
            task.taskType, task.notificationDelaySeconds
        )
        attachTickerOnly(remaining)
    }

    /**
     * Attaches a CountDownTimer used purely as a UI wakeup every second.
     * Every displayed value is derived from [timerDeadlineEpoch] (wall clock),
     * not from CountDownTimer's internal millisUntilFinished — so drift,
     * rapid restarts, and process death cannot corrupt the display.
     */
    private fun attachTickerOnly(remaining: Long) {
        countDownTimer?.cancel()
        countDownTimer = object : CountDownTimer(remaining * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsLeft = ((timerDeadlineEpoch - System.currentTimeMillis()) / 1000L)
                    .coerceAtLeast(0L)
                _timerSeconds.postValue(secondsLeft)
                _currentTask.value?.let { t ->
                    _currentTask.postValue(t.copy(remainingSeconds = secondsLeft))
                }
            }
            override fun onFinish() {
                _timerSeconds.postValue(0L)
                _timerRunning.postValue(false)
                timerDeadlineEpoch = 0L
                onTimerFinished()
            }
        }.start()
    }

    fun pauseTimer() {
        stopAlarmSound()
        countDownTimer?.cancel()
        countDownTimer = null
        _timerRunning.value = false

        // Derive exact remaining from the wall-clock deadline — not from the LiveData
        // value, which may lag one tick behind, and not from CountDownTimer state.
        val secondsLeft = if (timerDeadlineEpoch > 0L)
            ((timerDeadlineEpoch - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
        else
            _timerSeconds.value ?: 0L

        val elapsed = (sessionStartRemaining - secondsLeft).coerceAtLeast(0L)
        timerDeadlineEpoch    = 0L
        sessionStartRemaining = 0L

        // Snap in-memory task to exact paused time so the card shows the right value
        _currentTask.value?.let { t ->
            _currentTask.value = t.copy(remainingSeconds = secondsLeft, isRunning = false,
                timerDeadlineEpoch = 0L)
        }
        _timerSeconds.value = secondsLeft

        // Persist paused state — clear deadline so resume logic does not re-fire
        val task = _currentTask.value
        if (task != null) {
            viewModelScope.launch {
                repository.update(
                    task.copy(remainingSeconds = secondsLeft, isRunning = false,
                        timerDeadlineEpoch = 0L)
                )
            }
        }

        if (elapsed > 0) applyVruntimeUpdate(elapsed)
        AlarmForegroundService.timerPause(getApplication())
    }

    fun resetTimer() {
        pauseTimer()
        val task = _currentTask.value ?: return
        _timerSeconds.value = task.timeSliceSeconds
        viewModelScope.launch {
            val updated = task.copy(remainingSeconds = task.timeSliceSeconds)
            repository.update(updated)
            _currentTask.postValue(updated)
        }
    }

    /** Reset the timer slice of any task back to its default [timeSliceSeconds]. */
    fun resetSlice(task: Task) {
        // If this task is the currently running one, also reset the live timer display
        if (task.id == _currentTask.value?.id) {
            resetTimer()
            return
        }
        viewModelScope.launch {
            val updated = task.copy(remainingSeconds = task.timeSliceSeconds)
            repository.update(updated)
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
    private fun onTimerFinished(taskOverride: Task? = null) {
        val task = taskOverride ?: _currentTask.value ?: return
        val ctx = getApplication<Application>()

        viewModelScope.launch {
            // updateVruntimeAfterRun handles leaf + all ancestor groups
            repository.updateVruntimeAfterRun(task, task.timeSliceSeconds)
            val updated = task.copy(remainingSeconds = task.timeSliceSeconds)
            repository.update(updated)
            _toastMessage.postValue("Time slice done for \"${task.name}\"")
            refreshSchedule()

            if (_autoMode.value == true) {
                // Auto mode: skip the alarm banner and immediately advance to the
                // next EEVDF-selected task, then signal MainActivity to start the timer.
                val next = repository.selectNextTask()
                if (next != null) {
                    pendingAutoStart = true
                    _currentTask.postValue(next)
                    _timerSeconds.postValue(next.remainingSeconds)
                    _toastMessage.postValue("Auto → \"${next.name}\"")
                } else {
                    _currentTask.postValue(null)
                    _toastMessage.postValue("Auto: no more active tasks")
                }
            } else {
                AlarmForegroundService.timerExpire(ctx, task.name)
                _alarmTaskName.postValue(task.name)
                _alarmElapsedSeconds.postValue(0L)
                startInAppOverrunCounter(task.name)
                _currentTask.postValue(null)
            }
        }
    }

    private fun startInAppOverrunCounter(taskName: String) {
        overrunTimer?.cancel()
        overrunTimer = object : CountDownTimer(3600_000L, 1000L) {
            var elapsed = 0L
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
    }

    private fun stopTimer(completed: Boolean) {
        stopAlarmSound()
        countDownTimer?.cancel()
        countDownTimer = null
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

    private fun applyVruntimeUpdate(ranSeconds: Long) {
        val task = _currentTask.value ?: return
        viewModelScope.launch {
            repository.updateVruntimeAfterRun(task, ranSeconds)
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
        stopAlarmSound()
        countDownTimer?.cancel()
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