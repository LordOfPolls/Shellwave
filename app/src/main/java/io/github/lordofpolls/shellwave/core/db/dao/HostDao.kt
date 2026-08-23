package io.github.lordofpolls.shellwave.core.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import io.github.lordofpolls.shellwave.core.db.entities.HostEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HostDao {
    @Query("SELECT * FROM hosts ORDER BY label COLLATE NOCASE, hostname COLLATE NOCASE")
    fun observeAll(): Flow<List<HostEntity>>

    @Query("SELECT * FROM hosts WHERE lastConnectedAt IS NOT NULL ORDER BY lastConnectedAt DESC LIMIT :limit")
    fun observeRecents(limit: Int = 5): Flow<List<HostEntity>>

    @Query("SELECT * FROM hosts WHERE id = :id")
    suspend fun getById(id: Long): HostEntity?

    @Insert
    suspend fun insert(host: HostEntity): Long

    @Update
    suspend fun update(host: HostEntity)

    @Delete
    suspend fun delete(host: HostEntity)

    /**
     * `proxyJumpHostId` is `RESTRICT`, so deleting a host any of these depend on fails at the database
     * layer. The delete UI calls this first to turn that into a specific message.
     */
    @Query("SELECT * FROM hosts WHERE proxyJumpHostId = :hostId ORDER BY label COLLATE NOCASE, hostname COLLATE NOCASE")
    suspend fun getProxyJumpDependents(hostId: Long): List<HostEntity>

    /**
     * A host's credential is normally its own private row, but `~/.ssh/config` import can attach an
     * existing one to a new host. Deleting a host must only delete its credential when this returns 0,
     * or it pulls the key out from under whichever other host still points at it.
     */
    @Query("SELECT COUNT(*) FROM hosts WHERE credentialId = :credentialId AND id != :excludeHostId")
    suspend fun countOtherHostsUsingCredential(credentialId: Long, excludeHostId: Long): Int

    @Query("UPDATE hosts SET lastConnectedAt = :timestamp WHERE id = :id")
    suspend fun touchLastConnected(id: Long, timestamp: Long)
}
