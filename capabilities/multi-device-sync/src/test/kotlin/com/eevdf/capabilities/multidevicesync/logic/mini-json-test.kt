package com.eevdf.capabilities.multidevicesync.logic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniJsonTest {

    private fun roundTrip(value: Any?): Any? = MiniJson.toCell(MiniJson.parse(MiniJson.write(value)))

    @Test fun `every SQLite value kind survives a round trip with its type intact`() {
        assertNull(roundTrip(null))
        assertEquals(42L, roundTrip(42L))
        assertEquals(Long.MIN_VALUE, roundTrip(Long.MIN_VALUE))
        assertEquals(5.0, roundTrip(5.0))                 // stays REAL, does not become INTEGER 5
        assertEquals(1.0E-10, roundTrip(1.0E-10))
        assertEquals("plain", roundTrip("plain"))
        assertArrayEquals(byteArrayOf(0, 1, -1, 127), roundTrip(byteArrayOf(0, 1, -1, 127)) as ByteArray)
        assertTrue((roundTrip(Double.NaN) as Double).isNaN())
    }

    @Test fun `awkward text survives a round trip`() {
        for (s in listOf("", "quote \" and \\ slash", "line\nbreak\ttab\r", "emoji 😀 and ünïcödé", "\u0001\u001f", "{\"b\":\"x\"}")) {
            assertEquals(s, roundTrip(s))
        }
    }

    @Test fun `a journal line survives framing and a damaged one is rejected`() {
        val json = SyncCodec.encodeLocalCells("tasks", "[\"id\"]", true, 123L, linkedMapOf("name" to "A\nB", "n" to 1L))
        val framed = SyncCodec.frame(json).trimEnd('\n')
        assertEquals(json, SyncCodec.unframe(framed))
        assertNull(SyncCodec.unframe(framed.dropLast(1)))
        assertNull(SyncCodec.unframe(framed.replace("tasks", "tasky")))
        assertNull(SyncCodec.unframe("not a line at all"))
    }

    @Test fun `an unknown line kind is skipped, not an error`() {
        assertNull(SyncCodec.decode("{\"o\":\"future-kind\",\"t\":\"tasks\",\"k\":\"[1]\"}", "peer"))
        assertNull(SyncCodec.decode("{\"o\":\"h\",\"peer\":\"x\"}", "peer"))
    }
}
