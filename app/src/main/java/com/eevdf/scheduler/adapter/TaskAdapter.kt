package com.eevdf.scheduler.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.eevdf.scheduler.R
import com.eevdf.scheduler.model.notice.NoticePhase
import com.eevdf.scheduler.model.task.Task
import com.eevdf.scheduler.model.task.TaskDisplayItem
import com.eevdf.scheduler.scheduler.RtScheduler

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
 *   • TaskAdapterFormatters.kt     — pure duration / SI format functions
 *   • TaskAdapterUnitFormat.kt     — unitFormatEnabled-aware fmtFloat/Int/Dur
 *   • TaskAdapterDisplayPrefs.kt   — applyCardScale / applyCompactMode / applySimpleMode
 *   • TaskAdapterBindHelpers.kt    — priority label, pill colour, quota bind, bindQuotaOnly
 *   • TaskAdapterNoticeSegments.kt — segmented notice-bar build and fill-update
 */
class TaskAdapter(
    private val onTaskClick:        (Task) -> Unit,
    private val onTaskLongClick:    (Task) -> Unit = {},
    private val onDeleteClick:      (Task) -> Unit,
    private val onCompleteClick:    (Task) -> Unit,
    private val onRunClick:         (Task) -> Unit,
    private val onGroupToggle:      (Task) -> Unit,   // expand / collapse this group only
    private val onGroupToggleDeep:  (Task) -> Unit = {},  // expand / collapse this group + all descendants (long-press)
    private val onResetSliceClick:  (Task) -> Unit = {},
    private val onRevertClick:      (Task) -> Unit = {},
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
    /** Card height scale: 1 (smallest / most compact) … 5 (default full size). */
    var cardHeightScale: Int = 5
        internal set

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
     * Apply a new display configuration and trigger a full rebind so all visible
     * items reflect the updated scale and compact mode immediately.
     */
    fun setDisplayPrefs(scale: Int, compact: Boolean) {
        val changed = scale != cardHeightScale || compact != hideNonEssentialStats
        cardHeightScale       = scale.coerceIn(1, 5)
        hideNonEssentialStats = compact
        if (changed) notifyDataSetChanged()
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
        params.marginStart = (item.depth * 20 * density).toInt()
        holder.itemView.layoutParams = params

        // ── UI Customization: card height scale ───────────────────────────────
        applyCardScale(holder, cardHeightScale, density)

        // ── Common fields ──────────────────────────────────────────────────────
        holder.tvName.text     = task.name
        bindPriorityLabel(holder.tvPriority, task, priorityColor(holder, task))
        holder.tvVruntime.text  = "VRT: ${fmtFloat(task.vruntime)}"
        holder.tvVdeadline.text = "VDL: ${fmtFloat(task.virtualDeadline)}"
        val pinned = task.pinnedShare != null
        holder.tvCpuShare.text = "RS: ${"%.1f".format(item.cpuShare)}"
        holder.tvCpuShare.setTextColor(
            if (pinned) Color.parseColor("#FF9800")
            else        Color.parseColor("#BDBDBD")
        )

        // ── Group vs leaf rendering ────────────────────────────────────────────
        if (task.isGroup) {
            // Group header row
            holder.tvCategory.text  = "Group · ${item.childCount} task${if (item.childCount != 1) "s" else ""}"
            holder.tvTimeSlice.text = "TRT: ${fmtDur(item.childTotalRuntime)}"
            holder.tvRemaining.text = "VRT: ${fmtFloat(task.vruntime)}"
            holder.tvRunCount.text  = "Runs: ${fmtInt(task.runCount)}"
            holder.progressBar.visibility    = View.GONE
            holder.progressNotice.visibility = View.GONE  // fix: hide notice bar on recycled group ViewHolders
            holder.btnRun.visibility         = View.GONE
            holder.btnComplete.visibility    = View.GONE
            holder.btnResetSlice.visibility  = View.GONE
            holder.btnRevert.visibility      = View.GONE
            holder.btnGroupToggle.visibility = View.VISIBLE
            // Rotate play icon: 180° = pointing down (expanded), 0° = pointing right (collapsed)
            holder.btnGroupToggle.rotation = if (expandStateProvider(task.id)) 180f else 0f
            holder.btnGroupToggle.setOnClickListener { onGroupToggle(task) }
            holder.btnGroupToggle.setOnLongClickListener { onGroupToggleDeep(task); true }
        } else {
            // Leaf task row
            holder.tvCategory.text  = task.category
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
            } else {
                // Active / schedule tabs
                holder.btnRevert.visibility      = View.GONE
                holder.btnRun.visibility         = View.VISIBLE
                holder.btnComplete.visibility    = View.VISIBLE
                // Show reset button only when the slice has been partially consumed
                holder.btnResetSlice.visibility  =
                    if (task.remainingSeconds < task.timeSliceSeconds) View.VISIBLE else View.GONE
                holder.btnRun.setOnClickListener        { onRunClick(task) }
                holder.btnComplete.setOnClickListener   { onCompleteClick(task) }
                holder.btnResetSlice.setOnClickListener { onResetSliceClick(task) }
            }
        }

        // ── Schedule / queue rank — shown for DL-active or RT-active tasks at rank #1 ──
        val isRtActive = item.isRtActive
        if ((item.isDlActive || isRtActive) && !task.isGroup && item.queueNumber == "1") {
            holder.tvRank.visibility = View.VISIBLE
            holder.tvRank.text = "#1"
            holder.tvRank.setTextColor(Color.parseColor(if (item.isDlActive) "#1565C0" else "#1B5E20"))
        } else {
            holder.tvRank.visibility = View.GONE
        }

        // ── Running state ──────────────────────────────────────────────────────
        holder.viewRunning.visibility = if (isRunning) View.VISIBLE else View.INVISIBLE

        // ── DL budget pill (no emojis, amber / grey) ───────────────────────────
        if (task.isDlConfigured && !task.isGroup) {
            holder.tvDlStatus.visibility = View.VISIBLE
            val dlActive = task.isDlBudgetActive
            holder.tvDlStatus.text = if (dlActive) {
                formatDlDuration(task.dlRuntimeRemainingSeconds)
            } else {
                val periodRem = task.dlPeriodRemainingSeconds
                if (periodRem > 0) formatDlDuration(periodRem) else "done"
            }
            applyPillColor(holder.tvDlStatus, holder.itemView.context,
                if (dlActive) "#E65100" else "#78909C")
        } else {
            holder.tvDlStatus.visibility = View.GONE
        }

        // ── RT window pill (green = active, grey = pending / inactive) ─────────
        if (task.isRtConfigured && !task.isGroup) {
            holder.tvRtStatus.visibility = View.VISIBLE
            val rtWindowActive = RtScheduler.isRtWindowActive(task)
            if (rtWindowActive) {
                val secsLeft = RtScheduler.nextDeactivationMs(task) / 1_000L
                holder.tvRtStatus.text = "RT · ${formatDlDuration(secsLeft)}"
                applyPillColor(holder.tvRtStatus, holder.itemView.context, "#1B5E20")
            } else {
                val secsUntil = RtScheduler.nextActivationMs(task) / 1_000L
                holder.tvRtStatus.text = if (secsUntil < Long.MAX_VALUE / 1_000L)
                    "RT in ${formatDlDuration(secsUntil)}" else "RT · off"
                applyPillColor(holder.tvRtStatus, holder.itemView.context, "#78909C")
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
                when {
                    quotaExceeded -> Color.parseColor("#E65100")
                    quotaWarning  -> Color.parseColor("#F57C00")
                    else          -> Color.parseColor("#757575")
                }
            )
            // Quota progress bar
            holder.progressQuota.visibility = View.VISIBLE
            holder.progressQuota.progress   = task.quotaProgressPercent
            val quotaBarTint = when {
                quotaExceeded -> "#E53935"
                quotaWarning  -> "#FFA000"
                else          -> "#66BB6A"
            }
            holder.progressQuota.progressTintList =
                android.content.res.ColorStateList.valueOf(Color.parseColor(quotaBarTint))
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
        holder.card.setCardBackgroundColor(
            when {
                isRunning        -> Color.parseColor("#E3F2FD")  // light-blue  (selected/running)
                isDlActive       -> Color.parseColor("#FFEBEE")  // light-red   (DL deadline priority)
                isRtActive       -> Color.parseColor("#E8F5E9")  // light-green (RT window active)
                quotaExceeded    -> Color.parseColor("#FFFDE7")  // light-yellow (quota exceeded)
                quotaWarning     -> Color.parseColor("#FFF8E1")  // light-amber  (quota warning)
                task.isGroup     -> Color.parseColor("#F5F5F5")
                else             -> Color.WHITE
            }
        )

        // In simple mode a card tap expands/collapses it; forward to caller too.
        holder.card.setOnClickListener {
            if (simpleModeEnabled) setSelectedTask(task.id)
            onTaskClick(task)
        }
        holder.card.setOnLongClickListener { onTaskLongClick(task); true }
        holder.btnDelete.setOnClickListener { onDeleteClick(task) }
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

    // ── Payload constants ─────────────────────────────────────────────────────

    companion object {
        /** Payload used for the 1-second quota tick — skips full rebind to avoid flicker. */
        const val PAYLOAD_QUOTA_TICK  = "quota_tick"
        const val PAYLOAD_NOTICE_TICK = "notice_tick"
    }
}
