package io.github.lordofpolls.shellwave.core.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Only one row is used, the first by [id]; [name] and the ability to hold more exist so per-host
 * overrides have something to reference. [customFontUri] is a persisted SAF URI, read only when
 * [fontFamily] is `CUSTOM`. [cursorStyle] stores "BLOCK"/"UNDERLINE"/"BAR" to match the engine's
 * own constants.
 */
@Entity(tableName = "terminal_profiles")
data class TerminalProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val fontFamily: String,
    val customFontUri: String? = null,
    val fontSizeSp: Float,
    val lineHeightMultiplier: Float,
    val cursorStyle: String,
    val cursorBlink: Boolean,
    val scrollbackLines: Int,
)
