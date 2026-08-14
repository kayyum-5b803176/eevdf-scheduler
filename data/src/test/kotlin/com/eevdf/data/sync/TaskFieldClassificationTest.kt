package com.eevdf.data.sync

import com.eevdf.data.task.Task
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.full.primaryConstructor

/**
 * THE guard for "a new field is never classified, so sync silently blanks it".
 *
 * Adding a field to Task without adding it to [TaskFieldClassification] fails
 * the build with a message naming the field. That converts a data-loss bug into
 * a thirty-second edit.
 */
class TaskFieldClassificationTest {

    private val persistedFields: Set<String> =
        Task::class.primaryConstructor!!.parameters.mapNotNull { it.name }.toSet()

    @Test fun `every persisted Task field is classified for sync`() {
        val unclassified = persistedFields - TaskFieldClassification.KINDS.keys
        assertTrue(
            buildString {
                appendLine("${unclassified.size} Task field(s) are not classified for sync.")
                appendLine("Add each to TaskFieldClassification.KINDS as CONTENT, OPERATIONAL or IDENTITY:")
                unclassified.sorted().forEach { appendLine("  \"$it\" to SyncFieldKind.???,") }
                appendLine()
                appendLine("CONTENT     = user authored; a remote blank must not overwrite it.")
                appendLine("OPERATIONAL = timer/scheduler state; always auto-accepted.")
                appendLine("IDENTITY    = key/structure; used for matching, never merged.")
            },
            unclassified.isEmpty(),
        )
    }

    @Test fun `classification does not reference fields that no longer exist`() {
        val stale = TaskFieldClassification.KINDS.keys - persistedFields
        assertTrue(
            "TaskFieldClassification references removed Task field(s): ${stale.sorted()}",
            stale.isEmpty(),
        )
    }

    @Test fun `the fields SyncFieldGuard actually protects are all declared CONTENT`() {
        // Mirrors SyncFieldGuard's private guarded lists. If the guard is
        // widened, widen this and the classification together.
        val guardedBySyncFieldGuard = setOf(
            "name", "category", "taskType", "schedulerClass", "timeSliceSeconds", "priority",
        )
        val misclassified = guardedBySyncFieldGuard - TaskFieldClassification.contentFields
        assertTrue(
            "SyncFieldGuard protects $misclassified but they are not CONTENT",
            misclassified.isEmpty(),
        )
    }
}
