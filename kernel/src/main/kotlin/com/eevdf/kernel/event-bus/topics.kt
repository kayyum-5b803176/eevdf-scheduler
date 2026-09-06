package com.eevdf.kernel.eventbus

/**
 * A type-safe event topic. [T] is the payload type carried by this topic —
 * the compiler, not a convention, enforces that a subscriber's handler
 * matches what a publisher actually sends.
 */
data class Topic<T>(val name: String)

/**
 * Payload for [Topics.PHONE_CALL_STATE_CHANGED].
 */
enum class CallState { STARTED, ENDED }

/**
 * The fixed catalogue of known topics (rule 3: capabilities never call each
 * other directly — every cross-capability signal is one of these constants).
 * Add new topics here as new capabilities need them; never hardcode a topic
 * string inline in a capability.
 *
 * Table below matches the known-topics table from the implementation prompt
 * §3, wired here so `bus.publish(Topics.TIMER_EXPIRED, taskId)` is
 * compiler-checked end to end.
 */
object Topics {
    /** Published by countdown-timer. Payload: the expired task's id. */
    val TIMER_EXPIRED = Topic<String>("timer.expired")

    /** Published by alarm-ringer. Payload: the ringing task's id. */
    val ALARM_RINGING = Topic<String>("alarm.ringing")

    /** Published by alarm-ringer. Payload: the stopped task's id. */
    val ALARM_STOPPED = Topic<String>("alarm.stopped")

    /** Published by task-scheduling. Payload: the task id whose RT window closed. */
    val REALTIME_WINDOW_EXPIRED = Topic<String>("realtime-window.expired")

    /** Published by task-storage. Payload: the saved task's id. */
    val TASK_SAVED = Topic<String>("task.saved")

    /** Published by the platform call-state receiver. Payload: started/ended. */
    val PHONE_CALL_STATE_CHANGED = Topic<CallState>("phone.call-state-changed")

    /** Published by call-autoswitch. Payload: the task id the overlay now shows. */
    val OVERLAY_SHOWN = Topic<String>("overlay.shown")

    /** Published by multi-device-sync. Payload: the conflicting task's id. */
    val TASK_CONFLICT_DETECTED = Topic<String>("task.conflict-detected")

    /** Published by backup-restore. Payload: unused (Unit) — a pure signal. */
    val BACKUP_EXPORT_REQUESTED = Topic<Unit>("backup.export-requested")

    /** Published by backup-restore. Payload: unused (Unit) — a pure signal. */
    val BACKUP_IMPORT_REQUESTED = Topic<Unit>("backup.import-requested")
}
