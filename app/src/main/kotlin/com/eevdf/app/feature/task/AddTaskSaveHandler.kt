package com.eevdf.app.feature.task

import android.view.View
import android.widget.Toast
import com.eevdf.data.task.Task
import com.eevdf.data.scheduler.EEVDFScheduler
import com.eevdf.data.scheduler.RtScheduler

/**
 * Save handler for [AddTaskActivity].
 *
 * Separated so validation rule changes (e.g. new required field, changed
 * constraint on quota vs period) or new fields added to the Task model don't
 * require touching the core activity file or any section setup file.
 *
 * [saveTask] reads form state from all section fields, validates, and either
 * returns early with an inline error or persists via the ViewModel and finishes.
 *
 * Depends on package-level helpers from sibling section files:
 *   • [parseDelayInput] / [formatDelaySecs]    — AddTaskTypeSection.kt
 *   • [parseQuotaInput] / [formatQuotaDuration] — AddTaskQuotaSection.kt
 *   • [schedulerClassValues]                   — AddTaskSchedulerSection.kt
 *   • [noticeResumeTypeValues]                 — AddTaskTypeSection.kt
 */

internal fun AddTaskActivity.saveTask() {

    // ── Basic validation ──────────────────────────────────────────────────────
    val name = etName.text.toString().trim()
    if (name.isEmpty()) { etName.error = "Task name required"; return }

    val h = etHours.text.toString().toLongOrNull() ?: 0L
    val m = etMinutes.text.toString().toLongOrNull() ?: 0L
    val s = etSeconds.text.toString().toLongOrNull() ?: 0L
    val totalSeconds = h * 3600 + m * 60 + s
    if (totalSeconds <= 0) {
        Toast.makeText(this, "Please set a time slice > 0", Toast.LENGTH_SHORT).show()
        return
    }

    val priority    = sliderPriority.value.toInt()
    val description = etDescription.text.toString().trim()
    val isGroup     = if (groupsEnabled) switchIsGroup.isChecked else false
    val parentId: String? = if (groupsEnabled) selectedParentId else null

    // ── Task type / notice ────────────────────────────────────────────────────
    val notifDelaySecs = if (selectedTaskType == "NOTIFICATION")
        parseDelayInput(etNotifDelay.text.toString()) else 0L
    val notifRestSecs  = if (selectedTaskType == "NOTIFICATION")
        parseDelayInput(etNoticeRest.text.toString()) else 0L
    val notifRepeat    = if (selectedTaskType == "NOTIFICATION")
        (etNoticeRepeat.text.toString().toIntOrNull() ?: 0).coerceIn(0, 12) else 0
    val notifResumeType = if (selectedTaskType == "NOTIFICATION")
        noticeResumeTypeValues.getOrElse(spinnerNoticeResumeType.selectedItemPosition) { "MIDDLE" }
    else "MIDDLE"

    // ── Pinned share ──────────────────────────────────────────────────────────
    // null if field empty (auto-float), else validated 0.01–99.99
    val pinnedShareRaw = etPinnedShare.text.toString().toDoubleOrNull()
    val pinnedShare: Double? = if (pinnedShareRaw != null) {
        val clamped = pinnedShareRaw.coerceIn(0.01, 99.99)
        val tasks   = viewModel.activeTasks.value ?: emptyList()
        val selectedParentId: String? = if (groupsEnabled) selectedParentId else null
        val otherPinned = EEVDFScheduler.otherPinnedTotal(tasks, existingTaskId, selectedParentId)
        if (otherPinned + clamped > 100.0) {
            tvPinnedShareWarning.text =
                "Cannot save: total sibling pinned share would be " +
                "${"%.2f".format(otherPinned + clamped)}%. " +
                "Reduce this or sibling pinned tasks so total ≤ 100%."
            tvPinnedShareWarning.visibility = View.VISIBLE
            return
        }
        // Round to 2dp so DB and display are always consistent
        "%.2f".format(clamped).toDouble()
    } else null

    // internalWeight is set only when pinnedShare is active; cleared otherwise so
    // the task falls back to the slider-based integer priority for weight calculation.
    val internalWeight: Double? = if (pinnedShare != null) autoCalcWeight else null

    // ── Quota limit ───────────────────────────────────────────────────────────
    val quotaEnabled = switchQuotaEnabled.isChecked
    val quotaSeconds: Long
    val quotaPeriodSeconds: Long
    if (quotaEnabled) {
        val rawQuota  = parseQuotaInput(etQuota.text.toString())
        val rawPeriod = parseQuotaInput(etPeriod.text.toString())
        if (rawQuota <= 0L) {
            tvQuotaError.text = "Quota must be at least 1 second (e.g. 30m, 2h, 1d)"
            tvQuotaError.visibility = View.VISIBLE
            layoutQuotaFields.visibility = View.VISIBLE
            return
        }
        if (rawPeriod <= 0L) {
            tvQuotaError.text = "Period must be at least 1 second (e.g. 1d, 7d)"
            tvQuotaError.visibility = View.VISIBLE
            layoutQuotaFields.visibility = View.VISIBLE
            return
        }
        if (rawQuota > rawPeriod) {
            tvQuotaError.text =
                "Quota (${formatQuotaDuration(rawQuota)}) cannot exceed period (${formatQuotaDuration(rawPeriod)})"
            tvQuotaError.visibility = View.VISIBLE
            layoutQuotaFields.visibility = View.VISIBLE
            return
        }
        tvQuotaError.visibility = View.GONE
        quotaSeconds       = rawQuota
        quotaPeriodSeconds = rawPeriod
    } else {
        quotaSeconds       = 0L
        quotaPeriodSeconds = 86400L
    }

    // ── Scheduler class ───────────────────────────────────────────────────────
    val schedulerEnabled = switchSchedulerEnabled.isChecked
    val schedulerClass: String = if (schedulerEnabled)
        schedulerClassValues.getOrElse(spinnerSchedulerClass.selectedItemPosition) { "fair_sched_class" }
    else "fair_sched_class"

    var dlRuntimeSeconds  = 0L
    var dlDeadlineSeconds = 0L
    var dlPeriodSeconds   = 0L

    if (schedulerClass == "dl_sched_class") {
        val rawRuntime  = parseQuotaInput(etDlRuntime.text.toString())
        val rawDeadline = parseQuotaInput(etDlDeadline.text.toString())
        val rawPeriod   = parseQuotaInput(etDlPeriod.text.toString())
        if (rawRuntime <= 0L) {
            tvDlError.text = "Runtime must be at least 1s for SCHED_DEADLINE (e.g. 10m)"
            tvDlError.visibility = View.VISIBLE
            layoutDlFields.visibility = View.VISIBLE
            return
        }
        if (rawDeadline <= 0L) {
            tvDlError.text = "Deadline must be at least 1s for SCHED_DEADLINE (e.g. 30m)"
            tvDlError.visibility = View.VISIBLE
            layoutDlFields.visibility = View.VISIBLE
            return
        }
        if (rawRuntime > rawDeadline) {
            tvDlError.text =
                "Runtime (${formatQuotaDuration(rawRuntime)}) cannot exceed deadline (${formatQuotaDuration(rawDeadline)})"
            tvDlError.visibility = View.VISIBLE
            layoutDlFields.visibility = View.VISIBLE
            return
        }
        tvDlError.visibility = View.GONE
        dlRuntimeSeconds  = rawRuntime
        dlDeadlineSeconds = rawDeadline
        // Period defaults to deadline when left empty (mirrors Linux SCHED_DEADLINE behaviour)
        dlPeriodSeconds   = if (rawPeriod > 0L) rawPeriod else rawDeadline
    }

    // ── DL period start epoch (RT Sync) ───────────────────────────────────────
    // pendingDlPeriodStartEpoch is 0L when the user never tapped RT Sync.
    // For new tasks 0L is the right default — TaskRepository opens the first
    // period on first run.  For edits, populateSchedulerSection restored the
    // existing value into pendingDlPeriodStartEpoch, so if the user did not
    // tap RT Sync again the original period clock is carried through unchanged.
    //
    // dlRuntimeUsedSeconds: whenever RT Sync stamps a NEW epoch, the old runtime
    // counter must be zeroed — otherwise the scheduler sees "budget exhausted"
    // against the fresh period start and withholds DL priority until a full
    // period elapses.  If RT Sync was not used (epoch == 0L) the existing
    // counter is preserved so normal in-period accounting is not disturbed.
    val dlPeriodStartEpoch    = pendingDlPeriodStartEpoch
    val dlRuntimeUsedReset    = dlPeriodStartEpoch != 0L

    // ── RT fields ─────────────────────────────────────────────────────────────
    var rtPriority            = 50
    var rtPolicy              = "RR"
    var rtActiveDays          = 0
    var rtActivationHour      = 0
    var rtActivationMinute    = 0
    var rtActivationSecond    = 0
    var rtSliceTimeoutSeconds = 0L

    if (schedulerClass == "rt_sched_class") {
        rtPriority = sliderRtPriority.value.toInt()
        rtPolicy   = if (spinnerRtPolicy.selectedItemPosition == 1) "FIFO" else "RR"

        if (cbRtSun.isChecked) rtActiveDays = rtActiveDays or RtScheduler.DAY_SUN
        if (cbRtMon.isChecked) rtActiveDays = rtActiveDays or RtScheduler.DAY_MON
        if (cbRtTue.isChecked) rtActiveDays = rtActiveDays or RtScheduler.DAY_TUE
        if (cbRtWed.isChecked) rtActiveDays = rtActiveDays or RtScheduler.DAY_WED
        if (cbRtThu.isChecked) rtActiveDays = rtActiveDays or RtScheduler.DAY_THU
        if (cbRtFri.isChecked) rtActiveDays = rtActiveDays or RtScheduler.DAY_FRI
        if (cbRtSat.isChecked) rtActiveDays = rtActiveDays or RtScheduler.DAY_SAT

        if (rtActiveDays == 0) {
            tvRtError.text = "Select at least one active day"
            tvRtError.visibility = View.VISIBLE
            layoutRtFields.visibility = View.VISIBLE
            return
        }

        rtActivationHour   = (etRtHour.text.toString().toIntOrNull()   ?: 0).coerceIn(0, 23)
        rtActivationMinute = (etRtMinute.text.toString().toIntOrNull() ?: 0).coerceIn(0, 59)
        rtActivationSecond = (etRtSecond.text.toString().toIntOrNull() ?: 0).coerceIn(0, 59)

        val rawTimeout = parseQuotaInput(etRtSliceTimeout.text.toString()).coerceIn(1L, 604_800L)
        if (rawTimeout <= 0L) {
            tvRtError.text = "Slice timeout required (e.g. 30m, 2h)"
            tvRtError.visibility = View.VISIBLE
            layoutRtFields.visibility = View.VISIBLE
            return
        }
        tvRtError.visibility = View.GONE
        rtSliceTimeoutSeconds = rawTimeout

        // Clear RR state whenever RT config changes so stale index is not reused
        RtScheduler.clearRrState(prefs)
    }

    // ── Assemble and persist ──────────────────────────────────────────────────
    if (existingTask != null) {
        val updated = existingTask!!.copy(
            name             = name,
            description      = description,
            priority         = priority,
            timeSliceSeconds = totalSeconds,
            category         = selectedCategory,
            isGroup          = isGroup,
            parentId         = parentId,
            taskType         = selectedTaskType,
            notificationDelaySeconds = notifDelaySecs,
            notificationRestSeconds  = notifRestSecs,
            notificationRepeatCount  = notifRepeat,
            notificationResumeType   = notifResumeType,
            pinnedShare      = pinnedShare,
            internalWeight   = internalWeight,
            quotaSeconds       = quotaSeconds,
            quotaPeriodSeconds = quotaPeriodSeconds,
            schedulerClass     = schedulerClass,
            dlRuntimeSeconds   = dlRuntimeSeconds,
            dlDeadlineSeconds  = dlDeadlineSeconds,
            dlPeriodSeconds    = dlPeriodSeconds,
            dlPeriodStartEpoch    = dlPeriodStartEpoch,
            dlRuntimeUsedSeconds  = if (dlRuntimeUsedReset) 0L else existingTask!!.dlRuntimeUsedSeconds,
            rtPriority            = rtPriority,
            rtPolicy              = rtPolicy,
            rtActiveDays          = rtActiveDays,
            rtActivationHour      = rtActivationHour,
            rtActivationMinute    = rtActivationMinute,
            rtActivationSecond    = rtActivationSecond,
            rtSliceTimeoutSeconds = rtSliceTimeoutSeconds
        )
        // Handle interrupt assignment
        when {
            switchIsInterrupt.isChecked  -> viewModel.assignInterruptTask(updated)
            switchIsInterruptB.isChecked -> viewModel.assignInterruptTaskB(updated)
            updated.isInterrupt && updated.interruptSlot == "B" -> viewModel.clearInterruptTaskB()
            updated.isInterrupt -> viewModel.clearInterruptTask()
        }
        viewModel.updateTask(updated)
    } else {
        val task = Task(
            name             = name,
            description      = description,
            priority         = priority,
            timeSliceSeconds = totalSeconds,
            remainingSeconds = totalSeconds,
            category         = selectedCategory,
            isGroup          = isGroup,
            parentId         = parentId,
            taskType         = selectedTaskType,
            notificationDelaySeconds = notifDelaySecs,
            notificationRestSeconds  = notifRestSecs,
            notificationRepeatCount  = notifRepeat,
            notificationResumeType   = notifResumeType,
            pinnedShare      = pinnedShare,
            internalWeight   = internalWeight,
            quotaSeconds       = quotaSeconds,
            quotaPeriodSeconds = quotaPeriodSeconds,
            schedulerClass     = schedulerClass,
            dlRuntimeSeconds   = dlRuntimeSeconds,
            dlDeadlineSeconds  = dlDeadlineSeconds,
            dlPeriodSeconds    = dlPeriodSeconds,
            dlPeriodStartEpoch    = dlPeriodStartEpoch,
            // dlRuntimeUsedSeconds: new tasks always start with 0 (Task default),
            // no explicit reset needed — included here for clarity.
            rtPriority            = rtPriority,
            rtPolicy              = rtPolicy,
            rtActiveDays          = rtActiveDays,
            rtActivationHour      = rtActivationHour,
            rtActivationMinute    = rtActivationMinute,
            rtActivationSecond    = rtActivationSecond,
            rtSliceTimeoutSeconds = rtSliceTimeoutSeconds
        )
        viewModel.addTask(task)
        when {
            switchIsInterrupt.isChecked  -> viewModel.assignInterruptTask(task)
            switchIsInterruptB.isChecked -> viewModel.assignInterruptTaskB(task)
        }
    }
    finish()
}
