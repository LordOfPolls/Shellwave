package io.github.lordofpolls.shellwave.core.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import io.github.lordofpolls.shellwave.core.db.entities.GroupEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupDao {
    @Query("SELECT * FROM host_groups ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<GroupEntity>>

    @Insert
    suspend fun insert(group: GroupEntity): Long

    @Update
    suspend fun update(group: GroupEntity)

    @Delete
    suspend fun delete(group: GroupEntity)

    @Query("UPDATE host_groups SET position = :position WHERE id = :id")
    suspend fun setPosition(id: Long, position: Int)

    @Transaction
    suspend fun setPositions(positions: Map<Long, Int>) {
        positions.forEach { (id, position) -> setPosition(id, position) }
    }
}
