package io.github.lordofpolls.shellwave.core.db

import androidx.room.Database
import androidx.room.RoomDatabase
import io.github.lordofpolls.shellwave.core.db.dao.ColorSchemeDao
import io.github.lordofpolls.shellwave.core.db.dao.CredentialDao
import io.github.lordofpolls.shellwave.core.db.dao.HostDao
import io.github.lordofpolls.shellwave.core.db.dao.KeyBarLayoutDao
import io.github.lordofpolls.shellwave.core.db.dao.PortForwardDao
import io.github.lordofpolls.shellwave.core.db.dao.ScriptDao
import io.github.lordofpolls.shellwave.core.db.dao.ScriptRunDao
import io.github.lordofpolls.shellwave.core.db.dao.TerminalProfileDao
import io.github.lordofpolls.shellwave.core.db.entities.ColorSchemeEntity
import io.github.lordofpolls.shellwave.core.db.entities.CredentialEntity
import io.github.lordofpolls.shellwave.core.db.entities.HostEntity
import io.github.lordofpolls.shellwave.core.db.entities.KeyBarLayoutEntity
import io.github.lordofpolls.shellwave.core.db.entities.PortForwardEntity
import io.github.lordofpolls.shellwave.core.db.entities.ScriptEntity
import io.github.lordofpolls.shellwave.core.db.entities.ScriptRunEntity
import io.github.lordofpolls.shellwave.core.db.entities.TerminalProfileEntity

/**
 * `exportSchema = true` from the very first version: the schema JSON this generates (under
 * `app/schemas/`, wired via `room.schemaLocation`) is committed to git and is what
 * [androidx.room.testing.MigrationTestHelper] migrates from in every future version bump.
 * `fallbackToDestructiveMigration` is never used - every bump ships a tested migration instead.
 */
@Database(
    entities = [
        HostEntity::class,
        CredentialEntity::class,
        TerminalProfileEntity::class,
        ColorSchemeEntity::class,
        KeyBarLayoutEntity::class,
        ScriptEntity::class,
        ScriptRunEntity::class,
        PortForwardEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
abstract class ShellwaveDatabase : RoomDatabase() {
    abstract fun hostDao(): HostDao

    abstract fun credentialDao(): CredentialDao

    abstract fun terminalProfileDao(): TerminalProfileDao

    abstract fun colorSchemeDao(): ColorSchemeDao

    abstract fun keyBarLayoutDao(): KeyBarLayoutDao

    abstract fun scriptDao(): ScriptDao

    abstract fun scriptRunDao(): ScriptRunDao

    abstract fun portForwardDao(): PortForwardDao

    companion object {
        const val DATABASE_NAME = "shellwave.db"
    }
}
