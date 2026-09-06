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
 * Payload for [Topics.TIMER_RUNNING_CHANGED].
 *
 * REPLACES the three global `var`s that used to live on `BubbleEventBus`
 * (`timerRunning`, `anyTimerRunning`, `callTaskRunning`).
 *
 * Why a snapshot rather than three separate topics: the three flags were
 * always written together, in the same statement block, at all six call
 * sites. Splitting them into separate topics would let a subscriber observe
 * a torn combination that never existed (e.g. `callTaskRunning = true` while
 * `anyTimerRunning` is still false). One immutable snapshot makes that
 * impossible by construction.
 */
data class TimerRunningState(
    /** True while any task timer is active, regardless of which task. */
    val anyTimerRunning: Boolean = false,
    /** True when the currently active timer belongs to the call-assigned task. */
    val callTaskRunning: Boolean = false,
    /** True while the call task timer is running. Legacy flag, kept 1:1. */
    val timerRunning: Boolean = false,
)

/**
 * The fixed catalogue of known topics (rule 3: capabilities never call each
 * other directly — every cross-capability signal is one of these constants).
 * Add new topics here as new capabilities need them; never hardcode a topic
 * string inline in a capability.
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

    /**
     * Published by task-list-screen AND call-autoswitch — both are
     * authoritative at different moments (the list screen when the user
     * starts/stops a timer; call-autoswitch when it auto-switches on an
     * incoming call). Subscribed by both, each keeping a local copy for
     * synchronous reads.
     *
     * This replaces `BubbleEventBus`'s global mutable flags. Nothing is
     * shared any more: each capability owns its own snapshot and learns
     * about the other's changes through this topic.
     */
    val TIMER_RUNNING_CHANGED = Topic<TimerRunningState>("timer.running-changed")

    /**
     * Published by call-autoswitch when the user taps the floating bubble;
     * subscribed by task-list-screen.
     *
     * Replaces `BubbleEventBus.onBubbleTap`, a nullable global callback that
     * MainActivity assigned in onCreate and had to remember to null out in
     * onDestroy — its own KDoc carried a LEAK WARNING saying so. A bus
     * subscription is unregistered by capability id instead, so forgetting
     * cannot retain an Activity.
     */
    val BUBBLE_TAPPED = Topic<Unit>("overlay.bubble-tapped")
}
