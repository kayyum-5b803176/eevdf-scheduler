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
        /** Members whose state differed from ours at exactly the same point in the journals. */
        val drift: MutableList<String> = ArrayList(),
        var repairLoaded: Int = 0,
        var journalFilesRemoved: Int = 0,
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
            else result.announced = engine.announce(flush = {
                // Each batch goes to the card before the next is prepared.
                engine.capture()
                tryDrain(result)
                result.writeError == null
            })
        }

        // 4. Send again.
        engine.capture()
        tryDrain(result)

        // 5. Confirm what was read, and check we agree with the others.
        //    Neither is worth failing a sync over.
        if (result.writeError == null) {
            try { acknowledge(result) } catch (e: IOException) { /* retried next cycle */ }
        }

        // 6. Tidy.
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
            val after = folder.append(seg, journalHeader(seg), pack(batch.map { it.second }))
            // Only now — the bytes are on the card — is it safe to forget them.
            engine.ackOutbox(batch.last().first)
            engine.setMeta(META_MY_LEN, after.toString())
            lines += batch.size
            bytes += after - before
        }
        return lines to bytes
    }

    /**
     * Packs a large batch into compressed frames (see [SyncCodec.encodeFrame]);
     * small batches — the everyday case — are written as plain lines.
     */
    private fun pack(lines: List<String>): List<String> {
        if (lines.sumOf { it.length } < COMPRESS_MIN_CHARS) return lines
        val out = ArrayList<String>()
        var chunk = ArrayList<String>()
        var size = 0
        for (l in lines) {
            if (size + l.length > FRAME_CHARS && chunk.isNotEmpty()) {
                out.add(SyncCodec.encodeFrame(chunk)); chunk = ArrayList(); size = 0
            }
            chunk.add(l); size += l.length
        }
        if (chunk.isNotEmpty()) out.add(SyncCodec.encodeFrame(chunk))
        return out
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
                            if (SyncCodec.isFrame(json)) {
                                for (inner in SyncCodec.expandFrame(json)) SyncCodec.decode(inner, peer)?.let(ops::add)
                            } else {
                                SyncCodec.decode(json, peer)?.let(ops::add)
                            }
                        } catch (_: Exception) {
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

        repairIfRequested(result)
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
            val (complete, applied) = mergeSnapshot(snapshot, result)
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

    /** Reads a snapshot through the normal merge. Returns (file was complete, what changed). Read positions are NOT touched. */
    private fun mergeSnapshot(snapshot: SyncFolder.SnapshotFile, result: CycleResult): Pair<Boolean, SyncEngine.ApplyResult> {
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
        return complete to applied
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

    // ── Confirming what was read, and checking we agree ──────────────────────

    private class AckInfo(
        val peer: String,
        val at: Long,
        /** How far this member has read into each journal: (segment, byte offset). */
        val read: Map<String, Pair<Int, Long>>,
        /** Where this member's own journal ends. */
        val own: Pair<Int, Long>?,
        val fingerprint: String?,
        val schema: String?,
    )

    @Suppress("UNCHECKED_CAST")
    private fun parseAck(json: String): AckInfo? {
        return try {
            val m = MiniJson.parse(json) as? Map<String, Any?> ?: return null
            val peer = m["peer"] as? String ?: return null
            val at = (m["at"] as? Number)?.toLong() ?: return null
            fun pos(v: Any?): Pair<Int, Long>? {
                val l = v as? List<Any?> ?: return null
                return ((l.getOrNull(0) as? Number)?.toInt() ?: return null) to ((l.getOrNull(1) as? Number)?.toLong() ?: return null)
            }
            val read = (m["read"] as? Map<String, Any?>)?.mapNotNull { (p, v) -> pos(v)?.let { p to it } }?.toMap().orEmpty()
            AckInfo(peer, at, read, pos(m["own"]), m["fp"] as? String, m["sig"] as? String)
        } catch (_: Exception) {
            null
        }
    }

    /** The newest readable ack of every other member. */
    private fun loadAcks(): Map<String, AckInfo> {
        val out = HashMap<String, AckInfo>()
        for ((peer, files) in folder.ackFiles()) {
            if (peer == engine.peerId) continue
            for (f in files) { // newest first; fall back to an older one if the newest is unreadable
                val info = folder.readText(f)?.let(::parseAck)
                if (info != null && info.peer == peer) { out[peer] = info; break }
            }
        }
        return out
    }

    /**
     * Writes this member's ack (how far it has read in everyone's journal) and
     * compares its state with the other members' acks.
     *
     * WHEN AN ACK IS WRITTEN — each write costs a few file-system blocks, so:
     *   - at once when the read position moved into a later journal FILE of
     *     someone (this is what lets that owner delete the earlier file);
     *   - otherwise at most every [ACK_MIN_INTERVAL_MS] and only if something
     *     changed;
     *   - and at least every [ACK_HEARTBEAT_MS] so the others can tell this
     *     member is alive (a member silent for [ABSENT_MS] is no longer waited for).
     */
    @Throws(IOException::class)
    private fun acknowledge(result: CycleResult) {
        val me = engine.peerId
        val read = engine.cursors().toSortedMap()
        val mySeg = engine.getMeta(META_MY_SEG)?.toIntOrNull() ?: 0
        val myLen = engine.getMeta(META_MY_LEN)?.toLongOrNull() ?: 0L
        val clean = engine.isClean()
        val sig = engine.schemaId()
        val t = now()

        if (clean) selfCheck(loadAcks(), read, mySeg to myLen, sig, result)

        val segKey = MiniJson.write(read.mapValues { it.value.first.toLong() })
        val contentKey = MiniJson.write(
            listOf(read.mapValues { listOf(it.value.first.toLong(), it.value.second) }, mySeg.toLong(), myLen, clean)
        )
        val lastAt = engine.getMeta(META_ACK_AT)?.toLongOrNull() ?: 0L
        val due = segKey != engine.getMeta(META_ACK_SEG) ||
            (contentKey != engine.getMeta(META_ACK_KEY) && t - lastAt >= ACK_MIN_INTERVAL_MS) ||
            t - lastAt >= ACK_HEARTBEAT_MS
        if (!due) return

        val payload = LinkedHashMap<String, Any?>()
        payload["v"] = 1L
        payload["peer"] = me
        payload["name"] = memberName
        payload["at"] = t
        payload["read"] = read.mapValues { listOf(it.value.first.toLong(), it.value.second) }
        payload["own"] = listOf(mySeg.toLong(), myLen)
        payload["fp"] = if (clean) engine.fingerprint() else null
        payload["sig"] = sig
        folder.writeAck(t, MiniJson.write(payload))
        engine.setMeta(META_ACK_SEG, segKey)
        engine.setMeta(META_ACK_KEY, contentKey)
        engine.setMeta(META_ACK_AT, t.toString())
    }

    /**
     * Two members that have applied exactly the same changes must hold the
     * same data. "Exactly the same changes" is checked from the read
     * positions both published: if mine and theirs describe the same point in
     * every journal, the fingerprints must be equal. If they are not, one of
     * us has silently lost or gained something.
     *
     * The response is deliberately gentle and can never remove data: publish a
     * fresh snapshot of our own state and, for [REPAIR_WINDOW_MS], also merge
     * the other members' newest snapshots (the normal newest-wins merge).
     */
    private fun selfCheck(
        acks: Map<String, AckInfo>,
        read: Map<String, Pair<Int, Long>>,
        own: Pair<Int, Long>,
        sig: String,
        result: CycleResult,
    ) {
        val me = engine.peerId
        // A member that has never written has position (0,0) in its own note and
        // no entry at all in everyone else's: both mean "nothing there".
        fun normal(m: Map<String, Pair<Int, Long>>) = m.filterValues { it.first != 0 || it.second != 0L }
        val mine = normal(HashMap<String, Pair<Int, Long>>(read).also { it[me] = own })
        var myFingerprint: String? = null
        for ((peer, ack) in acks) {
            val theirFingerprint = ack.fingerprint ?: continue
            val theirOwn = ack.own ?: continue
            if (ack.schema != sig) continue // different table layouts are not comparable
            val theirs = normal(HashMap<String, Pair<Int, Long>>(ack.read).also { it[peer] = theirOwn })
            if (theirs != mine) continue      // not at the same point; nothing to conclude
            val fp = myFingerprint ?: engine.fingerprint().also { myFingerprint = it }
            if (fp == theirFingerprint) continue
            val t = now()
            val last = engine.getMeta(META_DRIFT_AT)?.toLongOrNull() ?: 0L
            if (t - last < DRIFT_COOLDOWN_MS) continue
            engine.setMeta(META_DRIFT_AT, t.toString())
            engine.setMeta(META_REPAIR_UNTIL, (t + REPAIR_WINDOW_MS).toString())
            engine.setMeta(META_NEED_SNAPSHOT, "1")
            result.drift.add(ack.peer)
        }
    }

    /** While a repair is open, merge the newest snapshot of every other member that we have not merged yet. */
    private fun repairIfRequested(result: CycleResult) {
        val until = engine.getMeta(META_REPAIR_UNTIL)?.toLongOrNull() ?: return
        if (now() > until) { engine.setMeta(META_REPAIR_UNTIL, null); return }
        val me = engine.peerId
        val newest = folder.snapshots().filter { it.peer != me }.groupBy { it.peer }.mapValues { it.value.first() }
        for ((peer, snapshot) in newest) {
            val key = META_REPAIR_LOADED + peer
            if (engine.getMeta(key) == snapshot.file.name) continue
            val (complete, applied) = mergeSnapshot(snapshot, result)
            result.applied += applied
            if (complete) { engine.setMeta(key, snapshot.file.name); result.repairLoaded++ }
        }
    }

    // ── Tidying ───────────────────────────────────────────────────────────────

    /**
     * Deletes journal files nobody needs and writes a snapshot only when it
     * pays for itself.
     *
     * A journal file of mine may go when BOTH hold:
     *  (a) a snapshot covers it — so a member that joins later, or was away
     *      too long, can still catch up (from the snapshot); and
     *  (b) every other member has confirmed reading past it (its ack names a
     *      later file of mine) — or has been silent for [ABSENT_MS] and is no
     *      longer waited for. A member that has never written an ack (an
     *      older build) is given [LEGACY_GRACE_MS] after the file was closed.
     *
     * A snapshot is written when the journal written since the last one has
     * grown to [SNAPSHOT_AMORTISE] times that snapshot's size (and at least
     * [SNAPSHOT_MIN_BYTES]). Its cost S is then at most 1/[SNAPSHOT_AMORTISE]
     * of the journal bytes written in the same period — the scheme as a whole
     * writes at most (1 + 1/[SNAPSHOT_AMORTISE]) times the real changes, however
     * large the database. A fixed small trigger instead made a large database
     * rewrite its whole snapshot every few MB of changes.
     */
    @Throws(IOException::class)
    private fun maintain(result: CycleResult) {
        val me = engine.peerId
        val mySeg = engine.getMeta(META_MY_SEG)?.toIntOrNull() ?: return
        val newest = folder.snapshots().firstOrNull()
        val coveredBelow = newest?.let { vectorOf(it)?.get(me)?.first } ?: 0
        val t = now()

        if (coveredBelow > 0) {
            val acks = loadAcks()
            val others = (folder.segments().keys + acks.keys) - me
            for (seg in folder.mySegmentNumbers()) {
                if (seg >= coveredBelow || seg >= mySeg) continue
                val closedAt = engine.getMeta(META_SEG_END + seg)?.toLongOrNull()
                    ?: t.also { engine.setMeta(META_SEG_END + seg, it.toString()) }
                val everyoneDone = others.all { m ->
                    val ack = acks[m]
                    when {
                        ack == null -> t - closedAt >= LEGACY_GRACE_MS
                        (ack.read[me]?.first ?: 0) > seg -> true
                        else -> t - ack.at >= ABSENT_MS
                    }
                }
                if (everyoneDone && folder.deleteMySegment(seg)) {
                    engine.setMeta(META_SEG_END + seg, null)
                    result.journalFilesRemoved++
                }
            }
        }

        val mine = folder.segments()[me].orEmpty()
        val uncovered = mine.filter { it.seg >= coveredBelow }.sumOf { it.file.length() }
        val lastSize = engine.getMeta(META_SNAPSHOT_SIZE)?.toLongOrNull() ?: newest?.file?.length() ?: 0L
        val needed = maxOf(SNAPSHOT_MIN_BYTES, lastSize * SNAPSHOT_AMORTISE)
        val forced = engine.getMeta(META_NEED_SNAPSHOT) == "1"
        if (!forced && uncovered < needed) return
        if (engine.outboxSize() > 0L) return

        // Start a new journal file first, so the snapshot can truthfully say
        // "everything of mine before this file is in here". The new file is
        // created right away (header only) so members reading my journal move
        // on to it and never mistake the hand-over for missing data.
        val nextSeg = mySeg + 1
        val vector = LinkedHashMap<String, Any?>()
        for ((peer, c) in engine.cursors()) vector[peer] = listOf(c.first.toLong(), c.second)
        vector[me] = listOf(nextSeg.toLong(), 0L)
        val header = MiniJson.write(
            linkedMapOf(
                "o" to "H", "fmt" to SyncCodec.FORMAT.toLong(), "by" to me, "at" to t,
                "app" to appVersion, "vec" to vector,
            )
        )
        val written = folder.writeSnapshot(t, header) { sink -> engine.exportSnapshot(sink) }
        engine.setMeta(META_SEG_END + mySeg, t.toString())
        val length = folder.append(nextSeg, journalHeader(nextSeg), emptyList())
        engine.setMeta(META_MY_SEG, nextSeg.toString())
        engine.setMeta(META_MY_LEN, length.toString())
        engine.setMeta(META_LAST_SNAPSHOT, written.file.name)
        engine.setMeta(META_SNAPSHOT_SIZE, written.file.length().toString())
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
        /**
         * A journal file is continued in a new one past this size. Small on
         * purpose: it only sets how finely old data can be deleted (at most one
         * file of overshoot), and many small files cost nothing next to one
         * large rewrite.
         */
        const val SEGMENT_BYTES = 128L * 1024L

        /** No snapshot is written before this much journal exists. */
        const val SNAPSHOT_MIN_BYTES = 2L * 1024L * 1024L

        /** A snapshot costs at most 1/this of the journal written since the previous one. */
        const val SNAPSHOT_AMORTISE = 4L

        /** A member that has not shown a sign of life for this long is no longer waited for. */
        const val ABSENT_MS = 3L * 24L * 60L * 60L * 1000L

        /** An ack is refreshed at least this often, so a live member is never mistaken for an absent one. */
        const val ACK_HEARTBEAT_MS = 12L * 60L * 60L * 1000L

        /** Between two acks that differ only in read offsets. */
        const val ACK_MIN_INTERVAL_MS = 6L * 60L * 60L * 1000L

        /** How long a closed journal file is kept for a member that never writes acks (an older build). */
        const val LEGACY_GRACE_MS = 12L * 60L * 60L * 1000L

        const val DRIFT_COOLDOWN_MS = 60L * 60L * 1000L
        const val REPAIR_WINDOW_MS = 24L * 60L * 60L * 1000L

        /** Batches smaller than this (the everyday case) are written as plain lines. */
        private const val COMPRESS_MIN_CHARS = 8 * 1024

        /** Raw size of the lines packed into one compressed frame. */
        private const val FRAME_CHARS = 512 * 1024

        private const val OUTBOX_BATCH = 1000
        private const val READ_BATCH = 1000

        private const val META_MY_SEG = "my_seg"
        private const val META_MY_LEN = "my_len"
        private const val META_NEED_SNAPSHOT = "need_snapshot"
        private const val META_SEG_END = "seg_end_"
        private const val META_LAST_SNAPSHOT = "last_snapshot"
        private const val META_SNAPSHOT_SIZE = "snapshot_size"
        private const val META_ACK_SEG = "ack_seg"
        private const val META_ACK_KEY = "ack_key"
        private const val META_ACK_AT = "ack_at"
        private const val META_DRIFT_AT = "drift_at"
        private const val META_REPAIR_UNTIL = "repair_until"
        private const val META_REPAIR_LOADED = "repair_loaded_"
    }
}
