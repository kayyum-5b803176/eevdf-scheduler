# Adding a feature without breaking an existing one

The rule this whole setup exists to enforce:

> **A feature's diff should touch zero files owned by another feature.**

If it can't reach another feature's files, it can't break them. Everything
below is in service of that one line.

---

## Checklist

Copy this into the PR description.

- [ ] Lives in its own package under `feature/` (Phase 2: its own Gradle module)
- [ ] Does **not** import from another `feature/` package — `scripts/check_architecture.sh` enforces this
- [ ] Persisted data is in **its own table**, not new columns on `tasks`
      (`tasks` is frozen — `TaskSchemaFreezeTest` fails the build if it grows)
- [ ] Decided explicitly whether the new table needs to sync (backup is automatic; sync is not)
- [ ] If it added a Room migration: version bumped, migration registered in `addMigrations()`, schema JSON committed
- [ ] Any new `Task` field is classified in `TaskFieldClassification` (the test forces this)
- [ ] Any new `Task` field round-trips through backup (the test forces this)
- [ ] No new `object` holding mutable state — use an injected `@Singleton` with `StateFlow`
- [ ] Gated behind a `FeatureFlag` for its first release
- [ ] Entry point wrapped in `safeFeature("name") { ... }` so a crash can't take down the alarm service
- [ ] Unit tests for its logic; `./gradlew verifyAll` is green
- [ ] Diff touches no file owned by another feature

---

## The migration protocol (read this before touching TaskDatabase)

This is where two people working in parallel break each other, and neither
branch looks wrong on its own.

**The failure:** you and a teammate both branch from version 21. You both write
`MIGRATION_21_22`. Both branches pass CI. Both merge. Git flags a conflict in
the migration list, someone renumbers one to 22→23 — but their **device already
ran the old 22**, so Room sees "already at 22" and never runs the renumbered
migration. Their local DB now silently differs from what every user will get.

**The protocol:**

1. **Claim the version number before you write it.** On a small team, say it in
   chat. It takes ten seconds and removes the whole class of problem.
2. **Never renumber a migration that has been merged.** Renumber only your own
   unmerged one.
3. **After renumbering, wipe your device DB** — uninstall the app or clear its
   data. Otherwise you are testing a schema no user will ever have.
4. **Rebase onto main before merging.** This turns the silent case into a
   visible git conflict.
5. **Run `./gradlew :data:assembleDebug` and commit `data/schemas/`.** CI fails
   if you don't.

`scripts/check_architecture.sh` step 3 catches duplicates, gaps, unregistered
migrations, and `fallbackToDestructiveMigration` (which silently wipes users).

---

## Where does my data go?

**Default: a new table.** Not a new column on `tasks`.

`tasks` has 51 persisted columns and 21 migrations because every feature ever
added became columns on one shared row. Adding one now forces edits to `Task`,
`TaskDatabase`, `TaskDao`, `TaskRepository`, `BackupManager`, `SyncFieldGuard`
and every UI file that maps the entity — six shared files, six chances to break
something unrelated.

`tasks` is **frozen**, and enforced twice: `TaskSchemaFreezeTest` reflects over
the entity, and the architecture guard flags a widening `ALTER TABLE` in a
migration. Use the pattern in [`SIDE_TABLE_TEMPLATE.md`](SIDE_TABLE_TEMPLATE.md).

Two things worth knowing about a side table:

- **Backup covers it for free.** Export copies the raw `.db` file into a zip.
- **Sync does not.** Sync is field-by-field JSON that only knows about `Task`.
  Decide deliberately whether your table should sync, and write it down.

The narrow exception: a field the **scheduler itself** reasons about on every
tick (a `vruntime`-class value). Joining for those on a hot path costs real
frame time. If you think you have one, say so in the PR and justify it.

---

## Cross-feature communication

You need something another feature has. Ranked best to worst:

1. **Move the shared type down.** Pure logic → `:core`. Utility → `:shared`.
   Persisted data → `:data`. This is right more often than people expect.
2. **Define an interface in `:data` and inject it.** Your feature depends on the
   contract, not on their implementation.
3. **Emit an event both sides observe.** A shared `StateFlow` in `:data`.
4. **Import their class directly.** Blocked by the guard. If you truly must, add
   the edge to `scripts/feature_import_allowlist.txt` and explain why in the PR
   — you are adding debt someone will pay down later.

---

## Feature flags

```kotlin
// shared/FeatureFlag.kt
MY_FEATURE(key = "ff_my_feature", defaultEnabled = false, description = "...")
```

```kotlin
@Inject lateinit var flags: FeatureFlags

if (flags.isEnabled(FeatureFlag.MY_FEATURE)) {
    startMyFeature()
}
```

`defaultEnabled = false` for the first release, `true` once it's proven, then
**delete the flag**. Flags that outlive their feature create 2ⁿ configurations
nobody tests.

---

## Crash isolation

Wrap the **outer edge only** — a service entry point, `onReceive`, a render
pass. Never inside business logic; swallowing exceptions mid-transaction hides
bugs instead of containing them.

```kotlin
override fun onReceive(context: Context, intent: Intent) = safeFeature("my-feature") {
    handleIntent(context, intent)
}
```

Contained failures go to `CrashIsolation.report`. Wire that to Crashlytics or
Sentry as a **non-fatal** — a contained crash you never see is a bug you never
fix.
