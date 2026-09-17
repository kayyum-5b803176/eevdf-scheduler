package com.eevdf.capabilities.tasklistscreen

import android.view.View
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.eevdf.capabilities.designsystem.R as DesignSystemR
import com.eevdf.capabilities.taskstorage.Task

/**
 * A state the timer card's phase-status bar can show. Fixed priority order —
 * this is also the order used to build the ping-pong segment pattern (QUOTA
 * outermost, WAIT innermost when all three are active at once).
 */
internal enum class PhaseStatusState(val colorRes: Int) {
    QUOTA(DesignSystemR.color.quotaBarExceeded),
    DELAY(DesignSystemR.color.timerYellow),
    WAIT(DesignSystemR.color.timerGreen),
}

/**
 * True when [current]'s own quota is exhausted, OR any ancestor's is —
 * checked from the ROOT down to [current], stopping at the first exhausted
 * node found (an ancestor's exhaustion is the answer; nothing below it needs
 * checking). This is a pure display concept: it never changes any task's
 * own class or accounting, purely what the phase-status bar reports.
 */
internal fun isQuotaChainExhausted(current: Task, allTasks: List<Task>, nowMs: Long): Boolean {
    val byId = allTasks.associateBy { it.id }
    val chain = mutableListOf<Task>()
    var node: Task? = current
    while (node != null) {
        chain.add(node)
        node = node.parentId?.let { byId[it] }
    }
    chain.reverse() // root ... current
    for (t in chain) {
        if (t.isQuotaEnabled && t.isQuotaExceeded(nowMs)) return true
    }
    return false
}

/**
 * Index into [PhaseStatusState]s (0 until n) for bar position [i] under a
 * ping-pong (triangle-wave) walk: forward through all n states, then back,
 * repeating. n=1 → always 0. n=2 → 0,1,0,1,… n=3 → 0,1,2,1,0,1,2,…
 */
private fun pingPongIndex(i: Int, n: Int): Int {
    if (n <= 1) return 0
    val period = 2 * (n - 1)
    val m = i % period
    return if (m < n) m else period - m
}

/** Fixed segment count — see [PhaseStatusState]'s KDoc: never grows, even for a future 4th state. */
private const val SEGMENT_COUNT = 7

/**
 * Renders the timer card's phase-status bar — the ONE shared strip
 * [PhaseStatusState.QUOTA]/[DELAY]/[WAIT] all report through, not a
 * feature-specific view. [activeStates] must already be in the fixed
 * priority order; this function does no reordering.
 *
 * Always draws exactly [SEGMENT_COUNT] same-size segments, same as a plain
 * `ProgressBar` always shows its empty track rather than vanishing at 0% —
 * this strip is a permanent part of the card, not something that
 * appears/disappears. Empty [activeStates] → every segment uses the neutral
 * `divider` track color (the same "nothing here yet" tone the card's other
 * two progress bars already use for their empty background). Non-empty →
 * each segment's color comes from [pingPongIndex] into [activeStates] — a
 * single active state colors every segment the same; 2+ states bounce
 * across them. Segment size is identical either way; only the color set
 * changes.
 */
internal fun buildPhaseStatusSegments(container: LinearLayout, activeStates: List<PhaseStatusState>) {
    container.visibility = View.VISIBLE
    container.removeAllViews()
    val context = container.context
    val density = context.resources.displayMetrics.density
    val gapPx   = (2 * density).toInt()
    val neutralColor = ContextCompat.getColor(context, DesignSystemR.color.divider)
    for (i in 0 until SEGMENT_COUNT) {
        val color = if (activeStates.isEmpty()) neutralColor
                    else ContextCompat.getColor(context, activeStates[pingPongIndex(i, activeStates.size)].colorRes)
        val segment = View(context).apply { setBackgroundColor(color) }
        val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        if (i > 0) params.marginStart = gapPx
        container.addView(segment, params)
    }
}
