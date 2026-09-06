package com.eevdf.capabilities.runhistory

import com.eevdf.kernel.eventbus.CapabilityManifest
import com.eevdf.kernel.eventbus.Topic
import com.eevdf.kernel.eventbus.Topics

object RunHistoryManifest : CapabilityManifest {
    override val capabilityId = "run-history"
    override val publishes: Set<Topic<*>> = emptySet()
    override val subscribes = setOf(Topics.TASK_SAVED)

    override fun fallbackWhenUnavailable(topic: Topic<*>) {
        // A missed task.saved event means that one run isn't logged to
        // history — stats-screens shows an incomplete picture until the
        // next successful save, but nothing else in the app depends on
        // run-history synchronously, so there's no cascading failure.
    }
}
