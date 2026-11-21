package com.eevdf.capabilities.taskstorage

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * AXES — the generic dimensions a task/group can live on.
 *
 * Nothing in the code may assume what an axis MEANS. An axis is only an id:
 * `x`, `y`, `z`, and later any other id appended to [Axes.ALL]. "User",
 * "activity", "process" are explanations in a design discussion, never code.
 *
 * Model:
 *  - Every axis after the first has ONE definition tree: ordinary [Task] rows whose
 *    [Task.axisId] is that axis. A definition is a TEMPLATE — it is never scheduled
 *    itself. Every row that existed before axes is "x" (the executed pool).
 *  - A first-axis LEAF holds the next axis's included definitions as REAL child rows
 *    (axisId "x", real [Task.parentId]); each of those leaves holds the axis after
 *    that the same way. The parent turns into a container (isGroup) while it has
 *    any, exactly like a group — so the timer, Next, rotate, run history, sleep,
 *    wake, semaphore, completed, backup and sync all work on them with no special
 *    case, and a run credits the node, then every ancestor, as it does for any child.
 *  - Definitions are edited once; [AxisReconcile] keeps every copy's shared fields
 *    in step. Per-copy state (timer, vruntime, quota used, completed, sleep…) is the
 *    copy's own and is never overwritten.
 *  - Everything is included by default; the user switches entries off per outer
 *    leaf ([AxisExclusion]); only the exclusions are stored. Deleting a copy by hand
 *    is the same switch-off, so it does not come back.
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
 * Marks a real task row as a COPY of a later-axis definition.
 *
 * [taskId]       the copy (axisId "x", a real child of its parent)
 * [definitionId] the template it follows
 * [axisId]       which axis the template lives on ("y", "z", …)
 * [scopeKey]     where the copy lives: the scope its parent opens ([AxisReconcile.childScope]).
 *                (scopeKey, definitionId) is unique — one copy per definition per scope.
 */
@Entity(
    tableName = "axis_node",
    indices = [Index(value = ["scopeKey", "definitionId"], unique = true), Index(value = ["definitionId"])],
)
data class AxisNode(
    @PrimaryKey val taskId: String,
    val definitionId: String,
    val axisId: String,
    val scopeKey: String,
)

/**
 * A row that is a container ONLY because later-axis copies hang under it (it was a leaf and
 * was flipped to a group). Lets it flip back when the last copy goes. A real group is never listed.
 */
@Entity(tableName = "axis_container")
data class AxisContainer(@PrimaryKey val taskId: String)

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

    // ── nodes (copies) ───────────────────────────────────────────────────
    @Query("SELECT * FROM axis_node")
    fun getAllNodes(): LiveData<List<AxisNode>>

    @Query("SELECT * FROM axis_node")
    suspend fun getAllNodesSync(): List<AxisNode>

    @Query("SELECT * FROM axis_node WHERE taskId = :taskId")
    suspend fun getNodeOf(taskId: String): AxisNode?

    @Query("SELECT * FROM axis_node WHERE definitionId = :definitionId")
    suspend fun getNodesOfDefinition(definitionId: String): List<AxisNode>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNode(node: AxisNode)

    @Query("DELETE FROM axis_node WHERE taskId = :taskId")
    suspend fun deleteNodeOf(taskId: String)

    // ── containers ───────────────────────────────────────────────────────
    @Query("SELECT * FROM axis_container")
    suspend fun getAllContainersSync(): List<AxisContainer>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContainer(row: AxisContainer)

    @Query("DELETE FROM axis_container WHERE taskId = :taskId")
    suspend fun deleteContainerOf(taskId: String)
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

/** One row of a flattened definition tree: [entry] at nesting [depth]. */
data class AxisRow(val entry: Task, val depth: Int)

/** Pure helpers for the include/exclude checklist. */
object AxisChecklist {

    /** Depth-first, name-sorted flattening of one axis's definition tree. */
    fun flatten(defs: List<Task>): List<AxisRow> {
        val byParent = defs.groupBy { it.parentId?.takeIf { p -> defs.any { d -> d.id == p } } }
        val out = ArrayList<AxisRow>(defs.size)
        fun walk(parent: String?, depth: Int) {
            byParent[parent].orEmpty().sortedBy { it.name.lowercase() }.forEach { t ->
                out += AxisRow(t, depth)
                if (t.isGroup) walk(t.id, depth + 1)
            }
        }
        walk(null, 0)
        return out
    }

    /**
     * From a checklist state (id -> checked), the minimal exclusion set to store:
     * an unchecked entry whose parent is NOT already unchecked. Children follow
     * their parent, so they need no row of their own.
     */
    fun topmostExcluded(defs: List<Task>, checked: Map<String, Boolean>): Set<String> {
        val byId = defs.associateBy { it.id }
        return defs.filter { checked[it.id] == false }
            .filter { t -> t.parentId?.let { byId.containsKey(it) && checked[it] == false } != true }
            .mapTo(HashSet()) { it.id }
    }
}

/**
 * Keeping the real copies in step with the definitions. Pure: it only decides, the
 * repository applies. See [Axes] for the model.
 */
object AxisReconcile {

    /** A copy that already exists: its node row and its task row. */
    data class Existing(val node: AxisNode, val task: Task)

    /**
     * @param create   definitions that have no copy here yet, parents before children
     * @param remove   copies whose definition is no longer included (switched off or deleted)
     * @param keep     copies to refresh, with the definition they follow
     * @param included every definition included here (its size is the number of copies wanted)
     */
    data class Plan(
        val create: List<Task>,
        val remove: List<Existing>,
        val keep: List<Pair<Existing, Task>>,
        val included: List<Task>,
    )

    fun plan(axisId: String, defs: List<Task>, excluded: Set<String>, existing: List<Existing>): Plan {
        val included = AxisRules.includedEntries(axisId, defs, excluded)
        val inc      = included.associateBy { it.id }
        val have     = existing.associateBy { it.node.definitionId }
        return Plan(
            create   = included.filter { it.id !in have }.sortedBy { depth(it, inc) },
            remove   = existing.filter { it.node.definitionId !in inc },
            keep     = existing.filter { it.node.definitionId in inc }.map { it to inc.getValue(it.node.definitionId) },
            included = included,
        )
    }

    private fun depth(t: Task, inc: Map<String, Task>): Int {
        var d = 0; var cur: Task? = t; val seen = HashSet<String>()
        while (cur != null && seen.add(cur.id)) { cur = cur.parentId?.let { inc[it] }; if (cur != null) d++ }
        return d
    }

    /** The scope the children of [parent] live in: its own id for a first-axis row, else its node's path. */
    fun childScope(parent: Task, parentNode: AxisNode?): String =
        if (parentNode == null) parent.id else "${parentNode.scopeKey}/${parentNode.definitionId}"

    /** Where a copy of [def] hangs: under the copy of its parent definition if that is included here, else under [fallbackParentId]. */
    fun parentFor(def: Task, includedIds: Set<String>, copyOf: Map<String, String>, fallbackParentId: String): String =
        def.parentId?.takeIf { it in includedIds }?.let { copyOf[it] } ?: fallbackParentId

    /** A fresh copy of [def]: shared fields from the definition, its own runtime from zero. */
    fun newCopy(def: Task, id: String, parentId: String): Task =
        Task(id = id, name = def.name, priority = def.priority, timeSliceSeconds = def.timeSliceSeconds,
            parentId = parentId, isGroup = def.isGroup, axisId = Axes.DEFAULT)
            .sharingFrom(def)
}

/**
 * The fields a copy takes from its definition. Everything that CHANGES while the copy lives —
 * timer, vruntime, quota used, DL period, completed, sleep, semaphore slot, load average — is
 * deliberately not in this list, so editing the definition never disturbs a running copy.
 * `isGroup` and `parentId` are not here either: structure is decided by [AxisReconcile].
 * `internalWeight` is derived per sibling pool, so each copy gets its own from the pin.
 */
fun Task.sharingFrom(def: Task): Task = copy(
    name = def.name, description = def.description, priority = def.priority,
    timeSliceSeconds = def.timeSliceSeconds,
    // An untouched timer follows a new slice; a partly used one is left alone (same rule as inherited slices).
    remainingSeconds = if (remainingSeconds == timeSliceSeconds) def.timeSliceSeconds else remainingSeconds,
    timeSliceInherited = def.timeSliceInherited,
    category = def.category, color = def.color,
    taskType = def.taskType, notificationDelaySeconds = def.notificationDelaySeconds,
    notificationRestSeconds = def.notificationRestSeconds, notificationRepeatCount = def.notificationRepeatCount,
    resumeType = def.resumeType, pinnedShare = def.pinnedShare,
    quotaSeconds = def.quotaSeconds, quotaPeriodSeconds = def.quotaPeriodSeconds,
    schedulerClass = def.schedulerClass,
    dlRuntimeSeconds = def.dlRuntimeSeconds, dlDeadlineSeconds = def.dlDeadlineSeconds, dlPeriodSeconds = def.dlPeriodSeconds,
    rtPriority = def.rtPriority, rtPolicy = def.rtPolicy, rtActiveDays = def.rtActiveDays,
    rtActivationHour = def.rtActivationHour, rtActivationMinute = def.rtActivationMinute,
    rtActivationSecond = def.rtActivationSecond, rtSliceTimeoutSeconds = def.rtSliceTimeoutSeconds,
    loadFactor = def.loadFactor, loadFactorInherited = def.loadFactorInherited,
    sleepDurationSeconds = def.sleepDurationSeconds,
    semaphoreEnabled = def.semaphoreEnabled, semaphoreLimit = def.semaphoreLimit,
)
