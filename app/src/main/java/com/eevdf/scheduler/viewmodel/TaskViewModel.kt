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
    private val KEY_GROUPS = "groups_enabled"

    private val repository: TaskRepository
    val allTasks: LiveData<List<Task>>
    val activeTasks: LiveData<List<Task>>
    val completedTasks: LiveData<List<Task>>

    /** Groups available for parent selection in AddTaskActivity. */
    val activeGroups: LiveData<List<Task>>

    private val _currentTask = MutableLiveData<Task?>(null)
    val currentTask: LiveData<Task?> = _currentTask

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

    // Initialized after init{} so activeTasks/_scheduleOrder are already assigned
    lateinit var flatActiveTasks:   MediatorLiveData<List<TaskDisplayItem>>
    lateinit var flatScheduleOrder: MediatorLiveData<List<TaskDisplayItem>>

    /**
     * Flattens the task tree into a display list.
     *
     * Groups mode OFF → all non-group tasks sorted by virtualDeadline, depth=0.
     * Groups mode ON  → depth-first traversal respecting isGroupExpanded;
     *                   groups appear as header rows with child count/time.
     */
    private fun buildFlatList(tasks: List<Task>, groupsEnabled: Boolean): List<TaskDisplayItem> {
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
                val directChildren = tasks.filter { it.parentId == task.id }
                val childCount     = directChildren.size
                val childRuntime   = directChildren.sumOf { it.totalRunTime }
                result.add(TaskDisplayItem(task, depth, childCount, childRuntime))
                if (task.isGroup && task.isGroupExpanded) {
                    addLevel(task.id, depth + 1)
                }
            }
        }

        addLevel(null, 0)
        return result
    }

    // ── Timer state ───────────────────────────────────────────────────────────

    private var countDownTimer: CountDownTimer? = null
    private var overrunTimer: CountDownTimer? = null
    private var sessionStartSeconds: Long = 0L
    private var sessionElapsed: Long = 0L

    init {
        val db = TaskDatabase.getDatabase(application)
        repository = TaskRepository(db.taskDao())
        allTasks       = repository.allTasks
        activeTasks    = repository.activeTasks
        completedTasks = repository.completedTasks
        activeGroups   = repository.activeGroups

        flatActiveTasks = MediatorLiveData<List<TaskDisplayItem>>().apply {
            fun rebuild() {
                val tasks   = activeTasks.value ?: emptyList()
                val enabled = _groupsEnabled.value ?: false
                value = buildFlatList(tasks, enabled)
            }
            addSource(activeTasks)    { rebuild() }
            addSource(_groupsEnabled) { rebuild() }
        }

        flatScheduleOrder = MediatorLiveData<List<TaskDisplayItem>>().apply {
            fun rebuild() {
                val tasks   = _scheduleOrder.value ?: emptyList()
                val enabled = _groupsEnabled.value ?: false
                value = buildFlatList(tasks, enabled)
            }
            addSource(_scheduleOrder) { rebuild() }
            addSource(_groupsEnabled) { rebuild() }
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
        val task = _currentTask.value ?: return
        val remaining = _timerSeconds.value ?: task.remainingSeconds
        if (remaining <= 0) return

        sessionStartSeconds = remaining
        sessionElapsed = 0L
        _timerRunning.value = true

        val ctx = getApplication<Application>()
        AlarmForegroundService.timerStart(ctx, task.name, remaining)

        countDownTimer?.cancel()
        countDownTimer = object : CountDownTimer(remaining * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsLeft = millisUntilFinished / 1000L
                sessionElapsed = sessionStartSeconds - secondsLeft
                _timerSeconds.postValue(secondsLeft)

                // Update _currentTask in memory every tick so the card's
                // progress bar and remaining display stay in sync without
                // hitting the DB every second
                _currentTask.value?.let { t ->
                    _currentTask.postValue(t.copy(remainingSeconds = secondsLeft))
                }

                // No per-second service Intent needed — the notification countdown is
                // driven by the system clock via setUsesChronometer/setChronometerCountDown.
                if (sessionElapsed % 10 == 0L) persistTimerState(secondsLeft)
            }
            override fun onFinish() {
                _timerSeconds.postValue(0L)
                _timerRunning.postValue(false)
                onTimerFinished()
            }
        }.start()
    }

    fun pauseTimer() {
        stopAlarmSound()
        countDownTimer?.cancel()
        countDownTimer = null
        _timerRunning.value = false
        val secondsLeft = _timerSeconds.value ?: return

        // Snap _currentTask in memory to the exact paused time so the card
        // immediately shows the correct remaining value (not the stale DB value)
        _currentTask.value?.let { t ->
            _currentTask.value = t.copy(remainingSeconds = secondsLeft, isRunning = false)
        }

        persistTimerState(secondsLeft)
        if (sessionElapsed > 0) {
            applyVruntimeUpdate(sessionElapsed)
            sessionElapsed = 0L
        }
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

    private fun onTimerFinished() {
        val task = _currentTask.value ?: return
        val ctx = getApplication<Application>()

        AlarmForegroundService.timerExpire(ctx, task.name)
        _alarmTaskName.postValue(task.name)
        _alarmElapsedSeconds.postValue(0L)
        startInAppOverrunCounter(task.name)

        viewModelScope.launch {
            // updateVruntimeAfterRun handles leaf + all ancestor groups
            repository.updateVruntimeAfterRun(task, task.timeSliceSeconds)
            val updated = task.copy(remainingSeconds = task.timeSliceSeconds)
            repository.update(updated)
            _toastMessage.postValue("Time slice done for \"${task.name}\"")
            _currentTask.postValue(null)
            refreshSchedule()
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

    private fun persistTimerState(secondsLeft: Long) {
        val task = _currentTask.value ?: return
        viewModelScope.launch {
            val updated = task.copy(remainingSeconds = secondsLeft, isRunning = true)
            repository.update(updated)
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

    override fun onCleared() {
        super.onCleared()
        stopAlarmSound()
        countDownTimer?.cancel()
    }
}
