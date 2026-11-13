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
