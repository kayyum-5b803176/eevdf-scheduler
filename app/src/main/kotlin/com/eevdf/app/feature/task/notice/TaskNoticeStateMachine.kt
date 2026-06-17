package com.eevdf.app.feature.task.notice

import android.app.Application
import android.os.CountDownTimer
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.eevdf.app.feature.task.notice.NoticePhase
import com.eevdf.data.runlog.RunSession
import com.eevdf.data.task.Task
import com.eevdf.app.feature.task.timer.TimerState
import com.eevdf.app.feature.task.timer.timerState
import com.eevdf.app.feature.task.timer.withTimerState
import com.eevdf.app.feature.alarm.AlarmForegroundService
import com.eevdf.app.feature.settings.SoundManager
import kotlinx.coroutines.launch
import com.eevdf.app.feature.task.TaskViewModel

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

    /**
     * The iteration index of the last execute phase that was started.
     * Persists across pause/resume so that [initSession] can restore the
     * correct phase after the user pauses on execute-N and resumes.
     */
    private var lastExecuteIteration: Int = 0

    // ── Pending-wait state (timestamp-based resume / skip) ────────────────────
    //
    // When the user cancels during a wait phase the wait is NOT discarded.
    // Instead, wall-clock time continues to count against the remaining wait
    // duration.  On the next Start:
    //
    //   adjustedRemaining = waitRemainingAtCancelMs − (nowMs − waitCancelledEpochMs)
    //
    //   • adjustedRemaining > 0  →  resume the wait at that reduced duration
    //   • adjustedRemaining ≤ 0  →  the wait would already be over; skip directly
    //                               to the next execute phase (or wrap to execute-0
    //                               if there is no next phase).
    //
    // The delay phase (if any) naturally adds to the elapsed time because
    // waitCancelledEpochMs is set at cancel time and the check happens AFTER
    // the delay completes, so delay seconds are automatically subtracted.
    //
    // Example — wait 90 s, cancel with 90 s remaining:
    //   case 1 (resume): 10 s idle + 5 s delay → elapsed = 15 s → resume at 75 s
    //   case 2 (skip):  100 s idle + 5 s delay → elapsed = 105 s → skip to execute-2

    /** ≥ 0 while a wait-phase cancel is pending; −1 means no pending wait. */
    private var pendingWaitIteration: Int = -1
    /** System.currentTimeMillis() at the moment the wait was cancelled. */
    private var waitCancelledEpochMs: Long = 0L
    /** Remaining wait time in ms at the moment of cancel. */
    private var waitRemainingAtCancelMs: Long = 0L

    // ── Phase timers ──────────────────────────────────────────────────────────

    private var delayTimer: CountDownTimer? = null
    private var waitTimer:  CountDownTimer? = null

    // ── Phase query helpers (used by pauseTimer in ViewModel) ─────────────────

    fun isDelayRunning(): Boolean = _delayRunning.value == true
    fun isWaitRunning():  Boolean = _waitRunning.value  == true

    // ── Public entry points ───────────────────────────────────────────────────

    /**
     * Initialises session-level state.
     * Called from [TaskViewModel.startTimer] on every Start tap.
     *
     * For a FRESH start ([task] timer state is Idle):
     *   • Reset iteration counters to 0.
     *
     * For a RESUME (timer state is Paused from a mid-execute pause):
     *   • Restore [currentRepeatIteration] from [lastExecuteIteration] so the
     *     engine resumes at the correct execute cycle instead of always restarting
     *     from execute-0.  (Bug fix #2)
     *
     * For a post-wait-cancel start ([lastExecuteIteration] was already advanced
     * by [cancelWaitPhase] to the next cycle):
     *   • Same restore path — [lastExecuteIteration] already holds the right target.
     */
    fun initSession(task: Task) {
        when {
            pendingWaitIteration >= 0 -> {
                // Pending-wait resume: keep pendingWait* fields intact so
                // resolveAfterDelay() can use them.  currentRepeatIteration will be
                // set there once the timestamp check decides resume vs skip.
            }
            task.timerState is TimerState.Idle -> {
                // Fresh start: reset all iteration counters.
                currentRepeatIteration = 0
                lastExecuteIteration   = 0
            }
            else -> {
                // Execute resume: restore the iteration we were on when paused.
                currentRepeatIteration = lastExecuteIteration
            }
        }
        noticeSessionSeconds      = 0L
        noticeSessionStartEpochMs = System.currentTimeMillis()
    }

    /** True when the user cancelled mid-wait and Start should resolve the pending wait. */
    fun hasPendingWait(): Boolean = pendingWaitIteration >= 0

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
        currentRepeatIteration  = 0
        lastExecuteIteration    = 0
        noticeSessionSeconds    = 0L
        _noticePhase.value      = NoticePhase.Idle
        clearPendingWait()
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
                resolveAfterDelay(task, remaining)
            }
        }.start()
        AlarmForegroundService.delayStart(vm.app, task.name, delaySecs)
    }

    /** Step 2: the actual timed work window. Hands off to the engine in ViewModel. */
    fun startExecutePhase(task: Task, secs: Long, iteration: Int) {
        // Record the iteration so that initSession(task) can restore it on resume
        // and cancelWaitPhase() can advance past this cycle. (Bug fix #2)
        lastExecuteIteration = iteration
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
        lastExecuteIteration         = 0
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
        val remainingMs = (_waitSecondsRemaining.value ?: 0L) * 1000L
        val task        = vm._currentTask.value
        val waitSecs    = task?.notificationRestSeconds ?: 0L
        val waitElapsedSeconds = ((waitSecs * 1000L - remainingMs) / 1000L).coerceAtLeast(0L)

        waitTimer?.cancel(); waitTimer = null
        _waitRunning.value          = false
        _waitSecondsRemaining.value = 0L
        _noticePhase.value          = NoticePhase.Idle

        // ── Record pending-wait state (timestamp-based resume) ────────────────
        // Do NOT advance the iteration here.  resolveAfterDelay() will decide
        // whether to resume the wait or skip to the next execute based on how
        // much real time has elapsed since this cancel moment.
        pendingWaitIteration    = lastExecuteIteration   // iteration of the execute that owns this wait
        waitCancelledEpochMs    = System.currentTimeMillis()
        waitRemainingAtCancelMs = remainingMs

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
        // Show the full execute slice on the timer card while idle so the user
        // can see what duration will run on the next Start tap.
        val sliceSecs = task?.timeSliceSeconds ?: 0L
        vm._timerSeconds.value = sliceSecs
        vm._currentTask.value?.let { t ->
            // Reset timerState to Idle so the engine does not treat next Start as
            // an execute resume (it's a wait-resume, handled by resolveAfterDelay).
            val reset = t.copy(remainingSeconds = sliceSecs).withTimerState(TimerState.reset())
            vm._currentTask.value = reset
            vm.viewModelScope.launch { vm.repository.update(reset) }
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

    // ── Phase routing ─────────────────────────────────────────────────────────

    /**
     * Decision point called after the delay phase (or immediately if delaySecs == 0).
     *
     * If a wait-phase cancel is pending ([pendingWaitIteration] ≥ 0) the timestamp
     * check decides whether to resume the wait or skip to the next execute:
     *
     *   adjustedRemaining = waitRemainingAtCancelMs − (nowMs − waitCancelledEpochMs)
     *
     *   > 0  →  resume wait at [adjustedRemaining] ms  (case 1 — resume type)
     *   ≤ 0  →  the wait already expired in the background; advance to the next
     *            execute, or wrap to execute-0 if there is no next cycle (case 2 — skip type)
     *
     * When there is no pending wait the method falls straight through to
     * [startExecutePhase] with [remainingExecuteSecs] (normal/resume path).
     *
     * @param remainingExecuteSecs  Full-slice or partial-slice seconds to use when
     *                              starting a normal execute.  Ignored for wait-resume
     *                              and skip paths, which derive their own duration.
     */
    fun resolveAfterDelay(task: Task, remainingExecuteSecs: Long) {
        if (pendingWaitIteration >= 0) {
            val savedIter      = pendingWaitIteration
            val nowMs          = System.currentTimeMillis()
            val adjustedMs     = waitRemainingAtCancelMs - (nowMs - waitCancelledEpochMs)
            clearPendingWait()

            if (adjustedMs > 0) {
                // Case 1 — resume: wait still has time left
                currentRepeatIteration = savedIter
                lastExecuteIteration   = savedIter
                startWaitPhase(task, adjustedMs / 1000L)
            } else {
                // Case 2 — skip: wait expired while user was away
                val maxRepeat = task.notificationRepeatCount
                val nextIter  = if (savedIter < maxRepeat) savedIter + 1 else 0
                currentRepeatIteration = nextIter
                lastExecuteIteration   = nextIter
                startExecutePhase(task.withTimerState(TimerState.reset()), task.timeSliceSeconds, nextIter)
            }
            return
        }
        // Normal path: no pending wait — start (or resume) execute
        startExecutePhase(task, remainingExecuteSecs, currentRepeatIteration)
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
    private fun clearPendingWait() {
        pendingWaitIteration    = -1
        waitCancelledEpochMs    = 0L
        waitRemainingAtCancelMs = 0L
    }

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
