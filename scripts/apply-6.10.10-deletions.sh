#!/usr/bin/env bash
#
# Deletions for v6.10.10 — build-fix release.
#
# Root cause: Hilt requires the @HiltAndroidApp-annotated class to be
# compiled inside the actual com.android.application module. composition/
# uses the android-library-convention plugin, not com.android.application —
# only :app has that. Moving SchedulerApplication/ApplicationEntry into
# composition/ in Phase 10 violated this outright; it was never going to
# build, independent of any dependency wiring being correct.
#
# ApplicationEntry moves to app/src/main/kotlin/com/eevdf/app/boot/ (package
# com.eevdf.app.boot). AndroidManifest.xml's android:name is updated to
# match. composition/capability-bindings.kt is UNCHANGED and stays in
# composition/ — it's a plain @Module, which has no same-module
# restriction; only the @HiltAndroidApp entry point does.
#
# Run AFTER confirming the build succeeds with ApplicationEntry in its new
# location.

set -euo pipefail

echo "v6.10.10: removing the old composition/boot/ (ApplicationEntry now in :app)"
rm -f composition/src/main/kotlin/com/eevdf/composition/boot/application-entry.kt
rmdir --ignore-fail-on-non-empty composition/src/main/kotlin/com/eevdf/composition/boot 2>/dev/null || true

echo "v6.10.10: done."
