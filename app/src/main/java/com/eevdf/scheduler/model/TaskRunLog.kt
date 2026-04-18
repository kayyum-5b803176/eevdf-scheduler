package com.eevdf.scheduler.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Records a single wall-clock run session for a task.
 * Used to compute the moving-window exceed multiplier shown on task cards.
 *
 * @param taskId          The owning task's id.
 * @param startEpoch      Wall-clock ms when this run started (System.currentTimeMillis()).
 * @param durationSeconds Actual seconds elapsed during this session (from pauseTimer / stopTimer).
 */
@Entity(tableName = "task_run_logs")
data class TaskRunLog(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val taskId: String,
    val startEpoch: Long,
    val durationSeconds: Long
)
