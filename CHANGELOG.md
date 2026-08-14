# Changelog

## 4.3.0 — Phase 1: guard rails, characterization tests, build fixes

`versionName` 4.2.0 → **4.3.0** (MINOR). `versionCode` unchanged at 1.

Rationale: backwards-compatible functionality was added (feature flags, crash
isolation, sync field classification, test infrastructure) alongside bug fixes.
No existing public API changed, so this is not a MAJOR bump; it is more than
fixes, so it is not a PATCH bump.

32 files: 21 added, 11 modified, 0 deleted.

---

### Fixed — build blockers

- **`app/proguard-rules.pro` did not exist** but was referenced by
  `buildTypes.release`. `assembleRelease` could not complete. Added, with keep
  rules for Room entities, Hilt, Kotlin metadata, coroutines, MPAndroidChart and
  `org.json`. `Task` field names are kept explicitly — obfuscating them would
  break restore of any backup archive written by an older build.
- **Release builds were signed with the DEBUG key.** An app published that way
  can never be updated under a real key. Now reads `keystore.properties` from
  the project root (gitignored); falls back to debug signing with a loud build
  warning until you supply one.
- **`java.util.Properties` unresolved in `app/build.gradle.kts`.** In a `.kts`
  script a bare `java` resolves to Gradle's `JavaPluginExtension`, not the
  package. Fixed with a top-level `import java.util.Properties`.
- Replaced `org.gradle.internal.os.OperatingSystem` (an internal API that breaks
  between Gradle versions) with `System.getProperty("os.name")`.

### Added — regression guards

- **`scripts/check_architecture.sh`** — runs in ~2s, enforces five rules:
  1. No feature package may import another feature. 15 pre-existing edges are
     baselined in `scripts/feature_import_allowlist.txt`; the list may shrink,
     never grow. New edges fail with the exact file and line.
  2. `:core` stays free of Android / Room / Hilt imports.
  3. `@Database(version)`, the `MIGRATION_x_y` objects and the exported schema
     JSON must agree. Catches duplicate versions (two branches both writing
     `MIGRATION_21_22`), gaps, migrations declared but never registered in
     `addMigrations()`, and `fallbackToDestructiveMigration` (silent data loss).
  4. Global mutable state may not grow beyond the current count.
  5. No new file over 800 lines (`MainActivity` and `TaskViewModel`
     grandfathered until Phase 2).
- **`BackupRoundTripCoverageTest`** — reflects over `Task`'s constructor, fills
  all 51 fields with distinct sentinels, round-trips through the real
  export/import path, and names any field that did not survive. No hardcoded
  field list, so a new field cannot be silently dropped from backup.
- **`TaskFieldClassificationTest`** + **`TaskFieldClassification`** — every
  persisted `Task` field must be declared `CONTENT`, `OPERATIONAL` or
  `IDENTITY`. Previously `SyncFieldGuard` carried a private hand-maintained
  list, so a new content field could be silently blanked by a remote peer.
- **`TaskDatabaseMigrationTest`** — walks schema versions 1 → 21, both in one
  step and one version at a time, and asserts a row inserted at v1 still exists
  at v21. Schema validation alone does not catch a migration that recreates a
  table without copying rows.
- **CI** (`.github/workflows/ci.yml`) — architecture guard, unit tests, detekt,
  `assembleDebug`, and a check that Room did not export an uncommitted schema.
  PRs are merged against the target branch first, so a branch that is green
  against a stale `main` cannot pass.
- **detekt** (`config/detekt/detekt.yml`) — `ignoreFailures = true` for now so
  it does not fail on day one against 20k existing lines. Flip to false once the
  baseline is clean.
- **`CODEOWNERS`** — review gate on the shared surfaces: build config,
  `TaskDatabase.kt`, `Task.kt`, backup/sync, `:core`, and the guards themselves.

### Added — characterization tests for `:core`

Lock in current behaviour so the Phase 2 refactors cannot change it silently.

- `TimerEngineCharacterizationTest` — start/tick/pause/clear, sub-second
  remainder carry, expiry firing exactly once, reducer purity.
- `RtWindowCharacterizationTest` — activation windows including the
  midnight-crossing and Saturday→Sunday wrap branches.
- `CpuSharesCharacterizationTest` — proportional and pinned shares, over-pinned
  clamping, hierarchical scoping, pinned-weight convergence, Jain fairness.
- `BudgetCharacterizationTest` — quota leak-back, DL replenishment, and the
  EEVDF edge cases the existing test did not cover.

**Three real bugs found and locked in** (tests assert current behaviour and are
marked `KNOWN BUG` / `KNOWN GAP`; fix the bug, then update the test):

- `TimerEffect.Expired.ranSeconds` is hardcoded `0L`, so anything crediting run
  time from that effect records zero.
- `RtConfig.secondsUntilClose` never checks `isConfigured`, despite its KDoc
  promising 0 when inactive.
- `SchedTask.weight` has no floor: `priority = 0` divides by zero, producing an
  infinite `virtualDeadline`, and `advanceVruntime` then never advances that
  task — it can starve the queue.

### Added — runtime safety

- **`FeatureFlag` / `FeatureFlags` / `SharedPrefsFeatureFlags`** — local kill
  switches so a broken feature can be turned off instead of hotfixed.
- **`safeFeature { }` / `CrashIsolation`** — contains a crash at a feature's
  outer edge and reports it as a non-fatal, so a failure in stats or the bubble
  overlay cannot take down the alarm foreground service. Installed in
  `SchedulerApplication.onCreate`.

### Changed

- **`BubbleEventBus`** is now `StateFlow`-backed. The `var` API is unchanged, so
  all 27 call sites compile untouched; consumers can now `collect` instead of
  polling. Added `clearBubbleTap()` and `reset()`. This is the seam that makes
  the Phase 2 conversion to an injected `@Singleton` mechanical.
- **`TaskDatabase`**: `exportSchema = false` → `true`. Room now writes
  `data/schemas/*.json` at compile time.
- **`gradle.properties`**: heap 2 GB → 4 GB (KSP + Hilt + Room codegen across
  five modules was tight), `nonTransitiveRClass`, incremental KSP. Configuration
  cache left OFF deliberately — AGP 8.5 + KSP + Hilt is not reliably CC-clean.
- **`.gitignore`**: ignores `keystore.properties`; documents that
  `data/schemas/` must NOT be ignored.

### One-time setup after your first successful build

```bash
./gradlew :data:assembleDebug   # Room writes data/schemas/*.json
git add data/schemas            # commit them — migration tests need them
```

Until then the guard reports `PENDING` on that check.

### Not in this release (Phase 2)

Gated on a green build: splitting `MainActivity` (1281 lines) and
`TaskViewModel` (1151); promoting feature packages to Gradle modules; freezing
`tasks` and moving new feature data to side tables; multibound backup/sync
contributors; `BubbleEventBus` → injected singleton; `build-logic` convention
plugins; `explicitApi()`.
