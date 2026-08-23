package io.github.lordofpolls.shellwave.core.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import io.github.lordofpolls.shellwave.core.db.entities.PortForwardEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PortForwardDao {
    @Query("SELECT * FROM port_forwards WHERE hostId = :hostId")
    suspend fun getForHost(hostId: Long): List<PortForwardEntity>

    @Query("SELECT * FROM port_forwards WHERE hostId = :hostId ORDER BY id")
    fun observeForHost(hostId: Long): Flow<List<PortForwardEntity>>

    @Insert
    suspend fun insert(forward: PortForwardEntity): Long

    @Update
    suspend fun update(forward: PortForwardEntity)

    @Delete
    suspend fun delete(forward: PortForwardEntity)
}
