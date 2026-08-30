#!/usr/bin/env bash
#
# Run this against your local checkout BEFORE extracting/copying in the
# v5.13.0 full zip. Phase 6 restructured feature/task from a flat layout
# into list/, addtask/, group/ subpackages (adapter/, notice/, timer/ already
# existed and got internal renames only, no path change).
#
# This assumes you're starting from v5.12.0 (already had apply-5.12.0-deletions.sh
# applied). If you're coming from an earlier version, run that one first.

set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 1

echo "Deleting feature/task's old flat-layout files (superseded by v5.13.0)..."

TASK="feature/src/main/task/kotlin/com/eevdf/feature/task"

# Old top-level files (now in list/, addtask/, or group/)
rm -f "$TASK/AddTaskActivity.kt"
rm -f "$TASK/AddTaskCategoryPrioritySection.kt"
rm -f "$TASK/AddTaskConfigSection.kt"
rm -f "$TASK/AddTaskGroupSection.kt"
rm -f "$TASK/AddTaskInterruptSection.kt"
rm -f "$TASK/AddTaskLoadFactorSection.kt"
rm -f "$TASK/AddTaskPinnedShareSection.kt"
rm -f "$TASK/AddTaskQuotaSection.kt"
rm -f "$TASK/AddTaskSaveHandler.kt"
rm -f "$TASK/AddTaskSchedulerSection.kt"
rm -f "$TASK/AddTaskTimeSliceSection.kt"
rm -f "$TASK/AddTaskTypeSection.kt"
rm -f "$TASK/GroupPickerDialog.kt"
rm -f "$TASK/GroupTaskPrefs.kt"
rm -f "$TASK/MainActivity.kt"
rm -f "$TASK/QueueLastRunDelegate.kt"
rm -f "$TASK/RecentGroupPrefs.kt"
rm -f "$TASK/TaskCallSwitchDelegate.kt"
rm -f "$TASK/TaskGroupExpandDelegate.kt"
rm -f "$TASK/TaskListBuilderDelegate.kt"
rm -f "$TASK/TaskSchedulerDelegate.kt"
rm -f "$TASK/TaskSettingsDelegate.kt"
rm -f "$TASK/TaskSortHelper.kt"
rm -f "$TASK/TaskViewModel.kt"

# Old adapter/, notice/, timer/ filenames (same folder, renamed in place)
rm -f "$TASK/adapter/TaskAdapterBindHelpers.kt"
rm -f "$TASK/adapter/TaskAdapterDisplayPrefs.kt"
rm -f "$TASK/adapter/TaskAdapterFormatters.kt"
rm -f "$TASK/adapter/TaskAdapterNoticeSegments.kt"
rm -f "$TASK/adapter/TaskAdapterUnitFormat.kt"
rm -f "$TASK/notice/TaskNoticeStateMachine.kt"
rm -f "$TASK/timer/TaskInterruptDelegate.kt"

echo "Done. Now extract or copy in the v5.13.0 full zip contents."
