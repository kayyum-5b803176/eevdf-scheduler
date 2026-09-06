package com.eevdf.capabilities.settingsstorage

import com.eevdf.kernel.eventbus.CapabilityManifest
import com.eevdf.kernel.eventbus.Topic

object SettingsStorageManifest : CapabilityManifest {
    override val capabilityId = "settings-storage"
    override val publishes: Set<Topic<*>> = emptySet()
    override val subscribes: Set<Topic<*>> = emptySet()

    override fun fallbackWhenUnavailable(topic: Topic<*>) {
        // No-op: settings-storage has no bus participation. Like task-storage,
        // it is a persistent-state capability that other capabilities read
        // from and write to directly as a declared compile-time dependency.
        // Each accessor already returns a documented default when a key is
        // unset, so an unreadable preference degrades to that default rather
        // than failing — there is no separate fallback path to define.
    }
}

/**
 * WHY THIS CAPABILITY EXISTS (design note):
 *
 * These six preference holders were previously `feature/shared/`, imported
 * directly by task-list-screen, settings-screens, alarm-ringer,
 * call-autoswitch, stats-screens and :app — a shared mutable substrate that
 * blocked every one of those capabilities from moving independently.
 *
 * Assigning each pref file to a single "owning" capability was considered and
 * rejected: settings-screens WRITES all of them while other capabilities READ
 * them, so any single-owner assignment immediately recreates a
 * capability-to-capability call in the opposite direction.
 *
 * Instead this follows the split already proven in Phase 2: task-storage
 * (persistent state) is a separate capability from task-list-screen (UI that
 * renders it). settings-storage is that same shape — the state layer —
 * and settings-screens becomes just one of its several consumers rather than
 * its owner.
 */
