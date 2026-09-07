package com.eevdf.kernel.testing

import com.eevdf.kernel.eventbus.Handler
import com.eevdf.kernel.eventbus.RequestHandler
import com.eevdf.kernel.eventbus.RequestTopic
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
 * contract ([Topic]/[Handler]/[RequestTopic]/[RequestHandler]), never
 * another capability's internals.
 */
class FakeBus {
    /** Every (topic, publisherId, payload) triple published so far, in order. */
    val published = mutableListOf<Triple<Topic<*>, String, Any?>>()

    private val handlers = mutableMapOf<Topic<*>, MutableList<Handler<*>>>()
    private val responders = mutableMapOf<RequestTopic<*, *>, RequestHandler<*, *>>()

    fun <T> subscribe(topic: Topic<T>, handler: Handler<T>) {
        @Suppress("UNCHECKED_CAST")
        (handlers.getOrPut(topic) { mutableListOf() } as MutableList<Handler<T>>).add(handler)
    }

    /** Mirrors [com.eevdf.kernel.eventbus.EventBus.respondTo] — one responder per [RequestTopic]. */
    fun <Req, Res> respondTo(topic: RequestTopic<Req, Res>, handler: RequestHandler<Req, Res>) {
        check(topic !in responders) { "RequestTopic '${topic.name}' already has a responder in this FakeBus." }
        responders[topic] = handler
    }

    private val lastValue = mutableMapOf<String, Any?>()

    suspend fun <T> publish(topic: Topic<T>, payload: T, publisherId: String) {
        published += Triple(topic, publisherId, payload)
        if (topic.retained) lastValue[topic.name] = payload
        @Suppress("UNCHECKED_CAST")
        (handlers[topic] as? List<Handler<T>>)?.forEach { it.handle(payload) }
    }

    /** Mirrors [com.eevdf.kernel.eventbus.EventBus.request]. */
    suspend fun <Req, Res> request(topic: RequestTopic<Req, Res>, payload: Req): Res? {
        @Suppress("UNCHECKED_CAST")
        val responder = responders[topic] as? RequestHandler<Req, Res> ?: return null
        return responder.handle(payload)
    }

    /** Mirrors [com.eevdf.kernel.eventbus.EventBus.getLast] for retained-topic tests. */
    fun <T> getLast(topic: Topic<T>): T? {
        if (!topic.retained) return null
        @Suppress("UNCHECKED_CAST")
        return lastValue[topic.name] as T?
    }

    /** Convenience for assertions: every payload published on [topic], in order. */
    fun <T> payloadsOf(topic: Topic<T>): List<T> =
        published.filter { it.first == topic }.map {
            @Suppress("UNCHECKED_CAST")
            it.third as T
        }

    fun reset() {
        published.clear()
        handlers.clear()
        responders.clear()
        lastValue.clear()
    }
}
