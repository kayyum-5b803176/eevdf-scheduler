package com.eevdf.scheduler.viewmodel

import android.app.Application
import android.os.CountDownTimer
import androidx.lifecycle.*
import com.eevdf.scheduler.db.TaskDatabase
import com.eevdf.scheduler.db.TaskRepository
import com.eevdf.scheduler.model.Task
import com.eevdf.scheduler.scheduler.EEVDFScheduler
import com.eevdf.scheduler.scheduler.SchedulerStats
import kotlinx.coroutines.launch

class TaskViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: TaskRepository
    val allTasks: LiveData<List<Task>>
    val activeTasks: LiveData<List<Task>>
    val completedTasks: LiveData<List<Task>>

    private val _currentTask = MutableLiveData<Task?>()
    val currentTask: LiveData<Task?> = _currentTask

    private val _timerSeconds = MutableLiveData<Long>()
    val timerSeconds: LiveData<Long> = _timerSeconds

    private val _timerRunning = MutableLiveData<Boolean>(false)
    val timerRunning: LiveData<Boolean> = _timerRunning

    private val _scheduleOrder = MutableLiveData<List<Task>>(emptyList())
    val scheduleOrder: LiveData<List<Task>> = _scheduleOrder

    private val _stats = MutableLiveData<SchedulerStats>()
    val stats: LiveData<SchedulerStats> = _stats

    private val _toastMessage = MutableLiveData<String?>()
    val toastMessage: LiveData<String?> = _toastMessage

    private var countDownTimer: CountDownTimer? = null
    private var sessionStartSeconds: Long = 0L
    private var sessionElapsed: Long = 0L

    init {
        val db = TaskDatabase.getDatabase(application)
        repository = TaskRepository(db.taskDao())
        allTasks = repository.allTasks
        activeTasks = repository.activeTasks
        completedTasks = repository.completedTasks
    }

    // ─── CRUD ──────────────────────────────────────────────────────────────────

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
        if (task.id == _currentTask.value?.id) {
            stopTimer(completed = true)
        } else {
            repository.markCompleted(task)
        }
        refreshSchedule()
    }

    fun clearCompleted() = viewModelScope.launch {
        repository.clearCompleted()
    }

    fun clearToast() {
        _toastMessage.value = null
    }

    // ─── SCHEDULER ─────────────────────────────────────────────────────────────

    fun scheduleNext() = viewModelScope.launch {
        pauseTimer()
        val next = repository.selectNextTask()
        if (next != null) {
            _currentTask.postValue(next)
            _timerSeconds.postValue(next.remainingSeconds)
            _toastMessage.postValue("▶ Now: \"${next.name}\" (Priority ${next.priority})")
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

    // ─── TIMER ─────────────────────────────────────────────────────────────────

    fun startTimer() {
        val task = _currentTask.value ?: return
        val remaining = _timerSeconds.value ?: task.remainingSeconds
        if (remaining <= 0) return

        sessionStartSeconds = remaining
        sessionElapsed = 0L
        _timerRunning.value = true

        countDownTimer?.cancel()
        countDownTimer = object : CountDownTimer(remaining * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsLeft = millisUntilFinished / 1000L
                sessionElapsed = sessionStartSeconds - secondsLeft
                _timerSeconds.postValue(secondsLeft)

                // Persist remaining every 10 seconds
                if (sessionElapsed % 10 == 0L) {
                    persistTimerState(secondsLeft)
                }
            }

            override fun onFinish() {
                _timerSeconds.postValue(0L)
                _timerRunning.postValue(false)
                onTimerFinished()
            }
        }.start()
    }

    fun pauseTimer() {
        countDownTimer?.cancel()
        countDownTimer = null
        _timerRunning.value = false
        val secondsLeft = _timerSeconds.value ?: return
        persistTimerState(secondsLeft)
        if (sessionElapsed > 0) {
            applyVruntimeUpdate(sessionElapsed)
            sessionElapsed = 0L
        }
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

    fun skipTask() {
        pauseTimer()
        val task = _currentTask.value ?: return
        _toastMessage.value = "Skipped \"${task.name}\""
        _currentTask.value = null
        scheduleNext()
    }

    private fun onTimerFinished() {
        val task = _currentTask.value ?: return
        viewModelScope.launch {
            repository.updateVruntimeAfterRun(task, task.timeSliceSeconds)
            val updated = task.copy(
                remainingSeconds = task.timeSliceSeconds, // Reset for next round
                runCount = task.runCount + 1,
                totalRunTime = task.totalRunTime + task.timeSliceSeconds
            )
            repository.update(updated)
            _toastMessage.postValue("✓ Time slice done for \"${task.name}\"")
            _currentTask.postValue(null)
            refreshSchedule()
        }
    }

    private fun stopTimer(completed: Boolean) {
        countDownTimer?.cancel()
        countDownTimer = null
        _timerRunning.value = false
        if (completed) {
            val task = _currentTask.value ?: return
            viewModelScope.launch {
                repository.markCompleted(task)
                _currentTask.postValue(null)
                _toastMessage.postValue("✓ \"${task.name}\" completed!")
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
        countDownTimer?.cancel()
    }
}
