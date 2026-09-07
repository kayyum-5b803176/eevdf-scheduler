package com.eevdf.kernel.eventbus

/**
 * Every [RequestTopic] used across the app — the request/response
 * counterpart to [Topics]. One place to see the full shape of every
 * question a capability can ask over the bus, the same way [Topics] lists
 * every announcement.
 */
object RequestTopics {

    /**
     * Asked by alarm-ringer, answered by notification, right before
     * posting the expired-alarm notification.
     *
     * Replaces four separate direct imports alarm-ringer used to have
     * (`AppForegroundTracker`, `ForegroundAppDetector`, `AlarmNotificationPolicy`,
     * `AlarmReliabilityChecker`) with one question: "given an alarm is about
     * to ring, how should its notification be presented?" notification
     * now owns that whole decision internally — none of those four classes
     * need to be public API for another capability to consume any more; see
     * `AlarmNotificationDecisionResponder`.
     *
     * Payload is `Unit`: none of the decision's inputs (is the app itself
     * foreground, is the current foreground app on the exclude list, is the
     * lock-screen-overlay pref on) depend on which task is expiring, so
     * there is nothing for the requester to supply.
     */
    val ALARM_NOTIFICATION_DECISION =
        RequestTopic<Unit, AlarmNotificationDecision>("alarm.notification-decision")
}

/**
 * Answer to [RequestTopics.ALARM_NOTIFICATION_DECISION].
 *
 * [canUseFullScreenIntent] is carried purely so the requester can still log
 * it — it was already diagnostic-only before this became a request/response
 * call ("Diagnostic snapshot only — never gates behavior"), and moving it
 * into the response means alarm-ringer doesn't need its own import of
 * `AlarmReliabilityChecker` just to log one extra field.
 */
data class AlarmNotificationDecision(
    val suppressBanner: Boolean,
    val attachFullScreenIntent: Boolean,
    val canUseFullScreenIntent: Boolean,
)
