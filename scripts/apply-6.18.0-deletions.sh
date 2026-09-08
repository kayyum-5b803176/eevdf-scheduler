#!/usr/bin/env bash
#
# Deletions for v6.18.0 — notification capability redecomposition.
#
# capabilities/notification/ stays (it's now genuinely generic: channel
# creation + banner/full-screen builders + the one real bus action,
# cancelling). These 6 files move OUT of it:
#   alarm-reliability-checker.kt   -> capabilities/permissions/ (renamed
#                                      PermissionChecker — every check in it
#                                      except canUseFullScreenIntent was
#                                      never notification-specific)
#   app-foreground-tracker.kt      -> capabilities/alarm-ringer/
#   foreground-app-detector.kt     -> capabilities/alarm-ringer/
#   alarm-notification-policy.kt   -> capabilities/alarm-ringer/
#   alarm-delivery-log.kt          -> capabilities/alarm-ringer/
# (all four moved because alarm-ringer was and is their only real consumer —
#  the decision they support is now a local call chain again, not a bus
#  request/response, since it no longer crosses a capability boundary)
#
# 2 files are deleted outright, no replacement:
#   alarm-delivery-handler.kt              (its two actions are now either a
#                                            local call in AlarmForegroundService
#                                            or the generic NotificationCancelHandler)
#   alarm-notification-decision-responder.kt (decision moved local, see above)
#   kernel/.../requests.kt                 (its one populated RequestTopic,
#                                            ALARM_NOTIFICATION_DECISION, is
#                                            gone now that the decision is
#                                            local; the generic request()/
#                                            respondTo() mechanism itself
#                                            stays in kernel/event-bus/bus.kt,
#                                            available for a future real need)
#
# Every real import, project(...) dependency, and settings.gradle.kts entry
# was already repointed as part of this version's zip — this script only
# removes the now-superseded old file paths, which a zip can't do on its own.
#
# Run this against your local checkout, after extracting v6.18.0's zip on
# top of it — the new file locations need to already be in place first.

set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 1

echo "Deleting files superseded by the v6.18.0 notification redecomposition..."

rm -f capabilities/notification/src/main/kotlin/com/eevdf/capabilities/notification/alarm-reliability-checker.kt
rm -f capabilities/notification/src/main/kotlin/com/eevdf/capabilities/notification/app-foreground-tracker.kt
rm -f capabilities/notification/src/main/kotlin/com/eevdf/capabilities/notification/foreground-app-detector.kt
rm -f capabilities/notification/src/main/kotlin/com/eevdf/capabilities/notification/alarm-notification-policy.kt
rm -f capabilities/notification/src/main/kotlin/com/eevdf/capabilities/notification/alarm-delivery-log.kt
rm -f capabilities/notification/src/main/kotlin/com/eevdf/capabilities/notification/alarm-delivery-handler.kt
rm -f capabilities/notification/src/main/kotlin/com/eevdf/capabilities/notification/alarm-notification-decision-responder.kt
rm -f kernel/src/main/kotlin/com/eevdf/kernel/event-bus/requests.kt

echo "Done. capabilities/notification/ now contains only generic"
echo "notification construction/cancellation code; the moved classes live in"
echo "capabilities/alarm-ringer/ and capabilities/permissions/."
