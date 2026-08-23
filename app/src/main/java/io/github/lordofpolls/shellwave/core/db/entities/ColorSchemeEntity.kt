package io.github.lordofpolls.shellwave.core.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Only one row is used: "the current scheme" is the first by [id]. No library of saved schemes.
 * [name] and [id] exist so per-host overrides have something to point at. [isBuiltIn] holds while
 * the values are still an unmodified copy of a built-in; editing any colour flips it.
 *
 * The ANSI colours are one comma-separated string because that is the form
 * [com.termux.terminal.TerminalColorScheme.updateWith] takes. [selection] has no engine equivalent
 * and is drawn by TerminalCanvas alone.
 */
@Entity(tableName = "color_schemes")
data class ColorSchemeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val isBuiltIn: Boolean,
    val background: Int,
    val foreground: Int,
    val cursor: Int,
    val selection: Int,
    val ansiColorsCsv: String,
)
