package com.eevdf.app.feature.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * Hardware-key → timer-expire action mapping.
 *
 * Three physical keys can each be bound to one expire-time action:
 *   • Volume Up
 *   • Volume Down
 *   • Power
 *
 * Two actions are available:
 *   • [ACTION_STOP]    — dismiss the alarm (same as the on-screen Stop button).
 *   • [ACTION_RESTART] — dismiss the alarm AND immediately restart the expired
 *                        task's timer from a full slice ("stop and start").
 *
 * Exclusivity rule (requirement #3): an action may be bound to at most ONE key.
 * If the user assigns e.g. STOP to Volume Up, then RESTART is the only remaining
 * choice for Volume Down / Power (besides NONE).  All mutual-exclusion logic is
 * centralised here so the UI and the key dispatcher share one source of truth.
 *
 * Stored in the existing "button_action_prefs" file so a single SharedPreferences
 * file continues to back the whole Button Action settings screen.
 */
object HardwareKeyPrefs {

    private const val PREFS_NAME = "button_action_prefs"

    // ── Key identifiers (stable string ids, persisted) ─────────────────────────
    const val KEY_VOLUME_UP   = "volume_up"
    const val KEY_VOLUME_DOWN = "volume_down"
    const val KEY_POWER       = "power"

    /** Ordered list of every assignable hardware key. */
    val ALL_KEYS = listOf(KEY_VOLUME_UP, KEY_VOLUME_DOWN, KEY_POWER)

    // ── Action identifiers (stable string ids, persisted) ──────────────────────
    const val ACTION_NONE    = "none"
    const val ACTION_STOP    = "stop"
    const val ACTION_RESTART = "restart"   // stop and start (restart)

    /** Actions the user can pick on the option page (NONE is the implicit default). */
    val SELECTABLE_ACTIONS = listOf(ACTION_NONE, ACTION_STOP, ACTION_RESTART)

    /**
     * Actions a given key is permitted to perform.
     *
     * The Power key is restricted to NONE / STOP only: it is driven by screen-off
     * detection (the screen always turns off on a Power press), which can reliably
     * dismiss the alarm but is a poor fit for "Stop and Start" — restarting a task
     * the instant the screen goes dark is surprising.  Volume keys allow the full
     * set.
     */
    fun selectableActionsFor(key: String): List<String> = when (key) {
        KEY_POWER -> listOf(ACTION_NONE, ACTION_STOP)
        else      -> SELECTABLE_ACTIONS
    }

    private fun prefKey(key: String) = "hw_key_action_$key"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Read / write ───────────────────────────────────────────────────────────

    /** Action bound to [key]; [ACTION_NONE] when unassigned. */
    fun getAction(ctx: Context, key: String): String {
        val stored = prefs(ctx).getString(prefKey(key), ACTION_NONE) ?: ACTION_NONE
        // Defend against a stale/disallowed value (e.g. Power=RESTART from an old
        // backup) so it never takes effect.
        return if (stored in selectableActionsFor(key)) stored else ACTION_NONE
    }

    /**
     * Bind [action] to [key], enforcing exclusivity: if [action] is a real action
     * (STOP / RESTART) and is already bound to a different key, that other key is
     * cleared to NONE first so the action ends up bound to exactly one key.
     */
    fun setAction(ctx: Context, key: String, action: String) {
        // Enforce per-key limits (e.g. Power may only be NONE or STOP); fall back
        // to NONE for a disallowed action so an illegal pairing can never persist.
        val effective = if (action in selectableActionsFor(key)) action else ACTION_NONE
        val editor = prefs(ctx).edit()
        if (effective != ACTION_NONE) {
            // Clear the same action from any other key.
            for (other in ALL_KEYS) {
                if (other != key && getAction(ctx, other) == effective) {
                    editor.putString(prefKey(other), ACTION_NONE)
                }
            }
        }
        editor.putString(prefKey(key), effective)
        editor.apply()
    }

    /**
     * The key (if any) currently bound to [action], excluding [exceptKey].
     * Used by the option page to grey-out actions already taken by another key.
     */
    fun keyBoundTo(ctx: Context, action: String, exceptKey: String? = null): String? {
        if (action == ACTION_NONE) return null
        for (key in ALL_KEYS) {
            if (key == exceptKey) continue
            if (getAction(ctx, key) == action) return key
        }
        return null
    }

    /**
     * Reverse lookup used by the key dispatcher at expire time: given a pressed
     * key id, return its bound action ([ACTION_NONE] when unassigned).
     */
    fun actionForKey(ctx: Context, key: String): String = getAction(ctx, key)

    // ── Display helpers ────────────────────────────────────────────────────────

    fun keyLabel(key: String): String = when (key) {
        KEY_VOLUME_UP   -> "Volume Up"
        KEY_VOLUME_DOWN -> "Volume Down"
        KEY_POWER       -> "Power Button"
        else            -> key
    }

    fun actionLabel(action: String): String = when (action) {
        ACTION_STOP    -> "Stop"
        ACTION_RESTART -> "Stop and Start (Restart)"
        else           -> "None"
    }
}
