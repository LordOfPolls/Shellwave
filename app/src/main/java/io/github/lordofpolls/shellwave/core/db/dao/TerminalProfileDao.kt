package io.github.lordofpolls.shellwave.core.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import io.github.lordofpolls.shellwave.core.db.entities.TerminalProfileEntity
import io.github.lordofpolls.shellwave.terminal.DEFAULT_TERMINAL_PROFILE
import kotlinx.coroutines.flow.Flow

@Dao
interface TerminalProfileDao {
    @Query("SELECT * FROM terminal_profiles")
    suspend fun getAll(): List<TerminalProfileEntity>

    @Query("SELECT * FROM terminal_profiles ORDER BY id LIMIT 1")
    suspend fun getDefault(): TerminalProfileEntity?

    @Query("SELECT * FROM terminal_profiles ORDER BY id LIMIT 1")
    fun observeDefault(): Flow<TerminalProfileEntity?>

    @Query("SELECT * FROM terminal_profiles WHERE id = :id")
    suspend fun getById(id: Long): TerminalProfileEntity?

    @Insert
    suspend fun insert(profile: TerminalProfileEntity): Long

    @Update
    suspend fun update(profile: TerminalProfileEntity)

    @Delete
    suspend fun delete(profile: TerminalProfileEntity)

    /**
     * Inserts a new per-host row and returns it.
     *
     * The app-wide default is the lowest-id row and Settings only inserts it on the first edit, so
     * a row inserted into an empty table would become the default for every host. The default is
     * therefore written first when missing. One transaction keeps two quick calls from inserting
     * two defaults.
     */
    @Transaction
    suspend fun insertOverride(): TerminalProfileEntity {
        if (getDefault() == null) insert(DEFAULT_TERMINAL_PROFILE.copy(id = 0))
        val fresh = DEFAULT_TERMINAL_PROFILE.copy(id = 0, name = "Host override")
        return fresh.copy(id = insert(fresh))
    }
}
