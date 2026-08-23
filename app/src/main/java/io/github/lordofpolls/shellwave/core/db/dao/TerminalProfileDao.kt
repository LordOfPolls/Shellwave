package io.github.lordofpolls.shellwave.core.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import io.github.lordofpolls.shellwave.core.db.entities.TerminalProfileEntity
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
}
