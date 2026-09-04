package io.github.lordofpolls.shellwave.core.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import io.github.lordofpolls.shellwave.core.db.entities.CredentialEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CredentialDao {
    @Query("SELECT * FROM credentials WHERE id = :id")
    suspend fun getById(id: Long): CredentialEntity?

    @Query("SELECT * FROM credentials ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<CredentialEntity>>

    @Insert
    suspend fun insert(credential: CredentialEntity): Long

    /** In place: a re-insert would change the id and break every host pointing at it. */
    @Update
    suspend fun update(credential: CredentialEntity)

    /** Column-targeted, not a read-modify-write [update]: leaves the sealed fields structurally untouched and can't race a concurrent reseal onto them. */
    @Query("UPDATE credentials SET certificate = :certificate WHERE id = :id")
    suspend fun setCertificate(id: Long, certificate: String?)

    @Delete
    suspend fun delete(credential: CredentialEntity)
}
