#!/usr/bin/env bash
#
# v4.4.0 moved 14 files to new packages. Unzipping writes the NEW copies but
# cannot remove the OLD ones — leaving both on disk means two declarations of
# the same class and a wall of "Redeclaration" / "Conflicting overloads" errors.
#
# Run this once from the project root, immediately after unzipping.

set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

OLD=(
  "app/src/main/kotlin/com/eevdf/app/feature/task/timer/TimerState.kt"
  "app/src/main/kotlin/com/eevdf/app/feature/task/timer/TaskTimerExt.kt"
  "app/src/main/kotlin/com/eevdf/app/feature/notification/NotificationHelper.kt"
  "app/src/main/kotlin/com/eevdf/app/feature/settings/SoundManager.kt"
  "app/src/main/kotlin/com/eevdf/app/feature/settings/VibrationManager.kt"
  "app/src/main/kotlin/com/eevdf/app/feature/settings/UiCustomizationPrefs.kt"
  "app/src/main/kotlin/com/eevdf/app/feature/settings/QuickActionPrefs.kt"
  "app/src/main/kotlin/com/eevdf/app/feature/settings/HardwareKeyPrefs.kt"
  "app/src/main/kotlin/com/eevdf/app/feature/settings/TaskSettingsDelegate.kt"
  "app/src/main/kotlin/com/eevdf/app/feature/autoswitch/AutoSwitchPrefs.kt"
  "app/src/main/kotlin/com/eevdf/app/feature/autoswitch/BubbleEventBus.kt"
  "app/src/main/kotlin/com/eevdf/app/feature/autoswitch/CallEvents.kt"
  "app/src/main/kotlin/com/eevdf/app/feature/autoswitch/TaskCallSwitchDelegate.kt"
  "app/src/main/kotlin/com/eevdf/app/feature/task/RecentGroupPrefs.kt"
)

removed=0
for f in "${OLD[@]}"; do
  if [ -f "$f" ]; then rm -f "$f"; echo "  removed $f"; removed=$((removed+1))
  else echo "  already gone: $f"; fi
done

# feature/notification held only NotificationHelper and is now empty.
rmdir app/src/main/kotlin/com/eevdf/app/feature/notification 2>/dev/null \
  && echo "  removed empty dir app/.../feature/notification" || true

echo
echo "Removed $removed of ${#OLD[@]} superseded files."
echo "Now run: ./gradlew clean && ./gradlew :app:assembleDebug"
