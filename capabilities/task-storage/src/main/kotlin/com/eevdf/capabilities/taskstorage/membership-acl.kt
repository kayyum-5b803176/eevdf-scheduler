package com.eevdf.capabilities.taskstorage

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.eevdf.capabilities.taskstorage.scheduling.EEVDFScheduler
import com.eevdf.capabilities.taskstorage.scheduling.MEMBERSHIP_SYNTHETIC_PREFIX

/**
 * One visibility RULE for one item inside a hardlinked group, as seen through
 * ONE hardlink placement ([TaskMembership]) of that group.
 *
 * MULTI-LEVEL. A rule can sit on ANY item below the placement's group, at any
 * depth, and is inherited downward: an item with no rule follows the nearest
 * rule above it, and above all rules sits [TaskMembership.newChildrenHidden]
 * (which applies to the group's DIRECT children only). The deepest rule wins.
 *
 * The three states of an item are therefore:
 *   - NO row            → Inherit
 *   - row `hidden=true`  → Hide
 *   - row `hidden=false` → Show
 *
 * Hiding an item hides its whole subtree through this placement, and nothing
 * under a hidden item can be shown again (Show is only meaningful on an item
 * whose parent chain is visible). Hiding never deletes or changes the item: it
 * still shows in its real location and in every other placement. Only the view
 * through [membershipId] changes.
 *
 * [childKey] names the item (see [MembershipAclKeys]): a real task/group uses
 * its own task id, a hardlink placement or symlink HOSTED in a group uses a
 * prefixed key. Keys are not paths: a task has exactly one real parent, so its
 * id is already unique within a placement's subtree (and survives moves). A
 * hosted hardlink placement is a new door of its own — its inside is governed
 * by ITS rules, so rules for keys inside it are never stored under this one.
 */
@Entity(tableName = "membership_acl", primaryKeys = ["membershipId", "childKey"])
data class MembershipAcl(
    val membershipId: String,
    val childKey: String,
    val hidden: Boolean,
)

/** What the user picks for one item: follow the parent, force hidden, force shown. */
enum class AclChoice { INHERIT, HIDE, SHOW }

@Dao
interface MembershipAclDao {

    @Query("SELECT * FROM membership_acl")
    fun getAll(): LiveData<List<MembershipAcl>>

    @Query("SELECT * FROM membership_acl")
    suspend fun getAllSync(): List<MembershipAcl>

    @Query("SELECT * FROM membership_acl WHERE membershipId = :membershipId")
    suspend fun getForMembership(membershipId: String): List<MembershipAcl>

    @Query("SELECT * FROM membership_acl WHERE childKey = :childKey")
    suspend fun getForChildKey(childKey: String): List<MembershipAcl>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: MembershipAcl)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<MembershipAcl>)

    /** Back to Inherit: no row. */
    @Query("DELETE FROM membership_acl WHERE membershipId = :membershipId AND childKey = :childKey")
    suspend fun deleteRule(membershipId: String, childKey: String)

    // ── Cleanup ───────────────────────────────────────────────────────────────
    // A rule is meaningless once its placement (the door) or its item is gone.
    // Every delete path in TaskRepository calls the matching query below BEFORE
    // it deletes the task_memberships / task_links rows these subqueries read
    // from. The literal 'm:' mirrors MembershipAclKeys.MEMBERSHIP_PREFIX
    // (asserted equal in MembershipAclRulesTest).

    /** The placement itself was deleted: drop its whole ACL. */
    @Query("DELETE FROM membership_acl WHERE membershipId = :membershipId")
    suspend fun deleteByMembership(membershipId: String)

    /** The item was deleted: drop its rule under every door, at any depth. */
    @Query("DELETE FROM membership_acl WHERE childKey = :childKey")
    suspend fun deleteByChildKey(childKey: String)

    /** Every placement OF [taskId] is about to go: drop their ACLs. */
    @Query("DELETE FROM membership_acl WHERE membershipId IN (SELECT id FROM task_memberships WHERE taskId = :taskId)")
    suspend fun deleteByMembershipsOfTask(taskId: String)

    /** Every placement HOSTED IN [groupId] is about to go: drop their ACLs. */
    @Query("DELETE FROM membership_acl WHERE membershipId IN (SELECT id FROM task_memberships WHERE groupId = :groupId)")
    suspend fun deleteByMembershipsHostedIn(groupId: String)

    /** Every placement of [taskId] is about to go: drop the rows that named them as hosted items elsewhere. */
    @Query("DELETE FROM membership_acl WHERE childKey IN (SELECT 'm:' || id FROM task_memberships WHERE taskId = :taskId)")
    suspend fun deleteChildKeysOfMembershipsOfTask(taskId: String)
}

/**
 * Keys used in [MembershipAcl.childKey].
 *
 * Real items use the raw task id (UUIDs, so a prefixed key can never collide
 * with one). Hardlink placements and symlinks HOSTED inside a group are also
 * items of it, and get prefixed keys.
 */
object MembershipAclKeys {
    const val MEMBERSHIP_PREFIX = "m:"
    const val SYMLINK_PREFIX    = "l:"

    fun real(taskId: String): String       = taskId
    fun membership(membershipId: String)   = MEMBERSHIP_PREFIX + membershipId
    fun symlink(linkId: String)            = SYMLINK_PREFIX + linkId

    /**
     * Key for one entry of an "effective" task list (see
     * [EEVDFScheduler.withMemberships]): a synthetic hardlink-placement entry
     * maps to its membership key, a real task to its own id.
     */
    fun ofEntry(entry: Task): String =
        if (entry.id.startsWith(MEMBERSHIP_SYNTHETIC_PREFIX))
            membership(entry.id.removePrefix(MEMBERSHIP_SYNTHETIC_PREFIX))
        else real(entry.id)
}

/** How one item resolves through one door — what the ACL page shows for its row. */
data class AclResolution(
    /** What is stored on the item itself. */
    val choice: AclChoice,
    /** The effective result through the door (true = hidden). */
    val hidden: Boolean,
    /** Key of the outermost HIDDEN ancestor, or null. Non-null locks the row: nothing can be shown under it. */
    val lockedByKey: String?,
)

object MembershipAclRules {

    /**
     * Is the DIRECT child [childKey] of the placement's group hidden through
     * [membership]? An explicit row wins; with no row the child follows
     * [TaskMembership.newChildrenHidden]. (For deeper items see [resolve].)
     */
    fun isHidden(membership: TaskMembership, childKey: String, aclRows: List<MembershipAcl>): Boolean {
        val explicit = aclRows.firstOrNull { it.membershipId == membership.id && it.childKey == childKey }
        return explicit?.hidden ?: membership.newChildrenHidden
    }

    /**
     * Resolves one item through [door].
     *
     * [ancestorKeys] are the keys of the item's ancestors BELOW the door's
     * group, outermost first (empty for a direct child of the group). An item
     * with no rule inherits: at depth 1 that is the door's default, deeper
     * down it is "shown" — a hidden parent is the only way to hide the inside
     * of a group without a rule of its own, and that case is [lockedByKey].
     */
    fun resolve(door: TaskMembership, ancestorKeys: List<String>, key: String, aclRows: List<MembershipAcl>): AclResolution {
        val rules = HashMap<String, Boolean>()
        for (r in aclRows) if (r.membershipId == door.id) rules[r.childKey] = r.hidden

        val own = rules[key]
        val choice = when (own) { null -> AclChoice.INHERIT; true -> AclChoice.HIDE; false -> AclChoice.SHOW }
        ancestorKeys.forEachIndexed { depth, ancestor ->
            val ancestorHidden = rules[ancestor] ?: (depth == 0 && door.newChildrenHidden)
            if (ancestorHidden) return AclResolution(choice, hidden = true, lockedByKey = ancestor)
        }
        val inheritedHidden = ancestorKeys.isEmpty() && door.newChildrenHidden
        return AclResolution(choice, hidden = own ?: inheritedHidden, lockedByKey = null)
    }

    /**
     * Would [taskId], viewed through the door [membership], be hidden — i.e.
     * is it, or any real ancestor of it below the door's group, hidden? Walks
     * real `parentId` edges up from [taskId] until it reaches a direct child
     * of [TaskMembership.taskId], then evaluates that chain outermost first.
     *
     * False when [taskId] isn't inside the door's group at all (including
     * [taskId] == the door's own group: the door itself is never "hidden by
     * itself"), and false on a cyclic parent chain rather than looping.
     *
     * [parentOf] resolves a task id to its real parent id (null at the root
     * or for an unknown id).
     */
    fun isTaskHiddenThroughDoor(
        taskId: String,
        membership: TaskMembership,
        aclRows: List<MembershipAcl>,
        parentOf: (String) -> String?,
    ): Boolean {
        val chain = ArrayList<String>()   // [taskId, its parent, …, the direct child of the group]
        var current = taskId
        val seen = HashSet<String>()
        while (seen.add(current)) {
            chain.add(current)
            val parent = parentOf(current) ?: return false
            if (parent == membership.taskId) {
                for (i in chain.indices.reversed()) {
                    val direct = i == chain.lastIndex
                    val rule = aclRows.firstOrNull {
                        it.membershipId == membership.id && it.childKey == MembershipAclKeys.real(chain[i])
                    }?.hidden
                    if (rule ?: (direct && membership.newChildrenHidden)) return true
                }
                return false
            }
            current = parent
        }
        return false
    }
}

/**
 * Per-rebuild view of every door's rules, used by the list builders.
 *
 * Build ONE per list rebuild from the memberships + rules that rebuild is
 * using, then ask it, at each place a group's children are enumerated while a
 * door is active: which children are visible ([visibleChildren] /
 * [visibleLinks]), which real items to leave out of row counts and runtime
 * rollups ([hiddenRealIds]), and what the sibling CPU shares are ([sharesFor]).
 *
 * THE DOOR RULE. The door in effect is the NEAREST hardlink placement above
 * the level being listed (the builders already thread it down). Every level
 * below it is filtered by that door's rules; only the group's DIRECT children
 * also fall back to [TaskMembership.newChildrenHidden]. A hidden item takes its
 * whole subtree with it, so a level is never listed under a hidden parent.
 * Cost when nothing is hidden anywhere ([isInert]): every function returns its
 * input unchanged.
 */
class DoorAcl(memberships: List<TaskMembership>, aclRows: List<MembershipAcl>) {

    private val membershipsById: Map<String, TaskMembership> = memberships.associateBy { it.id }
    private val explicit: Map<String, Map<String, Boolean>> =
        aclRows.groupBy { it.membershipId }.mapValues { (_, rows) -> rows.associate { it.childKey to it.hidden } }

    /** True when nothing can be hidden anywhere — callers and this class skip all work. */
    val isInert: Boolean = memberships.none { it.newChildrenHidden } && aclRows.none { it.hidden }

    private val shareCache  = HashMap<String, Map<String, Double>>()
    private val hiddenCache = HashMap<String, Set<String>>()

    /**
     * Is the item [childKey] hidden through [door]? [direct] = it is a direct
     * child of the door's group (the only level the door's default applies to).
     */
    fun isHidden(door: TaskMembership, childKey: String, direct: Boolean): Boolean =
        explicit[door.id]?.get(childKey) ?: (direct && door.newChildrenHidden)

    /**
     * The door whose rules apply when listing the children of [groupRealId]
     * while [doorId] is the active door; null when none does (nothing hidden
     * anywhere, no door, an unknown door, or the root level).
     */
    fun doorFor(doorId: String?, groupRealId: String?): TaskMembership? {
        if (isInert || doorId == null || groupRealId == null) return null
        return membershipsById[doorId]
    }

    /** [children] (real and/or synthetic placement entries of [groupRealId]) minus the ones hidden through [doorId]. */
    fun visibleChildren(children: List<Task>, doorId: String?, groupRealId: String?): List<Task> {
        val door = doorFor(doorId, groupRealId) ?: return children
        val direct = door.taskId == groupRealId
        return children.filterNot { isHidden(door, MembershipAclKeys.ofEntry(it), direct) }
    }

    /** [links] hosted in [groupRealId] minus the symlinks hidden through [doorId]. */
    fun visibleLinks(links: List<TaskLink>, doorId: String?, groupRealId: String?): List<TaskLink> {
        val door = doorFor(doorId, groupRealId) ?: return links
        val direct = door.taskId == groupRealId
        return links.filterNot { isHidden(door, MembershipAclKeys.symlink(it.id), direct) }
    }

    /**
     * Ids of every REAL task hidden through [doorId], at any depth below its
     * group (a hidden group's own descendants are not listed separately — they
     * are simply never reached). Used to keep them out of descendant counts and
     * `childTotalRuntime` for every row listed under that door. Cached per door
     * for this instance's lifetime ([tasks] must be the same on every call).
     */
    fun hiddenRealIds(doorId: String?, tasks: List<Task>): Set<String> {
        if (isInert || doorId == null) return emptySet()
        val door = membershipsById[doorId] ?: return emptySet()
        return hiddenCache.getOrPut(door.id) {
            val byParent = tasks.groupBy { it.parentId }
            val out = HashSet<String>()
            val seen = HashSet<String>()
            val stack = ArrayDeque<Pair<String, Boolean>>()   // (group id, is the door's own group)
            stack.add(door.taskId to true)
            while (stack.isNotEmpty()) {
                val (groupId, direct) = stack.removeLast()
                if (!seen.add(groupId)) continue
                for (child in byParent[groupId].orEmpty()) {
                    if (isHidden(door, MembershipAclKeys.real(child.id), direct)) out.add(child.id)
                    else stack.add(child.id to false)
                }
            }
            out
        }
    }

    /**
     * CPU shares for rows listed under [doorId] at [groupRealId]'s level.
     *
     * With nothing hidden at that level this returns [globalShares] itself.
     * Otherwise the hidden entries are left out of that ONE pool and
     * [EEVDFScheduler.computeShares] is re-run over the visible siblings, so
     * they split the pool as if the hidden ones never existed. A hidden item's
     * OWN pinned/realtime share, quota and schedule class are never touched —
     * it simply isn't in the pool.
     *
     * Shares are per level (each parent's children sum to 100), so hiding an
     * item can only change the level it sits in; the recomputed pool is laid
     * OVER [globalShares], which keeps a value for every other id.
     *
     * Cached per door+level for this instance's lifetime — [effectiveTasks]
     * and [globalShares] must be the same on every call, which holds within
     * one list rebuild.
     */
    fun sharesFor(
        doorId: String?,
        groupRealId: String?,
        effectiveTasks: List<Task>,
        globalShares: Map<String, Double>,
    ): Map<String, Double> {
        val door = doorFor(doorId, groupRealId) ?: return globalShares
        return shareCache.getOrPut(door.id + "|" + groupRealId) {
            val direct = door.taskId == groupRealId
            val entries = effectiveTasks.filter { it.parentId == groupRealId }
            val visible = entries.filterNot { isHidden(door, MembershipAclKeys.ofEntry(it), direct) }
            if (visible.size == entries.size) globalShares
            else globalShares + EEVDFScheduler.computeShares(visible, groupsEnabled = false)
        }
    }
}
