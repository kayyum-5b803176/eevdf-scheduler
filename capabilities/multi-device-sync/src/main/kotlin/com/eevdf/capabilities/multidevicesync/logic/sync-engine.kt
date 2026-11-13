package com.eevdf.capabilities.multidevicesync.logic

/**
 * The merge engine: turns "rows changed here" into journal lines, and journal
 * lines from other members into rows here — on the live database, inside
 * ordinary transactions, without ever closing, copying or replacing the
 * database file.
 *
 * ── The model, in five sentences ─────────────────────────────────────────────
 *
 *  1. Every member keeps its own complete database. Nothing is shared except
 *     small text lines describing changes.
 *  2. Each change carries a [Stamp] (when + who). When two members changed
 *     the same thing, the later stamp wins — per column for `tasks`, per row
 *     for everything else. Every member applies the same rule to the same
 *     lines, so they all arrive at the same data in whatever order the lines
 *     reach them.
 *  3. A member can only ever *add* lines about rows it actually touched. A
 *     brand-new, empty member has touched nothing, so it has nothing to say
 *     and cannot erase anyone — which was the failure the old whole-file sync
 *     had.
 *  4. Deleting a row leaves a tombstone, so the delete reaches members that
 *     were away; but if someone else changed that row *after* the delete, the
 *     row comes back rather than their work vanishing.
 *  5. Data this build has no table or column for is parked verbatim and
 *     applied the day a newer build that does have it is installed.
 *
 * ── What is deliberately NOT here ────────────────────────────────────────────
 *
 * No files, no Android, no Room, no threads, no knowledge of what a "task"
 * is. Files are [SyncFolder]; orchestration is [SyncSession]; Android is
 * `SyncCoordinator`. This class only needs a [SyncDb].
 *
 * Not thread-safe by itself: the caller serialises calls (the coordinator
 * holds one mutex around every sync, restore and snapshot).
 */
internal class SyncEngine(
    private val db: SyncDb,
    /** This member's id. Stable for the life of this app install + Android user. */
    val peerId: String,
    private val now: () -> Long,
) {
    private var tables: Map<String, TableInfo> = emptyMap()

    /** Counters for one [apply] call, reported to the UI and the event log. */
    data class ApplyResult(
        var applied: Int = 0,
        var ignoredOlder: Int = 0,
        var parked: Int = 0,
        var deletesKept: Int = 0,
        val tables: MutableSet<String> = sortedSetOf(),
    ) {
        operator fun plusAssign(o: ApplyResult) {
            applied += o.applied; ignoredOlder += o.ignoredOlder
            parked += o.parked; deletesKept += o.deletesKept
            tables.addAll(o.tables)
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Call once per app start, before anything else. Creates the bookkeeping
     * tables, and — if this database has ever joined a sync group — repairs
     * triggers (a Room migration may have rebuilt a table) and replays parked
     * data that a schema change may now have a home for.
     *
     * Safe when sync is switched off: everything here is local and cheap.
     */
    fun init() {
        db.transaction {
            SyncSchema.ensureTables(db)
            // A database file that came from a different install (an old
            // whole-file backup restored by an older build, a device-to-device
            // transfer) carries that install's bookkeeping. Using it would
            // make two members claim the same identity. Start clean instead;
            // the data rows themselves are untouched.
            val owner = SyncSchema.getMeta(db, META_OWNER)
            if (owner != null && owner != peerId) {
                SyncSchema.dropAll(db)
                SyncSchema.ensureTables(db)
            }
            SyncSchema.setMeta(db, META_OWNER, peerId)
            // A crash can never leave this set (it is written and cleared
            // inside one transaction), but a stuck flag would silently stop
            // all change capture, so it is cheap insurance to clear it.
            SyncSchema.setMeta(db, META_APPLYING, "0")
            refreshTables()
            if (isBootstrapped) {
                for (t in tables.values) SyncSchema.ensureTriggers(db, t)
                val signature = schemaSignature()
                if (SyncSchema.getMeta(db, META_SCHEMA) != signature) {
                    indexUnknownRows()
                    replayParked()
                    SyncSchema.setMeta(db, META_SCHEMA, signature)
                }
            }
        }
    }

    val isBootstrapped: Boolean get() = SyncSchema.getMeta(db, META_BOOTSTRAPPED) == "1"

    /** True once this member has told the group about the rows only it had. See [announce]. */
    val isAnnounced: Boolean get() = SyncSchema.getMeta(db, META_ANNOUNCED) == "1"

    fun getMeta(key: String): String? = SyncSchema.getMeta(db, key)
    fun setMeta(key: String, value: String?) = SyncSchema.setMeta(db, key, value)

    private fun refreshTables() {
        tables = SyncSchema.discoverTables(db).associateBy { it.name }
    }

    private fun schemaSignature(): String =
        tables.values.joinToString(";") { t -> t.name + "(" + t.columnNames.joinToString(",") + ")" }

    /**
     * First-time setup, run when sync is first switched on for this database.
     *
     * Records every existing row as [Stamp.BASELINE] — "here, but not yet
     * vouched for" — and installs the triggers. Nothing is sent anywhere by
     * this: what happens to baseline rows is decided after the first pull
     * (see [announce] and [discardUnannounced]).
     */
    fun bootstrap() {
        if (isBootstrapped) return
        db.transaction {
            refreshTables()
            for (t in tables.values) SyncSchema.ensureTriggers(db, t)
            indexUnknownRows()
            SyncSchema.setMeta(db, META_SCHEMA, schemaSignature())
            SyncSchema.setMeta(db, META_BOOTSTRAPPED, "1")
            SyncSchema.setMeta(db, META_ANNOUNCED, "0")
        }
    }

    /**
     * Gives a baseline entry to every row that has none. At bootstrap that is
     * every row; later it is only the rows of a table a newer build just
     * added (and anything written while triggers were briefly missing after
     * a Room migration rebuilt a table).
     */
    private fun indexUnknownRows() {
        for (t in tables.values) {
            forEachLocalRow(t) { row ->
                if (t.localIds) {
                    val lid = (row[t.pk[0]] as? Number)?.toLong() ?: return@forEachLocalRow
                    if (loadMetaByLid(t, lid) != null) return@forEachLocalRow
                    val key = freeContentKey(t, row)
                    val existing = loadMeta(t, key)
                    val meta = existing ?: RowMeta(t.name, key, null, Stamp.BASELINE, false, null, null)
                    meta.lid = lid; meta.del = false
                    meta.vh = SyncCodec.rowHash(t.dataColumns, row)
                    saveMeta(meta)
                } else {
                    val key = naturalKey(t, row) ?: return@forEachLocalRow
                    if (loadMeta(t, key) != null) return@forEachLocalRow
                    val meta = RowMeta(t.name, key, null, Stamp.BASELINE, false, null, null)
                    stampAll(t, meta, row, Stamp.BASELINE)
                    saveMeta(meta)
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Local changes → outbox
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Turns the trigger-written "this row changed" marks into stamped journal
     * lines in the outbox. Returns how many lines were produced.
     *
     * Only what actually differs from what the group already has is written:
     * a row saved again with identical values produces nothing, and for a
     * task only the columns whose value changed are sent. This is the main
     * reason the SD card sees so little writing.
     */
    fun capture(): Int = db.transaction {
        if (!isBootstrapped) return@transaction 0
        var produced = 0
        while (true) {
            val batch = db.queryRows(
                "SELECT tbl, k1, k2, k3, k4, ts FROM _sync_dirty ORDER BY ts LIMIT $BATCH"
            )
            if (batch.isEmpty()) break
            for (d in batch) {
                val keys = listOf(d["k1"], d["k2"], d["k3"], d["k4"])
                val t = tables[d["tbl"] as String]
                if (t != null) produced += captureRow(t, keys.take(t.pk.size), (d["ts"] as Number).toLong())
                db.exec(
                    "DELETE FROM _sync_dirty WHERE tbl = ? AND k1 = ? AND k2 = ? AND k3 = ? AND k4 = ?",
                    listOf(d["tbl"], keys[0], keys[1], keys[2], keys[3])
                )
            }
        }
        produced
    }

    private fun captureRow(t: TableInfo, pkValues: List<Any?>, dirtyTs: Long): Int {
        val row = loadLocalRow(t, pkValues)
        val meta: RowMeta? = if (t.localIds) {
            val lid = (pkValues[0] as? Number)?.toLong() ?: return 0
            loadMetaByLid(t, lid)
        } else {
            loadMeta(t, MiniJson.write(pkValues))
        }

        // ── The row is gone: a local delete ──────────────────────────────────
        if (row == null) {
            if (meta == null || meta.del) return 0
            if (meta.newest() == Stamp.BASELINE) {
                // Never shared with anyone, so there is nobody to tell.
                db.exec("DELETE FROM _sync_row WHERE tbl = ? AND gk = ?", listOf(t.name, meta.key))
                return 0
            }
            val stamp = Stamp(maxOf(dirtyTs, meta.newest().ts + 1), peerId)
            meta.markDeleted(stamp)
            saveMeta(meta)
            enqueue(SyncCodec.encodeLocalDelete(t.name, meta.key, stamp.ts))
            return 1
        }

        // ── A row the group has not heard of (or had deleted): send it whole ─
        if (meta == null || meta.del) {
            val key = meta?.key ?: (if (t.localIds) freeContentKey(t, row) else naturalKey(t, row) ?: return 0)
            // A content key may resolve to a tombstone: the same record was
            // deleted earlier and has now been created again.
            val target = meta ?: loadMeta(t, key) ?: RowMeta(t.name, key, null, Stamp.BASELINE, false, null, null)
            val stamp = Stamp(maxOf(dirtyTs, target.newest().ts + 1), peerId)
            emitPut(t, target, row, stamp)
            return 1
        }

        // ── A known row: send only what differs ──────────────────────────────
        if (t.perColumn) {
            val cols = meta.cols ?: HashMap<String, ColClock>().also { meta.cols = it }
            val changed = LinkedHashMap<String, Any?>()
            var ts = dirtyTs
            for (c in t.dataColumns) {
                val h = SyncCodec.valueHash(row[c])
                val clock = cols[c]
                if (clock != null && clock.hash == h) continue
                changed[c] = row[c]
                if (clock != null) ts = maxOf(ts, clock.stamp.ts + 1)
            }
            if (changed.isEmpty()) return 0
            val stamp = Stamp(ts, peerId)
            for (c in changed.keys) cols[c] = ColClock(stamp, SyncCodec.valueHash(row[c]))
            saveMeta(meta)
            enqueue(SyncCodec.encodeLocalCells(t.name, meta.key, false, stamp.ts, changed))
            return 1
        }
        val h = SyncCodec.rowHash(t.dataColumns, row)
        if (meta.vh == h) return 0
        emitPut(t, meta, row, Stamp(maxOf(dirtyTs, meta.stamp.ts + 1), peerId))
        return 1
    }

    /** Sends [row] whole under [stamp] and records that this member now vouches for all of it. */
    private fun emitPut(t: TableInfo, meta: RowMeta, row: Map<String, Any?>, stamp: Stamp) {
        meta.stamp = stamp
        meta.del = false
        if (t.localIds) meta.lid = (row[t.pk[0]] as? Number)?.toLong()
        stampAll(t, meta, row, stamp)
        saveMeta(meta)
        val values = LinkedHashMap<String, Any?>()
        for (c in t.dataColumns) values[c] = row[c]
        enqueue(SyncCodec.encodeLocalCells(t.name, meta.key, true, stamp.ts, values))
    }

    private fun stampAll(t: TableInfo, meta: RowMeta, row: Map<String, Any?>, stamp: Stamp) {
        if (t.perColumn) {
            val cols = HashMap<String, ColClock>(t.dataColumns.size * 2)
            for (c in t.dataColumns) cols[c] = ColClock(stamp, SyncCodec.valueHash(row[c]))
            meta.cols = cols
            meta.vh = null
        } else {
            meta.cols = null
            meta.vh = SyncCodec.rowHash(t.dataColumns, row)
        }
    }

    /**
     * Tells the group about every row only this member has (still at
     * baseline after the first pull). Called once, after a member's first
     * complete read of the shared folder. This is what "a new user can join
     * without losing what they already had" means in code.
     *
     * Rows the group already had are not re-sent: the pull replaced their
     * baseline with the group's stamp, so they no longer match here.
     */
    fun announce(): Int {
        var produced = 0
        while (true) {
            val n = db.transaction {
                val batch = db.queryRows(
                    "SELECT tbl, gk FROM _sync_row WHERE ts = 0 AND del = 0 LIMIT $BATCH"
                )
                for (m in batch) {
                    val t = tables[m["tbl"] as String]
                    val meta = t?.let { loadMeta(it, m["gk"] as String) }
                    val row = if (t != null && meta != null) loadLocalRowFor(t, meta) else null
                    if (t == null || meta == null || row == null) {
                        db.exec("DELETE FROM _sync_row WHERE tbl = ? AND gk = ?", listOf(m["tbl"], m["gk"]))
                    } else {
                        emitPut(t, meta, row, Stamp(maxOf(now(), 1L), peerId))
                        produced++
                    }
                }
                batch.size
            }
            if (n == 0) break
        }
        SyncSchema.setMeta(db, META_ANNOUNCED, "1")
        return produced
    }

    /**
     * The other choice at join time: instead of adding this member's
     * leftover rows to the group, drop them so this member ends up with
     * exactly the group's data. Used when a user's copy is known to be stale
     * (for example it still contains tasks the others deleted long ago).
     * Returns how many rows were removed.
     */
    fun discardUnannounced(): Int = db.transaction {
        var removed = 0
        withTriggersSilenced {
            while (true) {
                val batch = db.queryRows(
                    "SELECT tbl, gk FROM _sync_row WHERE ts = 0 AND del = 0 LIMIT $BATCH"
                )
                if (batch.isEmpty()) break
                for (m in batch) {
                    val t = tables[m["tbl"] as String]
                    val meta = t?.let { loadMeta(it, m["gk"] as String) }
                    if (t != null && meta != null && deleteLocalRow(t, meta)) removed++
                    db.exec("DELETE FROM _sync_row WHERE tbl = ? AND gk = ?", listOf(m["tbl"], m["gk"]))
                }
            }
        }
        SyncSchema.setMeta(db, META_ANNOUNCED, "1")
        removed
    }

    /** How many rows are still at baseline, i.e. what [announce] would send. */
    fun countUnannounced(): Long =
        db.queryLong("SELECT COUNT(*) FROM _sync_row WHERE ts = 0 AND del = 0") ?: 0L

    // ── Outbox ────────────────────────────────────────────────────────────────

    private fun enqueue(line: String) {
        db.exec("INSERT INTO _sync_outbox(line) VALUES(?)", listOf(line))
    }

    /** Up to [limit] pending lines, oldest first, as (sequence, json). */
    fun peekOutbox(limit: Int): List<Pair<Long, String>> =
        db.query("SELECT seq, line FROM _sync_outbox ORDER BY seq LIMIT $limit") { c ->
            val out = ArrayList<Pair<Long, String>>()
            while (c.next()) out.add((c.value(0) as Number).toLong() to c.value(1).toString())
            out
        }

    /** Forgets every outbox line up to and including [seq] — call only after they are safely on disk. */
    fun ackOutbox(seq: Long) = db.exec("DELETE FROM _sync_outbox WHERE seq <= ?", listOf(seq))

    fun outboxSize(): Long = db.queryLong("SELECT COUNT(*) FROM _sync_outbox") ?: 0L

    fun hasPendingLocalChanges(): Boolean =
        (db.queryLong("SELECT EXISTS(SELECT 1 FROM _sync_dirty)") ?: 0L) != 0L || outboxSize() > 0L

    // ═════════════════════════════════════════════════════════════════════════
    // Remote changes → local rows
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Merges [ops] into the live database in one transaction, then runs
     * [alsoInTransaction] inside the same transaction (the caller advances
     * its read position there, so "applied" and "remembered as applied" can
     * never disagree).
     *
     * Local changes not yet stamped are stamped *first*: otherwise an
     * incoming older value could overwrite a newer local edit that simply had
     * not been looked at yet.
     */
    fun apply(ops: List<SyncOp>, alsoInTransaction: () -> Unit = {}): ApplyResult = db.transaction {
        val result = ApplyResult()
        if (ops.isNotEmpty()) {
            capture()
            withTriggersSilenced {
                for (op in ops) {
                    when (op) {
                        is SyncOp.Cells -> applyCells(op, result)
                        is SyncOp.Delete -> applyDelete(op, result)
                    }
                }
            }
        }
        alsoInTransaction()
        result
    }

    private inline fun <T> withTriggersSilenced(block: () -> T): T {
        SyncSchema.setMeta(db, META_APPLYING, "1")
        try {
            return block()
        } finally {
            SyncSchema.setMeta(db, META_APPLYING, "0")
        }
    }

    private fun applyCells(op: SyncOp.Cells, result: ApplyResult) {
        val t = tables[op.table]
        if (t == null) {
            park(op.table, op.key, op.cells, if (op.full) Cell("1", op.rowStamp) else null)
            result.parked++
            return
        }
        // Columns this build does not have are kept for a build that does.
        val unknown = op.cells.filterKeys { t.column(it) == null }
        if (unknown.isNotEmpty()) { park(t.name, op.key, unknown, null); result.parked++ }
        val known = op.cells.filterKeys { t.column(it) != null && !(t.localIds && it == t.pk[0]) }

        val meta = loadMeta(t, op.key)
        if (meta == null || meta.del) {
            val mayCreate = op.full && (meta == null || op.rowStamp > meta.stamp)
            if (!mayCreate) {
                if (meta == null && known.isNotEmpty()) {
                    // A change to a row whose creation has not been read yet
                    // (it is in another member's journal). Hold on to it; it
                    // is merged in the moment the row arrives.
                    park(t.name, op.key, known, null)
                    result.parked++
                } else {
                    result.ignoredOlder++
                }
                return
            }
            insertFromRemote(t, op, known, meta)
            result.applied++
            result.tables.add(t.name)
            return
        }

        if (t.perColumn) {
            val cols = meta.cols ?: HashMap<String, ColClock>().also { meta.cols = it }
            val winners = LinkedHashMap<String, Any?>()
            for ((c, cell) in known) {
                val current = cols[c]?.stamp ?: Stamp.BASELINE
                if (cell.stamp > current) {
                    winners[c] = cell.value
                    cols[c] = ColClock(cell.stamp, SyncCodec.valueHash(cell.value))
                }
            }
            if (op.full && op.rowStamp > meta.stamp) meta.stamp = op.rowStamp
            if (winners.isEmpty()) { result.ignoredOlder++; saveMeta(meta); return }
            updateLocalRow(t, meta, winners)
            saveMeta(meta)
            result.applied++
            result.tables.add(t.name)
        } else {
            if (op.rowStamp <= meta.stamp) { result.ignoredOlder++; return }
            if (known.isNotEmpty()) updateLocalRow(t, meta, known.mapValues { it.value.value })
            meta.stamp = op.rowStamp
            val row = loadLocalRowFor(t, meta)
            if (row != null) meta.vh = SyncCodec.rowHash(t.dataColumns, row)
            saveMeta(meta)
            result.applied++
            result.tables.add(t.name)
        }
    }

    private fun insertFromRemote(t: TableInfo, op: SyncOp.Cells, known: Map<String, Cell>, existing: RowMeta?) {
        val names = ArrayList<String>()
        val values = ArrayList<Any?>()
        val pkFromKey: List<Any?>? = if (t.localIds) null else parseNaturalKey(op.key)
        for (c in t.dataColumns) {
            val pkIndex = t.pk.indexOf(c)
            when {
                // The key is the authority for key columns, whatever the line says.
                pkFromKey != null && pkIndex >= 0 && pkIndex < pkFromKey.size -> {
                    names.add(c); values.add(pkFromKey[pkIndex])
                }
                known.containsKey(c) -> { names.add(c); values.add(known.getValue(c).value) }
                // Sender's build has no such column. Let SQLite's own default
                // apply if there is one; otherwise use an empty value of the
                // right kind so the NOT NULL constraint is satisfied.
                t.column(c)?.defaultSql != null -> Unit
                else -> { names.add(c); values.add(t.column(c)?.zeroValue()) }
            }
        }
        val sql = "INSERT OR REPLACE INTO ${qi(t.name)}(" + names.joinToString(", ") { qi(it) } +
            ") VALUES(" + names.joinToString(", ") { "?" } + ")"
        db.exec(sql, values)

        val meta = existing ?: RowMeta(t.name, op.key, null, op.rowStamp, false, null, null)
        meta.stamp = op.rowStamp
        meta.del = false
        if (t.localIds) meta.lid = db.queryLong("SELECT last_insert_rowid()")
        val row = loadLocalRowFor(t, meta) ?: return
        if (t.perColumn) {
            val cols = HashMap<String, ColClock>(t.dataColumns.size * 2)
            for (c in t.dataColumns) {
                val stamp = known[c]?.stamp ?: Stamp.BASELINE
                cols[c] = ColClock(stamp, SyncCodec.valueHash(row[c]))
            }
            meta.cols = cols; meta.vh = null
        } else {
            meta.cols = null; meta.vh = SyncCodec.rowHash(t.dataColumns, row)
        }
        saveMeta(meta)
        mergeParkedInto(t, meta)
    }

    private fun applyDelete(op: SyncOp.Delete, result: ApplyResult) {
        val t = tables[op.table]
        if (t == null) {
            park(op.table, op.key, emptyMap(), Cell("0", op.stamp))
            result.parked++
            return
        }
        val meta = loadMeta(t, op.key)
        if (meta == null) {
            // Never seen here. Remember the delete so the row is not created
            // later by an older line still on its way.
            saveMeta(RowMeta(t.name, op.key, null, op.stamp, true, null, null))
            return
        }
        if (meta.del) {
            if (op.stamp > meta.stamp) { meta.stamp = op.stamp; saveMeta(meta) }
            result.ignoredOlder++
            return
        }
        val newest = meta.newest()
        if (op.stamp > newest) {
            deleteLocalRow(t, meta)
            meta.markDeleted(op.stamp)
            saveMeta(meta)
            result.applied++
            result.tables.add(t.name)
            return
        }
        // Something about this row was changed *after* the other member
        // deleted it. Losing that work silently is worse than a deleted row
        // reappearing, so the row stays. If the later change was made here,
        // say so to the group by re-sending the whole row; if it was made by
        // a third member, that member will do the same when it reads the
        // delete.
        result.deletesKept++
        if (meta.authoredAfter(op.stamp, peerId)) {
            val row = loadLocalRowFor(t, meta)
            if (row != null) emitPut(t, meta, row, Stamp(maxOf(now(), newest.ts + 1), peerId))
        }
    }

    // ── Parked data (tables/columns this build lacks, and early changes) ─────

    private fun park(table: String, key: String, cells: Map<String, Cell>, rowMark: Cell?) {
        fun put(col: String, cell: Cell) {
            val current = db.query(
                "SELECT ts, peer FROM _sync_foreign WHERE tbl = ? AND gk = ? AND col = ?", listOf(table, key, col)
            ) { c -> if (c.next()) Stamp((c.value(0) as Number).toLong(), c.value(1).toString()) else null }
            if (current != null && cell.stamp <= current) return
            db.exec(
                "INSERT OR REPLACE INTO _sync_foreign(tbl, gk, col, ts, peer, val) VALUES(?, ?, ?, ?, ?, ?)",
                listOf(table, key, col, cell.stamp.ts, cell.stamp.peer, MiniJson.write(cell.value))
            )
        }
        for ((c, cell) in cells) put(c, cell)
        if (rowMark != null) put(SyncCodec.ROW_MARK, rowMark)
    }

    private fun loadParked(table: String, key: String): Map<String, Cell> {
        val out = LinkedHashMap<String, Cell>()
        db.query(
            "SELECT col, ts, peer, val FROM _sync_foreign WHERE tbl = ? AND gk = ?", listOf(table, key)
        ) { c ->
            while (c.next()) {
                val value = MiniJson.toCell(MiniJson.parse(c.value(3)?.toString() ?: "null"))
                out[c.value(0).toString()] = Cell(value, Stamp((c.value(1) as Number).toLong(), c.value(2).toString()))
            }
        }
        return out
    }

    /** After a row is created from a remote line: fold in any changes to it that arrived first. */
    private fun mergeParkedInto(t: TableInfo, meta: RowMeta) {
        if ((db.queryLong("SELECT EXISTS(SELECT 1 FROM _sync_foreign WHERE tbl = ? AND gk = ?)",
                listOf(t.name, meta.key)) ?: 0L) == 0L) return
        val parked = loadParked(t.name, meta.key).filterKeys { it != SyncCodec.ROW_MARK && t.column(it) != null }
        if (parked.isEmpty()) return
        val winners = LinkedHashMap<String, Any?>()
        if (t.perColumn) {
            val cols = meta.cols ?: HashMap<String, ColClock>().also { meta.cols = it }
            for ((c, cell) in parked) {
                if (cell.stamp > (cols[c]?.stamp ?: Stamp.BASELINE)) {
                    winners[c] = cell.value
                    cols[c] = ColClock(cell.stamp, SyncCodec.valueHash(cell.value))
                }
            }
            if (winners.isNotEmpty()) updateLocalRow(t, meta, winners)
        } else {
            val newest = parked.values.maxOf { it.stamp }
            if (newest > meta.stamp) {
                updateLocalRow(t, meta, parked.mapValues { it.value.value })
                meta.stamp = newest
                loadLocalRowFor(t, meta)?.let { meta.vh = SyncCodec.rowHash(t.dataColumns, it) }
            }
        }
        saveMeta(meta)
        for (c in parked.keys) {
            db.exec("DELETE FROM _sync_foreign WHERE tbl = ? AND gk = ? AND col = ?", listOf(t.name, meta.key, c))
        }
    }

    /**
     * Runs after the schema changed (an app update added a table or column):
     * everything that was parked because it had nowhere to go is offered to
     * the merge again. Whatever still has no home stays parked.
     */
    private fun replayParked() {
        val keys = db.queryRows("SELECT DISTINCT tbl, gk FROM _sync_foreign")
        if (keys.isEmpty()) return
        withTriggersSilenced {
            val scratch = ApplyResult()
            for (k in keys) {
                val table = k["tbl"] as String
                val key = k["gk"] as String
                val t = tables[table] ?: continue
                val parked = loadParked(table, key)
                val mark = parked[SyncCodec.ROW_MARK]
                val usable = parked.filterKeys { it != SyncCodec.ROW_MARK && t.column(it) != null }
                if (mark == null && usable.isEmpty()) continue
                for (c in usable.keys + listOfNotNull(mark?.let { SyncCodec.ROW_MARK })) {
                    db.exec("DELETE FROM _sync_foreign WHERE tbl = ? AND gk = ? AND col = ?", listOf(table, key, c))
                }
                if (mark != null && mark.value?.toString() == "0") {
                    applyDelete(SyncOp.Delete(table, key, mark.stamp), scratch)
                } else {
                    val rowStamp = mark?.stamp ?: usable.values.maxOf { it.stamp }
                    applyCells(SyncOp.Cells(table, key, mark != null, rowStamp, usable), scratch)
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Read positions
    // ═════════════════════════════════════════════════════════════════════════

    /** How far this member has read into [peer]'s journal: (segment, byte offset), or null if never. */
    fun cursor(peer: String): Pair<Int, Long>? =
        db.query("SELECT seg, off FROM _sync_cursor WHERE peer = ?", listOf(peer)) { c ->
            if (c.next()) (c.value(0) as Number).toInt() to (c.value(1) as Number).toLong() else null
        }

    fun setCursor(peer: String, seg: Int, off: Long) =
        db.exec("INSERT OR REPLACE INTO _sync_cursor(peer, seg, off) VALUES(?, ?, ?)", listOf(peer, seg.toLong(), off))

    fun cursors(): Map<String, Pair<Int, Long>> =
        db.query("SELECT peer, seg, off FROM _sync_cursor") { c ->
            val out = LinkedHashMap<String, Pair<Int, Long>>()
            while (c.next()) out[c.value(0).toString()] = (c.value(1) as Number).toInt() to (c.value(2) as Number).toLong()
            out
        }

    // ═════════════════════════════════════════════════════════════════════════
    // Snapshot export
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Streams this member's complete merged state — every shared row with its
     * stamps, every tombstone, everything parked — as snapshot lines. Loading
     * those lines through [apply] on any member, in any state, is always
     * safe: it is the same newest-wins merge as everything else.
     *
     * Returns the number of lines written.
     */
    fun exportSnapshot(sink: (String) -> Unit): Int {
        var count = 0
        for (t in tables.values) {
            var after = ""
            while (true) {
                val page = db.queryRows(
                    "SELECT gk FROM _sync_row WHERE tbl = ? AND gk > ? AND ts > 0 ORDER BY gk LIMIT $BATCH",
                    listOf(t.name, after)
                )
                if (page.isEmpty()) break
                for (p in page) {
                    val key = p["gk"] as String
                    after = key
                    val meta = loadMeta(t, key) ?: continue
                    if (meta.del) {
                        sink(SyncCodec.encodeSnapshotDelete(t.name, key, meta.stamp)); count++
                        continue
                    }
                    val row = loadLocalRowFor(t, meta) ?: continue
                    val values = LinkedHashMap<String, Any?>()
                    for (c in t.dataColumns) values[c] = row[c]
                    val columnStamps = if (t.perColumn) {
                        // Baseline columns are left out of "cc": nobody vouches
                        // for them, so they must not travel under the row's stamp.
                        val stamps = meta.cols.orEmpty().filterValues { it.stamp != Stamp.BASELINE }
                        for (c in t.dataColumns) if (c !in stamps) values.remove(c)
                        stamps.mapValues { it.value.stamp }
                    } else null
                    sink(SyncCodec.encodeSnapshotRow(t.name, key, meta.stamp, values, columnStamps)); count++
                }
            }
        }
        // Tombstones of tables this build no longer has, and parked data.
        val parkedKeys = db.queryRows("SELECT DISTINCT tbl, gk FROM _sync_foreign ORDER BY tbl, gk")
        for (k in parkedKeys) {
            val cells = loadParked(k["tbl"] as String, k["gk"] as String)
            if (cells.isNotEmpty()) { sink(SyncCodec.encodeSnapshotParked(k["tbl"] as String, k["gk"] as String, cells)); count++ }
        }
        return count
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Restore from a backup database
    // ═════════════════════════════════════════════════════════════════════════

    enum class RestoreMode {
        /** Add what the backup has and this database lacks. Never changes or removes anything. */
        MERGE,
        /** Make every table found in the backup equal to the backup: add, overwrite and remove. */
        REPLACE,
    }

    data class RestoreResult(
        var added: Int = 0,
        var updated: Int = 0,
        var removed: Int = 0,
        val tablesRestored: MutableList<String> = ArrayList(),
        val tablesSkipped: MutableList<String> = ArrayList(),
    )

    /**
     * Brings data in from another SQLite database — a backup made by any
     * build, older or newer — by reading its rows and writing them here with
     * ordinary INSERT / UPDATE / DELETE statements, in one transaction.
     *
     * This replaces "overwrite the database file and restart the app". It is
     * why a restore can no longer collide with sync or crash a running app:
     * to the rest of the system these are simply local edits. If this
     * database is in a sync group the triggers see them and they travel to
     * the other members like any other change.
     *
     * Version tolerance falls out of working by name: a table only in the
     * backup is skipped, a table only here is left alone, a column only in
     * the backup is ignored, a column only here keeps its value (or gets its
     * default on a newly added row).
     *
     * [adjust] may edit a backup row before it is written (the coordinator
     * uses it so a restore can never resurrect a running timer).
     */
    fun restoreFrom(
        source: SyncDb,
        mode: RestoreMode,
        adjust: (table: String, row: MutableMap<String, Any?>) -> Unit = { _, _ -> },
    ): RestoreResult = db.transaction {
        refreshTables()
        val result = RestoreResult()
        for (src in SyncSchema.discoverTables(source)) {
            val t = tables[src.name]
            if (t == null) { result.tablesSkipped.add(src.name); continue }
            val common = src.dataColumns.filter { t.column(it) != null && it in t.dataColumns }
            if (common.isEmpty() || (!t.localIds && !t.pk.all { it in common })) {
                result.tablesSkipped.add(src.name); continue
            }
            if (t.localIds) restoreByContent(source, src, t, common, mode, adjust, result)
            else restoreByKey(source, src, t, common, mode, adjust, result)
            result.tablesRestored.add(t.name)
        }
        result
    }

    private fun restoreByKey(
        source: SyncDb, src: TableInfo, t: TableInfo, common: List<String>, mode: RestoreMode,
        adjust: (String, MutableMap<String, Any?>) -> Unit, result: RestoreResult,
    ) {
        val seen = HashSet<String>()
        forEachRow(source, src) { raw ->
            val row = LinkedHashMap<String, Any?>()
            for (c in common) row[c] = raw[c]
            adjust(t.name, row)
            val key = naturalKey(t, row) ?: return@forEachRow
            seen.add(key)
            val local = loadLocalRow(t, t.pk.map { row[it] })
            if (local == null) {
                insertRestored(t, row)
                result.added++
            } else if (mode == RestoreMode.REPLACE) {
                val diff = row.filter { (c, v) -> c !in t.pk && !sameValue(local[c], v) }
                if (diff.isNotEmpty()) {
                    db.exec(
                        "UPDATE ${qi(t.name)} SET " + diff.keys.joinToString(", ") { "${qi(it)} = ?" } +
                            " WHERE " + t.pk.joinToString(" AND ") { "${qi(it)} = ?" },
                        diff.values.toList() + t.pk.map { row[it] }
                    )
                    result.updated++
                }
            }
        }
        if (mode != RestoreMode.REPLACE) return
        val doomed = ArrayList<List<Any?>>()
        forEachLocalRow(t) { local ->
            val key = naturalKey(t, local)
            if (key != null && key !in seen) doomed.add(t.pk.map { local[it] })
        }
        for (pk in doomed) {
            db.exec("DELETE FROM ${qi(t.name)} WHERE " + t.pk.joinToString(" AND ") { "${qi(it)} = ?" }, pk)
            result.removed++
        }
    }

    private fun restoreByContent(
        source: SyncDb, src: TableInfo, t: TableInfo, common: List<String>, mode: RestoreMode,
        adjust: (String, MutableMap<String, Any?>) -> Unit, result: RestoreResult,
    ) {
        // Machine-local ids cannot be matched across databases, so rows are
        // matched by what they contain (over the columns both sides have).
        fun fingerprint(row: Map<String, Any?>): String =
            SyncCodec.contentId(t.name, common.associateWith { row[it] })

        val localCounts = HashMap<String, Int>()
        forEachLocalRow(t) { row -> localCounts.merge(fingerprint(row), 1, Int::plus) }
        val backupCounts = HashMap<String, Int>()
        forEachRow(source, src) { raw ->
            val row = LinkedHashMap<String, Any?>()
            for (c in common) row[c] = raw[c]
            adjust(t.name, row)
            val f = fingerprint(row)
            val n = backupCounts.merge(f, 1, Int::plus)!!
            if (n > (localCounts[f] ?: 0)) {
                insertRestored(t, row)
                result.added++
            }
        }
        if (mode != RestoreMode.REPLACE) return
        val keep = HashMap<String, Int>()
        val doomed = ArrayList<Any?>()
        forEachLocalRow(t) { row ->
            val f = fingerprint(row)
            val n = keep.merge(f, 1, Int::plus)!!
            if (n > (backupCounts[f] ?: 0)) doomed.add(row[t.pk[0]])
        }
        for (id in doomed) {
            db.exec("DELETE FROM ${qi(t.name)} WHERE ${qi(t.pk[0])} = ?", listOf(id))
            result.removed++
        }
    }

    private fun insertRestored(t: TableInfo, row: Map<String, Any?>) {
        val names = ArrayList<String>()
        val values = ArrayList<Any?>()
        for (c in t.dataColumns) {
            when {
                row.containsKey(c) -> { names.add(c); values.add(row[c]) }
                t.column(c)?.defaultSql != null -> Unit
                else -> { names.add(c); values.add(t.column(c)?.zeroValue()) }
            }
        }
        db.exec(
            "INSERT OR REPLACE INTO ${qi(t.name)}(" + names.joinToString(", ") { qi(it) } +
                ") VALUES(" + names.joinToString(", ") { "?" } + ")",
            values
        )
    }

    private fun sameValue(a: Any?, b: Any?): Boolean = when {
        a is ByteArray && b is ByteArray -> a.contentEquals(b)
        a is Number && b is Number -> a.toDouble() == b.toDouble() && (a is Double) == (b is Double)
        else -> a == b
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Row access helpers
    // ═════════════════════════════════════════════════════════════════════════

    /** A row's identity for a table whose primary key is meaningful everywhere: its key values as a JSON array. */
    internal fun naturalKey(t: TableInfo, row: Map<String, Any?>): String? {
        val values = t.pk.map { row[it] }
        if (values.any { it == null }) return null
        return MiniJson.write(values)
    }

    private fun parseNaturalKey(key: String): List<Any?>? = try {
        (MiniJson.parse(key) as? List<*>)?.map { MiniJson.toCell(it) }
    } catch (_: IllegalArgumentException) {
        null
    }

    /**
     * A row's identity for a machine-local-id table: a fingerprint of its
     * content, made unique with a "#n" suffix when this member genuinely has
     * several identical rows. Computed once, by whichever member first sees
     * the row; from then on the key travels with the row.
     */
    private fun freeContentKey(t: TableInfo, row: Map<String, Any?>): String {
        val base = SyncCodec.contentId(t.name, t.dataColumns.associateWith { row[it] })
        var key = base
        var n = 1
        while (true) {
            val meta = loadMeta(t, key)
            // Free, or a tombstone / an entry whose row has since vanished.
            if (meta == null || meta.del || meta.lid == null) return key
            n++
            key = "$base#$n"
        }
    }

    private fun loadLocalRow(t: TableInfo, pkValues: List<Any?>): Map<String, Any?>? =
        db.queryRows(
            "SELECT * FROM ${qi(t.name)} WHERE " + t.pk.joinToString(" AND ") { "${qi(it)} = ?" } + " LIMIT 1",
            pkValues
        ).firstOrNull()

    private fun loadLocalRowFor(t: TableInfo, meta: RowMeta): Map<String, Any?>? =
        if (t.localIds) meta.lid?.let { loadLocalRow(t, listOf(it)) }
        else parseNaturalKey(meta.key)?.let { if (it.size == t.pk.size) loadLocalRow(t, it) else null }

    private fun updateLocalRow(t: TableInfo, meta: RowMeta, values: Map<String, Any?>) {
        val settable = values.filterKeys { it !in t.pk }
        if (settable.isEmpty()) return
        val pk = if (t.localIds) listOf(meta.lid ?: return) else parseNaturalKey(meta.key) ?: return
        db.exec(
            "UPDATE ${qi(t.name)} SET " + settable.keys.joinToString(", ") { "${qi(it)} = ?" } +
                " WHERE " + t.pk.joinToString(" AND ") { "${qi(it)} = ?" },
            settable.values.toList() + pk
        )
    }

    private fun deleteLocalRow(t: TableInfo, meta: RowMeta): Boolean {
        val pk = if (t.localIds) listOf(meta.lid ?: return false) else parseNaturalKey(meta.key) ?: return false
        if (pk.size != t.pk.size) return false
        db.exec("DELETE FROM ${qi(t.name)} WHERE " + t.pk.joinToString(" AND ") { "${qi(it)} = ?" }, pk)
        return true
    }

    private fun forEachLocalRow(t: TableInfo, block: (Map<String, Any?>) -> Unit) = forEachRow(db, t, block)

    /**
     * Visits every row of [t] in pages, so a large history table is never
     * held in memory at once and the visitor is free to write to the
     * database between pages.
     */
    private fun forEachRow(source: SyncDb, t: TableInfo, block: (Map<String, Any?>) -> Unit) {
        var after = Long.MIN_VALUE
        while (true) {
            val page = ArrayList<Map<String, Any?>>()
            var last = after
            try {
                source.query(
                    "SELECT rowid AS _sync_rid, * FROM ${qi(t.name)} WHERE rowid > ? ORDER BY rowid LIMIT $BATCH",
                    listOf(after)
                ) { c ->
                    while (c.next()) {
                        val row = LinkedHashMap<String, Any?>(c.columnCount * 2)
                        for (i in 1 until c.columnCount) row[c.columnName(i)] = c.value(i)
                        last = (c.value(0) as Number).toLong()
                        page.add(row)
                    }
                }
            } catch (e: Exception) {
                // A table declared WITHOUT ROWID cannot be paged this way.
                // Room never creates one; if some future table is, read it in
                // one go rather than fail the whole sync.
                if (after != Long.MIN_VALUE) throw e
                source.queryRows("SELECT * FROM ${qi(t.name)}").forEach(block)
                return
            }
            if (page.isEmpty()) return
            after = last
            page.forEach(block)
        }
    }

    // ── Bookkeeping rows ──────────────────────────────────────────────────────

    private class ColClock(val stamp: Stamp, val hash: Long)

    private class RowMeta(
        val table: String,
        val key: String,
        var lid: Long?,
        var stamp: Stamp,
        var del: Boolean,
        var vh: Long?,
        var cols: MutableMap<String, ColClock>?,
    ) {
        /** The most recent stamp anywhere on this row. */
        fun newest(): Stamp {
            var s = stamp
            cols?.values?.forEach { if (it.stamp > s) s = it.stamp }
            return s
        }

        /** True if [peer] changed anything on this row after [since]. */
        fun authoredAfter(since: Stamp, peer: String): Boolean {
            if (stamp > since && stamp.peer == peer) return true
            return cols?.values?.any { it.stamp > since && it.stamp.peer == peer } == true
        }

        fun markDeleted(at: Stamp) {
            stamp = at; del = true; lid = null; vh = null; cols = null
        }
    }

    private fun loadMeta(t: TableInfo, key: String): RowMeta? =
        readMeta("SELECT tbl, gk, lid, ts, peer, del, vh, cols FROM _sync_row WHERE tbl = ? AND gk = ?", listOf(t.name, key))

    private fun loadMetaByLid(t: TableInfo, lid: Long): RowMeta? =
        readMeta("SELECT tbl, gk, lid, ts, peer, del, vh, cols FROM _sync_row WHERE tbl = ? AND lid = ? LIMIT 1", listOf(t.name, lid))

    @Suppress("UNCHECKED_CAST")
    private fun readMeta(sql: String, args: List<Any?>): RowMeta? = db.query<RowMeta?>(sql, args) { c ->
        if (!c.next()) return@query null
        val colsJson = c.value(7)?.toString()
        val cols: MutableMap<String, ColClock>? = colsJson?.let { text ->
            val parsed = MiniJson.parse(text) as? Map<String, Any?> ?: return@let null
            val out = HashMap<String, ColClock>(parsed.size * 2)
            for ((name, v) in parsed) {
                val l = v as? List<Any?> ?: continue
                out[name] = ColClock(
                    Stamp((l[0] as Number).toLong(), l[1] as String),
                    (l[2] as Number).toLong()
                )
            }
            out
        }
        RowMeta(
            table = c.value(0).toString(),
            key = c.value(1).toString(),
            lid = (c.value(2) as? Number)?.toLong(),
            stamp = Stamp((c.value(3) as Number).toLong(), c.value(4).toString()),
            del = (c.value(5) as Number).toInt() != 0,
            vh = (c.value(6) as? Number)?.toLong(),
            cols = cols,
        )
    }

    private fun saveMeta(m: RowMeta) {
        val colsJson = m.cols?.let { cols ->
            MiniJson.write(cols.mapValues { listOf(it.value.stamp.ts, it.value.stamp.peer, it.value.hash) })
        }
        db.exec(
            "INSERT OR REPLACE INTO _sync_row(tbl, gk, lid, ts, peer, del, vh, cols) VALUES(?, ?, ?, ?, ?, ?, ?, ?)",
            listOf(m.table, m.key, m.lid, m.stamp.ts, m.stamp.peer, if (m.del) 1L else 0L, m.vh, colsJson)
        )
    }

    companion object {
        private const val BATCH = 500

        private const val META_OWNER = "owner"
        private const val META_APPLYING = "applying"
        private const val META_BOOTSTRAPPED = "bootstrapped"
        private const val META_ANNOUNCED = "announced"
        private const val META_SCHEMA = "schema"
    }
}
