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

class TaskAdapter(
    private val onTaskClick: (Task) -> Unit,
    private val onDeleteClick: (Task) -> Unit,
    private val onCompleteClick: (Task) -> Unit,
    private val onRunClick: (Task) -> Unit,
    private val showScheduleRank: Boolean = false
) : ListAdapter<Task, TaskAdapter.TaskViewHolder>(TaskDiffCallback()) {

    private var runningTaskId: String? = null

    fun setRunningTask(id: String?) {
        runningTaskId = id
        notifyDataSetChanged()
    }

    inner class TaskViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val card: CardView = itemView.findViewById(R.id.cardTask)
        val tvRank: TextView = itemView.findViewById(R.id.tvRank)
        val tvName: TextView = itemView.findViewById(R.id.tvTaskName)
        val tvCategory: TextView = itemView.findViewById(R.id.tvCategory)
        val tvPriority: TextView = itemView.findViewById(R.id.tvPriority)
        val tvTimeSlice: TextView = itemView.findViewById(R.id.tvTimeSlice)
        val tvRemaining: TextView = itemView.findViewById(R.id.tvRemaining)
        val tvVruntime: TextView = itemView.findViewById(R.id.tvVruntime)
        val tvVdeadline: TextView = itemView.findViewById(R.id.tvVdeadline)
        val progressBar: ProgressBar = itemView.findViewById(R.id.progressTask)
        val btnDelete: ImageButton = itemView.findViewById(R.id.btnDelete)
        val btnComplete: ImageButton = itemView.findViewById(R.id.btnComplete)
        val btnRun: ImageButton = itemView.findViewById(R.id.btnRun)
        val tvRunCount: TextView = itemView.findViewById(R.id.tvRunCount)
        val viewRunning: View = itemView.findViewById(R.id.viewRunningIndicator)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TaskViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_task, parent, false)
        return TaskViewHolder(view)
    }

    override fun onBindViewHolder(holder: TaskViewHolder, position: Int) {
        val task = getItem(position)
        val isRunning = task.id == runningTaskId

        holder.tvName.text = task.name
        holder.tvCategory.text = task.category
        holder.tvPriority.text = "Priority: ${task.priority}/10"
        holder.tvTimeSlice.text = "Slice: ${task.timeSliceDisplay}"
        holder.tvRemaining.text = task.remainingDisplay
        holder.progressBar.progress = task.progressPercent
        holder.tvRunCount.text = "Runs: ${task.runCount}"
        holder.tvVruntime.text = "VRT: ${"%.2f".format(task.vruntime)}"
        holder.tvVdeadline.text = "VDL: ${"%.2f".format(task.virtualDeadline)}"

        // Rank display
        if (showScheduleRank) {
            holder.tvRank.visibility = View.VISIBLE
            holder.tvRank.text = "#${position + 1}"
        } else {
            holder.tvRank.visibility = View.GONE
        }

        // Running indicator
        holder.viewRunning.visibility = if (isRunning) View.VISIBLE else View.INVISIBLE

        // Priority color badge
        val priorityColor = when (task.priority) {
            in 9..10 -> Color.parseColor("#F44336") // Red
            in 7..8  -> Color.parseColor("#FF9800") // Orange
            in 5..6  -> Color.parseColor("#2196F3") // Blue
            in 3..4  -> Color.parseColor("#4CAF50") // Green
            else     -> Color.parseColor("#9E9E9E") // Grey
        }
        holder.tvPriority.setTextColor(priorityColor)

        // Card elevation for running task
        holder.card.cardElevation = if (isRunning) 12f else 4f
        holder.card.setCardBackgroundColor(
            if (isRunning) Color.parseColor("#E3F2FD")
            else Color.WHITE
        )

        holder.card.setOnClickListener { onTaskClick(task) }
        holder.btnDelete.setOnClickListener { onDeleteClick(task) }
        holder.btnComplete.setOnClickListener { onCompleteClick(task) }
        holder.btnRun.setOnClickListener { onRunClick(task) }
    }

    class TaskDiffCallback : DiffUtil.ItemCallback<Task>() {
        override fun areItemsTheSame(oldItem: Task, newItem: Task) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Task, newItem: Task) = oldItem == newItem
    }
}
