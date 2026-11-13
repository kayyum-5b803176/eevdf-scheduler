package com.eevdf.capabilities.multidevicesync.logic

import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * Test-only [SyncDb] over a real SQLite file via JDBC.
 *
 * This is what lets the merge engine's actual SQL — triggers included — run in
 * a plain JVM unit test, with no emulator and no Robolectric.
 */
internal class JdbcSyncDb(path: String) : SyncDb {
    val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$path")
    private var depth = 0

    private fun bind(ps: java.sql.PreparedStatement, args: List<Any?>) {
        args.forEachIndexed { i, a ->
            when (a) {
                null -> ps.setNull(i + 1, java.sql.Types.NULL)
                is Long -> ps.setLong(i + 1, a)
                is Int -> ps.setLong(i + 1, a.toLong())
                is Double -> ps.setDouble(i + 1, a)
                is String -> ps.setString(i + 1, a)
                is ByteArray -> ps.setBytes(i + 1, a)
                else -> ps.setString(i + 1, a.toString())
            }
        }
    }

    override fun exec(sql: String, args: List<Any?>) {
        conn.prepareStatement(sql).use { ps -> bind(ps, args); ps.execute() }
    }

    override fun <T> query(sql: String, args: List<Any?>, block: (SyncCursor) -> T): T =
        conn.prepareStatement(sql).use { ps ->
            bind(ps, args)
            ps.executeQuery().use { rs -> block(Cur(rs)) }
        }

    override fun <T> transaction(block: () -> T): T {
        if (depth == 0) conn.autoCommit = false
        depth++
        try {
            val r = block()
            depth--
            if (depth == 0) { conn.commit(); conn.autoCommit = true }
            return r
        } catch (e: Throwable) {
            depth--
            if (depth == 0) { conn.rollback(); conn.autoCommit = true }
            throw e
        }
    }

    private class Cur(val rs: ResultSet) : SyncCursor {
        override fun next() = rs.next()
        override val columnCount get() = rs.metaData.columnCount
        override fun columnName(index: Int): String = rs.metaData.getColumnName(index + 1)
        override fun value(index: Int): Any? = when (val o = rs.getObject(index + 1)) {
            null -> null
            is Int -> o.toLong()
            is Long -> o
            is Double -> o
            is Float -> o.toDouble()
            is String -> o
            is ByteArray -> o
            else -> o.toString()
        }
    }
}
