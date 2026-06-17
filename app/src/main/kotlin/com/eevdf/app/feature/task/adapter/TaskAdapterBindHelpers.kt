package com.eevdf.app.feature.task.adapter

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.eevdf.app.R
import com.eevdf.data.task.Task
import com.eevdf.data.task.TaskDisplayItem
import com.eevdf.data.scheduler.RtScheduler

/**
 * Per-card bind helpers for [TaskAdapter].
 *
 * Separated so priority/badge/quota rendering can be iterated independently
 * of the notice-segment or layout-scale logic. Domain:
 *   • [priorityColor]       — resolve priority tint from theme colors
 *   • [bindPriorityLabel]   — build the SpannableString with scheduler-class banner
 *   • [applyPillColor]      — tint a badge pill drawable
 *   • [TaskAdapter.setQuotaBarTopMargin] — adaptive gap between the two progress bars
 *   • [TaskAdapter.bindQuotaOnly]       — lightweight partial rebind via PAYLOAD_QUOTA_TICK
 */

internal fun priorityColor(holder: TaskViewHolder, task: Task): Int =
    when (task.priority) {
        7    -> androidx.core.content.ContextCompat.getColor(holder.itemView.context, R.color.priority7)
        6    -> androidx.core.content.ContextCompat.getColor(holder.itemView.context, R.color.priority6)
        5    -> androidx.core.content.ContextCompat.getColor(holder.itemView.context, R.color.priority5)
        4    -> androidx.core.content.ContextCompat.getColor(holder.itemView.context, R.color.priority4)
        3    -> androidx.core.content.ContextCompat.getColor(holder.itemView.context, R.color.priority3)
        2    -> androidx.core.content.ContextCompat.getColor(holder.itemView.context, R.color.priority2)
        else -> androidx.core.content.ContextCompat.getColor(holder.itemView.context, R.color.priority1)
    }

internal fun bindPriorityLabel(tv: TextView, task: Task, pColor: Int) {
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

internal fun applyPillColor(tv: TextView, context: android.content.Context, hexColor: String) {
    val bg = tv.background?.mutate()
        ?: androidx.core.content.ContextCompat
            .getDrawable(context, R.drawable.bg_dl_badge)
            ?.mutate()
    (bg as? android.graphics.drawable.GradientDrawable)?.setColor(Color.parseColor(hexColor))
    tv.background = bg
}

/**
 * Adjusts the gap between the task-progress bar and the quota bar based on
 * [cardHeightScale] and whether both bars are currently visible.
 */
internal fun TaskAdapter.setQuotaBarTopMargin(holder: TaskViewHolder, bothBarsVisible: Boolean) {
    val lp    = holder.progressQuota.layoutParams as? LinearLayout.LayoutParams ?: return
    val density = holder.progressQuota.context.resources.displayMetrics.density
    val gapDp = if (bothBarsVisible) {
        when (cardHeightScale) { 5 -> 3f; 4 -> 2f; 3 -> 2f; 2 -> 1f; else -> 1f }
    } else {
        when (cardHeightScale) { 5 -> 8f; 4 -> 6f; 3 -> 5f; 2 -> 3f; else -> 2f }
    }
    lp.topMargin = (gapDp * density + 0.5f).toInt()
    holder.progressQuota.layoutParams = lp
}

/**
 * Lightweight partial rebind triggered by [TaskAdapter.PAYLOAD_QUOTA_TICK].
 *
 * Updates only quota text/bar, DL badge, RT badge, and card tint — skipping
 * the full view hierarchy rebuild to avoid the per-second flicker.
 */
internal fun TaskAdapter.bindQuotaOnly(holder: TaskViewHolder, item: TaskDisplayItem) {
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
        setQuotaBarTopMargin(
            holder,
            bothBarsVisible = holder.progressBar.visibility == View.VISIBLE
                    || holder.progressNotice.visibility == View.VISIBLE
        )
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
        applyPillColor(
            holder.tvDlStatus, holder.itemView.context,
            if (isDlActive) "#E65100" else "#78909C"
        )
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
