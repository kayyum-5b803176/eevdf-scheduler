#!/usr/bin/env bash
#
# Deletions for v6.4.0 — Phase 4: settings-storage (shared-substrate split).
#
# Run AFTER verifying :capabilities:settings-storage builds and all existing
# tests pass.
#
# This removes the 6 preference files that made feature/shared/ a shared
# mutable substrate blocking 8 capabilities from moving independently.
#
# NOT removed: feature/src/main/shared/kotlin/.../signals/ (BubbleEventBus.kt,
# CallEvents.kt). Those two ad-hoc buses are retired in Phase 7, together with
# task-list-screen — their replacement is behavioral (StateFlow polling and a
# LiveData singleton become real kernel bus topics), not a file move, and
# every one of their call sites lives inside task-list-screen or
# call-autoswitch. Splitting that work away from those capabilities' own
# migration would mean editing the same files twice.

set -euo pipefail

echo "v6.4.0: removing old shared preference files (now capabilities/settings-storage/)"
rm -f feature/src/main/shared/kotlin/com/eevdf/feature/shared/AppPreferences.kt
rm -rf feature/src/main/shared/kotlin/com/eevdf/feature/shared/prefs

echo "v6.4.0: done."
echo ""
echo "feature/src/main/shared/kotlin/.../signals/ deliberately left in place —"
echo "retired in Phase 7 alongside task-list-screen (see comment above)."
