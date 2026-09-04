package com.eevdf.feature.task.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.eevdf.feature.R
import com.eevdf.feature.task.notice.NoticePhase
import com.eevdf.data.task.Task
import com.eevdf.data.task.TaskDisplayItem
import com.eevdf.data.scheduler.RtScheduler

/**
 * RecyclerView adapter for the task list.
 *
 * This file owns:
 *   • adapter state (running/notice/display-pref fields)
 *   • public API surface (set* methods)
 *   • [onCreateViewHolder] / [onBindViewHolder] orchestration
 *   • payload constants
 *
 * Domain-specific logic is in sibling extension files:
 *   • TaskViewHolder.kt            — ViewHolder field declarations
 *   • TaskDiffCallback.kt          — DiffUtil.ItemCallback
 *   • Formatters.kt     — pure duration / SI format functions
 *   • UnitFormat.kt     — unitFormatEnabled-aware fmtFloat/Int/Dur
 *   • CardScale.kt   — applyCardScale / applyCompactMode / applySimpleMode
 *   • BindHelpers.kt    — priority label, pill colour, quota bind, bindQuotaOnly
 *   • NoticeSegments.kt — segmented notice-bar build and fill-update
 */
class TaskAdapter(
    private val onTaskClick:        (Task) -> Unit,
    private val onTaskLongClick:    (Task) -> Unit = {},
    private val onDeleteClick:      (Task) -> Unit,
    private val onCompleteClick:    (Task) -> Unit,
    private val onRunClick:         (TaskDisplayItem) -> Unit,
    private val onGroupToggle:      (TaskDisplayItem) -> Unit,   // expand / collapse this group only
    private val onGroupToggleDeep:  (Task) -> Unit = {},  // expand / collapse this group + all descendants (long-press)
    private val onResetSliceClick:  (Task) -> Unit = {},
    private val onRevertClick:      (Task) -> Unit = {},
    /** Symlink row's timer icon tapped — must navigate to the target's real location, never run anything here. */
    private val onSymlinkNavigate:  (TaskDisplayItem) -> Unit = {},
    /** Hardlink placement row's timer icon tapped — selects the task credited to THIS placement (see TaskMembership). */
    private val onMembershipRunClick: (TaskDisplayItem) -> Unit = {},
    /** Symlink row's delete button — removes only this pointer, never the real task. */
    private val onSymlinkDelete:      (TaskDisplayItem) -> Unit = {},
    /** Hardlink row's delete button — removes only this one placement, never the real task or its other placements. */
    private val onMembershipDelete:   (TaskDisplayItem) -> Unit = {},
    private val showScheduleRank:   Boolean = false,
    private val isCompletedTab:     Boolean = false,
    /** Returns the expanded state for a group task id — used for rotation icon. */
    private val expandStateProvider: (String) -> Boolean = { true }
) : ListAdapter<TaskDisplayItem, TaskViewHolder>(DiffCallback()) {

    // ── Running / notice state ────────────────────────────────────────────────
    internal var runningTaskId:      String?      = null
    // Current notice state — used by buildNoticeSegments to render live progress.
    // Updated via setNoticeState() called from MainActivity's noticePhase observer.
    internal var noticeTaskId:       String?      = null
    internal var currentNoticePhase: NoticePhase  = NoticePhase.Idle
    // Persists the last non-Idle phase per task so segments stay filled
    // after pause or cancel (instead of resetting to empty track).
    // Keyed by task.id; entries survive the run until overwritten by a new
    // non-Idle phase (i.e. the next run naturally replaces stale progress).
    internal val persistedPhaseByTask = mutableMapOf<String, NoticePhase>()

    // ── UI Customization state ────────────────────────────────────────────────
    /**
     * When true, hides non-essential EEVDF stats (VRT, VDL, RS, Runs, TRT) to
     * save space in floating / PiP window mode.
     */
    var hideNonEssentialStats: Boolean = false
        internal set

    /**
     * When true, non-selected cards collapse rows 0 (progress bars), 1 (TRT/time
     * slice), and 2 (VRT/VDL/RS/Runs). The running task and the user-tapped task
     * are always considered "selected" and show all rows.
     */
    var simpleModeEnabled: Boolean = false
        internal set

    /**
     * When true, VRT/VDL use SI float suffixes, Runs uses SI integer (no ".00"),
     * and TRT shows only the 2 most-significant non-zero time units.
     * RS, quota, DL/RT badges, and priority are intentionally untouched.
     */
    var unitFormatEnabled: Boolean = false
        internal set

    /**
     * The id of the card the user last tapped while simple mode is active.
     * null means no explicit tap selection — only the running task is expanded.
     */
    internal var selectedTaskId: String? = null

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Apply a new compact-mode configuration and trigger a full rebind so all
     * visible items reflect it immediately. Padding/margin scale is no longer
     * adapter-tracked state — CardScale.applyCardScale reads the live
     * LayoutTokenPrefs directly at bind time, the same way the shared card-view
     * components (CardDensity) and the timer/alarm cards (DisplayScaleDelegate)
     * already do, so a single shared "scale" int here can't represent padding
     * and margin moving independently, which they now do.
     *
     * ALWAYS rebinds now, not only when [compact] itself changes. Found and
     * fixed as a direct consequence of the above: since padding/margin are no
     * longer tracked here, this adapter has no local way to know whether
     * either one changed since the last call — only that [compact] did or
     * didn't. Before this fix, adjusting a scale slider on the Layout demo
     * page correctly updated that page instantly, but the main task list
     * only picked it up after a full app restart, because nothing ever told
     * its already-bound rows to rebind. This function already runs on every
     * `onResume` (not a hot path), so an unconditional rebind here is cheap —
     * the cost this guarded against no longer applies once the thing being
     * guarded no longer changes state this class can observe.
     */
    fun setCompactMode(compact: Boolean) {
        hideNonEssentialStats = compact
        notifyDataSetChanged()
    }

    /**
     * Enable or disable Simple Mode. Clears any explicit tap-selection so the
     * list starts fresh with only the running task expanded.
     */
    fun setSimpleMode(enabled: Boolean) {
        if (enabled == simpleModeEnabled) return
        simpleModeEnabled = enabled
        if (!enabled) selectedTaskId = null   // reset tap selection when turning off
        notifyDataSetChanged()
    }

    /** Toggle Unit Format — rebinds only when the value actually changes. */
    fun setUnitFormat(enabled: Boolean) {
        if (enabled == unitFormatEnabled) return
        unitFormatEnabled = enabled
        notifyDataSetChanged()
    }

    /**
     * Mark [taskId] as the user-selected card in Simple Mode (tapped).
     * Passing null deselects. The old selected card is also refreshed.
     */
    fun setSelectedTask(taskId: String?) {
        if (taskId == selectedTaskId) {
            // Tap same card again → deselect (collapse it back)
            val old = selectedTaskId
            selectedTaskId = null
            notifyItemChanged(positionOf(old))
        } else {
            val old = selectedTaskId
            selectedTaskId = taskId
            notifyItemChanged(positionOf(old))
            notifyItemChanged(positionOf(taskId))
        }
    }

    fun setRunningTask(id: String?) {
        val old = runningTaskId
        runningTaskId = id
        if (simpleModeEnabled) {
            // In simple mode both old and new running cards need a full rebind
            // so their expanded/collapsed state updates immediately.
            notifyItemChanged(positionOf(old))
            notifyItemChanged(positionOf(id))
        } else {
            notifyDataSetChanged()
        }
    }

    /**
     * Updates the active notice phase so [buildNoticeSegments] can render live
     * progress.  Call this from MainActivity's noticePhase observer on every
     * phase change (including per-second Wait ticks).
     *
     * Triggers a targeted rebind of the notice task card so the segmented bar
     * redraws without rebinding the whole list.
     */
    fun setNoticeState(taskId: String?, phase: NoticePhase) {
        val oldTaskId = noticeTaskId
        val oldPhase  = currentNoticePhase
        noticeTaskId       = taskId
        currentNoticePhase = phase

        // Persist Execute / Wait / Expired phases so buildNoticeSegments can show
        // the last frozen progress after a pause, cancel, or while Delay is running.
        // Delay is explicitly excluded: its ticks must NOT overwrite the prior
        // Execute/Wait snapshot — that snapshot is the fallback the Delay branch
        // in buildNoticeSegments reads to keep segments visible during the countdown.
        // Idle is excluded for the same reason (phase reset should not erase history).
        if (taskId != null
            && phase !is NoticePhase.Idle
            && phase !is NoticePhase.Delay) {
            persistedPhaseByTask[taskId] = phase
        }

        // When the active notice task changes, fully rebind the OLD task card so
        // its bar resets — stale progress from a different run should not linger.
        if (oldTaskId != null && oldTaskId != taskId) {
            persistedPhaseByTask.remove(oldTaskId)
            notifyItemChanged(positionOf(oldTaskId))
        }

        // Decide whether to do a FULL rebind or a lightweight FILL-ONLY update.
        //
        // FULL rebind (null payload) is needed when:
        //   • the task itself changed (different id)
        //   • the phase TYPE changed (e.g. Execute → Wait, Wait → Execute)
        //     because the active segment index shifts and may change colour
        //
        // FILL-ONLY (PAYLOAD_NOTICE_TICK) is sufficient when:
        //   • same task, same phase type — only the fill level inside the
        //     active segment changed (a Wait or Delay second elapsed)
        //   This path calls updateNoticeSegmentFills which mutates ClipDrawable
        //   levels in-place without touching the view hierarchy → no flicker.
        val sameTask = (taskId == oldTaskId)
        val sameType = when {
            phase is NoticePhase.Wait  && oldPhase is NoticePhase.Wait  -> true
            phase is NoticePhase.Delay && oldPhase is NoticePhase.Delay -> true
            else -> false
        }
        if (sameTask && sameType) {
            notifyItemChanged(positionOf(taskId), PAYLOAD_NOTICE_TICK)
        } else {
            notifyItemChanged(positionOf(taskId))
        }
    }

    // ── Adapter internals ─────────────────────────────────────────────────────

    /** Helper: find the adapter position for a task id, or -1. */
    internal fun positionOf(taskId: String?): Int {
        if (taskId == null) return -1
        for (i in 0 until itemCount) {
            if (getItem(i).task.id == taskId) return i
        }
        return -1
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TaskViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_task, parent, false)
        return TaskViewHolder(view)
    }

    override fun onBindViewHolder(holder: TaskViewHolder, position: Int) {
        val item  = getItem(position)
        val task  = item.task
        val isRunning = task.id == runningTaskId

        // ── Depth indentation ─────────────────────────────────────────────────
        val density = holder.itemView.context.resources.displayMetrics.density
        val params  = holder.itemView.layoutParams as RecyclerView.LayoutParams
        val basePx  = (10 * density).toInt()
        params.marginStart = basePx + (item.depth * 10 * density).toInt()
        holder.itemView.layoutParams = params

        // ── UI Customization: card height scale ───────────────────────────────
        applyCardScale(holder, density)

        // ── Common fields ──────────────────────────────────────────────────────
        // Symlinks are pure pointers: always show the target's live name, with
        // a small arrow prefix so they read as shortcuts, not real entries.
        holder.tvName.text     = if (item.symlinkId != null) "\u21A6 ${task.name}" else task.name
        // Broken link (target deleted — see TaskLink doc comment): dim the
        // whole row so it visually reads as inert. Reset for every other row
        // since views are recycled.
        holder.itemView.alpha = if (item.isBrokenLink) 0.5f else 1f
        bindPriorityLabel(holder.tvPriority, task, priorityColor(holder, task))
        // Membership (hardlink) rows show THIS placement's own vrt/vdl — see
        // TaskDisplayItem.displayVruntime's doc comment for why these live as
        // separate cosmetic fields rather than ever being written onto `task`
        // itself: task.vruntime must stay the real row's true value so it's
        // always safe to seed as the running task and persist unmodified.
        val displayVrt = item.displayVruntime        ?: task.vruntime
        val displayVdl = item.displayVirtualDeadline  ?: task.virtualDeadline
        holder.tvVruntime.text  = "VRT: ${fmtFloat(displayVrt)}"
        holder.tvVdeadline.text = "VDL: ${fmtFloat(displayVdl)}"
        val pinned = task.pinnedShare != null
        holder.tvCpuShare.text = "RS: ${"%.1f".format(item.cpuShare)}"
        holder.tvCpuShare.setTextColor(
            if (pinned) androidx.core.content.ContextCompat.getColor(holder.itemView.context, R.color.pinActive)
            else        androidx.core.content.ContextCompat.getColor(holder.itemView.context, R.color.pinInactive)
        )

        // ── Group vs leaf rendering ────────────────────────────────────────────
        if (task.isGroup) {
            // Group header row
            holder.tvCategory.text  = buildCategoryLine(item.childGroupCount, item.childTaskCount, task.category)
            holder.tvTimeSlice.text = "TRT: ${fmtDur(task.totalRunTime)}"
            holder.tvRemaining.text = "VRT: ${fmtFloat(displayVrt)}"
            holder.tvRunCount.text  = "Runs: ${fmtInt(task.runCount)}"
            holder.progressBar.visibility    = View.GONE
            holder.progressNotice.visibility = View.GONE  // fix: hide notice bar on recycled group ViewHolders
            holder.btnRun.visibility         = View.GONE
            holder.btnComplete.visibility    = View.GONE
            holder.btnResetSlice.visibility  = View.GONE
            holder.btnRevert.visibility      = View.GONE
            holder.btnGroupToggle.visibility = View.VISIBLE
            if (item.symlinkId != null) {
                // A symlinked GROUP is a single pointer row — its target's real
                // children are never rendered nested under it (see
                // ListBuilderDelegate: link rows are leaf entries only, no
                // recursion). Toggling this row's arrow must never silently
                // flip the REAL group's expand state elsewhere, so it also
                // navigates instead of expanding in place.
                holder.btnGroupToggle.rotation = 0f
                holder.btnGroupToggle.setOnClickListener { onSymlinkNavigate(item) }
                holder.btnGroupToggle.setOnLongClickListener { true }
            } else {
                // Rotate play icon: 180° = pointing down (expanded), 0° = pointing right (collapsed)
                holder.btnGroupToggle.rotation = if (expandStateProvider(task.id)) 180f else 0f
                holder.btnGroupToggle.setOnClickListener { onGroupToggle(item) }
                holder.btnGroupToggle.setOnLongClickListener { onGroupToggleDeep(task); true }
            }
        } else {
            // Leaf task row
            holder.tvCategory.text  = buildCategoryLine(item.childGroupCount, item.childTaskCount, task.category)
            holder.tvTimeSlice.text = "TRT: ${fmtDur(task.totalRunTime)}"
            holder.tvRemaining.text = task.remainingDisplay
            holder.tvRunCount.text  = "Runs: ${fmtInt(task.runCount)}"
            holder.btnGroupToggle.visibility = View.GONE  // fix: hide group toggle on recycled leaf ViewHolders
            // NOTIFICATION tasks: show segmented bar (execute=blue, wait=green) with live fill.
            // All other types: show the standard single-colour progress bar.
            if (task.taskType == "NOTIFICATION") {
                holder.progressBar.visibility    = View.GONE
                holder.progressNotice.visibility = View.VISIBLE
                buildNoticeSegments(holder.progressNotice, task)
            } else {
                holder.progressBar.visibility    = View.VISIBLE
                holder.progressBar.progress      = task.progressPercent
                holder.progressNotice.visibility = View.GONE
            }
            if (isCompletedTab) {
                // Completed tab: show only Revert + Delete, hide all active-only actions
                holder.btnRevert.visibility     = View.VISIBLE
                holder.btnRun.visibility        = View.GONE
                holder.btnComplete.visibility   = View.GONE
                holder.btnResetSlice.visibility = View.GONE
                holder.btnRevert.setOnClickListener { onRevertClick(task) }
            } else if (item.symlinkId != null) {
                // Symlink row: pure pointer. No complete/reset from here — those
                // are the real task's own actions at its real location. Tapping
                // the timer icon navigates there instead of running anything.
                // A BROKEN link (target deleted — see TaskLink doc comment) has
                // nowhere to navigate to, so the button is disabled entirely
                // rather than routing into a synthetic placeholder id.
                holder.btnRun.visibility         = View.VISIBLE
                holder.btnComplete.visibility    = View.GONE
                holder.btnResetSlice.visibility  = View.GONE
                if (item.isBrokenLink) {
                    holder.btnRun.isEnabled = false
                    holder.btnRun.alpha     = 0.4f
                    holder.btnRun.setOnClickListener(null)
                } else {
                    holder.btnRun.isEnabled = true
                    holder.btnRun.alpha     = 1f
                    holder.btnRun.setOnClickListener { onSymlinkNavigate(item) }
                }
            } else if (item.membershipId != null) {
                // Hardlink placement row: a real, complete task here — full
                // action set — but runtime/vruntime must credit THIS placement,
                // so btnRun goes through onMembershipRunClick, not onRunClick.
                holder.btnRun.visibility         = View.VISIBLE
                holder.btnComplete.visibility    = View.VISIBLE
                holder.btnResetSlice.visibility  =
                    if (task.remainingSeconds < task.timeSliceSeconds) View.VISIBLE else View.GONE
                holder.btnRun.setOnClickListener        { onMembershipRunClick(item) }
                holder.btnComplete.setOnClickListener   { onCompleteClick(task) }
                holder.btnResetSlice.setOnClickListener { onResetSliceClick(task) }
            } else {
                // Active / schedule tabs
                holder.btnRevert.visibility      = View.GONE
                holder.btnRun.visibility         = View.VISIBLE
                holder.btnComplete.visibility    = View.VISIBLE
                // Show reset button only when the slice has been partially consumed
                holder.btnResetSlice.visibility  =
                    if (task.remainingSeconds < task.timeSliceSeconds) View.VISIBLE else View.GONE
                holder.btnRun.setOnClickListener        { onRunClick(item) }
                holder.btnComplete.setOnClickListener   { onCompleteClick(task) }
                holder.btnResetSlice.setOnClickListener { onResetSliceClick(task) }
            }
        }

        // Button visibility for this bind is now final — safe to compute gaps.
        applyColumnGap(holder, density)

        // ── Schedule / queue rank — shown for DL-active or RT-active tasks at rank #1 ──
        val isRtActive = item.isRtActive
        if ((item.isDlActive || isRtActive) && !task.isGroup && item.queueNumber == "1") {
            holder.tvRank.visibility = View.VISIBLE
            holder.tvRank.text = "#1"
            holder.tvRank.setTextColor(androidx.core.content.ContextCompat.getColor(
                holder.itemView.context,
                if (item.isDlActive) R.color.rankDl else R.color.rankRt
            ))
        } else {
            holder.tvRank.visibility = View.GONE
        }

        // ── Running state ──────────────────────────────────────────────────────
        holder.viewRunning.visibility = if (isRunning) View.VISIBLE else View.INVISIBLE

        // ── DL budget pill (no emojis, amber / grey) ───────────────────────────
        if (task.isDlConfigured) {
            holder.tvDlStatus.visibility = View.VISIBLE
            val dlActive = task.isDlBudgetActive
            holder.tvDlStatus.text = if (dlActive) {
                formatDlDuration(task.dlRuntimeRemainingSeconds)
            } else {
                val periodRem = task.dlPeriodRemainingSeconds
                if (periodRem > 0) formatDlDuration(periodRem) else "done"
            }
            applyPillColor(holder.tvDlStatus, holder.itemView.context,
                if (dlActive) R.color.pillDlActive else R.color.pillInactive)
        } else {
            holder.tvDlStatus.visibility = View.GONE
        }

        // ── RT window pill (green = active, grey = pending / inactive) ─────────
        if (task.isRtConfigured) {
            holder.tvRtStatus.visibility = View.VISIBLE
            val rtWindowActive = RtScheduler.isRtWindowActive(task)
            if (rtWindowActive) {
                val secsLeft = RtScheduler.nextDeactivationMs(task) / 1_000L
                holder.tvRtStatus.text = "RT · ${formatDlDuration(secsLeft)}"
                applyPillColor(holder.tvRtStatus, holder.itemView.context, R.color.pillRtActive)
            } else {
                val secsUntil = RtScheduler.nextActivationMs(task) / 1_000L
                holder.tvRtStatus.text = if (secsUntil < Long.MAX_VALUE / 1_000L)
                    "RT in ${formatDlDuration(secsUntil)}" else "RT · off"
                applyPillColor(holder.tvRtStatus, holder.itemView.context, R.color.pillInactive)
            }
        } else {
            holder.tvRtStatus.visibility = View.GONE
        }

        // ── Quota display ──────────────────────────────────────────────────────
        val quotaExceeded = item.effectiveQuotaExceeded
        val quotaWarning  = item.effectiveQuotaWarning
        if (task.isQuotaEnabled) {
            val remaining = task.quotaRemainingSeconds
            holder.tvQuotaRemaining.visibility = View.VISIBLE
            holder.tvQuotaRemaining.text = when {
                quotaExceeded -> "-${formatQuota(task.quotaOverflowSeconds)}"
                else          -> "+${formatQuota(remaining)}"
            }
            holder.tvQuotaRemaining.setTextColor(
                androidx.core.content.ContextCompat.getColor(
                    holder.itemView.context,
                    when {
                        quotaExceeded -> R.color.quotaTextExceeded
                        quotaWarning  -> R.color.quotaTextWarning
                        else          -> R.color.quotaTextNormal
                    }
                )
            )
            // Quota progress bar
            holder.progressQuota.visibility = View.VISIBLE
            holder.progressQuota.progress   = task.quotaProgressPercent
            val quotaBarColor = androidx.core.content.ContextCompat.getColor(
                holder.itemView.context,
                when {
                    quotaExceeded -> R.color.quotaBarExceeded
                    quotaWarning  -> R.color.quotaBarWarning
                    else          -> R.color.quotaBarNormal
                }
            )
            holder.progressQuota.progressTintList =
                android.content.res.ColorStateList.valueOf(quotaBarColor)
            // Tighten gap when both bars are showing; restore normal spacing when alone
            setQuotaBarTopMargin(holder, bothBarsVisible = holder.progressBar.visibility == View.VISIBLE || holder.progressNotice.visibility == View.VISIBLE)
        } else {
            holder.tvQuotaRemaining.visibility = View.GONE
            holder.progressQuota.visibility    = View.GONE
        }

        // ── UI Customization: hide non-essential stats in compact / floating mode ──
        applyCompactMode(holder, hideNonEssentialStats)

        // ── UI Customization: simple mode — collapse rows on non-selected cards ──
        val isSelected = task.id == selectedTaskId || task.id == runningTaskId
        applySimpleMode(holder, simpleModeEnabled, isSelected, hideNonEssentialStats)

        // ── Card highlight ─────────────────────────────────────────────────────
        val isDlActive = task.isDlBudgetActive
        holder.card.cardElevation = when {
            isRunning  -> 12f
            isDlActive -> 8f
            isRtActive -> 7f
            else       -> 4f
        }
        val ctx = holder.itemView.context
        holder.card.setCardBackgroundColor(
            androidx.core.content.ContextCompat.getColor(ctx, when {
                isRunning         -> R.color.cardStateRunning
                item.isJumpHighlighted -> R.color.cardStateJumpHighlight
                isDlActive        -> R.color.cardStateDl
                isRtActive        -> R.color.cardStateRt
                quotaExceeded     -> R.color.cardStateQuotaExceeded
                quotaWarning      -> R.color.cardStateQuotaWarning
                task.isGroup      -> R.color.cardStateGroup
                else              -> R.color.cardBackground
            })
        )

        // In simple mode a card tap expands/collapses it; forward to caller too.
        holder.card.setOnClickListener {
            if (simpleModeEnabled) setSelectedTask(task.id)
            onTaskClick(task)
        }
        // Link rows never edit/delete the real underlying task from here — a
        // symlink has no config of its own to edit, and "delete" on ANY link
        // row must remove only that one pointer/placement, never cascade into
        // deleting the real task everywhere else it lives.
        when {
            item.symlinkId != null -> {
                holder.card.setOnLongClickListener { true }   // no-op: nothing of its own to edit
                holder.btnDelete.setOnClickListener { onSymlinkDelete(item) }
            }
            item.membershipId != null -> {
                holder.card.setOnLongClickListener { onTaskLongClick(task); true }  // shared config IS editable
                holder.btnDelete.setOnClickListener { onMembershipDelete(item) }
            }
            else -> {
                holder.card.setOnLongClickListener { onTaskLongClick(task); true }
                holder.btnDelete.setOnClickListener { onDeleteClick(task) }
            }
        }
    }

    override fun onBindViewHolder(
        holder: TaskViewHolder, position: Int, payloads: MutableList<Any>
    ) {
        when {
            payloads.any { it == PAYLOAD_NOTICE_TICK } -> {
                val item = getItem(position) ?: return
                // Only repaint segment fill levels — no view creation/removal.
                // This prevents the flicker caused by removeAllViews()+addView()
                // on every Wait/Delay tick while the card structure is unchanged.
                updateNoticeSegmentFills(holder.progressNotice, item.task)
            }
            payloads.any { it == PAYLOAD_QUOTA_TICK } -> {
                val item = getItem(position) ?: return
                bindQuotaOnly(holder, item)
            }
            else -> super.onBindViewHolder(holder, position, payloads)
        }
    }

    // ── Category line builder ────────────────────────────────────────────────

    /**
     * Builds the category row string shared by both group and leaf task cards.
     * Zero-value counts are omitted. Category "None" is omitted.
     * Segments joined with " | "; returns "" when all suppress (tvCategory blank).
     *
     * G=2, T=5, General  →  "G: 2 | T: 5 | General"
     * G=0, T=3, General  →  "T: 3 | General"
     * G=0, T=0, None     →  ""
     */
    private fun buildCategoryLine(groupCount: Int, taskCount: Int, category: String): String {
        val parts = mutableListOf<String>()
        if (groupCount > 0)     parts.add("G: $groupCount")
        if (taskCount  > 0)     parts.add("T: $taskCount")
        if (category != "None") parts.add(category)
        return parts.joinToString(" | ")
    }

    // ── Payload constants ─────────────────────────────────────────────────────

    companion object {
        /** Payload used for the 1-second quota tick — skips full rebind to avoid flicker. */
        const val PAYLOAD_QUOTA_TICK  = "quota_tick"
        const val PAYLOAD_NOTICE_TICK = "notice_tick"
    }
}
