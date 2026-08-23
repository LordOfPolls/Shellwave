package io.github.lordofpolls.shellwave.core.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "port_forwards",
    foreignKeys = [
        ForeignKey(
            entity = HostEntity::class,
            parentColumns = ["id"],
            childColumns = ["hostId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("hostId")],
)
data class PortForwardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hostId: Long,
    val type: String,
    val bindAddress: String?,
    val bindPort: Int,
    val targetHost: String?,
    val targetPort: Int?,
    val autoStart: Boolean,
)
