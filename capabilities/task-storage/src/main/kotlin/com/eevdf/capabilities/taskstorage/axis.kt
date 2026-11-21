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

/**
 * The ONE '−' rule of a definition that was placed on a path: "hidden everywhere", except where a door
 * says Show. Without it a new later-axis entry is copied into every place by default; with it only the
 * doors that Show it (the path it was placed on, and any the person later adds with '+') get a copy —
 * including places created afterwards. Nothing has to be deleted or edited elsewhere. Absent = the
 * default (everywhere). See [AxisAclRules] and [AxisPath].
 */
@Entity(tableName = "axis_entry_hidden")
data class AxisEntryHidden(@PrimaryKey val definitionId: String)

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

    // ── entries hidden everywhere (the single '−') ───────────────────────
    @Query("SELECT * FROM axis_entry_hidden")
    fun getAllEntryHidden(): LiveData<List<AxisEntryHidden>>

    @Query("SELECT * FROM axis_entry_hidden")
    suspend fun getAllEntryHiddenSync(): List<AxisEntryHidden>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntryHidden(row: AxisEntryHidden)

    @Query("DELETE FROM axis_entry_hidden WHERE definitionId = :definitionId")
    suspend fun deleteEntryHidden(definitionId: String)

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

    /**
     * [rules]: childKey -> hidden for ONE door. [ancestorKeys]: the entry's ancestors, outermost first.
     * [hiddenEverywhere]: entries carrying the single '−' ([AxisEntryHidden]) — hidden at every door that
     * has no rule of its own for them, exactly as if that door said Hide. Empty = the plain hardlink rules.
     */
    fun resolve(
        ancestorKeys: List<String>, key: String, rules: Map<String, Boolean>, hideNew: Boolean,
        hiddenEverywhere: Set<String> = emptySet(),
    ): AclResolution {
        val own = rules[key]
        val choice = when (own) { null -> AclChoice.INHERIT; true -> AclChoice.HIDE; false -> AclChoice.SHOW }
        ancestorKeys.forEachIndexed { depth, ancestor ->
            val ancestorHidden = rules[ancestor] ?: (ancestor in hiddenEverywhere || (depth == 0 && hideNew))
            if (ancestorHidden) return AclResolution(choice, hidden = true, lockedByKey = ancestor)
        }
        val inheritedHidden = key in hiddenEverywhere || (ancestorKeys.isEmpty() && hideNew)
        return AclResolution(choice, hidden = own ?: inheritedHidden, lockedByKey = null)
    }

    /** Is [entry] hidden at this door? Walks its parents inside [defs] (cycle-safe) and resolves it. */
    fun isHidden(
        entry: Task, defs: Map<String, Task>, rules: Map<String, Boolean>, hideNew: Boolean,
        hiddenEverywhere: Set<String> = emptySet(),
    ): Boolean {
        val chain = ArrayList<String>()
        var cur: Task? = entry.parentId?.let { defs[it] }
        val seen = HashSet<String>().apply { add(entry.id) }
        while (cur != null && seen.add(cur.id)) { chain.add(cur.id); cur = cur.parentId?.let { defs[it] } }
        return resolve(chain.asReversed(), entry.id, rules, hideNew, hiddenEverywhere).hidden
    }

    /** The definition entries of [axisId] that exist at one door. */
    fun includedEntries(
        axisId: String, all: List<Task>, rules: Map<String, Boolean>, hideNew: Boolean,
        hiddenEverywhere: Set<String> = emptySet(),
    ): List<Task> {
        val defs = all.asSequence().filter { it.axisId == axisId }.associateBy { it.id }
        return defs.values.filter { !isHidden(it, defs, rules, hideNew, hiddenEverywhere) }
    }

    /** A copy's effective rules: the entry's, with the copy's own rule winning per key. */
    fun merge(entryRules: Map<String, Boolean>, copyRules: Map<String, Boolean>): Map<String, Boolean> =
        entryRules + copyRules

    /** A copy's own default if it set one, else the entry's, else everything included. */
    fun effectiveHideNew(copyHideNew: Boolean?, entryHideNew: Boolean?): Boolean = copyHideNew ?: entryHideNew ?: false
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

    fun plan(
        axisId: String, defs: List<Task>, rules: Map<String, Boolean>, hideNew: Boolean, existing: List<Existing>,
        hiddenEverywhere: Set<String> = emptySet(),
    ): Plan {
        val included = AxisAclRules.includedEntries(axisId, defs, rules, hideNew, hiddenEverywhere)
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

/**
 * A task or group becomes a TEMPLATE when it moves onto a later axis: it is never scheduled itself, so
 * everything that records "how it has been running" is meaningless and is reset. Configuration is kept.
 */
fun Task.asTemplate(): Task = copy(
    vruntime = 0.0, eligibleTime = 0.0, virtualDeadline = 0.0, lag = 0.0,
    remainingSeconds = timeSliceSeconds, isRunning = false, isCompleted = false,
    totalRunTime = 0L, runCount = 0, accumulatedMs = 0L, startTimeEpoch = 0L,
    quotaPeriodStartEpoch = 0L, quotaUsedSeconds = 0L,
    dlPeriodStartEpoch = 0L, dlRuntimeUsedSeconds = 0L,
    loadAverage = 0.0, loadLastUpdateEpoch = 0L,
    loadAvgCognitive = 0.0, loadLastUpdateCognitive = 0L,
    loadAvgPhysical = 0.0, loadLastUpdatePhysical = 0L,
    loadAvgEmotional = 0.0, loadLastUpdateEmotional = 0L,
    sleepUntilEpoch = 0L, sleepLag = 0.0, semaphoreHeld = false, semaphoreQueuedAt = 0L,
)

/** What moving a row to another axis would cost — shown before it is done. */
data class AxisMovePreview(
    /** Rows that move: the row itself, or the group and everything real inside it. */
    val rowsMoved: Int,
    /** Copies that go: copies hanging under the moved rows, and copies of the moved definitions elsewhere. */
    val copiesRemoved: Int,
    /** Hardlinks and symlinks that point at, or are hosted in, the moved rows. */
    val linksRemoved: Int,
    /** Moving onto a later axis resets timers, run counts and completion (a template never runs). */
    val resetsRuntime: Boolean,
    /** Something in the moved rows is running — it must be stopped first. */
    val blockedByRunning: Boolean,
) {
    val hasLoss: Boolean get() = copiesRemoved > 0 || linksRemoved > 0 || resetsRuntime

    companion object { val NONE = AxisMovePreview(0, 0, 0, false, false) }
}

/** Pure part of moving a row (and what is inside it) to another axis. */
object AxisMove {

    /** [real]: the row and every real row inside it (completed ones too). [copies]: copies hanging inside it. */
    data class Collected(val real: List<Task>, val copies: List<Task>)

    /**
     * Everything under [root] in [all] (any axis, completed rows included), split into real rows, which
     * move with it, and copies of later-axis definitions, which cannot — a copy follows its definition
     * and is simply removed. Cycle-safe.
     */
    fun collect(root: Task, all: List<Task>, copyIds: Set<String>): Collected {
        val byParent = all.groupBy { it.parentId }
        val real = arrayListOf(root)
        val copies = ArrayList<Task>()
        val seen = hashSetOf(root.id)
        val queue = ArrayDeque<String>().apply { add(root.id) }
        while (queue.isNotEmpty()) {
            for (child in byParent[queue.removeFirst()].orEmpty()) {
                if (!seen.add(child.id)) continue
                if (child.id in copyIds) copies += child else real += child
                queue.add(child.id)
            }
        }
        return Collected(real, copies)
    }

    /** [groups] minus [rootId] and everything inside it: where a row may NOT be moved (under itself). */
    fun withoutSubtree(groups: List<Task>, rootId: String?): List<Task> {
        if (rootId == null) return groups
        val byParent = groups.groupBy { it.parentId }
        val banned = hashSetOf(rootId)
        val queue = ArrayDeque<String>().apply { add(rootId) }
        while (queue.isNotEmpty()) for (c in byParent[queue.removeFirst()].orEmpty()) if (banned.add(c.id)) queue.add(c.id)
        return groups.filter { it.id !in banned }
    }
}

/**
 * PATH MODE of the parent group picker: pick at most one entry per axis, in one go, and the task is
 * placed in that path ONLY.
 *
 * By default a later-axis entry is copied into every place that includes it. A path says where it
 * should exist: the entry gets the single '−' ([AxisEntryHidden], "hidden everywhere") and a '+' on the
 * one door that was picked. Nothing is copied elsewhere, so there is nothing to delete afterwards; any
 * other place can add it later with '+' in its own ACL tab.
 *
 * The axes before the last one are the ROUTE: each lists only entries that can hold the next axis
 * (holders — never groups mixed in), and together they say where the task exists. The LAST axis is the
 * ordinary parent picker: it lists groups only. Picks are contiguous from the first axis. The DEEPEST pick
 * decides what the task is:
 *  - a GROUP is the task's parent group, on that group's axis;
 *  - a HOLDER (a leaf with the next axis's entries hanging under it) means "inside it": the task becomes
 *    an entry of the NEXT axis, at its top level.
 * Every shallower pick must be a holder — it names the place the task exists in.
 *
 * Pure: no Android, no database — the picker and the tests feed it plain lists.
 */
object AxisPath {

    /** One selectable entry. [holder]: a leaf that holds the next axis's entries (false = a group). */
    data class Choice(val task: Task, val holder: Boolean)

    /** What the picker needs to list each axis; read once from the database. */
    data class Snapshot(
        /** Real first-axis rows (no copies), active. */
        val firstAxisRows: List<Task>,
        /** Active definitions of every later axis. */
        val definitions: List<Task>,
        val acl: List<AxisAcl>,
        val doors: List<AxisDoor>,
        val hiddenEverywhere: Set<String>,
        /** Rows that are containers only because copies hang under them (leaves, not groups). */
        val containers: Set<String>,
    )

    /** Where the task goes. [axisIndex] indexes the axis list; [door] is where the '+' is written (null = no narrowing). */
    data class Placement(val axisIndex: Int, val parentId: String?, val door: String?, val labels: List<String>)

    sealed class Result {
        data class Ok(val placement: Placement) : Result()
        data class Invalid(val reason: String) : Result()
    }

    /**
     * What axis [axisIndex] offers, given the picks on the axes before it ([picks] is indexed by axis).
     * The first axis offers its real rows; a later axis offers only the entries that EXIST at the door the
     * earlier picks name (so a path can never name a place that is not there). The last axis offers groups
     * only — a leaf there holds nothing. Empty when an earlier axis has no pick. [exclude] (the row being
     * edited) and everything inside it are left out: a row cannot be placed under itself.
     */
    fun choices(axes: List<String>, axisIndex: Int, picks: List<Choice?>, s: Snapshot, exclude: String? = null): List<Choice> {
        val axis = axes.getOrNull(axisIndex) ?: return emptyList()
        val isLast = axisIndex == axes.lastIndex
        val list: List<Choice> = if (axisIndex == 0) {
            s.firstAxisRows.filter { !it.isInterrupt && !it.isCompleted }
                .map { Choice(it, holder = !it.isGroup || it.id in s.containers) }
        } else {
            val prior = picks.take(axisIndex)
            if (prior.size < axisIndex || prior.any { it == null }) return emptyList()
            val door = Axes.scopeKey(prior.map { it!!.task.id })
            val layer = Axes.layerDoorOf(door)
            val ownRules = s.acl.asSequence().filter { it.scopeKey == door }.associate { it.childKey to it.hidden }
            val entryRules = layer?.let { l -> s.acl.asSequence().filter { it.scopeKey == l }.associate { it.childKey to it.hidden } }.orEmpty()
            val hideNew = AxisAclRules.effectiveHideNew(
                s.doors.firstOrNull { it.scopeKey == door }?.newChildrenHidden,
                layer?.let { l -> s.doors.firstOrNull { it.scopeKey == l }?.newChildrenHidden },
            )
            AxisAclRules.includedEntries(axis, s.definitions, AxisAclRules.merge(entryRules, ownRules), hideNew, s.hiddenEverywhere)
                .map { Choice(it, holder = !it.isGroup && !isLast) }
        }
        // Never a mix: the last axis acts as the plain parent picker (groups only); every axis before it is a
        // route (holders only — a group cannot hold the next axis's entries).
        val visible = if (isLast) list.filter { it.task.isGroup } else list.filter { it.holder }
        val kept = AxisMove.withoutSubtree(visible.map { it.task }, exclude).mapTo(HashSet()) { it.id }
        return visible.filter { it.task.id in kept }.sortedBy { it.task.name.lowercase() }
    }

    /** Turn the picks (indexed by axis, null = nothing picked) into a placement, or say what is wrong. */
    fun resolve(axes: List<String>, picks: List<Choice?>): Result {
        val deepest = picks.indexOfLast { it != null }
        if (deepest < 0) return Result.Invalid("Pick where it should exist.")
        for (i in 0 until deepest) {
            val p = picks[i] ?: return Result.Invalid("Pick ${axes[i].uppercase()} first.")
            if (!p.holder) return Result.Invalid("${axes[i].uppercase()} · ${p.task.name} is a group — only the last pick can be one.")
        }
        val last = picks[deepest]!!
        val labels = picks.take(deepest + 1).map { it!!.task.name }
        return if (!last.holder) {
            // A group: the task's parent group on that axis; the picks before it say where it exists.
            Result.Ok(Placement(deepest, last.task.id,
                if (deepest == 0) null else Axes.scopeKey(picks.take(deepest).map { it!!.task.id }), labels))
        } else {
            // A holder: "inside it" — an entry of the next axis, at its top level, existing only under this path.
            if (deepest + 1 > axes.lastIndex) return Result.Invalid("Pick a group on ${axes[deepest].uppercase()}.")
            Result.Ok(Placement(deepest + 1, null, Axes.scopeKey(picks.take(deepest + 1).map { it!!.task.id }), labels))
        }
    }
}
