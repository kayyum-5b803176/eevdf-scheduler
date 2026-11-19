package com.eevdf.capabilities.timercard

/**
 * The card's clock: how a number of seconds is written, and how a countdown / count-up is read
 * off an instant. Pure.
 */
object CardClock {

    /** "05:09" under an hour, "1:02:03" from an hour up — what the timer card has always shown. */
    fun format(seconds: Long): String {
        val s = seconds.coerceAtLeast(0L)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%02d:%02d", m, sec)
    }

    /** "0:05", "1:23", "1:02:34" — the expired card's overrun counter, same style as Google Clock. */
    fun formatElapsed(seconds: Long): String {
        val s = seconds.coerceAtLeast(0L)
        val m = s / 60
        val sec = s % 60
        return if (m >= 60) "${m / 60}:${"%02d".format(m % 60)}:${"%02d".format(sec)}" else "$m:${"%02d".format(sec)}"
    }

    /** Whole seconds left until [endEpochMs], rounded up so "00:00" is only shown at the very end; never negative. */
    fun remainingSeconds(endEpochMs: Long, nowMs: Long): Long {
        val ms = endEpochMs - nowMs
        return if (ms <= 0L) 0L else (ms + 999L) / 1000L
    }

    /** Whole seconds since [sinceEpochMs]; never negative. */
    fun elapsedSeconds(sinceEpochMs: Long, nowMs: Long): Long = ((nowMs - sinceEpochMs) / 1000L).coerceAtLeast(0L)
}
