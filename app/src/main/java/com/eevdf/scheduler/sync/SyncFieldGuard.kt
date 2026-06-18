package com.eevdf.scheduler.sync

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.eevdf.scheduler.db.TaskDatabase
import com.eevdf.scheduler.model.task.Task

/**
 * Compares a remote DB snapshot against the local task table and returns any
 * [SyncConflict]s that would prevent a safe automatic import.
 *
 * ── Rules ────────────────────────────────────────────────────────────────────
 *
 *  AUTO-SYNC  (no conflicts returned, import proceeds):
 *    • A remote task has a field that is *more informative* than the local one:
 *        – a non-blank value where local is blank / null
 *        – a positive numeric where local is zero / negative
 *    • A remote task is brand-new (id not in local DB).
 *    • Timer/scheduler state fields (vruntime, lag, remainingSeconds, etc.)
 *      — those are operational and always auto-accepted.
 *
 *  WARN + BLOCK (conflict returned, user must manually confirm):
 *    • Remote task has a *key content field* that is blank/null/zero where the
 *      local value is non-blank/non-null/positive.
 *      Key content fields: name, description, category, timeSliceSeconds,
 *      priority, taskType, schedulerClass.
 *    • A task that exists locally is completely absent from the remote snapshot
 *      (deleted on remote side).
 *
 * ── How it works ─────────────────────────────────────────────────────────────
 *
 *  1. The remote DB file is opened read-only as a plain SQLiteDatabase (not
 *     Room) so we can query it without interfering with the live Room instance.
 *  2. Local tasks are fetched from the live Room DB via suspend DAO call.
 *  3. The two sets are diffed at the field level.
 *  4. Returns a (possibly empty) list of [SyncConflict].  An empty list means
 *     all changes are safe to auto-apply.
 */
object SyncFieldGuard {

    /**
     * Key content fields that are checked for blank-overwrite conflicts.
     *
     * Timer/EEVDF state columns are intentionally excluded — those always
     * auto-sync because they are operational values, not authored content.
     */
    private val GUARDED_STRING_FIELDS: List<Pair<String, (RemoteTaskRow) -> Pair<String, String>>> = listOf(
        "name"          to { r: RemoteTaskRow -> "name"          to r.name },
        "category"      to { r: RemoteTaskRow -> "category"      to r.category },
        "taskType"      to { r: RemoteTaskRow -> "taskType"      to r.taskType },
        "schedulerClass" to { r: RemoteTaskRow -> "schedulerClass" to r.schedulerClass },
    )

    /**
     * Numeric fields where 0 / negative remote value on a locally positive task
     * is treated as a blank-overwrite conflict.
     */
    private val GUARDED_LONG_FIELDS: List<Triple<String, (Task) -> Long, (RemoteTaskRow) -> Long>> = listOf(
        Triple("timeSliceSeconds", { t: Task -> t.timeSliceSeconds }, { r: RemoteTaskRow -> r.timeSliceSeconds }),
        Triple("priority",         { t: Task -> t.priority.toLong() }, { r: RemoteTaskRow -> r.priority.toLong() }),
    )

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Opens [remoteDbFile] read-only and compares every task against the live
     * local Room database.  Returns the list of conflicts found.
     *
     * Must be called from a background / IO coroutine — it performs DB I/O.
     */
    suspend fun detectConflicts(
        context: Context,
        remoteDbFile: java.io.File,
    ): List<SyncConflict> {
        // 1. Fetch local tasks from live Room DB
        val localTasks: Map<String, Task> =
            TaskDatabase.getDatabase(context).taskDao()
                .getAllTasksForBackup()
                .associateBy { task -> task.id }

        if (localTasks.isEmpty()) return emptyList()   // nothing to protect

        // 2. Open remote DB read-only (plain SQLite, not Room)
        val remoteTasks: Map<String, RemoteTaskRow> = try {
            openRemoteDb(remoteDbFile)
        } catch (e: Exception) {
            // If the remote DB can't be opened for comparison, block the import
            // with a single synthetic conflict so the user sees an explicit error.
            return listOf(
                SyncConflict(
                    taskId   = "__read_error__",
                    taskName = "(remote DB unreadable)",
                    kind     = SyncConflict.Kind.FIELD_BLANKED,
                    fieldName  = null,
                    localValue = null,
                    remoteValue = e.message?.take(100),
                )
            )
        }

        val conflicts = mutableListOf<SyncConflict>()

        for ((id, local) in localTasks) {
            val remote = remoteTasks[id]

            // ── Deleted on remote side ────────────────────────────────────────
            if (remote == null) {
                // Only flag as deleted if local task is active (not already done)
                if (!local.isCompleted) {
                    conflicts += SyncConflict(
                        taskId   = id,
                        taskName = local.name.ifBlank { id },
                        kind     = SyncConflict.Kind.TASK_DELETED,
                    )
                }
                continue
            }

            // ── String field blank-overwrite check ────────────────────────────
            checkStringField("name", local.name, remote.name, local, conflicts)
            checkStringField("description", local.description, remote.description, local, conflicts)
            checkStringField("category", local.category, remote.category, local, conflicts)
            checkStringField("taskType", local.taskType, remote.taskType, local, conflicts)
            checkStringField("schedulerClass", local.schedulerClass, remote.schedulerClass, local, conflicts)

            // ── Numeric field zero-overwrite check ────────────────────────────
            checkLongField("timeSliceSeconds", local.timeSliceSeconds, remote.timeSliceSeconds, local, conflicts)
            checkLongField("priority", local.priority.toLong(), remote.priority.toLong(), local, conflicts)
        }

        return conflicts
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun checkStringField(
        fieldName: String,
        localVal: String,
        remoteVal: String,
        local: Task,
        out: MutableList<SyncConflict>,
    ) {
        // Only conflict if LOCAL has real content that REMOTE would erase
        if (localVal.isNotBlank() && remoteVal.isBlank()) {
            out += SyncConflict(
                taskId      = local.id,
                taskName    = local.name.ifBlank { local.id },
                kind        = SyncConflict.Kind.FIELD_BLANKED,
                fieldName   = fieldName,
                localValue  = localVal,
                remoteValue = remoteVal,
            )
        }
    }

    private fun checkLongField(
        fieldName: String,
        localVal: Long,
        remoteVal: Long,
        local: Task,
        out: MutableList<SyncConflict>,
    ) {
        // Only conflict if LOCAL has a positive value that REMOTE would zero out
        if (localVal > 0L && remoteVal <= 0L) {
            out += SyncConflict(
                taskId      = local.id,
                taskName    = local.name.ifBlank { local.id },
                kind        = SyncConflict.Kind.FIELD_BLANKED,
                fieldName   = fieldName,
                localValue  = localVal.toString(),
                remoteValue = remoteVal.toString(),
            )
        }
    }

    // ── Remote DB reader ──────────────────────────────────────────────────────

    /**
     * Opens the remote .db file with a plain [SQLiteDatabase] (read-only),
     * reads all rows from the `tasks` table, and returns them keyed by id.
     */
    private fun openRemoteDb(file: java.io.File): Map<String, RemoteTaskRow> {
        val db = SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
        return db.use { readTasks(it) }
    }

    private fun readTasks(db: SQLiteDatabase): Map<String, RemoteTaskRow> {
        val result = mutableMapOf<String, RemoteTaskRow>()
        db.rawQuery("SELECT * FROM tasks", null).use { cursor ->
            if (cursor.count == 0) return result

            val idxId              = cursor.getColumnIndex("id")
            val idxName            = cursor.getColumnIndex("name")
            val idxDescription     = cursor.getColumnIndex("description")
            val idxCategory        = cursor.getColumnIndex("category")
            val idxPriority        = cursor.getColumnIndex("priority")
            val idxTimeSlice       = cursor.getColumnIndex("timeSliceSeconds")
            val idxTaskType        = cursor.getColumnIndex("taskType")
            val idxSchedulerClass  = cursor.getColumnIndex("schedulerClass")
            val idxIsCompleted     = cursor.getColumnIndex("isCompleted")

            while (cursor.moveToNext()) {
                val id = if (idxId >= 0) cursor.getString(idxId) else continue
                result[id] = RemoteTaskRow(
                    id             = id,
                    name           = if (idxName >= 0)           cursor.getString(idxName)  ?: "" else "",
                    description    = if (idxDescription >= 0)    cursor.getString(idxDescription) ?: "" else "",
                    category       = if (idxCategory >= 0)       cursor.getString(idxCategory) ?: "" else "",
                    priority       = if (idxPriority >= 0)       cursor.getInt(idxPriority)    else 0,
                    timeSliceSeconds = if (idxTimeSlice >= 0)    cursor.getLong(idxTimeSlice)  else 0L,
                    taskType       = if (idxTaskType >= 0)       cursor.getString(idxTaskType) ?: "" else "",
                    schedulerClass = if (idxSchedulerClass >= 0) cursor.getString(idxSchedulerClass) ?: "" else "",
                    isCompleted    = if (idxIsCompleted >= 0)    cursor.getInt(idxIsCompleted) != 0 else false,
                )
            }
        }
        return result
    }

    /**
     * Lightweight projection of a remote tasks row — only the fields we guard.
     * We don't need the full Task Room entity here.
     */
    data class RemoteTaskRow(
        val id: String,
        val name: String,
        val description: String,
        val category: String,
        val priority: Int,
        val timeSliceSeconds: Long,
        val taskType: String,
        val schedulerClass: String,
        val isCompleted: Boolean,
    )
}
