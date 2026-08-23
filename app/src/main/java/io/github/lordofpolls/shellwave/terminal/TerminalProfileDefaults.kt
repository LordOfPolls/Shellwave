package io.github.lordofpolls.shellwave.terminal

import com.termux.terminal.TerminalEmulator
import io.github.lordofpolls.shellwave.core.db.entities.TerminalProfileEntity

/**
 * The profile used before the user has ever saved one. Settings persists a row on its first edit;
 * SessionManager and MainActivity fall back to this directly instead of inserting it themselves, so
 * opening a session never races an insert against a concurrent one.
 *
 * Values match [com.termux.terminal.TerminalEmulator]'s own engine defaults, so a user who never
 * opens Settings sees exactly the engine's behaviour.
 */
val DEFAULT_TERMINAL_PROFILE =
    TerminalProfileEntity(
        name = "Default",
        fontFamily = TerminalFontFamily.JETBRAINS_MONO.name,
        customFontUri = null,
        fontSizeSp = DEFAULT_TERMINAL_TEXT_SIZE_SP.value,
        lineHeightMultiplier = 1f,
        cursorStyle = TerminalCursorStyle.BLOCK.name,
        cursorBlink = false,
        scrollbackLines = TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS,
    )

/** Client-side clamp: the engine only enforces this at construction and silently substitutes 2000 outside it. */
val SCROLLBACK_LINES_RANGE =
    TerminalEmulator.TERMINAL_TRANSCRIPT_ROWS_MIN..TerminalEmulator.TERMINAL_TRANSCRIPT_ROWS_MAX

/** Sane app-chosen bounds for pinch-to-resize and the settings font-size control alike - the engine itself has no opinion on font size. */
val FONT_SIZE_SP_RANGE = 8f..40f

/** Sane app-chosen bounds for the line-height control - the engine has no opinion here either, this is purely [TerminalRenderer]'s row-height multiplier. */
val LINE_HEIGHT_MULTIPLIER_RANGE = 0.8f..2.0f

/** Stored in TerminalProfileEntity.cursorStyle as a name rather than a raw int, so the column stays self-describing. */
enum class TerminalCursorStyle(val displayName: String) {
    BLOCK("Block"),
    UNDERLINE("Underline"),
    BAR("Bar"),
    ;

    companion object {
        fun fromStored(name: String?): TerminalCursorStyle =
            entries.firstOrNull { it.name == name } ?: BLOCK
    }
}

/** The [com.termux.terminal.TerminalSessionClient.getTerminalCursorStyle] value for this style. */
fun TerminalCursorStyle.toEngineConstant(): Int =
    when (this) {
        TerminalCursorStyle.BLOCK -> TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK
        TerminalCursorStyle.UNDERLINE -> TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE
        TerminalCursorStyle.BAR -> TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR
    }
