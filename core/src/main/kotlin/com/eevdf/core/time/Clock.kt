package com.eevdf.core.time

/**
 * The single source of "now" for every pure subsystem.
 *
 * WHY THIS EXISTS
 * ---------------
 * In the reference implementation the domain model (`Task`) called
 * `System.currentTimeMillis()` *inside* computed properties such as
 * `currentQuotaUsed` and `isDlBudgetActive`. That had three costs:
 *
 *   1. Non-determinism — the same property returned different values on two
 *      consecutive reads, so a single scheduling pass could see a task as
 *      both "under quota" and "over quota".
 *   2. Untestable — you cannot unit-test deadline/quota logic without freezing
 *      wall-clock time, which the reference code made impossible.
 *   3. Hidden platform coupling — `System` is a JVM/Android dependency baked
 *      into what should be pure business logic.
 *
 * Every core function that needs the current time now takes a [Clock] (or a
 * pre-sampled epoch) as an explicit parameter. Production wires a
 * [SystemClock]; tests wire a [FixedClock]. The core itself stays pure.
 */
fun interface Clock {
    /** Milliseconds since the Unix epoch. */
    fun nowEpochMillis(): Long

    /** Convenience: whole seconds since the Unix epoch. */
    fun nowEpochSeconds(): Long = nowEpochMillis() / 1_000L
}

/** Deterministic clock for tests and replay. Advance it explicitly. */
class FixedClock(private var epochMillis: Long = 0L) : Clock {
    override fun nowEpochMillis(): Long = epochMillis
    fun advanceMillis(delta: Long) { epochMillis += delta }
    fun set(epochMillis: Long) { this.epochMillis = epochMillis }
}
