#!/usr/bin/env bash
#
# Phase 9 (v5.15.0): deletes 3 files with NO replacement — this is the first
# true deletion in this refactor, as opposed to every prior phase's
# renames/moves. See ARCHITECTURE.md Phase 9 for the full reasoning:
# core.scheduler.timer.TimerEngine was an abandoned prototype (its own test
# suite documented a KNOWN BUG — zero run-time crediting on expiry), not a
# finished replacement for the live com.eevdf.feature.task.timer.TimerEngine.
#
# Run this against your local checkout. Nothing needs to be added back —
# unlike every other apply-*.sh script in this repo, there is no
# corresponding "new file" to extract afterward.

set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 1

echo "Deleting confirmed-dead TimerEngine prototype (Phase 9, v5.15.0)..."

rm -f core/src/main/kotlin/com/eevdf/core/scheduler/timer/TimerEngine.kt
rm -f platform/src/main/kotlin/com/eevdf/platform/scheduler/CountdownTimerDriver.kt
rm -f testing/src/test/kotlin/com/eevdf/core/scheduler/TimerEngineCharacterizationTest.kt

rmdir core/src/main/kotlin/com/eevdf/core/scheduler/timer 2>/dev/null || true

echo "Done. com.eevdf.feature.task.timer.TimerEngine (the live implementation)"
echo "is untouched and remains the one timer implementation in the app."
