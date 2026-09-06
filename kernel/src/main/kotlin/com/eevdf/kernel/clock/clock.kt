package com.eevdf.kernel.clock

import java.time.Instant
import java.time.ZoneId

/**
 * The single source of "now" for every capability.
 *
 * This is the one shared clock primitive kernel rule 1 reserves a permanent
 * home for. Every capability that needs the current time takes a [Clock] (or
 * a pre-sampled epoch) as an explicit parameter — never `System
 * .currentTimeMillis()` inline — so scheduling/timer/alarm decisions stay
 * deterministic and testable: production wires [SystemClock], tests wire
 * [FixedClock].
 *
 * (Migrated unchanged in behavior from `core/time/Clock.kt` — see Phase -1
 * audit's kernel/clock/ mapping.)
 */
fun interface Clock {
    /** Milliseconds since the Unix epoch. */
    fun nowEpochMillis(): Long

    /** Convenience: whole seconds since the Unix epoch. */
    fun nowEpochSeconds(): Long = nowEpochMillis() / 1_000L
}

/** Production clock: the real wall-clock time. */
class SystemClock : Clock {
    override fun nowEpochMillis(): Long = System.currentTimeMillis()
}

/** Deterministic clock for tests and replay. Advance it explicitly. */
class FixedClock(private var epochMillis: Long = 0L) : Clock {
    override fun nowEpochMillis(): Long = epochMillis
    fun advanceMillis(delta: Long) { epochMillis += delta }
    fun set(epochMillis: Long) { this.epochMillis = epochMillis }
}

/**
 * Pure conversion from an epoch instant to local wall-clock fields.
 *
 * Uses java.time (JDK, not Android) so it stays valid in a pure Kotlin/JVM
 * kernel, exactly as it did in `:core`. (Migrated unchanged from
 * `core/time/WallClock.kt`.)
 */
object WallClock {

    /** @param dayOfWeekIndex 0 = Sunday … 6 = Saturday (matches the RT day bitmask). */
    data class LocalWallTime(val dayOfWeekIndex: Int, val secondOfDay: Long)

    fun localize(epochMillis: Long, zone: ZoneId): LocalWallTime {
        val zdt = Instant.ofEpochMilli(epochMillis).atZone(zone)
        // java.time: MONDAY=1 … SUNDAY=7. RT bitmask wants SUNDAY=0 … SATURDAY=6.
        val dayIndex = zdt.dayOfWeek.value % 7
        val secondOfDay = zdt.toLocalTime().toSecondOfDay().toLong()
        return LocalWallTime(dayIndex, secondOfDay)
    }

    /** Day index 0..6 for the day before [dayOfWeekIndex] (used for midnight-crossing windows). */
    fun previousDayIndex(dayOfWeekIndex: Int): Int = (dayOfWeekIndex + 6) % 7
}
