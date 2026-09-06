#!/usr/bin/env bash
#
# Deletions for v6.6.0 — Phase 6: breaking the TaskViewModel hub.
#
# Run AFTER verifying the app builds and a backup export/import round-trips
# correctly (this phase changes how that flow is sequenced).

set -euo pipefail

echo "v6.6.0: removing SortHelper from task/list (now task-storage/logic/sort-tasks.kt)"
rm -f feature/src/main/task/kotlin/com/eevdf/feature/task/list/SortHelper.kt

echo "v6.6.0: done."
echo ""
echo "No other deletions: TaskViewModel.prepareForDbExport/Import were replaced"
echo "in place (their DB half moved to task-storage's BackupCheckpointHandler,"
echo "their UI half became a bus subscription), not deleted as whole files."
