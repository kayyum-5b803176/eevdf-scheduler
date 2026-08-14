package com.eevdf.data.backup

import com.eevdf.data.task.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.KParameter
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/**
 * THE guard for "a new field silently disappears from backup".
 *
 * This test does not hardcode a field list. It reflects over [Task]'s primary
 * constructor, fills EVERY persisted field with a distinct sentinel value,
 * pushes it through the real export -> import path, and asserts every field
 * came back unchanged.
 *
 * Consequence: the day someone adds field #52 to Task and forgets to add it to
 * BackupManager.taskToJson / taskFromJson, THIS TEST GOES RED. No code review
 * discipline required, no checklist to remember.
 *
 * Sentinels are derived from each parameter's index, so two fields of the same
 * type never share a value - that catches copy-paste bugs where a field is
 * written to the wrong JSON key as well as fields that are simply missing.
 */
class BackupRoundTripCoverageTest {

    /** Fields deliberately NOT preserved by a regular backup (live timer state). */
    private val resetByDesign = setOf("isRunning", "accumulatedMs", "startTimeEpoch")

    private fun sentinel(param: KParameter, index: Int): Any? {
        val i = index + 1
        val type = param.type.classifier
        val nullable = param.type.isMarkedNullable
        return when (type) {
            String::class -> "sentinel_${param.name}_$i"
            Long::class -> 1_000_000L + i
            Int::class -> 1_000 + i
            Double::class -> if (nullable) 10.0 + i else 100.0 + i * 0.5
            Boolean::class -> i % 2 == 0
            else -> error("Unhandled Task field type for '${param.name}': ${param.type}. Add a sentinel rule.")
        }
    }

    private fun fullyPopulatedTask(): Task {
        val ctor = requireNotNull(Task::class.primaryConstructor) { "Task must be a data class" }
        val args = ctor.parameters.withIndex().associate { (i, p) -> p to sentinel(p, i) }
        return ctor.callBy(args)
    }

    @Test fun `every persisted Task field survives a backup export-import round trip`() {
        val original = fullyPopulatedTask()

        val restored = BackupManager
            .importTasksJson(BackupManager.exportTasksJson(listOf(original)))
            .single()

        val ctorNames = Task::class.primaryConstructor!!.parameters.mapNotNull { it.name }.toSet()
        val props = Task::class.memberProperties.filter { it.name in ctorNames }

        val missing = props
            .filter { it.name !in resetByDesign }
            .mapNotNull { prop ->
                val before = prop.get(original)
                val after = prop.get(restored)
                if (before != after) "${prop.name}: expected <$before> but was <$after>" else null
            }

        assertTrue(
            buildString {
                appendLine("${missing.size} Task field(s) did not survive backup round trip.")
                appendLine("Add them to BackupManager.taskToJson AND taskFromJson:")
                missing.forEach { appendLine("  - $it") }
            },
            missing.isEmpty(),
        )
    }

    @Test fun `live timer state is deliberately cleared by a regular backup`() {
        val running = fullyPopulatedTask().copy(isRunning = true, accumulatedMs = 5_000L, startTimeEpoch = 99L)
        val restored = BackupManager
            .importTasksJson(BackupManager.exportTasksJson(listOf(running)))
            .single()

        assertEquals("restoring a backup must never resurrect a running timer", false, restored.isRunning)
        assertEquals(0L, restored.accumulatedMs)
        assertEquals(0L, restored.startTimeEpoch)
    }

    @Test fun `sync export preserves live timer state`() {
        val running = fullyPopulatedTask().copy(isRunning = true, accumulatedMs = 5_000L, startTimeEpoch = 99L)
        val restored = BackupManager.fromSyncJson(BackupManager.toSyncJson(listOf(running))).tasks.single()

        assertEquals("sync must carry live state, unlike backup", true, restored.isRunning)
        assertEquals(5_000L, restored.accumulatedMs)
        assertEquals(99L, restored.startTimeEpoch)
    }

    @Test fun `sync round trip also preserves every other field`() {
        val original = fullyPopulatedTask()
        val restored = BackupManager.fromSyncJson(BackupManager.toSyncJson(listOf(original))).tasks.single()

        val ctorNames = Task::class.primaryConstructor!!.parameters.mapNotNull { it.name }.toSet()
        val broken = Task::class.memberProperties
            .filter { it.name in ctorNames }
            .mapNotNull { p -> if (p.get(original) != p.get(restored)) p.name else null }

        assertTrue("sync dropped field(s): $broken", broken.isEmpty())
    }

    @Test fun `manifest declares the archive contents`() {
        val manifest = BackupManager.manifestJson(taskCount = 3)
        listOf("database.db", "tasks.json", "settings.json", "eevdf-backup").forEach {
            assertTrue("manifest must mention $it", manifest.contains(it))
        }
    }

    @Test fun `empty backup round trips to an empty list`() {
        assertTrue(BackupManager.importTasksJson(BackupManager.exportTasksJson(emptyList())).isEmpty())
    }
}
