#!/usr/bin/env bash
#
# Deletions for v6.7.0 — Phase 7: retiring the two ad-hoc event buses.
#
# Run AFTER verifying the app builds and BOTH of these still work end to end:
#   1. Incoming call -> bubble overlay appears, auto-switch fires, dot colour
#      correct (this exercised BubbleEventBus's synchronous reads).
#   2. Tapping the floating bubble pauses/resumes the call task.

set -euo pipefail

echo "v6.7.0: retiring feature/shared/signals/ — replaced by kernel bus topics"
# BubbleEventBus.kt -> Topics.TIMER_RUNNING_CHANGED + Topics.BUBBLE_TAPPED.
#   Its three global mutable flags are gone entirely: each capability now
#   holds its own LatestValue snapshot, synchronised by bus events, so the
#   synchronous mid-draw reads in BubbleOverlayService still work without any
#   shared global existing.
#   Its onBubbleTap callback is gone too — that field's own KDoc carried a
#   LEAK WARNING (MainActivity had to remember to null it in onDestroy).
#   A bus subscription is torn down by capability id instead.
#
# CallEvents.kt -> Topics.PHONE_CALL_STATE_CHANGED. The manual
#   'event.value = null // consume' step disappears: bus events are delivered
#   once rather than held in a LiveData that must be drained.
rm -rf feature/src/main/shared

echo "v6.7.0: done."
echo ""
echo "feature/src/main/shared/ is now GONE entirely — its prefs left in v6.4.0,"
echo "its signals leave here. Remember to drop \"shared\" from the subfeatures"
echo "list in feature/build.gradle.kts if it is still listed there."
