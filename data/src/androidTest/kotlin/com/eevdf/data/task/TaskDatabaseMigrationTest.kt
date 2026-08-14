package com.eevdf.data.task

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Walks every schema version from 1 to the current one.
 *
 * You have 21 migrations and, before this file, zero proof that any of them
 * run. A broken migration is the single worst failure mode this app has: it
 * crashes on upgrade, for users who already have data, and no reinstall short
 * of losing that data fixes it.
 *
 * PREREQUISITE - run once, then commit the result:
 *
 *     ./gradlew :data:assembleDebug
 *     git add data/schemas
 *
 * Room writes data/schemas/<version>.json during compilation. Those files are
 * the contract this test verifies against, and they must be in version control
 * or the test has nothing to compare to.
 *
 * Run with:  ./gradlew :data:connectedDebugAndroidTest   (needs a device/emulator)
 */
@RunWith(AndroidJUnit4::class)
class TaskDatabaseMigrationTest {

    private companion object {
        const val TEST_DB = "migration-test.db"
        const val LATEST = 21          // keep in sync with @Database(version = ...)
    }

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TaskDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    /**
     * Creates the DB at v1 and migrates it all the way forward in one shot.
     * Room validates the resulting schema against <LATEST>.json and throws if a
     * migration produced anything different from what the entities declare.
     */
    @Test
    fun migrateAll_fromVersion1_toLatest() {
        helper.createDatabase(TEST_DB, 1).close()
        helper.runMigrationsAndValidate(TEST_DB, LATEST, true).close()
    }

    /**
     * Migrates one version at a time. When the all-at-once test above fails,
     * this one tells you WHICH step is broken instead of just that something is.
     */
    @Test
    fun migrateStepByStep_everyVersion() {
        helper.createDatabase(TEST_DB, 1).close()
        for (target in 2..LATEST) {
            try {
                helper.runMigrationsAndValidate(TEST_DB, target, true).close()
            } catch (e: Throwable) {
                throw AssertionError("Migration to version $target failed: ${e.message}", e)
            }
        }
    }

    /**
     * A v1 row must still be readable after the full migration chain. Schema
     * validation alone does not prove that existing DATA survives - a migration
     * that recreates the table without copying rows passes validation and
     * silently wipes the user.
     */
    @Test
    fun userDataSurvivesTheFullMigrationChain() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO tasks (id, name, description, priority, timeSliceSeconds, category, color) " +
                    "VALUES ('survivor', 'Old Task', 'from v1', 4, 600, 'General', 0)",
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, LATEST, true).use { db ->
            db.query("SELECT name FROM tasks WHERE id = 'survivor'").use { c ->
                assertTrue("the v1 row was lost somewhere in the migration chain", c.moveToFirst())
                assertTrue("the v1 row's name was corrupted", c.getString(0) == "Old Task")
            }
        }
    }
}
