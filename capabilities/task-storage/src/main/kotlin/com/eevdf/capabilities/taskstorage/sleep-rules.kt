package com.eevdf.capabilities.taskstorage

/**
 * Pure rules for task sleep / wake — no Android, no clock, no database.
 *
 * A task is EFFECTIVELY asleep when its own [Task.sleepUntilEpoch] is set OR any
 * ancestor group's is. A group's sleep hides its whole subtree without ever
 * writing to the children, so each child keeps its own sleep state separate
 * from the group's (a child's own timer is never shortened or extended by the
 * group sleeping).
 *
 * "Asleep" is the DATABASE state (`sleepUntilEpoch > 0`), not a comparison with
 * the clock: the wake pass ([dueForWake] → TaskRepository.wakeExpired) is what
 * flips it back, and doing that as an explicit step is what lets the task be
 * re-placed in its queue exactly once at the moment it comes back.
 */
object SleepRules {

    /** Ids of every task that is effectively asleep (own sleep or an asleep ancestor). */
    fun asleepIds(all: List<Task>): Set<String> {
        if (all.none { it.isAsleep }) return emptySet()
        val byId = all.associateBy { it.id }
        val memo = HashMap<String, Boolean>(all.size)
        fun asleep(task: Task, visiting: MutableSet<String>): Boolean {
            memo[task.id]?.let { return it }
            if (!visiting.add(task.id)) return false          // defensive: parent cycle
            val result = task.isAsleep ||
                (task.parentId?.let { byId[it] }?.let { asleep(it, visiting) } ?: false)
            visiting.remove(task.id)
            memo[task.id] = result
            return result
        }
        return all.filter { asleep(it, mutableSetOf()) }.mapTo(HashSet()) { it.id }
    }

    /** [all] without anything effectively asleep — what every list/scheduler consumer sees. */
    fun awake(all: List<Task>): List<Task> {
        val asleep = asleepIds(all)
        return if (asleep.isEmpty()) all else all.filter { it.id !in asleep }
    }

    /**
     * Tasks whose own sleep has run out and that can wake NOW: their wake time is
     * ≤ [nowMs] AND no ancestor is still asleep. A task whose own timer expired
     * while its group still sleeps stays pending and is picked up on the pass
     * after the group wakes (callers loop until this is empty).
     *
     * Ordered shallowest first, then earliest wake time, so a parent is always
     * placed before its children.
     */
    fun dueForWake(all: List<Task>, nowMs: Long): List<Task> {
        val sleeping = all.filter { it.isAsleep && it.sleepUntilEpoch <= nowMs }
        if (sleeping.isEmpty()) return emptyList()
        val byId = all.associateBy { it.id }
        fun ancestorAsleep(task: Task): Boolean {
            var p = task.parentId?.let { byId[it] }
            var guard = 0
            while (p != null && guard++ < 10_000) {
                if (p.isAsleep) return true
                p = p.parentId?.let { byId[it] }
            }
            return false
        }
        fun depth(task: Task): Int {
            var d = 0
            var p = task.parentId?.let { byId[it] }
            while (p != null && d < 10_000) { d++; p = p.parentId?.let { byId[it] } }
            return d
        }
        return sleeping.filter { !ancestorAsleep(it) }
            .sortedWith(compareBy<Task>({ depth(it) }, { it.sleepUntilEpoch }))
    }

    /**
     * Roots of the Sleep tab: every task whose OWN sleep is active and that has no
     * asleep ancestor (a child under a sleeping group is reached through its
     * group, not listed twice). Soonest wake first.
     */
    fun sleepRoots(all: List<Task>): List<Task> {
        val byId = all.associateBy { it.id }
        fun ancestorAsleep(task: Task): Boolean {
            var p = task.parentId?.let { byId[it] }
            var guard = 0
            while (p != null && guard++ < 10_000) {
                if (p.isAsleep) return true
                p = p.parentId?.let { byId[it] }
            }
            return false
        }
        return all.filter { it.isAsleep && !ancestorAsleep(it) }.sortedBy { it.sleepUntilEpoch }
    }
}
