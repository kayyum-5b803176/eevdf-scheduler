#!/usr/bin/env bash
#
# Deletions for v6.50.0 — the card above the list moved into its own capability (timer-card).
#
# The timer card, the expired-alarm card and the wake card used to be three code paths in
# task-list-screen drawing one view. They are now ONE view (TimerCardView) in the new timer-card
# capability, described by card specs published on the event bus. What is gone from task-list-screen:
#
#   timer-card-delegate.kt — drew the card and wired its buttons (now TimerCardView + TimerCardPublisher)
#   res/values/ids.xml     — held only the status strip's blink tag, which moved with the strip
#
# Run this against your local checkout, after extracting v6.50.0's zip on top of it.
# Safe to run twice.

set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 1

LIST=capabilities/task-list-screen/src/main

echo "Deleting the retired card drawing code (v6.50.0)..."

rm -f "$LIST/kotlin/com/eevdf/capabilities/tasklistscreen/timer-card-delegate.kt"
rm -f "$LIST/res/values/ids.xml"

echo "Done."
