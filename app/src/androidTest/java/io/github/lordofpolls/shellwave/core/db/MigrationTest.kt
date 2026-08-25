package io.github.lordofpolls.shellwave.core.db

import android.content.ContentValues
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One test per shipped migration: insert a row under the older schema, run the real
 * [androidx.room.migration.Migration], check the surviving row's new column.
 *
 * [v1SchemaMatchesExportedSchema] proves MigrationTestHelper can build a database from the JSON
 * committed under `app/schemas/` and that the live [ShellwaveDatabase] still matches it.
 * `validateDroppedTables = true` is also what pins `MIGRATION_4_5`'s `ON DELETE RESTRICT` against
 * HostEntity's `@ForeignKey` - a mismatched `onDelete` fails here, as well as a missing column.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper: MigrationTestHelper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ShellwaveDatabase::class.java,
            emptyList(),
            FrameworkSQLiteOpenHelperFactory(),
        )

    @Test
    fun v1SchemaMatchesExportedSchema() {
        // createDatabase validates the live @Database/@Entity definitions against the exported
        // schemas/1.json and throws if they disagree - that is the "harness runs" proof for now.
        helper.createDatabase(TEST_DB, 1).close()
    }

    @Test
    fun migrate1To2PreservesHostsAndAddsResilientSession() {
        // Build the DB at v1 (against the exported 1.json) and insert a host row using only columns
        // that exist in v1: resilientSession does not exist yet at this point.
        helper.createDatabase(TEST_DB, 1).use { db ->
            val values =
                ContentValues().apply {
                    put("label", "test-host")
                    put("hostname", "10.0.2.2")
                    put("port", 2222)
                    put("username", "test")
                    put("credentialId", 1L)
                    putNull("lastConnectedAt")
                    put("createdAt", 1000L)
                }
            db.insert("hosts", android.database.sqlite.SQLiteDatabase.CONFLICT_FAIL, values)
        }

        // Run the real migration and validate against the live (v2) schema.
        val migrated = helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2)

        migrated.query("SELECT hostname, resilientSession FROM hosts").use { cursor ->
            assertEquals(1, cursor.count)
            assertEquals(true, cursor.moveToFirst())
            assertEquals("10.0.2.2", cursor.getString(cursor.getColumnIndexOrThrow("hostname")))
            assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("resilientSession")))
        }
    }

    @Test
    fun migrate2To3PreservesTerminalProfilesAndAddsCustomFontUri() {
        // Build the DB at v2 (against the exported 2.json) and insert a terminal_profiles row using
        // only columns that exist in v2: customFontUri does not exist yet at this point.
        helper.createDatabase(TEST_DB, 2).use { db ->
            val values =
                ContentValues().apply {
                    put("name", "Default")
                    put("fontFamily", "JETBRAINS_MONO")
                    put("fontSizeSp", 14f)
                    put("lineHeightMultiplier", 1f)
                    put("cursorStyle", "BLOCK")
                    put("cursorBlink", 0)
                    put("scrollbackLines", 2000)
                }
            db.insert(
                "terminal_profiles",
                android.database.sqlite.SQLiteDatabase.CONFLICT_FAIL,
                values
            )
        }

        // Run the real migration and validate against the live (v3) schema.
        val migrated = helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3)

        migrated.query("SELECT fontFamily, scrollbackLines, customFontUri FROM terminal_profiles")
            .use { cursor ->
                assertEquals(1, cursor.count)
                assertEquals(true, cursor.moveToFirst())
                assertEquals(
                    "JETBRAINS_MONO",
                    cursor.getString(cursor.getColumnIndexOrThrow("fontFamily"))
                )
                assertEquals(2000, cursor.getInt(cursor.getColumnIndexOrThrow("scrollbackLines")))
                assertEquals(true, cursor.isNull(cursor.getColumnIndexOrThrow("customFontUri")))
            }
    }

    @Test
    fun migrate3To4PreservesHostsAndAddsOverrideColumns() {
        // Build the DB at v3 (against the exported 3.json) and insert a host row using only columns
        // that exist in v3: terminalProfileId/colorSchemeId/keyBarLayoutId do not exist yet at this
        // point.
        helper.createDatabase(TEST_DB, 3).use { db ->
            val values =
                ContentValues().apply {
                    put("label", "prod-db")
                    put("hostname", "10.0.2.2")
                    put("port", 2222)
                    put("username", "test")
                    put("credentialId", 1L)
                    putNull("lastConnectedAt")
                    put("createdAt", 1000L)
                    put("resilientSession", 0)
                }
            db.insert("hosts", android.database.sqlite.SQLiteDatabase.CONFLICT_FAIL, values)
        }

        // Run the real migration and validate against the live (v4) schema.
        val migrated = helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_3_4)

        migrated.query("SELECT hostname, terminalProfileId, colorSchemeId, keyBarLayoutId FROM hosts")
            .use { cursor ->
                assertEquals(1, cursor.count)
                assertEquals(true, cursor.moveToFirst())
                assertEquals("10.0.2.2", cursor.getString(cursor.getColumnIndexOrThrow("hostname")))
                assertEquals(true, cursor.isNull(cursor.getColumnIndexOrThrow("terminalProfileId")))
                assertEquals(true, cursor.isNull(cursor.getColumnIndexOrThrow("colorSchemeId")))
                assertEquals(true, cursor.isNull(cursor.getColumnIndexOrThrow("keyBarLayoutId")))
            }
    }

    @Test
    fun migrate4To5PreservesHostsAndAddsProxyJumpHostId() {
        // Build the DB at v4 (against the exported 4.json) and insert a host row using only columns
        // that exist in v4 - proxyJumpHostId does not exist yet at this point.
        helper.createDatabase(TEST_DB, 4).use { db ->
            val values =
                ContentValues().apply {
                    put("label", "bastion")
                    put("hostname", "10.0.2.2")
                    put("port", 2222)
                    put("username", "test")
                    put("credentialId", 1L)
                    putNull("lastConnectedAt")
                    put("createdAt", 1000L)
                    put("resilientSession", 0)
                }
            db.insert("hosts", android.database.sqlite.SQLiteDatabase.CONFLICT_FAIL, values)
        }

        // Run the real migration and validate against the live (v5) schema.
        val migrated = helper.runMigrationsAndValidate(TEST_DB, 5, true, MIGRATION_4_5)

        migrated.query("SELECT hostname, proxyJumpHostId FROM hosts").use { cursor ->
            assertEquals(1, cursor.count)
            assertEquals(true, cursor.moveToFirst())
            assertEquals("10.0.2.2", cursor.getString(cursor.getColumnIndexOrThrow("hostname")))
            assertEquals(true, cursor.isNull(cursor.getColumnIndexOrThrow("proxyJumpHostId")))
        }
    }

    @Test
    fun migrate5To6PreservesKeyBarLayoutsAndDefaultsRowsToOne() {
        // Build the DB at v5 (against the exported 5.json) and insert a key bar layout using only
        // columns that exist in v5 - `rows` does not exist yet at this point.
        helper.createDatabase(TEST_DB, 5).use { db ->
            val values =
                ContentValues().apply {
                    put("name", "compact")
                    put("keysJson", "[]")
                }
            db.insert(
                "key_bar_layouts",
                android.database.sqlite.SQLiteDatabase.CONFLICT_FAIL,
                values
            )
        }

        // Run the real migration and validate against the live (v6) schema.
        val migrated = helper.runMigrationsAndValidate(TEST_DB, 6, true, MIGRATION_5_6)

        migrated.query("SELECT name, rows FROM key_bar_layouts").use { cursor ->
            assertEquals(1, cursor.count)
            assertEquals(true, cursor.moveToFirst())
            assertEquals("compact", cursor.getString(cursor.getColumnIndexOrThrow("name")))
            // What the NOT NULL DEFAULT 1 buys: an existing layout keeps rendering as the single
            // row it always was.
            assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("rows")))
        }
    }

    @Test
    fun migrate6To7PreservesScriptsAndLeavesAutomationOff() {
        // Build the DB at v6 (against the exported 6.json) and insert a script using only columns
        // that exist in v6 - `allowAutomation` does not exist yet at this point. targetHostId is
        // left NULL so this needs no `hosts` row to satisfy the foreign key.
        helper.createDatabase(TEST_DB, 6).use { db ->
            val values =
                ContentValues().apply {
                    put("name", "nightly backup")
                    putNull("icon")
                    putNull("color")
                    putNull("targetHostId")
                    put("snippet", "borg create ::daily /srv")
                    put("mode", "CAPTURE")
                    put("disconnectAfter", 0)
                    put("paramsJson", "[]")
                    put("confirmBeforeRun", 0)
                    put("createdAt", 1000L)
                }
            db.insert("scripts", android.database.sqlite.SQLiteDatabase.CONFLICT_FAIL, values)
        }

        // Run the real migration and validate against the live (v7) schema.
        val migrated = helper.runMigrationsAndValidate(TEST_DB, 7, true, MIGRATION_6_7)

        migrated.query("SELECT name, allowAutomation FROM scripts").use { cursor ->
            assertEquals(1, cursor.count)
            assertEquals(true, cursor.moveToFirst())
            assertEquals("nightly backup", cursor.getString(cursor.getColumnIndexOrThrow("name")))
            // The security property here, and no formality: a script written before automation
            // existed is not reachable from outside the app just because a token is later pasted
            // into Tasker.
            assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("allowAutomation")))
        }
    }

    @Test
    fun migrate7To8PreservesHostsAndAddsMacAddress() {
        helper.createDatabase(TEST_DB, 7).use { db ->
            val values =
                ContentValues().apply {
                    put("label", "nas")
                    put("hostname", "10.0.2.2")
                    put("port", 2222)
                    put("username", "test")
                    put("credentialId", 1L)
                    putNull("lastConnectedAt")
                    put("createdAt", 1000L)
                    put("resilientSession", 0)
                    putNull("terminalProfileId")
                    putNull("colorSchemeId")
                    putNull("keyBarLayoutId")
                    putNull("proxyJumpHostId")
                }
            db.insert("hosts", android.database.sqlite.SQLiteDatabase.CONFLICT_FAIL, values)
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 8, true, MIGRATION_7_8)

        migrated.query("SELECT hostname, macAddress FROM hosts").use { cursor ->
            assertEquals(1, cursor.count)
            assertEquals(true, cursor.moveToFirst())
            assertEquals("10.0.2.2", cursor.getString(cursor.getColumnIndexOrThrow("hostname")))
            assertEquals(true, cursor.isNull(cursor.getColumnIndexOrThrow("macAddress")))
        }
    }

    companion object {
        private const val TEST_DB = "migration-test"
    }
}
