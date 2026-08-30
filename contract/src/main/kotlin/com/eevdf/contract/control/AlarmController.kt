package com.eevdf.contract.control

/**
 * Contract for driving the alarm subsystem.
 *
 * WHY THIS EXISTS
 * ---------------
 * `task` and `autoswitch` both need to start, pause and stop the alarm. They
 * used to do it by calling static methods on `AlarmForegroundService` directly,
 * which meant three feature packages were welded together at compile time:
 *
 *     task       -> alarm      (TaskViewModel, TaskNoticeStateMachine)
 *     autoswitch -> alarm      (CallSwitchService, BubbleOverlayService)
 *
 * Those edges are the last thing standing between the feature packages and
 * being real Gradle modules — a module cannot depend on a sibling it is not
 * allowed to depend on.
 *
 * Now callers depend on this interface. The implementation lives inside the
 * alarm feature and is bound by [com.eevdf.app.feature.alarm.AlarmControlModule],
 * so the dependency arrow points *inward* to a contract rather than sideways to
 * another feature. When `:feature:alarm` becomes a module, it provides the
 * binding and nothing else changes.
 *
 * WHY THERE IS NO Context PARAMETER
 * ---------------------------------
 * The old static API took a `Context` at every call site, which meant every
 * caller had to have one and had to remember which flavour was safe. The
 * implementation is a `@Singleton` holding the application context, so callers
 * just express intent.
 */
interface AlarmController {

    /**
     * Timer started or resumed: schedules the alarm and shows the countdown
     * notification.
     *
     * @param remainingSecs what the notification counts down.
     * @param alarmSecs when the alarm actually fires. Differs from
     *   [remainingSecs] only for NOTICE-type tasks, where the alarm is set once
     *   for the whole cycle instead of being re-armed on every phase change.
     */
    fun timerStart(
        taskName: String,
        remainingSecs: Long,
        taskType: String = "DEFAULT",
        alarmSecs: Long = remainingSecs,
    )

    /** Timer paused: cancels the pending alarm and clears the notification. */
    fun timerPause()

    /**
     * Drives the service into its Ringing state (sound + wake lock).
     *
     * Do NOT call this to *make* a timer expire — it bypasses the ghost-alarm
     * guard. It is for the receiver path after the alarm has genuinely fired.
     */
    fun timerExpire(taskName: String, taskType: String = "DEFAULT")

    /** User stopped a ringing alarm. Ringing -> Idle, service stops. */
    fun stopAlarm()

    /**
     * Cancels the pending alarm but leaves the notification service running.
     * Used by the notice state machine between phases.
     */
    fun cancelScheduledAlarm()

    /** Shows the pre-countdown "delay" notification for a NOTICE task. */
    fun delayStart(taskName: String, delaySecs: Long)

    /**
     * The currently ringing alarm, or null if nothing is ringing.
     *
     * Replaces `AlarmScheduler.currentState(ctx) as? AlarmState.Ringing` at
     * call sites outside the alarm feature. [AlarmState] is a sealed class
     * internal to that feature and cannot cross a module boundary, so this
     * returns a neutral snapshot carrying the fields callers actually need.
     *
     * A plain boolean was tried first and was not enough: the app-kill recovery
     * path in TaskViewModel needs both [RingingAlarm.taskName] and
     * [RingingAlarm.firedEpoch] to reconstruct how long the alarm has been
     * overrunning.
     */
    fun ringingAlarm(): RingingAlarm?

    /** Convenience for callers that only need to know whether it is ringing. */
    fun isRinging(): Boolean = ringingAlarm() != null
}

/**
 * Neutral snapshot of a ringing alarm, safe to pass across feature boundaries.
 *
 * @param taskName the task whose timer expired.
 * @param firedEpoch `System.currentTimeMillis()` at the moment the alarm fired.
 *   Overrun = now - firedEpoch, recomputed on every app reopen so it survives
 *   process death.
 */
data class RingingAlarm(
    val taskName: String,
    val taskType: String,
    val firedEpoch: Long,
)
