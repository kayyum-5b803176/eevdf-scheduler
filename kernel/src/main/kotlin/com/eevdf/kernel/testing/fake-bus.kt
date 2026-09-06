package com.eevdf.kernel.testing

import com.eevdf.kernel.eventbus.Handler
import com.eevdf.kernel.eventbus.Topic

/**
 * In-memory recording bus for a capability's own unit tests. Lets a test
 * assert "my capability published topic X with payload Y" without spinning
 * up the real [com.eevdf.kernel.eventbus.EventBus] +
 * [com.eevdf.kernel.supervisor.Supervisor].
 *
 * This is the one shared test fixture every capability may depend on
 * directly — the same exception granted to the kernel's other primitives
 * (clock, crash-guard), because it exercises the kernel's own public
 * contract ([Topic]/[Handler]), never another capability's internals.
 */
class FakeBus {
    /** Every (topic, payload) pair published so far, in order. */
    val published = mutableListOf<Pair<Topic<*>, Any?>>()

    private val handlers = mutableMapOf<Topic<*>, MutableList<Handler<*>>>()

    fun <T> subscribe(topic: Topic<T>, handler: Handler<T>) {
        @Suppress("UNCHECKED_CAST")
        (handlers.getOrPut(topic) { mutableListOf() } as MutableList<Handler<T>>).add(handler)
    }

    suspend fun <T> publish(topic: Topic<T>, payload: T) {
        published += topic to payload
        @Suppress("UNCHECKED_CAST")
        (handlers[topic] as? List<Handler<T>>)?.forEach { it.handle(payload) }
    }

    /** Convenience for assertions: every payload published on [topic], in order. */
    fun <T> payloadsOf(topic: Topic<T>): List<T> =
        published.filter { it.first == topic }.map {
            @Suppress("UNCHECKED_CAST")
            it.second as T
        }

    fun reset() {
        published.clear()
        handlers.clear()
    }
}
