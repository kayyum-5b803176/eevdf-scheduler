package com.eevdf.capabilities.sound

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.eevdf.capabilities.settingsstorage.state.SoundPrefs

/**
 * Moved from the old `feedback-cues` capability (deleted — see
 * `scripts/delete-feedback-cues.sh`). Behavior is unchanged; only the prefs
 * key constants (`KEY_*`, `DEFAULT_*`, `prefixFor`, the `*Key()` helpers)
 * moved out to [SoundPrefs] in settings-storage, since settings-screens needs
 * those same names without needing this capability's playback behavior.
 *
 * Reached ONLY via the bus now — see [SoundCueHandler]. Nothing calls this
 * object directly from outside this capability.
 */
object SoundManager {

    // ── Internal alarm state ──────────────────────────────────────────────────
    private var player: MediaPlayer? = null
    @Volatile private var isPlaying = false
    private val handler = Handler(Looper.getMainLooper())
    private var stopRunnable: Runnable? = null
    private var fadeRunnable: Runnable? = null
    private var savedAlarmStreamVol: Int = -1
    private var targetVolume: Float = 1f
    private var fadeDurationMs: Long = 0L

    // ── Action sound internal state ───────────────────────────────────────────
    private var actionPlayer: MediaPlayer? = null
    private var savedNotifStreamVol: Int = -1

    // ── Alarm public API ──────────────────────────────────────────────────────
    fun startAlarmForType(context: Context, prefs: SharedPreferences, taskType: String) =
        startAlarmWithPrefix(context, prefs, SoundPrefs.prefixFor(taskType))

    fun startAlarm(context: Context, prefs: SharedPreferences) =
        startAlarmWithPrefix(context, prefs, "")

    fun stop(context: Context) {
        isPlaying = false
        cancelPendingCallbacks()
        player?.let {
            try { if (it.isPlaying) it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        player = null
        restoreAlarmVolume(context)
    }

    val isActive: Boolean get() = isPlaying

    // ── Action sound API (Notice only) ────────────────────────────────────────
    fun playExecuteSound(context: Context, prefs: SharedPreferences) =
        playActionSound(context, prefs.getString(SoundPrefs.KEY_EXECUTE_SOUND_URI, null), prefs)

    fun playWaitSound(context: Context, prefs: SharedPreferences) =
        playActionSound(context, prefs.getString(SoundPrefs.KEY_WAIT_SOUND_URI, null), prefs)

    private fun playActionSound(context: Context, uriStr: String?, prefs: SharedPreferences) {
        actionPlayer?.let { try { it.stop(); it.release() } catch (_: Exception) {} }
        actionPlayer = null
        restoreNotifVolume(context)

        val volumePct = prefs.getInt(SoundPrefs.KEY_ACTION_VOLUME, SoundPrefs.DEFAULT_ACTION_VOLUME).coerceIn(0, 100)
        val uri: Uri = if (!uriStr.isNullOrBlank()) Uri.parse(uriStr)
        else RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_NOTIFICATION)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        savedNotifStreamVol = am.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION)
        try { am.setStreamVolume(AudioManager.STREAM_NOTIFICATION, (maxVol * volumePct / 100).coerceIn(0, maxVol), 0) } catch (_: Exception) {}

        try {
            actionPlayer = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(context, uri)
                isLooping = false
                prepare()
                start()
                setOnCompletionListener { restoreNotifVolume(context); try { release() } catch (_: Exception) {}; actionPlayer = null }
            }
        } catch (_: Exception) { restoreNotifVolume(context); actionPlayer = null }
    }

    // ── Private alarm helpers ─────────────────────────────────────────────────
    private fun startAlarmWithPrefix(context: Context, prefs: SharedPreferences, prefix: String) {
        stop(context)
        val uriStr    = prefs.getString(SoundPrefs.soundUriKey(prefix), null)
            ?: if (prefix.isNotEmpty()) prefs.getString(SoundPrefs.KEY_SOUND_URI, null) else null
        val timeoutSec = prefs.getInt(SoundPrefs.soundTimeoutKey(prefix), -1)
            .let { if (it == -1) prefs.getInt(SoundPrefs.KEY_SOUND_TIMEOUT, SoundPrefs.DEFAULT_SOUND_TIMEOUT) else it }
        val volumePct  = run { val v = prefs.getInt(SoundPrefs.soundVolumeKey(prefix), -1)
            if (v == -1) prefs.getInt(SoundPrefs.KEY_SOUND_VOLUME, SoundPrefs.DEFAULT_SOUND_VOLUME) else v }.coerceIn(0, 100)
        val fadeInSec  = run { val f = prefs.getInt(SoundPrefs.soundFadeInKey(prefix), -1)
            if (f == -1) prefs.getInt(SoundPrefs.KEY_SOUND_FADE_IN, SoundPrefs.DEFAULT_FADE_IN) else f }.coerceIn(0, 300)

        targetVolume   = volumePct / 100f
        fadeDurationMs = fadeInSec * 1000L

        val uri: Uri = if (!uriStr.isNullOrBlank()) Uri.parse(uriStr)
        else RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        savedAlarmStreamVol = am.getStreamVolume(AudioManager.STREAM_ALARM)
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        if (fadeDurationMs == 0L)
            am.setStreamVolume(AudioManager.STREAM_ALARM, (maxVol * targetVolume).toInt().coerceIn(0, maxVol), 0)

        try {
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(context, uri)
                isLooping = true
                setVolume(if (fadeDurationMs > 0) 0f else targetVolume, if (fadeDurationMs > 0) 0f else targetVolume)
                prepare(); start()
            }
            isPlaying = true
            if (fadeDurationMs > 0) scheduleFadeIn(context)
            if (timeoutSec > 0) { val r = Runnable { stop(context) }; stopRunnable = r; handler.postDelayed(r, timeoutSec * 1000L) }
        } catch (_: Exception) { isPlaying = false; restoreAlarmVolume(context) }
    }

    private fun scheduleFadeIn(context: Context) {
        val steps = 40; val stepMs = (fadeDurationMs / steps).coerceAtLeast(100L)
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        var step = 0
        val r = object : Runnable {
            override fun run() {
                if (!isPlaying || step >= steps) return
                step++; val fraction = step.toFloat() / steps
                player?.setVolume(targetVolume * fraction, targetVolume * fraction)
                am.setStreamVolume(AudioManager.STREAM_ALARM, (maxVol * targetVolume * fraction).toInt().coerceIn(0, maxVol), 0)
                if (step < steps) handler.postDelayed(this, stepMs)
            }
        }
        fadeRunnable = r; handler.postDelayed(r, stepMs)
    }

    private fun cancelPendingCallbacks() {
        stopRunnable?.let { handler.removeCallbacks(it) }; fadeRunnable?.let { handler.removeCallbacks(it) }
        stopRunnable = null; fadeRunnable = null
    }
    private fun restoreAlarmVolume(context: Context) {
        if (savedAlarmStreamVol < 0) return
        try { (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmStreamVol, 0) } catch (_: Exception) {}
        savedAlarmStreamVol = -1
    }
    private fun restoreNotifVolume(context: Context) {
        if (savedNotifStreamVol < 0) return
        try { (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .setStreamVolume(AudioManager.STREAM_NOTIFICATION, savedNotifStreamVol, 0) } catch (_: Exception) {}
        savedNotifStreamVol = -1
    }
}
