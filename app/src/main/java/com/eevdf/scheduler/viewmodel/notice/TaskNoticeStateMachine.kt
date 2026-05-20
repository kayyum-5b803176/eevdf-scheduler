package com.eevdf.scheduler.viewmodel.notice

import android.app.Application
import android.os.CountDownTimer
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.eevdf.scheduler.model.notice.NoticePhase
import com.eevdf.scheduler.model.runlog.RunSession
import com.eevdf.scheduler.model.task.Task
import com.eevdf.scheduler.model.timer.TimerState
import com.eevdf.scheduler.model.timer.withTimerState
import com.eevdf.scheduler.ui.alarm.AlarmForegroundService
import com.eevdf.scheduler.ui.settings.SoundManager
import kotlinx.coroutines.launch
import com.eevdf.scheduler.viewmodel.task.TaskViewModel

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
                // Bug fix #2: the task captured here has timerState = Expired(sliceMs).
                // TimerStartEvent.from(Expired) computes accumulated = sliceMs, so the
                // engine starts with 0 remaining and onFinish fires in ~0 ms.
                // Reset to Idle so the next execute gets the full slice duration.
                startExecutePhase(task.withTimerState(TimerState.reset()), task.timeSliceSeconds, currentRepeatIteration)
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
                // Bug fix #1: cancelDelayPhase() sets _noticePhase to Idle
                // synchronously on the main thread.  On some OEM ROMs the final
                // CountDownTimer message can still land after cancel() returns, so
                // guard here — if the phase is no longer Delay the cancel already
                // ran and we must not advance to Execute (which would schedule a
                // background alarm the user already dismissed).
                if (_noticePhase.value !is NoticePhase.Delay) return
                val delayElapsedSeconds = ((System.currentTimeMillis() - delayStart) / 1000L)
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
        // Compute total alarm time covering all remaining (execute + wait) cycles so
        // the AlarmManager entry is set ONCE and never cancelled between phases.
        // On pause the alarm is cancelled normally; on resume startExecutePhase is
        // called again with the actual remaining execute seconds, so the recalc is
        // always precise.
        val alarmSecs = calcTotalAlarmSecs(secs, task, iteration)
        vm.startActualTimer(task, secs, alarmSecs)
    }

    /** Step 3: rest period between execute cycles. */
    fun startWaitPhase(task: Task, waitSecs: Long) {
        // DO NOT cancel the alarm here.  startExecutePhase already scheduled it for
        // the full remaining cycle time (executeRemaining + waitSecs + future cycles).
        // Cancelling here was the original bug: it killed the backup wakeup alarm between
        // execute phases, leaving no AlarmManager entry if the process was killed mid-wait.
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
                // Bug fix #1 (mirror of delay guard): cancelWaitPhase() sets
                // _noticePhase to Idle synchronously; if it fired before this
                // callback the user already cancelled — do not advance to Execute.
                if (_noticePhase.value !is NoticePhase.Wait) return
                val waitElapsedSeconds = ((System.currentTimeMillis() - waitStart) / 1000L)
                    .coerceAtLeast(0L)
                noticeSessionSeconds += waitElapsedSeconds
                _waitRunning.postValue(false)
                _waitSecondsRemaining.postValue(0L)
                waitTimer = null
                val maxRepeat = task.notificationRepeatCount
                if (currentRepeatIteration < maxRepeat) {
                    currentRepeatIteration++
                    // Bug fix #2 (wait path): same Expired-timerState issue —
                    // reset to Idle so the repeat execute gets the full slice.
                    startExecutePhase(task.withTimerState(TimerState.reset()), task.timeSliceSeconds, currentRepeatIteration)
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
        val ctx = vm.app
        _noticePhase.value = NoticePhase.Expired

        // Cancel the AlarmManager backup alarm BEFORE starting the in-app expire path.
        //
        // At end-of-last-cycle, the AlarmManager entry and the wait-phase CountDownTimer
        // fire at the same wall-clock epoch.  Cancelling here (synchronously, on the main
        // thread, before any coroutine suspend) ensures AlarmState==Idle when the broadcast
        // arrives, so TimerAlarmReceiver.onAlarmFired() returns false and the alarm does
        // not double-ring via the background path.
        //
        // When the app IS dead the CountDownTimer never runs — the AlarmManager fires alone,
        // onAlarmFired() finds AlarmState==Scheduled, transitions to Ringing, and the
        // service rings normally.  No conflict in that path.
        AlarmForegroundService.cancelScheduledAlarm(ctx)

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

    /**
     * Computes the total AlarmManager trigger time (in seconds from now) for a
     * NOTIFICATION task that is about to start execute iteration [currentIteration]
     * with [executeRemaining] seconds left in that execute run.
     *
     * Formula (delay is excluded — the alarm only covers the active work window):
     *
     *   executeRemaining + waitSecs + (executeSecs + waitSecs) × (maxRepeat − currentIteration)
     *
     * Breakdown:
     *   • executeRemaining              — time left in the current execute slice
     *   • + waitSecs                    — the wait that follows this execute
     *   • + (executeSecs + waitSecs)    — each subsequent full (execute + wait) pair
     *     × (maxRepeat − currentIteration) — number of full pairs still to run
     *
     * Example — execute: 10 s, wait: 5 s, notificationRepeatCount: 1, iteration: 0
     *   = 10 + 5 + (10 + 5) × (1 − 0) = 30 s
     *   Alarm fires 30 s from now, after: execute(10) → wait(5) → execute(10) → wait(5).
     *
     * On pause: the caller cancels the alarm via [AlarmForegroundService.timerPause].
     * On resume: [startExecutePhase] is called with the actual remaining execute seconds
     * so the formula always produces a precise, up-to-date trigger time.
     *
     * When [task.notificationRestSeconds] == 0 there is no wait phase; the method
     * returns [executeRemaining] unchanged so non-wait NOTIFICATION tasks behave
     * identically to DEFAULT tasks.
     */
    private fun calcTotalAlarmSecs(
        executeRemaining: Long,
        task: Task,
        currentIteration: Int
    ): Long {
        val waitSecs    = task.notificationRestSeconds
        if (waitSecs <= 0L) return executeRemaining   // no wait phase — single execute alarm
        val executeSecs = task.timeSliceSeconds
        val maxRepeat   = task.notificationRepeatCount
        val remainingFullCycles = (maxRepeat - currentIteration).coerceAtLeast(0)
        return executeRemaining + waitSecs + (executeSecs + waitSecs) * remainingFullCycles
    }
}
