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

object SoundManager {

    // ── Alarm prefs keys ──────────────────────────────────────────────────────
    const val KEY_SOUND_URI        = "sound_uri"
    const val KEY_SOUND_TIMEOUT    = "sound_timeout_sec"
    const val KEY_SOUND_VOLUME     = "sound_volume"
    const val KEY_SOUND_FADE_IN    = "sound_fade_in_sec"

    const val DEFAULT_SOUND_TIMEOUT = 60
    const val DEFAULT_SOUND_VOLUME  = 80
    const val DEFAULT_FADE_IN       = 0

    // ── Action sound prefs keys (Notice profile) ──────────────────────────────
    const val KEY_EXECUTE_SOUND_URI = "notif_delay_sound_uri"
    const val KEY_WAIT_SOUND_URI    = "notif_rest_sound_uri"
    const val KEY_ACTION_VOLUME     = "notif_action_volume"
    const val DEFAULT_ACTION_VOLUME = 80

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

    // ── Profile key helpers ───────────────────────────────────────────────────
    fun prefixFor(taskType: String): String = when (taskType) {
        "NOTIFICATION" -> "notif_"
        "ALARM"        -> "alarm_"
        "CUSTOM"       -> "custom_"
        else           -> ""
    }
    fun soundUriKey(prefix: String)     = "${prefix}${KEY_SOUND_URI}"
    fun soundTimeoutKey(prefix: String) = "${prefix}${KEY_SOUND_TIMEOUT}"
    fun soundVolumeKey(prefix: String)  = "${prefix}${KEY_SOUND_VOLUME}"
    fun soundFadeInKey(prefix: String)  = "${prefix}${KEY_SOUND_FADE_IN}"

    // ── Alarm public API ──────────────────────────────────────────────────────
    fun startAlarmForType(context: Context, prefs: SharedPreferences, taskType: String) =
        startAlarmWithPrefix(context, prefs, prefixFor(taskType))

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
        playActionSound(context, prefs.getString(KEY_EXECUTE_SOUND_URI, null), prefs)

    fun playWaitSound(context: Context, prefs: SharedPreferences) =
        playActionSound(context, prefs.getString(KEY_WAIT_SOUND_URI, null), prefs)

    private fun playActionSound(context: Context, uriStr: String?, prefs: SharedPreferences) {
        actionPlayer?.let { try { it.stop(); it.release() } catch (_: Exception) {} }
        actionPlayer = null
        restoreNotifVolume(context)

        val volumePct = prefs.getInt(KEY_ACTION_VOLUME, DEFAULT_ACTION_VOLUME).coerceIn(0, 100)
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
        val uriStr    = prefs.getString(soundUriKey(prefix), null)
            ?: if (prefix.isNotEmpty()) prefs.getString(KEY_SOUND_URI, null) else null
        val timeoutSec = prefs.getInt(soundTimeoutKey(prefix), -1)
            .let { if (it == -1) prefs.getInt(KEY_SOUND_TIMEOUT, DEFAULT_SOUND_TIMEOUT) else it }
        val volumePct  = run { val v = prefs.getInt(soundVolumeKey(prefix), -1)
            if (v == -1) prefs.getInt(KEY_SOUND_VOLUME, DEFAULT_SOUND_VOLUME) else v }.coerceIn(0, 100)
        val fadeInSec  = run { val f = prefs.getInt(soundFadeInKey(prefix), -1)
            if (f == -1) prefs.getInt(KEY_SOUND_FADE_IN, DEFAULT_FADE_IN) else f }.coerceIn(0, 300)

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
