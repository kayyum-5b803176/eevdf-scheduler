package com.eevdf.scheduler.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.eevdf.scheduler.R
import com.eevdf.scheduler.model.Task
import com.eevdf.scheduler.model.TaskDisplayItem

class TaskAdapter(
    private val onTaskClick:        (Task) -> Unit,
    private val onTaskLongClick:    (Task) -> Unit = {},
    private val onDeleteClick:      (Task) -> Unit,
    private val onCompleteClick:    (Task) -> Unit,
    private val onRunClick:         (Task) -> Unit,
    private val onGroupToggle:      (Task) -> Unit,   // expand / collapse a group
    private val onResetSliceClick:  (Task) -> Unit = {},
    private val onRevertClick:      (Task) -> Unit = {},
    private val showScheduleRank:   Boolean = false,
    private val isCompletedTab:     Boolean = false,
    /** Returns the expanded state for a group task id — used for rotation icon. */
    private val expandStateProvider: (String) -> Boolean = { true }
) : ListAdapter<TaskDisplayItem, TaskAdapter.TaskViewHolder>(DiffCallback()) {

    private var runningTaskId: String? = null

    fun setRunningTask(id: String?) {
        runningTaskId = id
        notifyDataSetChanged()
    }

    inner class TaskViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val card:           CardView    = itemView.findViewById(R.id.cardTask)
        val tvRank:         TextView    = itemView.findViewById(R.id.tvRank)
        val tvName:         TextView    = itemView.findViewById(R.id.tvTaskName)
        val tvCategory:     TextView    = itemView.findViewById(R.id.tvCategory)
        val tvPriority:     TextView    = itemView.findViewById(R.id.tvPriority)
        val tvTimeSlice:    TextView    = itemView.findViewById(R.id.tvTimeSlice)
        val tvRemaining:    TextView    = itemView.findViewById(R.id.tvRemaining)
        val tvVruntime:     TextView    = itemView.findViewById(R.id.tvVruntime)
        val tvVdeadline:    TextView    = itemView.findViewById(R.id.tvVdeadline)
        val progressBar:    ProgressBar = itemView.findViewById(R.id.progressTask)
        val btnDelete:      ImageButton = itemView.findViewById(R.id.btnDelete)
        val btnComplete:    ImageButton = itemView.findViewById(R.id.btnComplete)
        val btnRun:         ImageButton = itemView.findViewById(R.id.btnRun)
        val btnGroupToggle: ImageButton = itemView.findViewById(R.id.btnGroupToggle)
        val btnResetSlice:  ImageButton = itemView.findViewById(R.id.btnResetSlice)
        val btnRevert:      ImageButton = itemView.findViewById(R.id.btnRevert)
        val tvRunCount:     TextView    = itemView.findViewById(R.id.tvRunCount)
        val viewRunning:    View        = itemView.findViewById(R.id.viewRunningIndicator)
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

        // ── Common fields ──────────────────────────────────────────────────────
        holder.tvName.text     = task.name
        holder.tvPriority.text = "Priority: ${task.priority}"
        holder.tvVruntime.text  = "VRT: ${"%.2f".format(task.vruntime)}"
        holder.tvVdeadline.text = "VDL: ${"%.2f".format(task.virtualDeadline)}"

        // ── Group vs leaf rendering ────────────────────────────────────────────
        if (task.isGroup) {
            // Group header row
            holder.tvCategory.text  = "Group · ${item.childCount} task${if (item.childCount != 1) "s" else ""}"
            holder.tvTimeSlice.text = "Child time: ${formatSeconds(item.childTotalRuntime)}"
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
        } else {
            // Leaf task row
            holder.tvCategory.text  = task.category
            holder.tvTimeSlice.text = "Slice: ${task.timeSliceDisplay}"
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

        // ── Schedule rank ──────────────────────────────────────────────────────
        if (showScheduleRank) {
            holder.tvRank.visibility = View.VISIBLE
            holder.tvRank.text       = "#${position + 1}"
        } else {
            holder.tvRank.visibility = View.GONE
        }

        // ── Running state ──────────────────────────────────────────────────────
        holder.viewRunning.visibility = if (isRunning) View.VISIBLE else View.INVISIBLE

        // ── Priority colour ────────────────────────────────────────────────────
        val priorityColor = when (task.priority) {
            7    -> androidx.core.content.ContextCompat.getColor(holder.itemView.context, R.color.priority7)
            6    -> androidx.core.content.ContextCompat.getColor(holder.itemView.context, R.color.priority6)
            5    -> androidx.core.content.ContextCompat.getColor(holder.itemView.context, R.color.priority5)
            4    -> androidx.core.content.ContextCompat.getColor(holder.itemView.context, R.color.priority4)
            3    -> androidx.core.content.ContextCompat.getColor(holder.itemView.context, R.color.priority3)
            2    -> androidx.core.content.ContextCompat.getColor(holder.itemView.context, R.color.priority2)
            else -> androidx.core.content.ContextCompat.getColor(holder.itemView.context, R.color.priority1)
        }
        holder.tvPriority.setTextColor(priorityColor)

        // ── Card highlight ─────────────────────────────────────────────────────
        holder.card.cardElevation = if (isRunning) 12f else 4f
        holder.card.setCardBackgroundColor(
            when {
                isRunning   -> Color.parseColor("#E3F2FD")
                task.isGroup -> Color.parseColor("#F5F5F5")
                else         -> Color.WHITE
            }
        )

        holder.card.setOnClickListener { onTaskClick(task) }
        holder.card.setOnLongClickListener { onTaskLongClick(task); true }
        holder.btnDelete.setOnClickListener { onDeleteClick(task) }
    }

    private fun formatSeconds(totalSec: Long): String {
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            else  -> "${s}s"
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<TaskDisplayItem>() {
        override fun areItemsTheSame(old: TaskDisplayItem, new: TaskDisplayItem) =
            old.task.id == new.task.id
        override fun areContentsTheSame(old: TaskDisplayItem, new: TaskDisplayItem) =
            old == new
    }
}
