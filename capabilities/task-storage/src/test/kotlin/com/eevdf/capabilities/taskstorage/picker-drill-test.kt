package com.eevdf.capabilities.taskstorage

import com.eevdf.capabilities.taskstorage.PickerDrill.Next
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PickerDrillTest {

    private var n = 0
    private fun t(name: String, identity: String, parent: Task? = null, isGroup: Boolean = true) =
        Task(id = "id${++n}", name = name, priority = 3, timeSliceSeconds = 60L,
            identity = identity, parentId = parent?.id, isGroup = isGroup)

    // pgroup1 (pgroup:104) { process1, process2 }   pgroup2 (pgroup:105) { process1, process3 }
    private val pg1 = t("pgroup1", "pgroup:104")
    private val pg2 = t("pgroup2", "pgroup:105")
    private val p1a = t("process1", "process:201", pg1)
    private val p2  = t("process2", "process:202", pg1)
    private val p1b = t("process1", "process:203", pg2)
    private val p3  = t("process3", "process:204", pg2)
    private val all = listOf(pg1, pg2, p1a, p2, p1b, p3)
    private val byId = all.associateBy { it.id }
    private val processes = listOf(p1a, p2, p1b, p3)

    // ── the first step: distinct names ───────────────────────────────────────

    @Test fun `each distinct name is listed once`() {
        val rows = PickerDrill.nameRows(processes)
        assertEquals(listOf("process1", "process2", "process3"), rows.map { it.label })
        assertEquals(2, rows.first { it.label == "process1" }.candidates.size)
    }

    // ── case 1: a unique name does nothing extra ─────────────────────────────

    @Test fun `a unique name is chosen at once and keeps its plain label`() {
        assertEquals(Next.Chosen(p3), PickerDrill.next(listOf(p3), byId))
        assertEquals("process3", PickerDrill.label(p3, processes, byId))
    }

    // ── case 2: a repeated name drills into where they are ───────────────────

    @Test fun `a repeated name asks which group holds it`() {
        val ask = PickerDrill.next(listOf(p1a, p1b), byId) as Next.Ask
        assertEquals("process1", ask.title)           // just the name, no "which one?"
        assertEquals(listOf("pgroup1", "pgroup2"), ask.rows.map { it.label })
        assertEquals(Next.Chosen(p1a), PickerDrill.next(ask.rows[0].candidates, byId, ask.depth))
        assertEquals(Next.Chosen(p1b), PickerDrill.next(ask.rows[1].candidates, byId, ask.depth))
    }

    @Test fun `the field shows the path of identity badges`() {
        assertEquals("104.pgroup › process1", PickerDrill.label(p1a, processes, byId))
        assertEquals("105.pgroup › process1", PickerDrill.label(p1b, processes, byId))
        assertEquals("process2", PickerDrill.label(p2, processes, byId))
    }

    // ── climbing until the path is unique ────────────────────────────────────

    @Test fun `groups with one name climb to their parents`() {
        val s1 = t("session1", "session:301"); val s2 = t("session2", "session:302")
        val gA = t("pgroup1", "pgroup:111", s1); val gB = t("pgroup1", "pgroup:112", s2)   // same name, different sessions
        val xA = t("process1", "process:401", gA); val xB = t("process1", "process:402", gB)
        val world = listOf(s1, s2, gA, gB, xA, xB).associateBy { it.id }

        // Both candidates sit in a group NAMED pgroup1: that level is still shown (one row), and picking it
        // climbs to the sessions.
        val level1 = PickerDrill.next(listOf(xA, xB), world) as Next.Ask
        assertEquals(listOf("pgroup1"), level1.rows.map { it.label })
        val level2 = PickerDrill.next(level1.rows[0].candidates, world, level1.depth) as Next.Ask
        assertEquals(listOf("session1", "session2"), level2.rows.map { it.label })
        assertEquals(Next.Chosen(xA), PickerDrill.next(level2.rows[0].candidates, world, level2.depth))
        assertEquals("301.session › 111.pgroup › process1", PickerDrill.label(xA, listOf(xA, xB), world))
        assertEquals("302.session › 112.pgroup › process1", PickerDrill.label(xB, listOf(xA, xB), world))
    }

    // ── the same position: list them one by one ──────────────────────────────

    @Test fun `two top-level tasks with one name are listed with their identity`() {
        val a = t("process1", "process:501", isGroup = false); val b = t("process1", "process:502", isGroup = false)
        val ask = PickerDrill.next(listOf(a, b), mapOf(a.id to a, b.id to b)) as Next.Ask
        assertEquals(listOf("process1 · 501.process", "process1 · 502.process"), ask.rows.map { it.label })
        assertEquals(Next.Chosen(a), PickerDrill.next(ask.rows[0].candidates, mapOf(a.id to a, b.id to b), ask.depth))
    }

    @Test fun `two tasks with one name in one group are listed with their identity`() {
        val a = t("process1", "process:511", pg1); val b = t("process1", "process:512", pg1)
        val world = (all + a + b).associateBy { it.id }
        val level1 = PickerDrill.next(listOf(a, b), world) as Next.Ask
        assertEquals(listOf("pgroup1"), level1.rows.map { it.label })       // the shared group, shown once
        val ask = PickerDrill.next(level1.rows[0].candidates, world, level1.depth) as Next.Ask
        assertEquals(listOf("process1 · 511.process", "process1 · 512.process"), ask.rows.map { it.label })
        assertEquals("104.pgroup › process1 · 511.process", PickerDrill.label(a, listOf(a, b), world))
    }

    @Test fun `a root-level one and a nested one are told apart`() {
        val root = t("process1", "process:521", isGroup = false)
        val nested = t("process1", "process:522", pg1, isGroup = false)
        val world = (all + root + nested).associateBy { it.id }
        val ask = PickerDrill.next(listOf(root, nested), world) as Next.Ask
        assertEquals(listOf(PickerDrill.ROOT_LABEL, "pgroup1"), ask.rows.map { it.label })
        assertEquals("process1", PickerDrill.label(root, listOf(root, nested), world))
        assertEquals("104.pgroup › process1", PickerDrill.label(nested, listOf(root, nested), world))
    }

    @Test fun `a parent that is not known ends the climb instead of failing`() {
        val orphan = Task(id = "o1", name = "process1", priority = 3, timeSliceSeconds = 60L, identity = "process:531", parentId = "missing")
        val other  = Task(id = "o2", name = "process1", priority = 3, timeSliceSeconds = 60L, identity = "process:532", parentId = "gone")
        val world = mapOf(orphan.id to orphan, other.id to other)
        val ask = PickerDrill.next(listOf(orphan, other), world) as Next.Ask
        assertEquals(2, ask.rows.size)                                     // told apart one by one
    }

    @Test fun `a cycle in the parent chain cannot hang it`() {
        val a = Task(id = "c1", name = "x", priority = 3, timeSliceSeconds = 60L, identity = "process:541", parentId = "c2", isGroup = true)
        val b = Task(id = "c2", name = "x", priority = 3, timeSliceSeconds = 60L, identity = "process:542", parentId = "c1", isGroup = true)
        val world = mapOf(a.id to a, b.id to b)
        val ask = PickerDrill.next(listOf(a, b), world) as Next.Ask      // terminates
        assertEquals(2, ask.rows.size)
        PickerDrill.label(a, listOf(a, b), world)                         // terminates
    }

    @Test fun `a badge for a task with no identity reads undefined`() {
        assertEquals("undefined", PickerDrill.badge(t("g", "")))
        assertEquals("104.pgroup", PickerDrill.badge(pg1))
    }
}
