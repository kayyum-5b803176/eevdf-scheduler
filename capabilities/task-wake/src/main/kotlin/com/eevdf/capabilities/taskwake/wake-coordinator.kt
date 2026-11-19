package com.eevdf.capabilities.taskwake

import android.content.Context
import android.content.Intent
import com.eevdf.capabilities.settingsstorage.state.WakePrefs
import com.eevdf.capabilities.taskstorage.TaskRepository
import com.eevdf.capabilities.taskstorage.TaskWake
import com.eevdf.capabilities.taskstorage.TaskWakeDao
import com.eevdf.capabilities.taskstorage.WakeReturn
import com.eevdf.kernel.eventbus.CurrentTaskState
import com.eevdf.kernel.eventbus.EventBus
import com.eevdf.kernel.eventbus.TaskSelectRequest
import com.eevdf.kernel.eventbus.Topics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The brain of the scheduled wake — and this capability's ONLY bus wiring.
 *
 * It talks to the rest of the app exclusively through topics (see the KDoc of each in
 * `Topics`): it LISTENS to which task is current and whether the app is on screen, to the
 * wake icon being tapped or held, and to the edit screen's profile choice; it ANSWERS the
 * profile query; and it ASKS for things by publishing — "select this task", "ring this
 * profile", "these tasks have an armed wake". Nothing in task-list-screen, the edit screen,
 * sound or vibration calls it, and it calls none of them.
 *
 * What a wake does, end to end:
 *
 *   arm      — a row in `task_wake` (+ an AlarmManager entry, [WakeScheduler]).
 *   due      — the alarm fires (or the app is opened / the running task changes): the wake
 *              compares its task's scheduler class with the CURRENT task's.
 *                 nothing current, or the wake outranks it → the preempt card is shown
 *                 the current task is of equal or higher class → keep waiting, look again
 *                                                                when the current task changes
 *                 the wake task is already current          → cleared, nothing to ask
 *   answer   — Preempt: the current task is remembered (return stack), paused, and the wake
 *              task is SELECTED — never started; the person starts it. Select (nothing was
 *              current): just selects. Dismiss: the wake is cleared.
 *   return   — when the wake task ends (it stops being current and nothing replaced it) the
 *              remembered task is selected again, paused, through the same placement — what
 *              INT-A's "back" does. If the person picks another task meanwhile, the pending
 *              return is dropped: they took control.
 *
 * A wake is one-shot and device-local (see [TaskWake], `SyncSchema`).
 *
 * All state changes run under one [lock]: bus handlers, receivers and the host activity can
 * call in concurrently.
 */
class WakeCoordinator(
    private val appContext: Context,
    private val bus: EventBus,
    private val repository: TaskRepository,
    private val dao: TaskWakeDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()

    @Volatile private var current: CurrentTaskState = CurrentTaskState()
    @Volatile private var foreground: Boolean = false

    /** The task we asked task-list-screen to select; its CURRENT_TASK_CHANGED is ours, not the person's. */
    private var expectSelect: String? = null

    /** The wake whose card is up (or whose notification was posted), so it is not presented twice. */
    @Volatile private var presenting: String? = null

    /** returnTaskId → the placement it was current through, like INT-A keeps in memory (a reboot falls back to the real one). */
    private val returnPlacement = HashMap<String, Pair<String?, String?>>()

    init {
        foreground = bus.getLast(Topics.APP_FOREGROUND_CHANGED) ?: false

        bus.subscribe(Topics.CURRENT_TASK_CHANGED, CAPABILITY_ID) { onCurrentChanged(it) }
        bus.subscribe(Topics.APP_FOREGROUND_CHANGED, CAPABILITY_ID) { isForeground ->
            foreground = isForeground
            if (isForeground) { rearmAll(); evaluate() }
        }
        bus.subscribe(Topics.WAKE_EDIT_REQUESTED, CAPABILITY_ID)  { startHost(WakeHostActivity.MODE_PROMPT, it) }
        bus.subscribe(Topics.WAKE_STATE_REQUESTED, CAPABILITY_ID) { startHost(WakeHostActivity.MODE_STATE, it) }
        bus.subscribe(Topics.WAKE_PROFILE_SET, CAPABILITY_ID) { setProfile(it.taskId, it.profile) }
        bus.respondTo(Topics.WAKE_PROFILE_QUERY, CAPABILITY_ID) { taskId ->
            dao.get(taskId)?.profile ?: TaskWake.PROFILE_ALARM
        }

        // Cold start: AlarmManager forgot nothing unless the phone rebooted, but re-arming is idempotent.
        scope.launch {
            rearmAll()
            publishArmed()
            evaluate()
        }
    }

    // ── Entered by the OS ─────────────────────────────────────────────────────

    /** A wake's AlarmManager entry fired (the receiver is waiting for this to return). */
    suspend fun onWakeDue() = evaluate()

    /** The phone booted: put future wakes back (if the setting allows) and report the missed ones (always). */
    suspend fun onBoot() {
        val now = System.currentTimeMillis()
        val rearm = WakePrefs.isRearmOnBootEnabled(appContext)
        for (w in dao.getAll()) {
            if (w.phase != TaskWake.PHASE_ARMED) continue       // a CARD wake re-shows its card on the next open
            if (w.targetEpochMs > now) {
                if (rearm) WakeScheduler.schedule(appContext, w.taskId, w.targetEpochMs)
            } else {
                val name = repository.getTaskById(w.taskId)?.name ?: continue
                WakeNotifier.postMissed(appContext, w.taskId, name, w.targetEpochMs)
            }
        }
    }

    // ── Entered by the host activity ──────────────────────────────────────────

    /** What the prompt needs: the task's name and its current wake row (null when none). */
    data class PromptData(val taskName: String, val wake: TaskWake?)

    suspend fun promptData(taskId: String): PromptData? {
        val task = repository.getTaskById(taskId) ?: return null
        return PromptData(task.name, dao.get(taskId))
    }

    suspend fun arm(taskId: String, mode: String, targetEpochMs: Long) = lock.withLock {
        dao.insertIfAbsent(TaskWake(taskId))
        val row = dao.get(taskId) ?: TaskWake(taskId)
        dao.upsert(row.copy(mode = mode, targetEpochMs = targetEpochMs, phase = TaskWake.PHASE_ARMED))
        WakeScheduler.schedule(appContext, taskId, targetEpochMs)
        publishArmed()
    }

    suspend fun disarm(taskId: String) = lock.withLock {
        clearLocked(taskId)
    }

    /** The lines of the state popup, and whether there is a wake to cancel. */
    data class StateData(val taskName: String, val lines: List<String>, val canCancel: Boolean)

    suspend fun stateData(taskId: String): StateData? {
        val task = repository.getTaskById(taskId) ?: return null
        val w = dao.get(taskId)
        val profile = profileLabel(w?.profile ?: TaskWake.PROFILE_ALARM)
        if (w == null || !w.isArmed) return StateData(task.name, listOf("No wake set", "Profile: $profile"), false)
        val now = System.currentTimeMillis()
        val lines = mutableListOf(
            "Mode: ${if (w.mode == TaskWake.MODE_TIMER) "Timer" else "Alarm"}",
            "Due: ${WakeRules.formatTarget(w.targetEpochMs)}",
        )
        lines += when {
            w.phase == TaskWake.PHASE_CARD -> "Waiting for your answer on the preempt card"
            w.targetEpochMs > now -> "Armed — in ${WakeRules.formatRemaining(w.targetEpochMs - now)}"
            else -> {
                val cur = current.taskId?.let { repository.getTaskById(it) }
                if (cur != null && cur.id != task.id) "Due — waiting: \"${cur.name}\" (${classLabel(cur.schedulerClass, cur.rtPriority)}) is current and is not outranked"
                else "Due now"
            }
        }
        lines += "Profile: $profile"
        return StateData(task.name, lines, true)
    }

    /** What the preempt card shows. [profile] is the sound / vibration profile to ring. */
    data class CardData(val title: String, val subtitle: String, val primaryLabel: String, val profile: String)

    suspend fun cardData(taskId: String): CardData? {
        val w = dao.get(taskId) ?: return null
        if (w.phase != TaskWake.PHASE_CARD) return null
        val task = repository.getTaskById(taskId) ?: return null
        val cur = current.taskId?.takeIf { it != taskId }?.let { repository.getTaskById(it) }
        val missed = WakeRules.isMissed(w.targetEpochMs, System.currentTimeMillis())
        val title = if (missed) "Missed wake: ${task.name}" else "Wake: ${task.name}"
        val subtitle = if (cur != null)
            "wants to preempt \"${cur.name}\" · ${classLabel(task.schedulerClass, task.rtPriority)} over ${classLabel(cur.schedulerClass, cur.rtPriority)}"
        else
            "is due · nothing is current"
        return CardData(title, subtitle, if (cur != null) "Preempt" else "Select", w.profile)
    }

    suspend fun startAlert(profile: String) = bus.publish(Topics.WAKE_ALERT_REQUESTED, profile, CAPABILITY_ID)
    suspend fun stopAlert() = bus.publish(Topics.WAKE_ALERT_STOP_REQUESTED, Unit, CAPABILITY_ID)

    /** The host activity closed. A card left unanswered stays pending and is shown again on the next open. */
    fun onHostClosed(taskId: String?) {
        if (taskId != null && presenting == taskId) presenting = null
    }

    /** Preempt / Select: remember the current task, pause it, select the wake task — never start it. */
    suspend fun accept(taskId: String) = lock.withLock {
        stopAlert()
        presenting = null
        val task = repository.getTaskById(taskId)
        if (task == null || task.isCompleted) { clearLocked(taskId); return@withLock }
        val cur = current
        if (cur.taskId != null && cur.taskId != taskId) {
            dao.pushReturn(WakeReturn(wakeTaskId = taskId, returnTaskId = cur.taskId!!, createdAt = System.currentTimeMillis()))
            returnPlacement[cur.taskId!!] = cur.symlinkId to cur.membershipId
        }
        clearLocked(taskId)                       // one shot: disarmed, the row (and its profile) stays
        expectSelect = taskId
        bus.publish(
            Topics.TASK_SELECT_REQUESTED,
            TaskSelectRequest(taskId, pauseCurrent = true,
                message = if (cur.taskId != null && cur.taskId != taskId) "Preempted \"${cur.taskName}\" — start \"${task.name}\" when ready" else null),
            CAPABILITY_ID,
        )
    }

    suspend fun dismiss(taskId: String) = lock.withLock {
        stopAlert()
        presenting = null
        clearLocked(taskId)
    }

    // ── Entered by the bus ────────────────────────────────────────────────────

    private suspend fun setProfile(taskId: String, profile: String) = lock.withLock {
        dao.insertIfAbsent(TaskWake(taskId, profile = profile))
        dao.setProfile(taskId, profile)
    }

    private suspend fun onCurrentChanged(next: CurrentTaskState) = lock.withLock {
        val prev = current
        current = next
        val ours = expectSelect != null && next.taskId == expectSelect
        expectSelect = null                       // either consumed, or a request that never took effect
        if (!ours && prev.taskId != null && prev.taskId != next.taskId) {
            val entry = dao.topReturn(prev.taskId!!)
            if (entry != null) {
                // The wake task stopped being current. Nothing took its place → it ended: go back.
                // Something else was picked → the person took control: forget the return.
                if (next.taskId == null) performReturnLocked(prev.taskId!!, entry) else dao.clearReturns(prev.taskId!!)
            }
        }
        evaluateLocked()                          // a wake that was waiting on the old current task may fire now
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private suspend fun evaluate() = lock.withLock { evaluateLocked() }

    private suspend fun evaluateLocked() {
        val all = dao.getAll()
        // A card waiting for an answer (app restarted, or it was left unanswered) comes back first.
        all.firstOrNull { it.phase == TaskWake.PHASE_CARD }?.let { present(it); return }

        val now = System.currentTimeMillis()
        for (w in all.filter { it.phase == TaskWake.PHASE_ARMED && it.targetEpochMs in 1..now }.sortedBy { it.targetEpochMs }) {
            val task = repository.getTaskById(w.taskId)
            if (task == null || task.isCompleted) { clearLocked(w.taskId); continue }       // deleted / done: nothing to wake
            if (task.isAsleep) repository.wakeNow(task.id)                                   // a wake wakes a sleeping task first
            if (repository.getActiveTasksSync().none { it.id == task.id }) {                 // queued behind a semaphore, or hidden by a group
                clearLocked(w.taskId)
                WakeNotifier.postNotice(appContext, w.taskId, "Wake skipped: ${task.name}", "It is waiting for a slot or hidden by its group")
                continue
            }
            val cur = current.taskId?.let { repository.getTaskById(it) }
            val decision = WakeRules.decide(
                hasCurrent        = cur != null,
                currentIsWakeTask = cur?.id == task.id,
                outranksCurrent   = cur != null && WakeRules.outranks(task.schedulerClass, task.rtPriority, cur.schedulerClass, cur.rtPriority),
            )
            when (decision) {
                WakeRules.Decision.CLEAR_SELF -> clearLocked(w.taskId)
                WakeRules.Decision.BLOCKED    -> Unit                                         // keep waiting; looked at again when the current task changes
                WakeRules.Decision.SHOW_CARD  -> {
                    dao.setPhase(w.taskId, TaskWake.PHASE_CARD)                               // survives a restart
                    publishArmed()
                    present(w.copy(phase = TaskWake.PHASE_CARD))
                    return                                                                    // one card at a time
                }
            }
        }
    }

    /** In the foreground: the card (host activity). Otherwise: a notification that opens it. */
    private suspend fun present(w: TaskWake) {
        if (presenting == w.taskId) return
        presenting = w.taskId
        if (foreground) {
            startHost(WakeHostActivity.MODE_PREEMPT, w.taskId)
        } else {
            val name = repository.getTaskById(w.taskId)?.name ?: return
            if (WakeRules.isMissed(w.targetEpochMs, System.currentTimeMillis())) WakeNotifier.postMissed(appContext, w.taskId, name, w.targetEpochMs)
            else WakeNotifier.postDue(appContext, w.taskId, name)
            presenting = null                                                                 // the notification is not a card: let the next open show it
        }
    }

    private suspend fun performReturnLocked(wakeTaskId: String, entry: WakeReturn) {
        dao.deleteReturn(entry.id)
        val back = repository.getTaskById(entry.returnTaskId) ?: return
        if (back.isCompleted) return
        if (repository.getActiveTasksSync().none { it.id == back.id }) return                // asleep or queued: nothing returns, quietly
        val (symlinkId, membershipId) = returnPlacement.remove(back.id) ?: (null to null)
        expectSelect = back.id
        bus.publish(
            Topics.TASK_SELECT_REQUESTED,
            TaskSelectRequest(back.id, pauseCurrent = false, symlinkId = symlinkId, membershipId = membershipId,
                message = "Returned to \"${back.name}\""),
            CAPABILITY_ID,
        )
    }

    /** Disarms (keeps the row and its profile), drops the OS alarm and the notification. Call under [lock]. */
    private suspend fun clearLocked(taskId: String) {
        dao.clearWake(taskId)
        WakeScheduler.cancel(appContext, taskId)
        WakeNotifier.cancel(appContext, taskId)
        if (presenting == taskId) presenting = null
        publishArmed()
    }

    private suspend fun rearmAll() {
        val now = System.currentTimeMillis()
        for (w in dao.getAll()) if (w.phase == TaskWake.PHASE_ARMED && w.targetEpochMs > now) {
            WakeScheduler.schedule(appContext, w.taskId, w.targetEpochMs)
        }
    }

    private suspend fun publishArmed() {
        val ids = dao.getAll().filter { it.isArmed }.map { it.taskId }.toSet()
        bus.publish(Topics.WAKE_ARMED_CHANGED, ids, CAPABILITY_ID)
    }

    private fun startHost(mode: String, taskId: String) {
        appContext.startActivity(
            Intent(appContext, WakeHostActivity::class.java).apply {
                putExtra(WakeHostActivity.EXTRA_MODE, mode)
                putExtra(WakeHostActivity.EXTRA_TASK_ID, taskId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    private fun profileLabel(profile: String): String = when (profile) {
        TaskWake.PROFILE_DEFAULT      -> "Default"
        TaskWake.PROFILE_NOTIFICATION -> "Notice"
        TaskWake.PROFILE_CUSTOM       -> "Custom"
        else                          -> "Alert"
    }

    private fun classLabel(schedulerClass: String, rtPriority: Int): String = when (schedulerClass) {
        "stop_sched_class" -> "stop"
        "dl_sched_class"   -> "deadline"
        "rt_sched_class"   -> "realtime p$rtPriority"
        "idle_sched_class" -> "idle"
        else               -> "fair"
    }

    private companion object {
        const val CAPABILITY_ID = "task-wake"
    }
}
