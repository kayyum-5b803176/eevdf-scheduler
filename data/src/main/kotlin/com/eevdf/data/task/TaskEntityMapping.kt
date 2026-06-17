package com.eevdf.data.task

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.eevdf.core.scheduler.model.DlBudget
import com.eevdf.core.scheduler.model.QuotaBudget
import com.eevdf.core.scheduler.model.RtConfig
import com.eevdf.core.scheduler.model.SchedTask
import java.util.UUID

/**
 * Persistence row — the ONLY place a task carries Room annotations. It may hold
 * every column the app needs (UI flags, timer runtime, sync metadata, etc.);
 * none of that leaks into the scheduler because the algorithm only ever sees
 * [SchedTask]. Trimmed to the mapped fields here; the migration keeps all
 * existing columns. The point is the separation, not the column list.
 */
@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val priority: Int,
    val timeSliceSeconds: Long,
    val parentId: String? = null,
    val isGroup: Boolean = false,
    val isCompleted: Boolean = false,
    val isRunning: Boolean = false,
    val vruntime: Double = 0.0,
    val eligibleTime: Double = 0.0,
    val virtualDeadline: Double = 0.0,
    val lag: Double = 0.0,
    val totalRunTime: Long = 0L,
    val runCount: Int = 0,
    val pinnedShare: Double? = null,
    val internalWeight: Double? = null,
    val schedulerClass: String = SchedTask.FAIR,
    // RT
    val rtPriority: Int = 50,
    val rtPolicy: String = "RR",
    val rtActiveDays: Int = 0,
    val rtActivationHour: Int = 0,
    val rtActivationMinute: Int = 0,
    val rtActivationSecond: Int = 0,
    val rtSliceTimeoutSeconds: Long = 0L,
    // DL
    val dlRuntimeSeconds: Long = 0L,
    val dlDeadlineSeconds: Long = 0L,
    val dlPeriodSeconds: Long = 0L,
    val dlPeriodStartEpoch: Long = 0L,
    val dlRuntimeUsedSeconds: Long = 0L,
    // Quota
    val quotaSeconds: Long = 0L,
    val quotaPeriodSeconds: Long = 86_400L,
    val quotaPeriodStartEpoch: Long = 0L,
    val quotaUsedSeconds: Long = 0L,
)

/**
 * Boundary mapper. The scheduler core stays pure; this is where columns become
 * cohesive domain value objects. Note there is no clock here — the new core
 * VOs take "now" as an explicit parameter at the point of use, so mapping is a
 * pure, total transformation with no hidden time reads.
 */
object TaskMapper {

    fun toDomain(e: TaskEntity): SchedTask = SchedTask(
        id = e.id,
        parentId = e.parentId,
        isGroup = e.isGroup,
        isCompleted = e.isCompleted,
        isRunning = e.isRunning,
        priority = e.priority,
        internalWeight = e.internalWeight,
        pinnedShare = e.pinnedShare,
        timeSliceSeconds = e.timeSliceSeconds,
        vruntime = e.vruntime,
        eligibleTime = e.eligibleTime,
        virtualDeadline = e.virtualDeadline,
        lag = e.lag,
        totalRunTime = e.totalRunTime,
        runCount = e.runCount,
        schedulerClass = e.schedulerClass,
        rt = if (e.schedulerClass == SchedTask.RT) RtConfig(
            priority = e.rtPriority,
            policy = if (e.rtPolicy == "FIFO") RtConfig.Policy.FIFO else RtConfig.Policy.RR,
            activeDaysMask = e.rtActiveDays,
            activationHour = e.rtActivationHour,
            activationMinute = e.rtActivationMinute,
            activationSecond = e.rtActivationSecond,
            sliceTimeoutSeconds = e.rtSliceTimeoutSeconds,
        ) else null,
        dl = if (e.schedulerClass == SchedTask.DL) DlBudget(
            runtimeSeconds = e.dlRuntimeSeconds,
            deadlineSeconds = e.dlDeadlineSeconds,
            periodSeconds = e.dlPeriodSeconds,
            periodStartEpochSeconds = e.dlPeriodStartEpoch / 1_000L,
            runtimeUsedSeconds = e.dlRuntimeUsedSeconds,
        ) else null,
        quota = if (e.quotaSeconds > 0L) QuotaBudget(
            quotaSeconds = e.quotaSeconds,
            periodSeconds = e.quotaPeriodSeconds,
            periodStartEpochSeconds = e.quotaPeriodStartEpoch / 1_000L,
            usedSeconds = e.quotaUsedSeconds,
        ) else null,
    )

    /** Write the scheduler's pure outputs back; all UI/runtime/sync columns are preserved. */
    fun applyScheduling(entity: TaskEntity, domain: SchedTask): TaskEntity = entity.copy(
        vruntime = domain.vruntime,
        eligibleTime = domain.eligibleTime,
        virtualDeadline = domain.virtualDeadline,
        lag = domain.lag,
        internalWeight = domain.internalWeight,
        totalRunTime = domain.totalRunTime,
        runCount = domain.runCount,
    )
}
