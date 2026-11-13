package com.eevdf.capabilities.multidevicesync.logic

/**
 * What the engine knows about one user table, discovered at runtime from
 * SQLite itself (`sqlite_master` + `PRAGMA table_info`) — never from a list
 * written into this file.
 *
 * That is the whole basis of the "any app version can sync with any other"
 * promise: a table or column added by a future build is picked up here the
 * first time that build runs, with no change to the sync code, and a table
 * this build does not have is simply absent from this list (its data is
 * parked, not dropped — see [SyncEngine]'s foreign store).
 */
internal data class TableInfo(
    val name: String,
    val columns: List<ColumnInfo>,
    /** Primary-key column names in key order. */
    val pk: List<String>,
    /**
     * True when the primary key is a single INTEGER column, i.e. an alias of
     * SQLite's rowid (`run_log.id`). Such ids are handed out by each device
     * on its own and mean nothing to another device — two users both own a
     * row "17" that are different rows — so these tables are matched by row
     * content instead of by id. See [SyncEngine.contentKey].
     */
    val localIds: Boolean,
    /** True when changes are tracked and merged column by column instead of row by row. */
    val perColumn: Boolean,
    /**
     * Columns that exist in this database but are NEVER shared: each user
     * works them out for itself. See [SyncSchema.LOCAL_ONLY_COLUMNS].
     */
    val localOnly: Set<String> = emptySet(),
) {
    val columnNames: List<String> = columns.map { it.name }
    private val byName: Map<String, ColumnInfo> = columns.associateBy { it.name }
    fun column(name: String): ColumnInfo? = byName[name]

    /** Columns that travel in the journal: all of them, minus a machine-local id and minus [localOnly]. */
    val dataColumns: List<String> =
        columnNames.filter { !(localIds && it == pk[0]) && it !in localOnly }
    private val dataSet: Set<String> = dataColumns.toSet()

    /** True if [name] is a column whose values are shared with the other users. */
    fun syncs(name: String): Boolean = name in dataSet

    /** Columns an INSERT must name: everything except a machine-local id (local-only ones get their default). */
    val insertColumns: List<String> = if (localIds) columnNames.filter { it != pk[0] } else columnNames
}

internal data class ColumnInfo(
    val name: String,
    /** Declared type, upper-cased (`INTEGER`, `TEXT`, `REAL`, `BLOB`, …). */
    val type: String,
    val notNull: Boolean,
    /** The column's SQL default expression, or null when it declares none. */
    val defaultSql: String?,
) {
    /**
     * The value to use when another device sends a brand-new row that does
     * not carry this column at all (it runs an older build that never had
     * it). Only consulted for NOT NULL columns with no SQL default, where
     * leaving the column out of the INSERT would fail.
     */
    fun zeroValue(): Any? {
        val t = type
        return when {
            !notNull -> null
            t.contains("INT") -> 0L
            t.contains("CHAR") || t.contains("CLOB") || t.contains("TEXT") -> ""
            t.contains("BLOB") -> ByteArray(0)
            t.contains("REAL") || t.contains("FLOA") || t.contains("DOUB") -> 0.0
            else -> 0L
        }
    }
}

/**
 * Creates and maintains everything the sync engine keeps *inside* the app
 * database: its own bookkeeping tables and the change-capture triggers.
 *
 * WHY THE BOOKKEEPING LIVES IN THE SAME DATABASE FILE
 * ---------------------------------------------------
 * A change to a task and the note "this task changed" are written by the
 * same SQLite transaction, so they can never disagree — not after a crash,
 * not after the process is killed mid-write on a user switch. Room does not
 * mind: it validates only the tables of its own entities, and every name
 * here starts with `_sync_`.
 *
 * WHY TRIGGERS
 * ------------
 * Every write path in the app — Room DAOs, raw queries, a capability that
 * does not exist yet — is captured without that code knowing sync exists,
 * and without a single line of this file changing when a table is added.
 */
internal object SyncSchema {

    /** Tables that are never synced: SQLite's, Android's, Room's, and our own. */
    private fun isInternal(name: String): Boolean =
        name.startsWith("sqlite_") || name.startsWith("_sync_") ||
            name == "android_metadata" || name == "room_master_table"

    /**
     * Tables merged column by column. A task is edited in one place while its
     * timer runs in another, so "newest whole row wins" would let a rename
     * silently undo a running timer; every other table is either append-only
     * history or a small record that is always rewritten whole.
     */
    private val PER_COLUMN_TABLES = setOf("tasks")

    /**
     * Columns that are cache, not data: each user recomputes them from the
     * shared columns, so sending them only costs card writes.
     *
     * `tasks.lag / eligibleTime / virtualDeadline` are what the EEVDF
     * scheduler derives from vruntime, weight and the other tasks. Every
     * pause recomputes them for ALL active tasks, so as shared columns they
     * produced one journal line per task per pause (about 40 KB for 200
     * tasks); kept local they cost nothing. After a sync the task list screen
     * recomputes them (`TaskRepository.recalculateScheduleCache`), because
     * the lists sort by the stored copy.
     */
    val LOCAL_ONLY_COLUMNS: Map<String, Set<String>> =
        mapOf("tasks" to setOf("lag", "eligibleTime", "virtualDeadline"))

    /** Largest primary key the dirty table can hold; wider keys are left unsynced (none exist today). */
    const val MAX_PK_COLUMNS = 4

    /** SQLite expression for "now" as epoch milliseconds, valid on every SQLite Android 12+ ships. */
    private const val NOW_MS = "CAST((julianday('now') - 2440587.5) * 86400000.0 AS INTEGER)"

    fun ensureTables(db: SyncDb) {
        db.exec("CREATE TABLE IF NOT EXISTS _sync_meta(k TEXT NOT NULL PRIMARY KEY, v TEXT) WITHOUT ROWID")
        // One line per locally-changed row, written by the triggers. Coalesces:
        // a row edited fifty times before the next flush is still one line.
        db.exec(
            "CREATE TABLE IF NOT EXISTS _sync_dirty(" +
                "tbl TEXT NOT NULL, k1 NOT NULL, k2 NOT NULL, k3 NOT NULL, k4 NOT NULL, ts INTEGER NOT NULL, " +
                "PRIMARY KEY(tbl, k1, k2, k3, k4)) WITHOUT ROWID"
        )
        // What this device knows about every synced row: who last wrote it and
        // when (or that it was deleted), plus a fingerprint of the value so an
        // unchanged row is never written to the SD card twice.
        db.exec(
            "CREATE TABLE IF NOT EXISTS _sync_row(" +
                "tbl TEXT NOT NULL, gk TEXT NOT NULL, lid INTEGER, " +
                "ts INTEGER NOT NULL, peer TEXT NOT NULL, del INTEGER NOT NULL DEFAULT 0, " +
                "vh INTEGER, cols TEXT, " +
                "PRIMARY KEY(tbl, gk)) WITHOUT ROWID"
        )
        db.exec("CREATE INDEX IF NOT EXISTS _sync_row_lid ON _sync_row(tbl, lid)")
        // Only rows still at baseline (ts = 0) are ever looked up by stamp. The
        // old index covered every row and was about a third of the bookkeeping.
        db.exec("DROP INDEX IF EXISTS _sync_row_ts")
        db.exec("CREATE INDEX IF NOT EXISTS _sync_row_base ON _sync_row(tbl) WHERE ts = 0")
        // Changes stamped and ready to leave, in order. Survives the SD card
        // being unreachable (a background user, a pulled card): nothing is
        // lost, it simply leaves on the next successful write.
        db.exec("CREATE TABLE IF NOT EXISTS _sync_outbox(seq INTEGER PRIMARY KEY AUTOINCREMENT, line TEXT NOT NULL)")
        // How far into each other member's journal this device has read.
        db.exec(
            "CREATE TABLE IF NOT EXISTS _sync_cursor(" +
                "peer TEXT NOT NULL PRIMARY KEY, seg INTEGER NOT NULL, off INTEGER NOT NULL) WITHOUT ROWID"
        )
        // Data for tables/columns this build does not have. Kept verbatim and
        // replayed if a later build adds them — never dropped.
        db.exec(
            "CREATE TABLE IF NOT EXISTS _sync_foreign(" +
                "tbl TEXT NOT NULL, gk TEXT NOT NULL, col TEXT NOT NULL, " +
                "ts INTEGER NOT NULL, peer TEXT NOT NULL, val TEXT, " +
                "PRIMARY KEY(tbl, gk, col)) WITHOUT ROWID"
        )
    }

    fun dropAll(db: SyncDb) {
        for (t in discoverTables(db)) dropTriggers(db, t.name)
        for (t in listOf("_sync_meta", "_sync_dirty", "_sync_row", "_sync_outbox", "_sync_cursor", "_sync_foreign")) {
            db.exec("DROP TABLE IF EXISTS $t")
        }
    }

    // ── Table discovery ───────────────────────────────────────────────────────

    fun discoverTables(db: SyncDb): List<TableInfo> {
        val names = db.query("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name") { c ->
            val out = ArrayList<String>()
            while (c.next()) out.add(c.value(0).toString())
            out
        }
        return names.filterNot(::isInternal).mapNotNull { describe(db, it) }
    }

    fun describe(db: SyncDb, table: String): TableInfo? {
        val cols = ArrayList<ColumnInfo>()
        val pk = sortedMapOf<Int, String>()
        var pkType = ""
        db.query("PRAGMA table_info(${qi(table)})") { c ->
            // cid | name | type | notnull | dflt_value | pk
            while (c.next()) {
                val name = c.value(1).toString()
                val type = (c.value(2)?.toString() ?: "").uppercase()
                val notNull = (c.value(3) as? Number)?.toInt() == 1
                val dflt = c.value(4)?.toString()
                val pkPos = (c.value(5) as? Number)?.toInt() ?: 0
                cols.add(ColumnInfo(name, type, notNull, dflt))
                if (pkPos > 0) { pk[pkPos] = name; pkType = type }
            }
        }
        if (cols.isEmpty() || pk.isEmpty() || pk.size > MAX_PK_COLUMNS) return null
        val localIds = pk.size == 1 && pkType == "INTEGER"
        return TableInfo(
            name = table,
            columns = cols,
            pk = pk.values.toList(),
            localIds = localIds,
            perColumn = !localIds && table in PER_COLUMN_TABLES,
            localOnly = LOCAL_ONLY_COLUMNS[table].orEmpty().filter { n -> cols.any { it.name == n } }.toSet(),
        )
    }

    // ── Change-capture triggers ───────────────────────────────────────────────

    private fun triggerNames(table: String) =
        listOf("_sync_ai_$table", "_sync_au_$table", "_sync_ad_$table")

    fun dropTriggers(db: SyncDb, table: String) {
        for (n in triggerNames(table)) db.exec("DROP TRIGGER IF EXISTS ${qi(n)}")
    }

    /**
     * Installs (or repairs) the three triggers on [t]. Idempotent and cheap:
     * if all three already exist with exactly the text this build would
     * write, nothing is touched. A Room migration that rebuilds a table drops
     * its triggers along with the old table; this puts them back on the next
     * app start.
     */
    fun ensureTriggers(db: SyncDb, t: TableInfo) {
        val wanted = triggerSql(t)
        val existing = HashMap<String, String>()
        db.query(
            "SELECT name, sql FROM sqlite_master WHERE type = 'trigger' AND tbl_name = ?", listOf(t.name)
        ) { c -> while (c.next()) existing[c.value(0).toString()] = c.value(1)?.toString() ?: "" }
        for ((name, sql) in wanted) {
            if (existing[name] == sql) continue
            db.exec("DROP TRIGGER IF EXISTS ${qi(name)}")
            db.exec(sql)
        }
    }

    private fun triggerSql(t: TableInfo): Map<String, String> {
        val tbl = ql(t.name)
        fun mark(rowAlias: String): String {
            val keys = (0 until MAX_PK_COLUMNS).map { i ->
                if (i < t.pk.size) "IFNULL($rowAlias.${qi(t.pk[i])}, '')" else "''"
            }
            val same = keys.mapIndexed { i, k -> "k${i + 1} = $k" }.joinToString(" AND ")
            // Deliberately NOT "INSERT OR REPLACE": SQLite lets the statement
            // that fires a trigger override the conflict clause of statements
            // inside it, and Room's @Update / @Delete / @Insert all say
            // "OR ABORT" (or "OR REPLACE") explicitly. Under that override a
            // second mark for a row already marked dirty — which every
            // UPDATE produces, since it marks OLD and NEW — became a
            // constraint violation and crashed the write. These two plain
            // statements cannot conflict whatever the outer statement says.
            return "UPDATE _sync_dirty SET ts = $NOW_MS WHERE tbl = $tbl AND $same; " +
                "INSERT INTO _sync_dirty(tbl, k1, k2, k3, k4, ts) " +
                "SELECT $tbl, ${keys.joinToString(", ")}, $NOW_MS " +
                "WHERE NOT EXISTS (SELECT 1 FROM _sync_dirty WHERE tbl = $tbl AND $same);"
        }
        // While the engine itself is writing rows it received from another
        // member, the triggers stay silent — otherwise every received change
        // would be sent straight back out again, forever.
        val guard = "WHEN (SELECT v FROM _sync_meta WHERE k = 'applying') IS NOT '1'"
        // An UPDATE only counts when a SHARED column really changed. Room's
        // @Update rewrites every column of the row, so without this the
        // scheduler's cache refresh (three local-only columns on every active
        // task, after every pause) would mark every task as changed.
        val watched = (t.dataColumns + t.pk).distinct()
        val changed = watched.joinToString(" OR ") { "OLD.${qi(it)} IS NOT NEW.${qi(it)}" }
        val updateGuard = "$guard AND ($changed)"
        val (ai, au, ad) = triggerNames(t.name)
        return linkedMapOf(
            ai to "CREATE TRIGGER ${qi(ai)} AFTER INSERT ON ${qi(t.name)} $guard BEGIN ${mark("NEW")} END",
            // OLD as well as NEW: an UPDATE that changes the primary key is,
            // to every other device, one row vanishing and another appearing.
            au to "CREATE TRIGGER ${qi(au)} AFTER UPDATE ON ${qi(t.name)} $updateGuard BEGIN ${mark("OLD")} ${mark("NEW")} END",
            ad to "CREATE TRIGGER ${qi(ad)} AFTER DELETE ON ${qi(t.name)} $guard BEGIN ${mark("OLD")} END",
        )
    }

    // ── Meta helpers ──────────────────────────────────────────────────────────

    fun getMeta(db: SyncDb, key: String): String? =
        db.queryString("SELECT v FROM _sync_meta WHERE k = ?", listOf(key))

    fun setMeta(db: SyncDb, key: String, value: String?) {
        if (value == null) db.exec("DELETE FROM _sync_meta WHERE k = ?", listOf(key))
        else db.exec("INSERT OR REPLACE INTO _sync_meta(k, v) VALUES(?, ?)", listOf(key, value))
    }
}
