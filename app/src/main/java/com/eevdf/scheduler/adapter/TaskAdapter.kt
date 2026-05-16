package com.eevdf.scheduler.adapter

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
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
import com.eevdf.scheduler.model.Task
import com.eevdf.scheduler.model.TaskDisplayItem
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

    private var runningTaskId: String? = null

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
        holder.tvVruntime.text  = "VRT: ${"%.2f".format(task.vruntime)}"
        holder.tvVdeadline.text = "VDL: ${"%.2f".format(task.virtualDeadline)}"
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
            holder.tvTimeSlice.text = "TRT: ${formatTRT(item.childTotalRuntime)}"
            holder.tvRemaining.text = "VRT: ${"%.2f".format(task.vruntime)}"
            holder.tvRunCount.text  = "Runs: ${task.runCount}"
            holder.progressBar.visibility = View.GONE
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
            holder.tvTimeSlice.text = "TRT: ${formatTRT(task.totalRunTime)}"
            holder.tvRemaining.text = task.remainingDisplay
            holder.tvRunCount.text  = "Runs: ${task.runCount}"
            holder.progressBar.visibility    = View.VISIBLE
            holder.progressBar.progress      = task.progressPercent
            holder.btnGroupToggle.visibility = View.GONE
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
            setQuotaBarTopMargin(holder, bothBarsVisible = holder.progressBar.visibility == View.VISIBLE)
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

        // ProgressBar top margin
        (holder.progressBar.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            lp.topMargin = px(progressTopDp)
            holder.progressBar.layoutParams = lp
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
     * Hides (or restores) the non-essential scheduler stat fields when [compact]
     * is true. The entire EEVDF metrics row collapses to zero height when hidden;
     * TRT is hidden in Row 2 while the countdown timer remains visible.
     *
     * Hidden fields: VRT · VDL · RS · Runs · TRT
     */
    private fun applyCompactMode(holder: TaskViewHolder, compact: Boolean) {
        val statVisibility = if (compact) View.GONE else View.VISIBLE
        holder.tvVruntime.visibility    = statVisibility
        holder.tvVdeadline.visibility   = statVisibility
        holder.tvCpuShare.visibility    = statVisibility
        holder.tvRunCount.visibility    = statVisibility
        holder.tvTimeSlice.visibility   = statVisibility   // TRT
        // Collapse the entire EEVDF metrics row when all its children are gone
        holder.rowEevdfMetrics.visibility = statVisibility
    }

    /**
     * Simple mode: when [simpleModeEnabled] is true and this card is NOT selected
     * ([isSelected] = false), collapse:
     *   • Row 0 — progress bars (progressTask + progressQuota)
     *   • Row 1 (rowTimeInfo)  — TRT / time slice
     *   • Row 2 (rowEevdfMetrics) — VRT / VDL / RS / Runs
     *
     * When the card IS selected (running task or user-tapped), all rows are
     * restored to their normal visibility (View.VISIBLE), unless compact mode
     * has already hidden a subset — compact mode takes priority over expansion.
     *
     * Must be called AFTER applyCompactMode() so compact-mode GONE wins over
     * simple-mode VISIBLE when both are active.
     */
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

    private fun setQuotaBarTopMargin(holder: TaskViewHolder, bothBarsVisible: Boolean) {
        val lp    = holder.progressQuota.layoutParams as? LinearLayout.LayoutParams ?: return
        val density = holder.progressQuota.context.resources.displayMetrics.density
        lp.topMargin = ((if (bothBarsVisible) 3f else 8f) * density + 0.5f).toInt()
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
            setQuotaBarTopMargin(holder, bothBarsVisible = holder.progressBar.visibility == View.VISIBLE)
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
