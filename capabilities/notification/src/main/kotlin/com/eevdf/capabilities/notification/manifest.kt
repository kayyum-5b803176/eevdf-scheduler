package com.eevdf.capabilities.notification

import com.eevdf.kernel.eventbus.CapabilityManifest
import com.eevdf.kernel.eventbus.Topic
import com.eevdf.kernel.eventbus.Topics

/**
 * Purely mechanical: builds and cancels notifications given fully-formed
 * content. Knows nothing about alarms, tasks, sound/vibration, or any
 * suppress/exclude-app decision — that logic lives with whichever
 * capability actually needs to make it (see alarm-ringer's
 * `AlarmNotificationPolicy`, moved there from here).
 *
 * RESOLVED (redecomposition): this capability used to hold
 * `AlarmNotificationPolicy`, `AlarmDeliveryLog`, `AlarmDeliveryHandler`,
 * `AlarmNotificationDecisionResponder`, `AppForegroundTracker`,
 * `ForegroundAppDetector`, and `AlarmReliabilityChecker` — every one of
 * them alarm-specific (or, for the permission checks, generically Android
 * but not notification-specific) despite this capability's name. All seven
 * moved out: the first five to alarm-ringer (the only capability that ever
 * needed them), the permission checks to a new neutral `permissions`
 * capability (see its own manifest.kt for why). What's left here is what
 * the name always should have meant: generic notification construction —
 * [NotificationChannelManager], [BannerNotificationBuilder],
 * [FullScreenNotificationBuilder] — plus [NOTIFICATION_CANCEL_REQUESTED],
 * the one genuine capability *action* (cancelling has a side effect;
 * building a Notification object does not) this capability performs, which
 * is why it's the only thing wired through the bus below.
 *
 * Building/posting is deliberately NOT bus-mediated — see
 * `notification-specs.kt`'s KDoc: a `Notification`/`PendingIntent` can never
 * legally appear in a kernel-declared [Topic]'s payload (kernel stays pure
 * Kotlin/JVM permanently), so "build me a notification" is a plain direct
 * call to this capability's builder classes instead, the same category as
 * settings-storage's `SoundPrefs` — deterministic, side-effect-free
 * construction, not a bus action. Posting itself (`startForeground()`/
 * `NotificationManager.notify()`) stays with whichever Service/Activity
 * owns that OS-level call; it can never be this capability's job, in any
 * architecture — a foreground service must post its own notification
 * synchronously, in-process.
 */
object NotificationManifest : CapabilityManifest {
    override val capabilityId = "notification"
    override val publishes: Set<Topic<*>> = setOf(Topics.NOTIFICATION_CANCELLED)
    override val subscribes: Set<Topic<*>> = setOf(Topics.NOTIFICATION_CANCEL_REQUESTED)

    override fun fallbackWhenUnavailable(topic: Topic<*>) {
        // If unavailable: a requested cancel never happens, so a stale
        // notification could linger until the user dismisses it manually.
        // Building notifications (the majority of this capability's actual
        // work) isn't bus-mediated at all, so it has no "unavailable" case —
        // a direct call either succeeds or the caller's own try/catch
        // handles it, same as any other local function call.
    }
}
