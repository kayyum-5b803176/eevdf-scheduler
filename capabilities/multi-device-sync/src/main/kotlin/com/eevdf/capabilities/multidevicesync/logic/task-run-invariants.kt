package com.eevdf.capabilities.multidevicesync.logic

/**
 * The one piece of *meaning* the sync capability enforces on merged data.
 *
 * The merge engine is deliberately ignorant of what any table means; it
 * guarantees every member ends up with the same rows. It cannot guarantee
 * those rows make sense together. One case matters enough to repair:
 *
 *   The app assumes at most ONE task is running. Two phones that were out
 *   of touch can each start a different task. After they merge, both rows
 *   say "running" — correct per row, impossible as a whole.
 *
 * Repair: the task started most recently keeps running; every other one is
 * paused at the moment the winner started (so its elapsed time up to the
 * hand-over is kept, not thrown away). The repair is written as an ordinary
 * local edit, so it travels to the other members like any other change, and
 * since every member computes the same answer from the same rows they all
 * agree.
 *
 * Every column is looked up by name first. If a future build renames or
 * drops one, this quietly does nothing rather than break syncing.
 */
internal object TaskRunInvariants {

    private const val TABLE = "tasks"
    private const val RUN_LOG = "run_log"
    private val NEEDED = listOf("id", "isRunning", "startTimeEpoch", "accumulatedMs")

    /** Returns how many tasks were paused. */
    fun enforceSingleRunningTask(db: SyncDb): Int = db.transaction {
        val info = SyncSchema.describe(db, TABLE) ?: return@transaction 0
        if (!NEEDED.all { info.column(it) != null }) return@transaction 0
        val running = db.queryRows(
            "SELECT id, startTimeEpoch, accumulatedMs FROM tasks " +
                "WHERE isRunning = 1 AND startTimeEpoch > 0 ORDER BY startTimeEpoch DESC, id"
        )
        if (running.size <= 1) return@transaction 0
        val winnerStart = (running[0]["startTimeEpoch"] as Number).toLong()
        val hasRemaining = info.column("remainingSeconds") != null && info.column("timeSliceSeconds") != null
        for (loser in running.drop(1)) {
            val start = (loser["startTimeEpoch"] as Number).toLong()
            val accumulated = (loser["accumulatedMs"] as Number).toLong() + (winnerStart - start).coerceAtLeast(0L)
            if (hasRemaining) {
                db.exec(
                    "UPDATE tasks SET isRunning = 0, startTimeEpoch = 0, accumulatedMs = ?, " +
                        "remainingSeconds = MAX(0, timeSliceSeconds - ? / 1000) WHERE id = ?",
                    listOf(accumulated, accumulated, loser["id"])
                )
            } else {
                db.exec(
                    "UPDATE tasks SET isRunning = 0, startTimeEpoch = 0, accumulatedMs = ? WHERE id = ?",
                    listOf(accumulated, loser["id"])
                )
            }
        }
        running.size - 1
    }

    /**
     * When two phones both see the same run expire, both credit it, and each
     * writes its own history row (the details differ slightly: duration to
     * the millisecond, load snapshots). After a merge those are two rows for
     * ONE run. Keep one.
     *
     * Same run = same task and same start time (a task cannot start twice in
     * the same millisecond). The kept row is chosen the same way on every
     * phone — longest duration, then the smallest content fingerprint — so
     * every phone deletes the same rows and the deletions agree when they
     * meet. Only runs since [sinceEpochMs] are checked, which keeps this
     * cheap and leaves old history untouched. Returns rows removed.
     */
    fun dedupeRecentRuns(db: SyncDb, sinceEpochMs: Long): Int = db.transaction {
        val info = SyncSchema.describe(db, RUN_LOG) ?: return@transaction 0
        if (listOf("taskId", "startEpoch", "durationSecs").any { info.column(it) == null }) return@transaction 0
        val groups = db.queryRows(
            "SELECT taskId, startEpoch FROM run_log WHERE startEpoch >= ? GROUP BY taskId, startEpoch HAVING COUNT(*) > 1",
            listOf(sinceEpochMs)
        )
        var removed = 0
        for (g in groups) {
            val rows = db.queryRows(
                "SELECT rowid AS _rid, * FROM run_log WHERE taskId = ? AND startEpoch = ?",
                listOf(g["taskId"], g["startEpoch"])
            )
            val ranked = rows.sortedWith(
                compareByDescending<Map<String, Any?>> { (it["durationSecs"] as? Number)?.toLong() ?: 0L }
                    .thenBy { row -> SyncCodec.contentId(RUN_LOG, info.dataColumns.associateWith { row[it] }) }
            )
            for (r in ranked.drop(1)) {
                db.exec("DELETE FROM run_log WHERE rowid = ?", listOf(r["_rid"]))
                removed++
            }
        }
        removed
    }

    /**
     * Applied to every `tasks` row read from a backup before it is written:
     * a restore must never bring back a *running* timer whose start time is
     * days old (it would instantly count as expired and ring). The elapsed
     * time already banked is kept; the task simply comes back paused.
     */
    fun neutraliseRestoredRow(table: String, row: MutableMap<String, Any?>) {
        if (table != TABLE) return
        val running = (row["isRunning"] as? Number)?.toLong() == 1L
        val started = ((row["startTimeEpoch"] as? Number)?.toLong() ?: 0L) > 0L
        if (!running && !started) return
        if (row.containsKey("isRunning")) row["isRunning"] = 0L
        if (row.containsKey("startTimeEpoch")) row["startTimeEpoch"] = 0L
    }
}
