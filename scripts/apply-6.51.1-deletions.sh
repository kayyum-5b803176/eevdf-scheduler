#!/usr/bin/env bash
#
# Deletions for v6.51.1 — the wake's "select this task" handler moved out of the ViewModel.
#
# task-select-delegate.kt was created by the ViewModel, which registered its bus subscription under
# the screen's capability id; MainActivity.onStop() removes every subscription under that id, so after
# the first stop (switching apps, locking the phone) selection requests went nowhere. Its logic now
# lives in wake-links.kt, owned by the screen under an id of its own.
#
# Run this against your local checkout, after extracting v6.51.1's zip on top of it.
# Safe to run twice.

set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 1

echo "Deleting the retired ViewModel-owned select handler (v6.51.1)..."

rm -f capabilities/task-list-screen/src/main/kotlin/com/eevdf/capabilities/tasklistscreen/task-select-delegate.kt

echo "Done."
