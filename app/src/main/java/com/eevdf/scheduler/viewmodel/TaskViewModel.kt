package com.eevdf.scheduler.viewmodel

import android.app.Application
import android.content.Context
import android.os.CountDownTimer
import androidx.lifecycle.*
import com.eevdf.scheduler.db.TaskDatabase
import com.eevdf.scheduler.db.TaskRepository
import com.eevdf.scheduler.model.Task
import com.eevdf.scheduler.scheduler.EEVDFScheduler
import com.eevdf.scheduler.model.TaskDisplayItem
import com.eevdf.scheduler.scheduler.SchedulerStats
import com.eevdf.scheduler.ui.AlarmForegroundService
import com.eevdf.scheduler.ui.SoundManager
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
     * Builds a map of taskId → exceedMultiple for every active task that has
     * [Task.frequencyPeriodHours] > 0.
     *
     * exceedMultiple = windowedSeconds / timeSliceSeconds
     *  • 1.0 = used exactly the allocated slice in the period
     *  • 2.0 = used twice the slice (2x)
     *  • 0.5 = used half the slice
     * null entry = feature disabled for that task or no runs in window yet.
     */
    private suspend fun buildExceedMap(tasks: List<Task>): Map<String, Double> {
        val result = mutableMapOf<String, Double>()
        for (task in tasks) {
            if (task.frequencyPeriodHours <= 0 || task.timeSliceSeconds <= 0) continue
            val windowedSecs = repository.getWindowedSeconds(task.id, task.frequencyPeriodHours)
            if (windowedSecs > 0) {
                result[task.id] = windowedSecs.toDouble() / task.timeSliceSeconds.toDouble()
            }
        }
        return result
    }

    /**
     * Queue tab: static sort by first number in task name (number pattern).
     * "icon 1"→1.0, "task 1.1"→1.1, "10 work"→10.0, "clean 4.4"→4.4.
     * Tasks with no number sort after numbered ones.
     * Uses queueExpandState — independent of Schedule tab.
     */
    private suspend fun buildQueueList(tasks: List<Task>, groupsEnabled: Boolean): List<TaskDisplayItem> {
        val shares  = EEVDFScheduler.computeShares(tasks, groupsEnabled)
        val exceeds = buildExceedMap(tasks)
        if (!groupsEnabled) {
            return tasks
                .filter { !it.isGroup }
                .sortedWith(compareBy({ extractNumber(it.name) }, { it.name }))
                .map { TaskDisplayItem(it, 0, cpuShare = shares[it.id] ?: 0.0, exceedMultiple = exceeds[it.id]) }
        }
        val result = mutableListOf<TaskDisplayItem>()
        fun addLevel(parentId: String?, depth: Int) {
            val children = tasks
                .filter { it.parentId == parentId }
                .sortedWith(compareBy({ extractNumber(it.name) }, { it.name }))
            for (task in children) {
                val dc = tasks.filter { it.parentId == task.id }
                result.add(TaskDisplayItem(task, depth, dc.size, dc.sumOf { it.totalRunTime }, shares[task.id] ?: 0.0, exceeds[task.id]))
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
    private suspend fun buildScheduleList(tasks: List<Task>, groupsEnabled: Boolean): List<TaskDisplayItem> {
        val shares  = EEVDFScheduler.computeShares(tasks, groupsEnabled)
        val exceeds = buildExceedMap(tasks)
        if (!groupsEnabled) {
            return tasks
                .filter { !it.isGroup }
                .sortedBy { it.virtualDeadline }
                .map { TaskDisplayItem(it, 0, cpuShare = shares[it.id] ?: 0.0, exceedMultiple = exceeds[it.id]) }
        }
        val result = mutableListOf<TaskDisplayItem>()
        fun addLevel(parentId: String?, depth: Int) {
            val children = tasks
                .filter { it.parentId == parentId }
                .sortedBy { it.virtualDeadline }
            for (task in children) {
                val dc = tasks.filter { it.parentId == task.id }
                result.add(TaskDisplayItem(task, depth, dc.size, dc.sumOf { it.totalRunTime }, shares[task.id] ?: 0.0, exceeds[task.id]))
                if (task.isGroup && (scheduleExpandState[task.id] ?: true)) addLevel(task.id, depth + 1)
            }
        }
        addLevel(null, 0)
        return result
    }

    // ── Timer state ───────────────────────────────────────────────────────────

    private var countDownTimer: CountDownTimer? = null
    private var overrunTimer:   CountDownTimer? = null
    private var delayTimer:     CountDownTimer? = null
    private var waitTimer:      CountDownTimer? = null   // Notice wait phase

    private val _delayRunning = MutableLiveData<Boolean>(false)
    val delayRunning: LiveData<Boolean> = _delayRunning
    private val _delaySecondsRemaining = MutableLiveData<Long>(0L)
    val delaySecondsRemaining: LiveData<Long> = _delaySecondsRemaining

    private val _waitRunning = MutableLiveData<Boolean>(false)
    val waitRunning: LiveData<Boolean> = _waitRunning
    private val _waitSecondsRemaining = MutableLiveData<Long>(0L)
    val waitSecondsRemaining: LiveData<Long> = _waitSecondsRemaining

    /** Elapsed seconds accumulated across delay + execute + wait phases this session. */
    private var delayElapsedSeconds:   Long = 0L
    private var waitElapsedSeconds:    Long = 0L
    /**
     * Running total of every second consumed by the notice state machine in the
     * current session (delay + all execute cycles + all wait cycles).
     * Applied to vruntime in a single shot on expire or pause — never per-cycle.
     */
    private var noticeSessionSeconds:  Long = 0L

    /** Tracks completed repeat cycles for the current Notice task session. */
    private var currentRepeatIteration = 0

    /** Epoch ms when the current countdown expires — in-memory mirror of Task.timerDeadlineEpoch.
     *  0 means no timer is running. Written to DB the instant Start is pressed. */
    private var timerDeadlineEpoch: Long = 0L

    /** Remaining seconds at the moment the current session's Start was pressed.
     *  Used to compute elapsed = sessionStartRemaining - secondsLeftNow on pause. */
    private var sessionStartRemaining: Long = 0L

    /** Wall-clock epoch ms when the current session's Start was pressed.
     *  Stored so logRun() can record the correct window-bucket for the run. */
    private var sessionStartEpoch: Long = 0L

    init {
        val db = TaskDatabase.getDatabase(application)
        repository = TaskRepository(db.taskDao(), db.taskRunLogDao())
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
                viewModelScope.launch { postValue(buildQueueList(tasks, enabled)) }
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
                viewModelScope.launch { postValue(buildScheduleList(tasks, enabled)) }
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
        val reverted = task.copy(
            isCompleted      = false,
            isRunning        = false,
            remainingSeconds = task.timeSliceSeconds
        )
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
        if (_timerRunning.value == true || _delayRunning.value == true || _waitRunning.value == true) return

        val task      = _currentTask.value ?: return
        val remaining = _timerSeconds.value ?: task.remainingSeconds
        if (remaining <= 0) return

        val delaySecs = if (task.taskType == "NOTIFICATION") task.notificationDelaySeconds else 0L

        if (delaySecs > 0) {
            currentRepeatIteration = 0   // fresh session — reset repeat counter
            noticeSessionSeconds   = 0L
            startDelayPhase(task, remaining, delaySecs)
        } else {
            currentRepeatIteration = 0
            noticeSessionSeconds   = 0L
            startActualTimer(task, remaining)
        }
    }

    // ── Delay phase (step 2) ──────────────────────────────────────────────────

    private fun startDelayPhase(task: Task, remaining: Long, delaySecs: Long) {
        delayElapsedSeconds          = 0L
        waitElapsedSeconds           = 0L
        _delayRunning.value          = true
        _delaySecondsRemaining.value = delaySecs
        val delayStart = System.currentTimeMillis()
        delayTimer?.cancel()
        delayTimer = object : CountDownTimer(delaySecs * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                _delaySecondsRemaining.postValue((millisUntilFinished / 1000L).coerceAtLeast(0L))
            }
            override fun onFinish() {
                delayElapsedSeconds = ((System.currentTimeMillis() - delayStart) / 1000L).coerceAtLeast(0L)
                noticeSessionSeconds += delayElapsedSeconds   // add delay to session total
                _delayRunning.postValue(false)
                _delaySecondsRemaining.postValue(0L)
                delayTimer = null
                // Step 3: delay just ended → always play execute sound then run execute time
                startExecutePhase(task, remaining)
            }
        }.start()
        AlarmForegroundService.delayStart(getApplication(), task.name, delaySecs)
    }

    /**
     * Step 3 entry point: play execute sound then start the execute countdown.
     * Called by:
     *  - delay onFinish         (first cycle, delay > 0)
     *  - wait  onFinish         (every repeat cycle)
     *  - onTimerFinished        (repeat when wait == 0)
     * NOT called on the very first run when delay == 0 (sound skipped per spec).
     */
    private fun startExecutePhase(task: Task, secs: Long) {
        SoundManager.playExecuteSound(getApplication(), prefs)
        startActualTimer(task, secs)
    }

    private fun cancelDelayPhase() {
        val elapsed = _delaySecondsRemaining.value?.let { (delaySecs() - it).coerceAtLeast(0L) } ?: 0L
        delayElapsedSeconds          = elapsed
        delayTimer?.cancel(); delayTimer = null
        _delayRunning.value          = false
        _delaySecondsRemaining.value = 0L
        currentRepeatIteration       = 0
        noticeSessionSeconds         = 0L
        if (elapsed > 0) applyVruntimeUpdate(elapsed)
        AlarmForegroundService.timerPause(getApplication())
    }

    /** Returns the configured delay seconds for the current task (0 if none). */
    private fun delaySecs(): Long =
        if (_currentTask.value?.taskType == "NOTIFICATION") _currentTask.value?.notificationDelaySeconds ?: 0L else 0L

    // ── Wait phase (step 4) ───────────────────────────────────────────────────

    private fun startWaitPhase(task: Task, waitSecs: Long) {
        // Issue 1 fix: cancel the AlarmManager that was set for execute-phase expiry.
        // Without this, TimerAlarmReceiver fires independently and triggers timerExpire
        // (ring + vibrate) while the wait phase is still in progress.
        AlarmForegroundService.cancelScheduledAlarm(getApplication())

        _waitRunning.value          = true
        _waitSecondsRemaining.value = waitSecs
        val waitStart = System.currentTimeMillis()
        SoundManager.playWaitSound(getApplication(), prefs)   // [wait sound] at step 4
        AlarmForegroundService.delayStart(getApplication(), task.name, waitSecs)
        waitTimer?.cancel()
        waitTimer = object : CountDownTimer(waitSecs * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                _waitSecondsRemaining.postValue((millisUntilFinished / 1000L).coerceAtLeast(0L))
            }
            override fun onFinish() {
                waitElapsedSeconds    = ((System.currentTimeMillis() - waitStart) / 1000L).coerceAtLeast(0L)
                noticeSessionSeconds += waitElapsedSeconds   // add wait to session total
                _waitRunning.postValue(false)
                _waitSecondsRemaining.postValue(0L)
                waitTimer = null
                // Step 5: if repeat → jump to step 3 (execute sound + execute time), else expire (step 6)
                val maxRepeat = task.notificationRepeatCount
                if (currentRepeatIteration < maxRepeat) {
                    currentRepeatIteration++
                    startExecutePhase(task, task.timeSliceSeconds)  // step 3: sound plays on every repeat
                } else {
                    triggerAlarmExpire(task)   // step 6
                }
            }
        }.start()
    }

    private fun cancelWaitPhase() {
        val secs = _waitSecondsRemaining.value ?: 0L
        val task = _currentTask.value
        val waitSecs = task?.notificationRestSeconds ?: 0L
        waitElapsedSeconds          = (waitSecs - secs).coerceAtLeast(0L)
        waitTimer?.cancel(); waitTimer = null
        _waitRunning.value          = false
        _waitSecondsRemaining.value = 0L
        currentRepeatIteration      = 0
        // Apply vruntime for everything consumed so far this session:
        // noticeSessionSeconds already holds delay + all completed execute cycles + completed waits.
        // waitElapsedSeconds holds the partial wait that just got cancelled.
        val totalConsumed = noticeSessionSeconds + waitElapsedSeconds
        noticeSessionSeconds = 0L
        if (totalConsumed > 0) applyVruntimeUpdate(totalConsumed)
        // Restore execute slice so pressing Start goes back to step 2 → step 3 correctly
        val sliceSecs = task?.timeSliceSeconds ?: 0L
        _timerSeconds.value = sliceSecs
        _currentTask.value?.let { t ->
            _currentTask.value = t.copy(remainingSeconds = sliceSecs)
        }
        AlarmForegroundService.timerPause(getApplication())
    }

    /** Fire the alarm banner / sound — end of the full Notice cycle. */
    private fun triggerAlarmExpire(task: Task) {
        val ctx = getApplication<Application>()
        // Apply the full session total to vruntime now — every second of delay + execute + wait
        if (noticeSessionSeconds > 0) applyVruntimeUpdate(noticeSessionSeconds)
        noticeSessionSeconds = 0L
        AlarmForegroundService.timerExpire(ctx, task.name, task.taskType)
        _alarmTaskName.postValue(task.name)
        _alarmElapsedSeconds.postValue(0L)
        startInAppOverrunCounter(task.name)
        _currentTask.postValue(null)
    }

    // ── Actual task timer ─────────────────────────────────────────────────────

    private fun startActualTimer(task: Task, remaining: Long) {
        // ── Wall-clock anchor ─────────────────────────────────────────────────
        val deadlineEpoch     = System.currentTimeMillis() + remaining * 1000L
        timerDeadlineEpoch    = deadlineEpoch
        sessionStartRemaining = remaining
        sessionStartEpoch     = System.currentTimeMillis()
        _timerRunning.value   = true

        viewModelScope.launch {
            repository.update(
                task.copy(remainingSeconds = remaining, isRunning = true,
                    timerDeadlineEpoch = deadlineEpoch)
            )
        }

        AlarmForegroundService.timerStart(getApplication(), task.name, remaining, task.taskType)
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
        // Delay phase → cancel, clear session
        if (_delayRunning.value == true) { cancelDelayPhase(); return }
        // Wait phase → cancel, restore execute slice for clean restart
        if (_waitRunning.value == true) { cancelWaitPhase(); return }

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

        // For NOTIFICATION tasks, noticeSessionSeconds already holds delay + all completed execute/wait
        // cycles from this session. Add the current execute's partial elapsed on top.
        // For non-NOTIFICATION tasks, use elapsed + any legacy delay/wait fields.
        if (task != null && task.taskType == "NOTIFICATION") {
            val totalConsumed = noticeSessionSeconds + elapsed
            noticeSessionSeconds = 0L
            if (totalConsumed > 0) applyVruntimeUpdate(totalConsumed)
        } else {
            if (elapsed > 0) applyVruntimeUpdate(elapsed + delayElapsedSeconds + waitElapsedSeconds)
        }
        delayElapsedSeconds = 0L
        waitElapsedSeconds  = 0L
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
            // For NOTIFICATION tasks, vruntime is accumulated in noticeSessionSeconds across
            // all phases and applied in a single shot at triggerAlarmExpire or pause.
            // updateVruntimeAfterRun here would double-count on every repeat cycle.
            if (task.taskType != "NOTIFICATION") {
                repository.updateVruntimeAfterRun(task, task.timeSliceSeconds)
            } else {
                noticeSessionSeconds += task.timeSliceSeconds   // add this execute cycle to session total
            }
            val updated = task.copy(remainingSeconds = task.timeSliceSeconds)
            repository.update(updated)
            _toastMessage.postValue("Time slice done for \"${task.name}\"")
            refreshSchedule()

            if (_autoMode.value == true) {
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
            } else if (task.taskType == "NOTIFICATION") {
                // Notice cycle: step 3 (execute done) → step 4 (wait) → step 5 (repeat/expire)
                val waitSecs  = task.notificationRestSeconds
                val maxRepeat = task.notificationRepeatCount
                when {
                    // Step 4: wait configured → go to wait phase (wait sound plays inside startWaitPhase)
                    waitSecs > 0 -> startWaitPhase(task, waitSecs)
                    // Step 5: no wait, but repeat remaining → jump to step 3 with execute sound
                    currentRepeatIteration < maxRepeat -> {
                        currentRepeatIteration++
                        startExecutePhase(task, task.timeSliceSeconds)  // step 3: sound plays on every repeat
                    }
                    // Step 6: no wait, no repeat remaining → expire
                    else -> triggerAlarmExpire(task)
                }
            } else {
                AlarmForegroundService.timerExpire(ctx, task.name, task.taskType)
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
        val capturedStart = sessionStartEpoch.also { sessionStartEpoch = 0L }
        viewModelScope.launch {
            repository.updateVruntimeAfterRun(task, ranSeconds)
            // Log the wall-clock run for the Exceed multiplier calculation.
            // capturedStart is 0 only if the timer was never started via startActualTimer
            // (e.g. app-kill recovery path) — fall back to now minus elapsed in that case.
            val logStart = if (capturedStart > 0L) capturedStart
                           else System.currentTimeMillis() - ranSeconds * 1000L
            repository.logRun(task.id, logStart, ranSeconds)
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