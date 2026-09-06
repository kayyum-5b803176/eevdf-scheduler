#!/usr/bin/env bash
#
# Architecture guard — microkernel edition.
#
# WHAT CHANGED FROM THE OLD VERSION
# ---------------------------------
# The old script's main job was checking "does feature A import feature B's
# internals" against scripts/feature_import_allowlist.txt, because the feature
# packages lived inside one Gradle module and the compiler could not see the
# boundary. They are real modules now: an illegal import is a build failure, so
# that check — and the allowlist that quietly grandfathered one violation in
# since v4.5.0 — are both gone.
#
# What is left is the set of rules the compiler still cannot see.

set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

FAIL=0
fail() { echo "ARCH FAIL: $*" >&2; FAIL=1; }
pass() { echo "  ok: $*"; }

echo "== 1. kernel stays small and dependency-free (rule 1) =="
# The kernel may not import a capability, or anything Android. It is a pure
# Kotlin/JVM module and must stay that way, forever.
if grep -rn "^import com\.eevdf\.capabilities\." kernel/src/main 2>/dev/null; then
  fail "kernel/ imports a capability. The kernel must never depend on a capability."
else
  pass "kernel imports no capability"
fi
if grep -rn "^import android\.\|^import androidx\.\|^import dagger\." kernel/src/main 2>/dev/null; then
  fail "kernel/ imports Android/Dagger. It must stay a pure Kotlin/JVM module."
else
  pass "kernel is Android-free"
fi
# Rule 1 says the kernel must stay small. This is a ratchet, not a limit that
# should ever be raised casually — raising it is the reviewed decision rule 1
# asks for.
KERNEL_FILES=$(find kernel/src/main -name '*.kt' | wc -l | tr -d ' ')
KERNEL_CEILING=9
if [ "$KERNEL_FILES" -gt "$KERNEL_CEILING" ]; then
  fail "kernel has $KERNEL_FILES files, ceiling is $KERNEL_CEILING. Adding to the kernel needs a deliberate, reviewed decision (rule 1)."
else
  pass "kernel file count $KERNEL_FILES <= $KERNEL_CEILING"
fi

echo "== 2. capabilities never import each other's internals (rule 2/3) =="
# A capability may import another capability's ROOT package (its manifest and
# public surface) only where a documented exception exists. What is never
# allowed is reaching into another capability's inner packages.
VIOLATIONS=0
for dir in capabilities/*/; do
  cap=$(basename "$dir")
  # derive this capability's own package segment, e.g. task-list-screen -> tasklistscreen
  own=$(echo "$cap" | tr -d '-')
  while IFS= read -r hit; do
    # allow importing a capability's own package, and the kernel
    echo "$hit" | grep -q "com\.eevdf\.capabilities\.$own\." && continue
    echo "  $cap: $hit"
    VIOLATIONS=$((VIOLATIONS + 1))
  done < <(grep -rhn "^import com\.eevdf\.capabilities\.[a-z]*\.\(logic\|state\|input\)\." "$dir/src" 2>/dev/null)
done
if [ "$VIOLATIONS" -gt 0 ]; then
  fail "$VIOLATIONS import(s) reach into another capability's logic/state/input package. Use a bus topic (rule 3)."
else
  pass "no capability reaches into another's internals"
fi

echo "== 3. topics are referenced by constant, never hardcoded (spec §3) =="
# A capability must say Topics.TIMER_EXPIRED, never the raw "timer.expired"
# string, so the catalogue in kernel/event-bus/topics.kt stays the single
# source of truth for what topics exist.
if grep -rn 'subscribe(\s*"\|publish(\s*"' capabilities/*/src 2>/dev/null; then
  fail "a capability passes a raw string to publish/subscribe. Use a Topics.* constant."
else
  pass "all publish/subscribe calls use Topics constants"
fi

echo "== 4. every capability declares a manifest.kt (rule 4) =="
MISSING=""
for dir in capabilities/*/; do
  cap=$(basename "$dir")
  [ -z "$(find "$dir" -name 'manifest.kt' -print -quit 2>/dev/null)" ] && MISSING="$MISSING $cap"
done
if [ -n "$MISSING" ]; then
  fail "capabilities missing manifest.kt:$MISSING"
else
  pass "every capability has a manifest.kt"
fi

echo "== 5. root arity stays fixed (rule 7) =="
# Only these may exist at the repo root. capabilities/ is the only one allowed
# to grow without bound.
ALLOWED="app build-logic capabilities composition config docs gradle kernel scripts"
for d in */; do
  name="${d%/}"
  case " $ALLOWED " in
    *" $name "*) ;;
    *) fail "unexpected root folder '$name'. Root arity is fixed (rule 7): kernel/, capabilities/, composition/ (plus build/tooling dirs)." ;;
  esac
done
[ "$FAIL" -eq 0 ] && pass "root folder set unchanged"

echo "== 6. composition wires, it never computes (rule 6) =="
# The binding file should contain provider functions and nothing resembling
# business logic. A control-flow keyword in there is the smell.
if grep -nE '^\s+(if|when|for|while)\s*[({]' composition/src/main/kotlin/com/eevdf/composition/capability-bindings.kt 2>/dev/null; then
  fail "capability-bindings.kt contains control flow. Composition wires, it never computes (rule 6)."
else
  pass "capability-bindings.kt has no control flow"
fi

echo "== 7. DB version, migration count and exported schemas agree =="
# Unchanged in intent from the old script; only the paths moved.
SCHEMA_DIR=capabilities/task-storage/schemas/com.eevdf.capabilities.taskstorage.TaskDatabase
DB_FILE=capabilities/task-storage/src/main/kotlin/com/eevdf/capabilities/taskstorage/task-database.kt
if [ -f "$DB_FILE" ]; then
  DB_VERSION=$(grep -oE 'version[[:space:]]*=[[:space:]]*[0-9]+' "$DB_FILE" | head -1 | grep -oE '[0-9]+')
  NEWEST_SCHEMA=$(ls "$SCHEMA_DIR" 2>/dev/null | sed 's/\.json//' | sort -n | tail -1)
  if [ -n "$DB_VERSION" ] && [ -n "$NEWEST_SCHEMA" ]; then
    if [ "$DB_VERSION" != "$NEWEST_SCHEMA" ]; then
      fail "TaskDatabase version=$DB_VERSION but newest exported schema is $NEWEST_SCHEMA.json. Export the schema for the new version."
    else
      pass "DB version $DB_VERSION matches newest exported schema"
    fi
  else
    echo "  skip: could not read DB version or schema dir"
  fi
else
  fail "expected $DB_FILE — did task-storage move?"
fi

echo
if [ "$FAIL" -eq 0 ]; then
  echo "Architecture guard: PASS"
else
  echo "Architecture guard: FAIL"
fi
exit "$FAIL"
