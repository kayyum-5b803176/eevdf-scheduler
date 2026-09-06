#!/usr/bin/env bash
#
# Deletions for v6.10.6 — build-fix release.
#
# 1. capabilities/countdown-timer/src/.../interrupt-delegate.kt merged into
#    task-list-screen — same cycle pattern as v6.10.3s notice-phase merge:
#    TaskViewModel constructs InterruptDelegate(this) directly, and
#    InterruptDelegate reaches into ~10 of TaskViewModels internals. The two
#    other countdown-timer files (timer-engine.kt, timer-card-action.kt) have
#    no such coupling and stay put — task-list-screen already depends on
#    countdown-timer one-way for those.
# 2. add-task-screen gained a real Gradle dependency on task-list-screen
#    (flagged in its build.gradle.kts as pre-existing debt, not fixed here —
#    no file to delete for this one).
#
# Run AFTER confirming the build succeeds with these changes in place.

set -euo pipefail

echo "v6.10.6: removing interrupt-delegate.kt from countdown-timer (now task-list-screen)"
rm -f capabilities/countdown-timer/src/main/kotlin/com/eevdf/capabilities/countdowntimer/interrupt-delegate.kt

echo "v6.10.6: done."
