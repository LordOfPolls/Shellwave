package io.github.lordofpolls.shellwave.terminal

import android.view.KeyEvent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.KeyboardHide
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.lordofpolls.shellwave.ui.design.MachineText
import java.util.Locale

/**
 * Ctrl, Alt, then [keys]. The modifiers latch on tap and are always present, not part of [keys],
 * which is what the editor edits; the next key, from the bar or the IME, consumes and clears them.
 *
 * Labels are uppercased with `Locale.ROOT` - a key names something the far end receives, and
 * mixed-case "Esc" beside a mono grid was the last chrome speaking in the app's own voice.
 * `Locale.ROOT` also keeps a Turkish device from turning "list" into "LIST" with a dotted I. Arrow
 * glyphs are left alone.
 *
 * [rows] gives two rows where one would mean a horizontal scroll hiding the rightmost keys behind
 * an unadvertised gesture. It halves the button list in order, modifiers included.
 *
 * A [KeyBarKeyType.CURSOR_CLUSTER] is a pinned column, never a member of that flow. It is the one
 * entry two rows tall, so chunking it alongside flat keys went wrong both ways: at `rows = 2` it
 * landed inside one of the rows, stacking a 48dp row and a gap on top of its own 100dp; at
 * `rows = 1` it stretched the single row to that 100dp and left every flat key floating above 52dp
 * of nothing. Lifting it out fixes both, and the height is then spent rather than wasted - with a
 * cluster present the flowing keys take two rows regardless of [rows], the second one being paid
 * for already. [MIN_BUTTONS_FOR_FREE_SECOND_ROW] is why that is not unconditional.
 *
 * The keyboard key sits outside the scroll on purpose. Every other button may be scrolled off the
 * right edge, survivable for `HOME` and fatal for this one: the soft keyboard is what the user
 * reaches for when the IME is hidden, and a control that is both the only way back and reachable
 * only by an unadvertised swipe is a dead end. It is also the only icon here, which is the
 * distinction - it sends nothing to the far end, so drawing it as one more [MachineText] key would
 * say the opposite.
 *
 * A bare arrow read aloud says nothing about what it does, and latch state is carried by colour
 * alone: hence [keyBarKeyDescription] building descriptions from [KeyBarKey] and not the rendered
 * label, [LatchButton]'s `stateDescription`, and the explicit
 * `Modifier.minimumInteractiveComponentSize` that M3's `Button` family omits. Uppercasing changes
 * what a key looks like, never what a screen reader is told. [KeyboardKey] has no label to fall
 * back on, so its description is the only thing a reader gets, and it swaps with the state.
 */
@Composable
fun KeyBar(
    ctrlLatched: Boolean,
    altLatched: Boolean,
    onCtrlToggle: () -> Unit,
    onAltToggle: () -> Unit,
    onSpecialKey: (keyCode: Int) -> Unit,
    onMacro: (text: String) -> Unit,
    keyboardVisible: Boolean = false,
    onKeyboardToggle: () -> Unit = {},
    keys: List<KeyBarKey> = DEFAULT_KEY_BAR_KEYS,
    rows: Int = 1,
    modifier: Modifier = Modifier,
) {
    // A cluster is drawn as its own pinned column below, so it never enters the chunked flow.
    val hasCluster = keys.any { it.type == KeyBarKeyType.CURSOR_CLUSTER }

    // The flowing part of the bar as one ordered list, so the row split below is a plain chunking
    // of it rather than a second notion of what's on the bar.
    val buttons: List<@Composable () -> Unit> =
        buildList {
            add { LatchButton(label = "Ctrl", latched = ctrlLatched, onClick = onCtrlToggle) }
            add { LatchButton(label = "Alt", latched = altLatched, onClick = onAltToggle) }
            keys.filter { it.type != KeyBarKeyType.CURSOR_CLUSTER }.forEach { key ->
                add {
                    FilledTonalButton(
                        onClick = {
                            when (key.type) {
                                KeyBarKeyType.SPECIAL -> onSpecialKey(key.keyCode)
                                KeyBarKeyType.MACRO -> onMacro(key.macroText)
                                KeyBarKeyType.CURSOR_CLUSTER -> Unit // filtered out above
                            }
                        },
                        modifier =
                            Modifier
                                .minimumInteractiveComponentSize()
                                .widthIn(min = MinKeyWidth)
                                .semantics { contentDescription = keyBarKeyDescription(key) },
                        contentPadding = KeyContentPadding,
                    ) {
                        MachineText(
                            key.label.uppercase(Locale.ROOT),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }

    // A cluster has already made the strip two rows tall, so a second row of flat keys is free.
    val freeSecondRow = hasCluster && buttons.size >= MIN_BUTTONS_FOR_FREE_SECOND_ROW
    val rowCount = maxOf(rows.coerceIn(1, MAX_KEY_BAR_ROWS), if (freeSecondRow) 2 else 1)
    // Ceiling division puts the longer row first: the bar grows away from the terminal.
    val perRow = ((buttons.size + rowCount - 1) / rowCount).coerceAtLeast(1)

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(KeyGap),
        // Bottom, since the pinned columns are the tall ones: fewer flowing rows should settle
        // against the cluster's lower arm, where the thumb rests, instead of floating above a gap.
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(
            // Weighted, so the pinned columns measure first and the scrolling rows take what is
            // left. The other way round, a long layout pushes the cluster and the keyboard key
            // off-screen, which is the thing being prevented.
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(KeyGap),
        ) {
            buttons.chunked(perRow).forEach { rowButtons ->
                // Two rows can still be wider than the window; scroll rather than crowd.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(KeyGap),
                ) {
                    rowButtons.forEach { it() }
                }
            }
        }
        if (hasCluster) CursorCluster(onSpecialKey = onSpecialKey)
        KeyboardKey(visible = keyboardVisible, onClick = onKeyboardToggle)
    }
}

/**
 * Below this many flowing buttons, a cluster's spare row is left unused - see [KeyBar]'s doc. Four
 * is the smallest count that splits into two rows of more than one, and Ctrl/Alt already account
 * for two of it, so in practice this only holds back a layout stripped down to one or two keys.
 */
private const val MIN_BUTTONS_FOR_FREE_SECOND_ROW = 4

/**
 * Shows the soft keyboard, or hides it when it is already up.
 *
 * This exists because focus is not visibility. The terminal's IME shim ([TerminalInputCapture]) is
 * focused for the whole life of a session, so once the keyboard has been dismissed - by the back
 * gesture, or by the system - tapping the terminal calls `requestFocus()` on a field that is
 * *already* focused, which is a no-op and brings nothing back. The bar is the only chrome that
 * survives an IME dismissal, so this is where the way back belongs.
 *
 * Icon-only, and the only icon on the bar: it is app chrome rather than a key, and nothing it does
 * reaches the far end, so dressing it as one more `CTRL`-style label would be a claim about the
 * wrong thing. The glyph swaps with [visible] because the button's meaning does - and the
 * `contentDescription` swaps with it, since a screen reader gets no glyph to read.
 */
@Composable
private fun KeyboardKey(visible: Boolean, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        // Square at the cluster's size so the pinned right-hand edge of the bar reads as one block
        // of controls rather than as a key of an odd width that wandered out of the flow.
        modifier =
            Modifier
                .size(ClusterKeySize)
                .semantics {
                    contentDescription = if (visible) "Hide the keyboard" else "Show the keyboard"
                },
        contentPadding = PaddingValues(0.dp),
    ) {
        Icon(
            imageVector = if (visible) Icons.Outlined.KeyboardHide else Icons.Outlined.Keyboard,
            // The button carries the description; a second one here would have TalkBack read the
            // control twice on the way past.
            contentDescription = null,
            modifier = Modifier.size(KeyIconSize),
        )
    }
}

const val MAX_KEY_BAR_ROWS = 2

/**
 * The arrow keys as the inverted T every physical keyboard uses:
 * ```
 *      ↑
 *  ←   ↓   →
 * ```
 * Two rows tall, about 44dp of terminal height, and that was the accepted trade. A linear `↑↓←→`
 * run has no spatial meaning and every press needs reading.
 *
 * Fixed [ClusterKeySize] rather than [MinKeyWidth], since `↑` has to sit above `↓` and equal-width
 * cells are what guarantee it.
 */
@Composable
private fun CursorCluster(onSpecialKey: (keyCode: Int) -> Unit) {
    val clusterWidth = ClusterKeySize * 3 + KeyGap * 2

    Column(verticalArrangement = Arrangement.spacedBy(KeyGap)) {
        Row(
            modifier = Modifier.width(clusterWidth),
            horizontalArrangement = Arrangement.Center,
        ) {
            ClusterKey(KeyEvent.KEYCODE_DPAD_UP, "↑", onSpecialKey)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
            ClusterKey(KeyEvent.KEYCODE_DPAD_LEFT, "←", onSpecialKey)
            ClusterKey(KeyEvent.KEYCODE_DPAD_DOWN, "↓", onSpecialKey)
            ClusterKey(KeyEvent.KEYCODE_DPAD_RIGHT, "→", onSpecialKey)
        }
    }
}

@Composable
private fun ClusterKey(keyCode: Int, glyph: String, onSpecialKey: (keyCode: Int) -> Unit) {
    FilledTonalButton(
        onClick = { onSpecialKey(keyCode) },
        modifier =
            Modifier
                .size(ClusterKeySize)
                .semantics {
                    contentDescription =
                        keyBarKeyDescription(
                            KeyBarKey(
                                glyph,
                                KeyBarKeyType.SPECIAL,
                                keyCode = keyCode
                            )
                        )
                },
        contentPadding = KeyContentPadding,
    ) {
        MachineText(glyph, style = MaterialTheme.typography.labelMedium)
    }
}

/** Square, because a 48x40 cell makes the T's vertical arm look shorter than its horizontal one. */
private val ClusterKeySize = 48.dp

/**
 * Under the 24dp Material default: at 24dp the icon fills a 48dp cell edge to edge and reads as
 * cramped beside the mono glyphs, which have [KeyContentPadding] around them.
 */
private val KeyIconSize = 20.dp

private val KeyGap = 4.dp

/**
 * `ButtonDefaults.ContentPadding` is sized for word-length labels. A key bar's are three or four
 * characters, and the default makes each key nearly twice as wide as its glyph needs, which is how
 * a six-key bar ends up scrolling on a phone. Vertical padding stays zero so
 * `ButtonDefaults.MinHeight` sets the height.
 */
private val KeyContentPadding =
    androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp)

/**
 * Tightening the padding makes a long bar fit, but a single-glyph key then falls under the 48dp
 * minimum. `minimumInteractiveComponentSize` does not save it: that reserves a touch target around
 * the component, while a pointer has to hit the button's own bounds. A floor and not a fixed width,
 * so `CTRL` and macro keys still size to their text.
 */
private val MinKeyWidth = 48.dp

@Composable
private fun LatchButton(label: String, latched: Boolean, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        colors =
            if (latched) {
                ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                ButtonDefaults.filledTonalButtonColors()
            },
        // Latched state is a container-colour swap, invisible to TalkBack. stateDescription is
        // announced after the label ("Ctrl, active") with no role change away from Button.
        modifier =
            Modifier
                .minimumInteractiveComponentSize()
                .widthIn(min = MinKeyWidth)
                .semantics {
                    contentDescription = "$label modifier key"
                    stateDescription = if (latched) "active" else "inactive"
                },
        contentPadding = KeyContentPadding,
    ) {
        MachineText(label.uppercase(Locale.ROOT), style = MaterialTheme.typography.labelMedium)
    }
}
