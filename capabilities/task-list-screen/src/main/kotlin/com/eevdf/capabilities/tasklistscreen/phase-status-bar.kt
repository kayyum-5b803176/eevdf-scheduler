package com.eevdf.capabilities.tasklistscreen

import android.view.View
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.eevdf.capabilities.designsystem.R as DesignSystemR
import com.eevdf.capabilities.taskstorage.Task
import com.eevdf.capabilities.taskstorage.TaskLink
import com.eevdf.capabilities.taskstorage.TaskMembership

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
 * True when the currently-selected INSTANCE's own quota is exhausted, OR
 * any ancestor's is — walked from the ROOT down to [ref], stopping at the
 * first exhausted node found (an ancestor's exhaustion is the answer;
 * nothing below it needs checking).
 *
 * Placement-aware: [ref]'s ancestor chain is resolved via
 * [TaskInstanceRef.ancestorChain] — a hardlink or symlink walks up from
 * whichever group actually hosts THAT placement, never the real task's own
 * primary parent, unless that's genuinely how this instance was reached.
 * Reading `task.parentId` directly here was the original bug: a hardlink
 * selected from a non-exhausted host used to show red because the check
 * silently used the real task's own (exhausted) primary parent instead.
 */
internal fun isQuotaChainExhausted(
    ref: TaskInstanceRef, allTasks: List<Task>, links: List<TaskLink>,
    memberships: List<TaskMembership>, nowMs: Long,
): Boolean {
    val tasksById = allTasks.associateBy { it.id }
    val chain = ref.ancestorChain(links, memberships, tasksById)
    for (t in chain) {
        if (t.isQuotaEnabled && t.isQuotaExceeded(nowMs)) return true
    }
    return false
}

/**
 * How many multiples over quota [task] currently is, at [nowMs]. 0 when
 * quota is disabled or not exceeded — only a genuinely exceeded task
 * contributes anything to the blink-severity comparison.
 */
private fun quotaOverageRatio(task: Task, nowMs: Long): Double {
    if (!task.isQuotaEnabled || task.quotaSeconds <= 0L || !task.isQuotaExceeded(nowMs)) return 0.0
    return task.currentQuotaUsed(nowMs).toDouble() / task.quotaSeconds
}

/**
 * Which segment (0-based) should blink for quota overage, and how fast, or
 * null when nothing should blink.
 *
 * Two independent questions, checked in this order — do not swap them:
 *
 *  1. THE GATE: blinking only makes sense when the bar has nothing else to
 *     say — [activeStates] must be EXACTLY `[QUOTA]`, no DELAY/WAIT mixed
 *     in. This is a property of what the SELECTED card is showing right
 *     now, checked first, regardless of how bad any ancestor's overage is.
 *  2. THE SEVERITY: only once the gate passes, walk [ref]'s full root-to-
 *     current ancestor chain and take the WORST overage found anywhere in
 *     it — not the first one found (unlike [isQuotaChainExhausted], which
 *     only needs a yes/no answer, this needs the actual worst number, so a
 *     distant ancestor at 8x correctly outweighs the selected task's own
 *     2x). Each doubling of overage advances the blink one segment further:
 *     1x → segment 0, 2x → 1, 4x → 2, ... up to segment 6 at 64x. Beyond
 *     that, segment 6 has its own adjustable range instead of a fixed
 *     doubling: it blinks at the normal rate from 64x up to 128x, then
 *     switches to a faster blink at 128x and beyond — "off the end of the scale" reads as faster, not as
 *     "stuck," the same way a Geiger counter clicks faster rather than
 *     just staying lit once it's pegged.
 */
internal data class QuotaBlink(val segmentIndex: Int, val fast: Boolean)

internal fun quotaBlinkSegment(
    activeStates: List<PhaseStatusState>, ref: TaskInstanceRef, allTasks: List<Task>,
    links: List<TaskLink>, memberships: List<TaskMembership>, nowMs: Long,
): QuotaBlink? {
    if (activeStates != listOf(PhaseStatusState.QUOTA)) return null   // the gate

    val tasksById = allTasks.associateBy { it.id }
    val chain = ref.ancestorChain(links, memberships, tasksById)
    val worstRatio = chain.maxOfOrNull { quotaOverageRatio(it, nowMs) } ?: 0.0
    if (worstRatio < 1.0) return null   // nothing in the chain is actually exceeded

    val level = kotlin.math.floor(kotlin.math.ln(worstRatio) / kotlin.math.ln(2.0)).toInt()
    val fast  = worstRatio >= FAST_BLINK_THRESHOLD_RATIO
    return QuotaBlink(level.coerceIn(0, SEGMENT_COUNT - 1), fast)
}

/** Overage at or beyond this ratio switches the last segment to a faster blink. */
private const val FAST_BLINK_THRESHOLD_RATIO = 128.0

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
 * The [SEGMENT_COUNT] segment views are created ONCE, on the first call for
 * a given [container], then reused and updated in place on every later
 * call — never torn down and rebuilt. This matters for two concrete
 * reasons, not just efficiency: (1) a blinking segment's animation runs on
 * one persistent View — recreating that View every refresh (this bar
 * refreshes every second, on every quota tick) destroyed the animation
 * before it could visibly move, which is why blinking never appeared to
 * happen at all; (2) tearing down and re-adding all 7 views on a fast,
 * repeating timer risks a layout pass catching a half-rebuilt container in
 * between, which can show a stale mix of old and new segment colors.
 *
 * Always shows exactly [SEGMENT_COUNT] same-size segments, same as a plain
 * `ProgressBar` always shows its empty track rather than vanishing at 0% —
 * this strip is a permanent part of the card, not something that
 * appears/disappears. Empty [activeStates] → every segment uses the neutral
 * `divider` track color (the same "nothing here yet" tone the card's other
 * two progress bars already use for their empty background). Non-empty →
 * each segment's color comes from [pingPongIndex] into [activeStates] — a
 * single active state colors every segment the same; 2+ states bounce
 * across them.
 *
 * [blink] (from [quotaBlinkSegment]) — when non-null, its segment slowly
 * toggles red/neutral instead of sitting static (faster once severity
 * crosses the threshold); every other segment is untouched. The animation
 * is only (re)started when the blink TARGET or SPEED actually changes —
 * not on every refresh, so a steady blink isn't restarted every second even
 * though this function itself is called that often.
 */
internal fun buildPhaseStatusSegments(
    container: LinearLayout, activeStates: List<PhaseStatusState>, blink: QuotaBlink? = null,
) {
    container.visibility = View.VISIBLE
    val context = container.context

    // Build the 7 segments once; every later call reuses them.
    if (container.childCount != SEGMENT_COUNT) {
        container.removeAllViews()
        val density = context.resources.displayMetrics.density
        val gapPx   = (2 * density).toInt()
        for (i in 0 until SEGMENT_COUNT) {
            val segment = View(context)
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            if (i > 0) params.marginStart = gapPx
            container.addView(segment, params)
        }
    }

    val neutralColor = ContextCompat.getColor(context, DesignSystemR.color.divider)
    val quotaColor   = ContextCompat.getColor(context, PhaseStatusState.QUOTA.colorRes)
    for (i in 0 until SEGMENT_COUNT) {
        val segment = container.getChildAt(i)
        val color = if (activeStates.isEmpty()) neutralColor
                    else ContextCompat.getColor(context, activeStates[pingPongIndex(i, activeStates.size)].colorRes)

        val shouldBlink   = i == blink?.segmentIndex
        val intervalMs    = if (blink?.fast == true) FAST_BLINK_INTERVAL_MS else NORMAL_BLINK_INTERVAL_MS
        val currentHandle = segment.getTag(R.id.phase_status_blink_tag) as? BlinkHandle
        when {
            shouldBlink && currentHandle == null -> segment.startBlinkToggle(quotaColor, neutralColor, intervalMs)
            shouldBlink && currentHandle?.intervalMs != intervalMs ->
                // Same segment, but severity crossed the fast-blink threshold
                // (or dropped back below it) since the last refresh — restart
                // at the new speed rather than leaving the old one running.
                segment.startBlinkToggle(quotaColor, neutralColor, intervalMs)
            !shouldBlink && currentHandle != null -> segment.stopBlinkToggle(color)
            !shouldBlink -> segment.setBackgroundColor(color)
            // shouldBlink && currentHandle.intervalMs == intervalMs → already
            // toggling correctly at the right speed; leave it running.
        }
    }
}

/**
 * Hard on/off color toggle — NOT a smooth fade. "On" is the quota-red color;
 * "off" is the same neutral track color the bar already uses for "nothing
 * active" (so the blinking segment reads as "red / same as an idle segment,"
 * not red-fading-to-black or red-fading-to-transparent). Runs via a
 * self-rescheduling [Runnable] rather than Android's [android.view.animation.Animation]
 * system, since that only interpolates continuously — a discrete two-state
 * toggle needed its own small mechanism instead.
 */
private class BlinkHandle(val handler: android.os.Handler, val runnable: Runnable, val intervalMs: Long)

private fun View.startBlinkToggle(onColor: Int, offColor: Int, intervalMs: Long) {
    (getTag(R.id.phase_status_blink_tag) as? BlinkHandle)?.let { it.handler.removeCallbacks(it.runnable) }
    val handler = android.os.Handler(android.os.Looper.getMainLooper())
    var isOn = true
    lateinit var toggle: Runnable
    toggle = Runnable {
        setBackgroundColor(if (isOn) onColor else offColor)
        isOn = !isOn
        handler.postDelayed(toggle, intervalMs)
    }
    setTag(R.id.phase_status_blink_tag, BlinkHandle(handler, toggle, intervalMs))
    handler.post(toggle)
}

/** Cancels a running [startBlinkToggle] and leaves the segment at [staticColor]. */
private fun View.stopBlinkToggle(staticColor: Int) {
    (getTag(R.id.phase_status_blink_tag) as? BlinkHandle)?.let { it.handler.removeCallbacks(it.runnable) }
    setTag(R.id.phase_status_blink_tag, null)
    setBackgroundColor(staticColor)
}

/** Each on/off half-cycle at ordinary severity — unhurried, not a flicker. */
private const val NORMAL_BLINK_INTERVAL_MS = 700L

/**
 * Each on/off half-cycle once overage reaches [FAST_BLINK_THRESHOLD_RATIO]
 * (128x) — this can ONLY ever apply to the last segment (index 6): reaching
 * a ratio this high already computes segment index 6 on its own (see
 * [quotaBlinkSegment]) — there is no ratio that triggers the fast interval
 * on an earlier segment. Between 64x and 128x, segment 6 still blinks, just
 * at [NORMAL_BLINK_INTERVAL_MS] like every other segment.
 */
private const val FAST_BLINK_INTERVAL_MS = 250L
