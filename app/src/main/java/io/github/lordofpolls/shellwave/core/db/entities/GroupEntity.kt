package io.github.lordofpolls.shellwave.core.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

// "host_groups", not "groups": GROUPS is an SQLite keyword.
@Entity(tableName = "host_groups")
data class GroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val color: Int,
    val collapsed: Boolean = false,
    val position: Int? = null,
) {
    companion object {
        val SWATCHES = listOf(
            0xFFE05252.toInt(),
            0xFFE08A3C.toInt(),
            0xFFD9A520.toInt(),
            0xFF8BAF3C.toInt(),
            0xFF4CAF50.toInt(),
            0xFF26A69A.toInt(),
            0xFF4A90D9.toInt(),
            0xFF6B7FD7.toInt(),
            0xFF9B6BD7.toInt(),
            0xFFD96BA8.toInt(),
        )
    }
}
