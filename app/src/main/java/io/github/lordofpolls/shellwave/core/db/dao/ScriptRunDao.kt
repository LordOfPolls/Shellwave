package io.github.lordofpolls.shellwave.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import io.github.lordofpolls.shellwave.core.db.entities.ScriptRunEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScriptRunDao {
    @Query("SELECT * FROM script_runs WHERE scriptId = :scriptId ORDER BY startedAt DESC")
    fun observeForScript(scriptId: Long): Flow<List<ScriptRunEntity>>

    @Insert
    suspend fun insert(run: ScriptRunEntity): Long
}
