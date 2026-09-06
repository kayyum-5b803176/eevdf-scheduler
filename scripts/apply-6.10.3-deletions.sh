#!/usr/bin/env bash
#
# Deletions for v6.10.3 — build-fix release.
#
# 1. capabilities/notice-phase/ retired entirely — merged into task-list-screen
#    (NoticeStateMachine + TaskViewModel were never actually separable; this
#    is the same mistake class as v6.10.2s task-scheduling/task-storage cycle,
#    just missed because notice-phase compiled in isolation before task-storage
#    existed to expose the real coupling).
# 2. backup-manager.kt had one stale com.eevdf.data.task.Task import left over
#    from before task-storage existed — fixed in place, no deletion needed.
#
# Run AFTER confirming the build succeeds with these changes in place.

set -euo pipefail

echo "v6.10.3: removing capabilities/notice-phase entirely (merged into task-list-screen)"
rm -rf capabilities/notice-phase

echo "v6.10.3: done."
