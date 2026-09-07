package com.eevdf.capabilities.vibration

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.eevdf.capabilities.settingsstorage.state.SoundPrefs
import com.eevdf.capabilities.settingsstorage.state.VibrationPrefs

/**
 * Moved from the old `feedback-cues` capability (deleted — see
 * `scripts/delete-feedback-cues.sh`). Behavior is unchanged; only the prefs
 * key constants and pattern list (`KEY_*`, `DEFAULT_*`, `PATTERNS`, the
 * `*Key()` helpers) moved out to [VibrationPrefs] in settings-storage, since
 * settings-screens needs those same names without needing this capability's
 * playback behavior. [SoundPrefs.prefixFor] (not duplicated here) is the one
 * constant genuinely shared with the `sound` capability's own prefs.
 *
 * Reached ONLY via the bus now — see `VibrationCueHandler`. Nothing calls
 * this object directly from outside this capability.
 */
object VibrationManager {

    // ── Internal state ────────────────────────────────────────────────────────
    private var stopTimeMs: Long = Long.MAX_VALUE
    @Volatile private var vibrating = false

    // ── Public API ────────────────────────────────────────────────────────────

    /** Start vibration for the given task type (reads the matching profile prefs). */
    fun startAlarmForType(context: Context, prefs: SharedPreferences, taskType: String) {
        startAlarmWithPrefix(context, prefs, SoundPrefs.prefixFor(taskType))
    }

    /** Start the selected vibration pattern, repeating until timeout or [stop]. */
    fun startAlarm(context: Context, prefs: SharedPreferences) {
        startAlarmWithPrefix(context, prefs, "")
    }

    private fun startAlarmWithPrefix(context: Context, prefs: SharedPreferences, prefix: String) {
        val patternId  = run {
            val p = prefs.getInt(VibrationPrefs.vibPatternKey(prefix), -1)
            if (p == -1) prefs.getInt(VibrationPrefs.KEY_PATTERN, VibrationPrefs.DEFAULT_PATTERN) else p
        }
        val timeoutSec = run {
            val t = prefs.getInt(VibrationPrefs.vibTimeoutKey(prefix), -1)
            if (t == -1) prefs.getInt(VibrationPrefs.KEY_TIMEOUT_SEC, VibrationPrefs.DEFAULT_TIMEOUT_SEC) else t
        }
        val pattern     = VibrationPrefs.PATTERNS.getOrNull(patternId)?.pattern ?: VibrationPrefs.PATTERNS[0].pattern
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
            Handler(Looper.getMainLooper()).postDelayed({
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
        val pattern = VibrationPrefs.PATTERNS.getOrNull(patternId)?.pattern ?: VibrationPrefs.PATTERNS[0].pattern
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
