package com.eevdf.contract.control

/**
 * A deliberately narrow, documented exception to rule 3.
 *
 * WHY THIS EXISTS INSTEAD OF A BUS TOPIC
 * ---------------------------------------
 * The old `AlarmController` had 7 methods: 6 one-way commands (now bus topics
 * — see `Topics.ALARM_*_REQUESTED` and alarm-ringer's `AlarmCommandHandler`)
 * and this one synchronous query, used exactly once: task-list-screen's
 * `StartupRecoveryDelegate` calls [ringingAlarm] on cold start (including
 * after the process was killed) to reconstruct how long an alarm has been
 * overrunning.
 *
 * A bus event cannot safely answer "what is true right now" after a process
 * restart: the kernel bus has no memory between processes, and a `LatestValue`
 * cache (the mechanism used for `BubbleEventBus`'s old flags) starts back at
 * its initial value until something republishes — which races against this
 * exact recovery check running on `TaskViewModel` construction. Getting that
 * race wrong means recovery UI briefly shows "not ringing" when something is.
 *
 * [AlarmScheduler.currentState] already reads real persisted state (not
 * in-memory), synchronously, with no such race. So this one query stays a
 * direct, documented, injected dependency — the same class of exception as
 * task-storage's compile-time dependency on task-scheduling's bridge file.
 * Everything else about alarm control goes through the bus.
 */
public interface AlarmRingingQuery {

    /** The currently ringing alarm, or null if nothing is ringing. */
    public fun ringingAlarm(): RingingAlarm?

    /** Convenience for callers that only need to know whether it is ringing. */
    public fun isRinging(): Boolean = ringingAlarm() != null
}

/**
 * Neutral snapshot of a ringing alarm, safe to pass across the capability
 * boundary. Unchanged from the old `AlarmController`'s `RingingAlarm`.
 */
public data class RingingAlarm(
    public val taskName: String,
    public val taskType: String,
    public val firedEpoch: Long,
)
