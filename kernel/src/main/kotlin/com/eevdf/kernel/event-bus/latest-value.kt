package com.eevdf.kernel.eventbus

import java.util.concurrent.atomic.AtomicReference

/**
 * A capability's own local, always-current copy of a bus topic's latest
 * payload.
 *
 * WHY THIS EXISTS
 * ---------------
 * `BubbleEventBus` was a global object holding three mutable `var`s that
 * `BubbleOverlayService` read *synchronously* mid-draw (to pick a tint colour)
 * and that two different capabilities wrote to. That is shared mutable state
 * across a capability boundary — the thing rule 3 forbids — but it could not
 * simply become fire-and-forget events, because a synchronous reader cannot
 * await an event.
 *
 * The resolution: the state stops being shared. Each capability keeps its own
 * [LatestValue], updated by a bus subscription. Reads stay synchronous and
 * allocation-free; writes go out as events. No capability can reach into
 * another's copy, and there is no global left to reach into.
 *
 * Thread-safety: [AtomicReference] because the writer is a bus dispatch
 * coroutine while readers are typically the main/UI thread (or, for the
 * overlay service, its own handler thread) — the same cross-thread access
 * pattern the old `@Volatile`-backed StateFlow had.
 */
class LatestValue<T>(initial: T) {

    private val ref = AtomicReference(initial)

    /** Current value. Safe to call from any thread; never blocks. */
    val value: T get() = ref.get()

    /**
     * Subscribes [bus] so this cache tracks [topic] for [capabilityId].
     * Returns itself so it can be built and wired in one expression.
     */
    fun trackedOn(bus: EventBus, topic: Topic<T>, capabilityId: String): LatestValue<T> {
        bus.subscribe(topic, capabilityId) { payload -> ref.set(payload) }
        return this
    }

    /**
     * Sets the local value AND publishes it, for a capability that is
     * authoritative at this moment. The local write happens first so this
     * capability's own synchronous reads are correct immediately, without
     * waiting for the dispatch round trip.
     */
    suspend fun setAndPublish(bus: EventBus, topic: Topic<T>, newValue: T) {
        ref.set(newValue)
        bus.publish(topic, newValue)
    }
}
