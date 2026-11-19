package com.eevdf.capabilities.tasklistscreen

import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.eevdf.capabilities.taskstorage.ScrollRangeRules

/**
 * Auto Scroll: frames the running task's parent chain, then boxes the list in.
 *
 * Two parts, both only on the Queue / Schedule tabs in a nested (non-drill-down)
 * list, both only while the Auto Scroll menu switch is on:
 *
 *  1. FRAME — when the running task (or whether a timer is running) changes, and
 *     the running row is not on screen, jump so the ROOT ancestor's row is at the
 *     top and the rows down to the running task are visible (task1 → task2 → task3
 *     shows task1 first). When that chain is taller than the screen the running
 *     task wins and is pinned to the bottom edge. Instant, never animated, never
 *     switches tab. If the running row is already visible nothing moves.
 *
 *  2. WALLS — while a timer is RUNNING the list cannot be scrolled above the root
 *     row or below the running row. Between them it scrolls freely; a fling that
 *     reaches a wall stops there. Pausing or stopping the timer, deselecting the
 *     task, collapsing the root group (the running row is no longer in the list)
 *     or turning Auto Scroll off all free the list again.
 *
 * Only the identity of the running row / timer state triggers part 1 — never the
 * once-a-second re-publishing of the same task — so a person is never pulled back
 * while scrolling. The maths lives in task-storage ([ScrollRangeRules], tested);
 * this class only reads row positions off the RecyclerView and applies the result.
 */
internal class ScrollRangeDelegate(private val activity: MainActivity) {

    private val vm get() = activity.viewModel

    /** True while a wall correction is being applied, so its own scroll events don't re-enter. */
    private var clamping = false

    /** Set by [release]; cleared by the next change of running row / timer state. */
    private var released = false

    private var lastKey: Pair<TaskInstanceRef?, Boolean>? = null

    /** Registered once on the RecyclerView; enforces the walls after every scroll. */
    val walls = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            if (clamping) return
            // dy == 0 is the notification after a layout pass: scrolling from inside it is
            // not allowed, so tighten against the walls just after it instead.
            if (dy == 0) recyclerView.post { clamp() } else clamp()
        }
    }

    /**
     * Called whenever the current task or the timer-running flag may have changed.
     * Acts only when the running row or the running state really differs from the
     * last call, which is what keeps the once-a-second re-publishing quiet.
     */
    fun onSelectionOrTimerChanged() {
        val key = Pair(vm.currentInstanceRef.value, vm.timerRunning.value == true)
        if (key == lastKey) return
        lastKey = key
        released = false                               // a new running task / state: the walls are back
        if (vm.autoScrollEnabled.value != true) return
        val box = boxFor(requireRunning = false) ?: return
        val llm = activity.recyclerView.layoutManager as? LinearLayoutManager ?: return
        val onScreen = llm.findFirstVisibleItemPosition()..llm.findLastVisibleItemPosition()
        if (box.last in onScreen) clamp() else frame()
    }

    /**
     * Frees the walls until the running task or timer state changes next. Called
     * before an explicit navigation the person asked for (a symlink's "go to the
     * real location"), which would otherwise be pushed straight back into the box.
     */
    fun release() { released = true }

    // ── internals ─────────────────────────────────────────────────────────────

    /** Rows of the box for the current task in the visible list, or null when there is none to enforce. */
    private fun boxFor(requireRunning: Boolean): IntRange? {
        if (vm.autoScrollEnabled.value != true) return null
        if (requireRunning && (released || vm.timerRunning.value != true)) return null
        val adapter = when (activity.currentTab) {
            0    -> if (vm.queueListStyle.value    == TaskListStyle.DRILL_DOWN) return null else activity.activeAdapter
            1    -> if (vm.scheduleListStyle.value == TaskListStyle.DRILL_DOWN) return null else activity.scheduleAdapter
            else -> return null
        }
        if (activity.recyclerView.adapter !== adapter) return null     // mid tab switch
        val ref   = vm.currentInstanceRef.value ?: return null
        val list  = adapter.currentList
        val index = list.indexOfFirst { it.matchesInstance(ref) }
        return ScrollRangeRules.rangeOf(list, index)
    }

    /** Puts the root row at the top; if the chain does not fit, pins the running row to the bottom instead. */
    private fun frame() {
        val box = boxFor(requireRunning = false) ?: return
        val rv  = activity.recyclerView
        val llm = rv.layoutManager as? LinearLayoutManager ?: return
        llm.scrollToPositionWithOffset(box.first, 0)
        rv.post { finishFrame() }
    }

    private fun finishFrame() {
        val box = boxFor(requireRunning = false) ?: return
        val rv  = activity.recyclerView
        val llm = rv.layoutManager as? LinearLayoutManager ?: return
        val runView = llm.findViewByPosition(box.last)
        if (runView != null && runView.bottom <= rv.height - rv.paddingBottom) { clamp(); return }
        // Chain taller than the screen: the running task wins. Lay it out, measure it, pin it to the bottom.
        llm.scrollToPositionWithOffset(box.last, 0)
        rv.post {
            val v = llm.findViewByPosition(box.last) ?: return@post
            val usable = rv.height - rv.paddingTop - rv.paddingBottom
            llm.scrollToPositionWithOffset(box.last, ScrollRangeRules.bottomOffset(usable, v.height))
        }
    }

    /** Pushes the list back inside the walls. Safe to call at any time; does nothing when already inside. */
    private fun clamp() {
        val box = boxFor(requireRunning = true) ?: return
        val rv  = activity.recyclerView
        val llm = rv.layoutManager as? LinearLayoutManager ?: return
        repeat(3) {
            val first = llm.findFirstVisibleItemPosition()
            val last  = llm.findLastVisibleItemPosition()
            if (first == RecyclerView.NO_POSITION) return

            // The whole viewport left the box: jump back to the nearest wall row, then tighten once laid out.
            ScrollRangeRules.farTarget(box, first, last)?.let { target ->
                stopFling(rv)
                llm.scrollToPositionWithOffset(target, 0)
                rv.post { clamp() }
                return
            }

            val rootView = llm.findViewByPosition(box.first)
            val runView  = llm.findViewByPosition(box.last)
            val dy = ScrollRangeRules.correction(
                rootTop    = rootView?.top,
                runBottom  = runView?.bottom,
                viewTop    = rv.paddingTop,
                viewBottom = rv.height - rv.paddingBottom,
            )
            if (dy == 0) return
            stopFling(rv)                      // a fling that reached a wall stops there
            clamping = true
            try { rv.scrollBy(0, dy) } finally { clamping = false }
        }
    }

    private fun stopFling(rv: RecyclerView) {
        if (rv.scrollState == RecyclerView.SCROLL_STATE_SETTLING) rv.stopScroll()
    }
}
