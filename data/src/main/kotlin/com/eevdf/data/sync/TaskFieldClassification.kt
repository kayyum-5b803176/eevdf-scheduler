package com.eevdf.data.sync

/**
 * Single source of truth for how each persisted [com.eevdf.data.task.Task]
 * field behaves during a multi-user sync.
 *
 * WHY THIS EXISTS
 * ---------------
 * [SyncFieldGuard] used to carry its own private, hand-maintained list of
 * guarded fields. Nothing forced that list to stay in step with the entity, so
 * a developer adding a content field would ship a version where a remote peer
 * could silently overwrite it with a blank. That is a new feature breaking an
 * old one, with no compiler and no test to catch it.
 *
 * Now every field must be declared here as exactly one of:
 *
 *   CONTENT     - authored by the user. A remote blank/zero must NOT overwrite a
 *                 local non-blank value; the guard raises a conflict instead.
 *   OPERATIONAL - scheduler/timer runtime state. Always auto-accepted from the
 *                 remote snapshot; never worth a conflict prompt.
 *   IDENTITY    - primary key / structural. Used to match rows, never merged.
 *
 * TaskFieldClassificationTest fails the build if any Task field is missing from
 * this map, so classification is mandatory rather than optional.
 */
enum class SyncFieldKind { IDENTITY, CONTENT, OPERATIONAL }

object TaskFieldClassification {

    val KINDS: Map<String, SyncFieldKind> = mapOf(
        // ── Identity / structure ──────────────────────────────────────────────
        "id" to SyncFieldKind.IDENTITY,
        "parentId" to SyncFieldKind.IDENTITY,
        "isGroup" to SyncFieldKind.IDENTITY,
        "createdAt" to SyncFieldKind.IDENTITY,

        // ── User-authored content (blank-overwrite protected) ─────────────────
        "name" to SyncFieldKind.CONTENT,
        "description" to SyncFieldKind.CONTENT,
        "priority" to SyncFieldKind.CONTENT,
        "timeSliceSeconds" to SyncFieldKind.CONTENT,
        "category" to SyncFieldKind.CONTENT,
        "color" to SyncFieldKind.CONTENT,
        "taskType" to SyncFieldKind.CONTENT,
        "schedulerClass" to SyncFieldKind.CONTENT,
        "isInterrupt" to SyncFieldKind.CONTENT,
        "interruptSlot" to SyncFieldKind.CONTENT,
        "notificationDelaySeconds" to SyncFieldKind.CONTENT,
        "notificationRestSeconds" to SyncFieldKind.CONTENT,
        "notificationRepeatCount" to SyncFieldKind.CONTENT,
        "notificationResumeType" to SyncFieldKind.CONTENT,
        "pinnedShare" to SyncFieldKind.CONTENT,
        "quotaSeconds" to SyncFieldKind.CONTENT,
        "quotaPeriodSeconds" to SyncFieldKind.CONTENT,
        "dlRuntimeSeconds" to SyncFieldKind.CONTENT,
        "dlDeadlineSeconds" to SyncFieldKind.CONTENT,
        "dlPeriodSeconds" to SyncFieldKind.CONTENT,
        "rtPriority" to SyncFieldKind.CONTENT,
        "rtPolicy" to SyncFieldKind.CONTENT,
        "rtActiveDays" to SyncFieldKind.CONTENT,
        "rtActivationHour" to SyncFieldKind.CONTENT,
        "rtActivationMinute" to SyncFieldKind.CONTENT,
        "rtActivationSecond" to SyncFieldKind.CONTENT,
        "rtSliceTimeoutSeconds" to SyncFieldKind.CONTENT,

        // ── Runtime / scheduler state (always auto-accepted) ──────────────────
        "isGroupExpanded" to SyncFieldKind.OPERATIONAL,
        "vruntime" to SyncFieldKind.OPERATIONAL,
        "eligibleTime" to SyncFieldKind.OPERATIONAL,
        "virtualDeadline" to SyncFieldKind.OPERATIONAL,
        "lag" to SyncFieldKind.OPERATIONAL,
        "remainingSeconds" to SyncFieldKind.OPERATIONAL,
        "isRunning" to SyncFieldKind.OPERATIONAL,
        "isCompleted" to SyncFieldKind.OPERATIONAL,
        "totalRunTime" to SyncFieldKind.OPERATIONAL,
        "runCount" to SyncFieldKind.OPERATIONAL,
        "accumulatedMs" to SyncFieldKind.OPERATIONAL,
        "startTimeEpoch" to SyncFieldKind.OPERATIONAL,
        "internalWeight" to SyncFieldKind.OPERATIONAL,
        "quotaPeriodStartEpoch" to SyncFieldKind.OPERATIONAL,
        "quotaUsedSeconds" to SyncFieldKind.OPERATIONAL,
        "dlPeriodStartEpoch" to SyncFieldKind.OPERATIONAL,
        "dlRuntimeUsedSeconds" to SyncFieldKind.OPERATIONAL,
        "loadFactor"          to SyncFieldKind.OPERATIONAL,
        "loadFactorInherited" to SyncFieldKind.OPERATIONAL,
        "loadAverage" to SyncFieldKind.OPERATIONAL,
        "loadLastUpdateEpoch" to SyncFieldKind.OPERATIONAL,
    )

    fun kindOf(field: String): SyncFieldKind? = KINDS[field]

    val contentFields: Set<String>
        get() = KINDS.filterValues { it == SyncFieldKind.CONTENT }.keys
}
