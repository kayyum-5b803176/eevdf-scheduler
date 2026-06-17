package com.eevdf.app.feature.task.adapter

import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import com.eevdf.app.R

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
