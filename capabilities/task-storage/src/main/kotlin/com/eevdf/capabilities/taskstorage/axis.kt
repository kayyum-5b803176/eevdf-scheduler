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
 *  - Everything is included by default. What an outer row includes is an ACL, the same
 *    one a hardlinked group has ([MembershipAcl]): per definition entry, at any depth,
 *    Inherit / Hide / Show, inherited downward, with a per-row "hide new" default
 *    ([AxisAcl], [AxisDoor], [AxisAclRules]). Every row that has a next axis has one — an
 *    X leaf for Y, each Y copy (its own, not shared) for Z. Deleting a copy by hand is a
 *    Hide, so it does not come back.
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

    /** Prefix of a DEFINITION's own door: the ACL a later-axis entry sets for ALL its copies ("def:<id>"). */
    const val DEFINITION_DOOR = "def:"

    fun definitionDoor(definitionId: String): String = DEFINITION_DOOR + definitionId

    /**
     * The definition-level door a COPY's door layers over ("L/a" → "def:a"). Null for a first-axis
     * row's door ("L") and for a definition's own door — neither has anything underneath it to follow.
     */
    fun layerDoorOf(door: String): String? =
        if (!door.startsWith(DEFINITION_DOOR) && '/' in door) definitionDoor(door.substringAfterLast('/')) else null

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
 * One ACL rule of one door: whether definition entry [childKey] is hidden (true) or shown
 * (false) under [scopeKey]. No row = Inherit. Exactly [MembershipAcl], for axes.
 *
 * The door is the OUTER row whose copies these entries become, named by its scope key
 * ([AxisReconcile.childScope]): a first-axis leaf's own id ("L"), or a copy's path ("L/a").
 * The path, not the copy's row id, so the rules survive that copy being switched off and on.
 * Every copy has its own door, so two copies of the same entry can include different things.
 * A later-axis ENTRY also has a door of its own ("def:<id>", see [Axes.definitionDoor]): the ACL
 * for ALL its copies, edited once. A copy's door is layered over it — the copy's own rule wins,
 * no rule follows the entry's, and so does the "hide new" default ([AxisAclRules.merge]).
 *
 * Rules sit on ANY entry of the definition tree, inherit downward, and the deepest rule wins;
 * hiding a group hides everything inside it and nothing inside a hidden group can be shown.
 */
@Entity(tableName = "axis_acl", primaryKeys = ["scopeKey", "childKey"])
data class AxisAcl(
    val scopeKey: String,
    val childKey: String,
    val hidden: Boolean,
)

/**
 * The door's default for DIRECT entries that have no rule of their own ([TaskMembership.newChildrenHidden]
 * for hardlinks). `true` = "hide new": only what is Shown appears, and entries defined later do
 * not appear here by themselves. No row = false (everything included — today's behaviour).
 */
@Entity(tableName = "axis_door")
data class AxisDoor(
    @PrimaryKey val scopeKey: String,
    val newChildrenHidden: Boolean,
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

    // ── ACL rules + door defaults ────────────────────────────────────────
    @Query("SELECT * FROM axis_acl")
    fun getAllAcl(): LiveData<List<AxisAcl>>

    @Query("SELECT * FROM axis_acl")
    suspend fun getAllAclSync(): List<AxisAcl>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRule(row: AxisAcl)

    /** Back to Inherit: no row. */
    @Query("DELETE FROM axis_acl WHERE scopeKey = :scopeKey AND childKey = :childKey")
    suspend fun deleteRule(scopeKey: String, childKey: String)

    @Query("SELECT * FROM axis_door")
    fun getAllDoors(): LiveData<List<AxisDoor>>

    @Query("SELECT * FROM axis_door")
    suspend fun getAllDoorsSync(): List<AxisDoor>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDoor(row: AxisDoor)

    @Query("DELETE FROM axis_door WHERE scopeKey = :scopeKey")
    suspend fun deleteDoor(scopeKey: String)

    /** A definition entry is deleted: its rule goes from every door. */
    @Query("DELETE FROM axis_acl WHERE childKey = :childKey")
    suspend fun deleteAclOfChild(childKey: String)

    /** An outer row / entry [id] is deleted: every door it opened, at any depth, goes with its rules. */
    @Query("DELETE FROM axis_acl WHERE scopeKey = :id OR scopeKey LIKE :id || '/%' OR scopeKey LIKE '%/' || :id || '/%' OR scopeKey LIKE '%/' || :id")
    suspend fun deleteAclUnder(id: String)

    @Query("DELETE FROM axis_door WHERE scopeKey = :id OR scopeKey LIKE :id || '/%' OR scopeKey LIKE '%/' || :id || '/%' OR scopeKey LIKE '%/' || :id")
    suspend fun deleteDoorsUnder(id: String)

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
    @Query("SELECT COUNT(*) FROM axis_container WHERE taskId = :taskId")
    suspend fun containerCount(taskId: String): Int

    @Query("SELECT * FROM axis_container")
    suspend fun getAllContainersSync(): List<AxisContainer>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContainer(row: AxisContainer)

    @Query("DELETE FROM axis_container WHERE taskId = :taskId")
    suspend fun deleteContainerOf(taskId: String)
}

/**
 * The ACL rules for axes — pure, and deliberately the SAME semantics as [MembershipAclRules.resolve]
 * (a test asserts they agree), so a hardlink's ACL and an axis door behave identically:
 *
 *  - an entry's own rule wins; with none it inherits;
 *  - a direct entry (no ancestor) inherits the door's "hide new" default;
 *  - a deeper entry inherits "shown" — the only way to hide the inside of a group without a rule
 *    of its own is to hide the group, which LOCKS everything inside it.
 */
object AxisAclRules {

    /** [rules]: childKey -> hidden for ONE door. [ancestorKeys]: the entry's ancestors, outermost first. */
    fun resolve(ancestorKeys: List<String>, key: String, rules: Map<String, Boolean>, hideNew: Boolean): AclResolution {
        val own = rules[key]
        val choice = when (own) { null -> AclChoice.INHERIT; true -> AclChoice.HIDE; false -> AclChoice.SHOW }
        ancestorKeys.forEachIndexed { depth, ancestor ->
            val ancestorHidden = rules[ancestor] ?: (depth == 0 && hideNew)
            if (ancestorHidden) return AclResolution(choice, hidden = true, lockedByKey = ancestor)
        }
        val inheritedHidden = ancestorKeys.isEmpty() && hideNew
        return AclResolution(choice, hidden = own ?: inheritedHidden, lockedByKey = null)
    }

    /** A copy's effective rules: the entry's, with the copy's own rule winning per key. */
    fun merge(entryRules: Map<String, Boolean>, copyRules: Map<String, Boolean>): Map<String, Boolean> =
        entryRules + copyRules

    /** A copy's own default if it set one, else the entry's, else everything included. */
    fun effectiveHideNew(copyHideNew: Boolean?, entryHideNew: Boolean?): Boolean = copyHideNew ?: entryHideNew ?: false

    /** Is [entry] hidden at this door? Walks its parents inside [defs] (cycle-safe) and resolves it. */
    fun isHidden(entry: Task, defs: Map<String, Task>, rules: Map<String, Boolean>, hideNew: Boolean): Boolean {
        val chain = ArrayList<String>()
        var cur: Task? = entry.parentId?.let { defs[it] }
        val seen = HashSet<String>().apply { add(entry.id) }
        while (cur != null && seen.add(cur.id)) { chain.add(cur.id); cur = cur.parentId?.let { defs[it] } }
        return resolve(chain.asReversed(), entry.id, rules, hideNew).hidden
    }

    /** The definition entries of [axisId] that exist at one door. */
    fun includedEntries(axisId: String, all: List<Task>, rules: Map<String, Boolean>, hideNew: Boolean): List<Task> {
        val defs = all.asSequence().filter { it.axisId == axisId }.associateBy { it.id }
        return defs.values.filter { !isHidden(it, defs, rules, hideNew) }
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
     * @param remove   copies whose definition is no longer included (hidden by the door's ACL or deleted)
     * @param keep     copies to refresh, with the definition they follow
     * @param included every definition included here (its size is the number of copies wanted)
     */
    data class Plan(
        val create: List<Task>,
        val remove: List<Existing>,
        val keep: List<Pair<Existing, Task>>,
        val included: List<Task>,
    )

    fun plan(axisId: String, defs: List<Task>, rules: Map<String, Boolean>, hideNew: Boolean, existing: List<Existing>): Plan {
        val included = AxisAclRules.includedEntries(axisId, defs, rules, hideNew)
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

/**
 * What an edit screen needs to know about the row it was opened from.
 *
 * [taskId]/[axisId]  what the Config tab edits — for a copy, its DEFINITION.
 * [door]             the ACL the ACL tab edits, or null for no tab: a first-axis leaf's own door, a
 *                    copy's own door, or a definition's "def:" door. See [AxisReconcile.childScope].
 * [doorAxis]         the axis that door lists (the one after this row's).
 * [layerDoor]        for a copy: the entry's door its own rules are layered over; else null.
 */
data class AxisEditTarget(
    val taskId: String,
    val axisId: String,
    val door: String?,
    val doorAxis: String?,
    val layerDoor: String?,
    val isCopy: Boolean,
)
