package com.eevdf.scheduler.viewmodel

import android.app.Application
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.CountDownTimer
import androidx.lifecycle.*
import com.eevdf.scheduler.db.TaskDatabase
import com.eevdf.scheduler.db.TaskRepository
import com.eevdf.scheduler.model.Task
import com.eevdf.scheduler.scheduler.EEVDFScheduler
import com.eevdf.scheduler.scheduler.SchedulerStats
import com.eevdf.scheduler.ui.NotificationHelper
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

    // ── Alarm state (null = no alarm ringing) ──────────────────────────────────
    /** Name of the task whose timer just expired, or null if no alarm is active. */
    private val _alarmTaskName = MutableLiveData<String?>(null)
    val alarmTaskName: LiveData<String?> = _alarmTaskName

    /** Seconds elapsed since the timer expired (counts up: 0, 1, 2, …). */
    private val _alarmElapsedSeconds = MutableLiveData<Long>(0L)
    val alarmElapsedSeconds: LiveData<Long> = _alarmElapsedSeconds

    private var countDownTimer: CountDownTimer? = null
    private var overrunTimer: CountDownTimer? = null  // ticks up after expiry
    private var sessionStartSeconds: Long = 0L
    private var sessionElapsed: Long = 0L
    private var alarmRingtone: Ringtone? = null
    private var expiredTaskName: String? = null        // held for notification updates

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
        stopAlarmSound()
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
        stopAlarmSound()
        pauseTimer()
        val task = _currentTask.value ?: return
        _toastMessage.value = "Skipped \"${task.name}\""
        _currentTask.value = null
        scheduleNext()
    }

    private fun onTimerFinished() {
        val task = _currentTask.value ?: return
        expiredTaskName = task.name
        playAlarmSound()
        startOverrunCounter(task.name)

        viewModelScope.launch {
            repository.updateVruntimeAfterRun(task, task.timeSliceSeconds)
            val updated = task.copy(
                remainingSeconds = task.timeSliceSeconds,
                runCount = task.runCount + 1,
                totalRunTime = task.totalRunTime + task.timeSliceSeconds
            )
            repository.update(updated)
            _toastMessage.postValue("✓ Time slice done for \"${task.name}\"")
            _currentTask.postValue(null)
            refreshSchedule()
        }
    }

    /**
     * Starts counting up elapsed seconds after the timer expires.
     * Updates _alarmElapsedSeconds + refreshes the notification every second
     * (like Google Clock which shows "Timer expired · 0:23").
     * Runs for up to 1 hour then auto-stops.
     */
    private fun startOverrunCounter(taskName: String) {
        overrunTimer?.cancel()
        _alarmTaskName.postValue(taskName)
        _alarmElapsedSeconds.postValue(0L)

        overrunTimer = object : CountDownTimer(3600_000L, 1000L) {
            var elapsed = 0L
            override fun onTick(millisUntilFinished: Long) {
                elapsed++
                _alarmElapsedSeconds.postValue(elapsed)
                // Update notification text so the "X:XX ago" ticks up
                val ctx = getApplication<Application>()
                NotificationHelper.showTimerExpired(ctx, taskName, elapsed)
            }
            override fun onFinish() {
                stopAlarmSound()
                clearAlarmState()
            }
        }.start()

        // Show first notification immediately (elapsed = 0)
        val ctx = getApplication<Application>()
        NotificationHelper.showTimerExpired(ctx, taskName, 0L)
    }

    private fun stopOverrunCounter() {
        overrunTimer?.cancel()
        overrunTimer = null
    }

    private fun clearAlarmState() {
        _alarmTaskName.postValue(null)
        _alarmElapsedSeconds.postValue(0L)
        expiredTaskName = null
        val ctx = getApplication<Application>()
        NotificationHelper.cancelExpired(ctx)
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

    // ─── ALARM SOUND ───────────────────────────────────────────────────────────

    private fun playAlarmSound() {
        stopAlarmSound()
        val context = getApplication<Application>()
        val alarmUri = RingtoneManager.getActualDefaultRingtoneUri(
            context, RingtoneManager.TYPE_ALARM
        ) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val ringtone = RingtoneManager.getRingtone(context, alarmUri) ?: return
        ringtone.audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        alarmRingtone = ringtone
        ringtone.play()
    }

    /** Stop alarm sound + overrun counter + dismiss notification + clear UI state. */
    fun stopAlarmSound() {
        stopOverrunCounter()
        alarmRingtone?.let { if (it.isPlaying) it.stop() }
        alarmRingtone = null
        clearAlarmState()
    }

    override fun onCleared() {
        super.onCleared()
        stopAlarmSound()
        countDownTimer?.cancel()
    }
}
