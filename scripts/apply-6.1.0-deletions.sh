#!/usr/bin/env bash
#
# Deletions for v6.1.0 — Phase 1 (partial): feedback-cues + design-system.
#
# Run this AFTER verifying the new :capabilities:feedback-cues and
# :capabilities:design-system modules build and all existing tests pass.
#
# NOTE: links-screen and stats-screens are NOT part of this release — they
# were found to have real cross-capability imports into task-storage (Phase 2),
# task-list-screen/group-picker (Phase 4), and settings-screens (Phase 5), so
# they can't move into isolated modules yet. They stay in feature/links and
# feature/stats untouched, rescheduled for after those phases land.

set -euo pipefail

echo "v6.1.0: removing old feedback-cues source (now capabilities/feedback-cues/)"
rm -f \
  platform/src/main/kotlin/com/eevdf/platform/media/SoundManager.kt \
  platform/src/main/kotlin/com/eevdf/platform/media/VibrationManager.kt
rmdir --ignore-fail-on-non-empty platform/src/main/kotlin/com/eevdf/platform/media || true

echo "v6.1.0: removing old design-system source (now capabilities/design-system/)"
rm -rf feature/src/main/ui

echo "v6.1.0: done."
