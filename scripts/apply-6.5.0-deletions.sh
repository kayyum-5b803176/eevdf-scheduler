#!/usr/bin/env bash
#
# Deletions for v6.5.0 — Phase 5: group-picker, navigation-routes,
# feature-toggles, and the retirement of :shared.
#
# Run AFTER verifying the three new capability modules build and tests pass.

set -euo pipefail

echo "v6.5.0: removing old group-picker source + its misfiled resources"
rm -rf feature/src/main/task/kotlin/com/eevdf/feature/task/group
rm -f feature/src/main/task/res/layout/dialog_group_picker.xml \
      feature/src/main/task/res/layout/item_group_picker_divider.xml \
      feature/src/main/task/res/layout/item_group_picker_entry.xml \
      feature/src/main/task/res/layout/item_group_picker_header.xml \
      feature/src/main/task/res/drawable/bg_group_picker_button.xml

echo "v6.5.0: removing old navigation-routes source"
rm -f contract/src/main/kotlin/com/eevdf/contract/nav/AppRoutes.kt
rmdir --ignore-fail-on-non-empty contract/src/main/kotlin/com/eevdf/contract/nav || true
rm -rf app/src/test/kotlin/com/eevdf/app/contract

echo "v6.5.0: removing old feature-toggles source"
rm -f shared/src/main/kotlin/com/eevdf/shared/FeatureFlag.kt
rm -rf app/src/main/kotlin/com/eevdf/app/core

echo "v6.5.0: retiring :shared entirely"
# SafeRun.kt: CrashIsolation moved to kernel/crash-guard/crash-isolation.kt.
# Its safeFeature/safeFeatureOr helpers were NOT migrated — they were
# 'internal' with zero call sites outside :shared (their own KDoc says so),
# and runIsolated now covers that role as an enforced mechanism.
#
# DurationFormat.kt: dead code. Zero call sites anywhere in the project —
# verified by grep across all modules. Deleted rather than given a new home
# in design-system as the Phase -1 audit had tentatively proposed.
rm -rf shared

echo "v6.5.0: done."
echo ""
echo "NOT removed — still in use, scheduled for later phases:"
echo "  - contract/control/{AlarmController,OverlayController,AlarmActions}.kt"
echo "    (bus-ified in Phases 7-9 with alarm-ringer and call-autoswitch)"
echo "  - feature/src/main/shared/kotlin/.../signals/ (Phase 7, with task-list-screen)"
echo "  - feature/task/notice/ — notice-phase is blocked on TaskViewModel (Phase 7)"
