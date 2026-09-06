#!/usr/bin/env bash
#
# Deletions for v6.0.0 — Phase 0 kernel scaffold.
#
# Nothing is deleted in this release. Phase 0 only ADDS the new :kernel
# module (event-bus, supervisor, crash-guard, clock) and registers it in
# settings.gradle.kts — no existing capability code has moved yet, so there
# is nothing to retire.
#
# Kept as a no-op script (rather than omitted) to match this repo's existing
# apply-{version}-deletions.sh convention: every release that touches the
# tree gets a paired deletion script, even when it deletes nothing, so the
# scripts/ directory stays a complete, chronological record.
#
# The first REAL deletions arrive with Phase 1 (leaf capabilities move:
# feedback-cues, design-system, links-screen, stats-screens) — that release
# will delete the old platform/media/, feature/ui/, feature/links/, and
# feature/stats/ source once the new capabilities/ locations are verified
# green.

set -euo pipefail
echo "v6.0.0: no deletions — Phase 0 only added kernel/, nothing existing moved."
