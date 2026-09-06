#!/usr/bin/env bash
#
# Deletions for v6.10.0 — Phase 10: composition layer, and the last module.
#
# Run AFTER verifying the app builds, launches, and `./gradlew verifyAll`
# passes (which now includes the rewritten architecture guard).

set -euo pipefail

echo "v6.10.0: retiring :contract entirely"
# Its last file, AlarmRingingQuery.kt, moved to
# kernel/src/main/kotlin/com/eevdf/kernel/contracts/alarm-ringing-query.kt.
# Both sides of that interface (alarm-ringer implements, task-list-screen
# consumes) live in different capabilities, so the kernel is the only place
# both are allowed to depend on.
rm -rf contract

echo "v6.10.0: removing the old app/di layer (now composition/capability-bindings.kt)"
# AppCoreModule, DatabaseModule, PlatformModule -> merged into the single
# capability-bindings.kt. RepositoryModule and SchedulerModule are DELETED,
# not merged: both contained zero bindings — every repository and scheduler
# service uses an @Inject constructor, so those two files were pure
# documentation of that fact.
rm -rf app/src/main/kotlin/com/eevdf/app/di

echo "v6.10.0: removing SchedulerApplication (now composition/boot/application-entry.kt)"
# Renamed per rule 8: the file name now says what it does rather than which
# framework class it extends. app/src/main/AndroidManifest.xml's
# android:name was updated to com.eevdf.composition.boot.ApplicationEntry.
rm -f app/src/main/kotlin/com/eevdf/app/SchedulerApplication.kt
rmdir --ignore-fail-on-non-empty app/src/main/kotlin/com/eevdf/app 2>/dev/null || true
rmdir --ignore-fail-on-non-empty app/src/main/kotlin/com/eevdf 2>/dev/null || true
rmdir --ignore-fail-on-non-empty app/src/main/kotlin/com 2>/dev/null || true
rmdir --ignore-fail-on-non-empty app/src/main/kotlin 2>/dev/null || true

echo "v6.10.0: retiring the feature-import allowlist"
# The whole point of the old allowlist was grandfathering cross-feature
# imports the compiler could not catch. Capabilities are real Gradle modules
# now — an illegal import is a build failure. The one entry it carried
# (backup -> task, in place since v4.5.0) was fixed for real in v6.6.0.
rm -f scripts/feature_import_allowlist.txt

echo "v6.10.0: done."
echo ""
echo "Root is now: app/ capabilities/ composition/ kernel/ + build-logic/"
echo "config/ docs/ gradle/ scripts/ and the Gradle files."
