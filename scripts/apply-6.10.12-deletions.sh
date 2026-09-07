#!/usr/bin/env bash
#
# Deletions for v6.10.12 — documentation only. Nothing to delete.
#
# Adds docs/manual/EVENT-BUS-ARCHITECTURE.md: a handoff document covering
# the actual current state of the microkernel migration and event bus —
# including which topics are genuinely wired vs. declared-only, the classes
# of mistakes made during the v6.10.x patch series (stale-base layering,
# late-discovered dependency cycles, .xml/.pro sweep blind spots, the Hilt
# Application-module constraint), and recommended next steps.
#
# This is the one explicit exception to "never touch docs/" — added at
# direct request, not unprompted.

set -euo pipefail
echo "v6.10.12: no deletions — new documentation file only."
