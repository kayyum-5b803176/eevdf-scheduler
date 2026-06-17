package com.eevdf.core.scheduler.model

/**
 * SCHED_FIFO / SCHED_RR configuration and window rules.
 *
 * Ported clean from the reference `RtScheduler` + `Task` RT getters. Kept: the
 * real midnight-crossing window logic and the activation/deactivation math.
 * Dropped: the `Calendar`/`System.currentTimeMillis()` coupling — the current
 * wall-clock day/second are passed in explicitly (see WallClock).
 */
data class RtConfig(
    val priority: Int = 50,
    val policy: Policy = Policy.RR,
    /** Bit 0 = Sunday … bit 6 = Saturday. */
    val activeDaysMask: Int = 0,
    val activationHour: Int = 0,
    val activationMinute: Int = 0,
    val activationSecond: Int = 0,
    val sliceTimeoutSeconds: Long = 0L,
) {
    enum class Policy { FIFO, RR }

    val isConfigured: Boolean get() = activeDaysMask != 0 && sliceTimeoutSeconds > 0L

    val activationSecondOfDay: Long
        get() = activationHour * 3_600L + activationMinute * 60L + activationSecond

    private fun dayActive(dayIndex: Int): Boolean = (activeDaysMask shr dayIndex) and 1 != 0

    /**
     * True when the current moment falls inside the activation window. Handles
     * windows that cross midnight by also honoring the previous day's bit.
     *
     * @param dayIndex   0 = Sunday … 6 = Saturday
     * @param secondOfDay 0 … 86399
     * @param prevDayIndex day index for the day before [dayIndex]
     */
    fun isWindowActive(dayIndex: Int, secondOfDay: Long, prevDayIndex: Int): Boolean {
        if (!isConfigured) return false
        val windowEnd = activationSecondOfDay + sliceTimeoutSeconds
        return if (windowEnd <= 86_400L) {
            dayActive(dayIndex) && secondOfDay >= activationSecondOfDay && secondOfDay < windowEnd
        } else {
            val overflow = windowEnd - 86_400L
            (dayActive(dayIndex) && secondOfDay >= activationSecondOfDay) ||
                (dayActive(prevDayIndex) && secondOfDay < overflow)
        }
    }

    /** Seconds remaining until the active window closes; 0 if not active. */
    fun secondsUntilClose(secondOfDay: Long): Long {
        val windowEnd = activationSecondOfDay + sliceTimeoutSeconds
        val remaining = if (windowEnd <= 86_400L) {
            windowEnd - secondOfDay
        } else if (secondOfDay >= activationSecondOfDay) {
            windowEnd - secondOfDay
        } else {
            (windowEnd - 86_400L) - secondOfDay
        }
        return remaining.coerceAtLeast(0L)
    }
}

/**
 * SCHED_DEADLINE budget. Ported from the reference `Task` DL getters; the only
 * change is that "now" is an explicit parameter instead of a hidden
 * `System.currentTimeMillis()` read (which made the budget non-deterministic).
 */
data class DlBudget(
    val runtimeSeconds: Long = 0L,
    val deadlineSeconds: Long = 0L,
    val periodSeconds: Long = 0L,
    val periodStartEpochSeconds: Long = 0L,
    val runtimeUsedSeconds: Long = 0L,
) {
    val isConfigured: Boolean get() = runtimeSeconds > 0L && deadlineSeconds > 0L

    /** period defaults to deadline when unset, matching Linux sched_period default. */
    val effectivePeriodSeconds: Long get() = if (periodSeconds > 0L) periodSeconds else deadlineSeconds

    fun isBudgetActiveAt(nowEpochSeconds: Long): Boolean {
        if (!isConfigured) return false
        if (periodStartEpochSeconds == 0L) return true            // never started → full budget
        val elapsed = nowEpochSeconds - periodStartEpochSeconds
        if (elapsed >= effectivePeriodSeconds) return true        // period elapsed → replenished
        return runtimeUsedSeconds < runtimeSeconds
    }
}

/**
 * Leaky-bucket quota. Ported from the reference `Task` quota getters with "now"
 * made explicit. The decayed-usage formula is unchanged.
 */
data class QuotaBudget(
    val quotaSeconds: Long = 0L,
    val periodSeconds: Long = 86_400L,
    val periodStartEpochSeconds: Long = 0L,
    val usedSeconds: Long = 0L,
) {
    val isEnabled: Boolean get() = quotaSeconds > 0L

    fun usedAt(nowEpochSeconds: Long): Long {
        if (!isEnabled || periodStartEpochSeconds == 0L) return usedSeconds.coerceAtLeast(0L)
        val elapsed = nowEpochSeconds - periodStartEpochSeconds
        val replenished = elapsed * quotaSeconds / periodSeconds
        return (usedSeconds - replenished).coerceAtLeast(0L)
    }

    fun isExceededAt(nowEpochSeconds: Long): Boolean = isEnabled && usedAt(nowEpochSeconds) >= quotaSeconds
    fun remainingAt(nowEpochSeconds: Long): Long =
        if (!isEnabled) -1L else (quotaSeconds - usedAt(nowEpochSeconds)).coerceAtLeast(0L)
}
