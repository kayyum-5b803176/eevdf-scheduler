#!/usr/bin/env bash
#
# Deletions for v6.53.0 — Category became Identity (kind:id, system-generated).
#
# category-priority-section.kt held the category field AND the priority slider. The category half is
# gone (replaced by identity-section.kt); the slider moved, unchanged, to priority-section.kt.
# section_add_task_category.xml was the old free-text Category card; section_add_task_identity.xml
# replaces it. Leaving either behind breaks the build (duplicate setupPrioritySlider /
# updatePriorityDisplay, and a layout that references views the activity no longer has).
#
# Run this against your local checkout, after extracting v6.53.0's zip on top of it.
# Safe to run twice.

set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 1

echo "Deleting the retired Category files (v6.53.0)..."

rm -f capabilities/add-task-screen/src/main/kotlin/com/eevdf/capabilities/addtaskscreen/category-priority-section.kt
rm -f capabilities/add-task-screen/src/main/res/layout/section_add_task_category.xml

echo "Done."
