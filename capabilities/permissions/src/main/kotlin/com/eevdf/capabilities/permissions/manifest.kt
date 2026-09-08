package com.eevdf.capabilities.permissions

import com.eevdf.kernel.eventbus.CapabilityManifest
import com.eevdf.kernel.eventbus.Topic

object PermissionsManifest : CapabilityManifest {
    override val capabilityId = "permissions"
    override val publishes: Set<Topic<*>> = emptySet()
    override val subscribes: Set<Topic<*>> = emptySet()

    override fun fallbackWhenUnavailable(topic: Topic<*>) {
        // No-op: no bus participation, same shape as settings-storage.
        // PermissionChecker is a pure, side-effect-free reader of live
        // system permission state — a neutral, shared utility every reader
        // (the Permissions settings page, alarm-ringer's diagnostic
        // logging) can depend on directly, since reading "is X granted?"
        // is data access, not a capability action.
    }
}

/**
 * WHY THIS CAPABILITY EXISTS (design note)
 * -----------------------------------------
 * [PermissionChecker] (moved here, renamed from `AlarmReliabilityChecker`)
 * used to live in `notification`, but every one of its checks except
 * [PermissionChecker.canUseFullScreenIntent] has nothing to do with
 * notifications at all — exact-alarm scheduling, battery optimization,
 * usage-stats access, and the overlay permission are all generic Android
 * special-access checks that settings-screens' Permissions page already
 * read directly, regardless of which feature needs them. Housing them
 * inside `notification` misrepresented that capability's actual scope —
 * see `notification`'s own manifest.kt for what it's scoped to now.
 */
