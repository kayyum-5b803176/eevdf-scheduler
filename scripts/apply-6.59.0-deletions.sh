#!/usr/bin/env bash
#
# Deletions for v6.59.0 — axis inclusion is an ACL now.
#
# The 6.55.0 "Axis inclusion…" checklist dialog and its overflow-menu entry are replaced by an ACL tab
# on the edit screen (axis-acl-section.kt), the same screen a hardlink's ACL uses. axis-checklist-test.kt
# tested AxisChecklist, which only the dialog used.
#
# Run this against your local checkout, after extracting v6.59.0's zip on top of it.
# Safe to run twice.

set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 1

echo "Deleting the retired axis inclusion dialog (v6.59.0)..."

rm -f capabilities/add-task-screen/src/main/kotlin/com/eevdf/capabilities/addtaskscreen/axis-inclusion-dialog.kt
rm -f capabilities/add-task-screen/src/main/res/menu/menu_add_task.xml
rmdir capabilities/add-task-screen/src/main/res/menu 2>/dev/null
rm -f capabilities/task-storage/src/test/kotlin/com/eevdf/capabilities/taskstorage/axis-checklist-test.kt

echo "Done."
