package com.eevdf.capabilities.taskstorage

/**
 * Pure rules for the group semaphore — no Android, no clock, no database.
 *
 * A group with [Task.semaphoreEnabled] is a counting semaphore with
 * [Task.semaphoreLimit] slots. Its DIRECT children (leaf or group, a child group
 * costs exactly one slot whatever it contains) either HOLD a slot or WAIT:
 *
 *   • holder  — [Task.semaphoreHeld] is true. Visible and scheduled as normal.
 *   • waiter  — under an enabled semaphore, not held, not asleep, not completed.
 *               Hidden like a sleeping task: no list row, no scheduling, no share
 *               of the sibling average (see [SleepRules.hiddenIds]).
 *
 * Like `sem_wait` / `sem_post`:
 *   • A holder keeps its slot until its state changes — it is slept, marked done,
 *     deleted or moved to another group. Pause, a finished timer, a scheduler
 *     class change… do not release anything.
 *   • A release hands the slot to the OLDEST waiter (FIFO by arrival).
 *   • A child ARRIVES — and joins the back of the queue — when it is created in
 *     the group, moved into it, woken from sleep or un-done. A woken child does
 *     not get its old slot back.
 *   • Lowering the limit never evicts: holders keep their slots until they leave.
 *     Raising it admits waiters immediately.
 *   • No priority, no class, no override: realtime and deadline children queue
 *     exactly like everyone else, as a kernel semaphore does.
 *
 * Why the holder is STORED ([Task.semaphoreHeld]) instead of derived as "the
 * first N by priority": a derived set would let a newly arrived child with a
 * better deadline bump a running one, which breaks "a holder persists until its
 * state changes".
 *
 * [Task.semaphoreQueuedAt] is the arrival stamp (a strictly increasing number per
 * group, seeded from the wall clock). 0 means "never stamped": a child that was
 * already in the group when the semaphore was switched on; those rank by
 * creation time, so the oldest N become the first holders.
 *
 * Only REAL children are governed. Symlinks and hardlink placements hosted in the
 * group are not tasks of this group and never take a slot.
 */
object SemaphoreRules {

    const val MIN_LIMIT     = 1
    const val MAX_LIMIT     = 64
    const val DEFAULT_LIMIT = 4

    fun clampLimit(n: Int): Int = n.coerceIn(MIN_LIMIT, MAX_LIMIT)

    /** A change to one child's slot, produced by [plan]. */
    data class SlotChange(
        val id: String,
        val held: Boolean,
        val queuedAt: Long,
        /** True when a real waiter is being admitted from the queue: its vruntime is re-placed like a new arrival. */
        val fromQueue: Boolean,
    )

    // ── Queue order ───────────────────────────────────────────────────────────

    private fun rank(t: Task): Long = if (t.semaphoreQueuedAt > 0L) t.semaphoreQueuedAt else t.createdAt

    /** Oldest arrival first; ties broken by creation time then id so the order is deterministic. */
    private val queueOrder = Comparator<Task> { a, b ->
        val r = rank(a).compareTo(rank(b))
        if (r != 0) r else {
            val c = a.createdAt.compareTo(b.createdAt)
            if (c != 0) c else a.id.compareTo(b.id)
        }
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    /** True when [task] is waiting for a slot of [parent]'s semaphore. */
    fun isWaiting(task: Task, parent: Task?): Boolean =
        parent != null && parent.semaphoreEnabled &&
            !task.isCompleted && !task.isAsleep && !task.semaphoreHeld

    /** Children that count towards [groupId]'s semaphore: real, not completed, not asleep. */
    private fun queueMembers(all: List<Task>, groupId: String, excludeId: String? = null): List<Task> =
        all.filter { it.parentId == groupId && it.id != excludeId && !it.isCompleted && !it.isAsleep }

    /**
     * Would a child arriving in [parentId] be admitted at once? True when the
     * parent has no semaphore, or has a free slot and nobody queued ahead.
     * [excludeId] is the arriving task itself when it is already in [all].
     */
    fun admitsImmediately(parentId: String?, all: List<Task>, excludeId: String? = null): Boolean {
        val parent = parentId?.let { id -> all.firstOrNull { it.id == id } } ?: return true
        if (!parent.semaphoreEnabled) return true
        val members = queueMembers(all, parent.id, excludeId)
        return members.count { it.semaphoreHeld } < clampLimit(parent.semaphoreLimit) &&
            members.none { !it.semaphoreHeld }
    }

    /** Number of waiting DIRECT children per group id (only groups with a semaphore, only counts above 0). */
    fun waitingCounts(all: List<Task>): Map<String, Int> {
        if (all.none { it.semaphoreEnabled }) return emptyMap()
        val byId = all.associateBy { it.id }
        val out = HashMap<String, Int>()
        for (t in all) {
            val pid = t.parentId ?: continue
            if (isWaiting(t, byId[pid])) out[pid] = (out[pid] ?: 0) + 1
        }
        return out
    }

    /** 1-based queue position of every waiting child inside its own parent's queue. */
    fun queuePositions(all: List<Task>): Map<String, Int> {
        if (all.none { it.semaphoreEnabled }) return emptyMap()
        val byId = all.associateBy { it.id }
        val out = HashMap<String, Int>()
        all.filter { isWaiting(it, it.parentId?.let { id -> byId[id] }) }
            .groupBy { it.parentId }
            .values
            .forEach { queue -> queue.sortedWith(queueOrder).forEachIndexed { i, t -> out[t.id] = i + 1 } }
        return out
    }

    /**
     * Roots of the Wait list: every task that is itself waiting and has no hidden
     * ancestor (a waiting group's contents are reached through the group, like the
     * Sleep tab). Oldest arrival first.
     */
    fun waitingRoots(all: List<Task>): List<Task> {
        if (all.none { it.semaphoreEnabled }) return emptyList()
        val byId = all.associateBy { it.id }
        fun parentOf(t: Task): Task? = t.parentId?.let { byId[it] }
        fun hidden(t: Task): Boolean = t.isAsleep || isWaiting(t, parentOf(t))
        fun hiddenAncestor(t: Task): Boolean {
            var p = parentOf(t)
            var guard = 0
            while (p != null && guard++ < 10_000) {
                if (hidden(p)) return true
                p = parentOf(p)
            }
            return false
        }
        return all.filter { isWaiting(it, parentOf(it)) && !hiddenAncestor(it) }.sortedWith(queueOrder)
    }

    // ── Arrival ───────────────────────────────────────────────────────────────

    private fun nextStamp(members: List<Task>, nowMs: Long): Long =
        maxOf(nowMs, (members.maxOfOrNull { it.semaphoreQueuedAt } ?: 0L) + 1L)

    /**
     * Records [task] ARRIVING in its (current) parent: it goes to the back of the
     * queue and takes a free slot only when nobody is queued ahead. Mutates the
     * two slot fields in place. Under a parent without a semaphore both are
     * cleared. [all] is the pool WITHOUT [task] (it is excluded either way).
     */
    fun stamp(task: Task, all: List<Task>, nowMs: Long) {
        val parent = task.parentId?.let { id -> all.firstOrNull { it.id == id } }
        if (parent == null || !parent.semaphoreEnabled) {
            task.semaphoreHeld     = false
            task.semaphoreQueuedAt = 0L
            return
        }
        val members = queueMembers(all, parent.id, excludeId = task.id)
        task.semaphoreHeld     = admitsImmediately(parent.id, all, excludeId = task.id)
        task.semaphoreQueuedAt = nextStamp(members, nowMs)
    }

    /** Copy of [task] after arriving in its parent — see [stamp]. */
    fun arrive(task: Task, all: List<Task>, nowMs: Long): Task =
        task.copy().also { stamp(it, all.filter { o -> o.id != task.id }, nowMs) }

    // ── Reconcile ─────────────────────────────────────────────────────────────

    /**
     * The slot changes that bring the stored state back to the rules' invariant:
     *
     *   1. Clean-up — a child that cannot hold or queue (its parent has no
     *      semaphore, or it is asleep) loses any stale held flag / stamp.
     *   2. Admission — every enabled group with free slots admits its oldest
     *      waiters, never evicting anyone when the limit was lowered.
     *
     * [only] limits the work to those group ids (null = every group). [all] is the
     * non-completed pool WITH sleeping and waiting tasks. Pure: nothing is written.
     */
    fun plan(all: List<Task>, only: Set<String>? = null): List<SlotChange> {
        val anyConfigured = all.any { it.semaphoreEnabled }
        val anyStale      = all.any { it.semaphoreHeld || it.semaphoreQueuedAt != 0L }
        if (!anyConfigured && !anyStale) return emptyList()

        val byId = all.associateBy { it.id }
        val out  = ArrayList<SlotChange>()

        // 1. Clean-up
        for (t in all) {
            if (t.isCompleted || (!t.semaphoreHeld && t.semaphoreQueuedAt == 0L)) continue
            val pid = t.parentId
            if (only != null && (pid == null || pid !in only)) continue
            val parent = pid?.let { byId[it] }
            if (parent?.semaphoreEnabled != true || t.isAsleep) out += SlotChange(t.id, false, 0L, false)
        }

        // 2. Admission
        for (g in all) {
            if (!g.semaphoreEnabled || (only != null && g.id !in only)) continue
            val members = queueMembers(all, g.id)
            val free = clampLimit(g.semaphoreLimit) - members.count { it.semaphoreHeld }
            if (free <= 0) continue
            members.filter { !it.semaphoreHeld }
                .sortedWith(queueOrder)
                .take(free)
                .forEach { w -> out += SlotChange(w.id, true, w.semaphoreQueuedAt, fromQueue = w.semaphoreQueuedAt > 0L) }
        }
        return out
    }
}
