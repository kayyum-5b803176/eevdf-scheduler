package com.eevdf.capabilities.notification

import android.app.PendingIntent

/**
 * These carry [PendingIntent] — an Android framework type — which is exactly
 * why they're declared here, in this capability, rather than in kernel as a
 * [com.eevdf.kernel.eventbus.Topic]/[com.eevdf.kernel.eventbus.RequestTopic]
 * payload: kernel stays pure Kotlin/JVM permanently (rule 1), so an
 * Android-typed field can never legally appear in anything kernel declares.
 * Building a notification from a spec is a plain, deterministic,
 * side-effect-free function call instead — see [BannerNotificationBuilder]/
 * [FullScreenNotificationBuilder] — the same category as settings-storage's
 * `SoundPrefs`, not a bus-mediated capability action.
 */

/** A standard heads-up/inbox-style notification. */
data class BannerSpec(
    val channelId: String,
    val title: String,
    val text: String,
    val iconRes: Int,
    val contentIntent: PendingIntent?,
    val priority: Int,
    val autoCancel: Boolean = true,
    val ongoing: Boolean = false,
    val silent: Boolean = false,
)

/**
 * A notification carrying a full-screen intent — the platform decides
 * whether that actually launches full-screen (device locked) or downgrades
 * to a normal heads-up banner (device unlocked); this capability only
 * attaches the intent, it never chooses the presentation style itself.
 */
data class FullScreenSpec(
    val channelId: String,
    val title: String,
    val text: String,
    val iconRes: Int,
    val contentIntent: PendingIntent?,
    val fullScreenIntent: PendingIntent,
    val ongoing: Boolean = true,
    val silent: Boolean = false,
)
