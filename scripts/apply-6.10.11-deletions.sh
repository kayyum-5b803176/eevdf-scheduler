#!/usr/bin/env bash
#
# Deletions for v6.10.11 — build-fix only. Nothing to delete.
#
# All four bugs fixed here predate this whole migration and were never
# caught, because every stale-reference sweep run throughout this process
# searched .kt and .kts files only — never .pro or .xml. Fixing that gap in
# process, not just these four instances:
#
# 1. app/proguard-rules.pro — three stale -keep rules, referencing package
#    paths from before ANY phase of this migration touched them:
#      -keep class com.eevdf.data.task.Task            -> capabilities.taskstorage.Task
#      -keep class com.eevdf.data.task.InterruptReturnEntry -> capabilities.taskstorage.InterruptReturnEntry
#      -keep class com.eevdf.data.runlog.**             -> capabilities.runhistory.**
#    plus the SchedulerApplication rule from v6.10.10s fix:
#      -keep class com.eevdf.app.SchedulerApplication   -> com.eevdf.app.boot.ApplicationEntry
#    R8 resolves -keep rules against real class files; an unresolvable one
#    is exactly the "Could not find class file for ..." error reported.
#
# 2. capabilities/backup-restore/src/main/AndroidManifest.xml —
#    android:parentActivityName pointed at the pre-migration
#    com.eevdf.feature.settings.SettingsActivity.
#
# 3. capabilities/settings-screens/.../activity_settings.xml (15 tags) and
#    activity_layout_demo.xml (1 tag) — <com.eevdf.feature.ui.NavCardView>
#    and <com.eevdf.feature.ui.ModelDiagramView> custom-view XML tags. This
#    class is the most severe of the four: Android resolves a custom-view
#    XML tag via Class.forName() at INFLATE TIME, so this was a guaranteed
#    InflateException crash the moment SettingsActivity or the layout-demo
#    screen was opened -- not caught by the Kotlin compiler at all, and not
#    caught by any previous fix in this whole migration because no sweep
#    ever checked XML.
#
# Swept the entire tree for .pro and .xml files referencing any retired
# package (com.eevdf.feature./platform./data./contract.) before packaging --
# these four files were the only remaining instances of either.

set -euo pipefail
echo "v6.10.11: no deletions — config and resource-file fixes only."
