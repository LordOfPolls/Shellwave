package io.github.lordofpolls.shellwave.core.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import io.github.lordofpolls.shellwave.core.db.entities.ColorSchemeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ColorSchemeDao {
    @Query("SELECT * FROM color_schemes")
    suspend fun getAll(): List<ColorSchemeEntity>

    @Query("SELECT * FROM color_schemes ORDER BY id LIMIT 1")
    suspend fun getDefault(): ColorSchemeEntity?

    @Query("SELECT * FROM color_schemes ORDER BY id LIMIT 1")
    fun observeDefault(): Flow<ColorSchemeEntity?>

    @Query("SELECT * FROM color_schemes WHERE id = :id")
    suspend fun getById(id: Long): ColorSchemeEntity?

    @Insert
    suspend fun insert(scheme: ColorSchemeEntity): Long

    @Update
    suspend fun update(scheme: ColorSchemeEntity)

    @Delete
    suspend fun delete(scheme: ColorSchemeEntity)
}
