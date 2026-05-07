package com.eevdf.scheduler.viewmodel

import android.app.Application
import android.os.CountDownTimer
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.eevdf.scheduler.model.NoticePhase
import com.eevdf.scheduler.model.RunSession
import com.eevdf.scheduler.model.Task
import com.eevdf.scheduler.model.TimerState
import com.eevdf.scheduler.model.withTimerState
import com.eevdf.scheduler.ui.AlarmForegroundService
import com.eevdf.scheduler.ui.SoundManager
import kotlinx.coroutines.launch

/**
 * State machine for NOTIFICATION-type tasks.
 *
 * Phase transitions:
 *
 *   Idle  ──startTimer()──►  Delay ──onFinish──► Execute ──onFinish──► Wait ──(loop or expire)
 *           (delaySecs=0)                         │
 *                 └─────────────────────────────►─┘
 *
 *   Any phase ──cancelNotice()──► Idle
 *   Execute   ──pauseTimer()───► Idle (slice preserved in DB)
 *   Wait      ──(max repeats)──► Expired (alarm fires)
 *
 * Everything specific to NOTIFICATION tasks lives here.  Adding a new task type
 * with its own multi-phase flow only requires a parallel class — no timer core
 * or scheduler code needs to change.
 */
internal class TaskNoticeStateMachine(private val vm: TaskViewModel) {

    // ── Phase LiveData ─────────────────────────────────────────────────────────

    private val _noticePhase = MutableLiveData<NoticePhase>(NoticePhase.Idle)
    val noticePhase: LiveData<NoticePhase> = _noticePhase

    private val _delayRunning = MutableLiveData<Boolean>(false)
    val delayRunning: LiveData<Boolean> = _delayRunning

    private val _delaySecondsRemaining = MutableLiveData<Long>(0L)
    val delaySecondsRemaining: LiveData<Long> = _delaySecondsRemaining

    private val _waitRunning = MutableLiveData<Boolean>(false)
    val waitRunning: LiveData<Boolean> = _waitRunning

    private val _waitSecondsRemaining = MutableLiveData<Long>(0L)
    val waitSecondsRemaining: LiveData<Long> = _waitSecondsRemaining

    // ── Session-level accumulators ─────────────────────────────────────────────

    /** Running total of every second consumed across all phases this session. */
    private var noticeSessionSeconds: Long = 0L

    /**
     * Wall-clock epoch ms when the current notice cycle started (before delay).
     * Used as RunSession.NoticeSession.startEpochMs so the RunLog always has the
     * correct start time regardless of how many phase transitions occurred.
     */
    private var noticeSessionStartEpochMs: Long = 0L

    /** Tracks completed repeat cycles for the current Notice task session. */
    private var currentRepeatIteration: Int = 0

    // ── Phase timers ──────────────────────────────────────────────────────────

    private var delayTimer: CountDownTimer? = null
    private var waitTimer:  CountDownTimer? = null

    // ── Phase query helpers (used by pauseTimer in ViewModel) ─────────────────

    fun isDelayRunning(): Boolean = _delayRunning.value == true
    fun isWaitRunning():  Boolean = _waitRunning.value  == true

    // ── Public entry points ───────────────────────────────────────────────────

    /**
     * Initialises session-level state for a fresh start.
     * Called from [TaskViewModel.startTimer] before entering Delay or Execute.
     */
    fun initSession() {
        currentRepeatIteration    = 0
        noticeSessionSeconds      = 0L
        noticeSessionStartEpochMs = System.currentTimeMillis()
    }

    /**
     * Accumulates [seconds] from the engine's execute run into the session total.
     * Called from [TaskViewModel.onTimerFinished] for NOTIFICATION tasks.
     */
    fun accumulateSessionSeconds(seconds: Long) {
        noticeSessionSeconds += seconds
    }

    /**
     * Decides what happens when the execute timer expires inside a NOTIFICATION task.
     * Either starts the wait phase, continues repeating, or triggers the alarm.
     */
    fun handleExpiredNotificationTask(task: Task) {
        val waitSecs  = task.notificationRestSeconds
        val maxRepeat = task.notificationRepeatCount
        when {
            waitSecs > 0                           -> startWaitPhase(task, waitSecs)
            currentRepeatIteration < maxRepeat     -> {
                currentRepeatIteration++
                startExecutePhase(task, task.timeSliceSeconds, currentRepeatIteration)
            }
            else                                   -> triggerAlarmExpire(task)
        }
    }

    /**
     * Dispatches to the correct cancel handler based on the current phase.
     * Called from [TaskViewModel.cancelNotice].
     */
    fun cancelNotice() {
        when (_noticePhase.value) {
            is NoticePhase.Delay   -> cancelDelayPhase()
            is NoticePhase.Wait    -> cancelWaitPhase()
            is NoticePhase.Execute -> vm.pauseTimer()   // execute cancel = pause
            else                   -> Unit
        }
    }

    /**
     * Resets all notice state without posting any vruntime update.
     * Called from [TaskViewModel.resetTimer].
     */
    fun resetState() {
        currentRepeatIteration = 0
        noticeSessionSeconds   = 0L
        _noticePhase.value     = NoticePhase.Idle
    }

    /**
     * Applies accumulated notice session time to vruntime when the timer is paused
     * while in Execute phase.  Called from [TaskViewModel.pauseTimer].
     */
    fun handlePause(taskId: String, sessionWallClockSeconds: Long, nowMs: Long) {
        val totalConsumed    = noticeSessionSeconds + sessionWallClockSeconds
        noticeSessionSeconds = 0L
        _noticePhase.value   = NoticePhase.Idle
        if (totalConsumed > 0) {
            vm.applyVruntimeUpdate(RunSession.NoticeSession(
                taskId         = taskId,
                startEpochMs   = noticeSessionStartEpochMs,
                endEpochMs     = nowMs,
                totalPhaseSecs = totalConsumed
            ))
        }
    }

    /**
     * Cancels all in-flight CountDownTimers and resets phase to Idle.
     * Called from [TaskViewModel.onCleared].
     */
    fun cancelTimers() {
        delayTimer?.cancel()
        waitTimer?.cancel()
        _noticePhase.value = NoticePhase.Idle
    }

    // ── Phase starters ────────────────────────────────────────────────────────

    /** Step 1 of the notice cycle: countdown before execute phase begins. */
    fun startDelayPhase(task: Task, remaining: Long, delaySecs: Long) {
        var delayElapsedSeconds      = 0L
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
                delayElapsedSeconds   = ((System.currentTimeMillis() - delayStart) / 1000L)
                    .coerceAtLeast(0L)
                noticeSessionSeconds += delayElapsedSeconds
                _delayRunning.postValue(false)
                _delaySecondsRemaining.postValue(0L)
                delayTimer = null
                startExecutePhase(task, remaining, currentRepeatIteration)
            }
        }.start()
        AlarmForegroundService.delayStart(vm.app, task.name, delaySecs)
    }

    /** Step 2: the actual timed work window. Hands off to the engine in ViewModel. */
    fun startExecutePhase(task: Task, secs: Long, iteration: Int) {
        _noticePhase.value = NoticePhase.Execute(iteration)
        SoundManager.playExecuteSound(vm.app, vm.prefs)
        vm.startActualTimer(task, secs)
    }

    /** Step 3: rest period between execute cycles. */
    fun startWaitPhase(task: Task, waitSecs: Long) {
        AlarmForegroundService.cancelScheduledAlarm(vm.app)
        _waitRunning.value          = true
        _waitSecondsRemaining.value = waitSecs
        _noticePhase.value          = NoticePhase.Wait(waitSecs, currentRepeatIteration)
        val waitStart = System.currentTimeMillis()
        SoundManager.playWaitSound(vm.app, vm.prefs)
        AlarmForegroundService.delayStart(vm.app, task.name, waitSecs)
        waitTimer?.cancel()
        waitTimer = object : CountDownTimer(waitSecs * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val secs = (millisUntilFinished / 1000L).coerceAtLeast(0L)
                _waitSecondsRemaining.postValue(secs)
                _noticePhase.postValue(NoticePhase.Wait(secs, currentRepeatIteration))
            }
            override fun onFinish() {
                val waitElapsedSeconds = ((System.currentTimeMillis() - waitStart) / 1000L)
                    .coerceAtLeast(0L)
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

    // ── Phase cancellations ───────────────────────────────────────────────────

    fun cancelDelayPhase() {
        val elapsed = _delaySecondsRemaining.value
            ?.let { (delaySecs() - it).coerceAtLeast(0L) } ?: 0L
        delayTimer?.cancel(); delayTimer = null
        _delayRunning.value          = false
        _delaySecondsRemaining.value = 0L
        currentRepeatIteration       = 0
        noticeSessionSeconds         = 0L
        _noticePhase.value           = NoticePhase.Idle
        if (elapsed > 0) {
            val nowMs = System.currentTimeMillis()
            vm.applyVruntimeUpdate(RunSession.NoticeSession(
                taskId         = vm._currentTask.value?.id ?: "",
                startEpochMs   = noticeSessionStartEpochMs,
                endEpochMs     = nowMs,
                totalPhaseSecs = elapsed
            ))
        }
        AlarmForegroundService.timerPause(vm.app)
    }

    fun cancelWaitPhase() {
        val secs     = _waitSecondsRemaining.value ?: 0L
        val task     = vm._currentTask.value
        val waitSecs = task?.notificationRestSeconds ?: 0L
        val waitElapsedSeconds = (waitSecs - secs).coerceAtLeast(0L)
        waitTimer?.cancel(); waitTimer = null
        _waitRunning.value          = false
        _waitSecondsRemaining.value = 0L
        currentRepeatIteration      = 0
        _noticePhase.value          = NoticePhase.Idle
        val totalConsumed    = noticeSessionSeconds + waitElapsedSeconds
        noticeSessionSeconds = 0L
        if (totalConsumed > 0) {
            val nowMs = System.currentTimeMillis()
            vm.applyVruntimeUpdate(RunSession.NoticeSession(
                taskId         = task?.id ?: "",
                startEpochMs   = noticeSessionStartEpochMs,
                endEpochMs     = nowMs,
                totalPhaseSecs = totalConsumed
            ))
        }
        // Restore execute slice so Start restarts from the full slice
        val sliceSecs = task?.timeSliceSeconds ?: 0L
        vm._timerSeconds.value = sliceSecs
        vm._currentTask.value?.let { t ->
            vm._currentTask.value = t.copy(remainingSeconds = sliceSecs)
        }
        AlarmForegroundService.timerPause(vm.app)
    }

    // ── Alarm expiry ──────────────────────────────────────────────────────────

    /**
     * Called when the NOTIFICATION task has consumed all its repeat cycles.
     * Fires the foreground alarm, posts the expired banner, and persists the
     * reset timer state — all in one sequential coroutine to prevent the 0:00
     * stuck bug caused by two concurrent dao.update() calls overwriting each other.
     */
    fun triggerAlarmExpire(task: Task) {
        val ctx = vm.app as Application
        _noticePhase.value = NoticePhase.Expired

        val sessionSecs  = noticeSessionSeconds
        noticeSessionSeconds = 0L

        vm.viewModelScope.launch {
            if (sessionSecs > 0) {
                val nowMs = System.currentTimeMillis()
                vm.repository.updateVruntimeAfterRun(task, RunSession.NoticeSession(
                    taskId         = task.id,
                    startEpochMs   = noticeSessionStartEpochMs,
                    endEpochMs     = nowMs,
                    totalPhaseSecs = sessionSecs
                ))
            }
            vm.repository.update(task.withTimerState(TimerState.reset()))
            vm.refreshSchedule()
        }

        AlarmForegroundService.timerExpire(ctx, task.name, task.taskType)
        vm._alarmTaskName.postValue(task.name)
        vm._alarmElapsedSeconds.postValue(0L)
        vm.startInAppOverrunCounter(task.name)
        vm._currentTask.postValue(null)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun delaySecs(): Long =
        if (vm._currentTask.value?.taskType == "NOTIFICATION")
            vm._currentTask.value?.notificationDelaySeconds ?: 0L else 0L
}
