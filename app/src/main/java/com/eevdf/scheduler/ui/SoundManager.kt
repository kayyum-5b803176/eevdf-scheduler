package com.eevdf.scheduler.ui

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper

/**
 * Centralised alarm-sound manager.
 *
 * Features:
 *  - Custom sound URI (selected via RingtoneManager picker in Settings).
 *    Falls back to system alarm tone if none is saved.
 *  - Sound timeout: auto-stops after user-configured seconds (0 = no auto-stop).
 *    Uses a simple Handler post so it works inside a foreground Service.
 *  - Default volume: rings at a fixed level (0-100 maps to 0.0-1.0 MediaPlayer gain).
 *    On stop, the stream volume is restored to whatever the user had it set to.
 *  - Gradual volume increase: starts at 0 and ramps to the target volume linearly
 *    over the user-configured fade-in duration (0 = start at full volume immediately).
 */
object SoundManager {

    // ── Prefs keys ────────────────────────────────────────────────────────────

    const val KEY_SOUND_URI        = "sound_uri"          // String | null = system default
    const val KEY_SOUND_TIMEOUT    = "sound_timeout_sec"  // Int, 0 = no timeout
    const val KEY_SOUND_VOLUME     = "sound_volume"       // Int 0-100
    const val KEY_SOUND_FADE_IN    = "sound_fade_in_sec"  // Int 0-300 (5 min)

    const val DEFAULT_SOUND_TIMEOUT = 60    // 1 minute
    const val DEFAULT_SOUND_VOLUME  = 80    // 80 %
    const val DEFAULT_FADE_IN       = 0     // immediate

    // ── Internal state ────────────────────────────────────────────────────────

    private var player: MediaPlayer? = null
    @Volatile private var isPlaying  = false

    private val handler = Handler(Looper.getMainLooper())
    private var stopRunnable: Runnable? = null
    private var fadeRunnable: Runnable? = null

    private var savedStreamVolume: Int = -1   // -1 = not saved
    private var targetVolume: Float   = 1f
    private var fadeDurationMs: Long  = 0L

    // ── Profile key helpers ───────────────────────────────────────────────────

    /** Returns the prefs-key prefix for the given task type string. */
    fun prefixFor(taskType: String): String = when (taskType) {
        "NOTIFICATION" -> "notif_"
        "ALARM"        -> "alarm_"
        "CUSTOM"       -> "custom_"
        else           -> ""   // "DEFAULT" — uses legacy/global keys
    }

    fun soundUriKey(prefix: String)     = "${prefix}${KEY_SOUND_URI}"
    fun soundTimeoutKey(prefix: String) = "${prefix}${KEY_SOUND_TIMEOUT}"
    fun soundVolumeKey(prefix: String)  = "${prefix}${KEY_SOUND_VOLUME}"
    fun soundFadeInKey(prefix: String)  = "${prefix}${KEY_SOUND_FADE_IN}"

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Start alarm sound for the given task type (reads the matching profile prefs).
     * Falls through to the default profile when a per-profile key has no value saved yet.
     */
    fun startAlarmForType(context: Context, prefs: SharedPreferences, taskType: String) {
        val prefix = prefixFor(taskType)
        startAlarmWithPrefix(context, prefs, prefix)
    }

    /**
     * Start alarm sound using settings from [prefs] (uses default profile keys).
     * Safe to call multiple times — stops any previous playback first.
     */
    fun startAlarm(context: Context, prefs: SharedPreferences) {
        startAlarmWithPrefix(context, prefs, "")
    }

    private fun startAlarmWithPrefix(context: Context, prefs: SharedPreferences, prefix: String) {
        stop(context)

        // Read profile-specific prefs, fall back to the default (no-prefix) value if unset
        val uriStr     = prefs.getString(soundUriKey(prefix), null)
            ?: if (prefix.isNotEmpty()) prefs.getString(KEY_SOUND_URI, null) else null
        val timeoutSec = prefs.getInt(soundTimeoutKey(prefix), -1)
            .let { if (it == -1) prefs.getInt(KEY_SOUND_TIMEOUT, DEFAULT_SOUND_TIMEOUT) else it }
        val volumePct  = run {
            val v = prefs.getInt(soundVolumeKey(prefix), -1)
            if (v == -1) prefs.getInt(KEY_SOUND_VOLUME, DEFAULT_SOUND_VOLUME) else v
        }.coerceIn(0, 100)
        val fadeInSec  = run {
            val f = prefs.getInt(soundFadeInKey(prefix), -1)
            if (f == -1) prefs.getInt(KEY_SOUND_FADE_IN, DEFAULT_FADE_IN) else f
        }.coerceIn(0, 300)

        targetVolume   = volumePct / 100f
        fadeDurationMs = fadeInSec * 1000L

        // Resolve URI
        val uri: Uri = when {
            !uriStr.isNullOrBlank() -> Uri.parse(uriStr)
            else -> RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        }

        // Save & set stream volume
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        savedStreamVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val targetStreamVol = (maxVol * targetVolume).toInt().coerceIn(0, maxVol)
        if (fadeDurationMs == 0L) {
            // Set immediately
            am.setStreamVolume(AudioManager.STREAM_ALARM, targetStreamVol, 0)
        }
        // else: volume set progressively during fade

        try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context, uri)
                isLooping = true
                // Start volume: 0.0 if fading in, else targetVolume
                val startVol = if (fadeDurationMs > 0) 0f else targetVolume
                setVolume(startVol, startVol)
                prepare()
                start()
            }
            isPlaying = true

            // Schedule gradual fade-in
            if (fadeDurationMs > 0) scheduleFadeIn(context)

            // Schedule auto-stop
            if (timeoutSec > 0) {
                val r = Runnable { stop(context) }
                stopRunnable = r
                handler.postDelayed(r, timeoutSec * 1000L)
            }
        } catch (e: Exception) {
            isPlaying = false
            restoreVolume(context)
        }
    }

    /** Stop playback immediately and restore the user's original alarm volume. */
    fun stop(context: Context) {
        isPlaying = false
        cancelPendingCallbacks()
        player?.let {
            try { if (it.isPlaying) it.stop() } catch (_: Exception) {}
            try { it.release()               } catch (_: Exception) {}
        }
        player = null
        restoreVolume(context)
    }

    val isActive: Boolean get() = isPlaying

    // ── Private ───────────────────────────────────────────────────────────────

    /**
     * Linearly ramps MediaPlayer volume from 0 to [targetVolume] over [fadeDurationMs].
     * Uses 20 steps (50 ms each at minimum). Also gradually raises the stream volume so
     * the user perceives the increase even at max stream level.
     */
    private fun scheduleFadeIn(context: Context) {
        val steps      = 40
        val stepMs     = (fadeDurationMs / steps).coerceAtLeast(100L)
        val am         = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol     = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)

        var step = 0
        val r = object : Runnable {
            override fun run() {
                if (!isPlaying || step >= steps) return
                step++
                val fraction = step.toFloat() / steps
                val vol      = targetVolume * fraction
                player?.setVolume(vol, vol)
                // Also ramp stream volume
                val streamVol = (maxVol * targetVolume * fraction).toInt().coerceIn(0, maxVol)
                am.setStreamVolume(AudioManager.STREAM_ALARM, streamVol, 0)
                if (step < steps) handler.postDelayed(this, stepMs)
            }
        }
        fadeRunnable = r
        handler.postDelayed(r, stepMs)
    }

    private fun cancelPendingCallbacks() {
        stopRunnable?.let { handler.removeCallbacks(it) }
        fadeRunnable?.let { handler.removeCallbacks(it) }
        stopRunnable = null
        fadeRunnable = null
    }

    private fun restoreVolume(context: Context) {
        if (savedStreamVolume < 0) return
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_ALARM, savedStreamVolume, 0)
        } catch (_: Exception) {}
        savedStreamVolume = -1
    }
}
