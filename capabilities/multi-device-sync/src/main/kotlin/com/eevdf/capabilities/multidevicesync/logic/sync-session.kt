package com.eevdf.capabilities.multidevicesync.logic

import java.io.IOException

/**
 * One complete sync, start to finish: send what changed here, read what
 * changed elsewhere, and keep the shared folder tidy.
 *
 * Pure orchestration over [SyncEngine] (the database side) and [SyncFolder]
 * (the card side). No Android, no threads: the coordinator decides *when*
 * to call this and guarantees only one call runs at a time.
 *
 * ── Order of a cycle, and why ────────────────────────────────────────────────
 *
 *  1. SEND FIRST. Local changes are stamped and appended to this member's
 *     journal before anything is read. If the app is killed a moment later
 *     (the user switched Android user), the work done here is already safe
 *     on the card.
 *  2. READ. Every other member's journal is read from where this member last
 *     stopped — only the new bytes. Each batch is merged and the read
 *     position saved in the same database transaction.
 *  3. FINISH JOINING (first complete cycle only). See [JoinChoice].
 *  4. SEND AGAIN, for anything step 2 or 3 produced.
 *  5. TIDY. Fold a long journal into a snapshot; delete this member's own
 *     journal files that a snapshot already covers.
 *
 * A failure at any step leaves everything consistent: nothing is ever
 * half-applied, and the next cycle simply continues.
 */
internal class SyncSession(
    private val engine: SyncEngine,
    private val folder: SyncFolder,
    private val now: () -> Long,
    private val appVersion: String,
    private val memberName: String,
) {
    /** What to do, the first time, with rows this member has and the group does not. */
    enum class JoinChoice {
        /** Add them to the group. Nothing this member had is lost. The default. */
        KEEP_MINE,
        /** Drop them: end up with exactly the group's data. For a known-stale copy. */
        GROUP_ONLY,
    }

    data class CycleResult(
        var linesWritten: Int = 0,
        var bytesWritten: Long = 0,
        val applied: SyncEngine.ApplyResult = SyncEngine.ApplyResult(),
        var damagedLines: Int = 0,
        var gaps: Int = 0,
        var snapshotLoaded: Boolean = false,
        var snapshotWritten: Boolean = false,
        var announced: Int = 0,
        var discarded: Int = 0,
        /** Set when the folder could be read but not written; the changes wait in the outbox. */
        var writeError: String? = null,
    )

    data class Member(val peer: String, val name: String, val lastWriteMs: Long, val bytes: Long, val isMe: Boolean)

    /** Snapshot headers already parsed this process run, by file name — so a cycle never re-reads one. */
    private val vectorCache = HashMap<String, Map<String, Pair<Int, Long>>?>()

    // ═════════════════════════════════════════════════════════════════════════

    /**
     * The fast path used when the app is leaving the screen: stamp and write
     * out local changes, nothing else. Returns (lines, bytes) written.
     */
    @Throws(IOException::class)
    fun pushOnly(): Pair<Int, Long> {
        engine.capture()
        return drain()
    }

    fun cycle(joinChoice: JoinChoice = JoinChoice.KEEP_MINE): CycleResult {
        val result = CycleResult()

        // 1. Send.
        engine.capture()
        tryDrain(result)

        // 2. Read. An unreadable folder is a real failure — let it surface.
        if (!folder.isReadable() && !folder.ensureWritable()) throw IOException("Sync folder cannot be opened")
        pull(result)

        // 3. Finish joining — only after a complete, successful read, so a
        //    stale copy is never announced over data it has not seen yet.
        if (!engine.isAnnounced) {
            if (joinChoice == JoinChoice.GROUP_ONLY) result.discarded = engine.discardUnannounced()
            else result.announced = engine.announce()
        }

        // 4. Send again.
        engine.capture()
        tryDrain(result)

        // 5. Tidy — never worth failing a sync over.
        if (result.writeError == null) {
            try { maintain(result) } catch (e: IOException) { result.writeError = e.message ?: "maintenance failed" }
        }
        return result
    }

    private fun tryDrain(result: CycleResult) {
        try {
            val (lines, bytes) = drain()
            result.linesWritten += lines
            result.bytesWritten += bytes
        } catch (e: IOException) {
            result.writeError = e.message ?: e.javaClass.simpleName
        }
    }

    // ── Sending ───────────────────────────────────────────────────────────────

    @Throws(IOException::class)
    private fun drain(): Pair<Int, Long> {
        if (engine.outboxSize() == 0L) return 0 to 0L
        if (!folder.ensureWritable()) throw IOException("Sync folder is not writable")

        var seg = engine.getMeta(META_MY_SEG)?.toIntOrNull() ?: 0
        val knownLength = engine.getMeta(META_MY_LEN)?.toLongOrNull() ?: 0L
        if (seg == 0) {
            seg = 1
        } else if (knownLength > 0L && !folder.mySegmentExists(seg)) {
            // The journal this member was writing is gone: the card was wiped
            // or swapped. Other members remember how far into the old file
            // they had read, so re-using its name would make them skip the
            // start of the new one. Move to a new file, and re-publish the
            // full picture so anyone joining the empty folder gets everything.
            seg += 1
            engine.setMeta(META_NEED_SNAPSHOT, "1")
        }
        engine.setMeta(META_MY_SEG, seg.toString())

        var lines = 0
        var bytes = 0L
        while (true) {
            val batch = engine.peekOutbox(OUTBOX_BATCH)
            if (batch.isEmpty()) break
            val before = folder.mySegmentLength(seg)
            if (before > SEGMENT_BYTES) {
                engine.setMeta(META_SEG_END + seg, now().toString())
                seg += 1
                engine.setMeta(META_MY_SEG, seg.toString())
                continue
            }
            val after = folder.append(seg, journalHeader(seg), batch.map { it.second })
            // Only now — the bytes are on the card — is it safe to forget them.
            engine.ackOutbox(batch.last().first)
            engine.setMeta(META_MY_LEN, after.toString())
            lines += batch.size
            bytes += after - before
        }
        return lines to bytes
    }

    private fun journalHeader(seg: Int): String = MiniJson.write(
        linkedMapOf(
            "o" to "h", "peer" to engine.peerId, "seg" to seg.toLong(), "fmt" to SyncCodec.FORMAT.toLong(),
            "app" to appVersion, "name" to memberName, "at" to now(),
        )
    )

    // ── Reading ───────────────────────────────────────────────────────────────

    private fun pull(result: CycleResult) {
        val me = engine.peerId
        val segments = folder.segments().filterKeys { it != me }

        loadSnapshotIfNeeded(segments, result)

        for ((peer, list) in segments) {
            if (list.isEmpty()) continue
            val first = list.first().seg
            var cursor = engine.cursor(peer) ?: (first to 0L)
            if (cursor.first < first) {
                // Older journal files of this member are gone and no snapshot
                // covered them. Nothing more can be recovered; carry on from
                // what exists rather than stall forever.
                if (!(cursor.first == 0 && cursor.second == 0L)) result.gaps++
                cursor = first to 0L
            }
            for (segment in list) {
                if (segment.seg < cursor.first) continue
                var offset = if (segment.seg == cursor.first) cursor.second else 0L
                if (offset > segment.file.length()) offset = 0L // file was replaced; re-reading is harmless
                var moved = segment.seg != cursor.first
                while (true) {
                    val read = folder.read(segment, offset, READ_BATCH)
                    result.damagedLines += read.damaged
                    val ops = ArrayList<SyncOp>(read.lines.size)
                    for (json in read.lines) {
                        try {
                            SyncCodec.decode(json, peer)?.let(ops::add)
                        } catch (_: RuntimeException) {
                            result.damagedLines++
                        }
                    }
                    if (ops.isNotEmpty() || read.newOffset != offset || moved) {
                        val seg = segment.seg
                        val newOffset = read.newOffset
                        result.applied += engine.apply(ops) { engine.setCursor(peer, seg, newOffset) }
                        moved = false
                    }
                    offset = read.newOffset
                    if (!read.more) break
                }
            }
        }
    }

    /**
     * A snapshot is only loaded when the journals alone cannot bring this
     * member up to date: it has never read a member whose early journal files
     * are already gone, or it was away so long that files it still needed
     * were folded into a snapshot and deleted.
     */
    private fun loadSnapshotIfNeeded(
        segments: Map<String, List<SyncFolder.Segment>>,
        result: CycleResult,
    ) {
        val snapshots = folder.snapshots()
        if (snapshots.isEmpty()) return
        val me = engine.peerId
        val lastLoaded = engine.getMeta(META_LAST_SNAPSHOT)

        fun needs(vector: Map<String, Pair<Int, Long>>): Boolean {
            for ((peer, covered) in vector) {
                if (peer == me) continue
                val cursor = engine.cursor(peer) ?: (0 to 0L)
                if (cursor.first >= covered.first) continue
                val present = segments[peer].orEmpty().map { it.seg }.toSet()
                for (seg in maxOf(cursor.first, 1) until covered.first) {
                    if (seg !in present) return true
                }
            }
            return false
        }

        for (snapshot in snapshots) {
            val name = snapshot.file.name
            if (name == lastLoaded) return // this one and everything older is already merged
            val vector = vectorOf(snapshot) ?: continue
            if (!needs(vector)) return
            val buffer = ArrayList<SyncOp>(READ_BATCH)
            val applied = SyncEngine.ApplyResult()
            val complete = folder.readSnapshot(snapshot) { line ->
                try {
                    SyncCodec.decode(line, snapshot.peer)?.let(buffer::add)
                } catch (_: RuntimeException) {
                    result.damagedLines++
                }
                if (buffer.size >= READ_BATCH) { applied += engine.apply(ArrayList(buffer)); buffer.clear() }
            }
            if (buffer.isNotEmpty()) applied += engine.apply(buffer)
            // Whatever was read has been merged either way (merging is always
            // safe). The read positions only move if the file was complete.
            result.applied += applied
            if (!complete) continue
            engine.apply(emptyList()) {
                for ((peer, covered) in vector) {
                    if (peer == me) continue
                    val cursor = engine.cursor(peer)
                    if (cursor == null || cursor.first < covered.first ||
                        (cursor.first == covered.first && cursor.second < covered.second)
                    ) engine.setCursor(peer, covered.first, covered.second)
                }
                engine.setMeta(META_LAST_SNAPSHOT, name)
            }
            result.snapshotLoaded = true
            return
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun vectorOf(snapshot: SyncFolder.SnapshotFile): Map<String, Pair<Int, Long>>? {
        val name = snapshot.file.name
        if (vectorCache.containsKey(name)) return vectorCache[name]
        val parsed: Map<String, Pair<Int, Long>>? = try {
            val header = folder.readSnapshotHeader(snapshot)?.let { MiniJson.parse(it) as? Map<String, Any?> }
            (header?.get("vec") as? Map<String, Any?>)?.mapNotNull { (peer, v) ->
                val l = v as? List<Any?> ?: return@mapNotNull null
                val seg = (l.getOrNull(0) as? Number)?.toInt() ?: return@mapNotNull null
                val off = (l.getOrNull(1) as? Number)?.toLong() ?: return@mapNotNull null
                peer to (seg to off)
            }?.toMap()
        } catch (_: RuntimeException) {
            null
        }
        vectorCache[name] = parsed
        return parsed
    }

    // ── Tidying ───────────────────────────────────────────────────────────────

    @Throws(IOException::class)
    private fun maintain(result: CycleResult) {
        val me = engine.peerId
        val mySeg = engine.getMeta(META_MY_SEG)?.toIntOrNull() ?: return
        val mine = folder.segments()[me].orEmpty()

        // How much of my journal the newest snapshot already contains.
        val newest = folder.snapshots().firstOrNull()
        val coveredBelow = newest?.let { vectorOf(it)?.get(me)?.first } ?: 0

        // Covered journal files are dead weight, but each one is kept for a
        // grace period after it was CLOSED (stopped being appended to): a
        // member that syncs regularly then catches up from them with a few
        // hundred bytes of reading, where loading the snapshot means going
        // through the whole data set. After the grace period only members
        // that were away that long need the snapshot. Only the owner deletes
        // its own files (one writer per file), and never the one it is still
        // appending to.
        //
        // The grace period is measured per file. It used to be measured from
        // the NEWEST SNAPSHOT'S age — but a busy user writes a new snapshot
        // every couple of MB, so that snapshot was never old enough and no
        // journal file was ever deleted while the app was in daily use.
        if (coveredBelow > 0) {
            for (seg in folder.mySegmentNumbers()) {
                if (seg >= coveredBelow || seg >= mySeg) continue
                val closedAt = engine.getMeta(META_SEG_END + seg)?.toLongOrNull()
                if (closedAt == null) {
                    // Closed before this was tracked (an earlier version, or a
                    // snapshot hand-over): start its clock now.
                    engine.setMeta(META_SEG_END + seg, now().toString())
                    continue
                }
                if (now() - closedAt >= PRUNE_DELAY_MS && folder.deleteMySegment(seg)) {
                    engine.setMeta(META_SEG_END + seg, null)
                }
            }
        }

        val uncovered = mine.filter { it.seg >= coveredBelow }.sumOf { it.file.length() }
        val forced = engine.getMeta(META_NEED_SNAPSHOT) == "1"
        if (!forced && uncovered < SNAPSHOT_BYTES) return
        if (engine.outboxSize() > 0L) return

        // Start a new journal file first, so the snapshot can truthfully say
        // "everything of mine before this file is in here". The new file is
        // created right away (header only) so members reading my journal move
        // on to it and never mistake the hand-over for missing data.
        val nextSeg = mySeg + 1
        val vector = LinkedHashMap<String, Any?>()
        for ((peer, c) in engine.cursors()) vector[peer] = listOf(c.first.toLong(), c.second)
        vector[me] = listOf(nextSeg.toLong(), 0L)
        val createdAt = now()
        val header = MiniJson.write(
            linkedMapOf(
                "o" to "H", "fmt" to SyncCodec.FORMAT.toLong(), "by" to me, "at" to createdAt,
                "app" to appVersion, "vec" to vector,
            )
        )
        val written = folder.writeSnapshot(createdAt, header) { sink -> engine.exportSnapshot(sink) }
        engine.setMeta(META_SEG_END + mySeg, createdAt.toString())
        val length = folder.append(nextSeg, journalHeader(nextSeg), emptyList())
        engine.setMeta(META_MY_SEG, nextSeg.toString())
        engine.setMeta(META_MY_LEN, length.toString())
        engine.setMeta(META_LAST_SNAPSHOT, written.file.name)
        engine.setMeta(META_NEED_SNAPSHOT, null)
        result.snapshotWritten = true
    }

    // ── For the settings screen ───────────────────────────────────────────────

    /** Who is in the group, as far as the folder shows. Reads one short line per member. */
    @Suppress("UNCHECKED_CAST")
    fun members(): List<Member> {
        val out = ArrayList<Member>()
        for ((peer, list) in folder.segments()) {
            val newest = list.lastOrNull() ?: continue
            val name = try {
                val header = folder.read(newest, 0L, 1).lines.firstOrNull()
                ((header?.let { MiniJson.parse(it) } as? Map<String, Any?>)?.get("name") as? String)
            } catch (_: Exception) {
                null
            }
            out.add(
                Member(
                    peer = peer,
                    name = name?.takeIf { it.isNotBlank() } ?: peer,
                    lastWriteMs = list.maxOf { it.file.lastModified() },
                    bytes = list.sumOf { it.file.length() },
                    isMe = peer == engine.peerId,
                )
            )
        }
        return out.sortedByDescending { it.lastWriteMs }
    }

    fun folderBytes(): Long = folder.totalBytes()

    companion object {
        /** A journal file is continued in a new one past this size, so old parts can be deleted whole. */
        const val SEGMENT_BYTES = 512L * 1024L

        /** Once a member's journal files total this much, they are folded into a snapshot. */
        const val SNAPSHOT_BYTES = 2L * 1024L * 1024L

        /**
         * How long a closed, snapshot-covered journal file is kept so members
         * that sync regularly never need the snapshot. Anyone away longer
         * catches up from the snapshot instead.
         */
        const val PRUNE_DELAY_MS = 12L * 60L * 60L * 1000L

        private const val OUTBOX_BATCH = 1000
        private const val READ_BATCH = 1000

        private const val META_MY_SEG = "my_seg"
        private const val META_MY_LEN = "my_len"
        private const val META_NEED_SNAPSHOT = "need_snapshot"
        private const val META_SEG_END = "seg_end_"
        private const val META_LAST_SNAPSHOT = "last_snapshot"
    }
}
