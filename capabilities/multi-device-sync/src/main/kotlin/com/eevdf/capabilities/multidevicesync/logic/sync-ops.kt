package com.eevdf.capabilities.multidevicesync.logic

import java.security.MessageDigest
import java.util.zip.CRC32

/**
 * "Who wrote this, and when" — the one thing every merge decision compares.
 *
 * Ordered by time first, then by member id, so two members that change the
 * same field in the same millisecond still agree on a winner without talking
 * to each other. `Stamp(0, "")` is the baseline: "this value was here before
 * sync was switched on and nobody has vouched for it" — it loses to anything.
 */
internal data class Stamp(val ts: Long, val peer: String) : Comparable<Stamp> {
    override fun compareTo(other: Stamp): Int {
        val c = ts.compareTo(other.ts)
        return if (c != 0) c else peer.compareTo(other.peer)
    }

    companion object {
        val BASELINE = Stamp(0L, "")
    }
}

/** One cell travelling between members: the value and who vouches for it. */
internal data class Cell(val value: Any?, val stamp: Stamp)

/**
 * One change, in the only two shapes the engine merges.
 *
 * Everything that crosses between members — a journal line, a snapshot line,
 * a row parked for a table this build lacks — is decoded into one of these,
 * so there is exactly one merge path to get right.
 */
internal sealed class SyncOp {
    abstract val table: String
    /** The row's identity as every member spells it — see [SyncEngine.naturalKey] / `contentKey`. */
    abstract val key: String

    /**
     * Values for some or all columns of a row.
     *
     * [full] true means "this is the whole row as the sender has it" and may
     * create the row. A partial op only ever changes a row that exists.
     */
    data class Cells(
        override val table: String,
        override val key: String,
        val full: Boolean,
        val rowStamp: Stamp,
        val cells: Map<String, Cell>,
    ) : SyncOp()

    data class Delete(
        override val table: String,
        override val key: String,
        val stamp: Stamp,
    ) : SyncOp()
}

/**
 * Text form of [SyncOp]s.
 *
 * JOURNAL LINES (one member's own changes; the member is the file's owner,
 * so it is not repeated on every line):
 *
 *   {"o":"p","t":"tasks","k":"[\"id\"]","s":1731400000000,"c":{"name":"A", …}}   whole row
 *   {"o":"s","t":"tasks","k":"[\"id\"]","s":1731400000000,"c":{"isRunning":1}}    some columns
 *   {"o":"d","t":"tasks","k":"[\"id\"]","s":1731400000000}                        delete
 *   {"o":"h", …}                                                                 segment header
 *
 * SNAPSHOT LINES (many members' changes folded together, so every line
 * names its own author; a per-column table also carries per-column authors):
 *
 *   {"o":"r","t":…,"k":…,"s":…,"p":"peer","c":{…},"cc":{"name":[ts,"peer"], …}}
 *   {"o":"d","t":…,"k":…,"s":…,"p":"peer"}
 *   {"o":"x","t":…,"k":…,"cells":{"col":[ts,"peer",value], …}}                   parked data
 *
 * An unknown "o" is skipped, not an error: a newer build may add line kinds
 * and an older build must keep reading everything it does understand.
 */
internal object SyncCodec {

    const val FORMAT = 1

    /** Pseudo-column in the parked-data store marking the row itself as alive ("1") or deleted ("0"). */
    const val ROW_MARK = "~row"

    // ── Encoding ──────────────────────────────────────────────────────────────

    fun encodeLocalCells(table: String, key: String, full: Boolean, ts: Long, values: Map<String, Any?>): String {
        val m = LinkedHashMap<String, Any?>()
        m["o"] = if (full) "p" else "s"
        m["t"] = table
        m["k"] = key
        m["s"] = ts
        m["c"] = values
        return MiniJson.write(m)
    }

    fun encodeLocalDelete(table: String, key: String, ts: Long): String =
        MiniJson.write(linkedMapOf("o" to "d", "t" to table, "k" to key, "s" to ts))

    fun encodeSnapshotRow(
        table: String, key: String, rowStamp: Stamp,
        values: Map<String, Any?>, columnStamps: Map<String, Stamp>?,
    ): String {
        val m = LinkedHashMap<String, Any?>()
        m["o"] = "r"; m["t"] = table; m["k"] = key
        m["s"] = rowStamp.ts; m["p"] = rowStamp.peer
        m["c"] = values
        if (columnStamps != null) m["cc"] = columnStamps.mapValues { listOf(it.value.ts, it.value.peer) }
        return MiniJson.write(m)
    }

    fun encodeSnapshotDelete(table: String, key: String, stamp: Stamp): String =
        MiniJson.write(linkedMapOf("o" to "d", "t" to table, "k" to key, "s" to stamp.ts, "p" to stamp.peer))

    fun encodeSnapshotParked(table: String, key: String, cells: Map<String, Cell>): String {
        val m = LinkedHashMap<String, Any?>()
        m["o"] = "x"; m["t"] = table; m["k"] = key
        m["cells"] = cells.mapValues { listOf(it.value.stamp.ts, it.value.stamp.peer, it.value.value) }
        return MiniJson.write(m)
    }

    // ── Decoding ──────────────────────────────────────────────────────────────

    /**
     * Decodes one line. [filePeer] is the journal's owner and is used when the
     * line does not name its own author. Returns null for headers, unknown
     * line kinds and anything structurally incomplete.
     */
    @Suppress("UNCHECKED_CAST")
    fun decode(json: String, filePeer: String): SyncOp? {
        val m = MiniJson.parse(json) as? Map<String, Any?> ?: return null
        val kind = m["o"] as? String ?: return null
        val table = m["t"] as? String ?: return null
        val key = m["k"] as? String ?: return null
        when (kind) {
            "p", "s", "r" -> {
                val ts = (m["s"] as? Number)?.toLong() ?: return null
                val rowStamp = Stamp(ts, (m["p"] as? String) ?: filePeer)
                val values = m["c"] as? Map<String, Any?> ?: return null
                val perColumn = m["cc"] as? Map<String, Any?>
                val cells = LinkedHashMap<String, Cell>(values.size * 2)
                for ((col, raw) in values) {
                    val cs = (perColumn?.get(col) as? List<Any?>)?.let { stampOf(it) } ?: rowStamp
                    cells[col] = Cell(MiniJson.toCell(raw), cs)
                }
                return SyncOp.Cells(table, key, full = kind != "s", rowStamp = rowStamp, cells = cells)
            }
            "d" -> {
                val ts = (m["s"] as? Number)?.toLong() ?: return null
                return SyncOp.Delete(table, key, Stamp(ts, (m["p"] as? String) ?: filePeer))
            }
            "x" -> {
                val raw = m["cells"] as? Map<String, Any?> ?: return null
                var mark: Cell? = null
                val cells = LinkedHashMap<String, Cell>()
                for ((col, v) in raw) {
                    val list = v as? List<Any?> ?: continue
                    val stamp = stampOf(list) ?: continue
                    val cell = Cell(MiniJson.toCell(list.getOrNull(2)), stamp)
                    if (col == ROW_MARK) mark = cell else cells[col] = cell
                }
                if (mark != null && mark.value?.toString() == "0") {
                    return SyncOp.Delete(table, key, mark.stamp)
                }
                val rowStamp = mark?.stamp ?: (cells.values.maxOfOrNull { it.stamp } ?: return null)
                return SyncOp.Cells(table, key, full = mark != null, rowStamp = rowStamp, cells = cells)
            }
            else -> return null
        }
    }

    private fun stampOf(list: List<Any?>): Stamp? {
        val ts = (list.getOrNull(0) as? Number)?.toLong() ?: return null
        val peer = list.getOrNull(1) as? String ?: return null
        return Stamp(ts, peer)
    }

    // ── Line framing: "<crc32 hex>\t<json>\n" ─────────────────────────────────

    /**
     * Wraps [json] for the journal file. The checksum is what makes a write
     * that was cut short (app killed on a user switch, card pulled) harmless:
     * the reader sees a line whose checksum does not match and steps over it.
     */
    fun frame(json: String): String = crcHex(json) + "\t" + json + "\n"

    /** Returns the JSON inside a framed line (without its trailing newline), or null if damaged. */
    fun unframe(line: String): String? {
        val tab = line.indexOf('\t')
        if (tab != 8) return null
        val json = line.substring(9)
        return if (crcHex(json) == line.substring(0, 8)) json else null
    }

    private fun crcHex(text: String): String {
        val crc = CRC32()
        crc.update(text.toByteArray(Charsets.UTF_8))
        return String.format("%08x", crc.value)
    }

    // ── Fingerprints ──────────────────────────────────────────────────────────

    /** 32-bit fingerprint of one cell value: "has this changed since I last looked". */
    fun valueHash(value: Any?): Long {
        val crc = CRC32()
        crc.update(MiniJson.write(value).toByteArray(Charsets.UTF_8))
        return crc.value
    }

    /** Fingerprint of a whole row, over [columns] in the given (schema) order. */
    fun rowHash(columns: List<String>, values: Map<String, Any?>): Long {
        val crc = CRC32()
        val sb = StringBuilder()
        for (c in columns) {
            sb.setLength(0)
            sb.append(c).append('=')
            MiniJson.write(values[c], sb)
            sb.append('\n')
            crc.update(sb.toString().toByteArray(Charsets.UTF_8))
        }
        return crc.value
    }

    /**
     * 80-bit identity for a row of a table whose ids are machine-local.
     * Computed from the row's content with the column names sorted, so two
     * builds that declare the same columns in a different order still agree.
     */
    fun contentId(table: String, values: Map<String, Any?>): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(table.toByteArray(Charsets.UTF_8))
        for (c in values.keys.sorted()) {
            md.update(0)
            md.update(c.toByteArray(Charsets.UTF_8))
            md.update(1)
            md.update(MiniJson.write(values[c]).toByteArray(Charsets.UTF_8))
        }
        val d = md.digest()
        val sb = StringBuilder("h:")
        for (i in 0 until 10) sb.append(String.format("%02x", d[i]))
        return sb.toString()
    }
}
