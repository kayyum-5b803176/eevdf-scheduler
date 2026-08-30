package com.eevdf.feature.task

/**
 * Task type constants and pure utility functions shared across sections.
 *
 * Setup, populate, and notice-field wiring moved to [AddTaskConfigSection].
 *
 * Domain:
 *   • [taskTypeLabels] / [taskTypeValues]      — task profile dropdown entries
 *   • [resumeTypeLabels] / [resumeTypeValues]  — resume type dropdown entries
 *   • [parseDelayInput]                        — mm-ss → seconds (pure)
 *   • [formatDelaySecs]                        — seconds → readable string (pure)
 */

internal val taskTypeLabels = listOf("Default", "Notice", "Alert", "Custom")
internal val taskTypeValues = listOf("DEFAULT", "NOTIFICATION", "ALARM", "CUSTOM")

internal val resumeTypeLabels = listOf("Middle", "Initial")
internal val resumeTypeValues  = listOf("MIDDLE", "INITIAL")

/**
 * Parses mm-ss format (e.g. "01-30") into total seconds.
 * Also accepts plain seconds. Result is clamped to [0, 300].
 */
internal fun parseDelayInput(raw: String): Long {
    val trimmed = raw.trim()
    return if (trimmed.contains('-')) {
        val parts = trimmed.split('-')
        val mm = parts.getOrNull(0)?.toLongOrNull() ?: 0L
        val ss = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        (mm * 60 + ss).coerceIn(0, 300)
    } else {
        trimmed.toLongOrNull()?.coerceIn(0, 300) ?: 0L
    }
}

internal fun formatDelaySecs(secs: Long): String = when {
    secs == 0L      -> "0s (no delay)"
    secs < 60       -> "${secs}s"
    secs % 60 == 0L -> "${secs / 60} min"
    else            -> "${secs / 60}m ${secs % 60}s"
}
