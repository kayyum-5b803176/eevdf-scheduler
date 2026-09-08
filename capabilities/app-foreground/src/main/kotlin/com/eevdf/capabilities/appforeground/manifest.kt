package com.eevdf.capabilities.appforeground

import com.eevdf.kernel.eventbus.CapabilityManifest
import com.eevdf.kernel.eventbus.Topic
import com.eevdf.kernel.eventbus.Topics

/**
 * Genuinely generic: "is any of this app's Activities currently started
 * (visible)?" — zero domain knowledge, no exceptions for any particular
 * screen. Any capability that needs a special-case adjustment (e.g.
 * alarm-ringer not wanting its own full-screen alarm overlay to count as
 * "the user is using the app") makes that adjustment locally, by combining
 * this topic's retained value with its own signal — see alarm-ringer's
 * `AlarmOverlayTracker` for that pattern. This capability never grows a
 * second exception for a second caller; that would be the same mistake
 * repeated (see the redecomposition note in `notification`'s manifest.kt
 * for the same principle applied there).
 */
object AppForegroundManifest : CapabilityManifest {
    override val capabilityId = "app-foreground"
    override val publishes: Set<Topic<*>> = setOf(Topics.APP_FOREGROUND_CHANGED)
    override val subscribes: Set<Topic<*>> = emptySet()

    override fun fallbackWhenUnavailable(topic: Topic<*>) {
        // If unavailable, Topics.APP_FOREGROUND_CHANGED simply never updates
        // again — readers keep getting the last value it retained (or null,
        // pre-first-publish). Since "no activity has started yet" and
        // "tracker unavailable" both correctly degrade to treating the app
        // as not foreground, this has no separate fallback behavior to define.
    }
}
