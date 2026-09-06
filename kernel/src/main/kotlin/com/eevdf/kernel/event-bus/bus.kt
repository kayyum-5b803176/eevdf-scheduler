package com.eevdf.kernel.eventbus

import com.eevdf.kernel.crashguard.runIsolated
import com.eevdf.kernel.supervisor.Supervisor
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * What every capability's own `manifest.kt` implements, declaring its side of
 * the contract with the bus (rule 4).
 */
interface CapabilityManifest {
    /** Stable id used for health tracking, e.g. "alarm-ringer". */
    val capabilityId: String

    /** Topics this capability may publish. */
    val publishes: Set<Topic<*>>

    /** Topics this capability subscribes to. */
    val subscribes: Set<Topic<*>>

    /**
     * Called (by the caller, or by the bus on a dead-letter) when this
     * capability is unavailable and something depended on it. Must not
     * throw and must not block.
     */
    fun fallbackWhenUnavailable(topic: Topic<*>)
}

/** A subscriber's callback for one topic. */
fun interface Handler<T> {
    suspend fun handle(payload: T)
}

/**
 * The only channel capabilities may use to reach each other (rule 3). Every
 * dispatch is wrapped by [runIsolated] and gated by [Supervisor]
 * availability, so one capability's failure or hang can never propagate to
 * another, and an unavailable capability is simply skipped rather than
 * blocking the publish.
 */
class EventBus(private val supervisor: Supervisor) {

    private data class Subscription<T>(val capabilityId: String, val handler: Handler<T>)

    private val subscriptions = mutableMapOf<Topic<*>, MutableList<Subscription<*>>>()

    /** Registers [handler] under [capabilityId] for [topic]. */
    fun <T> subscribe(topic: Topic<T>, capabilityId: String, handler: Handler<T>) {
        @Suppress("UNCHECKED_CAST")
        val list = subscriptions.getOrPut(topic) { mutableListOf() } as MutableList<Subscription<T>>
        list.add(Subscription(capabilityId, handler))
    }

    /** Removes every subscription owned by [capabilityId] (e.g. on detach). */
    fun unsubscribeAll(capabilityId: String) {
        subscriptions.values.forEach { list -> list.removeAll { it.capabilityId == capabilityId } }
    }

    /**
     * Fans [payload] out to every subscriber of [topic]. Each subscriber is
     * dispatched independently and in isolation: a subscriber that throws or
     * hangs is caught by [runIsolated] and marked unavailable by
     * [supervisor] for future dispatches — it never blocks or breaks
     * delivery to any other subscriber, nor to the publisher.
     */
    suspend fun <T> publish(topic: Topic<T>, payload: T) = coroutineScope {
        @Suppress("UNCHECKED_CAST")
        val subs = subscriptions[topic] as? List<Subscription<T>> ?: return@coroutineScope
        for (sub in subs) {
            if (!supervisor.isAvailable(sub.capabilityId)) continue
            launch {
                runIsolated(sub.capabilityId, supervisor) {
                    sub.handler.handle(payload)
                }
            }
        }
    }
}
