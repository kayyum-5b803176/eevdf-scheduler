#!/usr/bin/env bash
#
# Deletions for v6.10.7 — build-fix only. Nothing to delete.
#
# Another instance of the v6.10.5 mistake class: v6.10.3 rebuilt
# task-view-model.kt and notice-state-machine.kt from phase8/phase9 baselines
# that PRE-DATE v6.10.0s move of AlarmRingingQuery out of the retired
# :contract module into kernel/contracts/. That silently reintroduced
# `import com.eevdf.contract.control.AlarmRingingQuery` — a dead reference
# once :contract no longer exists. Hilts KSP reported this as the opaque
# "error.NonExistentClass" rather than a normal unresolved-import error,
# which is why it surfaced late.
#
# Full-tree swept for "com.eevdf.contract." across every patch shipped so
# far (v6.10.1 through v6.10.6) to confirm these two files were the only
# remaining instances.

set -euo pipefail
echo "v6.10.7: no deletions — source-file fix only."
