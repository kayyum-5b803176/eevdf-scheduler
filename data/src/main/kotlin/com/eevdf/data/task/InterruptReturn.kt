package com.eevdf.data.task

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Persisted "return-to" task for an interrupt jump, tracked PER TAB and PER SLOT.
 *
 * When the user is on a normal (non-interrupt) task and taps an INT button, the
 * task that was on the timer card is remembered here so the next INT-back tap can
 * return to it — and that memory now survives an app reboot.
 *
 * There are at most four rows, one per (tab, slot) cell:
 *   ("queue",    "A")   ("queue",    "B")
 *   ("schedule", "A")   ("schedule", "B")
 *
 * Only NON-interrupt tasks are ever stored here (an INT task can never become the
 * return-to target of another INT slot — see TaskInterruptDelegate).
 *
 * The primary key is the composite "tab|slot" string so an upsert simply overwrites
 * the single row for that cell.
 */
@Entity(tableName = "interrupt_return")
data class InterruptReturnEntry(
    /** Composite key, e.g. "queue|A". Built by [keyOf]. */
    @PrimaryKey
    val cellKey: String,
    /** Tab the jump originated from: "queue" or "schedule". */
    val tab: String,
    /** Interrupt slot: "A" or "B". */
    val slot: String,
    /** Id of the non-interrupt task to return to. */
    val taskId: String,
) {
    companion object {
        const val TAB_QUEUE    = "queue"
        const val TAB_SCHEDULE = "schedule"

        fun keyOf(tab: String, slot: String): String = "$tab|$slot"
    }
}

@Dao
interface InterruptReturnDao {

    @Query("SELECT * FROM interrupt_return WHERE cellKey = :cellKey LIMIT 1")
    suspend fun get(cellKey: String): InterruptReturnEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: InterruptReturnEntry)

    @Query("DELETE FROM interrupt_return WHERE cellKey = :cellKey")
    suspend fun clear(cellKey: String)

    /** Remove every stored return-to that points at [taskId] (e.g. task deleted). */
    @Query("DELETE FROM interrupt_return WHERE taskId = :taskId")
    suspend fun clearByTask(taskId: String)
}
