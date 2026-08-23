package io.github.lordofpolls.shellwave.core.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One JSON column; nothing queries individual keys, so a child table would earn nothing. */
@Entity(tableName = "key_bar_layouts")
data class KeyBarLayoutEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val keysJson: String,
    /**
     * `1` or `2`. A real column and not something derived from [keysJson]'s length, because which shape
     * a layout wants is a user choice: six keys may still want two rows of three, and ten may prefer to
     * scroll.
     *
     * A plain `Int`, validated where it is used, so a hand-edited or future value degrades to a sane
     * bar instead of throwing on read.
     */
    val rows: Int = 1,
)
