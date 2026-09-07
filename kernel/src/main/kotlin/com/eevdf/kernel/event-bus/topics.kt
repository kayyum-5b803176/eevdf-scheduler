package com.eevdf.kernel.eventbus

/**
 * A type-safe event topic. [T] is the payload type carried by this topic —
 * the compiler, not a convention, enforces that a subscriber's handler
 * matches what a publisher actually sends.
 *
 * @param retained When true, [EventBus.publish] keeps the most recent payload
 *   so a capability that subscribes *after* the event already fired can still
 *   ask for it via [EventBus.getLast] — e.g. a screen that attaches after
 *   `task.saved` already happened once. Fan-out to live subscribers is
 *   unaffected either way; this only adds a synchronous "what's the last
 *   value" read path on top of the same publish call.
 */
data class Topic<T>(val name: String, val retained: Boolean = false)

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

    /**
     * Published by alarm-ringer. Payload: [AlarmRingingEvent] (task name + type).
     *
     * Retained: a best-effort echo of the last alarm that started ringing, so
     * a subscriber attaching after the fact (rather than being live at the
     * moment of the event) has something to read via
     * `bus.getLast(Topics.ALARM_RINGING)`. NOT the authoritative "is an alarm
     * ringing right now" check — that already exists as
     * `kernel/contracts/AlarmRingingQuery`, a synchronous query against
     * AlarmScheduler's real persisted state, used specifically for cold-start
     * recovery. This retained value can go stale (e.g. after
     * `Topics.ALARM_STOPPED`, since retention isn't cleared on a different
     * topic firing) — treat it as "the last alarm.ringing payload", not as
     * live ringing state.
     */
    val ALARM_RINGING = Topic<AlarmRingingEvent>("alarm.ringing", retained = true)

    /** Published by alarm-ringer. Payload: the stopped task's id. */
    val ALARM_STOPPED = Topic<String>("alarm.stopped")

    /**
     * Published by task-list-screen's `ListBuilderDelegate` (the RT-window
     * transition-detection point — see its own KDoc), not by task-scheduling
     * despite the name suggesting otherwise; see task-scheduling's
     * manifest.kt for why that capability structurally can't be the
     * publisher. Payload: the task id whose RT window closed.
     */
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

    // ── Alarm commands (published by countdown-timer / notice-phase, ─────────
    // subscribed by alarm-ringer). Replace the 6 one-way methods of the old
    // AlarmController contract interface — see alarm-ringer's
    // AlarmCommandHandler for the subscriber side and its manifest.kt for why
    // the 7th method (ringingAlarm(), a genuine synchronous query) did NOT
    // become a topic.

    val ALARM_TIMER_START_REQUESTED = Topic<AlarmTimerStartRequest>("alarm.timer-start-requested")
    val ALARM_TIMER_PAUSE_REQUESTED = Topic<Unit>("alarm.timer-pause-requested")
    val ALARM_TIMER_EXPIRE_REQUESTED = Topic<AlarmTimerExpireRequest>("alarm.timer-expire-requested")
    val ALARM_STOP_REQUESTED = Topic<Unit>("alarm.stop-requested")
    val ALARM_CANCEL_SCHEDULED_REQUESTED = Topic<Unit>("alarm.cancel-scheduled-requested")
    val ALARM_DELAY_START_REQUESTED = Topic<AlarmDelayStartRequest>("alarm.delay-start-requested")

    // ── Overlay commands (published by task-list-screen, subscribed by ───────
    // call-autoswitch). Replace the 2 methods of the old OverlayController.

    val OVERLAY_CALL_STARTED_REQUESTED = Topic<Unit>("overlay.call-started-requested")
    val OVERLAY_CALL_ENDED_REQUESTED = Topic<Unit>("overlay.call-ended-requested")
}

data class AlarmTimerStartRequest(
    val taskName: String,
    val remainingSecs: Long,
    val taskType: String = "DEFAULT",
    val alarmSecs: Long = remainingSecs,
)

data class AlarmTimerExpireRequest(val taskName: String, val taskType: String = "DEFAULT")

data class AlarmDelayStartRequest(val taskName: String, val delaySecs: Long)

/**
 * Payload for [Topics.ALARM_RINGING].
 *
 * Carries [taskType] (not just the task name) because the two real
 * subscribers this topic exists for — feedback-cues' sound/vibration and any
 * future per-profile reaction — need it to pick the right ALARM/NOTIFICATION/
 * CUSTOM preference profile, exactly like [AlarmTimerExpireRequest] already
 * does for the same reason.
 */
data class AlarmRingingEvent(val taskName: String, val taskType: String = "DEFAULT")
