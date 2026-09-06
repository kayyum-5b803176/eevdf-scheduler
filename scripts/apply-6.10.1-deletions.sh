#!/usr/bin/env bash
#
# Deletions for v6.10.1 — bugfix only. Nothing to delete.
#
# Fixes a build error: capabilities/alarm-ringer/build.gradle.kts and
# capabilities/task-list-screen/build.gradle.kts still declared
# `implementation(project(":contract"))` after v6.10.0 retired that module
# (AlarmRingingQuery moved to kernel/contracts/). Both already depend on
# :kernel, which is all either needs now. No leftover consumer of :contract
# was found anywhere else — swept every capability's build.gradle.kts to confirm.

set -euo pipefail
echo "v6.10.1: no deletions — build-file fix only."
