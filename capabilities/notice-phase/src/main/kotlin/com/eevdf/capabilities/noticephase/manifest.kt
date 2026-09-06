package com.eevdf.capabilities.noticephase

import com.eevdf.kernel.eventbus.CapabilityManifest
import com.eevdf.kernel.eventbus.Topic
import com.eevdf.kernel.eventbus.Topics

object NoticePhaseManifest : CapabilityManifest {
    override val capabilityId = "notice-phase"
    override val publishes: Set<Topic<*>> = emptySet()
    override val subscribes: Set<Topic<*>> = emptySet()

    override fun fallbackWhenUnavailable(topic: Topic<*>) {
        // No-op: pure state-machine logic driven by countdown-timer. If
        // unavailable, tasks run without the notice/execute phase split —
        // the timer itself is unaffected.
    }
}
