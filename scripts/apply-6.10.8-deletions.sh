#!/usr/bin/env bash
#
# Deletions for v6.10.8 — build-fix only. Nothing to delete.
#
# This release fixes 10 files in capabilities/task-list-screen/, none of them
# by chaining another "grab the latest patch" lookup -- each file's correct
# base was traced explicitly through every patch in order (phase8-build,
# phase9-build, phase10-build, v6.10.1 .. v6.10.7) to find the true latest
# version before applying new fixes on top. Full detail:
#
# STALE IMPORTS (mechanical, no behavior change):
#   - main-activity.kt: VibrationManager (-> feedback-cues)
#   - bubble-tap-delegate.kt: AutoSwitchPrefs (-> settings-storage)
#   - menu-sync-delegate.kt: SyncState (-> multi-device-sync)
#   - list-builder-delegate.kt, bind-helpers.kt, task-adapter.kt,
#     task-view-model.kt: EEVDFScheduler / RtScheduler / SchedulerStats /
#     MEMBERSHIP_SYNTHETIC_PREFIX (-> task-storage/scheduling, moved there
#     in v6.10.2's cycle fix)
#   - notice-segments.kt, task-adapter.kt: NoticePhase (now same-package,
#     merged in v6.10.3 -- import was simply dead weight)
#   - observer-delegate.kt: TaskDisplayItem inline FQN (-> task-storage)
#   - bind-helpers.kt: DesignTokens KDoc mentions (cosmetic only)
#
# REAL FIXES (behavior/architecture, not just paths):
#   - notice-state-machine.kt (v6.10.7, already shipped): bus-ified alarm
#     calls that had reverted to the pre-Phase-9 direct-call form.
#   - main-activity.kt: the old alarmStopReceiver (a raw BroadcastReceiver
#     for a local ACTION_STOP_ALARM intent, driven by the now-fully-retired
#     AlarmActions object) is GONE. Replaced with a bus subscription to
#     Topics.ALARM_STOPPED, torn down by the same unsubscribeAll(CAPABILITY_ID)
#     call already used for BUBBLE_TAPPED -- no separate lifecycle to manage.
#   - main-activity.kt: the two `Intent(this, AddTaskActivity::class.java)`
#     direct-class launches are GONE. Replaced with
#     `Intent().setClassName(this, AppRoutes.ADD_TASK)` -- the same pattern
#     every other screen (Stats, Settings, Links) already used. This one
#     predates the whole redesign; add-task-screen already depends on
#     task-list-screen, so adding the reverse dependency instead would have
#     created a real cycle.
#   - observer-delegate.kt: two `return@observe` labels, leftover from
#     before Phase 7 converted this to a bus subscription, corrected to
#     `return@subscribe`.
#   - call-switch-delegate.kt: `androidx.lifecycle.viewModelScope` and
#     `kotlinx.coroutines.launch` were never imported -- vm.viewModelScope
#     was always correctly written, just unresolvable.
#
# NEW, FLAGGED, ONE-WAY DEPENDENCIES (verified no reverse edge exists;
# each documented in task-list-screen/build.gradle.kts as debt, not fixed
# here -- same treatment as add-task-screen in v6.10.6):
#   - capabilities:multi-device-sync (TaskViewModel calls MultiUserSyncManager
#     and reads SyncState directly)
#   - capabilities:alarm-ringer (reads two Intent-extra-key string constants
#     off AlarmActivity -- narrow, not behavioral)

set -euo pipefail
echo "v6.10.8: no deletions — source-file and build-file fixes only."
