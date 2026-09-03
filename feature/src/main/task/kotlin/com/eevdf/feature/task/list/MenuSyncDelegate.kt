package com.eevdf.feature.task.list

import android.animation.ObjectAnimator
import android.content.res.ColorStateList
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import com.eevdf.contract.nav.AppRoutes
import com.eevdf.data.sync.SyncState
import com.eevdf.feature.R
import com.eevdf.feature.task.timer.TimerCardAction
import android.widget.Toast

/**
 * Owns the options menu — inflation, item-selection handling, the sync status
 * icon (color + spin animation), and the key1 "Schedule Next" status dot.
 *
 * Extracted from MainActivity (Phase 10). `onCreateOptionsMenu`/
 * `onOptionsItemSelected` stay as thin overrides on MainActivity itself —
 * Android calls those directly on the Activity, they can't be delegated away
 * — and forward into [inflateMenu]/[handleItemSelected] here. No behavior
 * changed — every line is the original, moved as-is.
 */
internal class MenuSyncDelegate(private val activity: MainActivity) {

    fun inflateMenu(menu: Menu): Boolean {
        activity.menuInflater.inflate(R.menu.main_menu, menu)
        activity.groupsMenuItem       = menu.findItem(R.id.action_toggle_groups)
        activity.groupsMenuItem?.isChecked = activity.viewModel.groupsEnabled.value ?: false
        activity.queueDrillMenuItem    = menu.findItem(R.id.action_toggle_queue_drill)
        activity.scheduleDrillMenuItem = menu.findItem(R.id.action_toggle_schedule_drill)
        syncDrillMenuItems()
        activity.globalRotateMenuItem = menu.findItem(R.id.action_toggle_global_rotate)
        activity.globalRotateMenuItem?.isChecked = activity.viewModel.globalRotateEnabled.value ?: false
        activity.allowEditMenuItem    = menu.findItem(R.id.action_allow_edit)
        activity.allowEditMenuItem?.isChecked = activity.viewModel.allowEditEnabled.value ?: false
        activity.autoScrollMenuItem   = menu.findItem(R.id.action_auto_scroll)
        activity.autoScrollMenuItem?.isChecked = activity.viewModel.autoScrollEnabled.value ?: false

        // ── Sync icon action view ─────────────────────────────────────────────
        menu.findItem(R.id.action_sync)?.actionView?.let { syncView ->
            activity.syncDotView  = syncView.findViewById(R.id.viewSyncDot)
            activity.syncIconView = syncView.findViewById(R.id.ivSyncIcon)
            syncView.setOnClickListener {
                // Tap sync icon → trigger an immediate sync export
                activity.viewModel.triggerSyncExport()
                Toast.makeText(activity, "Syncing…", Toast.LENGTH_SHORT).show()
            }
        }

        // Action view supports both tap and long-press; a plain MenuItem only fires tap.
        menu.findItem(R.id.action_schedule_next)?.actionView?.let { view ->
            // Grab the status dot so timerCardAction observer can tint it.
            activity.schedNextDotView = view.findViewById(R.id.viewScheduleNextDot)

            // Tap — two cases:
            //   Case 1: any non-interrupt leaf task is visible in the current tab
            //           → jump to the first visible leaf (interrupt tasks skipped).
            //   Case 2: all tasks are under collapsed groups (no non-interrupt leaf
            //           visible) → select the assigned interrupt task instead.
            view.setOnClickListener {
                val list = if (activity.currentTab == 0) activity.viewModel.flatActiveTasks.value
                           else                 activity.viewModel.flatScheduleOrder.value
                val hasLeaves = list?.any {
                    !it.task.isGroup && !it.task.isCompleted && !it.task.isInterrupt
                } == true
                activity.haptic(view)
                if (hasLeaves) {
                    activity.viewModel.jumpToFirst(onQueueTab = activity.currentTab == 0)
                } else {
                    // No visible normal tasks — fall back to the active interrupt slot
                    activity.viewModel.jumpToInterrupt()
                }
            }
            // Hold → toggle timer card open/closed (UI only — timer state unchanged).
            //   • Alarm ringing → no-op (the expired card must stay visible)
            //   • Card visible  → hide it; persist; dot switches to colored state
            //   • Card hidden + active task → show it; persist; dot reverts to grey
            //   • Card hidden + no task     → no-op
            view.setOnLongClickListener {
                activity.haptic(view)
                val action = activity.viewModel.timerCardAction.value
                when {
                    action is TimerCardAction.Expired -> Unit   // can't hide a ringing alarm
                    activity.cardTimer.visibility == View.VISIBLE -> {
                        activity.isCardManuallyHidden = true
                        activity.cardTimer.visibility = View.GONE
                        activity.viewModel.setCardManuallyHidden(true)
                    }
                    activity.viewModel.currentTask.value != null -> {
                        activity.isCardManuallyHidden = false
                        activity.cardTimer.visibility = View.VISIBLE
                        activity.viewModel.setCardManuallyHidden(false)
                    }
                }
                updateScheduleNextDot()
                true
            }
        }
        // ── Overflow (3-dot) long-press: global group collapse / expand ───────
        // Deferred with toolbar.post so the overflow button is in the view tree.
        // Collapse if any non-interrupt leaf is visible; expand if all collapsed.
        // Interrupt-task ancestor groups are excluded from the toggle.
        activity.findViewById<Toolbar>(R.id.toolbar)?.post {
            val desc = activity.getString(androidx.appcompat.R.string.abc_action_menu_overflow_description)
            findViewByContentDesc(activity.findViewById(R.id.toolbar), desc)
                ?.setOnLongClickListener { v ->
                    activity.haptic(v)
                    val list = if (activity.currentTab == 0) activity.viewModel.flatActiveTasks.value
                               else                 activity.viewModel.flatScheduleOrder.value
                    val hasLeaves = list?.any {
                        !it.task.isGroup && !it.task.isCompleted && !it.task.isInterrupt
                    } == true
                    activity.viewModel.toggleAllGroupsGlobal(
                        onQueueTab       = activity.currentTab == 0,
                        hasVisibleLeaves = hasLeaves
                    )
                    true
                }
        }

        return true
    }

    fun handleItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_toggle_groups -> {
                activity.viewModel.toggleGroupsEnabled()
                item.isChecked = activity.viewModel.groupsEnabled.value ?: false
                syncDrillMenuItems()
                true
            }
            R.id.action_toggle_queue_drill -> {
                activity.viewModel.toggleQueueListStyle()
                item.isChecked = activity.viewModel.queueListStyle.value == TaskListStyle.DRILL_DOWN
                true
            }
            R.id.action_toggle_schedule_drill -> {
                activity.viewModel.toggleScheduleListStyle()
                item.isChecked = activity.viewModel.scheduleListStyle.value == TaskListStyle.DRILL_DOWN
                true
            }
            R.id.action_toggle_global_rotate -> {
                activity.viewModel.toggleGlobalRotate()
                item.isChecked = activity.viewModel.globalRotateEnabled.value ?: false
                true
            }
            R.id.action_allow_edit -> {
                activity.viewModel.toggleAllowEdit()
                item.isChecked = activity.viewModel.allowEditEnabled.value ?: false
                true
            }
            R.id.action_auto_scroll -> {
                activity.viewModel.toggleAutoScroll()
                item.isChecked = activity.viewModel.autoScrollEnabled.value ?: false
                true
            }
            R.id.action_clear_completed -> { activity.viewModel.clearCompleted(); true }
            R.id.action_settings -> {
                activity.startActivity(AppRoutes.settings(activity))
                true
            }
            else -> false
        }
    }

    // ── Links feature: per-tab display style menu items ─────────────────────

    /** Visibility + checked state for the two drill-down toggles — only shown
     *  at all when groups are enabled (no hierarchy to drill into otherwise). */
    private fun syncDrillMenuItems() {
        val groupsOn = activity.viewModel.groupsEnabled.value ?: false
        activity.queueDrillMenuItem?.apply {
            isVisible = groupsOn
            isChecked = activity.viewModel.queueListStyle.value == TaskListStyle.DRILL_DOWN
        }
        activity.scheduleDrillMenuItem?.apply {
            isVisible = groupsOn
            isChecked = activity.viewModel.scheduleListStyle.value == TaskListStyle.DRILL_DOWN
        }
    }

    // ── Key1 (Schedule Next) dot update ──────────────────────────────────────

    /**
     * Updates the key1 status dot to reflect timer state — but only when the
     * timer card is manually hidden.  While the card is open (visible) the dot
     * stays grey because the card itself already shows the full state; coloring
     * the dot too would be redundant and visually noisy.
     *
     * Call from:
     *  • timerCardAction observer  — timer state changed
     *  • key1 hold handler         — card visibility toggled
     */
    fun updateScheduleNextDot() {
        val dot = activity.schedNextDotView ?: return
        val grey = android.graphics.Color.parseColor("#9E9E9E")
        val color = if (activity.isCardManuallyHidden) {
            // Card is hidden — show actual timer state so user knows what's running.
            // Hidden (no task) and Unavailable have no meaningful colour → grey.
            // Expired never reaches here (the alarm forces the card visible).
            val action = activity.viewModel.timerCardAction.value
            when (action) {
                null,
                is TimerCardAction.Hidden,
                is TimerCardAction.Unavailable -> grey
                else -> ContextCompat.getColor(activity, action.colorRes)
            }
        } else {
            // Card is visible (or no task) — grey dot; card shows the state
            grey
        }
        dot.visibility = View.VISIBLE
        dot.backgroundTintList = ColorStateList.valueOf(color)
    }

    // ── View-tree helper ──────────────────────────────────────────────────────

    /**
     * Recursively walks [root]'s view tree and returns the first child whose
     * [android.view.View.contentDescription] exactly matches [desc], or null.
     * Used to locate the overflow (3-dot) button by its AppCompat content-
     * description, which is the only stable cross-version identifier.
     */
    private fun findViewByContentDesc(
        root: android.view.ViewGroup,
        desc: String
    ): android.view.View? {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child.contentDescription?.toString() == desc) return child
            if (child is android.view.ViewGroup) {
                val found = findViewByContentDesc(child, desc)
                if (found != null) return found
            }
        }
        return null
    }

    // ── Sync icon update ──────────────────────────────────────────────────────

    /**
     * Updates the sync status dot color and the sync icon spin animation
     * based on the current [SyncState].
     *
     *   Disabled / Idle  → gray dot,  no spin
     *   Syncing          → gray dot,  spin animation
     *   OK               → green dot, no spin
     *   Error            → red dot,   no spin
     */
    fun updateSyncIcon(state: SyncState) {
        val dot  = activity.syncDotView  ?: return
        val icon = activity.syncIconView ?: return

        // Colors
        val color = when (state) {
            SyncState.OK       -> android.graphics.Color.parseColor("#4CAF50") // green
            is SyncState.Error -> android.graphics.Color.parseColor("#F44336") // red
            SyncState.Syncing  -> android.graphics.Color.parseColor("#FF9800") // amber
            else               -> android.graphics.Color.parseColor("#9E9E9E") // gray
        }
        dot.backgroundTintList = ColorStateList.valueOf(color)

        // Spin animation while syncing
        if (state == SyncState.Syncing) {
            if (activity.syncSpinAnim?.isRunning != true) {
                activity.syncSpinAnim = ObjectAnimator.ofFloat(icon, "rotation", 0f, 360f).apply {
                    duration    = 1000
                    repeatCount = ObjectAnimator.INFINITE
                    start()
                }
            }
        } else {
            activity.syncSpinAnim?.cancel()
            activity.syncSpinAnim = null
            icon.rotation = 0f
        }

        // Show a tooltip / snackbar on error so the user knows what went wrong
        if (state is SyncState.Error) {
            dot.contentDescription = "Sync error: ${state.message}"
        } else {
            dot.contentDescription = null
        }
    }
}
