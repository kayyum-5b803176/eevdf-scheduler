package com.eevdf.capabilities.taskstorage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure maths of the auto-scroll box: which rows bound it, when the viewport left it, how to push it back. */
class ScrollRangeRulesTest {

    private fun row(id: String, depth: Int) =
        TaskDisplayItem(Task(id = id, name = id, priority = 4, timeSliceSeconds = 60L), depth)

    // task1            0
    //   task2          1
    //     task3        2   ← running
    //     sib3         2
    //   sib2           1
    // other            0
    private val list = listOf(
        row("task1", 0), row("task2", 1), row("task3", 2), row("sib3", 2), row("sib2", 1), row("other", 0),
    )

    // ── rangeOf ───────────────────────────────────────────────────────────────

    @Test fun `box of a grandchild runs from the root group to the task`() {
        assertEquals(0..2, ScrollRangeRules.rangeOf(list, 2))
    }

    @Test fun `box of a child runs from its parent`() {
        assertEquals(0..1, ScrollRangeRules.rangeOf(list, 1))
    }

    @Test fun `a root task boxes only itself`() {
        assertEquals(5..5, ScrollRangeRules.rangeOf(list, 5))
        assertEquals(0..0, ScrollRangeRules.rangeOf(list, 0))
    }

    @Test fun `siblings between the root and the task are inside the box`() {
        // sib2 (index 4) is a child of task1; its box starts at task1 and covers everything above it
        assertEquals(0..4, ScrollRangeRules.rangeOf(list, 4))
    }

    @Test fun `the root is the nearest depth-0 row above, not an earlier root`() {
        val l = listOf(row("a", 0), row("a1", 1), row("b", 0), row("b1", 1), row("b2", 2))
        assertEquals(2..4, ScrollRangeRules.rangeOf(l, 4))
    }

    @Test fun `an unknown row has no box`() {
        assertEquals(null, ScrollRangeRules.rangeOf(list, -1))
        assertEquals(null, ScrollRangeRules.rangeOf(list, 99))
        assertEquals(null, ScrollRangeRules.rangeOf(emptyList(), 0))
    }

    @Test fun `a list that starts mid-tree boxes from the highest ancestor it has`() {
        val l = listOf(row("x", 1), row("y", 2))
        assertEquals(0..1, ScrollRangeRules.rangeOf(l, 1))
    }

    // ── farTarget ─────────────────────────────────────────────────────────────

    @Test fun `viewport wholly above the box jumps to the root row`() {
        assertEquals(172, ScrollRangeRules.farTarget(172..221, first = 100, last = 120))
    }

    @Test fun `viewport wholly below the box jumps to the running row`() {
        assertEquals(221, ScrollRangeRules.farTarget(172..221, first = 225, last = 245))
    }

    @Test fun `any box row on screen means no far jump`() {
        assertEquals(null, ScrollRangeRules.farTarget(172..221, first = 160, last = 172))   // only the root row visible
        assertEquals(null, ScrollRangeRules.farTarget(172..221, first = 221, last = 240))   // only the running row visible
        assertEquals(null, ScrollRangeRules.farTarget(172..221, first = 180, last = 190))   // inside
        assertEquals(null, ScrollRangeRules.farTarget(172..221, first = 100, last = 300))   // box inside the viewport
    }

    // ── correction (viewport 0..1000) ─────────────────────────────────────────

    @Test fun `inside the box nothing moves`() {
        assertEquals(0, ScrollRangeRules.correction(rootTop = null, runBottom = null, viewTop = 0, viewBottom = 1000))
        assertEquals(0, ScrollRangeRules.correction(rootTop = -300, runBottom = null, viewTop = 0, viewBottom = 1000))
        assertEquals(0, ScrollRangeRules.correction(rootTop = null, runBottom = 1400, viewTop = 0, viewBottom = 1000))
        assertEquals(0, ScrollRangeRules.correction(rootTop = -50, runBottom = 1200, viewTop = 0, viewBottom = 1000))
    }

    @Test fun `scrolled above the root row pushes it back to the top edge`() {
        // the root row starts 120 px below the top: that gap shows rows above the root
        assertEquals(120, ScrollRangeRules.correction(rootTop = 120, runBottom = 2000, viewTop = 0, viewBottom = 1000))
    }

    @Test fun `scrolled below the running row pins its bottom to the bottom edge`() {
        // the running row ends 200 px above the bottom: that gap shows rows after it
        assertEquals(-200, ScrollRangeRules.correction(rootTop = null, runBottom = 800, viewTop = 0, viewBottom = 1000))
        assertEquals(-200, ScrollRangeRules.correction(rootTop = -900, runBottom = 800, viewTop = 0, viewBottom = 1000))
    }

    @Test fun `a box shorter than the screen keeps the root row at the top`() {
        // root at 0, running ends at 400: bottom wall would want 600 down, top wall allows none
        assertEquals(0, ScrollRangeRules.correction(rootTop = 0, runBottom = 400, viewTop = 0, viewBottom = 1000))
        // root already cut by 100: only 100 of the 600 may move
        assertEquals(-100, ScrollRangeRules.correction(rootTop = -100, runBottom = 400, viewTop = 0, viewBottom = 1000))
    }

    @Test fun `the top wall wins when both are violated`() {
        assertEquals(50, ScrollRangeRules.correction(rootTop = 50, runBottom = 300, viewTop = 0, viewBottom = 1000))
    }

    @Test fun `padding moves the walls`() {
        assertEquals(30, ScrollRangeRules.correction(rootTop = 50, runBottom = 2000, viewTop = 20, viewBottom = 980))
        assertEquals(-80, ScrollRangeRules.correction(rootTop = null, runBottom = 900, viewTop = 20, viewBottom = 980))
    }

    @Test fun `correction converges in one step`() {
        // apply the returned dy (positive moves content up = edges decrease) and ask again
        val rootTop = -100; val runBottom = 700
        val dy = ScrollRangeRules.correction(rootTop, runBottom, 0, 1000)
        val again = ScrollRangeRules.correction(rootTop - dy, runBottom - dy, 0, 1000)
        assertEquals(0, again)
    }

    // ── bottomOffset ──────────────────────────────────────────────────────────

    @Test fun `a row is pinned to the bottom edge by its own height`() {
        assertEquals(900, ScrollRangeRules.bottomOffset(viewHeight = 1000, rowHeight = 100))
        assertEquals(0, ScrollRangeRules.bottomOffset(viewHeight = 80, rowHeight = 100))
        assertTrue(ScrollRangeRules.bottomOffset(1000, 1000) == 0)
    }
}
