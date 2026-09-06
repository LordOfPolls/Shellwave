package io.github.lordofpolls.shellwave.terminal

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.termux.terminal.KeyHandler
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent

/**
 * Non-empty so backspace is observable at all: a delete on an already-empty field produces no
 * edit at all. Deleting this placeholder is how a bare backspace is detected.
 */
private const val PLACEHOLDER = " "

/**
 * An invisible, always-focused field capturing IME text for the terminal. [onText] gets newly typed
 * characters, not yet control/alt transformed; [onBackspace] fires with the number of code points
 * removed after the common prefix, not counting the placeholder.
 *
 * [accessibilityLabel] is the whole terminal's accessible name in practice. The grid above is a
 * custom-drawn canvas with no semantics of its own, so this field is the only node a screen reader
 * lands on, and without a label its "text" is [PLACEHOLDER], which TalkBack announces as "space".
 *
 * [screenText] is the visible screen content, already rate-limited by the caller so fast output
 * (a build log, `yes`) doesn't turn into a flood of announcements - this composable just publishes
 * whatever it's given.
 *
 * [resetKey] changing drops the buffer, so a tab switch does not diff against text typed into the
 * previous session.
 *
 * The buffer holds the current line until Enter so the IME can revise it, which also means the IME
 * sees that line, including a password typed at a no-echo prompt. Compose cannot request
 * no-suggestions or no-personalised-learning without also disabling the autocorrect this exists
 * for, so that exposure is accepted.
 */
@Composable
fun TerminalInputCapture(
    focusRequester: FocusRequester,
    resetKey: Any?,
    onText: (String) -> Unit,
    onBackspace: (count: Int) -> Unit,
    accessibilityLabel: String,
    screenText: String,
    modifier: Modifier = Modifier,
) {
    val state = remember(resetKey) { TextFieldState(PLACEHOLDER, TextRange(1)) }
    val currentOnText by rememberUpdatedState(onText)
    val currentOnBackspace by rememberUpdatedState(onBackspace)
    val transformation =
        remember {
            InputTransformation {
                val delta = terminalInputDelta(originalText.toString(), asCharSequence().toString())
                if (delta.backspaces > 0) currentOnBackspace(delta.backspaces)
                if (delta.text.isNotEmpty()) currentOnText(delta.text)

                if ('\n' in asCharSequence() || length > 1024) {
                    // Deliberate ceiling. Enter ends the line the IME could still revise, and the cap
                    // bounds an invisible field; past a reset the IME can no longer revert an
                    // autocorrect with backspace.
                    replace(0, length, PLACEHOLDER)
                } else if (length == 0 || asCharSequence()[0] != PLACEHOLDER[0]) {
                    replace(0, 0, PLACEHOLDER)
                }
            }
        }

    // This field's own long-press-to-select menu would otherwise pop up alongside the ActionMode a
    // long-press on the terminal is supposed to open, and Android has no notion of one deferring to
    // the other.
    CompositionLocalProvider(LocalTextToolbar provides NoOpTextToolbar) {
        BasicTextField(
            state = state,
            inputTransformation = transformation,
            // Plain `semantics` over `clearAndSetSemantics`: the field's editable-text role and
            // SetText action are what make the IME reachable at all.
            modifier =
                modifier
                    .focusRequester(focusRequester)
                    .semantics {
                        contentDescription =
                            if (screenText.isEmpty()) accessibilityLabel else "$accessibilityLabel\n$screenText"
                    },
            textStyle = TextStyle(color = Color.Transparent),
            cursorBrush = SolidColor(Color.Transparent),
            keyboardOptions =
                KeyboardOptions(
                    autoCorrectEnabled = false,
                    capitalization = KeyboardCapitalization.None,
                ),
        )
    }
}

internal data class InputDelta(val backspaces: Int, val text: String)

/**
 * Pure diff of the field's state before and after an edit: code points removed after the common
 * prefix (not counting the placeholder), then the text inserted after it.
 */
internal fun terminalInputDelta(original: String, result: String): InputDelta {
    var prefix = original.commonPrefixWith(result).length
    if (prefix > 0 && Character.isHighSurrogate(original[prefix - 1])) prefix--

    var backspaces = original.codePointCount(prefix, original.length)
    if (prefix == 0 && !(original == PLACEHOLDER && result.isEmpty())) backspaces--

    return InputDelta(backspaces = backspaces, text = result.substring(prefix))
}

private object NoOpTextToolbar : TextToolbar {
    override val status: TextToolbarStatus = TextToolbarStatus.Hidden

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
    }

    override fun hide() {}
}

fun ctrlCode(char: Char): Char? {
    val upper = char.uppercaseChar()
    if (upper !in 'A'..'Z') return null
    return (upper - 'A' + 1).toChar()
}

/**
 * Null for any key this layer doesn't handle, which the caller must let fall through to normal IME
 * text input. Plain characters, and modifier-less Space in particular, are handled by
 * [TerminalInputCapture]'s field, so this must not swallow them.
 *
 * [KeyHandler.getCode] covers nav/function/edit/numpad keys with any modifier combination. It does
 * not cover Ctrl+<letter> or Alt+<char> chords: that logic lives only in upstream Termux's
 * un-vendored `TerminalView`, so those are ported by hand below.
 */
fun hardwareKeyCode(event: ComposeKeyEvent, cursorApp: Boolean, keypadApp: Boolean): String? {
    if (event.type != KeyEventType.KeyDown) return null
    val native = event.nativeKeyEvent
    val ctrl = event.isCtrlPressed
    val alt = event.isAltPressed

    var keyMode = 0
    if (ctrl) keyMode = keyMode or KeyHandler.KEYMOD_CTRL
    if (alt) keyMode = keyMode or KeyHandler.KEYMOD_ALT
    if (event.isShiftPressed) keyMode = keyMode or KeyHandler.KEYMOD_SHIFT
    if (native.isNumLockOn) keyMode = keyMode or KeyHandler.KEYMOD_NUM_LOCK

    KeyHandler.getCode(native.keyCode, keyMode, cursorApp, keypadApp)?.let { return it }

    // Not in KeyHandler. Use the base (unshifted, unmodified) character, matching upstream's table.
    if (ctrl) {
        ctrlChordCode(native.getUnicodeChar(0))?.let { return it.toString() }
    }

    // xterm/bash convention: ESC then the character, e.g. Alt+. to recall the last argument.
    if (alt && !ctrl) {
        val codePoint =
            native.getUnicodeChar(native.metaState and android.view.KeyEvent.META_SHIFT_ON)
        if (codePoint > 0) return "\u001B" + String(Character.toChars(codePoint))
    }

    return null
}

/** Ported from upstream `TerminalView.inputCodePoint`. */
private fun ctrlChordCode(codePoint: Int): Char? {
    if (codePoint in 0..0xFFFF) {
        ctrlCode(codePoint.toChar())?.let { return it }
    }
    return when (codePoint) {
        ' '.code, '2'.code -> 0.toChar()
        '['.code, '3'.code -> 27.toChar()
        '\\'.code, '4'.code -> 28.toChar()
        ']'.code, '5'.code -> 29.toChar()
        '^'.code, '6'.code -> 30.toChar()
        '_'.code, '7'.code, '/'.code -> 31.toChar()
        '8'.code -> 127.toChar()
        else -> null
    }
}
