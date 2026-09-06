#!/usr/bin/env bash
#
# Deletions for v6.10.4 — build-fix only. Nothing to delete.
#
# v6.10.3 retired capabilities/notice-phase but missed two other consumers of
# it: app/build.gradle.kts and composition/build.gradle.kts both still had
# `implementation(project(":capabilities:notice-phase"))`. Swept every file
# in the tree for "notice-phase"/"noticephase" this time to confirm these
# were the only two remaining.

set -euo pipefail
echo "v6.10.4: no deletions — build-file fix only."
