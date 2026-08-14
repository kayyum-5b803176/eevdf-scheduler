# Side-table template

Copy-paste starting point for putting a feature's data in its own table instead
of widening `tasks`. Replace `Focus` / `focus` throughout.

**Why:** a new column on `tasks` forces edits to six shared files. A new table
forces edits to zero. That difference is the entire scalability argument.

---

## 1. Entity — `data/.../focus/FocusConfig.kt`

```kotlin
package com.eevdf.data.focus

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.eevdf.data.task.Task

@Entity(
    tableName = "focus_config",
    foreignKeys = [
        ForeignKey(
            entity = Task::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            // Deleting a task must not orphan its config. Room enforces this
            // only if foreign keys are ON — see step 6.
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index(value = ["taskId"], unique = true)],
)
data class FocusConfig(
    @PrimaryKey val taskId: String,
    val blockNotifications: Boolean = false,
    val allowedContacts: String = "",
    val strictMode: Boolean = false,
)
```

One row per task, `taskId` as the primary key. `CASCADE` means task deletion
cleans up after itself with no code in the delete path.

## 2. DAO — `data/.../focus/FocusConfigDao.kt`

```kotlin
@Dao
interface FocusConfigDao {

    @Query("SELECT * FROM focus_config WHERE taskId = :taskId")
    suspend fun get(taskId: String): FocusConfig?

    @Query("SELECT * FROM focus_config WHERE taskId = :taskId")
    fun observe(taskId: String): Flow<FocusConfig?>

    @Query("SELECT * FROM focus_config")
    suspend fun getAll(): List<FocusConfig>

    @Upsert
    suspend fun upsert(config: FocusConfig)

    @Query("DELETE FROM focus_config WHERE taskId = :taskId")
    suspend fun delete(taskId: String)
}
```

## 3. Register it — `TaskDatabase.kt`

**Only shared file you touch.** Three lines:

```kotlin
@Database(
    entities = [
        Task::class, RunLogEntry::class, RunDailySummary::class,
        RunMonthlySummary::class, InterruptReturnEntry::class,
        FocusConfig::class,                                  // +
    ],
    version = 22,                                            // was 21
    exportSchema = true
)
abstract class TaskDatabase : RoomDatabase() {
    abstract fun focusConfigDao(): FocusConfigDao            // +
```

Claim version 22 with your team **before** you write it. See the migration
protocol in `ADDING_A_FEATURE.md`.

## 4. Migration

```kotlin
/** version 21 -> 22: focus mode config moves into its own table */
private val MIGRATION_21_22 = object : Migration(21, 22) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS focus_config (" +
                "taskId TEXT NOT NULL PRIMARY KEY, " +
                "blockNotifications INTEGER NOT NULL DEFAULT 0, " +
                "allowedContacts TEXT NOT NULL DEFAULT '', " +
                "strictMode INTEGER NOT NULL DEFAULT 0, " +
                "FOREIGN KEY(taskId) REFERENCES tasks(id) ON DELETE CASCADE)"
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_focus_config_taskId ON focus_config(taskId)")
    }
}
```

Register it, or it silently never runs — the guard script checks this:

```kotlin
.addMigrations(..., MIGRATION_20_21, MIGRATION_21_22)
```

The SQL must produce **exactly** what the entity declares, column types and
index names included, or `runMigrationsAndValidate` fails. That failure is the
test doing its job — read the diff it prints.

## 5. Repository

```kotlin
@Singleton
class FocusConfigRepository @Inject constructor(private val dao: FocusConfigDao) {
    fun observe(taskId: String): Flow<FocusConfig> =
        dao.observe(taskId).map { it ?: FocusConfig(taskId) }   // absent row == defaults

    suspend fun save(config: FocusConfig) = dao.upsert(config)
}
```

Returning defaults for a missing row means callers never branch on null, and
the feature works for every task that predates it.

## 6. Enable foreign keys

`CASCADE` is inert unless SQLite enforcement is on. Add once, in
`DatabaseModule`:

```kotlin
Room.databaseBuilder(context, TaskDatabase::class.java, DB_NAME)
    .addMigrations(...)
    .addCallback(object : RoomDatabase.Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) {
            db.execSQL("PRAGMA foreign_keys=ON")
        }
    })
    .build()
```

## 7. Backup

Until Phase 2 lands multibound contributors, add an explicit export/import for
the new table alongside the existing task JSON, and a round-trip test modelled
on `BackupRoundTripCoverageTest`. **A feature whose data isn't in the backup is
a feature that silently loses user data on restore.**

## 8. Sync

Nothing to do — `TaskFieldClassification` covers `Task` fields only. When you
want the new table synced, extend `SyncFieldGuard` and add the same style of
coverage test.

---

## Tests to write

```kotlin
@Test fun `migration 21 to 22 creates focus_config`()      // in TaskDatabaseMigrationTest
@Test fun `missing row yields defaults`()
@Test fun `deleting a task cascades its config away`()
@Test fun `focus config survives backup round trip`()
```

## What you did *not* touch

`Task.kt` · `TaskDao.kt` · `TaskRepository.kt` · `BackupManager.taskToJson` ·
`SyncFieldGuard` · every UI file that maps the entity.

That's the point.
