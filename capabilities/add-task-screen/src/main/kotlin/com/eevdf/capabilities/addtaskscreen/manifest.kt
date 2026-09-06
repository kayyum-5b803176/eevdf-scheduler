package com.eevdf.capabilities.addtaskscreen

import com.eevdf.kernel.eventbus.CapabilityManifest
import com.eevdf.kernel.eventbus.Topic
import com.eevdf.kernel.eventbus.Topics

object AddTaskScreenManifest : CapabilityManifest {
    override val capabilityId = "add-task-screen"
    override val publishes: Set<Topic<*>> = emptySet()
    override val subscribes: Set<Topic<*>> = emptySet()

    override fun fallbackWhenUnavailable(topic: Topic<*>) {
        // No-op: a modal editor reached by Intent. Its writes go through
        // task-storage's repository, which publishes task.saved on its own
        // behalf — this screen is never the thing another capability waits on.
    }
}
