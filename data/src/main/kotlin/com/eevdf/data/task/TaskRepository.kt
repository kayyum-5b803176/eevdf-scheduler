package com.eevdf.data.task
import com.eevdf.data.runlog.RunLogRepository
import com.eevdf.data.task.TaskLoadFactor

import androidx.lifecycle.LiveData
import com.eevdf.data.runlog.RunSession
import com.eevdf.data.task.Task
import com.eevdf.data.scheduler.EEVDFScheduler
import com.eevdf.data.scheduler.RtScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TaskRepository @Inject constructor(
    private val dao: TaskDao,
    private val runLog: RunLogRepository,
    private val interruptReturnDao: InterruptReturnDao,
    private val loadFactorDao: TaskLoadFactorDao,
    private val taskLinkDao: TaskLinkDao,               // ← new: symlinks
    private val taskMembershipDao: TaskMembershipDao,   // ← new: hardlinks
) {

    val allTasks: LiveData<List<Task>> = dao.getAllTasks()
    val activeTasks: LiveData<List<Task>> = dao.getActiveTasks()
    val completedTasks: LiveData<List<Task>> = dao.getCompletedTasks()
    val activeGroups: LiveData<List<Task>> = dao.getActiveGroups()

    // ── Links (symlinks) + Memberships (hardlinks) ────────────────────────────

    val allTaskLinks: LiveData<List<TaskLink>> = taskLinkDao.getAll()
    val allTaskMemberships: LiveData<List<TaskMembership>> = taskMembershipDao.getAll()

    /** Creates a symlink: [hostGroupId] shows [targetTaskId] as a live pointer. */
    suspend fun createSymlink(targetTaskId: String, hostGroupId: String) = withContext(Dispatchers.IO) {
        taskLinkDao.insert(TaskLink(targetTaskId = targetTaskId, hostGroupId = hostGroupId))
    }

    suspend fun deleteSymlink(linkId: String) = withContext(Dispatchers.IO) {
        taskLinkDao.deleteById(linkId)
    }

    /**
     * Creates a hardlink: an extra real placement of [taskId] inside [hostGroupId],
     * seeded at the sibling-average vruntime of that group (same anti-starvation
     * placement new tasks get — see [EEVDFScheduler.placeNewTask]) so it doesn't
     * unfairly jump the queue on arrival. The average includes BOTH real
     * children AND any other hardlink placements already hosted there — using
     * only real children would under-seed (or over-seed) the new placement's
     * starting vruntime whenever the group's siblings are themselves mostly
     * hardlinks, letting it unfairly dominate or starve relative to them.
     */
    suspend fun createHardlink(taskId: String, hostGroupId: String) = withContext(Dispatchers.IO) {
        val membership = TaskMembership(taskId = taskId, groupId = hostGroupId)
        val realSiblingVruntimes       = dao.getChildrenOf(hostGroupId).map { it.vruntime }
        val membershipSiblingVruntimes = taskMembershipDao.getForGroup(hostGroupId).map { it.vruntime }
        val avgVruntime = (realSiblingVruntimes + membershipSiblingVruntimes)
            .average().takeIf { !it.isNaN() } ?: 0.0
        taskMembershipDao.insert(membership.copy(vruntime = avgVruntime))
    }

    suspend fun deleteHardlink(membershipId: String) = withContext(Dispatchers.IO) {
        taskMembershipDao.deleteById(membershipId)
    }

    suspend fun getMembershipsForGroup(groupId: String): List<TaskMembership> =
        withContext(Dispatchers.IO) { taskMembershipDao.getForGroup(groupId) }

    suspend fun getLinksForHost(groupId: String): List<TaskLink> =
        withContext(Dispatchers.IO) { taskLinkDao.getForHost(groupId) }

    /**
     * Live, alphabetically sorted list of every distinct category string stored
     * in the tasks table.  Updated automatically whenever any task is saved with
     * a new or changed category value, so the autocomplete suggestions in the
     * Add / Edit screen always reflect the full set the user has ever typed.
     */
    val distinctCategories: LiveData<List<String>> = dao.getDistinctCategories()

    // ── Load factor side table ────────────────────────────────────────────────

    /** Returns the [TaskLoadFactor] entry for [taskId], or null if not yet configured. */
    suspend fun getLoadFactor(taskId: String): TaskLoadFactor? = withContext(Dispatchers.IO) {
        loadFactorDao.get(taskId)
    }

    /**
     * Persists a [TaskLoadFactor] entry (insert or replace).
     * Called from [SaveHandler] after the [Task] row is written.
     */
    suspend fun saveLoadFactor(entry: TaskLoadFactor) = withContext(Dispatchers.IO) {
        loadFactorDao.upsert(entry)
    }

    suspend fun insert(task: Task) = withContext(Dispatchers.IO) {
        val existing = dao.getActiveTasksSync().toMutableList()

        // ── Bug fix: Linux EEVDF place_entity for new tasks ──────────────────
        // Before this fix, every new task started with vruntime = 0.  The scheduler
        // saw a huge positive lag (avgVr − 0) × weight and kept picking the new task
        // first, starving all existing tasks until vruntime "caught up".
        //
        // Fix: mirrors Linux's place_entity(ENQUEUE_INITIAL) which clamps lag to 0
        // for a never-run task → vruntime = avg_vruntime → lag = 0 → eligible
        // immediately but with no scheduling advantage over existing tasks.
        EEVDFScheduler.placeNewTask(task, existing)

        existing.add(task)
        EEVDFScheduler.recalculate(existing)
        dao.insert(task)
        existing.forEach { dao.update(it) }
    }

    suspend fun update(task: Task) = withContext(Dispatchers.IO) {
        dao.update(task)
        propagateInheritedLoadFactor(task.id, task.loadFactor)
        propagateInheritedTimeSlice(task.id, task.timeSliceSeconds)
    }

    /**
     * Walks the task tree rooted at [parentId] and updates every descendant that
     * has [Task.timeSliceInherited] == true with the new [timeSliceSeconds] value.
     *
     * [remainingSeconds] is only synced when the child's timer is fully intact
     * (remainingSeconds == timeSliceSeconds) — a partially-consumed timer is left
     * alone so an in-progress session is not disrupted.
     */
    private suspend fun propagateInheritedTimeSlice(parentId: String, timeSliceSeconds: Long) {
        val children = dao.getChildrenOf(parentId)
        for (child in children) {
            if (child.timeSliceInherited) {
                val syncedRemaining =
                    if (child.remainingSeconds == child.timeSliceSeconds) timeSliceSeconds
                    else child.remainingSeconds
                dao.update(child.copy(
                    timeSliceSeconds = timeSliceSeconds,
                    remainingSeconds = syncedRemaining
                ))
                propagateInheritedTimeSlice(child.id, timeSliceSeconds)
            }
        }
    }

    /**
     * Walks up the ancestor chain from [taskId] to find the nearest
     * [TaskLoadFactor] entry whose [TaskLoadFactor.enabled] == true (i.e. the task
     * whose sliders were manually configured by the user).
     *
     * Returns null when no such ancestor exists — callers fall back to (4, 4, 4).
     *
     * This is the single source of truth for "which slider values should an
     * auto-inherited task display / store?"  Both the propagation path (repository)
     * and the UI population path (LoadFactorSection) use this logic so they
     * always agree, regardless of how deeply auto tasks are nested.
     */
    private suspend fun findNearestEnabledLoadFactor(taskId: String): TaskLoadFactor? {
        var currentId: String? = taskId
        while (currentId != null) {
            val entry = loadFactorDao.get(currentId)
            if (entry != null && entry.enabled) return entry
            // This node is also auto (or has no entry) — walk up to its parent
            val task = dao.getTaskById(currentId) ?: break
            currentId = task.parentId
        }
        return null   // no manually-configured ancestor in the chain
    }

    /**
     * Walks the task tree rooted at [parentId] updating every inheriting descendant.
     *
     * ── Slider value resolution ──────────────────────────────────────────────
     * Rather than copying the direct parent's stored slider values (which may
     * themselves be auto-inherited and therefore stale), we resolve the canonical
     * values ONCE by walking up to the nearest ancestor with enabled == true via
     * [findNearestEnabledLoadFactor].  Those values are then passed unchanged to
     * every level of the subtree via [propagateWithValues].
     *
     * This means:
     *   • A → B (auto) → C (auto)  with A enabled(2,4,6):  B and C both get (2,4,6) ✓
     *   • A (auto) → B (auto) with no enabled ancestor:    B gets (4,4,4)           ✓
     *
     * Stops recursing into a branch only when a child has manually overridden its
     * own load factor (loadFactorInherited == false), preserving that child's
     * explicit choice while still propagating to its siblings.
     */
    private suspend fun propagateInheritedLoadFactor(parentId: String, loadFactor: Double) {
        // Resolve the canonical slider values from the nearest manually-configured ancestor.
        val effectiveEntry = findNearestEnabledLoadFactor(parentId)
        val effCognitive   = effectiveEntry?.cognitive ?: TaskLoadFactor.DEFAULT_COGNITIVE
        val effPhysical    = effectiveEntry?.physical  ?: TaskLoadFactor.DEFAULT_PHYSICAL
        val effEmotional   = effectiveEntry?.emotional ?: TaskLoadFactor.DEFAULT_EMOTIONAL
        propagateWithValues(parentId, loadFactor, effCognitive, effPhysical, effEmotional)
    }

    /**
     * Inner recursive worker for [propagateInheritedLoadFactor].
     *
     * Accepts the already-resolved slider values so they are computed only once
     * at the top of the call, then passed unchanged through every level of the
     * subtree — no redundant DB reads per level.
     */
    private suspend fun propagateWithValues(
        parentId:  String,
        loadFactor: Double,
        cognitive:  Int,
        physical:   Int,
        emotional:  Int,
    ) {
        val children = dao.getChildrenOf(parentId)
        for (child in children) {
            if (child.loadFactorInherited) {
                dao.update(child.copy(loadFactor = loadFactor))
                loadFactorDao.upsert(TaskLoadFactor(
                    taskId    = child.id,
                    cognitive = cognitive,
                    physical  = physical,
                    emotional = emotional,
                    enabled   = false,
                ))
                propagateWithValues(child.id, loadFactor, cognitive, physical, emotional)
            }
        }
    }

    /** Batch-persists a list of tasks whose [Task.internalWeight] was re-synced. */
    suspend fun updateBatch(tasks: List<Task>) = withContext(Dispatchers.IO) {
        if (tasks.isNotEmpty()) dao.updateAll(tasks)
    }

    /**
     * Unconditional, final deletion — no promotion check. Used by
     * [deleteOrPromote]'s zero-membership branch, and directly by
     * [clearCompleted]: `isCompleted` lives on the one shared [Task] row, not
     * per-placement, so a completed task is completed EVERYWHERE it appears —
     * there is no "it's still alive at another placement" scenario to protect
     * against the way there is for an explicit single-location [delete]. A
     * completed task's other membership placements are just leftover rows to
     * clean up together with it, never a reason to keep it alive.
     */
    private suspend fun forceDelete(task: Task) {
        if (task.isGroup) deleteDescendants(task.id)
        dao.delete(task)
        interruptReturnDao.clearByTask(task.id)
        loadFactorDao.clearByTask(task.id)
        taskMembershipDao.deleteByTask(task.id)
        if (task.isGroup) {
            taskLinkDao.deleteByHost(task.id)
            taskMembershipDao.deleteByGroup(task.id)
        }
        // Symlinks pointing at this task persist as broken links — see TaskLink doc comment.
    }

    /**
     * Deletes [task], UNLESS it has a surviving [TaskMembership] elsewhere —
     * in which case that placement is promoted to become the task's new
     * primary [Task.parentId] instead, and the task itself lives on. This
     * mirrors real hardlink semantics deliberately: a task's data persists as
     * long as ANY placement (primary or membership) of it still exists; only
     * removing the very last one actually deletes it.
     *
     * Only `parentId` and `vruntime` are copied from the promoted membership.
     * `totalRunTime`/`runCount` are SHARED (see [TaskMembership] doc comment)
     * and already correct on [task] as-is — copying anything from the
     * (now-retired) membership row onto them would silently zero out the
     * task's real, current, shared lifetime totals. `eligibleTime`/
     * `virtualDeadline`/`lag` need no copying either: they're pure functions
     * of `vruntime` (recomputed fresh wherever needed), not independent state
     * to keep in sync.
     *
     * Used for both the top-level [delete] call and every real descendant
     * encountered by [deleteDescendants] — a hardlinked task several levels
     * deep inside a deleted group is promoted exactly the same way a
     * top-level hardlinked task would be, never silently destroyed just
     * because ITS ancestor happened to be the one the user deleted from.
     *
     * NOT used by [clearCompleted] — see [forceDelete]'s doc comment for why
     * completion specifically bypasses promotion.
     */
    private suspend fun deleteOrPromote(task: Task) {
        val remaining = taskMembershipDao.getForTask(task.id)
        if (remaining.isNotEmpty()) {
            val promoted = remaining.first()
            dao.update(task.copy(
                parentId = promoted.groupId,
                vruntime = promoted.vruntime,
            ))
            taskMembershipDao.deleteById(promoted.id)
            return
        }
        forceDelete(task)
    }

    suspend fun delete(task: Task) = withContext(Dispatchers.IO) {
        deleteOrPromote(task)
    }

    private suspend fun deleteDescendants(parentId: String) {
        val children = dao.getChildrenOf(parentId)
        children.forEach { child -> deleteOrPromote(child) }
    }

    suspend fun markCompleted(task: Task) = withContext(Dispatchers.IO) {
        val updated = task.copy(isCompleted = true, isRunning = false, remainingSeconds = 0)
        dao.update(updated)
        // A completed task is no longer a valid return-to target.
        interruptReturnDao.clearByTask(task.id)
    }

    suspend fun stopAll() = withContext(Dispatchers.IO) { dao.stopAllRunning() }

    /**
     * Deletes every completed task — routed through [forceDelete] per task
     * (never [deleteOrPromote] — see [forceDelete]'s doc comment for why
     * completion specifically must not promote) rather than a single bulk
     * DELETE. This also picks up the interruptReturn/loadFactor/membership/
     * link-host cleanup [forceDelete] already does for a single delete, which
     * the old bulk query never performed.
     */
    suspend fun clearCompleted() = withContext(Dispatchers.IO) {
        dao.getCompletedTasksSync().forEach { forceDelete(it) }
    }

    suspend fun getTaskById(id: String): Task? = withContext(Dispatchers.IO) { dao.getTaskById(id) }

    // ── Per-tab, per-slot interrupt return-to persistence ─────────────────────

    /** Stores the non-interrupt task to return to for a (tab, slot) cell. */
    suspend fun saveInterruptReturn(tab: String, slot: String, taskId: String) =
        withContext(Dispatchers.IO) {
            interruptReturnDao.upsert(
                InterruptReturnEntry(
                    cellKey = InterruptReturnEntry.keyOf(tab, slot),
                    tab     = tab,
                    slot    = slot,
                    taskId  = taskId,
                )
            )
        }

    /** Returns the stored return-to task id for a (tab, slot) cell, or null. */
    suspend fun getInterruptReturnTaskId(tab: String, slot: String): String? =
        withContext(Dispatchers.IO) {
            interruptReturnDao.get(InterruptReturnEntry.keyOf(tab, slot))?.taskId
        }

    /** Clears the stored return-to for a (tab, slot) cell. */
    suspend fun clearInterruptReturn(tab: String, slot: String) =
        withContext(Dispatchers.IO) {
            interruptReturnDao.clear(InterruptReturnEntry.keyOf(tab, slot))
        }

    /** Removes any stored return-to pointing at [taskId] (e.g. on delete/complete). */
    suspend fun clearInterruptReturnByTask(taskId: String) =
        withContext(Dispatchers.IO) {
            interruptReturnDao.clearByTask(taskId)
        }

    suspend fun getActiveTaskByName(name: String): Task? =
        withContext(Dispatchers.IO) { dao.getActiveTaskByName(name) }

    suspend fun getActiveTasksSync(): List<Task> = withContext(Dispatchers.IO) { dao.getActiveTasksSync() }

    /**
     * Returns the active interrupt task occupying [slot] ("A" or "B"), or null
     * if no task is currently assigned to that slot. Used by [CallSwitchService]
     * to resolve the call target from a slot label rather than a stored task ID,
     * so re-assigning the interrupt task in the UI needs no settings re-sync.
     */
    suspend fun getInterruptTaskBySlot(slot: String): Task? =
        withContext(Dispatchers.IO) {
            dao.getActiveTasksSync().firstOrNull {
                it.isInterrupt && it.interruptSlot == slot && !it.isCompleted
            }
        }

    /**
     * cgroup-aware vruntime update.
     * Updates the leaf task, then propagates elapsed time upward through all
     * ancestor groups — exactly like Linux cgroups crediting the task's CPU
     * time to every cgroup it belongs to.
     *
     * Also rolls the quota accounting window forward and credits session.wallClockSeconds
     * against the quota budget for the leaf and every ancestor that has quota enabled.
     *
     * [session] is the authoritative source for both:
     *   • seconds to credit → session.wallClockSeconds  (timestamp diff, never a config value)
     *   • RunLog start time → session.startEpochMs      (real wall-clock, never approximated)
     *
     * This fixes two compounding bugs in the old Long-based API:
     *   1. Caller passed task.timeSliceSeconds instead of actual elapsed → vruntime over-credited.
     *   2. RunLog start was approximated as (now - secondsRan*1000) → off by any pause delay.
     */
    /**
     * Accounts a completed run (vruntime, quota, DL, load) and propagates
     * runtime credit up the ancestor chain.
     *
     * Returns the authoritative, fully up-to-date [Task] — callers MUST use
     * this returned value for anything persisted afterward, not their own
     * [task] parameter. This is not a style preference: [task].vruntime is
     * mutated in place by [EEVDFScheduler.updateVruntime] above (so it
     * happens to stay correct on the caller's own reference), but
     * `virtualDeadline`/`eligibleTime`/`lag` are only recalculated on the
     * freshly-queried [allActive] list a few lines below — a *different*
     * set of object instances. A caller that keeps using its own [task]
     * reference after this function returns is holding a permanently stale
     * `virtualDeadline`, and the next time it persists anything derived from
     * that reference, it silently reverts the DB's correct value back to
     * stale. This exact bug shipped and was found via a real user report —
     * see ARCHITECTURE.md for the full trace. The return value exists
     * specifically so this can't happen again by a future caller forgetting
     * to know that history: the correct object is handed over explicitly.
     */
    /**
     * @param membershipId The "door" this run was credited through — a
     *   [TaskMembership] the task was reached via, whether that membership IS
     *   the task itself (the classic case: tapping run on a hardlink's own
     *   row) or an ANCESTOR further up (running a real leaf nested inside a
     *   hardlinked group's subtree, viewed through that group's placement).
     *   Either way there is exactly ONE substitution boundary per run: the
     *   node whose id equals [TaskMembership.taskId]. Below that boundary,
     *   accounting is always the plain, real-chain kind (a leaf's own fields
     *   are never door-dependent); AT that boundary, vruntime advances on
     *   BOTH the placement AND the real row's own field, by the same
     *   per-second-of-work delta computed independently for each — real work
     *   through either placement must keep both fair relative to their own
     *   respective siblings; ABOVE it, propagation continues from
     *   [TaskMembership.groupId] instead of the boundary's real `parentId`.
     *   See [creditAncestors] for the shared walk that applies this
     *   uniformly at whichever level the boundary actually falls.
     */
    suspend fun updateVruntimeAfterRun(
        task: Task,
        session: RunSession,
        membershipId: String? = null,
    ): Task = withContext(Dispatchers.IO) {
        val secondsRan = session.wallClockSeconds
        val door = membershipId?.let { taskMembershipDao.getById(it) }

        if (door != null && door.taskId == task.id) {
            // Direction: task run via its OWN membership placement `door`.
            val doorScratch = task.copy(vruntime = door.vruntime)
            EEVDFScheduler.updateVruntime(doorScratch, secondsRan)
            taskMembershipDao.updateVruntime(door.id, doorScratch.vruntime)

            // Independent advance from the SAME original `task` — its own
            // totalRunTime/runCount land at exactly one increment past
            // task's pre-run values here, same as doorScratch's did; using
            // this directly (not chaining off doorScratch) is what keeps the
            // shared fields advancing exactly once rather than twice.
            val realTaskUpdate = task.copy()
            EEVDFScheduler.updateVruntime(realTaskUpdate, secondsRan)
            applySharedRunAccounting(realTaskUpdate, session, secondsRan)

            // Unconditional symmetric fix: if `task` has YET OTHER placements
            // beyond `door` (a third, fourth, …), keep THOSE fair too — this
            // is a fresh database lookup done on every single run, never
            // gated behind whether the caller happened to supply a door
            // matching one specific placement.
            touchSiblingPlacements(task.id, task.weight, secondsRan, excludeMembershipId = door.id)

            creditAncestors(door.groupId, secondsRan, door = null)
            return@withContext refreshAllAndReturn(task.id) ?: task
        }

        // Plain run, or the door's boundary is an ancestor further up. Task's
        // own accounting is always the plain kind either way — a leaf
        // several levels inside a hardlinked group's subtree is never itself
        // door-dependent.
        EEVDFScheduler.updateVruntime(task, secondsRan)
        applySharedRunAccounting(task, session, secondsRan)

        // Unconditional symmetric fix: even on a run with NO door at all
        // (running from task's real, primary location), if `task` itself
        // separately has other placements, keep them fair too. This is the
        // exact case that was broken before this fix — a plain run supplied
        // no door, so nothing ever looked up whether placements existed at
        // all; now every run always checks, regardless of direction.
        touchSiblingPlacements(task.id, task.weight, secondsRan, excludeMembershipId = null)

        creditAncestors(task.parentId, secondsRan, door = door)
        refreshAllAndReturn(task.id) ?: task
    }

    /**
     * The unconditional half of the fix: advances every OTHER real
     * placement's ([TaskMembership]) vrt for the node identified by [nodeId]
     * — by the same per-second-of-work delta every other vrt advance in this
     * file uses — regardless of which specific placement (if any) was
     * actually used to run it. Called on EVERY node touched during
     * accounting (the task itself, and every ancestor [creditAncestors]
     * visits), via a fresh [TaskMembershipDao.getForTask] lookup each time —
     * never inferred from, or gated behind, the door supplied for a
     * particular run.
     *
     * This is the fix in this pass: real work through ANY one placement of a
     * multi-placement node must keep EVERY placement of that node fair to
     * its own siblings, not just whichever one or two paths a given call
     * happened to know about. Previously this only fired for the ONE
     * placement matching an explicitly-supplied door, so running from a
     * node's real, primary location (no door at all) silently left every
     * OTHER placement's vrt stale — exactly the reported bug.
     *
     * [excludeMembershipId] skips the one placement whose advance is already
     * being handled by the caller through its own (differently-sourced, but
     * identical-formula) code path, so it's never double-credited.
     */
    private suspend fun touchSiblingPlacements(
        nodeId: String, weight: Double, secondsRan: Long, excludeMembershipId: String?,
    ) {
        if (weight <= 0) return
        val delta = secondsRan.toDouble() / weight
        taskMembershipDao.getForTask(nodeId).forEach { m ->
            if (m.id != excludeMembershipId) {
                taskMembershipDao.updateVruntime(m.id, m.vruntime + delta)
            }
        }
    }

    /**
     * Recomputes eligibleTime/virtualDeadline/lag for every active task from
     * its own (possibly just-advanced) vruntime and persists all of them —
     * cheap relative to correctness risk, and the one place both the primary
     * path and every door-credited path converge so their derived-field
     * freshness can never silently diverge from each other.
     */
    private suspend fun refreshAllAndReturn(taskId: String): Task? {
        val allActive = dao.getActiveTasksSync()
        EEVDFScheduler.recalculate(allActive)
        allActive.forEach { dao.update(it) }
        return allActive.find { it.id == taskId }
    }

    /**
     * Every SHARED (never per-placement) accounting effect of running [task]
     * for [secondsRan]: quota, DL budget, load/EWMA, and the run-log entry.
     * Persists [task] via [dao.update] as its last step.
     *
     * Deliberately does NOT touch vruntime/eligibleTime/virtualDeadline/lag —
     * callers are responsible for advancing those before calling this, since
     * that's the one thing that differs depending on whether a door applies.
     * Extracted into one function so every accounting path can never drift
     * apart on what "shared run accounting" means — a bug fixed in quota/DL/
     * load logic here fixes every path at once.
     */
    private suspend fun applySharedRunAccounting(task: Task, session: RunSession, secondsRan: Long) {
        applyQuotaAccounting(task, secondsRan)
        applyDlAccounting(task, secondsRan)

        // applyLoadAccounting runs BEFORE recordRun so the EWMA snapshot fields
        // on task are populated and can be passed directly to the run log entry.
        val loadFactorEntry = loadFactorDao.get(task.id)
        applyLoadAccounting(task, session, loadFactorEntry)
        dao.update(task)

        // Record AFTER accounting so the log entry carries the correct EWMA snapshot.
        if (secondsRan > 0 && !task.isGroup) {
            runLog.recordRun(
                taskId            = task.id,
                startEpoch        = session.startEpochMs,
                durationSecs      = secondsRan,
                snapshotCognitive = task.loadAvgCognitive,
                snapshotPhysical  = task.loadAvgPhysical,
                snapshotEmotional = task.loadAvgEmotional,
            )
        }
    }

    /**
     * Walks the ancestor chain crediting each group exactly once — the single
     * shared implementation used whether or not a [door] applies partway up,
     * so the "plain chain" and "hardlink-aware chain" behaviors can never
     * silently drift apart from each other.
     *
     * Starts at [startParentId] and normally just follows each ancestor's
     * real `parentId` upward, crediting totalRunTime/runCount/quota/DL/
     * vruntime on every real group exactly like it always has.
     *
     * If [door] is non-null, the walk watches for the one ancestor whose id
     * equals [door.taskId] — the group [door] is a placement OF. At exactly
     * that ancestor: totalRunTime/runCount/quota/DL are credited normally
     * onto the real row (shared, correct regardless of which door was used to
     * reach it), AND vruntime advances on BOTH the real row's own field AND
     * the [door] row — by the same per-second-of-work delta, computed
     * independently for each. Propagation then continues from [door.groupId]
     * instead of that ancestor's real `parentId`. Above that point the walk
     * is purely the plain kind again, all the way to the top — [door] only
     * ever redirects the chain once; a second hardlink boundary further up
     * would need its own explicit door to redirect again, which this
     * function does not attempt (stacked/nested hardlink doors are out of
     * scope for now).
     *
     * CRITICALLY, [touchSiblingPlacements] is called for EVERY ancestor
     * visited — not just the one matching [door] — via a fresh database
     * lookup each time. This is what makes vrt symmetric regardless of
     * which direction this specific run is walking: an ancestor might have
     * its OWN separate placement(s) unrelated to [door] entirely, and those
     * must stay fair too, on every single run through this chain, whether or
     * not [door] happens to be null this time.
     */
    private suspend fun creditAncestors(
        startParentId: String?,
        secondsRan: Long,
        door: TaskMembership?,
    ) {
        var parentId = startParentId
        while (parentId != null) {
            val parent = dao.getTaskById(parentId) ?: break
            parent.totalRunTime += secondsRan
            parent.runCount     += 1
            if (parent.weight > 0) {
                parent.vruntime += secondsRan.toDouble() / parent.weight
            }
            applyQuotaAccounting(parent, secondsRan)
            applyDlAccounting(parent, secondsRan)

            if (door != null && parent.id == door.taskId) {
                // This IS the door's boundary: advance the door's OWN vrt
                // too (parent's real vrt already advanced, unconditionally,
                // above), then continue climbing from its host instead of
                // parent's real parent.
                val delta = if (parent.weight > 0) secondsRan.toDouble() / parent.weight else 0.0
                taskMembershipDao.updateVruntime(door.id, door.vruntime + delta)
                touchSiblingPlacements(parent.id, parent.weight, secondsRan, excludeMembershipId = door.id)
                dao.update(parent)
                parentId = door.groupId
            } else {
                // Unconditional symmetric fix: `parent` might have its OWN
                // placement(s) entirely unrelated to `door` (or door may be
                // null altogether, as on a plain run) — keep them fair too,
                // every time, via a fresh lookup rather than only when a
                // door happens to be supplied.
                touchSiblingPlacements(parent.id, parent.weight, secondsRan, excludeMembershipId = null)
                dao.update(parent)
                parentId = parent.parentId
            }
        }
    }

    /**
     * Rolls the quota accounting period forward if it has expired, then credits
     * [secondsRan] to [task.quotaUsedSeconds].
     *
     * The task object is mutated in-place (var fields); the caller persists it.
     *
     * Period roll-over logic (mirrors Linux cgroup bandwidth controller):
     *   - If no period has started yet (quotaPeriodStartEpoch == 0), open a fresh period now.
     *   - If the current period has elapsed, advance the start epoch by however many complete
     *     periods have passed so the window tracks real wall-clock time precisely.
     */
    private fun applyQuotaAccounting(task: Task, secondsRan: Long) {
        if (!task.isQuotaEnabled) return
        val nowMs = System.currentTimeMillis()

        // Snapshot the continuously-decayed value at this instant, then reset the
        // anchor to now.  This means the next tick in currentQuotaUsed always starts
        // from the correct baseline rather than accumulating floating-point drift.
        val decayedNow = if (task.quotaPeriodStartEpoch == 0L) 0L else task.currentQuotaUsed

        task.quotaPeriodStartEpoch = nowMs
        task.quotaUsedSeconds      = (decayedNow + secondsRan).coerceAtLeast(0L)
    }

    /**
     * Advances the task's load average to reflect a completed run, mirroring the
     * Linux load-average EWMA (see [com.eevdf.data.scheduler.LoadAverage]).
     *
     * Two-step integration so the run window is captured exactly:
     *   1. decay toward 0 (idle) over the gap from the last update → session start,
     *   2. integrate toward [Task.loadFactor] (running) over session start → end.
     *
     * The resulting loadAverage + loadLastUpdateEpoch are written back onto the
     * task (var fields); the caller persists it.  Foreground ticking later decays
     * it further while the task sits idle.
     */
    /**
     * Advances all three per-dimension EWMAs (Cognitive/Physical/Emotional) for
     * the completed session and updates [Task.loadAverage] with the combined result.
     *
     * Two-step integration — same as before, now with explicit per-dimension targets:
     *   Step 1: idle decay from last-persisted → session start (targets all 0).
     *   Step 2: running integration from session start → end (targets = slider values).
     *
     * Slider values come from [loadFactorEntry].  When the entry is null (task was
     * never configured) the combined [Task.loadFactor] (0–100) is distributed evenly
     * across all three dimensions as an approximation.
     */
    private fun applyLoadAccounting(task: Task, session: RunSession, loadFactorEntry: TaskLoadFactor?) {
        // Per-dimension targets: slider [1,7] → 0–100 via TaskLoadFactor.dimensionPercent.
        // Each EWMA stream operates entirely in the 0–100 range — same scale as
        // task.loadFactor — so all three can be averaged directly for combinedLoad.
        // Fallback: task.loadFactor used equally across all three dimensions when
        // no TaskLoadFactor entry exists (unconfigured task).
        val approxPerDim = task.loadFactor   // 0–100, equal distribution fallback
        val tC = loadFactorEntry?.cognitive?.let { TaskLoadFactor.dimensionPercent(it) } ?: approxPerDim
        val tP = loadFactorEntry?.physical?.let  { TaskLoadFactor.dimensionPercent(it) } ?: approxPerDim
        val tE = loadFactorEntry?.emotional?.let { TaskLoadFactor.dimensionPercent(it) } ?: approxPerDim

        // Step 1: idle decay up to the moment the run began (targets = 0)
        val atStart = com.eevdf.data.scheduler.LoadAverage.advanced(
            task, session.startEpochMs, isRunning = false,
            targetCognitive = 0.0, targetPhysical = 0.0, targetEmotional = 0.0,
        )
        // Step 2: running integration across the real run window
        val atEnd = com.eevdf.data.scheduler.LoadAverage.advanced(
            atStart, session.endEpochMs, isRunning = true,
            targetCognitive = tC, targetPhysical = tP, targetEmotional = tE,
        )

        // Write all six per-dimension fields + combined output back onto the task
        task.loadAvgCognitive         = atEnd.loadAvgCognitive
        task.loadLastUpdateCognitive  = atEnd.loadLastUpdateCognitive
        task.loadAvgPhysical          = atEnd.loadAvgPhysical
        task.loadLastUpdatePhysical   = atEnd.loadLastUpdatePhysical
        task.loadAvgEmotional         = atEnd.loadAvgEmotional
        task.loadLastUpdateEmotional  = atEnd.loadLastUpdateEmotional
        task.loadAverage              = atEnd.loadAverage
        task.loadLastUpdateEpoch      = atEnd.loadLastUpdateEpoch
    }

    /**
     * SCHED_DEADLINE period accounting — mirrors Linux SCHED_DEADLINE budget tracking.
     *
     * On every run:
     *  1. If no period has started (dlPeriodStartEpoch == 0), open a fresh period now
     *     and credit [secondsRan] as the first runtime consumption.
     *  2. If the current period has fully elapsed since the last recorded start, roll
     *     forward by whole periods and reset [dlRuntimeUsedSeconds] to zero before
     *     crediting the new run.  This ensures the budget replenishes exactly on period
     *     boundaries regardless of how much wall-clock time has passed between runs.
     *  3. Within an active period, accumulate [secondsRan] up to the maximum
     *     [Task.dlRuntimeSeconds] (excess is clamped — over-running is not possible).
     *
     * The task object is mutated in-place; the caller persists it.
     */
    private fun applyDlAccounting(task: Task, secondsRan: Long) {
        if (!task.isDlConfigured) return
        val nowMs             = System.currentTimeMillis()
        val effectivePeriodMs = task.dlEffectivePeriodSeconds * 1_000L

        if (task.dlPeriodStartEpoch == 0L) {
            // First ever run — open period now
            task.dlPeriodStartEpoch    = nowMs
            task.dlRuntimeUsedSeconds  = secondsRan.coerceAtMost(task.dlRuntimeSeconds)
            return
        }

        val elapsedMs = nowMs - task.dlPeriodStartEpoch
        if (elapsedMs >= effectivePeriodMs) {
            // One or more full periods have elapsed since last run — advance the anchor
            // by the number of whole periods that fit, then reset the budget.
            val periodsElapsed        = elapsedMs / effectivePeriodMs
            task.dlPeriodStartEpoch   = task.dlPeriodStartEpoch + periodsElapsed * effectivePeriodMs
            task.dlRuntimeUsedSeconds = secondsRan.coerceAtMost(task.dlRuntimeSeconds)
        } else {
            // Still within the same period — accumulate runtime (capped at budget)
            task.dlRuntimeUsedSeconds =
                (task.dlRuntimeUsedSeconds + secondsRan).coerceAtMost(task.dlRuntimeSeconds)
        }
    }

    /**
     * cgroup-aware task selection.
     * Applies EEVDF at each level of the hierarchy, drilling into the winning
     * group recursively until a leaf (non-group) task is found — same as
     * Linux's hierarchical scheduling.
     *
     * cgroup-aware SCHED_DEADLINE promotion:
     * Before the normal EEVDF pick, any group at the current level whose subtree
     * contains at least one DL-budget-active leaf is treated as a "deadline entity"
     * at that level and hoisted ahead of all EEVDF-ordered siblings — exactly as
     * Linux promotes a deadline-class cgroup entity to the top of the run-queue.
     * Among multiple hoisted groups, the one whose most-urgent descendant has the
     * shortest dlPeriodRemaining wins (EDF order).  If the winning hoisted group
     * has no runnable children (all completed/running), the algorithm falls back
     * to normal EEVDF selection at the same level.
     */
    suspend fun selectNextTask(): Task? = withContext(Dispatchers.IO) {
        val allActive = dao.getActiveTasksSync()
        selectNextCgroup(allActive, null)
    }

    private fun selectNextCgroup(
        all: List<Task>,
        parentId: String?,
        visited: MutableSet<String> = mutableSetOf()
    ): Task? {
        // Exclude already-tried empty groups to prevent infinite recursion
        val level = all.filter {
            it.parentId == parentId && !it.isCompleted && !it.isRunning && it.id !in visited
        }
        if (level.isEmpty()) return null

        // ── cgroup-aware SCHED_DEADLINE promotion ─────────────────────────────
        // Identify groups (or leaves) at this level that are DL-urgent.
        // A group is DL-urgent if the group itself has an active DL budget, OR
        // if any descendant has isDlBudgetActive == true.
        // A leaf is DL-urgent if it is a dl_sched_class task with budget remaining.
        //
        // Among DL-urgent entries, pick by EDF urgency: the entry whose most
        // imminent deadline fires soonest (smallest dlPeriodRemaining).
        // This matches Linux SCHED_DEADLINE which always picks the entity with the
        // earliest absolute deadline among eligible deadline tasks/groups.
        fun minDlUrgency(task: Task): Long =
            if (!task.isGroup) task.dlPeriodRemainingSeconds
            else if (task.isDlBudgetActive) task.dlPeriodRemainingSeconds   // group's own DL
            else all.filter { it.parentId == task.id && !it.isCompleted }
                    .minOfOrNull { minDlUrgency(it) } ?: Long.MAX_VALUE

        val dlUrgent = level.filter { entry ->
            if (entry.isGroup) entry.isDlBudgetActive || EEVDFScheduler.hasActiveDlDescendant(entry, all)
            else entry.isDlBudgetActive
        }.sortedBy { minDlUrgency(it) }

        // Try DL-urgent candidates first, then fall back to normal EEVDF selection
        for (dlEntry in dlUrgent) {
            if (dlEntry.id in visited) continue
            val result = if (dlEntry.isGroup) {
                visited.add(dlEntry.id)
                selectNextCgroup(all, dlEntry.id, visited)
                    ?: selectNextCgroup(all, parentId, visited) // group was empty, continue
            } else {
                dlEntry // leaf DL task — return directly
            }
            if (result != null) return result
        }

        // ── RT window promotion (between DL and EEVDF) ────────────────────────
        // After DL candidates are exhausted, check for RT-window-active entries.
        // A group is RT-urgent if the group itself has an active RT window, OR
        // if any descendant has an active RT window.
        // Among RT tasks: highest rtPriority wins; FIFO = never rotate, RR = round-robin.
        val rtUrgent = level.filter { entry ->
            if (entry.isGroup) RtScheduler.isRtWindowActive(entry) || RtScheduler.hasActiveRtDescendant(entry, all)
            else RtScheduler.isRtWindowActive(entry)
        }

        if (rtUrgent.isNotEmpty()) {
            // Collect all RT-active leaves across the urgent entries for RR selection.
            // When the group itself holds the RT config (no RT-configured children),
            // promote all eligible leaf children — they run via EEVDF within the group's window.
            val rtLeaves = rtUrgent.flatMap { entry ->
                if (!entry.isGroup) listOf(entry)
                else {
                    val rtChildren = all.filter { it.parentId == entry.id && !it.isCompleted &&
                                                  !it.isRunning && RtScheduler.isRtWindowActive(it) }
                    if (rtChildren.isEmpty() && RtScheduler.isRtWindowActive(entry)) {
                        all.filter { it.parentId == entry.id && !it.isCompleted &&
                                     !it.isRunning && !it.isGroup }
                    } else rtChildren
                }
            }.filter { it.id !in visited }

            if (rtLeaves.isNotEmpty()) {
                // SharedPrefs not accessible from repository directly; use highest-priority
                // as the fallback deterministic pick (RR index is managed by the ViewModel layer)
                val pick = rtLeaves.maxByOrNull { it.rtPriority }
                if (pick != null) return pick
            }
        }

        // No DL-urgent or RT-urgent entries — normal EEVDF selection
        EEVDFScheduler.recalculate(level)
        val winner = EEVDFScheduler.selectNext(level) ?: return null
        return if (winner.isGroup) {
            // Mark this group visited so it won't be retried if its children are empty
            visited.add(winner.id)
            // Drill into the group; if it has no eligible children fall back at
            // the SAME level (not root) — skipping the now-visited empty group
            selectNextCgroup(all, winner.id, visited)
                ?: selectNextCgroup(all, parentId, visited)
        } else {
            winner
        }
    }

    suspend fun getScheduleOrder(): List<Task> = withContext(Dispatchers.IO) {
        val activeTasks = dao.getActiveTasksSync()
        EEVDFScheduler.getScheduleOrder(activeTasks)
    }

    // ── Interrupt task ────────────────────────────────────────────────────────

    suspend fun getRunningTask(): Task? = withContext(Dispatchers.IO) { dao.getRunningTask() }

    /** Returns the INT-A interrupt task (legacy default slot). */
    suspend fun getInterruptTask(): Task? = withContext(Dispatchers.IO) { dao.getInterruptTask() }

    /** Returns the INT-B interrupt task. */
    suspend fun getInterruptTaskB(): Task? = withContext(Dispatchers.IO) { dao.getInterruptTaskB() }

    /**
     * Atomically clears all interrupt flags in [slot] then marks [task] as that slot's interrupt.
     * [slot] must be "A" or "B".
     */
    suspend fun setInterruptTask(task: Task, slot: String = "A") = withContext(Dispatchers.IO) {
        dao.clearInterruptsForSlot(slot)
        dao.update(task.copy(isInterrupt = true, interruptSlot = slot))
    }

    /** Clears interrupt flag for the given slot ("A" or "B"). */
    suspend fun clearInterruptTask(slot: String = "A") = withContext(Dispatchers.IO) {
        dao.clearInterruptsForSlot(slot)
    }

    /** Clears interrupt flags for ALL slots. */
    suspend fun clearAllInterruptTasks() = withContext(Dispatchers.IO) {
        dao.clearAllInterrupts()
    }

    // ── Backup / Restore ──────────────────────────────────────────────────────

    /** Returns every task (active + completed) for export. */
    suspend fun getAllTasksForBackup(): List<Task> = withContext(Dispatchers.IO) {
        dao.getAllTasksForBackup()
    }

    /**
     * Replaces the entire database with [tasks].
     * Runs inside a single IO coroutine so the DB is never left half-written.
     */
    suspend fun restoreFromBackup(tasks: List<Task>) = withContext(Dispatchers.IO) {
        dao.deleteAllTasks()
        for (task in tasks) {
            dao.insert(task)
        }
    }

    /**
     * Live-sync variant of [restoreFromBackup].
     *
     * Differences from the regular backup restore:
     *  • Does NOT pause the timer or restart the app — the ViewModel handles
     *    any in-memory state update via [MultiUserSyncManager.importEvent].
     *  • Preserves `isRunning`, `accumulatedMs`, and `startTimeEpoch` as-is
     *    (they were serialised by [BackupManager.toSyncJson]).
     */
    suspend fun restoreFromSyncBackup(tasks: List<Task>) = withContext(Dispatchers.IO) {
        dao.stopAllRunning()   // clear stale flags before the replace
        dao.deleteAllTasks()
        for (task in tasks) {
            dao.insert(task)
        }
    }
}
