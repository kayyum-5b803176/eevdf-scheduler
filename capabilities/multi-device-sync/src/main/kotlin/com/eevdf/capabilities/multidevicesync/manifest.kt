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
