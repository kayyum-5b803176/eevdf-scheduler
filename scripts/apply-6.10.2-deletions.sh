#!/usr/bin/env bash
#
# Deletions for v6.10.2 — build-fix release.
#
# Removes the 6 Task/RunLog-aware files from task-scheduling. They moved to
# capabilities/task-storage/src/main/kotlin/.../taskstorage/scheduling/,
# because keeping them in task-scheduling created a Gradle dependency CYCLE
# (task-scheduling needed Task; task-storage needed SchedTask).
#
# Run AFTER confirming the build succeeds with the new files in place.

set -euo pipefail
TS=capabilities/task-scheduling/src/main/kotlin/com/eevdf/capabilities/taskscheduling

echo "v6.10.2: removing Task-aware files from task-scheduling (now task-storage/scheduling/)"
rm -f $TS/load-average.kt \
      $TS/load-ewma-reconstructor.kt \
      $TS/run-eevdf-scheduler.kt \
      $TS/run-rt-scheduler.kt \
      $TS/scheduler-facade.kt \
      $TS/task-schedule-bridge.kt

echo "v6.10.2: done. task-scheduling is now pure scheduling domain with no capability deps."
