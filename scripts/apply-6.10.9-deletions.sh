#!/usr/bin/env bash
#
# Deletions for v6.10.9 — build-fix only. Nothing to delete.
#
# Two real bugs from my own v6.10.8 fix, not new discoveries:
#
# 1. notice-state-machine.kt: v6.10.7 only fixed a KDoc comment referencing
#    AlarmController. It missed that the file's actual CODE -- 6 vm.alarms.*
#    calls and the SoundManager import -- had reverted to its pre-Phase-9,
#    pre-bus-ification form (v6.10.3 rebuilt this file from a stale base when
#    merging notice-phase into task-list-screen, and that revert was never
#    caught until now). All 6 calls are now real bus.publish() calls again:
#    2x ALARM_DELAY_START_REQUESTED, 2x ALARM_TIMER_PAUSE_REQUESTED,
#    1x ALARM_CANCEL_SCHEDULED_REQUESTED, 1x ALARM_TIMER_EXPIRE_REQUESTED.
#    SoundManager now correctly imports from capabilities/feedback-cues.
#
# 2. output/task-adapter.kt and output/notice-segments.kt: v6.10.8 deleted
#    their `import ... noticephase.NoticePhase` line on the assumption that
#    NoticePhase (merged into task-list-screen in v6.10.3) was now in the
#    SAME package as these two files. It is not -- NoticePhase lives in
#    com.eevdf.capabilities.tasklistscreen, while these two files are in the
#    CHILD package com.eevdf.capabilities.tasklistscreen.output. Kotlin does
#    not auto-resolve across a parent/child package boundary; only an exact
#    package match needs no import. The import is restored, repointed at
#    NoticePhase's actual new location.
#
# Swept every other output/*.kt file in task-list-screen, and every other
# capability, for the same two mistakes before packaging -- no other
# instances found.

set -euo pipefail
echo "v6.10.9: no deletions — source-file fixes only."
