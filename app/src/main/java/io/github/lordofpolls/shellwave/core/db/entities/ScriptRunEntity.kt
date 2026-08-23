package io.github.lordofpolls.shellwave.core.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * [stdout]/[stderr] are capped in memory before this is built, and carry a `[truncated]` marker
 * when the cap was hit. No truncation flag column: the marker lives in the text, so capping needed
 * no migration.
 *
 * Never holds a `SECRET` parameter's raw value. The runner redacts known secrets out of both fields
 * first, in case the remote command echoed one back.
 */
@Entity(
    tableName = "script_runs",
    foreignKeys = [
        ForeignKey(
            entity = ScriptEntity::class,
            parentColumns = ["id"],
            childColumns = ["scriptId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("scriptId")],
)
data class ScriptRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scriptId: Long,
    val startedAt: Long,
    val finishedAt: Long?,
    val exitStatus: Int?,
    val stdout: String?,
    val stderr: String?,
)
