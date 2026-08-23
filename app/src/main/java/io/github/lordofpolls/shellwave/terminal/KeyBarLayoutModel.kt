package io.github.lordofpolls.shellwave.terminal

import android.view.KeyEvent
import io.github.lordofpolls.shellwave.core.db.entities.KeyBarLayoutEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * [KeyBarKeyType.SPECIAL] is an Android [KeyEvent] keycode fed to [KeyHandler.getCode];
 * KeyBarKeyType.MACRO is a literal string sent down the same path as typed IME input.
 */
enum class KeyBarKeyType {
    SPECIAL,
    MACRO,

    /**
     * The four arrow keys as one entry, drawn as the conventional inverted T.
     *
     * Composite instead of a gap marker plus a row/column field, because the T only reads if `↑` sits
     * above `↓` and nothing in a flat key list can promise that: [KeyBar]'s `MinKeyWidth` is a floor,
     * so keys size to their own text and a two-row bar's columns line up by luck. On a 411dp phone the
     * shipped layout holds for three columns and then drifts, 163.8dp against 171.0dp at the fourth.
     *
     * [keyCode] and [macroText] go unused. The four keycodes are implicit in what a cursor cluster is,
     * and rebinding them would only permit a cluster that lies about which way it points.
     */
    CURSOR_CLUSTER,
}

data class KeyBarKey(
    val label: String,
    val type: KeyBarKeyType,
    /** Meaningful only for [KeyBarKeyType.SPECIAL]. */
    val keyCode: Int = 0,
    /** Meaningful only for `KeyBarKeyType.MACRO`. */
    val macroText: String = "",
)

/** `org.json`: a handful of small ordered objects doesn't earn a serialization dependency. */
fun encodeKeyBarKeys(keys: List<KeyBarKey>): String {
    val array = JSONArray()
    keys.forEach { key ->
        val obj = JSONObject()
        obj.put("label", key.label)
        obj.put("type", key.type.name)
        obj.put("keyCode", key.keyCode)
        obj.put("macroText", key.macroText)
        array.put(obj)
    }
    return array.toString()
}

/** A corrupt or hand-edited row degrades to [DEFAULT_KEY_BAR_KEYS] instead of killing the screen. */
fun decodeKeyBarKeys(json: String): List<KeyBarKey> {
    if (json.isBlank()) return DEFAULT_KEY_BAR_KEYS
    return runCatching {
        val array = JSONArray(json)
        (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            val type = runCatching { KeyBarKeyType.valueOf(obj.getString("type")) }.getOrDefault(
                KeyBarKeyType.SPECIAL
            )
            KeyBarKey(
                label = obj.getString("label"),
                type = type,
                keyCode = obj.optInt("keyCode", 0),
                macroText = obj.optString("macroText", ""),
            )
        }
    }.getOrDefault(DEFAULT_KEY_BAR_KEYS)
}

/**
 * The bar shown until a host is assigned a named layout, on the same "insert on first edit, fall
 * back in code until then" convention as [DEFAULT_TERMINAL_PROFILE]/[DEFAULT_COLOR_SCHEME].
 *
 * The four arrows are one [KeyBarKeyType.CURSOR_CLUSTER], which makes the strip two rows tall.
 * [KeyBar] lays the flat keys across both rows instead of leaving the second one blank beside the
 * cluster, so `Home` and `End` cost nothing: on a 411.4dp phone the bar spans 100.2dp with them and
 * spanned 100.2dp without.
 *
 * `Home`/`End` because they are the two keys a phone IME never offers and a shell needs constantly.
 * `Ctrl-A`/`Ctrl-E` reach the first and nothing reaches the second, and both are two taps here
 * against one.
 *
 * `PgUp`/`PgDn` were the obvious next pair and are absent on the same measurement: the pinned
 * cluster and keyboard key leave the flowing column 206.9dp, and eight buttons would put four on
 * the wider row at 211.8dp, which is the horizontal scroll this arrangement removed. They stay one
 * tap away in [SPECIAL_KEY_CHOICES].
 *
 * Only the default changes. A saved layout decodes to exactly the keys it stored.
 */
val DEFAULT_KEY_BAR_KEYS: List<KeyBarKey> =
    listOf(
        KeyBarKey("Esc", KeyBarKeyType.SPECIAL, keyCode = KeyEvent.KEYCODE_ESCAPE),
        KeyBarKey("Tab", KeyBarKeyType.SPECIAL, keyCode = KeyEvent.KEYCODE_TAB),
        KeyBarKey("Home", KeyBarKeyType.SPECIAL, keyCode = KeyEvent.KEYCODE_MOVE_HOME),
        KeyBarKey("End", KeyBarKeyType.SPECIAL, keyCode = KeyEvent.KEYCODE_MOVE_END),
        KeyBarKey(CURSOR_CLUSTER_LABEL, KeyBarKeyType.CURSOR_CLUSTER),
    )

/** For the layout list only: the cluster draws four arrow glyphs, never its own name. */
const val CURSOR_CLUSTER_LABEL = "Arrows"

data class SpecialKeyChoice(val label: String, val keyCode: Int)

val SPECIAL_KEY_CHOICES: List<SpecialKeyChoice> =
    listOf(
        SpecialKeyChoice("Esc", KeyEvent.KEYCODE_ESCAPE),
        SpecialKeyChoice("Tab", KeyEvent.KEYCODE_TAB),
        SpecialKeyChoice("↑", KeyEvent.KEYCODE_DPAD_UP),
        SpecialKeyChoice("↓", KeyEvent.KEYCODE_DPAD_DOWN),
        SpecialKeyChoice("←", KeyEvent.KEYCODE_DPAD_LEFT),
        SpecialKeyChoice("→", KeyEvent.KEYCODE_DPAD_RIGHT),
        SpecialKeyChoice("Home", KeyEvent.KEYCODE_MOVE_HOME),
        SpecialKeyChoice("End", KeyEvent.KEYCODE_MOVE_END),
        SpecialKeyChoice("PgUp", KeyEvent.KEYCODE_PAGE_UP),
        SpecialKeyChoice("PgDn", KeyEvent.KEYCODE_PAGE_DOWN),
        SpecialKeyChoice("Ins", KeyEvent.KEYCODE_INSERT),
        SpecialKeyChoice("Del", KeyEvent.KEYCODE_FORWARD_DEL),
        SpecialKeyChoice("Enter", KeyEvent.KEYCODE_ENTER),
        SpecialKeyChoice("F1", KeyEvent.KEYCODE_F1),
        SpecialKeyChoice("F2", KeyEvent.KEYCODE_F2),
        SpecialKeyChoice("F3", KeyEvent.KEYCODE_F3),
        SpecialKeyChoice("F4", KeyEvent.KEYCODE_F4),
    )

/**
 * "Send Escape key" over "Esc"; "Move cursor up" over "↑". Several of `SPECIAL_KEY_CHOICES`'s
 * labels are bare arrows, which say nothing aloud. The `label` fallback is only for an unrecognised
 * [KeyBarLayoutEntity.keysJson] row. A macro key names the literal text it sends, since a short
 * custom label like "Go" doesn't say what actually gets typed.
 */
fun keyBarKeyDescription(key: KeyBarKey): String =
    when (key.type) {
        KeyBarKeyType.SPECIAL -> SPECIAL_KEY_DESCRIPTIONS[key.keyCode] ?: "Send ${key.label} key"
        KeyBarKeyType.MACRO -> "Send text: ${key.macroText}"
        // The editor's wording. A cluster on the bar renders four buttons, each describing itself
        // through the SPECIAL branch above.
        KeyBarKeyType.CURSOR_CLUSTER -> "Arrow keys"
    }

private val SPECIAL_KEY_DESCRIPTIONS: Map<Int, String> =
    mapOf(
        KeyEvent.KEYCODE_ESCAPE to "Send Escape key",
        KeyEvent.KEYCODE_TAB to "Send Tab key",
        KeyEvent.KEYCODE_DPAD_UP to "Move cursor up",
        KeyEvent.KEYCODE_DPAD_DOWN to "Move cursor down",
        KeyEvent.KEYCODE_DPAD_LEFT to "Move cursor left",
        KeyEvent.KEYCODE_DPAD_RIGHT to "Move cursor right",
        KeyEvent.KEYCODE_MOVE_HOME to "Move to start of line",
        KeyEvent.KEYCODE_MOVE_END to "Move to end of line",
        KeyEvent.KEYCODE_PAGE_UP to "Page up",
        KeyEvent.KEYCODE_PAGE_DOWN to "Page down",
        KeyEvent.KEYCODE_INSERT to "Send Insert key",
        KeyEvent.KEYCODE_FORWARD_DEL to "Send Delete key",
        KeyEvent.KEYCODE_ENTER to "Send Enter key",
        KeyEvent.KEYCODE_F1 to "Send F1 key",
        KeyEvent.KEYCODE_F2 to "Send F2 key",
        KeyEvent.KEYCODE_F3 to "Send F3 key",
        KeyEvent.KEYCODE_F4 to "Send F4 key",
    )
