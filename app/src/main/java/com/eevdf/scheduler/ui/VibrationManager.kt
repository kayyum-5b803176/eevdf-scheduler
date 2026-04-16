package com.eevdf.scheduler.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Centralised vibration helper.
 *
 * Patterns are defined as arrays of [off, on, off, on, …] milliseconds (standard Android format).
 * Index 0 = delay before first pulse (always 0 here so it starts immediately).
 */
object VibrationManager {

    // ── 7 named patterns ──────────────────────────────────────────────────────

    data class VibPattern(val id: Int, val name: String, val pattern: LongArray)

    val PATTERNS = listOf(
        VibPattern(0, "Single Pulse",      longArrayOf(0, 500)),
        VibPattern(1, "Double Tap",        longArrayOf(0, 100, 100, 100)),
        VibPattern(2, "Triple Tap",        longArrayOf(0, 80, 80, 80, 80, 80)),
        VibPattern(3, "Slow Pulse",        longArrayOf(0, 800, 400, 800, 400, 800)),
        VibPattern(4, "SOS",               longArrayOf(0, 150, 75, 150, 75, 150, 300, 400, 300, 400, 300, 300, 150, 75, 150, 75, 150)),
        VibPattern(5, "Heartbeat",         longArrayOf(0, 200, 100, 100, 500, 200, 100, 100, 500)),
        VibPattern(6, "Rapid Burst",       longArrayOf(0, 50, 50, 50, 50, 50, 50, 50, 50, 50, 50, 300))
    )

    // ── Prefs keys ────────────────────────────────────────────────────────────

    const val KEY_PATTERN       = "vib_pattern_id"
    const val KEY_TIMEOUT_SEC   = "vib_timeout_sec"   // 0 = no timeout
    const val KEY_HAPTIC        = "vib_haptic_enabled"

    const val DEFAULT_PATTERN     = 0
    const val DEFAULT_TIMEOUT_SEC = 60   // 1 minute
    const val DEFAULT_HAPTIC      = true

    // ── Profile key helpers ───────────────────────────────────────────────────

    fun vibPatternKey(prefix: String)  = "${prefix}${KEY_PATTERN}"
    fun vibTimeoutKey(prefix: String)  = "${prefix}${KEY_TIMEOUT_SEC}"
    fun vibHapticKey(prefix: String)   = "${prefix}${KEY_HAPTIC}"

    // ── Internal state ────────────────────────────────────────────────────────

    private var stopTimeMs: Long = Long.MAX_VALUE
    @Volatile private var vibrating = false

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Start vibration for the given task type (reads the matching profile prefs).
     */
    fun startAlarmForType(context: Context, prefs: android.content.SharedPreferences, taskType: String) {
        val prefix = SoundManager.prefixFor(taskType)
        startAlarmWithPrefix(context, prefs, prefix)
    }

    /**
     * Start the selected vibration pattern, repeating until [stopAfterMs] elapses or
     * [stop] is called. Pass [stopAfterMs] = 0 to respect the saved timeout.
     */
    fun startAlarm(context: Context, prefs: android.content.SharedPreferences) {
        startAlarmWithPrefix(context, prefs, "")
    }

    private fun startAlarmWithPrefix(context: Context, prefs: android.content.SharedPreferences, prefix: String) {
        val patternId  = run {
            val p = prefs.getInt(vibPatternKey(prefix), -1)
            if (p == -1) prefs.getInt(KEY_PATTERN, DEFAULT_PATTERN) else p
        }
        val timeoutSec = run {
            val t = prefs.getInt(vibTimeoutKey(prefix), -1)
            if (t == -1) prefs.getInt(KEY_TIMEOUT_SEC, DEFAULT_TIMEOUT_SEC) else t
        }
        val pattern     = PATTERNS.getOrNull(patternId)?.pattern ?: PATTERNS[0].pattern
        val timeoutMs   = if (timeoutSec == 0) Long.MAX_VALUE else timeoutSec * 1000L

        val vib = getVibrator(context)
        stopTimeMs = System.currentTimeMillis() + timeoutMs
        vibrating  = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createWaveform(pattern, 0))  // repeat from index 0
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(pattern, 0)
        }

        // Auto-stop after timeout
        if (timeoutSec > 0) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (vibrating && System.currentTimeMillis() >= stopTimeMs) stop(context)
            }, timeoutMs)
        }
    }

    /** Stop vibration immediately. */
    fun stop(context: Context) {
        vibrating = false
        stopTimeMs = Long.MAX_VALUE
        getVibrator(context).cancel()
    }

    /** Preview: play the pattern once (no repeat). */
    fun preview(context: Context, patternId: Int) {
        val pattern = PATTERNS.getOrNull(patternId)?.pattern ?: PATTERNS[0].pattern
        val vib = getVibrator(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createWaveform(pattern, -1))  // -1 = no repeat
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(pattern, -1)
        }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun getVibrator(context: Context): Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
                .defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
}
