#!/usr/bin/env bash
#
# Deletions for v6.8.0 — Phase 8: task-list-screen, add-task-screen,
# countdown-timer, notice-phase, links-screen, backup-restore.
#
# Run AFTER verifying all 6 new modules build and the app launches, the task
# list renders, add/edit/timer/notice flow works, links and backup work.

set -euo pipefail

echo "v6.8.0: removing old task/list, task/adapter, task/addtask, task/timer, task/notice"
rm -rf feature/src/main/task/kotlin/com/eevdf/feature/task/list
rm -rf feature/src/main/task/kotlin/com/eevdf/feature/task/adapter
rm -rf feature/src/main/task/kotlin/com/eevdf/feature/task/addtask
rm -rf feature/src/main/task/kotlin/com/eevdf/feature/task/timer
rm -rf feature/src/main/task/kotlin/com/eevdf/feature/task/notice

echo "v6.8.0: removing old task/res (now split across task-list-screen, add-task-screen, links-screen)"
rm -rf feature/src/main/task/res

echo "v6.8.0: removing old links + backup subfeatures entirely"
rm -rf feature/src/main/links
rm -rf feature/src/main/backup
rm -rf data/src/main/kotlin/com/eevdf/data/backup
rm -rf data/src/test/kotlin/com/eevdf/data/backup

echo "v6.8.0: done."
echo ""
echo "feature/src/main/task/ now contains ONLY what's left unmigrated in :feature"
echo "(nothing — the 'task' subfeature directory itself can be removed if empty"
echo "after this script runs)."
rmdir --ignore-fail-on-non-empty feature/src/main/task/kotlin/com/eevdf/feature/task 2>/dev/null || true
rmdir --ignore-fail-on-non-empty feature/src/main/task/kotlin/com/eevdf/feature 2>/dev/null || true
rmdir --ignore-fail-on-non-empty feature/src/main/task/kotlin/com/eevdf 2>/dev/null || true
rmdir --ignore-fail-on-non-empty feature/src/main/task/kotlin/com 2>/dev/null || true
rmdir --ignore-fail-on-non-empty feature/src/main/task/kotlin 2>/dev/null || true
rmdir --ignore-fail-on-non-empty feature/src/main/task 2>/dev/null || true
