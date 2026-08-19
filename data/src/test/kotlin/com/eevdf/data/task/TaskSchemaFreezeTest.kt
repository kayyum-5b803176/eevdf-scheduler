package com.eevdf.data.task

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.full.primaryConstructor

/**
 * THE FREEZE. `tasks` may not get any wider.
 *
 * WHY THIS IS THE MOST IMPORTANT TEST IN THE REPO
 * -----------------------------------------------
 * 51 columns and 21 migrations exist because every feature ever added became
 * columns on one shared row. That is the single mechanism by which a new
 * feature breaks a working one here: adding a column forces edits to Task,
 * TaskDatabase, TaskDao, TaskRepository, BackupManager, SyncFieldGuard and
 * every UI file that maps the entity — six shared files, six chances to break
 * something unrelated, on every single feature.
 *
 * The other guards in this repo DETECT the fallout (a field missing from
 * backup, a field unclassified for sync). This one prevents the cause.
 *
 * WHAT TO DO WHEN THIS TEST FAILS
 * -------------------------------
 * It is not asking you to abandon the feature. It is asking you to put its data
 * in its own table. See docs/SIDE_TABLE_TEMPLATE.md — a copy-paste vertical
 * slice: entity, DAO, migration, repository. Roughly twenty minutes, and it
 * costs the same for feature 60 as for feature 6, which widening does not.
 *
 * THE NARROW EXCEPTION
 * --------------------
 * A field the scheduler reasons about on EVERY tick (a `vruntime`-class value,
 * read inside tickQuotaOnVisibleItems on a scrolling RecyclerView). A join on
 * that path costs real frame time. If you genuinely have one: add it to
 * FROZEN_FIELDS, say so in the PR, and explain why a side table would not do.
 * Editing this list should feel like a decision, which is the entire point.
 */
class TaskSchemaFreezeTest {

    private companion object {
        /**
         * The 51 columns as of v4.5.x. This list is a contract, not a mirror —
         * do not regenerate it to make the build pass.
         */
        val FROZEN_FIELDS = setOf(
            // identity / structure
            "id", "name", "description", "priority", "timeSliceSeconds", "category", "color",
            "parentId", "isGroup", "isGroupExpanded", "createdAt",
            // EEVDF scheduler state
            "vruntime", "eligibleTime", "virtualDeadline", "lag", "internalWeight", "pinnedShare",
            "schedulerClass",
            // timer / run state
            "remainingSeconds", "isRunning", "isCompleted", "totalRunTime", "runCount",
            "accumulatedMs", "startTimeEpoch",
            // interrupt
            "isInterrupt", "interruptSlot",
            // notice / notification task type
            "taskType", "notificationDelaySeconds", "notificationRestSeconds",
            "notificationRepeatCount", "notificationResumeType",
            // quota budget
            "quotaSeconds", "quotaPeriodSeconds", "quotaPeriodStartEpoch", "quotaUsedSeconds",
            // deadline budget
            "dlRuntimeSeconds", "dlDeadlineSeconds", "dlPeriodSeconds", "dlPeriodStartEpoch",
            "dlRuntimeUsedSeconds",
            // realtime window
            "rtPriority", "rtPolicy", "rtActiveDays", "rtActivationHour", "rtActivationMinute",
            "rtActivationSecond", "rtSliceTimeoutSeconds",
            // load tracking
            "loadFactor", "loadFactorInherited", "loadAverage", "loadLastUpdateEpoch",
        )
    }

    private val actual: Set<String> =
        Task::class.primaryConstructor!!.parameters.mapNotNull { it.name }.toSet()

    @Test fun `tasks table has not been widened`() {
        val added = actual - FROZEN_FIELDS
        assertTrue(
            buildString {
                appendLine()
                appendLine("=================================================================")
                appendLine(" The `tasks` table is FROZEN. ${added.size} new field(s) were added:")
                added.sorted().forEach { appendLine("   - $it") }
                appendLine()
                appendLine(" Put this feature's data in its own table instead.")
                appendLine(" Copy-paste slice: docs/SIDE_TABLE_TEMPLATE.md")
                appendLine()
                appendLine(" Why: a new column forces edits to Task, TaskDatabase, TaskDao,")
                appendLine(" TaskRepository, BackupManager, SyncFieldGuard and the UI mappers.")
                appendLine(" A new table forces edits to none of them.")
                appendLine()
                appendLine(" Exception — a value the scheduler reads on EVERY tick. If that is")
                appendLine(" genuinely what this is, add it to FROZEN_FIELDS and justify it in")
                appendLine(" the PR. Do not regenerate the list to go green.")
                appendLine("=================================================================")
            },
            added.isEmpty(),
        )
    }

    @Test fun `no frozen field was removed without updating the freeze list`() {
        val removed = FROZEN_FIELDS - actual
        assertTrue(
            "Field(s) removed from Task but still listed in FROZEN_FIELDS: ${removed.sorted()}. " +
                "Removing a column is fine — drop it from FROZEN_FIELDS too, and make sure the " +
                "migration that removes it preserves the rest of the row.",
            removed.isEmpty(),
        )
    }

    @Test fun `freeze list and entity agree exactly`() {
        assertTrue(
            "expected ${FROZEN_FIELDS.size} columns, entity has ${actual.size}",
            FROZEN_FIELDS.size == actual.size,
        )
    }
}
