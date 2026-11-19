#!/usr/bin/env bash
#
# Deletions for v6.49.1 — the wake's preempt card moved into the timer card.
#
# In 6.49.0 the preempt card was its own view (PreemptCardView) shown by a see-through activity,
# which made it float over the screen. In 6.49.1 it is a block of the timer card itself, published
# by task-wake as wake.card-changed and rendered by the task list, so the custom view and its
# layout are gone. (WakeHostActivity stays, for the prompt and the state popup only.)
#
# Run this against your local checkout, after extracting v6.49.1's zip on top of it.
# Safe to run twice.

set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 1

WAKE=capabilities/task-wake/src/main

echo "Deleting the retired floating preempt card (v6.49.1)..."

rm -f "$WAKE/kotlin/com/eevdf/capabilities/taskwake/preempt-card-view.kt"
rm -f "$WAKE/res/layout/view_wake_preempt_card.xml"

echo "Done."
