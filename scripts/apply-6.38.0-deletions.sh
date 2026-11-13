#!/usr/bin/env bash
#
# Deletions for v6.38.0 — multi-user sync rebuilt on a change journal.
#
# The whole-database-file sync (copy the .db to the shared folder, swap it in
# on the other side, restart the app) is gone, and with it every file that
# existed only to make that survivable: the per-field conflict guard and its
# dialog model, the hand-maintained field classification and its test, the
# page-diff write statistics, the global MultiUserSyncManager object and the
# one-line handler that poked it. Their replacements shipped in v6.38.0's zip
# under the same folder (sync-engine.kt, sync-session.kt, sync-folder.kt,
# sync-coordinator.kt, …).
#
# Run this against your local checkout, after extracting v6.38.0's zip on top
# of it. Safe to run twice.

set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 1

SYNC=capabilities/multi-device-sync/src

echo "Deleting the retired whole-file sync implementation (v6.38.0)..."

rm -f "$SYNC/main/kotlin/com/eevdf/capabilities/multidevicesync/logic/multi-user-sync-manager.kt"
rm -f "$SYNC/main/kotlin/com/eevdf/capabilities/multidevicesync/logic/sync-field-guard.kt"
rm -f "$SYNC/main/kotlin/com/eevdf/capabilities/multidevicesync/logic/sync-conflict.kt"
rm -f "$SYNC/main/kotlin/com/eevdf/capabilities/multidevicesync/logic/sync-state.kt"
rm -f "$SYNC/main/kotlin/com/eevdf/capabilities/multidevicesync/logic/sync-write-stats.kt"
rm -f "$SYNC/main/kotlin/com/eevdf/capabilities/multidevicesync/logic/task-field-classification.kt"
rm -f "$SYNC/main/kotlin/com/eevdf/capabilities/multidevicesync/logic/task-saved-sync-handler.kt"
rm -f "$SYNC/test/kotlin/com/eevdf/capabilities/multidevicesync/logic/task-field-classification-test.kt"

echo "Done."
