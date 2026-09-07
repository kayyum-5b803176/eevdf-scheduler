package com.eevdf.kernel.eventbus

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * One recorded [EventBus.publish] call.
 *
 * [payload] is [Any.toString] of the actual payload, not the typed value —
 * this is a diagnostic record for a human to read (the event-log screen),
 * not something re-dispatched or type-checked later, so a plain string is
 * both simplest and safest (no risk of holding a reference to, say, a large
 * or capability-owned object past its useful lifetime).
 */
data class BusEventRecord(
    /** Monotonically increasing, unique within one process run — stable RecyclerView item id. */
    val id: Long,
    val topicName: String,
    val payload: String,
    val timestampMs: Long,
)

/**
 * Bounded, thread-safe record of the most recent events published on
 * [EventBus], newest first. Exists purely for the event-log screen — reading
 * it is diagnostic, not a channel capabilities communicate over (that's
 * still exclusively `subscribe`/`publish` on specific [Topic]s), so this
 * stays a plain in-memory log, not itself a topic.
 *
 * One instance lives inside [EventBus] and records every [EventBus.publish]
 * call unconditionally — including topics with zero live subscribers — so
 * the log reflects everything that happened on the bus, not just what
 * something happened to be listening for.
 */
class BusEventLog(private val capacity: Int = 500) {

    private val nextId = AtomicLong(0)
    private val _events = MutableStateFlow<List<BusEventRecord>>(emptyList())

    /** Newest-first. A screen collects this directly for a live-updating list. */
    val events: StateFlow<List<BusEventRecord>> = _events.asStateFlow()

    @Synchronized
    fun record(topicName: String, payload: String, nowMs: Long = System.currentTimeMillis()) {
        val record = BusEventRecord(nextId.getAndIncrement(), topicName, payload, nowMs)
        val updated = listOf(record) + _events.value
        _events.value = if (updated.size > capacity) updated.subList(0, capacity) else updated
    }
}
