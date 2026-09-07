package com.eevdf.capabilities.settingsstorage.state

/**
 * Pure SharedPreferences key constants and key-naming helpers for sound
 * playback — no playback behavior lives here.
 *
 * WHY THIS LIVES HERE, NOT IN `capabilities/sound`
 * ---------------------------------------------------
 * `capabilities/sound` owns *playing* a sound (a behavior, reached only via
 * the bus). `settings-screens` needs to *read and write the same
 * SharedPreferences keys* to build its UI (populate sliders, show which
 * ringtone is picked) — that's a static-data need, not a behavior call, and
 * an event bus has no "ask a question, get an answer back" mode to serve it.
 * Putting the key names here means `sound` and `settings-screens` agree on
 * the same keys without either one importing the other.
 *
 * [prefixFor] also lives here (not duplicated in a vibration-specific file)
 * since it's the shared "which task-type profile" concept both sound and
 * vibration keys are namespaced under.
 */
object SoundPrefs {

    // ── Alarm prefs keys ──────────────────────────────────────────────────────
    const val KEY_SOUND_URI     = "sound_uri"
    const val KEY_SOUND_TIMEOUT = "sound_timeout_sec"
    const val KEY_SOUND_VOLUME  = "sound_volume"
    const val KEY_SOUND_FADE_IN = "sound_fade_in_sec"

    const val DEFAULT_SOUND_TIMEOUT = 60
    const val DEFAULT_SOUND_VOLUME  = 80
    const val DEFAULT_FADE_IN       = 0

    // ── Action sound prefs keys (Notice profile) ──────────────────────────────
    const val KEY_EXECUTE_SOUND_URI = "notif_delay_sound_uri"
    const val KEY_WAIT_SOUND_URI    = "notif_rest_sound_uri"
    const val KEY_ACTION_VOLUME     = "notif_action_volume"
    const val DEFAULT_ACTION_VOLUME = 80

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
}
