package io.github.lordofpolls.shellwave.core.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Every schema bump ships a tested migration; `fallbackToDestructiveMigration` is never used. */
val MIGRATION_1_2 =
    object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE hosts ADD COLUMN resilientSession INTEGER NOT NULL DEFAULT 0")
        }
    }

/** Nullable with no default, matching the entity's `String? = null` - existing rows get `NULL`, which is exactly "no custom font picked". */
val MIGRATION_2_3 =
    object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE terminal_profiles ADD COLUMN customFontUri TEXT")
        }
    }

/** All three nullable with no default: an existing row gets `NULL` in each, which is exactly "use the app-wide default". */
val MIGRATION_3_4 =
    object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE hosts ADD COLUMN terminalProfileId INTEGER")
            db.execSQL("ALTER TABLE hosts ADD COLUMN colorSchemeId INTEGER")
            db.execSQL("ALTER TABLE hosts ADD COLUMN keyBarLayoutId INTEGER")
        }
    }

/**
 * Unlike [MIGRATION_3_4]'s three override columns, this one carries a `REFERENCES` clause. SQLite
 * allows a foreign key on a column added via `ALTER TABLE ADD COLUMN` as long as its default is
 * `NULL`, and this clause must match HostEntity's `@ForeignKey` exactly or
 * [androidx.room.testing.MigrationTestHelper]'s schema validation fails.
 */
val MIGRATION_4_5 =
    object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE hosts ADD COLUMN proxyJumpHostId INTEGER REFERENCES hosts(id) ON DELETE RESTRICT")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_hosts_proxyJumpHostId ON hosts(proxyJumpHostId)")
        }
    }

/**
 * `NOT NULL DEFAULT 1` keeps every existing layout rendering as the single row it always was.
 * SQLite permits `ADD COLUMN ... NOT NULL` only when a non-null default is supplied, which is also
 * why this needs no table rebuild.
 */
val MIGRATION_5_6 =
    object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE key_bar_layouts ADD COLUMN rows INTEGER NOT NULL DEFAULT 1")
        }
    }

/**
 * `DEFAULT 0` is the security-relevant part, not a formality. This runs on databases whose
 * scripts all predate the idea that a script could be reachable from outside the app. Defaulting to
 * 1, or rebuilding the table without a default, would arm every existing script the moment the
 * app-wide automation toggle was flipped on, which is the failure the per-script gate exists to
 * prevent.
 */
val MIGRATION_6_7 =
    object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE scripts ADD COLUMN allowAutomation INTEGER NOT NULL DEFAULT 0")
        }
    }

/** Nullable with no default: an existing host simply has no MAC to wake. */
val MIGRATION_7_8 =
    object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE hosts ADD COLUMN macAddress TEXT")
        }
    }

/** Nullable with no default: an existing credential simply has no certificate. */
val MIGRATION_8_9 =
    object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE credentials ADD COLUMN certificate TEXT")
        }
    }
