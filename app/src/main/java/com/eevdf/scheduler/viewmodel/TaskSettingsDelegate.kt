package com.eevdf.scheduler.viewmodel

import android.content.SharedPreferences
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/**
 * Owns every user-facing toggle that is persisted to [SharedPreferences].
 *
 * Domains covered:
 *  - Groups mode
 *  - Global Rotate mode (including the saved-state needed by Auto mode)
 *  - Allow Edit mode
 *  - Auto Scroll mode
 *  - Auto mode (saves / restores Global Rotate on toggle)
 *  - Last-selected tab
 *
 * Adding a new toggle:
 *  1. Add a private KEY_ constant.
 *  2. Add a private MutableLiveData + public LiveData pair.
 *  3. Add a toggle function.
 *  No other delegate or the ViewModel itself needs to change.
 */
internal class TaskSettingsDelegate(private val prefs: SharedPreferences) {

    // ── Preference keys ───────────────────────────────────────────────────────

    private val KEY_GROUPS                    = "groups_enabled"
    private val KEY_GLOBAL_ROTATE             = "global_rotate_enabled"
    private val KEY_ALLOW_EDIT                = "allow_edit_enabled"
    private val KEY_AUTO_SCROLL               = "auto_scroll_enabled"
    private val KEY_AUTO_MODE                 = "auto_mode"
    private val KEY_GLOBAL_ROTATE_BEFORE_AUTO = "global_rotate_before_auto"
    private val KEY_LAST_TAB                  = "last_tab"

    // ── Groups ────────────────────────────────────────────────────────────────

    private val _groupsEnabled = MutableLiveData<Boolean>(prefs.getBoolean(KEY_GROUPS, false))
    val groupsEnabled: LiveData<Boolean> = _groupsEnabled

    fun toggleGroupsEnabled() {
        val next = !(_groupsEnabled.value ?: false)
        prefs.edit().putBoolean(KEY_GROUPS, next).apply()
        _groupsEnabled.value = next
    }

    // ── Global Rotate ─────────────────────────────────────────────────────────

    private val _globalRotateEnabled =
        MutableLiveData<Boolean>(prefs.getBoolean(KEY_GLOBAL_ROTATE, false))
    val globalRotateEnabled: LiveData<Boolean> = _globalRotateEnabled

    /**
     * Direct mutable access used only by [toggleAutoMode] inside this class
     * and by [TaskViewModel] to wire the [nextButtonState] mediator source.
     */
    internal val mutableGlobalRotate: MutableLiveData<Boolean> get() = _globalRotateEnabled

    fun toggleGlobalRotate() {
        val next = !(_globalRotateEnabled.value ?: false)
        prefs.edit().putBoolean(KEY_GLOBAL_ROTATE, next).apply()
        _globalRotateEnabled.value = next
    }

    // ── Allow Edit ────────────────────────────────────────────────────────────

    private val _allowEditEnabled =
        MutableLiveData<Boolean>(prefs.getBoolean(KEY_ALLOW_EDIT, false))
    val allowEditEnabled: LiveData<Boolean> = _allowEditEnabled

    fun toggleAllowEdit() {
        val next = !(_allowEditEnabled.value ?: false)
        prefs.edit().putBoolean(KEY_ALLOW_EDIT, next).apply()
        _allowEditEnabled.value = next
    }

    // ── Auto Scroll ───────────────────────────────────────────────────────────

    private val _autoScrollEnabled =
        MutableLiveData<Boolean>(prefs.getBoolean(KEY_AUTO_SCROLL, false))
    val autoScrollEnabled: LiveData<Boolean> = _autoScrollEnabled

    fun toggleAutoScroll() {
        val next = !(_autoScrollEnabled.value ?: false)
        prefs.edit().putBoolean(KEY_AUTO_SCROLL, next).apply()
        _autoScrollEnabled.value = next
    }

    // ── Auto Mode ─────────────────────────────────────────────────────────────
    //
    //  ON  → saves current Global Rotate state, forces it OFF.
    //  OFF → restores the previously saved Global Rotate state.
    //
    //  savedGlobalRotateBeforeAuto is persisted so that if the app is killed
    //  while Auto mode is active, the original Global Rotate value survives
    //  the restart and can be correctly restored when Auto mode is turned off.

    private val _autoMode =
        MutableLiveData<Boolean>(prefs.getBoolean(KEY_AUTO_MODE, false))
    val autoMode: LiveData<Boolean> = _autoMode

    private var savedGlobalRotateBeforeAuto: Boolean =
        prefs.getBoolean(KEY_GLOBAL_ROTATE_BEFORE_AUTO, false)

    /**
     * Toggles Auto mode on/off.
     * @return Toast message for the ViewModel to post.
     */
    fun toggleAutoMode(): String {
        val next = !(_autoMode.value ?: false)
        if (next) {
            savedGlobalRotateBeforeAuto = _globalRotateEnabled.value ?: false
            prefs.edit()
                .putBoolean(KEY_GLOBAL_ROTATE_BEFORE_AUTO, savedGlobalRotateBeforeAuto)
                .putBoolean(KEY_GLOBAL_ROTATE, false)
                .putBoolean(KEY_AUTO_MODE, next)
                .apply()
            _globalRotateEnabled.value = false
            _autoMode.value = next
            return "Auto mode ON — Global Rotate disabled"
        } else {
            prefs.edit()
                .putBoolean(KEY_GLOBAL_ROTATE, savedGlobalRotateBeforeAuto)
                .remove(KEY_GLOBAL_ROTATE_BEFORE_AUTO)
                .putBoolean(KEY_AUTO_MODE, next)
                .apply()
            _globalRotateEnabled.value = savedGlobalRotateBeforeAuto
            savedGlobalRotateBeforeAuto = false
            _autoMode.value = next
            return "Auto mode OFF — Global Rotate restored"
        }
    }

    // ── Tab persistence ───────────────────────────────────────────────────────

    fun saveTab(tab: Int) { prefs.edit().putInt(KEY_LAST_TAB, tab).apply() }
    fun getSavedTab(): Int = prefs.getInt(KEY_LAST_TAB, 0)
}
