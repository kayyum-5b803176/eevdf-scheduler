package com.eevdf.capabilities.taskstorage

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * AXES — the generic dimensions a task/group can live on.
 *
 * Nothing in the code may assume what an axis MEANS. An axis is only an id:
 * `x`, `y`, `z`, and later any other id appended to [Axes.ALL]. "User",
 * "activity", "process" are explanations in a design discussion, never code.
 *
 * Model (see the design notes in the project history):
 *  - Every axis has ONE definition tree: ordinary [Task] rows whose
 *    [Task.axisId] is that axis (x rows are every row that existed before axes).
 *    Edit a definition once and every instance sees it.
 *  - Selection descends in [Axes.ALL] order. The first axis resolves its whole
 *    hierarchy to a leaf; INSIDE that leaf the next axis resolves its own
 *    hierarchy to a leaf; and so on. Each pass orders only its own siblings
 *    with its own scheduler classes, so nothing starves at any count.
 *  - The next axis's definition entries are, by default, ALL included under
 *    every outer leaf. The user switches entries off per outer leaf
 *    ([AxisExclusion]); only the exclusions are stored.
 *  - An included entry has its OWN runtime accounting per outer leaf
 *    ([AxisInstance]); it is created on first run, never up front.
 */
object Axes {
    const val X = "x"
    const val Y = "y"
    const val Z = "z"

    /** Descent order. Add an axis by appending its id here — nothing else assumes a count. */
    val ALL: List<String> = listOf(X, Y, Z)

    /** The axis every pre-existing row belongs to. */
    const val DEFAULT = X

    fun isKnown(axisId: String): Boolean = axisId in ALL

    /** The axis picked right after [axisId], or null when [axisId] is the last. */
    fun next(axisId: String): String? = ALL.getOrNull(ALL.indexOf(axisId) + 1)

    /**
     * The scope key of an instance: the ids of the leaves picked on every outer
     * axis, outermost first, joined by '/'. The first axis has no scope ("").
     * e.g. inside x-leaf `L` the y scope is "L"; inside y-leaf `M` under it the
     * z scope is "L/M".
     */
    fun scopeKey(pickedLeafIds: List<String>): String = pickedLeafIds.joinToString("/")
}

/**
 * "[outerKey] does NOT include definition entry [entryId] of axis [axisId]."
 *
 * Absent row = included (the default). [outerKey] is the scope key of the
 * outer leaf the switch belongs to (see [Axes.scopeKey]).
 *
 * Same idea as [MembershipAcl], kept separate on purpose: that one filters
 * what is VISIBLE through a hardlink on one axis; this one decides which
 * definition entries of ANOTHER axis exist under an outer leaf at all.
 * Switching a group off removes its whole subtree (children follow the parent),
 * see [AxisRules.isIncluded].
 */
@Entity(tableName = "axis_exclusion", primaryKeys = ["outerKey", "axisId", "entryId"])
data class AxisExclusion(
    val outerKey: String,
    val axisId: String,
    val entryId: String,
)

/**
 * Private runtime state of ONE definition entry inside ONE scope.
 *
 * Properties (quota size, share, class, slice…) always come from the shared
 * definition [Task]; only what CHANGES while running lives here, so two
 * outer leaves running the same definition never affect each other.
 *
 * Absent row = fresh instance (all zero); created on first credited run.
 */
@Entity(tableName = "axis_instance", primaryKeys = ["scopeKey", "entryId"])
data class AxisInstance(
    val scopeKey: String,
    val entryId: String,
    val vruntime: Double = 0.0,
    val quotaUsedSeconds: Long = 0L,
    val quotaPeriodStartEpoch: Long = 0L,
    val dlPeriodStartEpoch: Long = 0L,
    val dlRuntimeUsedSeconds: Long = 0L,
    val totalRunTime: Long = 0L,
    val runCount: Int = 0,
)

@Dao
interface AxisDao {

    // ── exclusions ───────────────────────────────────────────────────────
    @Query("SELECT * FROM axis_exclusion")
    fun getAllExclusions(): LiveData<List<AxisExclusion>>

    @Query("SELECT * FROM axis_exclusion")
    suspend fun getAllExclusionsSync(): List<AxisExclusion>

    @Query("SELECT * FROM axis_exclusion WHERE outerKey = :outerKey AND axisId = :axisId")
    suspend fun getExclusions(outerKey: String, axisId: String): List<AxisExclusion>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun exclude(row: AxisExclusion)

    @Query("DELETE FROM axis_exclusion WHERE outerKey = :outerKey AND axisId = :axisId AND entryId = :entryId")
    suspend fun include(outerKey: String, axisId: String, entryId: String)

    /** Cascade: an outer leaf is deleted — every switch that belonged to it (any depth) goes. */
    @Query("DELETE FROM axis_exclusion WHERE outerKey = :id OR outerKey LIKE :id || '/%' OR outerKey LIKE '%/' || :id || '/%' OR outerKey LIKE '%/' || :id")
    suspend fun deleteExclusionsUnder(id: String)

    /** Cascade: a definition entry is deleted — nobody needs to exclude it any more. */
    @Query("DELETE FROM axis_exclusion WHERE entryId = :entryId")
    suspend fun deleteExclusionsOf(entryId: String)

    // ── instances ────────────────────────────────────────────────────────
    @Query("SELECT * FROM axis_instance WHERE scopeKey = :scopeKey")
    suspend fun getInstances(scopeKey: String): List<AxisInstance>

    @Query("SELECT * FROM axis_instance")
    suspend fun getAllInstancesSync(): List<AxisInstance>

    @Query("SELECT * FROM axis_instance WHERE scopeKey = :scopeKey AND entryId = :entryId")
    suspend fun getInstance(scopeKey: String, entryId: String): AxisInstance?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertInstance(row: AxisInstance)

    @Query("DELETE FROM axis_instance WHERE entryId = :entryId")
    suspend fun deleteInstancesOf(entryId: String)

    @Query("DELETE FROM axis_instance WHERE scopeKey = :id OR scopeKey LIKE :id || '/%' OR scopeKey LIKE '%/' || :id || '/%' OR scopeKey LIKE '%/' || :id")
    suspend fun deleteInstancesUnder(id: String)
}

/** Pure rules — no Android, no database: testable with plain lists. */
object AxisRules {

    /**
     * Is definition entry [entry] included under the outer leaf whose
     * exclusions are [excludedIds]?
     *
     * Included unless the entry OR any ancestor (within [definitions], by
     * [Task.parentId]) is excluded — children follow the parent.
     */
    fun isIncluded(entry: Task, definitions: Map<String, Task>, excludedIds: Set<String>): Boolean {
        var cur: Task? = entry
        val seen = HashSet<String>()
        while (cur != null && seen.add(cur.id)) {
            if (cur.id in excludedIds) return false
            cur = cur.parentId?.let { definitions[it] }
        }
        return true
    }

    /** The definition entries of [axisId] that exist under one outer leaf. */
    fun includedEntries(
        axisId: String,
        all: List<Task>,
        excludedIds: Set<String>,
    ): List<Task> {
        val defs = all.asSequence().filter { it.axisId == axisId }.associateBy { it.id }
        return defs.values.filter { isIncluded(it, defs, excludedIds) }
    }
}
