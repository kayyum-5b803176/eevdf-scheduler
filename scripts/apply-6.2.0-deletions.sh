#!/usr/bin/env bash
#
# Deletions for v6.2.0 — Phase 2: task-storage, task-scheduling, run-history.
#
# Run AFTER verifying capabilities/task-storage, capabilities/task-scheduling,
# and capabilities/run-history build and all existing tests pass (including
# the Room migration test — confirm it resolves schemas at their new path).

set -euo pipefail

echo "v6.2.0: removing old task-storage source (now capabilities/task-storage/)"
rm -rf data/src/main/kotlin/com/eevdf/data/task
rm -rf data/src/test/kotlin/com/eevdf/data/task
rm -rf data/src/androidTest/kotlin/com/eevdf/data/task
rm -rf data/schemas/com.eevdf.data.task.TaskDatabase

echo "v6.2.0: removing old task-scheduling source (now capabilities/task-scheduling/)"
rm -rf core/src/main/kotlin/com/eevdf/core/scheduler
rm -rf data/src/main/kotlin/com/eevdf/data/scheduler
rm -f  platform/src/main/kotlin/com/eevdf/platform/scheduler/SystemClockAndRrStore.kt
rmdir --ignore-fail-on-non-empty platform/src/main/kotlin/com/eevdf/platform/scheduler || true

echo "v6.2.0: removing old run-history source (now capabilities/run-history/)"
rm -rf data/src/main/kotlin/com/eevdf/data/runlog

echo "v6.2.0: removing core/time/ — its only consumer (SystemClockAndRrStore) now"
echo "         uses kernel/clock/ instead (relocated there in Phase 0)"
rm -rf core/src/main/kotlin/com/eevdf/core/time

echo "v6.2.0: retiring :testing entirely — its 4 characterization tests + Fakes.kt"
echo "         were already just task-scheduling's own tests, now living at"
echo "         capabilities/task-scheduling/src/test/"
rm -rf testing

echo "v6.2.0: done."
echo ""
echo "NOT deleted, left for a later phase to handle explicitly:"
echo "  - core/src/main/kotlin/com/eevdf/core/platform/PlatformPorts.kt"
echo "    (AlarmPort/SoundPort/NotificationPort/VibrationPort — bus-ified in"
echo "     Phase 3/5 when alarm-ringer/reminder-notifier/feedback-cues migrate,"
echo "     not part of task-storage/task-scheduling/run-history)"
echo "  - scripts/check_architecture.sh still references old data/task,"
echo "    data/scheduler, core/scheduler paths for its DB-version/migration-count"
echo "    checks. It will need updating for the new capabilities/ paths — this"
echo "    is explicitly a Phase 6 rewrite per the migration plan, not fixed here."
