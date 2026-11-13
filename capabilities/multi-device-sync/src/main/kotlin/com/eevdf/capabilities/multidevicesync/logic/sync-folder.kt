package com.eevdf.capabilities.multidevicesync.logic

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Everything the sync feature does to the shared folder, and the only class
 * that touches it.
 *
 * ── Layout ───────────────────────────────────────────────────────────────────
 *
 *   <chosen folder>/eevdf-sync/
 *     journal/j-<member>-000001.log     one member's changes, append-only
 *     journal/j-<member>-000002.log     …continued once the first grows large
 *     snap/s-<time>-<member>.snap.gz    a complete picture, for joining/catching up
 *
 * ── The three rules that make this safe on a removable card ─────────────────
 *
 *  1. ONE WRITER PER FILE. A member only ever appends to its own journal and
 *     only ever deletes its own journal files. Two members can never be
 *     writing the same file, so there is nothing to lock and nothing to
 *     corrupt by interleaving.
 *  2. APPEND ONLY. Existing bytes are never rewritten. A write that is cut
 *     short can damage at most the last line, and every line carries a
 *     checksum, so a damaged line is recognised and skipped.
 *  3. WHOLE-FILE WRITES ARE ATOMIC. A snapshot is written under a temporary
 *     name and renamed when complete. A reader sees the old one or the new
 *     one, never half of one.
 *
 * ── Wear ─────────────────────────────────────────────────────────────────────
 *
 * A typical change (start a timer, stop it, rename a task) appends a few
 * hundred bytes. Reading never writes. The old design rewrote database pages
 * and a metadata file on every change.
 */
internal class SyncFolder(root: File, private val peerId: String) {

    private val base = File(root, DIR)
    private val journalDir = File(base, "journal")
    private val snapDir = File(base, "snap")

    data class Segment(val peer: String, val seg: Int, val file: File)

    data class SnapshotFile(val file: File, val createdAt: Long, val peer: String)

    /** Result of reading new lines from one journal file. */
    data class ReadResult(
        /** JSON of each intact line, in file order. */
        val lines: List<String>,
        /** Byte offset just past the last complete line — the next read position. */
        val newOffset: Long,
        /** Complete lines that failed their checksum and were stepped over. */
        val damaged: Int,
        /** True if the read stopped because [maxLines] was reached, not because the file ended. */
        val more: Boolean,
    )

    /** True when the folder exists (or can be created) and accepts writes. */
    fun ensureWritable(): Boolean = try {
        (journalDir.isDirectory || journalDir.mkdirs()) && (snapDir.isDirectory || snapDir.mkdirs()) && base.canWrite()
    } catch (_: SecurityException) {
        false
    }

    fun isReadable(): Boolean = try { base.isDirectory && base.canRead() } catch (_: SecurityException) { false }

    // ── Journals ──────────────────────────────────────────────────────────────

    /** Every journal segment in the folder, grouped by member and ordered by segment number. */
    fun segments(): Map<String, List<Segment>> {
        val out = HashMap<String, MutableList<Segment>>()
        val files = journalDir.listFiles() ?: return emptyMap()
        for (f in files) {
            val m = JOURNAL_NAME.matchEntire(f.name) ?: continue
            val peer = m.groupValues[1]
            out.getOrPut(peer) { ArrayList() }.add(Segment(peer, m.groupValues[2].toInt(), f))
        }
        out.values.forEach { list -> list.sortBy { it.seg } }
        return out
    }

    private fun segmentFile(seg: Int) = File(journalDir, "j-$peerId-" + String.format("%06d", seg) + ".log")

    /**
     * Appends already-encoded journal lines to this member's segment [seg],
     * creating it (with [header] as its first line) if needed, and forces
     * them to the card before returning. Returns the file's new length.
     *
     * If the previous append was cut short, the file ends mid-line. A newline
     * is written first so the torn fragment becomes one self-contained
     * damaged line instead of swallowing the first new one.
     */
    @Throws(IOException::class)
    fun append(seg: Int, header: String, jsonLines: List<String>): Long {
        val file = segmentFile(seg)
        val fresh = !file.exists() || file.length() == 0L
        var needsNewline = false
        if (!fresh) {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(raf.length() - 1)
                needsNewline = raf.read() != '\n'.code
            }
        }
        FileOutputStream(file, true).use { fos ->
            val w = BufferedWriter(OutputStreamWriter(fos, Charsets.UTF_8), 64 * 1024)
            if (needsNewline) w.write("\n")
            if (fresh) w.write(SyncCodec.frame(header))
            for (json in jsonLines) w.write(SyncCodec.frame(json))
            w.flush()
            fos.fd.sync()
        }
        return file.length()
    }

    fun mySegmentLength(seg: Int): Long = segmentFile(seg).let { if (it.exists()) it.length() else 0L }

    fun mySegmentExists(seg: Int): Boolean = segmentFile(seg).exists()

    /**
     * Reads the complete lines of [segment] starting at byte [offset].
     *
     * Stops before a final line that has no newline yet: it is either still
     * being written or was cut short, and in both cases the right thing is to
     * look again later rather than guess.
     */
    @Throws(IOException::class)
    fun read(segment: Segment, offset: Long, maxLines: Int): ReadResult {
        val length = segment.file.length()
        if (offset >= length) return ReadResult(emptyList(), offset.coerceAtMost(length), 0, false)
        val lines = ArrayList<String>()
        var damaged = 0
        var consumed = offset
        var more = false
        RandomAccessFile(segment.file, "r").use { raf ->
            raf.seek(offset)
            val buf = ByteArray(64 * 1024)
            val pending = java.io.ByteArrayOutputStream()
            var pos = offset
            outer@ while (true) {
                val n = raf.read(buf)
                if (n <= 0) break
                var start = 0
                for (i in 0 until n) {
                    if (buf[i] != '\n'.code.toByte()) continue
                    pending.write(buf, start, i - start)
                    start = i + 1
                    val text = pending.toString(Charsets.UTF_8.name())
                    pending.reset()
                    consumed = pos + i + 1
                    if (text.isNotEmpty()) {
                        val json = SyncCodec.unframe(text)
                        if (json == null) damaged++ else lines.add(json)
                    }
                    if (lines.size >= maxLines) { more = consumed < length; break@outer }
                }
                pending.write(buf, start, n - start)
                pos += n
            }
        }
        return ReadResult(lines, consumed, damaged, more)
    }

    /** This member's own journal segment numbers currently on the card. */
    fun mySegmentNumbers(): List<Int> = segments()[peerId].orEmpty().map { it.seg }

    /** Deletes one of this member's own journal segments. Never touches another member's file. */
    fun deleteMySegment(seg: Int): Boolean = segmentFile(seg).let { it.exists() && it.delete() }

    fun myJournalBytes(): Long = segments()[peerId].orEmpty().sumOf { it.file.length() }

    // ── Snapshots ─────────────────────────────────────────────────────────────

    /** Snapshots in the folder, newest first. */
    fun snapshots(): List<SnapshotFile> {
        val files = snapDir.listFiles() ?: return emptyList()
        return files.mapNotNull { f ->
            val m = SNAP_NAME.matchEntire(f.name) ?: return@mapNotNull null
            SnapshotFile(f, m.groupValues[1].toLong(), m.groupValues[2])
        }.sortedByDescending { it.createdAt }
    }

    /**
     * Writes a snapshot: [header] first, then whatever [body] emits, then an
     * end marker carrying the line count. Written to a temporary name, forced
     * to the card, then renamed — so a snapshot file that exists is complete.
     * Older snapshots beyond the newest [KEEP_SNAPSHOTS] are removed.
     */
    @Throws(IOException::class)
    fun writeSnapshot(createdAt: Long, header: String, body: ((String) -> Unit) -> Int): SnapshotFile {
        val name = "s-" + String.format("%013d", createdAt) + "-$peerId.snap.gz"
        val tmp = File(snapDir, "$name.tmp")
        val target = File(snapDir, name)
        try {
            FileOutputStream(tmp).use { fos ->
                val w = BufferedWriter(OutputStreamWriter(GZIPOutputStream(fos, 64 * 1024), Charsets.UTF_8), 64 * 1024)
                w.write(header); w.write("\n")
                val count = body { line -> w.write(line); w.write("\n") }
                w.write(MiniJson.write(linkedMapOf("o" to "E", "n" to count.toLong()))); w.write("\n")
                w.close() // finishes the gzip stream; fos is still open for the sync below
            }
            // Re-open to force the finished bytes to the card before the rename
            // makes the file visible under its real name.
            RandomAccessFile(tmp, "rw").use { it.fd.sync() }
            if (!tmp.renameTo(target)) throw IOException("could not finish snapshot ${target.name}")
        } finally {
            if (tmp.exists()) tmp.delete()
        }
        // Housekeeping: stale temporaries from an interrupted write, and old snapshots.
        snapDir.listFiles()?.forEach { f ->
            if (f.name.endsWith(".tmp") && f.lastModified() < createdAt - STALE_TMP_MS) f.delete()
        }
        snapshots().drop(KEEP_SNAPSHOTS).forEach { it.file.delete() }
        return SnapshotFile(target, createdAt, peerId)
    }

    /** Reads only a snapshot's header line (cheap: a few hundred bytes). Null if unreadable. */
    fun readSnapshotHeader(snapshot: SnapshotFile): String? = try {
        openSnapshot(snapshot).use { it.readLine() }
    } catch (_: IOException) {
        null
    }

    /**
     * Streams a snapshot's lines (after the header) to [onLine]. Returns true
     * only if the file decoded to its end marker with the expected number of
     * lines — i.e. it is complete and undamaged.
     */
    @Suppress("UNCHECKED_CAST")
    fun readSnapshot(snapshot: SnapshotFile, onLine: (String) -> Unit): Boolean = try {
        var count = 0L
        var complete = false
        openSnapshot(snapshot).use { r ->
            r.readLine() // header
            while (true) {
                val line = r.readLine() ?: break
                if (line.startsWith("{\"o\":\"E\"")) {
                    val n = ((MiniJson.parse(line) as? Map<String, Any?>)?.get("n") as? Number)?.toLong()
                    complete = n == count
                    break
                }
                onLine(line)
                count++
            }
        }
        complete
    } catch (_: IOException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun openSnapshot(snapshot: SnapshotFile): BufferedReader =
        BufferedReader(InputStreamReader(GZIPInputStream(FileInputStream(snapshot.file), 64 * 1024), Charsets.UTF_8), 64 * 1024)

    /** Total bytes the sync feature currently occupies in the folder. */
    fun totalBytes(): Long {
        var n = 0L
        journalDir.listFiles()?.forEach { n += it.length() }
        snapDir.listFiles()?.forEach { n += it.length() }
        return n
    }

    companion object {
        const val DIR = "eevdf-sync"
        const val KEEP_SNAPSHOTS = 2
        private const val STALE_TMP_MS = 60L * 60L * 1000L
        private val JOURNAL_NAME = Regex("j-([A-Za-z0-9]+)-(\\d{6})\\.log")
        private val SNAP_NAME = Regex("s-(\\d{13})-([A-Za-z0-9]+)\\.snap\\.gz")
    }
}
