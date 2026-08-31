package com.eevdf.feature.task.list

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
internal class ListTogglesDelegate(private val prefs: SharedPreferences) {

    // ── Preference keys ───────────────────────────────────────────────────────

    private val KEY_GROUPS                    = "groups_enabled"
    private val KEY_GLOBAL_ROTATE             = "global_rotate_enabled"
    private val KEY_ALLOW_EDIT                = "allow_edit_enabled"
    private val KEY_AUTO_SCROLL               = "auto_scroll_enabled"
    private val KEY_NEXT_BUTTON_SHOWS_AUTO    = "next_button_shows_auto"
    private val KEY_LAST_TAB                  = "last_tab"
    private val KEY_SELECTED_TASK_ID          = "selected_timer_task_id"
    private val KEY_CARD_MANUALLY_HIDDEN      = "timer_card_manually_hidden"

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

    // ── Next/Auto button arm state ───────────────────────────────────────────
    //
    // Purely which label the Next/Auto button currently shows — NOT a
    // scheduling mode. Previously ("Auto mode") this was a persistent
    // background flag that auto-advanced to a new task the instant a timer
    // expired, and had a side effect on Global Rotate (forced it off, restored
    // it when Auto mode turned off again). Both removed: Auto is now a
    // manual, one-shot action, triggered only by tapping the button while
    // armed to "Auto" (see SchedulerDelegate.triggerAutoJump) — never
    // automatically on timer expiry. Global Rotate is now a fully independent
    // preference, same as any other toggle in this class.

    private val _nextButtonShowsAuto =
        MutableLiveData<Boolean>(prefs.getBoolean(KEY_NEXT_BUTTON_SHOWS_AUTO, false))
    val nextButtonShowsAuto: LiveData<Boolean> = _nextButtonShowsAuto

    /** Hold gesture on the Next/Auto button — flips which label is armed for
     * the next tap. No side effects, no persistence of scheduling behavior,
     * just which action a tap will perform next. */
    fun toggleNextButtonMode() {
        val next = !(_nextButtonShowsAuto.value ?: false)
        prefs.edit().putBoolean(KEY_NEXT_BUTTON_SHOWS_AUTO, next).apply()
        _nextButtonShowsAuto.value = next
    }

    // ── Tab persistence ───────────────────────────────────────────────────────

    fun saveTab(tab: Int) { prefs.edit().putInt(KEY_LAST_TAB, tab).apply() }
    fun getSavedTab(): Int = prefs.getInt(KEY_LAST_TAB, 0)

    // ── Timer-card persistence (selected task + manual-hide state) ────────────
    //
    //  The merged timer card must survive both reboot and app re-open, restoring:
    //   • WHICH task was last selected (so the card reopens on the same task), and
    //   • WHETHER the user had manually closed it (so a closed card stays closed).
    //
    //  Only the task *id* is stored — the live Task row is re-read from the DB on
    //  startup so any state change (paused / reset / completed) is reflected. A
    //  null id means "no task selected"; the card stays hidden.

    /** Persisted id of the last task seated on the timer card, or null if none. */
    fun saveSelectedTaskId(id: String?) {
        prefs.edit().apply {
            if (id == null) remove(KEY_SELECTED_TASK_ID)
            else            putString(KEY_SELECTED_TASK_ID, id)
        }.apply()
    }

    fun getSavedSelectedTaskId(): String? = prefs.getString(KEY_SELECTED_TASK_ID, null)

    /** Persisted manual-hide flag — true when the user closed the card by hand. */
    fun saveCardManuallyHidden(hidden: Boolean) {
        prefs.edit().putBoolean(KEY_CARD_MANUALLY_HIDDEN, hidden).apply()
    }

    fun getSavedCardManuallyHidden(): Boolean =
        prefs.getBoolean(KEY_CARD_MANUALLY_HIDDEN, false)
}
