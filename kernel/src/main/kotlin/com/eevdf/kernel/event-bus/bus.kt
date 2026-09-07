package com.eevdf.kernel.eventbus

import com.eevdf.kernel.crashguard.runIsolated
import com.eevdf.kernel.supervisor.Supervisor
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
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
class EventBus(private val supervisor: Supervisor, private val eventLog: BusEventLog = BusEventLog()) {

    private data class Subscription<T>(val capabilityId: String, val handler: Handler<T>)

    private val subscriptions = mutableMapOf<Topic<*>, MutableList<Subscription<*>>>()

    /**
     * Backing store for [Topic.retained] topics: the most recent payload
     * published on each retained topic's name, keyed by [Topic.name] rather
     * than by the [Topic] instance itself so [getLast] works even when called
     * with a distinct [Topic] value carrying the same name (data class
     * equality already covers this, but keying by name matches how
     * [Topics] are actually declared — one canonical `val` per name — and
     * keeps this map trivially inspectable from tests without needing the
     * exact object reference).
     */
    private val lastValue = mutableMapOf<String, Any?>()

    /**
     * Read-only diagnostic feed of every [publish] call, newest first — see
     * [BusEventLog]. Not itself a bus channel; introspection only, for the
     * event-log screen.
     */
    val log: StateFlow<List<BusEventRecord>> get() = eventLog.events

    /** Wipes the event log — see [BusEventLog.clear]. */
    fun clearLog() = eventLog.clear()

    /** Registers [handler] under [capabilityId] for [topic]. */
    fun <T> subscribe(topic: Topic<T>, capabilityId: String, handler: Handler<T>) {
        @Suppress("UNCHECKED_CAST")
        val list = subscriptions.getOrPut(topic) { mutableListOf() } as MutableList<Subscription<T>>
        list.add(Subscription(capabilityId, handler))
    }

    /**
     * Returns the most recent payload published on [topic], or null if
     * nothing has been published yet — or if [topic] isn't [Topic.retained]
     * (retention is opt-in per topic; a non-retained topic always reads back
     * null here regardless of publish history, so callers don't silently
     * depend on retention no one declared).
     */
    fun <T> getLast(topic: Topic<T>): T? {
        if (!topic.retained) return null
        @Suppress("UNCHECKED_CAST")
        return lastValue[topic.name] as T?
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
        // Recorded unconditionally — including topics with zero live
        // subscribers — so the event-log screen reflects everything
        // actually published, not just what something happened to be
        // listening for. toString() rather than the typed payload: this is
        // a diagnostic record for a human to read, not a re-dispatch.
        // Unit payloads (pure signal topics like backup.export-requested)
        // record as an empty string rather than the unhelpful "kotlin.Unit".
        eventLog.record(topic.name, if (payload == Unit) "" else payload.toString())
        if (topic.retained) lastValue[topic.name] = payload
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
