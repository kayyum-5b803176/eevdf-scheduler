#!/usr/bin/env bash
#
# Deletions for v6.10.5 — build-fix only. Nothing to delete.
#
# Regression from my own process, not a new discovery: v6.10.3 rebuilt
# capabilities/task-list-screen/build.gradle.kts from the pre-v6.10.1
# baseline (to remove the notice-phase line) instead of from the v6.10.1-
# patched version, silently reintroducing the stale ":contract" dependency
# that v6.10.1 had already removed.
#
# Audited every capability build.gradle.kts touched by v6.10.1 against every
# later patch to confirm this was the only one silently reverted.

set -euo pipefail
echo "v6.10.5: no deletions — build-file fix only."
