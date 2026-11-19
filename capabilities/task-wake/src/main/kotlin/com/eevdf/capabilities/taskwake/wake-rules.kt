package com.eevdf.capabilities.taskwake

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Pure rules of the scheduled wake — no Android, no clock of its own, no database.
 *
 *  • which class outranks which ([outranks]),
 *  • what to do with a wake that is due ([decide]),
 *  • turning the prompt's six numbers into a time ([alarmTarget], [timerTarget]),
 *  • the text of the state popup ([formatRemaining], [formatTarget]).
 */
object WakeRules {

    // ── Class comparison ──────────────────────────────────────────────────────

    /** Higher outranks lower: stop > deadline > realtime > fair > idle. Unknown counts as fair. */
    fun classRank(schedulerClass: String): Int = when (schedulerClass) {
        "stop_sched_class"  -> 4
        "dl_sched_class"    -> 3
        "rt_sched_class"    -> 2
        "fair_sched_class"  -> 1
        "idle_sched_class"  -> 0
        else                -> 1
    }

    /**
     * Does the waking task outrank the current one? A strictly higher class does.
     * Inside the realtime class the higher `rtPriority` does (Linux sched_priority).
     * Equal fair / idle / deadline tasks never preempt each other.
     */
    fun outranks(wakeClass: String, wakeRtPriority: Int, currentClass: String, currentRtPriority: Int): Boolean {
        val w = classRank(wakeClass)
        val c = classRank(currentClass)
        return when {
            w != c                       -> w > c
            wakeClass == "rt_sched_class" -> wakeRtPriority > currentRtPriority
            else                         -> false
        }
    }

    // ── What a due wake does ──────────────────────────────────────────────────

    enum class Decision {
        /** The wake task is already the current task: nothing to ask, the wake is simply cleared. */
        CLEAR_SELF,
        /** Show the preempt card (Preempt when something is current, Select when nothing is). */
        SHOW_CARD,
        /** The current task is of equal or higher class: keep waiting, look again when it changes. */
        BLOCKED,
    }

    /**
     * [hasCurrent] — a task is in the timer card, running OR paused (it still blocks).
     * [currentIsWakeTask] — that task is the one waking.
     * [outranksCurrent] — see [outranks]; ignored when nothing is current.
     */
    fun decide(hasCurrent: Boolean, currentIsWakeTask: Boolean, outranksCurrent: Boolean): Decision = when {
        hasCurrent && currentIsWakeTask -> Decision.CLEAR_SELF
        !hasCurrent                     -> Decision.SHOW_CARD
        outranksCurrent                 -> Decision.SHOW_CARD
        else                            -> Decision.BLOCKED
    }

    /** A wake whose time passed this long ago is labelled "missed" (the phone was off, the app closed…). */
    const val MISSED_AFTER_MS = 120_000L

    fun isMissed(targetEpochMs: Long, nowMs: Long): Boolean = nowMs - targetEpochMs > MISSED_AFTER_MS

    // ── Prompt numbers → time ─────────────────────────────────────────────────

    /** The prompt's boxes. ALARM: a calendar date and time. TIMER: the same boxes as a duration. */
    data class Fields(val day: Int, val month: Int, val year: Int, val hour: Int, val minute: Int, val second: Int)

    /**
     * ALARM mode: the instant of that date and time in [zone], or null when the date
     * does not exist (31 Feb), a box is out of range, or it is not in the future.
     */
    fun alarmTarget(f: Fields, nowMs: Long, zone: TimeZone = TimeZone.getDefault()): Long? {
        if (f.year !in 2000..2200 || f.month !in 1..12 || f.day !in 1..31 ||
            f.hour !in 0..23 || f.minute !in 0..59 || f.second !in 0..59) return null
        val cal = Calendar.getInstance(zone).apply {
            isLenient = false
            clear()
            set(f.year, f.month - 1, f.day, f.hour, f.minute, f.second)
        }
        val t = try { cal.timeInMillis } catch (e: IllegalArgumentException) { return null }
        return t.takeIf { it > nowMs }
    }

    /**
     * TIMER mode: [nowMs] plus years, months, days (calendar arithmetic, so "1 month"
     * is a real month) plus hours, minutes, seconds. Null when every box is zero,
     * one is negative or absurdly large, or the result would not be in the future.
     */
    fun timerTarget(f: Fields, nowMs: Long, zone: TimeZone = TimeZone.getDefault()): Long? {
        val all = listOf(f.year, f.month, f.day, f.hour, f.minute, f.second)
        if (all.any { it < 0 } || all.all { it == 0 }) return null
        if (f.year > 100 || f.month > 1_200 || f.day > 36_500 || f.hour > 876_000 || f.minute > 52_560_000 || f.second > 2_000_000_000) return null
        val cal = Calendar.getInstance(zone).apply {
            timeInMillis = nowMs
            add(Calendar.YEAR, f.year)
            add(Calendar.MONTH, f.month)
            add(Calendar.DAY_OF_MONTH, f.day)
            add(Calendar.HOUR_OF_DAY, f.hour)
            add(Calendar.MINUTE, f.minute)
            add(Calendar.SECOND, f.second)
        }
        return cal.timeInMillis.takeIf { it > nowMs }
    }

    /** [targetMs] broken into the prompt's six boxes in [zone] — used to prefill an existing alarm. */
    fun fieldsOf(targetMs: Long, zone: TimeZone = TimeZone.getDefault()): Fields {
        val c = Calendar.getInstance(zone).apply { timeInMillis = targetMs }
        return Fields(
            day = c.get(Calendar.DAY_OF_MONTH), month = c.get(Calendar.MONTH) + 1, year = c.get(Calendar.YEAR),
            hour = c.get(Calendar.HOUR_OF_DAY), minute = c.get(Calendar.MINUTE), second = c.get(Calendar.SECOND),
        )
    }

    // ── Text ──────────────────────────────────────────────────────────────────

    /** "2d 11:00:03" for two days and a bit, "00:05:09" under a day, "now" when it is not in the future. */
    fun formatRemaining(ms: Long): String {
        if (ms <= 0L) return "now"
        val totalSeconds = ms / 1_000L
        val days = totalSeconds / 86_400L
        val h = (totalSeconds % 86_400L) / 3_600L
        val m = (totalSeconds % 3_600L) / 60L
        val s = totalSeconds % 60L
        val clock = String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
        return if (days > 0) "${days}d $clock" else clock
    }

    /** "Fri 21 Nov 2026 14:30:00" */
    fun formatTarget(ms: Long, zone: TimeZone = TimeZone.getDefault(), locale: Locale = Locale.getDefault()): String =
        SimpleDateFormat("EEE d MMM yyyy HH:mm:ss", locale).apply { timeZone = zone }.format(Date(ms))
}
