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
 * Where a definition that was placed on a PATH exists: a pattern over the doors that hold its copies.
 *
 * One segment per axis before the entry's own, joined by '/'; each is a row / definition id, or [AxisRoute.ANY]
 * (`type5/<any>` = under type5, any user). A door whose key matches the pattern shows the entry by default; every
 * other door hides it — including places created later, so a user added under type5 gets it too and a user
 * under type6 never does. An explicit rule at a door (+ / −) still wins. No row = the default (copied
 * wherever it is included). Replaces the 6.62–6.63 single '−' (the old axis_entry_hidden table, migrated).
 */
@Entity(tableName = "axis_entry_route")
data class AxisEntryRoute(@PrimaryKey val definitionId: String, val route: String)

/** Pure helpers for [AxisEntryRoute] patterns. */
object AxisRoute {
    /** A segment that matches any row or definition. */
    const val ANY = "*"

    /** Does the door key [door] ("L" or "L/y") match [route]? Same number of segments, each equal or [ANY]. */
    fun matches(route: String, door: String): Boolean {
        val r = route.split('/'); val d = door.split('/')
        return r.size == d.size && r.indices.all { r[it] == ANY || r[it] == d[it] }
    }

    /** The pattern for the ids picked on each axis before the entry's own (null = skipped = any). */
    fun of(segments: List<String?>): String = segments.joinToString("/") { it ?: ANY }

    /** Which routed entries a door shows (its door matches the route) and which it hides. */
    data class AtDoor(val shown: Set<String>, val hidden: Set<String>) {
        companion object { val NONE = AtDoor(emptySet(), emptySet()) }
    }

    fun at(routes: Map<String, String>, door: String): AtDoor {
        if (routes.isEmpty()) return AtDoor.NONE
        val shown = HashSet<String>(); val hidden = HashSet<String>()
        for ((id, route) in routes) if (matches(route, door)) shown += id else hidden += id
        return AtDoor(shown, hidden)
    }
}

@Dao
interface AxisDao {

    // ── ACL rules + door defaults ────────────────────────────────────────
    @Query("SELECT * FROM axis_acl")
    fun getAllAcl(): LiveData<List<AxisAcl>>

    @Query("SELECT * FROM axis_acl")
    suspend fun getAllAclSync(): List<AxisAcl>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRule(row: AxisAcl)

    /** Many rules in ONE transaction: one write, one refresh of everything watching the table. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRules(rows: List<AxisAcl>)

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

    // ── routed entries (placed on a path) ────────────────────────────────
    @Query("SELECT * FROM axis_entry_route")
    fun getAllEntryRoutes(): LiveData<List<AxisEntryRoute>>

    @Query("SELECT * FROM axis_entry_route")
    suspend fun getAllEntryRoutesSync(): List<AxisEntryRoute>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEntryRoute(row: AxisEntryRoute)

    @Query("DELETE FROM axis_entry_route WHERE definitionId = :definitionId")
    suspend fun deleteEntryRoute(definitionId: String)

    // ── nodes (copies) ───────────────────────────────────────────────────
    @Query("SELECT * FROM axis_node")
    fun getAllNodes(): LiveData<List<AxisNode>>

    @Query("SELECT * FROM axis_node")
    suspend fun getAllNodesSync(): List<AxisNode>

    @Query("SELECT * FROM axis_node WHERE taskId = :taskId")
    suspend fun getNodeOf(taskId: String): AxisNode?

    /** The materialized copy of [definitionId] at [scopeKey], if it was ever touched. */
    @Query("SELECT * FROM axis_node WHERE scopeKey = :scopeKey AND definitionId = :definitionId LIMIT 1")
    suspend fun getNodeAt(scopeKey: String, definitionId: String): AxisNode?

    @Query("SELECT * FROM axis_node WHERE definitionId = :definitionId")
    suspend fun getNodesOfDefinition(definitionId: String): List<AxisNode>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNode(node: AxisNode)

    @Query("DELETE FROM axis_node WHERE taskId = :taskId")
    suspend fun deleteNodeOf(taskId: String)

    // ── containers ───────────────────────────────────────────────────────
    @Query("SELECT * FROM axis_container")
    fun getAllContainers(): LiveData<List<AxisContainer>>

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
     * [hiddenHere] / [shownHere]: routed entries ([AxisEntryRoute]) whose pattern does not / does match THIS door
     * ([AxisRoute.at]). A routed entry is shown where its route matches and hidden everywhere else, unless this
     * door has a rule of its own for it. Both empty = the plain hardlink rules.
     */
    fun resolve(
        ancestorKeys: List<String>, key: String, rules: Map<String, Boolean>, hideNew: Boolean,
        hiddenHere: Set<String> = emptySet(), shownHere: Set<String> = emptySet(),
    ): AclResolution {
        val own = rules[key]
        val choice = when (own) { null -> AclChoice.INHERIT; true -> AclChoice.HIDE; false -> AclChoice.SHOW }
        ancestorKeys.forEachIndexed { depth, ancestor ->
            val ancestorHidden = rules[ancestor] ?: when {
                ancestor in shownHere  -> false
                ancestor in hiddenHere -> true
                else                   -> depth == 0 && hideNew
            }
            if (ancestorHidden) return AclResolution(choice, hidden = true, lockedByKey = ancestor)
        }
        val inheritedHidden = when {
            key in shownHere  -> false
            key in hiddenHere -> true
            else              -> ancestorKeys.isEmpty() && hideNew
        }
        return AclResolution(choice, hidden = own ?: inheritedHidden, lockedByKey = null)
    }

    /** Is [entry] hidden at this door? Walks its parents inside [defs] (cycle-safe) and resolves it. */
    fun isHidden(
        entry: Task, defs: Map<String, Task>, rules: Map<String, Boolean>, hideNew: Boolean,
        hiddenHere: Set<String> = emptySet(), shownHere: Set<String> = emptySet(),
    ): Boolean {
        val chain = ArrayList<String>()
        var cur: Task? = entry.parentId?.let { defs[it] }
        val seen = HashSet<String>().apply { add(entry.id) }
        while (cur != null && seen.add(cur.id)) { chain.add(cur.id); cur = cur.parentId?.let { defs[it] } }
        return resolve(chain.asReversed(), entry.id, rules, hideNew, hiddenHere, shownHere).hidden
    }

    /** The definition entries of [axisId] that exist at one door. */
    fun includedEntries(
        axisId: String, all: List<Task>, rules: Map<String, Boolean>, hideNew: Boolean,
        hiddenHere: Set<String> = emptySet(), shownHere: Set<String> = emptySet(),
    ): List<Task> {
        val defs = all.asSequence().filter { it.axisId == axisId }.associateBy { it.id }
        return defs.values.filter { !isHidden(it, defs, rules, hideNew, hiddenHere, shownHere) }
    }

    /** A copy's effective rules: the entry's, with the copy's own rule winning per key. */
    fun merge(entryRules: Map<String, Boolean>, copyRules: Map<String, Boolean>): Map<String, Boolean> =
        entryRules + copyRules

    /**
     * A door's "hide new" default: its own if it set one, else its entry's, else the global default ([global], a setting,
     * ON out of the box — so a new entry is not copied anywhere by itself).
     */
    fun effectiveHideNew(copyHideNew: Boolean?, entryHideNew: Boolean?, global: Boolean): Boolean = copyHideNew ?: entryHideNew ?: global

    /** Did the door (or its entry) set "hide new" itself? Only then does it also hide copies that already exist. */
    fun isExplicitHideNew(copyHideNew: Boolean?, entryHideNew: Boolean?): Boolean = copyHideNew != null || entryHideNew != null
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

    /**
     * [hideNew] decides which NEW copies are made. [hideNewForExisting] decides which EXISTING copies stay — it is the
     * same unless "hide new" came only from the global default, in which case pass false: a default hides NEW entries,
     * it never removes a copy that is already there. (A switch the person set on a door does hide existing ones, as it
     * does for a hardlink's ACL.)
     */
    fun plan(
        axisId: String, defs: List<Task>, rules: Map<String, Boolean>, hideNew: Boolean, existing: List<Existing>,
        hiddenHere: Set<String> = emptySet(), shownHere: Set<String> = emptySet(),
        hideNewForExisting: Boolean = hideNew,
    ): Plan {
        val forCreate = AxisAclRules.includedEntries(axisId, defs, rules, hideNew, hiddenHere, shownHere)
        val forKeep   = if (hideNewForExisting == hideNew) forCreate
                        else AxisAclRules.includedEntries(axisId, defs, rules, hideNewForExisting, hiddenHere, shownHere)
        val keepById  = forKeep.associateBy { it.id }
        val have      = existing.associateBy { it.node.definitionId }
        val createById = forCreate.associateBy { it.id }

        val create   = forCreate.filter { it.id !in have }.sortedBy { depth(it, createById) }
        val staying  = existing.filter { it.node.definitionId in keepById }
        val remove   = existing.filter { it.node.definitionId !in keepById }
        return Plan(
            create   = create,
            remove   = remove,
            keep     = staying.map { it to keepById.getValue(it.node.definitionId) },
            // What WILL exist here: the copies that stay and the ones about to be made. (Not everything the door could
            // show if "hide new" were off — that must not turn an empty place into a container.)
            included = staying.map { keepById.getValue(it.node.definitionId) } + create,
        )
    }

    /**
     * The definitions of [axisId] that SHOULD have a copy at this door but do not have one yet, parents first — what
     * [plan] calls `create`, without needing the copies that exist. These are the VIRTUAL copies ([AxisVirtualEngine]):
     * they cost nothing until something touches them.
     */
    fun missing(
        axisId: String, defs: List<Task>, rules: Map<String, Boolean>, hideNew: Boolean, haveDefinitionIds: Set<String>,
        hiddenHere: Set<String> = emptySet(), shownHere: Set<String> = emptySet(),
    ): List<Task> {
        val want = AxisAclRules.includedEntries(axisId, defs, rules, hideNew, hiddenHere, shownHere)
        val byId = want.associateBy { it.id }
        return want.filter { it.id !in haveDefinitionIds }.sortedBy { depth(it, byId) }
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
/** Can the row hold the next axis's entries (is it a "place")? Pure, so the x1 / x2 / x3 case is a test. */
object AxisPlaceRule {
    /**
     * @param storedIsGroup the stored row's `isGroup`, or null when there is no stored row (an UNTOUCHED copy)
     * @param isContainer   a leaf that became a container because touched copies hang under it
     * @param untouchedDefinitionIsGroup for an untouched copy: whether its DEFINITION is a group (null = not an untouched copy)
     * @param shownIsGroup  what the screen drew — a place with untouched entries is drawn as a group, so it never decides
     *                      when anything better is known
     */
    fun isPlaceLike(storedIsGroup: Boolean?, isContainer: Boolean, untouchedDefinitionIsGroup: Boolean?, shownIsGroup: Boolean): Boolean = when {
        storedIsGroup != null            -> !storedIsGroup || isContainer
        untouchedDefinitionIsGroup != null -> !untouchedDefinitionIsGroup     // the definition says what the copy is
        else                              -> !shownIsGroup || isContainer
    }
}

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
 * PATH MODE of the parent group picker: place a task in a group on any axis, and say WHERE it exists by
 * picking route entries on the axes above — so it is not copied everywhere.
 *
 * Tap any axis: it lists its groups, like the normal parent picker. The DEEPEST axis with a pick is the
 * anchor — the picked group is the task's parent, on that axis. Every axis above it is the ROUTE and lists
 * leaf nodes only (so they work as they do today); a route axis may be skipped, which means "any". An axis
 * lists groups while no deeper axis has a pick and leaves once one does, so it switches by itself.
 *
 * The route is stored as a pattern ([AxisRoute]) — `type5/<any>` is "under type5, any user" — so places added
 * later match it by themselves, and other places can still add the task with '+' in their ACL tab.
 *
 * Pure: no Android, no database — the picker and the tests feed it plain lists.
 */
object AxisPath {

    /** One selectable entry. [group]: a real group on its axis (a leaf that holds copies is not one). */
    data class Choice(val task: Task, val group: Boolean)

    /** What the picker lists, read once from the database. */
    data class Snapshot(
        /** Real first-axis rows (no copies), active. */
        val firstAxisRows: List<Task>,
        /** Active definitions of every later axis. */
        val definitions: List<Task>,
        /** Rows that are containers only because copies hang under them — leaves, not groups. */
        val containers: Set<String>,
    )

    /**
     * Where the task goes: on axis [axisIndex], inside group [parentId]. [route] is the pattern of the doors it
     * exists at (null = no narrowing: wherever it is included). [labels] are the picks' names, "any" for a skipped axis.
     */
    data class Placement(val axisIndex: Int, val parentId: String, val route: String?, val labels: List<String>)

    sealed class Result {
        data class Ok(val placement: Placement) : Result()
        data class Invalid(val reason: String) : Result()
    }

    /** [picks] after dropping what can no longer stand, and the axes that were dropped (in the order they went). */
    data class Normalized(val picks: List<Choice?>, val cleared: List<String>)

    /** Does axis [axisIndex] list groups right now? Yes while no deeper axis has a pick; once one does it is a route and lists leaves. */
    fun wantsGroups(axisIndex: Int, picks: List<Choice?>): Boolean = picks.drop(axisIndex + 1).none { it != null }

    /**
     * What axis [axisIndex] lists: all its groups, or — when a deeper axis has a pick — all its leaf nodes. Never
     * a mix, and not narrowed by the other picks (a route axis may be skipped, so any entry can be picked first).
     * The row being edited and everything inside it are left out. Interrupt and completed rows are not offered.
     */
    fun choices(axes: List<String>, axisIndex: Int, picks: List<Choice?>, s: Snapshot, exclude: String? = null): List<Choice> {
        val axis = axes.getOrNull(axisIndex) ?: return emptyList()
        val groups = wantsGroups(axisIndex, picks)
        val all: List<Choice> = if (axisIndex == 0) {
            s.firstAxisRows.filter { !it.isInterrupt && !it.isCompleted }
                .map { Choice(it, group = it.isGroup && it.id !in s.containers) }
        } else {
            s.definitions.filter { it.axisId == axis }.map { Choice(it, group = it.isGroup) }
        }
        val visible = all.filter { it.group == groups }
        val kept = AxisMove.withoutSubtree(visible.map { it.task }, exclude).mapTo(HashSet()) { it.id }
        return visible.filter { it.task.id in kept }.sortedBy { it.task.name.lowercase() }
    }

    /**
     * Keep the picks consistent after a change: the deepest pick is the anchor and must be a group (a leaf there
     * is dropped), and every pick above it is a route entry and must be a leaf (a group there is dropped).
     */
    fun normalize(axes: List<String>, picks: List<Choice?>): Normalized {
        val out = picks.toMutableList()
        val cleared = ArrayList<String>()
        var deepest = out.indexOfLast { it != null }
        while (deepest >= 0 && !out[deepest]!!.group) {
            cleared += axes[deepest]; out[deepest] = null
            deepest = out.indexOfLast { it != null }
        }
        for (j in 0 until deepest) {
            val p = out[j]
            if (p != null && p.group) { cleared += axes[j]; out[j] = null }
        }
        return Normalized(out, cleared)
    }

    /** Turn the picks (indexed by axis, null = nothing picked / any) into a placement, or say what is wrong. */
    fun resolve(axes: List<String>, picks: List<Choice?>): Result {
        val anchorIndex = picks.indexOfLast { it != null }
        if (anchorIndex < 0) return Result.Invalid("Pick a group.")
        val anchor = picks[anchorIndex]!!
        if (!anchor.group) return Result.Invalid("Pick a group on ${axes[anchorIndex].uppercase()}.")
        for (j in 0 until anchorIndex) {
            val p = picks[j] ?: continue
            if (p.group) return Result.Invalid("${axes[j].uppercase()} · ${p.task.name} is a group — a route entry must be a task.")
        }
        val segments = (0 until anchorIndex).map { picks[it]?.task?.id }
        val route = if (segments.all { it == null }) null else AxisRoute.of(segments)
        val labels = (0 until anchorIndex).map { picks[it]?.task?.name ?: "any" } + anchor.task.name
        return Result.Ok(Placement(anchorIndex, anchor.task.id, route, labels))
    }
}

/**
 * What the "copy it?" prompt offers, and the rules that carry it out. Nothing is copied without a '+': the prompt
 * only decides whether those '+' rules are written. Pure.
 */
object AxisCopy {

    enum class Kind { ENTRY, PLACE }

    /**
     * [ENTRY]: a new later-axis entry can be copied into [count] places. [PLACE]: a new place can receive [count]
     * existing entries. [rules] are the '+' that do it; [axisId] is the axis the entries / places are on.
     */
    data class Offer(val kind: Kind, val name: String, val axisId: String, val rules: List<AxisAcl>) {
        val count: Int get() = rules.size
    }

    /**
     * The doors a NEW entry can be copied into: every place ([places] = door keys) — or, for an entry inside a group,
     * only the places where a copy of that group exists ([copiesByDoor]: door -> definition ids copied there),
     * because a child cannot appear where its group does not.
     */
    fun eligibleDoors(places: List<String>, parentId: String?, copiesByDoor: Map<String, Set<String>>): List<String> =
        if (parentId == null) places else places.filter { parentId in copiesByDoor[it].orEmpty() }

    fun entryRules(entryId: String, doors: List<String>): List<AxisAcl> = doors.map { AxisAcl(it, entryId, hidden = false) }

    /** A new place [placeId] receives the entries [entryIds]: one '+' per entry at the place's own door. */
    fun placeRules(placeId: String, entryIds: List<String>): List<AxisAcl> = entryIds.map { AxisAcl(placeId, it, hidden = false) }
}
