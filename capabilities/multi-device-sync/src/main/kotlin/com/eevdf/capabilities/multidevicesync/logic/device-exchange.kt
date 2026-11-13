package com.eevdf.capabilities.multidevicesync.logic

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * Brings two phones' sync folders up to date with each other over any pair of
 * byte streams — in the app a Bluetooth socket, in the tests a pipe.
 *
 * ── The idea ──────────────────────────────────────────────────────────────────
 *
 * Nothing new is merged here. Each phone's folder already holds every one of
 * its users' journals, snapshots and acks; after an exchange it also holds the
 * OTHER phone's. The users on each phone then read those files exactly as they
 * read their neighbours' on the same card. The merge engine never learns that
 * a second phone exists.
 *
 * ── Who is the authority for what ────────────────────────────────────────────
 *
 * Every member (an app install in one Android user) lives on exactly one
 * phone; its acks say which (`dev`). For a member's journal:
 *
 *  - on its HOME phone the files are the originals; the other phone only
 *    ever receives them, and only ever as exact appended copies;
 *  - when the home phone has deleted old files (everyone confirmed them), the
 *    other phone deletes its copies of the same files — never the reverse;
 *  - for a member whose home is not known yet (no ack so far), files are only
 *    ever added, never deleted.
 *
 * Snapshots: the newest two across both phones are kept on both. Acks: the
 * newest per member.
 *
 * ── Order on the wire ─────────────────────────────────────────────────────────
 *
 *   initiator → HELLO          (version, phone id, name, its clock)
 *   responder → HELLO
 *   initiator → INVENTORY      (+ the measured clock difference)
 *   responder → INVENTORY
 *   initiator → its files, END
 *   responder → its files, END
 *
 * Strictly one direction at a time, so neither side can block on a full
 * buffer. Deletions are applied only after BOTH directions completed; an
 * exchange cut off half-way leaves each folder valid and resumes next time
 * (a journal copy that is shorter than the original is simply continued).
 */
internal class DeviceExchange(
    private val folder: SyncFolder,
    private val deviceName: String,
    private val now: () -> Long,
    /** Called with the running byte count, so a caller can tell a stalled link from a slow one. */
    private val onProgress: (Long) -> Unit = {},
) {
    data class Result(
        val remoteDevice: String,
        val remoteName: String,
        /** Other phone's clock minus ours, in ms; measured by the initiator, told to the responder. */
        val clockOffsetMs: Long,
        val filesSent: Int,
        val filesReceived: Int,
        val bytesSent: Long,
        val bytesReceived: Long,
        val filesDeleted: Int,
    )

    /** Raised when the other side speaks a different protocol; the exchange stops without changing anything. */
    class ProtocolException(message: String) : IOException(message)

    private var sent = 0L
    private var received = 0L

    @Throws(IOException::class)
    fun run(initiator: Boolean, input: InputStream, output: OutputStream): Result {
        val inp = DataInputStream(input.buffered(CHUNK * 2))
        val out = DataOutputStream(output.buffered(CHUNK * 2))
        val myId = folder.deviceId()
        folder.clearIncoming()

        // ── HELLO ────────────────────────────────────────────────────────────
        val t0 = now()
        if (initiator) writeHello(out, myId, t0)
        val hello = readHello(inp)
        val t2 = now()
        if (!initiator) writeHello(out, myId, now())
        if (hello.deviceId == myId) throw ProtocolException("both phones use the same sync folder identity")

        // ── INVENTORY ────────────────────────────────────────────────────────
        val myInventory = folder.inventory()
        val myHomes = folder.memberHomes()
        val offset: Long
        val theirs: Inventory
        if (initiator) {
            offset = hello.clock - (t0 + t2) / 2
            writeInventory(out, offset, myInventory, myHomes)
            theirs = readInventory(inp)
        } else {
            theirs = readInventory(inp)
            offset = -theirs.offset
            writeInventory(out, 0L, myInventory, myHomes)
        }

        val homes = HashMap<String, String>(theirs.homes).apply { putAll(myHomes) }
        val plan = plan(myInventory, theirs.entries, myId, hello.deviceId, homes)

        // ── FILES, one direction at a time ───────────────────────────────────
        var filesReceived: Int
        if (initiator) {
            sendAll(out, plan.send)
            filesReceived = receiveAll(inp)
        } else {
            filesReceived = receiveAll(inp)
            sendAll(out, plan.send)
        }

        // ── Only now: deletions ──────────────────────────────────────────────
        var deleted = 0
        for (e in plan.delete) if (folder.deleteCopy(e.kind, e.name)) deleted++
        return Result(hello.deviceId, hello.name, offset, plan.send.size, filesReceived, sent, received, deleted)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Planning (pure; both sides run it on the same two inventories)
    // ═════════════════════════════════════════════════════════════════════════

    data class Transfer(val kind: Char, val name: String, val from: Long, val size: Long)
    data class Plan(val send: List<Transfer>, val delete: List<SyncFolder.Entry>)

    fun plan(
        mine: List<SyncFolder.Entry>,
        theirs: List<SyncFolder.Entry>,
        myDevice: String,
        theirDevice: String,
        homes: Map<String, String>,
    ): Plan {
        val send = ArrayList<Transfer>()
        val delete = ArrayList<SyncFolder.Entry>()
        val theirsByName = theirs.associateBy { it.kind to it.name }

        // Journals, member by member, oldest file first (a copy never has a
        // gap at the start except where the home phone deleted).
        val myJournals = mine.filter { it.kind == 'j' }.groupBy { folder.memberOf('j', it.name)!! }
        val theirJournals = theirs.filter { it.kind == 'j' }.groupBy { folder.memberOf('j', it.name) ?: "" }
        for ((member, files) in myJournals.toSortedMap()) {
            val home = homes[member]
            val theirFiles = theirJournals[member].orEmpty()
            if (home == theirDevice) {
                // They hold the originals. Copies of files they no longer have go.
                val oldestTheirs = theirFiles.mapNotNull { folder.segmentOf(it.name) }.minOrNull() ?: continue
                for (f in files) if ((folder.segmentOf(f.name) ?: continue) < oldestTheirs) delete.add(f)
                continue
            }
            // Ours (or nobody knows yet): give them what they lack, oldest first.
            // A file of ours they deleted is not resent: they only delete copies
            // of OUR files after we did, so if they lack it, so do we.
            for (f in files.sortedBy { folder.segmentOf(it.name) }) {
                val other = theirsByName['j' to f.name]
                val from = other?.size ?: 0L
                if (other != null && from >= f.size) continue
                send.add(Transfer('j', f.name, from, f.size))
            }
        }

        // Snapshots: keep the newest two of both phones together.
        val allSnaps = (mine + theirs).filter { it.kind == 's' }.map { it.name }.distinct().sortedDescending()
        val keep = allSnaps.take(SyncFolder.KEEP_SNAPSHOTS).toSet()
        for (s in mine.filter { it.kind == 's' }) {
            if (s.name !in keep) delete.add(s)
            else if (theirsByName['s' to s.name] == null) send.add(Transfer('s', s.name, 0L, s.size))
        }

        // Acks: the newest per member, on both phones.
        val newestAck = (mine + theirs).filter { it.kind == 'a' }
            .groupBy { folder.memberOf('a', it.name) ?: "" }
            .mapValues { (_, v) -> v.maxOf { it.name } }
        for (a in mine.filter { it.kind == 'a' }) {
            val member = folder.memberOf('a', a.name) ?: continue
            if (a.name != newestAck[member]) delete.add(a)
            else if (theirsByName['a' to a.name] == null) send.add(Transfer('a', a.name, 0L, a.size))
        }
        return Plan(send, delete)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Wire format
    // ═════════════════════════════════════════════════════════════════════════

    private class Hello(val deviceId: String, val name: String, val clock: Long)
    private class Inventory(val offset: Long, val homes: Map<String, String>, val entries: List<SyncFolder.Entry>)

    private fun writeHello(out: DataOutputStream, id: String, clock: Long) {
        out.writeInt(MAGIC); out.writeInt(VERSION)
        out.writeUTF(id); out.writeUTF(deviceName.take(64)); out.writeLong(clock)
        out.flush()
    }

    private fun readHello(inp: DataInputStream): Hello {
        if (inp.readInt() != MAGIC) throw ProtocolException("not an EEVDF phone")
        val version = inp.readInt()
        if (version != VERSION) throw ProtocolException("the other phone runs a different sync version ($version)")
        return Hello(inp.readUTF(), inp.readUTF(), inp.readLong())
    }

    private fun writeInventory(out: DataOutputStream, offset: Long, entries: List<SyncFolder.Entry>, homes: Map<String, String>) {
        out.writeByte(T_INVENTORY.toInt())
        out.writeLong(offset)
        out.writeInt(homes.size)
        for ((m, d) in homes) { out.writeUTF(m); out.writeUTF(d) }
        out.writeInt(entries.size)
        for (e in entries) { out.writeByte(e.kind.code); out.writeUTF(e.name); out.writeLong(e.size) }
        out.flush()
    }

    private fun readInventory(inp: DataInputStream): Inventory {
        if (inp.readByte() != T_INVENTORY) throw ProtocolException("expected the file list")
        val offset = inp.readLong()
        val nHomes = inp.readInt()
        if (nHomes !in 0..MAX_ENTRIES) throw ProtocolException("bad member list")
        val homes = HashMap<String, String>()
        repeat(nHomes) { homes[inp.readUTF()] = inp.readUTF() }
        val n = inp.readInt()
        if (n !in 0..MAX_ENTRIES) throw ProtocolException("bad file list")
        val entries = ArrayList<SyncFolder.Entry>(n)
        repeat(n) {
            val kind = inp.readByte().toInt().toChar()
            val name = inp.readUTF()
            val size = inp.readLong()
            // Names that this folder would never hold are dropped, so a
            // misbehaving peer cannot make us read or write anything else.
            if (folder.fileFor(kind, name) != null && size >= 0) entries.add(SyncFolder.Entry(kind, name, size))
        }
        return Inventory(offset, homes, entries)
    }

    private fun sendAll(out: DataOutputStream, transfers: List<Transfer>) {
        val buffer = ByteArray(CHUNK)
        for (t in transfers) {
            val file = folder.fileFor(t.kind, t.name) ?: continue
            // A file can have grown since the inventory; send what was planned.
            RandomAccessFile(file, "r").use { raf ->
                var pos = t.from
                raf.seek(pos)
                while (pos < t.size) {
                    val n = raf.read(buffer, 0, minOf(CHUNK.toLong(), t.size - pos).toInt())
                    if (n <= 0) break
                    out.writeByte(T_CHUNK.toInt()); out.writeByte(t.kind.code); out.writeUTF(t.name)
                    out.writeLong(pos); out.writeInt(n); out.write(buffer, 0, n)
                    pos += n
                    sent += n
                    onProgress(sent + received)
                }
                out.writeByte(T_DONE.toInt()); out.writeByte(t.kind.code); out.writeUTF(t.name); out.writeLong(pos)
            }
        }
        out.writeByte(T_END.toInt())
        out.flush()
    }

    /** Receives files until END. Returns how many arrived complete. */
    private fun receiveAll(inp: DataInputStream): Int {
        val buffer = ByteArray(CHUNK)
        var complete = 0
        var tempName: String? = null
        var temp: File? = null
        var tempOut: FileOutputStream? = null
        val refused = HashSet<String>()
        try {
            while (true) {
                when (inp.readByte()) {
                    T_END -> return complete
                    T_CHUNK -> {
                        val kind = inp.readByte().toInt().toChar()
                        val name = inp.readUTF()
                        val offset = inp.readLong()
                        val len = inp.readInt()
                        if (len !in 1..CHUNK) throw ProtocolException("bad chunk")
                        inp.readFully(buffer, 0, len)
                        received += len
                        onProgress(sent + received)
                        val key = "$kind/$name"
                        if (key in refused) continue
                        if (kind == 'j') {
                            if (!folder.appendCopy(name, offset, buffer, len)) refused.add(key)
                        } else {
                            if (tempName != key) {
                                tempOut?.close(); temp?.delete()
                                tempName = key
                                temp = folder.copyTemp(kind, name)
                                tempOut = temp?.let { FileOutputStream(it) }
                                if (temp == null || offset != 0L) { refused.add(key); tempOut?.close(); temp?.delete(); tempName = null; temp = null; tempOut = null; continue }
                            }
                            tempOut!!.write(buffer, 0, len)
                        }
                    }
                    T_DONE -> {
                        val kind = inp.readByte().toInt().toChar()
                        val name = inp.readUTF()
                        val size = inp.readLong()
                        val key = "$kind/$name"
                        if (key in refused) continue
                        if (kind == 'j') {
                            folder.syncFile('j', name)
                            if ((folder.fileFor('j', name)?.length() ?: -1L) == size) complete++
                        } else if (tempName == key) {
                            tempOut?.close(); tempOut = null
                            if (folder.finishCopy(kind, name, temp!!, size)) {
                                complete++
                                if (kind == 'a') dropOlderAcks(name)
                            }
                            tempName = null; temp = null
                        } else if (size == 0L) {
                            // an empty whole file: nothing was chunked
                            val t = folder.copyTemp(kind, name) ?: continue
                            t.writeBytes(ByteArray(0))
                            if (folder.finishCopy(kind, name, t, 0L)) complete++
                        }
                    }
                    else -> throw ProtocolException("unexpected message")
                }
            }
        } finally {
            tempOut?.close()
            temp?.delete()
        }
    }

    /** Once a member's newer ack arrived, its older ones are no longer the truth. */
    private fun dropOlderAcks(newest: String) {
        val member = folder.memberOf('a', newest) ?: return
        for (f in folder.ackFiles()[member].orEmpty()) if (f.name < newest) f.delete()
    }

    companion object {
        private const val MAGIC = 0x45564446 // "EVDF"
        const val VERSION = 1
        private const val CHUNK = 32 * 1024
        private const val MAX_ENTRIES = 100_000

        private const val T_INVENTORY: Byte = 2
        private const val T_CHUNK: Byte = 3
        private const val T_DONE: Byte = 4
        private const val T_END: Byte = 5
    }
}
