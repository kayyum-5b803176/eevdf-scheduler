package com.eevdf.capabilities.multidevicesync.logic

/**
 * The only door the merge engine has into a database.
 *
 * Deliberately not Room, not `android.database`, not JDBC: three tiny
 * operations any SQLite binding can provide. In the app the implementation
 * is [RoomSyncDb] (the live Room connection — the database is never closed,
 * copied over, or reopened by sync). In unit tests it is a JDBC SQLite file,
 * which is what lets the real SQL in this package run on a plain JVM.
 *
 * Cell values are restricted to what SQLite itself stores:
 * `null`, [Long], [Double], [String], [ByteArray].
 */
interface SyncDb {
    /** Runs one statement that returns no rows. */
    fun exec(sql: String, args: List<Any?> = emptyList())

    /** Runs a query and hands the open cursor to [block]; the cursor is closed afterwards. */
    fun <T> query(sql: String, args: List<Any?> = emptyList(), block: (SyncCursor) -> T): T

    /**
     * Runs [block] atomically: everything it does is committed together, or —
     * if it throws — none of it is. Must be re-entrant (a nested call simply
     * joins the outer transaction).
     */
    fun <T> transaction(block: () -> T): T
}

/** A forward-only row cursor. Column indexes are zero-based. */
interface SyncCursor {
    fun next(): Boolean
    val columnCount: Int
    fun columnName(index: Int): String
    /** The cell as one of `null`, [Long], [Double], [String], [ByteArray]. */
    fun value(index: Int): Any?
}

// ── Small conveniences used throughout the engine ────────────────────────────

internal fun SyncDb.queryLong(sql: String, args: List<Any?> = emptyList()): Long? =
    query(sql, args) { c -> if (c.next()) (c.value(0) as? Number)?.toLong() else null }

internal fun SyncDb.queryString(sql: String, args: List<Any?> = emptyList()): String? =
    query(sql, args) { c -> if (c.next()) c.value(0)?.toString() else null }

/** Reads every row of a query as a column-name → value map. Only for small result sets. */
internal fun SyncDb.queryRows(sql: String, args: List<Any?> = emptyList()): List<Map<String, Any?>> =
    query(sql, args) { c ->
        val out = ArrayList<Map<String, Any?>>()
        while (c.next()) {
            val row = LinkedHashMap<String, Any?>(c.columnCount * 2)
            for (i in 0 until c.columnCount) row[c.columnName(i)] = c.value(i)
            out.add(row)
        }
        out
    }

/** Quotes an identifier (table / column name) for use inside SQL text. */
internal fun qi(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

/** Quotes a string literal for use inside SQL text (trigger bodies cannot bind parameters). */
internal fun ql(text: String): String = "'" + text.replace("'", "''") + "'"
