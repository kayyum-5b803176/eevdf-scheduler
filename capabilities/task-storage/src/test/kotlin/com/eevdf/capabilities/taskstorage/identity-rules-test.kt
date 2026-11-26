package com.eevdf.capabilities.taskstorage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class IdentityRulesTest {

    // ── parse / format ───────────────────────────────────────────────────────

    @Test fun `format and parse round trip for every kind`() {
        for (kind in IdentityKind.values()) {
            val raw = IdentityRules.format(kind, 4242)
            assertEquals(Identity(kind, 4242), IdentityRules.parse(raw))
        }
    }

    @Test fun `parse rejects malformed and out of range`() {
        assertNull(IdentityRules.parse(""))
        assertNull(IdentityRules.parse("process"))
        assertNull(IdentityRules.parse("process:"))
        assertNull(IdentityRules.parse("process:abc"))
        assertNull(IdentityRules.parse("daemon:101"))          // not a kind
        assertNull(IdentityRules.parse("process:0"))
        assertNull(IdentityRules.parse("process:-5"))
        assertNull(IdentityRules.parse("process:${IdentityRules.MAX_ID + 1}"))
        assertEquals(Identity(IdentityKind.PROCESS, IdentityRules.MAX_ID),
            IdentityRules.parse("process:${IdentityRules.MAX_ID}"))
    }

    @Test fun `parseKind reads the kind even with no id`() {
        assertEquals(IdentityKind.USER, IdentityRules.parseKind("user:"))
        assertEquals(IdentityKind.SESSION, IdentityRules.parseKind("session:7"))
        assertNull(IdentityRules.parseKind("nonsense:1"))
    }

    @Test fun `display falls back to undefined`() {
        assertEquals("process:101", IdentityRules.display("process:101"))
        assertEquals("undefined", IdentityRules.display(""))
        assertEquals("undefined", IdentityRules.display("garbage"))
    }

    // ── smallest free ────────────────────────────────────────────────────────

    @Test fun `first id is 101 not 1`() {
        assertEquals(101, IdentityRules.smallestFree(emptyList()))
    }

    @Test fun `reserved ids are never handed out`() {
        assertEquals(101, IdentityRules.smallestFree(listOf(1, 2, 50, 100)))
    }

    @Test fun `takes the lowest gap and reuses freed numbers`() {
        assertEquals(104, IdentityRules.smallestFree(listOf(101, 102, 103)))
        assertEquals(102, IdentityRules.smallestFree(listOf(101, 103)))     // 102 was deleted
        assertEquals(101, IdentityRules.smallestFree(listOf(102, 103)))
    }

    @Test fun `duplicates and order do not matter`() {
        assertEquals(103, IdentityRules.smallestFree(listOf(102, 101, 102, 101)))
    }

    @Test fun `full pool returns null`() {
        // A tiny ceiling stands in for MAX_ID so the test does not build 4M numbers.
        assertEquals(103, IdentityRules.smallestFree(listOf(101, 102), max = 103))
        assertNull(IdentityRules.smallestFree(listOf(101, 102, 103), max = 103))
    }

    @Test fun `the production ceiling is the linux pid maximum`() {
        assertEquals(4_194_304, IdentityRules.MAX_ID)
        // Holding the very top number does not block the bottom of the pool.
        assertEquals(101, IdentityRules.smallestFree(listOf(IdentityRules.MAX_ID)))
        // The ceiling is inclusive: with max = 102, number 102 is still handed out, 103 is not.
        assertEquals(102, IdentityRules.smallestFree(listOf(101), max = 102))
        assertNull(IdentityRules.smallestFree(listOf(101, 102), max = 102))
    }

    // ── per-kind pools ───────────────────────────────────────────────────────

    @Test fun `kinds have separate pools`() {
        val others = listOf("process:101", "process:102")
        assertEquals(103, IdentityRules.nextId(IdentityKind.PROCESS, others))
        assertEquals(101, IdentityRules.nextId(IdentityKind.USER, others))
    }

    @Test fun `changing kind takes the smallest free in the new pool`() {
        val others = listOf("user:101", "process:101", "process:102")
        assertEquals("user:102", IdentityRules.resolve("user:", others))
    }

    // ── resolve ──────────────────────────────────────────────────────────────

    @Test fun `empty becomes undefined 101 on an empty table`() {
        assertEquals("undefined:101", IdentityRules.resolve("", emptyList()))
    }

    @Test fun `a free well formed identity is kept as asked`() {
        assertEquals("process:105", IdentityRules.resolve("process:105", listOf("process:101")))
    }

    @Test fun `a taken identity is reallocated within the same kind`() {
        assertEquals("process:102", IdentityRules.resolve("process:101", listOf("process:101")))
    }

    @Test fun `a reserved identity is reallocated`() {
        assertEquals("user:101", IdentityRules.resolve("user:50", emptyList()))
    }

    @Test fun `unknown kind falls back to undefined`() {
        assertEquals("undefined:101", IdentityRules.resolve("daemon:300", emptyList()))
    }

    @Test fun `resolve returns null when the pool is full`() {
        val all = listOf("thread:101", "thread:102", "thread:103")
        assertNull(IdentityRules.resolve("thread:", all, max = 103))
        assertEquals("user:101", IdentityRules.resolve("user:", all, max = 103))   // other pools unaffected
    }

    // ── repair ───────────────────────────────────────────────────────────────

    @Test fun `repair leaves valid unique identities alone`() {
        val rows = listOf(IdentityRow("a", "process:101"), IdentityRow("b", "user:101"))
        assertTrue(IdentityRules.repair(rows).isEmpty())
    }

    @Test fun `repair fills empty identities in creation order`() {
        val rows = listOf(IdentityRow("a", ""), IdentityRow("b", ""), IdentityRow("c", ""))
        assertEquals(
            mapOf("a" to "undefined:101", "b" to "undefined:102", "c" to "undefined:103"),
            IdentityRules.repair(rows),
        )
    }

    @Test fun `repair keeps the older task on a duplicate and renumbers the newer`() {
        val rows = listOf(IdentityRow("old", "process:101"), IdentityRow("new", "process:101"))
        assertEquals(mapOf("new" to "process:102"), IdentityRules.repair(rows))
    }

    @Test fun `repair moves a reserved number out of the reserved range`() {
        val rows = listOf(IdentityRow("a", "group:7"))
        assertEquals(mapOf("a" to "group:101"), IdentityRules.repair(rows))
    }

    @Test fun `repair never collides with a number it just handed out`() {
        val rows = listOf(
            IdentityRow("a", "process:101"),
            IdentityRow("b", "process:101"),
            IdentityRow("c", "process:101"),
            IdentityRow("d", ""),
        )
        val fixed = IdentityRules.repair(rows)
        assertEquals("process:102", fixed["b"])
        assertEquals("process:103", fixed["c"])
        assertEquals("undefined:101", fixed["d"])
    }

    // ── which group a kind may sit in ────────────────────────────────────────

    @Test fun `undefined, group and session have no parent restriction`() {
        for (child in listOf(IdentityKind.UNDEFINED, IdentityKind.GROUP, IdentityKind.SESSION))
            for (parent in IdentityKind.values()) assertTrue(IdentityRules.canBeParent(child, parent))
    }

    @Test fun `a user sits only in a group`() {
        assertTrue(IdentityRules.canBeParent(IdentityKind.USER, IdentityKind.GROUP))
        for (p in IdentityKind.values().filter { it != IdentityKind.GROUP })
            assertFalse(IdentityRules.canBeParent(IdentityKind.USER, p))
    }

    @Test fun `a process sits in a user, a group or a process group`() {
        for (p in listOf(IdentityKind.USER, IdentityKind.GROUP, IdentityKind.PROCESS_GROUP))
            assertTrue(IdentityRules.canBeParent(IdentityKind.PROCESS, p))
        for (p in listOf(IdentityKind.UNDEFINED, IdentityKind.PROCESS, IdentityKind.THREAD, IdentityKind.SESSION))
            assertFalse(IdentityRules.canBeParent(IdentityKind.PROCESS, p))
    }

    @Test fun `a thread sits only in a process`() {
        assertEquals(setOf(IdentityKind.PROCESS), IdentityRules.allowedParentKinds(IdentityKind.THREAD))
    }

    @Test fun `a process group sits only in a session`() {
        assertEquals(setOf(IdentityKind.SESSION), IdentityRules.allowedParentKinds(IdentityKind.PROCESS_GROUP))
    }

    @Test fun `kindOf reads an unassigned identity as undefined`() {
        assertEquals(IdentityKind.USER, IdentityRules.kindOf("user:101"))
        assertEquals(IdentityKind.UNDEFINED, IdentityRules.kindOf(""))
        assertEquals(IdentityKind.UNDEFINED, IdentityRules.kindOf("process:"))
    }

    @Test fun `reserved range constants`() {
        assertTrue(IdentityRules.isReserved(1))
        assertTrue(IdentityRules.isReserved(100))
        assertFalse(IdentityRules.isReserved(101))
        assertFalse(IdentityRules.isReserved(0))
    }

    @Test fun `repair throws when a pool cannot hold another number`() {
        val rows = listOf(
            IdentityRow("a", "process:101"),
            IdentityRow("b", "process:102"),
            IdentityRow("extra", "process:"),
        )
        try {
            IdentityRules.repair(rows, max = 102)
            fail("expected IdentityPoolFullException")
        } catch (e: IdentityPoolFullException) {
            assertEquals(IdentityKind.PROCESS, e.kind)
        }
    }
}
