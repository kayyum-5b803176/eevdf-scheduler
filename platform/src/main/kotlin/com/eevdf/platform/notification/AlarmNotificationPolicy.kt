package com.eevdf.platform.notification

/**
 * Pure decision: given the current situation, which notification style(s)
 * should a timer expiry use? No Context, no Android APIs, no side effects —
 * every input is a plain value the caller already has, so this is trivially
 * unit-testable in isolation from the Service that acts on it.
 *
 * Previously this logic lived inline inside AlarmForegroundService's
 * onStartCommand(), mixed in with wake locks, sound, and notification
 * building. Every fix in that area risked silently changing the decision
 * itself, because there was no boundary around it. This object IS that
 * boundary.
 */
object AlarmNotificationPolicy {

    data class Decision(
        /** True → post the banner/notification silently (no heads-up peek). */
        val suppressBanner: Boolean,
        /** True → attach a full-screen intent to the posted notification. */
        val attachFullScreenIntent: Boolean
    )

    /**
     * @param appForeground true when the EEVDF app itself is the foreground
     *   app — always suppresses both styles (the in-app expired UI covers it).
     * @param excludeAppMatch true when the current foreground app is on the
     *   user's Exclude App list — suppresses the banner style only; never
     *   relevant to the full-screen style (see NotificationPrefs).
     * @param lockScreenOverlayEnabled the user's Lock Screen Overlay pref.
     *   When true, a full-screen intent is attached unconditionally — same
     *   as AOSP Clock's TimerNotificationBuilder.buildHeadsUp(), which never
     *   pre-checks lock state in app code. Whether it actually launches
     *   full-screen (locked) or gets downgraded to a banner (unlocked) is the
     *   platform's decision, made from real keyguard state at post time.
     */
    fun decide(
        appForeground: Boolean,
        excludeAppMatch: Boolean,
        lockScreenOverlayEnabled: Boolean
    ): Decision = Decision(
        suppressBanner = appForeground || excludeAppMatch,
        attachFullScreenIntent = !appForeground && lockScreenOverlayEnabled
    )
}
