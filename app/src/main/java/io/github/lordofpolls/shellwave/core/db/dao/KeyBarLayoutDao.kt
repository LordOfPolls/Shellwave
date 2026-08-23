package io.github.lordofpolls.shellwave.core.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import io.github.lordofpolls.shellwave.core.db.entities.KeyBarLayoutEntity
import kotlinx.coroutines.flow.Flow

/** No "default" row here: every row is one of several named layouts. */
@Dao
interface KeyBarLayoutDao {
    @Query("SELECT * FROM key_bar_layouts")
    suspend fun getAll(): List<KeyBarLayoutEntity>

    @Query("SELECT * FROM key_bar_layouts ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<KeyBarLayoutEntity>>

    @Query("SELECT * FROM key_bar_layouts WHERE id = :id")
    suspend fun getById(id: Long): KeyBarLayoutEntity?

    @Insert
    suspend fun insert(layout: KeyBarLayoutEntity): Long

    @Update
    suspend fun update(layout: KeyBarLayoutEntity)

    @Delete
    suspend fun delete(layout: KeyBarLayoutEntity)
}
