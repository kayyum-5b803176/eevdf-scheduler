package com.eevdf.capabilities.settingsstorage.state

/**
 * Pure SharedPreferences key constants, the named-pattern list, and
 * key-naming helpers for vibration — no playback behavior lives here. See
 * [SoundPrefs]'s KDoc for why this split exists (same reasoning, mirrored).
 */
object VibrationPrefs {

    // ── 7 named patterns ──────────────────────────────────────────────────────
    //
    // Patterns are [off, on, off, on, …] milliseconds (standard Android
    // format). Index 0 = delay before first pulse (always 0 here so it
    // starts immediately). This list is read by settings-screens (to label
    // the pattern picker) and by `capabilities/vibration` (to actually
    // vibrate) — neither owns it exclusively, so it lives here.

    data class VibPattern(val id: Int, val name: String, val pattern: LongArray)

    val PATTERNS = listOf(
        VibPattern(0, "Single Pulse", longArrayOf(0, 500)),
        VibPattern(1, "Double Tap",   longArrayOf(0, 100, 100, 100)),
        VibPattern(2, "Triple Tap",   longArrayOf(0, 80, 80, 80, 80, 80)),
        VibPattern(3, "Slow Pulse",   longArrayOf(0, 800, 400, 800, 400, 800)),
        VibPattern(4, "SOS",          longArrayOf(0, 150, 75, 150, 75, 150, 300, 400, 300, 400, 300, 300, 150, 75, 150, 75, 150)),
        VibPattern(5, "Heartbeat",    longArrayOf(0, 200, 100, 100, 500, 200, 100, 100, 500)),
        VibPattern(6, "Rapid Burst",  longArrayOf(0, 50, 50, 50, 50, 50, 50, 50, 50, 50, 50, 300)),
    )

    // ── Prefs keys ────────────────────────────────────────────────────────────
    const val KEY_PATTERN     = "vib_pattern_id"
    const val KEY_TIMEOUT_SEC = "vib_timeout_sec"   // 0 = no timeout
    const val KEY_HAPTIC      = "vib_haptic_enabled"

    const val DEFAULT_PATTERN     = 0
    const val DEFAULT_TIMEOUT_SEC = 60   // 1 minute
    const val DEFAULT_HAPTIC      = true

    // ── Profile key helpers ───────────────────────────────────────────────────
    fun vibPatternKey(prefix: String) = "${prefix}${KEY_PATTERN}"
    fun vibTimeoutKey(prefix: String) = "${prefix}${KEY_TIMEOUT_SEC}"
    fun vibHapticKey(prefix: String)  = "${prefix}${KEY_HAPTIC}"
}
