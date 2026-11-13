package com.eevdf.capabilities.multidevicesync.logic

import android.database.Cursor
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteProgram
import androidx.room.RoomDatabase
import androidx.sqlite.db.SimpleSQLiteQuery
import java.util.concurrent.Callable

/**
 * [SyncDb] over the app's LIVE Room database.
 *
 * This is the piece that retires "close Room, overwrite the file, restart
 * the app". Everything the merge engine does goes through Room's own open
 * connection:
 *
 *  - [transaction] is `RoomDatabase.runInTransaction`, so when it ends Room's
 *    invalidation tracker notices which tables changed and every LiveData /
 *    Flow query in the app re-runs by itself. The task lists refresh without
 *    anyone telling them to.
 *  - The Hilt-held `TaskDatabase` singleton stays valid for the life of the
 *    process. Nothing ever needs a restart to "see the new file", because
 *    there is no new file.
 *
 * Must be called off the main thread (Room enforces this).
 */
internal class RoomSyncDb(private val room: RoomDatabase) : SyncDb {

    override fun exec(sql: String, args: List<Any?>) {
        room.openHelper.writableDatabase.execSQL(sql, args.toTypedArray())
    }

    override fun <T> query(sql: String, args: List<Any?>, block: (SyncCursor) -> T): T =
        room.openHelper.writableDatabase.query(SimpleSQLiteQuery(sql, args.toTypedArray())).use { c ->
            block(AndroidSyncCursor(c))
        }

    override fun <T> transaction(block: () -> T): T = room.runInTransaction(Callable { block() })

    /**
     * Writes a complete, consistent copy of the database to [target] while
     * the app keeps running. SQLite builds the copy from a single read
     * snapshot, so it is never torn — unlike copying the .db file by hand
     * while something may be writing to it.
     */
    fun copyTo(target: java.io.File) {
        if (target.exists()) target.delete()
        target.parentFile?.mkdirs()
        room.openHelper.writableDatabase.execSQL("VACUUM INTO " + ql(target.absolutePath))
    }
}

/**
 * [SyncDb] over a standalone SQLite file — a backup being restored.
 *
 * Opened with the plain framework API, not Room, on purpose: Room refuses a
 * file whose schema version is newer than the app (and would try to migrate
 * one that is older). Reading rows by table and column name has no such
 * limits, which is what lets a backup from any build be restored into any
 * other.
 */
internal class FileSyncDb(path: String) : SyncDb, AutoCloseable {

    // READWRITE, although nothing is written: a database that was last used
    // in WAL mode needs its side files created before it can even be read.
    // The file is this app's own temporary copy, never the user's original.
    private val db: SQLiteDatabase =
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE)

    override fun exec(sql: String, args: List<Any?>) = db.execSQL(sql, args.toTypedArray())

    override fun <T> query(sql: String, args: List<Any?>, block: (SyncCursor) -> T): T {
        // rawQuery() only binds strings, and SQLite never considers the text
        // '5' equal to the number 5 — so arguments are bound with their real
        // types through a cursor factory instead.
        val cursor = db.rawQueryWithFactory({ _, driver, editTable, query ->
            bindAll(query, args)
            SQLiteCursor(driver, editTable, query)
        }, sql, null, null)
        return cursor.use { block(AndroidSyncCursor(it)) }
    }

    override fun <T> transaction(block: () -> T): T {
        db.beginTransaction()
        try {
            val result = block()
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    override fun close() = db.close()

    private fun bindAll(program: SQLiteProgram, args: List<Any?>) {
        args.forEachIndexed { i, a ->
            val index = i + 1
            when (a) {
                null -> program.bindNull(index)
                is ByteArray -> program.bindBlob(index, a)
                is Double -> program.bindDouble(index, a)
                is Float -> program.bindDouble(index, a.toDouble())
                is Number -> program.bindLong(index, a.toLong())
                is Boolean -> program.bindLong(index, if (a) 1L else 0L)
                else -> program.bindString(index, a.toString())
            }
        }
    }
}

private class AndroidSyncCursor(private val c: Cursor) : SyncCursor {
    override fun next(): Boolean = c.moveToNext()
    override val columnCount: Int get() = c.columnCount
    override fun columnName(index: Int): String = c.getColumnName(index)
    override fun value(index: Int): Any? = when (c.getType(index)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> c.getLong(index)
        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(index)
        Cursor.FIELD_TYPE_BLOB -> c.getBlob(index)
        else -> c.getString(index)
    }
}
