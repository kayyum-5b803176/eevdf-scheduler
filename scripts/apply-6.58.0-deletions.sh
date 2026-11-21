#!/usr/bin/env bash
#
# Deletions for v6.58.0 — later-axis entries are now REAL rows, so the machinery that rendered them as
# a separate virtual tree (6.57.0) and the descent test for the retired runtime table (6.54.0) are gone.
#
# axis-schedule-rows.kt / axis-schedule-delegate.kt built the Schedule tab's axis tree from definitions
# on every rebuild; the tree is now ordinary rows the ordinary list builders already draw.
# axis-descent-test.kt tested AxisDescent, which the real-row model replaces.
#
# Run this against your local checkout, after extracting v6.58.0's zip on top of it.
# Safe to run twice.

set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 1

echo "Deleting the retired virtual axis tree and descent test (v6.58.0)..."

rm -f capabilities/task-storage/src/main/kotlin/com/eevdf/capabilities/taskstorage/axis-schedule-rows.kt
rm -f capabilities/task-storage/src/test/kotlin/com/eevdf/capabilities/taskstorage/axis-schedule-rows-test.kt
rm -f capabilities/task-storage/src/test/kotlin/com/eevdf/capabilities/taskstorage/axis-descent-test.kt
rm -f capabilities/task-list-screen/src/main/kotlin/com/eevdf/capabilities/tasklistscreen/axis-schedule-delegate.kt

echo "Done."
