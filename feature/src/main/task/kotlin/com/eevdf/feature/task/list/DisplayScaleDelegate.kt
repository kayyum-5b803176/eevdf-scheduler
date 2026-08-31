package com.eevdf.feature.task.list

import android.view.View
import android.widget.LinearLayout
import com.eevdf.feature.shared.prefs.DisplayPrefs
import com.eevdf.feature.shared.prefs.QuickActionPrefs
import com.eevdf.feature.ui.DesignTokens
import com.eevdf.feature.ui.LayoutTokenPrefs

/**
 * Reads Display-settings prefs and card-scale/compact-mode/FAB-visibility rules,
 * pushing them onto the three task adapters and the fixed timer/alarm cards.
 *
 * Extracted from MainActivity (Phase 10) — pure view-mutation logic that owned
 * no state of its own beyond [MainActivity.isCompactModeActive], which stays on
 * the activity since [MainActivity.applyFabVisibility]'s equivalent here reads
 * it via the activity reference, same as every other delegate in this app reads
 * its owner. No behavior changed — every line is the original, moved as-is.
 */
internal class DisplayScaleDelegate(private val activity: MainActivity) {

    /**
     * Reads padding scale (from [LayoutTokenPrefs], not [DisplayPrefs] — the
     * old "Card Height" preference was consolidated into the layout-token
     * system, one padding scale instead of two that happened to agree) and
     * auto-adjust preference, pushing them to all three adapters and the
     * fixed timer / alarm cards.
     *
     * Called on `onResume` so changes made in DisplaySettingsActivity are picked
     * up immediately when the user navigates back.
     *
     * Simple-mode note: when simple mode is enabled the RecyclerView item animator
     * is set to null.  In simple mode `setRunningTask` uses notifyItemChanged on
     * two cards (old running → collapse rows, new running → expand rows), and the
     * DefaultItemAnimator runs a ~250 ms crossfade on each change.  That animation
     * delays both the row-visibility update AND the auto-scroll jump, causing the
     * visual stutter reported by the user.  Removing the animator makes every card
     * change and every scroll instant.  When simple mode is off the animator is
     * restored so normal-mode list updates keep their default feel.
     */
    fun applyDisplayPrefs() {
        val scale      = LayoutTokenPrefs.getPaddingScale(activity)
        val autoAdj    = DisplayPrefs.isAutoAdjustEnabled(activity)
        val simpleMode = DisplayPrefs.isSimpleModeEnabled(activity)
        val unitFormat = DisplayPrefs.isUnitFormatEnabled(activity)

        activity.activeAdapter.setSimpleMode(simpleMode)
        activity.scheduleAdapter.setSimpleMode(simpleMode)
        activity.completedAdapter.setSimpleMode(simpleMode)

        activity.activeAdapter.setUnitFormat(unitFormat)
        activity.scheduleAdapter.setUnitFormat(unitFormat)
        activity.completedAdapter.setUnitFormat(unitFormat)

        // Simple mode: kill the item animator so notifyItemChanged-driven row
        // visibility changes and auto-scroll jumps are both instantaneous.
        // Non-simple mode: restore a fresh DefaultItemAnimator so normal
        // add/remove/change animations work as expected.
        activity.recyclerView.itemAnimator = if (simpleMode) null
                                    else            androidx.recyclerview.widget.DefaultItemAnimator()

        updateCompactMode(scale, autoAdj)
    }

    /**
     * Detects whether the activity is currently running in a floating or
     * picture-in-picture window and updates [MainActivity.isCompactModeActive]
     * accordingly.
     *
     * Floating detection strategy (API 26+):
     *  • PiP mode         → definitive compact trigger
     *  • Multi-window     → compact trigger (covers freeform floating windows,
     *                        split-screen, and any other windowed mode).
     *                        resources.configuration.screenWidthDp reflects the
     *                        real window width, not the physical screen — used
     *                        for logging / future threshold tuning if needed.
     *
     * When auto-adjust is disabled, compact mode is always off regardless of
     * the window state.
     */
    fun updateCompactMode(scale: Int, autoAdjust: Boolean) {
        val density   = activity.resources.displayMetrics.density
        val widthDp   = activity.resources.configuration.screenWidthDp
        val heightDp  = activity.resources.configuration.screenHeightDp
        val inPip     = activity.isInPictureInPictureMode
        val inMulti   = activity.isInMultiWindowMode

        val matched   = DisplayPrefs.matchProfile(activity, widthDp, heightDp)
        val shouldBeCompact = autoAdjust && when (matched) {
            null -> inPip || inMulti
            else -> DisplayPrefs.isCompactProfile(matched)
        }

        activity.isCompactModeActive = shouldBeCompact

        activity.activeAdapter.setCompactMode(shouldBeCompact)
        activity.scheduleAdapter.setCompactMode(shouldBeCompact)
        activity.completedAdapter.setCompactMode(shouldBeCompact)

        // ── Float-mode banner hiding ──────────────────────────────────────────
        // When the window matches the FLOAT calibration profile, hide:
        //   • Banner 1 — toolbar (app name + all menu icons)
        //   • Banner 2 — statsBar (task status statistics)
        // The TabLayout row stays visible so the user can switch tabs.
        // Both banners are restored for any other profile or when auto-adjust
        // is off, so normal / mini / uncalibrated modes are unaffected.
        val isFloatProfile = autoAdjust && matched == DisplayPrefs.CalibrateProfile.MINI
        val bannerVis = if (isFloatProfile) View.GONE else View.VISIBLE
        activity.mainToolbar.visibility = bannerVis
        activity.statsBar.visibility    = bannerVis

        // ── FAB hiding on float / mini profiles ───────────────────────────────
        // When auto-adjust is on and the window matches FLOAT or MINI, both FABs
        // are hidden — the window is too small for them to be useful and they
        // overlap content in compact mode.  NORMAL profile and uncalibrated
        // windows always show the FABs (subject to their own pref gates).
        val isCompactProfile = autoAdjust && matched != null &&
            DisplayPrefs.isCompactProfile(matched)
        applyFabVisibility(isCompactProfile)

        // Scale the fixed cards (timer + alarm) to match task cards
        applyCardScaleToView(activity.layoutTimerContent, scale, density)
        applyCardScaleToView(activity.layoutAlarmContent, scale, density)
    }

    /**
     * Scales the padding of a card content [LinearLayout] to match [scale].
     * Delegates to [DesignTokens.paddingDpFor] — the canonical table, shared
     * with [com.eevdf.feature.task.adapter.applyCardScale] (task rows). See
     * [DesignTokens]'s class doc for why this consolidation shifted this
     * card's own padding slightly from its prior independent table.
     */
    fun applyCardScaleToView(layout: LinearLayout, scale: Int, density: Float) {
        val paddingDp = DesignTokens.paddingDpFor(scale)
        val p = (paddingDp * density + 0.5f).toInt()
        layout.setPadding(p, p, p, p)
    }

    /**
     * Controls visibility of both FABs in one place so they always stay in sync.
     *
     * Rules:
     *  • fabAdd — visible when Allow Edit is enabled AND not in a compact
     *    (FLOAT / MINI) calibration profile.
     *  • fabQuickAction — visible when Quick Action pref is on AND not in a
     *    compact profile. Independent of Allow Edit.
     *
     * [suppressForCompactProfile] is true when auto-adjust is on and the current
     * window matches a FLOAT or MINI calibration profile — both FABs are hidden
     * in that case regardless of other prefs, because the window is too small.
     *
     * Called from [updateCompactMode] (which runs inside [applyDisplayPrefs] on
     * every onResume and every relevant configuration / window change).
     */
    fun applyFabVisibility(suppressForCompactProfile: Boolean) {
        val editEnabled  = activity.viewModel.allowEditEnabled.value ?: false
        val quickEnabled = QuickActionPrefs.isQuickActionEnabled(activity)

        activity.fabAdd.visibility =
            if (!suppressForCompactProfile && editEnabled) View.VISIBLE else View.GONE

        activity.fabQuickAction.visibility =
            if (!suppressForCompactProfile && quickEnabled) View.VISIBLE else View.GONE

        // Keep RecyclerView bottom padding in sync with the VISIBLE FAB stack so
        // the last card can always scroll clear of any FAB and its buttons stay
        // tappable. The two FABs are stacked at bottom|end (FAB height ≈ 56dp):
        //   • fabAdd          — margin 16dp        → top edge ≈ 72dp from bottom
        //   • fabQuickAction  — marginBottom 88dp  → top edge ≈ 144dp from bottom
        //
        // The add-only case uses 80dp padding = its 72dp top edge + an 8dp gap of
        // breathing room above the FAB. To make the Quick Action FAB behave
        // IDENTICALLY, we pad to its top edge plus the SAME 8dp gap:
        //   88 (margin) + 56 (height) + 8 (gap) = 152dp.
        val density        = activity.resources.displayMetrics.density
        val addVisible     = activity.fabAdd.visibility == View.VISIBLE
        val quickVisible   = activity.fabQuickAction.visibility == View.VISIBLE
        val fabPadDp = when {
            quickVisible -> 152   // quick-action sits highest; match add-only's 8dp gap
            addVisible   -> 80    // only the add FAB is present
            else         -> 0     // no FABs — no extra padding
        }
        val fabPadPx = (fabPadDp * density).toInt()
        activity.recyclerView.setPadding(
            activity.recyclerView.paddingLeft,
            activity.recyclerView.paddingTop,
            activity.recyclerView.paddingRight,
            fabPadPx
        )
    }
}
