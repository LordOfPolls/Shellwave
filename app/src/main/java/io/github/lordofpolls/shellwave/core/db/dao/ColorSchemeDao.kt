package io.github.lordofpolls.shellwave.core.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import io.github.lordofpolls.shellwave.core.db.entities.ColorSchemeEntity
import io.github.lordofpolls.shellwave.terminal.DEFAULT_COLOR_SCHEME
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

    /**
     * Inserts a new per-host row and returns it.
     *
     * The app-wide default is the lowest-id row and Settings only inserts it on the first edit, so
     * a row inserted into an empty table would become the default for every host. The default is
     * therefore written first when missing. One transaction keeps two quick calls from inserting
     * two defaults.
     */
    @Transaction
    suspend fun insertOverride(): ColorSchemeEntity {
        if (getDefault() == null) insert(DEFAULT_COLOR_SCHEME.copy(id = 0))
        val fresh = DEFAULT_COLOR_SCHEME.copy(id = 0, name = "Custom")
        return fresh.copy(id = insert(fresh))
    }
}
