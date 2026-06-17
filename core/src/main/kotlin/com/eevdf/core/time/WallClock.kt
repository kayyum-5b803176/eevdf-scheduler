package com.eevdf.core.time

import java.time.Instant
import java.time.ZoneId

/**
 * Pure conversion from an epoch instant to local wall-clock fields.
 *
 * The reference RT logic called `Calendar.getInstance()` directly inside the
 * "core" scheduler — a hidden dependency on the device default timezone and a
 * non-deterministic read. Here the conversion is an explicit pure function: the
 * platform layer supplies the epoch (via [Clock]) and the [ZoneId]; the core
 * RT rules operate only on the resulting [LocalWallTime].
 *
 * Uses java.time (JDK, not Android) so it is valid in a pure Kotlin/JVM core.
 * The app's minSdk 26 supports java.time natively.
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
