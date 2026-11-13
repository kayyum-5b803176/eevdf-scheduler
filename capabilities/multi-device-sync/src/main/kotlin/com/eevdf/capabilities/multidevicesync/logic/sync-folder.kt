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
    private val ackDir = File(base, "ack")

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
        (journalDir.isDirectory || journalDir.mkdirs()) && (snapDir.isDirectory || snapDir.mkdirs()) &&
            (ackDir.isDirectory || ackDir.mkdirs()) && base.canWrite()
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

    // ── Acknowledgements ──────────────────────────────────────────────────────
    //
    // A member's ack is a small file saying how far it has read into everyone
    // else's journal. The owner of a journal file deletes it once every
    // active member's ack has moved past it. Same rule as the rest of the
    // folder: only the owner writes (or deletes) its own ack, and a new ack is
    // a NEW file (a-<member>-<time>.json), never an overwrite, so a reader
    // sees a complete one or an older complete one.

    /** Every member's ack files, newest first. */
    fun ackFiles(): Map<String, List<File>> {
        val out = HashMap<String, MutableList<File>>()
        for (f in ackDir.listFiles().orEmpty()) {
            val m = ACK_NAME.matchEntire(f.name) ?: continue
            out.getOrPut(m.groupValues[1]) { ArrayList() }.add(f)
        }
        out.values.forEach { list -> list.sortByDescending { it.name } }
        return out
    }

    fun readText(file: File): String? = try { file.readText(Charsets.UTF_8) } catch (_: IOException) { null }

    /** Writes this member's ack and removes its older ones. Returns the new file. */
    @Throws(IOException::class)
    fun writeAck(at: Long, json: String): File {
        val name = "a-$peerId-" + String.format("%013d", at) + ".json"
        val tmp = File(ackDir, "$name.tmp")
        val target = File(ackDir, name)
        try {
            FileOutputStream(tmp).use { fos ->
                fos.write(json.toByteArray(Charsets.UTF_8))
                fos.flush()
                fos.fd.sync()
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) throw IOException("could not write ack")
        } finally {
            if (tmp.exists()) tmp.delete()
        }
        ackFiles()[peerId].orEmpty().filter { it.name != name }.forEach { it.delete() }
        return target
    }

    // ── This phone ────────────────────────────────────────────────────────────
    //
    // `device.json` identifies the PHONE this folder lives on (every Android
    // user of the phone shares the folder, so they share the id) and holds the
    // phone-wide Bluetooth choice. It is the one file in the folder that is
    // rewritten in place and the one that is never copied to the other phone.

    data class DeviceSettings(
        val id: String,
        val bluetoothEnabled: Boolean = false,
        val peerAddress: String? = null,
        val peerName: String? = null,
        /** The other phone's id, learned on the first successful exchange. */
        val peerDeviceId: String? = null,
        /** Keep the Bluetooth link (and alarms in step) while the app is closed. */
        val keepAlive: Boolean = false,
    )

    private val deviceFile = File(base, "device.json")

    /** This phone's settings, creating them (with a new random id) the first time. */
    @Suppress("UNCHECKED_CAST")
    fun deviceSettings(): DeviceSettings {
        val parsed = try {
            if (deviceFile.exists()) MiniJson.parse(deviceFile.readText(Charsets.UTF_8)) as? Map<String, Any?> else null
        } catch (_: Exception) {
            null
        }
        val id = parsed?.get("id") as? String
        if (parsed != null && id != null && id.matches(Regex("[a-z0-9]{6,32}"))) {
            return DeviceSettings(
                id = id,
                bluetoothEnabled = parsed["bt"] == true,
                peerAddress = parsed["peerAddress"] as? String,
                peerName = parsed["peerName"] as? String,
                peerDeviceId = parsed["peerDevice"] as? String,
                keepAlive = parsed["bg"] == true,
            )
        }
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        val random = java.security.SecureRandom()
        val fresh = DeviceSettings(buildString { repeat(12) { append(alphabet[random.nextInt(alphabet.length)]) } })
        saveDeviceSettings(fresh)
        return fresh
    }

    fun deviceId(): String = deviceSettings().id

    @Throws(IOException::class)
    fun saveDeviceSettings(s: DeviceSettings) {
        if (!(base.isDirectory || base.mkdirs())) throw IOException("sync folder cannot be created")
        val json = MiniJson.write(
            linkedMapOf(
                "id" to s.id, "bt" to s.bluetoothEnabled, "peerAddress" to s.peerAddress,
                "peerName" to s.peerName, "peerDevice" to s.peerDeviceId, "bg" to s.keepAlive,
            )
        )
        val tmp = File(base, "device.json.tmp")
        FileOutputStream(tmp).use { it.write(json.toByteArray(Charsets.UTF_8)); it.fd.sync() }
        if (deviceFile.exists()) deviceFile.delete()
        if (!tmp.renameTo(deviceFile)) throw IOException("could not save device settings")
    }

    // ── Copying between phones ────────────────────────────────────────────────
    //
    // Used only by [DeviceExchange]. On this phone the copier is the only
    // writer of the OTHER phone's members' files, and it follows the same
    // rules: journal files only grow by appending bytes that are an exact copy
    // of the original, whole files (snapshots, acks) appear by rename.

    /** One shareable file: 'j' journal, 's' snapshot, 'a' ack. */
    data class Entry(val kind: Char, val name: String, val size: Long)

    fun inventory(): List<Entry> {
        val out = ArrayList<Entry>()
        journalDir.listFiles().orEmpty().forEach { if (JOURNAL_NAME.matches(it.name)) out.add(Entry('j', it.name, it.length())) }
        snapDir.listFiles().orEmpty().forEach { if (SNAP_NAME.matches(it.name)) out.add(Entry('s', it.name, it.length())) }
        ackDir.listFiles().orEmpty().forEach { if (ACK_NAME.matches(it.name)) out.add(Entry('a', it.name, it.length())) }
        return out
    }

    /** The file for a shareable name, or null if the name is not one this folder would ever hold. */
    fun fileFor(kind: Char, name: String): File? = when {
        kind == 'j' && JOURNAL_NAME.matches(name) -> File(journalDir, name)
        kind == 's' && SNAP_NAME.matches(name) -> File(snapDir, name)
        kind == 'a' && ACK_NAME.matches(name) -> File(ackDir, name)
        else -> null
    }

    /** The member a shareable file belongs to (journal and ack names carry it). */
    fun memberOf(kind: Char, name: String): String? = when (kind) {
        'j' -> JOURNAL_NAME.matchEntire(name)?.groupValues?.get(1)
        'a' -> ACK_NAME.matchEntire(name)?.groupValues?.get(1)
        's' -> SNAP_NAME.matchEntire(name)?.groupValues?.get(2)
        else -> null
    }

    fun segmentOf(name: String): Int? = JOURNAL_NAME.matchEntire(name)?.groupValues?.get(2)?.toInt()

    /** The member id → phone id recorded in each member's newest ack. */
    @Suppress("UNCHECKED_CAST")
    fun memberHomes(): Map<String, String> {
        val out = HashMap<String, String>()
        for ((member, files) in ackFiles()) {
            for (f in files) {
                val dev = try {
                    (MiniJson.parse(f.readText(Charsets.UTF_8)) as? Map<String, Any?>)?.get("dev") as? String
                } catch (_: Exception) {
                    null
                }
                if (dev != null) { out[member] = dev; break }
            }
        }
        return out
    }

    /** Appends a received piece of a journal file. Refuses (false) unless the file is exactly [offset] long. */
    @Throws(IOException::class)
    fun appendCopy(name: String, offset: Long, bytes: ByteArray, len: Int): Boolean {
        val file = fileFor('j', name) ?: return false
        if (!(journalDir.isDirectory || journalDir.mkdirs())) throw IOException("journal folder cannot be created")
        val current = if (file.exists()) file.length() else 0L
        if (current != offset) return false
        FileOutputStream(file, true).use { it.write(bytes, 0, len) }
        return true
    }

    /** Forces a received journal file to the card once its last piece arrived. */
    fun syncFile(kind: Char, name: String) {
        val file = fileFor(kind, name) ?: return
        try { RandomAccessFile(file, "rw").use { it.fd.sync() } } catch (_: IOException) { }
    }

    /** Temporary file a whole received file (snapshot, ack) is assembled in. */
    fun copyTemp(kind: Char, name: String): File? {
        val target = fileFor(kind, name) ?: return null
        target.parentFile?.mkdirs()
        return File(target.parentFile, ".incoming-$name")
    }

    /** Makes a fully received whole file visible under its real name. */
    @Throws(IOException::class)
    fun finishCopy(kind: Char, name: String, temp: File, expectedSize: Long): Boolean {
        val target = fileFor(kind, name) ?: return false
        if (temp.length() != expectedSize) { temp.delete(); return false }
        RandomAccessFile(temp, "rw").use { it.fd.sync() }
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) { temp.delete(); throw IOException("could not finish ${target.name}") }
        return true
    }

    fun deleteCopy(kind: Char, name: String): Boolean = fileFor(kind, name)?.let { it.exists() && it.delete() } ?: false

    /** Removes half-received files left by an interrupted exchange. */
    fun clearIncoming() {
        for (dir in listOf(snapDir, ackDir)) dir.listFiles().orEmpty().forEach { if (it.name.startsWith(".incoming-")) it.delete() }
    }

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
        ackDir.listFiles()?.forEach { n += it.length() }
        return n
    }

    companion object {
        const val DIR = "eevdf-sync"
        const val KEEP_SNAPSHOTS = 2
        private const val STALE_TMP_MS = 60L * 60L * 1000L
        private val JOURNAL_NAME = Regex("j-([A-Za-z0-9]+)-(\\d{6})\\.log")
        private val ACK_NAME = Regex("a-([A-Za-z0-9]+)-(\\d{13})\\.json")
        private val SNAP_NAME = Regex("s-(\\d{13})-([A-Za-z0-9]+)\\.snap\\.gz")
    }
}
