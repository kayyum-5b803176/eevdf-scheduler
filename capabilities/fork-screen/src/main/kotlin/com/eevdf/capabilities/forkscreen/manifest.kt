package com.eevdf.capabilities.forkscreen

import com.eevdf.kernel.eventbus.CapabilityManifest
import com.eevdf.kernel.eventbus.Topic
import com.eevdf.kernel.eventbus.Topics

object ForkScreenManifest : CapabilityManifest {
    override val capabilityId = "fork-screen"
    override val publishes: Set<Topic<*>> = emptySet()
    override val subscribes: Set<Topic<*>> = emptySet()

    override fun fallbackWhenUnavailable(topic: Topic<*>) {
        // No-op: reached by Intent via navigation-routes (the add button's speed dial). Its writes go through
        // task-storage's ForkRepository / TaskRepository, which publish task.saved (and hand the copied wake
        // profile to task-wake) on their own behalf — this screen is never the thing another capability waits
        // on. If task-wake is unavailable the fork is still created; only its wake profile is not carried over.
    }
}
