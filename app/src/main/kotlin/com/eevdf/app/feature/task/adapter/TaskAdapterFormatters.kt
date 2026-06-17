package com.eevdf.app.feature.task.adapter

/**
 * Stateless formatting helpers shared by TaskAdapter and its extension files.
 *
 * All functions are package-level so any file in the adapter package can call
 * them directly without an instance. Domain:
 *   • [formatTRT]         — full multi-unit duration string
 *   • [formatQuota]       — compact quota/DL duration (max 2 units)
 *   • [formatDlDuration]  — alias for formatQuota used on DL/RT badges
 *   • [siFloat]           — SI float with 2 d.p. (VRT/VDL columns)
 *   • [siInt]             — SI integer, no trailing ".0" (Runs column)
 *   • [siDur]             — SI duration, top-2 non-zero units (TRT column)
 *
 * The three adapter-state-aware wrappers [fmtFloat], [fmtInt], [fmtDur] that
 * branch on [TaskAdapter.unitFormatEnabled] live in TaskAdapterUnitFormat.kt.
 */

/**
 * Format a duration in seconds as a compact real-time string, showing only
 * the two most significant non-zero units down to seconds.
 */
internal fun formatTRT(totalSec: Long): String {
    if (totalSec <= 0L) return "0s"
    var rem = totalSec
    val years   = rem / 31_536_000L; rem %= 31_536_000L
    val months  = rem /  2_592_000L; rem %=  2_592_000L
    val days    = rem /     86_400L; rem %=     86_400L
    val hours   = rem /      3_600L; rem %=      3_600L
    val minutes = rem /         60L
    val seconds = rem %         60L
    val parts = buildList {
        if (years   > 0) add("${years}y")
        if (months  > 0) add("${months}mo")
        if (days    > 0) add("${days}d")
        if (hours   > 0) add("${hours}h")
        if (minutes > 0) add("${minutes}m")
        if (seconds > 0) add("${seconds}s")
    }
    return parts.joinToString(" ")
}

/** Format a quota or DL/RT budget duration (days → seconds, max 2 units). */
internal fun formatQuota(totalSec: Long): String {
    if (totalSec <= 0L) return "0s"
    var rem = totalSec
    val days    = rem / 86_400L; rem %= 86_400L
    val hours   = rem /  3_600L; rem %=  3_600L
    val minutes = rem /     60L
    val seconds = rem %     60L
    val parts = buildList {
        if (days    > 0) add("${days}d")
        if (hours   > 0) add("${hours}h")
        if (minutes > 0) add("${minutes}m")
        if (seconds > 0) add("${seconds}s")
    }
    return parts.take(2).joinToString(" ")
}

/** Alias for [formatQuota] used on DL/RT countdown badges. */
internal fun formatDlDuration(totalSec: Long): String = formatQuota(totalSec)

// ── SI format helpers ─────────────────────────────────────────────────────────

/**
 * SI float — always 2 decimal places in the suffix.
 *   88191.0  → "88.19K"   1900.21 → "1.90K"   12.5 → "12.50"
 */
internal fun siFloat(v: Double): String = when {
    v >= 1_000_000_000.0 -> "${"%.2f".format(v / 1_000_000_000.0)}G"
    v >= 1_000_000.0     -> "${"%.2f".format(v / 1_000_000.0)}M"
    v >= 1_000.0         -> "${"%.2f".format(v / 1_000.0)}K"
    else                 -> "%.2f".format(v)
}

/**
 * SI integer — no ".00" on small values; trailing ".0" stripped on suffix.
 *   18    → "18"    1800  → "1.8K"   15000 → "15K"   1_234_567 → "1.2M"
 */
internal fun siInt(v: Int): String {
    if (v < 1_000) return v.toString()
    val (scaled, suffix) = when {
        v >= 1_000_000_000 -> v / 1_000_000_000.0 to "G"
        v >= 1_000_000     -> v / 1_000_000.0     to "M"
        else               -> v / 1_000.0         to "K"
    }
    return "${("%.1f".format(scaled)).trimEnd('0').trimEnd('.')}$suffix"
}

/**
 * SI duration — top 2 most-significant non-zero units.
 *   3d 28h 3s → "3d 28h"     4y 6h 5s → "4y 6h"     45s → "45s"
 */
internal fun siDur(totalSec: Long): String {
    if (totalSec <= 0L) return "0s"
    var rem = totalSec
    val years   = rem / 31_536_000L; rem %= 31_536_000L
    val months  = rem /  2_592_000L; rem %=  2_592_000L
    val days    = rem /     86_400L; rem %=     86_400L
    val hours   = rem /      3_600L; rem %=      3_600L
    val minutes = rem /         60L
    val seconds = rem %         60L
    val parts = buildList {
        if (years   > 0) add("${years}y")
        if (months  > 0) add("${months}mo")
        if (days    > 0) add("${days}d")
        if (hours   > 0) add("${hours}h")
        if (minutes > 0) add("${minutes}m")
        if (seconds > 0) add("${seconds}s")
    }
    return parts.take(2).joinToString(" ")
}
