package com.eevdf.scheduler.adapter

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.eevdf.scheduler.R
import com.eevdf.scheduler.model.notice.NoticePhase
import com.eevdf.scheduler.model.task.Task
import com.eevdf.scheduler.model.task.TaskDisplayItem
import com.eevdf.scheduler.scheduler.RtScheduler

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
) : ListAdapter<TaskDisplayItem, TaskAdapter.TaskViewHolder>(DiffCallback()) {

    private var runningTaskId:      String?      = null
    // Current notice state — used by buildNoticeSegments to render live progress.
    // Updated via setNoticeState() called from MainActivity's noticePhase observer.
    private var noticeTaskId:       String?      = null
    private var currentNoticePhase: NoticePhase  = NoticePhase.Idle

    // ── UI Customization state ────────────────────────────────────────────────
    /** Card height scale: 1 (smallest / most compact) … 5 (default full size). */
    var cardHeightScale: Int = 5
        private set

    /**
     * When true, hides non-essential EEVDF stats (VRT, VDL, RS, Runs, TRT) to
     * save space in floating / PiP window mode.
     */
    var hideNonEssentialStats: Boolean = false
        private set

    /**
     * When true, non-selected cards collapse rows 0 (progress bars), 1 (TRT/time
     * slice), and 2 (VRT/VDL/RS/Runs). The running task and the user-tapped task
     * are always considered "selected" and show all rows.
     */
    var simpleModeEnabled: Boolean = false
        private set

    /**
     * When true, VRT/VDL use SI float suffixes, Runs uses SI integer (no ".00"),
     * and TRT shows only the 2 most-significant non-zero time units.
     * RS, quota, DL/RT badges, and priority are intentionally untouched.
     */
    var unitFormatEnabled: Boolean = false
        private set

    /**
     * The id of the card the user last tapped while simple mode is active.
     * null means no explicit tap selection — only the running task is expanded.
     */
    private var selectedTaskId: String? = null

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

    /** Helper: find the adapter position for a task id, or -1. */
    private fun positionOf(taskId: String?): Int {
        if (taskId == null) return -1
        for (i in 0 until itemCount) {
            if (getItem(i).task.id == taskId) return i
        }
        return -1
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
        val old = noticeTaskId
        noticeTaskId       = taskId
        currentNoticePhase = phase
        // Redraw old notice card (clears stale fill if task changed)
        if (old != taskId) notifyItemChanged(positionOf(old))
        notifyItemChanged(positionOf(taskId))
    }

    class TaskViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val card:              CardView     = itemView.findViewById(R.id.cardTask)
        val tvRank:            TextView     = itemView.findViewById(R.id.tvRank)
        val tvName:            TextView     = itemView.findViewById(R.id.tvTaskName)
        val tvCategory:        TextView     = itemView.findViewById(R.id.tvCategory)
        val tvPriority:        TextView     = itemView.findViewById(R.id.tvPriority)
        val tvQuotaRemaining:  TextView     = itemView.findViewById(R.id.tvQuotaRemaining)
        val tvDlStatus:        TextView     = itemView.findViewById(R.id.tvDlStatus)
        val tvRtStatus:        TextView     = itemView.findViewById(R.id.tvRtStatus)
        val tvTimeSlice:       TextView     = itemView.findViewById(R.id.tvTimeSlice)
        val tvRemaining:       TextView     = itemView.findViewById(R.id.tvRemaining)
        val tvVruntime:        TextView     = itemView.findViewById(R.id.tvVruntime)
        val tvVdeadline:       TextView     = itemView.findViewById(R.id.tvVdeadline)
        val tvCpuShare:        TextView     = itemView.findViewById(R.id.tvCpuShare)
        val progressBar:       ProgressBar  = itemView.findViewById(R.id.progressTask)
        val progressNotice:    LinearLayout = itemView.findViewById(R.id.progressNotice)
        val progressQuota:     ProgressBar  = itemView.findViewById(R.id.progressQuota)
        val btnDelete:         ImageButton  = itemView.findViewById(R.id.btnDelete)
        val btnComplete:       ImageButton  = itemView.findViewById(R.id.btnComplete)
        val btnRun:            ImageButton  = itemView.findViewById(R.id.btnRun)
        val btnGroupToggle:    ImageButton  = itemView.findViewById(R.id.btnGroupToggle)
        val btnResetSlice:     ImageButton  = itemView.findViewById(R.id.btnResetSlice)
        val btnRevert:         ImageButton  = itemView.findViewById(R.id.btnRevert)
        val tvRunCount:        TextView     = itemView.findViewById(R.id.tvRunCount)
        val viewRunning:       View         = itemView.findViewById(R.id.viewRunningIndicator)
        // ── UI Customization: layout containers for spacing & compact mode ────
        val layoutCardContent: LinearLayout = itemView.findViewById(R.id.layoutCardContent)
        val rowTimeInfo:       LinearLayout = itemView.findViewById(R.id.rowTimeInfo)
        val rowEevdfMetrics:   LinearLayout = itemView.findViewById(R.id.rowEevdfMetrics)
        val rowActionButtons:  LinearLayout = itemView.findViewById(R.id.rowActionButtons)
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
        applySimpleMode(holder, simpleModeEnabled, isSelected)

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

    // ── UI Customization helpers ──────────────────────────────────────────────

    /**
     * Scales the card's inner padding, outer margins, and row spacing based on
     * [scale] (1 = most compact, 5 = default). Font sizes are never changed.
     *
     * Scale table:
     * | Scale | Content padding | Card top | Card bottom | Row gaps | Btn row gap |
     * |-------|----------------|---------|-------------|----------|-------------|
     * |   5   |    14 dp        |  8 dp   |    4 dp     |  4 dp    |    8 dp     |
     * |   4   |    11 dp        |  6 dp   |    3 dp     |  3 dp    |    6 dp     |
     * |   3   |     8 dp        |  5 dp   |    2 dp     |  2 dp    |    4 dp     |
     * |   2   |     6 dp        |  3 dp   |    1 dp     |  1 dp    |    2 dp     |
     * |   1   |     4 dp        |  2 dp   |    1 dp     |  0 dp    |    2 dp     |
     */
    private fun applyCardScale(holder: TaskViewHolder, scale: Int, density: Float) {
        val contentPaddingDp = when (scale) { 5 -> 14f; 4 -> 11f; 3 -> 8f; 2 -> 6f; else -> 4f }
        val cardTopDp        = when (scale) { 5 ->  8f; 4 ->  6f; 3 -> 5f; 2 -> 3f; else -> 2f }
        val cardBottomDp     = when (scale) { 5 ->  4f; 4 ->  3f; 3 -> 2f; 2 -> 1f; else -> 1f }
        val rowGapDp         = when (scale) { 5 ->  4f; 4 ->  3f; 3 -> 2f; 2 -> 1f; else -> 0f }
        val btnGapDp         = when (scale) { 5 ->  8f; 4 ->  6f; 3 -> 4f; 2 -> 2f; else -> 2f }
        val progressTopDp    = when (scale) { 5 ->  8f; 4 ->  6f; 3 -> 5f; 2 -> 3f; else -> 2f }

        val px = { dp: Float -> (dp * density + 0.5f).toInt() }

        // Inner content padding
        val p = px(contentPaddingDp)
        holder.layoutCardContent.setPadding(p, p, p, p)

        // Card outer vertical margins (preserve depth marginStart set just before)
        val cardLp = holder.itemView.layoutParams as? RecyclerView.LayoutParams
        cardLp?.let {
            it.topMargin    = px(cardTopDp)
            it.bottomMargin = px(cardBottomDp)
            holder.itemView.layoutParams = it
        }

        // ProgressBar top margin (both default and notice segmented bar get the same spacing)
        (holder.progressBar.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            lp.topMargin = px(progressTopDp)
            holder.progressBar.layoutParams = lp
        }
        (holder.progressNotice.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            lp.topMargin = px(progressTopDp)
            holder.progressNotice.layoutParams = lp
        }

        // Row 2 (time info) top margin
        (holder.rowTimeInfo.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            lp.topMargin = px(rowGapDp)
            holder.rowTimeInfo.layoutParams = lp
        }

        // Row 3 (EEVDF metrics) top margin
        (holder.rowEevdfMetrics.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            lp.topMargin = px(rowGapDp)
            holder.rowEevdfMetrics.layoutParams = lp
        }

        // Row 4 (action buttons) top margin
        (holder.rowActionButtons.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            lp.topMargin = px(btnGapDp)
            holder.rowActionButtons.layoutParams = lp
        }
    }

    /**
     * Hides (or restores) the stat rows when [compact] is true (window calibrate /
     * floating mode active).
     *
     * Collapses both stat rows as units so no child text leaks through:
     *   • Row 1 (rowTimeInfo)     — TRT | time remaining
     *   • Row 2 (rowEevdfMetrics) — VRT | VDL | RS | Runs
     *
     * On restore, individual child views are explicitly set to VISIBLE so a
     * holder recycled from a previous compact bind comes back clean.
     *
     * Selected / running cards are intentionally NOT exempted: in float mode the
     * card stays collapsed even when tapped — applySimpleMode() respects this
     * because it checks hideNonEssentialStats before restoring any rows.
     */
    private fun applyCompactMode(holder: TaskViewHolder, compact: Boolean) {
        if (compact) {
            // Collapse both stat rows entirely — row 1 hides tvTimeSlice AND
            // tvRemaining; row 2 hides VRT / VDL / RS / Runs.
            holder.rowTimeInfo.visibility     = View.GONE
            holder.rowEevdfMetrics.visibility = View.GONE
        } else {
            // Restore rows and ensure every child is visible (a holder previously
            // bound in compact mode may have stale GONE children).
            holder.rowTimeInfo.visibility     = View.VISIBLE
            holder.rowEevdfMetrics.visibility = View.VISIBLE
            holder.tvTimeSlice.visibility     = View.VISIBLE
            holder.tvVruntime.visibility      = View.VISIBLE
            holder.tvVdeadline.visibility     = View.VISIBLE
            holder.tvCpuShare.visibility      = View.VISIBLE
            holder.tvRunCount.visibility      = View.VISIBLE
        }
    }

    /**
     * Simple mode: when [simpleModeEnabled] is true and this card is NOT selected
     * ([isSelected] = false), collapse only the text stat rows:
     *   • Row 1 (rowTimeInfo)    — TRT / time slice
     *   • Row 2 (rowEevdfMetrics) — VRT / VDL / RS / Runs
     *
     * The progress bars (progressTask + progressQuota) are intentionally kept
     * VISIBLE on collapsed cards for two reasons:
     *   1. They provide useful at-a-glance status without reading any text.
     *   2. Keeping them present prevents a two-step resize flicker on close:
     *      collapse → card shrinks to bar height → bar appears → card grows back.
     *      With bars always present the card only ever collapses in one step.
     *
     * When the card IS selected (running task or user-tapped), all stat rows are
     * restored. Must be called AFTER applyCompactMode() so compact-mode GONE
     * wins when both features are active at the same time.
     */
    private fun applySimpleMode(holder: TaskViewHolder, simpleModeEnabled: Boolean, isSelected: Boolean) {
        if (!simpleModeEnabled) return   // feature off — nothing to do

        if (isSelected) {
            // Expand: restore stat rows (only when compact mode hasn't hidden them)
            if (!hideNonEssentialStats) {
                holder.rowTimeInfo.visibility     = View.VISIBLE
                holder.rowEevdfMetrics.visibility = View.VISIBLE
                holder.tvVruntime.visibility      = View.VISIBLE
                holder.tvVdeadline.visibility     = View.VISIBLE
                holder.tvCpuShare.visibility      = View.VISIBLE
                holder.tvRunCount.visibility      = View.VISIBLE
                holder.tvTimeSlice.visibility     = View.VISIBLE
            }
            // Progress bars are left as set by quota/task logic above — no touch needed
        } else {
            // Collapse: hide only the text stat rows.
            // Progress bars stay as-is so there is no height jump on close/open.
            holder.rowTimeInfo.visibility     = View.GONE
            holder.rowEevdfMetrics.visibility = View.GONE
        }
    }

    // ── Unit Format helpers ───────────────────────────────────────────────────
    // Called only by tvVruntime (fmtFloat), tvVdeadline (fmtFloat),
    // tvRunCount (fmtInt), and tvTimeSlice (fmtDur).

    /** VRT / VDL: 2 d.p. always; SI suffixes when unitFormatEnabled. */
    private fun fmtFloat(v: Double): String =
        if (unitFormatEnabled) siFloat(v) else "%.2f".format(v)

    /** Runs: plain int when off; SI with no trailing ".0" when on. */
    private fun fmtInt(v: Int): String =
        if (unitFormatEnabled) siInt(v) else v.toString()

    /** TRT: full unit string when off; top-2 units when on. */
    private fun fmtDur(totalSec: Long): String =
        if (unitFormatEnabled) siDur(totalSec) else formatTRT(totalSec)

    /**
     * SI float — always 2 decimal places in the suffix.
     *   88191.0  → "88.19K"   1900.21 → "1.90K"   12.5 → "12.50"
     */
    private fun siFloat(v: Double): String = when {
        v >= 1_000_000_000.0 -> "${"%.2f".format(v / 1_000_000_000.0)}G"
        v >= 1_000_000.0     -> "${"%.2f".format(v / 1_000_000.0)}M"
        v >= 1_000.0         -> "${"%.2f".format(v / 1_000.0)}K"
        else                 -> "%.2f".format(v)
    }

    /**
     * SI integer — no ".00" on small values; trailing ".0" stripped on suffix.
     *   18    → "18"    1800  → "1.8K"   15000 → "15K"   1_234_567 → "1.2M"
     */
    private fun siInt(v: Int): String {
        if (v < 1_000) return v.toString()
        val (scaled, suffix) = when {
            v >= 1_000_000_000 -> v / 1_000_000_000.0 to "G"
            v >= 1_000_000     -> v / 1_000_000.0     to "M"
            else               -> v / 1_000.0         to "K"
        }
        return "${("%.1f".format(scaled)).trimEnd('0').trimEnd('.')  }$suffix"
    }

    /**
     * SI duration — top 2 most-significant non-zero units.
     *   3d 28h 3s → "3d 28h"     4y 6h 5s → "4y 6h"     45s → "45s"
     */
    private fun siDur(totalSec: Long): String {
        if (totalSec <= 0L) return "0s"
        var rem = totalSec
        val years   = rem / 31_536_000L; rem %= 31_536_000L
        val months  = rem /  2_592_000L; rem %=  2_592_000L
        val days    = rem /     86_400L; rem %=     86_400L
        val hours   = rem /      3_600L; rem %=      3_600L
        val minutes = rem /         60L
        val seconds = rem %         60L
        val parts = buildList {
            if (years   > 0) add("${years}y")
            if (months  > 0) add("${months}mo")
            if (days    > 0) add("${days}d")
            if (hours   > 0) add("${hours}h")
            if (minutes > 0) add("${minutes}m")
            if (seconds > 0) add("${seconds}s")
        }
        return parts.take(2).joinToString(" ")
    }

    // ── Existing helpers (unchanged) ──────────────────────────────────────────

    /**
     * Format a duration in seconds as a compact real-time string, showing only
     * the two most significant non-zero units down to seconds.
     */
    private fun formatTRT(totalSec: Long): String {
        if (totalSec <= 0L) return "0s"

        var rem = totalSec
        val years   = rem / 31_536_000L; rem %= 31_536_000L
        val months  = rem /  2_592_000L; rem %=  2_592_000L
        val days    = rem /     86_400L; rem %=     86_400L
        val hours   = rem /      3_600L; rem %=      3_600L
        val minutes = rem /         60L
        val seconds = rem %         60L

        val parts = buildList {
            if (years   > 0) add("${years}y")
            if (months  > 0) add("${months}mo")
            if (days    > 0) add("${days}d")
            if (hours   > 0) add("${hours}h")
            if (minutes > 0) add("${minutes}m")
            if (seconds > 0) add("${seconds}s")
        }
        return parts.joinToString(" ")
    }

    private fun formatQuota(totalSec: Long): String {
        if (totalSec <= 0L) return "0s"
        var rem = totalSec
        val days    = rem / 86_400L; rem %= 86_400L
        val hours   = rem /  3_600L; rem %=  3_600L
        val minutes = rem /     60L
        val seconds = rem %     60L
        val parts = buildList {
            if (days    > 0) add("${days}d")
            if (hours   > 0) add("${hours}h")
            if (minutes > 0) add("${minutes}m")
            if (seconds > 0) add("${seconds}s")
        }
        return parts.take(2).joinToString(" ")
    }

    private fun formatDlDuration(totalSec: Long): String = formatQuota(totalSec)

    private fun priorityColor(holder: TaskViewHolder, task: Task): Int =
        when (task.priority) {
            7    -> androidx.core.content.ContextCompat.getColor(holder.itemView.context, R.color.priority7)
            6    -> androidx.core.content.ContextCompat.getColor(holder.itemView.context, R.color.priority6)
            5    -> androidx.core.content.ContextCompat.getColor(holder.itemView.context, R.color.priority5)
            4    -> androidx.core.content.ContextCompat.getColor(holder.itemView.context, R.color.priority4)
            3    -> androidx.core.content.ContextCompat.getColor(holder.itemView.context, R.color.priority3)
            2    -> androidx.core.content.ContextCompat.getColor(holder.itemView.context, R.color.priority2)
            else -> androidx.core.content.ContextCompat.getColor(holder.itemView.context, R.color.priority1)
        }

    private fun bindPriorityLabel(tv: TextView, task: Task, pColor: Int) {
        val priorityPart = if (task.internalWeight != null)
            "Priority: ${"%.2f".format(task.weight)}"
        else
            "Priority: ${task.priority}"

        val banner = when (task.schedulerClass) {
            "dl_sched_class"   -> "DL"
            "rt_sched_class"   -> "RT"
            "stop_sched_class" -> "STOP"
            "idle_sched_class" -> "IDLE"
            else               -> null
        }
        val bannerColor = when (task.schedulerClass) {
            "dl_sched_class"   -> Color.parseColor("#D32F2F")
            "rt_sched_class"   -> Color.parseColor("#E64A19")
            "stop_sched_class" -> Color.parseColor("#B71C1C")
            "idle_sched_class" -> Color.parseColor("#757575")
            else               -> pColor
        }

        if (banner != null) {
            val full = SpannableString("$banner  $priorityPart")
            full.setSpan(ForegroundColorSpan(bannerColor), 0, banner.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            full.setSpan(StyleSpan(Typeface.BOLD),         0, banner.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            tv.text = full
        } else {
            tv.text = priorityPart
        }
        tv.setTextColor(pColor)
    }

    private fun applyPillColor(tv: TextView, context: android.content.Context, hexColor: String) {
        val bg = tv.background?.mutate()
            ?: androidx.core.content.ContextCompat
                .getDrawable(context, R.drawable.bg_dl_badge)
                ?.mutate()
        (bg as? android.graphics.drawable.GradientDrawable)?.setColor(Color.parseColor(hexColor))
        tv.background = bg
    }

    /**
     * Builds the segmented progress bar for NOTIFICATION-type tasks and fills
     * each segment with live progress from [currentNoticePhase].
     *
     * Structure:  [execute — blue] [wait — green]  ×  (repeatCount + 1)
     *
     * Heights: each segment View is explicitly 4 dp — matching the visual track
     * height of the horizontal ProgressBar style — and the container uses
     * gravity=CENTER_VERTICAL so the segments sit centred in the same 6 dp
     * space as progressTask. This avoids the "segments appear taller" issue that
     * comes from MATCH_PARENT filling the whole 6 dp with solid colour.
     *
     * Live progress: each segment uses a LayerDrawable of
     *   [0] GradientDrawable  — lighter-tint track (always visible)
     *   [1] ClipDrawable      — solid fill clipped left→right by fillPercent
     * ClipDrawable.level ranges 0–10 000 (0 = empty, 10 000 = full).
     *
     * Segments BEFORE the active phase index are fully filled (level 10000).
     * The ACTIVE segment is partially filled from task.progressPercent or
     * elapsed-wait calculation.
     * Segments AFTER are empty (level 0, showing only the track colour).
     */
    private fun buildNoticeSegments(container: LinearLayout, task: Task) {
        container.removeAllViews()
        container.gravity = Gravity.CENTER_VERTICAL

        val execSecs = task.timeSliceSeconds
        val waitSecs = task.notificationRestSeconds
        val cycles   = task.notificationRepeatCount + 1
        val hasWait  = waitSecs > 0

        if (execSecs <= 0) return

        // ── Compute per-segment fill (0–100) from current notice phase ──────
        val phase        = if (task.id == noticeTaskId) currentNoticePhase else NoticePhase.Idle
        val segsPerCycle = if (hasWait) 2 else 1
        val totalSegs    = cycles * segsPerCycle
        val fills        = IntArray(totalSegs) { 0 }

        when (phase) {
            is NoticePhase.Execute -> {
                val iter    = phase.iteration.coerceIn(0, cycles - 1)
                val execIdx = iter * segsPerCycle
                for (s in 0 until execIdx) fills[s] = 100   // previous segments: full
                if (execIdx < totalSegs) fills[execIdx] = task.progressPercent
            }
            is NoticePhase.Wait -> {
                val iter    = phase.iteration.coerceIn(0, cycles - 1)
                val execIdx = iter * segsPerCycle
                for (s in 0..execIdx) if (s < totalSegs) fills[s] = 100  // exec done: full
                if (hasWait) {
                    val waitIdx = execIdx + 1
                    if (waitIdx < totalSegs && waitSecs > 0) {
                        val elapsed = (waitSecs - phase.remainingSecs).coerceAtLeast(0L)
                        fills[waitIdx] = (elapsed * 100L / waitSecs).toInt().coerceIn(0, 100)
                    }
                }
            }
            else -> { /* Idle / Delay / Expired: all fills remain 0 (empty track) */ }
        }

        // ── Colour palette ───────────────────────────────────────────────────
        val execFill  = Color.parseColor("#2196F3")   // Blue  500 — active fill
        val execTrack = Color.parseColor("#BBDEFB")   // Blue  100 — empty track
        val waitFill  = Color.parseColor("#4CAF50")   // Green 500 — active fill
        val waitTrack = Color.parseColor("#C8E6C9")   // Green 100 — empty track

        // ── Segment geometry ─────────────────────────────────────────────────
        val density = container.context.resources.displayMetrics.density
        val segHPx  = (4f * density + 0.5f).toInt()          // explicit 4 dp height
        val gapPx   = (1.5f * density + 0.5f).toInt()        // ~1.5 dp inter-segment gap

        // ── Build and attach views ───────────────────────────────────────────
        var segIdx = 0
        for (i in 0 until cycles) {
            val eIsLast = segIdx == totalSegs - 1
            container.addView(makeSegmentView(
                container.context, execSecs.toFloat(), segHPx,
                if (eIsLast) 0 else gapPx,
                execFill, execTrack, fills[segIdx]))
            segIdx++

            if (hasWait) {
                val wIsLast = segIdx == totalSegs - 1
                container.addView(makeSegmentView(
                    container.context, waitSecs.toFloat(), segHPx,
                    if (wIsLast) 0 else gapPx,
                    waitFill, waitTrack, fills[segIdx]))
                segIdx++
            }
        }
    }

    /**
     * Creates a single segment View sized at [heightPx] tall with [weight]
     * for proportional width, a [endGapPx] right margin, and a two-layer
     * background: [trackColor] as the always-visible track, [fillColor]
     * clipped to [fillPercent]% from the left as the progress fill.
     *
     * Rounded caps (cornerRadius = height/2) match the pill style common in
     * horizontal progress bars.
     */
    private fun makeSegmentView(
        ctx: Context,
        weight: Float,
        heightPx: Int,
        endGapPx: Int,
        fillColor: Int,
        trackColor: Int,
        fillPercent: Int
    ): View {
        val v  = View(ctx)
        val lp = LinearLayout.LayoutParams(0, heightPx, weight)
        lp.marginEnd = endGapPx
        v.layoutParams = lp

        val radius = heightPx / 2f  // pill-shaped caps

        val track = GradientDrawable().apply {
            setColor(trackColor)
            cornerRadius = radius
        }
        val fill = GradientDrawable().apply {
            setColor(fillColor)
            cornerRadius = radius
        }
        val clip = ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL).also {
            it.level = (fillPercent * 100).coerceIn(0, 10_000)
        }
        v.background = LayerDrawable(arrayOf(track, clip))
        return v
    }

    private fun setQuotaBarTopMargin(holder: TaskViewHolder, bothBarsVisible: Boolean) {
        val lp    = holder.progressQuota.layoutParams as? LinearLayout.LayoutParams ?: return
        val density = holder.progressQuota.context.resources.displayMetrics.density
        // When both bars are shown the gap between them scales down with cardHeightScale,
        // matching the same rhythm as the rest of the card spacing.
        // When only the quota bar is shown (no task-progress bar), use a slightly
        // larger gap relative to the content padding.
        val gapDp = if (bothBarsVisible) {
            when (cardHeightScale) { 5 -> 3f; 4 -> 2f; 3 -> 2f; 2 -> 1f; else -> 1f }
        } else {
            when (cardHeightScale) { 5 -> 8f; 4 -> 6f; 3 -> 5f; 2 -> 3f; else -> 2f }
        }
        lp.topMargin = (gapDp * density + 0.5f).toInt()
        holder.progressQuota.layoutParams = lp
    }

    /** Payload used for the 1-second quota tick — skips full rebind to avoid flicker. */
    companion object { const val PAYLOAD_QUOTA_TICK = "quota_tick" }

    override fun onBindViewHolder(
        holder: TaskViewHolder, position: Int, payloads: MutableList<Any>
    ) {
        if (payloads.any { it == PAYLOAD_QUOTA_TICK }) {
            val item = getItem(position) ?: return
            bindQuotaOnly(holder, item)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    private fun bindQuotaOnly(holder: TaskViewHolder, item: TaskDisplayItem) {
        val task = item.task

        val ownQuotaExceeded = task.isQuotaExceeded
        val ownQuotaWarning  = task.isQuotaWarning
        val isRunning        = task.id == runningTaskId
        val isDlActive       = task.isDlBudgetActive

        if (task.isQuotaEnabled) {
            holder.tvQuotaRemaining.visibility = View.VISIBLE
            holder.progressQuota.visibility    = View.VISIBLE

            holder.tvQuotaRemaining.text = when {
                ownQuotaExceeded -> "-${formatQuota(task.quotaOverflowSeconds)}"
                else             -> "+${formatQuota(task.quotaRemainingSeconds)}"
            }
            holder.tvQuotaRemaining.setTextColor(
                when {
                    ownQuotaExceeded -> Color.parseColor("#E65100")
                    ownQuotaWarning  -> Color.parseColor("#F57C00")
                    else             -> Color.parseColor("#757575")
                }
            )
            holder.progressQuota.progress = task.quotaProgressPercent
            holder.progressQuota.progressTintList =
                android.content.res.ColorStateList.valueOf(Color.parseColor(when {
                    ownQuotaExceeded -> "#E53935"
                    ownQuotaWarning  -> "#FFA000"
                    else             -> "#66BB6A"
                }))
            setQuotaBarTopMargin(holder, bothBarsVisible = holder.progressBar.visibility == View.VISIBLE || holder.progressNotice.visibility == View.VISIBLE)
        } else {
            holder.tvQuotaRemaining.visibility = View.GONE
            holder.progressQuota.visibility    = View.GONE
        }

        // ── DL badge live refresh ──────────────────────────────────────────────
        if (task.isDlConfigured && !task.isGroup) {
            holder.tvDlStatus.visibility = View.VISIBLE
            holder.tvDlStatus.text = if (isDlActive) {
                formatDlDuration(task.dlRuntimeRemainingSeconds)
            } else {
                val periodRem = task.dlPeriodRemainingSeconds
                if (periodRem > 0) formatDlDuration(periodRem) else "done"
            }
            applyPillColor(holder.tvDlStatus, holder.itemView.context,
                if (isDlActive) "#E65100" else "#78909C")
        } else {
            holder.tvDlStatus.visibility = View.GONE
        }

        // ── RT badge live refresh ──────────────────────────────────────────────
        val isRtActive = RtScheduler.isRtWindowActive(task)
        if (task.isRtConfigured && !task.isGroup) {
            holder.tvRtStatus.visibility = View.VISIBLE
            if (isRtActive) {
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

        val cardQuotaExceeded = item.effectiveQuotaExceeded || ownQuotaExceeded
        val cardQuotaWarning  = item.effectiveQuotaWarning  || ownQuotaWarning

        holder.card.cardElevation = when {
            isRunning  -> 12f
            isDlActive -> 8f
            isRtActive -> 7f
            else       -> 4f
        }
        holder.card.setCardBackgroundColor(Color.parseColor(when {
            isRunning         -> "#E3F2FD"
            isDlActive        -> "#FFEBEE"
            isRtActive        -> "#E8F5E9"
            cardQuotaExceeded -> "#FFFDE7"
            cardQuotaWarning  -> "#FFF8E1"
            task.isGroup      -> "#F5F5F5"
            else              -> "#FFFFFF"
        }))
    }

    class DiffCallback : DiffUtil.ItemCallback<TaskDisplayItem>() {
        override fun areItemsTheSame(old: TaskDisplayItem, new: TaskDisplayItem) =
            old.task.id == new.task.id
        override fun areContentsTheSame(old: TaskDisplayItem, new: TaskDisplayItem) =
            old == new
    }
}
