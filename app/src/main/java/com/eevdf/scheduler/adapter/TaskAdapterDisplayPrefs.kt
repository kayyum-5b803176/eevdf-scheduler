package com.eevdf.scheduler.adapter

import android.view.View
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView

/**
 * UI-customization layout helpers for [TaskAdapter].
 *
 * Separated so that card-density and simple-mode changes (scale values, new
 * rows) can be made without touching the main adapter binding logic.
 *
 * Domain boundary: anything that adjusts *how big* or *how collapsed* a card
 * is lives here. What the card *shows* lives in TaskAdapterBindHelpers.kt.
 */

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
internal fun applyCardScale(holder: TaskViewHolder, scale: Int, density: Float) {
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
internal fun applyCompactMode(holder: TaskViewHolder, compact: Boolean) {
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
 *   2. Keeping them present prevents a two-step resize flicker on close.
 *
 * When the card IS selected (running task or user-tapped), all stat rows are
 * restored. Must be called AFTER applyCompactMode() so compact-mode GONE
 * wins when both features are active at the same time.
 */
internal fun applySimpleMode(
    holder: TaskViewHolder,
    simpleModeEnabled: Boolean,
    isSelected: Boolean,
    hideNonEssentialStats: Boolean
) {
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
