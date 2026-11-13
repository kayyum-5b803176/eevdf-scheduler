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


    /** Published by backup-restore. Payload: unused (Unit) — a pure signal. */
    val BACKUP_EXPORT_REQUESTED = Topic<Unit>("backup.export-requested")

    // ── Restore (published by backup-restore, carried out by multi-device-sync) ─
    //
    // REPLACES `backup.import-requested`, which asked task-storage to CLOSE
    // the database so backup-restore could overwrite the .db file and kill
    // the process. Nothing closes or replaces the database any more: a
    // restore reads the backup's rows and writes them into the live database
    // as ordinary edits, through the same merge engine sync uses. That is
    // why the engine's owner (multi-device-sync) is the subscriber, and why a
    // restore can no longer collide with a sync or leave a closed Room
    // handle behind.

    /**
     * Published by backup-restore once it has unpacked the backup's database
     * to a private file. Subscribed by multi-device-sync, which answers with
     * [BACKUP_RESTORE_FINISHED]. The handler returns immediately and does the
     * work on its own coroutine — a restore can take longer than the bus's
     * hang timeout allows a handler to run.
     */
    val BACKUP_RESTORE_REQUESTED = Topic<BackupRestoreRequest>("backup.restore-requested")

    /** Published by multi-device-sync when a requested restore ends, successfully or not. */
    val BACKUP_RESTORE_FINISHED = Topic<BackupRestoreResult>("backup.restore-finished")

    /**
     * Asked by backup-restore, answered by multi-device-sync: the automatic
     * safety copies currently kept (taken before a restore and before first
     * joining a sync group), newest first, as absolute file paths.
     */
    val SAFETY_COPIES_REQUESTED = RequestTopic<Unit, List<String>>("backup.safety-copies-requested")

    // ── Sync (published by multi-device-sync unless noted) ───────────────────
    //
    // Every step of a sync is a topic, so the event-log screen shows what
    // actually happened — what was written to the card, what arrived, what
    // went wrong — not just that "a sync ran".

    /**
     * The sync feature's current state, for anything that displays it (the
     * toolbar dot in task-list-screen). Retained, so a screen that opens
     * later still learns the current state. Replaces task-list-screen
     * reading `MultiUserSyncManager.syncState` by direct import.
     */
    val SYNC_STATUS_CHANGED = Topic<SyncStatus>("sync.status-changed", retained = true)

    /**
     * Published by whoever wants a sync right now (task-list-screen's toolbar
     * icon). Payload: unused. Replaces the direct
     * `MultiUserSyncManager.scheduleExport()` calls.
     */
    val SYNC_NOW_REQUESTED = Topic<Unit>("sync.now-requested")

    /** This member's own changes were written to the shared folder. */
    val SYNC_LOCAL_PUBLISHED = Topic<SyncLocalPublished>("sync.local-published")

    /**
     * Changes made elsewhere (another user, a restore) were merged into the
     * live database. Subscribed by task-list-screen, which re-reads whatever
     * it keeps in memory — the running timer above all — instead of the app
     * being restarted, which is what used to happen here.
     */
    val SYNC_REMOTE_APPLIED = Topic<SyncRemoteApplied>("sync.remote-applied")

    /** A sync could not complete. Payload: a short human-readable reason. Nothing is lost; it is retried. */
    val SYNC_FAILED = Topic<String>("sync.failed")

    /**
     * An exchange with the other phone over Bluetooth finished. Published by
     * multi-device-sync; read by the event log. What arrived is merged by the
     * normal sync that follows (and reported through [SYNC_REMOTE_APPLIED]).
     */
    val SYNC_DEVICE_EXCHANGED = Topic<SyncDeviceExchanged>("sync.device-exchanged")

    /** Housekeeping worth seeing in the log: snapshot written/loaded, damaged lines skipped, safety copy taken. */
    val SYNC_MAINTENANCE = Topic<String>("sync.maintenance")

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

    /**
     * Asked by alarm-ringer the moment a timer expires: "should THIS phone
     * make a sound?" Payload: the task name. Answered by multi-device-sync
     * from memory (no disk access), according to the ring mode shared by the
     * synced phones: Swarm → yes; Handover → only the phone used last, unless
     * that phone cannot be reached right now. No answer (sync not set up, or a
     * timeout) means ring — an extra ring is better than a missed one.
     */
    val ALARM_SHOULD_RING_REQUESTED = RequestTopic<String, Boolean>("alarm.should-ring-requested")

    // ── Overlay commands (published by task-list-screen, subscribed by ───────
    // call-autoswitch). Replace the 2 methods of the old OverlayController.

    val OVERLAY_CALL_STARTED_REQUESTED = Topic<Unit>("overlay.call-started-requested")
    val OVERLAY_CALL_ENDED_REQUESTED = Topic<Unit>("overlay.call-ended-requested")

    // ── Sound / vibration (published+subscribed by capabilities/sound and ────
    // capabilities/vibration respectively — the two capabilities never call
    // each other or anything else directly; every trigger and every actual
    // playback action is a topic, specifically so the event-log screen shows
    // the real thing that happened, not just the upstream request for it.

    /**
     * Published by task-list-screen's `NoticeStateMachine` (replaces the old
     * direct `SoundManager.playExecuteSound()`/`playWaitSound()` calls).
     * Subscribed by `sound`.
     */
    val SOUND_CUE_REQUESTED = Topic<SoundCue>("sound.cue-requested")

    /**
     * Published by settings-screens' `ProfileSettingsActivity` (replaces the
     * old direct `VibrationManager.preview()` call). Payload: the pattern id
     * being previewed — see `VibrationPrefs.PATTERNS` in settings-storage for
     * what that id indexes into. Subscribed by `vibration`.
     */
    val VIBRATION_PREVIEW_REQUESTED = Topic<Int>("vibration.preview-requested")

    /**
     * Published by `sound` itself, right after a sound genuinely starts
     * playing — whether triggered by [ALARM_RINGING] or [SOUND_CUE_REQUESTED].
     * Payload: a short human-readable description of what started, e.g.
     * `"alarm:ALARM"` or `"cue:EXECUTE"` — not itself meant to be parsed by
     * another capability, just to be legible in the event log.
     */
    val SOUND_STARTED = Topic<String>("sound.started")

    /**
     * Published by `sound`, right after it stops playing in response to
     * [ALARM_STOPPED]. Payload: unused (Unit) — a pure signal. NOT published
     * for [SOUND_CUE_REQUESTED] playback, which is one-shot and
     * self-terminating with no explicit stop call to hang this off of (see
     * `SoundCueHandler`'s KDoc).
     */
    val SOUND_STOPPED = Topic<Unit>("sound.stopped")

    /** Vibration's equivalent of [SOUND_STARTED] — same payload shape and reasoning. */
    val VIBRATION_STARTED = Topic<String>("vibration.started")

    /** Vibration's equivalent of [SOUND_STOPPED] — same payload shape and reasoning. */
    val VIBRATION_STOPPED = Topic<Unit>("vibration.stopped")

    // ── Notification (published+subscribed by capabilities/notification) ─────
    //
    // Deliberately just cancel, not "build"/"post": building a Notification
    // needs Android types (Notification, PendingIntent) that can never
    // appear in a kernel-declared Topic's payload (rule 1 — kernel stays
    // pure Kotlin/JVM, no Android import is ever valid here, permanently).
    // notification's builder classes are reached as plain direct calls
    // instead (see NotificationManifest's KDoc) — the same category as
    // SoundPrefs/VibrationPrefs: deterministic, side-effect-free
    // construction, not a capability action. Cancelling an already-posted
    // notification IS a real action with a plain (Int id) payload, so that
    // part fits the bus exactly like sound/vibration's stop.

    /** Published by whoever wants a notification gone. Payload: the notification id. */
    val NOTIFICATION_CANCEL_REQUESTED = Topic<Int>("notification.cancel-requested")

    /** Published by `notification`, right after it actually cancels. Payload: the notification id. */
    val NOTIFICATION_CANCELLED = Topic<Int>("notification.cancelled")

    // ── App foreground (published by capabilities/app-foreground) ────────────
    //
    // Retained: unlike a one-shot event, "is the app foreground right now" is
    // exactly the kind of durable state a late-attaching reader needs the
    // CURRENT answer to, not just future transitions — the same reasoning
    // ALARM_RINGING's retained flag already documents. Unlike that topic's
    // cold-start caveat, there's no false-negative risk here: before the
    // first activity ever starts, "not foreground" is the correct answer,
    // not a stale one, so `getLast(...) ?: false` is safe from process start.

    /** Payload: true the instant the started-activity count leaves 0, false the instant it returns to 0. */
    val APP_FOREGROUND_CHANGED = Topic<Boolean>("app.foreground-changed", retained = true)

    // ── Current-task dispatch (published by task-list-screen) ────────────────
    //
    // REPLACES 30+ direct writes to `TaskViewModel._currentTask` scattered
    // across every delegate in task-list-screen (bubble-tap, timer-lifecycle,
    // call-switch, scheduler, notice-state-machine, interrupt) — the exact
    // same shape of problem [TIMER_RUNNING_CHANGED] already fixed for the
    // three `BubbleEventBus` flags. "Which task is currently dispatched" is
    // the single most load-bearing fact in the app — the scheduler's own
    // context-switch — so it gets the same treatment: one owning object
    // (`CurrentTaskOwner`, in task-list-screen) is the only thing allowed to
    // change it, and every change is announced here instead of being poked
    // directly by whichever delegate happens to want a different task running.
    //
    // Payload is deliberately NOT the full `Task` entity: `Task` is a Room
    // `@Entity` (androidx import), and kernel Topics must never carry an
    // Android type (see NOTIFICATION_CANCEL_REQUESTED's note on the same
    // rule). Only the fields a cross-capability subscriber could plausibly
    // need are carried, same pattern as [AlarmRingingEvent].
    //
    // NOT retained, matching [TIMER_RUNNING_CHANGED] exactly: a late
    // subscriber mirrors it via its own [LatestValue] the same way
    // `TaskViewModel.timerState` already does, rather than depending on bus
    // retention.
    //
    // Published only on an actual identity change (a different task id, or a
    // transition to/from no task) — NOT on every field refresh of the task
    // that's already current (e.g. the once-a-second remaining-time tick).
    // That distinction is enforced once, inside `CurrentTaskOwner`, so no
    // call site has to reason about it — see its KDoc.
    val CURRENT_TASK_CHANGED = Topic<CurrentTaskState>("scheduler.current-task-changed")
}

/**
 * Payload for [Topics.CURRENT_TASK_CHANGED].
 *
 * [taskId] null means nothing is currently dispatched (timer card hidden /
 * nothing selected) — the scheduler equivalent of the idle task.
 */
data class CurrentTaskState(
    val taskId: String? = null,
    val taskName: String? = null,
    val isRunning: Boolean = false,
)

/** Coarse state of the sync feature — see [Topics.SYNC_STATUS_CHANGED]. */
enum class SyncPhase { DISABLED, IDLE, SYNCING, OK, ERROR }

/** Payload for [Topics.SYNC_STATUS_CHANGED]. [message] is set for [SyncPhase.ERROR]. */
data class SyncStatus(val phase: SyncPhase = SyncPhase.DISABLED, val message: String? = null)

/**
 * Payload for [Topics.SYNC_DEVICE_EXCHANGED]. [clockOffsetMs] is the other
 * phone's clock minus this one's.
 */
data class SyncDeviceExchanged(
    val device: String,
    val filesSent: Int,
    val filesReceived: Int,
    val bytesSent: Long,
    val bytesReceived: Long,
    val filesDeleted: Int,
    val clockOffsetMs: Long,
)

/** Payload for [Topics.SYNC_LOCAL_PUBLISHED]. */
data class SyncLocalPublished(val changes: Int, val bytes: Long)

/**
 * Payload for [Topics.SYNC_REMOTE_APPLIED].
 *
 * [source] is "sync" or "restore". [tables] names the tables that actually
 * changed, so a subscriber can ignore what does not concern it.
 */
data class SyncRemoteApplied(val source: String, val changes: Int, val tables: List<String>)

/** How a backup is brought in — see [Topics.BACKUP_RESTORE_REQUESTED]. */
enum class RestoreMode {
    /** Add what the backup has and this device lacks. Never changes or removes anything. */
    MERGE,
    /** Make the data equal to the backup: add, overwrite and remove. Travels to every synced member. */
    REPLACE,
}

/** Payload for [Topics.BACKUP_RESTORE_REQUESTED]. [databasePath] is a private, temporary SQLite file. */
data class BackupRestoreRequest(val databasePath: String, val mode: RestoreMode)

/** Payload for [Topics.BACKUP_RESTORE_FINISHED]. */
data class BackupRestoreResult(
    val ok: Boolean,
    val message: String,
    val added: Int = 0,
    val updated: Int = 0,
    val removed: Int = 0,
)

/** Which short, built-in UI cue to play — see [Topics.SOUND_CUE_REQUESTED]. */
enum class SoundCue { EXECUTE, WAIT }

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
 * subscribers this topic exists for — `sound` and `vibration` — need it to
 * pick the right ALARM/NOTIFICATION/CUSTOM preference profile, exactly like
 * [AlarmTimerExpireRequest] already does for the same reason.
 */
data class AlarmRingingEvent(
    val taskName: String,
    val taskType: String = "DEFAULT",
    /**
     * True when this phone shows the expired alarm but must not make a sound
     * or vibrate, because another phone is the one ringing (multi-device sync
     * "Handover" mode). Sound and vibration skip silent events; everything
     * else (notification, stop, overrun) behaves exactly as for a ringing alarm.
     */
    val silent: Boolean = false,
)
