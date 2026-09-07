package com.eevdf.capabilities.multidevicesync

import com.eevdf.kernel.eventbus.CapabilityManifest
import com.eevdf.kernel.eventbus.Topic
import com.eevdf.kernel.eventbus.Topics

object MultiDeviceSyncManifest : CapabilityManifest {
    override val capabilityId = "multi-device-sync"
    override val publishes: Set<Topic<*>> = setOf(Topics.TASK_CONFLICT_DETECTED)
    override val subscribes: Set<Topic<*>> = setOf(Topics.TASK_SAVED)

    override fun fallbackWhenUnavailable(topic: Topic<*>) {
        // A missed task.saved means this device does not export that change
        // to the shared sync file right away. The write is not lost: the next
        // successful export carries it, and the conflict guard on the
        // receiving side reconciles by field, so a delayed export degrades to
        // 'other devices see it later', not to data loss.
    }
}

/**
 * RESOLVED — `TASK_CONFLICT_DETECTED` (was dead) is now published for real,
 * from `MultiUserSyncManager` right after it posts `SyncState.ConflictPending`
 * for each conflict. `MultiUserSyncManager` is a plain `object`, not
 * Hilt-managed, so it takes the bus as an optional param on `init(context,
 * bus)` rather than via constructor injection — `bus` defaults to null so
 * any caller that never passes one (tests exercising this object directly)
 * keeps compiling, and the publish is skipped rather than crashing.
 * `SyncState.ConflictPending`'s own LiveData stays the primary, always-on
 * mechanism for the actual warning dialog (it carries full per-field detail
 * this topic's bare task-id payload doesn't) — the bus publish is
 * additional, not a replacement.
 */
