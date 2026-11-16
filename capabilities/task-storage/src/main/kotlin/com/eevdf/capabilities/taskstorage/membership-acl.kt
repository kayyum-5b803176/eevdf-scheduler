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
 * One visibility rule for a DIRECT child of a hardlinked group, as seen
 * through ONE hardlink placement ([TaskMembership]) of that group.
 *
 * A hardlink of a group shows every real child of that group. This table lets
 * a placement hide chosen direct children — per placement, per direct child.
 * Hiding a child hides its whole subtree through that one door and changes
 * nothing about the child itself: it is not deleted, not edited, and still
 * shows in its real location and in every other placement. Only the view
 * through [membershipId] changes.
 *
 * [childKey] names the direct child (see [MembershipAclKeys]): a real child
 * task/group uses its own task id; a hardlink placement or symlink HOSTED
 * inside the group uses a prefixed key, so the three id spaces can't collide.
 *
 * An explicit row always wins. A child with NO row follows
 * [TaskMembership.newChildrenHidden] (see [MembershipAclRules.isHidden]).
 */
@Entity(tableName = "membership_acl", primaryKeys = ["membershipId", "childKey"])
data class MembershipAcl(
    val membershipId: String,
    val childKey: String,
    val hidden: Boolean,
)

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

    /** Writes only rows that don't exist yet — never overwrites an explicit choice. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMissing(rows: List<MembershipAcl>)

    // ── Cleanup ───────────────────────────────────────────────────────────────
    // An ACL row is meaningless once its placement (the door) or its child is
    // gone. Every delete path in TaskRepository calls the matching query below
    // BEFORE it deletes the task_memberships / task_links rows these subqueries
    // read from. The literal 'm:' mirrors MembershipAclKeys.MEMBERSHIP_PREFIX
    // (asserted equal in MembershipAclRulesTest).

    /** The placement itself was deleted: drop its whole ACL. */
    @Query("DELETE FROM membership_acl WHERE membershipId = :membershipId")
    suspend fun deleteByMembership(membershipId: String)

    /** The child was deleted: drop its row under every door. */
    @Query("DELETE FROM membership_acl WHERE childKey = :childKey")
    suspend fun deleteByChildKey(childKey: String)

    /** [childKey] stopped being a direct child of [groupTaskId]: drop its row under that group's doors only. */
    @Query(
        "DELETE FROM membership_acl WHERE childKey = :childKey AND membershipId IN " +
            "(SELECT id FROM task_memberships WHERE taskId = :groupTaskId)"
    )
    suspend fun deleteChildUnderGroupDoors(childKey: String, groupTaskId: String)

    /** Every placement OF [taskId] is about to go: drop their ACLs. */
    @Query("DELETE FROM membership_acl WHERE membershipId IN (SELECT id FROM task_memberships WHERE taskId = :taskId)")
    suspend fun deleteByMembershipsOfTask(taskId: String)

    /** Every placement HOSTED IN [groupId] is about to go: drop their ACLs. */
    @Query("DELETE FROM membership_acl WHERE membershipId IN (SELECT id FROM task_memberships WHERE groupId = :groupId)")
    suspend fun deleteByMembershipsHostedIn(groupId: String)

    /** Every placement of [taskId] is about to go: drop the rows that named them as hosted children elsewhere. */
    @Query("DELETE FROM membership_acl WHERE childKey IN (SELECT 'm:' || id FROM task_memberships WHERE taskId = :taskId)")
    suspend fun deleteChildKeysOfMembershipsOfTask(taskId: String)
}

/**
 * Child keys used in [MembershipAcl.childKey].
 *
 * Real children use the raw task id (UUIDs, so a prefixed key can never
 * collide with one). Hardlink placements and symlinks HOSTED inside the group
 * are also direct children of it, and get prefixed keys.
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

object MembershipAclRules {

    /**
     * Is [childKey] hidden when its group is viewed through [membership]?
     * An explicit row wins; with no row the child follows
     * [TaskMembership.newChildrenHidden].
     */
    fun isHidden(membership: TaskMembership, childKey: String, aclRows: List<MembershipAcl>): Boolean {
        val explicit = aclRows.firstOrNull { it.membershipId == membership.id && it.childKey == childKey }
        return explicit?.hidden ?: membership.newChildrenHidden
    }

    /**
     * Would [taskId], viewed through the door [membership], be hidden — i.e.
     * is it, or any real ancestor of it below the door's group, a hidden
     * direct child? Walks real `parentId` edges up from [taskId] until it
     * reaches a direct child of [TaskMembership.taskId].
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
        var current = taskId
        val seen = HashSet<String>()
        while (seen.add(current)) {
            val parent = parentOf(current) ?: return false
            if (parent == membership.taskId) return isHidden(membership, MembershipAclKeys.real(current), aclRows)
            current = parent
        }
        return false
    }
}

/**
 * Per-rebuild view of every door's ACL, used by the list builders.
 *
 * Build ONE per list rebuild from the memberships + ACL rows that rebuild is
 * using, then ask it three things at each place a group's children are
 * enumerated: which children are visible ([visibleChildren] / [visibleLinks]),
 * which real children to leave out of a group row's counts and runtime rollup
 * ([hiddenRealChildIds]), and what the sibling CPU shares are ([sharesFor]).
 *
 * THE DOOR RULE. A door only filters the DIRECT children of the group it is a
 * placement of: [doorFor] returns the active door only when
 * `membership.taskId == the group whose children are being listed`. Deeper
 * levels beneath that group, reached through the very same door, are listed
 * unfiltered (a hidden child's subtree vanishes with the child; what is
 * visible keeps its own children untouched). Cost when nothing is hidden
 * anywhere ([isInert]): every function returns its input unchanged.
 */
class DoorAcl(memberships: List<TaskMembership>, aclRows: List<MembershipAcl>) {

    private val membershipsById: Map<String, TaskMembership> = memberships.associateBy { it.id }
    private val explicit: Map<String, Map<String, Boolean>> =
        aclRows.groupBy { it.membershipId }.mapValues { (_, rows) -> rows.associate { it.childKey to it.hidden } }

    /** True when nothing can be hidden anywhere — callers and this class skip all work. */
    val isInert: Boolean = memberships.none { it.newChildrenHidden } && aclRows.none { it.hidden }

    private val shareCache = HashMap<String, Map<String, Double>>()

    fun isHidden(door: TaskMembership, childKey: String): Boolean =
        explicit[door.id]?.get(childKey) ?: door.newChildrenHidden

    /**
     * The door whose ACL applies when listing the children of [groupRealId]
     * while [doorId] is the active door; null when none does (no door, an
     * unknown door, or the door is a placement of a DIFFERENT group).
     */
    fun doorFor(doorId: String?, groupRealId: String?): TaskMembership? {
        if (isInert || doorId == null || groupRealId == null) return null
        val membership = membershipsById[doorId] ?: return null
        return if (membership.taskId == groupRealId) membership else null
    }

    /** [children] (real and/or synthetic placement entries of [groupRealId]) minus the ones hidden through [doorId]. */
    fun visibleChildren(children: List<Task>, doorId: String?, groupRealId: String?): List<Task> {
        val door = doorFor(doorId, groupRealId) ?: return children
        return children.filterNot { isHidden(door, MembershipAclKeys.ofEntry(it)) }
    }

    /** [links] hosted in [groupRealId] minus the symlinks hidden through [doorId]. */
    fun visibleLinks(links: List<TaskLink>, doorId: String?, groupRealId: String?): List<TaskLink> {
        val door = doorFor(doorId, groupRealId) ?: return links
        return links.filterNot { isHidden(door, MembershipAclKeys.symlink(it.id)) }
    }

    /**
     * Ids of the REAL direct children of [door]'s group that are hidden
     * through it. Used to exclude them from the placement row's descendant
     * counts and `childTotalRuntime`.
     */
    fun hiddenRealChildIds(door: TaskMembership?, tasks: List<Task>): Set<String> {
        if (isInert || door == null) return emptySet()
        val out = HashSet<String>()
        for (t in tasks) if (t.parentId == door.taskId && isHidden(door, MembershipAclKeys.real(t.id))) out.add(t.id)
        return out
    }

    /**
     * CPU shares for rows listed under [doorId] at [groupRealId]'s level.
     *
     * With no hidden child at that door this returns [globalShares] itself.
     * Otherwise the hidden children and their subtrees are removed from
     * [effectiveTasks] and [EEVDFScheduler.computeShares] is re-run, so the
     * visible siblings split the pool as if the hidden ones never existed. A
     * hidden child's OWN pinned/realtime share, quota and schedule class are
     * never touched — it simply isn't in the pool.
     *
     * Shares are per-level (each parent's children sum to 100), so removing a
     * subtree can only change the one level the hidden child sat in. The
     * result is therefore laid OVER [globalShares], which keeps a value for
     * every id the recompute no longer visits (a visible hardlink placement
     * whose task lives inside a hidden sibling's subtree would otherwise lose
     * its own children's shares).
     *
     * Cached per door for this instance's lifetime — [effectiveTasks],
     * [groupsEnabled] and [globalShares] must be the same on every call, which
     * holds within one list rebuild.
     */
    fun sharesFor(
        doorId: String?,
        groupRealId: String?,
        effectiveTasks: List<Task>,
        groupsEnabled: Boolean,
        globalShares: Map<String, Double>,
    ): Map<String, Double> {
        val door = doorFor(doorId, groupRealId) ?: return globalShares
        return shareCache.getOrPut(door.id) {
            val hiddenEntries = effectiveTasks.filter {
                it.parentId == door.taskId && isHidden(door, MembershipAclKeys.ofEntry(it))
            }
            if (hiddenEntries.isEmpty()) {
                globalShares
            } else {
                val byParent = effectiveTasks.groupBy { it.parentId }
                val removed = HashSet<String>()
                fun dropSubtree(id: String) {
                    if (removed.add(id)) byParent[id].orEmpty().forEach { dropSubtree(it.id) }
                }
                for (entry in hiddenEntries) {
                    // A hidden hardlink placement is just its own entry: the real
                    // children under its task also live in that task's real
                    // placement and must keep their shares.
                    if (entry.id.startsWith(MEMBERSHIP_SYNTHETIC_PREFIX)) removed.add(entry.id) else dropSubtree(entry.id)
                }
                val recomputed = EEVDFScheduler.computeShares(effectiveTasks.filterNot { it.id in removed }, groupsEnabled)
                globalShares + recomputed
            }
        }
    }
}
