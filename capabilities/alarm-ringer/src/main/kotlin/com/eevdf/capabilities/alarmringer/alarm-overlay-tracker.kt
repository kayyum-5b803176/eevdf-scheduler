package com.eevdf.capabilities.alarmringer

/**
 * Whether [AlarmActivity] — the full-screen alarm overlay — is currently
 * showing. Purely local to this capability; never crosses the bus.
 *
 * WHY THIS EXISTS
 * -----------------
 * `app-foreground`'s [com.eevdf.capabilities.appforeground.AppForegroundTracker]
 * is genuinely generic now — it counts every Activity, no exceptions (see
 * its manifest.kt). But this capability's own notification-suppression
 * decision specifically needs to know "is the user looking at our NORMAL
 * UI" as distinct from "is the user looking at the alarm overlay we just
 * launched" — without that distinction, `AlarmActivity` showing would flip
 * `Topics.APP_FOREGROUND_CHANGED` to true and could suppress the very
 * notification whose full-screen intent launched it, or a second alarm
 * racing shortly after the first is dismissed (this is exactly what caused
 * the reported "works once, then never shows full-screen again" bug).
 *
 * The fix is local, not a change to the generic tracker: alarm-ringer reads
 * `bus.getLast(Topics.APP_FOREGROUND_CHANGED)` AND checks this flag, ANDing
 * them together at the one call site that needs the distinction (see
 * `AlarmForegroundService`'s decision block) — the generic capability never
 * learns this capability's screen names.
 *
 * A plain boolean, not a counter: [AlarmActivity] is a single well-known
 * Activity, not a set of them, so there's nothing to count. A rotation could
 * theoretically produce a brief false "not showing" blip between this
 * instance's `onStop` and the recreated instance's `onStart` — low
 * consequence (a momentary, self-correcting flicker in a rare edge case),
 * unlike the app-wide count [AppForegroundTracker] guards more carefully.
 */
object AlarmOverlayTracker {
    @Volatile
    var isShowing: Boolean = false
        private set

    fun markShowing() { isShowing = true }
    fun markHidden() { isShowing = false }
}
