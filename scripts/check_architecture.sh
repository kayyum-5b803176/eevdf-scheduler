#!/usr/bin/env bash
#
# Architecture guard for EEVDF Scheduler.
#
# The feature packages under app/src/main/kotlin/com/eevdf/app/feature/ are not
# yet real Gradle modules, so the Kotlin compiler cannot stop one feature from
# reaching into another. This script enforces that boundary in CI in the
# meantime, and also catches the database-versioning mistakes that two people
# working in parallel will otherwise make.
#
# Run locally:  ./gradlew checkArchitecture     (or bash scripts/check_architecture.sh)

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

FAILURES=0
red()  { printf '\033[31m%s\033[0m\n' "$*"; }
grn()  { printf '\033[32m%s\033[0m\n' "$*"; }
ylw()  { printf '\033[33m%s\033[0m\n' "$*"; }
fail() { red "  FAIL: $*"; FAILURES=$((FAILURES+1)); }

FEATURE_ROOT="app/src/main/kotlin/com/eevdf/app/feature"

# ─────────────────────────────────────────────────────────────────────────────
echo "[1/6] Feature isolation — no feature may import another feature"
# ─────────────────────────────────────────────────────────────────────────────
# Some cross-feature imports exist today. List them here so the check passes on
# the current tree while blocking any NEW ones. Delete entries as Phase 2
# removes them; never add to this list to make a build go green.
ALLOWLIST_FILE="scripts/feature_import_allowlist.txt"
touch "$ALLOWLIST_FILE"

if [ -d "$FEATURE_ROOT" ]; then
  VIOLATIONS=""
  for dir in "$FEATURE_ROOT"/*/; do
    [ -d "$dir" ] || continue
    self="$(basename "$dir")"
    while IFS= read -r hit; do
      [ -z "$hit" ] && continue
      file="${hit%%:*}"
      line="${hit#*:}"
      other="$(printf '%s' "$line" | sed -n 's/.*com\.eevdf\.app\.feature\.\([a-zA-Z0-9_]*\).*/\1/p')"
      [ "$other" = "$self" ] && continue
      [ -z "$other" ] && continue
      entry="$self -> $other"
      grep -qxF "$entry" "$ALLOWLIST_FILE" && continue
      VIOLATIONS+="  $entry"$'\n'"    ${file#./}"$'\n'"    ${line# }"$'\n'
    done < <(grep -rn "^import com\.eevdf\.app\.feature\." "$dir" --include=*.kt 2>/dev/null)
  done

  if [ -n "$VIOLATIONS" ]; then
    fail "cross-feature imports found"
    printf '%s' "$VIOLATIONS"
    echo
    ylw "  Fix by moving the shared type down into :core, :shared or :data,"
    ylw "  or by communicating through a :data interface instead."
    ylw "  To grandfather a pre-existing edge, add the exact 'a -> b' line to"
    ylw "  $ALLOWLIST_FILE — but treat that as debt, not a solution."
  else
    grn "  OK — every feature package is self-contained"
  fi
else
  ylw "  SKIP — $FEATURE_ROOT not found"
fi

# ─────────────────────────────────────────────────────────────────────────────
echo "[2/6] Core purity — :core must stay free of Android, Room and Hilt"
# ─────────────────────────────────────────────────────────────────────────────
IMPURE=$(grep -rn "^import \(android\|androidx\|dagger\|javax\.inject\)\." core/src/main --include=*.kt 2>/dev/null || true)
if [ -n "$IMPURE" ]; then
  fail ":core imports platform types — it must remain a pure JVM module"
  printf '%s\n' "$IMPURE" | sed 's/^/    /'
else
  grn "  OK — :core is pure"
fi

# ─────────────────────────────────────────────────────────────────────────────
echo "[3/6] Database versioning — version, migrations and schemas must agree"
# ─────────────────────────────────────────────────────────────────────────────
DB_FILE="data/src/main/kotlin/com/eevdf/data/task/TaskDatabase.kt"
if [ -f "$DB_FILE" ]; then
  DB_VERSION=$(grep -oP 'version\s*=\s*\K[0-9]+' "$DB_FILE" | head -1)
  MIGRATION_COUNT=$(grep -cP 'private val MIGRATION_[0-9]+_[0-9]+' "$DB_FILE" || true)
  EXPECTED=$((DB_VERSION - 1))

  echo "  @Database version = $DB_VERSION, MIGRATION_x_y objects = $MIGRATION_COUNT"

  if [ "$MIGRATION_COUNT" -ne "$EXPECTED" ]; then
    fail "expected $EXPECTED migrations for version $DB_VERSION, found $MIGRATION_COUNT"
  fi

  # Every step 1..N must exist exactly once. Duplicates are the classic
  # two-developers-both-wrote-MIGRATION_21_22 collision.
  for ((v=1; v<DB_VERSION; v++)); do
    n=$((v+1))
    c=$(grep -c "MIGRATION_${v}_${n}\b" "$DB_FILE" || true)
    if [ "$c" -eq 0 ]; then
      fail "missing MIGRATION_${v}_${n}"
    fi
    d=$(grep -c "private val MIGRATION_${v}_${n} " "$DB_FILE" || true)
    if [ "$d" -gt 1 ]; then
      fail "DUPLICATE MIGRATION_${v}_${n} — two branches picked the same version"
    fi
  done

  # Each migration must be passed to addMigrations, or it silently never runs.
  for ((v=1; v<DB_VERSION; v++)); do
    n=$((v+1))
    if grep -q "private val MIGRATION_${v}_${n} " "$DB_FILE"; then
      if ! grep -q "addMigrations(.*MIGRATION_${v}_${n}[,)]" "$DB_FILE"; then
        fail "MIGRATION_${v}_${n} is declared but not registered in addMigrations()"
      fi
    fi
  done

  if grep -q "fallbackToDestructiveMigration" "$DB_FILE"; then
    fail "fallbackToDestructiveMigration() present — this silently WIPES user data on upgrade"
  fi

  if grep -q "exportSchema = false" "$DB_FILE"; then
    fail "exportSchema = false — migration tests cannot validate without exported schemas"
  fi

  # Schema JSON presence (only enforced once the folder has been populated).
  SCHEMA_JSON_COUNT=$(find data/schemas -name "*.json" 2>/dev/null | wc -l | tr -d " ")
  if [ "$SCHEMA_JSON_COUNT" -gt 0 ]; then
    SCHEMA_DIR=$(find data/schemas -maxdepth 1 -type d -name '*TaskDatabase*' | head -1)
    [ -z "$SCHEMA_DIR" ] && SCHEMA_DIR="data/schemas"
    if [ ! -f "$SCHEMA_DIR/${DB_VERSION}.json" ]; then
      fail "no exported schema ${DB_VERSION}.json — run ':data:assembleDebug' then commit data/schemas/"
    else
      grn "  OK — schema ${DB_VERSION}.json is committed"
    fi
  else
    ylw "  PENDING — data/schemas is empty."
    ylw "  Run './gradlew :data:assembleDebug' then 'git add data/schemas' to activate this check."
  fi
else
  ylw "  SKIP — $DB_FILE not found"
fi

# ─────────────────────────────────────────────────────────────────────────────
echo "[4/6] The tasks table is frozen"
# ─────────────────────────────────────────────────────────────────────────────
# `tasks` reached 51 columns because every feature became columns on one shared
# row. TaskSchemaFreezeTest enforces this properly (it reflects over the
# entity); this catches the migration SQL earlier and with a clearer message.
if [ -f "$DB_FILE" ]; then
  WIDEN=$(grep -n "ALTER TABLE .*tasks.* ADD COLUMN\|ALTER TABLE \`tasks\`" "$DB_FILE" | grep -v "^\s*//" || true)
  KNOWN_WIDENING=39   # measured at v4.6.0 — migrations 1..21 widened it this many times
  COUNT=$(printf '%s\n' "$WIDEN" | grep -c "ALTER TABLE" || true)
  echo "  ALTER TABLE tasks ADD COLUMN statements: $COUNT (historical baseline $KNOWN_WIDENING)"
  if [ "$COUNT" -gt "$KNOWN_WIDENING" ]; then
    fail "a migration adds a new column to \`tasks\` — the table is frozen"
    ylw "  Put the feature's data in its own table: docs/SIDE_TABLE_TEMPLATE.md"
    ylw "  A new column forces edits to Task, TaskDatabase, TaskDao, TaskRepository,"
    ylw "  BackupManager, SyncFieldGuard and the UI mappers. A new table forces none."
  else
    grn "  OK"
  fi
fi

# ─────────────────────────────────────────────────────────────────────────────
echo "[5/6] No new global mutable singletons"
# ─────────────────────────────────────────────────────────────────────────────
# Counts 'object X { ... @Volatile var / var ... }' style shared state. The
# current count is the ceiling: it may go down, never up.
BASELINE_MUTABLE_OBJECTS=13
CURRENT=$(grep -rn "@Volatile var\|@Volatile private var" app/src/main data/src/main --include=*.kt 2>/dev/null | wc -l | tr -d ' ')
echo "  mutable global fields: $CURRENT (baseline $BASELINE_MUTABLE_OBJECTS)"
if [ "$CURRENT" -gt "$BASELINE_MUTABLE_OBJECTS" ]; then
  fail "global mutable state grew from $BASELINE_MUTABLE_OBJECTS to $CURRENT"
  ylw "  Use an @Singleton class with StateFlow and inject it instead."
  ylw "  If you genuinely reduced it, lower BASELINE_MUTABLE_OBJECTS in this script."
else
  grn "  OK"
fi

# ─────────────────────────────────────────────────────────────────────────────
echo "[6/6] God-file ceiling"
# ─────────────────────────────────────────────────────────────────────────────
# MainActivity (1281) and TaskViewModel (1151) are the known offenders and are
# grandfathered until Phase 2. Nothing else may join them.
FAILURES_BEFORE_STEP5=$FAILURES
GRANDFATHERED="MainActivity.kt TaskViewModel.kt"
LIMIT=800
while IFS= read -r f; do
  lines=$(wc -l < "$f" | tr -d ' ')
  base=$(basename "$f")
  case " $GRANDFATHERED " in *" $base "*) continue;; esac
  if [ "$lines" -gt "$LIMIT" ]; then
    fail "$f is $lines lines (limit $LIMIT) — split it before it becomes a shared edit surface"
  fi
done < <(find app core data platform shared -name '*.kt' -path '*/src/main/*' 2>/dev/null)
[ "$FAILURES" -eq "$FAILURES_BEFORE_STEP5" ] && grn "  OK (2 files grandfathered: MainActivity, TaskViewModel)"

# ─────────────────────────────────────────────────────────────────────────────
echo
if [ "$FAILURES" -gt 0 ]; then
  red "Architecture guard FAILED with $FAILURES problem(s)."
  exit 1
fi
grn "Architecture guard passed."
